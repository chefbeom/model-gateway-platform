<script setup lang="ts">
import { ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'
import { formatDateTime } from './dateTime'
import RequestDetailModal from './RequestDetailModal.vue'
import type { RequestDetail } from './requestDetail'

type Metric = { requestCount: number; succeeded: number; failed: number; inputTokens: number; outputTokens: number; estimatedCostByCurrency: Record<string, number> }
type Overview = { total: Metric; scopeLabel: string; availableProjects: { id: string; name: string; accessLabel: string }[] }
type Request = { requestId: string; serviceKey: string | null; startedAt: string; status: string; latencyMs: number | null; inputTokens: number | null; outputTokens: number | null; estimatedCost: number | null; costCurrency: string | null; failoverCount: number; errorCode: string | null }
type OwnQuota = { keyName: string; name: string; currency: string; limitAmount: number; period: string; enabled: boolean; usedAmount: number }
const props = defineProps<{ organizationId: string; auth: AdminAuth; mode: 'dashboard' | 'observability' | 'quotas' }>()
const overview = ref<Overview | null>(null)
const rows = ref<Request[]>([])
const quotas = ref<OwnQuota[]>([])
const project = ref('')
const offset = ref(0)
const error = ref('')
const loading = ref(false)
const detail = ref<RequestDetail | null>(null)
const detailOpen = ref(false)
const detailLoading = ref(false)
let generation = 0
async function load() {
  const current = ++generation
  overview.value = null; rows.value = []; quotas.value = []; error.value = ''; detailOpen.value = false; detail.value = null
  if (!props.organizationId) { error.value = '조직을 선택하세요.'; loading.value = false; return }
  loading.value = true
  const base = `/api/portal/organizations/${props.organizationId}/usage-overview`
  const filter = project.value ? `projectId=${encodeURIComponent(project.value)}&` : ''
  const end = new Date(); const start = new Date(end); start.setUTCDate(start.getUTCDate() - 29)
  const dates = `from=${start.toISOString().slice(0, 10)}&to=${end.toISOString().slice(0, 10)}`
  try {
    const [summary, history, keyQuotas] = await Promise.all([
      adminFetch<Overview>(`${base}?${filter}${dates}`, props.auth),
      props.mode === 'quotas' ? Promise.resolve([] as Request[]) : adminFetch<Request[]>(`${base}/requests?${filter}offset=${offset.value}&limit=50`, props.auth),
      props.mode === 'quotas' ? adminFetch<OwnQuota[]>(`${base}/own-key-quotas?${filter}`, props.auth) : Promise.resolve([] as OwnQuota[])
    ])
    if (current !== generation) return
    overview.value = summary; rows.value = history; quotas.value = keyQuotas
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : '조회하지 못했습니다.'
  } finally { if (current === generation) loading.value = false }
}
watch(() => props.organizationId, () => { project.value = ''; offset.value = 0; detailOpen.value = false; detail.value = null; void load() }, { immediate: true })
async function inspect(row: Request) {
  const current = generation
  detailOpen.value = true; detailLoading.value = true; detail.value = null
  try {
    const result = await adminFetch<RequestDetail>(`/api/portal/organizations/${props.organizationId}/usage-overview/requests/${encodeURIComponent(row.requestId)}`, props.auth)
    if (current === generation) detail.value = result
  } catch (e) { if (current === generation) { detailOpen.value = false; error.value = e instanceof Error ? e.message : '상세 조회 실패' } }
  finally { if (current === generation) detailLoading.value = false }
}
function changeProject() { offset.value = 0; void load() }
function page(delta: number) { offset.value = Math.max(0, offset.value + delta); void load() }
function amount(value: number, currency: string) { return `${currency} ${new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 6 }).format(value)}` }
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">MY WORKSPACE</p><h1>{{ mode === 'dashboard' ? '대시보드' : mode === 'observability' ? '관측성' : '요금·한도' }}</h1><p>요약은 최근 30일(UTC), 요청 목록은 전체 기간입니다. 접근 가능한 프로젝트와 발급한 API 키 범위만 표시합니다.</p></div>
      <select v-model="project" aria-label="프로젝트 범위" :disabled="loading || !overview" @change="changeProject"><option value="">접근 가능한 프로젝트 전체</option><option v-for="item in overview?.availableProjects ?? []" :key="item.id" :value="item.id">{{ item.name }} · {{ item.accessLabel }}</option></select></div>
    <p v-if="error" class="inline-alert">{{ error }}</p><p v-else-if="loading">불러오는 중입니다.</p>
    <template v-if="overview">
      <div class="scope-banner"><div><strong>{{ overview.scopeLabel }}</strong><small>조직·팀 공유 잔액과 인프라 상세는 표시하지 않습니다.</small></div></div>
      <div class="metric-grid"><article class="metric-card"><span>요청</span><strong>{{ overview.total.requestCount }}</strong></article><article class="metric-card"><span>성공 / 실패</span><strong>{{ overview.total.succeeded }} / {{ overview.total.failed }}</strong></article><article class="metric-card"><span>입력 / 출력 토큰</span><strong>{{ overview.total.inputTokens }} / {{ overview.total.outputTokens }}</strong></article><article class="metric-card"><span>추정 비용</span><strong>{{ Object.entries(overview.total.estimatedCostByCurrency ?? {}).map(([c, v]) => amount(v, c)).join(' · ') || '기록 없음' }}</strong></article></div>
      <article v-if="mode === 'quotas'" class="surface-card"><header class="card-header"><h2>내가 발급한 API 키 한도</h2></header><p>키에 직접 설정된 한도만 표시합니다. 공유 예산 및 사용액은 조회 권한이 없습니다. 사용액은 기록된 추정 비용의 합계이며 예약액이나 실제 청구액이 아닙니다.</p><div v-if="quotas.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>API 키</th><th>한도</th><th>기간</th><th>추정 사용액 / 상한</th><th>상태</th></tr></thead><tbody><tr v-for="(item, index) in quotas" :key="index"><td>{{ item.keyName }}</td><td>{{ item.name }}</td><td>{{ item.period }}</td><td>{{ amount(item.usedAmount, item.currency) }} / {{ amount(item.limitAmount, item.currency) }}</td><td>{{ item.enabled ? '활성' : '비활성' }}</td></tr></tbody></table></div><p v-else>조회 가능한 키에 직접 설정된 한도가 없습니다.</p></article>
      <article v-else class="surface-card"><header class="card-header"><h2>최근 요청</h2><span>최대 50건</span></header><div v-if="rows.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>시간</th><th>논리 모델</th><th>상태</th><th>토큰</th><th>추정 비용</th><th>지연</th><th>Failover</th><th>상세</th></tr></thead><tbody><tr v-for="row in rows" :key="row.requestId"><td>{{ formatDateTime(row.startedAt) }}</td><td>{{ row.serviceKey ?? '확인 불가' }}</td><td>{{ row.status }} <small>{{ row.errorCode }}</small></td><td>{{ row.inputTokens ?? '–' }} / {{ row.outputTokens ?? '–' }}</td><td>{{ row.estimatedCost == null ? '기록 없음' : amount(row.estimatedCost, row.costCurrency ?? 'KRW') }}</td><td>{{ row.latencyMs == null ? '–' : `${row.latencyMs} ms` }}</td><td>{{ row.failoverCount }}</td><td><button class="text-button" @click="inspect(row)">상세</button></td></tr></tbody></table></div><p v-else>이 범위에 요청 기록이 없습니다.</p><div class="hero-actions"><button :disabled="loading || offset === 0" @click="page(-50)">이전</button><button :disabled="loading || rows.length < 50 || offset >= 10000" @click="page(50)">다음</button></div></article>
    </template>
    <RequestDetailModal :open="detailOpen" :detail="detail" :loading="detailLoading" @close="detailOpen = false" />
  </section>
</template>
