<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
type Service = { id: string; organizationId: string; serviceKey: string; displayName: string; failoverPolicy: 'STRICT' | 'COMPATIBLE' | 'DEGRADED'; retryPolicy: 'SAFE' | 'AGGRESSIVE'; allowDegraded: boolean; requiredCapabilitiesJson: string; inputPricePerMillion: number; outputPricePerMillion: number; enabled: boolean }
type Target = { id: string; deploymentId: string; priority: number; weight: number; degraded: boolean; enabled: boolean; maxConcurrencyOverride?: number | null }

const services = ref<Service[]>([]); const selected = ref<Service | null>(null); const targets = ref<Target[]>([])
const availableDeployments = ref<Deployment[]>([]); const busy = ref(false); const message = ref('')
const serviceModal = ref(false); const targetModal = ref(false); const editingService = ref<Service | null>(null); const editingTarget = ref<Target | null>(null)
const serviceForm = ref({ serviceKey: '', displayName: '', failoverPolicy: 'STRICT' as Service['failoverPolicy'], retryPolicy: 'SAFE' as Service['retryPolicy'], allowDegraded: false, requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, enabled: true })
const targetForm = ref({ deploymentId: '', priority: 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null as number | null })

async function load() {
  if (!props.organizationId) { services.value = []; selected.value = null; return }
  busy.value = true
  try {
    services.value = await adminFetch<Service[]>(`/api/admin/organizations/${props.organizationId}/services`, props.auth)
    if (selected.value && !services.value.some(item => item.id === selected.value?.id)) selected.value = null
    if (!selected.value && services.value.length) selected.value = services.value[0]
    if (selected.value) await loadTargets(selected.value)
    await loadDeployments()
  } catch (error) { message.value = error instanceof Error ? error.message : '서비스를 불러오지 못했습니다.' }
  finally { busy.value = false }
}
async function loadTargets(service: Service) { selected.value = service; targets.value = await adminFetch<Target[]>(`/api/admin/services/${service.id}/targets`, props.auth) }
async function loadDeployments() {
  const endpoints = await adminFetch<Endpoint[]>('/api/admin/runtime-endpoints', props.auth)
  const groups = await Promise.all(endpoints.map(endpoint => adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpoint.id}/deployments`, props.auth)))
  availableDeployments.value = groups.flat().filter(item => item.enabled && item.loaded)
}
function openCreateService() { editingService.value = null; serviceForm.value = { serviceKey: '', displayName: '', failoverPolicy: 'STRICT', retryPolicy: 'SAFE', allowDegraded: false, requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, enabled: true }; serviceModal.value = true }
function openEditService() { if (!selected.value) return; editingService.value = selected.value; serviceForm.value = { ...selected.value }; serviceModal.value = true }
async function saveService() {
  busy.value = true
  try {
    if (editingService.value) {
      selected.value = await adminFetch<Service>(`/api/admin/services/${editingService.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify(serviceForm.value) })
      message.value = '서비스 정책을 저장했습니다. 외부 model 값은 유지됩니다.'
    } else {
      const created = await adminFetch<Service>('/api/admin/services', props.auth, { method: 'POST', body: JSON.stringify({ organizationId: props.organizationId, ...serviceForm.value }) })
      selected.value = created; message.value = '새 논리 서비스를 생성했습니다.'
    }
    serviceModal.value = false; await load()
  } catch (error) { message.value = error instanceof Error ? error.message : '서비스 저장에 실패했습니다.' }
  finally { busy.value = false }
}
function openCreateTarget() { editingTarget.value = null; targetForm.value = { deploymentId: availableDeployments.value[0]?.id ?? '', priority: targets.value.length + 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null }; targetModal.value = true }
function openEditTarget(target: Target) { editingTarget.value = target; targetForm.value = { deploymentId: target.deploymentId, priority: target.priority, weight: target.weight, degraded: target.degraded, enabled: target.enabled, maxConcurrencyOverride: target.maxConcurrencyOverride ?? null }; targetModal.value = true }
async function saveTarget() {
  if (!selected.value) return
  busy.value = true
  try {
    if (editingTarget.value) await adminFetch(`/api/admin/services/${selected.value.id}/targets/${editingTarget.value.id}`, props.auth, { method: 'PATCH', body: JSON.stringify(targetForm.value) })
    else await adminFetch(`/api/admin/services/${selected.value.id}/targets`, props.auth, { method: 'POST', body: JSON.stringify(targetForm.value) })
    targetModal.value = false; message.value = '라우팅 대상을 저장했습니다.'; await loadTargets(selected.value)
  } catch (error) { message.value = error instanceof Error ? error.message : 'Target 저장에 실패했습니다.' }
  finally { busy.value = false }
}
async function removeTarget(target: Target) {
  if (!selected.value || !confirm('이 Target을 라우팅에서 제거할까요?')) return
  await adminFetch(`/api/admin/services/${selected.value.id}/targets/${target.id}`, props.auth, { method: 'DELETE' }); await loadTargets(selected.value)
}
function deploymentName(id: string) { const item = availableDeployments.value.find(candidate => candidate.id === id); return item ? item.displayName : id }
watch(() => props.organizationId, load); onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">LOGICAL AI SERVICES</p><h1>서비스와 라우팅</h1><p>사용자에게 노출되는 model 값과 실제 Deployment의 우선순위를 분리합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load">↻ 새로고침</button><button class="primary-button" :disabled="!organizationId" @click="openCreateService">＋ 서비스 생성</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="split-layout services-layout">
      <article class="surface-card list-panel"><header class="card-header"><div><span class="card-kicker">SERVICE CATALOG</span><h2>논리 서비스</h2></div><span class="count-badge">{{ services.length }}</span></header><div v-if="services.length" class="entity-list service-entity-list"><button v-for="service in services" :key="service.id" :class="{ active: selected?.id === service.id }" @click="loadTargets(service)"><span class="service-glyph">⌘</span><span><strong>{{ service.serviceKey }}</strong><small>{{ service.displayName }}</small></span><span class="status-chip tiny" :class="service.enabled ? 'healthy' : 'unknown'">{{ service.enabled ? 'ACTIVE' : 'OFF' }}</span></button></div><div v-else class="empty-state"><span>⌘</span><h3>논리 서비스가 없습니다</h3><p>첫 서비스와 라우팅 정책을 생성하세요.</p></div></article>
      <article class="surface-card detail-panel">
        <template v-if="selected"><header class="detail-header"><div><span class="status-chip" :class="selected.enabled ? 'healthy' : 'unknown'"><i></i>{{ selected.enabled ? 'ACTIVE' : 'DISABLED' }}</span><h2>{{ selected.displayName }}</h2><p class="mono">model: {{ selected.serviceKey }}</p></div><button class="secondary-button" @click="openEditService">정책 편집</button></header><div class="policy-summary"><div><span>Failover</span><strong>{{ selected.failoverPolicy }}</strong></div><div><span>Retry</span><strong>{{ selected.retryPolicy }}</strong></div><div><span>입력 단가 / 1M</span><strong>{{ selected.inputPricePerMillion }}</strong></div><div><span>출력 단가 / 1M</span><strong>{{ selected.outputPricePerMillion }}</strong></div></div><div class="section-divider"><span>SERVICE TARGETS</span><button class="text-button" :disabled="!availableDeployments.length" @click="openCreateTarget">＋ Target 추가</button></div><div v-if="targets.length" class="route-stack"><div v-for="target in targets" :key="target.id" class="route-row"><span class="priority-badge">P{{ target.priority }}</span><div class="route-line"><i></i></div><span class="model-cube small">◇</span><div class="route-info"><strong>{{ deploymentName(target.deploymentId) }}</strong><small class="mono">{{ target.deploymentId }}</small></div><div class="route-meta"><span>Weight {{ target.weight }}</span><span v-if="target.degraded">Degraded</span><span>{{ target.enabled ? '활성' : '중지' }}</span></div><button class="icon-button" @click="openEditTarget(target)">···</button><button class="icon-button danger-text" @click="removeTarget(target)">×</button></div></div><div v-else class="empty-state compact"><span>↝</span><p>연결된 Target이 없습니다.</p><button class="text-button" :disabled="!availableDeployments.length" @click="openCreateTarget">첫 Target 추가</button></div></template><div v-else class="empty-state centered"><span>⌘</span><h3>서비스를 선택하세요</h3><p>정책과 Failover 순서를 확인할 수 있습니다.</p></div>
      </article>
    </div>
    <BaseModal :open="serviceModal" :title="editingService ? '서비스 정책 편집' : '논리 서비스 생성'" description="실제 GPU나 모델 파일과 독립적인 API model 값을 구성합니다." @close="serviceModal = false"><div class="modal-form"><div class="form-grid"><label class="field">Service Key<input v-model.trim="serviceForm.serviceKey" :disabled="!!editingService" placeholder="text-pro" /></label><label class="field">표시 이름<input v-model.trim="serviceForm.displayName" placeholder="Text Pro" /></label></div><div class="form-grid"><label class="field">Failover 정책<select v-model="serviceForm.failoverPolicy"><option>STRICT</option><option>COMPATIBLE</option><option>DEGRADED</option></select></label><label class="field">Retry 정책<select v-model="serviceForm.retryPolicy"><option>SAFE</option><option>AGGRESSIVE</option></select></label></div><label class="field">필수 Capability<textarea v-model="serviceForm.requiredCapabilitiesJson" rows="3" placeholder='["STRUCTURED_OUTPUT"]'></textarea></label><div class="form-grid"><label class="field">입력 단가 / 1M<input v-model.number="serviceForm.inputPricePerMillion" type="number" min="0" /></label><label class="field">출력 단가 / 1M<input v-model.number="serviceForm.outputPricePerMillion" type="number" min="0" /></label></div><div class="form-grid"><label class="toggle-field"><span>Degraded 허용<small>마지막 저성능 대체 대상</small></span><input v-model="serviceForm.allowDegraded" type="checkbox" /></label><label class="toggle-field"><span>서비스 활성화<small>외부 모델 목록에 노출</small></span><input v-model="serviceForm.enabled" type="checkbox" /></label></div></div><template #footer><button class="secondary-button" @click="serviceModal = false">취소</button><button class="primary-button" :disabled="busy || !serviceForm.displayName || (!editingService && !serviceForm.serviceKey)" @click="saveService">저장</button></template></BaseModal>
    <BaseModal :open="targetModal" :title="editingTarget ? 'Target 정책 편집' : 'Service Target 추가'" description="우선순위가 낮은 숫자부터 선택됩니다." @close="targetModal = false"><div class="modal-form"><label class="field">Deployment<select v-model="targetForm.deploymentId" :disabled="!!editingTarget"><option v-for="item in availableDeployments" :key="item.id" :value="item.id">{{ item.displayName }} · {{ item.providerModelId }}</option></select></label><div class="form-grid three"><label class="field">Priority<input v-model.number="targetForm.priority" type="number" min="1" /></label><label class="field">Weight<input v-model.number="targetForm.weight" type="number" min="1" /></label><label class="field">동시성 재정의<input v-model.number="targetForm.maxConcurrencyOverride" type="number" min="1" placeholder="선택" /></label></div><div class="form-grid"><label class="toggle-field"><span>Degraded Target</span><input v-model="targetForm.degraded" type="checkbox" /></label><label class="toggle-field"><span>Target 활성화</span><input v-model="targetForm.enabled" type="checkbox" /></label></div></div><template #footer><button class="secondary-button" @click="targetModal = false">취소</button><button class="primary-button" :disabled="busy || !targetForm.deploymentId" @click="saveTarget">Target 저장</button></template></BaseModal>
  </section>
</template>
