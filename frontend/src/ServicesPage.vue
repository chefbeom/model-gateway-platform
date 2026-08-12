<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import CapabilityPicker from './CapabilityPicker.vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
type Service = { id: string; organizationId: string; serviceKey: string; displayName: string; failoverPolicy: 'STRICT' | 'COMPATIBLE' | 'DEGRADED'; retryPolicy: 'SAFE' | 'AGGRESSIVE'; allowDegraded: boolean; requiredCapabilitiesJson: string; inputPricePerMillion: number; outputPricePerMillion: number; currency?: 'KRW' | 'USD'; enabled: boolean }
type Target = { id: string; deploymentId: string; priority: number; weight: number; degraded: boolean; enabled: boolean; maxConcurrencyOverride?: number | null }
type ServiceDeletionCheck = { serviceId: string; serviceKey: string; displayName: string; projectAccessCount: number; targetCount: number; requestHistoryCount: number; linkedProjectNames: string[]; canDelete: boolean }

const services = ref<Service[]>([])
const selected = ref<Service | null>(null)
const targets = ref<Target[]>([])
const availableDeployments = ref<Deployment[]>([])
const busy = ref(false)
const message = ref('')
const serviceModal = ref(false)
const targetModal = ref(false)
const deleteServiceModal = ref(false)
const editingService = ref<Service | null>(null)
const editingTarget = ref<Target | null>(null)
const deletionCheck = ref<ServiceDeletionCheck | null>(null)
const serviceForm = ref({ serviceKey: '', displayName: '', failoverPolicy: 'STRICT' as Service['failoverPolicy'], retryPolicy: 'SAFE' as Service['retryPolicy'], allowDegraded: false, requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', enabled: true })
const targetForm = ref({ deploymentId: '', priority: 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null as number | null })

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
    adminFetch<Array<{ id: string }>>(`/api/admin/organizations/${props.organizationId}/external-providers`, props.auth)
  ])
  const [localGroups, externalGroups] = await Promise.all([
    Promise.all(endpoints.map(endpoint => adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpoint.id}/deployments`, props.auth))),
    Promise.all(providers.map(provider => adminFetch<Deployment[]>(`/api/admin/external-providers/${provider.id}/models`, props.auth)))
  ])
  availableDeployments.value = [...localGroups.flat(), ...externalGroups.flat()].filter(item => item.enabled && item.loaded !== false)
}

function openCreateService() {
  editingService.value = null
  serviceForm.value = { serviceKey: '', displayName: '', failoverPolicy: 'STRICT', retryPolicy: 'SAFE', allowDegraded: false, requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW' as 'KRW' | 'USD', enabled: true }
  serviceModal.value = true
}

function openEditService() {
  if (!selected.value) return
  editingService.value = selected.value
  serviceForm.value = { ...selected.value, currency: selected.value.currency ?? 'KRW' }
  serviceModal.value = true
}

async function saveService() {
  busy.value = true
  try {
    if (editingService.value) {
      selected.value = await adminFetch<Service>(`/api/admin/services/${editingService.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify(serviceForm.value) })
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
  targetForm.value = { deploymentId: availableDeployments.value[0]?.id ?? '', priority: targets.value.length + 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null }
  targetModal.value = true
}

function openEditTarget(target: Target) {
  editingTarget.value = target
  targetForm.value = { deploymentId: target.deploymentId, priority: target.priority, weight: target.weight, degraded: target.degraded, enabled: target.enabled, maxConcurrencyOverride: target.maxConcurrencyOverride ?? null }
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
  if (!selected.value || !confirm('이 Target을 라우팅에서 제거할까요?')) return
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

function deploymentName(id: string) {
  const item = availableDeployments.value.find(candidate => candidate.id === id)
  return item ? `${item.externalProviderId ? 'CLOUD · ' : ''}${item.displayName}` : id
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
          <div class="policy-summary"><div><span>Failover</span><strong>{{ selected.failoverPolicy }}</strong></div><div><span>Retry</span><strong>{{ selected.retryPolicy }}</strong></div><div><span>입력 단가 / 1M ({{ selected.currency ?? 'KRW' }})</span><strong>{{ selected.currency === 'USD' ? '$' : '₩' }}{{ selected.inputPricePerMillion }}</strong></div><div><span>출력 단가 / 1M ({{ selected.currency ?? 'KRW' }})</span><strong>{{ selected.currency === 'USD' ? '$' : '₩' }}{{ selected.outputPricePerMillion }}</strong></div></div>
          <div class="section-divider"><span>SERVICE TARGETS</span><button class="text-button" :disabled="!availableDeployments.length" @click="openCreateTarget">+ Target 추가</button></div>
          <div v-if="targets.length" class="route-stack"><div v-for="target in targets" :key="target.id" class="route-row"><span class="priority-badge">P{{ target.priority }}</span><div class="route-line"><i></i></div><span class="model-cube small">◇</span><div class="route-info"><strong>{{ deploymentName(target.deploymentId) }}</strong><small class="mono">{{ target.deploymentId }}</small></div><div class="route-meta"><span>Weight {{ target.weight }}</span><span v-if="target.degraded">Degraded</span><span>{{ target.enabled ? '활성' : '중지' }}</span></div><button class="icon-button" @click="openEditTarget(target)">···</button><button class="icon-button danger-text" @click="removeTarget(target)">×</button></div></div>
          <div v-else class="empty-state compact"><span>↝</span><p>연결된 Target이 없습니다.</p><button class="text-button" :disabled="!availableDeployments.length" @click="openCreateTarget">첫 Target 추가</button></div>
        </template>
        <div v-else class="empty-state centered"><span>⌘</span><h3>서비스를 선택하세요</h3><p>정책과 Failover 순서를 확인할 수 있습니다.</p></div>
      </article>
    </div>

    <BaseModal :open="serviceModal" :title="editingService ? '서비스 정책 편집' : '논리 서비스 생성'" description="실제 GPU나 모델 파일과 독립적인 API model 값을 구성합니다." @close="serviceModal = false"><div class="modal-form"><div class="form-grid"><label class="field">Service Key<input v-model.trim="serviceForm.serviceKey" :disabled="!!editingService" placeholder="text-pro" /></label><label class="field">표시 이름<input v-model.trim="serviceForm.displayName" placeholder="Text Pro" /></label></div><div class="form-grid"><label class="field">Failover 정책<select v-model="serviceForm.failoverPolicy"><option>STRICT</option><option>COMPATIBLE</option><option>DEGRADED</option></select></label><label class="field">Retry 정책<select v-model="serviceForm.retryPolicy"><option>SAFE</option><option>AGGRESSIVE</option></select></label></div><CapabilityPicker v-model="serviceForm.requiredCapabilitiesJson" /><div class="form-grid"><label class="field">통화<select v-model="serviceForm.currency"><option value="KRW">원화 (KRW)</option><option value="USD">달러 (USD)</option></select></label><label class="field">입력 단가 / 1M ({{ serviceForm.currency === 'USD' ? '$' : '₩' }})<input v-model.number="serviceForm.inputPricePerMillion" type="number" min="0" step="0.000001" /></label><label class="field">출력 단가 / 1M ({{ serviceForm.currency === 'USD' ? '$' : '₩' }})<input v-model.number="serviceForm.outputPricePerMillion" type="number" min="0" step="0.000001" /></label></div><div class="form-grid"><label class="toggle-field"><span>Degraded 허용<small>마지막 저성능 대체 대상</small></span><input v-model="serviceForm.allowDegraded" type="checkbox" /></label><label class="toggle-field"><span>서비스 활성화<small>외부 모델 목록에 노출</small></span><input v-model="serviceForm.enabled" type="checkbox" /></label></div></div><template #footer><button class="secondary-button" @click="serviceModal = false">취소</button><button class="primary-button" :disabled="busy || !serviceForm.displayName || (!editingService && !serviceForm.serviceKey)" @click="saveService">저장</button></template></BaseModal>

    <BaseModal :open="targetModal" :title="editingTarget ? 'Target 정책 편집' : 'Service Target 추가'" description="우선순위가 낮은 숫자부터 선택됩니다." @close="targetModal = false"><div class="modal-form"><label class="field">Deployment<select v-model="targetForm.deploymentId" :disabled="!!editingTarget"><option v-for="item in availableDeployments" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.providerModelId }}</option></select></label><div class="form-grid three"><label class="field">Priority<input v-model.number="targetForm.priority" type="number" min="1" /></label><label class="field">Weight<input v-model.number="targetForm.weight" type="number" min="1" /></label><label class="field">동시성 재정의<input v-model.number="targetForm.maxConcurrencyOverride" type="number" min="1" placeholder="선택" /></label></div><div class="form-grid"><label class="toggle-field"><span>Degraded Target</span><input v-model="targetForm.degraded" type="checkbox" /></label><label class="toggle-field"><span>Target 활성화</span><input v-model="targetForm.enabled" type="checkbox" /></label></div></div><template #footer><button class="secondary-button" @click="targetModal = false">취소</button><button class="primary-button" :disabled="busy || !targetForm.deploymentId" @click="saveTarget">Target 저장</button></template></BaseModal>

    <BaseModal :open="deleteServiceModal" title="논리 서비스 삭제" description="삭제 가능 여부를 먼저 검사했습니다. 이 작업은 되돌릴 수 없습니다." size="sm" @close="deleteServiceModal = false">
      <div class="deletion-check" v-if="deletionCheck"><strong>{{ deletionCheck.displayName }}</strong><code>model: {{ deletionCheck.serviceKey }}</code><template v-if="deletionCheck.canDelete"><p>Service can be deleted. Existing request, audit, and usage history will be preserved.</p></template><template v-else><p class="danger-text">삭제 차단 사유를 항목별로 정리하세요. 프로젝트 자체를 삭제할 필요는 없습니다.</p><ul><li v-if="deletionCheck.projectAccessCount"><b>프로젝트 권한: {{ deletionCheck.projectAccessCount }}개</b><small>프로젝트 & API 키 → 서비스 권한에서 해당 논리 서비스의 권한을 해제하세요.<span v-if="deletionCheck.linkedProjectNames.length"> 대상: {{ deletionCheck.linkedProjectNames.join(', ') }}</span></small></li><li v-if="deletionCheck.targetCount"><b>Service Target: {{ deletionCheck.targetCount }}개</b><small>현재 서비스의 Target을 모두 제거하거나 다른 서비스로 이전하세요.</small></li></ul></template></div>
      <template #footer><button class="secondary-button" @click="deleteServiceModal = false">{{ deletionCheck?.canDelete ? '취소' : '확인' }}</button><button v-if="deletionCheck?.canDelete" class="secondary-button danger-text" :disabled="busy" @click="deleteService">서비스 삭제</button></template>
    </BaseModal>
  </section>
</template>

<style scoped>
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.deletion-check { display: grid; gap: 10px; padding: 15px; border: 1px solid color-mix(in srgb, var(--danger) 35%, var(--border)); border-radius: 12px; background: var(--danger-dim); }.deletion-check strong { font-size: 15px; }.deletion-check code { padding: 8px; overflow: auto; border-radius: 7px; background: var(--surface); color: var(--text-soft); font-size: 10px; }.deletion-check p { margin: 0; color: var(--text-soft); font-size: 11px; line-height: 1.6; }.deletion-check ul { margin: 0; padding-left: 17px; display: grid; gap: 8px; color: var(--text-soft); font-size: 11px; line-height: 1.5; }.deletion-check li { display: grid; gap: 3px; }.deletion-check li small { color: var(--muted); font-size: 10px; line-height: 1.5; }
@media (max-width: 620px) { .detail-actions { justify-content: flex-start; } }
</style>