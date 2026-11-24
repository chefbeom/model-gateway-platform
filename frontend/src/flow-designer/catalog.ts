import type {
  AddableNodeType,
  ConnectionRule,
  DataBoundaryPolicy,
  DeploymentInfo,
  FactRow,
  FieldSpec,
  FlowCatalog,
  FlowConfigState,
  FlowEdge,
  FlowGraph,
  FlowNode,
  FlowPropValue,
  GuardrailAction,
  GuardrailPreset,
  GuardrailPresetId,
  HealthTone,
  ModelAvailability,
  NodeType,
  NodeTypeSpec,
  NodeView,
  PolicySignal,
  PortRef,
  PortSpec,
  ProjectInfo,
  SchedulingField,
  SchedulingState,
  ServiceField,
  ServiceFlowSnapshot,
  ServiceState,
  TargetField,
  TargetState,
  WorkflowTemplate,
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
  start: 'start',
  guardrail: 'guardrail',
  masker: 'masker',
  prompt: 'prompt',
  params: 'params',
  cache: 'cache',
  router: 'router',
} as const

/** Canonical server-order rank of a model-specific prompt (it sits between the router and the LLM). */
export const PROMPT_BRANCH_RANK = 70

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

/** Ordered field lists shared by compile/diff/validate. */
export const SERVICE_FIELDS: ServiceField[] = [
  'serviceKey', 'displayName', 'enabled', 'dataBoundaryPolicy', 'requiredCapabilitiesJson', 'currency',
  'inputPricePerMillion', 'outputPricePerMillion', 'guardrailAction', 'maskerDeploymentId', 'maskPersonNames',
  'systemPromptTemplate', 'temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode',
  'responseCacheEnabled', 'responseCacheTtlSeconds', 'failoverPolicy', 'retryPolicy', 'allowDegraded',
]
export const SCHEDULING_FIELDS: SchedulingField[] = ['mode', 'localWaitMs', 'maxWaitMs', 'maxQueuedRequests', 'maxQueuedBytes', 'maxQueuedPerKey']
export const TARGET_FIELDS: TargetField[] = ['deploymentId', 'priority', 'weight', 'degraded', 'enabled', 'maxConcurrencyOverride', 'followModelChanges', 'systemPromptTemplate']

export const FIELD_LABELS: Record<string, string> = {
  serviceKey: '호출 이름 (model 값)',
  displayName: '표시 이름',
  enabled: '워크플로우 사용',
  dataBoundaryPolicy: '외부 전송 범위',
  requiredCapabilitiesJson: '필수 Capability',
  currency: '통화',
  inputPricePerMillion: '입력 단가 / 1M',
  outputPricePerMillion: '출력 단가 / 1M',
  guardrailAction: '민감정보 처리',
  maskerDeploymentId: '마스킹 워커 모델',
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
  deploymentId: '모델',
  priority: '우선순위',
  weight: '가중치',
  degraded: 'Degraded LLM',
  maxConcurrencyOverride: '동시성 재정의',
  followModelChanges: '모델 변경 자동 추적',
  'target.enabled': 'LLM 사용',
  'target.systemPromptTemplate': '모델별 시스템 프롬프트',
}

export function fieldLabel(entity: 'service' | 'scheduling' | 'target', field: string): string {
  if (entity === 'target') return FIELD_LABELS[`target.${field}`] ?? FIELD_LABELS[field] ?? field
  return FIELD_LABELS[field] ?? field
}

/* ------------------------------------------------------------------ */
/* Guardrail presets and workflow templates                            */
/* ------------------------------------------------------------------ */

export const GUARDRAIL_PRESETS: GuardrailPreset[] = [
  { id: 'mask-restore', label: '민감정보 마스킹 후 외부 전송', description: '외부 LLM에는 가린 문장을 보내고 응답에서 원래 값으로 되돌립니다', dataBoundaryPolicy: 'EXTERNAL_ALLOWED', guardrailAction: 'REDACT_RESTORE' },
  { id: 'local-sensitive', label: '민감정보는 사내 LLM만', description: '민감정보가 있는 요청은 로컬 LLM으로만 보냅니다', dataBoundaryPolicy: 'EXTERNAL_ALLOWED', guardrailAction: 'LOCAL_ONLY' },
  { id: 'no-retention', label: '무보존 외부만 + 마스킹', description: '외부는 무보존 확인된 곳만 쓰고 민감정보는 가립니다', dataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', guardrailAction: 'REDACT_RESTORE' },
  { id: 'internal-only', label: '사내 LLM 전용', description: '모든 요청을 사내 LLM에서만 처리합니다', dataBoundaryPolicy: 'INTERNAL_ONLY', guardrailAction: 'LOCAL_ONLY' },
  { id: 'block', label: '민감정보 요청 차단', description: '민감정보가 있는 요청을 거부합니다', dataBoundaryPolicy: 'EXTERNAL_ALLOWED', guardrailAction: 'BLOCK' },
]

export function presetOf(props: Record<string, FlowPropValue>): GuardrailPresetId | null {
  const hit = GUARDRAIL_PRESETS.find((p) => p.dataBoundaryPolicy === props.dataBoundaryPolicy && p.guardrailAction === props.guardrailAction)
  return hit ? hit.id : null
}

export const WORKFLOW_TEMPLATES: WorkflowTemplate[] = [
  { id: 'basic', label: '기본', description: '시작 → 프로젝트 → LLM' },
  { id: 'local-first', label: '로컬 우선 + 외부 Failover', description: '민감정보는 가려서 외부로 보내고, 로컬 LLM을 먼저 씁니다' },
  { id: 'internal-only', label: '사내 전용', description: '외부 전송 없이 로컬 LLM에서만 처리합니다' },
]

/* ------------------------------------------------------------------ */
/* Node type specs                                                     */
/* ------------------------------------------------------------------ */

const opts = (...pairs: Array<[string, string]>) => pairs.map(([value, label]) => ({ value, label }))

const BOUNDARY_OPTIONS = opts(
  ['INTERNAL_ONLY', '사내 LLM만'],
  ['APPROVED_EXTERNAL_NO_RETENTION', '외부는 무보존만'],
  ['EXTERNAL_ALLOWED', '외부 LLM 허용'],
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
  ['IMMEDIATE', '즉시 다음 LLM'],
  ['LOCAL_FIRST_WAIT', '로컬 우선 대기'],
  ['LOCAL_ONLY_QUEUE', '로컬 전용 대기'],
)

const port = (id: string, direction: 'in' | 'out', type: PortSpec['type'], label: string, multiple: boolean): PortSpec => ({ id, direction, type, label, multiple })
const FLOW_IN = (multiple = true) => port('in', 'in', 'flow', '요청', multiple)
const FLOW_OUT = (multiple = false) => port('out', 'out', 'flow', '요청', multiple)

const WAITING_MODES: FlowPropValue[] = ['LOCAL_FIRST_WAIT', 'LOCAL_ONLY_QUEUE']

/** Help text for the two prompt scopes (the node spec carries the common one). */
export const PROMPT_HELP_COMMON = '모든 LLM에 적용됩니다. 바꾸면 프롬프트 버전이 올라갑니다.'
export const PROMPT_HELP_MODEL = '이 LLM에만 적용됩니다(공통 프롬프트 뒤에 붙음).'

export const NODE_TYPES: Record<NodeType, NodeTypeSpec> = {
  start: {
    type: 'start', label: '시작 · 앱 호출', description: '앱이 호출하는 진입점(model 값)과 워크플로우 정체성', keywords: 'start entry api model 시작 앱 호출 진입점',
    category: 'entry', icon: '▶', rank: 0, singleton: true, deletable: false, paletteAddable: false, advanced: false, derived: false,
    ports: [port('out', 'out', 'flow', '앱 요청', true)],
    fields: [
      { key: 'serviceKey', label: '호출 이름 (model 값)', kind: 'text', newServiceOnly: true, maxLength: 120, placeholder: 'text-pro' },
      { key: 'displayName', label: '표시 이름', kind: 'text', maxLength: 200 },
      { key: 'enabled', label: '워크플로우 사용', kind: 'boolean' },
      { key: 'requiredCapabilitiesJson', label: '필수 Capability', kind: 'capabilities', maxLength: 2000, advanced: true },
      { key: 'currency', label: '통화', kind: 'select', options: opts(['KRW', 'KRW'], ['USD', 'USD']), advanced: true },
      { key: 'inputPricePerMillion', label: '입력 단가 · 1M', kind: 'number', min: 0, step: 0.000001, advanced: true },
      { key: 'outputPricePerMillion', label: '출력 단가 · 1M', kind: 'number', min: 0, step: 0.000001, advanced: true },
    ],
  },
  project: {
    type: 'project', label: '프로젝트', description: '이 워크플로우를 호출할 수 있는 프로젝트', keywords: 'project 프로젝트 접근 앱 키',
    category: 'entry', icon: '⌘', rank: 10, singleton: false, deletable: true, paletteAddable: true, advanced: false, derived: false,
    ports: [FLOW_IN(false), port('out', 'out', 'flow', '요청', false)],
    fields: [{ key: '$access', label: '이 워크플로우 호출 허용', kind: 'access-toggle' }],
  },
  policy: {
    type: 'policy', label: '보호 정책(상속)', description: '프로젝트 설정에서 상속되는 읽기 전용 보호 정책', keywords: 'policy 보호 정책 상속 프로젝트',
    category: 'guard', icon: '⛨', rank: 15, singleton: false, deletable: false, paletteAddable: false, advanced: false, derived: true,
    ports: [FLOW_IN(false), port('out', 'out', 'flow', '요청', false)],
    fields: [],
  },
  guardrail: {
    type: 'guardrail', label: '외부 노출 방지 가드레일', description: '데이터 경계와 민감정보 처리 정책', keywords: 'guardrail dlp 가드레일 외부 노출 방지 민감정보 데이터 경계 보호',
    category: 'guard', icon: '⛨', rank: 20, singleton: true, deletable: true, paletteAddable: true, advanced: false, derived: false,
    ports: [FLOW_IN(true), port('masker', 'in', 'masker', '마스커', false), FLOW_OUT(false)],
    fields: [
      { key: '$preset', label: '보호 수준', kind: 'guardrail-preset', basicOnly: true },
      { key: 'dataBoundaryPolicy', label: '외부 전송 범위', kind: 'select', options: BOUNDARY_OPTIONS, advanced: true },
      { key: 'guardrailAction', label: '민감정보 처리', kind: 'select', options: GUARDRAIL_OPTIONS, advanced: true },
      { key: 'maskPersonNames', label: '한국어 성명 가명화', kind: 'boolean', advanced: true },
    ],
  },
  masker: {
    type: 'masker', label: '마스킹 워커', description: '가드레일이 민감정보를 가릴 때 쓰는 모델', keywords: 'masker 마스킹 워커 가명화 마스커',
    category: 'guard', icon: '◍', rank: 21, singleton: true, deletable: true, paletteAddable: true, advanced: true, derived: false,
    ports: [port('masker', 'out', 'masker', '마스커', false)],
    fields: [{ key: 'deploymentId', label: '마스킹 워커 모델', kind: 'masker-select' }],
  },
  prompt: {
    type: 'prompt', label: '시스템 프롬프트', description: '요청 앞에 붙는 시스템 지시문(공통 또는 LLM별)', keywords: 'prompt system 프롬프트 시스템 지시문',
    category: 'prompt', icon: '✎', rank: 30, singleton: false, deletable: true, paletteAddable: true, advanced: false, derived: false,
    ports: [FLOW_IN(true), FLOW_OUT(false)],
    fields: [{ key: 'systemPromptTemplate', label: '시스템 프롬프트', kind: 'textarea', maxLength: 16000, help: PROMPT_HELP_COMMON }],
  },
  params: {
    type: 'params', label: '생성 파라미터', description: 'Temperature·추론 수준·Fast mode', keywords: 'params temperature reasoning fast 생성 파라미터 추론',
    category: 'prompt', icon: '≋', rank: 50, singleton: true, deletable: true, paletteAddable: true, advanced: true, derived: false,
    ports: [FLOW_IN(true), FLOW_OUT(false)],
    fields: [
      { key: 'temperaturePolicy', label: 'Temperature 처리', kind: 'select', options: TEMPERATURE_OPTIONS },
      { key: 'temperatureValue', label: '고정 Temperature (0–2)', kind: 'number', min: 0, max: 2, step: 0.1, nullable: true, visibleWhen: { key: 'temperaturePolicy', oneOf: ['FIXED'] } },
      { key: 'reasoningEffort', label: '추론 수준 · 외부 API', kind: 'select', options: REASONING_OPTIONS },
      { key: 'openAiFastMode', label: 'Fast mode · 외부 API', kind: 'boolean' },
    ],
  },
  cache: {
    type: 'cache', label: '응답 캐시', description: '같은 질문의 응답을 일정 시간 재사용', keywords: 'cache ttl 응답 캐시 동일 질문',
    category: 'cache', icon: '⟲', rank: 40, singleton: true, deletable: true, paletteAddable: true, advanced: false, derived: false,
    ports: [FLOW_IN(true), FLOW_OUT(false)],
    fields: [{ key: 'responseCacheTtlSeconds', label: '캐시 유지 시간(초)', kind: 'integer', min: 10, max: 86400 }],
  },
  router: {
    type: 'router', label: '라우터 · Failover', description: 'LLM이 여러 개일 때 배분·Failover·대기열 정책', keywords: 'router failover retry scheduling 라우터 배분 대기열 재시도',
    category: 'routing', icon: '⑂', rank: 60, singleton: true, deletable: true, paletteAddable: true, advanced: false, derived: false,
    ports: [FLOW_IN(true), FLOW_OUT(true)],
    fields: [
      { key: 'failoverPolicy', label: 'Failover 정책', kind: 'select', options: opts(['STRICT', 'STRICT'], ['COMPATIBLE', 'COMPATIBLE'], ['DEGRADED', 'DEGRADED']) },
      { key: 'schedulingMode', label: '배분 방식', kind: 'select', options: SCHEDULING_OPTIONS },
      { key: 'retryPolicy', label: 'Retry 정책', kind: 'select', options: opts(['SAFE', 'SAFE'], ['AGGRESSIVE', 'AGGRESSIVE']), advanced: true },
      { key: 'allowDegraded', label: 'Degraded 허용', kind: 'boolean', advanced: true },
      { key: 'localWaitMs', label: '외부 전환 전 로컬 대기(ms)', kind: 'integer', min: 0, max: 60000, step: 100, advanced: true, visibleWhen: { key: 'schedulingMode', oneOf: ['LOCAL_FIRST_WAIT'] } },
      { key: 'maxWaitMs', label: '전체 최대 대기(ms)', kind: 'integer', min: 100, max: 60000, step: 100, advanced: true, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
      { key: 'maxQueuedRequests', label: '서비스 최대 대기 요청', kind: 'integer', min: 1, max: 500, advanced: true, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
      { key: 'maxQueuedPerKey', label: 'API 키별 최대 대기', kind: 'integer', min: 1, max: 500, advanced: true, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
      { key: 'maxQueuedBytes', label: '대기 본문 합계 한도(bytes)', kind: 'integer', min: 1024, max: 134217728, advanced: true, visibleWhen: { key: 'schedulingMode', oneOf: WAITING_MODES } },
    ],
  },
  llm: {
    type: 'llm', label: 'LLM', description: '요청을 처리하는 모델(로컬 또는 외부)', keywords: 'llm model deployment target 모델 로컬 외부',
    category: 'llm', icon: '◆', rank: 80, singleton: false, deletable: true, paletteAddable: true, advanced: false, derived: false,
    ports: [FLOW_IN(true), port('host', 'out', 'host', '실행 위치', false)],
    fields: [
      { key: '$model', label: '모델 교체', kind: 'model-replace' },
      { key: 'enabled', label: '사용', kind: 'boolean' },
      { key: 'priority', label: '우선순위(작을수록 먼저)', kind: 'integer', min: 1 },
      { key: 'weight', label: '가중치', kind: 'integer', min: 1, advanced: true },
      { key: 'degraded', label: 'Degraded', kind: 'boolean', advanced: true },
      { key: 'maxConcurrencyOverride', label: '동시성 재정의', kind: 'integer', min: 1, nullable: true, advanced: true },
      { key: 'followModelChanges', label: '모델 변경 자동 추적', kind: 'boolean', advanced: true },
    ],
  },
  endpoint: {
    type: 'endpoint', label: '로컬 Runtime', description: 'LLM이 실행되는 로컬 Runtime(자동 표시)', keywords: 'endpoint runtime 로컬 실행 위치',
    category: 'infra', icon: '▤', rank: 90, singleton: false, deletable: false, paletteAddable: false, advanced: true, derived: true,
    ports: [port('in', 'in', 'host', '실행 위치', true)],
    fields: [],
  },
  provider: {
    type: 'provider', label: '외부 Provider', description: 'LLM을 제공하는 외부 Provider(자동 표시)', keywords: 'provider external 외부 실행 위치',
    category: 'infra', icon: '☁', rank: 90, singleton: false, deletable: false, paletteAddable: false, advanced: true, derived: true,
    ports: [port('in', 'in', 'host', '실행 위치', true)],
    fields: [],
  },
}

const FLOW_TARGET_TYPES: NodeType[] = ['guardrail', 'prompt', 'params', 'cache', 'router', 'llm']
const rules = (): ConnectionRule[] => {
  const out: ConnectionRule[] = []
  out.push({ fromType: 'start', fromPort: 'out', toType: 'project', toPort: 'in', kind: 'entry', userEditable: true })
  out.push({ fromType: 'project', fromPort: 'out', toType: 'policy', toPort: 'in', kind: 'policy', userEditable: false })
  for (const t of FLOW_TARGET_TYPES) out.push({ fromType: 'project', fromPort: 'out', toType: t, toPort: 'in', kind: 'access', userEditable: false })
  for (const t of FLOW_TARGET_TYPES) out.push({ fromType: 'policy', fromPort: 'out', toType: t, toPort: 'in', kind: 'access', userEditable: false })
  for (const from of ['guardrail', 'prompt', 'params', 'cache'] as NodeType[]) {
    for (const t of FLOW_TARGET_TYPES) out.push({ fromType: from, fromPort: 'out', toType: t, toPort: 'in', kind: 'flow', userEditable: true })
  }
  out.push({ fromType: 'router', fromPort: 'out', toType: 'prompt', toPort: 'in', kind: 'route', userEditable: true })
  out.push({ fromType: 'router', fromPort: 'out', toType: 'llm', toPort: 'in', kind: 'route', userEditable: true })
  out.push({ fromType: 'masker', fromPort: 'masker', toType: 'guardrail', toPort: 'masker', kind: 'masker', userEditable: true })
  out.push({ fromType: 'llm', fromPort: 'host', toType: 'endpoint', toPort: 'in', kind: 'host', userEditable: false })
  out.push({ fromType: 'llm', fromPort: 'host', toType: 'provider', toPort: 'in', kind: 'host', userEditable: false })
  return out
}
export const CONNECTION_RULES: ConnectionRule[] = rules()

export function nodeSpec(type: NodeType): NodeTypeSpec {
  return NODE_TYPES[type]
}

/** Looks up a port by id. Ports with the same id may exist in both directions: pass `direction` to disambiguate. */
export function portSpec(type: NodeType, portId: string, direction?: 'in' | 'out'): PortSpec | undefined {
  return NODE_TYPES[type].ports.find((p) => p.id === portId && (direction === undefined || p.direction === direction))
}

export function projectNodeId(projectId: string): string { return `project:${projectId}` }
export function policyNodeId(projectId: string): string { return `policy:${projectId}` }
export function llmNodeId(deploymentId: string): string { return `deployment:${deploymentId}` }
export function deploymentNodeId(deploymentId: string): string { return llmNodeId(deploymentId) }
export function branchPromptNodeId(deploymentId: string): string { return `prompt:${deploymentId}` }
export function branchPromptDeploymentId(nodeId: string): string | null {
  return nodeId.startsWith('prompt:') ? nodeId.slice('prompt:'.length) : null
}
export function targetNodeId(targetId: string): string { return `target:${targetId}` }
/** Domain key of an LLM node (TargetState.key). */
export function targetKeyOf(node: FlowNode): string {
  if (node.targetId) return targetNodeId(node.targetId)
  const deploymentId = node.refId ?? (node.id.startsWith('deployment:') ? node.id.slice('deployment:'.length) : node.id)
  return `target:new-${deploymentId}`
}
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

/** §9.3: palette availability of a catalog model. */
export function modelAvailability(d: DeploymentInfo, catalog: FlowCatalog): { availability: ModelAvailability; reason: string | null } {
  if (!d.enabled) return { availability: 'inactive', reason: '비활성 모델' }
  const endpoint = !d.external && d.runtimeEndpointId ? catalog.endpoints.find((e) => e.id === d.runtimeEndpointId) : undefined
  const provider = d.external && d.externalProviderId ? catalog.providers.find((p) => p.id === d.externalProviderId) : undefined
  if (!d.external && !d.loaded) return { availability: 'inactive', reason: '미로드' }
  if (endpoint && !endpoint.enabled) return { availability: 'inactive', reason: 'Runtime 비활성' }
  if (provider && !provider.enabled) return { availability: 'inactive', reason: 'Provider 비활성' }
  if (provider && !provider.apiKeyConfigured) return { availability: 'inactive', reason: 'API 키 미설정' }
  const bad = (tone: HealthTone) => tone === 'warning' || tone === 'error'
  if (bad(toneOf(d.enabled, d.healthStatus)) || (endpoint && bad(toneOf(endpoint.enabled, endpoint.healthStatus)))) {
    return { availability: 'degraded', reason: '상태 확인 필요' }
  }
  return { availability: 'active', reason: null }
}

/* ------------------------------------------------------------------ */
/* Policy signals (§6.9)                                               */
/* ------------------------------------------------------------------ */

export function projectSignals(project: ProjectInfo | undefined): PolicySignal[] {
  if (!project) return []
  const signals: PolicySignal[] = []
  const blocked = project.externalAiBlocked === true
  if (blocked) signals.push({ code: 'PROJECT_LOCAL_ONLY', label: '외부 AI 전송 금지(로컬 전용)', blocksExternal: true })
  if (!blocked) {
    let strictest: DataBoundaryPolicy | null = null
    for (const value of [project.dataBoundaryPolicy ?? null, project.maxDataBoundaryPolicy ?? null]) {
      if (value === null || value === undefined || !(value in BOUNDARY_RANK)) continue
      if (strictest === null || BOUNDARY_RANK[value] < BOUNDARY_RANK[strictest]) strictest = value
    }
    if (strictest === 'INTERNAL_ONLY') signals.push({ code: 'PROJECT_BOUNDARY', label: '데이터 경계: 사내 GPU 전용', blocksExternal: true })
    else if (strictest === 'APPROVED_EXTERNAL_NO_RETENTION') signals.push({ code: 'PROJECT_BOUNDARY', label: '데이터 경계: 외부는 무보존만', blocksExternal: false })
  }
  const dlp = project.dlp
  if (dlp && dlp.mode === 'ENFORCE' && dlp.externalAction !== 'ALLOW') {
    const label = dlp.externalAction === 'LOCAL_ONLY' ? '민감정보: 사내 LLM만' : dlp.externalAction === 'BLOCK' ? '민감정보: 차단' : '민감정보: 마스킹 후 전송'
    signals.push({ code: 'PROJECT_DLP', label, blocksExternal: false })
  }
  return signals
}

export function isProtectedGuardrail(base: ServiceFlowSnapshot): boolean {
  return base.serviceId !== null && (base.config.service.dataBoundaryPolicy !== 'EXTERNAL_ALLOWED' || base.config.service.guardrailAction !== 'ALLOW')
}

/* ------------------------------------------------------------------ */
/* Config <-> node props                                               */
/* ------------------------------------------------------------------ */

/** §5.5: which optional nodes a server configuration implies. */
export function presenceOf(config: FlowConfigState): { guardrail: boolean; masker: boolean; prompt: boolean; params: boolean; cache: boolean; router: boolean } {
  const s = config.service
  const masker = s.maskerDeploymentId !== null && s.maskerDeploymentId !== undefined
  return {
    guardrail: s.dataBoundaryPolicy !== 'EXTERNAL_ALLOWED' || s.guardrailAction !== 'ALLOW' || masker,
    masker,
    prompt: s.systemPromptTemplate !== null && s.systemPromptTemplate !== undefined,
    params: s.temperaturePolicy !== 'REQUEST' || s.reasoningEffort !== 'REQUEST' || s.openAiFastMode,
    cache: s.responseCacheEnabled,
    router: config.targets.length >= 2 || s.failoverPolicy !== 'STRICT' || s.retryPolicy !== 'SAFE' || s.allowDegraded || config.scheduling.mode !== 'IMMEDIATE',
  }
}

export function nodePropsFromConfig(config: FlowConfigState): Record<'start' | 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router', Record<string, FlowPropValue>> {
  const s = config.service
  const c = config.scheduling
  return {
    start: {
      serviceKey: s.serviceKey,
      displayName: s.displayName,
      enabled: s.enabled,
      requiredCapabilitiesJson: s.requiredCapabilitiesJson,
      currency: s.currency,
      inputPricePerMillion: s.inputPricePerMillion,
      outputPricePerMillion: s.outputPricePerMillion,
    },
    guardrail: { dataBoundaryPolicy: s.dataBoundaryPolicy, guardrailAction: s.guardrailAction, maskPersonNames: s.maskPersonNames },
    masker: { deploymentId: s.maskerDeploymentId },
    prompt: { systemPromptTemplate: s.systemPromptTemplate ?? '' },
    params: {
      temperaturePolicy: s.temperaturePolicy,
      temperatureValue: s.temperatureValue,
      reasoningEffort: s.reasoningEffort,
      openAiFastMode: s.openAiFastMode,
    },
    cache: { responseCacheTtlSeconds: s.responseCacheTtlSeconds },
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

export function llmPropsFromTarget(target: TargetState): Record<string, FlowPropValue> {
  return {
    priority: target.priority,
    weight: target.weight,
    enabled: target.enabled,
    degraded: target.degraded,
    maxConcurrencyOverride: target.maxConcurrencyOverride,
    followModelChanges: target.followModelChanges,
  }
}

function num(value: unknown): number {
  return value === null || value === undefined || value === '' ? Number.NaN : Number(value)
}

/** §5.4: props of a freshly added node. */
export function defaultNodeProps(type: AddableNodeType, graph: FlowGraph, snapshot: ServiceFlowSnapshot, refId: string | null): Record<string, FlowPropValue> {
  switch (type) {
    case 'guardrail':
      return { dataBoundaryPolicy: 'EXTERNAL_ALLOWED', guardrailAction: 'REDACT_RESTORE', maskPersonNames: true }
    case 'masker':
      return { deploymentId: refId }
    case 'prompt':
      return { systemPromptTemplate: '' }
    case 'params':
      return { temperaturePolicy: 'REQUEST', temperatureValue: null, reasoningEffort: 'REQUEST', openAiFastMode: false }
    case 'cache':
      return { responseCacheTtlSeconds: Math.max(10, snapshot.config.service.responseCacheTtlSeconds) }
    case 'router':
      return { ...nodePropsFromConfig(snapshot.config).router }
    case 'llm': {
      let max = 0
      for (const n of graph.nodes) {
        if (n.type !== 'llm') continue
        const p = num(n.props.priority)
        if (Number.isFinite(p)) max = Math.max(max, p)
      }
      const external = refId ? snapshot.catalog.deployments.find((d) => d.id === refId)?.external === true : false
      return { priority: max + 1, weight: 100, enabled: true, degraded: false, maxConcurrencyOverride: null, followModelChanges: !external }
    }
    default:
      return {}
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

/** Short labels of the sensitive-information handling actions (also used by validation messages). */
export const GUARDRAIL_ACTION_SHORT: Record<string, string> = {
  ALLOW: '허용',
  REDACT_RESTORE: '마스킹 후 복원',
  REDACT: '마스킹(가명 유지)',
  LOCAL_ONLY: '사내 LLM만',
  BLOCK: '차단',
}

function str(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}
function optionLabel(spec: FieldSpec[], key: string, value: unknown): string {
  const f = spec.find((x) => x.key === key)
  const hit = f?.options?.find((o) => o.value === value)
  return hit ? hit.label : str(value)
}

function outgoingEdges(graph: FlowGraph, nodeId: string, portId?: string): FlowEdge[] {
  return graph.edges.filter((e) => e.from.nodeId === nodeId && (portId === undefined || e.from.portId === portId))
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

function llmShare(node: FlowNode, graph: FlowGraph): number {
  if (node.props.enabled === false) return 0
  const priority = num(node.props.priority)
  let total = 0
  for (const other of graph.nodes) {
    if (other.type !== 'llm' || other.props.enabled === false) continue
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

function llmDisplayName(graph: FlowGraph, snapshot: ServiceFlowSnapshot, deploymentId: string): string {
  const node = nodeById(graph, llmNodeId(deploymentId))
  const dep = deploymentOf(snapshot, node?.refId ?? deploymentId)
  return dep ? dep.displayName : '알 수 없는 모델'
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
    locked: !spec.deletable && node.type !== 'guardrail',
    protected: false,
    lockReason: null,
    promptScope: null,
  }
  const p = node.props
  switch (node.type) {
    case 'start': {
      const accessCount = graph.edges.filter((e) => e.kind === 'entry').length
      view.title = str(p.displayName).trim() ? str(p.displayName) : '(이름 없음)'
      view.subtitle = `model: ${str(p.serviceKey) || '새 워크플로우'}`
      if (p.enabled === false) view.badges.push('사용 안 함')
      if (graph.serviceId === null) view.badges.push('초안')
      if (snapshot.projectsHidden === true) view.badges.push('프로젝트 숨김')
      const org = snapshot.orgDataProtection
      if (org && org.mode === 'ENFORCE' && org.externalAction !== 'ALLOW') view.badges.push('상위 정책')
      view.summary = [`허용 프로젝트 ${accessCount}개`, `Prompt v${snapshot.promptVersion}`]
      view.tone = p.enabled === false ? 'muted' : 'healthy'
      view.locked = true
      view.lockReason = '시작 노드는 항상 있습니다.'
      break
    }
    case 'project': {
      const info = snapshot.catalog.projects.find((x) => x.id === node.refId)
      const granted = graph.edges.some((e) => e.kind === 'entry' && e.to.nodeId === node.id)
      view.title = info?.name || '알 수 없는 프로젝트'
      view.subtitle = info?.status ?? ''
      if (info && info.status !== 'ACTIVE') view.badges.push('중지됨')
      if (projectSignals(info).length > 0) view.badges.push('보호 정책')
      view.summary = [granted ? '호출 허용' : '미연결']
      const boundary = info?.dataBoundaryPolicy
      if (boundary) view.summary.push(BOUNDARY_SHORT[boundary] ?? boundary)
      view.tone = !info ? 'error' : info.status === 'ACTIVE' ? 'healthy' : 'muted'
      break
    }
    case 'policy': {
      const info = snapshot.catalog.projects.find((x) => x.id === node.refId)
      const signals = projectSignals(info)
      view.title = `보호 정책 · ${info?.name ?? '알 수 없는 프로젝트'}`
      view.subtitle = signals[0]?.label ?? ''
      view.badges.push('상속')
      view.summary = signals.slice(0, 2).map((s) => s.label)
      view.tone = 'warning'
      view.locked = true
      view.lockReason = '프로젝트 설정에서 관리하는 정책입니다. 프로젝트 화면에서 바꾸세요.'
      break
    }
    case 'guardrail': {
      const protectedNow = isProtectedGuardrail(snapshot)
      const preset = presetOf(p)
      view.title = '외부 노출 방지 가드레일'
      view.subtitle = preset ? (GUARDRAIL_PRESETS.find((x) => x.id === preset)?.label ?? '사용자 지정') : '사용자 지정'
      const boundary = str(p.dataBoundaryPolicy)
      if (boundary) view.badges.push(BOUNDARY_SHORT[boundary] ?? boundary)
      if (p.maskPersonNames === true) view.badges.push('성명 가명화')
      view.summary = [maskerDisplay(graph, snapshot), `민감정보: ${optionLabel(spec.fields, 'guardrailAction', p.guardrailAction)}`]
      const noEffect = p.dataBoundaryPolicy === 'EXTERNAL_ALLOWED' && p.guardrailAction === 'ALLOW' && !graph.edges.some((e) => e.kind === 'masker' && e.to.nodeId === node.id)
      view.tone = noEffect ? 'muted' : 'healthy'
      view.protected = protectedNow
      view.lockReason = protectedNow ? '서버에 외부 노출 방지 정책이 적용되어 있습니다. 삭제하려면 [보호 해제 후 삭제]를 사용하세요.' : null
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
      const model = node.id !== 'prompt'
      const len = str(p.systemPromptTemplate).trim().length
      view.title = '시스템 프롬프트'
      view.promptScope = model ? 'model' : 'common'
      view.subtitle = model ? `모델별 · ${llmDisplayName(graph, snapshot, node.id.slice('prompt:'.length))}` : '공통 · 모든 LLM'
      view.summary = [len > 0 ? `${len}자` : '내용 없음']
      view.tone = len > 0 ? 'healthy' : 'muted'
      break
    }
    case 'params': {
      view.title = '생성 파라미터'
      view.subtitle = `temperature ${str(p.temperaturePolicy)}`
      if (p.openAiFastMode === true) view.badges.push('Fast')
      view.summary = [`reasoning ${str(p.reasoningEffort)}`]
      view.tone = 'healthy'
      break
    }
    case 'cache': {
      view.title = '응답 캐시'
      view.subtitle = `TTL ${str(p.responseCacheTtlSeconds)}초`
      view.tone = 'healthy'
      break
    }
    case 'router': {
      const active = graph.nodes.filter((n) => n.type === 'llm' && n.props.enabled !== false).length
      view.title = '라우터 · Failover'
      view.subtitle = `${str(p.failoverPolicy)} · ${str(p.retryPolicy)}`
      if (p.allowDegraded === true) view.badges.push('Degraded 허용')
      view.summary = [`배분 ${str(p.schedulingMode)}`, `사용 중 LLM ${active}개`]
      view.tone = active === 0 ? 'warning' : 'healthy'
      break
    }
    case 'llm': {
      const dep = deploymentOf(snapshot, node.refId)
      view.title = dep ? dep.displayName : '알 수 없는 모델'
      const hostInfo = dep
        ? (dep.external
          ? snapshot.catalog.providers.find((x) => x.id === dep.externalProviderId)?.displayName
          : snapshot.catalog.endpoints.find((x) => x.id === dep.runtimeEndpointId)?.displayName)
        : undefined
      view.subtitle = dep ? `${dep.external ? '외부' : '로컬'} · ${hostInfo ?? (dep.external ? '외부 Provider' : '로컬 Runtime')}` : ''
      if (node.targetId === null) view.badges.push('신규')
      if (p.enabled === false) view.badges.push('중지')
      if (p.degraded === true) view.badges.push('Degraded')
      if (dep && !dep.external && !dep.loaded) view.badges.push('미로드')
      if (dep && !dep.enabled) view.badges.push('비활성')
      if (p.followModelChanges === false && !(dep && dep.external)) view.badges.push('모델 고정')
      view.summary = [`P${str(p.priority)} · W${str(p.weight)} (${llmShare(node, graph)}%)`]
      if (dep) view.summary.push(dep.providerModelId)
      view.tone = p.enabled === false ? 'muted' : deploymentTone(dep)
      break
    }
    case 'endpoint': {
      const info = snapshot.catalog.endpoints.find((e) => e.id === node.refId)
      view.title = info?.displayName ?? '알 수 없는 Runtime'
      view.subtitle = info ? `${info.runtimeType} · ${info.addressLabel}` : ''
      if (info && !info.enabled) view.badges.push('비활성')
      view.tone = info ? toneOf(info.enabled, info.healthStatus) : 'error'
      view.locked = true
      view.lockReason = '실행 위치는 모델에 따라 자동으로 표시됩니다.'
      break
    }
    case 'provider': {
      const info = snapshot.catalog.providers.find((e) => e.id === node.refId)
      view.title = info?.displayName ?? '알 수 없는 Provider'
      view.subtitle = info?.providerType ?? ''
      if (info && !info.apiKeyConfigured) view.badges.push('키 미설정')
      else if (info && info.zeroRetentionVerified) view.badges.push('무보존 확인')
      view.tone = !info ? 'error' : !info.apiKeyConfigured ? 'error' : toneOf(info.enabled, info.healthStatus)
      view.locked = true
      view.lockReason = '실행 위치는 모델에 따라 자동으로 표시됩니다.'
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
    case 'start': {
      rows.push({ label: 'Service Key', value: str(node.props.serviceKey) || '새 워크플로우' })
      rows.push({ label: 'Prompt 버전', value: `v${snapshot.promptVersion}` })
      rows.push({ label: '불러온 시각', value: snapshot.loadedAt })
      const org = snapshot.orgDataProtection
      rows.push({ label: '조직 데이터 보호', value: org ? `${org.mode} · 외부 처리 ${org.externalAction}` : '정보 없음' })
      break
    }
    case 'policy': {
      const info = snapshot.catalog.projects.find((x) => x.id === node.refId)
      for (const s of projectSignals(info)) rows.push({ label: s.label, value: s.blocksExternal ? '외부 LLM 제외' : '해당 요청에만 적용' })
      if (rows.length === 0) rows.push({ label: '보호 신호', value: '없음' })
      break
    }
    case 'guardrail': {
      rows.push({ label: '보호 상태', value: isProtectedGuardrail(snapshot) ? '서버에 정책 적용됨(보호)' : '보호 없음' })
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
      rows.push({ label: '모델 ID', value: id ?? '미선택' })
      if (info) {
        rows.push({ label: '모델명', value: info.providerModelId })
        rows.push({ label: '소스', value: `${info.sourceType} · ${info.sourceName}` })
        rows.push({ label: '적격 여부', value: info.eligible ? '적격' : '부적격' })
        rows.push({ label: '사유', value: info.trustReason })
      }
      break
    }
    case 'prompt':
      rows.push({ label: '범위', value: node.id === 'prompt' ? '공통 · 모든 LLM' : '모델별 · 이 LLM만' })
      rows.push({ label: '프롬프트 길이', value: `${str(node.props.systemPromptTemplate).trim().length}자` })
      rows.push({ label: 'Prompt 버전', value: `v${snapshot.promptVersion}` })
      break
    case 'params':
      rows.push({ label: 'Temperature', value: str(node.props.temperaturePolicy) })
      rows.push({ label: '추론 수준', value: str(node.props.reasoningEffort) })
      break
    case 'cache':
      rows.push({ label: 'TTL', value: `${str(node.props.responseCacheTtlSeconds)}초` })
      break
    case 'router':
      rows.push({ label: '연결된 LLM', value: `${outgoingEdges(graph, node.id).filter((e) => e.kind === 'route').length}개` })
      break
    case 'llm': {
      const dep = deploymentOf(snapshot, node.refId)
      rows.push({ label: '모델 ID', value: node.refId ?? '' })
      rows.push({ label: 'Target ID', value: node.targetId ?? '생성 전' })
      if (dep) {
        const host = hostNodeIdFor(dep)
        rows.push({ label: '모델명', value: dep.providerModelId })
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
      const signals = projectSignals(info)
      if (signals.length > 0) rows.push({ label: '보호 신호', value: signals.map((s) => s.label).join(' · ') })
      break
    }
  }
  return rows
}

const EDGE_TEXT: Record<string, { label: string; meaning: string }> = {
  entry: { label: '접근 허용', meaning: 'project_service_access 행: 이 프로젝트의 API 키로 이 워크플로우(model)를 호출할 수 있음' },
  policy: { label: '보호 정책 적용', meaning: '프로젝트 설정(외부 AI 전송 금지·데이터 경계·DLP)이 이 프로젝트 요청에 적용됨(읽기 전용)' },
  access: { label: '처리 시작', meaning: '허용된 프로젝트의 요청이 워크플로우의 첫 노드로 들어감' },
  flow: { label: '요청 흐름', meaning: '공통 경로의 처리 단계(실제 순서는 서버 고정 순서)' },
  route: { label: 'LLM 분기', meaning: 'service_target: 라우터가 이 LLM으로 보낼 수 있음' },
  masker: { label: '마스킹 워커', meaning: 'llm_service.masker_deployment_id: 가드레일이 이 모델로 마스킹함' },
  host: { label: '실행 위치', meaning: 'model_deployment 의 Runtime/Provider 소속(읽기 전용)' },
}
export function describeEdge(edge: FlowEdge): { label: string; meaning: string } {
  const hit = EDGE_TEXT[edge.kind]
  return hit ? { ...hit } : { label: edge.kind, meaning: '' }
}
