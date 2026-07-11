<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth; platformAdmin: boolean }>()
const emit = defineEmits<{ organizationsChanged: []; organizationSelected: [id: string] }>()
type Project = { id: string; organizationId: string; name: string; status: string }
type ApiKey = { id: string; name: string; keyPrefix: string; status: string; expiresAt?: string; lastUsedAt?: string; createdAt: string }
type Service = { id: string; serviceKey: string; displayName: string; enabled: boolean }
type Quota = { projectId: string; requestsPerMinute: number; monthlyTokenLimit: number | null }
type ContentPolicy = { projectId: string; mode: 'METADATA_ONLY' | 'FULL_ENCRYPTED' }
const projects = ref<Project[]>([]); const selectedId = ref(sessionStorage.getItem('aiconnect.setup.projectId') ?? ''); const keys = ref<ApiKey[]>([]); const services = ref<Service[]>([])
const quota = ref<Quota | null>(null); const contentPolicy = ref<ContentPolicy | null>(null)
const busy = ref(false); const message = ref(''); const projectModal = ref(false); const orgModal = ref(false); const keyModal = ref(false); const accessModal = ref(false); const revealModal = ref(false); const policyModal = ref(false)
const quotaForm = ref({ requestsPerMinute: 60, monthlyTokenLimit: null as number | null }); const retentionMode = ref<ContentPolicy['mode']>('METADATA_ONLY')
const projectName = ref(''); const organizationName = ref(''); const keyName = ref(''); const expiresAt = ref(''); const selectedServiceId = ref(''); const issuedSecret = ref('')
const selectedProject = computed(() => projects.value.find(item => item.id === selectedId.value) ?? null)
const apiOrigin = window.location.origin

async function load() {
  if (!props.organizationId) { projects.value = []; keys.value = []; return }
  busy.value = true
  try {
    projects.value = await adminFetch<Project[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth)
    services.value = await adminFetch<Service[]>(`/api/admin/organizations/${props.organizationId}/services`, props.auth)
    if (selectedId.value && !projects.value.some(item => item.id === selectedId.value)) selectedId.value = ''
    if (!selectedId.value && projects.value.length) selectedId.value = projects.value[0].id
    if (selectedId.value) await selectProject(selectedId.value)
  } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트를 불러오지 못했습니다.' }
  finally { busy.value = false }
}
async function selectProject(id: string) {
  selectedId.value = id
  sessionStorage.setItem('aiconnect.setup.projectId', id)
  const [projectKeys, projectQuota, projectPolicy] = await Promise.all([
    adminFetch<ApiKey[]>(`/api/admin/projects/${id}/api-keys`, props.auth),
    adminFetch<Quota>(`/api/admin/projects/${id}/quota`, props.auth),
    adminFetch<ContentPolicy>(`/api/admin/projects/${id}/content-policy`, props.auth)
  ])
  keys.value = projectKeys; quota.value = projectQuota; contentPolicy.value = projectPolicy
  quotaForm.value = { requestsPerMinute: projectQuota.requestsPerMinute, monthlyTokenLimit: projectQuota.monthlyTokenLimit }
  retentionMode.value = projectPolicy.mode
}
async function createOrganization() {
  busy.value = true
  try { const created = await adminFetch<{ id: string }>('/api/admin/organizations', props.auth, { method: 'POST', body: JSON.stringify({ name: organizationName.value }) }); orgModal.value = false; organizationName.value = ''; emit('organizationsChanged'); emit('organizationSelected', created.id); message.value = '조직을 생성했습니다.' }
  catch (error) { message.value = error instanceof Error ? error.message : '조직 생성 실패' } finally { busy.value = false }
}
async function createProject() {
  busy.value = true
  try { const created = await adminFetch<Project>('/api/admin/projects', props.auth, { method: 'POST', body: JSON.stringify({ organizationId: props.organizationId, name: projectName.value }) }); projectModal.value = false; projectName.value = ''; await load(); await selectProject(created.id); message.value = '프로젝트를 생성했습니다.' }
  catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 생성 실패' } finally { busy.value = false }
}
async function issueKey() {
  if (!selectedId.value) return
  busy.value = true
  try { const issued = await adminFetch<{ secret: string }>(`/api/admin/projects/${selectedId.value}/api-keys`, props.auth, { method: 'POST', body: JSON.stringify({ name: keyName.value, expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null }) }); issuedSecret.value = issued.secret; keyModal.value = false; revealModal.value = true; keyName.value = ''; expiresAt.value = ''; await selectProject(selectedId.value) }
  catch (error) { message.value = error instanceof Error ? error.message : 'API 키 발급 실패' } finally { busy.value = false }
}
async function revokeKey(key: ApiKey) { if (!confirm(`${key.name} 키를 폐기할까요?`)) return; await adminFetch(`/api/admin/api-keys/${key.id}`, props.auth, { method: 'DELETE' }); await selectProject(selectedId.value); message.value = 'API 키를 폐기했습니다.' }
async function grantAccess() { if (!selectedId.value || !selectedServiceId.value) return; busy.value = true; try { await adminFetch(`/api/admin/projects/${selectedId.value}/service-access`, props.auth, { method: 'POST', body: JSON.stringify({ serviceId: selectedServiceId.value }) }); accessModal.value = false; message.value = '프로젝트에 서비스 사용 권한을 부여했습니다.' } catch (error) { message.value = error instanceof Error ? error.message : '권한 부여 실패' } finally { busy.value = false } }
async function copySecret() { await navigator.clipboard.writeText(issuedSecret.value); message.value = 'API 키를 클립보드에 복사했습니다.' }
function openPolicy() { if (!selectedId.value) return; quotaForm.value = { requestsPerMinute: quota.value?.requestsPerMinute ?? 60, monthlyTokenLimit: quota.value?.monthlyTokenLimit ?? null }; retentionMode.value = contentPolicy.value?.mode ?? 'METADATA_ONLY'; policyModal.value = true }
async function savePolicy() {
  if (!selectedId.value) return
  busy.value = true
  try {
    const tokenLimit = Number(quotaForm.value.monthlyTokenLimit)
    const [savedQuota, savedPolicy] = await Promise.all([
      adminFetch<Quota>(`/api/admin/projects/${selectedId.value}/quota`, props.auth, { method: 'PUT', body: JSON.stringify({ requestsPerMinute: quotaForm.value.requestsPerMinute, monthlyTokenLimit: tokenLimit > 0 ? tokenLimit : null }) }),
      adminFetch<ContentPolicy>(`/api/admin/projects/${selectedId.value}/content-policy`, props.auth, { method: 'PUT', body: JSON.stringify({ mode: retentionMode.value }) })
    ])
    quota.value = savedQuota; contentPolicy.value = savedPolicy; policyModal.value = false; message.value = '요청 한도와 데이터 보관 정책을 저장했습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 정책 저장 실패' }
  finally { busy.value = false }
}
watch(() => props.organizationId, () => { selectedId.value = ''; load() }); onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">ACCESS PLANE</p><h1>프로젝트와 API 키</h1><p>애플리케이션 단위로 모델 접근 권한과 사용 키를 분리합니다.</p></div><div class="hero-actions"><button v-if="platformAdmin" class="secondary-button" @click="orgModal = true">＋ 조직</button><button class="primary-button" :disabled="!organizationId" @click="projectModal = true">＋ 프로젝트</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="project-switcher surface-card"><div><span class="card-kicker">PROJECT SPACE</span><h2>{{ selectedProject?.name ?? '프로젝트 선택' }}</h2></div><select v-model="selectedId" @change="selectProject(selectedId)"><option value="">프로젝트를 선택하세요</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }} · {{ item.status }}</option></select><div class="hero-actions"><button class="secondary-button" :disabled="!selectedId" @click="openPolicy">한도·보관 정책</button><button class="secondary-button" :disabled="!selectedId" @click="accessModal = true">서비스 권한</button><button class="primary-button" :disabled="!selectedId" @click="keyModal = true">API 키 발급</button></div></div>
    <div v-if="selectedProject" class="project-policy-grid"><article class="surface-card policy-card"><span class="policy-icon">↯</span><div><span class="card-kicker">RATE & TOKEN LIMIT</span><h3>{{ quota?.requestsPerMinute ?? 60 }} RPM</h3><p>{{ quota?.monthlyTokenLimit ? `${quota.monthlyTokenLimit.toLocaleString()} 월 토큰` : '월 토큰 제한 없음' }}</p></div><button class="text-button" @click="openPolicy">변경</button></article><article class="surface-card policy-card"><span class="policy-icon">◇</span><div><span class="card-kicker">CONTENT RETENTION</span><h3>{{ contentPolicy?.mode === 'FULL_ENCRYPTED' ? '암호화 원문 보관' : '메타데이터만' }}</h3><p>{{ contentPolicy?.mode === 'FULL_ENCRYPTED' ? '비스트리밍 요청·응답을 AES-GCM으로 보관' : '프롬프트와 모델 응답 원문을 저장하지 않음' }}</p></div><button class="text-button" @click="openPolicy">변경</button></article></div>
    <article class="surface-card">
      <header class="card-header"><div><span class="card-kicker">PROJECT CREDENTIALS</span><h2>API 키</h2></div><span class="count-badge">{{ keys.length }}</span></header>
      <div v-if="keys.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>이름</th><th>Prefix</th><th>상태</th><th>마지막 사용</th><th>만료</th><th></th></tr></thead><tbody><tr v-for="key in keys" :key="key.id"><td><strong>{{ key.name }}</strong><small>{{ new Date(key.createdAt).toLocaleDateString() }} 생성</small></td><td class="mono">{{ key.keyPrefix }}••••</td><td><span class="status-chip tiny" :class="key.status === 'ACTIVE' ? 'healthy' : 'unknown'">{{ key.status }}</span></td><td>{{ key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleString() : '사용 전' }}</td><td>{{ key.expiresAt ? new Date(key.expiresAt).toLocaleDateString() : '만료 없음' }}</td><td><button class="text-button danger-text" :disabled="key.status !== 'ACTIVE'" @click="revokeKey(key)">폐기</button></td></tr></tbody></table></div>
      <div v-else class="empty-state"><span>⌁</span><h3>발급된 API 키가 없습니다</h3><p>키 원문은 발급 직후 한 번만 표시됩니다.</p><button class="text-button" :disabled="!selectedId" @click="keyModal = true">첫 API 키 발급</button></div>
    </article>
    <article v-if="selectedProject" class="surface-card code-card"><header class="card-header"><div><span class="card-kicker">QUICK START</span><h2>API 연결 예시</h2></div><span class="language-pill">curl</span></header><pre><code>curl {{ apiOrigin }}/v1/chat/completions \
  -H "Authorization: Bearer sk_llmg_..." \
  -H "Content-Type: application/json" \
  -d '{ "model": "text-pro", "messages": [{ "role": "user", "content": "안녕하세요" }] }'</code></pre></article>
    <BaseModal :open="orgModal" title="새 조직" description="회사 또는 개인 단위의 격리된 리소스 공간입니다." size="sm" @close="orgModal = false"><label class="field">조직 이름<input v-model.trim="organizationName" placeholder="AI Platform Team" /></label><template #footer><button class="secondary-button" @click="orgModal = false">취소</button><button class="primary-button" :disabled="busy || !organizationName" @click="createOrganization">조직 생성</button></template></BaseModal>
    <BaseModal :open="projectModal" title="새 프로젝트" description="API 키와 사용량을 묶는 애플리케이션 단위입니다." size="sm" @close="projectModal = false"><label class="field">프로젝트 이름<input v-model.trim="projectName" placeholder="image-prompt-service" /></label><template #footer><button class="secondary-button" @click="projectModal = false">취소</button><button class="primary-button" :disabled="busy || !projectName" @click="createProject">프로젝트 생성</button></template></BaseModal>
    <BaseModal :open="keyModal" title="API 키 발급" description="키 원문은 다음 단계에서 한 번만 확인할 수 있습니다." size="sm" @close="keyModal = false"><div class="modal-form"><label class="field">키 이름<input v-model.trim="keyName" placeholder="production-backend" /></label><label class="field">만료일 (선택)<input v-model="expiresAt" type="datetime-local" /></label></div><template #footer><button class="secondary-button" @click="keyModal = false">취소</button><button class="primary-button" :disabled="busy || !keyName" @click="issueKey">키 발급</button></template></BaseModal>
    <BaseModal :open="accessModal" title="서비스 사용 권한" description="선택한 논리 서비스를 프로젝트 API 키에서 사용할 수 있게 합니다." size="sm" @close="accessModal = false"><label class="field">논리 서비스<select v-model="selectedServiceId"><option value="">서비스 선택</option><option v-for="service in services.filter(item => item.enabled)" :key="service.id" :value="service.id">{{ service.serviceKey }} · {{ service.displayName }}</option></select></label><template #footer><button class="secondary-button" @click="accessModal = false">취소</button><button class="primary-button" :disabled="busy || !selectedServiceId" @click="grantAccess">권한 부여</button></template></BaseModal>
    <BaseModal :open="policyModal" title="프로젝트 한도와 데이터 정책" description="API 키 전체에 적용되는 요청 한도와 원문 보관 방식을 설정합니다." @close="policyModal = false"><div class="modal-form"><div class="form-grid"><label class="field">분당 요청 수 (RPM)<input v-model.number="quotaForm.requestsPerMinute" type="number" min="1" /></label><label class="field">월 토큰 한도<input v-model.number="quotaForm.monthlyTokenLimit" type="number" min="1" placeholder="비우면 제한 없음" /></label></div><div class="retention-options"><label :class="{ active: retentionMode === 'METADATA_ONLY' }"><input v-model="retentionMode" type="radio" value="METADATA_ONLY" /><span><b>메타데이터만</b><small>토큰, 비용, 지연, 상태만 저장합니다. 권장 기본값입니다.</small></span></label><label :class="{ active: retentionMode === 'FULL_ENCRYPTED' }"><input v-model="retentionMode" type="radio" value="FULL_ENCRYPTED" /><span><b>암호화 원문 보관</b><small>비스트리밍 JSON 원문을 AES-GCM으로 보관합니다. 사용자 고지가 필요합니다.</small></span></label></div></div><template #footer><button class="secondary-button" @click="policyModal = false">취소</button><button class="primary-button" :disabled="busy || quotaForm.requestsPerMinute < 1" @click="savePolicy">정책 저장</button></template></BaseModal>
    <BaseModal :open="revealModal" title="API 키가 발급되었습니다" description="이 창을 닫으면 키 원문을 다시 볼 수 없습니다." @close="revealModal = false"><div class="secret-reveal"><span>PROJECT API KEY</span><code>{{ issuedSecret }}</code><button class="secondary-button" @click="copySecret">복사</button></div><template #footer><button class="primary-button" @click="revealModal = false">안전하게 보관했습니다</button></template></BaseModal>
  </section>
</template>
