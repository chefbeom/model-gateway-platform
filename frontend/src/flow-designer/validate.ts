import {
  BOUNDARY_RANK,
  GUARDRAIL_ACTION_SHORT,
  GUARDRAIL_STRICTNESS,
  LIMITS,
  SERVICE_KEY_PATTERN,
  isProtectedGuardrail,
  policyNodeId,
  projectNodeId,
  projectSignals,
  targetKeyOf,
} from './catalog'
import { compile, normalizePrompt } from './compile'
import { analyzeWorkflow, findCycle, isBranchPrompt } from './workflow'
import type { FlowGraph, FlowNode, IssueFix, IssueSeverity, ServiceFlowSnapshot, ValidationIssue } from './types'

type Extra = { nodeId?: string | null; edgeId?: string | null; field?: string | null; fix?: IssueFix }

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

export function validateGraph(graph: FlowGraph, base: ServiceFlowSnapshot): ValidationIssue[] {
  const issues: ValidationIssue[] = []
  const add = (code: string, severity: IssueSeverity, message: string, extra: Extra = {}) => {
    const issue: ValidationIssue = { code, severity, message, nodeId: extra.nodeId ?? null, edgeId: extra.edgeId ?? null, field: extra.field ?? null }
    if (extra.fix) issue.fix = extra.fix
    issues.push(issue)
  }

  if (graph.nodes.length > LIMITS.maxNodes || graph.edges.length > LIMITS.maxEdges) {
    add('GRAPH_TOO_LARGE', 'error', '워크플로우는 노드 300개, 간선 600개까지 지원합니다.')
  }
  const nodesById = new Map<string, FlowNode>(graph.nodes.map((n) => [n.id, n]))
  if (!nodesById.has('start')) {
    add('WF_START_MISSING', 'error', '시작 노드가 없습니다. 되돌리기 또는 새로고침하세요.')
    return issues
  }
  const cycleNode = findCycle(graph)
  if (cycleNode) add('CYCLE_DETECTED', 'error', '순환 간선은 만들 수 없습니다.', { nodeId: cycleNode })

  const analysis = analyzeWorkflow(graph, base)
  issues.push(...analysis.issues)

  const L = compile(graph, base)
  const s = L.service
  const catalog = base.catalog
  const isNew = graph.serviceId === null
  const llmNodes = graph.nodes.filter((n) => n.type === 'llm')
  const depOf = (id: string) => catalog.deployments.find((d) => d.id === id)

  // --- start (service identity) ---
  if (isNew) {
    if (!SERVICE_KEY_PATTERN.test(s.serviceKey)) {
      add('SERVICE_KEY_INVALID', 'error', '호출 이름은 소문자·숫자·하이픈만 쓸 수 있고 영숫자로 시작해야 합니다(최대 120자).', { nodeId: 'start', field: 'serviceKey' })
    } else if (catalog.services.some((x) => x.serviceKey === s.serviceKey)) {
      add('SERVICE_KEY_TAKEN', 'error', '이 조직에 이미 있는 호출 이름입니다.', { nodeId: 'start', field: 'serviceKey' })
    }
  }
  if (s.displayName.trim() === '') add('DISPLAY_NAME_REQUIRED', 'error', '표시 이름을 입력하세요.', { nodeId: 'start', field: 'displayName' })
  else if (s.displayName.length > LIMITS.displayNameMaxLength) add('DISPLAY_NAME_TOO_LONG', 'error', '표시 이름은 200자 이하여야 합니다.', { nodeId: 'start', field: 'displayName' })
  if (!capabilitiesValid(s.requiredCapabilitiesJson)) add('CAPABILITIES_INVALID', 'error', '필수 Capability 형식이 올바르지 않습니다.', { nodeId: 'start', field: 'requiredCapabilitiesJson' })
  for (const field of ['inputPricePerMillion', 'outputPricePerMillion'] as const) {
    const v = s[field]
    if (!Number.isFinite(v) || v < 0) add('PRICE_INVALID', 'error', '단가는 0 이상의 숫자여야 합니다.', { nodeId: 'start', field })
  }

  // --- generation parameters ---
  if (nodesById.has('params') && s.temperaturePolicy === 'FIXED') {
    if (s.temperatureValue === null) add('TEMPERATURE_VALUE_REQUIRED', 'error', '고정 Temperature 값을 입력하세요.', { nodeId: 'params', field: 'temperatureValue' })
    else if (!Number.isFinite(s.temperatureValue) || s.temperatureValue < 0 || s.temperatureValue > 2) add('TEMPERATURE_VALUE_RANGE', 'error', 'Temperature는 0에서 2 사이여야 합니다.', { nodeId: 'params', field: 'temperatureValue' })
  }

  // --- prompts ---
  if (s.systemPromptTemplate !== null && s.systemPromptTemplate.length > LIMITS.promptMaxLength) {
    add('PROMPT_TOO_LONG', 'error', '시스템 프롬프트는 16,000자 이하여야 합니다.', { nodeId: 'prompt', field: 'systemPromptTemplate' })
  }
  for (const node of graph.nodes) {
    if (!isBranchPrompt(node)) continue
    const content = normalizePrompt(node.props.systemPromptTemplate as string | null | undefined)
    if (content !== null && content.length > LIMITS.promptMaxLength) {
      add('PROMPT_TOO_LONG', 'error', '시스템 프롬프트는 16,000자 이하여야 합니다.', { nodeId: node.id, field: 'systemPromptTemplate' })
    }
    if (content !== null && base.features?.targetPrompt === false) {
      add('TARGET_PROMPT_UNSUPPORTED', 'error', '서버가 모델별 시스템 프롬프트를 지원하지 않습니다(백엔드 업데이트 필요). 공통 시스템 프롬프트를 사용하세요.', { nodeId: node.id })
    }
  }

  // --- cache ---
  if (nodesById.has('cache')) {
    const ttl = s.responseCacheTtlSeconds
    if (!isInt(ttl) || ttl < 10 || ttl > 86400) add('CACHE_TTL_RANGE', 'error', '캐시 TTL은 10–86,400초 사이 정수여야 합니다.', { nodeId: 'cache', field: 'responseCacheTtlSeconds' })
  }

  // --- scheduling (router) ---
  if (nodesById.has('router')) {
    const c = L.scheduling
    const ranges: Array<[keyof typeof c, number, number]> = [
      ['localWaitMs', 0, 60000],
      ['maxWaitMs', 100, 60000],
      ['maxQueuedRequests', 1, 500],
      ['maxQueuedPerKey', 1, 500],
      ['maxQueuedBytes', 1024, 134217728],
    ]
    for (const [field, min, max] of ranges) {
      const v = c[field] as number
      if (!isInt(v) || v < min || v > max) add('SCHEDULING_RANGE', 'error', '배분·대기열 값이 허용 범위를 벗어났습니다.', { nodeId: 'router', field })
    }
    if (c.mode === 'LOCAL_FIRST_WAIT' && c.localWaitMs > c.maxWaitMs) {
      add('SCHEDULING_LOCAL_WAIT_EXCEEDS_MAX', 'error', '로컬 대기 시간은 전체 최대 대기 시간을 넘을 수 없습니다.', { nodeId: 'router', field: 'localWaitMs' })
    }
    if (c.maxQueuedPerKey > c.maxQueuedRequests) {
      add('SCHEDULING_PER_KEY_EXCEEDS_TOTAL', 'error', 'API 키별 대기 한도는 서비스 전체 한도를 넘을 수 없습니다.', { nodeId: 'router', field: 'maxQueuedPerKey' })
    }
  }

  // --- LLM nodes ---
  const baseTargets = new Map(base.config.targets.map((t) => [t.key, t]))
  const compiled = new Map(L.targets.map((t) => [t.key, t]))
  for (const node of llmNodes) {
    const t = compiled.get(targetKeyOf(node))
    if (!t) continue
    const positive = (v: number) => isInt(v) && v >= 1
    if (!positive(t.priority) || !positive(t.weight) || (t.maxConcurrencyOverride !== null && !positive(t.maxConcurrencyOverride))) {
      add('TARGET_NUMBER_INVALID', 'error', 'LLM 우선순위·가중치·동시성은 1 이상의 정수여야 합니다.', { nodeId: node.id })
    }
    const before = baseTargets.get(t.key)
    if (before && before.maxConcurrencyOverride !== null && t.maxConcurrencyOverride === null) {
      add('TARGET_CONCURRENCY_CLEAR_UNSUPPORTED', 'error', '서버가 동시성 재정의 해제를 지원하지 않습니다. LLM을 삭제 후 다시 추가하세요.', { nodeId: node.id, field: 'maxConcurrencyOverride' })
    }
    const dep = depOf(t.deploymentId)
    if (!dep) {
      const unchanged = !!before && before.deploymentId === t.deploymentId
      add('UNKNOWN_DEPLOYMENT', unchanged ? 'warning' : 'error', '등록 정보를 찾을 수 없는 모델입니다.', { nodeId: node.id })
      continue
    }
    if (!dep.enabled || String(dep.healthStatus).toUpperCase() === 'UNHEALTHY' || (!dep.external && !dep.loaded)) {
      add('DEPLOYMENT_UNAVAILABLE', 'warning', '현재 사용할 수 없는 모델입니다(LLM은 유지됩니다).', { nodeId: node.id })
    }
    if (dep.external && s.dataBoundaryPolicy !== 'EXTERNAL_ALLOWED') {
      add('EXTERNAL_TARGET_BLOCKED_BY_BOUNDARY', 'warning', '데이터 경계 정책 때문에 이 외부 LLM은 실행되지 않습니다.', { nodeId: node.id })
    }
    if (dep.external && t.followModelChanges) {
      add('FOLLOW_MODEL_ON_EXTERNAL', 'info', '외부 모델에는 모델 자동 추적이 적용되지 않습니다.', { nodeId: node.id, field: 'followModelChanges' })
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
      add('MASKER_NOT_SELECTED', 'error', '마스킹 워커 모델을 선택하세요.', { nodeId: 'masker', field: 'deploymentId' })
    } else if (base.unavailable.some((path) => path.includes('masker-deployments'))) {
      add('MASKER_UNVERIFIED', 'warning', '마스킹 워커 자격을 확인하지 못했습니다.', { nodeId: 'masker', field: 'deploymentId' })
    } else if (!catalog.maskers.some((m) => m.deploymentId === id && m.eligible)) {
      add('MASKER_NOT_ELIGIBLE', 'error', '신뢰 마스킹 워커로 사용할 수 없는 모델입니다.', { nodeId: 'masker', field: 'deploymentId' })
    }
  }

  // --- connections (connection-centric view: a connection placed before its models) ---
  for (const node of graph.nodes) {
    if ((node.type !== 'endpoint' && node.type !== 'provider') || node.props.pinned !== true) continue
    if (graph.edges.some((e) => e.kind === 'host' && e.to.nodeId === node.id)) continue
    add('CONNECTION_EMPTY', 'warning', '모델이 없는 연결입니다. 연결 노드에서 모델을 추가하거나 연결을 삭제하세요. 이 노드만으로는 서버 설정이 바뀌지 않습니다.', {
      nodeId: node.id,
      fix: { label: '연결 삭제', command: { type: 'removeItems', nodeIds: [node.id], edgeIds: [], withConnectionModels: true } },
    })
  }

  // --- access ---
  const before = new Set(base.config.accessProjectIds)
  const after = new Set(L.accessProjectIds)
  const added = L.accessProjectIds.filter((id) => !before.has(id))
  const removed = base.config.accessProjectIds.filter((id) => !after.has(id))
  for (const projectId of added) {
    if (!catalog.projects.some((p) => p.id === projectId)) add('UNKNOWN_PROJECT', 'error', '알 수 없는 프로젝트입니다.', { nodeId: projectNodeId(projectId) })
  }
  if ((added.length > 0 || removed.length > 0) && !base.accessComplete) {
    add('ACCESS_EDIT_UNAVAILABLE', 'error', '프로젝트 접근 정보를 모두 불러오지 못해 접근 변경을 적용할 수 없습니다.')
  }
  for (const projectId of L.accessProjectIds) {
    const info = catalog.projects.find((p) => p.id === projectId)
    if (info && info.status !== 'ACTIVE') add('PROJECT_INACTIVE', 'info', '비활성 프로젝트입니다.', { nodeId: projectNodeId(projectId) })
  }

  // --- guardrail protection (§6.9 E1, E4, E6) ---
  const guardrail = nodesById.get('guardrail')
  if (isProtectedGuardrail(base)) {
    const baseService = base.config.service
    if (!guardrail) {
      add('GUARDRAIL_REMOVED', 'warning', "외부 노출 방지 가드레일을 제거했습니다. 적용하면 데이터 경계는 '외부 LLM 허용', 민감정보 처리는 '허용'으로 바뀝니다.", { nodeId: 'start' })
    } else if (
      BOUNDARY_RANK[s.dataBoundaryPolicy] > BOUNDARY_RANK[baseService.dataBoundaryPolicy] ||
      GUARDRAIL_STRICTNESS[s.guardrailAction] < GUARDRAIL_STRICTNESS[baseService.guardrailAction]
    ) {
      add('GUARDRAIL_RELAXED', 'warning', '서버에 적용된 외부 노출 방지 정책을 완화합니다. 적용할 때 위험 작업 확인이 필요합니다.', { nodeId: 'guardrail' })
    }
  }
  const enabledLlms = llmNodes.filter((n) => n.props.enabled !== false)
  const externalOf = (n: FlowNode): boolean => depOf(n.refId ?? '')?.external === true
  if (!guardrail) {
    for (const node of llmNodes) {
      if (externalOf(node)) {
        add('EXTERNAL_LLM_WITHOUT_GUARDRAIL', 'info', '외부 LLM으로 나가는 요청을 보호할 가드레일이 없습니다.', {
          nodeId: node.id,
          fix: { label: '가드레일 추가', command: { type: 'addNode', nodeType: 'guardrail' } },
        })
      }
    }
  }

  // --- inherited project policies (§6.9 E3) ---
  const enabledLocal = enabledLlms.filter((n) => !externalOf(n))
  const enabledExternal = enabledLlms.filter((n) => externalOf(n))
  for (const projectId of L.accessProjectIds) {
    const info = catalog.projects.find((p) => p.id === projectId)
    const signals = projectSignals(info)
    if (!info || !signals.some((x) => x.blocksExternal)) continue
    if (enabledLocal.length === 0) {
      add('POLICY_NO_LOCAL_LLM', 'warning', `${info.name}의 보호 정책 때문에 외부 LLM을 쓸 수 없는데 사용 중인 로컬 LLM이 없습니다. 이 프로젝트의 요청은 모두 실패합니다.`, { nodeId: policyNodeId(projectId) })
    }
    if (enabledExternal.length > 0) {
      const names = enabledExternal.slice(0, 3).map((n) => depOf(n.refId ?? '')?.displayName ?? '알 수 없는 모델').join(', ')
      add('POLICY_SKIPS_EXTERNAL', 'info', `${info.name}의 요청은 외부 LLM(${names})을 건너뜁니다.`, { nodeId: policyNodeId(projectId) })
    }
  }
  const org = base.orgDataProtection
  if (org && org.mode === 'ENFORCE' && org.externalAction !== 'ALLOW' && llmNodes.some(externalOf)) {
    add('ORG_POLICY_APPLIES', 'info', `조직 데이터 보호 정책(민감정보: ${GUARDRAIL_ACTION_SHORT[org.externalAction] ?? org.externalAction})이 이 워크플로우보다 먼저 적용됩니다.`, { nodeId: 'start' })
  }
  if (base.projectPoliciesComplete === false) {
    add('POLICY_UNVERIFIED', 'info', '프로젝트 데이터 보호 정책을 모두 불러오지 못해 일부 보호 정책 노드가 빠졌을 수 있습니다.')
  }
  if (base.projectsHidden === true) {
    add('PROJECTS_HIDDEN', 'info', '비상 토큰 세션에서는 프로젝트가 보이지 않아 프로젝트 노드와 접근 변경을 쓸 수 없습니다. 사용자 계정으로 로그인하세요.', { nodeId: 'start' })
  }

  // --- service-level warnings ---
  if (llmNodes.length === 0) add('WF_NO_LLM', 'warning', 'LLM 노드가 없어 이 워크플로우의 요청은 모두 실패합니다.', { nodeId: 'start' })
  if (s.enabled && L.accessProjectIds.length === 0 && base.accessComplete && base.projectsHidden !== true) {
    add('WF_NO_PROJECT', 'warning', '호출을 허용한 프로젝트가 없어 어떤 앱도 이 워크플로우를 쓸 수 없습니다.', { nodeId: 'start' })
  }
  const enabledTargets = L.targets.filter((t) => t.enabled)
  if (s.enabled && llmNodes.length >= 1 && enabledTargets.length === 0) {
    add('NO_ENABLED_TARGETS', 'warning', '사용 중인 LLM이 없어 이 워크플로우의 요청은 모두 실패합니다.', { nodeId: nodesById.has('router') ? 'router' : 'start' })
  }
  if (s.guardrailAction === 'LOCAL_ONLY' && !enabledLocal.length) {
    add('GUARDRAIL_LOCAL_ONLY_WITHOUT_LOCAL_TARGET', 'warning', '민감정보 요청을 처리할 사내 로컬 LLM이 없습니다.', { nodeId: 'guardrail', field: 'guardrailAction' })
  }
  if (!s.enabled) add('SERVICE_DISABLED', 'info', '워크플로우가 사용 중지 상태라 모든 요청이 거부됩니다.', { nodeId: 'start', field: 'enabled' })
  if (base.unavailable.length > 0) add('CATALOG_PARTIAL', 'info', '일부 자원 정보를 불러오지 못했습니다.')

  // --- nodes that compile to nothing (§5.5) ---
  const noEffect = (id: string) => add('WF_NODE_NO_EFFECT', 'info', '기본값만 설정되어 적용해도 서버 설정이 바뀌지 않는 노드입니다. 다시 불러오면 표시되지 않습니다.', { nodeId: id })
  if (guardrail && s.dataBoundaryPolicy === 'EXTERNAL_ALLOWED' && s.guardrailAction === 'ALLOW' && s.maskerDeploymentId === null) noEffect('guardrail')
  const common = nodesById.get('prompt')
  if (common && s.systemPromptTemplate === null) noEffect('prompt')
  for (const node of graph.nodes) {
    if (isBranchPrompt(node) && normalizePrompt(node.props.systemPromptTemplate as string | null | undefined) === null) noEffect(node.id)
  }
  if (nodesById.has('params') && s.temperaturePolicy === 'REQUEST' && s.reasoningEffort === 'REQUEST' && !s.openAiFastMode) noEffect('params')
  if (nodesById.has('router') && L.targets.length < 2 && s.failoverPolicy === 'STRICT' && s.retryPolicy === 'SAFE' && !s.allowDegraded && L.scheduling.mode === 'IMMEDIATE') noEffect('router')

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
