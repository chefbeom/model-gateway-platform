<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
type Attempt = { deploymentId: string; attemptNumber: number; status: string; latencyMs?: number; httpStatus?: number; errorType?: string; errorMessage?: string; responseStarted: boolean }
type RequestItem = { requestId: string; projectId: string; serviceId: string; finalDeploymentId?: string; status: string; inputTokens?: number; outputTokens?: number; estimatedCost?: number; latencyMs?: number; failoverCount: number; startedAt: string; attempts: Attempt[] }
type PageResult = { items: RequestItem[]; totalElements: number; totalPages: number }
type Delivery = { id: string; channelType?: string; eventType: string; status: string; errorMessage?: string; createdAt: string }
type Incident = { id: string; endpointBaseUrl?: string; status: string; reason: string; openedAt: string; recoveredAt?: string; deliveries: Delivery[] }
const tab = ref<'requests' | 'incidents'>('requests'); const requests = ref<RequestItem[]>([]); const total = ref(0); const incidents = ref<Incident[]>([])
const status = ref(''); const failoverOnly = ref(false); const incidentStatus = ref(''); const selectedRequest = ref<RequestItem | null>(null); const detailOpen = ref(false); const busy = ref(false); const message = ref('')

async function loadRequests() {
  if (!props.organizationId) return
  busy.value = true
  try { const query = new URLSearchParams({ size: '50', failoverOnly: String(failoverOnly.value) }); if (status.value) query.set('status', status.value); const page = await adminFetch<PageResult>(`/api/admin/organizations/${props.organizationId}/requests?${query}`, props.auth); requests.value = page.items; total.value = page.totalElements }
  catch (error) { message.value = error instanceof Error ? error.message : '요청 이력 조회 실패' } finally { busy.value = false }
}
async function loadIncidents() {
  if (!props.organizationId) return
  busy.value = true
  try { const query = incidentStatus.value ? `?status=${incidentStatus.value}` : ''; incidents.value = await adminFetch<Incident[]>(`/api/admin/organizations/${props.organizationId}/incidents${query}`, props.auth) }
  catch (error) { message.value = error instanceof Error ? error.message : '장애 조회 실패' } finally { busy.value = false }
}
function switchTab(value: 'requests' | 'incidents') { tab.value = value; value === 'requests' ? loadRequests() : loadIncidents() }
function inspect(item: RequestItem) { selectedRequest.value = item; detailOpen.value = true }
function duration(value?: number) { return value == null ? '-' : `${new Intl.NumberFormat().format(value)} ms` }
watch(() => props.organizationId, () => { loadRequests(); loadIncidents() }); onMounted(() => { loadRequests(); loadIncidents() })
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">OBSERVABILITY</p><h1>요청과 장애</h1><p>논리 요청부터 실제 Deployment Attempt와 알림 전달까지 추적합니다.</p></div><button class="secondary-button" :disabled="busy" @click="tab === 'requests' ? loadRequests() : loadIncidents()">↻ 새로고침</button></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="tab-bar"><button :class="{ active: tab === 'requests' }" @click="switchTab('requests')">요청 탐색기 <span>{{ total }}</span></button><button :class="{ active: tab === 'incidents' }" @click="switchTab('incidents')">장애 이력 <span>{{ incidents.length }}</span></button></div>
    <article v-if="tab === 'requests'" class="surface-card">
      <div class="filter-bar"><select v-model="status"><option value="">모든 상태</option><option>SUCCEEDED</option><option>FAILED</option><option>IN_PROGRESS</option></select><label class="filter-check"><input v-model="failoverOnly" type="checkbox" /> Failover 요청만</label><button class="secondary-button" @click="loadRequests">필터 적용</button></div>
      <div v-if="requests.length" class="data-table-wrap"><table class="data-table request-table"><thead><tr><th>시간</th><th>상태</th><th>요청 ID</th><th>토큰</th><th>지연</th><th>Failover</th><th>Attempts</th><th></th></tr></thead><tbody><tr v-for="item in requests" :key="item.requestId"><td>{{ new Date(item.startedAt).toLocaleString() }}</td><td><span class="status-chip tiny" :class="item.status === 'SUCCEEDED' ? 'healthy' : item.status === 'IN_PROGRESS' ? 'unknown' : 'unhealthy'">{{ item.status }}</span></td><td class="mono clipped">{{ item.requestId }}</td><td>{{ (item.inputTokens ?? 0) + (item.outputTokens ?? 0) }}</td><td>{{ duration(item.latencyMs) }}</td><td><span :class="{ 'accent-text': item.failoverCount > 0 }">{{ item.failoverCount }}</span></td><td>{{ item.attempts.length }}</td><td><button class="text-button" @click="inspect(item)">상세</button></td></tr></tbody></table></div><div v-else class="empty-state"><span>◎</span><h3>조건에 맞는 요청이 없습니다</h3><p>필터를 변경하거나 API 요청을 실행해보세요.</p></div>
    </article>
    <div v-else class="incident-grid">
      <div class="filter-bar surface-card"><select v-model="incidentStatus"><option value="">모든 상태</option><option>OPEN</option><option>RECOVERED</option></select><button class="secondary-button" @click="loadIncidents">필터 적용</button></div>
      <article v-for="incident in incidents" :key="incident.id" class="surface-card incident-card"><header><span class="status-chip" :class="incident.status === 'RECOVERED' ? 'healthy' : 'unhealthy'"><i></i>{{ incident.status }}</span><time>{{ new Date(incident.openedAt).toLocaleString() }}</time></header><h2>{{ incident.endpointBaseUrl ?? incident.id }}</h2><p>{{ incident.reason }}</p><div class="incident-timeline"><span><i></i>발생 {{ new Date(incident.openedAt).toLocaleString() }}</span><span v-if="incident.recoveredAt"><i></i>복구 {{ new Date(incident.recoveredAt).toLocaleString() }}</span></div><div class="delivery-row"><span v-for="delivery in incident.deliveries" :key="delivery.id" class="delivery-chip" :class="delivery.status.toLowerCase()">{{ delivery.channelType ?? '채널 없음' }} · {{ delivery.eventType }} · {{ delivery.status }}</span></div></article><div v-if="!incidents.length" class="surface-card empty-state"><span>✓</span><h3>표시할 장애가 없습니다</h3></div>
    </div>
    <BaseModal :open="detailOpen" title="요청 Attempt 상세" :description="selectedRequest?.requestId" size="lg" @close="detailOpen = false"><div v-if="selectedRequest" class="request-detail"><div class="detail-stats"><div><span>상태</span><strong>{{ selectedRequest.status }}</strong></div><div><span>최종 Deployment</span><strong class="mono">{{ selectedRequest.finalDeploymentId ?? '-' }}</strong></div><div><span>토큰</span><strong>{{ (selectedRequest.inputTokens ?? 0) + (selectedRequest.outputTokens ?? 0) }}</strong></div><div><span>총 지연</span><strong>{{ duration(selectedRequest.latencyMs) }}</strong></div></div><div class="attempt-timeline"><div v-for="attempt in selectedRequest.attempts" :key="attempt.attemptNumber" class="attempt-step"><span class="attempt-index">{{ attempt.attemptNumber }}</span><div><strong>{{ attempt.status }}</strong><small class="mono">{{ attempt.deploymentId }}</small><p>{{ duration(attempt.latencyMs) }} · HTTP {{ attempt.httpStatus ?? '-' }} · {{ attempt.errorType ?? '정상 완료' }}</p><small v-if="attempt.errorMessage" class="error-copy">{{ attempt.errorMessage }}</small></div></div></div></div><template #footer><button class="primary-button" @click="detailOpen = false">확인</button></template></BaseModal>
  </section>
</template>
