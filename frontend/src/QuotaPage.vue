<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'
import { buildUsageCalendar, buildUsageChart, formatSpend, type UsageCalendarCell, type UsageMetric } from './quotaVisualization'

type ScopeType = 'ORGANIZATION' | 'TEAM' | 'PROJECT' | 'API_KEY'
type Currency = 'KRW' | 'USD'
type Period = 'MONTHLY' | 'DAILY' | 'TOTAL'
type Quota = {
  id: string
  name: string
  scopeType: ScopeType
  scopeId?: string | null
  scopeLabel?: string | null
  currency: Currency
  limitAmount: number
  usedAmount: number
  usagePercent: number
  period: Period
  periodFrom?: string | null
  periodTo?: string | null
  enabled: boolean
  exceeded: boolean
}
type SeriesPoint = { date: string; amount: number; requestCount: number; inputTokens: number; outputTokens: number; currency?: Currency; byCurrency?: Record<string, number> }
type Breakdown = { id?: string; label: string; requestCount: number; amount: number; currency: Currency; inputTokens: number; outputTokens: number; byCurrency?: Record<string, number> }
type Overview = {
  total: { requestCount: number; amount: number; byCurrency?: Record<string, number>; inputTokens: number; outputTokens: number }
  series: SeriesPoint[]
  byProject: Breakdown[]
  byTeam: Breakdown[]
  byApiKey: Breakdown[]
  quotas: Quota[]
  periodFrom?: string | null
  periodTo?: string | null
}

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const overview = ref<Overview | null>(null)
const busy = ref(false)
const modalOpen = ref(false)
const editingId = ref<string | null>(null)
const period = ref<'7d' | '30d' | 'month' | 'all'>('30d')
const usageMetric = ref<UsageMetric>('cost')
const relativeView = ref(true)
const displayCurrency = ref<Currency>('USD')
const calendarMonth = ref(new Date().toISOString().slice(0, 7))
const focusedDate = ref('')
const selectedDate = ref('')
const message = ref('요금 한도와 기간별 사용량을 불러오는 중입니다.')
const form = ref({ name: '', scopeType: 'ORGANIZATION' as ScopeType, scopeId: '', currency: 'KRW' as Currency, limitAmount: 100000, period: 'MONTHLY' as Period, enabled: true })
type ScopeOption = { id: string; scopeType: Exclude<ScopeType, 'ORGANIZATION'>; label: string; description: string }
const scopeOptions = ref<ScopeOption[]>([])

function money(value: number | null | undefined, currency: Currency = 'KRW') {
  return formatSpend(value, currency)
}
function number(value: number | null | undefined) { return new Intl.NumberFormat('ko-KR').format(Number(value ?? 0)) }
function percent(value: number | null | undefined) { return `${Math.min(100, Math.max(0, Number(value ?? 0))).toFixed(1)}%` }
function currencyOf(item: { currency?: Currency } | null | undefined) { return item?.currency === 'USD' ? 'USD' : 'KRW' }
function breakdown(map?: Record<string, number>) {
  if (!map || !Object.keys(map).length) return '₩0'
  return Object.entries(map).map(([key, value]) => money(value, key === 'USD' ? 'USD' : 'KRW')).join(' · ')
}
function queryString() {
  const query = new URLSearchParams()
  if (period.value === 'all') {
    query.set('all', 'true')
  } else {
    const today = new Date()
    const start = new Date(today)
    if (period.value === '7d') start.setUTCDate(start.getUTCDate() - 6)
    if (period.value === '30d') start.setUTCDate(start.getUTCDate() - 29)
    if (period.value === 'month') start.setUTCDate(1)
    query.set('from', start.toISOString().slice(0, 10))
    query.set('to', today.toISOString().slice(0, 10))
  }
  return query.toString() ? `?${query.toString()}` : ''
}
type RawUsage = { requestCount?: number; succeeded?: number; failed?: number; inputTokens?: number; outputTokens?: number; costByCurrency?: Record<string, number> }
type RawDimension = { id: string; name: string; usage?: RawUsage }
type RawQuota = { id: string; organizationId: string; scopeType: ScopeType; scopeId?: string | null; name: string; currency: Currency; limitAmount: number; usedAmount?: number; usagePercent?: number; period: Period; periodFrom?: string | null; periodTo?: string | null; enabled: boolean; exceeded?: boolean; scopeLabel?: string | null }
type RawOverview = { organizationId: string; from?: string; to?: string; total?: RawUsage; series?: Array<{ date: string; usage?: RawUsage }>; byProject?: RawDimension[]; byTeam?: RawDimension[]; byApiKey?: RawDimension[]; quotas?: RawQuota[] }
function sumCurrency(map?: Record<string, number>) { return Object.values(map ?? {}).reduce((sum, value) => sum + Number(value ?? 0), 0) }
function amountFor(map: Record<string, number> | undefined, currency: Currency) { return Number(map?.[currency] ?? 0) }
function normalizeOverview(raw: RawOverview): Overview {
  const usage = (value?: RawUsage) => ({ requestCount: Number(value?.requestCount ?? 0), amount: sumCurrency(value?.costByCurrency), byCurrency: value?.costByCurrency ?? {}, inputTokens: Number(value?.inputTokens ?? 0), outputTokens: Number(value?.outputTokens ?? 0) })
  const dimension = (items: RawDimension[] | undefined): Breakdown[] => (items ?? []).map(item => { const u = usage(item.usage); const currencies = Object.keys(u.byCurrency); return { label: item.name, id: item.id, requestCount: u.requestCount, amount: u.amount, currency: currencies.length === 1 && currencies[0] === 'USD' ? 'USD' : 'KRW', inputTokens: u.inputTokens, outputTokens: u.outputTokens, byCurrency: u.byCurrency } })
  const projects = dimension(raw.byProject); const teams = dimension(raw.byTeam); const keys = dimension(raw.byApiKey)
  const total = usage(raw.total)
  const quotas = (raw.quotas ?? []).map(item => {
    const source = item.scopeType === 'ORGANIZATION' ? total : item.scopeType === 'PROJECT' ? projects.find(row => row.id === item.scopeId) : item.scopeType === 'TEAM' ? teams.find(row => row.id === item.scopeId) : keys.find(row => row.id === item.scopeId)
    const fallbackAmount = item.scopeType === 'ORGANIZATION' ? amountFor(total.byCurrency, item.currency) : amountFor(source?.byCurrency, item.currency)
    const usedAmount = item.usedAmount == null ? fallbackAmount : Number(item.usedAmount)
    const usagePercent = item.usagePercent == null ? (item.limitAmount > 0 ? (usedAmount / item.limitAmount) * 100 : 0) : Number(item.usagePercent)
    return { ...item, scopeLabel: item.scopeLabel ?? (item.scopeType === 'ORGANIZATION' ? '조직 전체' : (source && 'label' in source ? source.label : item.scopeType)), usedAmount, usagePercent, exceeded: item.exceeded ?? usedAmount >= item.limitAmount }
  })
  const series = (raw.series ?? []).map(point => {
    const byCurrency = point.usage?.costByCurrency ?? {}
    const currencies = Object.keys(byCurrency)
    return {
      date: point.date,
      amount: sumCurrency(byCurrency),
      requestCount: Number(point.usage?.requestCount ?? 0),
      inputTokens: Number(point.usage?.inputTokens ?? 0),
      outputTokens: Number(point.usage?.outputTokens ?? 0),
      currency: currencies.length === 1 && currencies[0] === 'USD' ? 'USD' as const : 'KRW' as const,
      byCurrency
    }
  })
  return { total, series, byProject: projects, byTeam: teams, byApiKey: keys, quotas, periodFrom: raw.from, periodTo: raw.to }
}
function optionsForScope(scopeType: ScopeType) {
  return scopeOptions.value.filter(option => option.scopeType === scopeType)
}
function syncScopeId() {
  if (form.value.scopeType === 'ORGANIZATION') {
    form.value.scopeId = props.organizationId
    return
  }
  const options = optionsForScope(form.value.scopeType)
  if (!options.some(option => option.id === form.value.scopeId)) form.value.scopeId = options[0]?.id ?? ''
}
async function loadScopeOptions() {
  if (!props.organizationId) {
    scopeOptions.value = []
    form.value.scopeId = ''
    return
  }
  try {
    const [teams, projects] = await Promise.all([
      adminFetch<Array<{ id: string; name: string }>>('/api/admin/organizations/' + props.organizationId + '/teams', props.auth),
      adminFetch<Array<{ id: string; name: string }>>('/api/admin/organizations/' + props.organizationId + '/projects', props.auth)
    ])
    const keyGroups = await Promise.all(projects.map(async project => {
      try {
        const keys = await adminFetch<Array<{ id: string; name: string; keyPrefix?: string; status?: string }>>('/api/admin/projects/' + project.id + '/api-keys', props.auth)
        return keys.map(key => ({ id: key.id, scopeType: 'API_KEY' as const, label: key.name, description: (key.keyPrefix ?? key.id.slice(0, 8)) + ' · ' + project.name }))
      } catch {
        return []
      }
    }))
    scopeOptions.value = [
      ...teams.map(team => ({ id: team.id, scopeType: 'TEAM' as const, label: team.name, description: '팀' })),
      ...projects.map(project => ({ id: project.id, scopeType: 'PROJECT' as const, label: project.name, description: '프로젝트' })),
      ...keyGroups.flat()
    ]
    syncScopeId()
  } catch {
    scopeOptions.value = []
    if (form.value.scopeType !== 'ORGANIZATION') form.value.scopeId = ''
  }
}
async function load() {
  if (!props.organizationId) { overview.value = null; message.value = '먼저 워크스페이스를 선택하세요.'; return }
  busy.value = true
  try {
    const raw = await adminFetch<RawOverview>(`/api/admin/organizations/${props.organizationId}/quota-overview${queryString()}`, props.auth)
    overview.value = normalizeOverview(raw)
    message.value = `${periodLabel.value} 기준 총 사용량을 집계했습니다.`
  } catch (error) {
    overview.value = null
    message.value = error instanceof Error ? error.message : '요금 사용량을 불러오지 못했습니다.'
  } finally { busy.value = false }
}
const periodLabel = computed(() => period.value === '7d' ? '최근 7일' : period.value === '30d' ? '최근 30일' : period.value === 'month' ? '이번 달' : '전체 기간')
const metricLabel = computed(() => ({ cost: '비용', requests: '요청 수', tokens: '토큰' })[usageMetric.value])
const availableCurrencies = computed<Currency[]>(() => {
  const currencies = new Set(Object.keys(overview.value?.total.byCurrency ?? {}))
  for (const point of overview.value?.series ?? []) Object.keys(point.byCurrency ?? {}).forEach(currency => currencies.add(currency))
  const known = (['USD', 'KRW'] as Currency[]).filter(currency => currencies.has(currency))
  return known.length ? known : ['USD', 'KRW']
})
const availableMonths = computed(() => [...new Set((overview.value?.series ?? []).map(point => point.date.slice(0, 7)))].sort())
const calendarCells = computed(() => buildUsageCalendar(overview.value?.series ?? [], calendarMonth.value, usageMetric.value, displayCurrency.value))
const chart = computed(() => buildUsageChart(overview.value?.series ?? [], usageMetric.value, displayCurrency.value))
const chartLabelPoints = computed(() => chart.value.points.filter((_, index, points) => index === 0 || index === points.length - 1 || index % Math.max(1, Math.ceil(points.length / 5)) === 0))
const focusedPoint = computed(() => chart.value.points.find(point => point.date === focusedDate.value) ?? chart.value.points[chart.value.points.length - 1])
const selectedCell = computed(() => calendarCells.value.find(cell => cell.date === selectedDate.value && cell.inRange))
const monthLabel = computed(() => new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(calendarMonth.value + '-01T00:00:00Z')))
const calendarSummary = computed(() => calendarCells.value.filter(cell => cell.inRange).reduce((sum, cell) => ({ requests: sum.requests + cell.requestCount, tokens: sum.tokens + cell.inputTokens + cell.outputTokens, amount: sum.amount + Number(cell.byCurrency?.[displayCurrency.value] ?? 0) }), { requests: 0, tokens: 0, amount: 0 }))
function metricValueLabel(value: number) {
  return usageMetric.value === 'cost' ? money(value, displayCurrency.value) : number(value) + (usageMetric.value === 'requests' ? '건' : ' 토큰')
}
function ratioLabel(value: number) { return value > 0 && value < .1 ? '<0.1%' : `${value.toFixed(1)}%` }
const chartAxisLabels = computed(() => [1, .75, .5, .25, 0].map(ratio => relativeView.value ? `${chart.value.maximum > 0 ? ratio * 100 : 0}%` : metricValueLabel(chart.value.maximum * ratio)))
const chartAxisWidthLabel = computed(() => chartAxisLabels.value.reduce((longest, label) => label.length > longest.length ? label : longest, ''))
function usageCost(point: { requestCount: number; byCurrency?: Record<string, number> }) {
  return point.requestCount > 0 && !Object.keys(point.byCurrency ?? {}).length ? '비용 미기록' : breakdown(point.byCurrency)
}
function calendarTitle(cell: UsageCalendarCell) {
  return cell.inRange ? `${cell.date} · ${usageCost(cell)} · 요청 ${number(cell.requestCount)}건 · ${number(cell.inputTokens + cell.outputTokens)} 토큰` : `${cell.date} · 조회 범위 밖`
}
watch(availableCurrencies, currencies => { if (!currencies.includes(displayCurrency.value)) displayCurrency.value = currencies[0] })
watch(availableMonths, months => { if (months.length && !months.includes(calendarMonth.value)) calendarMonth.value = months[months.length - 1] })
watch(chart, value => { if (!value.points.some(point => point.date === focusedDate.value)) focusedDate.value = ([...value.points].reverse().find(point => point.requestCount > 0) ?? value.points[value.points.length - 1])?.date ?? '' })
watch(calendarCells, cells => { if (!cells.some(cell => cell.date === selectedDate.value && cell.inRange)) selectedDate.value = ([...cells].reverse().find(cell => cell.inRange && cell.requestCount > 0) ?? cells.find(cell => cell.inRange))?.date ?? '' })
function openCreate() {
  editingId.value = null
  form.value = { name: '', scopeType: 'ORGANIZATION', scopeId: props.organizationId, currency: 'KRW', limitAmount: 100000, period: 'MONTHLY', enabled: true }
  modalOpen.value = true
}
function openEdit(item: Quota) {
  editingId.value = item.id
  form.value = { name: item.name, scopeType: item.scopeType, scopeId: item.scopeId ?? '', currency: item.currency, limitAmount: item.limitAmount, period: item.period, enabled: item.enabled }
  modalOpen.value = true
}
async function saveQuota() {
  if (!props.organizationId || !form.value.name.trim() || form.value.limitAmount <= 0) return
  busy.value = true
  try {
    const path = editingId.value ? `/api/admin/quotas/${editingId.value}` : `/api/admin/organizations/${props.organizationId}/quotas`
    await adminFetch(path, props.auth, { method: editingId.value ? 'PATCH' : 'POST', body: JSON.stringify({ ...form.value, name: form.value.name.trim(), scopeId: form.value.scopeType === 'ORGANIZATION' ? props.organizationId : form.value.scopeId || null }) })
    modalOpen.value = false
    await load()
  } catch (error) { message.value = error instanceof Error ? error.message : '요금 한도 저장에 실패했습니다.' }
  finally { busy.value = false }
}
async function removeQuota(item: Quota) {
  if (!confirm(`'${item.name}' 한도를 삭제할까요?`)) return
  busy.value = true
  try { await adminFetch(`/api/admin/quotas/${item.id}`, props.auth, { method: 'DELETE' }); await load() }
  catch (error) { message.value = error instanceof Error ? error.message : '요금 한도 삭제에 실패했습니다.' }
  finally { busy.value = false }
}
watch(() => props.organizationId, () => { void load(); void loadScopeOptions() })
watch(() => form.value.scopeType, () => syncScopeId())
onMounted(() => { void load(); void loadScopeOptions() })
</script>

<template>
  <section class="page-stack quota-page">
    <div class="page-hero">
      <div><p class="eyebrow">BUDGET CONTROL</p><h1>요금·한도</h1><p>조직, 팀, 프로젝트, API 키별로 비용 상한을 설정하고 초과 요청을 자동 차단합니다.</p></div>
      <div class="hero-actions"><select v-model="period" :disabled="busy" aria-label="조회 기간" @change="load"><option value="7d">최근 7일</option><option value="30d">최근 30일</option><option value="month">이번 달</option><option value="all">전체 기간</option></select><button class="secondary-button" :disabled="busy" @click="load">새로고침</button><button class="primary-button" :disabled="busy || !organizationId" @click="openCreate">+ 한도 추가</button></div>
    </div>
    <p class="inline-alert">{{ message }}</p>

    <template v-if="overview">
      <div class="metric-grid six budget-metrics">
        <article class="metric-card accent"><span>전체 요청</span><strong>{{ number(overview.total.requestCount) }}</strong><small>{{ periodLabel }}</small></article>
        <article class="metric-card"><span>입력 토큰</span><strong>{{ number(overview.total.inputTokens) }}</strong></article>
        <article class="metric-card"><span>출력 토큰</span><strong>{{ number(overview.total.outputTokens) }}</strong></article>
        <article class="metric-card"><span>총 비용</span><strong class="metric-money">{{ breakdown(overview.total.byCurrency) }}</strong><small>통화별 합산</small></article>
        <article class="metric-card"><span>활성 한도</span><strong>{{ number(overview.quotas.filter(item => item.enabled).length) }}</strong><small>범위별 제한</small></article>
        <article class="metric-card" :class="{ warning: overview.quotas.some(item => item.exceeded) }"><span>초과 한도</span><strong>{{ number(overview.quotas.filter(item => item.exceeded).length) }}</strong><small>요청 차단 중</small></article>
      </div>

      <div class="usage-toolbar">
        <label>표시 지표<select v-model="usageMetric" aria-label="사용량 표시 지표"><option value="cost">비용</option><option value="requests">요청 수</option><option value="tokens">토큰</option></select></label>
        <label v-if="usageMetric === 'cost'">통화<select v-model="displayCurrency" aria-label="사용량 통화"><option v-for="currency in availableCurrencies" :key="currency" :value="currency">{{ currency === 'USD' ? '달러 (USD)' : '원화 (KRW)' }}</option></select></label>
        <label class="relative-toggle"><input v-model="relativeView" type="checkbox" /> 상대 비교 (%)</label>
        <p>{{ relativeView ? '그래프 최고 사용일을 100%로 표시합니다. 실제 값은 아래에 함께 표시됩니다.' : '실제 값 기준으로 눈금을 자동 조정합니다.' }} 비용은 통화별로 구분합니다.</p>
      </div>

      <div class="quota-layout">
        <article class="surface-card chart-card">
          <header class="card-header"><div><span class="card-kicker">DAILY USAGE</span><h2>일별 {{ metricLabel }} 흐름</h2></div><span class="chart-max">최고 일 {{ metricValueLabel(chart.maximum) }}</span></header>
          <div class="line-chart">
            <div class="chart-plot">
              <div class="chart-y-axis"><span class="axis-sizing" aria-hidden="true">{{ chartAxisWidthLabel }}</span><span v-for="(label, index) in chartAxisLabels" :key="index" :style="{ top: `${chart.pad + index * (chart.height - chart.pad * 2) / 4}px` }">{{ label }}</span></div>
              <svg :viewBox="`0 0 ${chart.width} ${chart.height}`" preserveAspectRatio="none" :aria-label="`일별 ${metricLabel} 그래프`">
                <g class="chart-grid" aria-hidden="true"><line v-for="ratio in [0, .25, .5, .75, 1]" :key="ratio" :x1="chart.pad" :x2="chart.width - chart.pad" :y1="chart.height - chart.pad - ratio * (chart.height - chart.pad * 2)" :y2="chart.height - chart.pad - ratio * (chart.height - chart.pad * 2)" /></g>
                <path class="chart-area" :d="chart.area" /><path class="chart-line" :d="chart.path" />
                <g v-for="point in chart.points" :key="point.date">
                  <circle v-if="point.value > 0" class="chart-dot" :cx="point.x" :cy="point.y" r="3" />
                  <circle class="chart-hit" :cx="point.x" :cy="point.y" r="9" tabindex="0" role="button" :aria-label="`${point.date} ${metricValueLabel(point.value)}`" @mouseenter="focusedDate = point.date" @focus="focusedDate = point.date" @click="focusedDate = point.date" @keydown.enter="focusedDate = point.date"><title>{{ point.date }} · {{ metricValueLabel(point.value) }} · {{ number(point.requestCount) }}건</title></circle>
                </g>
              </svg>
            </div>
            <div v-if="!chart.points.length" class="chart-empty">선택 기간에 사용량 데이터가 없습니다.</div>
            <div v-else class="chart-labels"><button v-for="point in chartLabelPoints" :key="point.date" type="button" @click="focusedDate = point.date">{{ point.date.slice(5) }}<b>{{ metricValueLabel(point.value) }}</b></button></div>
            <div v-if="focusedPoint" class="chart-readout" aria-live="polite">
              <strong>{{ focusedPoint.date }}</strong><b>{{ metricValueLabel(focusedPoint.value) }}</b><span v-if="relativeView">최고 일 대비 {{ ratioLabel(focusedPoint.relativePercent) }}</span>
              <small>요청 {{ number(focusedPoint.requestCount) }}건 · 입력 {{ number(focusedPoint.inputTokens) }} / 출력 {{ number(focusedPoint.outputTokens) }} 토큰 · {{ usageCost(focusedPoint) }}</small>
            </div>
            <p class="visualization-note">작은 비용도 실제 최대값에 맞춰 확대합니다. 그래프 점이나 날짜를 선택하면 실제 값을 확인할 수 있습니다. 일자는 UTC 기준입니다.</p>
          </div>
        </article>
        <article class="surface-card quota-card"><header class="card-header"><div><span class="card-kicker">QUOTA STATUS</span><h2>한도 현황</h2></div><span class="count-badge">{{ overview.quotas.length }}</span></header><div v-if="overview.quotas.length" class="quota-list"><div v-for="item in overview.quotas" :key="item.id" class="quota-row" :class="{ exceeded: item.exceeded }"><div class="quota-row-head"><div><strong>{{ item.name }}</strong><small>{{ item.scopeLabel || item.scopeType }} · {{ item.period }}</small></div><span :class="['status-chip tiny', item.exceeded ? 'unhealthy' : 'healthy']">{{ item.exceeded ? 'EXCEEDED' : item.enabled ? 'ACTIVE' : 'PAUSED' }}</span></div><div class="quota-progress"><span :style="{ width: `${Math.min(100, item.usagePercent)}%` }"></span></div><div class="quota-row-foot"><span>{{ money(item.usedAmount, item.currency) }} / {{ money(item.limitAmount, item.currency) }}</span><span>{{ percent(item.usagePercent) }}</span></div><div class="quota-actions"><button class="text-button" @click="openEdit(item)">수정</button><button class="text-button danger-text" @click="removeQuota(item)">삭제</button></div></div></div><div v-else class="empty-state compact"><span>◌</span><p>등록된 요금 한도가 없습니다.</p></div></article>
      </div>

      <article class="surface-card calendar-card">
        <header class="card-header"><div><span class="card-kicker">MONTHLY CALENDAR</span><h2>월별 {{ metricLabel }} 캘린더</h2></div><label class="month-selector">표시 월<select v-model="calendarMonth" aria-label="캘린더 표시 월"><option v-for="month in availableMonths" :key="month" :value="month">{{ month }}</option></select></label></header>
        <div class="calendar-wrap">
          <div class="calendar-summary"><strong>{{ monthLabel }}</strong><span>조회된 날짜 합계: {{ number(calendarSummary.requests) }}건 · {{ number(calendarSummary.tokens) }} 토큰 · {{ money(calendarSummary.amount, displayCurrency) }}</span></div>
          <p class="calendar-scroll-hint">캘린더를 좌우로 스크롤하면 모든 날짜를 볼 수 있습니다.</p>
          <div class="calendar-scroll">
            <div class="calendar-week"><span v-for="label in ['일', '월', '화', '수', '목', '금', '토']" :key="label">{{ label }}</span></div>
            <div class="calendar-grid">
              <template v-for="(cell, index) in calendarCells" :key="`${cell.date || 'empty'}-${index}`">
                <button v-if="cell.inMonth" type="button" :class="['calendar-cell', `heat-${cell.level}`, { unqueried: !cell.inRange, selected: selectedDate === cell.date, active: cell.requestCount > 0 }]" :disabled="!cell.inRange" :title="calendarTitle(cell)" :aria-pressed="selectedDate === cell.date" @click="selectedDate = cell.date">
                  <strong>{{ cell.day }}</strong><b>{{ cell.inRange ? metricValueLabel(cell.value) : '미조회' }}</b><small v-if="cell.inRange">{{ number(cell.requestCount) }}건<template v-if="relativeView && cell.value > 0"> · {{ ratioLabel(cell.relativePercent) }}</template></small>
                </button>
                <span v-else class="calendar-cell empty" aria-hidden="true"></span>
              </template>
            </div>
          </div>
          <div class="calendar-legend"><span>{{ metricLabel }} 적음</span><i class="heat-0"></i><i class="heat-1"></i><i class="heat-2"></i><i class="heat-3"></i><i class="heat-4"></i><span>많음 · 표시 월 최고일 기준</span></div>
          <div v-if="selectedCell" class="calendar-detail" aria-live="polite"><strong>{{ selectedCell.date }} 상세</strong><span>비용 <b>{{ usageCost(selectedCell) }}</b></span><span>요청 <b>{{ number(selectedCell.requestCount) }}건</b></span><span>입력 <b>{{ number(selectedCell.inputTokens) }} 토큰</b></span><span>출력 <b>{{ number(selectedCell.outputTokens) }} 토큰</b></span><span v-if="relativeView">이 달 최고일 대비 <b>{{ ratioLabel(selectedCell.relativePercent) }}</b></span></div>
          <p class="visualization-note">조회 범위: {{ overview.periodFrom }} ~ {{ overview.periodTo }} · 범위 밖 날짜는 미조회로 표시됩니다. 비용이 0이거나 미기록이어도 요청 수·토큰으로 실제 사용을 확인할 수 있습니다.</p>
        </div>
      </article>

      <div class="quota-layout breakdown-grid"><article class="surface-card"><header class="card-header"><div><span class="card-kicker">PROJECTS</span><h2>프로젝트별 비용</h2></div><span class="count-badge">{{ overview.byProject.length }}</span></header><div v-if="overview.byProject.length" class="breakdown-list"><div v-for="item in overview.byProject" :key="item.label" class="breakdown-row"><div><strong>{{ item.label }}</strong><small>{{ number(item.requestCount) }}건 · {{ number(item.inputTokens + item.outputTokens) }} tokens</small></div><b>{{ usageCost(item) }}</b></div></div><div v-else class="empty-state compact"><span>◌</span><p>프로젝트 사용량이 없습니다.</p></div></article><article class="surface-card"><header class="card-header"><div><span class="card-kicker">TEAMS</span><h2>부서·팀별 비용</h2></div><span class="count-badge">{{ overview.byTeam.length }}</span></header><div v-if="overview.byTeam.length" class="breakdown-list"><div v-for="item in overview.byTeam" :key="item.label" class="breakdown-row"><div><strong>{{ item.label }}</strong><small>{{ number(item.requestCount) }}건 · {{ number(item.inputTokens + item.outputTokens) }} tokens</small></div><b>{{ usageCost(item) }}</b></div></div><div v-else class="empty-state compact"><span>◌</span><p>팀별 사용량이 없습니다.</p></div></article></div>
      <article class="surface-card"><header class="card-header"><div><span class="card-kicker">API KEYS</span><h2>API 키별 비용</h2></div><span class="count-badge">{{ overview.byApiKey.length }}</span></header><div v-if="overview.byApiKey.length" class="breakdown-table"><div v-for="item in overview.byApiKey" :key="item.label" class="breakdown-row"><div><strong>{{ item.label }}</strong><small>{{ number(item.requestCount) }}건 · {{ number(item.inputTokens + item.outputTokens) }} tokens</small></div><b>{{ usageCost(item) }}</b></div></div><div v-else class="empty-state compact"><span>◌</span><p>API 키 사용량이 없습니다.</p></div></article>
    </template>
  </section>

  <BaseModal v-if="modalOpen" :open="modalOpen" :title="editingId ? '요금 한도 수정' : '요금 한도 추가'" description="범위와 통화를 선택해 비용 상한을 설정합니다." @close="modalOpen = false"><div class="modal-form"><label class="field">한도 이름<input v-model.trim="form.name" placeholder="예: 팀 월간 예산" maxlength="120" /></label><div class="form-grid two"><label class="field">적용 범위<select v-model="form.scopeType"><option value="ORGANIZATION">조직 전체</option><option value="TEAM">팀</option><option value="PROJECT">프로젝트</option><option value="API_KEY">API 키</option></select></label><label v-if="form.scopeType === 'ORGANIZATION'" class="field">적용 대상<input :value="props.organizationId" disabled /></label><label v-else class="field">적용 대상<select v-model="form.scopeId"><option value="">대상을 선택하세요</option><option v-for="option in optionsForScope(form.scopeType)" :key="option.scopeType + '-' + option.id" :value="option.id">{{ option.label }} · {{ option.description }}</option></select></label></div><div class="form-grid two"><label class="field">통화<select v-model="form.currency"><option value="KRW">원화 (KRW)</option><option value="USD">달러 (USD)</option></select></label><label class="field">주기<select v-model="form.period"><option value="MONTHLY">월간</option><option value="DAILY">일간</option><option value="TOTAL">전체 누적</option></select></label></div><label class="field">최대 금액<input v-model.number="form.limitAmount" type="number" min="0.01" step="0.01" /></label><label class="check-row"><input v-model="form.enabled" type="checkbox" /> 한도 활성화</label></div><template #footer><button class="secondary-button" @click="modalOpen = false">취소</button><button class="primary-button" :disabled="busy || !form.name.trim() || form.limitAmount <= 0 || (form.scopeType !== 'ORGANIZATION' && !form.scopeId)" @click="saveQuota">저장</button></template></BaseModal>
</template>

<style scoped>
.quota-page{--surface-muted:var(--surface-2);min-width:0}
.quota-page .surface-card,.quota-page .quota-layout{min-width:0}
.usage-toolbar{display:flex;flex-wrap:wrap;gap:14px 20px;align-items:center;padding:16px 20px;border:1px solid var(--border);border-radius:12px;background:var(--surface)}
.usage-toolbar label,.month-selector{display:flex;align-items:center;gap:8px;color:var(--text-soft);font-size:11px;white-space:nowrap}
.usage-toolbar select,.month-selector select{min-height:34px;padding:0 28px 0 10px;border:1px solid var(--border);border-radius:8px;background:var(--surface-2);color:var(--text)}
.usage-toolbar .relative-toggle input{width:16px;height:16px;margin:0;accent-color:var(--accent-strong)}
.usage-toolbar p{flex:1;min-width:180px;margin:0;color:var(--muted);font-size:10px;line-height:1.6}
.chart-card .card-header,.calendar-card .card-header{flex-wrap:wrap}
.quota-page .line-chart{min-height:0;padding:16px 22px 20px}
.chart-plot{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:12px;min-width:0}
.chart-y-axis{position:relative;height:210px;color:var(--muted);font-size:9px;text-align:right}
.chart-y-axis>span{position:absolute;right:0;transform:translateY(-50%)}
.chart-y-axis>.axis-sizing{position:static;display:block;visibility:hidden;transform:none}
.quota-page .chart-plot svg{min-width:0;background:none}
.chart-grid line{stroke:var(--border);stroke-width:1;stroke-dasharray:3 5}
.chart-dot{fill:var(--accent-strong)}
.chart-hit{fill:transparent;cursor:pointer}
.chart-hit:focus{outline:none;stroke:var(--accent-strong);stroke-width:2}
.quota-page .chart-labels{margin-top:8px;overflow-x:auto;align-items:flex-start}
.chart-labels button{display:grid;gap:4px;flex-shrink:0;padding:3px 0;border:0;background:none;color:var(--muted);font-size:9px;text-align:left}
.chart-readout{display:flex;flex-wrap:wrap;align-items:center;gap:7px 12px;margin-top:14px;padding:12px 14px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);font-size:11px;overflow-wrap:anywhere}
.chart-readout>b{font-size:13px;color:var(--accent-strong)}
.chart-readout>span{color:var(--muted);font-size:10px}
.chart-readout small{flex-basis:100%;color:var(--muted);font-size:10px;line-height:1.6}
.visualization-note{margin:12px 0 0;color:var(--muted);font-size:10px;line-height:1.6}
.quota-page .calendar-wrap{padding:18px 22px 22px;min-width:0}
.calendar-summary{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:16px}
.calendar-summary strong{font-size:14px}.calendar-summary span{color:var(--muted);font-size:11px;overflow-wrap:anywhere}
.calendar-scroll{min-width:0;max-width:100%;overflow-x:auto}
.calendar-scroll-hint{display:none;margin:0 0 10px;color:var(--muted);font-size:10px;line-height:1.5}
.quota-page .calendar-week,.quota-page .calendar-grid{min-width:600px}
.quota-page .calendar-cell{min-height:80px;padding:9px 8px;align-content:start;justify-items:start;gap:5px;text-align:left}
.calendar-cell strong{font-size:12px}.calendar-cell b{font-size:10px;line-height:1.4;overflow-wrap:anywhere}.calendar-cell small{font-size:9px;opacity:.8}
.calendar-cell.heat-4{color:var(--accent-ink)}
.calendar-cell.selected{border-color:var(--accent-strong);box-shadow:inset 0 0 0 1px var(--accent-strong)}
.calendar-cell.unqueried{border-style:dashed;background:transparent;opacity:.45}
.calendar-cell:disabled:hover{transform:none;border-color:var(--border)}
.calendar-detail{display:flex;flex-wrap:wrap;gap:10px 22px;margin-top:16px;padding:14px 16px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);font-size:11px}
.calendar-detail>strong{flex-basis:100%;font-size:12px}.calendar-detail>span{color:var(--muted)}.calendar-detail b{margin-left:5px;color:var(--text);overflow-wrap:anywhere}
.quota-page .breakdown-row>div{min-width:0;overflow-wrap:anywhere}.quota-page .breakdown-row>b{white-space:normal;overflow-wrap:anywhere;text-align:right}
.budget-metrics .metric-card strong{font-size:clamp(20px,3vw,32px);overflow-wrap:anywhere}
.quota-page .hero-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.quota-page .hero-actions select{min-height:40px;padding:0 30px 0 12px;border:1px solid var(--border);border-radius:10px;background:var(--surface);color:var(--text);font:inherit}.metric-money{font-size:clamp(16px,1.5vw,24px)!important}.quota-layout{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(320px,.7fr);gap:16px}.chart-card,.quota-card{min-height:350px}.line-chart{position:relative;min-height:260px;padding:16px 14px 0}.line-chart svg{width:100%;height:210px;overflow:visible;background:linear-gradient(to bottom,transparent 24%,var(--grid-line) 25%,transparent 26%,transparent 49%,var(--grid-line) 50%,transparent 51%,transparent 74%,var(--grid-line) 75%,transparent 76%)}.chart-line{fill:none;stroke:var(--accent-strong);stroke-width:3;stroke-linecap:round;stroke-linejoin:round}.chart-area{fill:var(--accent-dim);opacity:.65}.chart-empty{position:absolute;inset:80px 0 0;display:grid;place-items:center;color:var(--muted);font-size:12px}.chart-labels{display:flex;justify-content:space-between;gap:8px;color:var(--muted);font-size:9px}.chart-labels span{display:grid;gap:3px}.chart-labels b{color:var(--text);font-size:9px;font-weight:700}.chart-max{color:var(--muted);font-size:10px}.calendar-card{min-height:250px}.calendar-wrap{padding:0 18px 18px}.calendar-week,.calendar-grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:5px}.calendar-week{margin-bottom:5px;color:var(--muted);font-size:9px;text-align:center}.calendar-cell{display:grid;place-items:center;min-height:30px;border-radius:7px;border:1px solid var(--border);font-size:10px;color:var(--text);transition:transform .15s}.calendar-cell:not(.empty):hover{transform:translateY(-1px);border-color:var(--accent-strong)}.calendar-cell.empty{visibility:hidden;border-color:transparent;background:transparent}.heat-0{background:var(--surface-muted)}.heat-1{background:color-mix(in srgb,var(--accent-dim) 45%,var(--surface-muted))}.heat-2{background:color-mix(in srgb,var(--accent-dim) 70%,var(--surface-muted))}.heat-3{background:color-mix(in srgb,var(--accent) 45%,var(--surface-muted))}.heat-4{background:var(--accent)}.calendar-legend{display:flex;align-items:center;justify-content:flex-end;gap:4px;margin-top:10px;color:var(--muted);font-size:9px}.calendar-legend i{display:inline-block;width:12px;height:12px;border:1px solid var(--border);border-radius:3px}.quota-list{display:grid;gap:10px;padding:0 16px 16px;max-height:410px;overflow:auto}.quota-row{position:relative;padding:13px;border:1px solid var(--border);border-radius:12px;background:var(--surface-muted)}.quota-row.exceeded{border-color:color-mix(in srgb,var(--danger) 45%,var(--border))}.quota-row-head,.quota-row-foot{display:flex;justify-content:space-between;gap:10px;align-items:center}.quota-row-head strong,.quota-row-head small{display:block}.quota-row-head strong{font-size:12px}.quota-row-head small{margin-top:4px;color:var(--muted);font-size:9px}.quota-progress{height:7px;margin:12px 0 7px;overflow:hidden;border-radius:99px;background:var(--border)}.quota-progress span{display:block;height:100%;border-radius:inherit;background:var(--accent-strong);transition:width .2s}.quota-row.exceeded .quota-progress span{background:var(--danger)}.quota-row-foot{font-size:10px;color:var(--muted)}.quota-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:8px}.text-button{border:0;background:none;color:var(--accent-strong);font:inherit;font-size:10px;cursor:pointer}.breakdown-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.breakdown-list,.breakdown-table{display:grid;padding:0 16px 16px}.breakdown-row{display:flex;justify-content:space-between;gap:12px;padding:12px 2px;border-bottom:1px solid var(--border);align-items:center}.breakdown-row:last-child{border-bottom:0}.breakdown-row strong,.breakdown-row small{display:block}.breakdown-row strong{font-size:12px}.breakdown-row small{margin-top:4px;color:var(--muted);font-size:9px}.breakdown-row b{font-size:13px;white-space:nowrap}.budget-metrics{grid-template-columns:repeat(6,minmax(0,1fr))}@media(max-width:1100px){.quota-layout{grid-template-columns:1fr}.budget-metrics{grid-template-columns:repeat(3,minmax(0,1fr))}}@media(max-width:720px){.breakdown-grid{grid-template-columns:1fr}.budget-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.quota-page .hero-actions{display:grid;grid-template-columns:1fr 1fr}.quota-page .hero-actions .primary-button{grid-column:1/-1}.quota-page .hero-actions select{width:100%}}
@media(max-width:720px){.usage-toolbar p{flex-basis:100%}.calendar-summary{align-items:flex-start;flex-direction:column}.calendar-scroll-hint{display:block}}
@media(max-width:480px){.quota-page .line-chart,.quota-page .calendar-wrap{padding:16px}.chart-plot{gap:8px}.chart-y-axis{font-size:8px}.calendar-detail{padding:12px}.calendar-legend{flex-wrap:wrap}}
</style>
