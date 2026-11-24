<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import BaseModal from '../BaseModal.vue'
import { previewRequest } from './execute'
import type { FlowOperation, FlowPlan, OperationResult, OperationRisk, OperationStatus } from './types'

const props = defineProps<{
  open: boolean
  plan: FlowPlan | null
  phase: 'review' | 'applying' | 'done'
  results: OperationResult[]
  serviceLabel: string
  busy: boolean
}>()
const emit = defineEmits<{ close: []; apply: []; rebase: [] }>()

const confirmed = ref(false)

const RISK_LABEL: Record<OperationRisk, string> = { safe: '안전', caution: '주의', danger: '위험' }
const RISK_CLASS: Record<OperationRisk, string> = { safe: 'healthy', caution: 'suspect', danger: 'unhealthy' }
const STATUS_ICON: Record<OperationStatus, { icon: string; label: string }> = {
  pending: { icon: '○', label: '대기' },
  running: { icon: '◐', label: '진행 중' },
  succeeded: { icon: '✓', label: '성공' },
  'already-applied': { icon: '≡', label: '이미 적용됨' },
  failed: { icon: '✕', label: '실패' },
  skipped: { icon: '–', label: '건너뜀' }
}

const operations = computed<FlowOperation[]>(() => props.plan?.operations ?? [])
const dangerCount = computed(() => operations.value.filter(op => op.risk === 'danger').length)
const cautionCount = computed(() => operations.value.filter(op => op.risk === 'caution').length)
const conflicts = computed(() => props.plan?.conflicts ?? [])
const blocking = computed(() => props.plan?.blockingIssues ?? [])
const resultById = computed(() => new Map(props.results.map(item => [item.opId, item])))
const showStatus = computed(() => props.phase !== 'review' && props.results.length > 0)
const failed = computed(() => props.results.find(item => item.status === 'failed') ?? null)
const succeededCount = computed(() => props.results.filter(item => item.status === 'succeeded' || item.status === 'already-applied').length)

const canApply = computed(() =>
  props.phase === 'review' && !props.busy && props.plan !== null && operations.value.length > 0 &&
  conflicts.value.length === 0 && blocking.value.length === 0 && (dangerCount.value === 0 || confirmed.value))

watch(() => [props.open, props.plan?.fingerprint], () => { confirmed.value = false })

function requestLine(op: FlowOperation): string {
  const request = previewRequest(op)
  return `${request.method} ${request.path}`
}
function statusOf(op: FlowOperation) { return STATUS_ICON[resultById.value.get(op.opId)?.status ?? 'pending'] }
function resultOf(op: FlowOperation) { return resultById.value.get(op.opId) ?? null }
function requestClose() {
  if (props.phase === 'applying') return
  emit('close')
}
</script>

<template>
  <BaseModal :open="open" title="변경 검토" :description="serviceLabel" size="lg" @close="requestClose">
    <div class="fd-plan">
      <p v-if="!plan" class="inline-alert" role="status">서버의 최신 상태를 확인하고 변경 목록을 계산하고 있습니다…</p>
      <template v-else>
        <section class="fd-plan-summary" aria-label="요약">
          <span class="status-chip">작업 {{ operations.length }}건</span>
          <span v-if="dangerCount" class="status-chip unhealthy">위험 {{ dangerCount }}건</span>
          <span v-if="cautionCount" class="status-chip suspect">주의 {{ cautionCount }}건</span>
          <span v-if="plan.remoteDrift" class="status-chip standby">서버 구성이 변경됨</span>
        </section>

        <section v-if="conflicts.length" class="fd-plan-block fd-danger" aria-label="충돌">
          <header>
            <strong>충돌 {{ conflicts.length }}건</strong>
            <button class="secondary-button" type="button" :disabled="busy || phase !== 'review'" @click="emit('rebase')">최신 서버 상태와 병합</button>
          </header>
          <p class="fd-hint">충돌이 해결되기 전에는 적용할 수 없습니다. 병합하면 충돌한 항목은 서버 값으로 바뀝니다.</p>
          <ul>
            <li v-for="(conflict, index) in conflicts" :key="`${conflict.code}-${conflict.key}-${conflict.field ?? ''}-${index}`">
              <span>{{ conflict.message }}</span>
              <small>기준 {{ conflict.base }} · 내 변경 {{ conflict.local }} · 서버 {{ conflict.remote }}</small>
            </li>
          </ul>
        </section>

        <section v-if="blocking.length" class="fd-plan-block fd-danger" aria-label="차단 이슈">
          <header><strong>적용을 막는 문제 {{ blocking.length }}건</strong></header>
          <ul>
            <li v-for="(issue, index) in blocking" :key="`${issue.code}-${issue.nodeId ?? ''}-${issue.field ?? ''}-${index}`">{{ issue.message }}</li>
          </ul>
        </section>

        <section class="fd-plan-block" aria-label="작업 목록">
          <header><strong>작업 순서</strong></header>
          <p v-if="!operations.length" class="fd-hint">적용할 변경이 없습니다.</p>
          <ol class="fd-ops">
            <li v-for="(op, index) in operations" :key="op.opId" :class="['fd-op', `fd-op-${op.risk}`]">
              <div class="fd-op-head">
                <span class="fd-op-no">{{ index + 1 }}</span>
                <span :class="['status-chip', 'tiny', RISK_CLASS[op.risk]]">{{ RISK_LABEL[op.risk] }}</span>
                <strong>{{ op.summary }}</strong>
                <span v-if="showStatus" :class="['fd-op-status', `fd-status-${resultOf(op)?.status ?? 'pending'}`]" role="status">
                  <i aria-hidden="true">{{ statusOf(op).icon }}</i>{{ statusOf(op).label }}
                </span>
              </div>
              <table v-if="op.changes.length" class="fd-changes">
                <tbody>
                  <tr v-for="change in op.changes" :key="change.field">
                    <th scope="row">{{ change.label }}</th>
                    <td>{{ change.before }}</td>
                    <td aria-hidden="true">→</td>
                    <td>{{ change.after }}</td>
                  </tr>
                </tbody>
              </table>
              <code class="fd-request">{{ requestLine(op) }}</code>
              <p v-if="resultOf(op)?.message && resultOf(op)?.status === 'failed'" class="fd-op-error">
                {{ resultOf(op)?.message }}<template v-if="resultOf(op)?.code"> ({{ resultOf(op)?.code }})</template><template v-if="resultOf(op)?.httpStatus"> · HTTP {{ resultOf(op)?.httpStatus }}</template>
              </p>
            </li>
          </ol>
        </section>

        <section v-if="plan.notes.length" class="fd-plan-block" aria-label="안내">
          <header><strong>안내</strong></header>
          <ul>
            <li v-for="(note, index) in plan.notes" :key="index" :class="['fd-note', `fd-note-${note.level}`]">{{ note.message }}</li>
          </ul>
        </section>

        <label v-if="dangerCount && phase === 'review'" class="toggle-field fd-confirm">
          <span>위험 작업 {{ dangerCount }}건을 확인했습니다<small>서비스 중단, 외부 노출 방지 정책 완화, 삭제 또는 접근 해제가 포함되어 있습니다.</small></span>
          <input v-model="confirmed" type="checkbox" :disabled="busy" />
        </label>

        <section v-if="phase === 'done'" class="fd-plan-block" :class="failed ? 'fd-danger' : ''" aria-label="결과" role="status">
          <strong v-if="!failed">{{ succeededCount }}개 작업을 적용했습니다.</strong>
          <template v-else>
            <strong>작업 {{ succeededCount }}건 적용 후 실패하여 중단했습니다.</strong>
            <p class="fd-hint">남은 변경은 워크플로우에 그대로 유지됩니다. 서버 상태를 다시 불러와 반영했습니다.</p>
          </template>
        </section>
      </template>
    </div>
    <template #footer>
      <button class="secondary-button" type="button" :disabled="phase === 'applying'" @click="requestClose">{{ phase === 'done' ? '닫기' : '취소' }}</button>
      <button v-if="phase !== 'done'" class="primary-button" type="button" :disabled="!canApply" @click="emit('apply')">
        {{ phase === 'applying' ? '적용 중…' : `적용 (${operations.length}개 작업)` }}
      </button>
    </template>
  </BaseModal>
</template>

<style scoped>
.fd-plan { display: grid; gap: 14px; }
.fd-plan-summary { display: flex; flex-wrap: wrap; gap: 8px; }
.fd-plan-block { display: grid; gap: 8px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }
.fd-plan-block.fd-danger { border-color: color-mix(in srgb, var(--danger) 40%, transparent); background: var(--danger-dim); }
.fd-plan-block header { display: flex; justify-content: space-between; align-items: center; gap: 12px; color: var(--text); font-size: 12px; }
.fd-plan-block ul, .fd-plan-block ol { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; }
.fd-plan-block li { display: grid; gap: 3px; color: var(--text-soft); font-size: 11px; line-height: 1.5; }
.fd-plan-block li small { color: var(--muted); font-size: 10px; }
.fd-hint { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.5; }
.fd-ops { counter-reset: none; }
.fd-op { padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
.fd-op-danger { border-color: color-mix(in srgb, var(--danger) 40%, transparent); }
.fd-op-caution { border-color: color-mix(in srgb, var(--warning) 40%, transparent); }
.fd-op-head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.fd-op-head strong { font-size: 12px; color: var(--text); }
.fd-op-no { min-width: 20px; height: 20px; display: inline-grid; place-items: center; border-radius: 50%; background: var(--surface-3); color: var(--muted); font-size: 10px; font-weight: 800; }
.fd-op-status { margin-left: auto; display: inline-flex; align-items: center; gap: 5px; font-size: 10px; font-weight: 700; color: var(--muted); }
.fd-op-status i { font-style: normal; }
.fd-status-running { color: var(--info); }
.fd-status-succeeded { color: var(--accent-strong); }
.fd-status-already-applied { color: var(--info); }
.fd-status-failed { color: var(--danger); }
.fd-changes { width: 100%; border-collapse: collapse; font-size: 10px; }
.fd-changes th { width: 30%; padding: 3px 8px 3px 0; text-align: left; font-weight: 600; color: var(--muted); }
.fd-changes td { padding: 3px 6px; color: var(--text-soft); word-break: break-word; }
.fd-request { display: block; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 9px; color: var(--muted); word-break: break-all; }
.fd-op-error { margin: 0; color: var(--danger); font-size: 11px; }
.fd-note-warning { color: var(--warning); }
.fd-note-info { color: var(--text-soft); }
.fd-confirm { margin-top: 2px; }
</style>
