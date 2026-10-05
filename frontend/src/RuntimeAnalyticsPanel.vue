<script setup lang="ts">
import type { NumericSummary, RequestMetrics, AttemptMetrics, TimeSeriesPoint, RuntimeAnalytics } from './runtimeAnalytics'

defineProps<{ analytics: RuntimeAnalytics }>()
function integer(value: number | null | undefined) { return value == null ? '—' : new Intl.NumberFormat('ko-KR').format(value) }
function average(value: NumericSummary | null | undefined, suffix = '') { return value?.average == null ? '—' : new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value.average) + suffix }
function range(value: NumericSummary | null | undefined, suffix = '') {
  if (!value || value.sampleCount === 0) return '표본 없음'
  return `평균 ${average(value, suffix)} · 최소 ${integer(value.minimum)}${suffix} · 최대 ${integer(value.maximum)}${suffix} · n=${integer(value.sampleCount)}`
}
function width(value: number, max: number) { return `${max > 0 ? Math.max(value > 0 ? 3 : 0, value / max * 100) : 0}%` }
function maxRequests(items: TimeSeriesPoint[]) { return Math.max(0, ...items.map(item => item.requests)) }
function money(values: Record<string, number>) {
  const entries = Object.entries(values ?? {})
  if (!entries.length) return '비용 미산정'
  return entries.map(([currency, value]) => new Intl.NumberFormat(currency === 'USD' ? 'en-US' : 'ko-KR', {
    style: 'currency', currency: currency === 'USD' ? 'USD' : 'KRW', maximumFractionDigits: 8
  }).format(Number(value ?? 0))).join(' · ')
}
function success(item: RequestMetrics | AttemptMetrics) { return `${Number(item.successRatePercent ?? 0).toFixed(1)}%` }
function textLabel(value: string) {
  const labels: Record<string, string> = { TEXT_CHAT: '일반 텍스트', VISION: 'Vision', STRUCTURED_OUTPUT: 'Structured Output', TOOL_CALLING: 'Tool Calling', STREAMING: '스트리밍', NON_STREAMING: '일반 응답', UNSPECIFIED: '요청 미지정', UNKNOWN: '이전 기록 · 미분류' }
  return labels[value] ?? value
}
function timelineHeight(item: TimeSeriesPoint, all: TimeSeriesPoint[]) { return width(item.requests, maxRequests(all)) }
</script>

<template>
  <section class="surface-card runtime-analytics">
    <header class="card-header analytics-heading">
      <div><span class="card-kicker">RUNTIME ANALYTICS</span><h2>등록 서버·모델 상세 통계</h2><p>현재 사용량 화면의 기간·프로젝트 권한 범위에 맞춰 계산합니다.</p></div>
      <span class="count-badge">{{ integer(analytics.byServer.length) }} 서버 · {{ integer(analytics.byModel.length) }} 모델</span>
    </header>

    <div class="analytics-body">
    <div class="analytics-note">
      <b>지표 해석</b>
      <p>API 요청 수는 논리 요청 기준이며, 최종 응답을 완료한 모델에 귀속됩니다. 개별 Runtime/모델 호출은 재시도와 실패 전환을 포함한 Attempt 통계로 따로 셉니다. 따라서 두 요청 수는 다를 수 있습니다. 성공률은 완료된 요청(성공+실패)을 분모로 하며, 진행 중은 제외합니다.</p>
      <p>토큰·지연 평균/최솟값/최댓값은 해당 값이 기록된 표본만 사용합니다. 누락된 토큰은 0으로 간주하지 않습니다. 현재 토큰 수는 Provider 반환 usage 또는 Gateway 추정치일 수 있으며 출처별 구분은 저장되지 않습니다. Structured Output은 JSON Schema 요청 기준입니다(JSON Object 모드는 별도 분류되지 않음). 비용은 등록 가격 기준 추정치이며 USD·KRW를 합산하지 않습니다. 요청 기능은 중복될 수 있어 비율 합이 100%를 넘을 수 있습니다.</p>
    </div>

    <div class="analytics-subheading"><div><span class="card-kicker">TRAFFIC TREND</span><h3>{{ analytics.timeSeriesGranularity === 'MONTH' ? '월별 요청량' : '일별 요청량' }}</h3></div><small>막대 높이: 전체 요청 · 표기값: 요청 수 / 토큰 수</small></div>
    <div v-if="analytics.timeSeries.length" class="timeline-chart" role="img" :aria-label="analytics.timeSeriesGranularity === 'MONTH' ? '월별 요청 통계' : '일별 요청 통계'">
      <div v-for="item in analytics.timeSeries" :key="item.period" class="timeline-item" :title="`${item.period}: 요청 ${integer(item.requests)} · 성공 ${integer(item.succeeded)} · 실패 ${integer(item.failed)} · 토큰 ${integer(item.tokens)}`">
        <div class="bar-track"><span class="bar-fill" :style="{ height: timelineHeight(item, analytics.timeSeries) }"></span></div>
        <strong>{{ integer(item.requests) }}</strong><small>{{ item.period }}</small>
      </div>
    </div>
    <div v-else class="empty-state compact"><span>◴</span><p>선택 범위에 요청 이력이 없습니다.</p></div>

    <div class="analytics-subheading"><div><span class="card-kicker">REGISTERED RUNTIMES</span><h3>서버별 처리량·신뢰도</h3></div></div>
    <div v-if="analytics.byServer.length" class="table-wrap"><table class="stats-table"><thead><tr><th>등록 서버</th><th>완료 요청</th><th>Runtime 시도</th><th>응답 시간</th><th>입력 토큰</th><th>출력 토큰</th><th>전체 토큰</th><th>Failover / 비용</th></tr></thead><tbody>
      <tr v-for="item in analytics.byServer" :key="item.serverId"><td><strong>{{ item.serverName }}</strong><small>{{ item.serverType }} · {{ item.detail }}</small></td><td><b>{{ integer(item.requests.requestCount) }}</b><small>성공 {{ integer(item.requests.succeeded) }} · 실패 {{ integer(item.requests.failed) }} · {{ success(item.requests) }}</small></td><td><b>{{ integer(item.attempts.total) }}</b><small>성공 {{ integer(item.attempts.succeeded) }} · 실패 {{ integer(item.attempts.failed) }} · {{ success(item.attempts) }}</small></td><td>{{ range(item.requests.latencyMs, ' ms') }}</td><td>{{ range(item.requests.inputTokensStats) }}</td><td>{{ range(item.requests.outputTokensStats) }}</td><td>{{ range(item.requests.totalTokensStats) }}</td><td><b>{{ integer(item.requests.failoverRequests) }}회 요청 · {{ integer(item.requests.failoverAttempts) }}회 전환</b><small>{{ money(item.requests.estimatedCostByCurrency) }}<template v-if="item.requests.unknownCostRequests"> · 미산정 {{ integer(item.requests.unknownCostRequests) }}</template></small></td></tr>
    </tbody></table></div>
    <div v-else class="empty-state compact"><span>◇</span><p>선택 범위에 처리된 등록 서버가 없습니다. 배포 대상을 정하지 못한 실패는 아래 오류 분포에서 확인하세요.</p></div>

    <div class="analytics-subheading"><div><span class="card-kicker">MODEL RANKING</span><h3>모델별 사용·실패 시도</h3></div><small>모델 호출량은 Attempt를 기준으로 정렬합니다.</small></div>
    <div v-if="analytics.byModel.length" class="table-wrap"><table class="stats-table model-table"><thead><tr><th>모델 / 서버</th><th>완료 요청</th><th>시도 성공률</th><th>평균 응답</th><th>입력 평균·범위</th><th>출력 평균·범위</th><th>전체 평균·범위</th><th>비용</th></tr></thead><tbody>
      <tr v-for="item in analytics.byModel" :key="item.deploymentId"><td><strong>{{ item.modelName }}</strong><small>{{ item.detail }}</small></td><td><b>{{ integer(item.requests.requestCount) }}</b><small>성공 {{ integer(item.requests.succeeded) }} · 실패 {{ integer(item.requests.failed) }}</small></td><td><b>{{ integer(item.attempts.total) }}회 시도</b><small>성공 {{ integer(item.attempts.succeeded) }} · 실패 {{ integer(item.attempts.failed) }} · {{ success(item.attempts) }}</small></td><td>{{ range(item.attempts.latencyMs, ' ms') }}</td><td>{{ range(item.requests.inputTokensStats) }}</td><td>{{ range(item.requests.outputTokensStats) }}</td><td>{{ range(item.requests.totalTokensStats) }}</td><td>{{ money(item.requests.estimatedCostByCurrency) }}</td></tr>
    </tbody></table></div>
    <div v-else class="empty-state compact"><span>◇</span><p>선택 범위에서 연결된 모델의 처리 기록이 없습니다.</p></div>

    <div class="breakdown-grid">
      <article class="breakdown-card"><div class="analytics-subheading"><div><span class="card-kicker">REQUEST FEATURES</span><h3>요청 기능</h3></div></div><p class="helper">Vision, Structured Output, Tool Calling은 한 요청에 함께 들어갈 수 있습니다.</p><div v-for="item in analytics.byCapability" :key="item.label" class="breakdown-row"><span>{{ textLabel(item.label) }}</span><div class="breakdown-track"><i :style="{ width: width(item.percentOfRequests, 100) }"></i></div><b>{{ integer(item.requests) }}</b><small>{{ item.percentOfRequests.toFixed(1) }}%</small></div><div v-if="!analytics.byCapability.length" class="empty-state compact"><p>요청 기능 분류가 없습니다.</p></div></article>
      <article class="breakdown-card"><div class="analytics-subheading"><div><span class="card-kicker">REQUEST MODES</span><h3>유형·실행 모드</h3></div></div><div class="breakdown-title">조합별 요청 유형</div><div v-for="item in analytics.byRequestType" :key="item.label" class="breakdown-row"><span>{{ textLabel(item.label) }}</span><div class="breakdown-track"><i :style="{ width: width(item.percentOfRequests, 100) }"></i></div><b>{{ integer(item.requests) }}</b><small>{{ item.percentOfRequests.toFixed(1) }}%</small></div><div class="breakdown-title">스트리밍</div><div v-for="item in analytics.byStreamMode" :key="item.label" class="breakdown-row"><span>{{ textLabel(item.label) }}</span><div class="breakdown-track"><i :style="{ width: width(item.percentOfRequests, 100) }"></i></div><b>{{ integer(item.requests) }}</b><small>{{ item.percentOfRequests.toFixed(1) }}%</small></div></article>
      <article class="breakdown-card"><div class="analytics-subheading"><div><span class="card-kicker">EXECUTION PROFILE</span><h3>추론 수준·서비스 tier</h3></div></div><div class="breakdown-title">추론 수준</div><div v-for="item in analytics.byReasoningEffort" :key="item.label" class="breakdown-row"><span>{{ textLabel(item.label) }}</span><div class="breakdown-track"><i :style="{ width: width(item.percentOfRequests, 100) }"></i></div><b>{{ integer(item.requests) }}</b><small>{{ item.percentOfRequests.toFixed(1) }}%</small></div><div class="breakdown-title">요청 tier → 실제 응답 tier</div><div v-for="item in analytics.byServiceTier" :key="item.label" class="breakdown-row"><span>{{ item.label }}</span><div class="breakdown-track"><i :style="{ width: width(item.percentOfRequests, 100) }"></i></div><b>{{ integer(item.requests) }}</b><small>{{ item.percentOfRequests.toFixed(1) }}%</small></div></article>
      <article class="breakdown-card issue-card"><div class="analytics-subheading"><div><span class="card-kicker">FAILURE ANALYSIS</span><h3>실패 원인</h3></div></div><div class="breakdown-title">최종 요청 오류 코드</div><div v-for="item in analytics.byFailureCode" :key="item.label" class="error-row"><span>{{ item.label }}</span><b>{{ integer(item.requests) }}건</b></div><div v-if="!analytics.byFailureCode.length" class="clear-note">최종 실패 오류 코드가 없습니다.</div><div class="breakdown-title attempt-title">모델별 재시도 실패</div><div v-for="item in analytics.byAttemptFailure" :key="`${item.serverName}-${item.modelName}-${item.errorType}`" class="error-row"><span><b>{{ item.errorType }}</b><small>{{ item.serverName }} · {{ item.modelName }}</small></span><b>{{ integer(item.count) }}회</b></div><div v-if="!analytics.byAttemptFailure.length" class="clear-note">Runtime Attempt 실패가 없습니다.</div></article>
    </div>

    <section v-if="analytics.playground" class="playground-analytics">
      <div class="analytics-subheading"><div><span class="card-kicker">PLAYGROUND · SEPARATE SOURCE</span><h3>모델 테스트 콘솔 통계</h3></div><small>Gateway API 사용량·비용에 합산하지 않음</small></div>
      <div class="playground-summary"><div><small>테스트 요청</small><b>{{ integer(analytics.playground.total.requestCount) }}</b></div><div><small>성공률</small><b>{{ analytics.playground.total.successRatePercent.toFixed(1) }}%</b><small>성공 {{ integer(analytics.playground.total.succeeded) }} · 실패 {{ integer(analytics.playground.total.failed) }}</small></div><div><small>응답 시간 평균 · 최소 · 최대</small><b>{{ range(analytics.playground.total.latencyMs, ' ms') }}</b></div><div><small>전체 토큰 평균 · 최소 · 최대</small><b>{{ range(analytics.playground.total.totalTokens) }}</b><small>토큰 미확인 {{ integer(analytics.playground.total.unknownTokenRequests) }}건</small></div><div><small>스트리밍 테스트</small><b>{{ integer(analytics.playground.total.streamingRequests) }}</b></div></div>
      <div class="playground-lists"><div><h4>테스트 대상 모델</h4><div v-for="item in analytics.playground.byModel.slice(0, 10)" :key="`${item.label}-${item.detail}`" class="playground-model"><b>{{ item.label }}</b><small>{{ item.detail }}</small><span>{{ integer(item.requestCount) }}회 · 성공률 {{ item.successRatePercent.toFixed(1) }}% · 평균 {{ average(item.latencyMs, ' ms') }}</span></div><p v-if="!analytics.playground.byModel.length" class="helper">테스트 기록이 없습니다.</p></div><div><h4>테스트 요청 유형 · 실패 코드</h4><div v-for="item in analytics.playground.byRequestType" :key="item.label" class="simple-row"><span>{{ textLabel(item.label) }}</span><b>{{ integer(item.requests) }}</b></div><div v-for="item in analytics.playground.byErrorCode" :key="item.label" class="simple-row error-text"><span>{{ item.label }}</span><b>{{ integer(item.requests) }}</b></div><p v-if="!analytics.playground.byErrorCode.length" class="clear-note">테스트 실패 코드가 없습니다.</p></div></div>
      <div class="playground-note">테스트 콘솔 기록은 연결 검증용 별도 트래픽입니다. 가격 단가를 이력으로 저장하지 않으므로 청구 예상 비용에 포함하지 않습니다. 모델 테스트 요청의 유형은 새 로그부터 집계되며, 이전 로그는 미분류로 표시됩니다.</div>
    </section>
    </div>
  </section>
</template>

<style scoped>
.runtime-analytics{--surface-soft:var(--surface-2);--analytics-gutter:22px}
.analytics-body{display:grid;gap:20px;min-width:0;padding:0 var(--analytics-gutter) var(--analytics-gutter)}
.analytics-body>.table-wrap,.analytics-body>.timeline-chart,.playground-lists>div{min-width:0}
.playground-lists .playground-model{grid-template-columns:minmax(0,1fr) auto}
.playground-model b,.playground-model small,.error-row>span,.simple-row>span{overflow-wrap:anywhere}
.simple-row>span{min-width:0}
.simple-row>b{flex-shrink:0}
.runtime-analytics{display:grid;gap:17px;min-width:0}.analytics-heading{align-items:flex-start}.analytics-heading p,.analytics-heading small,.analytics-note p,.helper,.analytics-subheading small{color:var(--muted);font-size:10px;line-height:1.55}.analytics-heading p{margin:5px 0 0}.analytics-note{padding:12px 14px;border:1px solid var(--border);border-radius:12px;background:var(--surface-soft);color:var(--muted);font-size:10px;line-height:1.65}.analytics-note b{display:block;color:var(--text);margin-bottom:4px}.analytics-note p{margin:4px 0}.analytics-subheading{display:flex;justify-content:space-between;align-items:end;gap:12px}.analytics-subheading h3{margin:3px 0 0;font-size:14px}.analytics-subheading small{text-align:right}.timeline-chart{display:flex;align-items:stretch;gap:7px;overflow-x:auto;min-height:130px;padding:12px 3px 4px;border-bottom:1px solid var(--border)}.timeline-item{display:grid;grid-template-rows:1fr auto auto;gap:4px;min-width:44px;text-align:center}.bar-track{display:flex;align-items:end;justify-content:center;height:76px;border-bottom:1px solid var(--border)}.bar-fill{width:22px;min-height:0;border-radius:5px 5px 0 0;background:linear-gradient(0deg,var(--accent-dim),var(--accent-strong))}.timeline-item strong{font-size:9px}.timeline-item small{color:var(--muted);font-size:8px;white-space:nowrap}.table-wrap{max-width:100%;overflow:auto;border:1px solid var(--border);border-radius:10px}.stats-table{width:100%;min-width:1120px;border-collapse:collapse;font-size:10px}.model-table{min-width:1040px}.stats-table th,.stats-table td{padding:10px 11px;border-bottom:1px solid var(--border);text-align:left;vertical-align:top;white-space:nowrap}.stats-table th{color:var(--muted);font-size:9px;font-weight:700;background:var(--surface-soft)}.stats-table td strong,.stats-table td small{display:block}.stats-table td small{max-width:260px;margin-top:4px;color:var(--muted);font-size:8px;white-space:normal;line-height:1.5}.breakdown-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.breakdown-card{min-width:0;padding:13px;border:1px solid var(--border);border-radius:12px;background:var(--surface-soft)}.breakdown-card .analytics-subheading{margin-bottom:8px}.breakdown-card .analytics-subheading h3{font-size:12px}.helper{margin:0 0 8px}.breakdown-title{margin:12px 0 5px;color:var(--muted);font-size:9px;font-weight:700}.breakdown-row{display:grid;grid-template-columns:minmax(90px,1.1fr) minmax(40px,1fr) 38px 42px;gap:7px;align-items:center;margin:8px 0;font-size:9px}.breakdown-row>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.breakdown-row>b,.breakdown-row>small{text-align:right}.breakdown-row>small{color:var(--muted);font-size:8px}.breakdown-track{height:6px;border-radius:9px;background:var(--surface);overflow:hidden}.breakdown-track i{display:block;height:100%;border-radius:inherit;background:var(--accent-strong)}.issue-card{border-color:color-mix(in srgb,var(--danger) 35%,var(--border))}.error-row,.simple-row{display:flex;justify-content:space-between;gap:10px;align-items:center;padding:7px 0;border-bottom:1px solid var(--border);font-size:9px}.error-row>span{min-width:0}.error-row small{display:block;margin-top:3px;color:var(--muted);font-size:8px}.error-row>b{white-space:nowrap}.attempt-title{margin-top:16px}.clear-note{padding:7px 0;color:var(--muted);font-size:9px}.playground-analytics{display:grid;gap:11px;padding-top:15px;border-top:1px solid var(--border)}.playground-summary{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px}.playground-summary>div{min-width:0;padding:10px;border:1px solid var(--border);border-radius:9px;background:var(--surface-soft)}.playground-summary small,.playground-summary b{display:block}.playground-summary small{color:var(--muted);font-size:8px;line-height:1.5}.playground-summary b{margin-top:4px;font-size:10px;overflow-wrap:anywhere}.playground-lists{display:grid;grid-template-columns:1.2fr 1fr;gap:16px}.playground-lists h4{margin:4px 0 8px;font-size:11px}.playground-model{display:grid;grid-template-columns:1fr auto;gap:3px 10px;padding:7px 0;border-bottom:1px solid var(--border);font-size:9px}.playground-model small{grid-column:1/-1;color:var(--muted);font-size:8px}.playground-model span{color:var(--muted);font-size:8px}.error-text{color:var(--danger)}.playground-note{padding:9px 11px;border-radius:9px;background:var(--surface-soft);color:var(--muted);font-size:9px;line-height:1.55}
@media(max-width:900px){.breakdown-grid{grid-template-columns:1fr}.playground-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.playground-lists{grid-template-columns:1fr}.analytics-subheading{align-items:flex-start;flex-direction:column}.analytics-subheading small{text-align:left}}
@media(max-width:620px){.runtime-analytics{--analytics-gutter:16px}.analytics-heading{padding-inline:var(--analytics-gutter);flex-wrap:wrap}.breakdown-row{grid-template-columns:minmax(0,1fr) minmax(24px,.7fr) 30px 36px;gap:5px}}
@media(max-width:480px){.playground-summary{grid-template-columns:1fr}}
</style>
