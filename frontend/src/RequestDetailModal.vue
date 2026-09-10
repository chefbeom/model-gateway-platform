<script setup lang="ts">
import BaseModal from './BaseModal.vue'
import {
  formatRequestCost,
  formatRequestDate,
  formatRequestDuration,
  requestTypeLabel,
  type RequestDetail
} from './requestDetail'

withDefaults(defineProps<{
  open: boolean
  detail: RequestDetail | null
  loading?: boolean
}>(), { loading: false })

const emit = defineEmits<{ close: [] }>()

function reasonLabel(code: string) {
  const labels: Record<string, string> = {
    CAPABILITY_MISSING: '필수 기능 미지원', ENDPOINT_UNHEALTHY: 'Endpoint 비정상', ENDPOINT_MISSING: 'Endpoint 없음', ENDPOINT_DISABLED: 'Endpoint 비활성',
    DEPLOYMENT_UNHEALTHY: '배포 비정상', DEPLOYMENT_NOT_LOADED: '모델 미로드', DEPLOYMENT_MISSING: '배포 없음', DEPLOYMENT_DISABLED: '배포 비활성', TARGET_MODEL_STALE: 'Target 모델 변경 필요',
    TARGET_DISABLED: 'Target 비활성', DEGRADED_NOT_ALLOWED: 'Degraded 제외', COMPATIBILITY_MISMATCH: '호환성 불일치', CONCURRENCY_LIMIT_REACHED: '동시성 한도',
    EXTERNAL_AUTO_FAILOVER_NOT_ALLOWED: '자동 Failover 미승인', EXTERNAL_MANUAL_ACCESS_NOT_ALLOWED: '수동 사용 미승인', EXTERNAL_PROVIDER_UNHEALTHY: 'Provider 비정상',
    EXTERNAL_PROVIDER_DISABLED: 'Provider 비활성', EXTERNAL_PROVIDER_MISSING: 'Provider 없음', EXTERNAL_ACCESS_UNAVAILABLE: '외부 권한 확인 불가', EXTERNAL_PROJECT_REQUIRED: '프로젝트 정보 없음',
    UPSTREAM_REJECTED: 'Provider 요청 거부', RUNTIME_UNAVAILABLE: 'Runtime 응답 없음', STREAM_START_FAILED: '스트림 시작 실패', MODEL_AT_CAPACITY: '모델 처리 한도', DATA_POLICY_BLOCKED: '데이터 보호 정책 차단', DATA_PROTECTION_EXTERNAL_BLOCKED: '외부 전송 차단', DATA_PROTECTION_EXTERNAL_FAILOVER_BLOCKED: '외부 Failover 차단',
    CONTEXT_LENGTH_EXCEEDED: '컨텍스트 한도 초과', INPUT_TOKEN_LIMIT_EXCEEDED: '입력 토큰 한도 초과', OUTPUT_TOKEN_LIMIT_EXCEEDED: '출력 토큰 한도 초과', REQUEST_FORMAT_UNSUPPORTED: '요청 형식 미지원', AUTHENTICATION_FAILED: 'Provider 인증 실패', MODEL_NOT_FOUND: 'Provider 모델 없음', RATE_LIMITED: 'Provider 사용량 한도', REQUEST_TIMEOUT: 'Provider 응답 시간 초과', UPSTREAM_UNAVAILABLE: 'Provider 일시 장애'
  }
  return labels[code] ?? code
}
function targetState(target: NonNullable<RequestDetail['diagnostic']>['targets'][number]) {
  if (target.eligible) return '사용 가능'
  return target.reasonCodes.map(reasonLabel).join(' · ') || '제외 사유 없음'
}
</script>

<template>
  <BaseModal
    :open="open"
    title="요청 상세"
    :description="detail?.requestId ?? '안전한 메타데이터를 불러오는 중입니다.'"
    size="lg"
    @close="emit('close')"
  >
    <div v-if="loading" class="request-detail-loading" role="status">요청 메타데이터를 불러오는 중입니다…</div>

    <div v-else-if="detail" class="request-detail-content">
      <header class="request-detail-summary">
        <div>
          <span class="request-detail-kicker">REQUEST METADATA</span>
          <h3>{{ requestTypeLabel(detail.requestType) }}</h3>
          <p>{{ formatRequestDate(detail.startedAt) }} · {{ detail.stream ? 'SSE 스트리밍' : 'JSON 응답' }}</p>
        </div>
        <span class="request-detail-status" :class="detail.status === 'SUCCEEDED' ? 'success' : detail.status === 'IN_PROGRESS' ? 'pending' : 'failure'">
          {{ detail.status }}
        </span>
      </header>

      <section v-if="detail.status === 'FAILED'" class="request-detail-section diagnostic-section">
        <header><span>FAILURE DIAGNOSIS</span><h4>실패 원인과 권장 조치</h4></header>
        <div v-if="detail.diagnostic" class="diagnostic-panel">
          <div class="diagnostic-summary">
            <strong>{{ detail.diagnostic.summary }}</strong>
            <span>최종 코드 {{ detail.diagnostic.finalCode }} · HTTP {{ detail.diagnostic.httpStatus ?? detail.httpStatus ?? '-' }} · 시도 {{ detail.diagnostic.attemptedCount }}회</span>
          </div>
          <div v-if="detail.diagnostic.failure?.message || detail.diagnostic.failure?.providerMessage" class="diagnostic-failure">
            <strong>실패 원인: {{ reasonLabel(detail.diagnostic.failure?.code ?? detail.diagnostic.finalCode) }}</strong>
            <p v-if="detail.diagnostic.failure?.message">{{ detail.diagnostic.failure.message }}</p>
            <small v-if="detail.diagnostic.failure?.providerMessage">Provider 원문 요약: {{ detail.diagnostic.failure.providerMessage }}</small>
            <small>다음 Target 전환: {{ detail.diagnostic.failure?.failoverAllowed ? '허용됨' : '정책상 허용되지 않음' }}</small>
          </div>
          <div class="diagnostic-profile-token"><small>예상 입력 토큰</small><strong>{{ (detail.diagnostic.request.estimatedInputTokens ?? 0).toLocaleString('ko-KR') }}</strong></div>
          <div class="diagnostic-profile-token"><small>요청 출력 한도</small><strong>{{ (detail.diagnostic.request.requestedOutputTokens ?? 0).toLocaleString('ko-KR') }}</strong></div>
          <div class="diagnostic-profile">
            <div><small>요청 모델</small><strong class="mono">{{ detail.diagnostic.request.logicalModel ?? detail.serviceKey ?? '-' }}</strong></div>
            <div><small>요청 기능</small><strong>{{ detail.diagnostic.request.capabilities.join(' · ') || 'TEXT' }}</strong></div>
            <div><small>응답 형식</small><strong>{{ detail.diagnostic.request.responseFormatType ?? '일반 텍스트' }}</strong></div>
            <div><small>토큰 필드</small><strong>{{ detail.diagnostic.request.hasMaxCompletionTokens ? 'max_completion_tokens' : detail.diagnostic.request.hasMaxTokens ? 'max_tokens' : '미지정' }}</strong></div>
            <div><small>Failover / Retry</small><strong>{{ detail.diagnostic.service.failoverPolicy }} / {{ detail.diagnostic.service.retryPolicy }}</strong></div>
            <div><small>메시지 · 도구</small><strong>{{ detail.diagnostic.request.messageCount }}개 · {{ detail.diagnostic.request.toolCount }}개</strong></div>
          </div>
          <div v-if="detail.diagnostic.targets.length" class="diagnostic-targets">
            <div v-for="target in detail.diagnostic.targets" :key="target.targetId" class="diagnostic-target" :class="{ eligible: target.eligible }">
              <div class="diagnostic-target-heading"><strong>{{ target.displayName }}</strong><span>{{ target.providerDisplayName ?? target.providerType }} · P{{ target.priority }}</span></div>
              <div class="diagnostic-target-meta"><span>{{ target.endpointDisplayName ?? '외부 Provider' }}</span><span>{{ target.deploymentHealth ?? '상태 미상' }}</span><span v-if="target.activeRequests != null">동시 {{ target.activeRequests }}/{{ target.maxConcurrency }}</span></div>
              <p :class="target.eligible ? 'diagnostic-ok' : 'diagnostic-reason'">{{ targetState(target) }}</p>
              <small v-if="target.missingCapabilities.length" class="diagnostic-missing">누락 기능: {{ target.missingCapabilities.join(' · ') }}</small>
            </div>
          </div>
          <div v-if="detail.diagnostic.recommendations.length" class="diagnostic-recommendations">
            <div v-for="recommendation in detail.diagnostic.recommendations" :key="recommendation.code" class="diagnostic-recommendation">
              <strong>{{ recommendation.title }}</strong><p>{{ recommendation.detail }}</p>
            </div>
          </div>
        </div>
        <div v-else class="diagnostic-empty">이 요청에는 진단 스냅샷이 없습니다. 마이그레이션 이전 요청이거나 저장에 실패했을 수 있습니다.</div>
      </section>

      <section class="request-detail-section">
        <header><span>REQUEST INFO</span><h4>요청 정보</h4></header>
        <div class="request-detail-grid">
          <div><small>Request ID</small><strong class="mono">{{ detail.requestId }}</strong></div>
          <div><small>Endpoint</small><strong class="mono">{{ detail.endpoint }}</strong></div>
          <div><small>Capability</small><strong>{{ detail.capabilities.join(' · ') }}</strong></div>
          <div><small>HTTP 상태</small><strong>{{ detail.httpStatus ?? '-' }}</strong></div>
          <div v-if="detail.dataProtectionMode"><small>데이터 보호</small><strong>{{ detail.dataProtectionMode }} · {{ detail.dataProtectionLevel }} · {{ detail.dataProtectionAction }}</strong><em>{{ detail.dataClassificationsJson || '감지 없음' }}</em></div>
          <div v-if="detail.dataExternalAllowed != null"><small>외부 전송</small><strong>{{ detail.dataExternalAllowed ? '허용' : '차단' }}</strong></div>
        </div>
      </section>

      <section class="request-detail-section">
        <header><span>USAGE & COST</span><h4>토큰·비용</h4></header>
        <div class="request-detail-grid four">
          <div><small>입력 토큰</small><strong>{{ (detail.inputTokens ?? 0).toLocaleString('ko-KR') }}</strong><em>Prompt tokens</em></div>
          <div><small>출력 토큰</small><strong>{{ (detail.outputTokens ?? 0).toLocaleString('ko-KR') }}</strong><em>Completion tokens</em></div>
          <div><small>총 비용</small><strong>{{ formatRequestCost(detail.estimatedCost, detail.costCurrency) }}</strong><em>{{ detail.costCurrency ?? '통화 없음' }}</em></div>
          <div><small>처리 시간</small><strong>{{ formatRequestDuration(detail.latencyMs) }}</strong><em>Failover {{ detail.failoverCount }}회</em></div>
        </div>
        <div class="request-detail-prices">
          <span>입력 단가 {{ formatRequestCost(detail.inputUnitPrice, detail.costCurrency) }} / 1M</span>
          <span>출력 단가 {{ formatRequestCost(detail.outputUnitPrice, detail.costCurrency) }} / 1M</span>
        </div>
      </section>

      <section class="request-detail-section">
        <header><span>ROUTING</span><h4>라우팅 정보</h4></header>
        <div class="request-detail-route">
          <div><small>논리 서비스</small><strong>{{ detail.serviceDisplayName ?? detail.serviceKey ?? '-' }}</strong><em>{{ detail.serviceKey ?? '-' }}</em></div>
          <i>→</i>
          <div><small>실제 배포</small><strong>{{ detail.deploymentDisplayName ?? '-' }}</strong><em>{{ detail.finalDeploymentId ?? '-' }}</em></div>
          <i>→</i>
          <div><small>Provider</small><strong>{{ detail.providerType ?? 'LOCAL' }}</strong><em>{{ detail.routingReason ?? 'PRIMARY' }}</em></div>
        </div>
      </section>

      <section v-if="detail.attempts.length" class="request-detail-section">
        <header><span>ATTEMPTS</span><h4>실행 시도 이력</h4></header>
        <div class="request-attempts">
          <div v-for="attempt in detail.attempts" :key="attempt.attemptNumber" class="request-attempt">
            <span class="request-attempt-number">#{{ attempt.attemptNumber }}</span>
            <div>
              <strong>{{ attempt.status }}</strong>
              <small class="mono">{{ attempt.deploymentId }}</small>
            </div>
            <span>{{ formatRequestDuration(attempt.latencyMs) }}</span>
            <span>HTTP {{ attempt.httpStatus ?? '-' }}</span>
            <span v-if="attempt.errorType" class="request-attempt-error">{{ attempt.errorType }}</span>
            <span v-if="attempt.errorMessage" class="request-attempt-message">{{ attempt.errorMessage }}</span>
            <span v-if="!attempt.errorType && !attempt.errorMessage">{{ attempt.responseStarted ? '응답 시작' : '응답 없음' }}</span>
          </div>
        </div>
      </section>

      <aside class="request-detail-privacy">
        <strong>원문 비표시</strong>
        <p>요청 프롬프트와 모델 응답 원문은 개인정보·비밀정보 보호를 위해 이 화면과 상세 API 응답에 포함하지 않습니다. 이 화면에서는 토큰·비용·상태·라우팅 같은 메타데이터만 확인할 수 있습니다.</p>
      </aside>

      <p v-if="detail.errorCode" class="request-detail-error">오류 코드: {{ detail.errorCode }}</p>
    </div>
  </BaseModal>
</template>

<style scoped>
.request-detail-loading { min-height: 180px; display: grid; place-items: center; color: var(--muted); font-size: 12px; }
.request-detail-content { display: grid; gap: 17px; }
.request-detail-summary { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; padding: 15px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); }
.request-detail-kicker, .request-detail-section > header > span { color: var(--accent-strong); font-size: 8px; font-weight: 900; letter-spacing: .12em; }
.request-detail-summary h3 { margin: 7px 0 4px; font-size: 17px; }
.request-detail-summary p { margin: 0; color: var(--muted); font-size: 10px; }
.request-detail-status { padding: 6px 8px; border: 1px solid var(--border); border-radius: 7px; font-size: 9px; font-weight: 900; }
.request-detail-status.success { color: var(--accent-strong); border-color: var(--accent-border); }
.request-detail-status.pending { color: var(--muted); }
.request-detail-status.failure { color: var(--danger); border-color: color-mix(in srgb,var(--danger) 45%,var(--border)); }
.request-detail-section { display: grid; gap: 11px; }
.request-detail-section > header { display: grid; gap: 4px; }
.request-detail-section > header h4 { margin: 0; font-size: 13px; }
.request-detail-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 9px; }
.request-detail-grid > div { min-width: 0; display: grid; gap: 5px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.request-detail-grid small, .request-detail-grid em { color: var(--muted); font-size: 9px; font-style: normal; }
.request-detail-grid strong { overflow: hidden; color: var(--text); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.request-detail-prices { display: flex; flex-wrap: wrap; gap: 8px; color: var(--muted); font-size: 9px; }
.request-detail-prices span { padding: 7px 9px; border: 1px solid var(--border); border-radius: 7px; }
.request-detail-route { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr; gap: 9px; align-items: center; }
.request-detail-route > div { min-width: 0; display: grid; gap: 4px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.request-detail-route small, .request-detail-route em { color: var(--muted); font-size: 9px; font-style: normal; }
.request-detail-route strong, .request-detail-route em { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.request-detail-route strong { font-size: 10px; }
.request-detail-route i { color: var(--accent-strong); font-style: normal; }
.request-attempts { display: grid; gap: 7px; }
.request-attempt { display: grid; grid-template-columns: 36px minmax(130px,1fr) auto auto minmax(100px,auto); gap: 9px; align-items: center; padding: 10px; border: 1px solid var(--border); border-radius: 9px; background: var(--surface); color: var(--muted); font-size: 9px; }
.request-attempt-number { color: var(--accent-strong); font-weight: 900; }
.request-attempt strong, .request-attempt small { display: block; }
.request-attempt strong { color: var(--text); font-size: 10px; }
.request-attempt small { margin-top: 3px; color: var(--faint); }
.request-attempt-error, .request-attempt-message { color: var(--danger); overflow-wrap: anywhere; }
.request-detail-privacy { padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.request-detail-privacy strong { font-size: 10px; }
.request-detail-privacy p { margin: 5px 0 0; color: var(--muted); font-size: 9px; line-height: 1.6; }
.request-detail-error { margin: 0; color: var(--danger); font-size: 10px; }
.diagnostic-failure { display: grid; gap: 5px; padding: 10px; border: 1px solid color-mix(in srgb,var(--danger) 45%,var(--border)); border-radius: 9px; background: color-mix(in srgb,var(--danger) 10%,var(--surface-2)); }
.diagnostic-failure strong { color: var(--danger); font-size: 10px; }
.diagnostic-failure p, .diagnostic-failure small { margin: 0; color: var(--muted); font-size: 9px; line-height: 1.5; overflow-wrap: anywhere; }
.diagnostic-profile-token { display: grid; gap: 4px; padding: 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-2); }
.diagnostic-panel { display: grid; gap: 10px; padding: 12px; border: 1px solid color-mix(in srgb,var(--danger) 30%,var(--border)); border-radius: 11px; background: color-mix(in srgb,var(--danger) 5%,var(--surface)); }
.diagnostic-summary { display: grid; gap: 4px; }
.diagnostic-summary strong { color: var(--text); font-size: 11px; line-height: 1.5; }
.diagnostic-summary span, .diagnostic-target-meta, .diagnostic-target-heading span { color: var(--muted); font-size: 9px; }
.diagnostic-profile { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 7px; }
.diagnostic-profile > div { min-width: 0; display: grid; gap: 4px; padding: 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-2); }
.diagnostic-profile small { color: var(--muted); font-size: 8px; }
.diagnostic-profile strong { overflow: hidden; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.diagnostic-targets { display: grid; gap: 7px; }
.diagnostic-target { display: grid; gap: 5px; padding: 9px; border: 1px solid var(--border); border-radius: 9px; background: var(--surface-2); }
.diagnostic-target.eligible { border-color: var(--accent-border); }
.diagnostic-target-heading, .diagnostic-target-meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 7px; }
.diagnostic-target-heading strong { color: var(--text); font-size: 10px; }
.diagnostic-target-meta { justify-content: flex-start; }
.diagnostic-target p { margin: 0; font-size: 9px; line-height: 1.5; }
.diagnostic-reason { color: var(--danger); }
.diagnostic-ok { color: var(--accent-strong); }
.diagnostic-missing { color: var(--danger); font-size: 8px; }
.diagnostic-recommendations { display: grid; gap: 6px; }
.diagnostic-recommendation { padding: 9px; border-left: 2px solid var(--accent-strong); background: var(--surface-2); }
.diagnostic-recommendation strong { font-size: 9px; }
.diagnostic-recommendation p { margin: 3px 0 0; color: var(--muted); font-size: 9px; line-height: 1.5; }
.diagnostic-empty { padding: 12px; border: 1px dashed var(--border); border-radius: 9px; color: var(--muted); font-size: 10px; }
@media (max-width: 720px) { .request-detail-grid, .request-detail-grid.four { grid-template-columns: repeat(2,minmax(0,1fr)); } .request-detail-route { grid-template-columns: 1fr; } .request-detail-route > i { justify-self: center; transform: rotate(90deg); } .request-attempt { grid-template-columns: 30px 1fr auto; } .request-attempt > span:nth-last-child(-n+2) { display: none; } }
</style>
