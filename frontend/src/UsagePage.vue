<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'

type UsageMetric = { label: string; detail: string; requestCount: number; succeeded: number; failed: number; inputTokens: number; outputTokens: number; estimatedCost: number; failovers: number; averageLatencyMs: number }
type UsageRequest = { requestId: string; projectName: string; serviceKey: string; infrastructure: string; apiKeyLabel: string; status: string; inputTokens: number; outputTokens: number; estimatedCost: number; latencyMs?: number | null; failoverCount: number; errorCode?: string | null; startedAt: string }
type ProjectScope = { id: string; name: string; access: 'ORGANIZATION_ALL' | 'PROJECT_ALL' | 'OWN_KEYS'; accessLabel: string }
type UsageOverview = {
  total: UsageMetric
  byProject: UsageMetric[]
  byService: UsageMetric[]
  byInfrastructure: UsageMetric[]
  byApiKey: UsageMetric[]
  recentRequests: UsageRequest[]
  periodFrom?: string | null
  periodTo?: string | null
  scope: 'ORGANIZATION' | 'PROJECT_OWNER' | 'KEY_ISSUER'
  scopeLabel: string
  availableProjects: ProjectScope[]
}

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const overview = ref<UsageOverview | null>(null)
const busy = ref(false)
const message = ref('로그인 권한에 맞는 사용량을 준비하고 있습니다.')
const period = ref<'today' | '7d' | '30d' | 'all'>('30d')
const selectedProjectId = ref('')

const pageTitle = computed(() => overview.value?.scope === 'ORGANIZATION' ? '전체 API 사용량'
  : overview.value?.scope === 'PROJECT_OWNER' ? '프로젝트 API 사용량' : '내 API 사용량')
const scopeKicker = computed(() => overview.value?.scope === 'ORGANIZATION' ? 'ORGANIZATION ANALYTICS'
  : overview.value?.scope === 'PROJECT_OWNER' ? 'PROJECT OWNER ANALYTICS' : 'MY CREDENTIAL ANALYTICS')
const scopeDescription = computed(() => overview.value?.scope === 'ORGANIZATION'
  ? '관리 범위의 모든 프로젝트와 API 키 요청을 조회합니다.'
  : overview.value?.scope === 'PROJECT_OWNER'
    ? '소유 프로젝트의 모든 API 키와 그 밖에 직접 발급한 키 요청을 조회합니다.'
    : '현재 로그인 계정이 직접 발급한 API 키 요청만 조회합니다.')

function isoDate(date: Date) { return date.toISOString().slice(0, 10) }
function queryString() {
  const query = new URLSearchParams()
  if (period.value !== 'all') {
    const today = new Date()
    const start = new Date(today)
    if (period.value === '7d') start.setUTCDate(start.getUTCDate() - 6)
    if (period.value === '30d') start.setUTCDate(start.getUTCDate() - 29)
    query.set('from', isoDate(start))
    query.set('to', isoDate(today))
  }
  if (selectedProjectId.value) query.set('projectId', selectedProjectId.value)
  const value = query.toString()
  return value ? `?${value}` : ''
}

async function loadUsage() {
  if (!props.organizationId) {
    overview.value = null
    message.value = 'Workspace에서 조직을 선택하세요.'
    return
  }
  busy.value = true
  try {
    const result = await adminFetch<UsageOverview>(
      `/api/portal/organizations/${props.organizationId}/usage-overview${queryString()}`, props.auth)
    overview.value = result
    if (selectedProjectId.value && !result.availableProjects.some(item => item.id === selectedProjectId.value)) {
      selectedProjectId.value = ''
    }
    const rangeLabel = period.value === 'all' ? '전체 기간' : period.value === 'today' ? '오늘'
      : period.value === '7d' ? '최근 7일' : '최근 30일'
    message.value = `${result.scopeLabel} · ${rangeLabel} 요청 ${integer(result.total.requestCount)}건`
  } catch (error) {
    overview.value = null
    message.value = error instanceof Error ? error.message : '사용량을 조회하지 못했습니다.'
  } finally {
    busy.value = false
  }
}

function changeProject() { void loadUsage() }
function integer(value = 0) { return new Intl.NumberFormat('ko-KR').format(value) }
function cost(value = 0) { return `${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 6 }).format(value)} 원` }
function successRate(metric?: UsageMetric | null) {
  return !metric?.requestCount ? '0%' : `${((metric.succeeded / metric.requestCount) * 100).toFixed(1)}%`
}

watch(() => props.organizationId, () => {
  selectedProjectId.value = ''
  void loadUsage()
})
onMounted(() => { void loadUsage() })
</script>

<template>
  <section class="page-stack role-usage-page">
    <div class="page-hero">
      <div>
        <p class="eyebrow">{{ scopeKicker }}</p>
        <h1>{{ pageTitle }}</h1>
        <p>{{ scopeDescription }}</p>
      </div>
      <div class="hero-actions">
        <select v-model="selectedProjectId" :disabled="busy || !overview?.availableProjects.length" aria-label="프로젝트 범위" @change="changeProject">
          <option value="">접근 가능한 프로젝트 전체</option>
          <option v-for="project in overview?.availableProjects ?? []" :key="project.id" :value="project.id">{{ project.name }} · {{ project.accessLabel }}</option>
        </select>
        <select v-model="period" :disabled="busy" aria-label="조회 기간" @change="loadUsage">
          <option value="today">오늘</option>
          <option value="7d">최근 7일</option>
          <option value="30d">최근 30일</option>
          <option value="all">전체 기간</option>
        </select>
        <button class="primary-button" :disabled="busy || !organizationId" @click="loadUsage">새로고침</button>
      </div>
    </div>

    <div v-if="overview" class="scope-banner">
      <span>{{ overview.scope === 'ORGANIZATION' ? 'ADMIN' : overview.scope === 'PROJECT_OWNER' ? 'OWNER' : 'ISSUER' }}</span>
      <div><strong>{{ overview.scopeLabel }}</strong><small>API 키 원문을 입력하지 않습니다. 서버가 로그인 계정과 프로젝트 역할로 조회 범위를 결정합니다.</small></div>
    </div>
    <p class="inline-alert">{{ message }}</p>

    <template v-if="overview">
      <div class="metric-grid six">
        <article class="metric-card accent"><span>요청 수</span><strong>{{ integer(overview.total.requestCount) }}</strong><small>선택 범위</small></article>
        <article class="metric-card"><span>성공률</span><strong>{{ successRate(overview.total) }}</strong><small>성공 {{ integer(overview.total.succeeded) }} · 실패 {{ integer(overview.total.failed) }}</small></article>
        <article class="metric-card"><span>입력 토큰</span><strong>{{ integer(overview.total.inputTokens) }}</strong></article>
        <article class="metric-card"><span>출력 토큰</span><strong>{{ integer(overview.total.outputTokens) }}</strong></article>
        <article class="metric-card"><span>예상 비용</span><strong>{{ cost(overview.total.estimatedCost) }}</strong></article>
        <article class="metric-card" :class="{ warning: overview.total.failed > 0 }"><span>Failover</span><strong>{{ integer(overview.total.failovers) }}</strong><small>평균 {{ integer(overview.total.averageLatencyMs) }} ms</small></article>
      </div>

      <div class="usage-grid">
        <article class="surface-card">
          <header class="card-header"><div><span class="card-kicker">PROJECTS</span><h2>프로젝트별 사용량</h2></div><span class="count-badge">{{ overview.byProject.length }}</span></header>
          <div v-if="overview.byProject.length" class="data-table-wrap compact-table"><table class="data-table"><thead><tr><th>프로젝트</th><th>요청</th><th>토큰</th><th>비용</th><th>실패</th></tr></thead><tbody><tr v-for="item in overview.byProject" :key="item.label"><td><strong>{{ item.label }}</strong></td><td>{{ integer(item.requestCount) }}</td><td>{{ integer(item.inputTokens + item.outputTokens) }}</td><td>{{ cost(item.estimatedCost) }}</td><td :class="{ 'danger-text': item.failed }">{{ integer(item.failed) }}</td></tr></tbody></table></div>
          <div v-else class="empty-state compact"><span>⌘</span><p>선택 범위에 프로젝트 사용량이 없습니다.</p></div>
        </article>

        <article class="surface-card">
          <header class="card-header"><div><span class="card-kicker">API KEYS</span><h2>API 키별 사용량</h2></div><span class="count-badge">{{ overview.byApiKey.length }}</span></header>
          <div v-if="overview.byApiKey.length" class="data-table-wrap compact-table"><table class="data-table"><thead><tr><th>API 키</th><th>요청</th><th>토큰</th><th>비용</th><th>실패</th></tr></thead><tbody><tr v-for="item in overview.byApiKey" :key="`${item.label}-${item.detail}`"><td><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></td><td>{{ integer(item.requestCount) }}</td><td>{{ integer(item.inputTokens + item.outputTokens) }}</td><td>{{ cost(item.estimatedCost) }}</td><td :class="{ 'danger-text': item.failed }">{{ integer(item.failed) }}</td></tr></tbody></table></div>
          <div v-else class="empty-state compact"><span>◇</span><p>선택 범위에 API 키 사용량이 없습니다.</p></div>
        </article>
      </div>

      <div class="usage-grid">
        <article class="surface-card">
          <header class="card-header"><div><span class="card-kicker">LOGICAL SERVICES</span><h2>논리 서비스별 사용량</h2></div><span class="count-badge">{{ overview.byService.length }}</span></header>
          <div v-if="overview.byService.length" class="data-table-wrap compact-table"><table class="data-table"><thead><tr><th>model 값</th><th>요청</th><th>성공률</th><th>토큰</th><th>비용</th></tr></thead><tbody><tr v-for="item in overview.byService" :key="item.label"><td><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></td><td>{{ integer(item.requestCount) }}</td><td>{{ successRate(item) }}</td><td>{{ integer(item.inputTokens + item.outputTokens) }}</td><td>{{ cost(item.estimatedCost) }}</td></tr></tbody></table></div>
          <div v-else class="empty-state compact"><span>▣</span><p>선택 범위에 서비스 사용량이 없습니다.</p></div>
        </article>

        <article class="surface-card">
          <header class="card-header"><div><span class="card-kicker">INFRASTRUCTURE</span><h2>실제 배포별 처리량</h2></div><span class="count-badge">{{ overview.byInfrastructure.length }}</span></header>
          <div v-if="overview.byInfrastructure.length" class="data-table-wrap compact-table"><table class="data-table"><thead><tr><th>노드 · 모델 배포</th><th>요청</th><th>성공/실패</th><th>토큰</th><th>평균 지연</th></tr></thead><tbody><tr v-for="item in overview.byInfrastructure" :key="`${item.label}-${item.detail}`"><td><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></td><td>{{ integer(item.requestCount) }}</td><td>{{ integer(item.succeeded) }} / <span :class="{ 'danger-text': item.failed }">{{ integer(item.failed) }}</span></td><td>{{ integer(item.inputTokens + item.outputTokens) }}</td><td>{{ integer(item.averageLatencyMs) }} ms</td></tr></tbody></table></div>
          <div v-else class="empty-state compact"><span>◇</span><p>선택 범위에 처리된 배포가 없습니다.</p></div>
        </article>
      </div>

      <article class="surface-card">
        <header class="card-header"><div><span class="card-kicker">REQUEST HISTORY</span><h2>최근 요청</h2></div><span class="count-badge">{{ overview.recentRequests.length }}</span></header>
        <div v-if="overview.recentRequests.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>시간</th><th>프로젝트</th><th>논리 모델·키</th><th>실제 배포</th><th>상태</th><th>토큰</th><th>비용</th><th>지연</th><th>Failover</th></tr></thead><tbody><tr v-for="item in overview.recentRequests" :key="item.requestId"><td>{{ new Date(item.startedAt).toLocaleString() }}</td><td>{{ item.projectName }}</td><td><strong>{{ item.serviceKey }}</strong><small>{{ item.apiKeyLabel }}</small></td><td>{{ item.infrastructure }}</td><td><span class="status-chip tiny" :class="item.status === 'SUCCEEDED' ? 'healthy' : 'unhealthy'">{{ item.status }}</span><small v-if="item.errorCode" class="danger-text">{{ item.errorCode }}</small></td><td>{{ integer(item.inputTokens + item.outputTokens) }}</td><td>{{ cost(item.estimatedCost) }}</td><td>{{ integer(item.latencyMs ?? 0) }} ms</td><td>{{ item.failoverCount }}</td></tr></tbody></table></div>
        <div v-else class="empty-state"><span>◴</span><p>표시할 요청이 없습니다.</p></div>
      </article>
    </template>
  </section>
</template>

<style scoped>
.role-usage-page .hero-actions{align-items:center}.role-usage-page .hero-actions select{min-height:40px;max-width:310px;padding:0 34px 0 12px;border:1px solid var(--border);border-radius:10px;background:var(--surface);color:var(--text);font:inherit;font-size:11px}.scope-banner{display:grid;grid-template-columns:58px 1fr;gap:13px;align-items:center;padding:13px 15px;border:1px solid var(--accent-border);border-radius:13px;background:var(--accent-dim)}.scope-banner>span{height:34px;display:grid;place-items:center;border-radius:9px;background:var(--surface);color:var(--accent-strong);font-size:9px;font-weight:900;letter-spacing:.08em}.scope-banner strong,.scope-banner small{display:block}.scope-banner strong{font-size:12px}.scope-banner small{margin-top:4px;color:var(--muted);font-size:9px;line-height:1.5}.usage-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.compact-table .data-table th,.compact-table .data-table td{padding:10px 12px;font-size:10px}.compact-table small,.data-table td small{display:block;margin-top:3px;font-size:8px}.data-table td small.danger-text{color:var(--danger)}.metric-grid.six{grid-template-columns:repeat(6,minmax(0,1fr))}@media(max-width:1180px){.metric-grid.six{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:960px){.usage-grid{grid-template-columns:1fr}.role-usage-page .hero-actions{width:100%;display:grid;grid-template-columns:1fr 1fr}.role-usage-page .hero-actions select{max-width:none}.role-usage-page .hero-actions button{grid-column:1/-1}}@media(max-width:620px){.metric-grid.six{grid-template-columns:repeat(2,minmax(0,1fr))}.role-usage-page .hero-actions{grid-template-columns:1fr}.role-usage-page .hero-actions button{grid-column:auto}.scope-banner{grid-template-columns:1fr}.scope-banner>span{width:58px}}
</style>
