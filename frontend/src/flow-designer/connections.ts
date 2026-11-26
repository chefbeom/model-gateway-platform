// Connection-centric view ("연결 중심 배치") of the Workflow Designer. Pure TypeScript: no Vue, no DOM, no storage.
// A connection node IS the derived host node ('endpoint:<id>' | 'provider:<id>', the same id as ConnectionMeta.id).
// This module only groups, projects and describes: it never changes anything compile() reads.
// Import direction: catalog, layout, palette, workflow only (graphOps imports this module, never the reverse).
import { NODE_TYPES, branchPromptNodeId, edgeId, hostNodeIdFor, llmNodeId, toneOf } from './catalog'
import { COLUMN_WIDTH, NODE_WIDTH, ROW_GAP, autoLayout, nodeRect, nodeSize, snapUp } from './layout'
import { endpointConnectionMeta, groupModelsByConnection, modelItem, providerConnectionMeta } from './palette'
import { analyzeWorkflow, dependencyClosure, isBranchPrompt } from './workflow'
import type {
  ConnectionGroupView,
  ConnectionHealth,
  ConnectionMeta,
  ConnectionModelChoices,
  ConnectionView,
  FlowGraph,
  FlowNode,
  FlowViewMode,
  HealthTone,
  NodeView,
  Point,
  PortRef,
  PortSpec,
  Rect,
  ServiceFlowSnapshot,
  ViewEdge,
} from './types'

const CONNECTION_ID = /^(endpoint|provider):[A-Za-z0-9-]{1,64}$/
const UNKNOWN_LABEL = '알 수 없는 연결'
const REQUEST_KINDS: ReadonlySet<string> = new Set(['flow', 'route', 'access'])

/** Ports of a connection node in the connection view: requests come in on the left, models hang off the right. */
export const CONNECTION_PORTS: PortSpec[] = [
  { id: 'in', direction: 'in', type: 'flow', label: '요청', multiple: true },
  { id: 'out', direction: 'out', type: 'flow', label: '모델', multiple: true },
]
/** An LLM in the connection view: its connection is drawn in front of it, so the 실행 위치 port is hidden. */
const LLM_PORTS_IN_CONNECTION_VIEW: PortSpec[] = NODE_TYPES.llm.ports.filter((p) => p.id !== 'host')

export const CONNECTION_HEALTH_LABEL: Record<ConnectionHealth, string> = {
  online: '온라인',
  degraded: '주의',
  offline: '오프라인',
  disabled: '비활성',
  unknown: '상태 미확인',
}
const HEALTH_TONE: Record<ConnectionHealth, HealthTone> = { online: 'healthy', degraded: 'warning', offline: 'error', disabled: 'muted', unknown: 'unknown' }
const TONE_HEALTH: Record<HealthTone, ConnectionHealth> = { healthy: 'online', warning: 'degraded', error: 'offline', muted: 'disabled', unknown: 'unknown' }

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }
function num(value: unknown, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : fallback
}
function overlaps(a: Rect, b: Rect, gap: number): boolean {
  return a.x < b.x + b.width + gap && a.x + a.width + gap > b.x && a.y < b.y + b.height + gap && a.y + a.height + gap > b.y
}
/** Server dispatch order inside one connection (all its models share local/external): priority asc, weight desc, id. */
function dispatchOrder(a: FlowNode, b: FlowNode): number {
  return num(a.props.priority, Infinity) - num(b.props.priority, Infinity) || num(b.props.weight, 0) - num(a.props.weight, 0) || cmp(a.id, b.id)
}

/* ------------------------------------------------------------------ */
/* Identity                                                            */
/* ------------------------------------------------------------------ */

export function isConnectionId(id: string): boolean {
  return CONNECTION_ID.test(id)
}

export function isConnectionNode(node: FlowNode | null | undefined): boolean {
  return !!node && (node.type === 'endpoint' || node.type === 'provider')
}

export function deploymentIdOfNode(node: FlowNode): string {
  return node.refId ?? (node.id.startsWith('deployment:') ? node.id.slice('deployment:'.length) : node.id)
}

/** The connection (host node id) a catalog deployment belongs to; null when it has neither endpoint nor provider. */
export function connectionIdForDeployment(deploymentId: string, snapshot: ServiceFlowSnapshot): string | null {
  const deployment = snapshot.catalog.deployments.find((d) => d.id === deploymentId)
  return deployment ? hostNodeIdFor(deployment) : null
}

/** Same ConnectionMeta as palette.connectionOf for a known endpoint/provider; an "unknown connection" otherwise. */
export function connectionMetaOf(connectionId: string, snapshot: ServiceFlowSnapshot): ConnectionMeta {
  const refId = connectionId.slice(connectionId.indexOf(':') + 1)
  if (connectionId.startsWith('provider:')) {
    const provider = snapshot.catalog.providers.find((p) => p.id === refId)
    return provider ? providerConnectionMeta(provider) : { id: connectionId, kind: 'provider', label: UNKNOWN_LABEL, detail: '', tone: 'unknown', enabled: true }
  }
  const endpoint = snapshot.catalog.endpoints.find((e) => e.id === refId)
  return endpoint ? endpointConnectionMeta(endpoint) : { id: connectionId, kind: 'endpoint', label: UNKNOWN_LABEL, detail: '', tone: 'unknown', enabled: true }
}

export function connectionHealth(connectionId: string, snapshot: ServiceFlowSnapshot): ConnectionHealth {
  const refId = connectionId.slice(connectionId.indexOf(':') + 1)
  if (connectionId.startsWith('provider:')) {
    const provider = snapshot.catalog.providers.find((p) => p.id === refId)
    if (!provider) return 'unknown'
    if (!provider.enabled) return 'disabled'
    if (!provider.apiKeyConfigured) return 'offline'
    return TONE_HEALTH[toneOf(provider.enabled, provider.healthStatus)]
  }
  const endpoint = snapshot.catalog.endpoints.find((e) => e.id === refId)
  if (!endpoint) return 'unknown'
  if (!endpoint.enabled) return 'disabled'
  return TONE_HEALTH[toneOf(endpoint.enabled, endpoint.healthStatus)]
}

/* ------------------------------------------------------------------ */
/* Grouping                                                            */
/* ------------------------------------------------------------------ */

export interface ConnectionGroupModel {
  connectionId: string
  host: FlowNode
  /** Dispatch order. */
  llms: FlowNode[]
  /** Model-specific prompts of those LLMs (only the ones that exist). */
  prompts: FlowNode[]
}

/** Every host node with the LLMs that point at it through the derived `host` edges. Hosts sorted by id. */
export function connectionGroups(graph: FlowGraph): ConnectionGroupModel[] {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const members = new Map<string, FlowNode[]>()
  for (const e of graph.edges) {
    if (e.kind !== 'host') continue
    const llm = byId.get(e.from.nodeId)
    if (!llm || llm.type !== 'llm' || !isConnectionNode(byId.get(e.to.nodeId))) continue
    const list = members.get(e.to.nodeId) ?? []
    if (!list.includes(llm)) list.push(llm)
    members.set(e.to.nodeId, list)
  }
  return graph.nodes
    .filter((n) => isConnectionNode(n))
    .sort((a, b) => cmp(a.id, b.id))
    .map((host) => {
      const llms = [...(members.get(host.id) ?? [])].sort(dispatchOrder)
      const prompts = llms
        .map((llm) => byId.get(branchPromptNodeId(deploymentIdOfNode(llm))))
        .filter((p): p is FlowNode => p !== undefined)
      return { connectionId: host.id, host, llms, prompts }
    })
}

/** The connection of a host, LLM or model-specific prompt node; null for anything else or an LLM without host. */
export function connectionOfNode(graph: FlowGraph, nodeId: string): string | null {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return null
  if (isConnectionNode(node)) return node.id
  const llmId = node.type === 'llm' ? node.id : isBranchPrompt(node) ? llmNodeId(node.id.slice('prompt:'.length)) : null
  if (!llmId) return null
  const e = graph.edges.find((x) => x.kind === 'host' && x.from.nodeId === llmId)
  return e && graph.nodes.some((n) => n.id === e.to.nodeId && isConnectionNode(n)) ? e.to.nodeId : null
}

/** First node of an LLM's branch: its model-specific prompt when it exists, the LLM otherwise. */
export function headOf(graph: FlowGraph, llm: FlowNode): string {
  const prompt = branchPromptNodeId(deploymentIdOfNode(llm))
  return graph.nodes.some((n) => n.id === prompt) ? prompt : llm.id
}

/** A request (flow, route or access) edge enters this node's input. */
export function hasIncomingRequest(graph: FlowGraph, nodeId: string): boolean {
  return graph.edges.some((e) => e.to.nodeId === nodeId && e.to.portId === 'in' && REQUEST_KINDS.has(e.kind))
}

/** Branch heads of the connection that receive no request edge, in dispatch order. */
export function unroutedHeads(graph: FlowGraph, group: ConnectionGroupModel): string[] {
  return group.llms.map((llm) => headOf(graph, llm)).filter((head) => !hasIncomingRequest(graph, head))
}

/** Where a model of this connection should be fed from: a routed sibling's source, else the router, else the trunk tail. */
export function requestSourceFor(graph: FlowGraph, group: ConnectionGroupModel, snapshot?: ServiceFlowSnapshot): PortRef | null {
  for (const llm of group.llms) {
    const head = headOf(graph, llm)
    const e = graph.edges.find((x) => x.to.nodeId === head && x.to.portId === 'in' && (x.kind === 'flow' || x.kind === 'route'))
    if (e) return { nodeId: e.from.nodeId, portId: e.from.portId }
  }
  const analysis = analyzeWorkflow(graph, snapshot)
  if (analysis.router) return { nodeId: analysis.router, portId: 'out' }
  if (analysis.tail) return { nodeId: analysis.tail, portId: 'out' }
  return null
}

/** One extra column (for model-specific prompts) between connections and models once any grouped LLM has a prompt. */
export function connectionLaneOffset(graph: FlowGraph): number {
  return connectionGroups(graph).some((g) => g.prompts.length > 0) ? COLUMN_WIDTH : 0
}

/* ------------------------------------------------------------------ */
/* Projection (§5.3)                                                   */
/* ------------------------------------------------------------------ */

export function buildConnectionView(graph: FlowGraph, snapshot: ServiceFlowSnapshot): ConnectionView {
  const groups = connectionGroups(graph)
  const laneOffset = groups.some((g) => g.prompts.length > 0) ? COLUMN_WIDTH : 0
  const groupOf: Record<string, string> = {}
  for (const g of groups) for (const n of [g.host, ...g.llms, ...g.prompts]) groupOf[n.id] = g.connectionId
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))

  const items = groups.map((group) => {
    const rows = group.llms.map((llm) => {
      const prompt = byId.get(branchPromptNodeId(deploymentIdOfNode(llm)))
      return { llm, prompt, height: Math.max(nodeSize(llm).height, prompt ? nodeSize(prompt).height : 0) }
    })
    const offsets: number[] = []
    let offset = 0
    rows.forEach((row, i) => {
      if (i > 0) offset = snapUp(offset + rows[i - 1].height + ROW_GAP)
      offsets.push(offset)
    })
    const content = rows.length > 0 ? offsets[rows.length - 1] + rows[rows.length - 1].height : 0
    const anchor: Point = group.llms.length > 0
      ? { x: Math.min(...group.llms.map((n) => n.x)) - laneOffset, y: Math.min(...group.llms.map((n) => n.y)) }
      : { x: group.host.x, y: group.host.y }
    return {
      group,
      anchor,
      rows,
      offsets,
      width: group.llms.length > 0 ? COLUMN_WIDTH + laneOffset + NODE_WIDTH : NODE_WIDTH,
      height: Math.max(nodeSize(group.host).height, content),
    }
  })
  items.sort((a, b) => a.anchor.y - b.anchor.y || a.anchor.x - b.anchor.x || cmp(a.group.connectionId, b.group.connectionId))

  const obstacles = graph.nodes.filter((n) => groupOf[n.id] === undefined).map(nodeRect)
  const placed: Rect[] = []
  const positions: Record<string, Point> = {}
  const views: ConnectionGroupView[] = []
  const dimmed: string[] = []
  for (const item of items) {
    let y = item.anchor.y
    for (let i = 0; i < 500; i += 1) {
      const probe: Rect = { x: item.anchor.x, y, width: item.width, height: item.height }
      const hit = placed.find((r) => overlaps(probe, r, ROW_GAP / 2)) ?? obstacles.find((r) => overlaps(probe, r, ROW_GAP / 2))
      if (!hit) break
      y = snapUp(hit.y + hit.height + ROW_GAP)
    }
    const frame: Rect = { x: item.anchor.x, y, width: item.width, height: item.height }
    placed.push(frame)
    const g = item.group
    positions[g.host.id] = { x: item.anchor.x, y }
    item.rows.forEach((row, i) => {
      const rowY = y + item.offsets[i]
      if (row.prompt) positions[row.prompt.id] = { x: item.anchor.x + COLUMN_WIDTH, y: rowY }
      positions[row.llm.id] = { x: item.anchor.x + COLUMN_WIDTH + laneOffset, y: rowY }
    })
    const health = connectionHealth(g.connectionId, snapshot)
    if (health === 'offline' || health === 'disabled') for (const n of [...g.llms, ...g.prompts]) dimmed.push(n.id)
    views.push({
      connectionId: g.connectionId,
      kind: g.host.type === 'provider' ? 'provider' : 'endpoint',
      llmNodeIds: g.llms.map((n) => n.id),
      promptNodeIds: g.prompts.map((n) => n.id),
      health,
      frame,
    })
  }
  return { groups: views, positions, edges: projectEdges(graph, groups, groupOf), groupOf, dimmed: dimmed.sort(cmp) }
}

/** §3.1: host edges hidden, request edges into a group redrawn from the connection, plus drawn-only helper lines. */
function projectEdges(graph: FlowGraph, groups: ConnectionGroupModel[], groupOf: Record<string, string>): ViewEdge[] {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const llmOf = (id: string): string => {
    const n = byId.get(id)
    return n && isBranchPrompt(n) ? llmNodeId(n.id.slice('prompt:'.length)) : id
  }
  const sources = new Map<string, ViewEdge>()
  const drawn: ViewEdge[] = []
  const fed = new Set<string>()
  for (const e of graph.edges) {
    if (e.kind === 'host') continue
    const target = groupOf[e.to.nodeId]
    const intoModel = target !== undefined && e.to.nodeId !== target && REQUEST_KINDS.has(e.kind) && groupOf[e.from.nodeId] !== target
    if (!intoModel) {
      drawn.push({ ...e, from: { ...e.from }, to: { ...e.to }, interactive: true, detached: false })
      continue
    }
    drawn.push({ ...e, from: { nodeId: target, portId: 'out' }, to: { ...e.to }, interactive: true, detached: false })
    fed.add(llmOf(e.to.nodeId))
    const to: PortRef = { nodeId: target, portId: 'in' }
    const id = `view:${edgeId(e.from, to)}`
    if (!sources.has(id)) sources.set(id, { id, kind: e.kind, from: { ...e.from }, to, locked: true, interactive: false, detached: false })
  }
  const detached: ViewEdge[] = []
  for (const g of groups) {
    for (const llm of g.llms) {
      if (fed.has(llm.id)) continue
      const from: PortRef = { nodeId: g.connectionId, portId: 'out' }
      const to: PortRef = { nodeId: headOf(graph, llm), portId: 'in' }
      detached.push({ id: `view:${edgeId(from, to)}`, kind: 'route', from, to, locked: true, interactive: false, detached: true })
    }
  }
  return [...sources.values(), ...detached, ...drawn]
}

/* ------------------------------------------------------------------ */
/* Interaction helpers                                                 */
/* ------------------------------------------------------------------ */

/** §4.3: grouped ids expand to their whole group (never split); other ids follow the dependency rules. Sorted. */
export function connectionDragIds(graph: FlowGraph, view: ConnectionView, ids: string[], alone: boolean): string[] {
  const out = new Set<string>()
  for (const id of ids) {
    const connectionId = view.groupOf[id]
    if (connectionId !== undefined) {
      const group = view.groups.find((g) => g.connectionId === connectionId)
      out.add(connectionId)
      for (const member of [...(group?.llmNodeIds ?? []), ...(group?.promptNodeIds ?? [])]) out.add(member)
    } else if (alone) {
      out.add(id)
    } else {
      for (const dep of dependencyClosure(graph, [id])) if (view.groupOf[dep] === undefined) out.add(dep)
    }
  }
  return [...out].sort(cmp)
}

export function viewPortsOf(node: FlowNode, view: FlowViewMode): PortSpec[] {
  if (view === 'connection') {
    if (isConnectionNode(node)) return CONNECTION_PORTS
    if (node.type === 'llm') return LLM_PORTS_IN_CONNECTION_VIEW
  }
  return NODE_TYPES[node.type].ports
}

/** §5.1: card of a connection node in the connection view. */
export function describeConnectionNode(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): NodeView {
  const meta = connectionMetaOf(node.id, snapshot)
  const health = connectionHealth(node.id, snapshot)
  const llms = connectionGroups(graph).find((g) => g.connectionId === node.id)?.llms ?? []
  const enabled = llms.filter((n) => n.props.enabled !== false).length
  const priorities = llms.map((n) => num(n.props.priority, Number.NaN)).filter((p) => Number.isFinite(p))
  const external = node.type === 'provider'
  const summary = [llms.length > 0 ? `모델 ${llms.length}개 · 사용 ${enabled}개` : '모델을 추가하세요']
  if (priorities.length > 0) {
    const min = Math.min(...priorities)
    const max = Math.max(...priorities)
    summary.push(min === max ? `우선순위 P${min}` : `우선순위 P${min}–P${max}`)
  }
  return {
    title: meta.label,
    subtitle: `${external ? '외부' : '로컬'}${meta.detail ? ` · ${meta.detail}` : ''}`,
    typeLabel: '연결',
    category: 'infra',
    icon: NODE_TYPES[node.type].icon,
    tone: HEALTH_TONE[health],
    badges: llms.length === 0 ? [CONNECTION_HEALTH_LABEL[health], '모델 없음'] : [CONNECTION_HEALTH_LABEL[health]],
    summary,
    locked: false,
    protected: false,
    lockReason: null,
    promptScope: null,
  }
}

/** §4.2: the models of one connection for the "모델 추가" list (palette order; inactive hidden unless asked). */
export function connectionModelChoices(snapshot: ServiceFlowSnapshot, graph: FlowGraph, connectionId: string, showInactive: boolean): ConnectionModelChoices {
  const own = snapshot.catalog.deployments.filter((d) => hostNodeIdFor(d) === connectionId)
  const ordered = groupModelsByConnection(own, snapshot.catalog).flatMap((g) => g.items)
  let hiddenCount = 0
  const items = ordered.map((d) => modelItem(d, graph, snapshot)).filter((m) => {
    if (showInactive || m.onCanvas || m.availability !== 'inactive') return true
    hiddenCount += 1
    return false
  })
  return { connection: connectionMetaOf(connectionId, snapshot), items, hiddenCount }
}

/** Where a connection added without a drop point goes: below the lowest group, else next to the router / trunk tail. */
export function defaultConnectionSpot(graph: FlowGraph, snapshot: ServiceFlowSnapshot): Point {
  const view = buildConnectionView(graph, snapshot)
  if (view.groups.length > 0) {
    return {
      x: Math.min(...view.groups.map((g) => g.frame.x)),
      y: snapUp(Math.max(...view.groups.map((g) => g.frame.y + g.frame.height)) + ROW_GAP),
    }
  }
  const analysis = analyzeWorkflow(graph, snapshot)
  const sourceId = analysis.router ?? analysis.tail
  const source = sourceId ? graph.nodes.find((n) => n.id === sourceId) : undefined
  if (source) return { x: source.x + COLUMN_WIDTH, y: source.y }
  let right: FlowNode | undefined
  for (const n of graph.nodes) {
    if (n.type !== 'start' && n.type !== 'project' && n.type !== 'policy') continue
    if (!right || n.x > right.x || (n.x === right.x && n.y < right.y)) right = n
  }
  return right ? { x: right.x + COLUMN_WIDTH, y: 0 } : { x: COLUMN_WIDTH * 3, y: 0 }
}

/** §5.4: the model-centric auto layout with the LLM lane regrouped by connection (first-dispatch order). */
export function autoLayoutByConnection(graph: FlowGraph, snapshot?: ServiceFlowSnapshot): FlowGraph {
  const laid = autoLayout(graph, snapshot)
  const analysis = analyzeWorkflow(laid, snapshot)
  if (analysis.branches.length === 0) return laid
  const byId = new Map(laid.nodes.map((n) => [n.id, n]))
  const hostOf = (llmId: string): string | null => laid.edges.find((e) => e.kind === 'host' && e.from.nodeId === llmId)?.to.nodeId ?? null
  const order: string[] = []
  const members = new Map<string, typeof analysis.branches>()
  for (const branch of analysis.branches) {
    const key = hostOf(branch.llmNodeId) ?? branch.llmNodeId
    const list = members.get(key)
    if (list) list.push(branch)
    else { members.set(key, [branch]); order.push(key) }
  }
  const positions = new Map<string, Point>()
  const firstY = new Map<string, number>()
  let y = Math.min(...analysis.branches.map((b) => (byId.get(b.llmNodeId) as FlowNode).y))
  for (const key of order) {
    for (const branch of members.get(key) ?? []) {
      const llm = byId.get(branch.llmNodeId) as FlowNode
      const prompt = branch.promptNodeId ? byId.get(branch.promptNodeId) : undefined
      if (!firstY.has(key)) firstY.set(key, y)
      positions.set(llm.id, { x: llm.x, y })
      if (prompt) positions.set(prompt.id, { x: prompt.x, y })
      y = snapUp(y + Math.max(nodeSize(llm).height, prompt ? nodeSize(prompt).height : 0) + ROW_GAP)
    }
  }
  let floor = -Infinity
  for (const key of order) {
    const host = byId.get(key)
    if (!host || !isConnectionNode(host)) continue
    const hy = Math.max(firstY.get(key) ?? host.y, floor)
    positions.set(host.id, { x: host.x, y: hy })
    floor = snapUp(hy + nodeSize(host).height + ROW_GAP)
  }
  return { ...laid, nodes: laid.nodes.map((n) => { const p = positions.get(n.id); return p ? { ...n, x: p.x, y: p.y } : n }) }
}
