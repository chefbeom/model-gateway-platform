<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import CapabilityPicker from './CapabilityPicker.vue'
import { adminFetch, type AdminAuth } from './api'

type Provider = { id: string; organizationId: string; providerType: string; displayName: string; baseUrl: string; enabled: boolean; healthStatus: string; lastCheckedAt?: string; apiKeyConfigured: boolean }
type ProviderModel = { id: string; externalProviderId: string; providerModelId: string; compatibilityKey: string; displayName: string; contextLength?: number; enabled: boolean; healthStatus: string; maxConcurrency: number; capabilitiesJson: string; inputPricePerMillion?: number; outputPricePerMillion?: number }
type ProjectOption = { id: string; name: string }
type AccessRequest = { id: string; projectId: string; projectName: string; providerId: string; providerName: string; status: string; requestedBy?: string; requestedReason: string; manualAllowed: boolean; autoFailoverEnabled: boolean; monthlyCostLimit?: number; expiresAt?: string; createdAt: string }

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const providers = ref<Provider[]>([])
const models = ref<ProviderModel[]>([])
const accessRequests = ref<AccessRequest[]>([])
const projects = ref<ProjectOption[]>([])
const selectedId = ref('')
const busy = ref(false)
const message = ref('')
const providerOpen = ref(false)
const modelOpen = ref(false)
const decisionOpen = ref(false)
const directGrantOpen = ref(false)
const selectedRequest = ref<AccessRequest | null>(null)
const providerForm = ref({ displayName: '', baseUrl: 'https://api.openai.com/v1', apiKey: '' })
const modelForm = ref({ providerModelId: '', displayName: '', compatibilityKey: '', contextLength: 128000, maxConcurrency: 20, capabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0 })
const decisionForm = ref({ status: 'APPROVED', manualAllowed: true, autoFailoverEnabled: false, monthlyCostLimit: 0, expiresAt: '' })
const directGrantForm = ref({ projectId: '', providerId: '', manualAllowed: true, autoFailoverEnabled: false, monthlyCostLimit: 0, expiresAt: '' })
const selected = computed(() => providers.value.find(item => item.id === selectedId.value) ?? null)

async function load() {
  if (!props.organizationId) { providers.value = []; models.value = []; accessRequests.value = []; return }
  busy.value = true
  try {
    const [providerList, requests, projectList] = await Promise.all([
      adminFetch<Provider[]>(`/api/admin/organizations/${props.organizationId}/external-providers`, props.auth),
      adminFetch<AccessRequest[]>(`/api/admin/organizations/${props.organizationId}/external-access`, props.auth),
      adminFetch<ProjectOption[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth)
    ])
    providers.value = providerList
    accessRequests.value = requests
    projects.value = projectList
    if (!providers.value.some(item => item.id === selectedId.value)) selectedId.value = providers.value[0]?.id ?? ''
    await loadModels()
  } catch (error) { message.value = error instanceof Error ? error.message : '외부 AI 설정을 불러오지 못했습니다.' }
  finally { busy.value = false }
}
async function loadModels() {
  models.value = selectedId.value ? await adminFetch<ProviderModel[]>(`/api/admin/external-providers/${selectedId.value}/models`, props.auth) : []
}
async function createProvider() {
  if (!providerForm.value.displayName || !providerForm.value.apiKey) return
  busy.value = true
  try {
    const created = await adminFetch<Provider>('/api/admin/external-providers', props.auth, { method: 'POST', body: JSON.stringify({ organizationId: props.organizationId, ...providerForm.value }) })
    providers.value = [...providers.value, created]
    selectedId.value = created.id
    providerOpen.value = false
    providerForm.value = { displayName: '', baseUrl: 'https://api.openai.com/v1', apiKey: '' }
    message.value = '외부 Provider를 등록했습니다. 연결 확인 후 모델을 추가하세요.'
    await loadModels()
  } catch (error) { message.value = error instanceof Error ? error.message : 'Provider 등록에 실패했습니다.' }
  finally { busy.value = false }
}
async function probe() {
  if (!selected.value) return
  busy.value = true
  try {
    const result = await adminFetch<{ modelCount: number }>(`/api/admin/external-providers/${selected.value.id}/probe`, props.auth, { method: 'POST' })
    message.value = `OpenAI 연결을 확인했습니다. API가 반환한 모델은 ${result.modelCount}개입니다.`
    await load()
  } catch (error) { message.value = error instanceof Error ? error.message : '연결 확인에 실패했습니다.' }
  finally { busy.value = false }
}
async function toggleProvider() {
  if (!selected.value) return
  busy.value = true
  try {
    const updated = await adminFetch<Provider>(`/api/admin/external-providers/${selected.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify({ enabled: !selected.value.enabled }) })
    providers.value = providers.value.map(item => item.id === updated.id ? updated : item)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Provider 상태 변경에 실패했습니다.' }
  finally { busy.value = false }
}
async function addModel() {
  if (!selected.value || !modelForm.value.providerModelId || !modelForm.value.displayName) return
  busy.value = true
  try {
    const created = await adminFetch<ProviderModel>(`/api/admin/external-providers/${selected.value.id}/models`, props.auth, { method: 'POST', body: JSON.stringify(modelForm.value) })
    models.value = [...models.value, created]
    modelOpen.value = false
    modelForm.value = { providerModelId: '', displayName: '', compatibilityKey: '', contextLength: 128000, maxConcurrency: 20, capabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0 }
    message.value = '외부 모델을 등록했습니다. LLM 서비스에서 Target으로 연결할 수 있습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '외부 모델 등록에 실패했습니다.' }
  finally { busy.value = false }
}
function openDecision(item: AccessRequest) {
  selectedRequest.value = item
  decisionForm.value = { status: item.status === 'APPROVED' ? 'APPROVED' : 'APPROVED', manualAllowed: item.status === 'APPROVED' ? item.manualAllowed : true, autoFailoverEnabled: item.autoFailoverEnabled, monthlyCostLimit: item.monthlyCostLimit ?? 0, expiresAt: item.expiresAt ? item.expiresAt.slice(0, 16) : '' }
  decisionOpen.value = true
}
async function decide() {
  if (!selectedRequest.value) return
  busy.value = true
  try {
    const body = { ...decisionForm.value, monthlyCostLimit: decisionForm.value.monthlyCostLimit > 0 ? decisionForm.value.monthlyCostLimit : null, expiresAt: decisionForm.value.expiresAt ? new Date(decisionForm.value.expiresAt).toISOString() : null }
    const updated = await adminFetch<AccessRequest>(`/api/admin/projects/${selectedRequest.value.projectId}/external-access/${selectedRequest.value.providerId}`, props.auth, { method: 'PATCH', body: JSON.stringify(body) })
    accessRequests.value = accessRequests.value.map(item => item.id === updated.id ? updated : item)
    decisionOpen.value = false
    message.value = body.status === 'APPROVED' ? '외부 AI 사용 정책을 승인했습니다.' : '외부 AI 사용 요청 상태를 변경했습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '승인 정책 저장에 실패했습니다.' }
  finally { busy.value = false }
}

function openDirectGrant() {
  if (!providers.value.length || !projects.value.length) return
  directGrantForm.value = { projectId: projects.value[0]?.id ?? '', providerId: selected.value?.id ?? providers.value[0]?.id ?? '', manualAllowed: true, autoFailoverEnabled: false, monthlyCostLimit: 0, expiresAt: '' }
  directGrantOpen.value = true
}
async function grantDirectAccess() {
  const form = directGrantForm.value
  if (!form.projectId || !form.providerId) return
  busy.value = true
  try {
    const body = { status: 'APPROVED', manualAllowed: form.manualAllowed, autoFailoverEnabled: form.autoFailoverEnabled, monthlyCostLimit: form.monthlyCostLimit > 0 ? form.monthlyCostLimit : null, expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString() : null }
    const updated = await adminFetch<AccessRequest>(`/api/admin/projects/${form.projectId}/external-access/${form.providerId}`, props.auth, { method: 'PATCH', body: JSON.stringify(body) })
    const exists = accessRequests.value.some(item => item.id === updated.id)
    accessRequests.value = exists ? accessRequests.value.map(item => item.id === updated.id ? updated : item) : [updated, ...accessRequests.value]
    directGrantOpen.value = false
    message.value = '관리자가 프로젝트의 외부 AI 사용 정책을 직접 승인했습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 외부 AI 권한 부여에 실패했습니다.' }
  finally { busy.value = false }
}
watch(() => props.organizationId, () => { void load() })
watch(selectedId, () => { void loadModels() })
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack external-page">
    <div class="page-hero"><div><p class="eyebrow">EXTERNAL AI PROVIDERS</p><h1>외부 AI</h1><p>OpenAI API Key를 암호화해 등록하고, 프로젝트의 요청을 승인한 경우에만 수동 사용 또는 자동 Failover를 허용합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load">새로고침</button><button class="primary-button" @click="providerOpen = true">+ OpenAI 연결</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>

    <div class="external-layout">
      <article class="surface-card provider-list"><header class="card-header"><div><span class="card-kicker">PROVIDERS</span><h2>연결된 외부 API</h2></div><span class="count-badge">{{ providers.length }}</span></header><div v-if="providers.length" class="provider-items"><button v-for="item in providers" :key="item.id" :class="{ active: selectedId === item.id }" @click="selectedId = item.id"><span class="live-dot" :class="item.healthStatus.toLowerCase()"></span><span><b>{{ item.displayName }}</b><small>{{ item.providerType }} · {{ item.healthStatus }}</small></span><i>{{ item.enabled ? 'ON' : 'OFF' }}</i></button></div><div v-else class="empty-state compact"><span>◇</span><p>등록된 외부 Provider가 없습니다.</p></div></article>

      <article class="surface-card provider-detail"><template v-if="selected"><header class="card-header"><div><span class="status-chip" :class="selected.healthStatus === 'HEALTHY' ? 'healthy' : 'unknown'">{{ selected.healthStatus }}</span><h2>{{ selected.displayName }}</h2><small>{{ selected.baseUrl }}</small></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="probe">연결 확인</button><button class="secondary-button" :disabled="busy" @click="toggleProvider">{{ selected.enabled ? '연결 중지' : '연결 활성화' }}</button><button class="primary-button" @click="modelOpen = true">+ 모델 등록</button></div></header><div class="policy-note"><b>기본 정책</b><p>Provider 등록만으로 요청이 전송되지 않습니다. 프로젝트 승인과 서비스 Target 연결이 모두 필요하며 자동 Failover는 별도 ON 설정이 필요합니다.</p></div><div class="model-grid"><div v-for="model in models" :key="model.id" class="external-model"><span class="status-chip tiny healthy">CLOUD</span><h3>{{ model.displayName }}</h3><code>{{ model.providerModelId }}</code><div><small>Context {{ model.contextLength?.toLocaleString() ?? '-' }}</small><small>동시성 {{ model.maxConcurrency }}</small></div><p>{{ model.capabilitiesJson }}</p><footer>입력 {{ model.inputPricePerMillion ?? 0 }} / 1M · 출력 {{ model.outputPricePerMillion ?? 0 }} / 1M</footer></div><div v-if="!models.length" class="empty-state compact"><span>◇</span><p>허용할 OpenAI 모델을 명시적으로 등록하세요.</p></div></div></template><div v-else class="empty-state"><span>◇</span><h3>외부 Provider를 선택하세요</h3></div></article>
    </div>

    <article class="surface-card"><header class="card-header"><div><span class="card-kicker">ACCESS REQUESTS</span><h2>프로젝트 외부 AI 요청</h2></div><div class="hero-actions"><span class="count-badge">{{ accessRequests.length }}</span><button class="primary-button" :disabled="!providers.length || !projects.length" @click="openDirectGrant">+ 직접 권한 부여</button></div></header><div v-if="accessRequests.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>프로젝트</th><th>Provider</th><th>요청자·사유</th><th>상태</th><th>수동</th><th>자동 Failover</th><th>관리</th></tr></thead><tbody><tr v-for="item in accessRequests" :key="item.id"><td><strong>{{ item.projectName }}</strong></td><td>{{ item.providerName }}</td><td><strong>{{ item.requestedBy ?? '알 수 없음' }}</strong><small>{{ item.requestedReason }}</small></td><td><span class="status-chip tiny" :class="item.status === 'APPROVED' ? 'healthy' : 'unknown'">{{ item.status }}</span></td><td>{{ item.manualAllowed ? 'ON' : 'OFF' }}</td><td>{{ item.autoFailoverEnabled ? 'ON' : 'OFF' }}</td><td><button class="text-button" @click="openDecision(item)">정책 설정</button></td></tr></tbody></table></div><div v-else class="empty-state compact"><span>◇</span><p>아직 프로젝트의 외부 AI 사용 요청이 없습니다.</p></div></article>

    <BaseModal :open="providerOpen" title="OpenAI Provider 연결" description="API Key는 암호화되어 저장되고 저장 후 원문을 다시 표시하지 않습니다." @close="providerOpen = false"><div class="modal-form"><label class="field">표시 이름<input v-model.trim="providerForm.displayName" placeholder="회사 OpenAI API" /></label><label class="field">Base URL<input v-model.trim="providerForm.baseUrl" /></label><label class="field">OpenAI API Key<input v-model="providerForm.apiKey" type="password" autocomplete="new-password" placeholder="sk-..." /></label></div><template #footer><button class="secondary-button" @click="providerOpen = false">취소</button><button class="primary-button" :disabled="busy || !providerForm.displayName || !providerForm.apiKey" @click="createProvider">연결 저장</button></template></BaseModal>

    <BaseModal :open="modelOpen" title="외부 모델 등록" description="OpenAI의 모든 모델을 자동 노출하지 않고, 관리자가 검증한 모델만 서비스 Target으로 사용합니다." @close="modelOpen = false"><div class="modal-form"><div class="form-grid"><label class="field">Provider Model ID<input v-model.trim="modelForm.providerModelId" placeholder="모델 ID" /></label><label class="field">표시 이름<input v-model.trim="modelForm.displayName" placeholder="외부 고성능 모델" /></label></div><div class="form-grid"><label class="field">호환성 Key<input v-model.trim="modelForm.compatibilityKey" placeholder="text-pro-compatible" /></label><label class="field">컨텍스트 길이<input v-model.number="modelForm.contextLength" type="number" min="1" /></label></div><CapabilityPicker v-model="modelForm.capabilitiesJson" /><div class="form-grid three"><label class="field">최대 동시 요청<input v-model.number="modelForm.maxConcurrency" type="number" min="1" /></label><label class="field">입력 단가 / 1M<input v-model.number="modelForm.inputPricePerMillion" type="number" min="0" step="0.000001" /></label><label class="field">출력 단가 / 1M<input v-model.number="modelForm.outputPricePerMillion" type="number" min="0" step="0.000001" /></label></div></div><template #footer><button class="secondary-button" @click="modelOpen = false">취소</button><button class="primary-button" :disabled="busy || !modelForm.providerModelId || !modelForm.displayName" @click="addModel">모델 등록</button></template></BaseModal>

    <BaseModal :open="directGrantOpen" title="프로젝트 외부 AI 직접 승인" description="사용자 요청을 기다리지 않고 관리자가 프로젝트 정책을 직접 부여합니다. 자동 Failover는 기본적으로 꺼져 있습니다." @close="directGrantOpen = false">
      <div class="modal-form">
        <div class="form-grid">
          <label class="field">프로젝트<select v-model="directGrantForm.projectId"><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
          <label class="field">외부 Provider<select v-model="directGrantForm.providerId"><option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.displayName }} · {{ provider.healthStatus }}</option></select></label>
        </div>
        <div class="form-grid">
          <label class="toggle-field"><span>수동 사용 허용<small>외부 전용 논리 서비스 model을 명시적으로 호출</small></span><input v-model="directGrantForm.manualAllowed" type="checkbox" /></label>
          <label class="toggle-field"><span>자동 Failover 허용<small>로컬 Target 실패 시에만 외부 모델 후보 사용</small></span><input v-model="directGrantForm.autoFailoverEnabled" type="checkbox" /></label>
        </div>
        <div class="form-grid">
          <label class="field">월 외부 비용 상한<input v-model.number="directGrantForm.monthlyCostLimit" type="number" min="0" placeholder="0 = 제한 없음" /></label>
          <label class="field">승인 만료일<input v-model="directGrantForm.expiresAt" type="datetime-local" /></label>
        </div>
        <div class="policy-note"><b>데이터 외부 전송</b><p>외부 Provider를 호출하면 프로젝트의 프롬프트와 첨부 입력이 회사 네트워크 밖으로 전달될 수 있습니다. 프로젝트의 데이터 정책을 확인한 뒤 승인하세요.</p></div>
      </div>
      <template #footer><button class="secondary-button" @click="directGrantOpen = false">취소</button><button class="primary-button" :disabled="busy || !directGrantForm.projectId || !directGrantForm.providerId" @click="grantDirectAccess">직접 승인</button></template>
    </BaseModal>
    <BaseModal :open="decisionOpen" title="외부 AI 승인 정책" description="수동 사용과 자동 Failover를 분리합니다. 자동 전환은 명시적으로 켠 경우에만 동작합니다." @close="decisionOpen = false"><div class="modal-form"><div class="decision-summary"><b>{{ selectedRequest?.projectName }}</b><small>{{ selectedRequest?.providerName }}</small><p>{{ selectedRequest?.requestedReason }}</p></div><label class="field">처리 상태<select v-model="decisionForm.status"><option value="APPROVED">승인</option><option value="REJECTED">거절</option><option value="REVOKED">승인 회수</option></select></label><div class="form-grid"><label class="toggle-field"><span>수동 외부 모델 사용<small>외부 전용 논리 서비스를 명시적으로 호출</small></span><input v-model="decisionForm.manualAllowed" type="checkbox" :disabled="decisionForm.status !== 'APPROVED'" /></label><label class="toggle-field"><span>자동 Failover<small>모든 로컬 후보가 실패했을 때만 외부 전환</small></span><input v-model="decisionForm.autoFailoverEnabled" type="checkbox" :disabled="decisionForm.status !== 'APPROVED'" /></label></div><div class="form-grid"><label class="field">월 외부 비용 한도<input v-model.number="decisionForm.monthlyCostLimit" type="number" min="0" placeholder="0 = 제한 없음" /></label><label class="field">승인 만료일<input v-model="decisionForm.expiresAt" type="datetime-local" /></label></div></div><template #footer><button class="secondary-button" @click="decisionOpen = false">취소</button><button class="primary-button" :disabled="busy" @click="decide">정책 저장</button></template></BaseModal>
  </section>
</template>

<style scoped>
.external-layout { display:grid; grid-template-columns:360px minmax(0,1fr); gap:14px; }.provider-list,.provider-detail{min-height:460px}.provider-items{padding:10px;display:grid;gap:7px}.provider-items button{padding:13px;display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:10px;align-items:center;border:1px solid transparent;border-radius:11px;background:transparent;color:var(--text);text-align:left}.provider-items button.active{border-color:var(--accent-border);background:var(--accent-dim)}.provider-items span:nth-child(2){display:grid;gap:4px}.provider-items small{color:var(--muted)}.provider-items i{color:var(--accent-strong);font-size:9px;font-style:normal}.provider-detail>.card-header{align-items:flex-start}.provider-detail h2{margin:10px 0 4px}.policy-note{margin:16px;padding:14px;border:1px solid var(--accent-border);border-radius:11px;background:var(--accent-dim)}.policy-note p{margin:5px 0 0;color:var(--text-soft);font-size:11px;line-height:1.6}.model-grid{padding:0 16px 16px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.external-model{padding:15px;border:1px solid var(--border);border-radius:12px;background:var(--surface-2)}.external-model h3{margin:10px 0 5px}.external-model code{color:var(--accent-strong);font-size:10px}.external-model>div{margin:12px 0 7px;display:flex;gap:10px;color:var(--muted)}.external-model p{font-size:9px;color:var(--text-soft)}.external-model footer{padding-top:9px;border-top:1px solid var(--border);color:var(--muted);font-size:9px}.decision-summary{padding:13px;display:grid;gap:5px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2)}.decision-summary p{margin:4px 0 0;color:var(--text-soft);font-size:11px}.empty-state.compact{min-height:150px}@media(max-width:980px){.external-layout{grid-template-columns:1fr}.provider-list,.provider-detail{min-height:0}}@media(max-width:680px){.model-grid{grid-template-columns:1fr}}
</style>
