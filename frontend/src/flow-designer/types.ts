// Shared contracts for the experimental Service Flow Designer.
// Pure type declarations only: no runtime values, no imports.

/* ---------------- Domain enums (mirror backend enums) ---------------- */
export type FailoverPolicy = 'STRICT' | 'COMPATIBLE' | 'DEGRADED'
export type RetryPolicy = 'SAFE' | 'AGGRESSIVE'
export type DataBoundaryPolicy = 'INTERNAL_ONLY' | 'APPROVED_EXTERNAL_NO_RETENTION' | 'EXTERNAL_ALLOWED'
export type Currency = 'KRW' | 'USD'
export type TemperaturePolicy = 'REQUEST' | 'FIXED' | 'OMIT'
export type ReasoningEffort = 'REQUEST' | 'NONE' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX'
export type GuardrailAction = 'ALLOW' | 'REDACT_RESTORE' | 'REDACT' | 'LOCAL_ONLY' | 'BLOCK'
export type SchedulingMode = 'IMMEDIATE' | 'LOCAL_FIRST_WAIT' | 'LOCAL_ONLY_QUEUE'

/* ---------------- Editable domain state (server is the source of truth) ---------------- */
export interface ServiceState {
  serviceKey: string
  displayName: string
  enabled: boolean
  dataBoundaryPolicy: DataBoundaryPolicy
  requiredCapabilitiesJson: string
  currency: Currency
  inputPricePerMillion: number
  outputPricePerMillion: number
  guardrailAction: GuardrailAction
  maskerDeploymentId: string | null
  maskPersonNames: boolean
  systemPromptTemplate: string | null
  temperaturePolicy: TemperaturePolicy
  temperatureValue: number | null
  reasoningEffort: ReasoningEffort
  openAiFastMode: boolean
  responseCacheEnabled: boolean
  responseCacheTtlSeconds: number
  failoverPolicy: FailoverPolicy
  retryPolicy: RetryPolicy
  allowDegraded: boolean
}
export type ServiceField = keyof ServiceState

export interface SchedulingState {
  mode: SchedulingMode
  localWaitMs: number
  maxWaitMs: number
  maxQueuedRequests: number
  maxQueuedBytes: number
  maxQueuedPerKey: number
}
export type SchedulingField = keyof SchedulingState

export interface TargetState {
  /** FlowNode id of the target node: 'target:<uuid>' or 'target:new-<n>'. */
  key: string
  /** Server id; null until created. */
  id: string | null
  deploymentId: string
  priority: number
  weight: number
  degraded: boolean
  enabled: boolean
  maxConcurrencyOverride: number | null
  followModelChanges: boolean
}
export type TargetField = 'deploymentId' | 'priority' | 'weight' | 'degraded' | 'enabled' | 'maxConcurrencyOverride' | 'followModelChanges'

export interface FlowConfigState {
  service: ServiceState
  scheduling: SchedulingState
  /** Sorted by key. */
  targets: TargetState[]
  /** Sorted ascending, unique. */
  accessProjectIds: string[]
}

/* ---------------- Read-only catalog ---------------- */
export type HealthTone = 'healthy' | 'warning' | 'error' | 'muted' | 'unknown'
export interface ServiceSummary { id: string; serviceKey: string; displayName: string; enabled: boolean }
export interface DeploymentInfo {
  id: string
  displayName: string
  providerModelId: string
  runtimeEndpointId: string | null
  externalProviderId: string | null
  external: boolean
  enabled: boolean
  /** Always true for external deployments (load state does not apply). */
  loaded: boolean
  healthStatus: string
  maxConcurrency: number
  contextLength: number | null
}
export interface EndpointInfo { id: string; displayName: string; runtimeType: string; enabled: boolean; healthStatus: string; addressLabel: string }
export interface ProviderInfo { id: string; displayName: string; providerType: string; enabled: boolean; healthStatus: string; apiKeyConfigured: boolean; zeroRetentionVerified: boolean }
export interface ProjectInfo { id: string; name: string; status: string; dataBoundaryPolicy: DataBoundaryPolicy | null }
export interface MaskerInfo { deploymentId: string; displayName: string; providerModelId: string; sourceType: string; sourceName: string; eligible: boolean; trustReason: string }
export interface ServiceDataProtectionInfo {
  mode: string
  level: string
  externalAction: string
  allowExternalFailover: boolean
  detectSecrets: boolean
  detectPii: boolean
  detectFinancial: boolean
  detectConfidential: boolean
  detectMedia: boolean
}
export interface FlowCatalog {
  services: ServiceSummary[]
  deployments: DeploymentInfo[]
  endpoints: EndpointInfo[]
  providers: ProviderInfo[]
  projects: ProjectInfo[]
  maskers: MaskerInfo[]
}
export interface ServiceFlowSnapshot {
  organizationId: string
  /** null = unsaved new-service draft. */
  serviceId: string | null
  /** Read-only; 1 for drafts. */
  promptVersion: number
  config: FlowConfigState
  /** false => project access could not be fully read; access edits are disabled. */
  accessComplete: boolean
  catalog: FlowCatalog
  dataProtection: ServiceDataProtectionInfo | null
  /** ISO-8601 timestamp. */
  loadedAt: string
  /** API paths whose (non-fatal) read failed. */
  unavailable: string[]
}

/* ---------------- Graph ---------------- */
export type NodeType = 'project' | 'service' | 'masker' | 'guardrail' | 'prompt' | 'cache' | 'router' | 'target' | 'deployment' | 'endpoint' | 'provider'
export type NodeCategory = 'entry' | 'pipeline' | 'routing' | 'infra'
export type PortType = 'access' | 'request' | 'masker' | 'route' | 'binding' | 'host'
export type PortDirection = 'in' | 'out'
export type EdgeKind = 'access' | 'pipeline' | 'masker' | 'route' | 'binding' | 'host'
export type FlowPropValue = string | number | boolean | null

export interface FlowNode {
  id: string
  type: NodeType
  /** Referenced entity id (project/target/deployment/endpoint/provider); null otherwise or for new targets. */
  refId: string | null
  /** World coordinates of the top-left corner. */
  x: number
  y: number
  collapsed: boolean
  props: Record<string, FlowPropValue>
}
export interface PortRef { nodeId: string; portId: string }
export interface FlowEdge { id: string; kind: EdgeKind; from: PortRef; to: PortRef; locked: boolean }
export interface FlowGraph {
  schemaVersion: 1
  serviceId: string | null
  nodes: FlowNode[]
  edges: FlowEdge[]
  /** Sequence for 'target:new-<n>' ids; starts at 1. */
  nextSeq: number
}

export interface PortSpec { id: string; direction: PortDirection; type: PortType; label: string; multiple: boolean }
export type FieldKind = 'text' | 'textarea' | 'number' | 'integer' | 'boolean' | 'select' | 'capabilities' | 'masker-select' | 'deployment-binding' | 'access-toggle'
export interface FieldOption { value: string; label: string }
export interface FieldSpec {
  /** Prop key, or a virtual key starting with '$' ('$binding', '$access'). */
  key: string
  label: string
  kind: FieldKind
  options?: FieldOption[]
  min?: number
  max?: number
  step?: number
  maxLength?: number
  /** For number/integer: an empty input means null. */
  nullable?: boolean
  placeholder?: string
  help?: string
  visibleWhen?: { key: string; oneOf: FlowPropValue[] }
  /** Editable only while graph.serviceId === null. */
  newServiceOnly?: boolean
}
export interface NodeTypeSpec {
  type: NodeType
  label: string
  category: NodeCategory
  icon: string
  column: number
  singleton: boolean
  deletable: boolean
  paletteAddable: boolean
  ports: PortSpec[]
  fields: FieldSpec[]
}
export interface ConnectionRule { fromType: NodeType; fromPort: string; toType: NodeType; toPort: string; kind: EdgeKind; userEditable: boolean }
export interface NodeView {
  title: string
  subtitle: string
  typeLabel: string
  category: NodeCategory
  icon: string
  tone: HealthTone
  badges: string[]
  /** At most 2 lines. */
  summary: string[]
  locked: boolean
}
export interface FactRow { label: string; value: string }

/* ---------------- Geometry ---------------- */
export interface Point { x: number; y: number }
export interface Size { width: number; height: number }
export interface Rect { x: number; y: number; width: number; height: number }
export interface Viewport { x: number; y: number; zoom: number }

/* ---------------- Layout persistence ---------------- */
export interface FlowLayoutNode { x: number; y: number; collapsed?: boolean }
export interface FlowLayout { version: 1; viewport: Viewport; nodes: Record<string, FlowLayoutNode> }

/* ---------------- Editing ---------------- */
export interface FlowSelection { nodeIds: string[]; edgeIds: string[] }
export type AddableNodeType = 'project' | 'deployment' | 'target' | 'masker'
export type FlowCommand =
  | { type: 'moveNodes'; moves: Array<{ nodeId: string; x: number; y: number }> }
  | { type: 'addNode'; nodeType: AddableNodeType; refId?: string | null; position?: Point | null }
  | { type: 'removeItems'; nodeIds: string[]; edgeIds: string[] }
  | { type: 'connect'; from: PortRef; to: PortRef }
  | { type: 'updateProps'; nodeId: string; patch: Record<string, FlowPropValue> }
  | { type: 'bindTarget'; targetNodeId: string; deploymentId: string }
  | { type: 'setAccess'; projectNodeId: string; granted: boolean }
  | { type: 'toggleCollapse'; nodeIds: string[]; collapsed: boolean }
  | { type: 'autoLayout' }
export interface CommandResult { graph: FlowGraph; changed: boolean; error: string | null; select: FlowSelection | null }
export type ConnectCheck = { ok: true; replaces: string[] } | { ok: false; reason: string }
export type NodeChangeKind = 'added' | 'modified'
export type FlowToolbarAction = 'undo' | 'redo' | 'fit' | 'auto-layout' | 'validate' | 'plan' | 'revert' | 'reload' | 'save-layout' | 'toggle-palette' | 'toggle-inspector'

/* ---------------- Validation ---------------- */
export type IssueSeverity = 'error' | 'warning' | 'info'
export interface ValidationIssue { code: string; severity: IssueSeverity; message: string; nodeId: string | null; edgeId: string | null; field: string | null }

/* ---------------- Plan ---------------- */
export type ServiceRef = { kind: 'existing'; id: string } | { kind: 'created' }
export type TargetRef = { kind: 'existing'; id: string } | { kind: 'created'; key: string }
export type OperationRisk = 'safe' | 'caution' | 'danger'
export interface CreateServiceBody {
  organizationId: string
  serviceKey: string
  displayName: string
  failoverPolicy: FailoverPolicy
  retryPolicy: RetryPolicy
  allowDegraded: boolean
  requiredCapabilitiesJson: string
  inputPricePerMillion: number
  outputPricePerMillion: number
  currency: Currency
  temperaturePolicy: TemperaturePolicy
  temperatureValue: number | null
  reasoningEffort: ReasoningEffort
  openAiFastMode: boolean
  dataBoundaryPolicy: DataBoundaryPolicy
}
export interface PatchServiceBody {
  displayName?: string
  enabled?: boolean
  dataBoundaryPolicy?: DataBoundaryPolicy
  requiredCapabilitiesJson?: string
  currency?: Currency
  inputPricePerMillion?: number
  outputPricePerMillion?: number
  guardrailAction?: GuardrailAction
  maskerDeploymentId?: string
  clearMaskerDeployment?: true
  maskPersonNames?: boolean
  systemPromptTemplate?: string
  clearSystemPromptTemplate?: true
  temperaturePolicy?: TemperaturePolicy
  temperatureValue?: number | null
  reasoningEffort?: ReasoningEffort
  openAiFastMode?: boolean
  responseCacheEnabled?: boolean
  responseCacheTtlSeconds?: number
  failoverPolicy?: FailoverPolicy
  retryPolicy?: RetryPolicy
  allowDegraded?: boolean
}
export interface CreateTargetBody { deploymentId: string; priority: number; weight: number; degraded: boolean; followModelChanges: boolean; maxConcurrencyOverride?: number }
export interface PatchTargetBody { priority?: number; weight?: number; degraded?: boolean; enabled?: boolean; maxConcurrencyOverride?: number; followModelChanges?: boolean }
export interface OperationChange { field: string; label: string; before: string; after: string }
export interface OperationCommon { opId: string; risk: OperationRisk; summary: string; changes: OperationChange[] }
export type FlowOperation =
  | (OperationCommon & { kind: 'createService'; body: CreateServiceBody })
  | (OperationCommon & { kind: 'patchService'; service: ServiceRef; body: PatchServiceBody })
  | (OperationCommon & { kind: 'updateScheduling'; service: ServiceRef; body: SchedulingState })
  | (OperationCommon & { kind: 'createTarget'; service: ServiceRef; key: string; body: CreateTargetBody })
  | (OperationCommon & { kind: 'patchTarget'; service: ServiceRef; target: TargetRef; body: PatchTargetBody })
  | (OperationCommon & { kind: 'deleteTarget'; service: ServiceRef; targetId: string })
  | (OperationCommon & { kind: 'grantAccess'; service: ServiceRef; projectId: string })
  | (OperationCommon & { kind: 'revokeAccess'; service: ServiceRef; projectId: string })
export type FlowOperationKind = FlowOperation['kind']
export type ConflictCode = 'FIELD_CONFLICT' | 'TARGET_REMOVED_REMOTELY' | 'TARGET_CHANGED_REMOTELY' | 'DEPLOYMENT_TARGETED_REMOTELY'
export interface FlowConflict {
  code: ConflictCode
  entity: 'service' | 'scheduling' | 'target'
  /** Field name for service/scheduling, target key for targets. */
  key: string
  field: string | null
  base: string
  local: string
  remote: string
  message: string
}
export interface PlanNote { level: 'info' | 'warning'; message: string }
export interface FlowPlan {
  operations: FlowOperation[]
  conflicts: FlowConflict[]
  blockingIssues: ValidationIssue[]
  notes: PlanNote[]
  remoteDrift: boolean
  fingerprint: string
}
export interface PlanInput { base: ServiceFlowSnapshot; desired: FlowConfigState; remote: ServiceFlowSnapshot; issues: ValidationIssue[] }
export interface RebaseInput { graph: FlowGraph; base: ServiceFlowSnapshot; remote: ServiceFlowSnapshot; createdTargetIds?: Record<string, string> }
export interface RebaseResult { graph: FlowGraph; conflicts: FlowConflict[] }

/* ---------------- Execution (implemented by WP-C execute.ts) ---------------- */
export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
export interface FlowTransport { request<T>(method: HttpMethod, path: string, body?: unknown): Promise<T> }
export interface TransportError { message: string; code?: string; status?: number }
export type OperationStatus = 'pending' | 'running' | 'succeeded' | 'already-applied' | 'failed' | 'skipped'
export interface OperationResult { opId: string; status: OperationStatus; message: string | null; code: string | null; httpStatus: number | null }
export interface ExecutionReport {
  results: OperationResult[]
  /** Service id after execution (the created id for new services). */
  serviceId: string | null
  /** Target key (FlowNode id) -> created server id. */
  createdTargetIds: Record<string, string>
  completed: boolean
  failedOpId: string | null
}
