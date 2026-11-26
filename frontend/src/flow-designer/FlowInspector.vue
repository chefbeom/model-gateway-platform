<script setup lang="ts">
import { computed, nextTick } from 'vue'
import CapabilityPicker from '../CapabilityPicker.vue'
import FlowConnectionModels from './FlowConnectionModels.vue'
import { GUARDRAIL_PRESETS, NODE_TYPES, PROMPT_HELP_COMMON, PROMPT_HELP_MODEL, describeEdge, describeNode, hostNodeIdFor, modelAvailability, nodeFacts, nodeSpec, presetOf } from './catalog'
import { connectionGroups, connectionOfNode, describeConnectionNode, isConnectionNode } from './connections'
import { groupModelsByConnection, shortModelName } from './palette'
import { insertableTypesForEdge, isBranchPrompt, promptScopeForSplice } from './workflow'
import type {
  AddableNodeType,
  DesignerMode,
  FieldSpec,
  FlowCommand,
  FlowEdge,
  FlowGraph,
  FlowNode,
  FlowPropValue,
  FlowSelection,
  FlowViewMode,
  ServiceFlowSnapshot,
  ValidationIssue,
} from './types'

const props = withDefaults(defineProps<{
  graph: FlowGraph
  snapshot: ServiceFlowSnapshot
  selection: FlowSelection
  issues: ValidationIssue[]
  readOnly: boolean
  mode?: DesignerMode
  viewMode?: FlowViewMode
}>(), { mode: 'advanced' })

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
const panel = computed<'issues' | 'node' | 'edge' | 'multi'>(() => {
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

function applyFix(issue: ValidationIssue) {
  if (props.readOnly || !issue.fix) return
  emit('command', issue.fix.command)
}

function issueClickable(issue: ValidationIssue): boolean {
  return !!(issue.nodeId || issue.edgeId)
}

/* ------------------------------ node details ------------------------------ */
const connectionFirst = computed(() => props.viewMode === 'connection')
/** Connection view + a single connection (host) node selected. */
const connectionPanel = computed(() => connectionFirst.value && !!node.value && isConnectionNode(node.value))
const connectionMembers = computed(() => {
  if (!connectionPanel.value || !node.value) return []
  const group = connectionGroups(props.graph).find((g) => g.connectionId === node.value!.id)
  return (group?.llms ?? []).map((n) => ({
    id: n.id,
    title: describeNode(n, props.graph, props.snapshot).title,
    summary: `P${n.props.priority ?? '-'} · W${n.props.weight ?? '-'}${n.props.enabled === false ? ' · 중지' : ''}`,
  }))
})
/** Connection view: the model-replace select only offers models of the same connection (D9). */
const currentHost = computed(() => (connectionFirst.value && node.value && node.value.type === 'llm' ? connectionOfNode(props.graph, node.value.id) : null))
const edgeIntoModel = computed(() => {
  const e = edge.value
  if (!connectionFirst.value || !e || (e.kind !== 'route' && e.kind !== 'flow' && e.kind !== 'access')) return false
  return connectionOfNode(props.graph, e.to.nodeId) !== null
})

function pickMember(id: string) {
  emit('update:selection', { nodeIds: [id], edgeIds: [] })
  emit('focus-node', id)
}

function removeConnection() {
  if (!node.value || props.readOnly) return
  emit('command', { type: 'removeItems', nodeIds: [node.value.id], edgeIds: [], withConnectionModels: true })
}

const view = computed(() => (node.value ? (connectionPanel.value ? describeConnectionNode(node.value, props.graph, props.snapshot) : describeNode(node.value, props.graph, props.snapshot)) : null))
const spec = computed(() => (node.value ? nodeSpec(node.value.type) : null))
const facts = computed(() => (node.value ? nodeFacts(node.value, props.graph, props.snapshot) : []))
const nodeIssues = computed(() => (node.value ? props.issues.filter((i) => i.nodeId === node.value!.id) : []))

function isVisible(field: FieldSpec): boolean {
  if (!field.visibleWhen || !node.value) return true
  return field.visibleWhen.oneOf.includes((node.value.props[field.visibleWhen.key] ?? null))
}

function inMode(field: FieldSpec): boolean {
  if (field.advanced && props.mode === 'basic') return false
  if (field.basicOnly && props.mode !== 'basic') return false
  return true
}

const fields = computed(() => (spec.value ? spec.value.fields.filter((f) => inMode(f) && isVisible(f)) : []))

function fieldKeys(field: FieldSpec): string[] {
  if (field.key === '$model') return ['$model', 'deploymentId']
  if (field.key === '$access') return ['$access', 'access']
  if (field.key === '$preset') return ['$preset', 'dataBoundaryPolicy', 'guardrailAction']
  return [field.key]
}

function fieldHelp(field: FieldSpec): string | undefined {
  if (field.key === 'systemPromptTemplate' && node.value?.type === 'prompt') return isBranchPrompt(node.value) ? PROMPT_HELP_MODEL : PROMPT_HELP_COMMON
  return field.help
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

/* guardrail preset (basic mode '보호 수준') */
const currentPreset = computed(() => (node.value ? presetOf(node.value.props) ?? '' : ''))

function onPreset(event: Event) {
  const el = event.target as HTMLSelectElement
  const preset = GUARDRAIL_PRESETS.find((item) => item.id === el.value)
  if (preset && node.value && !props.readOnly) {
    emit('command', { type: 'updateProps', nodeId: node.value.id, patch: { dataBoundaryPolicy: preset.dataBoundaryPolicy, guardrailAction: preset.guardrailAction } })
  }
  void nextTick(() => { el.value = currentPreset.value })
}

const presetDescription = computed(() => GUARDRAIL_PRESETS.find((item) => item.id === currentPreset.value)?.description ?? null)

/* llm model replacement */
const currentDeploymentId = computed(() => (node.value && node.value.type === 'llm' ? node.value.refId ?? '' : ''))

const takenDeploymentIds = computed(() => {
  const taken = new Set<string>()
  for (const n of props.graph.nodes) if (n.type === 'llm' && n.refId && n.id !== node.value?.id) taken.add(n.refId)
  return taken
})

const modelOptions = computed(() => {
  const catalog = props.snapshot.catalog
  const free = catalog.deployments.filter((d) => !takenDeploymentIds.value.has(d.id) && (currentHost.value === null || hostNodeIdFor(d) === currentHost.value))
  const groups: Array<{ key: string; label: string; items: Array<{ id: string; label: string }> }> = []
  for (const external of [false, true]) {
    for (const g of groupModelsByConnection(free.filter((d) => d.external === external), catalog)) {
      groups.push({
        key: g.connection.id,
        label: (external ? '외부 · ' : '로컬 · ') + g.connection.label,
        items: g.items.map((d) => {
          const inactive = modelAvailability(d, catalog).availability === 'inactive'
          const short = shortModelName(d.displayName, d.providerModelId)
          const base = short !== d.displayName ? short : d.displayName + ' (' + d.providerModelId + ')'
          return { id: d.id, label: base + (inactive ? ' (중지)' : '') }
        }),
      })
    }
  }
  return groups
})

function onModelReplace(event: Event) {
  const el = event.target as HTMLSelectElement
  if (node.value && el.value !== '' && el.value !== currentDeploymentId.value && !props.readOnly) {
    emit('command', { type: 'replaceModel', nodeId: node.value.id, deploymentId: el.value })
  }
  void nextTick(() => { el.value = currentDeploymentId.value })
}

/** The flow edge that enters this LLM (where a model-specific prompt would be spliced in). */
const incomingFlowEdge = computed(() => {
  if (!node.value || node.value.type !== 'llm') return null
  return props.graph.edges.find((e) => (e.kind === 'flow' || e.kind === 'route') && e.to.nodeId === node.value!.id) ?? null
})
const branchPromptExists = computed(() => !!node.value && node.value.type === 'llm' && node.value.refId !== null && props.graph.nodes.some((n) => n.id === `prompt:${node.value!.refId}`))
const canAddBranchPrompt = computed(() =>
  !props.readOnly && !!incomingFlowEdge.value && !branchPromptExists.value && props.snapshot.features?.targetPrompt !== false &&
  insertableTypesForEdge(props.graph, incomingFlowEdge.value.id, props.snapshot).includes('prompt'))

function addBranchPrompt() {
  const incoming = incomingFlowEdge.value
  if (!incoming || !canAddBranchPrompt.value) return
  emit('command', { type: 'addNode', nodeType: 'prompt', spliceEdgeId: incoming.id })
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
const canDeleteNode = computed(() => !!spec.value && spec.value.deletable && !view.value?.locked && !view.value?.protected && !props.readOnly)

function releaseAndRemove() {
  if (!node.value || !view.value?.protected || props.readOnly) return
  if (!window.confirm('외부 노출 방지 정책을 해제하고 가드레일을 삭제할까요? 적용하면 데이터 경계와 민감정보 처리가 기본값으로 바뀝니다.')) return
  emit('command', { type: 'removeItems', nodeIds: ['guardrail'], edgeIds: [], force: true })
}

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
  emit('command', { type: 'removeItems', nodeIds: [...props.selection.nodeIds], edgeIds: [...props.selection.edgeIds], withConnectionModels: connectionFirst.value })
}

const edgeInfo = computed(() => (edge.value ? describeEdge(edge.value) : null))

const edgeInsertables = computed(() => {
  if (!edge.value || props.readOnly) return []
  const scope = promptScopeForSplice(props.graph, edge.value.id)
  return insertableTypesForEdge(props.graph, edge.value.id, props.snapshot).map((type: AddableNodeType) => ({
    type,
    label: type === 'prompt' ? (scope === 'model' ? '모델별 시스템 프롬프트' : '공통 시스템 프롬프트') : NODE_TYPES[type as keyof typeof NODE_TYPES].label,
  }))
})

function insertOnEdge(type: AddableNodeType) {
  if (!edge.value || props.readOnly) return
  emit('command', { type: 'addNode', nodeType: type, spliceEdgeId: edge.value.id })
}
</script>

<template>
  <aside class="fd-inspector" aria-label="인스펙터">
    <!-- No selection: validation results -->
    <div v-if="panel === 'issues'" class="fd-panel">
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
            <button v-if="issue.fix" type="button" class="secondary-button fd-small-button fd-fix" :disabled="readOnly" @click="applyFix(issue)">{{ issue.fix.label }}</button>
          </li>
        </ul>
      </section>
    </div>

    <!-- Single node -->
    <div v-else-if="panel === 'node' && node && view && spec" class="fd-panel">
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

      <p v-if="view.lockReason && (view.locked || view.protected)" class="fd-note" :class="{ 'fd-note--warning': view.protected }">{{ view.lockReason }}</p>
      <p v-if="node.type === 'policy'" class="fd-note"><a href="#projects">프로젝트 화면에서 변경</a></p>

      <dl v-if="facts.length > 0" class="fd-facts">
        <template v-for="row in facts" :key="row.label">
          <dt>{{ row.label }}</dt>
          <dd>{{ row.value }}</dd>
        </template>
      </dl>

      <template v-if="connectionPanel">
        <p v-if="view.tone === 'error' || view.tone === 'muted'" class="fd-note fd-note--warning">이 연결은 지금 요청을 처리할 수 없어 아래 모델이 흐리게 표시됩니다.</p>
        <section class="fd-group">
          <h3 class="fd-subheading">이 연결의 모델 {{ connectionMembers.length }}개</h3>
          <p v-if="connectionMembers.length === 0" class="fd-empty">아직 모델이 없습니다. 아래에서 모델을 고르세요.</p>
          <ul v-else class="fd-issue-list">
            <li v-for="member in connectionMembers" :key="member.id">
              <button type="button" class="fd-issue" @click="pickMember(member.id)">
                <span>{{ member.title }}</span>
                <span class="fd-issue-code">{{ member.summary }}</span>
              </button>
            </li>
          </ul>
        </section>
        <FlowConnectionModels :snapshot="snapshot" :graph="graph" :connection-id="node.id" :read-only="readOnly" @command="emit('command', $event)" />
      </template>

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

            <template v-else-if="field.kind === 'guardrail-preset'">
              <select :id="fieldId(field)" :aria-label="field.label" :value="currentPreset" :disabled="fieldDisabled(field)" @change="onPreset">
                <option v-if="currentPreset === ''" value="" disabled>사용자 지정 — 고급 모드에서 편집</option>
                <option v-for="preset in GUARDRAIL_PRESETS" :key="preset.id" :value="preset.id">{{ preset.label }}</option>
              </select>
              <p v-if="presetDescription" class="fd-help">{{ presetDescription }}</p>
            </template>

            <template v-else-if="field.kind === 'model-replace'">
              <select :id="fieldId(field)" :aria-label="field.label" :value="currentDeploymentId" :disabled="fieldDisabled(field)" @change="onModelReplace">
                <option v-if="!modelOptions.some((g) => g.items.some((i) => i.id === currentDeploymentId))" :value="currentDeploymentId" disabled>{{ view.title }} (현재)</option>
                <optgroup v-for="group in modelOptions" :key="group.key" :label="group.label">
                  <option v-for="item in group.items" :key="item.id" :value="item.id">{{ item.label }}</option>
                </optgroup>
              </select>
              <p v-if="connectionFirst" class="fd-help">연결 중심 보기에서는 같은 연결의 모델로만 바꿀 수 있습니다. 다른 연결의 모델은 그 연결 노드에서 추가하세요.</p>
            </template>

            <p v-if="fieldHelp(field)" class="fd-help">{{ fieldHelp(field) }}</p>
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
            <button v-if="issue.fix" type="button" class="secondary-button fd-small-button fd-fix" :disabled="readOnly" @click="applyFix(issue)">{{ issue.fix.label }}</button>
          </li>
        </ul>
      </section>

      <div v-if="node.type === 'llm'" class="fd-actions">
        <button type="button" class="secondary-button fd-small-button" :disabled="!canAddBranchPrompt" :title="branchPromptExists ? '이 LLM에는 모델별 프롬프트가 이미 있습니다.' : '이 LLM 앞에 모델별 시스템 프롬프트를 넣습니다.'" @click="addBranchPrompt">모델별 프롬프트 추가</button>
      </div>

      <div class="fd-actions">
        <button v-if="view.protected" type="button" class="secondary-button fd-small-button fd-danger" :disabled="readOnly" @click="releaseAndRemove">보호 해제 후 삭제</button>
        <button type="button" class="secondary-button fd-small-button" :disabled="readOnly" @click="toggleCollapse">{{ node.collapsed ? '펼치기' : '접기' }}</button>
        <button type="button" class="secondary-button fd-small-button" @click="emit('focus-node', node.id)">화면 중앙</button>
        <button v-if="connectionPanel" type="button" class="secondary-button fd-small-button fd-danger" :disabled="readOnly" @click="removeConnection">연결 삭제{{ connectionMembers.length > 0 ? ` (모델 ${connectionMembers.length}개 포함)` : '' }}</button>
        <button v-if="!connectionPanel" type="button" class="secondary-button fd-small-button fd-danger" :disabled="!canDeleteNode" :title="spec.deletable && !view.protected ? '' : '삭제할 수 없는 노드입니다.'" @click="removeNode">삭제</button>
      </div>
    </div>

    <!-- Single edge -->
    <div v-else-if="panel === 'edge' && edge && edgeInfo" class="fd-panel">
      <h2 class="fd-heading">간선</h2>
      <p class="fd-meta">{{ edgeInfo.label }}</p>
      <p class="fd-text">{{ edgeInfo.meaning }}</p>
      <p v-if="edgeIntoModel" class="fd-note">연결 중심 보기에서는 연결 노드를 거쳐 그려지지만, 실제 간선은 아래의 시작 → 끝입니다.</p>
      <dl class="fd-facts">
        <dt>시작</dt>
        <dd>{{ nodeTitle(edge.from.nodeId) }}</dd>
        <dt>끝</dt>
        <dd>{{ nodeTitle(edge.to.nodeId) }}</dd>
      </dl>
      <section v-if="edgeInsertables.length > 0" class="fd-group">
        <h3 class="fd-subheading">이 간선에 노드 넣기</h3>
        <div class="fd-actions">
          <button v-for="item in edgeInsertables" :key="item.type" type="button" class="secondary-button fd-small-button" @click="insertOnEdge(item.type)">{{ item.label }}</button>
        </div>
      </section>
      <p v-if="edge.locked" class="fd-note">자동으로 관리되는 간선이라 삭제할 수 없습니다.</p>
      <div class="fd-actions">
        <button type="button" class="secondary-button fd-small-button fd-danger" :disabled="edge.locked || readOnly" @click="removeEdge">간선 삭제</button>
      </div>
    </div>

    <!-- Multi selection -->
    <div v-else class="fd-panel">
      <h2 class="fd-heading">{{ totalSelected }}개 항목 선택됨</h2>
      <p class="fd-meta">노드 {{ selection.nodeIds.length }}개 · 간선 {{ selection.edgeIds.length }}개</p>
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
.fd-node-head[data-category='guard'] { border-left-color: var(--warning); }
.fd-node-head[data-category='prompt'] { border-left-color: var(--accent-strong); }
.fd-node-head[data-category='cache'] { border-left-color: var(--accent-strong); }
.fd-node-head[data-category='routing'] { border-left-color: var(--warning); }
.fd-node-head[data-category='llm'] { border-left-color: var(--accent); }
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
.fd-fix { margin-top: 4px; }
.fd-small-button:disabled { cursor: not-allowed; opacity: .5; }
</style>
