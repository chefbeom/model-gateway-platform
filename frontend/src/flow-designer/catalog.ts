import type {
  ConnectionRule,
  DataBoundaryPolicy,
  DeploymentInfo,
  FactRow,
  FieldSpec,
  FlowConfigState,
  FlowEdge,
  FlowGraph,
  FlowNode,
  FlowPropValue,
  GuardrailAction,
  HealthTone,
  NodeType,
  NodeTypeSpec,
  NodeView,
  PortRef,
  PortSpec,
  SchedulingField,
  SchedulingState,
  ServiceField,
  ServiceFlowSnapshot,
  ServiceState,
  TargetField,
} from './types'

export const GRID_SIZE = 16
export const LIMITS = {
  maxNodes: 300,
  maxEdges: 600,
  maxOperations: 100,
  promptMaxLength: 16000,
  displayNameMaxLength: 200,
  capabilitiesMaxLength: 2000,
  maxProjectsForAccess: 200,
} as const

export const SINGLETON_IDS = {
  service: 'service',
  guardrail: 'guardrail',
  prompt: 'prompt',
  cache: 'cache',
  router: 'router',
  masker: 'masker',
} as const

export const SERVICE_KEY_PATTERN = /^[a-z0-9][a-z0-9-]{0,119}$/

export const DEFAULT_SERVICE_STATE: ServiceState = {
  serviceKey: '',
  displayName: '',
  enabled: true,
  dataBoundaryPolicy: 'EXTERNAL_ALLOWED',
  requiredCapabilitiesJson: '[]',
  currency: 'KRW',
  inputPricePerMillion: 0,
  outputPricePerMillion: 0,
  guardrailAction: 'ALLOW',
  maskerDeploymentId: null,
  maskPersonNames: true,
  systemPromptTemplate: null,
  temperaturePolicy: 'REQUEST',
  temperatureValue: null,
  reasoningEffort: 'REQUEST',
  openAiFastMode: false,
  responseCacheEnabled: false,
  responseCacheTtlSeconds: 300,
  failoverPolicy: 'STRICT',
  retryPolicy: 'SAFE',
  allowDegraded: false,
}

export const DEFAULT_SCHEDULING: SchedulingState = {
  mode: 'IMMEDIATE',
  localWaitMs: 5000,
  maxWaitMs: 30000,
  maxQueuedRequests: 100,
  maxQueuedBytes: 33554432,
  maxQueuedPerKey: 10,
}

export const DEFAULT_CONFIG: FlowConfigState = {
  service: DEFAULT_SERVICE_STATE,
  scheduling: DEFAULT_SCHEDULING,
  targets: [],
  accessProjectIds: [],
}

export const GUARDRAIL_STRICTNESS: Record<GuardrailAction, number> = {
  ALLOW: 0,
  REDACT_RESTORE: 1,
  REDACT: 2,
  LOCAL_ONLY: 3,
  BLOCK: 4,
}

export const BOUNDARY_RANK: Record<DataBoundaryPolicy, number> = {
  INTERNAL_ONLY: 0,
  APPROVED_EXTERNAL_NO_RETENTION: 1,
  EXTERNAL_ALLOWED: 2,
}

/** Ordered field lists shared by compile/diff/validate (extra exports, not part of the §4.3 contract). */
export const SERVICE_FIELDS: ServiceField[] = [
  'serviceKey', 'displayName', 'enabled', 'dataBoundaryPolicy', 'requiredCapabilitiesJson', 'currency',
  'inputPricePerMillion', 'outputPricePerMillion', 'guardrailAction', 'maskerDeploymentId', 'maskPersonNames',
  'systemPromptTemplate', 'temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode',
  'responseCacheEnabled', 'responseCacheTtlSeconds', 'failoverPolicy', 'retryPolicy', 'allowDegraded',
]
export const SCHEDULING_FIELDS: SchedulingField[] = ['mode', 'localWaitMs', 'maxWaitMs', 'maxQueuedRequests', 'maxQueuedBytes', 'maxQueuedPerKey']
export const TARGET_FIELDS: TargetField[] = ['deploymentId', 'priority', 'weight', 'degraded', 'enabled', 'maxConcurrencyOverride', 'followModelChanges']

export const FIELD_LABELS: Record<string, string> = {
  serviceKey: 'Service Key (model 값)',
  displayName: '표시 이름',
  enabled: '서비스 활성화',
  dataBoundaryPolicy: '데이터 경계',
  requiredCapabilitiesJson: '필수 Capability',
  currency: '통화',
  inputPricePerMillion: '입력 단가 / 1M',
  outputPricePerMillion: '출력 단가 / 1M',
  guardrailAction: '처리 정책',
  maskerDeploymentId: '마스킹 워커 Deployment',
  maskPersonNames: '한국어 성명 가명화',
  systemPromptTemplate: '시스템 프롬프트',
  temperaturePolicy: 'Temperature 처리',
  temperatureValue: '고정 Temperature',
  reasoningEffort: '추론 수준',
  openAiFastMode: 'Fast mode',
  responseCacheEnabled: '동일 질문 캐싱',
  responseCacheTtlSeconds: '캐시 TTL(초)',
  failoverPolicy: 'Failover 정책',
  retryPolicy: 'Retry 정책',
  allowDegraded: 'Degraded 허용',
  mode: '배분 방식',
  localWaitMs: '외부 전환 전 로컬 대기(ms)',
  maxWaitMs: '전체 최대 대기(ms)',
  maxQueuedRequests: '서비스 최대 대기 요청',
  maxQueuedPerKey: 'API 키별 최대 대기',
  maxQueuedBytes: '대기 본문 합계 한도(bytes)',
  deploymentId: 'Deployment',
  priority: '우선순위',
  weight: '가중치',
  degraded: 'Degraded Target',
  maxConcurrencyOverride: '동시성 재정의',
  followModelChanges: '모델 변경 자동 추적',
  'target.enabled': 'Target 활성화',
}

export function fieldLabel(entity: 'service' | 'scheduling' | 'target', field: string): string {
  if (entity === 'target') return FIELD_LABELS[`target.${field}`] ?? FIELD_LABELS[field] ?? field
  return FIELD_LABELS[field] ?? field
}

/* ------------------------------------------------------------------ */
/* Node type specs                                                     */
/* ------------------------------------------------------------------ */

const opts = (...pairs: Array<[string, string]>) => pairs.map(([value, label]) => ({ value, label }))

const BOUNDARY_OPTIONS = opts(
  ['INTERNAL_ONLY', '사내 GPU 전용'],
  ['APPROVED_EXTERNAL_NO_RETENTION', '외부 비보존 GPU 허용'],
  ['EXTERNAL_ALLOWED', '외부 AI 허용'],
)
const GUARDRAIL_OPTIONS = opts(
  ['ALLOW', '개인정보 허가 · 외부 전송 허용'],
  ['REDACT_RESTORE', '마스킹 전송 + 응답 원본 복원'],
  ['REDACT', '마스킹 전송 · 가명화 유지'],
  ['LOCAL_ONLY', '개인정보 검사 · 외부 금지'],
  ['BLOCK', '민감정보 차단'],
)
const TEMPERATURE_OPTIONS = opts(['REQUEST', '요청값 기준'], ['FIXED', '고정값'], ['OMIT', '요청에서 제거'])
const REASONING_OPTIONS = opts(
  ['REQUEST', 'REQUEST'], ['NONE', 'NONE'], ['MINIMAL', 'MINIMAL'], ['LOW', 'LOW'],
  ['MEDIUM', 'MEDIUM'], ['HIGH', 'HIGH'], ['XHIGH', 'XHIGH'], ['MAX', 'MAX'],
)
const SCHEDULING_OPTIONS = opts(
  ['IMMEDIATE', '즉시 다음 Target'],
  ['LOCAL_FIRST_WAIT', '로컬 우선 대기'],
  ['LOCAL_ONLY_QUEUE', '로컬 전용 대기'],
)

const port = (id: string, direction: 'in' | 'out', type: PortSpec['type'], label: string, multiple: boolean): PortSpec => ({ id, direction, type, label, multiple })

const WAITING_MODES: FlowPropValue[] = ['LOCAL_FIRST_WAIT', 'LOCAL_ONLY_QUEUE']

export const NODE_TYPES: Record<NodeType, NodeTypeSpec> = {
  project: {
    type: 'project', label: '프로젝트', category: 'entry', icon: '⌘', column: 0,
    singleton: false, deletable: true, paletteAddable: true,
    ports: [port('access', 'out', 'access', '접근', true)],
    fields: [{ key: '$access', label: '이 서비스 호출 허용', kind: 'access-toggle' }],
  },
  service: {
    type: 'service', label: '논리 서비스', category: 'entry', icon: '▣', column: 1,
    singleton: true, deletable: false, paletteAddable: false,
    ports: [port('access', 'in', 'access', '접근', true), port('request', 'out', 'request', '요청', false)],
    fields: [
      { key: 'serviceKey', label: 'Service Key (model 값)', kind: 'text', newServiceOnly: true, maxLength: 120, placeholder: 'text-pro' },
      { key: 'displayName', label: '표시 이름', kind: 'text', maxLength: 200 },
      { key: 'enabled', label: '서비스 활성화', kind: 'boolean' },
      { key: 'dataBoundaryPolicy', label: '데이터 경계', kind: 'select', options: BOUNDARY_OPTIONS },
      { key: 'requiredCapabilitiesJson', label: '필수 Capability', kind: 'capabilities', maxLength: 2000 },
      { key: 'currency', label: '통화', kind: 'select', options: opts(['KRW', 'KRW'], ['USD', 'USD']) },
      { key: 'inputPricePerMillion', label: '입력 단가 / 1M', kind: 'number', min: 0, step: 0.000001 },
      { key: 'outputPricePerMillion', label: '출력 단가 / 1M', kind: 'number', min: 0, step: 0.000001 },
    ],
  },
  masker: {
    type: 'masker', label: '마스킹 워커', category: 'pipeline', icon: '◍', column: 1,
    singleton: true, deletable: true, paletteAddable: true,
    ports: [port('masker', 'out', 'masker', '마스커', false)],
    fields: [{ key: 'deploymentId', label: '마스킹 워커 Deployment', kind: 'masker-select' }],
  },
  guardrail: {
    type: 'guardrail', label: '가드레일 · DLP', category: 'pipeline', icon: '⛨', column: 2,
    singleton: true, deletable: false, paletteAddable: false,
    ports: [port('request', 'in', 'request', '요청', false), port('masker', 'in', 'masker', '마스커', false), port('request', 'out', 'request', '요청', false)],
    fields: [
      { key: 'guardrailAction', label: '처리 정책', kind: 'select', options: GUARDRAIL_OPTIONS },
      { key: 'maskPersonNames', label: '한국어 성명 가명화', kind: 'boolean' },
    ],
  },
  prompt: {
    type: 'prompt', label: '프롬프트 · 파라미터', category: 'pipeline', icon: '✎', column: 3,
    singleton: true, deletable: false, paletteAddable: false,
    ports: [port('request', 'in', 'request', '요청', false), port('request', 'out', 'request', '요청', false)],
    fields: [
      { key: 'systemPromptTemplate', label: '시스템 프롬프트 (변경 시 버전 증가)', kind: 'textarea', maxLength: 16000 },
      { key: 'temperaturePolicy', label: 'Temperature 처리', kind: 'select', options: TEMPERATURE_OPTIONS },
      { key: 'temperatureValue', label: '고정 Temperature (0–2)', kind: 'number', min: 0, max: 2, step: 0.1, nullable: true, visibleWhen: { key: 'temperaturePolicy', oneOf: ['FIXED'] } },
      { key: 'reasoningEffort', label: '추론 수준 · 외부 API', kind: 'select', options: REASONING_OPTIONS },
      { key: 'openAiFastMode', label: 'Fast mode · 외부 API', kind: 'boolean' },
    ],
  },
  cache: {
    type: 'cache', label: '응답 캐시', category: 'pipeline', icon: '⟲', column: 4,
    singleton: true, deletable: false, paletteAddable: false,
    ports: [port('request', 'in', 'request', '요청', false), port('request', 'out', 'request', '요청', false)],
    fields: [
      { key: 'responseCacheEnabled', label: '동일 질문 캐싱', kind: 'boolean' },
      { key: 'responseCacheTtlSeconds', label: 'TTL(초)', kind: 'integer', min: 10, max: 86400, visibleWhen: { key: 'responseCacheEnabled', oneOf: [true] } },
    ],
  },
  router: {
    type: 'router', label: '라우터', category: 'routing', icon: '⑂', column: 5,
    singleton: true, deletable: false, paletteAddable: false,
    ports: [port('request', 'in', 'request', '요청', false), port('targets', 'out', 'route', 'Target', true)],
    fields: [
      { key: 'failoverPolicy', label: 'Failover 정책', kind: 'select', options: opts(['STRICT', 'STRICT'], ['COMPATIBLE', 'COMPATIBLE'], ['DEGRADED', 'DEGRADED']) },
      { key: 'retryPolicy', label: 'Retry 정책', kind: 'select', options: opts(['SAFE', 'SAFE'], ['AGGRESSIVE', 'AGGRESSIVE']) },
      { key: 'allowDegraded', label: 'Degraded 허용', kind: 'boolean' },
      { key: 'schedulingMode', label: '배분 방식', kind: 'select', options: SCHEDULING_OPTIONS },
      { key: 'localWaitMs', label: '외부 전환 전 로컬 대기(ms)', kind: 'integer', min: 0, max: 60000, step: 100, visibleWhen: { key: 'schedulingMode', oneOf: ['LOCAL_FIRST_WAIT'] } },
      { key: 'maxWaitMs', label: '전체 최대 대기(ms)', kind: 'integer', min: 100, max: 60000, step: 100, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
      { key: 'maxQueuedRequests', label: '서비스 최대 대기 요청', kind: 'integer', min: 1, max: 500, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
      { key: 'maxQueuedPerKey', label: 'API 키별 최대 대기', kind: 'integer', min: 1, max: 500, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
      { key: 'maxQueuedBytes', label: '대기 본문 합계 한도(bytes)', kind: 'integer', min: 1024, max: 134217728, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
    ],
  },
  target: {
    type: 'target', label: 'Target', category: 'routing', icon: '↝', column: 6,
    singleton: false, deletable: true, paletteAddable: true,
    ports: [port('route', 'in', 'route', '라우팅', false), port('binding', 'out', 'binding', 'Deployment', false)],
    fields: [
      { key: '$binding', label: 'Deployment', kind: 'deployment-binding' },
      { key: 'priority', label: '우선순위(작을수록 먼저)', kind: 'integer', min: 1 },
      { key: 'weight', label: '가중치', kind: 'integer', min: 1 },
      { key: 'enabled', label: 'Target 활성화', kind: 'boolean' },
      { key: 'degraded', label: 'Degraded Target', kind: 'boolean' },
      { key: 'maxConcurrencyOverride', label: '동시성 재정의', kind: 'integer', min: 1, nullable: true },
      { key: 'followModelChanges', label: '모델 변경 자동 추적', kind: 'boolean' },
    ],
  },
  deployment: {
    type: 'deployment', label: '모델 Deployment', category: 'infra', icon: '◇', column: 7,
    singleton: false, deletable: true, paletteAddable: true,
    ports: [port('binding', 'in', 'binding', 'Deployment', true), port('host', 'out', 'host', '실행 위치', false)],
    fields: [],
  },
  endpoint: {
    type: 'endpoint', label: 'Local Runtime', category: 'infra', icon: '▤', column: 8,
    singleton: false, deletable: false, paletteAddable: false,
    ports: [port('host', 'in', 'host', '실행 위치', true)],
    fields: [],
  },
  provider: {
    type: 'provider', label: 'External Provider', category: 'infra', icon: '☁', column: 8,
    singleton: false, deletable: false, paletteAddable: false,
    ports: [port('host', 'in', 'host', '실행 위치', true)],
    fields: [],
  },
}

export const CONNECTION_RULES: ConnectionRule[] = [
  { fromType: 'project', fromPort: 'access', toType: 'service', toPort: 'access', kind: 'access', userEditable: true },
  { fromType: 'service', fromPort: 'request', toType: 'guardrail', toPort: 'request', kind: 'pipeline', userEditable: false },
  { fromType: 'guardrail', fromPort: 'request', toType: 'prompt', toPort: 'request', kind: 'pipeline', userEditable: false },
  { fromType: 'prompt', fromPort: 'request', toType: 'cache', toPort: 'request', kind: 'pipeline', userEditable: false },
  { fromType: 'cache', fromPort: 'request', toType: 'router', toPort: 'request', kind: 'pipeline', userEditable: false },
  { fromType: 'masker', fromPort: 'masker', toType: 'guardrail', toPort: 'masker', kind: 'masker', userEditable: true },
  { fromType: 'router', fromPort: 'targets', toType: 'target', toPort: 'route', kind: 'route', userEditable: false },
  { fromType: 'target', fromPort: 'binding', toType: 'deployment', toPort: 'binding', kind: 'binding', userEditable: true },
  { fromType: 'deployment', fromPort: 'host', toType: 'endpoint', toPort: 'host', kind: 'host', userEditable: false },
  { fromType: 'deployment', fromPort: 'host', toType: 'provider', toPort: 'host', kind: 'host', userEditable: false },
]

export function nodeSpec(type: NodeType): NodeTypeSpec {
  return NODE_TYPES[type]
}

/** Looks up a port by id. Ports with the same id may exist in both directions (guardrail.request): the first match wins. */
export function portSpec(type: NodeType, portId: string, direction?: 'in' | 'out'): PortSpec | undefined {
  return NODE_TYPES[type].ports.find((p) => p.id === portId && (direction === undefined || p.direction === direction))
}

export function targetNodeId(targetId: string): string { return `target:${targetId}` }
export function projectNodeId(projectId: string): string { return `project:${projectId}` }
export function deploymentNodeId(deploymentId: string): string { return `deployment:${deploymentId}` }
export function hostNodeIdFor(deployment: DeploymentInfo): string | null {
  if (deployment.external) return deployment.externalProviderId ? `provider:${deployment.externalProviderId}` : null
  return deployment.runtimeEndpointId ? `endpoint:${deployment.runtimeEndpointId}` : null
}
export function edgeId(from: PortRef, to: PortRef): string {
  return `${from.nodeId}/${from.portId}->${to.nodeId}/${to.portId}`
}

const HEALTHY = new Set(['HEALTHY', 'READY', 'ACTIVE', 'LOADED'])
const ERROR = new Set(['UNHEALTHY', 'FAILED', 'ERROR'])
const WARNING = new Set(['DRAINING', 'DEGRADED', 'RECOVERING', 'LOADING', 'STANDBY', 'SUSPECT'])
export function toneOf(enabled: boolean, healthStatus: string): HealthTone {
  if (!enabled) return 'muted'
  const status = String(healthStatus ?? '').toUpperCase()
  if (HEALTHY.has(status)) return 'healthy'
  if (ERROR.has(status)) return 'error'
  if (WARNING.has(status)) return 'warning'
  return 'unknown'
}

/** Props written to the five fixed singleton nodes (plus the masker) for a given config. Used by graph construction. */
export function singletonPropsFromConfig(config: FlowConfigState): Record<'service' | 'guardrail' | 'prompt' | 'cache' | 'router', Record<string, FlowPropValue>> {
  const s = config.service
  const c = config.scheduling
  return {
    service: {
      serviceKey: s.serviceKey,
      displayName: s.displayName,
      enabled: s.enabled,
      dataBoundaryPolicy: s.dataBoundaryPolicy,
      requiredCapabilitiesJson: s.requiredCapabilitiesJson,
      currency: s.currency,
      inputPricePerMillion: s.inputPricePerMillion,
      outputPricePerMillion: s.outputPricePerMillion,
    },
    guardrail: { guardrailAction: s.guardrailAction, maskPersonNames: s.maskPersonNames },
    prompt: {
      systemPromptTemplate: s.systemPromptTemplate ?? '',
      temperaturePolicy: s.temperaturePolicy,
      temperatureValue: s.temperatureValue,
      reasoningEffort: s.reasoningEffort,
      openAiFastMode: s.openAiFastMode,
    },
    cache: { responseCacheEnabled: s.responseCacheEnabled, responseCacheTtlSeconds: s.responseCacheTtlSeconds },
    router: {
      failoverPolicy: s.failoverPolicy,
      retryPolicy: s.retryPolicy,
      allowDegraded: s.allowDegraded,
      schedulingMode: c.mode,
      localWaitMs: c.localWaitMs,
      maxWaitMs: c.maxWaitMs,
      maxQueuedRequests: c.maxQueuedRequests,
      maxQueuedPerKey: c.maxQueuedPerKey,
      maxQueuedBytes: c.maxQueuedBytes,
    },
  }
}

/* ------------------------------------------------------------------ */
/* Describe helpers                                                    */
/* ------------------------------------------------------------------ */

const BOUNDARY_SHORT: Record<string, string> = {
  INTERNAL_ONLY: '사내 전용',
  APPROVED_EXTERNAL_NO_RETENTION: '외부 비보존',
  EXTERNAL_ALLOWED: '외부 허용',
}

function str(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}
function num(value: unknown): number {
  return value === null || value === undefined || value === '' ? Number.NaN : Number(value)
}
function optionLabel(spec: FieldSpec[], key: string, value: unknown): string {
  const f = spec.find((x) => x.key === key)
  const hit = f?.options?.find((o) => o.value === value)
  return hit ? hit.label : str(value)
}

function incomingEdges(graph: FlowGraph, nodeId: string, portId: string): FlowEdge[] {
  return graph.edges.filter((e) => e.to.nodeId === nodeId && e.to.portId === portId)
}
function outgoingEdges(graph: FlowGraph, nodeId: string, portId: string): FlowEdge[] {
  return graph.edges.filter((e) => e.from.nodeId === nodeId && e.from.portId === portId)
}
function nodeById(graph: FlowGraph, id: string): FlowNode | undefined {
  return graph.nodes.find((n) => n.id === id)
}

function deploymentOf(snapshot: ServiceFlowSnapshot, id: string | null): DeploymentInfo | undefined {
  if (!id) return undefined
  return snapshot.catalog.deployments.find((d) => d.id === id)
}

function deploymentTone(dep: DeploymentInfo | undefined): HealthTone {
  if (!dep) return 'error'
  if (dep.enabled && !dep.external && !dep.loaded) return 'warning'
  return toneOf(dep.enabled, dep.healthStatus)
}

/** Deployment node bound to a target node, if any. */
function boundDeploymentNode(graph: FlowGraph, targetNodeIdValue: string): FlowNode | undefined {
  const edge = outgoingEdges(graph, targetNodeIdValue, 'binding')[0]
  return edge ? nodeById(graph, edge.to.nodeId) : undefined
}

function targetShare(node: FlowNode, graph: FlowGraph): number {
  if (node.props.enabled === false) return 0
  const priority = num(node.props.priority)
  let total = 0
  for (const other of graph.nodes) {
    if (other.type !== 'target' || other.props.enabled === false) continue
    if (outgoingEdges(graph, other.id, 'binding').length === 0) continue
    if (num(other.props.priority) !== priority) continue
    const w = num(other.props.weight)
    if (Number.isFinite(w)) total += w
  }
  const weight = num(node.props.weight)
  if (!(total > 0) || !Number.isFinite(weight)) return 0
  return Math.round((weight / total) * 100)
}

function maskerDisplay(graph: FlowGraph, snapshot: ServiceFlowSnapshot): string {
  const masker = nodeById(graph, 'masker')
  const connected = masker && outgoingEdges(graph, 'masker', 'masker').length > 0
  const id = masker ? (masker.props.deploymentId as string | null) : null
  if (!connected || !id) return '내장 정규식 엔진'
  const info = snapshot.catalog.maskers.find((m) => m.deploymentId === id)
  return info?.displayName ?? deploymentOf(snapshot, id)?.displayName ?? id
}

export function describeNode(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): NodeView {
  const spec = NODE_TYPES[node.type]
  const view: NodeView = {
    title: spec.label,
    subtitle: '',
    typeLabel: spec.label,
    category: spec.category,
    icon: spec.icon,
    tone: 'unknown',
    badges: [],
    summary: [],
    locked: !spec.deletable,
  }
  const p = node.props
  switch (node.type) {
    case 'project': {
      const info = snapshot.catalog.projects.find((x) => x.id === node.refId)
      const connected = outgoingEdges(graph, node.id, 'access').length > 0
      view.title = info?.name || '알 수 없는 프로젝트'
      view.subtitle = info?.status ?? ''
      if (info && info.status !== 'ACTIVE') view.badges.push('중지됨')
      view.summary = [connected ? '접근 허용됨' : '미연결']
      if (info?.dataBoundaryPolicy) view.summary.push(BOUNDARY_SHORT[info.dataBoundaryPolicy] ?? info.dataBoundaryPolicy)
      view.tone = !info ? 'error' : info.status === 'ACTIVE' ? 'healthy' : 'muted'
      break
    }
    case 'service': {
      view.title = str(p.displayName).trim() ? str(p.displayName) : '(이름 없음)'
      view.subtitle = `model: ${str(p.serviceKey) || '새 서비스'}`
      if (p.enabled === false) view.badges.push('비활성')
      if (graph.serviceId === null) view.badges.push('초안')
      view.summary = [BOUNDARY_SHORT[str(p.dataBoundaryPolicy)] ?? str(p.dataBoundaryPolicy), `Prompt v${snapshot.promptVersion}`]
      view.tone = p.enabled === false ? 'muted' : 'healthy'
      break
    }
    case 'guardrail': {
      view.title = '가드레일 · DLP'
      view.subtitle = optionLabel(spec.fields, 'guardrailAction', p.guardrailAction)
      if (p.maskPersonNames === true) view.badges.push('성명 가명화')
      view.summary = [maskerDisplay(graph, snapshot)]
      if (snapshot.dataProtection) view.summary.push(`DLP ${snapshot.dataProtection.mode}`)
      view.tone = p.guardrailAction === 'ALLOW' ? 'muted' : 'healthy'
      break
    }
    case 'masker': {
      const id = (p.deploymentId as string | null) ?? null
      const info = id ? snapshot.catalog.maskers.find((m) => m.deploymentId === id) : undefined
      const dep = deploymentOf(snapshot, id)
      view.title = id ? (info?.displayName ?? dep?.displayName ?? id) : '워커 미선택'
      view.subtitle = info?.sourceName ?? ''
      if (id && info && !info.eligible) view.badges.push('사용 불가')
      view.summary = info?.trustReason ? [info.trustReason] : []
      view.tone = !id ? 'warning' : info && info.eligible ? 'healthy' : 'error'
      break
    }
    case 'prompt': {
      const len = str(p.systemPromptTemplate).trim().length
      view.title = '프롬프트 · 파라미터'
      view.subtitle = len > 0 ? `${len}자` : '시스템 프롬프트 없음'
      if (p.openAiFastMode === true) view.badges.push('Fast')
      view.summary = [`temperature ${str(p.temperaturePolicy)}`, `reasoning ${str(p.reasoningEffort)}`]
      view.tone = 'healthy'
      break
    }
    case 'cache': {
      view.title = '응답 캐시'
      view.subtitle = p.responseCacheEnabled === true ? `TTL ${str(p.responseCacheTtlSeconds)}초` : '사용 안 함'
      view.tone = p.responseCacheEnabled === true ? 'healthy' : 'muted'
      break
    }
    case 'router': {
      const active = outgoingEdges(graph, 'router', 'targets').filter((e) => {
        const t = nodeById(graph, e.to.nodeId)
        return !!t && t.props.enabled !== false && outgoingEdges(graph, t.id, 'binding').length > 0
      }).length
      view.title = '라우터'
      view.subtitle = `${str(p.failoverPolicy)} · ${str(p.retryPolicy)}`
      if (p.allowDegraded === true) view.badges.push('Degraded 허용')
      view.summary = [`배분 ${str(p.schedulingMode)}`, `활성 Target ${active}개`]
      view.tone = active === 0 ? 'warning' : 'healthy'
      break
    }
    case 'target': {
      const depNode = boundDeploymentNode(graph, node.id)
      const dep = deploymentOf(snapshot, depNode?.refId ?? null)
      view.title = !depNode ? '미연결 Target' : dep ? dep.displayName : '알 수 없는 Deployment'
      view.subtitle = `P${str(p.priority)} · W${str(p.weight)} (${targetShare(node, graph)}%)`
      if (p.enabled === false) view.badges.push('중지')
      if (p.degraded === true) view.badges.push('Degraded')
      if (p.followModelChanges === false && !(dep && dep.external)) view.badges.push('모델 고정')
      if (node.refId === null) view.badges.push('신규')
      view.summary = dep ? [dep.providerModelId] : []
      view.tone = !depNode ? 'error' : p.enabled === false ? 'muted' : deploymentTone(dep)
      break
    }
    case 'deployment': {
      const dep = deploymentOf(snapshot, node.refId)
      const bound = incomingEdges(graph, node.id, 'binding').length
      view.title = dep ? dep.displayName : '알 수 없는 Deployment'
      view.subtitle = dep?.providerModelId ?? ''
      if (dep?.external) view.badges.push('외부')
      if (dep && !dep.external && !dep.loaded) view.badges.push('미로드')
      if (dep && !dep.enabled) view.badges.push('비활성')
      view.summary = [`바인딩 Target ${bound}개`]
      if (dep?.contextLength) view.summary.push(`컨텍스트 ${dep.contextLength.toLocaleString('en-US')}`)
      view.tone = deploymentTone(dep)
      break
    }
    case 'endpoint': {
      const info = snapshot.catalog.endpoints.find((e) => e.id === node.refId)
      view.title = info?.displayName ?? '알 수 없는 Runtime'
      view.subtitle = info ? `${info.runtimeType} · ${info.addressLabel}` : ''
      if (info && !info.enabled) view.badges.push('비활성')
      view.tone = info ? toneOf(info.enabled, info.healthStatus) : 'error'
      break
    }
    case 'provider': {
      const info = snapshot.catalog.providers.find((e) => e.id === node.refId)
      view.title = info?.displayName ?? '알 수 없는 Provider'
      view.subtitle = info?.providerType ?? ''
      if (info && !info.apiKeyConfigured) view.badges.push('키 미설정')
      else if (info && info.zeroRetentionVerified) view.badges.push('무보존 확인')
      view.tone = !info ? 'error' : !info.apiKeyConfigured ? 'error' : toneOf(info.enabled, info.healthStatus)
      break
    }
  }
  view.summary = view.summary.slice(0, 2)
  return view
}

function yesNo(v: boolean): string { return v ? '예' : '아니오' }

export function nodeFacts(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): FactRow[] {
  const rows: FactRow[] = []
  switch (node.type) {
    case 'service':
      rows.push({ label: 'Service Key', value: str(node.props.serviceKey) || '새 서비스' })
      rows.push({ label: 'Prompt 버전', value: `v${snapshot.promptVersion}` })
      rows.push({ label: '불러온 시각', value: snapshot.loadedAt })
      break
    case 'guardrail': {
      const dp = snapshot.dataProtection
      if (!dp) {
        rows.push({ label: 'DLP 모드', value: '정보 없음' })
      } else {
        const detect = [
          dp.detectSecrets ? '비밀정보' : '', dp.detectPii ? '개인정보' : '', dp.detectFinancial ? '금융' : '',
          dp.detectConfidential ? '기밀' : '', dp.detectMedia ? '미디어' : '',
        ].filter(Boolean)
        rows.push({ label: 'DLP 모드', value: dp.mode })
        rows.push({ label: 'DLP 수준', value: dp.level })
        rows.push({ label: '외부 처리', value: dp.externalAction })
        rows.push({ label: '외부 Failover 허용', value: yesNo(dp.allowExternalFailover) })
        rows.push({ label: '탐지 항목', value: detect.length ? detect.join(', ') : '없음' })
      }
      break
    }
    case 'masker': {
      const id = (node.props.deploymentId as string | null) ?? null
      const info = id ? snapshot.catalog.maskers.find((m) => m.deploymentId === id) : undefined
      rows.push({ label: 'Deployment ID', value: id ?? '미선택' })
      if (info) {
        rows.push({ label: '모델 ID', value: info.providerModelId })
        rows.push({ label: '소스', value: `${info.sourceType} · ${info.sourceName}` })
        rows.push({ label: '적격 여부', value: info.eligible ? '적격' : '부적격' })
        rows.push({ label: '사유', value: info.trustReason })
      }
      break
    }
    case 'prompt':
      rows.push({ label: '프롬프트 길이', value: `${str(node.props.systemPromptTemplate).trim().length}자` })
      rows.push({ label: 'Prompt 버전', value: `v${snapshot.promptVersion}` })
      break
    case 'cache':
      rows.push({ label: '캐시', value: node.props.responseCacheEnabled === true ? '사용' : '사용 안 함' })
      break
    case 'router':
      rows.push({ label: '연결된 Target', value: `${outgoingEdges(graph, 'router', 'targets').length}개` })
      break
    case 'deployment': {
      const dep = deploymentOf(snapshot, node.refId)
      rows.push({ label: 'Deployment ID', value: node.refId ?? '' })
      if (dep) {
        const host = hostNodeIdFor(dep)
        rows.push({ label: '모델 ID', value: dep.providerModelId })
        rows.push({ label: '소스', value: dep.external ? '외부 Provider' : '로컬 Runtime' })
        rows.push({ label: '상태', value: `${dep.healthStatus}${dep.enabled ? '' : ' (비활성)'}` })
        rows.push({ label: '로드 상태', value: dep.external ? '해당 없음' : dep.loaded ? '로드됨' : '미로드' })
        rows.push({ label: '최대 동시성', value: String(dep.maxConcurrency) })
        rows.push({ label: '컨텍스트', value: dep.contextLength === null ? '정보 없음' : String(dep.contextLength) })
        if (host) rows.push({ label: '실행 위치', value: host })
      } else {
        rows.push({ label: '상태', value: '등록 정보를 찾을 수 없음' })
      }
      break
    }
    case 'endpoint': {
      const info = snapshot.catalog.endpoints.find((e) => e.id === node.refId)
      rows.push({ label: 'Runtime 유형', value: info?.runtimeType ?? '정보 없음' })
      rows.push({ label: '주소', value: info?.addressLabel ?? '정보 없음' })
      rows.push({ label: '상태', value: info ? `${info.healthStatus}${info.enabled ? '' : ' (비활성)'}` : '정보 없음' })
      break
    }
    case 'provider': {
      const info = snapshot.catalog.providers.find((e) => e.id === node.refId)
      rows.push({ label: 'Provider 유형', value: info?.providerType ?? '정보 없음' })
      rows.push({ label: 'API 키', value: info ? (info.apiKeyConfigured ? '설정됨' : '미설정') : '정보 없음' })
      rows.push({ label: '무보존 확인', value: info ? yesNo(info.zeroRetentionVerified) : '정보 없음' })
      rows.push({ label: '상태', value: info ? `${info.healthStatus}${info.enabled ? '' : ' (비활성)'}` : '정보 없음' })
      break
    }
    case 'project': {
      const info = snapshot.catalog.projects.find((x) => x.id === node.refId)
      rows.push({ label: '상태', value: info?.status ?? '정보 없음' })
      rows.push({ label: '데이터 경계', value: info?.dataBoundaryPolicy ?? '정보 없음' })
      break
    }
    case 'target':
      rows.push({ label: '서버 ID', value: node.refId ?? '생성 전' })
      break
  }
  return rows
}

const EDGE_TEXT: Record<string, { label: string; meaning: string }> = {
  access: { label: '접근 허용', meaning: 'project_service_access 행: 이 프로젝트의 API 키가 이 서비스의 model을 호출할 수 있음' },
  pipeline: { label: '요청 흐름', meaning: '요청 처리 순서(데이터 없음)' },
  masker: { label: '마스킹 워커', meaning: 'llm_service.masker_deployment_id: 가드레일이 이 Deployment로 마스킹함' },
  route: { label: 'Target', meaning: 'service_target.service_id: 이 서비스의 Target' },
  binding: { label: 'Deployment 바인딩', meaning: 'service_target.deployment_id: Target이 호출할 Deployment' },
  host: { label: '실행 위치', meaning: 'model_deployment 의 Runtime/Provider 소속(읽기 전용)' },
}
export function describeEdge(edge: FlowEdge): { label: string; meaning: string } {
  const hit = EDGE_TEXT[edge.kind]
  return hit ? { ...hit } : { label: edge.kind, meaning: '' }
}
