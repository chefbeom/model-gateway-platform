<script setup lang="ts">
import { ref } from 'vue'

type Attempt = { deploymentId: string; attemptNumber: number; status: string; latencyMs?: number; httpStatus?: number; errorType?: string }
type RequestItem = { requestId: string; projectId: string; serviceId: string; finalDeploymentId?: string; status: string; inputTokens?: number; outputTokens?: number; estimatedCost?: number; latencyMs?: number; failoverCount: number; startedAt: string; attempts: Attempt[] }
type PageResult = { items: RequestItem[]; page: number; size: number; totalElements: number; totalPages: number }

const organizationId = ref(sessionStorage.getItem('aiconnect.requestExplorerOrganization') ?? '')
const status = ref('')
const failoverOnly = ref(false)
const result = ref<PageResult | null>(null)
const message = ref('조직 ID를 입력해 요청과 실제 Runtime 시도를 검색할 수 있습니다.')
const busy = ref(false)

function authorization(): HeadersInit {
  const accessToken = sessionStorage.getItem('aiconnect.accessToken')
  if (accessToken) return { Authorization: `Bearer ${accessToken}` }
  return { 'X-Admin-Token': sessionStorage.getItem('aiconnect.platformToken') ?? '' }
}
async function search() {
  busy.value = true
  try {
    sessionStorage.setItem('aiconnect.requestExplorerOrganization', organizationId.value)
    const query = new URLSearchParams({ failoverOnly: String(failoverOnly.value), size: '50' })
    if (status.value) query.set('status', status.value)
    const response = await fetch(`/api/admin/organizations/${organizationId.value}/requests?${query}`, { headers: authorization() })
    if (!response.ok) throw new Error((await response.json().catch(() => ({ message: response.statusText }))).message ?? '요청 탐색 실패')
    result.value = await response.json(); message.value = `${result.value?.totalElements ?? 0}개의 요청을 찾았습니다.`
  } catch (error) { message.value = error instanceof Error ? error.message : '요청 탐색 실패' }
  finally { busy.value = false }
}
</script>

<template>
  <main class="explorer-main">
    <section class="explorer-shell">
      <div class="explorer-heading"><div><p class="eyebrow">OBSERVABILITY</p><h2>관리자 Request Explorer</h2><p>논리 서비스 요청과 실제 배포별 시도를 함께 확인합니다.</p></div>
        <div class="filters"><input v-model="organizationId" placeholder="Organization UUID" /><select v-model="status"><option value="">모든 상태</option><option>SUCCEEDED</option><option>FAILED</option><option>IN_PROGRESS</option></select><label><input v-model="failoverOnly" type="checkbox" /> Failover만</label><button :disabled="busy || !organizationId" @click="search">{{ busy ? '검색 중…' : '검색' }}</button></div>
      </div>
      <p class="notice">{{ message }}</p>
      <div v-if="result?.items.length" class="request-list">
        <details v-for="item in result.items" :key="item.requestId">
          <summary><span class="status" :class="item.status === 'SUCCEEDED' ? 'healthy' : 'unhealthy'">{{ item.status }}</span><span class="mono">{{ item.requestId }}</span><span>{{ new Date(item.startedAt).toLocaleString() }}</span><span>{{ item.inputTokens ?? 0 }} + {{ item.outputTokens ?? 0 }} tokens</span><span>{{ item.latencyMs ?? 0 }} ms</span><span>Failover {{ item.failoverCount }}</span></summary>
          <div class="attempts"><div v-for="attempt in item.attempts" :key="attempt.attemptNumber"><strong>#{{ attempt.attemptNumber }} {{ attempt.status }}</strong><span class="mono">{{ attempt.deploymentId }}</span><span>{{ attempt.latencyMs ?? 0 }} ms · HTTP {{ attempt.httpStatus ?? '-' }} · {{ attempt.errorType ?? '정상' }}</span></div></div>
        </details>
      </div>
    </section>
  </main>
</template>

<style scoped>
.explorer-main { padding-top: 0; }
.explorer-shell { border: 1px solid #24304a; border-radius: .9rem; padding: 1.25rem; background: #10182b; }
.explorer-heading { display: flex; justify-content: space-between; gap: 2rem; align-items: end; }
.filters { min-width: min(100%, 420px); display: grid; grid-template-columns: 1fr 130px; gap: .5rem; } select { border: 1px solid #33415f; border-radius: .5rem; padding: .65rem; color: #f7f9ff; background: #121b31; } .filters label { display: flex; align-items: center; gap: .45rem; } .filters label input { width: auto; }
details { border-bottom: 1px solid #24304a; padding: .75rem 0; } summary { display: grid; grid-template-columns: auto 1fr auto auto auto auto; gap: .75rem; align-items: center; cursor: pointer; font-size: .8rem; } .mono { overflow: hidden; text-overflow: ellipsis; font-family: ui-monospace, monospace; }
.attempts { margin: .75rem 0 0 2rem; display: grid; gap: .5rem; } .attempts div { display: grid; grid-template-columns: 120px 1fr auto; gap: .75rem; padding: .65rem; background: #151f36; font-size: .78rem; }
@media (max-width: 900px) { .explorer-heading { display: grid; } summary { grid-template-columns: auto 1fr; } .attempts div { grid-template-columns: 1fr; } }
</style>
