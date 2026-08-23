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

      <section class="request-detail-section">
        <header><span>REQUEST INFO</span><h4>요청 정보</h4></header>
        <div class="request-detail-grid">
          <div><small>Request ID</small><strong class="mono">{{ detail.requestId }}</strong></div>
          <div><small>Endpoint</small><strong class="mono">{{ detail.endpoint }}</strong></div>
          <div><small>Capability</small><strong>{{ detail.capabilities.join(' · ') }}</strong></div>
          <div><small>HTTP 상태</small><strong>{{ detail.httpStatus ?? '-' }}</strong></div>
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
            <span v-else>{{ attempt.responseStarted ? '응답 시작' : '응답 없음' }}</span>
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
.request-attempt-error { color: var(--danger); }
.request-detail-privacy { padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.request-detail-privacy strong { font-size: 10px; }
.request-detail-privacy p { margin: 5px 0 0; color: var(--muted); font-size: 9px; line-height: 1.6; }
.request-detail-error { margin: 0; color: var(--danger); font-size: 10px; }
@media (max-width: 720px) { .request-detail-grid, .request-detail-grid.four { grid-template-columns: repeat(2,minmax(0,1fr)); } .request-detail-route { grid-template-columns: 1fr; } .request-detail-route > i { justify-self: center; transform: rotate(90deg); } .request-attempt { grid-template-columns: 30px 1fr auto; } .request-attempt > span:nth-last-child(-n+2) { display: none; } }
</style>