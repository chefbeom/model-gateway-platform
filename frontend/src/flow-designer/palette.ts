import { NODE_TYPES, modelAvailability, projectNodeId, projectSignals, toneOf } from './catalog'
import type {
  AddableNodeType,
  ConnectionMeta,
  DeploymentConnectionGroup,
  DeploymentInfo,
  FlowCatalog,
  FlowGraph,
  HealthTone,
  ModelAvailability,
  PaletteConnectionGroup,
  PaletteGroup,
  PaletteGroupId,
  PaletteModelItem,
  PaletteNodeItem,
  PaletteOptions,
  PaletteProjectItem,
  ServiceFlowSnapshot,
} from './types'

const GROUP_LABELS: Record<PaletteGroupId, string> = {
  entry: '시작 · 프로젝트',
  guard: '가드레일 · 데이터 보호',
  prompt: '프롬프트',
  cache: '캐시',
  routing: '라우팅',
  'llm-local': '로컬 LLM',
  'llm-external': '외부 LLM',
}

const UNKNOWN_CONNECTION = '알 수 없는 연결'

const AVAILABILITY_ORDER: Record<ModelAvailability, number> = { active: 0, degraded: 1, inactive: 2 }

function nodeItem(nodeType: Extract<AddableNodeType, 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router'>, graph: FlowGraph, snapshot: ServiceFlowSnapshot): PaletteNodeItem {
  const spec = NODE_TYPES[nodeType]
  const has = (id: string) => graph.nodes.some((n) => n.id === id)
  const base = { nodeType, label: spec.label, description: spec.description, icon: spec.icon }
  switch (nodeType) {
    case 'guardrail': {
      const on = has('guardrail')
      return { ...base, clickDisabledReason: on ? '이미 워크플로우에 있습니다. 클릭하면 선택합니다.' : null, draggable: !on, onCanvas: on }
    }
    case 'masker': {
      const on = has('masker')
      const guard = has('guardrail')
      const reason = !guard ? '가드레일을 먼저 추가하세요.' : on ? '이미 있습니다.' : null
      return { ...base, clickDisabledReason: reason, draggable: reason === null, onCanvas: on }
    }
    case 'prompt': {
      const on = has('prompt')
      return {
        ...base,
        clickDisabledReason: on ? '공통 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선에 끌어 놓거나 ＋를 누르세요.' : null,
        draggable: !(on && snapshot.features?.targetPrompt === false),
        onCanvas: on,
      }
    }
    default: {
      const on = has(nodeType)
      return { ...base, clickDisabledReason: on ? '이미 워크플로우에 있습니다.' : null, draggable: !on, onCanvas: on }
    }
  }
}

function modelItem(d: DeploymentInfo, graph: FlowGraph, snapshot: ServiceFlowSnapshot): PaletteModelItem {
  const { availability, reason } = modelAvailability(d, snapshot.catalog)
  const source = d.external
    ? snapshot.catalog.providers.find((p) => p.id === d.externalProviderId)?.displayName ?? '외부 Provider'
    : snapshot.catalog.endpoints.find((e) => e.id === d.runtimeEndpointId)?.displayName ?? '로컬 Runtime'
  const tone: HealthTone = availability === 'inactive' ? 'muted' : toneOf(d.enabled, d.healthStatus)
  return {
    deploymentId: d.id,
    displayName: d.displayName,
    providerModelId: d.providerModelId,
    sourceName: source,
    external: d.external,
    availability,
    tone: availability === 'degraded' && (tone === 'healthy' || tone === 'unknown') ? 'warning' : tone,
    reason,
    onCanvas: graph.nodes.some((n) => n.id === `deployment:${d.id}`),
  }
}

function compareText(a: string, b: string): number {
  return a.localeCompare(b, 'ko')
}

const MODEL_EXTENSIONS = /\.(gguf|bin|safetensors)$/i

/**
 * Card title for a model. Only path-like names (containing `/` or `\`) are shortened to the file basename without
 * a common model extension; every other name is returned unchanged. `providerModelId` is the fallback for an empty name.
 */
export function shortModelName(displayName: string, providerModelId: string): string {
  const name = displayName.trim() !== '' ? displayName : providerModelId
  if (!/[\\/]/.test(name)) return name
  const base = name.split(/[\\/]/).filter((part) => part !== '').pop() ?? ''
  const short = base.replace(MODEL_EXTENSIONS, '')
  return short !== '' ? short : name
}

/** Resolves the connection a deployment belongs to; missing catalog entries land in an "unknown connection". */
export function connectionOf(d: DeploymentInfo, catalog: FlowCatalog): ConnectionMeta {
  if (d.external) {
    const provider = d.externalProviderId ? catalog.providers.find((p) => p.id === d.externalProviderId) : undefined
    if (provider) return { id: `provider:${provider.id}`, kind: 'provider', label: provider.displayName, detail: provider.providerType, tone: toneOf(provider.enabled, provider.healthStatus), enabled: provider.enabled }
    return { id: 'unknown-external', kind: 'provider', label: UNKNOWN_CONNECTION, detail: '', tone: 'unknown', enabled: true }
  }
  const endpoint = d.runtimeEndpointId ? catalog.endpoints.find((e) => e.id === d.runtimeEndpointId) : undefined
  if (endpoint) {
    return { id: `endpoint:${endpoint.id}`, kind: 'endpoint', label: endpoint.displayName, detail: [endpoint.runtimeType, endpoint.addressLabel].filter((x) => !!x).join(' · '), tone: toneOf(endpoint.enabled, endpoint.healthStatus), enabled: endpoint.enabled }
  }
  return { id: 'unknown-local', kind: 'endpoint', label: UNKNOWN_CONNECTION, detail: '', tone: 'unknown', enabled: true }
}

interface Sortable { availability: ModelAvailability; displayName: string; deploymentId: string }
function compareModels(a: Sortable, b: Sortable): number {
  return AVAILABILITY_ORDER[a.availability] - AVAILABILITY_ORDER[b.availability] || compareText(a.displayName, b.displayName) || compareText(a.deploymentId, b.deploymentId)
}

/**
 * Groups deployments by their connection. Connections with any active model come first, the unknown connection last within its tier, then by label, then id.
 * Inside a connection models are sorted active -> degraded -> inactive, then by name. Shared by the palette,
 * the quick-start picker and the inspector's model swap select.
 */
export function groupModelsByConnection(deployments: DeploymentInfo[], catalog: FlowCatalog): DeploymentConnectionGroup[] {
  const byId = new Map<string, { connection: ConnectionMeta; items: Array<{ d: DeploymentInfo; availability: ModelAvailability }>; activeCount: number }>()
  for (const d of deployments) {
    const connection = connectionOf(d, catalog)
    const entry = byId.get(connection.id) ?? { connection, items: [], activeCount: 0 }
    const availability = modelAvailability(d, catalog).availability
    entry.items.push({ d, availability })
    if (availability === 'active') entry.activeCount += 1
    byId.set(connection.id, entry)
  }
  return [...byId.values()]
    .sort((a, b) => Number(b.activeCount > 0) - Number(a.activeCount > 0) || Number(a.connection.id.startsWith('unknown-')) - Number(b.connection.id.startsWith('unknown-')) || compareText(a.connection.label, b.connection.label) || compareText(a.connection.id, b.connection.id))
    .map((g) => ({
      connection: g.connection,
      activeCount: g.activeCount,
      items: g.items
        .sort((a, b) => compareModels({ availability: a.availability, displayName: a.d.displayName, deploymentId: a.d.id }, { availability: b.availability, displayName: b.d.displayName, deploymentId: b.d.id }))
        .map((x) => x.d),
    }))
}

/** §9: the grouped, sorted and filtered palette model. Pure: the component only renders this. */
export function buildPalette(snapshot: ServiceFlowSnapshot, graph: FlowGraph, options: PaletteOptions): PaletteGroup[] {
  const query = options.query.trim().toLowerCase()
  const searching = query !== ''
  const matches = (...texts: Array<string | undefined>): boolean => texts.some((t) => !!t && t.toLowerCase().includes(query))

  const wanted = (type: Parameters<typeof nodeItem>[0]): boolean => !(options.mode === 'basic' && NODE_TYPES[type].advanced)
  const nodeGroups: Array<[PaletteGroupId, Array<Parameters<typeof nodeItem>[0]>]> = [
    ['guard', ['guardrail', 'masker']],
    ['prompt', ['prompt', 'params']],
    ['cache', ['cache']],
    ['routing', ['router']],
  ]

  const groups: PaletteGroup[] = []

  // entry: projects
  const granted = (id: string) => graph.edges.some((e) => e.kind === 'entry' && e.to.nodeId === id)
  const projects: PaletteProjectItem[] = snapshot.projectsHidden === true
    ? []
    : snapshot.catalog.projects
      .map((p) => ({
        projectId: p.id,
        name: p.name,
        status: p.status,
        onCanvas: graph.nodes.some((n) => n.id === projectNodeId(p.id)),
        granted: granted(projectNodeId(p.id)),
        guarded: projectSignals(p).length > 0,
      }))
      .sort((a, b) => compareText(a.name, b.name) || compareText(a.projectId, b.projectId))
  groups.push({ id: 'entry', label: GROUP_LABELS.entry, nodes: [], projects, models: [], connections: [], hiddenCount: 0 })

  for (const [id, types] of nodeGroups) {
    groups.push({
      id,
      label: GROUP_LABELS[id],
      nodes: types.filter(wanted).map((t) => nodeItem(t, graph, snapshot)),
      projects: [],
      models: [],
      connections: [],
      hiddenCount: 0,
    })
  }

  for (const external of [false, true]) {
    const id: PaletteGroupId = external ? 'llm-external' : 'llm-local'
    const connections: PaletteConnectionGroup[] = []
    let hiddenCount = 0
    for (const g of groupModelsByConnection(snapshot.catalog.deployments.filter((d) => d.external === external), snapshot.catalog)) {
      const all = g.items.map((d) => modelItem(d, graph, snapshot))
      let hidden = 0
      const visible = all.filter((m) => {
        if (searching || options.showInactive || m.availability !== 'inactive' || m.onCanvas) return true
        hidden += 1
        return false
      })
      hiddenCount += hidden
      // A disabled connection whose models are all hidden is dropped (the group-level "hidden" toggle still reveals it);
      // an enabled one stays as a header so users know it exists.
      if (visible.length === 0 && !g.connection.enabled) continue
      connections.push({ ...g.connection, models: visible, activeCount: g.activeCount, totalCount: all.length, hiddenCount: hidden })
    }
    // Flattened list keeps the historical global order (availability, then name).
    const models = connections.flatMap((c) => c.models).sort(compareModels)
    groups.push({ id, label: GROUP_LABELS[id], nodes: [], projects: [], models, connections, hiddenCount })
  }

  if (!searching) return groups

  const kindWord = (g: PaletteGroup) => (g.id === 'llm-local' ? '로컬' : g.id === 'llm-external' ? '외부' : '')
  const result: PaletteGroup[] = []
  for (const g of groups) {
    const groupHit = matches(g.label, kindWord(g))
    const nodes = groupHit ? g.nodes : g.nodes.filter((n) => matches(n.label, n.description, NODE_TYPES[n.nodeType].keywords))
    const projectItems = groupHit ? g.projects : g.projects.filter((p) => matches(p.name, p.status))
    const connections = groupHit
      ? g.connections
      : g.connections
        .map((c) => (matches(c.label, c.detail) ? c : { ...c, models: c.models.filter((m) => matches(m.displayName, m.providerModelId, m.sourceName)) }))
        .filter((c) => c.models.length > 0)
    const models = groupHit ? g.models : connections.flatMap((c) => c.models).sort(compareModels)
    if (nodes.length + projectItems.length + models.length === 0) continue
    result.push({ ...g, nodes, projects: projectItems, models, connections, hiddenCount: 0 })
  }
  return result
}
