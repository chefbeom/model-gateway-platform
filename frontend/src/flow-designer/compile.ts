import type {
  FlowConfigState,
  FlowGraph,
  FlowNode,
  FlowPropValue,
  SchedulingState,
  ServiceFlowSnapshot,
  ServiceState,
  TargetState,
} from './types'

export function normalizePrompt(value: string | null | undefined): string | null {
  if (value === null || value === undefined) return null
  const trimmed = String(value).trim()
  return trimmed === '' ? null : trimmed
}

/** Sorted, de-duplicated JSON string array when `json` is valid; otherwise the original text. */
export function canonicalCapabilities(json: string): string {
  try {
    const parsed: unknown = JSON.parse(json)
    if (Array.isArray(parsed) && parsed.every((x) => typeof x === 'string')) {
      return JSON.stringify([...new Set(parsed as string[])].sort())
    }
  } catch {
    // fall through: keep the raw text so validation can report it
  }
  return json
}

export function sameValue(a: unknown, b: unknown): boolean {
  if (typeof a === 'number' && typeof b === 'number') {
    if (Number.isNaN(a) && Number.isNaN(b)) return true
    return Math.abs(a - b) < 1e-9
  }
  const x = a === undefined ? null : a
  const y = b === undefined ? null : b
  return x === y
}

/** Field-aware comparison: capabilities and prompts are compared in their normalized form. */
export function sameFieldValue(field: string, a: unknown, b: unknown): boolean {
  if (field === 'requiredCapabilitiesJson') {
    return sameValue(typeof a === 'string' ? canonicalCapabilities(a) : a, typeof b === 'string' ? canonicalCapabilities(b) : b)
  }
  if (field === 'systemPromptTemplate') {
    return sameValue(normalizePrompt(a as string | null | undefined), normalizePrompt(b as string | null | undefined))
  }
  return sameValue(a, b)
}

function text(value: FlowPropValue | undefined, fallback: string): string {
  return value === undefined || value === null ? fallback : String(value)
}
function numeric(value: FlowPropValue | undefined, fallback: number): number {
  if (value === undefined) return fallback
  return Number(value)
}
function bool(value: FlowPropValue | undefined, fallback: boolean): boolean {
  return value === undefined || value === null ? fallback : Boolean(value)
}
function nullableNumber(value: FlowPropValue | undefined): number | null {
  if (value === undefined || value === null || value === '') return null
  return Number(value)
}

function propsOf(graph: FlowGraph, id: string): Record<string, FlowPropValue> {
  const node = graph.nodes.find((n) => n.id === id)
  return node ? node.props : {}
}

export function compile(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowConfigState {
  const base = snapshot.config
  const s = propsOf(graph, 'service')
  const g = propsOf(graph, 'guardrail')
  const p = propsOf(graph, 'prompt')
  const c = propsOf(graph, 'cache')
  const r = propsOf(graph, 'router')

  const nodesById = new Map<string, FlowNode>(graph.nodes.map((n) => [n.id, n]))
  const existingService = (graph.serviceId ?? snapshot.serviceId) !== null

  const temperaturePolicy = text(p.temperaturePolicy, base.service.temperaturePolicy) as ServiceState['temperaturePolicy']
  const maskerNode = nodesById.get('masker')
  const maskerConnected = graph.edges.some((e) => e.kind === 'masker' && e.from.nodeId === 'masker' && e.to.nodeId === 'guardrail')
  const maskerId = maskerNode ? maskerNode.props.deploymentId : null
  const maskerDeploymentId = maskerNode && maskerConnected && typeof maskerId === 'string' && maskerId !== '' ? maskerId : null

  const service: ServiceState = {
    serviceKey: existingService ? base.service.serviceKey : text(s.serviceKey, '').trim(),
    displayName: text(s.displayName, base.service.displayName),
    enabled: bool(s.enabled, base.service.enabled),
    dataBoundaryPolicy: text(s.dataBoundaryPolicy, base.service.dataBoundaryPolicy) as ServiceState['dataBoundaryPolicy'],
    requiredCapabilitiesJson: canonicalCapabilities(text(s.requiredCapabilitiesJson, base.service.requiredCapabilitiesJson)),
    currency: text(s.currency, base.service.currency) as ServiceState['currency'],
    inputPricePerMillion: numeric(s.inputPricePerMillion, base.service.inputPricePerMillion),
    outputPricePerMillion: numeric(s.outputPricePerMillion, base.service.outputPricePerMillion),
    guardrailAction: text(g.guardrailAction, base.service.guardrailAction) as ServiceState['guardrailAction'],
    maskerDeploymentId,
    maskPersonNames: bool(g.maskPersonNames, base.service.maskPersonNames),
    systemPromptTemplate: normalizePrompt(p.systemPromptTemplate === undefined ? base.service.systemPromptTemplate : (p.systemPromptTemplate as string | null)),
    temperaturePolicy,
    temperatureValue: temperaturePolicy === 'FIXED' ? nullableNumber(p.temperatureValue) : null,
    reasoningEffort: text(p.reasoningEffort, base.service.reasoningEffort) as ServiceState['reasoningEffort'],
    openAiFastMode: bool(p.openAiFastMode, base.service.openAiFastMode),
    responseCacheEnabled: bool(c.responseCacheEnabled, base.service.responseCacheEnabled),
    responseCacheTtlSeconds: numeric(c.responseCacheTtlSeconds, base.service.responseCacheTtlSeconds),
    failoverPolicy: text(r.failoverPolicy, base.service.failoverPolicy) as ServiceState['failoverPolicy'],
    retryPolicy: text(r.retryPolicy, base.service.retryPolicy) as ServiceState['retryPolicy'],
    allowDegraded: bool(r.allowDegraded, base.service.allowDegraded),
  }

  const scheduling: SchedulingState = {
    mode: text(r.schedulingMode, base.scheduling.mode) as SchedulingState['mode'],
    localWaitMs: numeric(r.localWaitMs, base.scheduling.localWaitMs),
    maxWaitMs: numeric(r.maxWaitMs, base.scheduling.maxWaitMs),
    maxQueuedRequests: numeric(r.maxQueuedRequests, base.scheduling.maxQueuedRequests),
    maxQueuedBytes: numeric(r.maxQueuedBytes, base.scheduling.maxQueuedBytes),
    maxQueuedPerKey: numeric(r.maxQueuedPerKey, base.scheduling.maxQueuedPerKey),
  }

  const bindingByTarget = new Map<string, string>()
  for (const e of [...graph.edges].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))) {
    if (e.kind === 'binding' && !bindingByTarget.has(e.from.nodeId)) bindingByTarget.set(e.from.nodeId, e.to.nodeId)
  }
  const targets: TargetState[] = []
  for (const node of graph.nodes) {
    if (node.type !== 'target') continue
    const depNodeId = bindingByTarget.get(node.id)
    if (!depNodeId) continue
    const dep = nodesById.get(depNodeId)
    const deploymentId = dep ? (dep.refId ?? depNodeId.slice('deployment:'.length)) : depNodeId.slice('deployment:'.length)
    targets.push({
      key: node.id,
      id: node.refId,
      deploymentId,
      priority: numeric(node.props.priority, 1),
      weight: numeric(node.props.weight, 100),
      degraded: bool(node.props.degraded, false),
      enabled: bool(node.props.enabled, true),
      maxConcurrencyOverride: nullableNumber(node.props.maxConcurrencyOverride),
      followModelChanges: bool(node.props.followModelChanges, true),
    })
  }
  targets.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0))

  const access = new Set<string>()
  for (const e of graph.edges) {
    if (e.kind !== 'access' || e.to.nodeId !== 'service') continue
    const project = nodesById.get(e.from.nodeId)
    if (project && project.type === 'project' && project.refId) access.add(project.refId)
  }

  return { service, scheduling, targets, accessProjectIds: [...access].sort() }
}
