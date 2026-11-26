import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

// Connection-centric view (docs/workflow-connection-first-design.ko.md).
const catalog = await loadTypeScript('../src/flow-designer/catalog.ts', import.meta.url)
const layout = await loadTypeScript('../src/flow-designer/layout.ts', import.meta.url)
const graphOps = await loadTypeScript('../src/flow-designer/graphOps.ts', import.meta.url)
const palette = await loadTypeScript('../src/flow-designer/palette.ts', import.meta.url)
const { decompile, syncGraphToConfig } = await loadTypeScript('../src/flow-designer/decompile.ts', import.meta.url)
const { compile } = await loadTypeScript('../src/flow-designer/compile.ts', import.meta.url)
const { validateGraph } = await loadTypeScript('../src/flow-designer/validate.ts', import.meta.url)
const diff = await loadTypeScript('../src/flow-designer/diff.ts', import.meta.url)
const conn = await loadTypeScript('../src/flow-designer/connections.ts', import.meta.url)

const read = (name) => readFileSync(new URL(`../src/flow-designer/${name}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

/* ---------------- fixtures ---------------- */
const { DEFAULT_CONFIG } = catalog
const dep = (id, extra = {}) => ({ id, displayName: `Model ${id.toUpperCase()}`, providerModelId: `model-${id}`, runtimeEndpointId: 'ep1', externalProviderId: null, external: false, enabled: true, loaded: true, healthStatus: 'HEALTHY', maxConcurrency: 4, contextLength: 8192, ...extra })
const ext = (id, provider, extra = {}) => dep(id, { runtimeEndpointId: null, externalProviderId: provider, external: true, ...extra })
const target = (id, deploymentId, extra = {}) => ({ key: `target:${id}`, id, deploymentId, priority: 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true, systemPromptTemplate: null, ...extra })
/** bc250: A P1·W100, B P3·W50 / GOOGLE Gemini: g1 P1, g2 P4 (the user's example). */
const TARGETS = () => [
  target('t1', 'a', { priority: 1 }),
  target('t2', 'b', { priority: 3, weight: 50 }),
  target('t3', 'g1', { priority: 1, followModelChanges: false }),
  target('t4', 'g2', { priority: 4, followModelChanges: false }),
]
function snapshot(targets = TARGETS(), mutate = null) {
  const s = {
    organizationId: 'org1', serviceId: 'svc1', promptVersion: 1, accessComplete: true, loadedAt: '2026-01-01T00:00:00.000Z', unavailable: [], dataProtection: null, features: { targetPrompt: true },
    config: { service: { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro', failoverPolicy: 'COMPATIBLE' }, scheduling: { ...DEFAULT_CONFIG.scheduling }, targets, accessProjectIds: ['p1'] },
    catalog: {
      services: [{ id: 'svc1', serviceKey: 'text-pro', displayName: 'Text Pro', enabled: true }],
      deployments: [dep('a'), dep('b'), dep('u', { loaded: false }), dep('c', { runtimeEndpointId: 'ep2' }), ext('g1', 'pv1'), ext('g2', 'pv1'), ext('o1', 'pv2'), dep('n', { runtimeEndpointId: null })],
      endpoints: [
        { id: 'ep1', displayName: 'bc250', runtimeType: 'LLAMA_CPP', enabled: true, healthStatus: 'HEALTHY', addressLabel: 'http://10.0.0.2:4040' },
        { id: 'ep2', displayName: 'GPU Box', runtimeType: 'OLLAMA', enabled: false, healthStatus: 'UNKNOWN', addressLabel: 'http://10.0.0.3:11434' },
      ],
      providers: [
        { id: 'pv1', displayName: 'GOOGLE Gemini API', providerType: 'GEMINI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true, zeroRetentionVerified: false },
        { id: 'pv2', displayName: 'OPEN-AI-API', providerType: 'OPENAI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: false, zeroRetentionVerified: false },
      ],
      projects: [{ id: 'p1', name: 'Alpha', status: 'ACTIVE', dataBoundaryPolicy: null }],
      maskers: [],
    },
  }
  if (mutate) mutate(s)
  return s
}
const draftSnapshot = () => ({ ...snapshot([]), serviceId: null, config: structuredClone(DEFAULT_CONFIG) })
function run(graph, command, s) {
  const r = graphOps.applyCommand(graph, command, s)
  assert.equal(r.error, null, `command ${command.type} failed: ${r.error}`)
  return r.graph
}
const node = (g, id) => g.nodes.find((n) => n.id === id)
const edge = (g, id) => g.edges.find((e) => e.id === id)
/** New-service draft with identity and project p1 (no LLM yet). */
function draftBase() {
  const s = draftSnapshot()
  let g = decompile(s, null)
  g = run(g, { type: 'updateProps', nodeId: 'start', patch: { serviceKey: 'text-new', displayName: 'New' } }, s)
  g = run(g, { type: 'addNode', nodeType: 'project', refId: 'p1' }, s)
  return { s, g }
}

/* ---------------- WP-1 ---------------- */

test('C0. palette connection groups expose onCanvas and addable; connection meta helpers match connectionOf', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const local = palette.buildPalette(s, g, { query: '', showInactive: true, mode: 'advanced' }).find((x) => x.id === 'llm-local')
  const ep1 = local.connections.find((c) => c.id === 'endpoint:ep1')
  assert.deepEqual([ep1.onCanvas, ep1.addable], [true, true])
  assert.equal(local.connections.find((c) => c.id === 'endpoint:ep2').onCanvas, false)
  assert.equal(local.connections.find((c) => c.id === 'unknown-local').addable, false)
  assert.deepEqual(palette.endpointConnectionMeta(s.catalog.endpoints[0]), palette.connectionOf(s.catalog.deployments.find((d) => d.id === 'a'), s.catalog))
  assert.deepEqual(palette.providerConnectionMeta(s.catalog.providers[0]), palette.connectionOf(s.catalog.deployments.find((d) => d.id === 'g1'), s.catalog))
  assert.equal(typeof palette.modelItem, 'function')
  assert.equal(typeof layout.snapUp, 'function')
  assert.equal(typeof layout.freeSpot, 'function')
})

/* ---------------- WP-2 ---------------- */

test('C1. connection identity: host node id = ConnectionMeta id = connectionIdForDeployment', () => {
  const s = snapshot()
  for (const d of s.catalog.deployments) {
    const id = conn.connectionIdForDeployment(d.id, s)
    assert.equal(id, catalog.hostNodeIdFor(d), d.id)
    const meta = palette.connectionOf(d, s.catalog)
    if (!meta.id.startsWith('unknown-')) assert.equal(id, meta.id, d.id)
  }
  assert.equal(conn.connectionIdForDeployment('n', s), null)
  assert.equal(conn.connectionIdForDeployment('zzz', s), null)
  assert.equal(conn.isConnectionId('endpoint:ep1'), true)
  assert.equal(conn.isConnectionId('provider:pv-1'), true)
  for (const bad of ['endpoint:', 'router', 'deployment:a', 'endpoint:../x', 'provider:a b']) assert.equal(conn.isConnectionId(bad), false, bad)
  assert.deepEqual(conn.connectionMetaOf('endpoint:ep1', s), palette.connectionOf(s.catalog.deployments[0], s.catalog))
  assert.deepEqual(conn.connectionMetaOf('provider:zzz', s), { id: 'provider:zzz', kind: 'provider', label: '알 수 없는 연결', detail: '', tone: 'unknown', enabled: true })
})

test('C2. buildConnectionView groups models behind their connection with derived, deterministic positions', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const before = JSON.stringify(g)
  const view = conn.buildConnectionView(g, s)
  assert.equal(JSON.stringify(g), before, 'the projection never mutates the graph')
  assert.deepEqual(view, conn.buildConnectionView(g, s))
  assert.deepEqual(view.groups.map((x) => [x.connectionId, x.kind, x.llmNodeIds]), [
    ['endpoint:ep1', 'endpoint', ['deployment:a', 'deployment:b']],
    ['provider:pv1', 'provider', ['deployment:g1', 'deployment:g2']],
  ])
  assert.deepEqual(view.positions, {
    'endpoint:ep1': { x: 900, y: 0 }, 'deployment:a': { x: 1200, y: 0 }, 'deployment:b': { x: 1200, y: 160 },
    'provider:pv1': { x: 900, y: 320 }, 'deployment:g1': { x: 1200, y: 320 }, 'deployment:g2': { x: 1200, y: 480 },
  })
  assert.deepEqual(view.groups.map((x) => x.frame), [{ x: 900, y: 0, width: 532, height: 288 }, { x: 900, y: 320, width: 532, height: 288 }])
  assert.equal(view.groupOf['deployment:b'], 'endpoint:ep1')
  assert.equal(view.groupOf.router, undefined)
  assert.deepEqual(view.dimmed, [])
})

test('C2b. connections.ts stays pure and free of cyclic imports', () => {
  const text = read('connections.ts')
  assert.doesNotMatch(text, /from ['"]vue['"]|\bwindow\b|\bdocument\b|localStorage|sessionStorage/)
  assert.doesNotMatch(text, /from '\.\/(graphOps|compile|decompile|validate|diff)'/)
})

test('C3. view edges: router -> connection is drawn only, connection -> model reuses the domain id, host edges are hidden', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const view = conn.buildConnectionView(g, s)
  assert.ok(!view.edges.some((e) => e.kind === 'host'))
  const decor = view.edges.filter((e) => !e.interactive)
  assert.deepEqual(decor.map((e) => e.id).sort(), ['view:router/out->endpoint:ep1/in', 'view:router/out->provider:pv1/in'])
  assert.ok(decor.every((e) => e.kind === 'route' && e.locked && !e.detached))
  const routeA = view.edges.find((e) => e.id === 'router/out->deployment:a/in')
  assert.deepEqual([routeA.interactive, routeA.from, routeA.to, routeA.kind], [true, { nodeId: 'endpoint:ep1', portId: 'out' }, { nodeId: 'deployment:a', portId: 'in' }, 'route'])
  assert.equal(view.edges.find((e) => e.id === 'router/out->deployment:g2/in').from.nodeId, 'provider:pv1')
  assert.deepEqual(
    view.edges.filter((e) => e.interactive).map((e) => e.id).sort(),
    g.edges.filter((e) => e.kind !== 'host').map((e) => e.id).sort(),
    'every non-host domain edge is drawn exactly once, with its own id',
  )
  assert.equal(view.edges.find((e) => e.id === 'project:p1/out->router/in').from.nodeId, 'project:p1')
})

test('C4. a model without a request edge keeps a dashed membership edge to its connection', () => {
  const s = snapshot()
  const g = run(decompile(s, null), { type: 'removeItems', nodeIds: [], edgeIds: ['router/out->deployment:a/in'] }, s)
  const view = conn.buildConnectionView(g, s)
  const dashed = view.edges.find((e) => e.id === 'view:endpoint:ep1/out->deployment:a/in')
  assert.ok(dashed)
  assert.deepEqual([dashed.interactive, dashed.detached], [false, true])
  assert.ok(view.edges.some((e) => e.id === 'view:router/out->endpoint:ep1/in'), 'the routed sibling keeps the router -> connection line')
  assert.deepEqual(view.groups[0].llmNodeIds, ['deployment:a', 'deployment:b'], 'membership does not depend on routing')
})

test('C5. a model-specific prompt sits between the connection and its model', () => {
  const s = snapshot([target('t1', 'a', { priority: 1, systemPromptTemplate: 'Be brief' }), ...TARGETS().slice(1)])
  const g = decompile(s, null)
  assert.equal(conn.connectionLaneOffset(g), layout.COLUMN_WIDTH)
  const view = conn.buildConnectionView(g, s)
  assert.deepEqual(view.positions['endpoint:ep1'], { x: 900, y: 0 })
  assert.deepEqual(view.positions['prompt:a'], { x: 1200, y: 0 })
  assert.deepEqual(view.positions['deployment:a'], { x: 1500, y: 0 })
  assert.deepEqual(view.positions['deployment:b'], { x: 1500, y: 160 })
  assert.deepEqual(view.groups[0].promptNodeIds, ['prompt:a'])
  assert.equal(view.edges.find((e) => e.id === 'router/out->prompt:a/in').from.nodeId, 'endpoint:ep1')
  assert.equal(view.edges.find((e) => e.id === 'prompt:a/out->deployment:a/in').from.nodeId, 'prompt:a')
  assert.ok(!view.edges.some((e) => e.detached))
})

test('C6. connection health is shown once; models behind an offline or disabled connection are dimmed', () => {
  const s = snapshot([...TARGETS(), target('t5', 'c', { priority: 2 }), target('t6', 'o1', { priority: 5, followModelChanges: false })])
  const g = decompile(s, null)
  const view = conn.buildConnectionView(g, s)
  assert.deepEqual(Object.fromEntries(view.groups.map((x) => [x.connectionId, x.health])), {
    'endpoint:ep1': 'online', 'endpoint:ep2': 'disabled', 'provider:pv1': 'online', 'provider:pv2': 'offline',
  })
  assert.deepEqual(view.dimmed, ['deployment:c', 'deployment:o1'])
  const degraded = snapshot(TARGETS(), (x) => { x.catalog.endpoints[0].healthStatus = 'DEGRADED' })
  assert.equal(conn.connectionHealth('endpoint:ep1', degraded), 'degraded')
  assert.equal(conn.connectionHealth('endpoint:zzz', s), 'unknown')
  const card = conn.describeConnectionNode(node(g, 'endpoint:ep1'), g, s)
  assert.deepEqual(
    [card.title, card.subtitle, card.tone, card.badges, card.summary, card.locked],
    ['bc250', '로컬 · LLAMA_CPP · http://10.0.0.2:4040', 'healthy', ['온라인'], ['모델 2개 · 사용 2개', '우선순위 P1–P3'], false],
  )
  assert.equal(conn.describeConnectionNode(node(g, 'provider:pv2'), g, s).badges[0], '오프라인')
  assert.equal(conn.CONNECTION_HEALTH_LABEL.disabled, '비활성')
})

test('C12. dragging a connection or any of its models moves the whole group; other nodes keep their dependency rules', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const view = conn.buildConnectionView(g, s)
  const group = ['deployment:a', 'deployment:b', 'endpoint:ep1']
  assert.deepEqual(conn.connectionDragIds(g, view, ['endpoint:ep1'], false), group)
  assert.deepEqual(conn.connectionDragIds(g, view, ['deployment:b'], true), group, 'Ctrl/Cmd does not split a group')
  assert.deepEqual(conn.connectionDragIds(g, view, ['router'], false), ['router'])
  const moved = run(g, { type: 'moveNodes', moves: group.map((id) => ({ nodeId: id, x: node(g, id).x + 160, y: node(g, id).y })) }, s)
  const after = conn.buildConnectionView(moved, s)
  assert.deepEqual(after.positions['endpoint:ep1'], { x: 1060, y: 0 })
  assert.deepEqual(after.positions['deployment:a'], { x: 1360, y: 0 })
  assert.deepEqual(after.positions['provider:pv1'], { x: 900, y: 320 })
})

test('C13. view ports and the per-connection model choices', () => {
  const s = snapshot()
  const g = decompile(s, null)
  assert.deepEqual(conn.viewPortsOf(node(g, 'endpoint:ep1'), 'connection').map((p) => `${p.direction}:${p.id}`), ['in:in', 'out:out'])
  assert.deepEqual(conn.viewPortsOf(node(g, 'deployment:a'), 'connection').map((p) => p.id), ['in'])
  assert.equal(conn.viewPortsOf(node(g, 'deployment:a'), 'model'), catalog.NODE_TYPES.llm.ports)
  assert.equal(conn.viewPortsOf(node(g, 'router'), 'connection'), catalog.NODE_TYPES.router.ports)
  const hidden = conn.connectionModelChoices(s, g, 'endpoint:ep1', false)
  assert.equal(hidden.connection.label, 'bc250')
  assert.deepEqual(hidden.items.map((m) => [m.deploymentId, m.onCanvas]), [['a', true], ['b', true]])
  assert.equal(hidden.hiddenCount, 1)
  const all = conn.connectionModelChoices(s, g, 'endpoint:ep1', true)
  assert.deepEqual(all.items.map((m) => m.deploymentId), ['a', 'b', 'u'])
  assert.equal(all.hiddenCount, 0)
  assert.deepEqual(conn.connectionModelChoices(s, g, 'provider:pv1', false).items.map((m) => m.deploymentId), ['g1', 'g2'])
})

test('C15. overlapping groups are pushed apart; auto layout in the connection view keeps each connection contiguous', () => {
  const s = snapshot([target('t1', 'a', { priority: 1 }), target('t5', 'c', { priority: 2 }), target('t2', 'b', { priority: 3 })])
  const g = decompile(s, null)
  assert.deepEqual(['deployment:a', 'deployment:c', 'deployment:b'].map((id) => node(g, id).y), [0, 160, 320], 'model view: dispatch order interleaves connections')
  const interleaved = conn.buildConnectionView(g, s)
  assert.deepEqual(interleaved.positions['endpoint:ep2'], { x: 900, y: 320 })
  const [f0, f1] = interleaved.groups.map((x) => x.frame)
  assert.ok(f0.y + f0.height <= f1.y)
  const grouped = conn.autoLayoutByConnection(g, s)
  assert.deepEqual(['deployment:a', 'deployment:b', 'deployment:c'].map((id) => [node(grouped, id).x, node(grouped, id).y]), [[900, 0], [900, 160], [900, 320]])
  assert.deepEqual([node(grouped, 'endpoint:ep1').y, node(grouped, 'endpoint:ep2').y], [0, 320])
  assert.deepEqual(grouped, conn.autoLayoutByConnection(g, s))
  assert.deepEqual(compile(grouped, s), compile(g, s))
})

/* ---------------- WP-3 ---------------- */

test('C7. addConnection places a pinned connection that survives sync and rebase, warns while empty and compiles to nothing', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const r = graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'endpoint:ep2', position: { x: 900, y: 800 } }, s)
  assert.deepEqual([r.error, r.changed, r.select], [null, true, { nodeIds: ['endpoint:ep2'], edgeIds: [] }])
  const host = node(r.graph, 'endpoint:ep2')
  assert.deepEqual([host.type, host.refId, host.props, host.x, host.y], ['endpoint', 'ep2', { pinned: true }, 900, 800])
  assert.deepEqual(compile(r.graph, s), compile(g, s))
  assert.ok(node(graphOps.syncDerived(r.graph, s), 'endpoint:ep2'))
  assert.ok(node(diff.rebase({ graph: r.graph, base: s, remote: s }).graph, 'endpoint:ep2'))
  assert.ok(node(syncGraphToConfig(r.graph, compile(r.graph, s), s), 'endpoint:ep2'))
  assert.deepEqual(conn.buildConnectionView(r.graph, s).positions['endpoint:ep2'], { x: 900, y: 800 })
  const issue = validateGraph(r.graph, s).find((i) => i.code === 'CONNECTION_EMPTY')
  assert.deepEqual([issue.severity, issue.nodeId], ['warning', 'endpoint:ep2'])
  assert.deepEqual(issue.fix.command, { type: 'removeItems', nodeIds: ['endpoint:ep2'], edgeIds: [], withConnectionModels: true })
  assert.equal(node(run(r.graph, issue.fix.command, s), 'endpoint:ep2'), undefined)
  const again = graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'endpoint:ep1' }, s)
  assert.deepEqual([again.error, again.changed, again.select], [null, false, { nodeIds: ['endpoint:ep1'], edgeIds: [] }])
  assert.equal(graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'endpoint:nope' }, s).error, '알 수 없는 연결입니다.')
  assert.equal(graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'router' }, s).error, '알 수 없는 연결입니다.')
  const lonely = snapshot(TARGETS(), (x) => { x.catalog.endpoints.push({ id: 'ep9', displayName: 'Empty Box', runtimeType: 'VLLM', enabled: true, healthStatus: 'HEALTHY', addressLabel: '' }) })
  assert.equal(graphOps.applyCommand(decompile(lonely, null), { type: 'addConnection', connectionId: 'endpoint:ep9' }, lonely).error, '이 연결에는 등록된 모델이 없습니다.')
  const refused = graphOps.applyCommand(r.graph, { type: 'removeItems', nodeIds: ['endpoint:ep2'], edgeIds: [] }, s)
  assert.equal(refused.error, '자동으로 표시되는 노드라 삭제할 수 없습니다.')
  assert.ok(node(refused.graph, 'endpoint:ep2'))
  // the pinned connection keeps its spot when its first model arrives
  const filled = run(r.graph, { type: 'addConnectionModels', connectionId: 'endpoint:ep2', deploymentIds: ['c'] }, s)
  assert.deepEqual(conn.buildConnectionView(filled, s).positions['endpoint:ep2'], { x: 900, y: 800 })
  assert.equal(validateGraph(filled, s).some((i) => i.code === 'CONNECTION_EMPTY'), false)
})

test('C8. addConnectionModels adds only that connection\'s models behind it; the connection does not jump', () => {
  let { s, g } = draftBase()
  g = run(g, { type: 'addConnection', connectionId: 'endpoint:ep1', position: { x: 900, y: 0 } }, s)
  const r = graphOps.applyCommand(g, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['a', 'b'] }, s)
  assert.equal(r.error, null)
  assert.deepEqual(r.select, { nodeIds: ['endpoint:ep1'], edgeIds: [] })
  const out = r.graph
  assert.ok(node(out, 'router'), 'two models add the router automatically')
  assert.ok(edge(out, 'router/out->deployment:a/in') && edge(out, 'router/out->deployment:b/in'))
  assert.deepEqual(['deployment:a', 'deployment:b', 'endpoint:ep1'].map((id) => [node(out, id).x, node(out, id).y]), [[900, 0], [900, 160], [1200, 0]], 'stored (model view) positions')
  const view = conn.buildConnectionView(out, s)
  assert.deepEqual(view.positions['endpoint:ep1'], { x: 900, y: 0 }, 'the connection stays where it was dropped')
  assert.deepEqual([view.positions['deployment:a'], view.positions['deployment:b']], [{ x: 1200, y: 0 }, { x: 1200, y: 160 }])
  assert.equal(graphOps.applyCommand(out, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['g1'] }, s).error, '선택한 모델 중 이 연결에 속하지 않는 모델이 있습니다.')
  assert.equal(graphOps.applyCommand(out, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['zzz'] }, s).error, '알 수 없는 모델입니다.')
  const same = graphOps.applyCommand(out, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['a'] }, s)
  assert.deepEqual([same.error, same.changed], [null, false])
  const more = run(run(out, { type: 'addConnectionModels', connectionId: 'provider:pv1', deploymentIds: ['g1'] }, s), { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['u'] }, s)
  const spots = more.nodes.filter((n) => n.type === 'llm').map((n) => `${n.x},${n.y}`)
  assert.equal(new Set(spots).size, spots.length, 'no two models share a stored position')
  const v2 = conn.buildConnectionView(more, s)
  assert.deepEqual(v2.groups.find((x) => x.connectionId === 'endpoint:ep1').llmNodeIds, ['deployment:a', 'deployment:b', 'deployment:u'])
  const frames = v2.groups.map((x) => x.frame)
  for (let i = 0; i < frames.length; i += 1) {
    for (let j = i + 1; j < frames.length; j += 1) assert.ok(frames[i].y + frames[i].height <= frames[j].y || frames[j].y + frames[j].height <= frames[i].y)
  }
})

test('C9. golden: the same targets compile to the same configuration and plan in both views', () => {
  const build = (steps) => { let { s, g } = draftBase(); for (const step of steps) g = run(g, step, s); return { s, g } }
  const viaConnections = build([
    { type: 'addConnection', connectionId: 'endpoint:ep1', position: { x: 900, y: 0 } },
    { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['a', 'b'] },
    { type: 'addConnectionModels', connectionId: 'provider:pv1', deploymentIds: ['g1'] },
  ])
  const viaModels = build(['a', 'b', 'g1'].map((refId) => ({ type: 'addNode', nodeType: 'llm', refId })))
  const s = viaConnections.s
  assert.deepEqual(compile(viaConnections.g, s), compile(viaModels.g, s))
  const ops = ({ g }) => diff.planChanges({ base: s, desired: compile(g, s), remote: s, issues: validateGraph(g, s) }).operations.map((op) => [op.opId, op.kind, JSON.stringify(op.body ?? null)])
  assert.deepEqual(ops(viaConnections), ops(viaModels))
  assert.ok(ops(viaConnections).length >= 5, 'createService + 3 createTarget + grantAccess')
  for (const key of Object.keys(layout.extractLayout(viaConnections.g, { x: 0, y: 0, zoom: 1 }).nodes)) assert.ok(layout.LAYOUT_NODE_ID_PATTERN.test(key), key)
})

test('C10. wiring in the connection view: same connection re-routes, another connection is refused', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const detached = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['router/out->deployment:a/in'] }, s)
  const epOut = { nodeId: 'endpoint:ep1', portId: 'out' }
  const aIn = { nodeId: 'deployment:a', portId: 'in' }
  const fixed = run(detached, { type: 'connectConnection', from: epOut, to: aIn }, s)
  assert.ok(edge(fixed, 'router/out->deployment:a/in'))
  const wrong = graphOps.applyCommand(detached, { type: 'connectConnection', from: { nodeId: 'provider:pv1', portId: 'out' }, to: aIn }, s)
  assert.match(wrong.error, /'bc250' 연결에 속합니다/)
  assert.match(wrong.error, /연결은 바꿀 수 없습니다/)
  assert.equal(graphOps.canConnectInView(detached, epOut, aIn, s, 'connection').ok, true)
  assert.equal(graphOps.canConnectInView(fixed, epOut, aIn, s, 'connection').reason, '이미 요청 경로에 연결된 모델입니다.')
  const routerToEp1 = { from: { nodeId: 'router', portId: 'out' }, to: { nodeId: 'endpoint:ep1', portId: 'in' } }
  assert.equal(graphOps.applyCommand(g, { type: 'connectConnection', ...routerToEp1 }, s).error, '이 연결의 모델은 이미 모두 요청 경로에 연결되어 있습니다.')
  assert.equal(graphOps.canConnectInView(g, routerToEp1.from, routerToEp1.to, s, 'model').reason, '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.')
  const bare = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['router/out->deployment:a/in', 'router/out->deployment:b/in'] }, s)
  const back = run(bare, { type: 'connectConnection', ...routerToEp1 }, s)
  assert.ok(edge(back, 'router/out->deployment:a/in') && edge(back, 'router/out->deployment:b/in'))
  assert.deepEqual(compile(back, s), compile(g, s))
  const guarded = run(bare, { type: 'addNode', nodeType: 'guardrail' }, s)
  assert.equal(graphOps.applyCommand(guarded, { type: 'connectConnection', from: { nodeId: 'guardrail', portId: 'out' }, to: { nodeId: 'endpoint:ep1', portId: 'in' } }, s).error, '모델이 여러 개인 연결은 라우터에서 이어 주세요.')
  assert.equal(
    graphOps.applyCommand(g, { type: 'connectConnection', from: { nodeId: 'project:p1', portId: 'out' }, to: { nodeId: 'endpoint:ep1', portId: 'in' } }, s).error,
    '연결 노드에는 라우터나 처리 단계(가드레일·공통 프롬프트·캐시·생성 파라미터)의 출력에서만 간선을 이을 수 있습니다.',
  )
  const view = conn.buildConnectionView(bare, s)
  const at = (n) => view.positions[n.id] ?? { x: n.x, y: n.y }
  const p = view.positions['endpoint:ep1']
  assert.deepEqual(graphOps.nearestPortInView(bare, routerToEp1.from, { x: p.x, y: p.y + 52 }, 24, s, 'connection', at), { nodeId: 'endpoint:ep1', portId: 'in' })
})

test('C11. deleting a connection takes its models and prompts; the auto-layout command honours the view', () => {
  const s = snapshot([target('t1', 'a', { priority: 1, systemPromptTemplate: 'Be brief' }), ...TARGETS().slice(1)])
  const g = decompile(s, null)
  const refused = graphOps.applyCommand(g, { type: 'removeItems', nodeIds: ['endpoint:ep1'], edgeIds: [] }, s)
  assert.deepEqual([refused.error, refused.changed], ['자동으로 표시되는 노드라 삭제할 수 없습니다.', false])
  const out = run(g, { type: 'removeItems', nodeIds: ['endpoint:ep1'], edgeIds: [], withConnectionModels: true }, s)
  for (const id of ['endpoint:ep1', 'deployment:a', 'deployment:b', 'prompt:a']) assert.equal(node(out, id), undefined, id)
  for (const id of ['provider:pv1', 'deployment:g1', 'deployment:g2', 'router']) assert.ok(node(out, id), id)
  const plan = diff.planChanges({ base: s, desired: compile(out, s), remote: s, issues: validateGraph(out, s) })
  assert.deepEqual(plan.operations.map((op) => op.opId).sort(), ['delete-target:t1', 'delete-target:t2'])
  const xy = (graph) => graph.nodes.map((n) => [n.id, n.x, n.y])
  assert.deepEqual(xy(run(g, { type: 'autoLayout', view: 'connection' }, s)), xy(graphOps.sortGraph(conn.autoLayoutByConnection(g, s))))
  assert.deepEqual(xy(run(g, { type: 'autoLayout' }, s)), xy(graphOps.sortGraph(layout.autoLayout(g, s))))
})

/* ---------------- WP-4 ---------------- */

test('C16. editor and toolbar wire the view toggle (module memory, default connection view)', () => {
  const editor = read('useFlowEditor.ts')
  assert.match(editor, /let lastViewMode: FlowViewMode = 'connection'/)
  assert.match(editor, /let lastMode: DesignerMode = 'basic'/)
  assert.match(editor, /case 'view-connection':/)
  assert.match(editor, /case 'view-model':/)
  assert.match(editor, /type: 'autoLayout', view: viewMode\.value/)
  assert.match(editor, /mode, viewMode, canNormalize/)
  const toolbar = read('FlowToolbar.vue')
  for (const needle of ["fire('view-connection')", "fire('view-model')", '연결 중심', '모델 중심', 'viewMode?: FlowViewMode']) assert.ok(toolbar.includes(needle), needle)
  assert.match(read('FlowDesignerPage.vue'), /:mode="mode" :can-normalize="canNormalize" :view-mode="viewMode"/)
})

/* ---------------- WP-5 ---------------- */

test('C17. canvas and node card render the connection view', () => {
  const canvas = read('FlowCanvas.vue')
  for (const needle of [
    'buildConnectionView(', 'viewPortsOf(node, viewKind', "nodeType === 'connection'", "type: 'connectConnection'",
    "withConnectionModels: viewKind.value === 'connection'", 'class="fd-group-frame"', 'v-if="item.interactive"',
    'connectionDragIds(', 'nearestPortInView(', 'canConnectInView(', "type: 'addConnectionModels'", 'groupDelta(',
    '(isConnectionId(from.nodeId) || isConnectionId(nodeId))',
  ]) assert.ok(canvas.includes(needle), needle)
  assert.match(canvas, /viewMode\?: FlowViewMode/)
  assert.match(canvas, /\{ mode: 'advanced' \}/)
  const card = read('FlowNodeCard.vue')
  for (const needle of ["'is-dimmed'", 'class="fd-node-action"', 'dimmed?: boolean', 'action?: string | null']) assert.ok(card.includes(needle), needle)
})

/* ---------------- WP-6 ---------------- */

test('C18. palette, inspector and the model picker use the connection commands', () => {
  const pal = read('FlowPalette.vue')
  for (const needle of ["dragStart($event, 'connection', conn.id)", "type: 'addConnection'", "type: 'addConnectionModels'", 'class="fd-conn-add"']) assert.ok(pal.includes(needle), needle)
  const inspector = read('FlowInspector.vue')
  for (const needle of ["import FlowConnectionModels from './FlowConnectionModels.vue'", 'withConnectionModels: true', 'hostNodeIdFor(d) === currentHost.value', '연결 삭제']) assert.ok(inspector.includes(needle), needle)
  const picker = read('FlowConnectionModels.vue')
  assert.match(picker, /type: 'addConnectionModels'/)
  assert.match(picker, /중지·미로드/)
  for (const forbidden of ['localStorage', 'sessionStorage', 'v-html', 'innerHTML']) assert.ok(!picker.includes(forbidden), forbidden)
  assert.doesNotMatch(picker, /#[0-9a-fA-F]{3,8}\b|rgba?\(/)
  const page = read('FlowDesignerPage.vue')
  assert.equal((page.match(/:view-mode="viewMode"/g) ?? []).length, 4)
  assert.match(page, /연결 중심/)
})
