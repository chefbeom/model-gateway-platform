<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { previewServicePipeline, type DataProtectionAction, type PipelinePreviewResponse } from './api'
import CapabilityPicker from './CapabilityPicker.vue'
import BaseModal from './BaseModal.vue'
import ServiceSchedulingPanel from './ServiceSchedulingPanel.vue'
import {
  adminFetch,
  DATA_BOUNDARY_OPTIONS,
  dataBoundaryShortLabel,
  updateServicePolicy,
  type AdminAuth,
  type DataBoundaryPolicy,
  type Deployment,
  type Endpoint
} from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
type Service = { id: string; organizationId: string; serviceKey: string; displayName: string; failoverPolicy: 'STRICT' | 'COMPATIBLE' | 'DEGRADED'; retryPolicy: 'SAFE' | 'AGGRESSIVE'; allowDegraded: boolean; dataBoundaryPolicy?: DataBoundaryPolicy; requiredCapabilitiesJson: string; inputPricePerMillion: number; outputPricePerMillion: number; currency?: 'KRW' | 'USD'; enabled: boolean; temperaturePolicy?: 'REQUEST' | 'FIXED' | 'OMIT'; temperatureValue?: number | null; reasoningEffort?: 'REQUEST' | 'NONE' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX'; openAiFastMode?: boolean; maskerDeploymentId?: string | null; maskPersonNames?: boolean; guardrailAction?: DataProtectionAction; systemPromptTemplate?: string | null; promptVersion?: number; responseCacheEnabled?: boolean; responseCacheTtlSeconds?: number }
type Target = { id: string; deploymentId: string; priority: number; weight: number; degraded: boolean; enabled: boolean; maxConcurrencyOverride?: number | null; followModelChanges?: boolean }
type MaskerCatalogEntry = { id: string; displayName: string; providerModelId: string; sourceType: 'LOCAL_RUNTIME' | 'EXTERNAL_ZERO_RETENTION' | 'EXTERNAL_UNVERIFIED'; sourceName: string; eligible: boolean; trustNote: string }

const GUARDRAIL_PALETTE: Array<{ action: DataProtectionAction; title: string; badge: string; detail: string }> = [
  { action: 'LOCAL_ONLY', title: '개인정보 검사 · 외부 금지', badge: '사내 전용', detail: '민감정보 포함 시 사내 Local Target으로만 라우팅하고 외부 API 전송을 차단합니다.' },
  { action: 'REDACT_RESTORE', title: '마스킹 전송 + 원본 복원', badge: '가명화/복원', detail: '개인정보만 가명화해 외부 API로 보내고, 응답 시 Vault에서 원본으로 복원합니다.' },
  { action: 'REDACT', title: '마스킹 전송 · 가명화 유지', badge: '단방향 마스킹', detail: '개인정보를 가명화한 상태로 외부 전송·응답하여 사용자 화면에서도 비식별 상태를 유지합니다.' },
  { action: 'ALLOW', title: '개인정보 허가 · 직접 사용', badge: '패스스루', detail: '가명화 없이 승인된 외부/로컬 Target으로 요청을 그대로 전달합니다.' }
]

const GUARDRAIL_SELECT_OPTIONS: Array<{ value: DataProtectionAction; label: string }> = [
  { value: 'ALLOW', label: '개인정보 허가 · 외부 전송 허용 (ALLOW)' },
  { value: 'REDACT_RESTORE', label: '마스킹 전송 + 응답 원본 복원 (REDACT_RESTORE)' },
  { value: 'REDACT', label: '마스킹 전송 · 가명화 유지 (REDACT)' },
  { value: 'LOCAL_ONLY', label: '개인정보 검사 · 외부 금지 (LOCAL_ONLY)' },
  { value: 'BLOCK', label: '민감정보 차단 (BLOCK)' }
]

const maskerCatalog = ref<MaskerCatalogEntry[]>([])
const pipelineForm = ref<{ guardrailAction: DataProtectionAction; maskerDeploymentId: string; maskPersonNames: boolean; systemPromptTemplate: string; responseCacheEnabled: boolean; responseCacheTtlSeconds: number }>({
  guardrailAction: 'ALLOW',
  maskerDeploymentId: '',
  maskPersonNames: true,
  systemPromptTemplate: '',
  responseCacheEnabled: false,
  responseCacheTtlSeconds: 300
})
const previewSampleText = ref('홍길동 965034-3231111 , 01012344321 어제 학교를 갔다')
const previewResult = ref<PipelinePreviewResponse | null>(null)
const previewBusy = ref(false)
const pipelineSaving = ref(false)
const dragState = ref<{ kind: 'policy'; action: DataProtectionAction } | { kind: 'masker'; deploymentId: string } | { kind: 'target'; targetId: string } | null>(null)
const dropHover = ref<'guardrail' | 'p1' | 'p2' | null>(null)
const abPrimaryRatio = ref(80)
type ServiceDeletionCheck = { serviceId: string; serviceKey: string; displayName: string; projectAccessCount: number; targetCount: number; requestHistoryCount: number; linkedProjectNames: string[]; canDelete: boolean }

const services = ref<Service[]>([])
const selected = ref<Service | null>(null)
const targets = ref<Target[]>([])
const deploymentCatalog = ref<Deployment[]>([])
const availableDeployments = computed(() => deploymentCatalog.value.filter(item =>
  item.enabled && item.loaded !== false && item.healthStatus !== 'UNHEALTHY'
))
const targetDeploymentOptions = computed(() => {
  const current = editingTarget.value
    ? deploymentCatalog.value.find(item => item.id === editingTarget.value?.deploymentId)
    : undefined
  return current && !availableDeployments.value.some(item => item.id === current.id)
    ? [current, ...availableDeployments.value]
    : availableDeployments.value
})
const busy = ref(false)
const message = ref('')
const serviceModal = ref(false)
const targetModal = ref(false)
const deleteServiceModal = ref(false)
const editingService = ref<Service | null>(null)
const editingTarget = ref<Target | null>(null)
const deletionCheck = ref<ServiceDeletionCheck | null>(null)
const serviceForm = ref({ serviceKey: '', displayName: '', failoverPolicy: 'STRICT' as Service['failoverPolicy'], retryPolicy: 'SAFE' as Service['retryPolicy'], allowDegraded: false, dataBoundaryPolicy: 'EXTERNAL_ALLOWED' as DataBoundaryPolicy, requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', enabled: true, temperaturePolicy: 'REQUEST' as NonNullable<Service['temperaturePolicy']>, temperatureValue: 1, reasoningEffort: 'REQUEST' as NonNullable<Service['reasoningEffort']>, openAiFastMode: false })
const targetForm = ref({ deploymentId: '', priority: 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null as number | null, followModelChanges: true })

async function load() {
  if (!props.organizationId) { services.value = []; selected.value = null; targets.value = []; return }
  busy.value = true
  try {
    services.value = await adminFetch<Service[]>(`/api/admin/organizations/${props.organizationId}/services`, props.auth)
    if (selected.value && !services.value.some(item => item.id === selected.value?.id)) selected.value = null
    if (!selected.value && services.value.length) selected.value = services.value[0]
    if (selected.value) await loadTargets(selected.value)
    else targets.value = []
    await loadDeployments()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '서비스를 불러오지 못했습니다.'
  } finally { busy.value = false }
}

async function loadTargets(service: Service) {
  selected.value = service
  targets.value = await adminFetch<Target[]>(`/api/admin/services/${service.id}/targets`, props.auth)
}

async function loadDeployments() {
  const [endpoints, providers] = await Promise.all([
    adminFetch<Endpoint[]>('/api/admin/runtime-endpoints', props.auth),
    adminFetch<Array<{ id: string; displayName?: string; zeroRetentionVerified?: boolean; zeroRetentionEvidence?: string | null }>>(`/api/admin/organizations/${props.organizationId}/external-providers`, props.auth)
  ])
  const [localGroups, externalGroups] = await Promise.all([
    Promise.all(endpoints.map(endpoint => adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpoint.id}/deployments`, props.auth))),
    Promise.all(providers.map(provider => adminFetch<Deployment[]>(`/api/admin/external-providers/${provider.id}/models`, props.auth)))
  ])
  deploymentCatalog.value = [...localGroups.flat(), ...externalGroups.flat()]
  const localEntries: MaskerCatalogEntry[] = endpoints.flatMap((endpoint, idx) =>
    (localGroups[idx] ?? []).map(dep => {
      const ep = endpoint as Endpoint & { displayName?: string; name?: string }
      return {
        id: dep.id,
        displayName: dep.displayName,
        providerModelId: dep.providerModelId,
        sourceType: 'LOCAL_RUNTIME',
        sourceName: ep.displayName ?? ep.name ?? '사내 Local Runtime',
        eligible: true,
        trustNote: '사내 Local / 비보존 확인 Runtime'
      }
    })
  )
  const externalEntries: MaskerCatalogEntry[] = providers.flatMap((provider, idx) =>
    (externalGroups[idx] ?? []).map(dep => {
      const verified = Boolean(provider.zeroRetentionVerified)
      return {
        id: dep.id,
        displayName: dep.displayName,
        providerModelId: dep.providerModelId,
        sourceType: verified ? 'EXTERNAL_ZERO_RETENTION' : 'EXTERNAL_UNVERIFIED',
        sourceName: provider.displayName ?? '외부 API',
        eligible: verified,
        trustNote: verified
          ? (provider.zeroRetentionEvidence ? `무유출 확인 (${provider.zeroRetentionEvidence})` : '외부 유출 없음(Zero-Retention) 확인됨')
          : '외부 AI 관리에서 Zero-Retention 무유출 확인 시 마스킹 워커로 사용 가능'
      }
    })
  )
  maskerCatalog.value = [...localEntries, ...externalEntries]
}

const eligibleMaskers = computed(() => maskerCatalog.value.filter(entry => entry.eligible))
const primaryTargets = computed(() => targets.value.filter(target => target.priority === 1))
const fallbackTargets = computed(() => targets.value.filter(target => target.priority > 1))
const selectedMaskerEntry = computed(() => maskerCatalog.value.find(entry => entry.id === pipelineForm.value.maskerDeploymentId) ?? null)

watch(selected, svc => {
  pipelineForm.value = {
    guardrailAction: svc?.guardrailAction ?? 'ALLOW',
    maskerDeploymentId: svc?.maskerDeploymentId ?? '',
    maskPersonNames: svc?.maskPersonNames ?? true,
    systemPromptTemplate: svc?.systemPromptTemplate ?? '',
    responseCacheEnabled: svc?.responseCacheEnabled ?? false,
    responseCacheTtlSeconds: svc?.responseCacheTtlSeconds ?? 300
  }
  previewResult.value = null
}, { immediate: true })

watch(primaryTargets, list => {
  if (list.length >= 2) {
    const first = Math.max(1, list[0].weight)
    const second = Math.max(1, list[1].weight)
    abPrimaryRatio.value = Math.round((first / (first + second)) * 100)
  }
}, { immediate: true })

function pipelineActionLabel(action: DataProtectionAction | string | undefined): string {
  switch (action) {
    case 'LOCAL_ONLY': return '개인정보 검사 · 외부 금지 (로컬 전용)'
    case 'REDACT_RESTORE': return '마스킹 전송 + 원본 복원'
    case 'REDACT': return '마스킹 전송 (가명화 유지)'
    case 'BLOCK': return '민감정보 요청 차단'
    default: return '개인정보 허가 · 외부 전송 허용'
  }
}

function pipelineReturnSummary(action: DataProtectionAction | string | undefined): string {
  switch (action) {
    case 'LOCAL_ONLY': return '사내 Local LLM이 직접 응답을 생성하며 외부 유출이 발생하지 않습니다.'
    case 'REDACT_RESTORE': return '외부 LLM 응답 내 가명화 토큰을 1차 마스커 Vault 원본 값으로 자동 복원합니다.'
    case 'REDACT': return '외부 LLM 응답에서도 개인정보를 가명화된 상태로 유지해 비식별 반환합니다.'
    case 'BLOCK': return '민감정보 포함 시 외부·로컬 실행 전 차단 응답(403)을 반환합니다.'
    default: return '원문을 수정 없이 라우팅된 Target에 전달·반환합니다.'
  }
}

function selectMaskerWorker(entry: MaskerCatalogEntry) {
  if (!entry.eligible) {
    message.value = entry.trustNote
    return
  }
  pipelineForm.value.maskerDeploymentId = entry.id
}

function startDragPolicy(action: DataProtectionAction) {
  dragState.value = { kind: 'policy', action }
}

function startDragMasker(entry: MaskerCatalogEntry) {
  if (!entry.eligible) return
  dragState.value = { kind: 'masker', deploymentId: entry.id }
}

function startDragTarget(target: Target) {
  dragState.value = { kind: 'target', targetId: target.id }
}

function onDropGuardrail() {
  dropHover.value = null
  const drag = dragState.value
  dragState.value = null
  if (!drag) return
  if (drag.kind === 'policy') pipelineForm.value.guardrailAction = drag.action
  else if (drag.kind === 'masker') pipelineForm.value.maskerDeploymentId = drag.deploymentId
}

async function moveTargetPriority(target: Target, nextPriority: number) {
  if (!selected.value || target.priority === nextPriority) return
  busy.value = true
  try {
    await adminFetch<Target>(`/api/admin/services/${selected.value.id}/targets/${target.id}`, props.auth, {
      method: 'PATCH',
      body: JSON.stringify({
        priority: nextPriority,
        weight: target.weight,
        degraded: target.degraded,
        enabled: target.enabled,
        maxConcurrencyOverride: target.maxConcurrencyOverride ?? null,
        followModelChanges: target.followModelChanges ?? true
      })
    })
    await loadTargets(selected.value)
    message.value = `Target 우선순위를 P${nextPriority}로 변경했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'Target 우선순위 변경에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

async function onDropTargetPriority(priority: 1 | 2) {
  dropHover.value = null
  const drag = dragState.value
  dragState.value = null
  if (!drag || drag.kind !== 'target') return
  const target = targets.value.find(item => item.id === drag.targetId)
  if (target) await moveTargetPriority(target, priority)
}

async function applyAbWeights(firstWeight: number, secondWeight: number) {
  if (!selected.value || primaryTargets.value.length < 2) return
  const first = primaryTargets.value[0]
  const second = primaryTargets.value[1]
  const w1 = Math.max(1, Math.round(firstWeight))
  const w2 = Math.max(1, Math.round(secondWeight))
  abPrimaryRatio.value = Math.round((w1 / (w1 + w2)) * 100)
  busy.value = true
  try {
    await Promise.all([
      adminFetch<Target>(`/api/admin/services/${selected.value.id}/targets/${first.id}`, props.auth, {
        method: 'PATCH',
        body: JSON.stringify({
          priority: first.priority,
          weight: w1,
          degraded: first.degraded,
          enabled: first.enabled,
          maxConcurrencyOverride: first.maxConcurrencyOverride ?? null,
          followModelChanges: first.followModelChanges ?? true
        })
      }),
      adminFetch<Target>(`/api/admin/services/${selected.value.id}/targets/${second.id}`, props.auth, {
        method: 'PATCH',
        body: JSON.stringify({
          priority: second.priority,
          weight: w2,
          degraded: second.degraded,
          enabled: second.enabled,
          maxConcurrencyOverride: second.maxConcurrencyOverride ?? null,
          followModelChanges: second.followModelChanges ?? true
        })
      })
    ])
    await loadTargets(selected.value)
    message.value = `A/B 트래픽 비율을 ${w1}:${w2}로 적용했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'A/B 트래픽 가중치 저장에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

async function savePipeline() {
  if (!selected.value) return
  const svc = selected.value
  const draft = pipelineForm.value
  const trimmedPrompt = draft.systemPromptTemplate.trim()
  pipelineSaving.value = true
  try {
    const updated = await adminFetch<Service>(`/api/admin/services/${svc.id}`, props.auth, {
      method: 'PATCH',
      body: JSON.stringify({
        displayName: svc.displayName,
        failoverPolicy: svc.failoverPolicy,
        retryPolicy: svc.retryPolicy,
        allowDegraded: svc.allowDegraded,
        dataBoundaryPolicy: svc.dataBoundaryPolicy,
        requiredCapabilitiesJson: svc.requiredCapabilitiesJson,
        inputPricePerMillion: svc.inputPricePerMillion,
        outputPricePerMillion: svc.outputPricePerMillion,
        currency: svc.currency ?? 'KRW',
        enabled: svc.enabled,
        temperaturePolicy: svc.temperaturePolicy,
        temperatureValue: svc.temperatureValue,
        reasoningEffort: svc.reasoningEffort,
        openAiFastMode: svc.openAiFastMode,
        maskerDeploymentId: draft.maskerDeploymentId || null,
        clearMaskerDeployment: !draft.maskerDeploymentId,
        maskPersonNames: draft.maskPersonNames,
        guardrailAction: draft.guardrailAction,
        systemPromptTemplate: trimmedPrompt ? draft.systemPromptTemplate : null,
        clearSystemPromptTemplate: !trimmedPrompt,
        responseCacheEnabled: draft.responseCacheEnabled,
        responseCacheTtlSeconds: Math.max(5, Math.min(86400, Number(draft.responseCacheTtlSeconds) || 300))
      })
    })
    services.value = services.value.map(item => item.id === updated.id ? updated : item)
    selected.value = updated
    message.value = `가드레일·프롬프트 v${updated.promptVersion ?? 1} 파이프라인을 저장했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '파이프라인 저장에 실패했습니다.'
  } finally {
    pipelineSaving.value = false
  }
}

async function runPipelinePreview() {
  if (!selected.value) return
  previewBusy.value = true
  try {
    previewResult.value = await previewServicePipeline(selected.value.id, {
      sampleText: previewSampleText.value,
      externalAction: pipelineForm.value.guardrailAction,
      maskPersonNames: pipelineForm.value.maskPersonNames,
      maskerDeploymentId: pipelineForm.value.maskerDeploymentId || null,
      invokeMaskerLlm: false
    }, props.auth)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '가드레일 시뮬레이션에 실패했습니다.'
  } finally {
    previewBusy.value = false
  }
}

function openCreateService() {
  editingService.value = null
  serviceForm.value = { serviceKey: '', displayName: '', failoverPolicy: 'STRICT', retryPolicy: 'SAFE', allowDegraded: false, dataBoundaryPolicy: 'EXTERNAL_ALLOWED', requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', enabled: true, temperaturePolicy: 'REQUEST', temperatureValue: 1, reasoningEffort: 'REQUEST', openAiFastMode: false }
  serviceModal.value = true
}

function openEditService() {
  if (!selected.value) return
  editingService.value = selected.value
  serviceForm.value = { ...selected.value, dataBoundaryPolicy: selected.value.dataBoundaryPolicy ?? 'EXTERNAL_ALLOWED', currency: selected.value.currency ?? 'KRW', temperaturePolicy: selected.value.temperaturePolicy ?? 'REQUEST', temperatureValue: selected.value.temperatureValue ?? 1, reasoningEffort: selected.value.reasoningEffort ?? 'REQUEST', openAiFastMode: selected.value.openAiFastMode ?? false }
  serviceModal.value = true
}

async function saveService() {
  busy.value = true
  try {
    if (editingService.value) {
      selected.value = await updateServicePolicy<Service>(editingService.value.id, serviceForm.value, props.auth)
      message.value = '서비스 정책을 저장했습니다. 외부 model 값은 유지됩니다.'
    } else {
      const created = await adminFetch<Service>('/api/admin/services', props.auth, { method: 'POST', body: JSON.stringify({ organizationId: props.organizationId, ...serviceForm.value }) })
      selected.value = created
      message.value = '새 논리 서비스를 생성했습니다.'
    }
    serviceModal.value = false
    await load()
  } catch (error) {
    message.value = error instanceof Error ? error.message : '서비스 저장에 실패했습니다.'
  } finally { busy.value = false }
}

function openCreateTarget() {
  editingTarget.value = null
  targetForm.value = { deploymentId: availableDeployments.value[0]?.id ?? '', priority: targets.value.length + 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true }
  targetModal.value = true
}

function openEditTarget(target: Target) {
  editingTarget.value = target
  targetForm.value = { deploymentId: target.deploymentId, priority: target.priority, weight: target.weight, degraded: target.degraded, enabled: target.enabled, maxConcurrencyOverride: target.maxConcurrencyOverride ?? null, followModelChanges: target.followModelChanges ?? true }
  targetModal.value = true
}

async function saveTarget() {
  if (!selected.value) return
  busy.value = true
  try {
    if (editingTarget.value) await adminFetch(`/api/admin/services/${selected.value.id}/targets/${editingTarget.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify(targetForm.value) })
    else await adminFetch(`/api/admin/services/${selected.value.id}/targets`, props.auth, { method: 'POST', body: JSON.stringify(targetForm.value) })
    targetModal.value = false
    message.value = '라우팅 대상을 저장했습니다.'
    await loadTargets(selected.value)
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'Target 저장에 실패했습니다.'
  } finally { busy.value = false }
}

async function removeTarget(target: Target) {
  if (!selected.value || !window.confirm('이 Target을 라우팅에서 제거할까요?')) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/services/${selected.value.id}/targets/${target.id}`, props.auth, { method: 'DELETE' })
    await loadTargets(selected.value)
    message.value = 'Target을 라우팅에서 제거했습니다.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'Target 제거에 실패했습니다.'
  } finally { busy.value = false }
}

async function openServiceDeletion() {
  if (!selected.value) return
  busy.value = true
  try {
    deletionCheck.value = await adminFetch<ServiceDeletionCheck>(`/api/admin/services/${selected.value.id}/deletion-check`, props.auth)
    deleteServiceModal.value = true
  } catch (error) {
    message.value = error instanceof Error ? error.message : '서비스 삭제 조건을 확인하지 못했습니다.'
  } finally { busy.value = false }
}

async function deleteService() {
  if (!selected.value || !deletionCheck.value?.canDelete) return
  busy.value = true
  try {
    const deletedId = selected.value.id
    const deletedName = selected.value.displayName
    await adminFetch(`/api/admin/services/${deletedId}`, props.auth, { method: 'DELETE' })
    services.value = services.value.filter(service => service.id !== deletedId)
    selected.value = services.value[0] ?? null
    targets.value = []
    if (selected.value) await loadTargets(selected.value)
    deleteServiceModal.value = false
    deletionCheck.value = null
    message.value = `'${deletedName}' 논리 서비스를 삭제했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '서비스 삭제에 실패했습니다. 연결 상태를 다시 확인하세요.'
    await openServiceDeletion()
  } finally { busy.value = false }
}

function deploymentForId(id: string) {
  return deploymentCatalog.value.find(candidate => candidate.id === id)
}

function deploymentName(id: string) {
  const item = deploymentForId(id)
  return item ? `${item.externalProviderId ? 'CLOUD · ' : ''}${item.displayName}` : '등록 정보가 없는 Deployment'
}

function deploymentIdentifier(id: string) {
  return deploymentForId(id)?.providerModelId ?? `Deployment ID: ${id}`
}

function deploymentStatusLabel(id: string) {
  const item = deploymentForId(id)
  if (!item) return '모델 기록을 찾을 수 없음 · Target 연결은 보존됨'
  if (item.healthStatus === 'UNHEALTHY') return 'Runtime에서 확인되지 않음 · Target 보존'
  if (!item.enabled) return '비활성 Deployment · Target 보존'
  if (!item.externalProviderId && !item.loaded) return '현재 미로드 · Target 보존'
  return ''
}

function temperaturePolicyLabel(service: Service) {
  if (service.temperaturePolicy === 'OMIT') return '모델 기본값'
  if (service.temperaturePolicy === 'FIXED') return `고정 ${service.temperatureValue ?? 1}`
  return '요청값 기준'
}

function reasoningEffortLabel(service: Service) {
  const labels: Record<NonNullable<Service['reasoningEffort']>, string> = {
    REQUEST: '요청·모델 기본값', NONE: '없음', MINIMAL: '최소', LOW: '낮음',
    MEDIUM: '중간', HIGH: '높음', XHIGH: '매우 높음', MAX: '최대'
  }
  return labels[service.reasoningEffort ?? 'REQUEST']
}

watch(() => props.organizationId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">LOGICAL AI SERVICES</p><h1>서비스와 라우팅</h1><p>사용자에게 노출되는 model 값과 실제 Deployment의 우선순위를 분리합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load">새로고침</button><button class="primary-button" :disabled="!organizationId" @click="openCreateService">+ 서비스 생성</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>

    <div class="split-layout services-layout">
      <article class="surface-card list-panel"><header class="card-header"><div><span class="card-kicker">SERVICE CATALOG</span><h2>논리 서비스</h2></div><span class="count-badge">{{ services.length }}</span></header><div v-if="services.length" class="entity-list service-entity-list"><button v-for="service in services" :key="service.id" :class="{ active: selected?.id === service.id }" @click="loadTargets(service)"><span class="service-glyph">⌘</span><span><strong>{{ service.serviceKey }}</strong><small>{{ service.displayName }}</small></span><span class="status-chip tiny" :class="service.enabled ? 'healthy' : 'unknown'">{{ service.enabled ? 'ACTIVE' : 'OFF' }}</span></button></div><div v-else class="empty-state"><span>⌘</span><h3>논리 서비스가 없습니다</h3><p>첫 서비스와 라우팅 정책을 생성하세요.</p></div></article>
      <article class="surface-card detail-panel">
        <template v-if="selected">
          <header class="detail-header"><div><span class="status-chip" :class="selected.enabled ? 'healthy' : 'unknown'"><i></i>{{ selected.enabled ? 'ACTIVE' : 'DISABLED' }}</span><h2>{{ selected.displayName }}</h2><p class="mono">model: {{ selected.serviceKey }}</p></div><div class="detail-actions"><button class="secondary-button" @click="openEditService">정책 편집</button><button class="secondary-button danger-text" :disabled="busy" @click="openServiceDeletion">서비스 삭제</button></div></header>
          <div class="policy-summary"><div><span>Failover</span><strong>{{ selected.failoverPolicy }}</strong></div><div><span>Retry</span><strong>{{ selected.retryPolicy }}</strong></div><div><span>데이터 경계</span><strong>{{ dataBoundaryShortLabel(selected.dataBoundaryPolicy) }}</strong></div><div><span>Temperature</span><strong>{{ temperaturePolicyLabel(selected) }}</strong></div><div><span>추론 수준 · 외부 API</span><strong>{{ reasoningEffortLabel(selected) }}</strong></div><div><span>Fast mode · 외부 API</span><strong>{{ selected.openAiFastMode ? '사용' : '요청/공급자 기본값' }}</strong></div><div><span>입력 단가 / 1M ({{ selected.currency ?? 'KRW' }})</span><strong>{{ selected.currency === 'USD' ? '$' : '₩' }}{{ selected.inputPricePerMillion }}</strong></div><div><span>출력 단가 / 1M ({{ selected.currency ?? 'KRW' }})</span><strong>{{ selected.currency === 'USD' ? '$' : '₩' }}{{ selected.outputPricePerMillion }}</strong></div></div>
          <ServiceSchedulingPanel :key="selected.id" :service-id="selected.id" :auth="auth" />
          <section class="pipeline-workbench" aria-label="AI Gateway 파이프라인 빌더">
            <header class="pipeline-head">
              <div>
                <strong>AI Gateway 가드레일 · 프롬프트 · 트래픽 파이프라인</strong>
                <small>정책이나 신뢰 마스킹 워커를 드래그/클릭해 게이트에 배치하고, A/B 트래픽 비율과 응답 복원을 한 화면에서 제어합니다.</small>
              </div>
              <div class="pipeline-head-actions">
                <span class="version-pill">Prompt v{{ selected.promptVersion ?? 1 }}</span>
                <button class="primary-button" :disabled="pipelineSaving" @click="savePipeline">{{ pipelineSaving ? '저장 중...' : '파이프라인 저장' }}</button>
              </div>
            </header>

            <div class="pipeline-palette">
              <div class="palette-group">
                <span class="palette-label">개인정보 보호 정책 (드래그 또는 클릭)</span>
                <div class="palette-chips">
                  <button
                    v-for="item in GUARDRAIL_PALETTE"
                    :key="item.action"
                    type="button"
                    draggable="true"
                    class="palette-chip"
                    :class="{ active: pipelineForm.guardrailAction === item.action }"
                    @dragstart="startDragPolicy(item.action)"
                    @click="pipelineForm.guardrailAction = item.action"
                  >
                    <strong>{{ item.title }}</strong>
                    <span>{{ item.badge }}</span>
                  </button>
                </div>
              </div>
              <div class="palette-group">
                <span class="palette-label">신뢰 마스킹 워커 (Local LLM 또는 무유출 확인 외부 API)</span>
                <div class="palette-chips">
                  <button
                    type="button"
                    draggable="true"
                    class="palette-chip"
                    :class="{ active: !pipelineForm.maskerDeploymentId }"
                    @dragstart="dragState = { kind: 'masker', deploymentId: '' }"
                    @click="pipelineForm.maskerDeploymentId = ''"
                  >
                    <strong>내장 정규식 엔진</strong>
                    <span>기본 · 무유출</span>
                  </button>
                  <button
                    v-for="entry in maskerCatalog"
                    :key="entry.id"
                    type="button"
                    :draggable="entry.eligible"
                    class="palette-chip"
                    :class="{ active: pipelineForm.maskerDeploymentId === entry.id, disabled: !entry.eligible }"
                    :title="entry.trustNote"
                    @dragstart="startDragMasker(entry)"
                    @click="selectMaskerWorker(entry)"
                  >
                    <strong>{{ entry.displayName }}</strong>
                    <span>{{ entry.sourceType === 'LOCAL_RUNTIME' ? '사내 Local LLM' : entry.eligible ? '무유출 외부 AI' : '미검증(사용 불가)' }}</span>
                  </button>
                </div>
              </div>
            </div>

            <div class="pipeline-flow">
              <article class="stage-card">
                <header><span>1. 요청 인입</span><strong>프롬프트 &amp; 캐싱</strong></header>
                <label class="field">시스템 프롬프트 템플릿 (변경 시 버전 증가)
                  <textarea v-model="pipelineForm.systemPromptTemplate" rows="2" placeholder="예: 모든 답변은 핵심 요약 3줄과 조치 사항 순으로 작성하세요."></textarea>
                </label>
                <div class="stage-inline-row">
                  <label class="inline-check">
                    <input v-model="pipelineForm.responseCacheEnabled" type="checkbox" />
                    <span>동일 질문 캐싱</span>
                  </label>
                  <label v-if="pipelineForm.responseCacheEnabled" class="ttl-input">
                    TTL(초)
                    <input v-model.number="pipelineForm.responseCacheTtlSeconds" type="number" min="5" max="86400" />
                  </label>
                </div>
              </article>

              <article
                class="stage-card drop-target"
                :class="{ hovering: dropHover === 'guardrail' }"
                @dragover.prevent="dropHover = 'guardrail'"
                @dragleave="dropHover = null"
                @drop.prevent="onDropGuardrail"
              >
                <header><span>2. 1차 가드레일</span><strong>마스킹 &amp; 외부 전송 게이트</strong></header>
                <label class="field">처리 정책
                  <select v-model="pipelineForm.guardrailAction" aria-label="가드레일 처리 정책">
                    <option v-for="opt in GUARDRAIL_SELECT_OPTIONS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                  </select>
                </label>
                <label class="field">1차 마스킹 AI 워커
                  <select v-model="pipelineForm.maskerDeploymentId" aria-label="1차 마스킹 AI 워커">
                    <option value="">내장 정규식 엔진 (기본)</option>
                    <option v-for="entry in eligibleMaskers" :key="entry.id" :value="entry.id">
                      {{ entry.displayName }} ({{ entry.sourceType === 'LOCAL_RUNTIME' ? 'Local LLM' : '무유출 확인 외부 AI' }})
                    </option>
                  </select>
                </label>
                <label class="inline-check">
                  <input v-model="pipelineForm.maskPersonNames" type="checkbox" />
                  <span>한국어 성명 가명화 (예: 홍길동 → 김가명)</span>
                </label>
                <small class="stage-note">{{ selectedMaskerEntry ? `${selectedMaskerEntry.sourceName} · ${selectedMaskerEntry.trustNote}` : '내장 규칙 엔진이 주민번호·휴대폰·성명을 형식 보존 가명화합니다.' }}</small>
              </article>

              <article class="stage-card">
                <header><span>3. 라우팅 · 폴백</span><strong>A/B 분산 &amp; P1/P2 드래그</strong></header>
                <div
                  class="priority-lane"
                  :class="{ hovering: dropHover === 'p1' }"
                  @dragover.prevent="dropHover = 'p1'"
                  @dragleave="dropHover = null"
                  @drop.prevent="onDropTargetPriority(1)"
                >
                  <div class="lane-title"><span>Priority 1 · 주 라우팅 ({{ primaryTargets.length }})</span><small>드롭 시 P1</small></div>
                  <div v-if="primaryTargets.length" class="lane-pills">
                    <div
                      v-for="target in primaryTargets"
                      :key="target.id"
                      draggable="true"
                      class="lane-pill"
                      @dragstart="startDragTarget(target)"
                    >
                      <span>{{ deploymentName(target.deploymentId) }} · W{{ target.weight }}</span>
                      <button type="button" class="mini-btn" @click="moveTargetPriority(target, 2)">P2로</button>
                    </div>
                  </div>
                  <small v-else class="stage-note">P1 대상이 없습니다.</small>
                </div>

                <div v-if="primaryTargets.length >= 2" class="ab-panel">
                  <div class="ab-label">
                    <span>A/B 비율</span>
                    <strong>{{ deploymentName(primaryTargets[0].deploymentId) }} {{ abPrimaryRatio }}% : {{ 100 - abPrimaryRatio }}% {{ deploymentName(primaryTargets[1].deploymentId) }}</strong>
                  </div>
                  <input v-model.number="abPrimaryRatio" type="range" min="1" max="99" aria-label="A/B 트래픽 비율 슬라이더" />
                  <div class="ab-presets">
                    <button type="button" class="mini-btn" @click="applyAbWeights(80, 20)">80% : 20%</button>
                    <button type="button" class="mini-btn" @click="applyAbWeights(70, 30)">70% : 30%</button>
                    <button type="button" class="mini-btn" @click="applyAbWeights(50, 50)">50% : 50%</button>
                    <button type="button" class="mini-btn primary" @click="applyAbWeights(abPrimaryRatio, 100 - abPrimaryRatio)">슬라이더 적용</button>
                  </div>
                </div>

                <div
                  class="priority-lane"
                  :class="{ hovering: dropHover === 'p2' }"
                  @dragover.prevent="dropHover = 'p2'"
                  @dragleave="dropHover = null"
                  @drop.prevent="onDropTargetPriority(2)"
                >
                  <div class="lane-title"><span>Priority 2+ · 자동 폴백 ({{ fallbackTargets.length }})</span><small>드롭 시 P2</small></div>
                  <div v-if="fallbackTargets.length" class="lane-pills">
                    <div
                      v-for="target in fallbackTargets"
                      :key="target.id"
                      draggable="true"
                      class="lane-pill"
                      @dragstart="startDragTarget(target)"
                    >
                      <span>{{ deploymentName(target.deploymentId) }} · P{{ target.priority }}</span>
                      <button type="button" class="mini-btn" @click="moveTargetPriority(target, 1)">P1로</button>
                    </div>
                  </div>
                  <small v-else class="stage-note">장애 시 대체할 P2+ 대상을 아래 Target 목록에서 추가하거나 여기로 드래그하세요.</small>
                </div>
              </article>

              <article class="stage-card">
                <header><span>4. 응답 게이트</span><strong>복원 &amp; 반환 정책</strong></header>
                <strong class="return-badge">{{ pipelineActionLabel(pipelineForm.guardrailAction) }}</strong>
                <p class="stage-note">{{ pipelineReturnSummary(pipelineForm.guardrailAction) }}</p>
              </article>
            </div>

            <div class="pipeline-simulator">
              <div class="simulator-row">
                <label class="field">실시간 가드레일 시뮬레이터
                  <textarea v-model="previewSampleText" rows="2" aria-label="가드레일 시뮬레이션 입력" placeholder="테스트할 문장을 입력하세요."></textarea>
                </label>
                <button type="button" class="secondary-button" :disabled="previewBusy || !previewSampleText.trim()" @click="runPipelinePreview">
                  {{ previewBusy ? '분석 중...' : '가드레일 시뮬레이션 실행' }}
                </button>
              </div>
              <div v-if="previewResult" class="simulator-grid">
                <div><span>외부 LLM 전달 본문 ({{ previewResult.maskerUsed }})</span><code>{{ previewResult.outboundTextForExternalLlm }}</code></div>
                <div>
                  <span>Vault 치환 매핑 ({{ previewResult.redactions.length }}건)</span>
                  <ul v-if="previewResult.redactions.length" class="vault-list">
                    <li v-for="(item, idx) in previewResult.redactions" :key="idx"><code>{{ item.original }}</code> → <code>{{ item.surrogate }}</code> <small>({{ item.category }})</small></li>
                  </ul>
                  <small v-else>감지·치환된 민감정보가 없습니다.</small>
                </div>
                <div><span>외부 LLM 응답 (가명화 상태)</span><code>{{ previewResult.simulatedExternalLlmReply }}</code></div>
                <div><span>최종 사용자 반환 ({{ previewResult.restoreOnReturn ? '원본 복원됨' : '가명화 유지' }})</span><code>{{ previewResult.finalUserResponse }}</code></div>
              </div>
            </div>
          </section>
          <div class="section-divider"><span>SERVICE TARGETS</span><button class="text-button" :disabled="!availableDeployments.length" @click="openCreateTarget">+ Target 추가</button></div>
          <div v-if="targets.length" class="route-stack"><div v-for="target in targets" :key="target.id" class="route-row"><span class="priority-badge">P{{ target.priority }}</span><div class="route-line"><i></i></div><span class="model-cube small">◇</span><div class="route-info"><strong>{{ deploymentName(target.deploymentId) }}</strong><small class="mono">{{ deploymentIdentifier(target.deploymentId) }}</small><small v-if="deploymentStatusLabel(target.deploymentId)" class="target-state-warning">{{ deploymentStatusLabel(target.deploymentId) }}</small></div><div class="route-meta"><span>Weight {{ target.weight }}</span><span v-if="target.degraded">Degraded</span><span>{{ target.followModelChanges === false ? '모델 고정' : '모델 자동 추적' }}</span><span>{{ target.enabled ? '활성' : '중지' }}</span></div><button class="icon-button" @click="openEditTarget(target)">···</button><button class="icon-button danger-text" @click="removeTarget(target)">×</button></div></div>
          <div v-else class="empty-state compact"><span>↝</span><p>연결된 Target이 없습니다.</p><button class="text-button" :disabled="!availableDeployments.length" @click="openCreateTarget">첫 Target 추가</button></div>
        </template>
        <div v-else class="empty-state centered"><span>⌘</span><h3>서비스를 선택하세요</h3><p>정책과 Failover 순서를 확인할 수 있습니다.</p></div>
      </article>
    </div>

    <BaseModal :open="serviceModal" :title="editingService ? '서비스 정책 편집' : '논리 서비스 생성'" description="실제 GPU나 모델 파일과 독립적인 API model 값을 구성합니다." @close="serviceModal = false">
      <div class="modal-form">
        <div class="form-grid">
          <label class="field">Service Key<input v-model.trim="serviceForm.serviceKey" :disabled="!!editingService" placeholder="text-pro" /></label>
          <label class="field">표시 이름<input v-model.trim="serviceForm.displayName" placeholder="Text Pro" /></label>
        </div>
        <div class="form-grid">
          <label class="field">Failover 정책<select v-model="serviceForm.failoverPolicy"><option>STRICT</option><option>COMPATIBLE</option><option>DEGRADED</option></select></label>
          <label class="field">Retry 정책<select v-model="serviceForm.retryPolicy"><option>SAFE</option><option>AGGRESSIVE</option></select></label>
        </div>
        <label class="field">데이터 경계 정책
          <select v-model="serviceForm.dataBoundaryPolicy">
            <option v-for="option in DATA_BOUNDARY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <p class="temperature-note">서비스 데이터 경계는 민감정보 탐지 여부와 관계없이 프로젝트·API 키 정책과 교집합(더 엄격한 정책 우선)으로 적용됩니다.</p>
        <div class="form-grid">
          <label class="field">Temperature 처리
            <select v-model="serviceForm.temperaturePolicy">
              <option value="REQUEST">요청값 기준 (기본)</option>
              <option value="FIXED">AICONNECT 고정값 사용</option>
              <option value="OMIT">요청에서 제거 · 모델 기본값 사용</option>
            </select>
          </label>
          <label v-if="serviceForm.temperaturePolicy === 'FIXED'" class="field">고정 Temperature (0–2)
            <input v-model.number="serviceForm.temperatureValue" type="number" min="0" max="2" step="0.1" placeholder="1" />
          </label>
        </div>
        <p class="temperature-note">요청값 기준은 기존 동작을 유지합니다. `OMIT`은 모델 기본값을 사용하고, `FIXED`는 실제 프로젝트 요청을 지정한 값으로 덮어씁니다. 현재 오류가 난 모델은 1로 설정하거나 모델 기본값을 선택하세요.</p>
        <div class="form-grid">
          <label class="field">추론 수준 · 외부 OpenAI API
            <select v-model="serviceForm.reasoningEffort">
              <option value="REQUEST">요청값 / 모델 기본값 유지</option>
              <option value="NONE">없음 (모델이 지원할 때)</option>
              <option value="MINIMAL">최소</option>
              <option value="LOW">낮음 · 속도 우선</option>
              <option value="MEDIUM">중간 · 균형</option>
              <option value="HIGH">높음</option>
              <option value="XHIGH">매우 높음</option>
              <option value="MAX">최대</option>
            </select>
          </label>
          <label class="toggle-field"><span>Fast mode · 외부 OpenAI API<small>OpenAI 요청에 service_tier=fast 지정</small></span><input v-model="serviceForm.openAiFastMode" type="checkbox" /></label>
        </div>
        <p class="temperature-note">이 두 설정은 외부 OpenAI 호환 Target에만 적용되고 Local LLM 요청에는 전달되지 않습니다. 추론 수준은 모델별 지원 값이 달라 미지원 값은 공급자 오류가 날 수 있습니다. GPT-6은 추론을 사용하는 동안 모델 입력 제한에 따라 temperature 같은 sampling 옵션이 제외될 수 있습니다. Fast mode는 지원 모델·프로젝트에서만 사용 가능하며 추가 요금이 발생할 수 있습니다. 끄면 프로젝트/요청의 기존 service_tier 또는 공급자 기본값을 유지합니다.</p>
        <CapabilityPicker v-model="serviceForm.requiredCapabilitiesJson" />
        <div class="form-grid">
          <label class="field">통화<select v-model="serviceForm.currency"><option value="KRW">원화 (KRW)</option><option value="USD">달러 (USD)</option></select></label>
          <label class="field">입력 단가 / 1M ({{ serviceForm.currency === 'USD' ? '$' : '₩' }})<input v-model.number="serviceForm.inputPricePerMillion" type="number" min="0" step="0.000001" /></label>
          <label class="field">출력 단가 / 1M ({{ serviceForm.currency === 'USD' ? '$' : '₩' }})<input v-model.number="serviceForm.outputPricePerMillion" type="number" min="0" step="0.000001" /></label>
        </div>
        <div class="form-grid">
          <label class="toggle-field"><span>Degraded 허용<small>마지막 저성능 대체 대상</small></span><input v-model="serviceForm.allowDegraded" type="checkbox" /></label>
          <label class="toggle-field"><span>서비스 활성화<small>외부 모델 목록에 노출</small></span><input v-model="serviceForm.enabled" type="checkbox" /></label>
        </div>
      </div>
      <template #footer><button class="secondary-button" @click="serviceModal = false">취소</button><button class="primary-button" :disabled="busy || !serviceForm.displayName || (!editingService && !serviceForm.serviceKey) || (serviceForm.temperaturePolicy === 'FIXED' && (!Number.isFinite(serviceForm.temperatureValue) || serviceForm.temperatureValue < 0 || serviceForm.temperatureValue > 2))" @click="saveService">저장</button></template>
    </BaseModal>

    <BaseModal :open="targetModal" :title="editingTarget ? 'Target 정책 편집' : 'Service Target 추가'" description="우선순위가 낮은 숫자부터 선택됩니다." @close="targetModal = false"><div class="modal-form"><label class="field">Deployment<select v-model="targetForm.deploymentId" :disabled="!!editingTarget"><option v-for="item in targetDeploymentOptions" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.providerModelId }}{{ deploymentStatusLabel(item.id) ? ` · ${deploymentStatusLabel(item.id)}` : '' }}</option><option v-if="editingTarget && !deploymentForId(editingTarget.deploymentId)" :value="editingTarget.deploymentId">모델 기록 없음 · {{ editingTarget.deploymentId }}</option></select></label><div class="form-grid three"><label class="field">Priority<input v-model.number="targetForm.priority" type="number" min="1" /></label><label class="field">Weight<input v-model.number="targetForm.weight" type="number" min="1" /></label><label class="field">동시성 재정의<input v-model.number="targetForm.maxConcurrencyOverride" type="number" min="1" placeholder="선택" /></label></div><div class="form-grid"><label class="toggle-field"><span>Degraded Target</span><input v-model="targetForm.degraded" type="checkbox" /></label><label class="toggle-field"><span>Target 활성화</span><input v-model="targetForm.enabled" type="checkbox" /></label></div><label class="toggle-field"><span>모델 변경 자동 추적<small>같은 Runtime에서 모델을 변경하면 새 Deployment로 자동 연결합니다. 여러 후보가 모호하면 임의 전환하지 않습니다.</small></span><input v-model="targetForm.followModelChanges" type="checkbox" /></label></div><template #footer><button class="secondary-button" @click="targetModal = false">취소</button><button class="primary-button" :disabled="busy || !targetForm.deploymentId" @click="saveTarget">Target 저장</button></template></BaseModal>

    <BaseModal :open="deleteServiceModal" title="논리 서비스 삭제" description="삭제 가능 여부를 먼저 검사했습니다. 이 작업은 되돌릴 수 없습니다." size="sm" @close="deleteServiceModal = false">
      <div class="deletion-check" v-if="deletionCheck"><strong>{{ deletionCheck.displayName }}</strong><code>model: {{ deletionCheck.serviceKey }}</code><template v-if="deletionCheck.canDelete"><p>Service can be deleted. Existing request, audit, and usage history will be preserved.</p></template><template v-else><p class="danger-text">삭제 차단 사유를 항목별로 정리하세요. 프로젝트 자체를 삭제할 필요는 없습니다.</p><ul><li v-if="deletionCheck.projectAccessCount"><b>프로젝트 권한: {{ deletionCheck.projectAccessCount }}개</b><small>프로젝트 → 서비스 권한에서 해당 논리 서비스의 권한을 해제하세요.<span v-if="deletionCheck.linkedProjectNames.length"> 대상: {{ deletionCheck.linkedProjectNames.join(', ') }}</span></small></li><li v-if="deletionCheck.targetCount"><b>Service Target: {{ deletionCheck.targetCount }}개</b><small>현재 서비스의 Target을 모두 제거하거나 다른 서비스로 이전하세요.</small></li></ul></template></div>
      <template #footer><button class="secondary-button" @click="deleteServiceModal = false">{{ deletionCheck?.canDelete ? '취소' : '확인' }}</button><button v-if="deletionCheck?.canDelete" class="secondary-button danger-text" :disabled="busy" @click="deleteService">서비스 삭제</button></template>
    </BaseModal>
  </section>
</template>

<style scoped>
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.detail-panel > .policy-summary { grid-template-columns: repeat(auto-fit, minmax(135px, 1fr)); }
.temperature-note { margin: -4px 0 0; color: var(--muted); font-size: 11px; line-height: 1.55; }
.route-info .target-state-warning { color: var(--danger); font-size: 8px; white-space: normal; }
.deletion-check { display: grid; gap: 10px; padding: 15px; border: 1px solid color-mix(in srgb, var(--danger) 35%, var(--border)); border-radius: 12px; background: var(--danger-dim); }.deletion-check strong { font-size: 15px; }.deletion-check code { padding: 8px; overflow: auto; border-radius: 7px; background: var(--surface); color: var(--text-soft); font-size: 10px; }.deletion-check p { margin: 0; color: var(--text-soft); font-size: 11px; line-height: 1.6; }.deletion-check ul { margin: 0; padding-left: 17px; display: grid; gap: 8px; color: var(--text-soft); font-size: 11px; line-height: 1.5; }.deletion-check li { display: grid; gap: 3px; }.deletion-check li small { color: var(--muted); font-size: 10px; line-height: 1.5; }
.pipeline-workbench { display: grid; gap: 14px; padding: 16px; border: 1px solid var(--border); border-radius: 14px; background: color-mix(in srgb, var(--surface) 88%, transparent); }
.pipeline-head { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-start; gap: 10px; }
.pipeline-head strong { display: block; font-size: 14px; }
.pipeline-head small { color: var(--muted); font-size: 11px; line-height: 1.5; }
.pipeline-head-actions { display: flex; align-items: center; gap: 8px; }
.version-pill { padding: 4px 10px; border-radius: 999px; border: 1px solid var(--border); font-size: 11px; font-weight: 600; color: var(--text-soft); background: var(--surface); }
.pipeline-palette { display: grid; gap: 10px; padding: 12px; border: 1px dashed var(--border); border-radius: 10px; background: var(--surface); }
.palette-group { display: grid; gap: 6px; }
.palette-label { font-size: 11px; font-weight: 600; color: var(--muted); }
.palette-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.palette-chip { display: grid; gap: 2px; text-align: left; padding: 7px 11px; border: 1px solid var(--border); border-radius: 9px; background: transparent; color: inherit; cursor: grab; font-size: 11px; }
.palette-chip span { color: var(--muted); font-size: 10px; }
.palette-chip.active { border-color: var(--accent, #2563eb); background: color-mix(in srgb, var(--accent, #2563eb) 12%, var(--surface)); }
.palette-chip.disabled { opacity: 0.52; cursor: not-allowed; }
.pipeline-flow { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
.stage-card { display: grid; gap: 8px; align-content: start; padding: 12px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface); }
.stage-card header { display: grid; gap: 2px; }
.stage-card header span { font-size: 10px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.04em; }
.stage-card header strong { font-size: 13px; }
.stage-card.drop-target.hovering, .priority-lane.hovering { border-color: var(--accent, #2563eb); background: color-mix(in srgb, var(--accent, #2563eb) 10%, var(--surface)); }
.stage-inline-row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.inline-check { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: var(--text-soft); cursor: pointer; }
.inline-check input { width: auto; }
.ttl-input { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: var(--muted); }
.ttl-input input { width: 72px; }
.stage-note { margin: 0; font-size: 11px; color: var(--muted); line-height: 1.5; }
.priority-lane { display: grid; gap: 6px; padding: 8px; border: 1px dashed var(--border); border-radius: 8px; }
.lane-title { display: flex; justify-content: space-between; align-items: center; font-size: 11px; font-weight: 600; color: var(--text-soft); }
.lane-title small { color: var(--muted); font-weight: 400; }
.lane-pills { display: grid; gap: 5px; }
.lane-pill { display: flex; justify-content: space-between; align-items: center; gap: 6px; padding: 5px 8px; border: 1px solid var(--border); border-radius: 7px; font-size: 11px; cursor: grab; background: var(--surface); }
.ab-panel { display: grid; gap: 6px; padding: 8px; border: 1px solid var(--border); border-radius: 8px; }
.ab-label { display: flex; justify-content: space-between; gap: 8px; font-size: 11px; }
.ab-presets { display: flex; flex-wrap: wrap; gap: 5px; }
.mini-btn { padding: 3px 8px; border: 1px solid var(--border); border-radius: 6px; background: transparent; color: inherit; font-size: 10px; cursor: pointer; }
.mini-btn.primary { border-color: var(--accent, #2563eb); color: var(--accent, #2563eb); font-weight: 600; }
.return-badge { font-size: 12px; color: var(--text-soft); }
.pipeline-simulator { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.simulator-row { display: grid; grid-template-columns: 1fr auto; gap: 10px; align-items: end; }
.simulator-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
.simulator-grid > div { display: grid; gap: 4px; align-content: start; padding: 9px; border: 1px solid var(--border); border-radius: 8px; }
.simulator-grid span { font-size: 10px; font-weight: 600; color: var(--muted); }
.simulator-grid code { display: block; white-space: pre-wrap; word-break: break-word; font-size: 11px; line-height: 1.5; }
.vault-list { margin: 0; padding-left: 16px; display: grid; gap: 4px; font-size: 11px; }
@media (max-width: 620px) { .detail-actions { justify-content: flex-start; } .simulator-row { grid-template-columns: 1fr; } }
</style>
