<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminFetch, type AdminAuth } from './api'

type Organization = { id: string; name: string; status: string }
type Overview = {
  requests24h: number
  succeeded24h: number
  failed24h: number
  activeRequests: number
  successRate24h: number
  errorRate24h: number
  inputTokens24h: number
  outputTokens24h: number
  estimatedCost24h: number
  p95LatencyMs24h: number
  failovers24h: number
  endpoints: number
  unhealthyEndpoints: number
  openIncidents: number
}

const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const organizations = ref<Organization[]>([])
const overview = ref<Overview | null>(null)
const busy = ref(false)
const message = ref('최근 24시간 운영 현황을 불러오세요.')

function auth(): AdminAuth {
  const accessToken = sessionStorage.getItem('aiconnect.accessToken')
  return accessToken ? { accessToken } : { platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? '' }
}

async function loadOrganizations() {
  organizations.value = await adminFetch<Organization[]>('/api/admin/organizations', auth())
  if (organizationId.value && !organizations.value.some(item => item.id === organizationId.value)) organizationId.value = ''
  if (!organizationId.value && organizations.value.length === 1) organizationId.value = organizations.value[0].id
}

async function load() {
  busy.value = true
  try {
    if (!organizations.value.length) await loadOrganizations()
    if (organizationId.value) sessionStorage.setItem('aiconnect.setup.organizationId', organizationId.value)
    const path = organizationId.value
      ? `/api/admin/organizations/${organizationId.value}/overview`
      : '/api/admin/overview'
    overview.value = await adminFetch<Overview>(path, auth())
    message.value = organizationId.value ? '선택한 조직의 최근 24시간 현황입니다.' : '전체 플랫폼의 최근 24시간 현황입니다.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '운영 현황 조회에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

function percent(value: number) { return `${(value * 100).toFixed(1)}%` }
function integer(value: number) { return new Intl.NumberFormat().format(value) }
function money(value: number) { return `${Number(value ?? 0).toFixed(4)}` }

onMounted(() => {
  if (sessionStorage.getItem('aiconnect.accessToken') || sessionStorage.getItem('aiconnect.platformToken')) load()
})
</script>

<template>
  <main class="overview-main">
    <section class="overview-shell">
      <div class="heading">
        <div><p class="eyebrow">OPERATIONS</p><h2>운영 현황</h2><p>요청, 토큰, 지연시간, Failover와 장애를 최근 24시간 기준으로 확인합니다.</p></div>
        <div class="scope"><select v-model="organizationId"><option value="">전체 플랫폼</option><option v-for="item in organizations" :key="item.id" :value="item.id">{{ item.name }}</option></select><button :disabled="busy" @click="load">새로고침</button></div>
      </div>
      <p class="notice">{{ message }}</p>
      <div v-if="overview" class="cards">
        <article><small>요청</small><strong>{{ integer(overview.requests24h) }}</strong><span>성공 {{ overview.succeeded24h }} · 실패 {{ overview.failed24h }}</span></article>
        <article><small>성공률</small><strong>{{ percent(overview.successRate24h) }}</strong><span>오류율 {{ percent(overview.errorRate24h) }}</span></article>
        <article><small>p95 지연시간</small><strong>{{ integer(overview.p95LatencyMs24h) }} ms</strong><span>활성 요청 {{ overview.activeRequests }}</span></article>
        <article><small>토큰</small><strong>{{ integer(overview.inputTokens24h + overview.outputTokens24h) }}</strong><span>입력 {{ integer(overview.inputTokens24h) }} · 출력 {{ integer(overview.outputTokens24h) }}</span></article>
        <article><small>예상 비용</small><strong>{{ money(overview.estimatedCost24h) }}</strong><span>논리 서비스 가격 기준</span></article>
        <article><small>Failover</small><strong>{{ integer(overview.failovers24h) }}</strong><span>실제 대체 시도 수</span></article>
        <article><small>Endpoint</small><strong>{{ overview.endpoints }}</strong><span>UNHEALTHY {{ overview.unhealthyEndpoints }}</span></article>
        <article><small>열린 장애</small><strong :class="{ danger: overview.openIncidents > 0 }">{{ overview.openIncidents }}</strong><span>복구 전 Incident</span></article>
      </div>
    </section>
  </main>
</template>

<style scoped>
.overview-main { padding-top: 0; }
.overview-shell { border: 1px solid #24304a; border-radius: .9rem; padding: 1.25rem; background: #10182b; }
.heading { display: flex; justify-content: space-between; gap: 1rem; align-items: end; }
.scope { display: flex; gap: .5rem; min-width: 22rem; }
.scope select { flex: 1; min-height: 2.6rem; border: 1px solid #33415f; border-radius: .5rem; padding: .6rem; color: #f7f9ff; background: #121b31; }
.cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .65rem; }
.cards article { display: grid; gap: .3rem; min-height: 7.2rem; }
.cards strong { font-size: 1.6rem; }
.cards span, .cards small { color: #9ba9c7; }
.danger { color: #ff8f9a; }
@media (max-width: 980px) { .cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } .heading { align-items: stretch; flex-direction: column; } .scope { min-width: 0; } }
@media (max-width: 560px) { .cards { grid-template-columns: 1fr; } .scope { flex-direction: column; } }
</style>
