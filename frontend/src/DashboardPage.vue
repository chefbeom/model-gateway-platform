<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { adminFetch, type AdminAuth, type Endpoint } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const emit = defineEmits<{ navigate: [page: string] }>()
type Overview = { requests24h: number; succeeded24h: number; failed24h: number; activeRequests: number; successRate24h: number; errorRate24h: number; inputTokens24h: number; outputTokens24h: number; estimatedCost24h: number; p95LatencyMs24h: number; failovers24h: number; endpoints: number; unhealthyEndpoints: number; openIncidents: number }
type Incident = { id: string; endpointBaseUrl?: string; status: string; reason: string; openedAt: string }

const overview = ref<Overview | null>(null)
const endpoints = ref<Endpoint[]>([])
const incidents = ref<Incident[]>([])
const busy = ref(false)
const error = ref('')

async function load() {
  busy.value = true; error.value = ''
  try {
    const overviewPath = props.organizationId ? `/api/admin/organizations/${props.organizationId}/overview` : '/api/admin/overview'
    const tasks: Promise<unknown>[] = [adminFetch<Overview>(overviewPath, props.auth)]
    if (props.organizationId) tasks.push(adminFetch<Incident[]>(`/api/admin/organizations/${props.organizationId}/incidents?status=OPEN`, props.auth))
    const [summary, openIncidents] = await Promise.all(tasks)
    overview.value = summary as Overview
    incidents.value = (openIncidents ?? []) as Incident[]
    endpoints.value = await adminFetch<Endpoint[]>('/api/admin/runtime-endpoints', props.auth)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '대시보드를 불러오지 못했습니다.' }
  finally { busy.value = false }
}
function integer(value = 0) { return new Intl.NumberFormat('ko-KR').format(value) }
function percent(value = 0) { return `${(value * 100).toFixed(1)}%` }
function money(value = 0) { return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 4 }).format(value) }
watch(() => props.organizationId, load)
onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="page-hero dashboard-hero">
      <div><p class="eyebrow">SYSTEM PULSE</p><h1>운영 대시보드</h1><p>지난 24시간의 요청 흐름과 런타임 상태를 한눈에 확인합니다.</p></div>
      <div class="hero-actions"><span class="live-pill"><i></i> LIVE</span><button class="secondary-button" :disabled="busy" @click="load">↻ 새로고침</button></div>
    </div>
    <p v-if="error" class="inline-alert danger-alert">{{ error }}</p>
    <div v-if="overview" class="metric-grid">
      <article class="metric-card accent"><div class="metric-icon">↗</div><span>24시간 요청</span><strong>{{ integer(overview.requests24h) }}</strong><small>활성 요청 {{ overview.activeRequests }}</small></article>
      <article class="metric-card"><div class="metric-icon">✓</div><span>성공률</span><strong>{{ percent(overview.successRate24h) }}</strong><small>실패 {{ integer(overview.failed24h) }}건</small></article>
      <article class="metric-card"><div class="metric-icon">⌁</div><span>p95 응답시간</span><strong>{{ integer(overview.p95LatencyMs24h) }}<em>ms</em></strong><small>Failover {{ overview.failovers24h }}회</small></article>
      <article class="metric-card"><div class="metric-icon">◇</div><span>처리 토큰</span><strong>{{ integer(overview.inputTokens24h + overview.outputTokens24h) }}</strong><small>입력 {{ integer(overview.inputTokens24h) }} · 출력 {{ integer(overview.outputTokens24h) }}</small></article>
      <article class="metric-card"><div class="metric-icon">₩</div><span>예상 비용</span><strong>{{ money(overview.estimatedCost24h) }}</strong><small>논리 서비스 가격 기준</small></article>
      <article class="metric-card" :class="{ warning: overview.openIncidents > 0 }"><div class="metric-icon">!</div><span>열린 장애</span><strong>{{ overview.openIncidents }}</strong><small>비정상 Endpoint {{ overview.unhealthyEndpoints }}</small></article>
    </div>
    <div v-else class="metric-grid"><div v-for="n in 6" :key="n" class="skeleton metric-skeleton"></div></div>
    <div class="dashboard-grid">
      <article class="surface-card span-two">
        <header class="card-header"><div><span class="card-kicker">RUNTIME MESH</span><h2>런타임 상태</h2></div><button class="text-button" @click="emit('navigate', 'infrastructure')">전체 인프라 →</button></header>
        <div v-if="endpoints.length" class="runtime-strip">
          <button v-for="endpoint in endpoints.slice(0, 6)" :key="endpoint.id" class="runtime-node" @click="emit('navigate', 'infrastructure')"><span class="node-light" :class="endpoint.healthStatus.toLowerCase()"></span><div><strong>{{ endpoint.baseUrl.replace(/^https?:\/\//, '') }}</strong><small>{{ endpoint.runtimeType }} · {{ endpoint.healthStatus }}</small></div><b>›</b></button>
        </div><div v-else class="empty-state compact"><span>⌁</span><p>등록된 런타임이 없습니다.</p><button class="text-button" @click="emit('navigate', 'infrastructure')">첫 런타임 연결</button></div>
      </article>
      <article class="surface-card">
        <header class="card-header"><div><span class="card-kicker">INCIDENT FEED</span><h2>진행 중 장애</h2></div><span class="count-badge">{{ incidents.length }}</span></header>
        <div v-if="incidents.length" class="activity-list"><button v-for="item in incidents.slice(0, 4)" :key="item.id" @click="emit('navigate', 'observability')"><i class="activity-icon error-icon">!</i><span><strong>{{ item.endpointBaseUrl ?? 'Runtime Endpoint' }}</strong><small>{{ item.reason }}</small></span><time>{{ new Date(item.openedAt).toLocaleTimeString() }}</time></button></div>
        <div v-else class="empty-state compact success-empty"><span>✓</span><p>현재 진행 중인 장애가 없습니다.</p></div>
      </article>
      <article class="surface-card">
        <header class="card-header"><div><span class="card-kicker">QUICK ACCESS</span><h2>빠른 작업</h2></div></header>
        <div class="quick-grid"><button @click="emit('navigate', 'infrastructure')"><span>＋</span><b>런타임 연결</b><small>새 LM Studio 등록</small></button><button @click="emit('navigate', 'services')"><span>⌘</span><b>서비스 구성</b><small>라우팅 정책 편집</small></button><button @click="emit('navigate', 'projects')"><span>⌁</span><b>API 키 발급</b><small>프로젝트 접근 생성</small></button><button @click="emit('navigate', 'observability')"><span>◎</span><b>요청 추적</b><small>Attempt 상세 확인</small></button></div>
      </article>
    </div>
  </section>
</template>
