<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  adminFetch,
  attestRuntimeTrust,
  getRuntimeTrust,
  revokeRuntimeTrust,
  type AdminAuth,
  type RuntimeTrustView
} from './api'
import { formatDateTime } from './dateTime'

const props = defineProps<{ endpointId: string; organizationId: string; auth: AdminAuth }>()
type Approval = { projectId: string; endpointId: string; valid: boolean; manualAllowed: boolean; failoverAllowed: boolean; expiresAt: string | null }
type Project = { id: string; name: string }
const trust = ref<RuntimeTrustView | null>(null), approval = ref<Approval | null>(null), projects = ref<Project[]>([])
const classification = ref<'INTERNAL' | 'EXTERNAL_NO_RETENTION' | 'EXTERNAL_UNVERIFIED'>('INTERNAL')
const evidence = ref(''), trustExpiry = ref(''), vendorName = ref(''), processingLocation = ref(''), retentionPolicy = ref(''), approvalScope = ref('')
const projectId = ref(''), approvalExpiry = ref('')
const manualAllowed = ref(false), failoverAllowed = ref(false), busy = ref(false), message = ref('')
let generation = 0
const isExternalNoRetention = computed(() => classification.value === 'EXTERNAL_NO_RETENTION')
const canSaveTrust = computed(() => {
  if (busy.value || !trust.value || !evidence.value.trim()) return false
  if (!isExternalNoRetention.value) return true
  return Boolean(
    trustExpiry.value
    && vendorName.value.trim()
    && processingLocation.value.trim()
    && retentionPolicy.value.trim()
    && approvalScope.value.trim()
  )
})

function expiry(value: string): string | null { return value ? new Date(value).toISOString() : null }
function errorText(error: unknown) { return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.' }
function toLocalDateTimeInput(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
function syncTrustForm(current: RuntimeTrustView) {
  classification.value = current.classification === 'EXTERNAL_NO_RETENTION'
    ? 'EXTERNAL_NO_RETENTION'
    : current.classification === 'EXTERNAL_UNVERIFIED'
      ? 'EXTERNAL_UNVERIFIED'
      : 'INTERNAL'
  evidence.value = current.evidence ?? ''
  trustExpiry.value = toLocalDateTimeInput(current.expiresAt)
  vendorName.value = current.vendorName ?? ''
  processingLocation.value = current.processingLocation ?? ''
  retentionPolicy.value = current.retentionPolicy ?? ''
  approvalScope.value = current.approvalScope ?? ''
}
async function refreshTrust() {
  const seq = ++generation, id = props.endpointId, org = props.organizationId
  trust.value = null; approval.value = null; projects.value = []; projectId.value = ''; message.value = ''
  try {
    const [current, list] = await Promise.all([
      getRuntimeTrust(id, props.auth),
      adminFetch<Project[]>(`/api/admin/organizations/${org}/projects`, props.auth)
    ])
    if (seq !== generation) return
    trust.value = current; projects.value = list
    syncTrustForm(current)
  } catch (error) { if (seq === generation) message.value = errorText(error) }
}
watch(() => [props.endpointId, props.organizationId], refreshTrust, { immediate: true })
watch(projectId, async id => {
  const endpoint = props.endpointId
  approval.value = null; manualAllowed.value = false; failoverAllowed.value = false; approvalExpiry.value = ''
  if (!id) return
  try {
    const current = await adminFetch<Approval>(`/api/admin/platform/projects/${id}/runtime-approvals/${endpoint}`, props.auth)
    if (id !== projectId.value || endpoint !== props.endpointId) return
    approval.value = current; manualAllowed.value = current.manualAllowed; failoverAllowed.value = current.failoverAllowed
  } catch (error) { if (id === projectId.value && endpoint === props.endpointId) message.value = errorText(error) }
})
async function saveTrust() {
  if (!canSaveTrust.value) return
  busy.value = true; message.value = ''
  try {
    const saved = await attestRuntimeTrust(props.endpointId, {
      classification: classification.value,
      evidence: evidence.value.trim(),
      expiresAt: expiry(trustExpiry.value),
      vendorName: isExternalNoRetention.value ? vendorName.value.trim() : null,
      processingLocation: isExternalNoRetention.value ? processingLocation.value.trim() : null,
      retentionPolicy: isExternalNoRetention.value ? retentionPolicy.value.trim() : null,
      approvalScope: isExternalNoRetention.value ? approvalScope.value.trim() : null
    }, props.auth)
    trust.value = saved
    syncTrustForm(saved)
    approval.value = null; projectId.value = ''
    message.value = '분류를 저장했습니다. 기존 외부 GPU 프로젝트 승인은 다시 확인해야 합니다.'
  } catch (error) { message.value = errorText(error) } finally { busy.value = false }
}
async function revokeTrust() {
  if (!trust.value || trust.value.classification === 'UNCLASSIFIED') return
  if (!window.confirm('이 Runtime의 신뢰 분류를 즉시 철회하고 모든 프로젝트 GPU 승인을 무효화할까요?')) return
  busy.value = true; message.value = ''
  try {
    const revoked = await revokeRuntimeTrust(props.endpointId, props.auth)
    trust.value = revoked
    approval.value = null; projectId.value = ''
    manualAllowed.value = false; failoverAllowed.value = false
    message.value = 'Runtime 신뢰 분류를 철회했습니다. 연결된 모든 프로젝트 GPU 승인이 즉시 차단됩니다.'
  } catch (error) { message.value = errorText(error) } finally { busy.value = false }
}
async function saveApproval() {
  busy.value = true; message.value = ''
  try {
    await adminFetch(`/api/admin/platform/projects/${projectId.value}/runtime-approvals/${props.endpointId}`, props.auth, {
      method: 'PUT', body: JSON.stringify({ manualAllowed: manualAllowed.value, failoverAllowed: trust.value?.classification === 'INTERNAL' ? false : failoverAllowed.value, expiresAt: expiry(approvalExpiry.value) })
    })
    approval.value = await adminFetch<Approval>(`/api/admin/platform/projects/${projectId.value}/runtime-approvals/${props.endpointId}`, props.auth)
    message.value = '프로젝트별 사용 승인을 저장했습니다.'
  } catch (error) { message.value = errorText(error) } finally { busy.value = false }
}
async function revokeApproval() {
  busy.value = true; message.value = ''
  try {
    await adminFetch(`/api/admin/platform/projects/${projectId.value}/runtime-approvals/${props.endpointId}`, props.auth, { method: 'DELETE' })
    approval.value = await adminFetch<Approval>(`/api/admin/platform/projects/${projectId.value}/runtime-approvals/${props.endpointId}`, props.auth)
    manualAllowed.value = false; failoverAllowed.value = false
    message.value = '승인을 철회했습니다. 이후 신규 요청에서 이 Runtime을 제외합니다.'
  } catch (error) { message.value = errorText(error) } finally { busy.value = false }
}
</script>

<template>
  <section class="trust-control">
    <h3>Runtime 보안 분류 · 최종 관리자</h3>
    <p>미분류 Runtime은 게이트웨이 요청에서 제외됩니다. 외부 비보존 GPU는 데이터가 외부로 전송되며, 벤더·리전·비보존 정책·승인 범위 메타데이터와 별도의 프로젝트 승인이 필요합니다.</p>
    <p v-if="trust">
      <strong>현재: {{ trust.classification }}</strong> · {{ trust.valid ? '분류 기록 유효' : '미분류 또는 만료·철회됨' }}
      <span v-if="trust.expiresAt"> · 만료 {{ formatDateTime(trust.expiresAt) }}</span>
      <span v-if="trust.revokedAt"> · 철회 {{ formatDateTime(trust.revokedAt) }}</span>
    </p>
    <p v-if="trust?.classification === 'EXTERNAL_NO_RETENTION' && (trust.vendorName || trust.processingLocation)" class="trust-metadata-summary">
      벤더 {{ trust.vendorName || '-' }} · 처리 위치 {{ trust.processingLocation || '-' }} · 보존 정책 {{ trust.retentionPolicy || '-' }} · 승인 범위 {{ trust.approvalScope || '-' }}
    </p>
    <details class="collapsible-subform trust-disclosure">
      <summary>보안 분류 · 프로젝트 GPU 승인 설정</summary>
      <div class="collapsible-subform-body">
        <div class="trust-fields">
          <label class="field">처리 위치<select v-model="classification"><option value="INTERNAL">사내 GPU</option><option value="EXTERNAL_NO_RETENTION">외부 GPU · 비보존 조건 확인</option><option value="EXTERNAL_UNVERIFIED">외부 GPU · 미확인</option></select></label>
          <label class="field">검토 근거<input v-model.trim="evidence" maxlength="1000" placeholder="계약 또는 검토 기록" /></label>
          <label class="field">승인 만료일{{ isExternalNoRetention ? ' (필수)' : ' (선택)' }}<input v-model="trustExpiry" type="datetime-local" /></label>
          <button type="button" class="secondary-button" :disabled="!canSaveTrust" @click="saveTrust">분류 저장</button>
          <button type="button" class="secondary-button danger-text" :disabled="busy || !trust || !trust.valid || trust.classification === 'UNCLASSIFIED'" @click="revokeTrust">신뢰 분류 철회</button>
        </div>
        <div v-if="isExternalNoRetention" class="trust-fields trust-metadata-fields">
          <label class="field">외부 벤더명 (필수)<input v-model.trim="vendorName" maxlength="120" placeholder="예: CoreWeave / RunPod" /></label>
          <label class="field">데이터 처리 위치 / 리전 (필수)<input v-model.trim="processingLocation" maxlength="120" placeholder="예: KR / ap-northeast-2" /></label>
          <label class="field">데이터 보존 정책 (필수)<input v-model.trim="retentionPolicy" maxlength="240" placeholder="예: Zero-retention · 메모리 처리 후 즉시 파기" /></label>
          <label class="field">승인 범위 (필수)<input v-model.trim="approvalScope" maxlength="240" placeholder="예: 비식별 문서 요약 및 내부 코드 리뷰" /></label>
        </div>
        <template v-if="(trust?.classification === 'EXTERNAL_NO_RETENTION' || trust?.classification === 'INTERNAL') && trust.valid">
          <h4>프로젝트별 GPU 사용 승인</h4>
          <p>사내 GPU도 프로젝트별 사용 승인이 필요합니다. 외부 GPU는 프로젝트의 외부 전송 제한을 우회할 수 없습니다.</p>
          <div class="trust-fields">
            <label class="field">프로젝트<select v-model="projectId"><option value="">프로젝트 선택</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
            <label class="field">승인 만료일<input v-model="approvalExpiry" type="datetime-local" /></label>
            <label class="trust-check"><input v-model="manualAllowed" type="checkbox" /> 직접 사용 허용</label>
            <label v-if="trust.classification === 'EXTERNAL_NO_RETENTION'" class="trust-check"><input v-model="failoverAllowed" type="checkbox" /> 자동 전환 허용</label>
            <button type="button" class="secondary-button" :disabled="busy || !projectId || !approvalExpiry" @click="saveApproval">프로젝트 승인 저장</button>
            <button type="button" class="secondary-button" :disabled="busy || !projectId || !approval?.valid" @click="revokeApproval">승인 철회</button>
          </div>
          <p v-if="approval && projectId">현재 승인: {{ approval.valid ? '유효' : '없음 또는 만료·철회' }}<span v-if="approval.expiresAt"> · 만료 {{ formatDateTime(approval.expiresAt) }}</span></p>
        </template>
      </div>
    </details>
    <p role="status">{{ message }}</p>
  </section>
</template>
<style scoped>
.trust-control{padding:18px 22px;border-bottom:1px solid var(--border);display:grid;gap:8px}.trust-control h3,.trust-control h4{margin:0}.trust-control h4{margin-top:6px}.trust-control p{font-size:12px;line-height:1.55;color:var(--muted);margin:0}.trust-fields{display:flex;align-items:end;gap:12px;flex-wrap:wrap}.trust-metadata-fields{margin-top:4px}.trust-metadata-summary{color:var(--text-soft)!important}.trust-fields .field{min-width:180px;flex:1}.trust-check{display:flex;gap:6px;align-items:center;min-height:40px}.trust-control [role=status]{color:var(--foreground)}
</style>
