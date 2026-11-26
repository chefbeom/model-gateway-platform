<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import FlowNodeCard from './FlowNodeCard.vue'
import { GRID_SIZE, NODE_TYPES, describeNode } from './catalog'
import { canConnectInView, nearestPortInView } from './graphOps'
import { buildConnectionView, connectionDragIds, connectionIdForDeployment, describeConnectionNode, isConnectionId, isConnectionNode, viewPortsOf } from './connections'
import { dependencyClosure, insertableTypesForEdge, promptScopeForSplice } from './workflow'
import {
  bezierPath,
  clientScale,
  clientToLocal,
  fitViewport,
  graphBounds,
  nearestEdge,
  nodeRect,
  nodeSize,
  nodesInRect,
  portAnchor,
  screenToWorld,
  snapToGrid,
  zoomAt,
} from './layout'
import { issueCountsByNode } from './validate'
import type {
  AddableNodeType,
  DesignerMode,
  FlowCommand,
  FlowEdge,
  FlowGraph,
  FlowNode,
  FlowSelection,
  FlowViewMode,
  NodeChangeKind,
  NodeView,
  Point,
  PortRef,
  ServiceFlowSnapshot,
  ValidationIssue,
  ViewEdge,
  Viewport,
} from './types'

const props = withDefaults(defineProps<{
  graph: FlowGraph
  snapshot: ServiceFlowSnapshot
  viewport: Viewport
  selection: FlowSelection
  issues: ValidationIssue[]
  nodeChanges: Record<string, NodeChangeKind>
  readOnly: boolean
  mode?: DesignerMode
  viewMode?: FlowViewMode
}>(), { mode: 'advanced' })

const emit = defineEmits<{
  'update:viewport': [Viewport]
  'update:selection': [FlowSelection]
  command: [FlowCommand]
  action: ['undo' | 'redo']
  'open-inspector': []
}>()

const DND_MIME = 'application/x-aiconnect-flow-node'
const ADDABLE: AddableNodeType[] = ['project', 'guardrail', 'masker', 'prompt', 'params', 'cache', 'router', 'llm']
const SPLICE_KINDS: Array<FlowEdge['kind']> = ['access', 'flow', 'route']
const DROP_RADIUS = 16
const MENU_WIDTH = 210
const CLICK_THRESHOLD = 3
const WHEEL_DEBOUNCE_MS = 140

const root = ref<HTMLElement | null>(null)
const size = ref({ width: 0, height: 0 })
const live = ref<Viewport | null>(null)
const isZooming = ref(false)
const announcement = ref('')
const marquee = ref<{ x: number; y: number; width: number; height: number } | null>(null)
const spaceDown = ref(false)
const interactionMode = ref<'idle' | 'pan' | 'marquee' | 'node' | 'wire'>('idle')

interface DragState { ids: Set<string>; dx: number; dy: number; snap: boolean; anchorId: string }
interface WireState { from: PortRef; to: Point; target: PortRef | null; compat: Map<string, string[]> }
const dragState = shallowRef<DragState | null>(null)
const wire = shallowRef<WireState | null>(null)

const vp = computed<Viewport>(() => live.value ?? props.viewport)

/* ------------------------------ connection-centric view ------------------------------ */
const viewKind = computed<FlowViewMode>(() => (props.viewMode === 'connection' ? 'connection' : 'model'))
const connectionView = computed(() => (viewKind.value === 'connection' ? buildConnectionView(props.graph, props.snapshot) : null))
const dimmedIds = computed(() => new Set(connectionView.value?.dimmed ?? []))
const FRAME_PAD = 12

/* ------------------------------ derived data ------------------------------ */
// Basic mode hides infrastructure nodes and host edges from rendering and hit-testing (data stays in the graph).
const HIDDEN_IN_BASIC = new Set(['endpoint', 'provider'])
const visibleGraph = computed<FlowGraph>(() => {
  // Connection view: every node is drawn (connections included in basic mode); edges are the projection (§3.1).
  const view = connectionView.value
  if (view) return { ...props.graph, nodes: props.graph.nodes, edges: view.edges }
  if (props.mode !== 'basic') return props.graph
  const nodes = props.graph.nodes.filter((n) => !HIDDEN_IN_BASIC.has(n.type))
  const edges = props.graph.edges.filter((e) => e.kind !== 'host')
  return { ...props.graph, nodes, edges }
})
const nodeMap = computed(() => new Map(props.graph.nodes.map((n) => [n.id, n])))
const selectedNodes = computed(() => new Set(props.selection.nodeIds))
const selectedEdges = computed(() => new Set(props.selection.edgeIds))
const counts = computed(() => issueCountsByNode(props.issues))
const ZERO_COUNTS = { error: 0, warning: 0, info: 0 }
const NO_HIGHLIGHT: string[] = []

// Reuse view objects while their content is unchanged so v-memo can skip cards.
const viewCache = new Map<string, { key: string; view: NodeView }>()
const views = computed(() => {
  const result = new Map<string, NodeView>()
  const alive = new Set<string>()
  for (const node of props.graph.nodes) {
    const view = viewKind.value === 'connection' && isConnectionNode(node) ? describeConnectionNode(node, props.graph, props.snapshot) : describeNode(node, props.graph, props.snapshot)
    const key = JSON.stringify(view)
    const cached = viewCache.get(node.id)
    alive.add(node.id)
    if (cached && cached.key === key) {
      result.set(node.id, cached.view)
    } else {
      viewCache.set(node.id, { key, view })
      result.set(node.id, view)
    }
  }
  for (const id of [...viewCache.keys()]) if (!alive.has(id)) viewCache.delete(id)
  return result
})

function dragPosition(node: FlowNode, drag: DragState): Point {
  const x = node.x + drag.dx
  const y = node.y + drag.dy
  return drag.snap ? { x: snapToGrid(x), y: snapToGrid(y) } : { x: Math.round(x), y: Math.round(y) }
}

/** Display position before any drag: derived in the connection view (§5.3), the stored position otherwise. */
function basePos(node: FlowNode): Point {
  return connectionView.value?.positions[node.id] ?? { x: node.x, y: node.y }
}

/** Connection view: one snapped delta for the whole dragged set, taken from the pressed node (§4.3). */
function groupDelta(drag: DragState): Point {
  const anchor = nodeMap.value.get(drag.anchorId)
  const base = anchor ? basePos(anchor) : { x: 0, y: 0 }
  const x = base.x + drag.dx
  const y = base.y + drag.dy
  const p = drag.snap ? { x: snapToGrid(x), y: snapToGrid(y) } : { x: Math.round(x), y: Math.round(y) }
  return { x: p.x - base.x, y: p.y - base.y }
}

function effective(node: FlowNode): FlowNode {
  const drag = dragState.value
  let p = basePos(node)
  if (drag && drag.ids.has(node.id)) {
    if (connectionView.value) {
      const d = groupDelta(drag)
      p = { x: p.x + d.x, y: p.y + d.y }
    } else {
      p = dragPosition(node, drag)
    }
  }
  return p.x === node.x && p.y === node.y ? node : { ...node, x: p.x, y: p.y }
}

function cardStyle(node: FlowNode): Record<string, string> {
  const pos = effective(node)
  const s = nodeSize(node)
  return { left: `${pos.x}px`, top: `${pos.y}px`, width: `${s.width}px`, height: `${s.height}px` }
}

function offsetKey(node: FlowNode): string {
  const p = effective(node)
  return `${p.x},${p.y}`
}

function highlightOf(nodeId: string): string[] {
  const w = wire.value
  if (!w) return NO_HIGHLIGHT
  const base = w.compat.get(nodeId) ?? NO_HIGHLIGHT
  if (w.target && w.target.nodeId === nodeId) return [...base, `hot:in:${w.target.portId}`]
  return base
}

function highlightKey(nodeId: string): string {
  return highlightOf(nodeId).join('|')
}

interface EdgeView { edge: FlowEdge; d: string; mid: Point; selected: boolean; dim: boolean; drop: boolean; interactive: boolean; detached: boolean }
const hoverEdgeId = ref<string | null>(null)
const dropEdgeId = ref<string | null>(null)
const edgeViews = computed<EdgeView[]>(() => {
  const result: EdgeView[] = []
  for (const edge of visibleGraph.value.edges as Array<FlowEdge & Partial<Pick<ViewEdge, 'interactive' | 'detached'>>>) {
    const from = nodeMap.value.get(edge.from.nodeId)
    const to = nodeMap.value.get(edge.to.nodeId)
    if (!from || !to) continue
    const a = portAnchor(effective(from), edge.from.portId, 'out')
    const b = portAnchor(effective(to), edge.to.portId, 'in')
    const interactive = edge.interactive !== false
    const detached = edge.detached === true
    result.push({
      edge,
      d: bezierPath(a, b),
      mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
      selected: interactive && selectedEdges.value.has(edge.id),
      dim: (edge.kind === 'route' && to.type === 'llm' && to.props.enabled === false) || dimmedIds.value.has(edge.to.nodeId),
      drop: dropEdgeId.value === edge.id,
      interactive,
      detached,
    })
  }
  return result
})

const wirePath = computed(() => {
  const w = wire.value
  if (!w) return ''
  const fromNode = nodeMap.value.get(w.from.nodeId)
  if (!fromNode) return ''
  const a = portAnchor(effective(fromNode), w.from.portId, 'out')
  let b = w.to
  if (w.target) {
    const targetNode = nodeMap.value.get(w.target.nodeId)
    if (targetNode) b = portAnchor(effective(targetNode), w.target.portId, 'in')
  }
  return bezierPath(a, b)
})

const groupFrames = computed(() => {
  const view = connectionView.value
  if (!view) return []
  const drag = dragState.value
  return view.groups.map((g) => {
    const d = drag && drag.ids.has(g.connectionId) ? groupDelta(drag) : { x: 0, y: 0 }
    return { id: g.connectionId, health: g.health, x: g.frame.x - FRAME_PAD + d.x, y: g.frame.y - FRAME_PAD + d.y, width: g.frame.width + FRAME_PAD * 2, height: g.frame.height + FRAME_PAD * 2 }
  })
})

/** Geometry of what is drawn: visible nodes at their display positions (fit, marquee, drop hit testing). */
const displayGraph = computed<FlowGraph>(() => ({ ...visibleGraph.value, nodes: visibleGraph.value.nodes.map(effective) }))
/** Only real (interactive) edges can take a palette drop. */
const spliceGraph = computed<FlowGraph>(() => ({
  ...displayGraph.value,
  edges: displayGraph.value.edges.filter((e) => (e as Partial<ViewEdge>).interactive !== false),
}))

const selectedEdgeButton = computed(() => {
  if (props.readOnly) return null
  const items = edgeViews.value.filter((e) => e.selected && !e.edge.locked)
  return items.length === 1 ? items[0] : null
})

// Edge insert "+": hovered or selected splice-able edges; types are computed only for those.
interface InsertMenu { edgeId: string }
const menu = ref<InsertMenu | null>(null)
const insertTypes = computed(() => {
  const map = new Map<string, AddableNodeType[]>()
  if (props.readOnly) return map
  const ids = new Set(props.selection.edgeIds.length === 1 && props.selection.nodeIds.length === 0 ? props.selection.edgeIds : [])
  if (hoverEdgeId.value) ids.add(hoverEdgeId.value)
  if (menu.value) ids.add(menu.value.edgeId)
  for (const id of ids) {
    const edge = props.graph.edges.find((e) => e.id === id)
    if (!edge || !SPLICE_KINDS.includes(edge.kind)) continue
    const types = insertableTypesForEdge(props.graph, id, props.snapshot)
    if (types.length > 0) map.set(id, types)
  }
  return map
})

function insertOffset(item: EdgeView): number {
  // Keep "+" beside the delete button of the same selected edge.
  return selectedEdgeButton.value?.edge.id === item.edge.id ? -14 : 0
}
function deleteOffset(item: EdgeView): number {
  return insertTypes.value.has(item.edge.id) ? 14 : 0
}

const menuEl = ref<HTMLElement | null>(null)
const menuView = computed(() => {
  const m = menu.value
  if (!m) return null
  const item = edgeViews.value.find((e) => e.edge.id === m.edgeId)
  const types = insertTypes.value.get(m.edgeId)
  if (!item || !types) return null
  const zoom = vp.value.zoom
  const x = Math.max(8, Math.min(item.mid.x * zoom + vp.value.x + 12, Math.max(8, size.value.width - MENU_WIDTH - 8)))
  const y = Math.max(8, Math.min(item.mid.y * zoom + vp.value.y + 12, Math.max(8, size.value.height - 28 - types.length * 34)))
  const scope = promptScopeForSplice(props.graph, m.edgeId)
  return {
    style: { left: `${x}px`, top: `${y}px`, width: `${MENU_WIDTH}px` },
    items: types.map((type) => ({
      type,
      label: type === 'prompt' ? (scope === 'model' ? '모델별 시스템 프롬프트' : '공통 시스템 프롬프트') : NODE_TYPES[type as keyof typeof NODE_TYPES].label,
      icon: NODE_TYPES[type as keyof typeof NODE_TYPES].icon,
    })),
  }
})

function openMenu(edgeId: string) {
  if (props.readOnly) return
  menu.value = { edgeId }
  void nextTick(() => { menuEl.value?.querySelector<HTMLElement>('button')?.focus({ preventScroll: true }) })
}
function closeMenu(refocus = false) {
  if (!menu.value) return
  menu.value = null
  if (refocus) root.value?.focus({ preventScroll: true })
}
function chooseInsert(type: AddableNodeType) {
  const m = menu.value
  if (!m || props.readOnly) return
  spliceRequested = true
  emit('command', { type: 'addNode', nodeType: type, spliceEdgeId: m.edgeId })
  closeMenu(true)
}

const isViewportMoving = computed(() => isZooming.value || (interactionMode.value === 'pan' && live.value !== null))

function snapOffset(value: number): number {
  if (isViewportMoving.value) return value
  const dpr = typeof window !== 'undefined' && window.devicePixelRatio > 0 ? window.devicePixelRatio : 1
  return Math.round(value * dpr) / dpr
}

const worldStyle = computed(() => ({
  transform: `translate(${snapOffset(vp.value.x)}px, ${snapOffset(vp.value.y)}px) scale(${vp.value.zoom})`,
  transformOrigin: '0 0',
}))

const gridStyle = computed(() => {
  const step = vp.value.zoom < 0.5 ? GRID_SIZE * 2 : GRID_SIZE
  const cell = `${step * vp.value.zoom}px`
  return { backgroundSize: `${cell} ${cell}`, backgroundPosition: `${snapOffset(vp.value.x)}px ${snapOffset(vp.value.y)}px` }
})

const marqueeStyle = computed(() => {
  const m = marquee.value
  if (!m) return {}
  return { left: `${m.x}px`, top: `${m.y}px`, width: `${m.width}px`, height: `${m.height}px` }
})

/* ------------------------------ viewport helpers ------------------------------ */
// The console applies CSS `zoom`: getBoundingClientRect()/client coordinates are scaled by s, while
// offsetWidth/offsetHeight and ResizeObserver contentRect are in unzoomed layout px (what the canvas works in).
let autoFit = false
let pendingFit = false

function measure() {
  const el = root.value
  if (!el) return
  size.value = { width: el.offsetWidth, height: el.offsetHeight }
}

function scale(): number {
  const el = root.value
  if (!el) return 1
  return clientScale(el.getBoundingClientRect().width, el.offsetWidth)
}

function commitViewport(next: Viewport, auto = false) {
  autoFit = auto
  live.value = next
  emit('update:viewport', next)
  void nextTick(() => {
    if (wheelTimer === null && interactionMode.value !== 'pan') live.value = null
  })
}

function fit() {
  measure()
  if (!(size.value.width > 0) || !(size.value.height > 0)) { pendingFit = true; return }
  pendingFit = false
  commitViewport(fitViewport(graphBounds(displayGraph.value), size.value), true)
}

function centerOn(nodeId: string) {
  const node = nodeMap.value.get(nodeId)
  if (!node) return
  measure()
  const rect = nodeRect(effective(node))
  const zoom = vp.value.zoom
  commitViewport({
    x: size.value.width / 2 - (rect.x + rect.width / 2) * zoom,
    y: size.value.height / 2 - (rect.y + rect.height / 2) * zoom,
    zoom,
  })
}

function viewportCenterWorld(): Point {
  measure()
  return screenToWorld({ x: size.value.width / 2, y: size.value.height / 2 }, vp.value)
}

function visibleWorldRect() {
  measure()
  const a = screenToWorld({ x: 0, y: 0 }, vp.value)
  const b = screenToWorld({ x: size.value.width, y: size.value.height }, vp.value)
  return { x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y }
}

defineExpose({ fit, centerOn, zoomCenter, viewportCenterWorld, visibleWorldRect })

function localPoint(event: { clientX: number; clientY: number }): Point {
  const el = root.value
  if (!el) return { x: event.clientX, y: event.clientY }
  return clientToLocal({ x: event.clientX, y: event.clientY }, el.getBoundingClientRect(), el.offsetWidth)
}

/* ------------------------------ interaction ------------------------------ */
let activePointer: number | null = null
let startClient: Point = { x: 0, y: 0 }
let startLocal: Point = { x: 0, y: 0 }
let startViewport: Viewport = { x: 0, y: 0, zoom: 1 }
let lastEvent: { clientX: number; clientY: number; altKey: boolean; shiftKey: boolean } = { clientX: 0, clientY: 0, altKey: false, shiftKey: false }
let moved = false
let rafId: number | null = null
let wheelTimer: number | null = null
let pressNodeId: string | null = null
let pressWasSelected = false
let pressShift = false
let dragIds: string[] = []
let additive = false
let baseSelection: FlowSelection = { nodeIds: [], edgeIds: [] }

function announce(message: string) {
  announcement.value = ''
  void nextTick(() => { announcement.value = message })
}

function setSelection(nodeIds: string[], edgeIds: string[]) {
  emit('update:selection', { nodeIds, edgeIds })
}

function targetElement(event: Event): Element | null {
  return event.target instanceof Element ? event.target : null
}

function isTextControl(el: Element | null): boolean {
  return !!el && !!el.closest('input, textarea, select, [contenteditable="true"]')
}

function onPointerDown(event: PointerEvent) {
  if (interactionMode.value !== 'idle') return
  const el = targetElement(event)
  if (!el || !root.value) return
  if (menu.value && !el.closest('[data-insert-menu]') && !el.closest('[data-edge-insert]')) closeMenu()
  if (el.closest('[data-edge-delete]') || el.closest('[data-edge-insert]') || el.closest('[data-insert-menu]')) return
  const isMiddle = event.button === 1
  if (event.button !== 0 && !isMiddle) return

  startClient = { x: event.clientX, y: event.clientY }
  lastEvent = event
  moved = false
  const portEl = el.closest('[data-port-dir]')
  const nodeEl = el.closest('.fd-node')
  const edgeEl = el.closest('[data-edge-id]')

  if (!nodeEl) root.value.focus({ preventScroll: true })

  if (isMiddle || spaceDown.value) {
    event.preventDefault()
    beginPan(event)
    return
  }

  if (portEl && portEl.getAttribute('data-port-dir') === 'out') {
    if (props.readOnly) return
    const nodeId = portEl.getAttribute('data-node-id')
    const portId = portEl.getAttribute('data-port-id')
    if (!nodeId || !portId) return
    event.preventDefault()
    beginWire(event, { nodeId, portId })
    return
  }
  if (portEl) return

  if (nodeEl) {
    if (el.closest('button, input, textarea, select, a')) return
    const nodeId = nodeEl.getAttribute('data-node-id')
    if (nodeId) beginNodePress(event, nodeId)
    return
  }

  if (edgeEl) {
    const id = edgeEl.getAttribute('data-edge-id')
    if (id) selectEdge(id, event.shiftKey)
    return
  }

  if (event.shiftKey) {
    beginMarquee(event)
    return
  }
  beginPan(event)
}

function capture(event: PointerEvent) {
  activePointer = event.pointerId
  try { root.value?.setPointerCapture(event.pointerId) } catch { /* pointer already gone */ }
}

function beginPan(event: PointerEvent) {
  interactionMode.value = 'pan'
  startViewport = { ...vp.value }
  pressShift = event.shiftKey
  capture(event)
}

function beginMarquee(event: PointerEvent) {
  interactionMode.value = 'marquee'
  additive = event.shiftKey
  baseSelection = { nodeIds: [...props.selection.nodeIds], edgeIds: [...props.selection.edgeIds] }
  capture(event)
  const p = localPoint(event)
  startLocal = p
  marquee.value = { x: p.x, y: p.y, width: 0, height: 0 }
}

function beginNodePress(event: PointerEvent, nodeId: string) {
  pressNodeId = nodeId
  pressShift = event.shiftKey
  pressWasSelected = selectedNodes.value.has(nodeId)
  if (!pressWasSelected) {
    setSelection(event.shiftKey ? [...props.selection.nodeIds, nodeId] : [nodeId], event.shiftKey ? [...props.selection.edgeIds] : [])
  }
  const base = pressWasSelected ? [...props.selection.nodeIds] : event.shiftKey ? [...props.selection.nodeIds, nodeId] : [nodeId]
  // Dependent nodes (masker, model prompt, hosts, policy) follow their owner; Ctrl/Cmd moves the selection alone.
  // Connection view: a connection, its models and their prompts always move as one group (§4.3).
  const alone = event.ctrlKey || event.metaKey
  dragIds = connectionView.value ? connectionDragIds(props.graph, connectionView.value, base, alone) : alone ? base : dependencyClosure(props.graph, base)
  interactionMode.value = 'node'
  capture(event)
}

function beginWire(event: PointerEvent, from: PortRef) {
  const compat = new Map<string, string[]>()
  for (const node of visibleGraph.value.nodes) {
    for (const port of viewPortsOf(node, viewKind.value)) {
      if (port.direction !== 'in') continue
      if (canConnectInView(props.graph, from, { nodeId: node.id, portId: port.id }, props.snapshot, viewKind.value).ok) {
        const list = compat.get(node.id) ?? []
        list.push(`in:${port.id}`)
        compat.set(node.id, list)
      }
    }
  }
  interactionMode.value = 'wire'
  wire.value = { from, to: screenToWorld(localPoint(event), vp.value), target: null, compat }
  capture(event)
}

function onPointerMove(event: PointerEvent) {
  if (interactionMode.value === 'idle') return
  if (activePointer !== null && event.pointerId !== activePointer) return
  lastEvent = event
  if (rafId === null) rafId = requestAnimationFrame(() => { rafId = null; flush() })
}

function flush() {
  const mode = interactionMode.value
  if (mode === 'idle') return
  const s = scale()
  const dxScreen = (lastEvent.clientX - startClient.x) / s
  const dyScreen = (lastEvent.clientY - startClient.y) / s
  if (!moved && Math.hypot(dxScreen, dyScreen) >= CLICK_THRESHOLD) moved = true

  if (mode === 'pan') {
    if (moved) live.value = { x: startViewport.x + dxScreen, y: startViewport.y + dyScreen, zoom: startViewport.zoom }
  } else if (mode === 'marquee') {
    const p = localPoint(lastEvent)
    const sx = startLocal.x
    const sy = startLocal.y
    marquee.value = { x: Math.min(sx, p.x), y: Math.min(sy, p.y), width: Math.abs(p.x - sx), height: Math.abs(p.y - sy) }
  } else if (mode === 'node') {
    if (moved && !props.readOnly) {
      const zoom = vp.value.zoom
      dragState.value = { ids: new Set(dragIds), dx: dxScreen / zoom, dy: dyScreen / zoom, snap: !lastEvent.altKey, anchorId: pressNodeId ?? '' }
    }
  } else if (mode === 'wire') {
    const w = wire.value
    if (!w) return
    const world = screenToWorld(localPoint(lastEvent), vp.value)
    const target = nearestPortInView(props.graph, w.from, world, 24 / vp.value.zoom, props.snapshot, viewKind.value, (n) => effective(n))
    wire.value = { ...w, to: world, target }
  }
}

function resetInteraction() {
  interactionMode.value = 'idle'
  activePointer = null
  dragState.value = null
  wire.value = null
  marquee.value = null
  pressNodeId = null
  moved = false
}

function wireCommand(from: PortRef, to: PortRef): FlowCommand {
  if (viewKind.value === 'connection' && (isConnectionId(from.nodeId) || isConnectionId(to.nodeId))) return { type: 'connectConnection', from, to }
  return { type: 'connect', from, to }
}

function onPointerUp(event: PointerEvent) {
  const mode = interactionMode.value
  if (mode === 'idle') return
  if (activePointer !== null && event.pointerId !== activePointer) return
  lastEvent = event
  if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null }
  flush()
  const pointerId = activePointer
  const target = wire.value?.target ?? null
  const from = wire.value?.from ?? null
  const didMove = moved // snapshot: the interaction reset clears the live flag

  if (mode === 'pan') {
    if (didMove) {
      const finalViewport = live.value ?? props.viewport
      resetInteraction()
      commitViewport(finalViewport)
    } else {
      resetInteraction()
      if (!pressShift && (props.selection.nodeIds.length > 0 || props.selection.edgeIds.length > 0)) setSelection([], [])
    }
  } else if (mode === 'marquee') {
    const m = marquee.value
    resetInteraction()
    if (m && (m.width >= CLICK_THRESHOLD || m.height >= CLICK_THRESHOLD)) {
      const a = screenToWorld({ x: m.x, y: m.y }, vp.value)
      const b = screenToWorld({ x: m.x + m.width, y: m.y + m.height }, vp.value)
      const ids = nodesInRect(displayGraph.value, { x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y })
      setSelection(additive ? [...new Set([...baseSelection.nodeIds, ...ids])] : ids, additive ? baseSelection.edgeIds : [])
    } else if (!additive) {
      setSelection([], [])
    }
  } else if (mode === 'node') {
    const drag = dragState.value
    const nodeId = pressNodeId
    const wasSelected = pressWasSelected
    const shift = pressShift
    resetInteraction()
    if (drag && didMove) {
      const delta = connectionView.value ? groupDelta(drag) : null
      const moves: Array<{ nodeId: string; x: number; y: number }> = []
      for (const id of drag.ids) {
        const node = nodeMap.value.get(id)
        if (!node) continue
        const p = delta ? { x: node.x + delta.x, y: node.y + delta.y } : dragPosition(node, drag)
        if (p.x !== node.x || p.y !== node.y) moves.push({ nodeId: id, x: p.x, y: p.y })
      }
      if (moves.length > 0 && !props.readOnly) emit('command', { type: 'moveNodes', moves })
    } else if (nodeId && wasSelected) {
      if (shift) setSelection(props.selection.nodeIds.filter((id) => id !== nodeId), props.selection.edgeIds)
      else if (props.selection.nodeIds.length > 1 || props.selection.edgeIds.length > 0) setSelection([nodeId], [])
    }
  } else if (mode === 'wire') {
    const el = document.elementFromPoint(event.clientX, event.clientY)
    resetInteraction()
    if (from && target) {
      emit('command', wireCommand(from, target))
    } else if (from && didMove) {
      const portEl = el?.closest('[data-port-dir="in"]')
      const nodeId = portEl?.getAttribute('data-node-id')
      const portId = portEl?.getAttribute('data-port-id')
      if (nodeId && portId) {
        const check = canConnectInView(props.graph, from, { nodeId, portId }, props.snapshot, viewKind.value)
        if (!check.ok) {
          announce(check.reason)
          // Connection view: a refused connection/model wire is also explained in the page message bar (D9).
          if (viewKind.value === 'connection' && (isConnectionId(from.nodeId) || isConnectionId(nodeId))) emit('command', { type: 'connectConnection', from, to: { nodeId, portId } })
        }
      }
    }
  }
  if (pointerId !== null) {
    try { root.value?.releasePointerCapture(pointerId) } catch { /* already released */ }
  }
}

function onPointerCancel() {
  if (interactionMode.value === 'idle') return
  if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null }
  const wasPan = interactionMode.value === 'pan'
  const finalViewport = live.value
  resetInteraction()
  if (wasPan && finalViewport) live.value = null
}

function selectEdge(id: string, shift: boolean) {
  if (shift) {
    const has = props.selection.edgeIds.includes(id)
    setSelection([...props.selection.nodeIds], has ? props.selection.edgeIds.filter((e) => e !== id) : [...props.selection.edgeIds, id])
  } else {
    setSelection([], [id])
  }
}

function onWheel(event: WheelEvent) {
  if (menu.value) closeMenu()
  isZooming.value = true
  const factor = Math.exp(-(event.deltaY * (event.deltaMode === 1 ? 16 : 1)) * 0.0015)
  live.value = zoomAt(vp.value, localPoint(event), factor)
  if (wheelTimer !== null) window.clearTimeout(wheelTimer)
  wheelTimer = window.setTimeout(() => {
    wheelTimer = null
    isZooming.value = false
    if (live.value) commitViewport(live.value)
  }, WHEEL_DEBOUNCE_MS)
}

/* ------------------------------ keyboard ------------------------------ */
function zoomCenter(factor: number | 'reset') {
  measure()
  const center = { x: size.value.width / 2, y: size.value.height / 2 }
  const next = factor === 'reset' ? zoomAt(vp.value, center, 1 / vp.value.zoom) : zoomAt(vp.value, center, factor)
  commitViewport(next)
}

function onKeyDown(event: KeyboardEvent) {
  const el = targetElement(event)
  if (isTextControl(el)) return
  const mod = event.ctrlKey || event.metaKey
  const key = event.key

  if (key === ' ' && !(el && el.closest('button'))) {
    spaceDown.value = true
    event.preventDefault()
    return
  }

  if (key === 'Escape' && menu.value) {
    event.preventDefault()
    closeMenu(true)
    return
  }

  if (key.startsWith('Arrow') && !event.altKey && !(el && el.closest('[data-insert-menu]'))) {
    if (props.readOnly || props.selection.nodeIds.length === 0) return
    event.preventDefault()
    const step = event.shiftKey ? 64 : GRID_SIZE
    const dx = key === 'ArrowLeft' ? -step : key === 'ArrowRight' ? step : 0
    const dy = key === 'ArrowUp' ? -step : key === 'ArrowDown' ? step : 0
    const ids = connectionView.value
      ? connectionDragIds(props.graph, connectionView.value, props.selection.nodeIds, mod)
      : mod ? props.selection.nodeIds : dependencyClosure(props.graph, props.selection.nodeIds)
    const moves = ids
      .map((id) => nodeMap.value.get(id))
      .filter((n): n is FlowNode => !!n)
      .map((n) => ({ nodeId: n.id, x: n.x + dx, y: n.y + dy }))
    if (moves.length > 0) emit('command', { type: 'moveNodes', moves })
    return
  }

  if (mod && key.toLowerCase() === 'z') {
    event.preventDefault()
    if (!props.readOnly) emit('action', event.shiftKey ? 'redo' : 'undo')
    return
  }
  if (mod && key.toLowerCase() === 'y') {
    event.preventDefault()
    if (!props.readOnly) emit('action', 'redo')
    return
  }
  if (mod && key.toLowerCase() === 'a') {
    event.preventDefault()
    setSelection(visibleGraph.value.nodes.map((n) => n.id), [])
    return
  }
  if (mod || event.altKey) return

  if (key === 'Delete' || key === 'Backspace') {
    if (props.readOnly) return
    if (props.selection.nodeIds.length === 0 && props.selection.edgeIds.length === 0) return
    event.preventDefault()
    emit('command', { type: 'removeItems', nodeIds: [...props.selection.nodeIds], edgeIds: [...props.selection.edgeIds], withConnectionModels: viewKind.value === 'connection' })
    return
  }
  if (key === 'f' || key === 'F') { event.preventDefault(); fit(); return }
  if (key === '+' || key === '=') { event.preventDefault(); zoomCenter(1.2); return }
  if (key === '-' || key === '_') { event.preventDefault(); zoomCenter(1 / 1.2); return }
  if (key === '0') { event.preventDefault(); zoomCenter('reset'); return }
  if (key === 'Escape') {
    if (interactionMode.value !== 'idle') {
      const id = activePointer
      onPointerCancel()
      if (id !== null) { try { root.value?.releasePointerCapture(id) } catch { /* ignore */ } }
    } else if (props.selection.nodeIds.length > 0 || props.selection.edgeIds.length > 0) {
      setSelection([], [])
    }
  }
}

function onKeyUp(event: KeyboardEvent) {
  if (event.key === ' ') spaceDown.value = false
}

function onFocusOut(event: FocusEvent) {
  if (!root.value || !(event.relatedTarget instanceof Node) || !root.value.contains(event.relatedTarget)) spaceDown.value = false
}

/* ------------------------------ node card events ------------------------------ */
function nodeAction(node: FlowNode): string | null {
  return connectionView.value && isConnectionNode(node) && !props.readOnly && !node.collapsed ? '＋ 모델 추가' : null
}

function onToggleCollapse(nodeId: string, collapsed: boolean) {
  if (props.readOnly) return
  emit('command', { type: 'toggleCollapse', nodeIds: [nodeId], collapsed })
}

function onActivate(nodeId: string) {
  setSelection([nodeId], [])
  emit('open-inspector')
  const view = views.value.get(nodeId)
  if (view) announce(`${view.typeLabel} ${view.title} 선택됨. 인스펙터에서 속성을 편집할 수 있습니다.`)
}

function onDblClick(event: MouseEvent) {
  const el = targetElement(event)
  if (el?.closest('.fd-node') && !el.closest('button, input, textarea, select, a')) emit('open-inspector')
}

function removeEdge(id: string) {
  if (props.readOnly) return
  emit('command', { type: 'removeItems', nodeIds: [], edgeIds: [id] })
}

/* ------------------------------ palette drag and drop ------------------------------ */
function hasPaletteData(event: DragEvent): boolean {
  return !!event.dataTransfer && Array.from(event.dataTransfer.types).includes(DND_MIME)
}

function onDragOver(event: DragEvent) {
  if (props.readOnly || !hasPaletteData(event)) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  const world = screenToWorld(localPoint(event), vp.value)
  const id = nearestEdge(spliceGraph.value, world, DROP_RADIUS / vp.value.zoom, SPLICE_KINDS)
  if (id !== dropEdgeId.value) dropEdgeId.value = id
}

function onDragLeave(event: DragEvent) {
  if (!root.value || (event.relatedTarget instanceof Node && root.value.contains(event.relatedTarget))) return
  dropEdgeId.value = null
}

function onDrop(event: DragEvent) {
  if (props.readOnly || !hasPaletteData(event)) return
  event.preventDefault()
  dropEdgeId.value = null
  let parsed: unknown
  try { parsed = JSON.parse(event.dataTransfer?.getData(DND_MIME) ?? '') } catch { return }
  if (!parsed || typeof parsed !== 'object') return
  const { nodeType, refId } = parsed as { nodeType?: unknown; refId?: unknown }
  if (refId !== null && refId !== undefined && (typeof refId !== 'string' || refId.length > 80)) return
  const world = screenToWorld(localPoint(event), vp.value)
  const snapped = { x: snapToGrid(world.x), y: snapToGrid(world.y) }
  // Connection view: a palette connection header becomes a (pinned) connection node at the drop point.
  if (nodeType === 'connection') {
    if (viewKind.value === 'connection' && typeof refId === 'string' && isConnectionId(refId)) emit('command', { type: 'addConnection', connectionId: refId, position: snapped })
    return
  }
  if (typeof nodeType !== 'string' || !ADDABLE.includes(nodeType as AddableNodeType)) return
  const type = nodeType as AddableNodeType
  const edgeId = nearestEdge(spliceGraph.value, world, DROP_RADIUS / vp.value.zoom, SPLICE_KINDS)
  if (edgeId && insertableTypesForEdge(props.graph, edgeId, props.snapshot).includes(type)) {
    spliceRequested = true
    emit('command', { type: 'addNode', nodeType: type, refId: typeof refId === 'string' ? refId : null, spliceEdgeId: edgeId })
    return
  }
  // Connection view: a model lands behind its own connection (its group), never as a loose LLM.
  if (viewKind.value === 'connection' && type === 'llm' && typeof refId === 'string') {
    const connectionId = connectionIdForDeployment(refId, props.snapshot)
    if (connectionId) {
      emit('command', { type: 'addConnectionModels', connectionId, deploymentIds: [refId], position: snapped })
      return
    }
  }
  emit('command', {
    type: 'addNode',
    nodeType: type,
    refId: typeof refId === 'string' ? refId : null,
    position: snapped,
    autoWire: !event.altKey,
  })
}

/* ------------------------------ lifecycle & announcements ------------------------------ */
let observer: ResizeObserver | null = null

onMounted(() => {
  measure()
  if (pendingFit && size.value.width > 0) fit()
  if (typeof ResizeObserver !== 'undefined' && root.value) {
    observer = new ResizeObserver((entries) => {
      const rect = entries[0]?.contentRect
      if (!rect) return
      size.value = { width: rect.width, height: rect.height }
      if (rect.width > 0 && rect.height > 0 && (pendingFit || autoFit)) fit()
    })
    observer.observe(root.value)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  if (rafId !== null) cancelAnimationFrame(rafId)
  if (wheelTimer !== null) window.clearTimeout(wheelTimer)
  rafId = null
  wheelTimer = null
  isZooming.value = false
  viewCache.clear()
})

watch(() => [props.viewport.x, props.viewport.y, props.viewport.zoom], () => {
  if (interactionMode.value !== 'pan' && wheelTimer === null) live.value = null
})

let knownNodeIds = new Set(props.graph.nodes.map((n) => n.id))
let spliceRequested = false

function describeAddition(added: FlowNode[]): string {
  const router = added.some((n) => n.type === 'router')
  if (router && added.length > 1) return 'LLM이 두 개가 되어 라우터를 자동으로 추가했습니다.'
  if (spliceRequested && added.length === 1) return '노드를 간선 사이에 넣었습니다.'
  if (added.length === 1) {
    const node = added[0]
    if (node.type === 'guardrail') return '가드레일을 처리 순서에 맞는 위치에 넣었습니다.'
    if (['prompt', 'params', 'cache', 'router'].includes(node.type)) return `${NODE_TYPES[node.type].label} 노드를 처리 순서에 맞는 위치에 넣었습니다.`
  }
  return `노드를 ${added.length}개 추가했습니다.`
}

watch(() => [props.graph.nodes, props.graph.edges.length] as const, ([nodes, edges], [prevNodes, prevEdges]) => {
  const ids = new Set(nodes.map((n) => n.id))
  const added = nodes.filter((n) => !knownNodeIds.has(n.id))
  const removed = prevNodes.filter((n) => !ids.has(n.id)).length
  knownNodeIds = ids
  const spliced = spliceRequested
  if (added.length > 0) {
    announce(describeAddition(added))
    spliceRequested = false
  } else if (removed > 0) announce(`노드를 ${removed}개 제거했습니다.`)
  else if (edges > prevEdges) announce('간선을 추가했습니다.')
  else if (edges < prevEdges) announce('간선을 제거했습니다.')
  if (spliced && added.length === 0) spliceRequested = false
})

watch(() => props.selection, (selection) => {
  if (selection.nodeIds.length === 1 && selection.edgeIds.length === 0) {
    const view = views.value.get(selection.nodeIds[0])
    if (view) announce(`${view.typeLabel} ${view.title} 선택됨`)
  } else if (selection.nodeIds.length + selection.edgeIds.length > 1) {
    announce(`${selection.nodeIds.length + selection.edgeIds.length}개 항목 선택됨`)
  }
})
</script>

<template>
  <div
    ref="root"
    class="fd-canvas"
    :class="{ 'is-panning': interactionMode === 'pan' && live !== null, 'is-zooming': isZooming, 'is-space': spaceDown, 'is-wiring': interactionMode === 'wire', 'is-readonly': readOnly }"
    :style="gridStyle"
    tabindex="0"
    role="application"
    aria-label="워크플로우 캔버스"
    aria-roledescription="워크플로우 캔버스"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerCancel"
    @lostpointercapture="onPointerCancel"
    @wheel.prevent="onWheel"
    @keydown="onKeyDown"
    @keyup="onKeyUp"
    @focusout="onFocusOut"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
    @dblclick="onDblClick"
  >
    <div class="fd-world" :style="worldStyle">
      <svg class="fd-edges" width="1" height="1" style="overflow: visible" aria-hidden="true">
        <g v-if="groupFrames.length > 0" class="fd-group-frames">
          <rect v-for="frame in groupFrames" :key="frame.id" class="fd-group-frame" :class="`is-${frame.health}`" :x="frame.x" :y="frame.y" :width="frame.width" :height="frame.height" rx="16" ry="16" />
        </g>
        <g
          v-for="item in edgeViews"
          :key="item.edge.id"
          class="fd-edge"
          :class="[`fd-edge--${item.edge.kind}`, { 'is-selected': item.selected, 'is-dim': item.dim, 'is-drop': item.drop, 'is-decor': !item.interactive, 'is-detached': item.detached }]"
          @pointerenter="item.interactive && (hoverEdgeId = item.edge.id)"
          @pointerleave="hoverEdgeId === item.edge.id && (hoverEdgeId = null)"
        >
          <path v-if="item.interactive" class="fd-edge-hit" :d="item.d" :data-edge-id="item.edge.id" />
          <path class="fd-edge-line" :d="item.d" />
          <g
            v-if="insertTypes.has(item.edge.id)"
            class="fd-edge-insert"
            data-edge-insert="true"
            role="button"
            :tabindex="item.selected ? 0 : -1"
            aria-haspopup="menu"
            aria-label="이 간선에 노드 넣기"
            :transform="`translate(${item.mid.x + insertOffset(item)}, ${item.mid.y})`"
            @click.stop="openMenu(item.edge.id)"
            @keydown.enter.stop.prevent="openMenu(item.edge.id)"
          >
            <circle r="10" />
            <text text-anchor="middle" dominant-baseline="central">＋</text>
          </g>
        </g>
        <path v-if="wirePath" class="fd-wire" :class="{ 'is-snapped': !!wire?.target }" :d="wirePath" />
        <g
          v-if="selectedEdgeButton"
          class="fd-edge-delete"
          data-edge-delete="true"
          role="button"
          tabindex="0"
          aria-label="선택한 간선 삭제"
          :transform="`translate(${selectedEdgeButton.mid.x + deleteOffset(selectedEdgeButton)}, ${selectedEdgeButton.mid.y})`"
          @click.stop="removeEdge(selectedEdgeButton.edge.id)"
          @keydown.enter.stop.prevent="removeEdge(selectedEdgeButton.edge.id)"
        >
          <circle r="10" />
          <text text-anchor="middle" dominant-baseline="central">×</text>
        </g>
      </svg>

      <FlowNodeCard
        v-for="node in visibleGraph.nodes"
        :key="node.id"
        v-memo="[node, views.get(node.id), selectedNodes.has(node.id), nodeChanges[node.id] ?? null, counts[node.id], offsetKey(node), highlightKey(node.id), readOnly, viewKind, dimmedIds.has(node.id)]"
        :style="cardStyle(node)"
        :node="node"
        :view="views.get(node.id)!"
        :ports="viewPortsOf(node, viewKind)"
        :selected="selectedNodes.has(node.id)"
        :change="nodeChanges[node.id] ?? null"
        :counts="counts[node.id] ?? ZERO_COUNTS"
        :highlight-port-ids="highlightOf(node.id)"
        :read-only="readOnly"
        :dimmed="dimmedIds.has(node.id)"
        :action="nodeAction(node)"
        @toggle-collapse="onToggleCollapse"
        @activate="onActivate"
      />
    </div>

    <div
      v-if="menuView"
      ref="menuEl"
      class="fd-insert-menu"
      data-insert-menu="true"
      role="menu"
      aria-label="간선에 넣을 노드"
      :style="menuView.style"
    >
      <button v-for="item in menuView.items" :key="item.type" type="button" class="fd-insert-item" role="menuitem" @click="chooseInsert(item.type)">
        <span class="fd-insert-icon" aria-hidden="true">{{ item.icon }}</span>
        <span>{{ item.label }}</span>
      </button>
    </div>

    <div v-if="marquee" class="fd-marquee" :style="marqueeStyle"></div>
    <div class="fd-sr" aria-live="polite" role="status">{{ announcement }}</div>
  </div>
</template>

<style scoped>
.fd-canvas {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 240px;
  overflow: hidden;
  contain: layout paint style;
  isolation: isolate;
  background-color: var(--bg-soft);
  background-image:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px),
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px);
  touch-action: none;
  outline: none;
  cursor: default;
  user-select: none;
}
.fd-canvas:focus-visible { box-shadow: inset 0 0 0 2px var(--accent-strong); }
.fd-canvas.is-space { cursor: grab; }
.fd-canvas.is-panning { cursor: grabbing; }
.fd-canvas.is-wiring { cursor: crosshair; }

/* Keep static text/edges crisp at any zoom by promoting GPU layer only while panning or zooming. */
.fd-world { position: absolute; left: 0; top: 0; width: 0; height: 0; }
.fd-canvas.is-panning .fd-world,
.fd-canvas.is-zooming .fd-world { will-change: transform; }
.fd-edges { position: absolute; left: 0; top: 0; pointer-events: none; shape-rendering: geometricPrecision; }

.fd-edge-line { fill: none; stroke: var(--faint); stroke-width: 2; stroke-linecap: round; pointer-events: none; }
.fd-edge-hit { fill: none; stroke: transparent; stroke-width: 14; pointer-events: stroke; cursor: pointer; }
.fd-edge--entry .fd-edge-line { stroke: var(--info); stroke-width: 1.5; }
.fd-edge--policy .fd-edge-line { stroke: var(--warning); stroke-dasharray: 6 5; }
.fd-edge--access .fd-edge-line { stroke: var(--info); stroke-dasharray: 6 5; }
.fd-edge--flow .fd-edge-line { stroke: var(--accent-strong); stroke-width: 2; }
.fd-edge--route .fd-edge-line { stroke: var(--warning); stroke-width: 2; }
.fd-edge--masker .fd-edge-line { stroke: var(--warning); stroke-dasharray: 6 5; }
.fd-edge--host .fd-edge-line { stroke: var(--faint); stroke-dasharray: 3 5; }
.fd-edge.is-drop .fd-edge-line { stroke: var(--accent-strong); stroke-width: 4; stroke-dasharray: none; }
.fd-edge.is-dim { opacity: .4; }
.fd-group-frame { fill: var(--surface-2); stroke: var(--border); stroke-width: 1; }
.fd-group-frame.is-degraded { stroke: var(--warning); }
.fd-group-frame.is-offline, .fd-group-frame.is-disabled { stroke-dasharray: 6 5; opacity: .7; }
.fd-edge.is-decor .fd-edge-line { opacity: .85; }
.fd-edge.is-detached .fd-edge-line { stroke: var(--muted); stroke-dasharray: 4 6; }
.fd-edge:hover .fd-edge-line { stroke-width: 3; }
.fd-edge.is-selected .fd-edge-line { stroke-width: 3; }
.fd-edge.is-selected .fd-edge-hit { stroke: var(--accent-dim); }

.fd-wire { fill: none; stroke: var(--accent-strong); stroke-width: 2; stroke-dasharray: 5 4; pointer-events: none; }
.fd-wire.is-snapped { stroke-dasharray: none; stroke-width: 3; }

.fd-edge-insert { pointer-events: all; cursor: pointer; outline: none; }
.fd-edge-insert circle { fill: var(--surface); stroke: var(--accent-strong); stroke-width: 2; }
.fd-edge-insert text { fill: var(--accent-strong); font-size: 13px; font-weight: 800; pointer-events: none; }
.fd-edge-insert:hover circle, .fd-edge-insert:focus-visible circle { fill: var(--accent-dim); }

.fd-insert-menu {
  position: absolute;
  z-index: 5;
  box-sizing: border-box;
  display: grid;
  gap: 2px;
  padding: 6px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  background: var(--surface);
  box-shadow: var(--shadow-soft);
  cursor: default;
}
.fd-insert-item {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text);
  font-size: 11px;
  font-weight: 700;
  text-align: left;
  cursor: pointer;
}
.fd-insert-item:hover, .fd-insert-item:focus-visible { background: var(--accent-dim); outline: none; }
.fd-insert-icon { width: 16px; text-align: center; color: var(--accent-strong); }

.fd-edge-delete { pointer-events: all; cursor: pointer; outline: none; }
.fd-edge-delete circle { fill: var(--surface); stroke: var(--danger); stroke-width: 2; }
.fd-edge-delete text { fill: var(--danger); font-size: 14px; font-weight: 800; pointer-events: none; }
.fd-edge-delete:hover circle, .fd-edge-delete:focus-visible circle { fill: var(--danger-dim); }

.fd-marquee {
  position: absolute;
  box-sizing: border-box;
  border: 1px solid var(--accent-strong);
  background: var(--accent-dim);
  pointer-events: none;
}

.fd-sr {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
