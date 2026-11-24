import {
  DEFAULT_CONFIG,
  SINGLETON_IDS,
  branchPromptNodeId,
  edgeId,
  llmNodeId,
  llmPropsFromTarget,
  nodePropsFromConfig,
  presenceOf,
  projectNodeId,
} from './catalog'
import { applyLayout, autoLayout, layoutGeneration } from './layout'
import { addFlowNode, createSkeletonGraph, makeEdge, makeNode, removeFlowNodes, sortGraph, syncDerived } from './graphOps'
import { normalizePrompt } from './compile'
import type { FlowConfigState, FlowEdge, FlowGraph, FlowLayout, FlowNode, NodeType, ServiceFlowSnapshot, TargetState } from './types'

const TRUNK_ORDER = ['guardrail', 'prompt', 'cache', 'params', 'router'] as const

const entryId = (projectNode: string): string => edgeId({ nodeId: 'start', portId: 'out' }, { nodeId: projectNode, portId: 'in' })

/**
 * §7.5: rewrites the graph so that compile(result) equals `config`, while keeping coordinates, the trunk order
 * the user built and project nodes that are not (yet) granted.
 */
export function syncGraphToConfig(graph: FlowGraph, config: FlowConfigState, snapshot: ServiceFlowSnapshot): FlowGraph {
  let g: FlowGraph = { ...graph, nodes: graph.nodes.map((n) => ({ ...n, props: { ...n.props } })), edges: graph.edges.map((e) => ({ ...e, from: { ...e.from }, to: { ...e.to } })) }
  const props = nodePropsFromConfig(config)
  const present = presenceOf(config)
  const has = (id: string) => g.nodes.some((n) => n.id === id)
  const patchNode = (id: string, patch: (n: FlowNode) => FlowNode) => { g = { ...g, nodes: g.nodes.map((n) => (n.id === id ? patch(n) : n)) } }

  // 1. start
  if (!has('start')) g = { ...g, nodes: [...g.nodes, makeNode('start', 'start', null, props.start)] }
  else patchNode('start', (n) => ({ ...n, props: { ...props.start } }))

  // 2. LLM nodes (first, so that routers and prompts find their targets).
  const matched = new Set<string>()
  const wantedPrompts = new Map<string, string>()
  for (const t of config.targets) {
    const wantedId = llmNodeId(t.deploymentId)
    let node = (t.id !== null ? g.nodes.find((n) => n.type === 'llm' && n.targetId === t.id) : undefined) ?? g.nodes.find((n) => n.id === wantedId)
    if (node && node.id !== wantedId && !has(wantedId)) {
      // Remote model tracking re-bound the target to another deployment: rename the node and its prompt.
      const oldId = node.id
      const oldDeployment = node.refId ?? oldId.slice('deployment:'.length)
      const oldPrompt = branchPromptNodeId(oldDeployment)
      const renames = new Map<string, string>([[oldId, wantedId]])
      if (has(oldPrompt)) renames.set(oldPrompt, branchPromptNodeId(t.deploymentId))
      g = {
        ...g,
        nodes: g.nodes.map((n) => {
          const next = renames.get(n.id)
          if (!next) return n
          return n.id === oldId ? { ...n, id: next, refId: t.deploymentId } : { ...n, id: next }
        }),
        edges: g.edges.map((e) => {
          const from = { ...e.from, nodeId: renames.get(e.from.nodeId) ?? e.from.nodeId }
          const to = { ...e.to, nodeId: renames.get(e.to.nodeId) ?? e.to.nodeId }
          return { ...e, from, to, id: edgeId(from, to) }
        }),
      }
      node = g.nodes.find((n) => n.id === wantedId)
    }
    if (!node) {
      g = addFlowNode(g, { type: 'llm', id: wantedId, refId: t.deploymentId, props: llmPropsFromTarget(t), targetId: t.id }, snapshot)
    }
    matched.add(wantedId)
    patchNode(wantedId, (n) => ({ ...n, refId: t.deploymentId, targetId: t.id, props: { ...llmPropsFromTarget(t) } }))
    const prompt = normalizePrompt(t.systemPromptTemplate)
    if (prompt !== null) wantedPrompts.set(t.deploymentId, prompt)
  }
  const stale = g.nodes.filter((n) => n.type === 'llm' && !matched.has(n.id)).map((n) => n.id)
  if (stale.length > 0) {
    const prompts = stale.map((id) => branchPromptNodeId((g.nodes.find((n) => n.id === id) as FlowNode).refId ?? id.slice('deployment:'.length))).filter(has)
    g = removeFlowNodes(g, [...stale, ...prompts], snapshot)
  }

  // 3. model-specific prompts
  for (const t of config.targets) {
    const id = branchPromptNodeId(t.deploymentId)
    const prompt = wantedPrompts.get(t.deploymentId)
    if (prompt !== undefined) {
      if (!has(id)) g = addFlowNode(g, { type: 'prompt', id, props: { systemPromptTemplate: prompt } }, snapshot)
      else patchNode(id, (n) => ({ ...n, props: { systemPromptTemplate: prompt } }))
    } else if (has(id)) {
      g = removeFlowNodes(g, [id], snapshot)
    }
  }

  // 4. optional trunk nodes (router last).
  for (const type of TRUNK_ORDER) {
    const id = SINGLETON_IDS[type]
    const want = present[type]
    if (want && !has(id)) g = addFlowNode(g, { type: type as NodeType, id, props: props[type] }, snapshot)
    else if (want) patchNode(id, (n) => ({ ...n, props: { ...props[type] } }))
    else if (has(id)) g = removeFlowNodes(g, type === 'guardrail' && has('masker') ? [id, 'masker'] : [id], snapshot)
  }

  // 5. masker
  const maskerId = config.service.maskerDeploymentId
  if (maskerId && has('guardrail')) {
    if (!has('masker')) g = addFlowNode(g, { type: 'masker', id: 'masker', props: { deploymentId: maskerId } }, snapshot)
    else patchNode('masker', (n) => ({ ...n, props: { ...n.props, deploymentId: maskerId } }))
    const edge = makeEdge('masker', { nodeId: 'masker', portId: 'masker' }, { nodeId: 'guardrail', portId: 'masker' }, false)
    g = { ...g, edges: [...g.edges.filter((e) => !(e.to.nodeId === 'guardrail' && e.to.portId === 'masker' && e.from.nodeId !== 'masker')), ...(g.edges.some((e) => e.id === edge.id) ? [] : [edge])] }
  } else if (has('masker')) {
    g = { ...g, nodes: g.nodes.filter((n) => n.id !== 'masker'), edges: g.edges.filter((e) => e.from.nodeId !== 'masker' && e.to.nodeId !== 'masker') }
  }

  // 6. access
  const access = new Set(config.accessProjectIds)
  for (const projectId of config.accessProjectIds) {
    const id = projectNodeId(projectId)
    if (!has(id)) g = addFlowNode(g, { type: 'project', id, refId: projectId }, snapshot)
    if (!g.edges.some((e) => e.id === entryId(id))) {
      g = { ...g, edges: [...g.edges, makeEdge('entry', { nodeId: 'start', portId: 'out' }, { nodeId: id, portId: 'in' }, false)] }
    }
  }
  g = {
    ...g,
    edges: g.edges.filter((e) => {
      if (e.kind !== 'entry') return true
      const project = g.nodes.find((n) => n.id === e.to.nodeId)
      return !!project && !!project.refId && access.has(project.refId)
    }),
  }

  return sortGraph(syncDerived({ ...g, serviceId: snapshot.serviceId, schemaVersion: 2 }, snapshot))
}

/** Canonical graph of a server state (§7.4), positioned from a saved layout when it is a v2 layout. */
export function decompile(snapshot: ServiceFlowSnapshot, layout: FlowLayout | null): FlowGraph {
  const config = snapshot.config ?? DEFAULT_CONFIG
  const props = nodePropsFromConfig(config)
  const present = presenceOf(config)
  const nodes: FlowNode[] = [makeNode('start', 'start', null, props.start)]
  const edges: FlowEdge[] = []
  const hasNode = (id: string) => nodes.some((n) => n.id === id)

  for (const projectId of config.accessProjectIds) {
    const id = projectNodeId(projectId)
    if (hasNode(id)) continue
    nodes.push(makeNode('project', id, projectId))
    edges.push(makeEdge('entry', { nodeId: 'start', portId: 'out' }, { nodeId: id, portId: 'in' }, false))
  }

  const trunk: string[] = []
  for (const type of TRUNK_ORDER) {
    if (!present[type]) continue
    nodes.push(makeNode(type, type, null, props[type]))
    trunk.push(type)
  }
  if (present.masker) {
    nodes.push(makeNode('masker', 'masker', null, props.masker))
    edges.push(makeEdge('masker', { nodeId: 'masker', portId: 'masker' }, { nodeId: 'guardrail', portId: 'masker' }, false))
  }
  for (let i = 0; i < trunk.length - 1; i += 1) {
    edges.push(makeEdge('flow', { nodeId: trunk[i], portId: 'out' }, { nodeId: trunk[i + 1], portId: 'in' }, false))
  }

  const firstOfBranch: string[] = []
  const seen = new Set<string>()
  const targets: TargetState[] = config.targets.filter((t) => (seen.has(t.deploymentId) ? false : (seen.add(t.deploymentId), true)))
  for (const t of targets) {
    const llmId = llmNodeId(t.deploymentId)
    nodes.push(makeNode('llm', llmId, t.deploymentId, llmPropsFromTarget(t), t.id))
    const prompt = normalizePrompt(t.systemPromptTemplate)
    if (prompt !== null) {
      const promptId = branchPromptNodeId(t.deploymentId)
      nodes.push(makeNode('prompt', promptId, null, { systemPromptTemplate: prompt }))
      edges.push(makeEdge('route', { nodeId: promptId, portId: 'out' }, { nodeId: llmId, portId: 'in' }, true))
      firstOfBranch.push(promptId)
    } else {
      firstOfBranch.push(llmId)
    }
  }
  if (present.router) {
    for (const id of firstOfBranch) edges.push(makeEdge('route', { nodeId: 'router', portId: 'out' }, { nodeId: id, portId: 'in' }, false))
  } else if (firstOfBranch.length > 0) {
    const tail = trunk.length > 0 ? trunk[trunk.length - 1] : null
    if (tail) edges.push(makeEdge('flow', { nodeId: tail, portId: 'out' }, { nodeId: firstOfBranch[0], portId: 'in' }, false))
  }

  const base: FlowGraph = { ...createSkeletonGraph(snapshot.serviceId), nodes, edges }
  const synced = syncDerived(base, snapshot)
  const positioned = layout && layoutGeneration(layout) === 2 ? applyLayout(synced, layout, snapshot) : autoLayout(synced, snapshot)
  return sortGraph({ ...positioned, serviceId: snapshot.serviceId, schemaVersion: 2 })
}
