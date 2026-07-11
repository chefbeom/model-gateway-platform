<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
type Accelerator = { id: string; nodeId: string; vendor?: string; productName?: string; deviceIndex: number; deviceUuid?: string; memoryTotalMb?: number; driverVersion?: string; metadataJson?: string; lastSeenAt: string }
const endpoints = ref<Endpoint[]>([])
const selected = ref<Endpoint | null>(null)
const deployments = ref<Deployment[]>([])
const accelerators = ref<Accelerator[]>([])
const busy = ref(false)
const message = ref('')
const createOpen = ref(false)
const editOpen = ref(false)
const deviceOpen = ref(false)
const editing = ref<Deployment | null>(null)
const form = ref({ nodeName: '', description: '', baseUrl: 'http://gpu-node-01:1234', apiToken: '' })
const deviceForm = ref({ vendor: '', productName: '', deviceIndex: 0, deviceUuid: '', memoryTotalMb: null as number | null, driverVersion: '', metadataJson: '{}' })

async function load() {
  busy.value = true; message.value = ''
  try {
    endpoints.value = await adminFetch<Endpoint[]>('/api/admin/runtime-endpoints', props.auth)
    if (selected.value && !endpoints.value.some(item => item.id === selected.value?.id)) selected.value = null
    if (!selected.value && endpoints.value.length) selected.value = endpoints.value[0]
    if (selected.value) await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : '런타임을 불러오지 못했습니다.' }
  finally { busy.value = false }
}
async function selectEndpoint(endpoint: Endpoint) {
  selected.value = endpoint
  const [runtimeDeployments, nodeAccelerators] = await Promise.all([
    adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpoint.id}/deployments`, props.auth),
    adminFetch<Accelerator[]>(`/api/admin/nodes/${endpoint.nodeId}/accelerators`, props.auth)
  ])
  deployments.value = runtimeDeployments; accelerators.value = nodeAccelerators
}
async function createRuntime() {
  if (!props.organizationId) return
  busy.value = true
  try {
    const node = await adminFetch<{ id: string }>('/api/admin/nodes', props.auth, { method: 'POST', body: JSON.stringify({ organizationId: props.organizationId, name: form.value.nodeName, description: form.value.description || null, connectionMode: 'DIRECT', labelsJson: '{"source":"console"}' }) })
    const endpoint = await adminFetch<Endpoint>('/api/admin/runtime-endpoints', props.auth, { method: 'POST', body: JSON.stringify({ nodeId: node.id, runtimeType: 'LM_STUDIO', baseUrl: form.value.baseUrl, apiToken: form.value.apiToken || null }) })
    form.value.apiToken = ''; createOpen.value = false; message.value = '런타임을 등록했습니다. 연결 확인 후 모델을 동기화하세요.'
    await load(); await selectEndpoint(endpoint)
  } catch (error) { message.value = error instanceof Error ? error.message : '런타임 등록에 실패했습니다.' }
  finally { busy.value = false }
}
async function action(endpoint: Endpoint, name: 'probe' | 'sync-models' | 'drain' | 'resume') {
  busy.value = true
  try {
    const result = await adminFetch<unknown>(`/api/admin/runtime-endpoints/${endpoint.id}/${name}`, props.auth, { method: 'POST' })
    message.value = name === 'probe' ? `연결 확인 완료: ${Array.isArray((result as { modelIds?: string[] }).modelIds) ? (result as { modelIds: string[] }).modelIds.length : 0}개 모델 발견` : name === 'sync-models' ? '모델 목록을 동기화했습니다.' : name === 'drain' ? '새 요청 수신을 중지했습니다.' : '복구 및 워밍업을 요청했습니다.'
    await load()
  } catch (error) { message.value = error instanceof Error ? error.message : '작업을 완료하지 못했습니다.' }
  finally { busy.value = false }
}
async function registerAccelerator() {
  if (!selected.value) return
  busy.value = true
  try {
    await adminFetch<Accelerator>(`/api/admin/nodes/${selected.value.nodeId}/accelerators`, props.auth, { method: 'POST', body: JSON.stringify({ vendor: deviceForm.value.vendor || null, productName: deviceForm.value.productName || null, deviceIndex: deviceForm.value.deviceIndex, deviceUuid: deviceForm.value.deviceUuid || null, memoryTotalMb: Number(deviceForm.value.memoryTotalMb) > 0 ? Number(deviceForm.value.memoryTotalMb) : null, driverVersion: deviceForm.value.driverVersion || null, metadataJson: deviceForm.value.metadataJson || '{}' }) })
    deviceOpen.value = false; deviceForm.value = { vendor: '', productName: '', deviceIndex: accelerators.value.length, deviceUuid: '', memoryTotalMb: null, driverVersion: '', metadataJson: '{}' }; message.value = '하드웨어 장치를 노드 인벤토리에 등록했습니다.'; await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : '하드웨어 장치 등록 실패' }
  finally { busy.value = false }
}
function openDeviceModal() { deviceForm.value.deviceIndex = accelerators.value.length; deviceOpen.value = true }
function edit(deployment: Deployment) { editing.value = { ...deployment }; editOpen.value = true }
async function saveDeployment() {
  if (!editing.value) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/model-deployments/${editing.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify({ compatibilityKey: editing.value.compatibilityKey, enabled: editing.value.enabled, maxConcurrency: editing.value.maxConcurrency, capabilityOverridesJson: editing.value.capabilityOverridesJson || '[]' }) })
    editOpen.value = false; message.value = 'Deployment 정책을 저장했습니다.'
    if (selected.value) await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Deployment 저장에 실패했습니다.' }
  finally { busy.value = false }
}
function healthClass(value: string) { return value.toLowerCase() }
watch(() => props.organizationId, () => { selected.value = null; load() })
onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">COMPUTE FABRIC</p><h1>인프라</h1><p>GPU 종류와 무관하게 LM Studio Endpoint와 배포 모델을 관리합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load">↻ 새로고침</button><button class="primary-button" :disabled="!organizationId" @click="createOpen = true">＋ 런타임 연결</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="split-layout infrastructure-layout">
      <article class="surface-card list-panel">
        <header class="card-header"><div><span class="card-kicker">RUNTIME ENDPOINTS</span><h2>연결된 런타임</h2></div><span class="count-badge">{{ endpoints.length }}</span></header>
        <div v-if="endpoints.length" class="entity-list"><button v-for="endpoint in endpoints" :key="endpoint.id" :class="{ active: selected?.id === endpoint.id }" @click="selectEndpoint(endpoint)"><span class="node-light" :class="healthClass(endpoint.healthStatus)"></span><span><strong>{{ endpoint.baseUrl.replace(/^https?:\/\//, '') }}</strong><small>{{ endpoint.runtimeType }} · {{ endpoint.healthStatus }}</small></span><b>›</b></button></div>
        <div v-else class="empty-state"><span>⌁</span><h3>아직 런타임이 없습니다</h3><p>Tailnet에서 접근 가능한 LM Studio를 연결하세요.</p><button class="text-button" :disabled="!organizationId" @click="createOpen = true">첫 런타임 연결</button></div>
      </article>
      <article class="surface-card detail-panel">
        <template v-if="selected">
          <header class="detail-header"><div><span class="status-chip" :class="healthClass(selected.healthStatus)"><i></i>{{ selected.healthStatus }}</span><h2>{{ selected.baseUrl }}</h2><p>{{ selected.runtimeType }} · 마지막 점검 {{ selected.lastCheckedAt ? new Date(selected.lastCheckedAt).toLocaleString() : '기록 없음' }}</p></div><div class="action-menu"><button class="secondary-button" :disabled="busy" @click="action(selected, 'probe')">Probe</button><button class="secondary-button" :disabled="busy" @click="action(selected, 'sync-models')">모델 동기화</button><button class="ghost-button" :disabled="busy" @click="action(selected, selected.healthStatus === 'DRAINING' ? 'resume' : 'drain')">{{ selected.healthStatus === 'DRAINING' ? '복구' : 'Drain' }}</button></div></header>
          <div class="section-divider"><span>ACCELERATOR INVENTORY</span><button class="text-button" @click="openDeviceModal">＋ 장치 등록</button></div>
          <div v-if="accelerators.length" class="hardware-strip"><article v-for="device in accelerators" :key="device.id" class="accelerator-card"><span class="accelerator-index">{{ device.deviceIndex }}</span><div><span class="card-kicker">{{ device.vendor || 'UNKNOWN VENDOR' }}</span><h3>{{ device.productName || '이름 없는 Accelerator' }}</h3><p>{{ device.memoryTotalMb ? `${device.memoryTotalMb.toLocaleString()} MB` : '메모리 정보 없음' }} · {{ device.driverVersion || '드라이버 정보 없음' }}</p></div><small class="mono">{{ device.deviceUuid || device.id }}</small></article></div>
          <div v-else class="hardware-empty"><span>GPU 정보는 선택 사항입니다.</span><p>Endpoint와 모델은 하드웨어 정보 없이도 정상 작동합니다.</p><button class="text-button" @click="openDeviceModal">인벤토리 추가</button></div>
          <div class="section-divider"><span>MODEL DEPLOYMENTS</span><b>{{ deployments.length }}</b></div>
          <div v-if="deployments.length" class="deployment-grid"><button v-for="deployment in deployments" :key="deployment.id" class="deployment-card" @click="edit(deployment)"><div class="deployment-top"><span class="model-cube">◇</span><span class="status-chip tiny" :class="healthClass(deployment.healthStatus)">{{ deployment.healthStatus }}</span></div><strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small><dl><div><dt>Context</dt><dd>{{ deployment.contextLength?.toLocaleString() ?? '-' }}</dd></div><div><dt>동시 요청</dt><dd>{{ deployment.maxConcurrency }}</dd></div><div><dt>양자화</dt><dd>{{ deployment.quantization ?? '-' }}</dd></div></dl><span class="capability-line">{{ deployment.capabilitiesJson }}</span></button></div>
          <div v-else class="empty-state"><span>◇</span><h3>동기화된 모델이 없습니다</h3><p>LM Studio에서 모델을 로드한 뒤 모델 동기화를 실행하세요.</p></div>
        </template>
        <div v-else class="empty-state centered"><span>⌁</span><h3>Endpoint를 선택하세요</h3><p>세부 상태와 배포 모델을 확인할 수 있습니다.</p></div>
      </article>
    </div>
    <BaseModal :open="createOpen" title="LM Studio 런타임 연결" description="GPU 정보 대신 Tailnet에서 접근 가능한 Endpoint를 등록합니다." @close="createOpen = false">
      <form class="modal-form" @submit.prevent="createRuntime"><div class="form-grid"><label class="field">노드 이름<input v-model.trim="form.nodeName" required placeholder="gpu-node-01" /></label><label class="field">Endpoint URL<input v-model.trim="form.baseUrl" required placeholder="http://gpu-node-01:1234" /></label></div><label class="field">설명<textarea v-model="form.description" rows="3" placeholder="위치, 담당 팀, 용도 등"></textarea></label><label class="field">LM Studio API Token<input v-model="form.apiToken" type="password" autocomplete="off" placeholder="인증을 사용하지 않는 테스트 환경은 비워 둘 수 있습니다" /></label></form>
      <template #footer><button class="secondary-button" @click="createOpen = false">취소</button><button class="primary-button" :disabled="busy || !form.nodeName || !form.baseUrl" @click="createRuntime">런타임 등록</button></template>
    </BaseModal>
    <BaseModal :open="deviceOpen" title="Accelerator 장치 등록" description="GPU 이름은 고정 목록이 아닌 자유 메타데이터로 저장됩니다." @close="deviceOpen = false"><div class="modal-form"><div class="form-grid three"><label class="field">제조사<input v-model.trim="deviceForm.vendor" placeholder="NVIDIA, AMD, Apple…" /></label><label class="field">제품명<input v-model.trim="deviceForm.productName" placeholder="FutureGPU X1000" /></label><label class="field">장치 인덱스<input v-model.number="deviceForm.deviceIndex" type="number" min="0" /></label></div><div class="form-grid"><label class="field">VRAM (MB)<input v-model.number="deviceForm.memoryTotalMb" type="number" min="1" placeholder="선택" /></label><label class="field">드라이버 버전<input v-model.trim="deviceForm.driverVersion" placeholder="선택" /></label></div><label class="field">Device UUID<input v-model.trim="deviceForm.deviceUuid" placeholder="GPU-… 또는 비워두기" /></label><label class="field">추가 메타데이터 JSON<textarea v-model="deviceForm.metadataJson" rows="3" placeholder='{"location":"rack-a"}'></textarea></label></div><template #footer><button class="secondary-button" @click="deviceOpen = false">취소</button><button class="primary-button" :disabled="busy || deviceForm.deviceIndex < 0" @click="registerAccelerator">장치 등록</button></template></BaseModal>
    <BaseModal :open="editOpen" title="Deployment 정책" description="자동 발견 정보는 유지하고 운영 정책만 변경합니다." @close="editOpen = false">
      <div v-if="editing" class="modal-form"><label class="field">호환 키<input v-model="editing.compatibilityKey" /></label><div class="form-grid"><label class="field">최대 동시 요청<input v-model.number="editing.maxConcurrency" type="number" min="1" /></label><label class="toggle-field"><span>라우팅 활성화<small>신규 요청 후보에 포함</small></span><input v-model="editing.enabled" type="checkbox" /></label></div><label class="field">관리자 검증 Capability<textarea v-model="editing.capabilityOverridesJson" rows="4" placeholder='["STRUCTURED_OUTPUT"]'></textarea></label></div>
      <template #footer><button class="secondary-button" @click="editOpen = false">취소</button><button class="primary-button" :disabled="busy" @click="saveDeployment">정책 저장</button></template>
    </BaseModal>
  </section>
</template>
