// Pure normalisation of raw admin API JSON into a ServiceFlowSnapshot.
// No network, no DOM, no vue: every API quirk (non_null omission, nested shapes) is handled here.
import { DEFAULT_SCHEDULING, DEFAULT_SERVICE_STATE, LIMITS, targetNodeId } from './catalog'
import { canonicalCapabilities, normalizePrompt } from './compile'
import type {
  Currency,
  DataBoundaryPolicy,
  DeploymentInfo,
  EndpointInfo,
  FailoverPolicy,
  FlowConfigState,
  GuardrailAction,
  MaskerInfo,
  OrgDataProtectionInfo,
  ProjectDlpInfo,
  ProjectInfo,
  ProviderInfo,
  ReasoningEffort,
  RetryPolicy,
  SchedulingMode,
  SchedulingState,
  ServiceDataProtectionInfo,
  ServiceFlowSnapshot,
  ServiceState,
  ServiceSummary,
  TargetState,
  TemperaturePolicy
} from './types'

export interface RawFlowData {
  organizationId: string
  serviceId: string | null
  loadedAt: string
  unavailable: string[]
  services: unknown[]
  targets: unknown[] | null
  scheduling: unknown | null
  dataProtection: unknown | null
  /** Organization data-protection response (v2). null = unreadable. Only mode/externalAction are ever copied out of it. */
  orgDataProtection?: unknown | null
  /** Break-glass platform-token session: projects are invisible to the server filter. */
  projectsHidden?: boolean
  endpoints: unknown[]
  endpointDeployments: Record<string, unknown[]>
  providers: unknown[]
  providerModels: Record<string, unknown[]>
  projects: unknown[] | null
  projectAccess: Record<string, unknown[] | null>
  maskers: unknown[]
}

export class FlowLoadError extends Error {
  readonly code: 'SERVICE_NOT_FOUND' | 'REQUIRED_READ_FAILED'
  constructor(code: 'SERVICE_NOT_FOUND' | 'REQUIRED_READ_FAILED', message?: string) {
    super(message ?? (code === 'SERVICE_NOT_FOUND' ? '서비스가 삭제되었거나 접근할 수 없습니다.' : '필수 구성 정보를 불러오지 못했습니다.'))
    this.name = 'FlowLoadError'
    this.code = code
  }
}

type Rec = Record<string, unknown>

function isRec(value: unknown): value is Rec {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function rows(value: unknown): Rec[] {
  return Array.isArray(value) ? value.filter(isRec) : []
}
function str(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : typeof value === 'number' ? String(value) : fallback
}
function optStr(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}
function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback
}
function num(value: unknown, fallback: number): number {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}
function optNum(value: unknown): number | null {
  if (value === null || value === undefined) return null
  const parsed = num(value, Number.NaN)
  return Number.isNaN(parsed) ? null : parsed
}
function oneOf<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? (value as T) : fallback
}
function byString<T>(select: (item: T) => string) {
  return (a: T, b: T) => {
    const x = select(a)
    const y = select(b)
    return x < y ? -1 : x > y ? 1 : 0
  }
}

const FAILOVER: FailoverPolicy[] = ['STRICT', 'COMPATIBLE', 'DEGRADED']
const RETRY: RetryPolicy[] = ['SAFE', 'AGGRESSIVE']
const BOUNDARY: DataBoundaryPolicy[] = ['INTERNAL_ONLY', 'APPROVED_EXTERNAL_NO_RETENTION', 'EXTERNAL_ALLOWED']
const CURRENCY: Currency[] = ['KRW', 'USD']
const TEMPERATURE: TemperaturePolicy[] = ['REQUEST', 'FIXED', 'OMIT']
const REASONING: ReasoningEffort[] = ['REQUEST', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX']
const GUARDRAIL: GuardrailAction[] = ['ALLOW', 'REDACT_RESTORE', 'REDACT', 'LOCAL_ONLY', 'BLOCK']
const SCHEDULING_MODES: SchedulingMode[] = ['IMMEDIATE', 'LOCAL_FIRST_WAIT', 'LOCAL_ONLY_QUEUE']

/** Strips credentials, query and fragment from an address for display only. */
export function safeAddressLabel(url: string): string {
  const fallback = '주소 확인 필요'
  if (typeof url !== 'string' || !url.trim()) return fallback
  try {
    const parsed = new URL(url.trim())
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return fallback
    if (!parsed.host) return fallback
    const path = parsed.pathname === '/' ? '' : parsed.pathname
    return `${parsed.protocol}//${parsed.host}${path}`
  } catch {
    return fallback
  }
}

function mapService(raw: Rec): ServiceState {
  const d = DEFAULT_SERVICE_STATE
  return {
    serviceKey: str(raw.serviceKey, d.serviceKey),
    displayName: str(raw.displayName, d.displayName),
    enabled: bool(raw.enabled, d.enabled),
    dataBoundaryPolicy: oneOf(raw.dataBoundaryPolicy, BOUNDARY, d.dataBoundaryPolicy),
    requiredCapabilitiesJson: canonicalCapabilities(typeof raw.requiredCapabilitiesJson === 'string' && raw.requiredCapabilitiesJson.trim() ? raw.requiredCapabilitiesJson : d.requiredCapabilitiesJson),
    currency: oneOf(raw.currency, CURRENCY, d.currency),
    inputPricePerMillion: num(raw.inputPricePerMillion, d.inputPricePerMillion),
    outputPricePerMillion: num(raw.outputPricePerMillion, d.outputPricePerMillion),
    guardrailAction: oneOf(raw.guardrailAction, GUARDRAIL, d.guardrailAction),
    maskerDeploymentId: optStr(raw.maskerDeploymentId),
    maskPersonNames: bool(raw.maskPersonNames, d.maskPersonNames),
    systemPromptTemplate: normalizePrompt(typeof raw.systemPromptTemplate === 'string' ? raw.systemPromptTemplate : null),
    temperaturePolicy: oneOf(raw.temperaturePolicy, TEMPERATURE, d.temperaturePolicy),
    temperatureValue: optNum(raw.temperatureValue),
    reasoningEffort: oneOf(raw.reasoningEffort, REASONING, d.reasoningEffort),
    openAiFastMode: bool(raw.openAiFastMode, d.openAiFastMode),
    responseCacheEnabled: bool(raw.responseCacheEnabled, d.responseCacheEnabled),
    responseCacheTtlSeconds: num(raw.responseCacheTtlSeconds, d.responseCacheTtlSeconds),
    failoverPolicy: oneOf(raw.failoverPolicy, FAILOVER, d.failoverPolicy),
    retryPolicy: oneOf(raw.retryPolicy, RETRY, d.retryPolicy),
    allowDegraded: bool(raw.allowDegraded, d.allowDegraded)
  }
}

/** temperatureValue only means something for the FIXED policy. */
function normalizeTemperature(service: ServiceState): ServiceState {
  return service.temperaturePolicy === 'FIXED' ? service : { ...service, temperatureValue: null }
}

function mapScheduling(raw: unknown): SchedulingState {
  const d = DEFAULT_SCHEDULING
  const source = isRec(raw) && isRec(raw.settings) ? raw.settings : isRec(raw) ? raw : {}
  return {
    mode: oneOf(source.mode, SCHEDULING_MODES, d.mode),
    localWaitMs: num(source.localWaitMs, d.localWaitMs),
    maxWaitMs: num(source.maxWaitMs, d.maxWaitMs),
    maxQueuedRequests: num(source.maxQueuedRequests, d.maxQueuedRequests),
    maxQueuedBytes: num(source.maxQueuedBytes, d.maxQueuedBytes),
    maxQueuedPerKey: num(source.maxQueuedPerKey, d.maxQueuedPerKey)
  }
}

function mapTarget(raw: Rec): TargetState | null {
  const id = optStr(raw.id)
  if (!id) return null
  return {
    key: targetNodeId(id),
    id,
    deploymentId: str(raw.deploymentId),
    priority: num(raw.priority, 1),
    weight: num(raw.weight, 1),
    degraded: bool(raw.degraded, false),
    enabled: bool(raw.enabled, true),
    maxConcurrencyOverride: optNum(raw.maxConcurrencyOverride),
    followModelChanges: bool(raw.followModelChanges, true),
    systemPromptTemplate: normalizePrompt(typeof raw.systemPromptTemplate === 'string' ? raw.systemPromptTemplate : null)
  }
}

function mapDataProtection(raw: unknown): ServiceDataProtectionInfo | null {
  if (!isRec(raw)) return null
  return {
    mode: str(raw.mode, 'UNKNOWN'),
    level: str(raw.level, 'UNKNOWN'),
    externalAction: str(raw.externalAction, 'UNKNOWN'),
    allowExternalFailover: bool(raw.allowExternalFailover, false),
    detectSecrets: bool(raw.detectSecrets, false),
    detectPii: bool(raw.detectPii, false),
    detectFinancial: bool(raw.detectFinancial, false),
    detectConfidential: bool(raw.detectConfidential, false),
    detectMedia: bool(raw.detectMedia, false)
  }
}

/** Reduces one organization/project DLP row to the two fields the designer needs; nothing else is copied. */
function mapDlpFields(row: unknown): { mode: string; externalAction: GuardrailAction } | null {
  if (!isRec(row)) return null
  return { mode: str(row.mode, 'UNKNOWN'), externalAction: oneOf(row.externalAction, GUARDRAIL, 'ALLOW') }
}

function projectDlpOf(orgDataProtection: unknown, projectId: string): ProjectDlpInfo | null {
  if (!isRec(orgDataProtection)) return null
  const policy = rows(orgDataProtection.policies).find((row) => str(row.scopeType) === 'PROJECT' && str(row.scopeId) === projectId)
  return policy ? mapDlpFields(policy) : null
}

export function buildSnapshot(raw: RawFlowData): ServiceFlowSnapshot {
  const serviceRows = rows(raw.services)
  const services: ServiceSummary[] = serviceRows
    .map((row) => ({ id: str(row.id), serviceKey: str(row.serviceKey), displayName: str(row.displayName), enabled: bool(row.enabled, true) }))
    .filter((row) => row.id !== '')
    .sort(byString((item: ServiceSummary) => item.serviceKey || item.id))

  const serviceId = raw.serviceId
  let config: FlowConfigState
  let promptVersion = 1
  let accessComplete = true
  let dataProtection: ServiceDataProtectionInfo | null = null
  const projectsHidden = raw.projectsHidden === true
  if (projectsHidden) accessComplete = false

  const projectRows = raw.projects === null ? null : rows(raw.projects)
  const projects: ProjectInfo[] = (projectRows ?? [])
    .map((row) => ({
      id: str(row.id),
      name: str(row.name, str(row.id)),
      status: str(row.status, 'UNKNOWN'),
      dataBoundaryPolicy: typeof row.dataBoundaryPolicy === 'string' && (BOUNDARY as string[]).includes(row.dataBoundaryPolicy) ? (row.dataBoundaryPolicy as DataBoundaryPolicy) : null,
      maxDataBoundaryPolicy: typeof row.maxDataBoundaryPolicy === 'string' && (BOUNDARY as string[]).includes(row.maxDataBoundaryPolicy) ? (row.maxDataBoundaryPolicy as DataBoundaryPolicy) : null,
      externalAiBlocked: bool(row.externalAiBlocked, false),
      dlp: projectDlpOf(raw.orgDataProtection, str(row.id))
    }))
    .filter((row) => row.id !== '')
    .sort(byString((item: ProjectInfo) => item.name + '\u0000' + item.id))

  if (serviceId === null) {
    config = { service: { ...DEFAULT_SERVICE_STATE }, scheduling: { ...DEFAULT_SCHEDULING }, targets: [], accessProjectIds: [] }
  } else {
    const row = serviceRows.find((item) => str(item.id) === serviceId)
    if (!row) throw new FlowLoadError('SERVICE_NOT_FOUND')
    if (raw.targets === null || raw.scheduling === null || raw.targets === undefined || raw.scheduling === undefined) {
      throw new FlowLoadError('REQUIRED_READ_FAILED')
    }
    promptVersion = Math.max(1, Math.trunc(num(row.promptVersion, 1)))
    const targets = rows(raw.targets)
      .map(mapTarget)
      .filter((item): item is TargetState => item !== null)
      .sort(byString((item: TargetState) => item.key))
    // Access: complete only when the project list was read, is small enough and every per-project read succeeded.
    if (projectRows === null || projectRows.length > LIMITS.maxProjectsForAccess) accessComplete = false
    const accessProjectIds: string[] = []
    if (accessComplete) {
      for (const project of projects) {
        const access = raw.projectAccess[project.id]
        if (!Array.isArray(access)) { accessComplete = false; break }
        if (rows(access).some((entry) => str(entry.id) === serviceId || str(entry.serviceId) === serviceId)) accessProjectIds.push(project.id)
      }
    }
    if (!accessComplete) {
      // Best effort for display: whatever was readable still counts as granted.
      accessProjectIds.length = 0
      for (const project of projects) {
        const access = raw.projectAccess[project.id]
        if (Array.isArray(access) && rows(access).some((entry) => str(entry.id) === serviceId || str(entry.serviceId) === serviceId)) accessProjectIds.push(project.id)
      }
    }
    config = {
      service: normalizeTemperature(mapService(row)),
      scheduling: mapScheduling(raw.scheduling),
      targets,
      accessProjectIds: [...new Set(accessProjectIds)].sort()
    }
    dataProtection = mapDataProtection(raw.dataProtection)
  }

  const deployments: DeploymentInfo[] = []
  const seen = new Set<string>()
  for (const [endpointId, list] of Object.entries(raw.endpointDeployments)) {
    for (const row of rows(list)) {
      const id = str(row.id)
      if (!id || seen.has(id)) continue
      seen.add(id)
      deployments.push({
        id,
        displayName: str(row.displayName, str(row.providerModelId, id)),
        providerModelId: str(row.providerModelId),
        runtimeEndpointId: optStr(row.runtimeEndpointId) ?? endpointId,
        externalProviderId: optStr(row.externalProviderId),
        external: optStr(row.externalProviderId) !== null,
        enabled: bool(row.enabled, true),
        loaded: bool(row.loaded, false),
        healthStatus: str(row.healthStatus, 'UNKNOWN'),
        maxConcurrency: num(row.maxConcurrency, 1),
        contextLength: optNum(row.contextLength)
      })
    }
  }
  for (const [providerId, list] of Object.entries(raw.providerModels)) {
    for (const row of rows(list)) {
      const id = str(row.id)
      if (!id || seen.has(id)) continue
      seen.add(id)
      deployments.push({
        id,
        displayName: str(row.displayName, str(row.providerModelId, id)),
        providerModelId: str(row.providerModelId),
        runtimeEndpointId: null,
        externalProviderId: optStr(row.externalProviderId) ?? providerId,
        external: true,
        enabled: bool(row.enabled, true),
        loaded: true,
        healthStatus: str(row.healthStatus, 'UNKNOWN'),
        maxConcurrency: num(row.maxConcurrency, 1),
        contextLength: optNum(row.contextLength)
      })
    }
  }
  deployments.sort((a, b) => Number(a.external) - Number(b.external) || byString((x: DeploymentInfo) => x.displayName)(a, b) || byString((x: DeploymentInfo) => x.id)(a, b))

  const endpoints: EndpointInfo[] = rows(raw.endpoints)
    .map((row) => ({
      id: str(row.id),
      displayName: str(row.displayName, str(row.id)),
      runtimeType: str(row.runtimeType, 'UNKNOWN'),
      enabled: bool(row.enabled, true),
      healthStatus: str(row.healthStatus, 'UNKNOWN'),
      addressLabel: safeAddressLabel(str(row.baseUrl))
    }))
    .filter((row) => row.id !== '')
    .sort(byString((item: EndpointInfo) => item.displayName + '\u0000' + item.id))

  const providers: ProviderInfo[] = rows(raw.providers)
    .map((row) => ({
      id: str(row.id),
      displayName: str(row.displayName, str(row.id)),
      providerType: str(row.providerType, 'UNKNOWN'),
      enabled: bool(row.enabled, true),
      healthStatus: str(row.healthStatus, 'UNKNOWN'),
      apiKeyConfigured: bool(row.apiKeyConfigured, false),
      zeroRetentionVerified: bool(row.zeroRetentionVerified, false)
    }))
    .filter((row) => row.id !== '')
    .sort(byString((item: ProviderInfo) => item.displayName + '\u0000' + item.id))

  const maskers: MaskerInfo[] = rows(raw.maskers)
    .map((row) => ({
      deploymentId: str(row.deploymentId),
      displayName: str(row.displayName, str(row.deploymentId)),
      providerModelId: str(row.providerModelId),
      sourceType: str(row.sourceType, 'UNKNOWN'),
      sourceName: str(row.sourceName),
      eligible: bool(row.eligible, false),
      trustReason: str(row.trustReason)
    }))
    .filter((row) => row.deploymentId !== '')
    .sort(byString((item: MaskerInfo) => item.displayName + '\u0000' + item.deploymentId))

  const orgEffective = isRec(raw.orgDataProtection) && isRec(raw.orgDataProtection.effective) ? raw.orgDataProtection.effective : null
  const orgDataProtection: OrgDataProtectionInfo | null = orgEffective ? { mode: str(orgEffective.mode, 'UNKNOWN'), externalAction: str(orgEffective.externalAction, 'UNKNOWN') } : null
  // Detect a backend without V54: rows exist but none carries the systemPromptTemplate key (§8.6).
  const targetRows = serviceId === null ? [] : rows(raw.targets)
  const targetPrompt = targetRows.length === 0 || targetRows.some((row) => 'systemPromptTemplate' in row)

  return {
    organizationId: raw.organizationId,
    serviceId,
    promptVersion,
    config,
    accessComplete,
    catalog: { services, deployments, endpoints, providers, projects, maskers },
    dataProtection,
    loadedAt: raw.loadedAt,
    unavailable: [...raw.unavailable],
    orgDataProtection,
    projectPoliciesComplete: isRec(raw.orgDataProtection),
    projectsHidden,
    features: { targetPrompt }
  }
}
