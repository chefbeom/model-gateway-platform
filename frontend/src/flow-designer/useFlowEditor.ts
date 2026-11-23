// Page state for the flow designer: load, edit, plan, apply, rebase, drafts and layout persistence.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from 'vue'
import type { AdminAuth } from '../api'
import { compile } from './compile'
import { decompile } from './decompile'
import { computeNodeChanges, isConfigDirty, planChanges, rebase, stableStringify } from './diff'
import { hasDraft, installDraftAutoClear, stashDraft, takeDraft } from './draftStore'
import { executePlan } from './execute'
import { createAdminTransport, currentUserId, loadFlowLayout, loadFlowSnapshot, loadServiceList, saveFlowLayout } from './flowApi'
import { FlowLoadError } from './flowSnapshot'
import { applyCommand } from './graphOps'
import { createHistory, recordHistory, redoHistory, undoHistory, type HistoryState } from './history'
import { extractLayout, suggestNodePosition } from './layout'
import { validateGraph } from './validate'
import type {
  FlowCommand,
  FlowGraph,
  FlowPlan,
  FlowSelection,
  FlowToolbarAction,
  OperationResult,
  Point,
  Rect,
  ServiceFlowSnapshot,
  ServiceSummary,
  Viewport
} from './types'

export const NEW_SERVICE_KEY = '__new__'
export interface CanvasHandle { fit(): void; centerOn(nodeId: string): void; viewportCenterWorld(): Point; visibleWorldRect(): Rect }
export interface FlowEditorProps { organizationId: string; auth: AdminAuth; platformAdmin: boolean }
export type PlanPhase = 'review' | 'applying' | 'done'

const DEFAULT_VIEWPORT: Viewport = { x: 0, y: 0, zoom: 1 }
const COMPACT_STAGE_WIDTH = 1180
const NARROW_STAGE_WIDTH = 640
const EMPTY_SELECTION: FlowSelection = { nodeIds: [], edgeIds: [] }
let autoClearInstalled = false
const STALE_PLAN_MESSAGE = '검토 이후 서버 구성이 바뀌어 변경 목록을 다시 계산했습니다. 다시 확인하세요.'

function layoutFingerprint(graph: FlowGraph): string {
  const nodes = extractLayout(graph, DEFAULT_VIEWPORT).nodes
  return stableStringify(Object.fromEntries(Object.entries(nodes).filter(([id]) => !id.startsWith('target:new-'))))
}

function hashServiceId(): string | null {
  const parts = window.location.hash.replace(/^#\/?/, '').split('/')
  if (parts[0] !== 'flow-designer' || !parts[1]) return null
  try { return decodeURIComponent(parts[1]) } catch { return null }
}

function syncHash(serviceId: string | null) {
  if (!window.location.hash.replace(/^#\/?/, '').startsWith('flow-designer')) return
  const next = serviceId ? `#flow-designer/${encodeURIComponent(serviceId)}` : '#flow-designer'
  if (window.location.hash !== next) window.history.replaceState(window.history.state, '', next)
}

function errorText(error: unknown): string {
  if (error instanceof FlowLoadError) {
    return error.code === 'SERVICE_NOT_FOUND' ? '서비스가 삭제되었거나 접근할 수 없습니다.' : error.message
  }
  return error instanceof Error && error.message ? error.message : '요청을 처리하지 못했습니다.'
}

export function useFlowEditor(props: FlowEditorProps, canvas: Ref<CanvasHandle | null>, stage: Ref<HTMLElement | null> = ref(null)) {
  const loadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const loadError = ref('')
  const message = ref('')
  const base = shallowRef<ServiceFlowSnapshot | null>(null)
  const graph = shallowRef<FlowGraph | null>(null)
  const viewport = ref<Viewport>({ ...DEFAULT_VIEWPORT })
  const selection = ref<FlowSelection>({ ...EMPTY_SELECTION })
  const history = shallowRef<HistoryState<FlowGraph>>(createHistory<FlowGraph>())
  const serviceOptions = ref<ServiceSummary[]>([])
  const selectedKey = ref<string>(NEW_SERVICE_KEY)
  const draftTick = ref(0)
  const restoredAt = ref<string | null>(null)

  const layoutAvailable = ref(false)
  const savedFingerprint = ref('')
  const layoutBusy = ref(false)

  const planOpen = ref(false)
  const planPhase = ref<PlanPhase>('review')
  const plan = shallowRef<FlowPlan | null>(null)
  const planResults = ref<OperationResult[]>([])
  const planBusy = ref(false)
  const planRemote = shallowRef<ServiceFlowSnapshot | null>(null)

  const narrow = ref(false)
  const compact = ref(false)
  const paletteOpen = ref(false)
  const inspectorOpen = ref(false)

  let loadTicket = 0

  /* ---------- derived ---------- */
  const issues = computed(() => (base.value && graph.value ? validateGraph(graph.value, base.value) : []))
  const preview = computed(() => {
    if (!base.value || !graph.value) return null
    return planChanges({ base: base.value, desired: compile(graph.value, base.value), remote: base.value, issues: issues.value })
  })
  const nodeChanges = computed(() => (base.value && graph.value ? computeNodeChanges(graph.value, base.value) : {}))
  const changeCount = computed(() => preview.value?.operations.length ?? 0)
  const errorCount = computed(() => issues.value.filter(issue => issue.severity === 'error').length)
  const warningCount = computed(() => issues.value.filter(issue => issue.severity === 'warning').length)
  const layoutDirty = computed(() => graph.value !== null && layoutFingerprint(graph.value) !== savedFingerprint.value)
  const configDirty = computed(() => base.value !== null && graph.value !== null && isConfigDirty(graph.value, base.value))
  const dirty = computed(() => base.value !== null && (history.value.past.length > 0 || layoutDirty.value || configDirty.value))
  const busy = computed(() => loadState.value === 'loading' || planBusy.value || layoutBusy.value)
  const readOnly = computed(() => narrow.value || loadState.value !== 'ready' || planBusy.value || planOpen.value)
  const canUndo = computed(() => history.value.past.length > 0)
  const canRedo = computed(() => history.value.future.length > 0)
  const serviceLabel = computed(() => {
    const snapshot = base.value
    if (!snapshot) return ''
    if (snapshot.serviceId === null) return '새 서비스 초안'
    const service = snapshot.catalog.services.find(item => item.id === snapshot.serviceId)
    return service ? `${service.displayName || service.serviceKey} (${service.serviceKey})` : snapshot.serviceId
  })
  const hasNewDraft = computed(() => { void draftTick.value; return props.organizationId !== '' && hasDraft(currentUserId(), props.organizationId, null) })

  /* ---------- drafts ---------- */
  function stashCurrent() {
    const snapshot = base.value
    const current = graph.value
    if (!snapshot || !current || !dirty.value) return
    stashDraft({ userId: currentUserId(), organizationId: snapshot.organizationId, serviceId: snapshot.serviceId, base: snapshot, graph: current, viewport: { ...viewport.value }, savedAt: Date.now() })
    draftTick.value += 1
  }

  /* ---------- state helpers ---------- */
  function setGraph(next: FlowGraph) {
    graph.value = next
    const ids = new Set(next.nodes.map(node => node.id))
    const edgeIds = new Set(next.edges.map(edge => edge.id))
    const nodeIds = selection.value.nodeIds.filter(id => ids.has(id))
    const edges = selection.value.edgeIds.filter(id => edgeIds.has(id))
    if (nodeIds.length !== selection.value.nodeIds.length || edges.length !== selection.value.edgeIds.length) selection.value = { nodeIds, edgeIds: edges }
  }

  function fitSoon() {
    void nextTick(() => { requestAnimationFrame(() => canvas.value?.fit()) })
  }

  function resetEditing() {
    history.value = createHistory<FlowGraph>()
    selection.value = { ...EMPTY_SELECTION }
  }

  /* ---------- loading ---------- */
  async function loadService(serviceId: string | null) {
    const ticket = ++loadTicket
    stashCurrent()
    const organizationId = props.organizationId
    const auth = props.auth
    const userId = currentUserId()
    selectedKey.value = serviceId ?? NEW_SERVICE_KEY
    loadState.value = 'loading'
    loadError.value = ''
    message.value = ''
    restoredAt.value = null
    planOpen.value = false
    try {
      const draftExists = hasDraft(userId, organizationId, serviceId)
      const layoutPromise = serviceId ? loadFlowLayout(serviceId, auth) : Promise.resolve({ available: false, layout: null, revision: 0 })
      const snapshotPromise = draftExists ? Promise.resolve(null) : loadFlowSnapshot(organizationId, serviceId, auth)
      const [snapshot, layoutResult] = await Promise.all([snapshotPromise, layoutPromise])
      if (ticket !== loadTicket) return
      const draft = draftExists ? takeDraft(userId, organizationId, serviceId) : null
      layoutAvailable.value = layoutResult.available && serviceId !== null
      if (draft) {
        base.value = draft.base
        graph.value = draft.graph
        viewport.value = { ...draft.viewport }
        savedFingerprint.value = layoutFingerprint(decompile(draft.base, layoutResult.layout))
        serviceOptions.value = draft.base.catalog.services
        restoredAt.value = draft.base.loadedAt
        draftTick.value += 1
        resetEditing()
        loadState.value = 'ready'
        syncHash(serviceId)
        return
      }
      const loaded = snapshot ?? await loadFlowSnapshot(organizationId, serviceId, auth)
      if (ticket !== loadTicket) return
      const initial = decompile(loaded, layoutResult.layout)
      base.value = loaded
      graph.value = initial
      viewport.value = layoutResult.layout ? { ...layoutResult.layout.viewport } : { ...DEFAULT_VIEWPORT }
      savedFingerprint.value = layoutFingerprint(initial)
      serviceOptions.value = loaded.catalog.services
      resetEditing()
      loadState.value = 'ready'
      syncHash(serviceId)
      if (!layoutResult.layout) fitSoon()
      if (loaded.unavailable.length) message.value = '일부 자원 정보를 불러오지 못했습니다. 해당 항목은 확인이 필요합니다.'
    } catch (error) {
      if (ticket !== loadTicket) return
      base.value = null
      graph.value = null
      loadError.value = errorText(error)
      loadState.value = 'error'
    }
  }

  async function init() {
    const ticket = ++loadTicket
    stashCurrent()
    base.value = null
    graph.value = null
    resetEditing()
    planOpen.value = false
    message.value = ''
    restoredAt.value = null
    serviceOptions.value = []
    if (!props.organizationId) { loadState.value = 'idle'; return }
    loadState.value = 'loading'
    try {
      const list = await loadServiceList(props.organizationId, props.auth)
      if (ticket !== loadTicket) return
      serviceOptions.value = list.map(item => ({ ...item, enabled: true }))
      const requested = hashServiceId()
      await loadService(requested ?? list[0]?.id ?? null)
    } catch (error) {
      if (ticket !== loadTicket) return
      loadError.value = errorText(error)
      loadState.value = 'error'
    }
  }

  function selectService(value: string) {
    if (planBusy.value) return
    void loadService(value === NEW_SERVICE_KEY ? null : value)
  }
  function onHashChange() {
    if (!props.organizationId || planBusy.value || planOpen.value) return
    const parts = window.location.hash.replace(/^#\/?/, '').split('/')
    if (parts[0] !== 'flow-designer') return
    const requested = hashServiceId()
    if (!requested || requested === selectedKey.value) return
    if (requested !== NEW_SERVICE_KEY && !serviceOptions.value.some(item => item.id === requested)) return
    selectService(requested)
  }
  function retry() {
    if (base.value || serviceOptions.value.length) void loadService(selectedKey.value === NEW_SERVICE_KEY ? null : selectedKey.value)
    else void init()
  }

  /* ---------- editing ---------- */
  function handleCommand(input: FlowCommand) {
    const snapshot = base.value
    const current = graph.value
    if (!snapshot || !current || readOnly.value) return
    let command = input
    if (command.type === 'addNode' && !command.position && canvas.value) {
      command = { ...command, position: suggestNodePosition(current, command.nodeType, canvas.value.viewportCenterWorld(), canvas.value.visibleWorldRect()) }
    }
    const result = applyCommand(current, command, snapshot)
    message.value = result.error ?? ''
    if (result.changed) {
      history.value = recordHistory(history.value, current)
      setGraph(result.graph)
      if (command.type === 'autoLayout') fitSoon()
    }
    if (result.select) selection.value = result.select
  }
  function undo() {
    const current = graph.value
    if (!current || readOnly.value) return
    const result = undoHistory(history.value, current)
    if (!result) return
    history.value = result.history
    setGraph(result.state)
  }
  function redo() {
    const current = graph.value
    if (!current || readOnly.value) return
    const result = redoHistory(history.value, current)
    if (!result) return
    history.value = result.history
    setGraph(result.state)
  }
  function focusNode(nodeId: string) {
    selection.value = { nodeIds: [nodeId], edgeIds: [] }
    canvas.value?.centerOn(nodeId)
  }
  function openInspector() {
    if (!compact.value) return
    inspectorOpen.value = true
    paletteOpen.value = false
  }
  function updateSelection(next: FlowSelection) { selection.value = next }
  function updateViewport(next: Viewport) { viewport.value = next }

  function revert() {
    const snapshot = base.value
    const current = graph.value
    if (!snapshot || !current || readOnly.value) return
    if (typeof window !== 'undefined' && !window.confirm('캔버스의 모든 변경을 버리고 마지막으로 불러온 서버 상태로 되돌릴까요?')) return
    history.value = recordHistory(history.value, current)
    setGraph(decompile(snapshot, extractLayout(current, viewport.value)))
    message.value = '변경을 되돌렸습니다.'
  }

  async function reload() {
    const snapshot = base.value
    const current = graph.value
    if (!snapshot || !current || busy.value) return
    planBusy.value = true
    message.value = '서버의 최신 상태를 불러오고 있습니다…'
    try {
      const remote = await loadFlowSnapshot(snapshot.organizationId, snapshot.serviceId, props.auth)
      if (changeCount.value > 0 || history.value.past.length > 0) {
        const merged = rebase({ graph: current, base: snapshot, remote })
        setGraph(merged.graph)
        message.value = merged.conflicts.length
          ? `서버 상태와 병합했습니다. 충돌한 ${merged.conflicts.length}개 항목은 서버 값으로 바뀌었습니다.`
          : '서버 상태와 병합했습니다. 내 변경은 그대로 유지됩니다.'
      } else {
        setGraph(decompile(remote, extractLayout(current, viewport.value)))
        message.value = '서버 상태를 다시 불러왔습니다.'
      }
      base.value = remote
      serviceOptions.value = remote.catalog.services
      restoredAt.value = null
      history.value = createHistory<FlowGraph>()
    } catch (error) {
      message.value = errorText(error)
    } finally {
      planBusy.value = false
    }
  }

  async function saveLayout() {
    const snapshot = base.value
    const current = graph.value
    if (!snapshot?.serviceId || !current || !layoutAvailable.value || busy.value) return
    layoutBusy.value = true
    try {
      const result = await saveFlowLayout(snapshot.serviceId, extractLayout(current, viewport.value), props.auth)
      savedFingerprint.value = layoutFingerprint(current)
      message.value = `배치를 저장했습니다. (리비전 ${result.revision})`
    } catch (error) {
      message.value = `배치를 저장하지 못했습니다. ${errorText(error)}`
    } finally {
      layoutBusy.value = false
    }
  }

  /* ---------- plan / apply ---------- */
  function computePlan(remote: ServiceFlowSnapshot): FlowPlan | null {
    if (!base.value || !graph.value) return null
    return planChanges({ base: base.value, desired: compile(graph.value, base.value), remote, issues: issues.value })
  }

  async function openPlan() {
    const snapshot = base.value
    if (!snapshot || !graph.value || planBusy.value) return
    planOpen.value = true
    planPhase.value = 'review'
    planResults.value = []
    plan.value = null
    planBusy.value = true
    try {
      const remote = await loadFlowSnapshot(snapshot.organizationId, snapshot.serviceId, props.auth)
      planRemote.value = remote
      plan.value = computePlan(remote)
    } catch (error) {
      planOpen.value = false
      message.value = errorText(error)
    } finally {
      planBusy.value = false
    }
  }

  function closePlan() {
    if (planPhase.value === 'applying') return
    planOpen.value = false
    plan.value = null
    planResults.value = []
  }

  async function mergeRemote() {
    const snapshot = base.value
    const current = graph.value
    const remote = planRemote.value
    if (!snapshot || !current || !remote || planBusy.value) return
    const merged = rebase({ graph: current, base: snapshot, remote })
    setGraph(merged.graph)
    base.value = remote
    serviceOptions.value = remote.catalog.services
    history.value = createHistory<FlowGraph>()
    message.value = merged.conflicts.length ? `서버 상태와 병합했습니다. 충돌한 ${merged.conflicts.length}개 항목은 서버 값으로 바뀌었습니다.` : '서버 상태와 병합했습니다.'
    await openPlan()
  }

  async function applyPlan() {
    const snapshot = base.value
    const current = graph.value
    const reviewed = plan.value
    if (!snapshot || !current || !reviewed || planPhase.value !== 'review' || planBusy.value) return
    planBusy.value = true
    planPhase.value = 'applying'
    planResults.value = []
    try {
      // Re-read and recompute: execute only when the reviewed plan is still exactly what would be applied.
      const remote = await loadFlowSnapshot(snapshot.organizationId, snapshot.serviceId, props.auth)
      planRemote.value = remote
      const fresh = computePlan(remote)
      if (!fresh) { planPhase.value = 'review'; return }
      if (fresh.fingerprint !== reviewed.fingerprint || fresh.conflicts.length > 0 || fresh.blockingIssues.length > 0) {
        plan.value = { ...fresh, notes: [{ level: 'warning', message: STALE_PLAN_MESSAGE }, ...fresh.notes] }
        planPhase.value = 'review'
        return
      }
      const report = await executePlan(fresh, createAdminTransport(props.auth), {
        serviceId: snapshot.serviceId,
        onProgress: results => { planResults.value = results }
      })
      planResults.value = report.results
      const anySucceeded = report.results.some(item => item.status === 'succeeded')

      // Always re-read and rebase so the canvas matches the server, keeping any remaining edits.
      try {
        const synced = await loadFlowSnapshot(snapshot.organizationId, report.serviceId ?? snapshot.serviceId, props.auth)
        const merged = rebase({ graph: current, base: snapshot, remote: synced, createdTargetIds: report.createdTargetIds, deletedTargetKeys: report.deletedTargetKeys })
        const wasNew = snapshot.serviceId === null
        setGraph(merged.graph)
        base.value = synced
        serviceOptions.value = synced.catalog.services
        history.value = createHistory<FlowGraph>()
        selection.value = { ...EMPTY_SELECTION }
        restoredAt.value = null
        if (synced.serviceId !== snapshot.serviceId) {
          selectedKey.value = synced.serviceId ?? NEW_SERVICE_KEY
          syncHash(synced.serviceId)
        }
        if (report.completed) takeDraft(currentUserId(), snapshot.organizationId, snapshot.serviceId)
        message.value = merged.conflicts.length ? `적용 후 서버 값과 다른 ${merged.conflicts.length}개 항목이 있어 서버 값으로 맞췄습니다.` : ''
        if (anySucceeded && synced.serviceId) {
          let available = layoutAvailable.value
          if (!available && wasNew) available = (await loadFlowLayout(synced.serviceId, props.auth)).available
          layoutAvailable.value = available
          if (available) {
            try {
              await saveFlowLayout(synced.serviceId, extractLayout(merged.graph, viewport.value), props.auth)
              savedFingerprint.value = layoutFingerprint(merged.graph)
            } catch { /* layout is optional; ignore */ }
          }
        }
      } catch (error) {
        message.value = `적용은 끝났지만 서버 상태를 다시 불러오지 못했습니다. 새로고침하세요. ${errorText(error)}`
      }
      planPhase.value = 'done'
    } catch (error) {
      message.value = errorText(error)
      planPhase.value = planResults.value.length ? 'done' : 'review'
    } finally {
      planBusy.value = false
    }
  }

  /* ---------- toolbar ---------- */
  function onToolbarAction(action: FlowToolbarAction) {
    switch (action) {
      case 'undo': undo(); break
      case 'redo': redo(); break
      case 'fit': canvas.value?.fit(); break
      case 'auto-layout': handleCommand({ type: 'autoLayout' }); break
      case 'validate': selection.value = { ...EMPTY_SELECTION }; inspectorOpen.value = true; break
      case 'plan': void openPlan(); break
      case 'revert': revert(); break
      case 'reload': void reload(); break
      case 'save-layout': void saveLayout(); break
      case 'toggle-palette': paletteOpen.value = !paletteOpen.value; if (paletteOpen.value) inspectorOpen.value = false; break
      case 'toggle-inspector': inspectorOpen.value = !inspectorOpen.value; if (inspectorOpen.value) paletteOpen.value = false; break
    }
  }

  /* ---------- lifecycle ---------- */
  // Layout mode is decided from the stage's own width (layout px, unaffected by the console's CSS zoom),
  // because the console sidebar takes a variable share of the window: compact (drawers) below 1180px,
  // read-only (narrow) below 640px.
  let stageObserver: ResizeObserver | null = null
  function applyStageWidth(width: number) {
    if (!(width > 0)) return
    narrow.value = width < NARROW_STAGE_WIDTH
    const next = width < COMPACT_STAGE_WIDTH
    if (next !== compact.value) {
      compact.value = next
      if (!next) { paletteOpen.value = false; inspectorOpen.value = false }
    }
  }
  watch(stage, (el) => {
    stageObserver?.disconnect()
    stageObserver = null
    if (!el || typeof ResizeObserver === 'undefined') return
    applyStageWidth(el.offsetWidth)
    stageObserver = new ResizeObserver(entries => { const rect = entries[0]?.contentRect; if (rect) applyStageWidth(rect.width) })
    stageObserver.observe(el)
  }, { flush: 'post' })
  function beforeUnload(event: BeforeUnloadEvent) {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  onMounted(() => {
    window.addEventListener('hashchange', onHashChange)
    window.addEventListener('beforeunload', beforeUnload)
    // Installed once for the app lifetime so drafts are also dropped while this page is not mounted.
    if (!autoClearInstalled) { autoClearInstalled = true; installDraftAutoClear(window) }
    void init()
  })
  onBeforeUnmount(() => {
    stashCurrent()
    loadTicket += 1
    window.removeEventListener('beforeunload', beforeUnload)
    window.removeEventListener('hashchange', onHashChange)
    stageObserver?.disconnect()
    stageObserver = null
  })
  watch(() => props.organizationId, () => { void init() })

  return {
    loadState, loadError, message, base, graph, viewport, selection, serviceOptions, selectedKey, restoredAt, hasNewDraft,
    layoutAvailable, layoutDirty, planOpen, planPhase, plan, planResults, planBusy,
    narrow, compact, paletteOpen, inspectorOpen,
    issues, nodeChanges, changeCount, errorCount, warningCount, busy, readOnly, canUndo, canRedo, serviceLabel,
    selectService, openInspector, retry, init, handleCommand, undo, redo, focusNode, updateSelection, updateViewport,
    onToolbarAction, openPlan, closePlan, mergeRemote, applyPlan, reload
  }
}
