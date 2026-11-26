import {
  CONNECTION_RULES,
  DEFAULT_CONFIG,
  GUARDRAIL_PRESETS,
  NODE_TYPES,
  SINGLETON_IDS,
  branchPromptNodeId,
  defaultNodeProps,
  edgeId,
  hostNodeIdFor,
  isProtectedGuardrail,
  llmNodeId,
  nodePropsFromConfig,
  policyNodeId,
  portSpec,
  projectNodeId,
  projectSignals,
  targetKeyOf,
} from './catalog'
import {
  FLOW_NODE_TYPES,
  analyzeWorkflow,
  dependencyClosure,
  dependentsOf,
  insertableTypesForEdge,
  isBranchPrompt,
  isEmptyWorkflow,
  promptScopeForSplice,
  rankOf,
} from './workflow'
import { COLUMN_WIDTH, ROW_GAP, autoLayout, freeSpot, nodeSize, placeNewNode, placeSpliced, portAnchor, snapUp } from './layout'
import {
  autoLayoutByConnection,
  connectionGroups,
  connectionLaneOffset,
  connectionMetaOf,
  connectionOfNode,
  defaultConnectionSpot,
  hasIncomingRequest,
  headOf,
  isConnectionId,
  isConnectionNode,
  requestSourceFor,
  unroutedHeads,
  viewPortsOf,
} from './connections'
import type {
  AddableNodeType,
  CommandResult,
  ConnectCheck,
  DeploymentInfo,
  EdgeKind,
  FieldSpec,
  FlowCommand,
  FlowEdge,
  FlowGraph,
  FlowNode,
  FlowPropValue,
  FlowSelection,
  FlowViewMode,
  NodeType,
  Point,
  PortRef,
  ServiceFlowSnapshot,
} from './types'

const MSG_NOT_FOUND = '간선을 이을 노드를 찾을 수 없습니다.'
const MSG_SAME_NODE = '같은 노드끼리 연결할 수 없습니다.'
const MSG_DIRECTION = '출력 포트에서 입력 포트로만 연결할 수 있습니다.'
const MSG_START_ONLY_PROJECT = '시작 노드는 프로젝트에만 연결합니다.'
const MSG_PROJECT_AUTO = '자동으로 관리되는 간선입니다. 시작 노드에서 프로젝트로 간선을 그리면 접근이 허용됩니다.'
const MSG_BRANCH_PROMPT_OUT = '모델별 시스템 프롬프트는 자기 LLM에만 연결됩니다.'
const MSG_NO_RULE = '이 포트 조합은 연결할 수 없습니다.'
const MSG_AUTO = '자동으로 관리되는 간선입니다.'
const MSG_AFTER_ROUTER = '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.'
const MSG_EXISTS = '이미 간선이 있습니다.'
const MSG_CYCLE = '순환 간선은 만들 수 없습니다.'
const MSG_PROMPT_COMMON_EXISTS = '공통 시스템 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선의 ＋를 누르세요.'
const MSG_TARGET_PROMPT_UNSUPPORTED = '서버가 모델별 시스템 프롬프트를 지원하지 않습니다.'
const MSG_CANNOT_SPLICE = '이 간선에는 이 노드를 넣을 수 없습니다.'
const MSG_CONN_UNKNOWN = '알 수 없는 연결입니다.'
const MSG_CONN_NO_CATALOG_MODELS = '이 연결에는 등록된 모델이 없습니다.'
const MSG_CONN_MODEL_MISMATCH = '선택한 모델 중 이 연결에 속하지 않는 모델이 있습니다.'
const MSG_CONN_SOURCE = '연결 노드에는 라우터나 처리 단계(가드레일·공통 프롬프트·캐시·생성 파라미터)의 출력에서만 간선을 이을 수 있습니다.'
const MSG_CONN_NO_MODELS = '이 연결에는 아직 모델이 없습니다. 연결 노드에서 모델을 먼저 추가하세요.'
const MSG_CONN_ALL_ROUTED = '이 연결의 모델은 이미 모두 요청 경로에 연결되어 있습니다.'
const MSG_CONN_NEEDS_ROUTER = '모델이 여러 개인 연결은 라우터에서 이어 주세요.'
const MSG_CONN_TARGET = '연결 노드의 출력은 그 연결의 모델에만 이을 수 있습니다.'
const MSG_CONN_MODEL_ROUTED = '이미 요청 경로에 연결된 모델입니다.'
const MSG_CONN_NO_SOURCE = '이 모델을 이을 요청 경로가 없습니다. 라우터나 처리 단계를 먼저 추가하세요.'
const msgOtherConnection = (label: string): string => `이 모델은 '${label}' 연결에 속합니다. 모델의 연결은 바꿀 수 없습니다. 다른 연결의 모델은 그 연결 노드에서 추가하세요.`
const STAGE_TYPES: ReadonlySet<NodeType> = new Set<NodeType>(['guardrail', 'prompt', 'params', 'cache'])

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }

function cloneNode(node: FlowNode): FlowNode {
  return { ...node, props: { ...node.props } }
}
function cloneEdge(edge: FlowEdge): FlowEdge {
  return { ...edge, from: { ...edge.from }, to: { ...edge.to } }
}

export function sortGraph(graph: FlowGraph): FlowGraph {
  const nodes = graph.nodes.map(cloneNode)
  nodes.sort((a, b) => rankOf(a) - rankOf(b) || cmp(a.id, b.id))
  const edges = graph.edges.map(cloneEdge)
  edges.sort((a, b) => cmp(a.id, b.id))
  return { ...graph, nodes, edges }
}

export function findNode(graph: FlowGraph, nodeId: string): FlowNode | undefined {
  return graph.nodes.find((n) => n.id === nodeId)
}

export function edgesOf(graph: FlowGraph, nodeId: string): FlowEdge[] {
  return graph.edges.filter((e) => e.from.nodeId === nodeId || e.to.nodeId === nodeId)
}

export function makeNode(type: NodeType, id: string, refId: string | null, props: Record<string, FlowPropValue> = {}, targetId: string | null = null): FlowNode {
  return { id, type, refId, targetId, x: 0, y: 0, collapsed: false, props: { ...props } }
}

export function makeEdge(kind: EdgeKind, from: PortRef, to: PortRef, locked: boolean): FlowEdge {
  return { id: edgeId(from, to), kind, from: { ...from }, to: { ...to }, locked }
}

export function createSkeletonGraph(serviceId: string | null): FlowGraph {
  return { schemaVersion: 2, serviceId, nodes: [makeNode('start', 'start', null, nodePropsFromConfig(DEFAULT_CONFIG).start)], edges: [] }
}

/* ------------------------------------------------------------------ */
/* Working copy                                                        */
/* ------------------------------------------------------------------ */

interface Working {
  base: FlowGraph
  nodes: Map<string, FlowNode>
  edges: Map<string, FlowEdge>
}
function begin(graph: FlowGraph): Working {
  return { base: graph, nodes: new Map(graph.nodes.map((n) => [n.id, cloneNode(n)])), edges: new Map(graph.edges.map((e) => [e.id, cloneEdge(e)])) }
}
function toGraph(w: Working): FlowGraph {
  return { ...w.base, nodes: [...w.nodes.values()], edges: [...w.edges.values()] }
}
function copyPositions(w: Working, g: FlowGraph): void {
  for (const n of g.nodes) {
    const hit = w.nodes.get(n.id)
    if (hit) { hit.x = n.x; hit.y = n.y }
  }
}
function link(w: Working, kind: EdgeKind, fromId: string, fromPort: string, toId: string, toPort: string, locked = false): FlowEdge {
  const edge = makeEdge(kind, { nodeId: fromId, portId: fromPort }, { nodeId: toId, portId: toPort }, locked)
  w.edges.set(edge.id, edge)
  return edge
}
/** Links two flow nodes with the kind implied by the source (router -> route, everything else -> flow). */
function linkFlow(w: Working, fromId: string, toId: string): FlowEdge {
  const from = w.nodes.get(fromId)
  const kind: EdgeKind = from && from.type === 'router' ? 'route' : 'flow'
  return link(w, kind, fromId, 'out', toId, 'in', false)
}
function unlink(w: Working, id: string): void { w.edges.delete(id) }
function isFlowEdge(w: Working, e: FlowEdge): boolean {
  if (e.kind !== 'flow' && e.kind !== 'route') return false
  const a = w.nodes.get(e.from.nodeId)
  const b = w.nodes.get(e.to.nodeId)
  return !!a && !!b && FLOW_NODE_TYPES.has(a.type) && FLOW_NODE_TYPES.has(b.type)
}
function flowOuts(w: Working, id: string): FlowEdge[] {
  return [...w.edges.values()].filter((e) => e.from.nodeId === id && isFlowEdge(w, e)).sort((a, b) => cmp(a.id, b.id))
}
function flowIns(w: Working, id: string): FlowEdge[] {
  return [...w.edges.values()].filter((e) => e.to.nodeId === id && isFlowEdge(w, e)).sort((a, b) => cmp(a.id, b.id))
}
function removeNodeAndEdges(w: Working, id: string): void {
  w.nodes.delete(id)
  for (const e of [...w.edges.values()]) if (e.from.nodeId === id || e.to.nodeId === id) w.edges.delete(e.id)
}
function placeW(w: Working, id: string, snapshot: ServiceFlowSnapshot): void {
  copyPositions(w, placeNewNode(toGraph(w), id, snapshot))
}

function deploymentOf(snapshot: ServiceFlowSnapshot, id: string | null): DeploymentInfo | undefined {
  return id ? snapshot.catalog.deployments.find((d) => d.id === id) : undefined
}
function externalDeployment(snapshot: ServiceFlowSnapshot, deploymentId: string | null): boolean {
  return deploymentOf(snapshot, deploymentId)?.external === true
}
function deploymentIdOf(node: FlowNode): string {
  return node.refId ?? (node.id.startsWith('deployment:') ? node.id.slice('deployment:'.length) : node.id)
}

/* ------------------------------------------------------------------ */
/* Connection checks                                                   */
/* ------------------------------------------------------------------ */

function hasFlowPath(graph: FlowGraph, fromId: string, toId: string): boolean {
  const seen = new Set<string>([fromId])
  const stack = [fromId]
  while (stack.length > 0) {
    const cur = stack.pop() as string
    if (cur === toId) return true
    for (const e of graph.edges) {
      if ((e.kind === 'flow' || e.kind === 'route') && e.from.nodeId === cur && !seen.has(e.to.nodeId)) { seen.add(e.to.nodeId); stack.push(e.to.nodeId) }
    }
  }
  return false
}

export function canConnect(graph: FlowGraph, from: PortRef, to: PortRef, _snapshot: ServiceFlowSnapshot): ConnectCheck {
  const fromNode = findNode(graph, from.nodeId)
  const toNode = findNode(graph, to.nodeId)
  if (!fromNode || !toNode) return { ok: false, reason: MSG_NOT_FOUND }
  if (fromNode.id === toNode.id) return { ok: false, reason: MSG_SAME_NODE }
  const fromPort = portSpec(fromNode.type, from.portId, 'out')
  const toPort = portSpec(toNode.type, to.portId, 'in')
  if (!fromPort || !toPort) return { ok: false, reason: MSG_DIRECTION }
  if (fromNode.type === 'start' && !(toNode.type === 'project' && to.portId === 'in')) return { ok: false, reason: MSG_START_ONLY_PROJECT }
  if (fromNode.type === 'project' || fromNode.type === 'policy') return { ok: false, reason: MSG_PROJECT_AUTO }
  if (isBranchPrompt(fromNode)) return { ok: false, reason: MSG_BRANCH_PROMPT_OUT }
  // Checked before the rule lookup so that "router -> anything but a branch" explains itself.
  if (fromNode.type === 'router' && toNode.type !== 'llm' && !isBranchPrompt(toNode)) return { ok: false, reason: MSG_AFTER_ROUTER }
  const rule = CONNECTION_RULES.find((r) => r.fromType === fromNode.type && r.fromPort === from.portId && r.toType === toNode.type && r.toPort === to.portId)
  if (!rule) return { ok: false, reason: MSG_NO_RULE }
  if (!rule.userEditable) return { ok: false, reason: MSG_AUTO }
  const id = edgeId(from, to)
  if (graph.edges.some((e) => e.id === id)) return { ok: false, reason: MSG_EXISTS }
  if ((rule.kind === 'flow' || rule.kind === 'route') && hasFlowPath(graph, to.nodeId, from.nodeId)) return { ok: false, reason: MSG_CYCLE }

  const replaces: string[] = []
  if (!fromPort.multiple) {
    for (const e of graph.edges) {
      if (e.from.nodeId === from.nodeId && e.from.portId === from.portId && e.kind !== 'access') replaces.push(e.id)
    }
  }
  if (toPort.type === 'flow' && toPort.id === 'in') {
    for (const e of graph.edges) {
      if (e.to.nodeId === to.nodeId && e.to.portId === to.portId && (e.kind === 'flow' || e.kind === 'route') && !replaces.includes(e.id)) replaces.push(e.id)
    }
  } else if (!toPort.multiple) {
    for (const e of graph.edges) {
      if (e.to.nodeId === to.nodeId && e.to.portId === to.portId && e.kind !== 'access' && !replaces.includes(e.id)) replaces.push(e.id)
    }
  }
  return { ok: true, replaces }
}

export function nearestCompatiblePort(graph: FlowGraph, from: PortRef, point: Point, radius: number, snapshot: ServiceFlowSnapshot): PortRef | null {
  let best: PortRef | null = null
  let bestDistance = Infinity
  for (const node of graph.nodes) {
    for (const port of NODE_TYPES[node.type].ports) {
      if (port.direction !== 'in') continue
      const ref: PortRef = { nodeId: node.id, portId: port.id }
      const anchor = portAnchor(node, port.id, 'in')
      const distance = Math.hypot(anchor.x - point.x, anchor.y - point.y)
      if (distance > radius) continue
      if (!canConnect(graph, from, ref, snapshot).ok) continue
      if (distance < bestDistance || (distance === bestDistance && best && (cmp(ref.nodeId, best.nodeId) || cmp(ref.portId, best.portId)) < 0)) {
        best = ref
        bestDistance = distance
      }
    }
  }
  return best
}

/** §4.4: connection-view wiring rules; the model view is exactly canConnect. */
export function canConnectInView(graph: FlowGraph, from: PortRef, to: PortRef, snapshot: ServiceFlowSnapshot, view: FlowViewMode): ConnectCheck {
  if (view !== 'connection') return canConnect(graph, from, to, snapshot)
  const fromNode = findNode(graph, from.nodeId)
  const toNode = findNode(graph, to.nodeId)
  if (!fromNode || !toNode) return { ok: false, reason: MSG_NOT_FOUND }
  if (fromNode.id === toNode.id) return { ok: false, reason: MSG_SAME_NODE }
  if (isConnectionNode(toNode)) {
    if (from.portId !== 'out' || to.portId !== 'in') return { ok: false, reason: MSG_DIRECTION }
    const source = fromNode.type === 'router' || (STAGE_TYPES.has(fromNode.type) && !isBranchPrompt(fromNode))
    if (!source) return { ok: false, reason: MSG_CONN_SOURCE }
    const group = connectionGroups(graph).find((g) => g.connectionId === toNode.id)
    if (!group || group.llms.length === 0) return { ok: false, reason: MSG_CONN_NO_MODELS }
    const pending = unroutedHeads(graph, group)
    if (pending.length === 0) return { ok: false, reason: MSG_CONN_ALL_ROUTED }
    if (fromNode.type !== 'router' && pending.length > 1) return { ok: false, reason: MSG_CONN_NEEDS_ROUTER }
    for (const head of pending) {
      const check = canConnect(graph, from, { nodeId: head, portId: 'in' }, snapshot)
      if (!check.ok) return check
    }
    return { ok: true, replaces: [] }
  }
  if (isConnectionNode(fromNode)) {
    if (from.portId !== 'out' || to.portId !== 'in') return { ok: false, reason: MSG_DIRECTION }
    const llmId = toNode.type === 'llm' ? toNode.id : isBranchPrompt(toNode) ? llmNodeId(toNode.id.slice('prompt:'.length)) : null
    const llm = llmId ? findNode(graph, llmId) : undefined
    if (!llm) return { ok: false, reason: MSG_CONN_TARGET }
    const owner = connectionOfNode(graph, llm.id)
    if (owner !== fromNode.id) return { ok: false, reason: owner ? msgOtherConnection(connectionMetaOf(owner, snapshot).label) : MSG_CONN_TARGET }
    const head = headOf(graph, llm)
    if (hasIncomingRequest(graph, head)) return { ok: false, reason: MSG_CONN_MODEL_ROUTED }
    const group = connectionGroups(graph).find((g) => g.connectionId === fromNode.id)
    const source = group ? requestSourceFor(graph, group, snapshot) : null
    if (!source) return { ok: false, reason: MSG_CONN_NO_SOURCE }
    const check = canConnect(graph, source, { nodeId: head, portId: 'in' }, snapshot)
    return check.ok ? { ok: true, replaces: [] } : check
  }
  return canConnect(graph, from, to, snapshot)
}

/** nearestCompatiblePort for a view: view ports, view rules and display positions (the canvas passes them). */
export function nearestPortInView(
  graph: FlowGraph,
  from: PortRef,
  point: Point,
  radius: number,
  snapshot: ServiceFlowSnapshot,
  view: FlowViewMode,
  positionOf: (node: FlowNode) => Point,
): PortRef | null {
  let best: PortRef | null = null
  let bestDistance = Infinity
  for (const node of graph.nodes) {
    const at = positionOf(node)
    const placed = at.x === node.x && at.y === node.y ? node : { ...node, x: at.x, y: at.y }
    for (const port of viewPortsOf(node, view)) {
      if (port.direction !== 'in') continue
      const ref: PortRef = { nodeId: node.id, portId: port.id }
      const anchor = portAnchor(placed, port.id, 'in')
      const distance = Math.hypot(anchor.x - point.x, anchor.y - point.y)
      if (distance > radius) continue
      if (!canConnectInView(graph, from, ref, snapshot, view).ok) continue
      if (distance < bestDistance || (distance === bestDistance && best && (cmp(ref.nodeId, best.nodeId) || cmp(ref.portId, best.portId)) < 0)) {
        best = ref
        bestDistance = distance
      }
    }
  }
  return best
}

/* ------------------------------------------------------------------ */
/* Derived nodes: hosts and inherited policies                         */
/* ------------------------------------------------------------------ */

export function syncHostNodes(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowGraph {
  const w = begin(graph)
  const deployments = new Map<string, DeploymentInfo>(snapshot.catalog.deployments.map((d) => [d.id, d]))
  const created: string[] = []

  for (const node of [...w.nodes.values()]) {
    if (node.type !== 'llm') continue
    const info = node.refId ? deployments.get(node.refId) : undefined
    const hostId = info ? hostNodeIdFor(info) : null
    for (const e of [...w.edges.values()]) {
      if (e.kind === 'host' && e.from.nodeId === node.id && e.to.nodeId !== hostId) w.edges.delete(e.id)
    }
    if (!hostId) continue
    if (!w.nodes.has(hostId)) {
      const isProvider = hostId.startsWith('provider:')
      w.nodes.set(hostId, makeNode(isProvider ? 'provider' : 'endpoint', hostId, hostId.slice(hostId.indexOf(':') + 1)))
      created.push(hostId)
    }
    link(w, 'host', node.id, 'host', hostId, 'in', true)
  }

  for (const node of [...w.nodes.values()]) {
    if (node.type !== 'endpoint' && node.type !== 'provider') continue
    const used = [...w.edges.values()].some((e) => e.kind === 'host' && e.to.nodeId === node.id)
    if (!used && node.props.pinned !== true) {
      removeNodeAndEdges(w, node.id)
      const idx = created.indexOf(node.id)
      if (idx >= 0) created.splice(idx, 1)
    }
  }
  for (const id of created) placeW(w, id, snapshot)
  return sortGraph(toGraph(w))
}

/** §6.11: host nodes, inherited policy nodes and the automatic `access` edges. */
export function syncDerived(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowGraph {
  const hosted = syncHostNodes(graph, snapshot)
  const w = begin(hosted)
  const head = analyzeWorkflow(hosted, snapshot).head
  const created: string[] = []

  for (const node of [...w.nodes.values()]) {
    if (node.type === 'policy' && !w.nodes.has(projectNodeId(node.refId ?? ''))) removeNodeAndEdges(w, node.id)
  }
  for (const project of [...w.nodes.values()].filter((n) => n.type === 'project')) {
    if (!project.refId) continue
    const policyId = policyNodeId(project.refId)
    for (const e of [...w.edges.values()]) {
      if ((e.from.nodeId === project.id || e.from.nodeId === policyId) && (e.kind === 'access' || e.kind === 'policy')) w.edges.delete(e.id)
    }
    const granted = w.edges.has(edgeId({ nodeId: 'start', portId: 'out' }, { nodeId: project.id, portId: 'in' }))
    if (!granted) {
      removeNodeAndEdges(w, policyId)
      continue
    }
    const info = snapshot.catalog.projects.find((p) => p.id === project.refId)
    if (projectSignals(info).length > 0) {
      if (!w.nodes.has(policyId)) {
        w.nodes.set(policyId, makeNode('policy', policyId, project.refId))
        created.push(policyId)
      }
      link(w, 'policy', project.id, 'out', policyId, 'in', true)
      if (head) link(w, 'access', policyId, 'out', head, 'in', true)
    } else {
      removeNodeAndEdges(w, policyId)
      if (head) link(w, 'access', project.id, 'out', head, 'in', true)
    }
  }
  for (const e of [...w.edges.values()]) {
    if (e.kind === 'access' && e.to.nodeId !== head) w.edges.delete(e.id)
  }
  for (const id of created) placeW(w, id, snapshot)
  return sortGraph(toGraph(w))
}

export function renameTargetNodes(graph: FlowGraph, createdTargetIds: Record<string, string>): FlowGraph {
  const nodes = graph.nodes.map((n) => {
    if (n.type !== 'llm') return cloneNode(n)
    const real = createdTargetIds[targetKeyOf(n)]
    return real ? { ...cloneNode(n), targetId: real } : cloneNode(n)
  })
  return sortGraph({ ...graph, nodes })
}

/** Turns LLM nodes whose server row is gone into brand-new pending LLMs (same position, props and edges). */
export function detachTargetNodes(graph: FlowGraph, keys: string[]): FlowGraph {
  const set = new Set(keys)
  const nodes = graph.nodes.map((n) => (n.type === 'llm' && n.targetId !== null && set.has(targetKeyOf(n)) ? { ...cloneNode(n), targetId: null } : cloneNode(n)))
  return sortGraph({ ...graph, nodes })
}

/* ------------------------------------------------------------------ */
/* Structural editing primitives (§6.5-6.7)                            */
/* ------------------------------------------------------------------ */

function insertTrunk(w: Working, node: FlowNode, snapshot: ServiceFlowSnapshot): void {
  const analysis = analyzeWorkflow(toGraph(w), snapshot)
  const rank = rankOf(node)
  const idx = analysis.trunk.findIndex((id) => rankOf(w.nodes.get(id) as FlowNode) > rank)
  w.nodes.set(node.id, node)
  if (idx >= 0) {
    const before = analysis.trunk[idx]
    const pred = idx > 0 ? analysis.trunk[idx - 1] : null
    if (pred) {
      for (const e of flowOuts(w, pred)) if (e.to.nodeId === before) unlink(w, e.id)
      linkFlow(w, pred, node.id)
    }
    linkFlow(w, node.id, before)
    copyPositions(w, placeSpliced(toGraph(w), node.id, pred, before))
    return
  }
  const tail = analysis.tail
  if (tail) {
    const outs = flowOuts(w, tail)
    for (const e of outs) unlink(w, e.id)
    linkFlow(w, tail, node.id)
    for (const e of outs) linkFlow(w, node.id, e.to.nodeId)
    copyPositions(w, placeSpliced(toGraph(w), node.id, tail, outs[0]?.to.nodeId ?? null))
    return
  }
  if (analysis.head) linkFlow(w, node.id, analysis.head)
  copyPositions(w, placeSpliced(toGraph(w), node.id, null, analysis.head))
}

/** Puts a router after `afterId` (or at the head), moving the outgoing flow edges of `afterId` (or `targets`) below it. */
function insertRouterAt(w: Working, node: FlowNode, afterId: string | null, targets: string[]): void {
  w.nodes.set(node.id, node)
  if (afterId) {
    for (const e of flowOuts(w, afterId)) if (targets.includes(e.to.nodeId)) unlink(w, e.id)
    linkFlow(w, afterId, node.id)
  }
  for (const t of targets) linkFlow(w, node.id, t)
  copyPositions(w, placeSpliced(toGraph(w), node.id, afterId, targets[0] ?? null))
}

function insertRouterAuto(w: Working, node: FlowNode, snapshot: ServiceFlowSnapshot): void {
  const analysis = analyzeWorkflow(toGraph(w), snapshot)
  if (analysis.tail) {
    insertRouterAt(w, node, analysis.tail, flowOuts(w, analysis.tail).map((e) => e.to.nodeId))
  } else {
    insertRouterAt(w, node, null, analysis.head ? [analysis.head] : [])
  }
}

/** Puts a model-specific prompt directly in front of its LLM, taking over the LLM's incoming flow edges. */
function insertBranchPrompt(w: Working, prompt: FlowNode, llmId: string, snapshot: ServiceFlowSnapshot): void {
  void snapshot
  w.nodes.set(prompt.id, prompt)
  const ins = flowIns(w, llmId)
  for (const e of ins) {
    unlink(w, e.id)
    linkFlow(w, e.from.nodeId, prompt.id)
  }
  link(w, 'route', prompt.id, 'out', llmId, 'in', true)
  const source = ins[0] ? w.nodes.get(ins[0].from.nodeId) : undefined
  const llm = w.nodes.get(llmId)
  if (source && source.type === 'router' && llm) {
    // Branch prompts share one lane in front of the LLM lane: push every LLM (and its hosts) right when needed.
    const promptX = source.x + COLUMN_WIDTH
    const llms = [...w.nodes.values()].filter((n) => n.type === 'llm')
    const dx = promptX + COLUMN_WIDTH - Math.min(...llms.map((n) => n.x))
    if (dx > 0) {
      const moved = new Set(dependencyClosure(toGraph(w), llms.map((n) => n.id)))
      moved.delete(prompt.id)
      for (const id of moved) { const n = w.nodes.get(id); if (n) n.x += dx }
    }
    prompt.x = promptX
    prompt.y = llm.y
    return
  }
  copyPositions(w, placeSpliced(toGraph(w), prompt.id, source ? source.id : null, llmId))
}

function newRouterNode(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowNode {
  return makeNode('router', SINGLETON_IDS.router, null, defaultNodeProps('router', graph, snapshot, null))
}

function addLlm(w: Working, node: FlowNode, snapshot: ServiceFlowSnapshot, autoWire: boolean): void {
  const graph = toGraph(w)
  w.nodes.set(node.id, node)
  if (!autoWire) { placeW(w, node.id, snapshot); return }
  const analysis = analyzeWorkflow(graph, snapshot)
  const llmCount = graph.nodes.filter((n) => n.type === 'llm').length
  if (analysis.router) {
    placeW(w, node.id, snapshot)
    linkFlow(w, analysis.router, node.id)
  } else if (llmCount === 0) {
    if (analysis.tail) linkFlow(w, analysis.tail, node.id)
    placeW(w, node.id, snapshot)
  } else {
    w.nodes.delete(node.id)
    insertRouterAuto(w, newRouterNode(graph, snapshot), snapshot)
    w.nodes.set(node.id, node)
    placeW(w, node.id, snapshot)
    linkFlow(w, SINGLETON_IDS.router, node.id)
  }
}

/** §5.5-style creation of one node with canonical wiring; used by commands and by graph synchronisation. */
export function addFlowNode(
  graph: FlowGraph,
  spec: { type: NodeType; id: string; refId?: string | null; props?: Record<string, FlowPropValue>; targetId?: string | null },
  snapshot: ServiceFlowSnapshot,
): FlowGraph {
  if (graph.nodes.some((n) => n.id === spec.id)) return graph
  const w = begin(graph)
  const node = makeNode(spec.type, spec.id, spec.refId ?? null, spec.props ?? {}, spec.targetId ?? null)
  if (spec.type === 'llm') addLlm(w, node, snapshot, true)
  else if (spec.type === 'router') insertRouterAuto(w, node, snapshot)
  else if (spec.type === 'prompt' && spec.id !== 'prompt') {
    const llmId = llmNodeId(spec.id.slice('prompt:'.length))
    if (w.nodes.has(llmId)) insertBranchPrompt(w, node, llmId, snapshot)
    else { w.nodes.set(node.id, node); placeW(w, node.id, snapshot) }
  } else if (spec.type === 'masker') {
    w.nodes.set(node.id, node)
    if (w.nodes.has(SINGLETON_IDS.guardrail)) link(w, 'masker', node.id, 'masker', SINGLETON_IDS.guardrail, 'masker', false)
    placeW(w, node.id, snapshot)
  } else if (spec.type === 'project') {
    w.nodes.set(node.id, node)
    placeW(w, node.id, snapshot)
  } else insertTrunk(w, node, snapshot)
  return toGraph(w)
}

/**
 * Removes nodes and heals the request path: a removed flow node with exactly one incoming flow edge hands its
 * outgoing flow edges to that predecessor. Dependents are not expanded here (see `removeItems`).
 */
export function removeFlowNodes(graph: FlowGraph, nodeIds: string[], snapshot: ServiceFlowSnapshot): FlowGraph {
  const w = begin(graph)
  removeWithHealing(w, [...nodeIds].sort(cmp), snapshot)
  return toGraph(w)
}

function removeWithHealing(w: Working, ids: string[], snapshot: ServiceFlowSnapshot): void {
  for (const id of ids) {
    const node = w.nodes.get(id)
    if (!node) continue
    const ins = flowIns(w, id)
    const outs = flowOuts(w, id)
    removeNodeAndEdges(w, id)
    if (ins.length !== 1 || outs.length === 0) continue
    const p = ins[0].from.nodeId
    for (const out of outs) {
      const s = out.to.nodeId
      if (!w.nodes.has(p) || !w.nodes.has(s)) continue
      const check = canConnect(toGraph(w), { nodeId: p, portId: 'out' }, { nodeId: s, portId: 'in' }, snapshot)
      if (!check.ok) continue
      for (const rid of check.replaces) unlink(w, rid)
      linkFlow(w, p, s)
    }
  }
}

/* ------------------------------------------------------------------ */
/* Commands                                                            */
/* ------------------------------------------------------------------ */

const selectNode = (id: string): FlowSelection => ({ nodeIds: [id], edgeIds: [] })
const emptySelection = (): FlowSelection => ({ nodeIds: [], edgeIds: [] })

function coerce(field: FieldSpec, value: FlowPropValue): FlowPropValue {
  switch (field.kind) {
    case 'number':
    case 'integer':
      if (field.nullable && (value === null || value === '')) return null
      return Number(value)
    case 'boolean':
      return typeof value === 'string' ? value === 'true' : Boolean(value)
    case 'masker-select':
      return value === null || value === '' ? null : String(value)
    default:
      return value === null || value === undefined ? '' : String(value)
  }
}

function propsEqual(a: FlowPropValue | undefined, b: FlowPropValue | undefined): boolean {
  if (typeof a === 'number' && typeof b === 'number' && Number.isNaN(a) && Number.isNaN(b)) return true
  return a === b
}

const entryEdgeId = (projectNode: string): string => edgeId({ nodeId: 'start', portId: 'out' }, { nodeId: projectNode, portId: 'in' })

export function applyCommand(graph: FlowGraph, command: FlowCommand, snapshot: ServiceFlowSnapshot): CommandResult {
  const ok = (g: FlowGraph, changed: boolean, select: FlowSelection | null = null): CommandResult => ({
    graph: sortGraph(syncDerived(g, snapshot)), changed, error: null, select,
  })
  const fail = (error: string): CommandResult => ({ graph, changed: false, error, select: null })

  switch (command.type) {
    case 'moveNodes': {
      const moves = new Map(command.moves.map((m) => [m.nodeId, m]))
      let changed = false
      const nodes = graph.nodes.map((n) => {
        const m = moves.get(n.id)
        if (!m || !Number.isFinite(m.x) || !Number.isFinite(m.y)) return n
        if (m.x === n.x && m.y === n.y) return n
        changed = true
        return { ...n, x: m.x, y: m.y }
      })
      return ok({ ...graph, nodes }, changed)
    }

    case 'addNode':
      return addNodeCommand(graph, command, snapshot, ok, fail)

    case 'removeItems': {
      const w = begin(graph)
      let error: string | null = null
      let changed = false
      const requested: string[] = []
      const analysis = analyzeWorkflow(graph, snapshot)
      for (const id of command.nodeIds) {
        const node = w.nodes.get(id)
        if (!node) continue
        if (node.type === 'start') { error ??= '시작 노드는 삭제할 수 없습니다.'; continue }
        const connection = command.withConnectionModels === true && isConnectionNode(node)
        if (NODE_TYPES[node.type].derived && !connection) { error ??= '자동으로 표시되는 노드라 삭제할 수 없습니다.'; continue }
        if (node.type === 'guardrail' && isProtectedGuardrail(snapshot) && command.force !== true) {
          error ??= '외부 노출 방지 정책이 적용된 가드레일입니다. 인스펙터의 [보호 해제 후 삭제]를 사용하세요.'
          continue
        }
        requested.push(id)
        // A model cannot exist outside its connection: deleting the connection takes its models along (§4.5).
        if (connection) {
          for (const e of graph.edges) {
            if (e.kind === 'host' && e.to.nodeId === id && !requested.includes(e.from.nodeId)) requested.push(e.from.nodeId)
          }
        }
      }
      const expanded = new Set(dependencyClosure(graph, requested))
      for (const id of [...expanded]) {
        const node = w.nodes.get(id)
        if (node && (node.type === 'endpoint' || node.type === 'provider' || node.type === 'policy') && !requested.includes(id)) expanded.delete(id)
      }
      if (expanded.has('router')) {
        const remaining = analysis.branches.filter((b) => !expanded.has(b.llmNodeId)).length
        if (remaining >= 2) {
          error ??= '여러 LLM이 연결된 라우터는 삭제할 수 없습니다. LLM을 하나만 남기세요.'
          expanded.delete('router')
        }
      }
      const removedEdges = new Set<string>()
      for (const id of command.edgeIds) {
        const edge = w.edges.get(id)
        if (!edge) continue
        const autoKind = edge.kind === 'access' || edge.kind === 'policy' || edge.kind === 'host'
        if (edge.kind !== 'entry' && (edge.locked || autoKind)) { error ??= MSG_AUTO; continue }
        removedEdges.add(id)
      }
      if (expanded.size > 0) {
        removeWithHealing(w, [...expanded].sort(cmp), snapshot)
        changed = true
      }
      for (const id of removedEdges) {
        if (w.edges.delete(id)) changed = true
      }
      if (!changed) return error ? fail(error) : ok(graph, false, emptySelection())
      const result = ok(toGraph(w), true, emptySelection())
      return { ...result, error }
    }

    case 'connect': {
      const check = canConnect(graph, command.from, command.to, snapshot)
      if (!check.ok) return fail(check.reason)
      const fromNode = findNode(graph, command.from.nodeId) as FlowNode
      const toNode = findNode(graph, command.to.nodeId) as FlowNode
      const w = begin(graph)
      if (fromNode.type === 'start') {
        link(w, 'entry', fromNode.id, command.from.portId, toNode.id, command.to.portId, false)
        return ok(toGraph(w), true)
      }
      const isStage = fromNode.type === 'guardrail' || fromNode.type === 'prompt' || fromNode.type === 'params' || fromNode.type === 'cache'
      if (isStage && toNode.type === 'llm' && !findNode(graph, SINGLETON_IDS.router)) {
        const outs = flowOuts(w, fromNode.id).filter((e) => e.to.nodeId !== toNode.id)
        const hasBranchTarget = outs.some((e) => {
          const t = w.nodes.get(e.to.nodeId)
          return !!t && (t.type === 'llm' || isBranchPrompt(t))
        })
        if (hasBranchTarget) {
          for (const e of flowIns(w, toNode.id)) unlink(w, e.id)
          insertRouterAt(w, newRouterNode(graph, snapshot), fromNode.id, outs.map((e) => e.to.nodeId))
          linkFlow(w, SINGLETON_IDS.router, toNode.id)
          return ok(toGraph(w), true)
        }
      }
      const rule = CONNECTION_RULES.find((r) => r.fromType === fromNode.type && r.fromPort === command.from.portId && r.toType === toNode.type && r.toPort === command.to.portId)
      for (const id of check.replaces) unlink(w, id)
      link(w, rule ? rule.kind : 'flow', command.from.nodeId, command.from.portId, command.to.nodeId, command.to.portId, rule ? !rule.userEditable : false)
      const llm = w.nodes.get(toNode.id)
      if (llm && llm.type === 'llm' && llm.targetId === null && externalDeployment(snapshot, llm.refId)) llm.props.followModelChanges = false
      return ok(toGraph(w), true)
    }

    case 'updateProps': {
      const node = findNode(graph, command.nodeId)
      if (!node) return fail('알 수 없는 노드입니다.')
      const fields = NODE_TYPES[node.type].fields.filter((f) => !f.key.startsWith('$'))
      const props = { ...node.props }
      let changed = false
      for (const [key, raw] of Object.entries(command.patch)) {
        const field = fields.find((f) => f.key === key)
        if (!field) continue
        if (field.newServiceOnly && graph.serviceId !== null) continue
        const next = coerce(field, raw)
        if (!propsEqual(props[key], next)) { props[key] = next; changed = true }
      }
      if (!changed) return ok(graph, false)
      return ok({ ...graph, nodes: graph.nodes.map((n) => (n.id === node.id ? { ...n, props } : n)) }, true)
    }

    case 'replaceModel': {
      const old = findNode(graph, command.nodeId)
      if (!old || old.type !== 'llm') return fail('알 수 없는 노드입니다.')
      if (!deploymentOf(snapshot, command.deploymentId)) return fail('알 수 없는 모델입니다.')
      const newId = llmNodeId(command.deploymentId)
      if (findNode(graph, newId)) return fail('이미 워크플로우에 있는 모델입니다.')
      const oldDeployment = deploymentIdOf(old)
      const renames = new Map<string, string>([[old.id, newId]])
      const oldPrompt = branchPromptNodeId(oldDeployment)
      const newPrompt = branchPromptNodeId(command.deploymentId)
      if (findNode(graph, oldPrompt)) renames.set(oldPrompt, newPrompt)
      const nodes = graph.nodes.map((n) => {
        const next = renames.get(n.id)
        if (!next) return cloneNode(n)
        if (n.id === old.id) {
          const props = { ...n.props }
          if (externalDeployment(snapshot, command.deploymentId)) props.followModelChanges = false
          return { ...n, id: next, refId: command.deploymentId, targetId: null, props }
        }
        return { ...cloneNode(n), id: next }
      })
      const edges: FlowEdge[] = []
      for (const e of graph.edges) {
        if (e.kind === 'host' && e.from.nodeId === old.id) continue
        const from = { ...e.from, nodeId: renames.get(e.from.nodeId) ?? e.from.nodeId }
        const to = { ...e.to, nodeId: renames.get(e.to.nodeId) ?? e.to.nodeId }
        edges.push({ ...e, from, to, id: edgeId(from, to) })
      }
      return ok({ ...graph, nodes, edges }, true, selectNode(newId))
    }

    case 'setAccess': {
      const project = findNode(graph, command.projectNodeId)
      if (!project || project.type !== 'project') return fail('알 수 없는 노드입니다.')
      const w = begin(graph)
      const id = entryEdgeId(project.id)
      const present = w.edges.has(id)
      if (command.granted === present) return ok(graph, false)
      if (command.granted) link(w, 'entry', 'start', 'out', project.id, 'in', false)
      else w.edges.delete(id)
      return ok(toGraph(w), true)
    }

    case 'normalizeOrder': {
      const analysis = analyzeWorkflow(graph, snapshot)
      const trunk = analysis.trunk.filter((id) => id !== analysis.router)
      const nodeOf = (id: string) => findNode(graph, id) as FlowNode
      const sorted = [...trunk].sort((a, b) => rankOf(nodeOf(a)) - rankOf(nodeOf(b)) || trunk.indexOf(a) - trunk.indexOf(b))
      if (trunk.every((id, i) => id === sorted[i])) return ok(graph, false)
      const w = begin(graph)
      const finalTargets = analysis.router
        ? [analysis.router]
        : flowOuts(w, trunk[trunk.length - 1]).map((e) => e.to.nodeId).filter((id) => !trunk.includes(id))
      for (const id of trunk) for (const e of flowOuts(w, id)) unlink(w, e.id)
      for (let i = 0; i < sorted.length - 1; i += 1) linkFlow(w, sorted[i], sorted[i + 1])
      for (const t of finalTargets) linkFlow(w, sorted[sorted.length - 1], t)
      const xs = trunk.map((id) => nodeOf(id).x).sort((a, b) => a - b)
      sorted.forEach((id, i) => {
        const node = w.nodes.get(id) as FlowNode
        const dx = xs[i] - node.x
        if (dx === 0) return
        node.x = xs[i]
        for (const dep of dependentsOf(graph, id)) {
          const d = w.nodes.get(dep)
          if (d && (d.type === 'masker')) d.x += dx
        }
      })
      return ok(toGraph(w), true)
    }

    case 'applyTemplate':
      return applyTemplateCommand(graph, command, snapshot, ok, fail)

    case 'toggleCollapse': {
      const ids = new Set(command.nodeIds)
      let changed = false
      const nodes = graph.nodes.map((n) => {
        if (!ids.has(n.id) || n.collapsed === command.collapsed) return n
        changed = true
        return { ...n, collapsed: command.collapsed }
      })
      return ok({ ...graph, nodes }, changed)
    }

    case 'autoLayout':
      return ok(command.view === 'connection' ? autoLayoutByConnection(graph, snapshot) : autoLayout(graph, snapshot), true)

    case 'addConnection':
      return addConnectionCommand(graph, command, snapshot, ok, fail)

    case 'addConnectionModels':
      return addConnectionModelsCommand(graph, command, snapshot, ok, fail)

    case 'connectConnection':
      return connectConnectionCommand(graph, command, snapshot, ok, fail)
  }
}

type Ok = (g: FlowGraph, changed: boolean, select?: FlowSelection | null) => CommandResult
type Fail = (error: string) => CommandResult

function singletonLabelMessage(type: NodeType): string {
  return `${NODE_TYPES[type].label}은(는) 워크플로우에 하나만 둘 수 있습니다.`
}

function addNodeCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'addNode' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const type: AddableNodeType = command.nodeType
  const refId = command.refId ?? null
  const position = command.position ?? null
  const autoWire = command.autoWire !== false
  const w = begin(graph)
  const place = (id: string) => {
    if (position && Number.isFinite(position.x) && Number.isFinite(position.y)) {
      const node = w.nodes.get(id) as FlowNode
      node.x = position.x
      node.y = position.y
    }
  }

  if (command.spliceEdgeId) return spliceCommand(graph, command.spliceEdgeId, type, snapshot, ok, fail, position)

  switch (type) {
    case 'project': {
      if (!refId || !snapshot.catalog.projects.some((p) => p.id === refId)) return fail('알 수 없는 프로젝트입니다.')
      const id = projectNodeId(refId)
      const hasStart = w.nodes.has('start')
      if (w.nodes.has(id)) {
        if (autoWire && hasStart && !w.edges.has(entryEdgeId(id))) {
          link(w, 'entry', 'start', 'out', id, 'in', false)
          return ok(toGraph(w), true, selectNode(id))
        }
        return ok(graph, false, selectNode(id))
      }
      w.nodes.set(id, makeNode('project', id, refId))
      if (autoWire && hasStart) link(w, 'entry', 'start', 'out', id, 'in', false)
      placeW(w, id, snapshot)
      place(id)
      return ok(toGraph(w), true, selectNode(id))
    }

    case 'llm': {
      if (!refId || !deploymentOf(snapshot, refId)) return fail('알 수 없는 모델입니다.')
      const id = llmNodeId(refId)
      if (w.nodes.has(id)) return ok(graph, false, selectNode(id))
      addLlm(w, makeNode('llm', id, refId, defaultNodeProps('llm', graph, snapshot, refId), null), snapshot, autoWire)
      place(id)
      return ok(toGraph(w), true, selectNode(id))
    }

    case 'masker': {
      if (w.nodes.has(SINGLETON_IDS.masker)) return fail('마스킹 워커는 하나만 둘 수 있습니다.')
      if (!w.nodes.has(SINGLETON_IDS.guardrail)) return fail('마스킹 워커는 가드레일에 연결됩니다. 가드레일을 먼저 추가하세요.')
      w.nodes.set('masker', makeNode('masker', 'masker', null, defaultNodeProps('masker', graph, snapshot, refId)))
      link(w, 'masker', 'masker', 'masker', SINGLETON_IDS.guardrail, 'masker', false)
      placeW(w, 'masker', snapshot)
      place('masker')
      return ok(toGraph(w), true, selectNode('masker'))
    }

    case 'router': {
      if (w.nodes.has(SINGLETON_IDS.router)) return fail(singletonLabelMessage('router'))
      const node = newRouterNode(graph, snapshot)
      if (autoWire) insertRouterAuto(w, node, snapshot)
      else { w.nodes.set(node.id, node); placeW(w, node.id, snapshot) }
      place(node.id)
      return ok(toGraph(w), true, selectNode(node.id))
    }

    case 'guardrail':
    case 'prompt':
    case 'params':
    case 'cache': {
      const id = SINGLETON_IDS[type]
      if (w.nodes.has(id)) return fail(type === 'prompt' ? MSG_PROMPT_COMMON_EXISTS : singletonLabelMessage(type as 'guardrail' | 'cache' | 'params' | 'router'))
      const node = makeNode(type, id, null, defaultNodeProps(type, graph, snapshot, null))
      if (autoWire) insertTrunk(w, node, snapshot)
      else { w.nodes.set(id, node); placeW(w, id, snapshot) }
      place(id)
      return ok(toGraph(w), true, selectNode(id))
    }
  }
  return fail(MSG_CANNOT_SPLICE)
}

function spliceCommand(
  graph: FlowGraph,
  edgeIdValue: string,
  type: AddableNodeType,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
  position: Point | null,
): CommandResult {
  const edge = graph.edges.find((e) => e.id === edgeIdValue)
  if (!edge) return fail('대상 간선을 찾을 수 없습니다.')
  const to = findNode(graph, edge.to.nodeId) as FlowNode
  const trunkTypes: AddableNodeType[] = ['guardrail', 'prompt', 'cache', 'params', 'router']
  if (!trunkTypes.includes(type)) return fail(MSG_CANNOT_SPLICE)
  const scope = promptScopeForSplice(graph, edge.id)
  const modelPrompt = type === 'prompt' && scope === 'model'
  if (!insertableTypesForEdge(graph, edge.id, snapshot).includes(type)) {
    if (modelPrompt) {
      if (snapshot.features?.targetPrompt === false) return fail(MSG_TARGET_PROMPT_UNSUPPORTED)
      return fail(MSG_CANNOT_SPLICE)
    }
    const id = SINGLETON_IDS[type as 'guardrail' | 'prompt' | 'cache' | 'params' | 'router']
    if (findNode(graph, id)) return fail(type === 'prompt' ? MSG_PROMPT_COMMON_EXISTS : singletonLabelMessage(type as 'guardrail' | 'cache' | 'params' | 'router'))
    return fail(MSG_CANNOT_SPLICE)
  }

  const w = begin(graph)
  let nodeId: string
  if (modelPrompt) {
    const llmId = to.id
    nodeId = branchPromptNodeId(deploymentIdOf(to))
    const prompt = makeNode('prompt', nodeId, null, defaultNodeProps('prompt', graph, snapshot, null))
    insertBranchPrompt(w, prompt, llmId, snapshot)
  } else {
    nodeId = SINGLETON_IDS[type as 'guardrail' | 'prompt' | 'cache' | 'params' | 'router']
    const node = type === 'router' ? newRouterNode(graph, snapshot) : makeNode(type as NodeType, nodeId, null, defaultNodeProps(type, graph, snapshot, null))
    w.nodes.set(nodeId, node)
    if (edge.kind === 'flow') {
      unlink(w, edge.id)
      linkFlow(w, edge.from.nodeId, nodeId)
      linkFlow(w, nodeId, to.id)
      copyPositions(w, placeSpliced(toGraph(w), nodeId, edge.from.nodeId, to.id))
    } else {
      // access edge: the new node becomes the head of the request path.
      linkFlow(w, nodeId, to.id)
      copyPositions(w, placeSpliced(toGraph(w), nodeId, null, to.id))
    }
  }
  if (position && Number.isFinite(position.x) && Number.isFinite(position.y)) {
    const node = w.nodes.get(nodeId) as FlowNode
    node.x = position.x
    node.y = position.y
  }
  return ok(toGraph(w), true, selectNode(nodeId))
}

function applyTemplateCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'applyTemplate' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  if (!isEmptyWorkflow(graph)) return fail('빈 워크플로우에서만 템플릿을 적용할 수 있습니다.')
  const unique = (ids: string[]) => [...new Set(ids)]
  const deploymentIds = unique(command.deploymentIds)
  const projectIds = unique(command.projectIds)
  const isExternal = (id: string) => externalDeployment(snapshot, id)
  const known = (id: string) => !!deploymentOf(snapshot, id)
  let order: string[] = deploymentIds
  let preset: (typeof GUARDRAIL_PRESETS)[number] | null = null

  if (command.template === 'basic') {
    if (deploymentIds.length === 0) return fail('LLM을 하나 이상 고르세요.')
  } else if (command.template === 'local-first') {
    const unknown = deploymentIds.find((id) => !known(id))
    if (unknown) return fail('알 수 없는 모델입니다.')
    const locals = deploymentIds.filter((id) => !isExternal(id))
    const externals = deploymentIds.filter((id) => isExternal(id))
    if (locals.length === 0 || externals.length === 0) return fail('로컬 LLM과 외부 LLM을 하나 이상씩 고르세요.')
    order = [...locals, ...externals]
    preset = GUARDRAIL_PRESETS.find((p) => p.id === 'mask-restore') ?? null
  } else {
    if (deploymentIds.some((id) => known(id) && isExternal(id))) return fail('사내 전용 템플릿에는 로컬 LLM만 쓸 수 있습니다.')
    if (deploymentIds.length === 0) return fail('로컬 LLM을 하나 이상 고르세요.')
    preset = GUARDRAIL_PRESETS.find((p) => p.id === 'internal-only') ?? null
  }

  let g = graph
  const run = (cmd: FlowCommand): string | null => {
    const result = applyCommand(g, cmd, snapshot)
    if (result.error) return result.error
    g = result.graph
    return null
  }
  for (const projectId of projectIds) {
    const error = run({ type: 'addNode', nodeType: 'project', refId: projectId })
    if (error) return fail(error)
  }
  if (preset) {
    let error = run({ type: 'addNode', nodeType: 'guardrail' })
    if (!error) error = run({ type: 'updateProps', nodeId: 'guardrail', patch: { dataBoundaryPolicy: preset.dataBoundaryPolicy, guardrailAction: preset.guardrailAction } })
    if (error) return fail(error)
  }
  for (const deploymentId of order) {
    const error = run({ type: 'addNode', nodeType: 'llm', refId: deploymentId })
    if (error) return fail(error)
  }
  if (command.template === 'local-first') {
    // The external model only works as a fallback when failover is not STRICT (STRICT excludes targets with a different compatibility key).
    const error = run({ type: 'updateProps', nodeId: 'router', patch: { failoverPolicy: 'COMPATIBLE' } })
    if (error) return fail(error)
  }
  return ok(autoLayout(g, snapshot), true, selectNode('start'))
}

/* ------------------------------------------------------------------ */
/* Connection-centric commands (docs/workflow-connection-first-design.ko.md §4)                         */
/* ------------------------------------------------------------------ */

function addConnectionCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'addConnection' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const id = command.connectionId
  if (!isConnectionId(id)) return fail(MSG_CONN_UNKNOWN)
  if (findNode(graph, id)) return ok(graph, false, selectNode(id))
  const refId = id.slice(id.indexOf(':') + 1)
  const isProvider = id.startsWith('provider:')
  const known = isProvider ? snapshot.catalog.providers.some((p) => p.id === refId) : snapshot.catalog.endpoints.some((e) => e.id === refId)
  if (!known) return fail(MSG_CONN_UNKNOWN)
  if (!snapshot.catalog.deployments.some((d) => hostNodeIdFor(d) === id)) return fail(MSG_CONN_NO_CATALOG_MODELS)
  const position = command.position && Number.isFinite(command.position.x) && Number.isFinite(command.position.y) ? command.position : defaultConnectionSpot(graph, snapshot)
  const w = begin(graph)
  const node = makeNode(isProvider ? 'provider' : 'endpoint', id, refId, { pinned: true })
  node.x = position.x
  node.y = position.y
  w.nodes.set(id, node)
  return ok(toGraph(w), true, selectNode(id))
}

function addConnectionModelsCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'addConnectionModels' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const connectionId = command.connectionId
  if (!isConnectionId(connectionId)) return fail(MSG_CONN_UNKNOWN)
  const wanted = [...new Set(command.deploymentIds)]
  for (const id of wanted) {
    const deployment = deploymentOf(snapshot, id)
    if (!deployment) return fail('알 수 없는 모델입니다.')
    if (hostNodeIdFor(deployment) !== connectionId) return fail(MSG_CONN_MODEL_MISMATCH)
  }
  const selectHost = (g: FlowGraph): FlowSelection | null => (g.nodes.some((n) => n.id === connectionId) ? selectNode(connectionId) : null)
  const fresh = wanted.filter((id) => !findNode(graph, llmNodeId(id)))
  if (fresh.length === 0) return ok(graph, false, selectHost(graph))

  // Remember the group as it was: its models (positions are re-read after the adds) or the empty connection's spot.
  const before = connectionGroups(graph).find((g) => g.connectionId === connectionId)
  const memberIds = before ? before.llms.map((n) => n.id) : []
  const hostBefore = findNode(graph, connectionId)
  const pinnedSpot = hostBefore && memberIds.length === 0 ? { x: hostBefore.x, y: hostBefore.y } : null

  // Same path as the model view (identical props, wiring and router insertion: §3.4).
  let g = graph
  for (const id of fresh) {
    const r = applyCommand(g, { type: 'addNode', nodeType: 'llm', refId: id }, snapshot)
    if (r.error) return fail(r.error)
    g = r.graph
  }

  // Stored (model-view) positions: below the siblings, or where the connection already is, or at the drop point.
  const w = begin(g)
  const offset = connectionLaneOffset(g)
  const position = command.position && Number.isFinite(command.position.x) && Number.isFinite(command.position.y) ? command.position : null
  let base: Point | null = null
  if (memberIds.length > 0) {
    const members = memberIds.map((id) => w.nodes.get(id)).filter((n): n is FlowNode => !!n)
    if (members.length > 0) {
      base = { x: Math.min(...members.map((n) => n.x)), y: snapUp(Math.max(...members.map((n) => n.y + nodeSize(n).height)) + ROW_GAP) }
    }
  } else if (pinnedSpot) {
    base = { x: pinnedSpot.x + offset, y: pinnedSpot.y }
  } else if (position) {
    base = { x: position.x + offset, y: position.y }
  }
  if (base) {
    const newIds = fresh.map(llmNodeId)
    let y = base.y
    for (let index = 0; index < newIds.length; index += 1) {
      const node = w.nodes.get(newIds[index])
      if (!node) continue
      const pending = new Set(newIds.slice(index))
      const others = [...w.nodes.values()].filter((n) => !pending.has(n.id) && n.id !== connectionId)
      const spot = freeSpot(others, node, base.x, y)
      node.x = spot.x
      node.y = spot.y
      y = snapUp(spot.y + nodeSize(node).height + ROW_GAP)
    }
  }
  // A connection that had no models gets its model-view spot (right of its first model) now that it has users.
  if (memberIds.length === 0 && w.nodes.has(connectionId)) copyPositions(w, placeNewNode(toGraph(w), connectionId, snapshot))
  const result = toGraph(w)
  return ok(result, true, selectHost(result))
}

function connectConnectionCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'connectConnection' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const check = canConnectInView(graph, command.from, command.to, snapshot, 'connection')
  if (!check.ok) return fail(check.reason)
  const fromNode = findNode(graph, command.from.nodeId) as FlowNode
  const toNode = findNode(graph, command.to.nodeId) as FlowNode
  let g = graph
  const connectHead = (from: PortRef, head: string): string | null => {
    const r = applyCommand(g, { type: 'connect', from, to: { nodeId: head, portId: 'in' } }, snapshot)
    if (r.error) return r.error
    g = r.graph
    return null
  }
  if (isConnectionNode(toNode)) {
    const group = connectionGroups(graph).find((x) => x.connectionId === toNode.id)
    for (const head of group ? unroutedHeads(graph, group) : []) {
      const error = connectHead(command.from, head)
      if (error) return fail(error)
    }
    return ok(g, true, selectNode(toNode.id))
  }
  if (isConnectionNode(fromNode)) {
    const group = connectionGroups(graph).find((x) => x.connectionId === fromNode.id)
    const llm = findNode(graph, toNode.type === 'llm' ? toNode.id : llmNodeId(toNode.id.slice('prompt:'.length))) as FlowNode
    const source = group ? requestSourceFor(graph, group, snapshot) : null
    if (!source) return fail(MSG_CONN_NO_SOURCE)
    const error = connectHead(source, headOf(graph, llm))
    if (error) return fail(error)
    return ok(g, true, null)
  }
  return fail(MSG_NO_RULE)
}
