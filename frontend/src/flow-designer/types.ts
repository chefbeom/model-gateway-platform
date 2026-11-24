// Shared contracts for the experimental Workflow Designer (v2; page key 'flow-designer').
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
  /** 'target:<targetId>' for persisted targets, 'target:new-<deploymentId>' for pending ones. */
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
  /** v2 (backend V54): model-specific system prompt, trimmed; null = none. Readers treat undefined as null. */
  systemPromptTemplate?: string | null
}
export type TargetField = 'deploymentId' | 'priority' | 'weight' | 'degraded' | 'enabled' | 'maxConcurrencyOverride' | 'followModelChanges' | 'systemPromptTemplate'

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
/** Stored PROJECT-scope data-protection policy: only what the designer needs (never custom patterns). */
export interface ProjectDlpInfo { mode: string; externalAction: GuardrailAction }
export interface ProjectInfo {
  id: string
  name: string
  status: string
  /** Server getter value (already INTERNAL_ONLY when externalAiBlocked). */
  dataBoundaryPolicy: DataBoundaryPolicy | null
  /** v2. Readers treat undefined as null. */
  maxDataBoundaryPolicy?: DataBoundaryPolicy | null
  /** v2: project "외부 AI 전송 금지". Readers treat undefined as false. */
  externalAiBlocked?: boolean
  /** v2: stored PROJECT-scope DLP policy; null/undefined = none stored or unknown. */
  dlp?: ProjectDlpInfo | null
}
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
/** Effective organization (platform + organization) data-protection policy. */
export interface OrgDataProtectionInfo { mode: string; externalAction: string }
export interface FlowCatalog {
  services: ServiceSummary[]
  deployments: DeploymentInfo[]
  endpoints: EndpointInfo[]
  providers: ProviderInfo[]
  projects: ProjectInfo[]
  maskers: MaskerInfo[]
}
export interface SnapshotFeatures {
  /** false when the server does not persist model-specific system prompts (pre-V54 backend). */
  targetPrompt: boolean
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
  /** v2. Readers treat undefined as null. */
  orgDataProtection?: OrgDataProtectionInfo | null
  /** v2: false when project-scope DLP policies could not be read. Readers treat undefined as true. */
  projectPoliciesComplete?: boolean
  /** v2: break-glass platform-token session (projects invisible). Readers treat undefined as false. */
  projectsHidden?: boolean
  /** v2. Readers treat undefined as { targetPrompt: true }. */
  features?: SnapshotFeatures
}

/* ---------------- Graph ---------------- */
export type NodeType = 'start' | 'project' | 'policy' | 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router' | 'llm' | 'endpoint' | 'provider'
export type NodeCategory = 'entry' | 'guard' | 'prompt' | 'cache' | 'routing' | 'llm' | 'infra'
export type PortType = 'flow' | 'masker' | 'host'
export type PortDirection = 'in' | 'out'
export type EdgeKind = 'entry' | 'policy' | 'access' | 'flow' | 'route' | 'masker' | 'host'
export type FlowPropValue = string | number | boolean | null
export type PromptScope = 'common' | 'model'

export interface FlowNode {
  id: string
  type: NodeType
  /** Primary entity: projectId (project, policy), deploymentId (llm), endpoint/provider id (hosts); null otherwise. */
  refId: string | null
  /** llm only: server ServiceTarget id, null while pending creation. Always null for other types. */
  targetId: string | null
  /** World coordinates of the top-left corner. */
  x: number
  y: number
  collapsed: boolean
  props: Record<string, FlowPropValue>
}
export interface PortRef { nodeId: string; portId: string }
export interface FlowEdge { id: string; kind: EdgeKind; from: PortRef; to: PortRef; locked: boolean }
export interface FlowGraph {
  schemaVersion: 2
  serviceId: string | null
  nodes: FlowNode[]
  edges: FlowEdge[]
}

export interface PortSpec { id: string; direction: PortDirection; type: PortType; label: string; multiple: boolean }
export type FieldKind = 'text' | 'textarea' | 'number' | 'integer' | 'boolean' | 'select' | 'capabilities' | 'masker-select' | 'access-toggle' | 'guardrail-preset' | 'model-replace'
export interface FieldOption { value: string; label: string }
export interface FieldSpec {
  /** Prop key, or a virtual key starting with '$' ('$access', '$preset', '$model'). */
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
  /** v2: shown only in advanced mode. */
  advanced?: boolean
  /** v2: shown only in basic mode. */
  basicOnly?: boolean
}
export interface NodeTypeSpec {
  type: NodeType
  label: string
  /** v2: one-line palette description. */
  description: string
  /** v2: extra lowercase search words (Korean and English, space separated). */
  keywords: string
  category: NodeCategory
  icon: string
  /** v2: canonical server-order rank (§6.3); model-specific prompts use PROMPT_BRANCH_RANK instead. */
  rank: number
  /** At most one node of this type (prompt is false: one common + one per LLM). */
  singleton: boolean
  deletable: boolean
  paletteAddable: boolean
  /** v2: hidden from the palette in basic mode. */
  advanced: boolean
  /** v2: created and removed only by synchronisation (policy, endpoint, provider). */
  derived: boolean
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
  /** Never deletable (start, derived nodes). */
  locked: boolean
  /** v2: deletable only through the explicit "보호 해제 후 삭제" action. */
  protected: boolean
  /** v2: reason shown for locked/protected nodes; null otherwise. */
  lockReason: string | null
  /** v2: prompt nodes only; null for other types. */
  promptScope: PromptScope | null
}
export interface FactRow { label: string; value: string }

/* ---------------- Workflow analysis & policy (v2) ---------------- */
export interface WorkflowBranch { llmNodeId: string; promptNodeId: string | null }
export interface WorkflowAnalysis {
  /** First flow node after the entry layer; null when the workflow has no flow node. */
  head: string | null
  /** Trunk node ids in visual order (head first; ends with the router when present; excludes LLMs and model prompts). */
  trunk: string[]
  router: string | null
  /** Last trunk node; null when the trunk is empty. */
  tail: string | null
  /** Reachable branches in server dispatch order (local first, then priority asc, weight desc, id). */
  branches: WorkflowBranch[]
  /** Flow nodes not reachable from head, sorted by id. */
  detached: string[]
  /** Structural issues (WF_* codes, CYCLE_DETECTED is reported by validate). */
  issues: ValidationIssue[]
}
export type PolicySignalCode = 'PROJECT_LOCAL_ONLY' | 'PROJECT_BOUNDARY' | 'PROJECT_DLP'
export interface PolicySignal { code: PolicySignalCode; label: string; /** External LLMs are skipped entirely for this project. */ blocksExternal: boolean }
export type GuardrailPresetId = 'mask-restore' | 'local-sensitive' | 'no-retention' | 'internal-only' | 'block'
export interface GuardrailPreset { id: GuardrailPresetId; label: string; description: string; dataBoundaryPolicy: DataBoundaryPolicy; guardrailAction: GuardrailAction }
export type WorkflowTemplateId = 'basic' | 'local-first' | 'internal-only'
export interface WorkflowTemplate { id: WorkflowTemplateId; label: string; description: string }

/* ---------------- Palette (v2) ---------------- */
export type DesignerMode = 'basic' | 'advanced'
export type ModelAvailability = 'active' | 'degraded' | 'inactive'
export type PaletteGroupId = 'entry' | 'guard' | 'prompt' | 'cache' | 'routing' | 'llm-local' | 'llm-external'
export interface PaletteNodeItem { nodeType: AddableNodeType; label: string; description: string; icon: string; clickDisabledReason: string | null; draggable: boolean; onCanvas: boolean }
export interface PaletteProjectItem { projectId: string; name: string; status: string; onCanvas: boolean; granted: boolean; guarded: boolean }
export interface PaletteModelItem { deploymentId: string; displayName: string; providerModelId: string; sourceName: string; external: boolean; availability: ModelAvailability; tone: HealthTone; reason: string | null; onCanvas: boolean }
export interface PaletteGroup { id: PaletteGroupId; label: string; nodes: PaletteNodeItem[]; projects: PaletteProjectItem[]; models: PaletteModelItem[]; hiddenCount: number }
export interface PaletteOptions { query: string; showInactive: boolean; mode: DesignerMode }

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
export type AddableNodeType = 'project' | 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router' | 'llm'
export type FlowCommand =
  | { type: 'moveNodes'; moves: Array<{ nodeId: string; x: number; y: number }> }
  | { type: 'addNode'; nodeType: AddableNodeType; refId?: string | null; position?: Point | null; spliceEdgeId?: string | null; autoWire?: boolean }
  | { type: 'removeItems'; nodeIds: string[]; edgeIds: string[]; force?: boolean }
  | { type: 'connect'; from: PortRef; to: PortRef }
  | { type: 'updateProps'; nodeId: string; patch: Record<string, FlowPropValue> }
  | { type: 'replaceModel'; nodeId: string; deploymentId: string }
  | { type: 'setAccess'; projectNodeId: string; granted: boolean }
  | { type: 'normalizeOrder' }
  | { type: 'applyTemplate'; template: WorkflowTemplateId; projectIds: string[]; deploymentIds: string[] }
  | { type: 'toggleCollapse'; nodeIds: string[]; collapsed: boolean }
  | { type: 'autoLayout' }
export interface CommandResult { graph: FlowGraph; changed: boolean; error: string | null; select: FlowSelection | null }
export type ConnectCheck = { ok: true; replaces: string[] } | { ok: false; reason: string }
export type NodeChangeKind = 'added' | 'modified'
export type FlowToolbarAction = 'undo' | 'redo' | 'fit' | 'auto-layout' | 'zoom-in' | 'zoom-out' | 'zoom-reset' | 'validate' | 'plan' | 'revert' | 'reload' | 'save-layout' | 'toggle-palette' | 'toggle-inspector' | 'mode-basic' | 'mode-advanced' | 'normalize-order'

/* ---------------- Validation ---------------- */
export type IssueSeverity = 'error' | 'warning' | 'info'
/** v2: a one-click fix offered next to the issue. */
export interface IssueFix { label: string; command: FlowCommand }
export interface ValidationIssue { code: string; severity: IssueSeverity; message: string; nodeId: string | null; edgeId: string | null; field: string | null; fix?: IssueFix }

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
export interface CreateTargetBody { deploymentId: string; priority: number; weight: number; degraded: boolean; followModelChanges: boolean; maxConcurrencyOverride?: number; systemPromptTemplate?: string }
export interface PatchTargetBody { priority?: number; weight?: number; degraded?: boolean; enabled?: boolean; maxConcurrencyOverride?: number; followModelChanges?: boolean; systemPromptTemplate?: string; clearSystemPromptTemplate?: true }
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
export interface RebaseInput { graph: FlowGraph; base: ServiceFlowSnapshot; remote: ServiceFlowSnapshot; createdTargetIds?: Record<string, string>; deletedTargetKeys?: string[] }
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
  /** Target key -> created server id. */
  createdTargetIds: Record<string, string>
  /** Target keys whose server row this run deleted (succeeded or already gone). */
  deletedTargetKeys?: string[]
  completed: boolean
  failedOpId: string | null
}
