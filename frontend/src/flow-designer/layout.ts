import { NODE_TYPES, GRID_SIZE } from './catalog'
import { analyzeWorkflow, dependencyClosure, isBranchPrompt, rankOf } from './workflow'
import type {
  AddableNodeType,
  EdgeKind,
  FlowGraph,
  FlowLayout,
  FlowLayoutNode,
  FlowNode,
  NodeType,
  Point,
  PortDirection,
  Rect,
  ServiceFlowSnapshot,
  Size,
  Viewport,
} from './types'

export const NODE_WIDTH = 232
export const HEADER_HEIGHT = 40
export const PORT_ROW_HEIGHT = 24
export const SUMMARY_HEIGHT = 56
export const NODE_PADDING = 8
export const COLLAPSED_HEIGHT = 40
export const COLUMN_WIDTH = 300
export const ROW_GAP = 24
export const MIN_ZOOM = 0.2
export const MAX_ZOOM = 2

/** v2 layout key pattern (§7.8): a superset of the v1 pattern, so saved v1 layouts stay valid. */
export const LAYOUT_NODE_ID_PATTERN = /^(start|service|guardrail|prompt|params|cache|router|masker|(project|policy|prompt|target|deployment|endpoint|provider):[A-Za-z0-9-]{1,64})$/
/** The original v1 pattern, used to retry a layout save against a pre-v2 backend. */
export const LAYOUT_NODE_ID_PATTERN_V1 = /^(service|guardrail|prompt|cache|router|masker|(project|target|deployment|endpoint|provider):[A-Za-z0-9-]{1,64})$/

const LAYOUT_COORD_LIMIT = 1_000_000
const LAYOUT_MAX_NODES = 500

function round1(value: number): number {
  return Math.round(value * 10) / 10
}

export function nodeSize(node: FlowNode): Size {
  if (node.collapsed) return { width: NODE_WIDTH, height: COLLAPSED_HEIGHT }
  const ports = NODE_TYPES[node.type].ports
  const ins = ports.filter((p) => p.direction === 'in').length
  const outs = ports.filter((p) => p.direction === 'out').length
  const rows = Math.max(ins, outs, 1)
  return { width: NODE_WIDTH, height: HEADER_HEIGHT + rows * PORT_ROW_HEIGHT + SUMMARY_HEIGHT + NODE_PADDING }
}

export function nodeRect(node: FlowNode): Rect {
  const size = nodeSize(node)
  return { x: node.x, y: node.y, width: size.width, height: size.height }
}

/**
 * Anchor of a port in world coordinates. Some nodes have an in and an out port with the same id:
 * pass `direction` to disambiguate; without it the first matching port (in ports are listed first) is used.
 */
export function portAnchor(node: FlowNode, portId: string, direction?: PortDirection): Point {
  const ports = NODE_TYPES[node.type].ports
  const spec = ports.find((p) => p.id === portId && (direction === undefined || p.direction === direction))
  const dir: PortDirection = spec ? spec.direction : (direction ?? 'in')
  const x = dir === 'in' ? node.x : node.x + NODE_WIDTH
  if (node.collapsed) return { x, y: node.y + COLLAPSED_HEIGHT / 2 }
  const index = spec ? ports.filter((p) => p.direction === dir).indexOf(spec) : 0
  return { x, y: node.y + HEADER_HEIGHT + PORT_ROW_HEIGHT * index + PORT_ROW_HEIGHT / 2 }
}

export function bezierPath(from: Point, to: Point): string {
  const dx = Math.max(40, Math.abs(to.x - from.x) * 0.5)
  return `M ${round1(from.x)} ${round1(from.y)} C ${round1(from.x + dx)} ${round1(from.y)}, ${round1(to.x - dx)} ${round1(to.y)}, ${round1(to.x)} ${round1(to.y)}`
}

export function graphBounds(graph: FlowGraph): Rect {
  if (graph.nodes.length === 0) return { x: 0, y: 0, width: 0, height: 0 }
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const node of graph.nodes) {
    const r = nodeRect(node)
    minX = Math.min(minX, r.x)
    minY = Math.min(minY, r.y)
    maxX = Math.max(maxX, r.x + r.width)
    maxY = Math.max(maxY, r.y + r.height)
  }
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
}

export function clampZoom(zoom: number): number {
  if (!Number.isFinite(zoom)) return 1
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom))
}

export function fitViewport(bounds: Rect, size: Size, padding = 48): Viewport {
  if (!(bounds.width > 0) || !(bounds.height > 0) || !(size.width > 0) || !(size.height > 0)) {
    return { x: Math.round(size.width / 2 - (bounds.x + bounds.width / 2)) || 0, y: Math.round(size.height / 2 - (bounds.y + bounds.height / 2)) || 0, zoom: 1 }
  }
  const availableW = Math.max(1, size.width - padding * 2)
  const availableH = Math.max(1, size.height - padding * 2)
  const zoom = clampZoom(Math.min(availableW / bounds.width, availableH / bounds.height, 1))
  return {
    x: (size.width - bounds.width * zoom) / 2 - bounds.x * zoom,
    y: (size.height - bounds.height * zoom) / 2 - bounds.y * zoom,
    zoom,
  }
}

export function screenToWorld(point: Point, viewport: Viewport): Point {
  return { x: (point.x - viewport.x) / viewport.zoom, y: (point.y - viewport.y) / viewport.zoom }
}

export function worldToScreen(point: Point, viewport: Viewport): Point {
  return { x: point.x * viewport.zoom + viewport.x, y: point.y * viewport.zoom + viewport.y }
}

export function zoomAt(viewport: Viewport, screenPoint: Point, factor: number): Viewport {
  const zoom = clampZoom(viewport.zoom * factor)
  const world = screenToWorld(screenPoint, viewport)
  return { x: screenPoint.x - world.x * zoom, y: screenPoint.y - world.y * zoom, zoom }
}

export function snapToGrid(value: number, grid = GRID_SIZE): number {
  return Math.round(value / grid) * grid + 0
}

function snapUp(value: number, grid = GRID_SIZE): number {
  return Math.ceil(value / grid) * grid + 0
}

export function nodesInRect(graph: FlowGraph, rect: Rect): string[] {
  const x1 = Math.min(rect.x, rect.x + rect.width)
  const x2 = Math.max(rect.x, rect.x + rect.width)
  const y1 = Math.min(rect.y, rect.y + rect.height)
  const y2 = Math.max(rect.y, rect.y + rect.height)
  const ids: string[] = []
  for (const node of graph.nodes) {
    const r = nodeRect(node)
    if (r.x <= x2 && r.x + r.width >= x1 && r.y <= y2 && r.y + r.height >= y1) ids.push(node.id)
  }
  return ids
}

/**
 * Converts a client-space point to canvas-local CSS px. The console applies CSS `zoom`, so
 * getBoundingClientRect() values are scaled by s = rect.width / layoutWidth while layout px are not.
 */
export function clientScale(rectWidth: number, layoutWidth: number): number {
  const s = rectWidth / layoutWidth
  return Number.isFinite(s) && s > 0 ? s : 1
}

export function clientToLocal(client: Point, rect: { left: number; top: number; width: number }, layoutWidth: number): Point {
  const s = clientScale(rect.width, layoutWidth)
  return { x: (client.x - rect.left) / s, y: (client.y - rect.top) / s }
}

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }

function rectsOverlap(a: Rect, b: Rect, gap = 0): boolean {
  return a.x < b.x + b.width + gap && a.x + a.width + gap > b.x && a.y < b.y + b.height + gap && a.y + a.height + gap > b.y
}

function cloneGraph(graph: FlowGraph, nodes: FlowNode[]): FlowGraph {
  return {
    ...graph,
    nodes,
    edges: graph.edges.map((e) => ({ ...e, from: { ...e.from }, to: { ...e.to } })),
  }
}

/** Static lane of each node type; used when a node is placed without any structural hint. */
const FALLBACK_LANE: Record<NodeType, number> = {
  start: 0, project: 1, policy: 2, guardrail: 3, masker: 3, prompt: 4, cache: 5, params: 6, router: 7, llm: 9, endpoint: 10, provider: 10,
}

function probeNode(type: NodeType | AddableNodeType): FlowNode {
  return { id: '__probe__', type, refId: null, targetId: null, x: 0, y: 0, collapsed: false, props: {} }
}

/** Pushes a node down until it no longer overlaps any other node (at most 200 steps). */
function freeSpot(others: FlowNode[], node: FlowNode, x: number, y: number): Point {
  const size = nodeSize(node)
  const rects = others.filter((n) => n.id !== node.id).map(nodeRect)
  let cy = snapUp(y)
  for (let i = 0; i < 200; i += 1) {
    const here: Rect = { x, y: cy, width: size.width, height: size.height }
    const hit = rects.find((r) => rectsOverlap(here, r))
    if (!hit) break
    cy = snapUp(hit.y + hit.height + ROW_GAP)
  }
  return { x, y: cy }
}

/** The right-most node of the entry layer (start, projects, policies); ties go to the topmost. */
function entryRight(nodes: FlowNode[]): FlowNode | undefined {
  let best: FlowNode | undefined
  for (const n of nodes) {
    if (n.type !== 'start' && n.type !== 'project' && n.type !== 'policy') continue
    if (!best || n.x > best.x || (n.x === best.x && n.y < best.y)) best = n
  }
  return best
}

function without(graph: FlowGraph, nodeId: string): FlowGraph {
  return {
    ...graph,
    nodes: graph.nodes.filter((n) => n.id !== nodeId),
    edges: graph.edges.filter((e) => e.from.nodeId !== nodeId && e.to.nodeId !== nodeId),
  }
}

/* ------------------------------------------------------------------ */
/* Auto layout (§7.9)                                                  */
/* ------------------------------------------------------------------ */

export function autoLayout(graph: FlowGraph, snapshot?: ServiceFlowSnapshot): FlowGraph {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const analysis = analyzeWorkflow(graph, snapshot)
  const positions = new Map<string, Point>()
  const stack = (nodes: FlowNode[], x: number, startY = 0) => {
    let y = snapUp(startY)
    for (const node of nodes) {
      positions.set(node.id, { x, y })
      y = snapUp(y + nodeSize(node).height + ROW_GAP)
    }
  }

  let lane = 0
  const start = byId.get('start')
  if (start) positions.set(start.id, { x: 0, y: 0 })
  lane = 1

  // Lane 1: projects (granted first, then the rest, each by name).
  const granted = new Set(graph.edges.filter((e) => e.kind === 'entry').map((e) => e.to.nodeId))
  const projectName = (n: FlowNode): string => snapshot?.catalog.projects.find((p) => p.id === n.refId)?.name ?? ''
  const projects = graph.nodes.filter((n) => n.type === 'project')
  projects.sort((a, b) => {
    const ga = granted.has(a.id) ? 0 : 1
    const gb = granted.has(b.id) ? 0 : 1
    if (ga !== gb) return ga - gb
    return cmp(projectName(a), projectName(b)) || cmp(a.refId ?? a.id, b.refId ?? b.id)
  })
  stack(projects, lane * COLUMN_WIDTH)
  lane += 1

  // Lane 2 (only when inherited policy nodes exist).
  const policies = graph.nodes.filter((n) => n.type === 'policy')
  if (policies.length > 0) {
    for (const policy of policies) {
      const owner = byId.get(`project:${policy.refId}`)
      const ownerPos = owner ? positions.get(owner.id) : undefined
      positions.set(policy.id, { x: lane * COLUMN_WIDTH, y: ownerPos ? ownerPos.y : 0 })
    }
    lane += 1
  }

  // Trunk: one node per lane, left to right.
  for (const id of analysis.trunk) {
    const node = byId.get(id)
    if (!node) continue
    positions.set(id, { x: lane * COLUMN_WIDTH, y: 0 })
    const masker = byId.get('masker')
    if (node.type === 'guardrail' && masker) {
      positions.set('masker', { x: lane * COLUMN_WIDTH, y: snapUp(nodeSize(node).height + 48) })
    }
    lane += 1
  }

  // Branches: optional model-prompt lane, then the LLM lane.
  const branches = analysis.branches
  if (branches.length > 0) {
    const hasPrompts = branches.some((b) => b.promptNodeId !== null)
    const promptLane = hasPrompts ? lane++ : -1
    const llmLane = lane++
    let y = 0
    const llmY = new Map<string, number>()
    for (const branch of branches) {
      const llm = byId.get(branch.llmNodeId)
      if (!llm) continue
      positions.set(llm.id, { x: llmLane * COLUMN_WIDTH, y })
      llmY.set(llm.id, y)
      const prompt = branch.promptNodeId ? byId.get(branch.promptNodeId) : undefined
      if (prompt && promptLane >= 0) positions.set(prompt.id, { x: promptLane * COLUMN_WIDTH, y })
      const height = Math.max(nodeSize(llm).height, prompt ? nodeSize(prompt).height : 0)
      y = snapUp(y + height + ROW_GAP)
    }

    // Hosts follow the first LLM that uses them.
    const hosts = graph.nodes.filter((n) => (n.type === 'endpoint' || n.type === 'provider'))
    const order = new Map(branches.map((b, i) => [b.llmNodeId, i]))
    const firstUser = (hostId: string): string | null => {
      let best: string | null = null
      for (const e of graph.edges) {
        if (e.kind !== 'host' || e.to.nodeId !== hostId || !llmY.has(e.from.nodeId)) continue
        if (best === null || (order.get(e.from.nodeId) ?? Infinity) < (order.get(best) ?? Infinity)) best = e.from.nodeId
      }
      return best
    }
    const placedHosts = hosts
      .map((h) => ({ node: h, user: firstUser(h.id) }))
      .filter((x) => x.user !== null)
      .sort((a, b) => (order.get(a.user as string) ?? 0) - (order.get(b.user as string) ?? 0) || cmp(a.node.id, b.node.id))
    if (placedHosts.length > 0) {
      const hostLane = lane++
      let floor = 0
      for (const { node, user } of placedHosts) {
        const hy = Math.max(llmY.get(user as string) ?? 0, floor)
        positions.set(node.id, { x: hostLane * COLUMN_WIDTH, y: hy })
        floor = snapUp(hy + nodeSize(node).height + ROW_GAP)
      }
    }
  }

  // Everything that is not part of the request path goes below, ordered by rank.
  const loose = graph.nodes.filter((n) => !positions.has(n.id)).sort((a, b) => rankOf(a) - rankOf(b) || cmp(a.id, b.id))
  if (loose.length > 0) {
    let maxY = -Infinity
    for (const [id, p] of positions) {
      const node = byId.get(id)
      if (node) maxY = Math.max(maxY, p.y + nodeSize(node).height)
    }
    const baseY = maxY === -Infinity ? 0 : snapUp(maxY + 96)
    loose.forEach((node, i) => positions.set(node.id, { x: i * COLUMN_WIDTH, y: baseY }))
  }

  return cloneGraph(graph, graph.nodes.map((n) => {
    const p = positions.get(n.id)
    return p ? { ...n, x: p.x, y: p.y } : { ...n }
  }))
}

/* ------------------------------------------------------------------ */
/* Placing new nodes                                                   */
/* ------------------------------------------------------------------ */

function fallbackSpot(others: FlowNode[], node: FlowNode): Point {
  const x = FALLBACK_LANE[node.type] * COLUMN_WIDTH
  let bottom = -Infinity
  for (const other of others) {
    if (other.id === node.id || other.x !== x) continue
    bottom = Math.max(bottom, other.y + nodeSize(other).height)
  }
  return { x, y: bottom === -Infinity ? 0 : snapUp(bottom + ROW_GAP) }
}

function structuralSpot(graph: FlowGraph, node: FlowNode, snapshot?: ServiceFlowSnapshot): Point {
  const others = graph.nodes.filter((n) => n.id !== node.id)
  const find = (id: string) => others.find((n) => n.id === id)
  switch (node.type) {
    case 'project': {
      const projects = others.filter((n) => n.type === 'project')
      const x = projects.length > 0 ? Math.min(...projects.map((p) => p.x)) : COLUMN_WIDTH
      let bottom = -Infinity
      for (const p of projects) bottom = Math.max(bottom, p.y + nodeSize(p).height)
      return { x, y: bottom === -Infinity ? 0 : snapUp(bottom + ROW_GAP) }
    }
    case 'policy': {
      const owner = find(`project:${node.refId}`)
      return owner ? { x: owner.x + COLUMN_WIDTH, y: owner.y } : fallbackSpot(others, node)
    }
    case 'endpoint':
    case 'provider': {
      const users = graph.edges
        .filter((e) => e.kind === 'host' && e.to.nodeId === node.id)
        .map((e) => find(e.from.nodeId))
        .filter((n): n is FlowNode => !!n)
        .sort((a, b) => a.y - b.y || cmp(a.id, b.id))
      return users.length > 0 ? { x: users[0].x + COLUMN_WIDTH, y: users[0].y } : fallbackSpot(others, node)
    }
    case 'masker': {
      const guardrail = find('guardrail')
      return guardrail ? { x: guardrail.x, y: snapUp(guardrail.y + nodeSize(guardrail).height + 48) } : fallbackSpot(others, node)
    }
    case 'llm': {
      const llms = others.filter((n) => n.type === 'llm')
      if (llms.length > 0) {
        const x = Math.min(...llms.map((n) => n.x))
        let bottom = -Infinity
        for (const n of llms) bottom = Math.max(bottom, n.y + nodeSize(n).height)
        return { x, y: snapUp(bottom + ROW_GAP) }
      }
      const trunk = analyzeWorkflow(without(graph, node.id), snapshot).tail
      const tail = trunk ? find(trunk) : undefined
      if (tail) return { x: tail.x + COLUMN_WIDTH, y: tail.y }
      const entry = entryRight(others)
      return entry ? { x: entry.x + COLUMN_WIDTH, y: 0 } : { x: FALLBACK_LANE.llm * COLUMN_WIDTH, y: 0 }
    }
    default: {
      if (isBranchPrompt(node)) {
        const llm = find(`deployment:${node.id.slice('prompt:'.length)}`)
        return llm ? { x: llm.x - COLUMN_WIDTH, y: llm.y } : fallbackSpot(others, node)
      }
      const analysis = analyzeWorkflow(without(graph, node.id), snapshot)
      const tail = analysis.tail ? find(analysis.tail) : undefined
      if (tail) return { x: tail.x + COLUMN_WIDTH, y: tail.y }
      const entry = entryRight(others)
      return entry ? { x: entry.x + COLUMN_WIDTH, y: 0 } : fallbackSpot(others, node)
    }
  }
}

/** Places a node (already present in the graph) at a structurally sensible, non-overlapping spot. */
export function placeNewNode(graph: FlowGraph, nodeId: string, snapshot?: ServiceFlowSnapshot): FlowGraph {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return graph
  const spot = structuralSpot(graph, node, snapshot)
  const free = freeSpot(graph.nodes, node, spot.x, spot.y)
  return { ...graph, nodes: graph.nodes.map((n) => (n.id === nodeId ? { ...n, x: free.x, y: free.y } : n)) }
}

/**
 * Places a node that was just spliced between `fromNodeId` and `toNodeId` (either may be null) and makes room by
 * pushing the downstream nodes of `toNodeId` to the right when the gap is too small.
 */
export function placeSpliced(graph: FlowGraph, nodeId: string, fromNodeId: string | null, toNodeId: string | null): FlowGraph {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return graph
  const from = fromNodeId ? graph.nodes.find((n) => n.id === fromNodeId) : undefined
  const to = toNodeId ? graph.nodes.find((n) => n.id === toNodeId) : undefined
  const anchor = from ?? entryRight(graph.nodes.filter((n) => n.id !== nodeId))
  const x = anchor ? anchor.x + COLUMN_WIDTH : COLUMN_WIDTH
  const y = isBranchPrompt(node) && to ? to.y : (anchor ? anchor.y : 0)

  let nodes = graph.nodes
  if (to && to.x < x + COLUMN_WIDTH) {
    const dx = x + COLUMN_WIDTH - to.x
    const seen = new Set<string>([to.id])
    const queue = [to.id]
    while (queue.length > 0) {
      const cur = queue.shift() as string
      for (const e of graph.edges) {
        if (e.from.nodeId === cur && e.to.nodeId !== nodeId && !seen.has(e.to.nodeId)) { seen.add(e.to.nodeId); queue.push(e.to.nodeId) }
      }
    }
    seen.delete(nodeId)
    if (anchor) seen.delete(anchor.id)
    const shifted = new Set(dependencyClosure(graph, [...seen]))
    shifted.delete(nodeId)
    if (anchor) shifted.delete(anchor.id)
    nodes = nodes.map((n) => (shifted.has(n.id) ? { ...n, x: n.x + dx } : n))
  }
  const free = freeSpot(nodes, node, x, y)
  return { ...graph, nodes: nodes.map((n) => (n.id === nodeId ? { ...n, x: free.x, y: free.y } : n)) }
}

/**
 * Position for a node added without an explicit drop point: its fallback lane, below the lowest node there.
 * If that spot is outside the visible world rect, use the view center instead, nudged down until it no longer
 * overlaps another node.
 */
export function suggestNodePosition(graph: FlowGraph, nodeType: NodeType | AddableNodeType, center: Point, visible?: Rect | null): Point {
  const probe = probeNode(nodeType)
  const size = nodeSize(probe)
  const column = fallbackSpot(graph.nodes, probe)
  const colRect: Rect = { x: column.x, y: column.y, width: size.width, height: size.height }
  if (!visible || rectsOverlap(colRect, visible)) return column
  const x = snapToGrid(center.x - size.width / 2)
  let y = snapToGrid(center.y - size.height / 2)
  const rects = graph.nodes.map(nodeRect)
  for (let i = 0; i < 200; i += 1) {
    const here: Rect = { x, y, width: size.width, height: size.height }
    const hit = rects.find((r) => rectsOverlap(here, r, ROW_GAP / 2))
    if (!hit) break
    y = snapUp(hit.y + hit.height + ROW_GAP)
  }
  return { x, y }
}

/* ------------------------------------------------------------------ */
/* Saved layouts                                                       */
/* ------------------------------------------------------------------ */

/** 2 when the layout (if any) uses v2 node keys or has no keys at all; 1 for a v1-shaped layout (§7.8). */
export function layoutGeneration(layout: FlowLayout | null): 1 | 2 {
  if (!layout) return 2
  const keys = Object.keys(layout.nodes ?? {})
  if (keys.length === 0) return 2
  return keys.includes('start') ? 2 : 1
}

export function applyLayout(graph: FlowGraph, layout: FlowLayout | null, snapshot?: ServiceFlowSnapshot): FlowGraph {
  if (!layout || layoutGeneration(layout) === 1) return autoLayout(graph, snapshot)
  const known = layout.nodes ?? {}
  const placed: FlowNode[] = []
  const pending: FlowNode[] = []
  for (const node of graph.nodes) {
    const saved = Object.prototype.hasOwnProperty.call(known, node.id) ? known[node.id] : undefined
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) placed.push({ ...node, x: saved.x, y: saved.y, collapsed: saved.collapsed === true })
    else pending.push(node)
  }
  // Place unsaved nodes one by one against the nodes that already have a position.
  let working: FlowGraph = { ...graph, nodes: placed }
  const positions = new Map<string, Point>()
  for (const node of pending) {
    const candidate: FlowGraph = { ...working, nodes: [...working.nodes, node] }
    const next = placeNewNode(candidate, node.id, snapshot)
    const hit = next.nodes.find((n) => n.id === node.id) as FlowNode
    positions.set(node.id, { x: hit.x, y: hit.y })
    working = { ...working, nodes: [...working.nodes, hit] }
  }
  const placedById = new Map(placed.map((n) => [n.id, n]))
  return cloneGraph(graph, graph.nodes.map((n) => {
    const hit = placedById.get(n.id)
    if (hit) return hit
    const p = positions.get(n.id) ?? { x: n.x, y: n.y }
    return { ...n, x: p.x, y: p.y }
  }))
}

export function extractLayout(graph: FlowGraph, viewport: Viewport): FlowLayout {
  const entries: Array<[string, FlowLayoutNode]> = []
  for (const node of graph.nodes) {
    if (!LAYOUT_NODE_ID_PATTERN.test(node.id)) continue
    const entry: FlowLayoutNode = { x: node.x, y: node.y }
    if (node.collapsed) entry.collapsed = true
    entries.push([node.id, entry])
  }
  entries.sort((a, b) => cmp(a[0], b[0]))
  const nodes: Record<string, FlowLayoutNode> = {}
  for (const [id, entry] of entries.slice(0, LAYOUT_MAX_NODES)) nodes[id] = entry
  return { version: 1, viewport: { x: viewport.x, y: viewport.y, zoom: viewport.zoom }, nodes }
}

function finiteInRange(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= LAYOUT_COORD_LIMIT
}

export function sanitizeLayout(raw: unknown): FlowLayout | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const source = raw as Record<string, unknown>
  if (source.version !== 1) return null
  const vp = source.viewport && typeof source.viewport === 'object' ? (source.viewport as Record<string, unknown>) : {}
  const viewport: Viewport = {
    x: finiteInRange(vp.x) ? vp.x : 0,
    y: finiteInRange(vp.y) ? vp.y : 0,
    zoom: typeof vp.zoom === 'number' && Number.isFinite(vp.zoom) ? clampZoom(vp.zoom) : 1,
  }
  const nodes: Record<string, FlowLayoutNode> = {}
  const rawNodes = source.nodes && typeof source.nodes === 'object' && !Array.isArray(source.nodes) ? (source.nodes as Record<string, unknown>) : {}
  let count = 0
  for (const id of Object.keys(rawNodes).sort(cmp)) {
    if (count >= LAYOUT_MAX_NODES) break
    if (!LAYOUT_NODE_ID_PATTERN.test(id)) continue
    const value = rawNodes[id]
    if (!value || typeof value !== 'object') continue
    const v = value as Record<string, unknown>
    if (!finiteInRange(v.x) || !finiteInRange(v.y)) continue
    const entry: FlowLayoutNode = { x: v.x, y: v.y }
    if (v.collapsed === true) entry.collapsed = true
    nodes[id] = entry
    count += 1
  }
  return { version: 1, viewport, nodes }
}

/* ------------------------------------------------------------------ */
/* Edge hit testing                                                    */
/* ------------------------------------------------------------------ */

function distanceToSegment(p: Point, a: Point, b: Point): number {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const len2 = dx * dx + dy * dy
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2))
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** The edge (of the given kinds) whose curve passes closest to `point`, within `radius`; ties go to the lower id. */
export function nearestEdge(graph: FlowGraph, point: Point, radius: number, kinds: EdgeKind[]): string | null {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  let best: string | null = null
  let bestDistance = Infinity
  const SAMPLES = 24
  for (const edge of graph.edges) {
    if (!kinds.includes(edge.kind)) continue
    const fromNode = byId.get(edge.from.nodeId)
    const toNode = byId.get(edge.to.nodeId)
    if (!fromNode || !toNode) continue
    const p0 = portAnchor(fromNode, edge.from.portId, 'out')
    const p3 = portAnchor(toNode, edge.to.portId, 'in')
    const dx = Math.max(40, Math.abs(p3.x - p0.x) * 0.5)
    const p1: Point = { x: p0.x + dx, y: p0.y }
    const p2: Point = { x: p3.x - dx, y: p3.y }
    let prev: Point = p0
    let distance = Infinity
    for (let i = 1; i < SAMPLES; i += 1) {
      const t = i / (SAMPLES - 1)
      const u = 1 - t
      const cur: Point = {
        x: u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
        y: u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
      }
      distance = Math.min(distance, distanceToSegment(point, prev, cur))
      prev = cur
    }
    if (distance > radius) continue
    if (distance < bestDistance || (distance === bestDistance && best !== null && cmp(edge.id, best) < 0)) {
      best = edge.id
      bestDistance = distance
    }
  }
  return best
}
