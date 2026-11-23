import {
  CONNECTION_RULES,
  DEFAULT_CONFIG,
  NODE_TYPES,
  SINGLETON_IDS,
  deploymentNodeId,
  edgeId,
  hostNodeIdFor,
  portSpec,
  projectNodeId,
  singletonPropsFromConfig,
} from './catalog'
import { autoLayout, placeNewNode, portAnchor, COLUMN_WIDTH } from './layout'
import type {
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
  NodeType,
  Point,
  PortRef,
  ServiceFlowSnapshot,
} from './types'

const MSG_NOT_FOUND = '연결할 노드를 찾을 수 없습니다.'
const MSG_SAME_NODE = '같은 노드끼리 연결할 수 없습니다.'
const MSG_DIRECTION = '출력 포트에서 입력 포트로만 연결할 수 있습니다.'
const MSG_DUPLICATE_BINDING = '이 Deployment에는 이미 Target이 있습니다(서비스당 Deployment 1개).'
const MSG_NO_RULE = '이 포트 조합은 연결할 수 없습니다.'
const MSG_AUTO = '자동으로 관리되는 연결입니다.'
const MSG_EXISTS = '이미 연결되어 있습니다.'

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }

function cloneNode(node: FlowNode): FlowNode {
  return { ...node, props: { ...node.props } }
}
function cloneEdge(edge: FlowEdge): FlowEdge {
  return { ...edge, from: { ...edge.from }, to: { ...edge.to } }
}

export function sortGraph(graph: FlowGraph): FlowGraph {
  const nodes = graph.nodes.map(cloneNode)
  nodes.sort((a, b) => NODE_TYPES[a.type].column - NODE_TYPES[b.type].column || cmp(a.id, b.id))
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

export function makeNode(type: NodeType, id: string, refId: string | null, props: Record<string, FlowPropValue> = {}): FlowNode {
  return { id, type, refId, x: NODE_TYPES[type].column * COLUMN_WIDTH, y: 0, collapsed: false, props: { ...props } }
}

export function makeEdge(kind: EdgeKind, from: PortRef, to: PortRef, locked: boolean): FlowEdge {
  return { id: edgeId(from, to), kind, from: { ...from }, to: { ...to }, locked }
}

const PIPELINE_EDGES: Array<[PortRef, PortRef]> = [
  [{ nodeId: 'service', portId: 'request' }, { nodeId: 'guardrail', portId: 'request' }],
  [{ nodeId: 'guardrail', portId: 'request' }, { nodeId: 'prompt', portId: 'request' }],
  [{ nodeId: 'prompt', portId: 'request' }, { nodeId: 'cache', portId: 'request' }],
  [{ nodeId: 'cache', portId: 'request' }, { nodeId: 'router', portId: 'request' }],
]

export function createSkeletonGraph(serviceId: string | null): FlowGraph {
  const props = singletonPropsFromConfig(DEFAULT_CONFIG)
  const types = ['service', 'guardrail', 'prompt', 'cache', 'router'] as const
  const nodes = types.map((t) => makeNode(t, SINGLETON_IDS[t], null, props[t]))
  const edges = PIPELINE_EDGES.map(([from, to]) => makeEdge('pipeline', from, to, true))
  return sortGraph({ schemaVersion: 1, serviceId, nodes, edges, nextSeq: 1 })
}

/* ------------------------------------------------------------------ */
/* Connection checks                                                   */
/* ------------------------------------------------------------------ */

function hasBinding(graph: FlowGraph, deploymentNode: string): boolean {
  return graph.edges.some((e) => e.kind === 'binding' && e.to.nodeId === deploymentNode)
}

export function canConnect(graph: FlowGraph, from: PortRef, to: PortRef, _snapshot: ServiceFlowSnapshot): ConnectCheck {
  const fromNode = findNode(graph, from.nodeId)
  const toNode = findNode(graph, to.nodeId)
  if (!fromNode || !toNode) return { ok: false, reason: MSG_NOT_FOUND }
  if (fromNode.id === toNode.id) return { ok: false, reason: MSG_SAME_NODE }
  const fromPort = portSpec(fromNode.type, from.portId, 'out')
  const toPort = portSpec(toNode.type, to.portId, 'in')
  if (!fromPort || !toPort) return { ok: false, reason: MSG_DIRECTION }

  // Convenience rule: router.targets -> deployment.binding inserts a new target node.
  if (fromNode.type === 'router' && from.portId === 'targets' && toNode.type === 'deployment' && to.portId === 'binding') {
    return hasBinding(graph, toNode.id) ? { ok: false, reason: MSG_DUPLICATE_BINDING } : { ok: true, replaces: [] }
  }

  const rule = CONNECTION_RULES.find((r) => r.fromType === fromNode.type && r.fromPort === from.portId && r.toType === toNode.type && r.toPort === to.portId)
  if (!rule) return { ok: false, reason: MSG_NO_RULE }
  if (!rule.userEditable) return { ok: false, reason: MSG_AUTO }
  const id = edgeId(from, to)
  if (graph.edges.some((e) => e.id === id)) return { ok: false, reason: MSG_EXISTS }

  const replaces: string[] = []
  if (!fromPort.multiple) {
    for (const e of graph.edges) if (e.from.nodeId === from.nodeId && e.from.portId === from.portId) replaces.push(e.id)
  }
  if (!toPort.multiple) {
    for (const e of graph.edges) if (e.to.nodeId === to.nodeId && e.to.portId === to.portId && !replaces.includes(e.id)) replaces.push(e.id)
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

/* ------------------------------------------------------------------ */
/* Host nodes                                                          */
/* ------------------------------------------------------------------ */

function hostEdge(depId: string, hostId: string): FlowEdge {
  return makeEdge('host', { nodeId: depId, portId: 'host' }, { nodeId: hostId, portId: 'host' }, true)
}

export function syncHostNodes(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowGraph {
  const deployments = new Map<string, DeploymentInfo>(snapshot.catalog.deployments.map((d) => [d.id, d]))
  const nodes = new Map(graph.nodes.map((n) => [n.id, cloneNode(n)]))
  const edges = new Map(graph.edges.map((e) => [e.id, cloneEdge(e)]))
  const created: string[] = []

  for (const node of [...nodes.values()]) {
    if (node.type !== 'deployment') continue
    const info = node.refId ? deployments.get(node.refId) : undefined
    const hostId = info ? hostNodeIdFor(info) : null
    // Drop host edges that no longer match the catalog.
    for (const e of [...edges.values()]) {
      if (e.kind === 'host' && e.from.nodeId === node.id && e.to.nodeId !== hostId) edges.delete(e.id)
    }
    if (!hostId) continue
    if (!nodes.has(hostId)) {
      const isProvider = hostId.startsWith('provider:')
      nodes.set(hostId, makeNode(isProvider ? 'provider' : 'endpoint', hostId, hostId.slice(hostId.indexOf(':') + 1)))
      created.push(hostId)
    }
    const edge = hostEdge(node.id, hostId)
    if (!edges.has(edge.id)) edges.set(edge.id, edge)
  }

  // Remove host nodes without any connected deployment.
  for (const node of [...nodes.values()]) {
    if (node.type !== 'endpoint' && node.type !== 'provider') continue
    const used = [...edges.values()].some((e) => e.kind === 'host' && e.to.nodeId === node.id)
    if (!used) {
      nodes.delete(node.id)
      const idx = created.indexOf(node.id)
      if (idx >= 0) created.splice(idx, 1)
    }
  }

  return placeCreated(graph, nodes, edges, created)
}

/** Builds a sorted graph from working maps, placing nodes listed in `created` (in order) via placeNewNode. */
export function placeCreated(graph: FlowGraph, nodes: Map<string, FlowNode>, edges: Map<string, FlowEdge>, created: string[]): FlowGraph {
  const fresh = new Set(created)
  let working: FlowGraph = { ...graph, nodes: [...nodes.values()].filter((n) => !fresh.has(n.id)), edges: [...edges.values()] }
  for (const id of created) {
    const node = nodes.get(id)
    if (!node) continue
    working = { ...working, nodes: [...working.nodes, node] }
    working = placeNewNode(working, id)
  }
  return sortGraph(working)
}

export function renameTargetNodes(graph: FlowGraph, createdTargetIds: Record<string, string>): FlowGraph {
  const existing = new Set(graph.nodes.map((n) => n.id))
  const renames = new Map<string, string>()
  for (const [oldId, realId] of Object.entries(createdTargetIds)) {
    const newId = `target:${realId}`
    if (oldId === newId || !existing.has(oldId) || existing.has(newId)) continue
    renames.set(oldId, newId)
  }
  if (renames.size === 0) return sortGraph(graph)
  const mapId = (id: string) => renames.get(id) ?? id
  const nodes = graph.nodes.map((n) => {
    const next = renames.get(n.id)
    return next ? { ...cloneNode(n), id: next, refId: next.slice('target:'.length) } : cloneNode(n)
  })
  const edges = graph.edges.map((e) => {
    const from = { ...e.from, nodeId: mapId(e.from.nodeId) }
    const to = { ...e.to, nodeId: mapId(e.to.nodeId) }
    return { ...e, from, to, id: edgeId(from, to) }
  })
  return sortGraph({ ...graph, nodes, edges })
}

/** Turn persisted Target nodes whose server row is gone into brand-new pending Targets (same position, props and edges). */
export function detachTargetNodes(graph: FlowGraph, keys: string[]): FlowGraph {
  const present = new Set(graph.nodes.map((n) => n.id))
  const targets = new Set(graph.nodes.filter((n) => n.type === 'target' && n.refId !== null && keys.includes(n.id)).map((n) => n.id))
  if (targets.size === 0) return graph
  const renames = new Map<string, string>()
  let seq = graph.nextSeq
  for (const id of [...targets].sort(cmp)) {
    while (present.has(`target:new-${seq}`)) seq += 1
    renames.set(id, `target:new-${seq}`)
    present.add(`target:new-${seq}`)
    seq += 1
  }
  const mapId = (id: string) => renames.get(id) ?? id
  const nodes = graph.nodes.map((n) => {
    const next = renames.get(n.id)
    return next ? { ...cloneNode(n), id: next, refId: null } : cloneNode(n)
  })
  const edges = graph.edges.map((e) => {
    const from = { ...e.from, nodeId: mapId(e.from.nodeId) }
    const to = { ...e.to, nodeId: mapId(e.to.nodeId) }
    return { ...e, from, to, id: edgeId(from, to) }
  })
  return sortGraph({ ...graph, nodes, edges, nextSeq: seq })
}

/* ------------------------------------------------------------------ */
/* Commands                                                            */
/* ------------------------------------------------------------------ */

const ok = (graph: FlowGraph, changed: boolean, select: FlowSelection | null = null): CommandResult => ({ graph: sortGraph(graph), changed, error: null, select })
const fail = (graph: FlowGraph, error: string): CommandResult => ({ graph, changed: false, error, select: null })
const selectNode = (id: string): FlowSelection => ({ nodeIds: [id], edgeIds: [] })

function numberProp(value: FlowPropValue | undefined): number {
  return typeof value === 'number' ? value : Number(value)
}

function externalDeployment(snapshot: ServiceFlowSnapshot, deploymentId: string): boolean {
  return snapshot.catalog.deployments.find((d) => d.id === deploymentId)?.external === true
}

function newTargetProps(graph: FlowGraph, deploymentId: string | null, snapshot: ServiceFlowSnapshot): Record<string, FlowPropValue> {
  let max = 0
  for (const n of graph.nodes) {
    if (n.type !== 'target') continue
    const p = numberProp(n.props.priority)
    if (Number.isFinite(p)) max = Math.max(max, p)
  }
  return {
    priority: max + 1,
    weight: 100,
    enabled: true,
    degraded: false,
    maxConcurrencyOverride: null,
    followModelChanges: deploymentId ? !externalDeployment(snapshot, deploymentId) : true,
  }
}

interface Working {
  graph: FlowGraph
  nodes: Map<string, FlowNode>
  edges: Map<string, FlowEdge>
  created: string[]
}
function begin(graph: FlowGraph): Working {
  return { graph, nodes: new Map(graph.nodes.map((n) => [n.id, cloneNode(n)])), edges: new Map(graph.edges.map((e) => [e.id, cloneEdge(e)])), created: [] }
}
function ensureNode(w: Working, type: NodeType, id: string, refId: string | null, props: Record<string, FlowPropValue> = {}): FlowNode {
  const hit = w.nodes.get(id)
  if (hit) return hit
  const node = makeNode(type, id, refId, props)
  w.nodes.set(id, node)
  w.created.push(id)
  return node
}
function finish(w: Working, snapshot: ServiceFlowSnapshot, nextSeq = w.graph.nextSeq, sync = true): FlowGraph {
  const placed = placeCreated({ ...w.graph, nextSeq }, w.nodes, w.edges, w.created)
  return sync ? syncHostNodes(placed, snapshot) : placed
}
function removeEdgesWhere(w: Working, predicate: (e: FlowEdge) => boolean): void {
  for (const e of [...w.edges.values()]) if (predicate(e)) w.edges.delete(e.id)
}

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

export function applyCommand(graph: FlowGraph, command: FlowCommand, snapshot: ServiceFlowSnapshot): CommandResult {
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

    case 'addNode': {
      const refId = command.refId ?? null
      if (command.nodeType === 'project') {
        if (!refId || !snapshot.catalog.projects.some((p) => p.id === refId)) return fail(graph, '알 수 없는 프로젝트입니다.')
        const id = projectNodeId(refId)
        if (findNode(graph, id)) return ok(graph, false, selectNode(id))
        const w = begin(graph)
        const node = ensureNode(w, 'project', id, refId)
        if (command.position) { node.x = command.position.x; node.y = command.position.y; w.created.pop() }
        return ok(finish(w, snapshot, undefined, false), true, selectNode(id))
      }
      if (command.nodeType === 'deployment') {
        if (!refId || !snapshot.catalog.deployments.some((d) => d.id === refId)) return fail(graph, '알 수 없는 Deployment입니다.')
        const id = deploymentNodeId(refId)
        if (findNode(graph, id)) return ok(graph, false, selectNode(id))
        const w = begin(graph)
        const node = ensureNode(w, 'deployment', id, refId)
        if (command.position) { node.x = command.position.x; node.y = command.position.y; w.created.pop() }
        return ok(finish(w, snapshot), true, selectNode(id))
      }
      if (command.nodeType === 'target') {
        if (refId) {
          if (!snapshot.catalog.deployments.some((d) => d.id === refId)) return fail(graph, '알 수 없는 Deployment입니다.')
          if (hasBinding(graph, deploymentNodeId(refId))) return fail(graph, MSG_DUPLICATE_BINDING)
        }
        const w = begin(graph)
        const id = `target:new-${graph.nextSeq}`
        const node = ensureNode(w, 'target', id, null, newTargetProps(graph, refId, snapshot))
        if (command.position) { node.x = command.position.x; node.y = command.position.y; w.created.pop() }
        const route = makeEdge('route', { nodeId: 'router', portId: 'targets' }, { nodeId: id, portId: 'route' }, true)
        w.edges.set(route.id, route)
        if (refId) {
          const depId = deploymentNodeId(refId)
          ensureNode(w, 'deployment', depId, refId)
          const binding = makeEdge('binding', { nodeId: id, portId: 'binding' }, { nodeId: depId, portId: 'binding' }, false)
          w.edges.set(binding.id, binding)
        }
        return ok(finish(w, snapshot, graph.nextSeq + 1), true, selectNode(id))
      }
      // masker
      if (findNode(graph, SINGLETON_IDS.masker)) return fail(graph, '마스킹 워커는 하나만 둘 수 있습니다.')
      const w = begin(graph)
      const node = ensureNode(w, 'masker', SINGLETON_IDS.masker, null, { deploymentId: refId })
      if (command.position) { node.x = command.position.x; node.y = command.position.y; w.created.pop() }
      const occupied = [...w.edges.values()].some((e) => e.to.nodeId === 'guardrail' && e.to.portId === 'masker')
      if (!occupied) {
        const e = makeEdge('masker', { nodeId: 'masker', portId: 'masker' }, { nodeId: 'guardrail', portId: 'masker' }, false)
        w.edges.set(e.id, e)
      }
      return ok(finish(w, snapshot, undefined, false), true, selectNode('masker'))
    }

    case 'removeItems': {
      const w = begin(graph)
      let error: string | null = null
      let changed = false
      const removedNodes = new Set<string>()
      for (const id of command.nodeIds) {
        const node = w.nodes.get(id)
        if (!node) continue
        if (node.type === 'deployment') continue // evaluated after targets are removed
        if (!NODE_TYPES[node.type].deletable) { error ??= '고정 노드는 삭제할 수 없습니다.'; continue }
        removedNodes.add(id)
      }
      const removedEdges = new Set<string>()
      for (const id of command.edgeIds) {
        const edge = w.edges.get(id)
        if (!edge) continue
        if (edge.locked) { error ??= MSG_AUTO; continue }
        removedEdges.add(id)
      }
      for (const id of command.nodeIds) {
        const node = w.nodes.get(id)
        if (!node || node.type !== 'deployment') continue
        const stillBound = [...w.edges.values()].some((e) => e.kind === 'binding' && e.to.nodeId === id && !removedEdges.has(e.id) && !removedNodes.has(e.from.nodeId))
        if (stillBound) { error ??= 'Target이 연결된 Deployment는 제거할 수 없습니다.'; continue }
        removedNodes.add(id)
      }
      for (const e of [...w.edges.values()]) {
        if (removedEdges.has(e.id) || removedNodes.has(e.from.nodeId) || removedNodes.has(e.to.nodeId)) { w.edges.delete(e.id); changed = true }
      }
      for (const id of removedNodes) { w.nodes.delete(id); changed = true }
      if (!changed) return error ? fail(graph, error) : ok(graph, false, { nodeIds: [], edgeIds: [] })
      const result = ok(finish(w, snapshot), true, { nodeIds: [], edgeIds: [] })
      return { ...result, error }
    }

    case 'connect': {
      const check = canConnect(graph, command.from, command.to, snapshot)
      if (!check.ok) return fail(graph, check.reason)
      const fromNode = findNode(graph, command.from.nodeId) as FlowNode
      const toNode = findNode(graph, command.to.nodeId) as FlowNode
      const w = begin(graph)
      if (fromNode.type === 'router' && toNode.type === 'deployment') {
        const id = `target:new-${graph.nextSeq}`
        const deploymentId = toNode.refId ?? ''
        ensureNode(w, 'target', id, null, newTargetProps(graph, deploymentId, snapshot))
        const route = makeEdge('route', { nodeId: 'router', portId: 'targets' }, { nodeId: id, portId: 'route' }, true)
        const binding = makeEdge('binding', { nodeId: id, portId: 'binding' }, { nodeId: toNode.id, portId: 'binding' }, false)
        w.edges.set(route.id, route)
        w.edges.set(binding.id, binding)
        return ok(finish(w, snapshot, graph.nextSeq + 1), true, selectNode(id))
      }
      const rule = CONNECTION_RULES.find((r) => r.fromType === fromNode.type && r.fromPort === command.from.portId && r.toType === toNode.type && r.toPort === command.to.portId)
      for (const id of check.replaces) w.edges.delete(id)
      const edge = makeEdge(rule ? rule.kind : 'access', command.from, command.to, rule ? !rule.userEditable : false)
      w.edges.set(edge.id, edge)
      if (edge.kind === 'binding') {
        const target = w.nodes.get(fromNode.id)
        if (target && toNode.refId && externalDeployment(snapshot, toNode.refId)) target.props.followModelChanges = false
      }
      return ok(finish(w, snapshot), true, null)
    }

    case 'updateProps': {
      const node = findNode(graph, command.nodeId)
      if (!node) return fail(graph, '알 수 없는 노드입니다.')
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

    case 'bindTarget': {
      const target = findNode(graph, command.targetNodeId)
      if (!target || target.type !== 'target') return fail(graph, '알 수 없는 노드입니다.')
      if (!snapshot.catalog.deployments.some((d) => d.id === command.deploymentId)) return fail(graph, '알 수 없는 Deployment입니다.')
      const depId = deploymentNodeId(command.deploymentId)
      const w = begin(graph)
      ensureNode(w, 'deployment', depId, command.deploymentId)
      removeEdgesWhere(w, (e) => e.kind === 'binding' && e.from.nodeId === target.id)
      const binding = makeEdge('binding', { nodeId: target.id, portId: 'binding' }, { nodeId: depId, portId: 'binding' }, false)
      w.edges.set(binding.id, binding)
      const t = w.nodes.get(target.id)
      if (t && externalDeployment(snapshot, command.deploymentId)) t.props.followModelChanges = false
      return ok(finish(w, snapshot), true, null)
    }

    case 'setAccess': {
      const project = findNode(graph, command.projectNodeId)
      if (!project || project.type !== 'project') return fail(graph, '알 수 없는 노드입니다.')
      const w = begin(graph)
      const edge = makeEdge('access', { nodeId: project.id, portId: 'access' }, { nodeId: 'service', portId: 'access' }, false)
      const present = w.edges.has(edge.id)
      if (command.granted === present) return ok(graph, false)
      if (command.granted) w.edges.set(edge.id, edge)
      else w.edges.delete(edge.id)
      return ok(finish(w, snapshot, undefined, false), true)
    }

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
      return ok(autoLayout(graph, snapshot), true)
  }
}

