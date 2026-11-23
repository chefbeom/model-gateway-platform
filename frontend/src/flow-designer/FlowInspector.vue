<script setup lang="ts">
import { computed, nextTick } from 'vue'
import CapabilityPicker from '../CapabilityPicker.vue'
import { describeEdge, describeNode, nodeFacts, nodeSpec } from './catalog'
import type {
  FieldSpec,
  FlowCommand,
  FlowEdge,
  FlowGraph,
  FlowNode,
  FlowPropValue,
  FlowSelection,
  ServiceFlowSnapshot,
  ValidationIssue,
} from './types'

const props = defineProps<{
  graph: FlowGraph
  snapshot: ServiceFlowSnapshot
  selection: FlowSelection
  issues: ValidationIssue[]
  readOnly: boolean
}>()

const emit = defineEmits<{
  command: [FlowCommand]
  'update:selection': [FlowSelection]
  'focus-node': [string]
}>()

const SEVERITY_LABEL = { error: '오류', warning: '경고', info: '정보' } as const

/* ------------------------------ selection ------------------------------ */
const totalSelected = computed(() => props.selection.nodeIds.length + props.selection.edgeIds.length)
const node = computed<FlowNode | null>(() => {
  if (props.selection.nodeIds.length !== 1 || props.selection.edgeIds.length !== 0) return null
  return props.graph.nodes.find((n) => n.id === props.selection.nodeIds[0]) ?? null
})
const edge = computed<FlowEdge | null>(() => {
  if (props.selection.edgeIds.length !== 1 || props.selection.nodeIds.length !== 0) return null
  return props.graph.edges.find((e) => e.id === props.selection.edgeIds[0]) ?? null
})
const mode = computed<'issues' | 'node' | 'edge' | 'multi'>(() => {
  if (totalSelected.value === 0) return 'issues'
  if (node.value) return 'node'
  if (edge.value) return 'edge'
  return 'multi'
})

function nodeTitle(id: string): string {
  const n = props.graph.nodes.find((item) => item.id === id)
  if (!n) return id
  const view = describeNode(n, props.graph, props.snapshot)
  return `${view.typeLabel} · ${view.title}`
}

/* ------------------------------ issue list (no selection) ------------------------------ */
const groupedIssues = computed(() => {
  const order: Array<'error' | 'warning' | 'info'> = ['error', 'warning', 'info']
  return order
    .map((severity) => ({ severity, items: props.issues.filter((i) => i.severity === severity) }))
    .filter((g) => g.items.length > 0)
})

function issueLabel(issue: ValidationIssue): string {
  return issue.nodeId ? `${nodeTitle(issue.nodeId)}: ${issue.message}` : issue.message
}

function pickIssue(issue: ValidationIssue) {
  if (issue.nodeId && props.graph.nodes.some((n) => n.id === issue.nodeId)) {
    emit('update:selection', { nodeIds: [issue.nodeId], edgeIds: [] })
    emit('focus-node', issue.nodeId)
  } else if (issue.edgeId && props.graph.edges.some((e) => e.id === issue.edgeId)) {
    emit('update:selection', { nodeIds: [], edgeIds: [issue.edgeId] })
  }
}

function issueClickable(issue: ValidationIssue): boolean {
  return !!(issue.nodeId || issue.edgeId)
}

/* ------------------------------ node details ------------------------------ */
const view = computed(() => (node.value ? describeNode(node.value, props.graph, props.snapshot) : null))
const spec = computed(() => (node.value ? nodeSpec(node.value.type) : null))
const facts = computed(() => (node.value ? nodeFacts(node.value, props.graph, props.snapshot) : []))
const nodeIssues = computed(() => (node.value ? props.issues.filter((i) => i.nodeId === node.value!.id) : []))

function isVisible(field: FieldSpec): boolean {
  if (!field.visibleWhen || !node.value) return true
  return field.visibleWhen.oneOf.includes((node.value.props[field.visibleWhen.key] ?? null))
}

const fields = computed(() => (spec.value ? spec.value.fields.filter(isVisible) : []))

function fieldKeys(field: FieldSpec): string[] {
  if (field.key === '$binding') return ['$binding', 'deploymentId']
  if (field.key === '$access') return ['$access', 'access']
  return [field.key]
}

function fieldIssues(field: FieldSpec): ValidationIssue[] {
  const keys = fieldKeys(field)
  return nodeIssues.value.filter((i) => i.field !== null && keys.includes(i.field))
}

const generalIssues = computed(() => {
  const covered = new Set(fields.value.flatMap(fieldKeys))
  return nodeIssues.value.filter((i) => i.field === null || !covered.has(i.field))
})

function fieldId(field: FieldSpec): string {
  return `fd-field-${node.value?.id ?? 'x'}-${field.key.replace('$', '')}`
}

function propValue(key: string): FlowPropValue {
  return node.value ? (node.value.props[key] ?? null) : null
}
function textValue(key: string): string {
  const v = propValue(key)
  return v === null || v === undefined ? '' : String(v)
}
function boolValue(key: string): boolean {
  return propValue(key) === true
}

function fieldDisabled(field: FieldSpec): boolean {
  if (props.readOnly) return true
  if (field.newServiceOnly && props.graph.serviceId !== null) return true
  return false
}

function commit(key: string, value: FlowPropValue) {
  if (!node.value || props.readOnly) return
  emit('command', { type: 'updateProps', nodeId: node.value.id, patch: { [key]: value } })
}

/** After emitting, restore the control to the prop value in case the page rejected the edit. */
function resync(el: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, key: string, checkbox = false) {
  void nextTick(() => {
    if (checkbox && el instanceof HTMLInputElement) el.checked = boolValue(key)
    else el.value = textValue(key)
  })
}

function onText(event: Event, field: FieldSpec) {
  const el = event.target as HTMLInputElement | HTMLTextAreaElement
  commit(field.key, el.value)
  resync(el, field.key)
}

function onNumber(event: Event, field: FieldSpec) {
  const el = event.target as HTMLInputElement
  const raw = el.value.trim()
  if (raw === '') {
    if (field.nullable) commit(field.key, null)
    resync(el, field.key)
    return
  }
  const n = Number(raw)
  if (!Number.isFinite(n)) { resync(el, field.key); return }
  commit(field.key, field.kind === 'integer' ? Math.trunc(n) : n)
  resync(el, field.key)
}

function onBoolean(event: Event, field: FieldSpec) {
  const el = event.target as HTMLInputElement
  commit(field.key, el.checked)
  resync(el, field.key, true)
}

function onSelect(event: Event, field: FieldSpec) {
  const el = event.target as HTMLSelectElement
  commit(field.key, el.value)
  resync(el, field.key)
}

function onCapabilities(field: FieldSpec, value: string) {
  commit(field.key, value)
}

/* masker */
const currentMasker = computed(() => {
  const id = textValue('deploymentId')
  return id ? props.snapshot.catalog.maskers.find((m) => m.deploymentId === id) ?? null : null
})

function onMasker(event: Event) {
  const el = event.target as HTMLSelectElement
  commit('deploymentId', el.value === '' ? null : el.value)
  resync(el, 'deploymentId')
}

const maskerConnected = computed(() => !!node.value && node.value.type === 'masker' && props.graph.edges.some((e) => e.from.nodeId === node.value!.id))
const guardrailExists = computed(() => props.graph.nodes.some((n) => n.id === 'guardrail'))

function connectMasker() {
  if (!node.value || props.readOnly) return
  emit('command', { type: 'connect', from: { nodeId: node.value.id, portId: 'masker' }, to: { nodeId: 'guardrail', portId: 'masker' } })
}

/* target binding */
const boundDeploymentId = computed(() => {
  if (!node.value || node.value.type !== 'target') return ''
  const bind = props.graph.edges.find((e) => e.kind === 'binding' && e.from.nodeId === node.value!.id)
  if (!bind) return ''
  return props.graph.nodes.find((n) => n.id === bind.to.nodeId)?.refId ?? ''
})

const takenDeploymentIds = computed(() => {
  const taken = new Set<string>()
  for (const e of props.graph.edges) {
    if (e.kind !== 'binding' || !node.value || e.from.nodeId === node.value.id) continue
    const ref = props.graph.nodes.find((n) => n.id === e.to.nodeId)?.refId
    if (ref) taken.add(ref)
  }
  return taken
})

const bindingOptions = computed(() =>
  props.snapshot.catalog.deployments
    .slice()
    .sort((a, b) => Number(a.external) - Number(b.external) || a.displayName.localeCompare(b.displayName, 'ko') || a.id.localeCompare(b.id))
    .map((d) => ({
      id: d.id,
      label: `${d.displayName} (${d.providerModelId})${d.external ? ' · 외부' : ' · 로컬'}${takenDeploymentIds.value.has(d.id) ? ' · 이미 Target 있음' : ''}`,
      disabled: takenDeploymentIds.value.has(d.id),
    })),
)

function onBinding(event: Event) {
  const el = event.target as HTMLSelectElement
  if (node.value && el.value !== '' && !props.readOnly) {
    emit('command', { type: 'bindTarget', targetNodeId: node.value.id, deploymentId: el.value })
  }
  void nextTick(() => { el.value = boundDeploymentId.value })
}

/* project access */
const accessGranted = computed(() => !!node.value && props.graph.edges.some((e) => e.kind === 'access' && e.from.nodeId === node.value!.id))

function onAccess(event: Event) {
  const el = event.target as HTMLInputElement
  if (node.value && !props.readOnly && props.snapshot.accessComplete) {
    emit('command', { type: 'setAccess', projectNodeId: node.value.id, granted: el.checked })
  }
  void nextTick(() => { el.checked = accessGranted.value })
}

/* ------------------------------ actions ------------------------------ */
const canDeleteNode = computed(() => !!spec.value && spec.value.deletable && !view.value?.locked && !props.readOnly)

function removeNode() {
  if (!node.value || !canDeleteNode.value) return
  emit('command', { type: 'removeItems', nodeIds: [node.value.id], edgeIds: [] })
}

function toggleCollapse() {
  if (!node.value || props.readOnly) return
  emit('command', { type: 'toggleCollapse', nodeIds: [node.value.id], collapsed: !node.value.collapsed })
}

function removeEdge() {
  if (!edge.value || edge.value.locked || props.readOnly) return
  emit('command', { type: 'removeItems', nodeIds: [], edgeIds: [edge.value.id] })
}

function removeSelection() {
  if (props.readOnly) return
  emit('command', { type: 'removeItems', nodeIds: [...props.selection.nodeIds], edgeIds: [...props.selection.edgeIds] })
}

const edgeInfo = computed(() => (edge.value ? describeEdge(edge.value) : null))
</script>

<template>
  <aside class="fd-inspector" aria-label="인스펙터">
    <!-- No selection: validation results -->
    <div v-if="mode === 'issues'" class="fd-panel">
      <h2 class="fd-heading">검증 결과</h2>
      <p v-if="issues.length === 0" class="fd-empty">검증 이슈가 없습니다. 노드를 선택하면 속성을 편집할 수 있습니다.</p>
      <section v-for="group in groupedIssues" :key="group.severity" class="fd-group">
        <h3 class="fd-subheading" :class="`is-${group.severity}`">{{ SEVERITY_LABEL[group.severity] }} {{ group.items.length }}건</h3>
        <ul class="fd-issue-list">
          <li v-for="(issue, index) in group.items" :key="`${issue.code}-${issue.nodeId}-${issue.edgeId}-${index}`">
            <button v-if="issueClickable(issue)" type="button" class="fd-issue" :class="`is-${issue.severity}`" @click="pickIssue(issue)">
              <span class="fd-issue-code">{{ issue.code }}</span>
              <span>{{ issueLabel(issue) }}</span>
            </button>
            <div v-else class="fd-issue is-static" :class="`is-${issue.severity}`">
              <span class="fd-issue-code">{{ issue.code }}</span>
              <span>{{ issue.message }}</span>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <!-- Single node -->
    <div v-else-if="mode === 'node' && node && view && spec" class="fd-panel">
      <header class="fd-node-head" :data-category="view.category">
        <span class="fd-node-icon" aria-hidden="true">{{ view.icon }}</span>
        <div class="fd-node-text">
          <h2 class="fd-heading">{{ view.title }}</h2>
          <p class="fd-meta">{{ view.typeLabel }}<template v-if="view.subtitle"> · {{ view.subtitle }}</template></p>
        </div>
      </header>
      <div v-if="view.badges.length > 0" class="fd-badges">
        <span v-for="badge in view.badges" :key="badge" class="fd-chip">{{ badge }}</span>
      </div>

      <dl v-if="facts.length > 0" class="fd-facts">
        <template v-for="row in facts" :key="row.label">
          <dt>{{ row.label }}</dt>
          <dd>{{ row.value }}</dd>
        </template>
      </dl>

      <p v-if="readOnly" class="fd-note">보기 전용 모드에서는 속성을 편집할 수 없습니다.</p>

      <form v-if="fields.length > 0" class="fd-form" @submit.prevent>
        <div v-for="field in fields" :key="field.key" class="fd-field">
          <!-- boolean -->
          <label v-if="field.kind === 'boolean'" class="toggle-field" :for="fieldId(field)">
            <span>{{ field.label }}<small v-if="field.help">{{ field.help }}</small></span>
            <input :id="fieldId(field)" :aria-label="field.label" type="checkbox" :checked="boolValue(field.key)" :disabled="fieldDisabled(field)" @change="onBoolean($event, field)" />
          </label>

          <!-- access toggle -->
          <template v-else-if="field.kind === 'access-toggle'">
            <label class="toggle-field" :for="fieldId(field)">
              <span>{{ field.label }}<small>이 프로젝트의 API 키가 이 서비스를 호출할 수 있습니다.</small></span>
              <input :id="fieldId(field)" :aria-label="field.label" type="checkbox" :checked="accessGranted" :disabled="readOnly || !snapshot.accessComplete" @change="onAccess" />
            </label>
            <p v-if="!snapshot.accessComplete" class="fd-note fd-note--warning">프로젝트 접근 정보를 모두 읽지 못해 접근 설정을 편집할 수 없습니다.</p>
          </template>

          <template v-else>
            <label class="fd-label" :for="fieldId(field)">{{ field.label }}</label>

            <input
              v-if="field.kind === 'text'"
              :id="fieldId(field)" :aria-label="field.label"
              type="text"
              :value="textValue(field.key)"
              :maxlength="field.maxLength"
              :placeholder="field.placeholder"
              :disabled="fieldDisabled(field)"
              @change="onText($event, field)"
            />

            <textarea
              v-else-if="field.kind === 'textarea'"
              :id="fieldId(field)" :aria-label="field.label"
              rows="6"
              :value="textValue(field.key)"
              :maxlength="field.maxLength"
              :placeholder="field.placeholder"
              :disabled="fieldDisabled(field)"
              @change="onText($event, field)"
            ></textarea>

            <input
              v-else-if="field.kind === 'number' || field.kind === 'integer'"
              :id="fieldId(field)" :aria-label="field.label"
              type="number"
              inputmode="decimal"
              :value="textValue(field.key)"
              :min="field.min"
              :max="field.max"
              :step="field.step ?? (field.kind === 'integer' ? 1 : 'any')"
              :placeholder="field.nullable ? '비워 두면 사용 안 함' : field.placeholder"
              :disabled="fieldDisabled(field)"
              @change="onNumber($event, field)"
            />

            <select v-else-if="field.kind === 'select'" :id="fieldId(field)" :aria-label="field.label" :value="textValue(field.key)" :disabled="fieldDisabled(field)" @change="onSelect($event, field)">
              <option v-for="option in field.options ?? []" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>

            <CapabilityPicker
              v-else-if="field.kind === 'capabilities'"
              :model-value="textValue(field.key) || '[]'"
              :class="{ 'fd-disabled': fieldDisabled(field) }"
              @update:model-value="!fieldDisabled(field) && onCapabilities(field, $event)"
            />

            <template v-else-if="field.kind === 'masker-select'">
              <select :id="fieldId(field)" :aria-label="field.label" :value="textValue('deploymentId')" :disabled="readOnly" @change="onMasker">
                <option value="">선택 안 함 (내장 정규식 엔진)</option>
                <option
                  v-for="m in snapshot.catalog.maskers"
                  :key="m.deploymentId"
                  :value="m.deploymentId"
                  :disabled="!m.eligible && m.deploymentId !== textValue('deploymentId')"
                >{{ m.displayName }} · {{ m.sourceName }}{{ m.eligible ? '' : ' (사용 불가)' }}</option>
              </select>
              <p v-if="currentMasker" class="fd-help">{{ currentMasker.trustReason }}</p>
              <ul v-if="snapshot.catalog.maskers.some((m) => !m.eligible)" class="fd-help-list">
                <li v-for="m in snapshot.catalog.maskers.filter((x) => !x.eligible)" :key="m.deploymentId">{{ m.displayName }}: {{ m.trustReason }}</li>
              </ul>
              <div v-if="!maskerConnected" class="fd-connect">
                <p class="fd-note fd-note--warning">마스커가 가드레일에 연결되어 있지 않아 적용되지 않습니다.</p>
                <button type="button" class="secondary-button fd-small-button" :disabled="readOnly || !guardrailExists" @click="connectMasker">가드레일에 연결</button>
              </div>
            </template>

            <select v-else-if="field.kind === 'deployment-binding'" :id="fieldId(field)" :aria-label="field.label" :value="boundDeploymentId" :disabled="readOnly" @change="onBinding">
              <option value="" disabled>Deployment 선택</option>
              <option v-for="o in bindingOptions" :key="o.id" :value="o.id" :disabled="o.disabled">{{ o.label }}</option>
            </select>

            <p v-if="field.help" class="fd-help">{{ field.help }}</p>
          </template>

          <ul v-if="fieldIssues(field).length > 0" class="fd-field-issues">
            <li v-for="(issue, index) in fieldIssues(field)" :key="index" :class="`is-${issue.severity}`">{{ issue.message }}</li>
          </ul>
        </div>
      </form>

      <p v-if="node.type === 'guardrail'" class="fd-note">
        세부 탐지 정책은 데이터 보호 화면에서 관리합니다.
        <a href="#data-protection">데이터 보호 화면 열기</a>
      </p>

      <section v-if="generalIssues.length > 0" class="fd-group">
        <h3 class="fd-subheading">노드 이슈</h3>
        <ul class="fd-issue-list">
          <li v-for="(issue, index) in generalIssues" :key="index">
            <div class="fd-issue is-static" :class="`is-${issue.severity}`">
              <span class="fd-issue-code">{{ issue.code }}</span>
              <span>{{ issue.message }}</span>
            </div>
          </li>
        </ul>
      </section>

      <div class="fd-actions">
        <button type="button" class="secondary-button fd-small-button" :disabled="readOnly" @click="toggleCollapse">{{ node.collapsed ? '펼치기' : '접기' }}</button>
        <button type="button" class="secondary-button fd-small-button" @click="emit('focus-node', node.id)">화면 중앙</button>
        <button type="button" class="secondary-button fd-small-button fd-danger" :disabled="!canDeleteNode" :title="spec.deletable ? '' : '삭제할 수 없는 노드입니다.'" @click="removeNode">삭제</button>
      </div>
    </div>

    <!-- Single edge -->
    <div v-else-if="mode === 'edge' && edge && edgeInfo" class="fd-panel">
      <h2 class="fd-heading">연결</h2>
      <p class="fd-meta">{{ edgeInfo.label }}</p>
      <p class="fd-text">{{ edgeInfo.meaning }}</p>
      <dl class="fd-facts">
        <dt>시작</dt>
        <dd>{{ nodeTitle(edge.from.nodeId) }}</dd>
        <dt>끝</dt>
        <dd>{{ nodeTitle(edge.to.nodeId) }}</dd>
      </dl>
      <p v-if="edge.locked" class="fd-note">자동으로 관리되는 연결이라 삭제할 수 없습니다.</p>
      <div class="fd-actions">
        <button type="button" class="secondary-button fd-small-button fd-danger" :disabled="edge.locked || readOnly" @click="removeEdge">연결 삭제</button>
      </div>
    </div>

    <!-- Multi selection -->
    <div v-else class="fd-panel">
      <h2 class="fd-heading">{{ totalSelected }}개 항목 선택됨</h2>
      <p class="fd-meta">노드 {{ selection.nodeIds.length }}개 · 연결 {{ selection.edgeIds.length }}개</p>
      <div class="fd-actions">
        <button type="button" class="secondary-button fd-small-button fd-danger" :disabled="readOnly" @click="removeSelection">선택 삭제</button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.fd-inspector { height: 100%; min-height: 0; overflow-y: auto; background: var(--surface); color: var(--text); }
.fd-panel { display: grid; gap: 12px; padding: 14px; }
.fd-heading { margin: 0; font-size: 13px; font-weight: 800; line-height: 1.3; overflow-wrap: anywhere; }
.fd-subheading { margin: 0 0 6px; color: var(--muted); font-size: 10px; font-weight: 800; }
.fd-subheading.is-error { color: var(--danger); }
.fd-subheading.is-warning { color: var(--warning); }
.fd-meta { margin: 2px 0 0; color: var(--muted); font-size: 10px; overflow-wrap: anywhere; }
.fd-text { margin: 0; color: var(--text-soft); font-size: 11px; line-height: 1.5; }
.fd-empty { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.5; }

.fd-node-head { display: flex; align-items: center; gap: 10px; padding-left: 10px; border-left: 4px solid var(--muted); }
.fd-node-head[data-category='entry'] { border-left-color: var(--info); }
.fd-node-head[data-category='pipeline'] { border-left-color: var(--accent-strong); }
.fd-node-head[data-category='routing'] { border-left-color: var(--warning); }
.fd-node-icon { font-size: 18px; color: var(--text-soft); }
.fd-node-text { min-width: 0; }

.fd-badges { display: flex; flex-wrap: wrap; gap: 4px; }
.fd-chip { display: inline-flex; align-items: center; height: 18px; padding: 0 7px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface-3); color: var(--muted); font-size: 9px; font-weight: 800; }

.fd-facts { margin: 0; display: grid; grid-template-columns: minmax(70px, auto) 1fr; gap: 5px 10px; padding: 10px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); font-size: 10px; }
.fd-facts dt { color: var(--muted); font-weight: 700; }
.fd-facts dd { margin: 0; color: var(--text-soft); overflow-wrap: anywhere; }

.fd-form { display: grid; gap: 12px; }
.fd-field { display: grid; gap: 6px; }
.fd-label { color: var(--text-soft); font-size: 10px; font-weight: 800; }
.fd-field input:not([type='checkbox']), .fd-field select { min-height: 36px; font-size: 11px; }
.fd-field textarea { font-size: 11px; min-height: 96px; }
.fd-field input:disabled, .fd-field select:disabled, .fd-field textarea:disabled { opacity: .6; cursor: not-allowed; }
.fd-disabled { opacity: .6; pointer-events: none; }
.fd-help { margin: 0; color: var(--muted); font-size: 9px; line-height: 1.5; }
.fd-help-list { margin: 0; padding-left: 16px; color: var(--muted); font-size: 9px; line-height: 1.5; }
.fd-note { margin: 0; padding: 9px 11px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); color: var(--text-soft); font-size: 10px; line-height: 1.5; }
.fd-note a { color: var(--accent-strong); font-weight: 700; }
.fd-note--warning { border-color: var(--warning); background: var(--warning-dim); }
.fd-connect { display: grid; gap: 8px; }

.fd-field-issues { margin: 0; padding: 0; list-style: none; display: grid; gap: 3px; font-size: 9px; }
.fd-field-issues li { padding-left: 8px; border-left: 2px solid var(--muted); color: var(--text-soft); }
.fd-field-issues li.is-error { border-left-color: var(--danger); color: var(--danger); }
.fd-field-issues li.is-warning { border-left-color: var(--warning); color: var(--warning); }

.fd-group { display: grid; }
.fd-issue-list { margin: 0; padding: 0; list-style: none; display: grid; gap: 6px; }
.fd-issue {
  width: 100%;
  display: grid;
  gap: 2px;
  padding: 8px 10px;
  text-align: left;
  border: 1px solid var(--border);
  border-left-width: 3px;
  border-radius: 8px;
  background: var(--surface-2);
  color: var(--text-soft);
  font-size: 10px;
  line-height: 1.45;
}
button.fd-issue { cursor: pointer; }
button.fd-issue:hover { background: var(--accent-dim); }
.fd-issue.is-error { border-left-color: var(--danger); }
.fd-issue.is-warning { border-left-color: var(--warning); }
.fd-issue.is-info { border-left-color: var(--info); }
.fd-issue-code { color: var(--muted); font-size: 8px; font-weight: 800; letter-spacing: .03em; }

.fd-actions { display: flex; flex-wrap: wrap; gap: 6px; padding-top: 4px; }
.fd-small-button { min-height: 32px; padding: 0 12px; }
.fd-danger { color: var(--danger); border-color: var(--danger); }
.fd-danger:hover:not(:disabled) { background: var(--danger-dim); border-color: var(--danger); }
.fd-small-button:disabled { cursor: not-allowed; opacity: .5; }
</style>
