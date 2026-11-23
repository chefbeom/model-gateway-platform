import { DEFAULT_CONFIG, deploymentNodeId, projectNodeId, singletonPropsFromConfig } from './catalog'
import { applyLayout, autoLayout } from './layout'
import { createSkeletonGraph, makeEdge, makeNode, placeCreated, sortGraph, syncHostNodes } from './graphOps'
import type { FlowConfigState, FlowEdge, FlowGraph, FlowLayout, FlowNode, ServiceFlowSnapshot } from './types'

const SINGLETONS = ['service', 'guardrail', 'prompt', 'cache', 'router'] as const

/**
 * Rewrites the graph so that compile(result) equals `config`, while keeping extra nodes
 * (unbound targets, unconnected projects, unbound deployments) and all coordinates.
 */
export function syncGraphToConfig(graph: FlowGraph, config: FlowConfigState, snapshot: ServiceFlowSnapshot): FlowGraph {
  const nodes = new Map<string, FlowNode>(graph.nodes.map((n) => [n.id, { ...n, props: { ...n.props } }]))
  const edges = new Map<string, FlowEdge>(graph.edges.map((e) => [e.id, { ...e, from: { ...e.from }, to: { ...e.to } }]))
  const created: string[] = []
  const ensure = (type: FlowNode['type'], id: string, refId: string | null): FlowNode => {
    const hit = nodes.get(id)
    if (hit) { hit.refId = refId; return hit }
    const node = makeNode(type, id, refId)
    nodes.set(id, node)
    created.push(id)
    return node
  }
  const addEdge = (edge: FlowEdge) => { if (!edges.has(edge.id)) edges.set(edge.id, edge) }
  const dropEdges = (predicate: (e: FlowEdge) => boolean) => { for (const e of [...edges.values()]) if (predicate(e)) edges.delete(e.id) }

  // 1-2. Singletons and fixed pipeline edges.
  const skeleton = createSkeletonGraph(graph.serviceId)
  const props = singletonPropsFromConfig(config)
  for (const type of SINGLETONS) {
    const node = ensure(type, type, null)
    node.props = { ...props[type] }
  }
  for (const e of skeleton.edges) addEdge({ ...e, from: { ...e.from }, to: { ...e.to } })

  // 3. Masker.
  const maskerId = config.service.maskerDeploymentId
  if (maskerId) {
    const masker = ensure('masker', 'masker', null)
    masker.props = { ...masker.props, deploymentId: maskerId }
    dropEdges((e) => e.to.nodeId === 'guardrail' && e.to.portId === 'masker' && e.from.nodeId !== 'masker')
    addEdge(makeEdge('masker', { nodeId: 'masker', portId: 'masker' }, { nodeId: 'guardrail', portId: 'masker' }, false))
  } else {
    nodes.delete('masker')
    dropEdges((e) => e.from.nodeId === 'masker' || e.to.nodeId === 'masker')
  }

  // 4. Targets.
  const keys = new Set(config.targets.map((t) => t.key))
  let nextSeq = graph.nextSeq
  for (const t of config.targets) {
    const match = /^target:new-(\d+)$/.exec(t.key)
    if (match) nextSeq = Math.max(nextSeq, Number(match[1]) + 1)
    const node = ensure('target', t.key, t.id)
    node.props = {
      priority: t.priority,
      weight: t.weight,
      enabled: t.enabled,
      degraded: t.degraded,
      maxConcurrencyOverride: t.maxConcurrencyOverride,
      followModelChanges: t.followModelChanges,
    }
    addEdge(makeEdge('route', { nodeId: 'router', portId: 'targets' }, { nodeId: t.key, portId: 'route' }, true))
    const depNodeId = deploymentNodeId(t.deploymentId)
    ensure('deployment', depNodeId, t.deploymentId)
    dropEdges((e) => e.kind === 'binding' && e.from.nodeId === t.key)
    addEdge(makeEdge('binding', { nodeId: t.key, portId: 'binding' }, { nodeId: depNodeId, portId: 'binding' }, false))
  }
  // Bound targets that are no longer in the config disappear; unbound ones stay.
  for (const node of [...nodes.values()]) {
    if (node.type !== 'target' || keys.has(node.id)) continue
    const bound = [...edges.values()].some((e) => e.kind === 'binding' && e.from.nodeId === node.id)
    if (!bound) continue
    nodes.delete(node.id)
    dropEdges((e) => e.from.nodeId === node.id || e.to.nodeId === node.id)
  }

  // 5. Access.
  const access = new Set(config.accessProjectIds)
  for (const projectId of config.accessProjectIds) {
    const id = projectNodeId(projectId)
    ensure('project', id, projectId)
    addEdge(makeEdge('access', { nodeId: id, portId: 'access' }, { nodeId: 'service', portId: 'access' }, false))
  }
  dropEdges((e) => {
    if (e.kind !== 'access') return false
    const project = nodes.get(e.from.nodeId)
    return !project || !project.refId || !access.has(project.refId)
  })

  // 6. Hosts, placement, ordering.
  const placed = placeCreated({ ...graph, serviceId: snapshot.serviceId, nextSeq }, nodes, edges, created)
  return sortGraph(syncHostNodes({ ...placed, serviceId: snapshot.serviceId }, snapshot))
}

export function decompile(snapshot: ServiceFlowSnapshot, layout: FlowLayout | null): FlowGraph {
  const skeleton = createSkeletonGraph(snapshot.serviceId)
  const synced = syncGraphToConfig(skeleton, snapshot.config ?? DEFAULT_CONFIG, snapshot)
  return sortGraph(layout ? applyLayout(synced, layout, snapshot) : autoLayout(synced, snapshot))
}
