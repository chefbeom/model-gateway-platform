import { LIMITS, SERVICE_KEY_PATTERN } from './catalog'
import { compile } from './compile'
import type { FlowGraph, FlowNode, IssueSeverity, ServiceFlowSnapshot, ValidationIssue } from './types'

type Extra = { nodeId?: string | null; edgeId?: string | null; field?: string | null }

const PIPELINE_EDGE_IDS = [
  'service/request->guardrail/request',
  'guardrail/request->prompt/request',
  'prompt/request->cache/request',
  'cache/request->router/request',
]
const SINGLETON_NODES = ['service', 'guardrail', 'prompt', 'cache', 'router']

function isInt(value: number): boolean {
  return Number.isFinite(value) && Number.isInteger(value)
}

function capabilitiesValid(json: string): boolean {
  if (json.length > LIMITS.capabilitiesMaxLength) return false
  try {
    const parsed: unknown = JSON.parse(json)
    return Array.isArray(parsed) && parsed.every((x) => typeof x === 'string')
  } catch {
    return false
  }
}

function findCycle(graph: FlowGraph): string | null {
  const adjacency = new Map<string, string[]>()
  for (const e of graph.edges) {
    const list = adjacency.get(e.from.nodeId)
    if (list) list.push(e.to.nodeId)
    else adjacency.set(e.from.nodeId, [e.to.nodeId])
  }
  const state = new Map<string, 0 | 1 | 2>() // 1 = on stack, 2 = done
  for (const start of graph.nodes.map((n) => n.id)) {
    if (state.get(start)) continue
    const stack: Array<{ id: string; index: number }> = [{ id: start, index: 0 }]
    state.set(start, 1)
    while (stack.length > 0) {
      const frame = stack[stack.length - 1]
      const next = adjacency.get(frame.id) ?? []
      if (frame.index >= next.length) {
        state.set(frame.id, 2)
        stack.pop()
        continue
      }
      const to = next[frame.index++]
      const s = state.get(to)
      if (s === 1) return to
      if (!s) {
        state.set(to, 1)
        stack.push({ id: to, index: 0 })
      }
    }
  }
  return null
}

export function validateGraph(graph: FlowGraph, base: ServiceFlowSnapshot): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const add = (code: string, severity: IssueSeverity, message: string, extra: Extra = {}) => {
    issues.push({ code, severity, message, nodeId: extra.nodeId ?? null, edgeId: extra.edgeId ?? null, field: extra.field ?? null })
  }

  if (graph.nodes.length > LIMITS.maxNodes || graph.edges.length > LIMITS.maxEdges) {
    add('GRAPH_TOO_LARGE', 'error', '캔버스는 노드 300개, 연결 600개까지 지원합니다.')
  }
  const nodeIds = new Set(graph.nodes.map((n) => n.id))
  const edgeIds = new Set(graph.edges.map((e) => e.id))
  const broken = !SINGLETON_NODES.every((id) => nodeIds.has(id)) || !PIPELINE_EDGE_IDS.every((id) => edgeIds.has(id))
  if (broken) add('STRUCTURE_BROKEN', 'error', '고정 파이프라인 구조가 손상되었습니다. 되돌리기 또는 새로고침하세요.', { nodeId: 'service' })
  const cycleNode = findCycle(graph)
  if (cycleNode) add('CYCLE_DETECTED', 'error', '순환 연결은 허용되지 않습니다.', { nodeId: cycleNode })
  if (broken) return issues

  const L = compile(graph, base)
  const s = L.service
  const catalog = base.catalog
  const isNew = graph.serviceId === null
  const nodesById = new Map<string, FlowNode>(graph.nodes.map((n) => [n.id, n]))

  // --- service ---
  if (isNew) {
    if (!SERVICE_KEY_PATTERN.test(s.serviceKey)) {
      add('SERVICE_KEY_INVALID', 'error', 'Service Key는 소문자·숫자·하이픈만 쓸 수 있고 영숫자로 시작해야 합니다(최대 120자).', { nodeId: 'service', field: 'serviceKey' })
    } else if (catalog.services.some((x) => x.serviceKey === s.serviceKey)) {
      add('SERVICE_KEY_TAKEN', 'error', '이 조직에 이미 있는 Service Key입니다.', { nodeId: 'service', field: 'serviceKey' })
    }
  }
  if (s.displayName.trim() === '') add('DISPLAY_NAME_REQUIRED', 'error', '표시 이름을 입력하세요.', { nodeId: 'service', field: 'displayName' })
  else if (s.displayName.length > LIMITS.displayNameMaxLength) add('DISPLAY_NAME_TOO_LONG', 'error', '표시 이름은 200자 이하여야 합니다.', { nodeId: 'service', field: 'displayName' })
  if (!capabilitiesValid(s.requiredCapabilitiesJson)) add('CAPABILITIES_INVALID', 'error', '필수 Capability 형식이 올바르지 않습니다.', { nodeId: 'service', field: 'requiredCapabilitiesJson' })
  for (const field of ['inputPricePerMillion', 'outputPricePerMillion'] as const) {
    const v = s[field]
    if (!Number.isFinite(v) || v < 0) add('PRICE_INVALID', 'error', '단가는 0 이상의 숫자여야 합니다.', { nodeId: 'service', field })
  }

  // --- prompt / parameters ---
  if (s.temperaturePolicy === 'FIXED') {
    if (s.temperatureValue === null) add('TEMPERATURE_VALUE_REQUIRED', 'error', '고정 Temperature 값을 입력하세요.', { nodeId: 'prompt', field: 'temperatureValue' })
    else if (!Number.isFinite(s.temperatureValue) || s.temperatureValue < 0 || s.temperatureValue > 2) add('TEMPERATURE_VALUE_RANGE', 'error', 'Temperature는 0에서 2 사이여야 합니다.', { nodeId: 'prompt', field: 'temperatureValue' })
  }
  if (s.systemPromptTemplate !== null && s.systemPromptTemplate.length > LIMITS.promptMaxLength) {
    add('PROMPT_TOO_LONG', 'error', '시스템 프롬프트는 16,000자 이하여야 합니다.', { nodeId: 'prompt', field: 'systemPromptTemplate' })
  }

  // --- cache ---
  const ttl = s.responseCacheTtlSeconds
  if (!isInt(ttl) || ttl < 10 || ttl > 86400) add('CACHE_TTL_RANGE', 'error', '캐시 TTL은 10–86,400초 사이 정수여야 합니다.', { nodeId: 'cache', field: 'responseCacheTtlSeconds' })

  // --- scheduling ---
  const c = L.scheduling
  const ranges: Array<[keyof typeof c, number, number, string]> = [
    ['localWaitMs', 0, 60000, 'localWaitMs'],
    ['maxWaitMs', 100, 60000, 'maxWaitMs'],
    ['maxQueuedRequests', 1, 500, 'maxQueuedRequests'],
    ['maxQueuedPerKey', 1, 500, 'maxQueuedPerKey'],
    ['maxQueuedBytes', 1024, 134217728, 'maxQueuedBytes'],
  ]
  for (const [key, min, max, field] of ranges) {
    const v = c[key] as number
    if (!isInt(v) || v < min || v > max) add('SCHEDULING_RANGE', 'error', '배분·대기열 값이 허용 범위를 벗어났습니다.', { nodeId: 'router', field })
  }
  if (c.mode === 'LOCAL_FIRST_WAIT' && c.localWaitMs > c.maxWaitMs) {
    add('SCHEDULING_LOCAL_WAIT_EXCEEDS_MAX', 'error', '로컬 대기 시간은 전체 최대 대기 시간을 넘을 수 없습니다.', { nodeId: 'router', field: 'localWaitMs' })
  }
  if (c.maxQueuedPerKey > c.maxQueuedRequests) {
    add('SCHEDULING_PER_KEY_EXCEEDS_TOTAL', 'error', 'API 키별 대기 한도는 서비스 전체 한도를 넘을 수 없습니다.', { nodeId: 'router', field: 'maxQueuedPerKey' })
  }

  // --- targets ---
  const baseTargets = new Map(base.config.targets.map((t) => [t.key, t]))
  const targetNodes = graph.nodes.filter((n) => n.type === 'target')
  const byDeployment = new Map<string, string[]>()
  for (const t of L.targets) {
    const list = byDeployment.get(t.deploymentId)
    if (list) list.push(t.key)
    else byDeployment.set(t.deploymentId, [t.key])
  }
  const compiled = new Map(L.targets.map((t) => [t.key, t]))
  for (const node of targetNodes) {
    const t = compiled.get(node.id)
    if (!t) {
      add('TARGET_UNBOUND', 'error', 'Deployment에 연결되지 않은 Target이 있습니다.', { nodeId: node.id })
      continue
    }
    const positive = (v: number) => isInt(v) && v >= 1
    if (!positive(t.priority) || !positive(t.weight) || (t.maxConcurrencyOverride !== null && !positive(t.maxConcurrencyOverride))) {
      add('TARGET_NUMBER_INVALID', 'error', 'Target 우선순위·가중치·동시성은 1 이상의 정수여야 합니다.', { nodeId: node.id })
    }
    const before = baseTargets.get(node.id)
    if (before && before.maxConcurrencyOverride !== null && t.maxConcurrencyOverride === null) {
      add('TARGET_CONCURRENCY_CLEAR_UNSUPPORTED', 'error', '서버가 동시성 재정의 해제를 지원하지 않습니다. Target을 삭제 후 다시 추가하세요.', { nodeId: node.id, field: 'maxConcurrencyOverride' })
    }
    if ((byDeployment.get(t.deploymentId) ?? []).length > 1) {
      add('DUPLICATE_DEPLOYMENT_TARGET', 'error', '한 서비스에서 같은 Deployment에는 Target을 하나만 둘 수 있습니다.', { nodeId: node.id })
    }
    const dep = catalog.deployments.find((d) => d.id === t.deploymentId)
    if (!dep) {
      const unchanged = !!before && before.deploymentId === t.deploymentId
      add('UNKNOWN_DEPLOYMENT', unchanged ? 'warning' : 'error', '등록 정보를 찾을 수 없는 Deployment입니다.', { nodeId: node.id })
      continue
    }
    if (!dep.enabled || String(dep.healthStatus).toUpperCase() === 'UNHEALTHY' || (!dep.external && !dep.loaded)) {
      add('DEPLOYMENT_UNAVAILABLE', 'warning', '현재 사용할 수 없는 Deployment입니다(Target은 유지됩니다).', { nodeId: node.id })
    }
    if (dep.external && s.dataBoundaryPolicy !== 'EXTERNAL_ALLOWED') {
      add('EXTERNAL_TARGET_BLOCKED_BY_BOUNDARY', 'warning', '서비스 데이터 경계 정책 때문에 이 외부 Target은 실행되지 않습니다.', { nodeId: node.id })
    }
    if (dep.external && t.followModelChanges) {
      add('FOLLOW_MODEL_ON_EXTERNAL', 'info', '외부 Deployment에는 모델 자동 추적이 적용되지 않습니다.', { nodeId: node.id, field: 'followModelChanges' })
    }
  }

  // --- masker ---
  const masker = nodesById.get('masker')
  if (masker) {
    const connected = graph.edges.some((e) => e.kind === 'masker' && e.from.nodeId === 'masker' && e.to.nodeId === 'guardrail')
    const id = typeof masker.props.deploymentId === 'string' ? masker.props.deploymentId : ''
    if (!connected) {
      add('MASKER_DISCONNECTED', 'warning', '연결되지 않은 마스킹 워커는 적용되지 않습니다.', { nodeId: 'masker' })
    } else if (id === '') {
      add('MASKER_NOT_SELECTED', 'error', '마스킹 워커 Deployment를 선택하세요.', { nodeId: 'masker', field: 'deploymentId' })
    } else if (base.unavailable.some((path) => path.includes('masker-deployments'))) {
      add('MASKER_UNVERIFIED', 'warning', '마스킹 워커 자격을 확인하지 못했습니다.', { nodeId: 'masker', field: 'deploymentId' })
    } else if (!catalog.maskers.some((m) => m.deploymentId === id && m.eligible)) {
      add('MASKER_NOT_ELIGIBLE', 'error', '신뢰 마스킹 워커로 사용할 수 없는 Deployment입니다.', { nodeId: 'masker', field: 'deploymentId' })
    }
  }

  // --- access ---
  const before = new Set(base.config.accessProjectIds)
  const after = new Set(L.accessProjectIds)
  const added = L.accessProjectIds.filter((id) => !before.has(id))
  const removed = base.config.accessProjectIds.filter((id) => !after.has(id))
  for (const projectId of added) {
    if (!catalog.projects.some((p) => p.id === projectId)) add('UNKNOWN_PROJECT', 'error', '알 수 없는 프로젝트입니다.', { nodeId: `project:${projectId}` })
  }
  if ((added.length > 0 || removed.length > 0) && !base.accessComplete) {
    add('ACCESS_EDIT_UNAVAILABLE', 'error', '프로젝트 접근 정보를 모두 불러오지 못해 접근 변경을 적용할 수 없습니다.')
  }
  for (const projectId of L.accessProjectIds) {
    const info = catalog.projects.find((p) => p.id === projectId)
    if (info && info.status !== 'ACTIVE') add('PROJECT_INACTIVE', 'info', '비활성 프로젝트입니다.', { nodeId: `project:${projectId}` })
  }

  // --- service-level warnings ---
  const enabledBound = L.targets.filter((t) => t.enabled)
  if (s.enabled && enabledBound.length === 0) add('NO_ENABLED_TARGETS', 'warning', '활성 Target이 없어 이 서비스 요청은 모두 실패합니다.', { nodeId: 'router' })
  if (s.guardrailAction === 'LOCAL_ONLY') {
    const hasLocal = enabledBound.some((t) => {
      const dep = catalog.deployments.find((d) => d.id === t.deploymentId)
      return !!dep && !dep.external
    })
    if (!hasLocal) add('GUARDRAIL_LOCAL_ONLY_WITHOUT_LOCAL_TARGET', 'warning', '민감정보 요청을 처리할 사내 Local Target이 없습니다.', { nodeId: 'guardrail', field: 'guardrailAction' })
  }
  if (!s.enabled) add('SERVICE_DISABLED', 'info', '서비스가 비활성 상태라 모든 요청이 거부됩니다.', { nodeId: 'service', field: 'enabled' })
  if (base.unavailable.length > 0) add('CATALOG_PARTIAL', 'info', '일부 자원 정보를 불러오지 못했습니다.')

  return issues
}

export function issueCountsByNode(issues: ValidationIssue[]): Record<string, { error: number; warning: number; info: number }> {
  const counts: Record<string, { error: number; warning: number; info: number }> = {}
  for (const issue of issues) {
    if (!issue.nodeId) continue
    const entry = counts[issue.nodeId] ?? (counts[issue.nodeId] = { error: 0, warning: 0, info: 0 })
    entry[issue.severity] += 1
  }
  return counts
}
