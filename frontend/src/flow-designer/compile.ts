import { branchPromptNodeId, targetKeyOf } from './catalog'
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

/** Field-aware comparison: capabilities and prompts (service and target) are compared in their normalized form. */
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

/**
 * §7.3: compiles the graph into the server configuration. Only the existence and ids of nodes matter (never
 * coordinates, trunk order or connectivity): a missing optional node means the server default.
 */
export function compile(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowConfigState {
  const base = snapshot.config
  const nodesById = new Map<string, FlowNode>(graph.nodes.map((n) => [n.id, n]))
  const S = nodesById.get('start')?.props ?? {}
  const G = nodesById.get('guardrail')
  const M = nodesById.get('masker')
  const P = nodesById.get('prompt')
  const Q = nodesById.get('params')
  const C = nodesById.get('cache')
  const R = nodesById.get('router')
  const existingService = (graph.serviceId ?? snapshot.serviceId) !== null
  const maskerLinked = graph.edges.some((e) => e.kind === 'masker' && e.from.nodeId === 'masker' && e.to.nodeId === 'guardrail')
  const maskerId = M ? M.props.deploymentId : null
  const g = G ? G.props : {}
  const q = Q ? Q.props : {}
  const c = C ? C.props : {}
  const r = R ? R.props : {}

  const temperaturePolicy = (Q ? text(q.temperaturePolicy, 'REQUEST') : 'REQUEST') as ServiceState['temperaturePolicy']
  const service: ServiceState = {
    serviceKey: existingService ? base.service.serviceKey : text(S.serviceKey, '').trim(),
    displayName: text(S.displayName, base.service.displayName),
    enabled: bool(S.enabled, base.service.enabled),
    dataBoundaryPolicy: (G ? text(g.dataBoundaryPolicy, 'EXTERNAL_ALLOWED') : 'EXTERNAL_ALLOWED') as ServiceState['dataBoundaryPolicy'],
    requiredCapabilitiesJson: canonicalCapabilities(text(S.requiredCapabilitiesJson, base.service.requiredCapabilitiesJson)),
    currency: text(S.currency, base.service.currency) as ServiceState['currency'],
    inputPricePerMillion: numeric(S.inputPricePerMillion, base.service.inputPricePerMillion),
    outputPricePerMillion: numeric(S.outputPricePerMillion, base.service.outputPricePerMillion),
    guardrailAction: (G ? text(g.guardrailAction, 'ALLOW') : 'ALLOW') as ServiceState['guardrailAction'],
    maskerDeploymentId: G && M && maskerLinked && typeof maskerId === 'string' && maskerId !== '' ? maskerId : null,
    maskPersonNames: G ? bool(g.maskPersonNames, base.service.maskPersonNames) : base.service.maskPersonNames,
    systemPromptTemplate: P ? normalizePrompt(P.props.systemPromptTemplate as string | null | undefined) : null,
    temperaturePolicy,
    temperatureValue: Q && temperaturePolicy === 'FIXED' ? nullableNumber(q.temperatureValue) : null,
    reasoningEffort: (Q ? text(q.reasoningEffort, 'REQUEST') : 'REQUEST') as ServiceState['reasoningEffort'],
    openAiFastMode: Q ? bool(q.openAiFastMode, false) : false,
    responseCacheEnabled: C !== undefined,
    responseCacheTtlSeconds: C ? numeric(c.responseCacheTtlSeconds, base.service.responseCacheTtlSeconds) : base.service.responseCacheTtlSeconds,
    failoverPolicy: (R ? text(r.failoverPolicy, 'STRICT') : 'STRICT') as ServiceState['failoverPolicy'],
    retryPolicy: (R ? text(r.retryPolicy, 'SAFE') : 'SAFE') as ServiceState['retryPolicy'],
    allowDegraded: R ? bool(r.allowDegraded, false) : false,
  }

  const scheduling: SchedulingState = R
    ? {
        mode: text(r.schedulingMode, 'IMMEDIATE') as SchedulingState['mode'],
        localWaitMs: numeric(r.localWaitMs, base.scheduling.localWaitMs),
        maxWaitMs: numeric(r.maxWaitMs, base.scheduling.maxWaitMs),
        maxQueuedRequests: numeric(r.maxQueuedRequests, base.scheduling.maxQueuedRequests),
        maxQueuedBytes: numeric(r.maxQueuedBytes, base.scheduling.maxQueuedBytes),
        maxQueuedPerKey: numeric(r.maxQueuedPerKey, base.scheduling.maxQueuedPerKey),
      }
    : { ...base.scheduling, mode: 'IMMEDIATE' }

  const targets: TargetState[] = []
  for (const node of graph.nodes) {
    if (node.type !== 'llm') continue
    const deploymentId = node.refId ?? (node.id.startsWith('deployment:') ? node.id.slice('deployment:'.length) : node.id)
    const prompt = nodesById.get(branchPromptNodeId(deploymentId))
    targets.push({
      key: targetKeyOf(node),
      id: node.targetId,
      deploymentId,
      priority: numeric(node.props.priority, 1),
      weight: numeric(node.props.weight, 100),
      degraded: bool(node.props.degraded, false),
      enabled: bool(node.props.enabled, true),
      maxConcurrencyOverride: nullableNumber(node.props.maxConcurrencyOverride),
      followModelChanges: bool(node.props.followModelChanges, true),
      systemPromptTemplate: prompt ? normalizePrompt(prompt.props.systemPromptTemplate as string | null | undefined) : null,
    })
  }
  targets.sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0))

  const access = new Set<string>()
  for (const e of graph.edges) {
    if (e.kind !== 'entry' || e.from.nodeId !== 'start') continue
    const project = nodesById.get(e.to.nodeId)
    if (project && project.type === 'project' && project.refId) access.add(project.refId)
  }

  return { service, scheduling, targets, accessProjectIds: [...access].sort() }
}
