<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import CapabilityPicker from './CapabilityPicker.vue'
import { adminFetch, type AdminAuth } from './api'

type Provider = { id:string; organizationId:string; providerType:string; displayName:string; baseUrl:string; enabled:boolean; healthStatus:string; apiKeyConfigured:boolean }
type Model = { id:string; providerModelId:string; displayName:string; contextLength?:number; maxConcurrency:number; capabilitiesJson:string; healthStatus:string; enabled:boolean; inputPricePerMillion?:number; outputPricePerMillion?:number }
type Project = { id:string; name:string }
type Access = { id:string; projectId:string; projectName:string; providerId:string; providerName:string; status:string; requestedReason:string; manualAllowed:boolean; autoFailoverEnabled:boolean }
type ProbeModel = { modelId:string; ownedBy?:string; objectType?:string; contextLength?:number; maxConcurrency?:number; capabilities:string[]; capabilitySource:string; registeredInAiconnect:boolean; registeredHealthStatus?:string }
type Probe = { reachable:boolean; httpStatus:number; latencyMs:number; providerName:string; api:{ providerType:string; baseUrl:string; protocol:string; authentication:string; endpoints:string; latencyMs:number; httpStatus:number }; models:ProbeModel[]; capabilityNote:string }
type Preview = { providerId:string; displayName:string; modelCount:number; targetCount:number; projectAccessCount:number; requestHistoryCount:number; behavior:string }

const props = defineProps<{ organizationId:string; auth:AdminAuth; platformAdmin?:boolean }>()
const isPlatformAdmin = computed(() => Boolean(props.platformAdmin || props.auth.platformToken))
const providers = ref<Provider[]>([])
const models = ref<Model[]>([])
const projects = ref<Project[]>([])
const accesses = ref<Access[]>([])
const selectedId = ref('')
const selected = computed(() => providers.value.find(item => item.id === selectedId.value) ?? null)
const busy = ref(false)
const message = ref('')
const providerOpen = ref(false)
const editing = ref(false)
const modelOpen = ref(false)
const probeOpen = ref(false)
const deleteOpen = ref(false)
const accessOpen = ref(false)
const probeResult = ref<Probe | null>(null)
const preview = ref<Preview | null>(null)
const forceDelete = ref(false)
const purgeHistory = ref(false)
const selectedAccess = ref<Access | null>(null)
const providerForm = ref({ displayName:'', baseUrl:'https://api.openai.com/v1', apiKey:'' })
const modelForm = ref({ providerModelId:'', displayName:'', compatibilityKey:'', contextLength:128000, maxConcurrency:20, capabilitiesJson:'[]', inputPricePerMillion:0, outputPricePerMillion:0 })
const accessForm = ref({ projectId:'', providerId:'', manualAllowed:true, autoFailoverEnabled:false })

function showError(error:unknown, fallback:string) { message.value = error instanceof Error ? error.message : fallback }
async function load() {
  if (!props.organizationId) { providers.value=[]; models.value=[]; projects.value=[]; accesses.value=[]; return }
  busy.value = true
  try {
    providers.value = await adminFetch<Provider[]>(`/api/admin/organizations/${props.organizationId}/external-providers`, props.auth)
    projects.value = await adminFetch<Project[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth)
    accesses.value = await adminFetch<Access[]>(`/api/admin/organizations/${props.organizationId}/external-access`, props.auth)
    if (!providers.value.some(item => item.id === selectedId.value)) selectedId.value = providers.value[0]?.id ?? ''
    await loadModels()
  } catch (error) { showError(error, '외부 AI Provider 목록을 불러오지 못했습니다.') }
  finally { busy.value = false }
}
async function loadModels() { models.value = selectedId.value ? await adminFetch<Model[]>(`/api/admin/external-providers/${selectedId.value}/models`, props.auth) : [] }
function createProvider() { editing.value=false; providerForm.value={displayName:'',baseUrl:'https://api.openai.com/v1',apiKey:''}; providerOpen.value=true }
function editProvider() { if (!selected.value) return; editing.value=true; providerForm.value={displayName:selected.value.displayName,baseUrl:selected.value.baseUrl,apiKey:''}; providerOpen.value=true }
async function saveProvider() {
  if (!providerForm.value.displayName || (!editing.value && !providerForm.value.apiKey)) return
  busy.value=true
  try {
    const path = editing.value ? `/api/admin/external-providers/${selected.value!.id}` : '/api/admin/external-providers'
    const body = editing.value
      ? { displayName:providerForm.value.displayName, baseUrl:providerForm.value.baseUrl, ...(providerForm.value.apiKey ? {apiKey:providerForm.value.apiKey} : {}) }
      : { organizationId:props.organizationId, ...providerForm.value }
    const saved = await adminFetch<Provider>(path, props.auth, {method:editing.value?'PATCH':'POST', body:JSON.stringify(body)})
    providers.value = editing.value ? providers.value.map(item => item.id===saved.id ? saved : item) : [...providers.value, saved]
    selectedId.value=saved.id; providerOpen.value=false; await loadModels()
    message.value = editing.value ? '외부 Provider 설정을 수정했습니다.' : '외부 Provider를 등록했습니다.'
  } catch (error) { showError(error, '외부 Provider 저장에 실패했습니다.') }
  finally { busy.value=false }
}
async function probe() {
  if (!selected.value) return
  busy.value=true
  try { probeResult.value=await adminFetch<Probe>(`/api/admin/external-providers/${selected.value.id}/probe`,props.auth,{method:'POST'}); probeOpen.value=true; await load() }
  catch (error) { showError(error, '외부 Provider 연결 확인에 실패했습니다.') }
  finally { busy.value=false }
}
async function openDelete() {
  if (!selected.value) return
  busy.value=true
  try { preview.value=await adminFetch<Preview>(`/api/admin/external-providers/${selected.value.id}/deletion-preview`,props.auth); forceDelete.value=false; purgeHistory.value=false; deleteOpen.value=true }
  catch (error) { showError(error, '삭제 가능 여부를 확인하지 못했습니다.') }
  finally { busy.value=false }
}
async function confirmDelete() {
  if (!preview.value) return
  busy.value=true
  try {
    await adminFetch<void>(`/api/admin/external-providers/${preview.value.providerId}?force=${forceDelete.value}&purgeHistory=${purgeHistory.value}`,props.auth,{method:'DELETE'})
    providers.value=providers.value.filter(item=>item.id!==preview.value!.providerId); selectedId.value=providers.value[0]?.id??''; deleteOpen.value=false; await loadModels(); message.value='외부 Provider를 삭제했습니다.'
  } catch (error) { showError(error, '외부 Provider 삭제에 실패했습니다.') }
  finally { busy.value=false }
}
async function toggleProvider() {
  if (!selected.value) return
  busy.value=true
  try { const saved=await adminFetch<Provider>(`/api/admin/external-providers/${selected.value.id}`,props.auth,{method:'PATCH',body:JSON.stringify({enabled:!selected.value.enabled})}); providers.value=providers.value.map(item=>item.id===saved.id?saved:item) }
  catch (error) { showError(error, 'Provider 상태 변경에 실패했습니다.') }
  finally { busy.value=false }
}
async function addModel() {
  if (!selected.value || !modelForm.value.providerModelId || !modelForm.value.displayName) return
  busy.value=true
  try { const saved=await adminFetch<Model>(`/api/admin/external-providers/${selected.value.id}/models`,props.auth,{method:'POST',body:JSON.stringify(modelForm.value)}); models.value=[...models.value,saved]; modelOpen.value=false; message.value='외부 모델을 등록했습니다.' }
  catch (error) { showError(error, '외부 모델 등록에 실패했습니다.') }
  finally { busy.value=false }
}
function openAccess(item?:Access) { selectedAccess.value=item??null; accessForm.value={projectId:item?.projectId??projects.value[0]?.id??'',providerId:item?.providerId??selected.value?.id??providers.value[0]?.id??'',manualAllowed:item?.manualAllowed??true,autoFailoverEnabled:item?.autoFailoverEnabled??false}; accessOpen.value=true }
async function saveAccess() {
  const form=accessForm.value; if(!form.projectId||!form.providerId)return
  busy.value=true
  try { const saved=await adminFetch<Access>(`/api/admin/projects/${form.projectId}/external-access/${form.providerId}`,props.auth,{method:'PATCH',body:JSON.stringify({status:'APPROVED',manualAllowed:form.manualAllowed,autoFailoverEnabled:form.autoFailoverEnabled})}); accesses.value=[saved,...accesses.value.filter(item=>item.id!==saved.id)]; accessOpen.value=false; message.value='프로젝트 외부 AI 권한을 저장했습니다.' }
  catch (error) { showError(error, '프로젝트 외부 AI 권한 저장에 실패했습니다.') }
  finally { busy.value=false }
}
watch(()=>props.organizationId,()=>void load()); watch(selectedId,()=>void loadModels()); onMounted(()=>void load())
</script>

<template>
  <section class="page-stack external-page">
    <div class="page-hero"><div><p class="eyebrow">EXTERNAL AI PROVIDERS</p><h1>외부 AI</h1><p>관리자가 검증한 OpenAI 호환 API를 프로젝트 권한과 서비스 Target 뒤에서 사용합니다.</p></div><div class="hero-actions"><button class="secondary-button" @click="load">새로고침</button><button class="primary-button" @click="createProvider">+ OpenAI 연결</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="external-layout">
      <article class="surface-card provider-list"><header class="card-header"><div><span class="card-kicker">PROVIDERS</span><h2>연결된 외부 API</h2></div><span class="count-badge">{{ providers.length }}</span></header><div v-if="providers.length" class="provider-items"><button v-for="provider in providers" :key="provider.id" :class="{active:provider.id===selectedId}" @click="selectedId=provider.id"><span class="live-dot"></span><span><b>{{ provider.displayName }}</b><small>{{ provider.providerType }} · {{ provider.healthStatus }}</small></span><i>{{ provider.enabled?'ON':'OFF' }}</i></button></div><div v-else class="empty-state compact"><span>◇</span><p>등록된 Provider가 없습니다.</p></div></article>
      <article class="surface-card provider-detail"><template v-if="selected"><header class="card-header"><div><span class="status-chip healthy">{{ selected.healthStatus }}</span><h2>{{ selected.displayName }}</h2><small>{{ selected.baseUrl }}</small></div><div class="hero-actions"><button class="secondary-button" @click="probe">연결 확인</button><button class="secondary-button" @click="editProvider">설정</button><button class="secondary-button danger-button" @click="openDelete">삭제</button><button class="secondary-button" @click="toggleProvider">{{ selected.enabled?'연결 중지':'연결 활성화' }}</button><button class="primary-button" @click="modelOpen=true">+ 모델 등록</button></div></header><div class="policy-note"><b>Provider 정보</b><p>연결 확인은 모델 목록·기능·API 상태를 조회합니다. 모델은 자동 공개되지 않으며 등록 후 Logical Service Target에 연결해야 합니다.</p></div><div class="model-grid"><div v-for="model in models" :key="model.id" class="external-model"><span class="status-chip tiny healthy">MODEL</span><h3>{{ model.displayName }}</h3><code>{{ model.providerModelId }}</code><p>Context {{ model.contextLength?.toLocaleString()??'-' }} · 동시성 {{ model.maxConcurrency }}</p><p>{{ model.capabilitiesJson }}</p></div><div v-if="!models.length" class="empty-state compact"><span>◇</span><p>등록된 모델이 없습니다.</p></div></div></template><div v-else class="empty-state"><span>◇</span><h3>Provider를 선택하세요</h3></div></article>
    </div>
    <article class="surface-card"><header class="card-header"><div><span class="card-kicker">ACCESS REQUESTS</span><h2>프로젝트 외부 AI 권한</h2></div><button class="primary-button" :disabled="!projects.length||!providers.length" @click="openAccess()">+ 권한 부여</button></header><div v-if="accesses.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>프로젝트</th><th>Provider</th><th>상태</th><th>수동</th><th>자동 Failover</th><th>관리</th></tr></thead><tbody><tr v-for="access in accesses" :key="access.id"><td>{{ access.projectName }}</td><td>{{ access.providerName }}</td><td><span class="status-chip tiny healthy">{{ access.status }}</span></td><td>{{ access.manualAllowed?'ON':'OFF' }}</td><td>{{ access.autoFailoverEnabled?'ON':'OFF' }}</td><td><button class="text-button" @click="openAccess(access)">정책 설정</button></td></tr></tbody></table></div><div v-else class="empty-state compact"><span>◇</span><p>프로젝트 권한이 없습니다.</p></div></article>

    <BaseModal :open="providerOpen" :title="editing?'외부 Provider 설정 수정':'OpenAI Provider 연결'" description="API Key 원문은 다시 표시하지 않습니다." @close="providerOpen=false"><div class="modal-form"><label class="field">표시 이름<input v-model.trim="providerForm.displayName" placeholder="OpenAI Production" /></label><label class="field">Base URL<input v-model.trim="providerForm.baseUrl" placeholder="https://api.openai.com/v1" /></label><label class="field">API Key<input v-model="providerForm.apiKey" type="password" :placeholder="editing?'변경할 때만 입력':'sk-...'" /></label></div><template #footer><button class="secondary-button" @click="providerOpen=false">취소</button><button class="primary-button" :disabled="busy||!providerForm.displayName||(!editing&&!providerForm.apiKey)" @click="saveProvider">저장</button></template></BaseModal>
    <BaseModal :open="probeOpen" title="Provider 연결 상세" description="반환 모델, 지원 기능, API 연결 정보를 확인합니다." size="lg" @close="probeOpen=false"><div v-if="probeResult" class="probe-result"><div class="probe-summary"><b>{{ probeResult.providerName }}</b><span>{{ probeResult.api.protocol }} · {{ probeResult.api.authentication }}</span><span>HTTP {{ probeResult.httpStatus }} · {{ probeResult.latencyMs }}ms</span></div><p>Base URL: {{ probeResult.api.baseUrl }}</p><p>엔드포인트: {{ probeResult.api.endpoints }}</p><h3>반환 모델 {{ probeResult.models.length }}개</h3><div class="probe-models"><div v-for="model in probeResult.models" :key="model.modelId" class="probe-model"><div><b>{{ model.modelId }}</b><span class="status-chip tiny" :class="model.registeredInAiconnect?'healthy':'unknown'">{{ model.registeredInAiconnect?'등록됨':'미등록' }}</span></div><small>{{ model.ownedBy??'-' }} · Context {{ model.contextLength?.toLocaleString()??'-' }} · 동시성 {{ model.maxConcurrency??'-' }}</small><small class="probe-source">Capability 출처: {{ model.capabilitySource === 'REGISTERED_MODEL_POLICY' ? 'AICONNECT 등록 정책' : 'Provider 응답 메타데이터' }}</small><p><span v-for="capability in model.capabilities" :key="capability" class="capability-chip">{{ capability }}</span><span v-if="!model.capabilities.length">기능 정보 없음</span></p></div></div><div class="policy-note">{{ probeResult.capabilityNote }}</div></div></BaseModal>
    <BaseModal :open="deleteOpen" title="외부 Provider 삭제" description="참조 관계를 확인한 뒤 삭제합니다." @close="deleteOpen=false"><div v-if="preview" class="modal-form"><div class="delete-summary"><b>{{ preview.displayName }}</b><p>모델 {{ preview.modelCount }} · Target {{ preview.targetCount }} · 프로젝트 권한 {{ preview.projectAccessCount }} · 요청 이력 {{ preview.requestHistoryCount }}</p><small>{{ preview.behavior }}</small></div><label v-if="isPlatformAdmin" class="toggle-field"><span>강제 정리</span><input v-model="forceDelete" type="checkbox" /></label><label v-if="isPlatformAdmin&&forceDelete" class="toggle-field"><span>요청 이력 영구 삭제</span><input v-model="purgeHistory" type="checkbox" /></label></div><template #footer><button class="secondary-button" @click="deleteOpen=false">취소</button><button class="danger-button primary-button" :disabled="busy||(!isPlatformAdmin&&Boolean(preview&&(preview.modelCount>0||preview.targetCount>0||preview.projectAccessCount>0||preview.requestHistoryCount>0)))" @click="confirmDelete">Provider 삭제</button></template></BaseModal>
    <BaseModal :open="modelOpen" title="외부 모델 등록" description="Provider 모델을 Logical Service Target에서 사용할 수 있도록 등록합니다." @close="modelOpen=false"><div class="modal-form"><div class="form-grid"><label class="field">Provider Model ID<input v-model.trim="modelForm.providerModelId" placeholder="gpt-5.6-luna" /></label><label class="field">표시 이름<input v-model.trim="modelForm.displayName" placeholder="GPT-5.6 Luna" /></label></div><div class="form-grid"><label class="field">호환성 Key<input v-model.trim="modelForm.compatibilityKey" placeholder="text-pro-compatible" /></label><label class="field">컨텍스트 길이<input v-model.number="modelForm.contextLength" type="number" min="1" /></label></div><CapabilityPicker v-model="modelForm.capabilitiesJson" /><label class="field">최대 동시 요청<input v-model.number="modelForm.maxConcurrency" type="number" min="1" /></label></div><template #footer><button class="secondary-button" @click="modelOpen=false">취소</button><button class="primary-button" :disabled="busy||!modelForm.providerModelId||!modelForm.displayName" @click="addModel">모델 등록</button></template></BaseModal>
    <BaseModal :open="accessOpen" title="프로젝트 외부 AI 권한" description="수동 사용과 자동 Failover 정책을 설정합니다." @close="accessOpen=false"><div class="modal-form"><label class="field">프로젝트<select v-model="accessForm.projectId"><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label><label class="field">Provider<select v-model="accessForm.providerId"><option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.displayName }}</option></select></label><label class="toggle-field"><span>수동 사용 허용</span><input v-model="accessForm.manualAllowed" type="checkbox" /></label><label class="toggle-field"><span>자동 Failover 허용</span><input v-model="accessForm.autoFailoverEnabled" type="checkbox" /></label></div><template #footer><button class="secondary-button" @click="accessOpen=false">취소</button><button class="primary-button" :disabled="busy" @click="saveAccess">권한 저장</button></template></BaseModal>
  </section>
</template>

<style scoped>
.external-layout{display:grid;grid-template-columns:360px minmax(0,1fr);gap:14px}.provider-list,.provider-detail{min-height:460px}.provider-items{padding:10px;display:grid;gap:7px}.provider-items button{padding:13px;display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:10px;align-items:center;border:1px solid transparent;border-radius:11px;background:transparent;color:var(--text);text-align:left}.provider-items button.active{border-color:var(--accent-border);background:var(--accent-dim)}.provider-items button span:nth-child(2){display:grid;gap:4px}.provider-items small{color:var(--muted)}.provider-items i{color:var(--accent-strong);font-size:9px;font-style:normal}.provider-detail>.card-header{align-items:flex-start}.provider-detail h2{margin:10px 0 4px}.danger-button{color:#e66b72!important;border-color:rgba(230,107,114,.4)!important}.policy-note{margin:16px;padding:14px;border:1px solid var(--accent-border);border-radius:11px;background:var(--accent-dim)}.policy-note p{margin:5px 0 0;color:var(--text-soft);font-size:11px;line-height:1.6}.model-grid{padding:0 16px 16px;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.external-model{padding:15px;border:1px solid var(--border);border-radius:12px;background:var(--surface-2)}.external-model h3{margin:10px 0 5px}.external-model code{color:var(--accent-strong);font-size:10px}.external-model p{font-size:10px;color:var(--text-soft)}.probe-result{display:grid;gap:12px}.probe-summary,.delete-summary{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:14px;border:1px solid var(--border);border-radius:11px;background:var(--surface-2)}.probe-models{display:grid;gap:8px;max-height:340px;overflow:auto}.probe-model{padding:12px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2)}.probe-model>div{display:flex;justify-content:space-between;gap:8px}.probe-model small,.probe-model p,.delete-summary p,.delete-summary small{display:block;margin:8px 0 0;color:var(--muted);line-height:1.5}.capability-chip{display:inline-block;margin:2px;padding:3px 7px;border:1px solid var(--accent-border);border-radius:999px;color:var(--accent-strong);font-size:10px}@media(max-width:980px){.external-layout{grid-template-columns:1fr}.provider-list,.provider-detail{min-height:0}}@media(max-width:680px){.model-grid{grid-template-columns:1fr}}
</style>
