import { NODE_TYPES, PROMPT_BRANCH_RANK, branchPromptNodeId, llmNodeId } from './catalog'
import type {
  AddableNodeType,
  FlowEdge,
  FlowGraph,
  FlowNode,
  NodeType,
  PromptScope,
  ServiceFlowSnapshot,
  ValidationIssue,
  WorkflowAnalysis,
  WorkflowBranch,
} from './types'

/** Node types that take part in the request path (the "flow" nodes). */
export const FLOW_NODE_TYPES: ReadonlySet<NodeType> = new Set<NodeType>(['guardrail', 'prompt', 'params', 'cache', 'router', 'llm'])

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }

export function isBranchPrompt(node: FlowNode): boolean {
  return node.type === 'prompt' && node.id !== 'prompt'
}

export function rankOf(node: FlowNode): number {
  return isBranchPrompt(node) ? PROMPT_BRANCH_RANK : NODE_TYPES[node.type].rank
}

export function isEmptyWorkflow(graph: FlowGraph): boolean {
  return !graph.nodes.some((n) => FLOW_NODE_TYPES.has(n.type))
}

function issue(code: string, message: string, nodeId: string | null, fix?: ValidationIssue['fix']): ValidationIssue {
  const out: ValidationIssue = { code, severity: code === 'WF_ORDER_NONCANONICAL' ? 'warning' : 'error', message, nodeId, edgeId: null, field: null }
  if (fix) out.fix = fix
  return out
}

export const WF_MESSAGES = {
  MULTIPLE_ROOTS: '처리 경로가 여러 갈래로 끊겨 있습니다. 하나의 경로로 연결하세요.',
  NODE_DETACHED: '요청 경로에 연결되지 않은 노드가 있습니다. 연결하거나 삭제하세요.',
  LLM_DETACHED: '요청 경로에 연결되지 않은 LLM이 있습니다. 연결하거나 삭제하세요.',
  ROUTER_REQUIRED: 'LLM을 두 개 이상 쓰려면 라우터 노드가 필요합니다.',
  STAGE_AFTER_ROUTER: '가드레일·공통 시스템 프롬프트·응답 캐시·생성 파라미터는 라우터 앞(모든 LLM의 공통 경로)에만 둘 수 있습니다.',
  BRANCH_PROMPT_MISPLACED: '모델별 시스템 프롬프트는 해당 LLM 바로 앞에만 둘 수 있습니다.',
  ORDER_NONCANONICAL: '화면의 순서와 서버 처리 순서(가드레일 → 공통 시스템 프롬프트 → 응답 캐시 → 생성 파라미터 → 라우터)가 다릅니다. 서버는 항상 이 순서로 처리합니다.',
} as const

/**
 * §6.3: the single structural analysis shared by layout, wiring, validation and derived-node sync.
 * `snapshot` may be omitted by callers that only need the structure (every LLM is then treated as local).
 */
export function analyzeWorkflow(graph: FlowGraph, snapshot?: ServiceFlowSnapshot | null): WorkflowAnalysis {
  const flowNodes = new Map<string, FlowNode>()
  for (const n of graph.nodes) if (FLOW_NODE_TYPES.has(n.type)) flowNodes.set(n.id, n)

  const outMap = new Map<string, string[]>()
  const inMap = new Map<string, string[]>()
  const edges = graph.edges
    .filter((e) => (e.kind === 'flow' || e.kind === 'route') && flowNodes.has(e.from.nodeId) && flowNodes.has(e.to.nodeId))
    .sort((a, b) => cmp(a.id, b.id))
  for (const e of edges) {
    const o = outMap.get(e.from.nodeId)
    if (o) { if (!o.includes(e.to.nodeId)) o.push(e.to.nodeId) } else outMap.set(e.from.nodeId, [e.to.nodeId])
    const i = inMap.get(e.to.nodeId)
    if (i) { if (!i.includes(e.from.nodeId)) i.push(e.from.nodeId) } else inMap.set(e.to.nodeId, [e.from.nodeId])
  }
  const outs = (id: string): string[] => outMap.get(id) ?? []
  const ins = (id: string): string[] => inMap.get(id) ?? []

  const reach = (from: string): Set<string> => {
    const seen = new Set<string>([from])
    const stack = [from]
    while (stack.length > 0) {
      const cur = stack.pop() as string
      for (const next of outs(cur)) if (!seen.has(next)) { seen.add(next); stack.push(next) }
    }
    return seen
  }
  const llmCount = (set: Set<string>): number => {
    let n = 0
    for (const id of set) if (flowNodes.get(id)?.type === 'llm') n += 1
    return n
  }

  const issues: ValidationIssue[] = []
  const seenIssues = new Set<string>()
  const add = (code: string, message: string, nodeId: string | null, fix?: ValidationIssue['fix']) => {
    const key = `${code}|${nodeId ?? ''}`
    if (seenIssues.has(key)) return
    seenIssues.add(key)
    issues.push(issue(code, message, nodeId, fix))
  }
  const routerFix = { label: '라우터 추가', command: { type: 'addNode' as const, nodeType: 'router' as AddableNodeType } }

  // 1-2. roots and head
  const roots = [...flowNodes.values()].filter((n) => ins(n.id).length === 0)
  let head: string | null = null
  const rootReach = new Map<string, Set<string>>()
  if (roots.length > 0) {
    for (const r of roots) rootReach.set(r.id, reach(r.id))
    const sorted = [...roots].sort((a, b) =>
      llmCount(rootReach.get(b.id) as Set<string>) - llmCount(rootReach.get(a.id) as Set<string>) ||
      rankOf(a) - rankOf(b) ||
      cmp(a.id, b.id))
    head = sorted[0].id
    for (const r of sorted.slice(1)) add('WF_MULTIPLE_ROOTS', WF_MESSAGES.MULTIPLE_ROOTS, r.id)
  }

  // 3. reachable / detached
  const reachable = head ? (rootReach.get(head) as Set<string>) : new Set<string>()
  const detached = [...flowNodes.keys()].filter((id) => !reachable.has(id)).sort(cmp)

  // 4. trunk
  const trunk: string[] = []
  let router: string | null = null
  const visited = new Set<string>()
  let cur: string | null = head
  while (cur && !visited.has(cur)) {
    visited.add(cur)
    const node = flowNodes.get(cur) as FlowNode
    if (node.type === 'llm' || isBranchPrompt(node)) break
    trunk.push(cur)
    if (node.type === 'router') { router = cur; break }
    const next = outs(cur)
    if (next.length === 1) cur = next[0]
    else {
      if (next.length > 1) add('WF_ROUTER_REQUIRED', WF_MESSAGES.ROUTER_REQUIRED, cur, routerFix)
      break
    }
  }
  const tail = trunk.length > 0 ? trunk[trunk.length - 1] : null

  // 5. branches
  const branches: WorkflowBranch[] = []
  const branchOf = (target: string): WorkflowBranch | null => {
    const node = flowNodes.get(target)
    if (!node) return null
    if (node.type === 'llm') return { llmNodeId: node.id, promptNodeId: null }
    if (isBranchPrompt(node)) {
      const llm = llmNodeId(node.id.slice('prompt:'.length))
      const o = outs(node.id)
      if (o.length === 1 && o[0] === llm && flowNodes.get(llm)?.type === 'llm') return { llmNodeId: llm, promptNodeId: node.id }
      return null
    }
    return null
  }
  if (router) {
    for (const target of outs(router)) {
      const node = flowNodes.get(target)
      const b = branchOf(target)
      if (b) branches.push(b)
      else if (node && isBranchPrompt(node)) add('WF_BRANCH_PROMPT_MISPLACED', WF_MESSAGES.BRANCH_PROMPT_MISPLACED, target)
      else add('WF_STAGE_AFTER_ROUTER', WF_MESSAGES.STAGE_AFTER_ROUTER, target)
    }
  } else if (head) {
    const headNode = flowNodes.get(head) as FlowNode
    if (headNode.type === 'llm' || isBranchPrompt(headNode)) {
      const b = branchOf(head)
      if (b) branches.push(b)
    } else if (tail) {
      const o = outs(tail)
      if (o.length === 1) {
        const b = branchOf(o[0])
        if (b) branches.push(b)
      }
    }
  }

  // 6. additional checks
  if (router) {
    const after = reach(router)
    after.delete(router)
    for (const id of [...after].sort(cmp)) {
      const node = flowNodes.get(id) as FlowNode
      if (node.type !== 'llm' && !isBranchPrompt(node)) add('WF_STAGE_AFTER_ROUTER', WF_MESSAGES.STAGE_AFTER_ROUTER, id)
    }
  }
  for (const id of [...reachable].sort(cmp)) {
    const node = flowNodes.get(id) as FlowNode
    if (node.id === 'prompt' && !trunk.includes(id)) add('WF_STAGE_AFTER_ROUTER', WF_MESSAGES.STAGE_AFTER_ROUTER, id)
    if (!isBranchPrompt(node)) continue
    const llm = llmNodeId(node.id.slice('prompt:'.length))
    const o = outs(id)
    const outOk = o.length === 1 && o[0] === llm
    const inputs = ins(id)
    const inOk = id === head || (tail !== null && inputs.length > 0 && inputs.every((s) => s === tail))
    if (!outOk || !inOk) add('WF_BRANCH_PROMPT_MISPLACED', WF_MESSAGES.BRANCH_PROMPT_MISPLACED, id)
  }
  if (!router && llmCount(reachable) >= 2 && !issues.some((x) => x.code === 'WF_ROUTER_REQUIRED')) {
    add('WF_ROUTER_REQUIRED', WF_MESSAGES.ROUTER_REQUIRED, tail ?? head, routerFix)
  }
  const branched = new Set(branches.map((b) => b.llmNodeId))
  for (const id of [...reachable].sort(cmp)) {
    if (flowNodes.get(id)?.type === 'llm' && !branched.has(id)) add('WF_LLM_DETACHED', WF_MESSAGES.LLM_DETACHED, id)
  }

  // 7. order check
  const ordered = trunk.filter((id) => id !== router)
  for (let i = 1; i < ordered.length; i += 1) {
    const prev = flowNodes.get(ordered[i - 1]) as FlowNode
    const here = flowNodes.get(ordered[i]) as FlowNode
    if (rankOf(here) < rankOf(prev)) {
      add('WF_ORDER_NONCANONICAL', WF_MESSAGES.ORDER_NONCANONICAL, here.id, { label: '순서 정리', command: { type: 'normalizeOrder' } })
      break
    }
  }

  // 8. dispatch order of branches
  const isExternal = (llmId: string): number => {
    const node = flowNodes.get(llmId) as FlowNode
    const dep = snapshot?.catalog.deployments.find((d) => d.id === node.refId)
    return dep?.external === true ? 1 : 0
  }
  const num = (v: unknown, fallback: number): number => {
    const n = typeof v === 'number' ? v : Number(v)
    return Number.isFinite(n) ? n : fallback
  }
  branches.sort((a, b) => {
    const na = flowNodes.get(a.llmNodeId) as FlowNode
    const nb = flowNodes.get(b.llmNodeId) as FlowNode
    return isExternal(a.llmNodeId) - isExternal(b.llmNodeId) ||
      num(na.props.priority, Infinity) - num(nb.props.priority, Infinity) ||
      num(nb.props.weight, 0) - num(na.props.weight, 0) ||
      cmp(a.llmNodeId, b.llmNodeId)
  })

  // 9. detached nodes
  for (const id of detached) {
    if (flowNodes.get(id)?.type === 'llm') add('WF_LLM_DETACHED', WF_MESSAGES.LLM_DETACHED, id)
    else add('WF_NODE_DETACHED', WF_MESSAGES.NODE_DETACHED, id)
  }

  return { head, trunk, router, tail, branches, detached, issues }
}

/* ------------------------------------------------------------------ */
/* Dependencies (§5.6)                                                 */
/* ------------------------------------------------------------------ */

/** Direct dependents of a node: nodes that exist only because of it. */
export function dependentsOf(graph: FlowGraph, nodeId: string): string[] {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return []
  const out = new Set<string>()
  const has = (id: string) => graph.nodes.some((n) => n.id === id)
  if (node.type === 'guardrail') {
    if (has('masker')) out.add('masker')
  } else if (node.type === 'llm') {
    const deploymentId = node.refId ?? (node.id.startsWith('deployment:') ? node.id.slice('deployment:'.length) : null)
    if (deploymentId && has(branchPromptNodeId(deploymentId))) out.add(branchPromptNodeId(deploymentId))
    for (const e of graph.edges) {
      if (e.kind !== 'host' || e.from.nodeId !== nodeId) continue
      const users = graph.edges.filter((x) => x.kind === 'host' && x.to.nodeId === e.to.nodeId).length
      if (users === 1 && has(e.to.nodeId)) out.add(e.to.nodeId)
    }
  } else if (node.type === 'project') {
    const policy = node.refId ? `policy:${node.refId}` : null
    if (policy && has(policy)) out.add(policy)
  }
  return [...out].sort(cmp)
}

export function dependencyClosure(graph: FlowGraph, nodeIds: string[]): string[] {
  const seen = new Set<string>(nodeIds)
  const stack = [...nodeIds]
  while (stack.length > 0) {
    const cur = stack.pop() as string
    for (const dep of dependentsOf(graph, cur)) {
      if (!seen.has(dep)) { seen.add(dep); stack.push(dep) }
    }
  }
  return [...seen].sort(cmp)
}

/* ------------------------------------------------------------------ */
/* Splice (§6.6)                                                       */
/* ------------------------------------------------------------------ */

/** Target node of the edge a prompt would be spliced into decides the scope. */
export function promptScopeForSplice(graph: FlowGraph, edgeId: string): PromptScope {
  const edge = graph.edges.find((e) => e.id === edgeId)
  const target = edge ? graph.nodes.find((n) => n.id === edge.to.nodeId) : undefined
  return target && target.type === 'llm' ? 'model' : 'common'
}

const TRUNK_INSERT_ORDER: AddableNodeType[] = ['guardrail', 'prompt', 'cache', 'params', 'router']

export function insertableTypesForEdge(graph: FlowGraph, edgeId: string, snapshot: ServiceFlowSnapshot): AddableNodeType[] {
  const edge = graph.edges.find((e) => e.id === edgeId)
  if (!edge) return []
  const to = graph.nodes.find((n) => n.id === edge.to.nodeId)
  if (!to) return []
  const has = (id: string) => graph.nodes.some((n) => n.id === id)
  const targetPromptOk = snapshot.features?.targetPrompt !== false
  const modelPromptFor = (llm: FlowNode): boolean => {
    const deploymentId = llm.refId ?? (llm.id.startsWith('deployment:') ? llm.id.slice('deployment:'.length) : null)
    return !!deploymentId && targetPromptOk && !has(branchPromptNodeId(deploymentId))
  }

  if (edge.kind === 'route') {
    if (to.type === 'llm' && graph.nodes.find((n) => n.id === edge.from.nodeId)?.type === 'router' && modelPromptFor(to)) return ['prompt']
    return []
  }
  if (edge.kind !== 'access' && edge.kind !== 'flow') return []

  const result: AddableNodeType[] = []
  for (const type of TRUNK_INSERT_ORDER) {
    if (type === 'prompt') {
      if (to.type === 'llm') { if (modelPromptFor(to)) result.push('prompt') }
      else if (!has('prompt')) result.push('prompt')
    } else if (type === 'router') {
      if (!has('router') && (to.type === 'llm' || isBranchPrompt(to))) result.push('router')
    } else if (!has(type)) {
      result.push(type)
    }
  }
  return result
}

/* ------------------------------------------------------------------ */
/* Cycle detection (moved from the v1 validator)                       */
/* ------------------------------------------------------------------ */

export function findCycle(graph: FlowGraph): string | null {
  const adjacency = new Map<string, string[]>()
  for (const e of graph.edges as FlowEdge[]) {
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
