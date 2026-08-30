<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'

const props = defineProps<{ endpoint: Endpoint; deployments: Deployment[]; auth: AdminAuth }>()
const emit = defineEmits<{ changed: [] }>()

type Profile = { id: string; name: string; modelKey: string; configJson: string }
type Operation = { id: string; modelKey: string; operationType: string; status: string; message?: string | null; resultJson?: string | null; createdAt: string }
type Preflight = {
  modelKey: string
  displayName: string
  modelSizeBytes: number
  heuristicMemoryBytes: number
  maxContextLength: number
  requestedContextLength: number
  compatible: boolean
  warnings: string[]
  alreadyLoaded: boolean
  variants: string[]
  selectedVariant?: string | null
  requestedVariant?: string | null
  variantAvailable: boolean
  variantSelectionSupported: boolean
  nativeSupportedOptions: string[]
}
type LoadCommand = {
  modelKey: string
  variantKey: string
  contextLength: number | null
  evalBatchSize: number | null
  physicalBatchSize: number | null
  parallel: number | null
  numExperts: number | null
  flashAttention: boolean
  offloadKvCacheToGpu: boolean
  gpuOffloadLayers: number | null
  autoUnloadTtlSeconds: number | null
  apiIdentifier: string
  gpuOffloadMode: string
  gpuOffloadRatio: number | null
  cpuThreadPoolSize: number | null
  unifiedKvCache: boolean | null
  ropeFrequencyBase: number | null
  ropeFrequencyScale: number | null
  keepModelInMemory: boolean | null
  tryMmap: boolean | null
  seed: number | null
  kCacheQuantizationType: string
  vCacheQuantizationType: string
}
type ModelMetadata = {
  variants?: unknown
  selected_variant?: unknown
  loaded_instances?: unknown
  capabilities?: unknown
  quantization?: { name?: unknown; bits_per_weight?: unknown } | null
  size_bytes?: unknown
}
type LoadedConfig = Record<string, unknown>

const profiles = ref<Profile[]>([])
const operations = ref<Operation[]>([])
const busy = ref(false)
const message = ref('')
const loadOpen = ref(false)
const downloadOpen = ref(false)
const preflight = ref<Preflight | null>(null)
const profileName = ref('')
const download = ref({ modelKey: '', quantization: '' })

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
}
function parseMetadata(deployment?: Deployment | null): ModelMetadata {
  if (!deployment?.metadataJson) return {}
  try { return JSON.parse(deployment.metadataJson) as ModelMetadata } catch { return {} }
}
function loadedConfig(deployment?: Deployment | null): LoadedConfig {
  const metadata = parseMetadata(deployment)
  if (!Array.isArray(metadata.loaded_instances)) return {}
  const instances = metadata.loaded_instances as Array<{ id?: unknown; config?: unknown }>
  const selected = instances.find(item => item.id === deployment?.providerModelId) ?? instances[0]
  return selected?.config && typeof selected.config === 'object' ? selected.config as LoadedConfig : {}
}
function variantsFor(deployment?: Deployment | null): string[] {
  const values = parseMetadata(deployment).variants
  if (!Array.isArray(values)) return []
  return Array.from(new Set(values.filter((value): value is string => typeof value === 'string' && value.trim().length > 0)))
}
function selectedVariantFor(deployment?: Deployment | null): string {
  const value = parseMetadata(deployment).selected_variant
  return typeof value === 'string' ? value : ''
}
function variantLabel(value: string): string {
  const suffix = value.includes('@') ? value.slice(value.lastIndexOf('@') + 1) : value
  return suffix.toUpperCase()
}
function capabilityList(deployment?: Deployment | null): string[] {
  if (!deployment?.capabilitiesJson) return []
  try {
    const values = JSON.parse(deployment.capabilitiesJson)
    return Array.isArray(values) ? values.filter((value): value is string => typeof value === 'string') : []
  } catch { return [] }
}
function emptyCommand(deployment?: Deployment | null): LoadCommand {
  const config = loadedConfig(deployment)
  const variants = variantsFor(deployment)
  return {
    modelKey: deployment?.providerModelId ?? '',
    variantKey: selectedVariantFor(deployment) || (variants.length === 1 ? variants[0] : ''),
    contextLength: deployment?.contextLength ?? numberOrNull(config.context_length),
    evalBatchSize: numberOrNull(config.eval_batch_size),
    physicalBatchSize: null,
    parallel: numberOrNull(config.parallel),
    numExperts: numberOrNull(config.num_experts),
    flashAttention: typeof config.flash_attention === 'boolean' ? config.flash_attention : true,
    offloadKvCacheToGpu: typeof config.offload_kv_cache_to_gpu === 'boolean' ? config.offload_kv_cache_to_gpu : true,
    gpuOffloadLayers: null,
    autoUnloadTtlSeconds: null,
    apiIdentifier: '',
    gpuOffloadMode: '',
    gpuOffloadRatio: null,
    cpuThreadPoolSize: null,
    unifiedKvCache: null,
    ropeFrequencyBase: null,
    ropeFrequencyScale: null,
    keepModelInMemory: null,
    tryMmap: null,
    seed: null,
    kCacheQuantizationType: '',
    vCacheQuantizationType: ''
  }
}
const command = ref<LoadCommand>(emptyCommand())

const modelOptions = computed(() => props.deployments.map(item => ({
  key: item.providerModelId,
  label: `${item.displayName || item.providerModelId}${item.quantization ? ` · ${item.quantization}` : ''}`,
  loaded: item.loaded
})))
const selectedDeployment = computed(() => props.deployments.find(item => item.providerModelId === command.value.modelKey) ?? null)
const availableVariants = computed(() => variantsFor(selectedDeployment.value))
const activeVariant = computed(() => selectedVariantFor(selectedDeployment.value))
const variantMismatch = computed(() => availableVariants.value.length > 1 && Boolean(command.value.variantKey) && Boolean(activeVariant.value) && command.value.variantKey !== activeVariant.value)
const selectedCapabilities = computed(() => capabilityList(selectedDeployment.value))

function bytes(value: number) {
  if (!value) return '확인 불가'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']; let size = value; let unit = 0
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++ }
  return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}`
}
function resetCommand(modelKey?: string) {
  const deployment = props.deployments.find(item => item.providerModelId === modelKey) ?? props.deployments[0]
  command.value = emptyCommand(deployment)
  preflight.value = null
  profileName.value = ''
}
function deploymentVariants(deployment: Deployment): string {
  const variants = variantsFor(deployment)
  if (!variants.length) return deployment.quantization || '기본 변형'
  const active = selectedVariantFor(deployment)
  return `${variants.length}개 변형${active ? ` · 현재 ${variantLabel(active)}` : ''}`
}
async function refresh() {
  try {
    const [nextProfiles, nextOperations] = await Promise.all([
      adminFetch<Profile[]>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-profiles`, props.auth),
      adminFetch<Operation[]>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations`, props.auth)
    ])
    profiles.value = nextProfiles; operations.value = nextOperations
  } catch (error) { message.value = error instanceof Error ? error.message : '모델 작업 정보를 불러오지 못했습니다.' }
}
async function inspect() {
  busy.value = true; message.value = ''
  try { preflight.value = await adminFetch<Preflight>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/preflight`, props.auth, { method: 'POST', body: JSON.stringify(command.value) }) }
  catch (error) { message.value = error instanceof Error ? error.message : '사전 점검에 실패했습니다.' }
  finally { busy.value = false }
}
async function runLoad() {
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/load`, props.auth, { method: 'POST', body: JSON.stringify(command.value) })
    message.value = operation.message ?? '로드 작업을 요청했습니다.'; loadOpen.value = false; await refresh(); emit('changed')
  } catch (error) { message.value = error instanceof Error ? error.message : '모델 로드에 실패했습니다.' }
  finally { busy.value = false }
}
async function saveProfile() {
  if (!profileName.value.trim()) { message.value = '프로필 이름을 입력하세요.'; return }
  busy.value = true; message.value = ''
  try {
    await adminFetch<Profile>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-profiles`, props.auth, { method: 'POST', body: JSON.stringify({ name: profileName.value.trim(), command: command.value }) })
    message.value = '모델 변형과 로딩 설정 프로필을 저장했습니다.'; await refresh()
  } catch (error) { message.value = error instanceof Error ? error.message : '프로필 저장에 실패했습니다.' }
  finally { busy.value = false }
}
async function applyProfile(profile: Profile) {
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-profiles/${profile.id}/apply`, props.auth, { method: 'POST' })
    message.value = operation.message ?? `${profile.name} 프로필을 적용했습니다.`; await refresh(); emit('changed')
  } catch (error) { message.value = error instanceof Error ? error.message : '프로필 적용에 실패했습니다.' }
  finally { busy.value = false }
}
async function unload(modelKey: string) {
  if (!window.confirm(`'${modelKey}' 모델을 메모리에서 내리시겠습니까? 진행 중인 요청이 있으면 작업이 보류됩니다.`)) return
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/unload`, props.auth, { method: 'POST', body: JSON.stringify({ modelKey }) })
    message.value = operation.message ?? '언로드 작업을 완료했습니다.'; await refresh(); emit('changed')
  } catch (error) { message.value = error instanceof Error ? error.message : '모델 언로드에 실패했습니다.' }
  finally { busy.value = false }
}
async function requestDownload() {
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/download`, props.auth, { method: 'POST', body: JSON.stringify(download.value) })
    message.value = operation.message ?? '다운로드 작업을 요청했습니다.'; downloadOpen.value = false; await refresh()
  } catch (error) { message.value = error instanceof Error ? error.message : '다운로드 요청에 실패했습니다.' }
  finally { busy.value = false }
}
function openLoad(modelKey?: string) { resetCommand(modelKey); loadOpen.value = true }
watch(command, () => { preflight.value = null }, { deep: true })
watch(() => props.endpoint.id, refresh)
onMounted(refresh)
</script>

<template>
  <section class="model-operations">
    <div class="section-divider"><span>MODEL OPERATIONS</span><div class="operation-actions"><button class="secondary-button" :disabled="busy" @click="openLoad()">모델 로드 설정</button><button class="text-button" :disabled="busy" @click="downloadOpen = true">다운로드 요청</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="operation-grid">
      <article class="operation-card">
        <header><div><span class="card-kicker">SAFE CONTROL</span><h3>메모리 모델 상태</h3></div><button class="text-button" :disabled="busy" @click="refresh">새로고침</button></header>
        <div v-if="deployments.length" class="model-state-list"><div v-for="deployment in deployments" :key="deployment.id" class="model-state-row"><div><strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small><small class="variant-summary">{{ deploymentVariants(deployment) }}</small></div><div class="state-actions"><span class="status-chip tiny" :class="deployment.loaded ? 'healthy' : 'unknown'">{{ deployment.loaded ? 'LOADED' : 'NOT LOADED' }}</span><button class="text-button" :disabled="busy" @click="deployment.loaded ? unload(deployment.providerModelId) : openLoad(deployment.providerModelId)">{{ deployment.loaded ? '언로드' : '로드' }}</button></div></div></div>
        <p v-else class="field-help">먼저 ‘모델 동기화’를 실행하면 이 Runtime에서 발견된 모델을 선택할 수 있습니다.</p>
      </article>
      <article class="operation-card">
        <header><div><span class="card-kicker">CONFIGURATION PROFILES</span><h3>저장된 로드 프로필</h3></div><span class="count-badge">{{ profiles.length }}</span></header>
        <div v-if="profiles.length" class="profile-list"><div v-for="profile in profiles" :key="profile.id"><div><strong>{{ profile.name }}</strong><small class="mono">{{ profile.modelKey }}</small></div><button class="text-button" :disabled="busy" @click="applyProfile(profile)">적용</button></div></div>
        <p v-else class="field-help">모델 변형과 로딩 설정을 프로필로 저장할 수 있습니다.</p>
      </article>
    </div>
    <article class="agentless-note"><strong>네이티브 REST 적용 범위</strong><span>Context Length, Evaluation Batch Size, Flash Attention, MoE Expert 수, KV Cache GPU Offload는 LM Studio native v1 API로 적용합니다. GPU 비율, TTL과 나머지 고급 항목은 프로필에 저장하고 Node Agent·CLI·SDK 연결이 준비되면 적용할 수 있도록 상태를 표시합니다.</span></article>
    <article class="operation-card operation-history"><header><div><span class="card-kicker">AUDIT TRAIL</span><h3>최근 모델 작업</h3></div></header><div v-if="operations.length" class="history-list"><div v-for="operation in operations.slice(0, 6)" :key="operation.id"><span class="status-chip tiny" :class="operation.status === 'SUCCEEDED' ? 'healthy' : operation.status === 'FAILED' ? 'unhealthy' : 'suspect'">{{ operation.status }}</span><strong>{{ operation.operationType }} · {{ operation.modelKey }}</strong><small>{{ operation.message || '처리 중' }} · {{ new Date(operation.createdAt).toLocaleString() }}</small></div></div><p v-else class="field-help">아직 기록된 모델 작업이 없습니다.</p></article>

    <BaseModal :open="loadOpen" title="LM Studio 모델 로드 설정" description="모델 파일 변형과 LM Studio 로딩 옵션을 한 곳에서 관리합니다. 적용되지 않는 항목은 사전 점검에서 명확히 안내합니다." size="lg" @close="loadOpen = false">
      <div class="modal-form load-settings-form">
        <div class="settings-callout"><strong>모델 변형과 유효 설정을 확인하세요.</strong><span>Q4/Q8은 같은 모델의 다른 파일입니다. 현재 LM Studio가 선택한 변형과 다르면 native REST만으로 변경할 수 없으므로 적용 전에 경고가 표시됩니다.</span></div>
        <label class="field">모델<select v-model="command.modelKey" required @change="preflight = null"><option disabled value="">동기화된 모델 선택</option><option v-for="option in modelOptions" :key="option.key" :value="option.key">{{ option.label }} {{ option.loaded ? '(로드됨)' : '' }}</option></select></label>
        <div v-if="availableVariants.length" class="variant-picker"><label class="field">모델 파일 / 양자화 변형<select v-model="command.variantKey" @change="preflight = null"><option v-for="variant in availableVariants" :key="variant" :value="variant">{{ variantLabel(variant) }} · {{ variant }}</option></select><small class="field-help">현재 LM Studio 선택: {{ activeVariant ? variantLabel(activeVariant) : '확인되지 않음' }}</small></label><div class="variant-list"><span v-for="variant in availableVariants" :key="`${variant}-chip`" class="variant-chip" :class="{ active: variant === activeVariant, selected: variant === command.variantKey }">{{ variantLabel(variant) }}<b v-if="variant === activeVariant">현재</b></span></div></div>
        <div v-if="selectedCapabilities.length" class="capability-tags"><span v-for="capability in selectedCapabilities" :key="capability" class="capability-tag">{{ capability }}</span></div>
        <p v-if="variantMismatch" class="settings-warning">선택한 변형은 {{ variantLabel(command.variantKey) }}이지만 LM Studio의 현재 선택은 {{ variantLabel(activeVariant) }}입니다. LM Studio에서 변형을 먼저 선택하고 모델 동기화 후 다시 점검하세요.</p>
        <label class="field">프로필 이름<input v-model.trim="profileName" maxlength="120" placeholder="예: gemma-q8-32k" /><small class="field-help">선택: 현재 모델 변형과 로딩 설정을 다시 적용할 이름입니다.</small></label>
        <div class="form-grid"><label class="field">컨텍스트 길이<input v-model.number="command.contextLength" type="number" min="1" placeholder="예: 32768" /><small class="field-help">최대 컨텍스트가 클수록 더 많은 메모리가 필요합니다.</small></label><label class="field">Evaluation Batch Size<input v-model.number="command.evalBatchSize" type="number" min="1" placeholder="예: 512" /><small class="field-help">입력 토큰을 한 번에 처리하는 크기입니다. Native REST 적용.</small></label></div>
        <div class="form-grid three"><label class="field">Physical Batch Size<input v-model.number="command.physicalBatchSize" type="number" min="1" placeholder="예: 512" /><small class="field-help">프로필 저장 가능 · native REST는 미지원</small></label><label class="field">Max Concurrent Predictions<input v-model.number="command.parallel" type="number" min="1" placeholder="예: 4" /><small class="field-help">프로필 저장 가능 · native REST는 미지원</small></label><label class="field">MoE Expert 수<input v-model.number="command.numExperts" type="number" min="1" placeholder="예: 4" /><small class="field-help">MoE 모델에서만 적용됩니다.</small></label></div>
        <label class="toggle-field"><span>Flash Attention<small>지원 모델에서 메모리 사용량과 생성 속도를 개선합니다. Native REST 적용.</small></span><input v-model="command.flashAttention" type="checkbox" /></label>
        <label class="toggle-field"><span>KV Cache GPU Offload<small>KV 캐시를 GPU 메모리에 둘지 선택합니다. Native REST 적용.</small></span><input v-model="command.offloadKvCacheToGpu" type="checkbox" /></label>
        <details class="advanced-options" open><summary>고급 로딩 옵션 <span>Node Agent·CLI·SDK 필요 항목 포함</span></summary><div class="advanced-grid"><label class="field">API Identifier<input v-model.trim="command.apiIdentifier" maxlength="200" placeholder="예: gemma-4-12b-q8" /><small class="field-help">OpenAI 호환 API에서 노출할 별칭입니다. Native REST는 프로필에 저장합니다.</small></label><label class="field">GPU Offload<select v-model="command.gpuOffloadMode"><option value="">LM Studio 기본값</option><option value="auto">Auto</option><option value="off">Off</option><option value="max">Max</option><option value="custom">비율 직접 입력</option></select><small class="field-help">CLI `lms load --gpu` 기준입니다.</small></label><label class="field">GPU Offload 비율 (0~1)<input v-model.number="command.gpuOffloadRatio" type="number" min="0" max="1" step="0.05" placeholder="예: 0.8" :disabled="command.gpuOffloadMode !== 'custom'" /></label><label class="field">GPU Offload Layers<input v-model.number="command.gpuOffloadLayers" type="number" min="0" placeholder="예: 48" /></label><label class="field">CPU Thread Pool Size<input v-model.number="command.cpuThreadPoolSize" type="number" min="1" placeholder="예: 8" /></label><label class="field">자동 언로드 TTL (초)<input v-model.number="command.autoUnloadTtlSeconds" type="number" min="1" placeholder="예: 3600" /><small class="field-help">유휴 시간 후 모델을 내립니다.</small></label><label class="field">Seed<input v-model.number="command.seed" type="number" min="0" placeholder="예: 42" /></label><label class="field">RoPE Frequency Base<input v-model.number="command.ropeFrequencyBase" type="number" min="0" step="0.01" placeholder="모델 기본값" /></label><label class="field">RoPE Frequency Scale<input v-model.number="command.ropeFrequencyScale" type="number" min="0" step="0.01" placeholder="모델 기본값" /></label><label class="field">K Cache Quantization<select v-model="command.kCacheQuantizationType"><option value="">LM Studio 기본값</option><option value="Q4_0">Q4_0</option><option value="Q8_0">Q8_0</option><option value="F16">F16</option></select></label><label class="field">V Cache Quantization<select v-model="command.vCacheQuantizationType"><option value="">LM Studio 기본값</option><option value="Q4_0">Q4_0</option><option value="Q8_0">Q8_0</option><option value="F16">F16</option></select></label></div><div class="advanced-toggles"><label class="toggle-field"><span>Unified KV Cache<small>Agent·SDK 적용 항목</small></span><input v-model="command.unifiedKvCache" type="checkbox" /></label><label class="toggle-field"><span>Keep Model in Memory<small>Agent·SDK 적용 항목</small></span><input v-model="command.keepModelInMemory" type="checkbox" /></label><label class="toggle-field"><span>mmap 사용<small>Agent·SDK 적용 항목</small></span><input v-model="command.tryMmap" type="checkbox" /></label></div></details>
        <div v-if="preflight" class="preflight-result" :class="{ warning: !preflight.compatible }"><strong>{{ preflight.displayName }}</strong><span>모델 {{ bytes(preflight.modelSizeBytes) }} · 보수적 필요 메모리 약 {{ bytes(preflight.heuristicMemoryBytes) }}</span><span>컨텍스트 {{ preflight.requestedContextLength.toLocaleString() }} / 최대 {{ preflight.maxContextLength ? preflight.maxContextLength.toLocaleString() : '미확인' }}</span><span v-if="preflight.variants.length">변형: {{ preflight.requestedVariant ? variantLabel(preflight.requestedVariant) : '기본' }} · 현재 {{ preflight.selectedVariant ? variantLabel(preflight.selectedVariant) : '미확인' }}</span><small v-for="warning in preflight.warnings" :key="warning">{{ warning }}</small></div>
      </div>
      <template #footer><button class="secondary-button" :disabled="busy || !command.modelKey" @click="inspect">사전 점검</button><button class="secondary-button" :disabled="busy || !profileName" @click="saveProfile">프로필 저장</button><button class="primary-button" :disabled="busy || !command.modelKey || (preflight !== null && !preflight.compatible)" @click="runLoad">Drain 후 로드</button></template>
    </BaseModal>
    <BaseModal :open="downloadOpen" title="LM Studio 모델 다운로드" description="모델 ID와 양자화 형식을 지정합니다. 다운로드가 끝나면 ‘모델 동기화’ 후 로드 설정을 적용하세요." @close="downloadOpen = false"><div class="modal-form"><label class="field">모델 식별자<input v-model.trim="download.modelKey" placeholder="publisher/model-name 또는 Hugging Face URL" required /></label><label class="field">양자화 (선택)<select v-model="download.quantization"><option value="">LM Studio 기본 선택</option><option value="Q4_K_M">Q4_K_M</option><option value="Q5_K_M">Q5_K_M</option><option value="Q6_K">Q6_K</option><option value="Q8_0">Q8_0</option><option value="F16">F16</option></select><small class="field-help">공식 API에서는 Hugging Face URL을 사용할 때 quantization 선택을 지원합니다.</small></label></div><template #footer><button class="secondary-button" @click="downloadOpen = false">취소</button><button class="primary-button" :disabled="busy || !download.modelKey" @click="requestDownload">다운로드 요청</button></template></BaseModal>
  </section>
</template>

<style scoped>
.model-operations { display: grid; gap: 16px; margin-top: 22px; }
.operation-actions, .state-actions { display: flex; gap: 8px; align-items: center; }
.operation-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(280px, .8fr); gap: 14px; }
.operation-card, .agentless-note { padding: 17px; border: 1px solid var(--border); border-radius: 16px; background: var(--surface-2); }
.operation-card > header { display: flex; justify-content: space-between; align-items: start; gap: 12px; margin-bottom: 14px; }
.operation-card h3 { margin: 3px 0 0; font-size: 15px; }
.model-state-list, .profile-list, .history-list { display: grid; gap: 8px; }
.model-state-row, .profile-list > div { display: flex; min-width: 0; justify-content: space-between; gap: 10px; align-items: center; padding: 10px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface); }
.model-state-row > div:first-child, .profile-list > div > div { display: grid; min-width: 0; gap: 4px; }
.mono { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.variant-summary { color: var(--accent-strong); font-size: 9px; }
.agentless-note { display: grid; gap: 5px; border-color: var(--accent-border); background: var(--accent-dim); color: var(--text-soft); font-size: 11px; line-height: 1.6; }
.agentless-note strong { color: var(--accent-strong); }
.history-list > div { display: grid; grid-template-columns: auto 1fr; column-gap: 9px; row-gap: 3px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border); }
.history-list > div:last-child { border-bottom: 0; }
.history-list small { grid-column: 2; color: var(--muted); }
.settings-callout, .settings-warning { padding: 12px 14px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); color: var(--text-soft); font-size: 10px; line-height: 1.6; }
.settings-callout { display: grid; gap: 4px; }
.settings-callout strong { color: var(--accent-strong); }
.settings-warning { border-color: var(--warning-border); background: var(--warning-dim); color: var(--warning); }
.variant-picker { display: grid; gap: 9px; padding: 12px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--surface-2); }
.variant-list, .capability-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.variant-chip, .capability-tag { padding: 5px 8px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface); color: var(--muted); font-size: 9px; }
.variant-chip.active { border-color: var(--accent-border); color: var(--accent-strong); }
.variant-chip.selected { box-shadow: 0 0 0 2px var(--accent-dim); }
.variant-chip b { margin-left: 4px; color: var(--accent-strong); font-size: 8px; }
.capability-tags { margin-top: -5px; }
.capability-tag { border-color: var(--accent-border); color: var(--accent-strong); }
.advanced-options { border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); overflow: hidden; }
.advanced-options summary { padding: 13px 14px; color: var(--text-soft); font-size: 11px; font-weight: 800; cursor: pointer; }
.advanced-options summary span { margin-left: 6px; color: var(--muted); font-size: 9px; font-weight: 500; }
.advanced-options[open] summary { border-bottom: 1px solid var(--border); }
.advanced-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px; padding: 14px; }
.advanced-toggles { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; padding: 0 14px 14px; }
.preflight-result { display: grid; gap: 5px; padding: 12px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); color: var(--text); }
.preflight-result.warning { border-color: var(--warning-border); background: var(--warning-dim); }
.preflight-result small { color: var(--muted); }
@media (max-width: 900px) { .operation-grid { grid-template-columns: 1fr; } .advanced-toggles { grid-template-columns: 1fr; } }
@media (max-width: 580px) { .model-state-row, .profile-list > div { align-items: flex-start; flex-direction: column; } .operation-actions { flex-wrap: wrap; } .advanced-grid { grid-template-columns: 1fr; } }
</style>