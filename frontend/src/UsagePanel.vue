<script setup lang="ts">
import { ref } from 'vue'

type Usage = { requestCount: number; inputTokens: number; outputTokens: number; estimatedCost: number; failedRequests: number }
type RequestItem = { requestId: string; serviceKey?: string; serviceDisplayName?: string; deploymentDisplayName?: string; stream: boolean; status: string; inputTokens?: number; outputTokens?: number; estimatedCost?: number; latencyMs?: number; failoverCount: number; httpStatus?: number; errorCode?: string; startedAt: string }

const apiKey = ref(sessionStorage.getItem('aiconnect.consumerApiKey') ?? '')
const usage = ref<Usage | null>(null)
const requests = ref<RequestItem[]>([])
const message = ref('프로젝트 API 키를 입력하면 사용량과 요청 이력을 확인할 수 있습니다.')
const busy = ref(false)

async function api<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Authorization: `Bearer ${apiKey.value}` } })
  if (!response.ok) throw new Error((await response.json().catch(() => ({ message: response.statusText }))).message ?? '사용량 조회 실패')
  return response.json() as Promise<T>
}
async function loadUsage() {
  busy.value = true
  try {
    sessionStorage.setItem('aiconnect.consumerApiKey', apiKey.value)
    const [summary, history] = await Promise.all([api<Usage>('/api/me/usage'), api<RequestItem[]>('/api/me/requests')])
    usage.value = summary; requests.value = history; message.value = `최근 ${history.length}개 요청을 불러왔습니다.`
  } catch (error) { message.value = error instanceof Error ? error.message : '사용량 조회 실패' }
  finally { busy.value = false }
}
function number(value?: number) { return new Intl.NumberFormat('ko-KR').format(value ?? 0) }
function cost(value?: number) { return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 6 }).format(value ?? 0)} 원` }
</script>

<template>
  <main class="usage-main">
    <section class="usage-shell">
      <div class="usage-heading">
        <div><p class="eyebrow">CONSUMER</p><h2>프로젝트 사용량</h2><p>프롬프트 원문 대신 모델·토큰·비용·상태 메타데이터를 조회합니다.</p></div>
        <div class="key-entry"><label for="consumer-key">프로젝트 API 키</label><input id="consumer-key" v-model="apiKey" type="password" placeholder="sk_llmg_..." @keyup.enter="loadUsage" /><button :disabled="busy || !apiKey" @click="loadUsage">{{ busy ? '조회 중…' : '사용량 조회' }}</button></div>
      </div>
      <p class="notice" role="status">{{ message }}</p>
      <div v-if="usage" class="summary-grid">
        <div><small>요청 수</small><strong>{{ number(usage.requestCount) }}</strong></div>
        <div><small>입력 토큰</small><strong>{{ number(usage.inputTokens) }}</strong></div>
        <div><small>출력 토큰</small><strong>{{ number(usage.outputTokens) }}</strong></div>
        <div><small>예상 비용</small><strong>{{ cost(usage.estimatedCost) }}</strong></div>
        <div><small>실패 요청</small><strong>{{ number(usage.failedRequests) }}</strong></div>
      </div>
      <div v-if="requests.length" class="request-table">
        <table>
          <thead><tr><th>시간</th><th>논리 모델</th><th>실제 배포</th><th>방식</th><th>상태</th><th>토큰</th><th>비용</th><th>지연</th><th>Failover</th><th>요청 ID</th></tr></thead>
          <tbody><tr v-for="item in requests" :key="item.requestId"><td>{{ new Date(item.startedAt).toLocaleString() }}</td><td><strong>{{ item.serviceKey ?? '-' }}</strong><small>{{ item.serviceDisplayName }}</small></td><td>{{ item.deploymentDisplayName ?? '-' }}</td><td>{{ item.stream ? 'SSE' : 'JSON' }}</td><td><span class="status" :class="item.status === 'SUCCEEDED' ? 'healthy' : 'unhealthy'">{{ item.status }}</span></td><td>{{ number((item.inputTokens ?? 0) + (item.outputTokens ?? 0)) }}</td><td>{{ cost(item.estimatedCost) }}</td><td>{{ number(item.latencyMs) }} ms</td><td>{{ item.failoverCount }}</td><td class="mono">{{ item.requestId }}</td></tr></tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<style scoped>
.usage-main { padding-top: 0; }
.usage-shell { border: 1px solid #24304a; border-radius: .9rem; padding: 1.25rem; background: #10182b; }
.usage-heading { display: flex; justify-content: space-between; gap: 2rem; align-items: end; }
.key-entry { min-width: min(100%, 340px); display: grid; gap: .5rem; }
.summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: .75rem; margin: 1rem 0; }
.summary-grid div { padding: 1rem; border-radius: .75rem; background: #151f36; }
.summary-grid strong { margin-top: .4rem; font-size: 1.35rem; }
.request-table { overflow-x: auto; } table { width: 100%; border-collapse: collapse; font-size: .82rem; } th, td { border-bottom: 1px solid #24304a; padding: .75rem; text-align: left; white-space: nowrap; } th { color: #9ba9c7; } .mono { max-width: 180px; overflow: hidden; text-overflow: ellipsis; font-family: ui-monospace, monospace; }
@media (max-width: 850px) { .usage-heading { display: grid; } .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
