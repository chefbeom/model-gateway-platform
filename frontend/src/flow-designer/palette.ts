import { NODE_TYPES, modelAvailability, projectNodeId, projectSignals, toneOf } from './catalog'
import type {
  AddableNodeType,
  DeploymentInfo,
  FlowGraph,
  HealthTone,
  ModelAvailability,
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
  groups.push({ id: 'entry', label: GROUP_LABELS.entry, nodes: [], projects, models: [], hiddenCount: 0 })

  for (const [id, types] of nodeGroups) {
    groups.push({
      id,
      label: GROUP_LABELS[id],
      nodes: types.filter(wanted).map((t) => nodeItem(t, graph, snapshot)),
      projects: [],
      models: [],
      hiddenCount: 0,
    })
  }

  for (const external of [false, true]) {
    const id: PaletteGroupId = external ? 'llm-external' : 'llm-local'
    const all = snapshot.catalog.deployments
      .filter((d) => d.external === external)
      .map((d) => modelItem(d, graph, snapshot))
      .sort((a, b) => AVAILABILITY_ORDER[a.availability] - AVAILABILITY_ORDER[b.availability] || compareText(a.displayName, b.displayName) || compareText(a.deploymentId, b.deploymentId))
    let hiddenCount = 0
    const models = all.filter((m) => {
      if (searching || options.showInactive || m.availability !== 'inactive' || m.onCanvas) return true
      hiddenCount += 1
      return false
    })
    groups.push({ id, label: GROUP_LABELS[id], nodes: [], projects: [], models, hiddenCount })
  }

  if (!searching) return groups

  const kindWord = (g: PaletteGroup) => (g.id === 'llm-local' ? '로컬' : g.id === 'llm-external' ? '외부' : '')
  const result: PaletteGroup[] = []
  for (const g of groups) {
    const groupHit = matches(g.label, kindWord(g))
    const nodes = groupHit ? g.nodes : g.nodes.filter((n) => matches(n.label, n.description, NODE_TYPES[n.nodeType].keywords))
    const projectItems = groupHit ? g.projects : g.projects.filter((p) => matches(p.name, p.status))
    const models = groupHit ? g.models : g.models.filter((m) => matches(m.displayName, m.providerModelId, m.sourceName))
    if (nodes.length + projectItems.length + models.length === 0) continue
    result.push({ ...g, nodes, projects: projectItems, models, hiddenCount: 0 })
  }
  return result
}
