<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import ModelOperationsPanel from './ModelOperationsPanel.vue'
import ModelFeatureBadges from './ModelFeatureBadges.vue'
import ModelFeatureSupportManager from './ModelFeatureSupportManager.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint, type RuntimeType } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const emit = defineEmits<{ openPlayground: [targetId: string] }>()

type Accelerator = { id: string; nodeId: string; vendor?: string; productName?: string; deviceIndex: number; deviceUuid?: string; memoryTotalMb?: number; driverVersion?: string }
type EndpointDetail = Endpoint & { nodeName: string; nodeDescription?: string | null; apiTokenConfigured: boolean }

const runtimeTypes: Array<{ value: RuntimeType; label: string; description: string; placeholder: string; defaultPort?: number }> = [
  { value: 'LM_STUDIO', label: 'LM Studio', description: 'OpenAI-compatible API with native model management.', placeholder: 'http://gpu-node-01:1234', defaultPort: 1234 },
  { value: 'OLLAMA', label: 'Ollama', description: 'Ollama native model catalog; model loading stays server-managed.', placeholder: 'http://gpu-node-01:11434', defaultPort: 11434 },
  { value: 'LLAMA_CPP', label: 'llama.cpp', description: 'OpenAI-compatible llama.cpp server; model loading stays process-managed.', placeholder: 'http://gpu-node-01:8080', defaultPort: 8080 },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI compatible', description: 'Any server implementing the OpenAI Chat Completions contract.', placeholder: 'https://api.example.com/v1' }
]
function runtimeTypeInfo(type?: string) { return runtimeTypes.find(item => item.value === type) ?? runtimeTypes[0] }
function runtimeLabel(type?: string) { return runtimeTypeInfo(type).label }
function runtimeDescription(type?: string) { return runtimeTypeInfo(type).description }
function runtimePlaceholder(type?: string) { return runtimeTypeInfo(type).placeholder }
function updateRuntimeDefaults() {
  const info = runtimeTypeInfo(runtime.value.runtimeType)
  if (info.defaultPort && (!runtime.value.baseUrl || /:\d+$/.test(runtime.value.baseUrl))) runtime.value.baseUrl = 'http://gpu-node-01:' + info.defaultPort
}

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
const deploymentPricingOpen = ref(false)
const deploymentDetailOpen = ref(false)
const inspectingDeployment = ref<Deployment | null>(null)
const editing = ref<Deployment | null>(null)
const editingAccelerator = ref<Accelerator | null>(null)
const endpointDetail = ref<EndpointDetail | null>(null)
const runtime = ref({ nodeName: '', runtimeName: '', runtimeType: 'LM_STUDIO' as RuntimeType, description: '', baseUrl: 'http://gpu-node-01:1234', apiToken: '', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', clearPricing: true })
const endpointForm = ref({ displayName: '', runtimeType: 'LM_STUDIO' as RuntimeType, baseUrl: '', enabled: true, apiToken: '', clearApiToken: false, inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', clearPricing: false })
const accelerator = ref({ vendor: '', productName: '', deviceIndex: 0, deviceUuid: '', memoryTotalMb: null as number | null, driverVersion: '' })

const currentRuntimeDeployments = computed(() => deployments.value.filter(item => item.healthStatus !== 'UNHEALTHY'))
const unavailableDeployments = computed(() => deployments.value.filter(item => item.healthStatus === 'UNHEALTHY'))
const loadedDeployments = computed(() => currentRuntimeDeployments.value.filter(item => item.loaded))
const candidateDeployments = computed(() => currentRuntimeDeployments.value.filter(item => !item.loaded))

type RuntimeSettingField = { label: string; keys: string[]; fallback?: unknown; source?: string }
type RuntimeSettingSection = { title: string; fields: RuntimeSettingField[] }
type RuntimeSettingValue = RuntimeSettingField & { value: unknown; reported: boolean }
type RuntimeSettingSectionValue = { title: string; fields: RuntimeSettingValue[] }

function objectValue(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}
function normalizedSettingKey(value: string) { return value.replace(/[^a-z0-9]/gi, '').toLowerCase() }
function modelMetadata(deployment?: Deployment | null): Record<string, unknown> {
  if (!deployment?.metadataJson) return {}
  try { return JSON.parse(deployment.metadataJson) as Record<string, unknown> } catch { return {} }
}
function runtimeSettingSections(type?: RuntimeType): RuntimeSettingSection[] {
  const common: RuntimeSettingSection[] = [
    { title: '모델·컨텍스트', fields: [
      { label: '컨텍스트 길이', keys: ['context_length', 'contextLength', 'n_ctx', 'n_ctx_per_seq', 'ctx_size', 'max_context_length'] },
      { label: '양자화', keys: ['quantization', 'quantization_level', 'quantization_type', 'file_type', 'ftype'] },
      { label: '모델 메모리 크기', keys: ['size', 'model_size', 'size_bytes'] }
    ] },
    { title: '실행·메모리 설정', fields: [
      { label: '동시 처리 / 슬롯', keys: ['parallel', 'parallelSlots', 'n_parallel', 'max_concurrent_predictions', 'n_slots', 'total_slots'] },
      { label: 'GPU 오프로딩 레이어', keys: ['gpu_offload_layers', 'gpuLayers', 'n_gpu_layers', 'n_gpu_layers_set', 'gpu_layers'] },
      { label: 'GPU 메모리 사용량', keys: ['size_vram', 'size_vram_bytes', 'vram_bytes'] },
      { label: 'Flash Attention', keys: ['flash_attention', 'flash_attn'] },
      { label: 'K 캐시 형식', keys: ['k_cache_quantization_type', 'cache_type_k', 'type_k'] },
      { label: 'V 캐시 형식', keys: ['v_cache_quantization_type', 'cache_type_v', 'type_v'] }
    ] }
  ]
  if (type === 'LM_STUDIO') {
    return [
      { title: '모델·컨텍스트', fields: [
        { label: '컨텍스트 길이', keys: ['context_length', 'max_context_length', 'n_ctx'] },
        { label: '평가 배치 크기', keys: ['eval_batch_size'] },
        { label: '물리 배치 크기', keys: ['physical_batch_size'] },
        { label: '동시 예측 수', keys: ['parallel', 'max_concurrent_predictions'] },
        { label: 'MoE Expert 수', keys: ['num_experts'] }
      ] },
      { title: 'GPU·캐시·실행 설정', fields: [
        { label: 'GPU 오프로딩 레이어', keys: ['gpu_offload_layers', 'gpuLayers', 'n_gpu_layers'] },
        { label: 'KV 캐시 GPU 오프로딩', keys: ['offload_kv_cache_to_gpu'] },
        { label: '통합 KV 캐시', keys: ['unified_kv_cache'] },
        { label: 'Flash Attention', keys: ['flash_attention'] },
        { label: 'K 캐시 형식', keys: ['k_cache_quantization_type', 'kvCacheTypeK'] },
        { label: 'V 캐시 형식', keys: ['v_cache_quantization_type', 'kvCacheTypeV'] },
        { label: 'CPU 스레드 풀 크기', keys: ['cpu_thread_pool_size'] },
        { label: '모델 메모리 유지', keys: ['keep_model_in_memory'] },
        { label: '메모리 매핑 (mmap)', keys: ['try_mmap'] },
        { label: 'RoPE 주파수 Base / Scale', keys: ['rope_frequency_base', 'rope_frequency_scale'] }
      ] }
    ]
  }
  if (type === 'LLAMA_CPP') {
    return [
      { title: '모델·컨텍스트', fields: [
        { label: '컨텍스트 길이', keys: ['n_ctx', 'n_ctx_per_seq', 'ctx_size', 'context_length', 'max_context_length'] },
        { label: '모델 파일 / 경로', keys: ['model_path', 'modelPath', 'path', 'model'] },
        { label: '양자화', keys: ['quantization', 'quantization_level', 'ftype'] },
        { label: '평가 배치 크기', keys: ['eval_batch_size', 'batch_size', 'n_batch'] },
        { label: '물리 배치 크기', keys: ['physical_batch_size', 'ubatch_size', 'n_ubatch'] }
      ] },
      { title: '서버가 보고한 실행 정보', fields: [
        { label: '동시 처리 / 슬롯', keys: ['n_parallel', 'parallel', 'parallelSlots', 'n_slots', 'total_slots'] },
        { label: 'CPU 스레드 수', keys: ['cpu_threads', 'n_threads', 'threads'] },
        { label: 'GPU 오프로딩 레이어', keys: ['n_gpu_layers', 'n_gpu_layers_set', 'gpu_offload_layers', 'gpuLayers'] },
        { label: 'KV 캐시 GPU 오프로딩', keys: ['offloadKvCacheToGpu', 'kv_offload'] },
        { label: 'GPU 메모리 사용량', keys: ['size_vram', 'size_vram_bytes', 'vram_bytes'] },
        { label: 'Flash Attention', keys: ['flash_attn', 'flash_attention'] },
        { label: 'K 캐시 형식', keys: ['cache_type_k', 'kvCacheTypeK', 'type_k'] },
        { label: 'V 캐시 형식', keys: ['cache_type_v', 'kvCacheTypeV', 'type_v'] }
      ] }
    ]
  }
  if (type === 'OLLAMA') {
    return [
      { title: '모델·컨텍스트', fields: [
        { label: '컨텍스트 길이', keys: ['context_length', 'num_ctx', 'n_ctx'] },
        { label: '모델 메모리 크기', keys: ['size', 'model_size', 'size_bytes', 'sizeBytes'] },
        { label: '양자화', keys: ['quantization', 'quantization_level'] }
      ] },
      { title: '실행·메모리 설정', fields: [
        { label: 'GPU 메모리 사용량', keys: ['size_vram', 'size_vram_bytes', 'vram_bytes'] },
        { label: 'GPU 오프로딩 레이어', keys: ['gpu_offload_layers', 'gpuLayers', 'n_gpu_layers'] },
        { label: 'KV 캐시 K 형식', keys: ['cache_type_k', 'kvCacheTypeK'] },
        { label: 'KV 캐시 V 형식', keys: ['cache_type_v', 'kvCacheTypeV'] },
        { label: 'KV 캐시 형식', keys: ['cache_type'] },
        { label: '병렬 처리 수', keys: ['parallel', 'parallelSlots', 'num_parallel'] }
      ] }
    ]
  }
  return common
}
function deploymentRuntimeSettingSections(deployment: Deployment): RuntimeSettingSectionValue[] {
  const metadata = modelMetadata(deployment)
  const metadataRecord = objectValue(metadata.metadata)
  const normalized = objectValue(metadata.runtimeSettings) ?? objectValue(metadata.runtime_settings)
    ?? objectValue(metadataRecord?.runtimeSettings) ?? objectValue(metadataRecord?.runtime_settings)
  const loadedInstances = Array.isArray(metadata.loaded_instances) ? metadata.loaded_instances : []
  const matchingInstance = loadedInstances.find(value => {
    const instance = objectValue(value)
    return instance?.id === deployment.providerModelId
  })
  const instance = objectValue(matchingInstance) ?? (loadedInstances.length === 1 ? objectValue(loadedInstances[0]) : null)
  const instanceConfig = objectValue(instance?.config)
  const status = objectValue(metadata.status)
  const statusArgs = objectValue(status?.args)
  const args = objectValue(metadata.args)
  const metadataMeta = objectValue(metadata.meta)
  const details = objectValue(metadata.details)
  const sources = [normalized, instanceConfig, args, statusArgs, metadata, metadataMeta, details].filter((value): value is Record<string, unknown> => value !== null)
  return runtimeSettingSections(selected.value?.runtimeType).map(section => ({
    title: section.title,
    fields: section.fields.map(field => {
      let found: unknown
      for (const source of sources) {
        const key = Object.keys(source).find(candidate => field.keys.some(expected => normalizedSettingKey(candidate) === normalizedSettingKey(expected)) && source[candidate] !== null && source[candidate] !== '')
        if (key) { found = source[key]; break }
      }
      if (found === undefined && field.label === '컨텍스트 길이' && deployment.contextLength) found = deployment.contextLength
      if (found === undefined && field.label === '양자화' && deployment.quantization) found = deployment.quantization
      return { ...field, value: found, reported: found !== undefined }
    })
  }))
}
const inspectingRuntimeSettings = computed(() => inspectingDeployment.value ? deploymentRuntimeSettingSections(inspectingDeployment.value) : [])
function runtimeState(deployment: Deployment): string {
  const metadata = modelMetadata(deployment)
  const runtimeState = metadata.runtimeState ?? metadata.runtime_state
  if (typeof runtimeState === 'string' && runtimeState.trim()) return runtimeState.toUpperCase()
  const runtimeStateObject = objectValue(runtimeState)
  const normalizedState = runtimeStateObject?.status ?? runtimeStateObject?.value
  if (typeof normalizedState === 'string' && normalizedState.trim()) return normalizedState.toUpperCase()
  const status = objectValue(metadata.status)
  const statusValue = status?.value ?? metadata.state
  if (typeof statusValue === 'string' && statusValue.trim()) return statusValue.toUpperCase()
  return deployment.loaded ? 'LOADED' : 'UNLOADED'
}
function runtimeStateLabel(deployment: Deployment): string {
  const labels: Record<string, string> = { SLEEPING: '절전', LOADING: '로딩 중', DOWNLOADING: '다운로드 중', ERROR: '오류', UNKNOWN: '상태 확인 필요', FAILED: '실패' }
  return labels[runtimeState(deployment)] ?? '미로드'
}
function formatRuntimeValue(value: unknown): string {
  if (typeof value === 'boolean') return value ? '켜짐' : '꺼짐'
  if (typeof value === 'number') return value.toLocaleString()
  if (typeof value === 'string') return value
  if (Array.isArray(value) || objectValue(value)) return JSON.stringify(value)
  return String(value)
}
function formatRuntimeFieldValue(field: RuntimeSettingField, value: unknown): string {
  if (typeof value === 'number' && (field.label === '모델 메모리 크기' || field.label === 'GPU 메모리 사용량')) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB']
    let size = value
    let unit = 0
    while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++ }
    return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}`
  }
  return formatRuntimeValue(value)
}
function runtimeDetailNote(type?: RuntimeType): string {
  if (type === 'LM_STUDIO') return 'LM Studio가 동기화 시 반환한 loaded instance 설정을 표시합니다. 응답에 포함되지 않은 값은 추정하지 않습니다.'
  if (type === 'LLAMA_CPP') return 'llama.cpp의 router/API 응답으로 확인 가능한 정보만 표시합니다. 프로세스 시작 인자나 서버 API가 보고하지 않는 캐시·GPU 설정은 여기서 확인할 수 없습니다.'
  if (type === 'OLLAMA') return 'Ollama가 반환한 실행 모델 정보만 표시합니다. API 응답에 없는 캐시·오프로딩 세부 설정은 추정하지 않습니다.'
  return 'Runtime 응답에 포함된 정보만 표시합니다. 응답에 포함되지 않은 값은 확인할 수 없습니다.'
}

function healthClass(value?: string) { return (value ?? 'unknown').toLowerCase() }
function endpointLabel(endpoint: Endpoint) { return endpoint.displayName || endpoint.baseUrl.replace(/^https?:\/\//, '') }
function deploymentVariantSummary(deployment: Deployment) {
  if (!deployment.metadataJson) return deployment.quantization || '-'
  try {
    const metadata = JSON.parse(deployment.metadataJson) as { variants?: unknown; selected_variant?: unknown }
    const variants = Array.isArray(metadata.variants) ? metadata.variants.filter((value): value is string => typeof value === 'string') : []
    const selected = typeof metadata.selected_variant === 'string' ? metadata.selected_variant : ''
    if (!variants.length) return deployment.quantization || '-'
    const label = selected.includes('@') ? selected.slice(selected.lastIndexOf('@') + 1).toUpperCase() : selected
    return `${variants.length}개 변형${label ? ` · ${label}` : ''}`
  } catch { return deployment.quantization || '-' }
}
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
    endpoints.value = await adminFetch<Endpoint[]>(`/api/admin/organizations/${props.organizationId}/runtime-endpoints`, props.auth)
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
    const endpoint = await adminFetch<Endpoint>('/api/admin/runtime-endpoints', props.auth, { method: 'POST', body: JSON.stringify({ nodeId: node.id, displayName: runtime.value.runtimeName || runtime.value.nodeName, runtimeType: runtime.value.runtimeType, baseUrl: runtime.value.baseUrl, apiToken: runtime.value.apiToken || null, inputPricePerMillion: runtime.value.clearPricing ? null : runtime.value.inputPricePerMillion, outputPricePerMillion: runtime.value.clearPricing ? null : runtime.value.outputPricePerMillion, currency: runtime.value.clearPricing ? null : runtime.value.currency }) })
    runtime.value = { nodeName: '', runtimeName: '', runtimeType: 'LM_STUDIO' as RuntimeType, description: '', baseUrl: 'http://gpu-node-01:1234', apiToken: '', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', clearPricing: true }
    createOpen.value = false
    message.value = 'AI Runtime을 등록했습니다. 연결 확인 후 모델을 동기화하세요.'
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
    endpointForm.value = { displayName: endpointDetail.value.displayName, runtimeType: endpointDetail.value.runtimeType as RuntimeType, baseUrl: endpointDetail.value.baseUrl, enabled: endpointDetail.value.enabled, apiToken: '', clearApiToken: false, inputPricePerMillion: endpointDetail.value.inputPricePerMillion ?? 0, outputPricePerMillion: endpointDetail.value.outputPricePerMillion ?? 0, currency: endpointDetail.value.currency ?? 'KRW', clearPricing: endpointDetail.value.inputPricePerMillion == null && endpointDetail.value.outputPricePerMillion == null }
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

async function saveAccelerator() {
  if (!selected.value) return
  busy.value = true
  try {
    const path = `/api/admin/nodes/${selected.value.nodeId}/accelerators${editingAccelerator.value ? `/${editingAccelerator.value.id}` : ''}`
    await adminFetch(path, props.auth, { method: editingAccelerator.value ? 'PATCH' : 'POST', body: JSON.stringify({ vendor: accelerator.value.vendor || null, productName: accelerator.value.productName || null, deviceIndex: accelerator.value.deviceIndex, deviceUuid: accelerator.value.deviceUuid || null, memoryTotalMb: accelerator.value.memoryTotalMb || null, driverVersion: accelerator.value.driverVersion || null, metadataJson: '{}' }) })
    acceleratorOpen.value = false
    message.value = editingAccelerator.value ? 'GPU 인벤토리 정보를 수정했습니다.' : 'GPU 인벤토리 정보를 추가했습니다.'
    editingAccelerator.value = null
    await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Accelerator 등록에 실패했습니다.' }
  finally { busy.value = false }
}

async function deleteAccelerator(device: Accelerator) {
  if (!selected.value || !window.confirm(`${device.productName || '이 GPU'} 인벤토리를 삭제할까요?`)) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/nodes/${selected.value.nodeId}/accelerators/${device.id}`, props.auth, { method: 'DELETE' })
    message.value = 'GPU 인벤토리를 삭제했습니다.'
    await selectEndpoint(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : 'GPU 인벤토리 삭제에 실패했습니다.' }
  finally { busy.value = false }
}
async function registerAccelerator() { await saveAccelerator() }

function openAccelerator(device?: Accelerator | Event) {
  const selectedDevice = device instanceof Event ? undefined : device
  editingAccelerator.value = selectedDevice ?? null
  accelerator.value = selectedDevice ? { vendor: selectedDevice.vendor ?? '', productName: selectedDevice.productName ?? '', deviceIndex: selectedDevice.deviceIndex, deviceUuid: selectedDevice.deviceUuid ?? '', memoryTotalMb: selectedDevice.memoryTotalMb ?? null, driverVersion: selectedDevice.driverVersion ?? '' } : { vendor: '', productName: '', deviceIndex: accelerators.value.length, deviceUuid: '', memoryTotalMb: null, driverVersion: '' }
  acceleratorOpen.value = true
}

function openDeployment(deployment: Deployment) { inspectingDeployment.value = deployment; deploymentDetailOpen.value = true }
function openDeploymentPricing(deployment: Deployment) { editing.value = { ...deployment, currency: deployment.currency ?? 'KRW' }; deploymentPricingOpen.value = true }
function saveDeploymentFeatureSupport(model: { id:string; featureSupportJson?:string|null }) { deployments.value=deployments.value.map(item=>item.id===model.id?{...item,featureSupportJson:model.featureSupportJson}:item) }
async function saveDeployment() {
  if (!editing.value) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/model-deployments/${editing.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify({ displayName: editing.value.displayName, compatibilityKey: editing.value.compatibilityKey, enabled: editing.value.enabled, maxConcurrency: editing.value.maxConcurrency, capabilityOverridesJson: editing.value.capabilityOverridesJson || '[]', inputPricePerMillion: editing.value.inputPricePerMillion, outputPricePerMillion: editing.value.outputPricePerMillion, currency: editing.value.currency }) })
    deploymentPricingOpen.value = false
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
    <div class="page-hero"><div><p class="eyebrow">COMPUTE FABRIC</p><h1>인프라와 모델 운영</h1><p>GPU 종류와 관계없이 Tailscale에서 접근 가능한 AI Runtime을 연결하고, Endpoint·모델·라우팅 준비 상태를 관리합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load()">새로고침</button><button class="primary-button" :disabled="!organizationId" @click="createOpen = true">+ Runtime 연결</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div v-if="!organizationId" class="workspace-required"><span>WORKSPACE REQUIRED</span><div><strong>워크스페이스를 먼저 선택하세요</strong><p>GPU 노드, Runtime Endpoint와 발견된 모델은 선택한 워크스페이스의 인프라로 관리됩니다.</p></div></div>
    <div class="split-layout infrastructure-layout">
      <article class="surface-card list-panel">
        <header class="card-header"><div><span class="card-kicker">RUNTIME ENDPOINTS</span><h2>연결된 Runtime</h2></div><span class="count-badge">{{ endpoints.length }}</span></header>
        <div v-if="endpoints.length" class="endpoint-list"><article v-for="endpoint in endpoints" :key="endpoint.id" class="endpoint-list-row" :class="{ active: selected?.id === endpoint.id }"><button class="endpoint-select" @click="selectEndpoint(endpoint)"><span class="node-light" :class="healthClass(endpoint.healthStatus)"></span><span><strong>{{ endpointLabel(endpoint) }}</strong><small>{{ endpoint.runtimeType }} · {{ endpoint.healthStatus }}</small></span><b>›</b></button><button class="endpoint-settings-button" :aria-label="`${endpointLabel(endpoint)} 설정`" :disabled="busy" @click="openEndpointSettings(endpoint)">⚙</button></article></div>
        <div v-else class="empty-state"><span>◌</span><h3>등록된 Runtime이 없습니다</h3><p>Tailscale에서 접근 가능한 AI Runtime URL을 연결하세요.</p><button class="text-button" :disabled="!organizationId" @click="createOpen = true">첫 Runtime 연결</button></div>
      </article>
      <article class="surface-card detail-panel">
        <template v-if="selected">
          <header class="detail-header"><div><span class="status-chip" :class="healthClass(selected.healthStatus)"><i></i>{{ selected.healthStatus }}</span><h2>{{ selected.baseUrl }}</h2><p>{{ selected.runtimeType }} · 마지막 확인 {{ selected.lastCheckedAt ? new Date(selected.lastCheckedAt).toLocaleString() : '기록 없음' }}</p></div><div class="action-menu"><button class="secondary-button" :disabled="busy || !deployments.some(item => item.loaded)" @click="emit('openPlayground', selected.id)">로드된 모델 테스트</button><button class="secondary-button" :disabled="busy" @click="openEndpointSettings()">Endpoint 설정</button><button class="secondary-button" :disabled="busy" @click="action('probe')">연결 확인</button><button class="secondary-button" :disabled="busy" @click="action('sync-models')">모델 동기화</button><button class="ghost-button" :disabled="busy" @click="action(selected.healthStatus === 'DRAINING' ? 'resume' : 'drain')">{{ selected.healthStatus === 'DRAINING' ? '복구 재투입' : 'Drain' }}</button></div></header>
          <div class="section-divider"><span>ACCELERATOR INVENTORY</span><button class="text-button" @click="openAccelerator">+ 장치 등록</button></div>
          <div v-if="accelerators.length" class="hardware-strip"><article v-for="device in accelerators" :key="device.id" class="accelerator-card"><span class="accelerator-index">{{ device.deviceIndex }}</span><div><span class="card-kicker">{{ device.vendor || 'UNKNOWN VENDOR' }}</span><h3>{{ device.productName || '이름 없는 Accelerator' }}</h3><p>{{ device.memoryTotalMb ? `${device.memoryTotalMb.toLocaleString()} MB` : '메모리 정보 없음' }} · {{ device.driverVersion || '드라이버 정보 없음' }}</p></div><small class="mono">{{ device.deviceUuid || device.id }}</small></article></div>
          <div v-if="accelerators.length" class="hardware-actions"><span>Hardware inventory is optional metadata. It does not change routing capacity automatically.</span><div><button v-for="device in accelerators" :key="`${device.id}-actions`" class="text-button" :disabled="busy" @click="openAccelerator(device)">Edit {{ device.deviceIndex }}</button><button v-for="device in accelerators" :key="`${device.id}-delete`" class="danger-text-button" :disabled="busy" @click="deleteAccelerator(device)">Delete {{ device.deviceIndex }}</button></div></div>
          <div v-else class="hardware-empty"><span>GPU 정보는 선택 항목입니다.</span><p>Endpoint와 모델 운영은 GPU 메타데이터 없이도 정상 동작합니다.</p><button class="text-button" @click="openAccelerator">인벤토리 추가</button></div>
          <div class="section-divider"><span>현재 Runtime 인벤토리</span><b>{{ currentRuntimeDeployments.length }}</b></div>
          <section v-if="loadedDeployments.length" class="runtime-model-section">
            <header class="runtime-model-heading"><div><span class="card-kicker">ACTIVE IN MEMORY</span><h3>현재 로드된 모델</h3></div><span class="count-badge">{{ loadedDeployments.length }}</span></header>
            <div class="deployment-grid">
              <article v-for="deployment in loadedDeployments" :key="deployment.id" class="deployment-card-slot">
                <button class="deployment-card loaded-model-card" @click="openDeployment(deployment)">
                  <div class="deployment-top"><span class="model-cube">◈</span><span class="status-chip tiny healthy">LOADED · 상세 보기</span></div>
                  <strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small>
                  <small class="deployment-variant-summary">{{ deploymentVariantSummary(deployment) }}</small>
                  <dl><div><dt>컨텍스트</dt><dd>{{ deployment.contextLength?.toLocaleString() ?? '확인 불가' }}</dd></div><div><dt>AIConnect 요청 상한</dt><dd>{{ deployment.maxConcurrency }}</dd></div><div><dt>양자화</dt><dd>{{ deployment.quantization ?? '확인 불가' }}</dd></div></dl>
                  <ModelFeatureBadges :capabilities="deployment.capabilityOverridesJson || deployment.capabilitiesJson" :feature-support-json="deployment.featureSupportJson" />
                  <span class="capability-line">Capability: {{ deployment.capabilityOverridesJson || deployment.capabilitiesJson }}</span>
                </button>
                <div class="deployment-card-actions"><button class="secondary-button deployment-chat-button" :disabled="busy" @click="emit('openPlayground', deployment.id)">Chat 테스트</button><button class="text-button" :disabled="busy" @click="openDeploymentPricing(deployment)">요금·라우팅 설정</button></div>
              </article>
            </div>
          </section>
          <p v-else-if="candidateDeployments.length" class="loaded-model-empty">현재 로드된 모델이 없습니다. Runtime에서 모델을 로드한 뒤 ‘모델 동기화’를 실행하세요.</p>
          <details v-if="candidateDeployments.length" class="candidate-models">
            <summary><span><strong>미로드 모델 후보</strong><small>현재 메모리에 로드되지 않은 모델입니다. 펼쳐서 확인할 수 있습니다.</small></span><b>{{ candidateDeployments.length }}</b></summary>
            <div class="candidate-model-list">
              <button v-for="deployment in candidateDeployments" :key="deployment.id" class="candidate-model-row" @click="openDeployment(deployment)">
                <span class="model-cube small">◈</span><span class="candidate-model-name"><strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small></span><span class="deployment-variant-summary">{{ deploymentVariantSummary(deployment) }}</span><span class="status-chip tiny" :class="['ERROR', 'FAILED'].includes(runtimeState(deployment)) ? 'unhealthy' : 'unknown'">{{ runtimeStateLabel(deployment) }}</span>
              </button>
            </div>
          </details>
          <details v-if="unavailableDeployments.length" class="candidate-models unavailable-models">
            <summary><span><strong>이전 등록 모델 · 현재 확인 불가</strong><small>연결 장애 또는 최근 Runtime 목록에 없어도 모델 기록과 서비스 Target을 보존합니다.</small></span><b>{{ unavailableDeployments.length }}</b></summary>
            <div class="candidate-model-list">
              <button v-for="deployment in unavailableDeployments" :key="deployment.id" class="candidate-model-row" @click="openDeployment(deployment)">
                <span class="model-cube small">◈</span><span class="candidate-model-name"><strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small></span><span class="deployment-variant-summary">상세 정보 및 기존 요금 설정 보존</span><span class="status-chip tiny unhealthy">기록 보존</span>
              </button>
            </div>
            <p class="unavailable-model-note">이 모델들은 현재 로드·호출 후보로 취급하지 않습니다. Runtime이 복구되면 ‘모델 동기화’로 다시 확인할 수 있으며, 기존 논리 서비스 Target은 자동으로 삭제하거나 임의 변경하지 않습니다.</p>
          </details>
          <ModelFeatureSupportManager v-if="deployments.length" runtime :models="deployments" :auth="auth" @saved="saveDeploymentFeatureSupport" />
          <div v-if="!currentRuntimeDeployments.length && !unavailableDeployments.length" class="empty-state"><span>◈</span><h3>현재 Runtime에서 확인된 모델이 없습니다</h3><p>Runtime 서버에서 모델을 준비한 뒤 ‘모델 동기화’를 실행하세요.</p></div>
          <ModelOperationsPanel :endpoint="selected" :deployments="currentRuntimeDeployments" :auth="auth" @changed="load(selected?.id)" />
        </template>
        <div v-else class="empty-state centered"><span>◌</span><h3>Runtime을 선택하세요</h3><p>선택한 Runtime의 상태, Endpoint 설정, GPU 인벤토리와 모델 작업을 확인할 수 있습니다.</p></div>
      </article>
    </div>

    <BaseModal :open="createOpen" title="AI Runtime" description="Register an AI runtime with its provider-specific protocol." @close="createOpen = false"><div class="modal-form"><label class="field">Runtime type<select v-model="runtime.runtimeType" @change="updateRuntimeDefaults()"><option v-for="item in runtimeTypes" :key="item.value" :value="item.value">{{ item.label }}</option></select></label><p class="field-help">{{ runtimeDescription(runtime.runtimeType) }}</p><div class="form-grid"><label class="field">노드 이름<input v-model.trim="runtime.nodeName" required placeholder="gpu-node-01" /></label><label class="field">Endpoint URL<input v-model.trim="runtime.baseUrl" required :placeholder="runtimePlaceholder(runtime.runtimeType)" /></label></div><label class="toggle-field"><span>Endpoint 기본 단가 사용 안 함<small>체크하면 논리 서비스·모델 단가로 fallback합니다.</small></span><input v-model="runtime.clearPricing" type="checkbox" /></label><div class="form-grid three"><label class="field">통화<select v-model="runtime.currency" :disabled="runtime.clearPricing"><option value="KRW">원화 (KRW)</option><option value="USD">달러 (USD)</option></select></label><label class="field">입력 단가 / 1M 토큰 ({{ runtime.currency === 'USD' ? '$' : '₩' }})<input v-model.number="runtime.inputPricePerMillion" :disabled="runtime.clearPricing" type="number" min="0" step="0.000001" /></label><label class="field">출력 단가 / 1M 토큰 ({{ runtime.currency === 'USD' ? '$' : '₩' }})<input v-model.number="runtime.outputPricePerMillion" :disabled="runtime.clearPricing" type="number" min="0" step="0.000001" /></label></div><label class="field">설명<textarea v-model="runtime.description" rows="3" placeholder="위치, 담당 팀, 운영 목적"></textarea></label><label class="field">API Token<input v-model="runtime.apiToken" type="password" autocomplete="off" placeholder="인증을 사용하지 않으면 비워 둘 수 있습니다" /></label></div><template #footer><button class="secondary-button" @click="createOpen = false">취소</button><button class="primary-button" :disabled="busy || !runtime.nodeName || !runtime.baseUrl" @click="createRuntime">Runtime 등록</button></template></BaseModal>

    <BaseModal :open="endpointSettingsOpen" title="Endpoint 설정" description="연결 주소와 사용 여부를 관리합니다. API Token 원문은 다시 표시되지 않습니다." @close="endpointSettingsOpen = false"><div v-if="endpointDetail" class="modal-form"><label class="field">Runtime type<select v-model="endpointForm.runtimeType"><option v-for="item in runtimeTypes" :key="item.value" :value="item.value">{{ item.label }}</option></select></label><p class="field-help">{{ runtimeDescription(endpointForm.runtimeType) }}</p><div class="endpoint-info"><div><span>노드</span><strong>{{ endpointDetail.nodeName }}</strong><small>{{ endpointDetail.nodeDescription || '설명 없음' }}</small></div><div><span>Runtime</span><strong>{{ runtimeLabel(endpointDetail.runtimeType) }}</strong><small>{{ endpointDetail.healthStatus }} · {{ endpointDetail.lastCheckedAt ? new Date(endpointDetail.lastCheckedAt).toLocaleString() : '확인 기록 없음' }}</small></div><div><span>API Token</span><strong>{{ endpointDetail.apiTokenConfigured ? '설정됨' : '설정 안 됨' }}</strong><small>보안을 위해 원문은 표시하지 않습니다.</small></div></div><label class="field">Endpoint 이름<input v-model.trim="endpointForm.displayName" maxlength="160" required :placeholder="runtimeLabel(endpointForm.runtimeType) + ' Runtime'" /></label><label class="field">Endpoint URL<input v-model.trim="endpointForm.baseUrl" required :placeholder="runtimePlaceholder(endpointForm.runtimeType)" /></label><div class="form-grid three"><label class="field">통화<select v-model="endpointForm.currency" :disabled="endpointForm.clearPricing"><option value="KRW">원화 (KRW)</option><option value="USD">달러 (USD)</option></select></label><label class="field">입력 단가 / 1M 토큰 ({{ endpointForm.currency === 'USD' ? '$' : '₩' }})<input v-model.number="endpointForm.inputPricePerMillion" :disabled="endpointForm.clearPricing" type="number" min="0" step="0.000001" /></label><label class="field">출력 단가 / 1M 토큰 ({{ endpointForm.currency === 'USD' ? '$' : '₩' }})<input v-model.number="endpointForm.outputPricePerMillion" :disabled="endpointForm.clearPricing" type="number" min="0" step="0.000001" /></label></div><p class="field-help">모델별 단가가 비어 있을 때 이 Endpoint 단가를 local LLM 비용 계산의 기본값으로 사용합니다.</p><label class="toggle-field"><span>Endpoint 기본 단가 사용 안 함<small>끄면 논리 서비스·모델 단가를 사용합니다.</small></span><input v-model="endpointForm.clearPricing" type="checkbox" /></label><label class="toggle-field"><span>Endpoint 활성화<small>비활성화하면 신규 라우팅 후보에서 제외됩니다.</small></span><input v-model="endpointForm.enabled" type="checkbox" /></label><label class="field">새 API Token (선택)<input v-model="endpointForm.apiToken" :disabled="endpointForm.clearApiToken" type="password" autocomplete="new-password" placeholder="입력할 때만 기존 Token을 교체합니다" /></label><label class="toggle-field"><span>저장된 API Token 제거<small>Runtime 인증을 사용하지 않을 때만 선택하세요.</small></span><input v-model="endpointForm.clearApiToken" type="checkbox" /></label><button class="danger-text-button" type="button" :disabled="busy" @click="endpointDeleteOpen = true">이 Endpoint 삭제</button></div><template #footer><button class="secondary-button" @click="endpointSettingsOpen = false">취소</button><button class="primary-button" :disabled="busy || !endpointForm.baseUrl" @click="saveEndpointSettings">설정 저장</button></template></BaseModal>

    <BaseModal :open="endpointDeleteOpen" title="Endpoint 삭제" description="삭제된 Endpoint는 화면과 라우팅에서 제거되지만, 과거 요청·장애·감사 기록은 보존됩니다." size="sm" @close="endpointDeleteOpen = false"><div v-if="endpointDetail" class="delete-confirm"><span>삭제 대상</span><strong>{{ endpointDetail.baseUrl }}</strong><p>서비스 Target이 하나라도 연결되어 있으면 삭제할 수 없습니다. 먼저 LLM 서비스에서 Target을 다른 Deployment로 변경하거나 제거하세요.</p></div><template #footer><button class="secondary-button" @click="endpointDeleteOpen = false">취소</button><button class="danger-button" :disabled="busy" @click="archiveEndpoint">Endpoint 삭제</button></template></BaseModal>

    <BaseModal :open="acceleratorOpen" title="Accelerator 장치 등록" description="Register an AI runtime with its provider-specific protocol." @close="acceleratorOpen = false"><div class="modal-form"><div class="form-grid three"><label class="field">제조사<input v-model.trim="accelerator.vendor" placeholder="NVIDIA" /></label><label class="field">제품명<input v-model.trim="accelerator.productName" placeholder="RTX 5090" /></label><label class="field">장치 번호<input v-model.number="accelerator.deviceIndex" type="number" min="0" /></label></div><div class="form-grid"><label class="field">VRAM (MB)<input v-model.number="accelerator.memoryTotalMb" type="number" min="1" placeholder="선택" /></label><label class="field">드라이버 버전<input v-model.trim="accelerator.driverVersion" placeholder="선택" /></label></div><label class="field">Device UUID<input v-model.trim="accelerator.deviceUuid" placeholder="GPU-... (선택)" /></label></div><template #footer><button class="secondary-button" @click="acceleratorOpen = false">취소</button><button class="primary-button" :disabled="busy || accelerator.deviceIndex < 0" @click="registerAccelerator">장치 등록</button></template></BaseModal>

    <BaseModal :open="deploymentDetailOpen" :title="inspectingDeployment?.displayName ?? '모델 정보'" :description="`${runtimeLabel(selected?.runtimeType)} · Runtime에서 보고한 정보`" size="lg" @close="deploymentDetailOpen = false">
      <div v-if="inspectingDeployment" class="runtime-model-detail">
        <div class="runtime-detail-summary"><div><span>모델 식별자</span><strong class="mono">{{ inspectingDeployment.providerModelId }}</strong></div><div><span>상태</span><strong>{{ inspectingDeployment.loaded ? '현재 로드됨' : runtimeStateLabel(inspectingDeployment) }}</strong></div><div><span>Runtime</span><strong>{{ runtimeLabel(selected?.runtimeType) }}</strong></div><div><span>양자화</span><strong>{{ inspectingDeployment.quantization ?? '런타임 응답에 포함되지 않음' }}</strong></div></div>
        <p class="runtime-detail-note">{{ runtimeDetailNote(selected?.runtimeType) }}</p>
        <section v-for="section in inspectingRuntimeSettings" :key="section.title" class="runtime-setting-section">
          <h3>{{ section.title }}</h3>
          <dl><div v-for="field in section.fields" :key="field.label" :class="{ unreported: !field.reported }"><dt>{{ field.label }}</dt><dd>{{ field.reported ? formatRuntimeFieldValue(field, field.value) : '런타임 응답에 포함되지 않음' }}</dd></div></dl>
        </section>
        <p class="runtime-detail-footnote">표시되지 않은 설정은 기본값이라고 단정할 수 없습니다. 각 Runtime API가 실제 값을 반환하지 않은 경우 ‘응답에 포함되지 않음’으로 표시합니다.</p>
      </div>
      <template #footer><button class="secondary-button" @click="deploymentDetailOpen = false">닫기</button><button v-if="inspectingDeployment" class="primary-button" @click="openDeploymentPricing(inspectingDeployment); deploymentDetailOpen = false">요금·라우팅 설정</button></template>
    </BaseModal>

    <BaseModal :open="deploymentOpen" title="Deployment 운영 설정" description="자동으로 발견한 모델 정보는 유지하고, 라우팅에 필요한 운영 설정만 변경합니다." @close="deploymentOpen = false"><div v-if="editing" class="modal-form"><label class="field">호환 키<input v-model="editing.compatibilityKey" /></label><div class="form-grid"><label class="field">최대 동시 요청<input v-model.number="editing.maxConcurrency" type="number" min="1" /></label><label class="toggle-field"><span>라우팅 활성화<small>신규 요청 후보에 포함</small></span><input v-model="editing.enabled" type="checkbox" /></label></div><label class="field">관리자 검증 Capability JSON<textarea v-model="editing.capabilityOverridesJson" rows="4" placeholder='["STRUCTURED_OUTPUT"]'></textarea></label></div><template #footer><button class="secondary-button" @click="deploymentOpen = false">취소</button><button class="primary-button" :disabled="busy" @click="saveDeployment">설정 저장</button></template></BaseModal>
    <BaseModal :open="deploymentPricingOpen" title="Deployment 비용 설정" description="이 모델이 실제로 호출될 때 적용할 입력·출력 단가와 통화를 저장합니다." @close="deploymentPricingOpen = false"><div v-if="editing" class="modal-form"><label class="field">Deployment name<input v-model.trim="editing.displayName" maxlength="200" required /></label><div class="form-grid three"><label class="field">Input price / 1M tokens<input v-model.number="editing.inputPricePerMillion" type="number" min="0" step="0.000001" /></label><label class="field">Output price / 1M tokens<input v-model.number="editing.outputPricePerMillion" type="number" min="0" step="0.000001" /></label><label class="field">Currency<select v-model="editing.currency"><option value="KRW">KRW (원화)</option><option value="USD">USD (달러)</option></select></label></div><p class="field-help">가격은 1M 토큰 기준입니다. Runtime이 usage를 반환하지 않으면 gateway가 요청/응답 텍스트를 기준으로 보수적으로 추정합니다.</p><label class="field">Compatibility key<input v-model="editing.compatibilityKey" /></label><div class="form-grid"><label class="field">Max concurrency<input v-model.number="editing.maxConcurrency" type="number" min="1" /></label><label class="toggle-field"><span>Routing enabled<small>신규 요청 후보에 포함</small></span><input v-model="editing.enabled" type="checkbox" /></label></div><label class="field">Capability overrides JSON<textarea v-model="editing.capabilityOverridesJson" rows="4" placeholder='["STRUCTURED_OUTPUT"]'></textarea></label></div><template #footer><button class="secondary-button" @click="deploymentPricingOpen = false">취소</button><button class="primary-button" :disabled="busy" @click="saveDeployment">단가 저장</button></template></BaseModal>
  </section>
</template>

<style scoped>
.deployment-card-slot{min-width:0;display:grid;align-content:start;gap:7px}.deployment-chat-button{justify-self:end;min-height:30px;padding-inline:10px;font-size:9px}
.runtime-model-section{padding:10px 0 0}.runtime-model-heading{padding:8px 22px 0;display:flex;align-items:center;justify-content:space-between}.runtime-model-heading h3{margin:5px 0 0;font-size:13px}.loaded-model-empty{margin:13px;padding:13px;border:1px dashed var(--border);border-radius:11px;color:var(--muted);font-size:10px;line-height:1.6}.loaded-model-card{width:100%;cursor:pointer}.deployment-card-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px}.deployment-card-actions .text-button{font-size:9px}.candidate-models{margin:10px 13px 14px;border:1px solid var(--border);border-radius:12px;background:var(--surface-2);overflow:hidden}.candidate-models summary{min-height:58px;padding:10px 14px;display:flex;align-items:center;justify-content:space-between;gap:12px;cursor:pointer;list-style:none}.candidate-models summary::-webkit-details-marker{display:none}.candidate-models summary::after{content:'⌄';color:var(--accent-strong);font-size:15px}.candidate-models[open] summary::after{content:'⌃'}.candidate-models summary>span{display:grid;gap:4px}.candidate-models summary strong{font-size:11px}.candidate-models summary small{color:var(--muted);font-size:9px}.candidate-models summary>b{margin-left:auto;padding:5px 8px;border:1px solid var(--border);border-radius:8px;color:var(--muted);font-size:9px}.candidate-model-list{padding:0 10px 10px;display:grid;gap:6px}.candidate-model-row{min-width:0;padding:9px 11px;display:grid;grid-template-columns:30px minmax(120px,1fr) minmax(70px,auto) auto;align-items:center;gap:9px;border:1px solid var(--border);border-radius:10px;background:var(--surface);color:var(--text);text-align:left;cursor:pointer}.candidate-model-row:hover{border-color:var(--accent-border)}.candidate-model-name{min-width:0;display:grid;gap:4px}.candidate-model-name strong,.candidate-model-name small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.candidate-model-name strong{font-size:10px}.candidate-model-name small{color:var(--muted);font-size:8px}.runtime-model-detail{display:grid;gap:13px}.runtime-detail-summary{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.runtime-detail-summary>div{min-width:0;padding:10px 12px;display:grid;gap:5px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2)}.runtime-detail-summary span,.runtime-setting-section dt{color:var(--muted);font-size:9px}.runtime-detail-summary strong{overflow-wrap:anywhere;font-size:10px}.runtime-detail-note,.runtime-detail-footnote{margin:0;padding:10px 12px;border:1px solid var(--accent-border);border-radius:10px;background:var(--accent-dim);color:var(--text-soft);font-size:10px;line-height:1.6}.runtime-setting-section{padding:12px;border:1px solid var(--border);border-radius:11px;background:var(--surface-2)}.runtime-setting-section h3{margin:0 0 10px;font-size:11px}.runtime-setting-section dl{margin:0;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.runtime-setting-section dl>div{min-width:0;padding:9px;border:1px solid var(--border);border-radius:8px;background:var(--surface)}.runtime-setting-section dt{margin-bottom:5px}.runtime-setting-section dd{margin:0;overflow-wrap:anywhere;font-size:10px;font-weight:700}.runtime-setting-section .unreported dd{color:var(--muted);font-weight:500}.runtime-detail-footnote{border-color:var(--border);background:transparent;color:var(--muted)}
.unavailable-models{border-color:color-mix(in srgb,var(--danger) 28%,var(--border))}.unavailable-model-note{margin:0;padding:0 14px 12px;color:var(--muted);font-size:9px;line-height:1.6}
.endpoint-list { padding: 8px; display: grid; gap: 4px; }
.endpoint-list-row { display: grid; grid-template-columns: 1fr 37px; gap: 3px; align-items: stretch; border: 1px solid transparent; border-radius: 11px; }
.endpoint-list-row:hover, .endpoint-list-row.active { border-color: var(--accent-border); background: var(--accent-dim); }
.endpoint-select { min-width: 0; min-height: 59px; padding: 10px; display: grid; grid-template-columns: auto 1fr auto; gap: 11px; align-items: center; border: 0; background: transparent; color: var(--muted); text-align: left; }
.endpoint-list-row.active .endpoint-select { color: var(--text); }
.endpoint-select > span:nth-child(2) { min-width: 0; display: grid; gap: 5px; }
.endpoint-select strong, .endpoint-select small { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.endpoint-select strong { font-size: 11px; }.endpoint-select small { color: var(--muted); font-size: 8px; }.endpoint-select b { color: var(--faint); }.deployment-variant-summary { display: block; margin-top: 3px; color: var(--accent-strong); font-size: 9px; }
.endpoint-settings-button { width: 34px; height: 34px; margin: auto 3px auto 0; border: 1px solid var(--border); border-radius: 9px; background: var(--surface); color: var(--muted); }
.endpoint-settings-button:hover { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.endpoint-info { display: grid; gap: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }.endpoint-info div { display: grid; gap: 3px; }.endpoint-info span, .endpoint-info small { color: var(--muted); font-size: 9px; }.endpoint-info strong { font-size: 11px; }
.danger-text-button { justify-self: start; padding: 4px 0; border: 0; background: transparent; color: var(--danger); font-size: 11px; font-weight: 800; }.danger-button { min-height: 40px; padding: 0 15px; border: 1px solid color-mix(in srgb, var(--danger) 42%, transparent); border-radius: 11px; background: var(--danger-dim); color: var(--danger); font-size: 12px; font-weight: 800; }
.delete-confirm { display: grid; gap: 9px; padding: 14px; border: 1px solid color-mix(in srgb, var(--danger) 35%, transparent); border-radius: 12px; background: var(--danger-dim); }.delete-confirm span { color: var(--danger); font-size: 9px; font-weight: 800; letter-spacing: .12em; }.delete-confirm strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }.delete-confirm p { margin: 0; color: var(--text-soft); font-size: 10px; line-height: 1.6; }
@media(max-width:700px){.candidate-model-row{grid-template-columns:30px minmax(0,1fr) auto}.candidate-model-row>.deployment-variant-summary{display:none}.runtime-detail-summary,.runtime-setting-section dl{grid-template-columns:1fr}}
</style>
