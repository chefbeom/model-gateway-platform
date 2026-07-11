<script setup lang="ts">
import { ref } from 'vue'
import { adminFetch, type AdminAuth } from './api'

type ServicePolicy = { id: string; serviceKey: string; displayName: string; failoverPolicy: 'STRICT' | 'COMPATIBLE' | 'DEGRADED'; retryPolicy: 'SAFE' | 'AGGRESSIVE'; allowDegraded: boolean; requiredCapabilitiesJson: string; inputPricePerMillion: number; outputPricePerMillion: number; enabled: boolean }
type TargetPolicy = { id: string; deploymentId: string; priority: number; weight: number; degraded: boolean; enabled: boolean; maxConcurrencyOverride?: number | null }

const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const services = ref<ServicePolicy[]>([])
const selected = ref<ServicePolicy | null>(null)
const targets = ref<TargetPolicy[]>([])
const busy = ref(false)
const message = ref('조직의 논리 서비스와 라우팅 대상을 불러오세요.')

function auth(): AdminAuth {
  const accessToken = sessionStorage.getItem('aiconnect.accessToken')
  return accessToken ? { accessToken } : { platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? '' }
}
async function loadServices() {
  busy.value = true
  try {
    services.value = await adminFetch<ServicePolicy[]>(`/api/admin/organizations/${organizationId.value}/services`, auth())
    selected.value = services.value[0] ?? null
    if (selected.value) await loadTargets(selected.value)
    message.value = `${services.value.length}개의 논리 서비스를 불러왔습니다.`
  } catch (error) { message.value = error instanceof Error ? error.message : '서비스 조회 실패' }
  finally { busy.value = false }
}
async function loadTargets(service: ServicePolicy) {
  selected.value = service
  targets.value = await adminFetch<TargetPolicy[]>(`/api/admin/services/${service.id}/targets`, auth())
}
async function saveService() {
  if (!selected.value) return
  busy.value = true
  try {
    selected.value = await adminFetch<ServicePolicy>(`/api/admin/services/${selected.value.id}`, auth(), { method: 'PATCH', body: JSON.stringify(selected.value) })
    message.value = '논리 서비스 정책을 저장했습니다. API 키와 model 값은 변경되지 않습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '서비스 정책 저장 실패' }
  finally { busy.value = false }
}
async function saveTarget(target: TargetPolicy) {
  if (!selected.value) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/services/${selected.value.id}/targets/${target.id}`, auth(), { method: 'PATCH', body: JSON.stringify(target) })
    await loadTargets(selected.value); message.value = '라우팅 대상 정책을 저장했습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '대상 정책 저장 실패' }
  finally { busy.value = false }
}
async function removeTarget(target: TargetPolicy) {
  if (!selected.value || !window.confirm('이 라우팅 대상을 삭제할까요?')) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/services/${selected.value.id}/targets/${target.id}`, auth(), { method: 'DELETE' })
    await loadTargets(selected.value); message.value = '라우팅 대상을 삭제했습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '대상 삭제 실패' }
  finally { busy.value = false }
}
</script>

<template>
  <main class="setup-main">
    <section class="setup-shell">
      <div><p class="eyebrow">ROUTING POLICY</p><h2>논리 서비스 운영</h2><p>사용자 API 키와 model 값을 유지한 채 Failover 정책과 대상 우선순위를 변경합니다.</p></div>
      <p class="notice">{{ message }}</p>
      <div class="filters"><input v-model="organizationId" placeholder="Organization UUID" /><button :disabled="busy || !organizationId" @click="loadServices">서비스 조회</button></div>
      <div class="policy-grid">
        <aside><button v-for="service in services" :key="service.id" :class="{ selected: selected?.id === service.id }" @click="loadTargets(service)"><strong>{{ service.serviceKey }}</strong><small>{{ service.displayName }}</small></button></aside>
        <article v-if="selected" class="policy-editor">
          <input v-model="selected.displayName" placeholder="표시 이름" />
          <div class="filters"><select v-model="selected.failoverPolicy"><option>STRICT</option><option>COMPATIBLE</option><option>DEGRADED</option></select><select v-model="selected.retryPolicy"><option>SAFE</option><option>AGGRESSIVE</option></select></div>
          <label><input v-model="selected.allowDegraded" type="checkbox" /> 성능 저하 대상 허용</label>
          <input v-model="selected.requiredCapabilitiesJson" placeholder='["STRUCTURED_OUTPUT"]' />
          <div class="filters"><input v-model.number="selected.inputPricePerMillion" type="number" min="0" placeholder="입력 단가" /><input v-model.number="selected.outputPricePerMillion" type="number" min="0" placeholder="출력 단가" /></div>
          <label><input v-model="selected.enabled" type="checkbox" /> 서비스 활성화</label><button :disabled="busy" @click="saveService">서비스 정책 저장</button>
          <h3>Service Targets</h3>
          <div v-for="target in targets" :key="target.id" class="target-row">
            <small class="mono">{{ target.deploymentId }}</small><div class="filters"><label>Priority<input v-model.number="target.priority" type="number" min="1" /></label><label>Weight<input v-model.number="target.weight" type="number" min="1" /></label><label>동시성<input v-model.number="target.maxConcurrencyOverride" type="number" min="1" /></label></div>
            <div class="filters"><label><input v-model="target.degraded" type="checkbox" /> Degraded</label><label><input v-model="target.enabled" type="checkbox" /> 활성</label><button :disabled="busy" @click="saveTarget(target)">저장</button><button class="danger" :disabled="busy" @click="removeTarget(target)">삭제</button></div>
          </div>
        </article>
      </div>
    </section>
  </main>
</template>

<style scoped>
.setup-main { padding-top: 0; } .setup-shell { border: 1px solid #24304a; border-radius: .9rem; padding: 1.25rem; background: #10182b; } .policy-grid { display: grid; grid-template-columns: 15rem 1fr; gap: 1rem; } aside { display: flex; flex-direction: column; gap: .5rem; } aside button { text-align: left; display: flex; flex-direction: column; } aside button.selected { border-color: #7f9cff; } .policy-editor { display: flex; flex-direction: column; gap: .65rem; } .filters { display: flex; gap: .5rem; align-items: center; } .filters > * { flex: 1; } select { min-height: 2.6rem; border: 1px solid #33415f; border-radius: .5rem; padding: .6rem; color: #f7f9ff; background: #121b31; } .target-row { border-top: 1px solid #2b3855; padding-top: .75rem; display: grid; gap: .5rem; } .danger { border-color: #7f3645; } @media (max-width: 760px) { .policy-grid { grid-template-columns: 1fr; } .filters { flex-wrap: wrap; } }
</style>
