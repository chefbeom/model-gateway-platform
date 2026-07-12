<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import ModelOperationsPanel from './ModelOperationsPanel.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()

type Accelerator = { id: string; nodeId: string; vendor?: string; productName?: string; deviceIndex: number; deviceUuid?: string; memoryTotalMb?: number; driverVersion?: string }
type EndpointDetail = Endpoint & { nodeName: string; nodeDescription?: string | null; apiTokenConfigured: boolean }

const endpoints = ref<Endpoint[]>([])
const deployments = ref<Deployment[]>([])
const accelerators = ref<Accelerator[]>([])
const selected = ref<Endpoint | null>(null)
const busy = ref(false)
const message = ref('')
const createOpen = ref(false)
const endpointSettingsOpen = ref(false)
const endpointDeleteOpen = ref(false)
const acceleratorOpen = ref(false)
const deploymentOpen = ref(false)
const editing = ref<Deployment | null>(null)
const endpointDetail = ref<EndpointDetail | null>(null)
const runtime = ref({ nodeName: '', description: '', baseUrl: 'http://gpu-node-01:1234', apiToken: '' })
const endpointForm = ref({ baseUrl: '', enabled: true, apiToken: '', clearApiToken: false })
const accelerator = ref({ vendor: '', productName: '', deviceIndex: 0, deviceUuid: '', memoryTotalMb: null as number | null, driverVersion: '' })

function healthClass(value?: string) { return (value ?? 'unknown').toLowerCase() }
function endpointLabel(endpoint: Endpoint) { return endpoint.baseUrl.replace(/^https?:\/\//, '') }

async function selectEndpoint(endpoint: Endpoint) {
  selected.value = endpoint
  const [models, devices] = await Promise.all([
    adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpoint.id}/deployments`, props.auth),
    adminFetch<Accelerator[]>(`/api/admin/nodes/${endpoint.nodeId}/accelerators`, props.auth)
  ])
  deployments.value = models
  accelerators.value = devices
}

async function load(preferredId?: string) {
  busy.value = true
  message.value = ''
  try {
    endpoints.value = await adminFetch<Endpoint[]>('/api/admin/runtime-endpoints', props.auth)
    const next = endpoints.value.find(item => item.id === (preferredId ?? selected.value?.id)) ?? endpoints.value[0] ?? null
    selected.value = next
    if (next) await selectEndpoint(next)
    else { deployments.value = []; accelerators.value = [] }
  } catch (error) { message.value = error instanceof Error ? error.message : '인프라 목록을 불러오지 못했습니다.' }
  finally { busy.value = false }
}

async function createRuntime() {
  if (!props.organizationId) return
  busy.value = true
  message.value = ''
  try {
    const node = await adminFetch<{ id: string }>('/api/admin/nodes', props.auth, { method: 'POST', body: JSON.stringify({ organizationId: props.organizationId, name: runtime.value.nodeName, description: runtime.value.description || null, connectionMode: 'DIRECT', labelsJson: '{"source":"console"}' }) })
    const endpoint = await adminFetch<Endpoint>('/api/admin/runtime-endpoints', props.auth, { method: 'POST', body: JSON.stringify({ nodeId: node.id, runtimeType: 'LM_STUDIO', baseUrl: runtime.value.baseUrl, apiToken: runtime.value.apiToken || null }) })
    runtime.value = { nodeName: '', description: '', baseUrl: 'http://gpu-node-01:1234', apiToken: '' }
    createOpen.value = false
    message.value = 'LM Studio Runtime을 등록했습니다. 연결 확인 후 모델을 동기화하세요.'
    await load(endpoint.id)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Runtime 등록에 실패했습니다.' }
  finally { busy.value = false }
}

async function action(name: 'probe' | 'sync-models' | 'drain' | 'resume') {
  if (!selected.value) return
  busy.value = true
  message.value = ''
  try {
    if (name === 'probe') {
      const result = await adminFetch<{ reachable: boolean; httpStatus: number; modelIds: string[]; errorMessage?: string | null }>(
        `/api/admin/runtime-endpoints/${selected.value.id}/probe`, props.auth, { method: 'POST' }
      )
      message.value = result.reachable
        ? `연결 성공 · ${result.modelIds.length}개 모델을 확인했습니다.`
        : `연결 실패${result.httpStatus ? ` · HTTP ${result.httpStatus}` : ''}${result.errorMessage ? ` · ${result.errorMessage}` : ''}`
      await load(selected.value.id)
      return
    }
    await adminFetch<unknown>(`/api/admin/runtime-endpoints/${selected.value.id}/${name}`, props.auth, { method: 'POST' })
    if (name === 'sync-models') message.value = '모델 목록을 동기화했습니다.'
    else if (name === 'drain') message.value = '새 요청을 중지하고 Drain 상태로 전환했습니다.'
    else message.value = 'Endpoint 복구 및 재투입을 요청했습니다.'
    await load(selected.value.id)
  } catch (error) { message.value = error instanceof Error ? error.message : '작업을 완료하지 못했습니다.' }
  finally { busy.value = false }
}

async function openEndpointSettings(endpoint = selected.value) {
  if (!endpoint) return
  busy.value = true
  message.value = ''
  try {
    endpointDetail.value = await adminFetch<EndpointDetail>(`/api/admin/runtime-endpoints/${endpoint.id}`, props.auth)
    endpointForm.value = { baseUrl: endpointDetail.value.baseUrl, enabled: endpointDetail.value.enabled, apiToken: '', clearApiToken: false }
    endpointSettingsOpen.value = true
  } catch (error) { message.value = error instanceof Error ? error.message : 'Endpoint 정보를 불러오지 못했습니다.' }
  finally { busy.value = false }
}

async function saveEndpointSettings() {
  if (!endpointDetail.value) return
  busy.value = true
  message.value = ''
  try {
    const saved = await adminFetch<Endpoint>(`/api/admin/runtime-endpoints/${endpointDetail.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify(endpointForm.value) })
    endpointSettingsOpen.value = false
    message.value = 'Endpoint 설정을 저장했습니다.'
    await load(saved.id)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Endpoint 설정 저장에 실패했습니다.' }
  finally { busy.value = false }
}

async function archiveEndpoint() {
  if (!endpointDetail.value) return
  busy.value = true
  message.value = ''
  try {
    await adminFetch<void>(`/api/admin/runtime-endpoints/${endpointDetail.value.id}`, props.auth, { method: 'DELETE' })
    endpointDeleteOpen.value = false
    endpointSettingsOpen.value = false
    endpointDetail.value = null
    selected.value = null
    message.value = 'Endpoint를 삭제했습니다. 과거 요청·장애·감사 기록은 보존됩니다.'
    await load()
  } catch (error) { message.value = error instanceof Error ? error.message : 'Endpoint 삭제에 실패했습니다.' }
  finally { busy.value = false }
}

async function registerAccelerator() {
  if (!selected.value) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/nodes/${selected.value.nodeId}/accelerators`, props.auth, { method: 'POST', body: JSON.stringify({ vendor: accelerator.value.vendor || null, productName: accelerator.value.productName || null, deviceIndex: accelerator.value.deviceIndex, deviceUuid: accelerator.value.deviceUuid || null, memoryTotalMb: accelerator.value.memoryTotalMb || null, driverVersion: accelerator.value.driverVersion || null, metadataJson: '{}' }) })
    acceleratorOpen.value = false
    message.value = 'Accelerator 정보를 등록했습니다.'
    await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Accelerator 등록에 실패했습니다.' }
  finally { busy.value = false }
}

function openAccelerator() {
  accelerator.value = { vendor: '', productName: '', deviceIndex: accelerators.value.length, deviceUuid: '', memoryTotalMb: null, driverVersion: '' }
  acceleratorOpen.value = true
}

function openDeployment(deployment: Deployment) { editing.value = { ...deployment }; deploymentOpen.value = true }
async function saveDeployment() {
  if (!editing.value) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/model-deployments/${editing.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify({ compatibilityKey: editing.value.compatibilityKey, enabled: editing.value.enabled, maxConcurrency: editing.value.maxConcurrency, capabilityOverridesJson: editing.value.capabilityOverridesJson || '[]' }) })
    deploymentOpen.value = false
    message.value = 'Deployment 운영 설정을 저장했습니다.'
    if (selected.value) await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Deployment 설정 저장에 실패했습니다.' }
  finally { busy.value = false }
}

watch(() => props.organizationId, () => { selected.value = null; load() })
onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">COMPUTE FABRIC</p><h1>인프라와 모델 운영</h1><p>GPU 종류와 관계없이 Tailscale에서 접근 가능한 LM Studio Runtime을 연결하고, Endpoint·모델·라우팅 준비 상태를 관리합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load()">새로고침</button><button class="primary-button" :disabled="!organizationId" @click="createOpen = true">+ Runtime 연결</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="split-layout infrastructure-layout">
      <article class="surface-card list-panel">
        <header class="card-header"><div><span class="card-kicker">RUNTIME ENDPOINTS</span><h2>연결된 Runtime</h2></div><span class="count-badge">{{ endpoints.length }}</span></header>
        <div v-if="endpoints.length" class="endpoint-list"><article v-for="endpoint in endpoints" :key="endpoint.id" class="endpoint-list-row" :class="{ active: selected?.id === endpoint.id }"><button class="endpoint-select" @click="selectEndpoint(endpoint)"><span class="node-light" :class="healthClass(endpoint.healthStatus)"></span><span><strong>{{ endpointLabel(endpoint) }}</strong><small>{{ endpoint.runtimeType }} · {{ endpoint.healthStatus }}</small></span><b>›</b></button><button class="endpoint-settings-button" :aria-label="`${endpointLabel(endpoint)} 설정`" :disabled="busy" @click="openEndpointSettings(endpoint)">⚙</button></article></div>
        <div v-else class="empty-state"><span>◌</span><h3>등록된 Runtime이 없습니다</h3><p>Tailscale에서 접근 가능한 LM Studio URL을 연결하세요.</p><button class="text-button" :disabled="!organizationId" @click="createOpen = true">첫 Runtime 연결</button></div>
      </article>
      <article class="surface-card detail-panel">
        <template v-if="selected">
          <header class="detail-header"><div><span class="status-chip" :class="healthClass(selected.healthStatus)"><i></i>{{ selected.healthStatus }}</span><h2>{{ selected.baseUrl }}</h2><p>{{ selected.runtimeType }} · 마지막 확인 {{ selected.lastCheckedAt ? new Date(selected.lastCheckedAt).toLocaleString() : '기록 없음' }}</p></div><div class="action-menu"><button class="secondary-button" :disabled="busy" @click="openEndpointSettings()">Endpoint 설정</button><button class="secondary-button" :disabled="busy" @click="action('probe')">연결 확인</button><button class="secondary-button" :disabled="busy" @click="action('sync-models')">모델 동기화</button><button class="ghost-button" :disabled="busy" @click="action(selected.healthStatus === 'DRAINING' ? 'resume' : 'drain')">{{ selected.healthStatus === 'DRAINING' ? '복구 재투입' : 'Drain' }}</button></div></header>
          <div class="section-divider"><span>ACCELERATOR INVENTORY</span><button class="text-button" @click="openAccelerator">+ 장치 등록</button></div>
          <div v-if="accelerators.length" class="hardware-strip"><article v-for="device in accelerators" :key="device.id" class="accelerator-card"><span class="accelerator-index">{{ device.deviceIndex }}</span><div><span class="card-kicker">{{ device.vendor || 'UNKNOWN VENDOR' }}</span><h3>{{ device.productName || '이름 없는 Accelerator' }}</h3><p>{{ device.memoryTotalMb ? `${device.memoryTotalMb.toLocaleString()} MB` : '메모리 정보 없음' }} · {{ device.driverVersion || '드라이버 정보 없음' }}</p></div><small class="mono">{{ device.deviceUuid || device.id }}</small></article></div>
          <div v-else class="hardware-empty"><span>GPU 정보는 선택 항목입니다.</span><p>Endpoint와 모델 운영은 GPU 메타데이터 없이도 정상 동작합니다.</p><button class="text-button" @click="openAccelerator">인벤토리 추가</button></div>
          <div class="section-divider"><span>DISCOVERED MODEL DEPLOYMENTS</span><b>{{ deployments.length }}</b></div>
          <div v-if="deployments.length" class="deployment-grid"><button v-for="deployment in deployments" :key="deployment.id" class="deployment-card" @click="openDeployment(deployment)"><div class="deployment-top"><span class="model-cube">◈</span><span class="status-chip tiny" :class="healthClass(deployment.healthStatus)">{{ deployment.loaded ? 'LOADED' : deployment.healthStatus }}</span></div><strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small><dl><div><dt>Context</dt><dd>{{ deployment.contextLength?.toLocaleString() ?? '-' }}</dd></div><div><dt>동시 요청</dt><dd>{{ deployment.maxConcurrency }}</dd></div><div><dt>양자화</dt><dd>{{ deployment.quantization ?? '-' }}</dd></div></dl><span class="capability-line">{{ deployment.capabilitiesJson }}</span></button></div>
          <div v-else class="empty-state"><span>◈</span><h3>동기화된 모델이 없습니다</h3><p>LM Studio에서 모델을 준비한 뒤 ‘모델 동기화’를 실행하세요.</p></div>
          <ModelOperationsPanel :endpoint="selected" :deployments="deployments" :auth="auth" @changed="load(selected?.id)" />
        </template>
        <div v-else class="empty-state centered"><span>◌</span><h3>Runtime을 선택하세요</h3><p>선택한 Runtime의 상태, Endpoint 설정, GPU 인벤토리와 모델 작업을 확인할 수 있습니다.</p></div>
      </article>
    </div>

    <BaseModal :open="createOpen" title="LM Studio Runtime 연결" description="GPU 사양이 아닌, Tailscale에서 접근 가능한 LM Studio Endpoint를 등록합니다." @close="createOpen = false"><div class="modal-form"><div class="form-grid"><label class="field">노드 이름<input v-model.trim="runtime.nodeName" required placeholder="gpu-node-01" /></label><label class="field">Endpoint URL<input v-model.trim="runtime.baseUrl" required placeholder="http://100.x.x.x:1234" /></label></div><label class="field">설명<textarea v-model="runtime.description" rows="3" placeholder="위치, 담당 팀, 운영 목적"></textarea></label><label class="field">LM Studio API Token<input v-model="runtime.apiToken" type="password" autocomplete="off" placeholder="인증을 사용하지 않으면 비워 둘 수 있습니다" /></label></div><template #footer><button class="secondary-button" @click="createOpen = false">취소</button><button class="primary-button" :disabled="busy || !runtime.nodeName || !runtime.baseUrl" @click="createRuntime">Runtime 등록</button></template></BaseModal>

    <BaseModal :open="endpointSettingsOpen" title="Endpoint 설정" description="연결 주소와 사용 여부를 관리합니다. API Token 원문은 다시 표시되지 않습니다." @close="endpointSettingsOpen = false"><div v-if="endpointDetail" class="modal-form"><div class="endpoint-info"><div><span>노드</span><strong>{{ endpointDetail.nodeName }}</strong><small>{{ endpointDetail.nodeDescription || '설명 없음' }}</small></div><div><span>Runtime</span><strong>{{ endpointDetail.runtimeType }}</strong><small>{{ endpointDetail.healthStatus }} · {{ endpointDetail.lastCheckedAt ? new Date(endpointDetail.lastCheckedAt).toLocaleString() : '확인 기록 없음' }}</small></div><div><span>LM Studio Token</span><strong>{{ endpointDetail.apiTokenConfigured ? '설정됨' : '설정 안 됨' }}</strong><small>보안을 위해 원문은 표시하지 않습니다.</small></div></div><label class="field">Endpoint URL<input v-model.trim="endpointForm.baseUrl" required placeholder="http://100.x.x.x:1234" /></label><label class="toggle-field"><span>Endpoint 활성화<small>비활성화하면 신규 라우팅 후보에서 제외됩니다.</small></span><input v-model="endpointForm.enabled" type="checkbox" /></label><label class="field">새 LM Studio API Token (선택)<input v-model="endpointForm.apiToken" :disabled="endpointForm.clearApiToken" type="password" autocomplete="new-password" placeholder="입력할 때만 기존 Token을 교체합니다" /></label><label class="toggle-field"><span>저장된 API Token 제거<small>Runtime 인증을 사용하지 않을 때만 선택하세요.</small></span><input v-model="endpointForm.clearApiToken" type="checkbox" /></label><button class="danger-text-button" type="button" :disabled="busy" @click="endpointDeleteOpen = true">이 Endpoint 삭제</button></div><template #footer><button class="secondary-button" @click="endpointSettingsOpen = false">취소</button><button class="primary-button" :disabled="busy || !endpointForm.baseUrl" @click="saveEndpointSettings">설정 저장</button></template></BaseModal>

    <BaseModal :open="endpointDeleteOpen" title="Endpoint 삭제" description="삭제된 Endpoint는 화면과 라우팅에서 제거되지만, 과거 요청·장애·감사 기록은 보존됩니다." size="sm" @close="endpointDeleteOpen = false"><div v-if="endpointDetail" class="delete-confirm"><span>삭제 대상</span><strong>{{ endpointDetail.baseUrl }}</strong><p>서비스 Target이 하나라도 연결되어 있으면 삭제할 수 없습니다. 먼저 LLM 서비스에서 Target을 다른 Deployment로 변경하거나 제거하세요.</p></div><template #footer><button class="secondary-button" @click="endpointDeleteOpen = false">취소</button><button class="danger-button" :disabled="busy" @click="archiveEndpoint">Endpoint 삭제</button></template></BaseModal>

    <BaseModal :open="acceleratorOpen" title="Accelerator 장치 등록" description="GPU 이름은 고정 목록이 아닌 자유 메타데이터입니다." @close="acceleratorOpen = false"><div class="modal-form"><div class="form-grid three"><label class="field">제조사<input v-model.trim="accelerator.vendor" placeholder="NVIDIA" /></label><label class="field">제품명<input v-model.trim="accelerator.productName" placeholder="RTX 5090" /></label><label class="field">장치 번호<input v-model.number="accelerator.deviceIndex" type="number" min="0" /></label></div><div class="form-grid"><label class="field">VRAM (MB)<input v-model.number="accelerator.memoryTotalMb" type="number" min="1" placeholder="선택" /></label><label class="field">드라이버 버전<input v-model.trim="accelerator.driverVersion" placeholder="선택" /></label></div><label class="field">Device UUID<input v-model.trim="accelerator.deviceUuid" placeholder="GPU-... (선택)" /></label></div><template #footer><button class="secondary-button" @click="acceleratorOpen = false">취소</button><button class="primary-button" :disabled="busy || accelerator.deviceIndex < 0" @click="registerAccelerator">장치 등록</button></template></BaseModal>

    <BaseModal :open="deploymentOpen" title="Deployment 운영 설정" description="자동으로 발견한 모델 정보는 유지하고, 라우팅에 필요한 운영 설정만 변경합니다." @close="deploymentOpen = false"><div v-if="editing" class="modal-form"><label class="field">호환 키<input v-model="editing.compatibilityKey" /></label><div class="form-grid"><label class="field">최대 동시 요청<input v-model.number="editing.maxConcurrency" type="number" min="1" /></label><label class="toggle-field"><span>라우팅 활성화<small>신규 요청 후보에 포함</small></span><input v-model="editing.enabled" type="checkbox" /></label></div><label class="field">관리자 검증 Capability JSON<textarea v-model="editing.capabilityOverridesJson" rows="4" placeholder='["STRUCTURED_OUTPUT"]'></textarea></label></div><template #footer><button class="secondary-button" @click="deploymentOpen = false">취소</button><button class="primary-button" :disabled="busy" @click="saveDeployment">설정 저장</button></template></BaseModal>
  </section>
</template>

<style scoped>
.endpoint-list { padding: 8px; display: grid; gap: 4px; }
.endpoint-list-row { display: grid; grid-template-columns: 1fr 37px; gap: 3px; align-items: stretch; border: 1px solid transparent; border-radius: 11px; }
.endpoint-list-row:hover, .endpoint-list-row.active { border-color: var(--accent-border); background: var(--accent-dim); }
.endpoint-select { min-width: 0; min-height: 59px; padding: 10px; display: grid; grid-template-columns: auto 1fr auto; gap: 11px; align-items: center; border: 0; background: transparent; color: var(--muted); text-align: left; }
.endpoint-list-row.active .endpoint-select { color: var(--text); }
.endpoint-select > span:nth-child(2) { min-width: 0; display: grid; gap: 5px; }
.endpoint-select strong, .endpoint-select small { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.endpoint-select strong { font-size: 11px; }.endpoint-select small { color: var(--muted); font-size: 8px; }.endpoint-select b { color: var(--faint); }
.endpoint-settings-button { width: 34px; height: 34px; margin: auto 3px auto 0; border: 1px solid var(--border); border-radius: 9px; background: var(--surface); color: var(--muted); }
.endpoint-settings-button:hover { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.endpoint-info { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }.endpoint-info div { display: grid; gap: 3px; }.endpoint-info span, .endpoint-info small { color: var(--muted); font-size: 9px; }.endpoint-info strong { font-size: 11px; }
.danger-text-button { justify-self: start; padding: 4px 0; border: 0; background: transparent; color: var(--danger); font-size: 11px; font-weight: 800; }.danger-button { min-height: 40px; padding: 0 15px; border: 1px solid color-mix(in srgb, var(--danger) 42%, transparent); border-radius: 11px; background: var(--danger-dim); color: var(--danger); font-size: 12px; font-weight: 800; }
.delete-confirm { display: grid; gap: 9px; padding: 14px; border: 1px solid color-mix(in srgb, var(--danger) 35%, transparent); border-radius: 12px; background: var(--danger-dim); }.delete-confirm span { color: var(--danger); font-size: 9px; font-weight: 800; letter-spacing: .12em; }.delete-confirm strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }.delete-confirm p { margin: 0; color: var(--text-soft); font-size: 10px; line-height: 1.6; }
</style>
