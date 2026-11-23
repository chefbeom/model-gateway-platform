import { NODE_TYPES, GRID_SIZE } from './catalog'
import type {
  FlowGraph,
  FlowLayout,
  FlowLayoutNode,
  FlowNode,
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

export const LAYOUT_NODE_ID_PATTERN = /^(service|guardrail|prompt|cache|router|masker|(project|target|deployment|endpoint|provider):[A-Za-z0-9-]{1,64})$/

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
 * Anchor of a port in world coordinates. Some nodes (guardrail) have an in and an out port with the same id:
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

/* ------------------------------------------------------------------ */
/* Auto layout                                                         */
/* ------------------------------------------------------------------ */

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }
function numOr(value: unknown, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : fallback
}

export function autoLayout(graph: FlowGraph, snapshot?: ServiceFlowSnapshot): FlowGraph {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const columnOf = (n: FlowNode) => NODE_TYPES[n.type].column

  // Ordering keys for the stacked columns.
  const targetNodes = graph.nodes.filter((n) => n.type === 'target')
  targetNodes.sort((a, b) =>
    numOr(a.props.priority, Infinity) - numOr(b.props.priority, Infinity) ||
    numOr(b.props.weight, 0) - numOr(a.props.weight, 0) ||
    cmp(a.id, b.id))
  // The subtraction of two Infinity values yields NaN; fall back to a stable key order in that case.
  const targetOrder = new Map<string, number>()
  targetNodes.forEach((n, i) => targetOrder.set(n.id, i))

  const depTargetRank = (depId: string): number => {
    let best = Infinity
    for (const e of graph.edges) {
      if (e.kind === 'binding' && e.to.nodeId === depId) best = Math.min(best, targetOrder.get(e.from.nodeId) ?? Infinity)
    }
    return best
  }
  const deployments = graph.nodes.filter((n) => n.type === 'deployment')
  deployments.sort((a, b) => {
    const ra = depTargetRank(a.id)
    const rb = depTargetRank(b.id)
    if (ra !== rb) return ra === Infinity ? 1 : rb === Infinity ? -1 : ra - rb
    return cmp(a.refId ?? a.id, b.refId ?? b.id)
  })
  const depOrder = new Map<string, number>()
  deployments.forEach((n, i) => depOrder.set(n.id, i))

  const hostRank = (hostId: string): number => {
    let best = Infinity
    for (const e of graph.edges) {
      if (e.kind === 'host' && e.to.nodeId === hostId) best = Math.min(best, depOrder.get(e.from.nodeId) ?? Infinity)
    }
    return best
  }
  const hosts = graph.nodes.filter((n) => n.type === 'endpoint' || n.type === 'provider')
  hosts.sort((a, b) => {
    const ra = hostRank(a.id)
    const rb = hostRank(b.id)
    if (ra !== rb) return ra === Infinity ? 1 : rb === Infinity ? -1 : ra - rb
    return cmp(a.id, b.id)
  })

  const projectName = (n: FlowNode): string => {
    const hit = snapshot?.catalog.projects.find((p) => p.id === n.refId)
    return hit ? hit.name : ''
  }
  const projects = graph.nodes.filter((n) => n.type === 'project')
  projects.sort((a, b) => {
    if (snapshot) {
      const byName = cmp(projectName(a), projectName(b))
      if (byName !== 0) return byName
    }
    return cmp(a.refId ?? a.id, b.refId ?? b.id)
  })

  const positions = new Map<string, Point>()
  const stack = (nodes: FlowNode[], column: number, startY = 0) => {
    let y = snapUp(startY)
    for (const node of nodes) {
      positions.set(node.id, { x: column * COLUMN_WIDTH, y })
      y = snapUp(y + nodeSize(node).height + ROW_GAP)
    }
  }
  stack(projects, 0)
  stack(targetNodes, 6)
  stack(deployments, 7)
  stack(hosts, 8)

  // Singletons: one node per column except the masker, which sits below the service.
  const service = byId.get('service')
  for (const node of graph.nodes) {
    if (node.type === 'masker' || positions.has(node.id)) continue
    if (['service', 'guardrail', 'prompt', 'cache', 'router'].includes(node.type)) {
      positions.set(node.id, { x: columnOf(node) * COLUMN_WIDTH, y: 0 })
    }
  }
  const masker = byId.get('masker')
  if (masker) {
    const serviceHeight = service ? nodeSize(service).height : 0
    positions.set('masker', { x: COLUMN_WIDTH, y: snapUp(serviceHeight + 48) })
  }

  return {
    ...graph,
    nodes: graph.nodes.map((n) => {
      const p = positions.get(n.id)
      return p ? { ...n, x: p.x, y: p.y } : { ...n }
    }),
    edges: graph.edges.map((e) => ({ ...e, from: { ...e.from }, to: { ...e.to } })),
  }
}

function placeIn(nodes: FlowNode[], node: FlowNode): Point {
  const column = NODE_TYPES[node.type].column
  const x = column * COLUMN_WIDTH
  if (node.type === 'masker') {
    const service = nodes.find((n) => n.type === 'service')
    return { x, y: snapUp((service ? nodeSize(service).height : 0) + 48) }
  }
  let bottom = -Infinity
  for (const other of nodes) {
    if (other.id === node.id || NODE_TYPES[other.type].column !== column) continue
    bottom = Math.max(bottom, other.y + nodeSize(other).height)
  }
  return { x, y: bottom === -Infinity ? 0 : snapUp(bottom + ROW_GAP) }
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

function rectsOverlap(a: Rect, b: Rect, gap = 0): boolean {
  return a.x < b.x + b.width + gap && a.x + a.width + gap > b.x && a.y < b.y + b.height + gap && a.y + a.height + gap > b.y
}

/**
 * Position for a node added without an explicit drop point: its catalog column, below the lowest node of
 * that column. If that spot is outside the visible world rect, use the view center instead, nudged down
 * until it no longer overlaps another node.
 */
export function suggestNodePosition(graph: FlowGraph, nodeType: FlowNode['type'], center: Point, visible?: Rect | null): Point {
  const probe = { id: '__probe__', type: nodeType, x: 0, y: 0, props: {}, collapsed: false, refId: null } as FlowNode
  const size = nodeSize(probe)
  const column = placeIn(graph.nodes, probe)
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

export function placeNewNode(graph: FlowGraph, nodeId: string): FlowGraph {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return graph
  const point = placeIn(graph.nodes, node)
  return { ...graph, nodes: graph.nodes.map((n) => (n.id === nodeId ? { ...n, x: point.x, y: point.y } : n)) }
}

export function applyLayout(graph: FlowGraph, layout: FlowLayout | null, snapshot?: ServiceFlowSnapshot): FlowGraph {
  if (!layout) return autoLayout(graph, snapshot)
  const known = layout.nodes ?? {}
  const placed: FlowNode[] = []
  const pending: FlowNode[] = []
  for (const node of graph.nodes) {
    const saved = Object.prototype.hasOwnProperty.call(known, node.id) ? known[node.id] : undefined
    if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) placed.push({ ...node, x: saved.x, y: saved.y, collapsed: saved.collapsed === true })
    else pending.push(node)
  }
  let working = placed
  const positions = new Map<string, Point>()
  for (const node of pending) {
    const p = placeIn(working, node)
    positions.set(node.id, p)
    working = [...working, { ...node, x: p.x, y: p.y }]
  }
  const placedById = new Map(placed.map((n) => [n.id, n]))
  return {
    ...graph,
    nodes: graph.nodes.map((n) => {
      const hit = placedById.get(n.id)
      if (hit) return hit
      const p = positions.get(n.id) ?? { x: n.x, y: n.y }
      return { ...n, x: p.x, y: p.y }
    }),
    edges: graph.edges.map((e) => ({ ...e, from: { ...e.from }, to: { ...e.to } })),
  }
}

/* ------------------------------------------------------------------ */
/* Layout persistence                                                  */
/* ------------------------------------------------------------------ */

export function extractLayout(graph: FlowGraph, viewport: Viewport): FlowLayout {
  const entries: Array<[string, FlowLayoutNode]> = []
  for (const node of graph.nodes) {
    if (node.id.startsWith('target:new-')) continue
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
