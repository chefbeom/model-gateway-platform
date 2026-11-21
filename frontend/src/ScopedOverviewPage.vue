<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { adminFetch, type AdminAuth, type ProjectSpendQuotaOverview, type SpendQuotaView } from './api'
import { formatDateTime } from './dateTime'
import RequestDetailModal from './RequestDetailModal.vue'
import type { RequestDetail } from './requestDetail'

type Metric = { requestCount: number; succeeded: number; failed: number; inputTokens: number; outputTokens: number; estimatedCostByCurrency: Record<string, number> }
type Project = { id: string; name: string; access: string; accessLabel: string }
type Overview = { total: Metric; scopeLabel: string; availableProjects: Project[] }
type Request = { requestId: string; serviceKey: string | null; startedAt: string; status: string; latencyMs: number | null; inputTokens: number | null; outputTokens: number | null; estimatedCost: number | null; costCurrency: string | null; failoverCount: number; errorCode: string | null }
type OwnQuota = { keyName: string; name: string; currency: string; limitAmount: number; period: string; enabled: boolean; usedAmount: number | null }
const props = defineProps<{ organizationId: string; organizationName?: string; userName?: string; roleLabel?: string; auth: AdminAuth; mode: 'dashboard' | 'observability' | 'quotas'; isDirector?: boolean }>()
const emit = defineEmits<{ navigate: [page: 'projects' | 'observability' | 'quotas' | 'docs' | 'portal' | 'playground' | 'relationship'] }>()
const overview = ref<Overview | null>(null), rows = ref<Request[]>([]), quotas = ref<OwnQuota[]>([]), projectQuotas = ref<SpendQuotaView[]>([])
const projectOptions = ref<Project[]>([])
const project = ref(''), offset = ref(0), error = ref(''), historyError = ref(''), quotaError = ref('')
const loading = ref(false), historyLoading = ref(false), quotaLoading = ref(false)
const detail = ref<RequestDetail | null>(null), detailOpen = ref(false), detailLoading = ref(false)
let generation = 0
let detailGeneration = 0
const managed = computed(() => (overview.value?.availableProjects ?? []).filter(item => item.access === 'PROJECT_ALL'))
const quotaProject = computed(() => project.value ? managed.value.find(item => item.id === project.value) : managed.value.length === 1 ? managed.value[0] : undefined)
const completed = computed(() => (overview.value?.total.succeeded ?? 0) + (overview.value?.total.failed ?? 0))
const number = (value: number) => new Intl.NumberFormat('ko-KR').format(value)
function amount(value: number, currency: string) { return `${currency} ${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 6 }).format(value)}` }
function message(e: unknown) { return e instanceof Error ? e.message : '조회하지 못했습니다.' }
async function load() {
  const current = ++generation
  overview.value = null; rows.value = []; quotas.value = []; projectQuotas.value = []
  error.value = ''; historyError.value = ''; quotaError.value = ''; detailOpen.value = false; detail.value = null; detailGeneration++
  loading.value = false; historyLoading.value = false; quotaLoading.value = false
  if (!props.organizationId) { error.value = '조직을 선택하세요.'; return }
  const base = `/api/portal/organizations/${encodeURIComponent(props.organizationId)}/usage-overview`
  const filter = project.value ? `projectId=${encodeURIComponent(project.value)}&` : ''
  const end = new Date(), start = new Date(end); start.setUTCDate(start.getUTCDate() - 29)
  const dates = `from=${start.toISOString().slice(0, 10)}&to=${end.toISOString().slice(0, 10)}`
  loading.value = true
  const summaryTask = (async () => {
    try {
      const summary = await adminFetch<Overview>(`${base}?${filter}${dates}`, props.auth)
      if (current !== generation) return
      if (project.value && !summary.availableProjects.some(item => item.id === project.value)) { project.value = ''; offset.value = 0; void load(); return }
      overview.value = summary; projectOptions.value = summary.availableProjects; loading.value = false
      if (props.mode === 'dashboard' && props.isDirector && quotaProject.value) {
        quotaLoading.value = true
        try {
          const result = await adminFetch<ProjectSpendQuotaOverview>(`/api/admin/projects/${encodeURIComponent(quotaProject.value.id)}/spend-quotas`, props.auth)
          if (current !== generation) return
          const seen = new Set<string>()
          projectQuotas.value = [...result.ceilings, ...result.quotas].filter(item => {
            if (seen.has(item.id) || item.scopeType !== 'PROJECT' || item.period !== 'MONTHLY' || !item.enabled || item.usedAmount == null) return false
            seen.add(item.id); return true
          })
        } catch (e) { if (current === generation) quotaError.value = message(e) }
        finally { if (current === generation) quotaLoading.value = false }
      }
    } catch (e) { if (current === generation) error.value = message(e) }
    finally { if (current === generation) loading.value = false }
  })()
  const extraTask = (async () => {
    if (props.mode === 'quotas') {
      quotaLoading.value = true
      try { const result = await adminFetch<OwnQuota[]>(`${base}/own-key-quotas?${filter}`, props.auth); if (current === generation) quotas.value = result }
      catch (e) { if (current === generation) quotaError.value = message(e) }
      finally { if (current === generation) quotaLoading.value = false }
    } else {
      historyLoading.value = true
      try { const result = await adminFetch<Request[]>(`${base}/requests?${filter}offset=${offset.value}&limit=${props.mode === 'dashboard' ? 5 : 50}`, props.auth); if (current === generation) rows.value = result }
      catch (e) { if (current === generation) historyError.value = message(e) }
      finally { if (current === generation) historyLoading.value = false }
    }
  })()
  await Promise.all([summaryTask, extraTask])
}
watch(() => [props.organizationId, props.auth, props.isDirector, props.mode] as const, () => { project.value = ''; projectOptions.value = []; offset.value = 0; void load() }, { immediate: true, deep: true })
onBeforeUnmount(() => { generation++; detailGeneration++ })
async function inspect(row: Request) {
  const current = generation, ticket = ++detailGeneration; detailOpen.value = true; detailLoading.value = true; detail.value = null
  try { const result = await adminFetch<RequestDetail>(`/api/portal/organizations/${encodeURIComponent(props.organizationId)}/usage-overview/requests/${encodeURIComponent(row.requestId)}`, props.auth); if (current === generation && ticket === detailGeneration) detail.value = result }
  catch (e) { if (current === generation && ticket === detailGeneration) { detailOpen.value = false; historyError.value = message(e) } }
  finally { if (current === generation && ticket === detailGeneration) detailLoading.value = false }
}
function closeDetail() { detailGeneration++; detailOpen.value = false; detailLoading.value = false }
function changeProject() { offset.value = 0; void load() }
function page(delta: number) { offset.value = Math.max(0, offset.value + delta); void load() }
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">MY WORKSPACE</p><h1>{{ mode === 'dashboard' ? '대시보드' : mode === 'observability' ? '관측성' : '요금·한도' }}</h1><p>사용량 요약은 최근 30일 UTC 날짜 기준입니다. 요청 목록은 전체 기간입니다.</p></div>
      <div class="hero-actions"><select v-model="project" aria-label="프로젝트 범위" :disabled="loading || (!projectOptions.length && !project)" @change="changeProject"><option value="">접근 가능한 프로젝트 전체</option><option v-for="item in projectOptions" :key="item.id" :value="item.id">{{ item.name }} · {{ item.accessLabel }}</option></select><button class="secondary-button" :disabled="loading" @click="load">새로고침</button></div></div>
    <p v-if="error" class="inline-alert danger-alert">사용량 조회 실패: {{ error }}</p><p v-else-if="loading && !overview">사용량을 불러오는 중입니다.</p>
    <template v-if="overview">
      <div class="scope-banner"><strong>{{ overview.scopeLabel }}</strong><small>접근 권한에 따른 사용량 · 최근 30일 UTC 날짜 기준</small></div>
      <div class="metric-grid"><article class="metric-card"><span>요청</span><strong>{{ number(overview.total.requestCount) }}</strong></article><article class="metric-card"><span>완료 요청 성공률</span><strong>{{ completed ? `${(overview.total.succeeded / completed * 100).toFixed(1)}%` : '기록 없음' }}</strong><small>성공 {{ number(overview.total.succeeded) }} · 실패 {{ number(overview.total.failed) }}</small></article><article class="metric-card"><span>기록된 토큰</span><strong>{{ number(overview.total.inputTokens + overview.total.outputTokens) }}</strong><small>입력 {{ number(overview.total.inputTokens) }} · 출력 {{ number(overview.total.outputTokens) }}</small></article><article class="metric-card"><span>추정 비용</span><strong>{{ Object.entries(overview.total.estimatedCostByCurrency ?? {}).map(([c, v]) => amount(v, c)).join(' · ') || '기록 없음' }}</strong><small>통화별 추정액 · 실제 청구액 아님</small></article></div>
      <div v-if="overview.total.failed > 0" class="scope-banner"><strong>최근 30일 실패 {{ number(overview.total.failed) }}건</strong><button class="text-button" @click="emit('navigate', 'observability')">요청 상세 확인 →</button></div>
    </template>
    <template v-if="mode === 'dashboard'">
      <div class="dashboard-grid"><article v-if="isDirector" class="surface-card"><header class="card-header"><div><span class="card-kicker">PROJECT BUDGET</span><h2>프로젝트 월간 한도</h2></div><button class="text-button" @click="emit('navigate', 'quotas')">한도 관리 →</button></header><p v-if="loading">권한을 확인하는 중입니다.</p><p v-else-if="error">사용량 범위를 확인할 수 없어 예산을 조회하지 않았습니다.</p><p v-else-if="!quotaProject">{{ project ? '선택한 프로젝트의 전체 관리 권한이 없습니다.' : managed.length > 1 ? '예산을 확인할 프로젝트를 선택하세요.' : '관리 가능한 프로젝트가 없습니다.' }}</p><template v-else><p><strong>{{ quotaProject.name }}</strong> · 달력 월 기준 (위 30일 사용량과 기간이 다름)</p><p v-if="quotaLoading">예산을 불러오는 중입니다.</p><p v-else-if="quotaError" class="inline-alert danger-alert">예산 조회 실패: {{ quotaError }}</p><p v-else-if="!projectQuotas.length">표시할 활성 프로젝트 월간 한도가 없습니다.</p><div v-else class="budget-list"><div v-for="item in projectQuotas" :key="item.id"><strong>{{ item.name }}</strong><span>{{ amount(item.usedAmount, item.currency) }} / {{ amount(item.limitAmount, item.currency) }}</span><small>{{ item.exceeded ? '한도 도달 또는 초과' : '활성' }}</small></div></div><small>기록된 추정 사용액이며 실제 청구액과 다를 수 있습니다.</small></template></article>
      <article class="surface-card"><header class="card-header"><div><span class="card-kicker">QUICK ACCESS</span><h2>빠른 작업</h2></div></header><div class="quick-grid"><button v-if="isDirector" @click="emit('navigate', 'projects')"><b>프로젝트 관리</b><small>멤버와 연결 관리</small></button><button @click="emit('navigate', 'relationship')"><b>조직 관계도</b><small>계층과 구성원 구조</small></button><button @click="emit('navigate', 'portal')"><b>내 API</b><small>모델과 API 키</small></button><button @click="emit('navigate', 'playground')"><b>API 테스트</b><small>모델 연결 확인</small></button><button @click="emit('navigate', 'observability')"><b>요청 추적</b><small>상세와 전체 목록</small></button><button @click="emit('navigate', 'quotas')"><b>요금·한도</b><small>한도 확인</small></button><button @click="emit('navigate', 'docs')"><b>개발 문서</b><small>API 사용 안내</small></button></div></article></div>
    </template>
    <article v-if="mode === 'quotas'" class="surface-card"><header class="card-header"><h2>내가 발급한 API 키 한도</h2></header><p>키에 직접 설정된 한도입니다. 사용액은 추정 비용이며 실제 청구액이 아닙니다.</p><p v-if="quotaLoading">한도를 불러오는 중입니다.</p><p v-else-if="quotaError" class="inline-alert danger-alert">한도 조회 실패: {{ quotaError }}</p><div v-else-if="quotas.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>API 키</th><th>한도</th><th>기간</th><th>추정 사용액 / 상한</th><th>상태</th></tr></thead><tbody><tr v-for="(item, index) in quotas" :key="index"><td>{{ item.keyName }}</td><td>{{ item.name }}</td><td>{{ item.period }}</td><td>{{ item.usedAmount == null ? '확인 불가' : amount(item.usedAmount, item.currency) }} / {{ amount(item.limitAmount, item.currency) }}</td><td>{{ item.enabled ? '활성' : '비활성' }}</td></tr></tbody></table></div><p v-else>조회 가능한 키에 직접 설정된 한도가 없습니다.</p></article>
    <article v-else class="surface-card"><header class="card-header"><h2>최근 요청</h2><button v-if="mode === 'dashboard'" class="text-button" @click="emit('navigate', 'observability')">전체 요청 →</button><span v-else>최대 50건</span></header><p v-if="historyLoading" class="scoped-empty">요청을 불러오는 중입니다.</p><p v-else-if="historyError" class="inline-alert danger-alert">요청 조회 실패: {{ historyError }}</p><div v-else-if="rows.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>시간</th><th>논리 모델</th><th>상태</th><th>토큰</th><th>추정 비용</th><th>지연</th><th>Failover</th><th>상세</th></tr></thead><tbody><tr v-for="row in rows.slice(0, mode === 'dashboard' ? 5 : 50)" :key="row.requestId"><td>{{ formatDateTime(row.startedAt) }}</td><td>{{ row.serviceKey ?? '확인 불가' }}</td><td>{{ row.status }} <small>{{ row.errorCode }}</small></td><td>{{ row.inputTokens ?? '–' }} / {{ row.outputTokens ?? '–' }}</td><td>{{ row.estimatedCost == null || !row.costCurrency ? '확인 불가' : amount(row.estimatedCost, row.costCurrency) }}</td><td>{{ row.latencyMs == null ? '–' : `${row.latencyMs} ms` }}</td><td>{{ row.failoverCount }}</td><td><button class="text-button" @click="inspect(row)">상세</button></td></tr></tbody></table></div><p v-else class="scoped-empty">이 범위에 요청 기록이 없습니다.</p><div v-if="mode === 'observability' && !historyError && (rows.length || offset > 0)" class="hero-actions scoped-pagination"><button class="secondary-button" :disabled="historyLoading || offset === 0" @click="page(-50)">이전</button><button class="secondary-button" :disabled="historyLoading || rows.length < 50 || offset >= 10000" @click="page(50)">다음</button></div></article>
    <RequestDetailModal :open="detailOpen" :detail="detail" :loading="detailLoading" @close="closeDetail" />
  </section>
</template>

<style scoped>
.scope-banner { padding:16px 20px; border:1px solid var(--accent-border); border-radius:12px; background:var(--accent-dim); }.scope-banner strong,.scope-banner small { display:block; }.scope-banner small { margin-top:5px; color:var(--muted); line-height:1.5; }.scoped-empty { margin:0; padding:24px; color:var(--muted); }.scoped-pagination { padding:0 24px 20px; }.dashboard-grid > .surface-card > p, .dashboard-grid > .surface-card > small, .budget-list { margin-left:24px; margin-right:24px; }.dashboard-grid > .surface-card > small { display:block; margin-bottom:20px; }.budget-list { display:grid; gap:12px; margin-top:16px; margin-bottom:16px; }.budget-list div { display:flex; flex-wrap:wrap; gap:8px 16px; justify-content:space-between; }.budget-list small { color:var(--muted); }
</style>
