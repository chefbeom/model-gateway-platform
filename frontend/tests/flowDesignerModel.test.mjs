import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

const catalog = await loadTypeScript('../src/flow-designer/catalog.ts', import.meta.url)
const workflow = await loadTypeScript('../src/flow-designer/workflow.ts', import.meta.url)
const layout = await loadTypeScript('../src/flow-designer/layout.ts', import.meta.url)
const graphOps = await loadTypeScript('../src/flow-designer/graphOps.ts', import.meta.url)
const { decompile, syncGraphToConfig } = await loadTypeScript('../src/flow-designer/decompile.ts', import.meta.url)
const { compile, normalizePrompt, canonicalCapabilities, sameValue } = await loadTypeScript('../src/flow-designer/compile.ts', import.meta.url)
const { validateGraph, issueCountsByNode } = await loadTypeScript('../src/flow-designer/validate.ts', import.meta.url)
const diff = await loadTypeScript('../src/flow-designer/diff.ts', import.meta.url)
const history = await loadTypeScript('../src/flow-designer/history.ts', import.meta.url)
const palette = await loadTypeScript("../src/flow-designer/palette.ts", import.meta.url)
const { buildPalette } = palette

const {
  NODE_TYPES, CONNECTION_RULES, DEFAULT_CONFIG, GUARDRAIL_PRESETS, WORKFLOW_TEMPLATES, portSpec, describeNode, nodeFacts, describeEdge,
  projectSignals, isProtectedGuardrail, presetOf, presenceOf, modelAvailability, targetKeyOf,
} = catalog
const { applyCommand, canConnect, nearestCompatiblePort, createSkeletonGraph, makeNode, makeEdge } = graphOps
const { planChanges, rebase, mergeConfigs, computeNodeChanges, configsEqual, stableStringify } = diff
const { analyzeWorkflow, dependencyClosure, insertableTypesForEdge, promptScopeForSplice, isEmptyWorkflow } = workflow

/* ---------------- fixtures ---------------- */

const target = (id, deploymentId, extra = {}) => ({
  key: `target:${id}`, id, deploymentId, priority: 1, weight: 100, degraded: false, enabled: true,
  maxConcurrencyOverride: null, followModelChanges: true, systemPromptTemplate: null, ...extra,
})
const dep = (id, extra) => ({ displayName: `Model ${id.toUpperCase()}`, providerModelId: `model-${id}`, runtimeEndpointId: 'ep1', externalProviderId: null, external: false, enabled: true, loaded: true, healthStatus: 'HEALTHY', maxConcurrency: 4, contextLength: 8192, id, ...extra })

function fixture() {
  return {
    organizationId: 'org1',
    serviceId: 'svc1',
    promptVersion: 3,
    accessComplete: true,
    loadedAt: '2026-01-01T00:00:00.000Z',
    unavailable: [],
    dataProtection: { mode: 'ENFORCE', level: 'STANDARD', externalAction: 'MASK', allowExternalFailover: false, detectSecrets: true, detectPii: true, detectFinancial: true, detectConfidential: false, detectMedia: false },
    features: { targetPrompt: true },
    config: {
      service: {
        ...DEFAULT_CONFIG.service,
        serviceKey: 'text-pro', displayName: 'Text Pro', guardrailAction: 'BLOCK', maskerDeploymentId: 'a',
        systemPromptTemplate: 'You are helpful', responseCacheEnabled: true, responseCacheTtlSeconds: 300, failoverPolicy: 'COMPATIBLE',
      },
      scheduling: { ...DEFAULT_CONFIG.scheduling },
      targets: [
        target('t1', 'a', { priority: 1, weight: 80, systemPromptTemplate: 'Be brief' }),
        target('t2', 'x', { priority: 2, weight: 100, maxConcurrencyOverride: 2, followModelChanges: false }),
      ],
      accessProjectIds: ['p1', 'p3'],
    },
    catalog: {
      services: [{ id: 'svc1', serviceKey: 'text-pro', displayName: 'Text Pro', enabled: true }],
      deployments: [
        dep('a'),
        dep('b', { loaded: false, maxConcurrency: 2, contextLength: null }),
        dep('c', { healthStatus: 'DEGRADED' }),
        dep('x', { runtimeEndpointId: null, externalProviderId: 'pv1', external: true, maxConcurrency: 8, contextLength: 128000, displayName: 'Model X', providerModelId: 'gpt-x' }),
        dep('y', { runtimeEndpointId: null, externalProviderId: 'pv2', external: true, displayName: 'Model Y', providerModelId: 'gpt-y' }),
      ],
      endpoints: [{ id: 'ep1', displayName: 'GPU Box', runtimeType: 'LLAMA_CPP', enabled: true, healthStatus: 'HEALTHY', addressLabel: 'http://10.0.0.1:4040' }],
      providers: [
        { id: 'pv1', displayName: 'Cloud AI', providerType: 'OPENAI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true, zeroRetentionVerified: true },
        { id: 'pv2', displayName: 'Other AI', providerType: 'OPENAI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: false, zeroRetentionVerified: false },
      ],
      projects: [
        { id: 'p1', name: 'Alpha', status: 'ACTIVE', dataBoundaryPolicy: null },
        { id: 'p2', name: 'Beta', status: 'ACTIVE', dataBoundaryPolicy: null },
        { id: 'p3', name: 'Gamma', status: 'ACTIVE', dataBoundaryPolicy: 'INTERNAL_ONLY', externalAiBlocked: true },
        { id: 'p4', name: 'Delta', status: 'ACTIVE', dataBoundaryPolicy: null, dlp: { mode: 'ENFORCE', externalAction: 'LOCAL_ONLY' } },
      ],
      maskers: [
        { deploymentId: 'a', displayName: 'Model A', providerModelId: 'model-a', sourceType: 'LOCAL', sourceName: 'GPU Box', eligible: true, trustReason: '사내 Runtime' },
        { deploymentId: 'x', displayName: 'Model X', providerModelId: 'gpt-x', sourceType: 'EXTERNAL', sourceName: 'Cloud AI', eligible: false, trustReason: '외부 Provider' },
      ],
    },
  }
}
const clone = (v) => structuredClone(v)
const draftSnapshot = () => ({ ...fixture(), serviceId: null, promptVersion: 1, config: clone(DEFAULT_CONFIG), accessComplete: true, dataProtection: null })
/** A snapshot of an existing service whose config is customised. */
const variant = (mutate) => { const s = fixture(); mutate(s.config, s); return s }

const allOps = []
function plan(s, graph, remote = s) {
  const issues = validateGraph(graph, s)
  const result = planChanges({ base: s, desired: compile(graph, s), remote, issues })
  allOps.push(...result.operations)
  return result
}
function run(graph, command, s) {
  const r = applyCommand(graph, command, s)
  assert.equal(r.error, null, `command ${command.type} failed: ${r.error}`)
  return r.graph
}
const props = (graph, id, patch, s) => run(graph, { type: 'updateProps', nodeId: id, patch }, s)
const codes = (issues) => issues.map((i) => i.code)
const base = fixture()
const baseGraph = () => decompile(base, null)
const node = (graph, id) => graph.nodes.find((n) => n.id === id)
const edge = (graph, id) => graph.edges.find((e) => e.id === id)
const ids = (graph) => graph.nodes.map((n) => n.id)
const edgeIds = (graph, kind) => graph.edges.filter((e) => !kind || e.kind === kind).map((e) => e.id)
const withService = (s, patch) => { const next = clone(s); next.config.service = { ...next.config.service, ...patch }; return next }
function rectsOverlap(p, q) { return p.x < q.x + q.width && q.x < p.x + p.width && p.y < q.y + q.height && q.y < p.y + p.height }

/** Draft with one project, the given models and a valid identity. */
function draftWith(models = ['a'], projects = ['p1']) {
  const s = draftSnapshot()
  let g = decompile(s, null)
  g = props(g, 'start', { serviceKey: 'text-new', displayName: 'New Svc' }, s)
  for (const p of projects) g = run(g, { type: 'addNode', nodeType: 'project', refId: p }, s)
  for (const m of models) g = run(g, { type: 'addNode', nodeType: 'llm', refId: m }, s)
  return { s, g }
}

/* ---------------- 1. catalog integrity ---------------- */

test('1. catalog integrity: rules reference ports, locks hold, every field is consumed by compile', () => {
  for (const rule of CONNECTION_RULES) {
    assert.ok(portSpec(rule.fromType, rule.fromPort, 'out'), `${rule.fromType}.${rule.fromPort} out`)
    assert.ok(portSpec(rule.toType, rule.toPort, 'in'), `${rule.toType}.${rule.toPort} in`)
  }
  assert.equal(NODE_TYPES.start.deletable, false)
  assert.equal(NODE_TYPES.start.paletteAddable, false)
  for (const type of ['policy', 'endpoint', 'provider']) {
    assert.equal(NODE_TYPES[type].derived, true)
    assert.equal(NODE_TYPES[type].paletteAddable, false)
    assert.equal(NODE_TYPES[type].deletable, false)
  }
  assert.equal(NODE_TYPES.prompt.singleton, false)
  assert.equal(Object.keys(NODE_TYPES).length, 12)
  assert.equal(NODE_TYPES.prompt.rank, 30)
  assert.equal(catalog.PROMPT_BRANCH_RANK, 70)

  const s = clone(base)
  let graph = baseGraph()
  graph = run(graph, { type: 'addNode', nodeType: 'params' }, s)
  graph = props(graph, 'params', { temperaturePolicy: 'FIXED', temperatureValue: 0.5 }, s)
  const baseline = stableStringify(compile(graph, s))
  const nodeIds = { start: 'start', masker: 'masker', guardrail: 'guardrail', prompt: 'prompt', params: 'params', cache: 'cache', router: 'router', llm: 'deployment:a' }
  for (const [type, id] of Object.entries(nodeIds)) {
    for (const field of NODE_TYPES[type].fields) {
      if (field.key.startsWith('$') || field.newServiceOnly) continue
      const current = node(graph, id).props[field.key]
      let next
      if (field.kind === 'boolean') next = !current
      else if (field.kind === 'number' || field.kind === 'integer') next = typeof current === 'number' ? current + 1 : 5
      else if (field.kind === 'select') next = field.options.find((o) => o.value !== current).value
      else if (field.kind === 'capabilities') next = '["zz"]'
      else if (field.kind === 'masker-select') next = current === 'a' ? 'x' : 'a'
      else next = `${current ?? ''}x`
      const changed = props(graph, id, { [field.key]: next }, s)
      assert.notEqual(stableStringify(compile(changed, s)), baseline, `${type}.${field.key} is not consumed by compile`)
    }
  }
  // model-specific prompt content is consumed too
  assert.notEqual(stableStringify(compile(props(graph, 'prompt:a', { systemPromptTemplate: 'zz' }, s), s)), baseline)
  for (const key of ['$access']) assert.ok(NODE_TYPES.project.fields.some((f) => f.key === key))
  assert.ok(NODE_TYPES.guardrail.fields.some((f) => f.key === '$preset' && f.basicOnly))
  assert.ok(NODE_TYPES.llm.fields.some((f) => f.key === '$model'))
  assert.equal(GUARDRAIL_PRESETS.length, 5)
  assert.equal(WORKFLOW_TEMPLATES.length, 3)
})

/* ---------------- 2/3. decompile ---------------- */

test('2. decompile is deterministic and follows the id rules', () => {
  const a = decompile(base, null)
  const b = decompile(base, null)
  assert.deepEqual(a, b)
  const expected = ['start', 'project:p1', 'project:p3', 'policy:p3', 'guardrail', 'masker', 'prompt', 'cache', 'router', 'deployment:a', 'deployment:x', 'prompt:a', 'endpoint:ep1', 'provider:pv1']
  assert.deepEqual([...ids(a)].sort(), [...expected].sort())
  assert.equal(a.schemaVersion, 2)
  assert.equal(a.serviceId, 'svc1')
  assert.equal('nextSeq' in a, false)
  // canonical trunk order and wiring
  const analysis = analyzeWorkflow(a, base)
  assert.deepEqual(analysis.trunk, ['guardrail', 'prompt', 'cache', 'router'])
  assert.equal(analysis.head, 'guardrail')
  assert.equal(analysis.router, 'router')
  assert.deepEqual(analysis.branches, [{ llmNodeId: 'deployment:a', promptNodeId: 'prompt:a' }, { llmNodeId: 'deployment:x', promptNodeId: null }])
  assert.equal(analysis.issues.length, 0)
  assert.deepEqual(edgeIds(a, 'entry').sort(), ['start/out->project:p1/in', 'start/out->project:p3/in'])
  assert.equal(edge(a, 'project:p3/out->policy:p3/in').kind, 'policy')
  assert.equal(edge(a, 'project:p3/out->policy:p3/in').locked, true)
  assert.equal(edge(a, 'policy:p3/out->guardrail/in').kind, 'access')
  assert.equal(edge(a, 'project:p1/out->guardrail/in').kind, 'access')
  assert.equal(edgeIds(a, 'access').length, 2)
  assert.equal(edge(a, 'prompt:a/out->deployment:a/in').locked, true)
  assert.equal(edge(a, 'router/out->prompt:a/in').kind, 'route')
  assert.equal(edge(a, 'router/out->deployment:x/in').kind, 'route')
  assert.equal(edge(a, 'masker/masker->guardrail/masker').kind, 'masker')
  assert.equal(edgeIds(a, 'host').length, 2)
  assert.ok(edgeIds(a, 'host').every((id) => edge(a, id).locked))
  assert.equal(node(a, 'deployment:a').targetId, 't1')
  assert.equal(targetKeyOf(node(a, 'deployment:a')), 'target:t1')
  const sorted = [...a.nodes].sort((p, q) => workflow.rankOf(p) - workflow.rankOf(q) || (p.id < q.id ? -1 : 1))
  assert.deepEqual(ids(a), ids({ nodes: sorted }))
  assert.equal(createSkeletonGraph('svc1').nodes.length, 1)
})

const ROUND_TRIP = {
  'fixture': () => fixture(),
  'defaults, no targets': () => variant((c) => { c.targets = []; c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro' }; c.accessProjectIds = [] }),
  'one target, no router': () => variant((c) => { c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro' }; c.targets = [target('t1', 'a')]; c.accessProjectIds = ['p1'] }),
  'parameters only': () => variant((c) => { c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro', temperaturePolicy: 'FIXED', temperatureValue: 0.5, openAiFastMode: true }; c.targets = [] }),
  'ALLOW with masker': () => variant((c) => { c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro', guardrailAction: 'ALLOW', maskerDeploymentId: 'a' }; c.targets = [] }),
  'no person-name masking and no guardrail': () => variant((c) => { c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro', maskPersonNames: false }; c.targets = [] }),
  'queueing with one target': () => variant((c) => { c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro' }; c.scheduling = { ...DEFAULT_CONFIG.scheduling, mode: 'LOCAL_FIRST_WAIT', localWaitMs: 100, maxWaitMs: 200 }; c.targets = [target('t1', 'a')] }),
  'model prompt only': () => variant((c) => { c.service = { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro' }; c.targets = [target('t1', 'a', { systemPromptTemplate: 'Only me' })] }),
  'draft': () => draftSnapshot(),
}
test('3. round trip: compile(decompile(s)) equals s.config for every shape', () => {
  for (const [name, make] of Object.entries(ROUND_TRIP)) {
    const s = make()
    const snapshot = clone(s)
    const graph = decompile(s, null)
    assert.deepEqual(compile(graph, s), s.config, name)
    assert.equal(validateGraph(graph, s).filter((i) => i.severity === 'error' && !['DISPLAY_NAME_REQUIRED', 'SERVICE_KEY_INVALID'].includes(i.code)).length, 0, name)
    assert.deepEqual(s, snapshot, `${name}: inputs are never mutated`)
  }
  const withLayout = layout.extractLayout(baseGraph(), { x: 0, y: 0, zoom: 1 })
  assert.deepEqual(compile(decompile(base, withLayout), base), base.config)
  assert.deepEqual(base, fixture())
})

/* ---------------- 4. presence based compile ---------------- */

test('4. presence based compile ignores coordinates and trunk order; missing nodes mean server defaults', () => {
  const g = baseGraph()
  const moved = run(g, { type: 'moveNodes', moves: g.nodes.map((n, i) => ({ nodeId: n.id, x: i * 7, y: 900 - i })) }, base)
  assert.deepEqual(compile(moved, base), compile(g, base))

  // cache before guardrail (order flipped by hand)
  let h = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['guardrail/out->prompt/in'] }, base)
  h = run(h, { type: 'connect', from: { nodeId: 'cache', portId: 'out' }, to: { nodeId: 'guardrail', portId: 'in' } }, base)
  assert.notEqual(analyzeWorkflow(h, base).head, 'guardrail')
  assert.deepEqual(compile(h, base), compile(g, base))

  const noCache = run(g, { type: 'removeItems', nodeIds: ['cache'], edgeIds: [] }, base)
  const c = compile(noCache, base)
  assert.equal(c.service.responseCacheEnabled, false)
  assert.equal(c.service.responseCacheTtlSeconds, base.config.service.responseCacheTtlSeconds)

  const withParams = props(run(g, { type: 'addNode', nodeType: 'params' }, base), 'params', { temperaturePolicy: 'OMIT', openAiFastMode: true }, base)
  assert.equal(compile(withParams, base).service.temperaturePolicy, 'OMIT')
  const without = run(withParams, { type: 'removeItems', nodeIds: ['params'], edgeIds: [] }, base)
  assert.equal(compile(without, base).service.temperaturePolicy, 'REQUEST')
  assert.equal(compile(without, base).service.openAiFastMode, false)

  const noRouter = run(run(g, { type: 'removeItems', nodeIds: ['deployment:x'], edgeIds: [] }, base), { type: 'removeItems', nodeIds: ['router'], edgeIds: [] }, base)
  const nr = compile(noRouter, base)
  assert.equal(nr.service.failoverPolicy, 'STRICT')
  assert.equal(nr.scheduling.mode, 'IMMEDIATE')
  assert.equal(nr.scheduling.maxWaitMs, base.config.scheduling.maxWaitMs)
})

/* ---------------- 5/6. default workflow and second LLM ---------------- */

test('5. default workflow start -> project -> LLM applies with three operations', () => {
  const { s, g } = draftWith(['a'], ['p1'])
  const config = compile(g, s)
  assert.deepEqual(config.accessProjectIds, ['p1'])
  assert.equal(config.targets.length, 1)
  assert.equal(config.targets[0].key, 'target:new-a')
  assert.equal(config.targets[0].priority, 1)
  assert.equal(config.targets[0].weight, 100)
  assert.equal(config.service.failoverPolicy, 'STRICT')
  assert.equal(node(g, 'router'), undefined)
  assert.equal(analyzeWorkflow(g, s).head, 'deployment:a')
  assert.deepEqual(edgeIds(g, 'access'), ['project:p1/out->deployment:a/in'])
  assert.deepEqual(validateGraph(g, s).filter((i) => i.severity === 'error'), [])

  const early = props(draftWith(['a'], ['p1']).g, 'start', { serviceKey: '', displayName: '' }, s)
  assert.ok(codes(validateGraph(early, s)).includes('SERVICE_KEY_INVALID'))
  const p = plan(s, g)
  assert.equal(p.blockingIssues.length, 0)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-service', 'create-target:target:new-a', 'grant-access:p1'])
  assert.equal(p.operations[0].summary, '새 워크플로우(논리 서비스) 생성: text-new')
  assert.equal(p.operations[1].summary.startsWith('LLM 연결 추가'), true)
  assert.ok(p.notes.some((n) => n.message.startsWith('새 워크플로우는 시작 노드에')))
  assert.ok(codes(validateGraph(draftWith(['a'], []).g, s)).includes('WF_NO_PROJECT'))
  assert.ok(codes(validateGraph(decompile(s, null), s)).includes('WF_NO_LLM'))
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'llm', refId: 'nope' }, s).error, '알 수 없는 모델입니다.')
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'project', refId: 'zzz' }, s).error, '알 수 없는 프로젝트입니다.')
  assert.equal(node(props(g, 'start', { serviceKey: 'hacked' }, s), 'start').props.serviceKey, 'hacked', 'drafts may edit the key')
  assert.equal(node(props(baseGraph(), 'start', { serviceKey: 'hacked' }, base), 'start').props.serviceKey, 'text-pro', 'existing services keep their key')
})

test('6. a second LLM adds the router automatically', () => {
  const { s, g } = draftWith(['a'], ['p1'])
  const two = run(g, { type: 'addNode', nodeType: 'llm', refId: 'x' }, s)
  assert.ok(node(two, 'router'))
  assert.equal(edge(two, 'router/out->deployment:a/in').kind, 'route')
  assert.equal(edge(two, 'router/out->deployment:x/in').kind, 'route')
  assert.equal(node(two, 'deployment:x').props.followModelChanges, false)
  assert.equal(node(two, 'deployment:x').props.priority, 2)
  assert.equal(analyzeWorkflow(two, s).head, 'router')
  assert.deepEqual(edgeIds(two, 'access'), ['project:p1/out->router/in'])
  assert.deepEqual(validateGraph(two, s).filter((i) => i.severity === 'error'), [])
  assert.equal(presenceOf(compile(two, s)).router, true)

  // wiring a second LLM by hand from the trunk end gives the same shape
  let h = run(decompile(draftSnapshot(), null), { type: 'addNode', nodeType: 'project', refId: 'p1' }, s)
  h = run(h, { type: 'addNode', nodeType: 'cache' }, s)
  h = run(h, { type: 'addNode', nodeType: 'llm', refId: 'a' }, s)
  h = run(h, { type: 'addNode', nodeType: 'llm', refId: 'x', autoWire: false }, s)
  assert.equal(analyzeWorkflow(h, s).issues.some((i) => i.code === 'WF_NODE_DETACHED' || i.code === 'WF_LLM_DETACHED'), true)
  h = run(h, { type: 'connect', from: { nodeId: 'cache', portId: 'out' }, to: { nodeId: 'deployment:x', portId: 'in' } }, s)
  assert.ok(node(h, 'router'))
  assert.ok(edge(h, 'cache/out->router/in'))
  assert.ok(edge(h, 'router/out->deployment:a/in'))
  assert.ok(edge(h, 'router/out->deployment:x/in'))
  assert.deepEqual(analyzeWorkflow(h, s).branches.map((b) => b.llmNodeId), ['deployment:a', 'deployment:x'])
})

/* ---------------- 7-9. splice, auto wiring, order ---------------- */

test('7. splicing into access, flow and route edges', () => {
  const noCache = variant((c) => { c.service.responseCacheEnabled = false })
  const g0 = decompile(noCache, null)
  assert.deepEqual(insertableTypesForEdge(g0, 'project:p1/out->guardrail/in', noCache), ['cache', 'params'])
  const head = run(g0, { type: 'addNode', nodeType: 'cache', spliceEdgeId: 'project:p1/out->guardrail/in' }, noCache)
  assert.equal(analyzeWorkflow(head, noCache).head, 'cache')
  assert.deepEqual(edgeIds(head, 'access').sort(), ['policy:p3/out->cache/in', 'project:p1/out->cache/in'])
  assert.ok(edge(head, 'cache/out->guardrail/in'))
  assert.ok(codes(validateGraph(head, noCache)).includes('WF_ORDER_NONCANONICAL'))

  const g = baseGraph()
  const mid = run(g, { type: 'addNode', nodeType: 'params', spliceEdgeId: 'guardrail/out->prompt/in' }, base)
  assert.ok(edge(mid, 'guardrail/out->params/in'))
  assert.ok(edge(mid, 'params/out->prompt/in'))
  assert.equal(edge(mid, 'guardrail/out->prompt/in'), undefined)
  const placed = (id) => node(mid, id)
  assert.ok(placed('params').x > placed('guardrail').x && placed('prompt').x > placed('params').x, 'downstream nodes make room')
  const rects = mid.nodes.map(layout.nodeRect)
  for (let i = 0; i < rects.length; i += 1) for (let j = i + 1; j < rects.length; j += 1) assert.equal(rectsOverlap(rects[i], rects[j]), false, `${mid.nodes[i].id} / ${mid.nodes[j].id}`)

  const branch = run(g, { type: 'addNode', nodeType: 'prompt', spliceEdgeId: 'router/out->deployment:x/in' }, base)
  assert.ok(edge(branch, 'router/out->prompt:x/in'))
  const out = edge(branch, 'prompt:x/out->deployment:x/in')
  assert.ok(out && out.kind === 'route' && out.locked)
  assert.equal(edge(branch, 'router/out->deployment:x/in'), undefined)
  assert.deepEqual(analyzeWorkflow(branch, base).branches.map((b) => b.promptNodeId), ['prompt:a', 'prompt:x'])

  assert.deepEqual(insertableTypesForEdge(g, 'router/out->deployment:x/in', base), ['prompt'])
  assert.deepEqual(insertableTypesForEdge(g, 'router/out->prompt:a/in', base), [])
  assert.deepEqual(insertableTypesForEdge(g, 'prompt:a/out->deployment:a/in', base), [])
  assert.deepEqual(insertableTypesForEdge(g, 'start/out->project:p1/in', base), [])
  assert.deepEqual(insertableTypesForEdge(g, 'masker/masker->guardrail/masker', base), [])
  assert.deepEqual(insertableTypesForEdge(g, 'guardrail/out->prompt/in', base), ['params'])
  assert.deepEqual(insertableTypesForEdge(g, 'nope', base), [])
  assert.equal(promptScopeForSplice(g, 'router/out->deployment:x/in'), 'model')
  assert.equal(promptScopeForSplice(g, 'guardrail/out->prompt/in'), 'common')

  // a single LLM: the incoming edge of the LLM scopes the prompt to the model and offers a router
  const { s, g: single } = draftWith(['a'], ['p1'])
  const accessEdge = edgeIds(single, 'access')[0]
  assert.deepEqual(insertableTypesForEdge(single, accessEdge, s), ['guardrail', 'prompt', 'cache', 'params', 'router'])
  assert.equal(promptScopeForSplice(single, accessEdge), 'model')
  const scoped = run(single, { type: 'addNode', nodeType: 'prompt', spliceEdgeId: accessEdge }, s)
  assert.ok(node(scoped, 'prompt:a'))
  assert.equal(analyzeWorkflow(scoped, s).head, 'prompt:a')
  const routed = run(single, { type: 'addNode', nodeType: 'router', spliceEdgeId: accessEdge }, s)
  assert.equal(edge(routed, 'router/out->deployment:a/in').kind, 'route')
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'cache', spliceEdgeId: 'guardrail/out->prompt/in' }, base).error, '응답 캐시은(는) 워크플로우에 하나만 둘 수 있습니다.')
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'llm', refId: 'b', spliceEdgeId: 'guardrail/out->prompt/in' }, base).error, '이 간선에는 이 노드를 넣을 수 없습니다.')
})

test('8. auto wiring puts nodes at the canonical position', () => {
  const s = draftSnapshot()
  let g = decompile(s, null)
  for (const type of ['cache', 'guardrail', 'prompt']) g = run(g, { type: 'addNode', nodeType: type }, s)
  const a = analyzeWorkflow(g, s)
  assert.deepEqual(a.trunk, ['guardrail', 'prompt', 'cache'])
  assert.equal(a.head, 'guardrail')
  assert.ok(edge(g, 'guardrail/out->prompt/in') && edge(g, 'prompt/out->cache/in'))
  assert.equal(a.issues.length, 0)
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'cache' }, s).error, '응답 캐시은(는) 워크플로우에 하나만 둘 수 있습니다.')
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'guardrail' }, s).error, '외부 노출 방지 가드레일은(는) 워크플로우에 하나만 둘 수 있습니다.')
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'prompt' }, s).error, '공통 시스템 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선의 ＋를 누르세요.')
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'router' }, s).error, null)
  // params goes between the cache and the router; an LLM goes at the end
  g = run(g, { type: 'addNode', nodeType: 'params' }, s)
  g = run(g, { type: 'addNode', nodeType: 'llm', refId: 'a' }, s)
  assert.deepEqual(analyzeWorkflow(g, s).trunk, ['guardrail', 'prompt', 'cache', 'params'])
  assert.ok(edge(g, 'params/out->deployment:a/in'))
  // not wiring leaves an unconnected node, which is an error until connected
  const loose = run(g, { type: 'addNode', nodeType: 'router', autoWire: false, position: { x: 4000, y: 80 } }, s)
  assert.equal(node(loose, 'router').x, 4000)
  assert.ok(codes(validateGraph(loose, s)).includes('WF_NODE_DETACHED'))
  // masker needs a guardrail
  assert.equal(applyCommand(decompile(s, null), { type: 'addNode', nodeType: 'masker', refId: 'a' }, s).error, '마스킹 워커는 가드레일에 연결됩니다. 가드레일을 먼저 추가하세요.')
})

test('9. a non canonical order warns, normalizeOrder fixes it, compile never changes', () => {
  const s = draftSnapshot()
  let g = decompile(s, null)
  g = run(g, { type: 'addNode', nodeType: 'guardrail' }, s)
  g = run(g, { type: 'addNode', nodeType: 'cache' }, s)
  g = run(g, { type: 'addNode', nodeType: 'llm', refId: 'a' }, s)
  const before = compile(g, s)
  let h = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['guardrail/out->cache/in', 'cache/out->deployment:a/in'] }, s)
  h = run(h, { type: 'connect', from: { nodeId: 'cache', portId: 'out' }, to: { nodeId: 'guardrail', portId: 'in' } }, s)
  h = run(h, { type: 'connect', from: { nodeId: 'guardrail', portId: 'out' }, to: { nodeId: 'deployment:a', portId: 'in' } }, s)
  assert.deepEqual(analyzeWorkflow(h, s).trunk, ['cache', 'guardrail'])
  const warn = validateGraph(h, s).find((i) => i.code === 'WF_ORDER_NONCANONICAL')
  assert.ok(warn)
  assert.equal(warn.severity, 'warning')
  assert.equal(warn.nodeId, 'guardrail')
  assert.deepEqual(warn.fix, { label: '순서 정리', command: { type: 'normalizeOrder' } })
  assert.deepEqual(compile(h, s), before)
  const fixed = run(h, warn.fix.command, s)
  assert.deepEqual(analyzeWorkflow(fixed, s).trunk, ['guardrail', 'cache'])
  assert.ok(!codes(validateGraph(fixed, s)).includes('WF_ORDER_NONCANONICAL'))
  assert.deepEqual(compile(fixed, s), before)
  assert.ok(edge(fixed, 'cache/out->deployment:a/in'))
  assert.ok(node(fixed, 'guardrail').x < node(fixed, 'cache').x)
  const again = applyCommand(fixed, { type: 'normalizeOrder' }, s)
  assert.equal(again.changed, false)
  assert.equal(analyzeWorkflow(fixed, s).issues.length, 0)
})

/* ---------------- 10. impossible layouts ---------------- */

test('10. layouts the server cannot express are errors with fixes', () => {
  const g = baseGraph()
  const reason = (from, to) => { const r = canConnect(g, from, to, base); assert.equal(r.ok, false); return r.reason }
  assert.equal(reason({ nodeId: 'router', portId: 'out' }, { nodeId: 'cache', portId: 'in' }), '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.')

  const manual = (nodes, edges) => ({ schemaVersion: 2, serviceId: 'svc1', nodes: [makeNode('start', 'start', null, catalog.nodePropsFromConfig(base.config).start), ...nodes], edges })
  const n = (type, id, refId = null) => makeNode(type, id, refId, id.startsWith('deployment') ? catalog.llmPropsFromTarget(target('t', refId)) : {}, null)
  const flow = (from, to, kind = 'flow', locked = false) => makeEdge(kind, { nodeId: from, portId: 'out' }, { nodeId: to, portId: 'in' }, locked)

  const stage = manual([n('router', 'router'), n('cache', 'cache'), n('llm', 'deployment:a', 'a')], [flow('router', 'cache', 'route'), flow('cache', 'deployment:a')])
  assert.ok(analyzeWorkflow(stage, base).issues.some((i) => i.code === 'WF_STAGE_AFTER_ROUTER' && i.nodeId === 'cache'))
  assert.ok(codes(validateGraph(stage, base)).includes('WF_STAGE_AFTER_ROUTER'))

  const fan = manual([n('guardrail', 'guardrail'), n('llm', 'deployment:a', 'a'), n('llm', 'deployment:x', 'x')], [flow('guardrail', 'deployment:a'), flow('guardrail', 'deployment:x')])
  const needRouter = analyzeWorkflow(fan, base).issues.find((i) => i.code === 'WF_ROUTER_REQUIRED')
  assert.ok(needRouter)
  assert.deepEqual(needRouter.fix, { label: '라우터 추가', command: { type: 'addNode', nodeType: 'router' } })
  assert.equal(needRouter.nodeId, 'guardrail')

  const orphan = manual([n('guardrail', 'guardrail'), n('llm', 'deployment:a', 'a'), n('llm', 'deployment:x', 'x')], [flow('guardrail', 'deployment:a')])
  const orphanCodes = codes(analyzeWorkflow(orphan, base).issues)
  assert.ok(orphanCodes.includes('WF_LLM_DETACHED'))
  assert.ok(orphanCodes.includes('WF_MULTIPLE_ROOTS'))
  assert.deepEqual(analyzeWorkflow(orphan, base).detached, ['deployment:x'])
  assert.ok(analyzeWorkflow(orphan, base).issues.every((i) => i.severity === 'error'))

  const loop = manual([n('guardrail', 'guardrail'), n('cache', 'cache')], [flow('guardrail', 'cache'), flow('cache', 'guardrail')])
  assert.ok(codes(validateGraph(loop, base)).includes('CYCLE_DETECTED'))

  const wrong = manual([n('router', 'router'), n('prompt', 'prompt:a'), n('llm', 'deployment:a', 'a'), n('llm', 'deployment:x', 'x')], [flow('router', 'prompt:a', 'route'), flow('prompt:a', 'deployment:x', 'route', true), flow('router', 'deployment:a', 'route')])
  assert.ok(analyzeWorkflow(wrong, base).issues.some((i) => i.code === 'WF_BRANCH_PROMPT_MISPLACED' && i.nodeId === 'prompt:a'))

  assert.ok(!codes(validateGraph({ ...baseGraph(), nodes: baseGraph().nodes.filter((x) => x.id !== 'start') }, base)).includes('CYCLE_DETECTED'))
  assert.deepEqual(codes(validateGraph({ ...baseGraph(), nodes: baseGraph().nodes.filter((x) => x.id !== 'start') }, base)), ['WF_START_MISSING'])
  const big = { ...baseGraph(), nodes: Array.from({ length: 301 }, (_, i) => makeNode('cache', `project:p${i}`, null)) }
  assert.ok(codes(validateGraph({ ...big, nodes: [...big.nodes, makeNode('start', 'start', null)] }, base)).includes('GRAPH_TOO_LARGE'))
})

/* ---------------- 11. delete and heal ---------------- */

test('11. deleting nodes heals the request path', () => {
  const g = baseGraph()
  const noCache = run(g, { type: 'removeItems', nodeIds: ['cache'], edgeIds: [] }, base)
  assert.ok(edge(noCache, 'prompt/out->router/in'))
  assert.equal(analyzeWorkflow(noCache, base).issues.length, 0)

  const refuse = applyCommand(g, { type: 'removeItems', nodeIds: ['router'], edgeIds: [] }, base)
  assert.equal(refuse.error, '여러 LLM이 연결된 라우터는 삭제할 수 없습니다. LLM을 하나만 남기세요.')
  assert.deepEqual(refuse.graph, g)

  // one LLM left: the router can go and the previous node feeds the LLM prompt directly
  let one = run(g, { type: 'removeItems', nodeIds: ['deployment:x'], edgeIds: [] }, base)
  assert.ok(!ids(one).includes('provider:pv1'), 'unused host disappears')
  one = run(one, { type: 'removeItems', nodeIds: ['router'], edgeIds: [] }, base)
  assert.equal(edge(one, 'cache/out->prompt:a/in').kind, 'flow')
  assert.equal(analyzeWorkflow(one, base).issues.length, 0)
  assert.equal(compile(one, base).scheduling.mode, 'IMMEDIATE')

  // deleting an LLM takes its model prompt (and exclusive host) along
  const noA = run(g, { type: 'removeItems', nodeIds: ['deployment:a'], edgeIds: [] }, base)
  assert.ok(!ids(noA).includes('prompt:a') && !ids(noA).includes('endpoint:ep1'))
  assert.deepEqual(plan(base, noA).operations.map((o) => o.opId), ['delete-target:t1'])

  // protected guardrail
  const protectedTry = applyCommand(g, { type: 'removeItems', nodeIds: ['guardrail'], edgeIds: [] }, base)
  assert.equal(protectedTry.error, '외부 노출 방지 정책이 적용된 가드레일입니다. 인스펙터의 [보호 해제 후 삭제]를 사용하세요.')
  assert.equal(protectedTry.changed, false)
  assert.deepEqual(protectedTry.graph, g)
  const forced = run(g, { type: 'removeItems', nodeIds: ['guardrail'], edgeIds: [], force: true }, base)
  assert.ok(!ids(forced).includes('guardrail') && !ids(forced).includes('masker'))
  assert.equal(analyzeWorkflow(forced, base).head, 'prompt')
  assert.ok(edge(forced, 'project:p1/out->prompt/in'))

  // fixed nodes
  assert.equal(applyCommand(g, { type: 'removeItems', nodeIds: ['start'], edgeIds: [] }, base).error, '시작 노드는 삭제할 수 없습니다.')
  for (const id of ['policy:p3', 'endpoint:ep1', 'provider:pv1']) {
    const r = applyCommand(g, { type: 'removeItems', nodeIds: [id], edgeIds: [] }, base)
    assert.equal(r.error, '자동으로 표시되는 노드라 삭제할 수 없습니다.')
    assert.deepEqual(r.graph, g)
  }
  // edges: entry = revoke access (node stays), automatic edges are protected
  const revoked = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['start/out->project:p1/in'] }, base)
  assert.ok(ids(revoked).includes('project:p1'))
  assert.deepEqual(compile(revoked, base).accessProjectIds, ['p3'])
  assert.equal(edge(revoked, 'project:p1/out->guardrail/in'), undefined)
  assert.deepEqual(plan(base, revoked).operations.map((o) => o.opId), ['revoke-access:p1'])
  for (const id of ['project:p1/out->guardrail/in', 'prompt:a/out->deployment:a/in', 'deployment:a/host->endpoint:ep1/in', 'project:p3/out->policy:p3/in']) {
    const r = applyCommand(g, { type: 'removeItems', nodeIds: [], edgeIds: [id] }, base)
    assert.equal(r.error, '자동으로 관리되는 간선입니다.', id)
    assert.deepEqual(r.graph, g)
  }
  // deleting a project deletes its inherited policy node
  const noP3 = run(g, { type: 'removeItems', nodeIds: ['project:p3'], edgeIds: [] }, base)
  assert.ok(!ids(noP3).includes('policy:p3'))
})

/* ---------------- 12. guardrail protection ---------------- */

test('12. a guardrail protecting the server stays until it is explicitly released', () => {
  assert.equal(isProtectedGuardrail(base), true)
  assert.equal(isProtectedGuardrail(draftSnapshot()), false)
  assert.equal(isProtectedGuardrail(withService(base, { guardrailAction: 'ALLOW', dataBoundaryPolicy: 'INTERNAL_ONLY' })), true)
  assert.equal(isProtectedGuardrail(withService(base, { guardrailAction: 'ALLOW', dataBoundaryPolicy: 'EXTERNAL_ALLOWED' })), false)
  const g = baseGraph()
  assert.equal(describeNode(node(g, 'guardrail'), g, base).protected, true)
  assert.ok(describeNode(node(g, 'guardrail'), g, base).lockReason.includes('[보호 해제 후 삭제]'))
  assert.equal(describeNode(node(draftWith().g, 'start'), draftWith().g, draftSnapshot()).protected, false)

  const released = run(g, { type: 'removeItems', nodeIds: ['guardrail'], edgeIds: [], force: true }, base)
  const c = compile(released, base)
  assert.equal(c.service.dataBoundaryPolicy, 'EXTERNAL_ALLOWED')
  assert.equal(c.service.guardrailAction, 'ALLOW')
  assert.equal(c.service.maskerDeploymentId, null)
  const p = plan(base, released)
  const patch = p.operations.find((o) => o.kind === 'patchService')
  assert.equal(patch.risk, 'danger')
  assert.deepEqual(patch.body, { guardrailAction: 'ALLOW', clearMaskerDeployment: true })
  assert.ok(p.notes.some((n) => n.level === 'warning' && n.message.startsWith('외부 노출 방지 정책이 완화됩니다')))
  const removedIssue = validateGraph(released, base).find((i) => i.code === 'GUARDRAIL_REMOVED')
  assert.ok(removedIssue && removedIssue.severity === 'warning' && removedIssue.nodeId === 'start')

  const relaxed = props(g, 'guardrail', { guardrailAction: 'REDACT_RESTORE' }, base)
  assert.ok(codes(validateGraph(relaxed, base)).includes('GUARDRAIL_RELAXED'))
  const strict = props(g, 'guardrail', { dataBoundaryPolicy: 'INTERNAL_ONLY' }, base)
  assert.ok(!codes(validateGraph(strict, base)).includes('GUARDRAIL_RELAXED'))
  assert.ok(!codes(validateGraph(g, base)).includes('GUARDRAIL_RELAXED'))

  // drafts have nothing to protect
  const s = draftSnapshot()
  const draft = run(decompile(s, null), { type: 'addNode', nodeType: 'guardrail' }, s)
  assert.equal(describeNode(node(draft, 'guardrail'), draft, s).protected, false)
  const del = run(draft, { type: 'removeItems', nodeIds: ['guardrail'], edgeIds: [] }, s)
  assert.ok(!ids(del).includes('guardrail'))
})

/* ---------------- 13. inherited policy nodes ---------------- */

test('13. project policies appear as locked inherited nodes', () => {
  const g = baseGraph()
  assert.ok(edge(g, 'project:p3/out->policy:p3/in').locked)
  assert.ok(edge(g, 'policy:p3/out->guardrail/in').locked)
  assert.equal(describeNode(node(g, 'policy:p3'), g, base).locked, true)
  assert.ok(describeNode(node(g, 'policy:p3'), g, base).lockReason.includes('프로젝트'))

  const off = run(g, { type: 'setAccess', projectNodeId: 'project:p3', granted: false }, base)
  assert.ok(!ids(off).includes('policy:p3'))
  assert.equal(edge(off, 'project:p3/out->guardrail/in'), undefined)

  // local LLMs all stopped: the blocked project would fail, the external one is skipped
  const stopped = props(g, 'deployment:a', { enabled: false }, base)
  const issues = validateGraph(stopped, base)
  const noLocal = issues.find((i) => i.code === 'POLICY_NO_LOCAL_LLM')
  assert.ok(noLocal && noLocal.severity === 'warning' && noLocal.nodeId === 'policy:p3')
  assert.ok(noLocal.message.includes('Gamma'))
  const skips = issues.find((i) => i.code === 'POLICY_SKIPS_EXTERNAL')
  assert.ok(skips && skips.severity === 'info' && skips.message.includes('Model X'))
  const healthy = validateGraph(g, base)
  assert.ok(!codes(healthy).includes('POLICY_NO_LOCAL_LLM'))
  assert.ok(codes(healthy).includes('POLICY_SKIPS_EXTERNAL'))

  // DLP project: signal without blocking external models
  const p4 = run(g, { type: 'addNode', nodeType: 'project', refId: 'p4' }, base)
  assert.ok(ids(p4).includes('policy:p4'))
  const signals = projectSignals(base.catalog.projects.find((p) => p.id === 'p4'))
  assert.deepEqual(signals.map((x) => [x.code, x.blocksExternal, x.label]), [['PROJECT_DLP', false, '민감정보: 사내 LLM만']])
  assert.deepEqual(projectSignals(base.catalog.projects.find((p) => p.id === 'p3')).map((x) => [x.code, x.blocksExternal]), [['PROJECT_LOCAL_ONLY', true]])
  const strictBoundary = { id: 'q', name: 'Q', status: 'ACTIVE', dataBoundaryPolicy: 'EXTERNAL_ALLOWED', maxDataBoundaryPolicy: 'INTERNAL_ONLY' }
  assert.deepEqual(projectSignals(strictBoundary).map((x) => [x.code, x.blocksExternal]), [['PROJECT_BOUNDARY', true]])
  assert.deepEqual(projectSignals({ ...strictBoundary, maxDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION' }).map((x) => x.blocksExternal), [false])
  assert.deepEqual(projectSignals({ ...strictBoundary, maxDataBoundaryPolicy: null, dlp: { mode: 'ENFORCE', externalAction: 'BLOCK' } }).map((x) => x.label), ['민감정보: 차단'])
  assert.deepEqual(projectSignals({ ...strictBoundary, maxDataBoundaryPolicy: null, dlp: { mode: 'MONITOR', externalAction: 'BLOCK' } }), [])
  assert.deepEqual(projectSignals(undefined), [])
  assert.deepEqual(projectSignals({ id: 'z', name: 'Z', status: 'ACTIVE', dataBoundaryPolicy: 'EXTERNAL_ALLOWED' }), [])

  const suggestion = validateGraph(run(g, { type: 'removeItems', nodeIds: ['guardrail'], edgeIds: [], force: true }, base), base).find((i) => i.code === 'EXTERNAL_LLM_WITHOUT_GUARDRAIL')
  assert.ok(suggestion && suggestion.nodeId === 'deployment:x')
  assert.deepEqual(suggestion.fix, { label: '가드레일 추가', command: { type: 'addNode', nodeType: 'guardrail' } })
  const org = validateGraph(g, { ...base, orgDataProtection: { mode: 'ENFORCE', externalAction: 'BLOCK' } })
  assert.ok(org.some((i) => i.code === 'ORG_POLICY_APPLIES' && i.severity === 'info'))
  assert.ok(codes(validateGraph(g, { ...base, projectPoliciesComplete: false })).includes('POLICY_UNVERIFIED'))
})

/* ---------------- 14. dependent nodes ---------------- */

test('14. dependency closure follows the ownership rules', () => {
  const g = baseGraph()
  assert.deepEqual(dependencyClosure(g, ['guardrail']), ['guardrail', 'masker'])
  assert.deepEqual(dependencyClosure(g, ['masker']), ['masker'])
  assert.deepEqual(dependencyClosure(g, ['deployment:a']), ['deployment:a', 'endpoint:ep1', 'prompt:a'])
  assert.deepEqual(dependencyClosure(g, ['project:p3']), ['policy:p3', 'project:p3'])
  assert.deepEqual(dependencyClosure(g, ['cache']), ['cache'])
  assert.deepEqual(dependencyClosure(g, ['deployment:x', 'deployment:a']), ['deployment:a', 'deployment:x', 'endpoint:ep1', 'prompt:a', 'provider:pv1'])
  assert.deepEqual(workflow.dependentsOf(g, 'deployment:a'), ['endpoint:ep1', 'prompt:a'])
  const shared = run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base)
  assert.ok(!dependencyClosure(shared, ['deployment:a']).includes('endpoint:ep1'), 'a host used by two LLMs is not a dependent')
  assert.deepEqual(dependencyClosure(g, ['nope']), ['nope'])
})

/* ---------------- 15/16. prompts ---------------- */

test('15. model-specific prompts: plan bodies, notes and server support', () => {
  const g = baseGraph()
  let x = run(g, { type: 'addNode', nodeType: 'prompt', spliceEdgeId: 'router/out->deployment:x/in' }, base)
  x = props(x, 'prompt:x', { systemPromptTemplate: '  Be formal  ' }, base)
  const p = plan(base, x)
  assert.deepEqual(p.operations.map((o) => o.opId), ['patch-target:t2'])
  assert.deepEqual(p.operations[0].body, { systemPromptTemplate: 'Be formal' })
  assert.equal(p.operations[0].risk, 'caution')
  assert.ok(p.notes.some((n) => n.level === 'info' && n.message.includes('모델별 시스템 프롬프트가 바뀌어 프롬프트 버전(현재 v3)')))
  assert.ok(!JSON.stringify(p.operations[0].changes).includes('Be formal'))
  assert.deepEqual(computeNodeChanges(x, base), { 'prompt:x': 'added' })

  const cleared = plan(base, props(g, 'prompt:a', { systemPromptTemplate: '  ' }, base))
  assert.deepEqual(cleared.operations.map((o) => [o.opId, o.body]), [['patch-target:t1', { clearSystemPromptTemplate: true }]])
  assert.equal(plan(base, props(g, 'prompt:a', { systemPromptTemplate: ' Be brief ' }, base)).operations.length, 0)
  assert.deepEqual(computeNodeChanges(props(g, 'prompt:a', { systemPromptTemplate: 'Other' }, base), base), { 'prompt:a': 'modified' })

  let fresh = run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base)
  fresh = run(fresh, { type: 'addNode', nodeType: 'prompt', spliceEdgeId: 'router/out->deployment:b/in' }, base)
  fresh = props(fresh, 'prompt:b', { systemPromptTemplate: 'Hello' }, base)
  const created = plan(base, fresh).operations.find((o) => o.kind === 'createTarget')
  assert.deepEqual(created.body, { deploymentId: 'b', priority: 3, weight: 100, degraded: false, followModelChanges: true, systemPromptTemplate: 'Hello' })
  const noPrompt = plan(base, run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base)).operations.find((o) => o.kind === 'createTarget')
  assert.equal('systemPromptTemplate' in noPrompt.body, false)

  const deleted = plan(base, run(g, { type: 'removeItems', nodeIds: ['deployment:a'], edgeIds: [] }, base))
  assert.ok(deleted.notes.some((n) => n.message.includes('모델별 시스템 프롬프트가 바뀌어')))

  const legacy = { ...base, features: { targetPrompt: false } }
  const issue = validateGraph(g, legacy).find((i) => i.code === 'TARGET_PROMPT_UNSUPPORTED')
  assert.ok(issue && issue.severity === 'error' && issue.nodeId === 'prompt:a')
  assert.ok(plan(legacy, g).blockingIssues.some((i) => i.code === 'TARGET_PROMPT_UNSUPPORTED'))
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'prompt', spliceEdgeId: 'router/out->deployment:x/in' }, legacy).error, '서버가 모델별 시스템 프롬프트를 지원하지 않습니다.')
  assert.deepEqual(insertableTypesForEdge(g, 'router/out->deployment:x/in', legacy), [])
  assert.ok(codes(validateGraph(props(g, 'prompt:a', { systemPromptTemplate: 'x'.repeat(16001) }, base), base)).includes('PROMPT_TOO_LONG'))
  assert.equal(buildPalette(legacy, g, { query: '', showInactive: false, mode: 'advanced' }).find((x2) => x2.id === 'prompt').nodes[0].draggable, false)
})

test('16. common prompt: normalization, version note and duplicate rule', () => {
  const g = baseGraph()
  const cleared = plan(base, props(g, 'prompt', { systemPromptTemplate: '  ' }, base))
  assert.deepEqual(cleared.operations.map((o) => o.body), [{ clearSystemPromptTemplate: true }])
  assert.equal(plan(base, props(g, 'prompt', { systemPromptTemplate: '  You are helpful \n' }, base)).operations.length, 0)
  const changed = plan(base, props(g, 'prompt', { systemPromptTemplate: 'New prompt' }, base))
  assert.deepEqual(changed.operations[0].body, { systemPromptTemplate: 'New prompt' })
  assert.ok(changed.notes.some((n) => n.message.includes('v3에서 v4')))
  assert.ok(!JSON.stringify(changed.operations[0].changes).includes('New prompt'))
  assert.equal(normalizePrompt('  a '), 'a')
  assert.equal(normalizePrompt('   '), null)
  assert.equal(canonicalCapabilities('["b","a","b"]'), '["a","b"]')
  assert.equal(canonicalCapabilities('not json'), 'not json')
  assert.ok(sameValue(null, undefined) && sameValue(0.1 + 0.2, 0.3))
  assert.equal(applyCommand(g, { type: 'addNode', nodeType: 'prompt' }, base).error, '공통 시스템 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선의 ＋를 누르세요.')
  const empty = props(g, 'prompt', { systemPromptTemplate: '' }, base)
  assert.ok(validateGraph(empty, base).some((i) => i.code === 'WF_NODE_NO_EFFECT' && i.nodeId === 'prompt'))
})

/* ---------------- 17. replace model ---------------- */

test('17. replaceModel swaps the LLM and keeps position, props and prompt', () => {
  const g = baseGraph()
  const before = node(g, 'deployment:a')
  const r = applyCommand(g, { type: 'replaceModel', nodeId: 'deployment:a', deploymentId: 'b' }, base)
  assert.equal(r.error, null)
  assert.deepEqual(r.select, { nodeIds: ['deployment:b'], edgeIds: [] })
  const h = r.graph
  const b = node(h, 'deployment:b')
  assert.equal(b.targetId, null)
  assert.equal(b.x, before.x)
  assert.equal(b.y, before.y)
  assert.equal(b.props.weight, 80)
  assert.ok(!ids(h).includes('deployment:a') && !ids(h).includes('prompt:a'))
  assert.ok(node(h, 'prompt:b'))
  assert.ok(edge(h, 'router/out->prompt:b/in') && edge(h, 'prompt:b/out->deployment:b/in').locked)
  assert.ok(edge(h, 'deployment:b/host->endpoint:ep1/in'))
  const p = plan(base, h)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-target:target:new-b', 'delete-target:t1'])
  assert.equal(p.operations[0].body.systemPromptTemplate, 'Be brief')
  assert.equal(p.operations[0].body.weight, 80)
  assert.equal(applyCommand(g, { type: 'replaceModel', nodeId: 'deployment:a', deploymentId: 'x' }, base).error, '이미 워크플로우에 있는 모델입니다.')
  assert.equal(applyCommand(g, { type: 'replaceModel', nodeId: 'deployment:a', deploymentId: 'nope' }, base).error, '알 수 없는 모델입니다.')
  assert.equal(applyCommand(g, { type: 'replaceModel', nodeId: 'cache', deploymentId: 'b' }, base).error, '알 수 없는 노드입니다.')
  const toExternal = run(g, { type: 'replaceModel', nodeId: 'deployment:a', deploymentId: 'y' }, base)
  assert.equal(node(toExternal, 'deployment:y').props.followModelChanges, false)
})

/* ---------------- 18. access ---------------- */

test('18. project access is drawn from the start node', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'project', refId: 'p2', autoWire: false }, base)
  assert.deepEqual(compile(g, base).accessProjectIds, ['p1', 'p3'])
  g = run(g, { type: 'connect', from: { nodeId: 'start', portId: 'out' }, to: { nodeId: 'project:p2', portId: 'in' } }, base)
  assert.deepEqual(compile(g, base).accessProjectIds, ['p1', 'p2', 'p3'])
  assert.ok(edge(g, 'project:p2/out->guardrail/in'))
  const manual = canConnect(g, { nodeId: 'project:p2', portId: 'out' }, { nodeId: 'cache', portId: 'in' }, base)
  assert.deepEqual(manual, { ok: false, reason: '자동으로 관리되는 간선입니다. 시작 노드에서 프로젝트로 간선을 그리면 접근이 허용됩니다.' })
  const wrong = canConnect(g, { nodeId: 'start', portId: 'out' }, { nodeId: 'cache', portId: 'in' }, base)
  assert.deepEqual(wrong, { ok: false, reason: '시작 노드는 프로젝트에만 연결합니다.' })

  const p = plan(base, g)
  assert.deepEqual(p.operations.map((o) => o.opId), ['grant-access:p2'])
  const partial = { ...fixture(), accessComplete: false }
  assert.ok(codes(validateGraph(g, partial)).includes('ACCESS_EDIT_UNAVAILABLE'))
  const pp = plan(partial, g)
  assert.ok(pp.blockingIssues.some((i) => i.code === 'ACCESS_EDIT_UNAVAILABLE'))
  assert.equal(pp.operations.filter((o) => o.kind === 'grantAccess' || o.kind === 'revokeAccess').length, 0)

  assert.ok(codes(validateGraph(decompile(variant((c) => { c.accessProjectIds = [] }), null), variant((c) => { c.accessProjectIds = [] }))).includes('WF_NO_PROJECT'))
  const hiddenSnapshot = { ...variant((c) => { c.accessProjectIds = [] }), projectsHidden: true, accessComplete: false }
  const hidden = codes(validateGraph(decompile(hiddenSnapshot, null), hiddenSnapshot))
  assert.ok(hidden.includes('PROJECTS_HIDDEN'))
  assert.ok(!hidden.includes('WF_NO_PROJECT'))
  assert.equal(describeNode(node(decompile(hiddenSnapshot, null), 'start'), decompile(hiddenSnapshot, null), hiddenSnapshot).badges.includes('프로젝트 숨김'), true)
})

/* ---------------- 19. domain regressions from v1 ---------------- */

function remoteWith(mutator) {
  const r = fixture()
  mutator(r)
  return r
}

test('19a. minimal patches, guardrail side effects, masker, parameters and new disabled LLMs', () => {
  const g = baseGraph()
  assert.equal(plan(base, g).operations.length, 0)
  assert.equal(plan(base, g).fingerprint, plan(base, g).fingerprint)
  assert.deepEqual(computeNodeChanges(g, base), {})

  const weight = props(g, 'deployment:a', { weight: 50 }, base)
  const w = plan(base, weight)
  assert.equal(w.operations.length, 1)
  assert.deepEqual(w.operations[0].body, { weight: 50 })
  assert.equal(w.operations[0].risk, 'safe')
  assert.equal(w.operations[0].opId, 'patch-target:t1')
  assert.deepEqual(computeNodeChanges(weight, base), { 'deployment:a': 'modified' })

  const allow = plan(base, props(g, 'guardrail', { guardrailAction: 'ALLOW' }, base))
  assert.deepEqual(allow.operations[0].body, { guardrailAction: 'ALLOW' })
  assert.equal(allow.operations[0].risk, 'danger')
  assert.ok(allow.notes.some((n) => n.level === 'warning' && n.message.startsWith('가드레일 처리 정책을 바꾸면')))
  const other = plan(base, run(run(g, { type: 'addNode', nodeType: 'params' }, base), { type: 'updateProps', nodeId: 'params', patch: { reasoningEffort: 'HIGH' } }, base))
  assert.ok(!('guardrailAction' in other.operations[0].body))
  assert.ok(!other.notes.some((n) => n.message.startsWith('가드레일')))

  const noMasker = plan(base, run(g, { type: 'removeItems', nodeIds: ['masker'], edgeIds: [] }, base))
  assert.deepEqual(noMasker.operations[0].body, { clearMaskerDeployment: true })
  assert.equal(noMasker.operations[0].risk, 'danger')
  assert.ok(codes(validateGraph(props(g, 'masker', { deploymentId: 'x' }, base), base)).includes('MASKER_NOT_ELIGIBLE'))
  assert.ok(codes(validateGraph(props(g, 'masker', { deploymentId: '' }, base), base)).includes('MASKER_NOT_SELECTED'))
  const unverified = { ...fixture(), unavailable: ['/api/admin/organizations/org1/masker-deployments'] }
  assert.ok(codes(validateGraph(g, unverified)).includes('MASKER_UNVERIFIED'))
  const swapMasker = run(run(g, { type: 'removeItems', nodeIds: ['masker'], edgeIds: [] }, base), { type: 'addNode', nodeType: 'masker', refId: 'a' }, base)
  assert.ok(edge(swapMasker, 'masker/masker->guardrail/masker'))
  assert.equal(applyCommand(swapMasker, { type: 'addNode', nodeType: 'masker' }, base).error, '마스킹 워커는 하나만 둘 수 있습니다.')
  assert.equal(plan(base, swapMasker).operations.length, 0)

  let params = run(g, { type: 'addNode', nodeType: 'params' }, base)
  const missing = props(params, 'params', { temperaturePolicy: 'FIXED' }, base)
  assert.ok(codes(validateGraph(missing, base)).includes('TEMPERATURE_VALUE_REQUIRED'))
  assert.ok(plan(base, missing).blockingIssues.some((i) => i.code === 'TEMPERATURE_VALUE_REQUIRED'))
  assert.ok(codes(validateGraph(props(params, 'params', { temperaturePolicy: 'FIXED', temperatureValue: 3 }, base), base)).includes('TEMPERATURE_VALUE_RANGE'))
  params = props(params, 'params', { temperaturePolicy: 'FIXED', temperatureValue: 0.7 }, base)
  const fixed = plan(base, params)
  assert.deepEqual(fixed.operations[0].body, { temperaturePolicy: 'FIXED', temperatureValue: 0.7 })
  assert.equal(fixed.blockingIssues.length, 0)

  const disabled = props(run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base), 'deployment:b', { enabled: false }, base)
  const d = plan(base, disabled)
  assert.deepEqual(d.operations.map((o) => o.opId), ['create-target:target:new-b', 'disable-created-target:target:new-b'])
  assert.deepEqual(d.operations[1].target, { kind: 'created', key: 'target:new-b' })
  assert.deepEqual(d.operations[1].body, { enabled: false })
  assert.deepEqual(d.operations[0].service, { kind: 'existing', id: 'svc1' })
  assert.deepEqual(computeNodeChanges(disabled, base)['deployment:b'], 'added')

  const del = plan(base, run(g, { type: 'removeItems', nodeIds: ['deployment:x'], edgeIds: [] }, base))
  assert.deepEqual(del.operations.map((o) => o.opId), ['delete-target:t2'])
  assert.equal(del.operations[0].risk, 'danger')
  const priority = plan(base, props(g, 'deployment:a', { priority: 5 }, base))
  assert.deepEqual(priority.operations.map((o) => o.body), [{ priority: 5 }])
  const clear = props(g, 'deployment:x', { maxConcurrencyOverride: '' }, base)
  assert.equal(node(clear, 'deployment:x').props.maxConcurrencyOverride, null)
  assert.ok(codes(validateGraph(clear, base)).includes('TARGET_CONCURRENCY_CLEAR_UNSUPPORTED'))
  assert.ok(codes(validateGraph(props(g, 'deployment:a', { weight: 0 }, base), base)).includes('TARGET_NUMBER_INVALID'))
})

test('19b. three-way planning, remote removal and adoption', () => {
  const remoteWeight = remoteWith((r) => { r.config.targets[0].weight = 50 })
  const same = plan(base, baseGraph(), remoteWeight)
  assert.equal(same.operations.length, 0)
  assert.equal(same.remoteDrift, true)
  assert.ok(same.notes.some((n) => n.message.startsWith('불러온 이후 다른 곳에서')))
  const mixed = plan(base, props(baseGraph(), 'deployment:a', { priority: 5 }, base), remoteWeight)
  assert.deepEqual(mixed.operations.map((o) => o.body), [{ priority: 5 }])

  const localWeight = props(baseGraph(), 'deployment:a', { weight: 10 }, base)
  const conflict = plan(base, localWeight, remoteWith((r) => { r.config.targets[0].weight = 20 }))
  assert.equal(conflict.conflicts.length, 1)
  assert.deepEqual([conflict.conflicts[0].code, conflict.conflicts[0].key, conflict.conflicts[0].field], ['FIELD_CONFLICT', 'target:t1', 'weight'])
  assert.equal(conflict.operations.length, 0)
  assert.equal(plan(base, localWeight, remoteWith((r) => { r.config.targets[0].weight = 10 })).operations.length, 0)
  const followed = plan(base, localWeight, remoteWith((r) => { r.config.targets[0].deploymentId = 'b' }))
  assert.deepEqual(followed.operations.map((o) => o.body), [{ weight: 10 }])
  const svcConflict = plan(base, props(baseGraph(), 'start', { displayName: 'Mine' }, base), remoteWith((r) => { r.config.service.displayName = 'Theirs' }))
  assert.equal(svcConflict.conflicts[0].field, 'displayName')

  const removed = remoteWith((r) => { r.config.targets = r.config.targets.filter((t) => t.id !== 't1') })
  const p = plan(base, props(baseGraph(), 'deployment:a', { weight: 10 }, base), removed)
  assert.equal(p.conflicts[0].code, 'TARGET_REMOVED_REMOTELY')
  assert.equal(plan(base, baseGraph(), removed).conflicts.length, 0)
  const deleted = run(baseGraph(), { type: 'removeItems', nodeIds: ['deployment:x'], edgeIds: [] }, base)
  const q = plan(base, deleted, remoteWith((r) => { r.config.targets[1].weight = 7 }))
  assert.equal(q.conflicts[0].code, 'TARGET_CHANGED_REMOTELY')
  assert.equal(plan(base, deleted, remoteWith((r) => { r.config.targets = r.config.targets.filter((t) => t.id !== 't2') })).operations.length, 0)

  // a target created remotely for the same model conflicts, then rebase adopts it
  const g = run(baseGraph(), { type: 'addNode', nodeType: 'llm', refId: 'b' }, base)
  const remote = remoteWith((r) => { r.config.targets.push(target('rb', 'b', { priority: 3 })); r.config.targets.sort((a, b2) => (a.key < b2.key ? -1 : 1)) })
  const adopt = plan(base, g, remote)
  assert.deepEqual(adopt.conflicts.map((c) => c.code), ['DEPLOYMENT_TARGETED_REMOTELY'])
  assert.equal(adopt.operations.filter((o) => o.kind === 'createTarget').length, 0)
  const result = rebase({ graph: g, base, remote })
  assert.equal(node(result.graph, 'deployment:b').targetId, 'rb')
  const again = plan(remote, result.graph, remote)
  assert.equal(again.conflicts.length, 0)
  assert.equal(again.operations.length, 0)

  // partial apply keeps coordinates and leaves only the unapplied work
  let partial = run(baseGraph(), { type: 'addNode', nodeType: 'llm', refId: 'b', position: { x: 1600, y: 480 } }, base)
  partial = props(partial, 'deployment:b', { enabled: false }, base)
  const remote9 = remoteWith((r) => { r.config.targets.push(target('real-9', 'b', { priority: 3 })); r.config.targets.sort((a, b2) => (a.key < b2.key ? -1 : 1)) })
  const rebased = rebase({ graph: partial, base, remote: remote9, createdTargetIds: { 'target:new-b': 'real-9' } })
  assert.equal(rebased.conflicts.length, 0)
  assert.equal(node(rebased.graph, 'deployment:b').targetId, 'real-9')
  assert.equal(node(rebased.graph, 'deployment:b').x, 1600)
  assert.equal(node(rebased.graph, 'deployment:b').y, 480)
  const rest = plan(remote9, rebased.graph, remote9)
  assert.deepEqual(rest.operations.map((o) => o.opId), ['patch-target:real-9'])
  assert.deepEqual(rest.operations[0].body, { enabled: false })
  const renamed = graphOps.renameTargetNodes(partial, { 'target:new-b': 'real-9' })
  assert.equal(node(renamed, 'deployment:b').targetId, 'real-9')
  assert.equal(node(partial, 'deployment:b').targetId, null, 'input graph untouched')
  const detached = graphOps.detachTargetNodes(renamed, ['target:real-9'])
  assert.equal(node(detached, 'deployment:b').targetId, null)

  // a swap whose pre-deletes succeeded keeps the replacements as pending creates
  let swap = run(baseGraph(), { type: 'replaceModel', nodeId: 'deployment:a', deploymentId: 'y' }, base)
  swap = run(swap, { type: 'replaceModel', nodeId: 'deployment:x', deploymentId: 'a' }, base)
  const emptyRemote = remoteWith((r) => { r.config.targets = [] })
  const kept = rebase({ graph: swap, base, remote: emptyRemote, createdTargetIds: {}, deletedTargetKeys: ['target:t1', 'target:t2'] })
  assert.deepEqual(kept.graph.nodes.filter((n) => n.type === 'llm').map((n) => n.targetId), [null, null])
  assert.deepEqual(compile(kept.graph, emptyRemote).targets.map((t) => t.deploymentId).sort(), ['a', 'y'])
  assert.equal(rebase({ graph: swap, base, remote: emptyRemote }).graph.nodes.filter((n) => n.type === 'llm').length, 2, 'replacements are pending creates, so a swap never loses data')
  // untouched persisted LLMs whose server rows this run deleted become pending creates; without the keys they would vanish
  const gone = rebase({ graph: baseGraph(), base, remote: emptyRemote })
  assert.equal(gone.graph.nodes.filter((n) => n.type === 'llm').length, 0, 'documents the data loss without deletedTargetKeys')
  const detachedAll = rebase({ graph: baseGraph(), base, remote: emptyRemote, deletedTargetKeys: ['target:t1', 'target:t2'] })
  assert.deepEqual(detachedAll.graph.nodes.filter((n) => n.type === 'llm').map((n) => n.targetId), [null, null])
  assert.deepEqual(compile(detachedAll.graph, emptyRemote).targets.map((t) => t.key).sort(), ['target:new-a', 'target:new-x'])
  const removedAndGone = rebase({
    graph: run(baseGraph(), { type: 'removeItems', nodeIds: ['deployment:x'], edgeIds: [] }, base), base,
    remote: remoteWith((r) => { r.config.targets = r.config.targets.filter((t) => t.id !== 't2') }), deletedTargetKeys: ['target:t2'],
  })
  assert.deepEqual(removedAndGone.graph.nodes.filter((n) => n.type === 'llm').map((n) => n.id), ['deployment:a'])
})

test('19c. new workflow ordering, scheduling, ranges, size limit and determinism', () => {
  const { s, g } = draftWith(['a'], ['p1'])
  const guarded = props(run(g, { type: 'addNode', nodeType: 'guardrail' }, s), 'guardrail', { dataBoundaryPolicy: 'INTERNAL_ONLY', guardrailAction: 'LOCAL_ONLY' }, s)
  const p = plan(s, guarded)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-service', 'patch-service', 'create-target:target:new-a', 'grant-access:p1'])
  const [create, patch, createTarget, grant] = p.operations
  assert.equal(create.body.serviceKey, 'text-new')
  assert.equal(create.body.dataBoundaryPolicy, 'INTERNAL_ONLY')
  assert.equal(create.body.organizationId, 'org1')
  assert.equal(create.body.temperatureValue, null)
  assert.ok(!('guardrailAction' in create.body))
  assert.deepEqual(patch.body, { guardrailAction: 'LOCAL_ONLY' })
  assert.deepEqual(createTarget.service, { kind: 'created' })
  assert.deepEqual(grant.service, { kind: 'created' })
  assert.ok(codes(validateGraph(props(guarded, 'start', { serviceKey: 'Text_New' }, s), s)).includes('SERVICE_KEY_INVALID'))
  assert.ok(codes(validateGraph(props(guarded, 'start', { serviceKey: 'text-pro' }, s), s)).includes('SERVICE_KEY_TAKEN'))
  const remote = clone(s)
  remote.catalog.services.push({ id: 'zz', serviceKey: 'text-new', displayName: 'Z', enabled: true })
  assert.ok(plan(s, guarded, remote).blockingIssues.some((i) => i.code === 'SERVICE_KEY_TAKEN'))

  const tooLong = props(baseGraph(), 'router', { schedulingMode: 'LOCAL_FIRST_WAIT', localWaitMs: 40000 }, base)
  assert.ok(codes(validateGraph(tooLong, base)).includes('SCHEDULING_LOCAL_WAIT_EXCEEDS_MAX'))
  assert.ok(codes(validateGraph(props(baseGraph(), 'router', { maxQueuedPerKey: 200 }, base), base)).includes('SCHEDULING_PER_KEY_EXCEEDS_TOTAL'))
  assert.ok(validateGraph(props(baseGraph(), 'router', { maxQueuedRequests: 9999 }, base), base).some((i) => i.code === 'SCHEDULING_RANGE' && i.field === 'maxQueuedRequests'))
  const remoteQueue = remoteWith((r) => { r.config.scheduling.maxQueuedBytes = 1048576 })
  const sched = props(baseGraph(), 'router', { schedulingMode: 'LOCAL_ONLY_QUEUE' }, base)
  const sp = plan(base, sched, remoteQueue)
  assert.equal(sp.operations.length, 1)
  assert.equal(sp.operations[0].kind, 'updateScheduling')
  assert.deepEqual(sp.operations[0].body, { ...remoteQueue.config.scheduling, mode: 'LOCAL_ONLY_QUEUE' })
  assert.deepEqual(computeNodeChanges(sched, base), { router: 'modified' })

  const ttl = props(baseGraph(), 'cache', { responseCacheTtlSeconds: 5 }, base)
  assert.ok(plan(base, ttl).blockingIssues.some((i) => i.code === 'CACHE_TTL_RANGE'))
  assert.ok(codes(validateGraph(props(baseGraph(), 'start', { displayName: '   ' }, base), base)).includes('DISPLAY_NAME_REQUIRED'))
  assert.ok(codes(validateGraph(props(baseGraph(), 'start', { requiredCapabilitiesJson: '{"a":1}' }, base), base)).includes('CAPABILITIES_INVALID'))
  assert.ok(codes(validateGraph(props(baseGraph(), 'start', { inputPricePerMillion: -1 }, base), base)).includes('PRICE_INVALID'))

  const targets = []
  for (let i = 0; i < 101; i += 1) targets.push({ key: `target:new-d${i}`, id: null, deploymentId: `d${i}`, priority: i + 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true })
  const big = planChanges({ base, desired: { ...clone(base.config), targets }, remote: base, issues: [] })
  assert.ok(big.operations.length > 100)
  assert.ok(big.blockingIssues.some((i) => i.code === 'PLAN_TOO_LARGE'))
  const desired = clone(base.config)
  desired.service.displayName = 'Renamed'
  desired.scheduling.mode = 'LOCAL_FIRST_WAIT'
  desired.targets = [
    { ...desired.targets[1], weight: 55 },
    target(null, 'a', { key: 'target:new-a', id: null, priority: 4 }),
    target(null, 'b', { key: 'target:new-b', id: null, priority: 3, enabled: false }),
  ]
  desired.accessProjectIds = ['p2']
  const input = { base, desired, remote: base, issues: [] }
  const ordered = planChanges(input)
  assert.deepEqual(ordered.operations.map((o) => o.opId), [
    'patch-service', 'update-scheduling', 'delete-target:t1',
    'create-target:target:new-b', 'disable-created-target:target:new-b', 'create-target:target:new-a',
    'patch-target:t2', 'grant-access:p2', 'revoke-access:p1', 'revoke-access:p3',
  ])
  assert.equal(planChanges({ ...input, desired: { ...desired, targets: [...desired.targets].reverse() } }).fingerprint, ordered.fingerprint)
  assert.deepEqual(planChanges(input), ordered)
  assert.equal(stableStringify({ b: 1, a: { d: undefined, c: [1, undefined] } }), '{"a":{"c":[1,null]},"b":1}')
})

/* ---------------- 20. syncGraphToConfig ---------------- */

test('20. syncGraphToConfig reconciles a graph with a newer server state', () => {
  const noCache = variant((c) => { c.service.responseCacheEnabled = false })
  const g = decompile(noCache, null)
  const added = syncGraphToConfig(g, base.config, base)
  assert.deepEqual(analyzeWorkflow(added, base).trunk, ['guardrail', 'prompt', 'cache', 'router'])
  assert.deepEqual(compile(added, base), base.config)
  assert.deepEqual(edgeIds(added, 'access').sort(), ['policy:p3/out->guardrail/in', 'project:p1/out->guardrail/in'])

  const unguarded = variant((c) => { c.service = { ...c.service, guardrailAction: 'ALLOW', maskerDeploymentId: null } })
  const healed = syncGraphToConfig(baseGraph(), unguarded.config, unguarded)
  assert.ok(!ids(healed).includes('guardrail') && !ids(healed).includes('masker'))
  assert.equal(analyzeWorkflow(healed, unguarded).head, 'prompt')
  assert.deepEqual(compile(healed, unguarded), unguarded.config)
  assert.equal(analyzeWorkflow(healed, unguarded).issues.length, 0)

  const rebound = variant((c) => { c.targets[0] = { ...c.targets[0], deploymentId: 'b' } })
  const g2 = baseGraph()
  const moved = run(g2, { type: 'moveNodes', moves: [{ nodeId: 'deployment:a', x: 2222, y: 333 }] }, base)
  const renamed = syncGraphToConfig(moved, rebound.config, rebound)
  assert.ok(ids(renamed).includes('deployment:b') && !ids(renamed).includes('deployment:a'))
  assert.ok(ids(renamed).includes('prompt:b') && !ids(renamed).includes('prompt:a'))
  assert.equal(node(renamed, 'deployment:b').x, 2222)
  assert.equal(node(renamed, 'deployment:b').y, 333)
  assert.equal(node(renamed, 'deployment:b').targetId, 't1')
  assert.deepEqual(compile(renamed, rebound), rebound.config)

  const newTarget = variant((c) => { c.targets.push(target('t3', 'b', { priority: 3 })) })
  const grown = syncGraphToConfig(baseGraph(), newTarget.config, newTarget)
  assert.ok(edge(grown, 'router/out->deployment:b/in'))
  assert.equal(node(grown, 'deployment:b').targetId, 't3')
  assert.deepEqual(compile(grown, newTarget), newTarget.config)
  assert.equal(analyzeWorkflow(grown, newTarget).issues.length, 0)

  // targets shrinking to one drops the router again and heals the path
  const shrunk = variant((c) => { c.targets = [c.targets[0]]; c.service.failoverPolicy = 'STRICT' })
  const lean = syncGraphToConfig(baseGraph(), shrunk.config, shrunk)
  assert.ok(!ids(lean).includes('router'))
  assert.ok(edge(lean, 'cache/out->prompt:a/in'))
  assert.deepEqual(compile(lean, shrunk), shrunk.config)

  // unconnected project nodes survive, removed access drops the entry edge only
  const withP2 = run(baseGraph(), { type: 'addNode', nodeType: 'project', refId: 'p2', autoWire: false }, base)
  const kept = syncGraphToConfig(withP2, base.config, base)
  assert.ok(ids(kept).includes('project:p2'))
  const revoked = syncGraphToConfig(baseGraph(), variant((c) => { c.accessProjectIds = ['p3'] }).config, base)
  assert.ok(ids(revoked).includes('project:p1'))
  assert.deepEqual(compile(revoked, base).accessProjectIds, ['p3'])
})

/* ---------------- 21. layout ---------------- */

test('21. layout: patterns, generations, geometry, auto layout and persistence', () => {
  for (const id of ['start', 'params', 'policy:x', 'prompt:x', 'deployment:x', 'router', 'service']) assert.ok(layout.LAYOUT_NODE_ID_PATTERN.test(id), id)
  for (const id of ['evil', 'prompt:../x', 'policy:', 'start:x']) assert.equal(layout.LAYOUT_NODE_ID_PATTERN.test(id), false, id)
  assert.equal(layout.LAYOUT_NODE_ID_PATTERN_V1.test('start'), false)
  assert.equal(layout.LAYOUT_NODE_ID_PATTERN_V1.test('target:abc'), true)
  assert.equal(layout.layoutGeneration(null), 2)
  assert.equal(layout.layoutGeneration({ version: 1, viewport: { x: 0, y: 0, zoom: 1 }, nodes: {} }), 2)
  assert.equal(layout.layoutGeneration({ version: 1, viewport: { x: 0, y: 0, zoom: 1 }, nodes: { start: { x: 0, y: 0 } } }), 2)
  const v1 = { version: 1, viewport: { x: 0, y: 0, zoom: 1 }, nodes: { service: { x: 9, y: 9 }, router: { x: 5, y: 5 } } }
  assert.equal(layout.layoutGeneration(v1), 1)
  const g = baseGraph()
  assert.deepEqual(layout.applyLayout(g, v1, base), layout.autoLayout(g, base))

  // geometry
  const llm = node(g, 'deployment:a')
  const guard = node(g, 'guardrail')
  assert.deepEqual(layout.nodeSize(llm), { width: 232, height: 40 + 24 + 56 + 8 })
  assert.equal(layout.nodeSize(guard).height, 40 + 48 + 56 + 8)
  assert.deepEqual(layout.portAnchor(llm, 'in'), { x: llm.x, y: llm.y + 52 })
  assert.deepEqual(layout.portAnchor(llm, 'host'), { x: llm.x + 232, y: llm.y + 52 })
  assert.deepEqual(layout.portAnchor(guard, 'masker'), { x: guard.x, y: guard.y + 76 })
  assert.deepEqual(layout.portAnchor(guard, 'out', 'out'), { x: guard.x + 232, y: guard.y + 52 })
  assert.equal(layout.portAnchor({ ...llm, collapsed: true }, 'host').y, llm.y + 20)
  assert.equal(layout.bezierPath({ x: 0, y: 0 }, { x: 100, y: 50 }), 'M 0 0 C 50 0, 50 50, 100 50')
  assert.equal(layout.bezierPath({ x: 0, y: 0 }, { x: 10, y: 0 }), 'M 0 0 C 40 0, -30 0, 10 0')
  assert.equal(layout.fitViewport({ x: 0, y: 0, width: 100000, height: 80000 }, { width: 800, height: 600 }).zoom, layout.MIN_ZOOM)
  const small = layout.fitViewport({ x: 0, y: 0, width: 100, height: 100 }, { width: 800, height: 600 })
  assert.deepEqual([small.zoom, small.x, small.y], [1, 350, 250])
  const vp = { x: 30, y: -20, zoom: 0.8 }
  const cursor = { x: 400, y: 300 }
  const back = layout.worldToScreen(layout.screenToWorld(cursor, vp), layout.zoomAt(vp, cursor, 1.5))
  assert.ok(Math.abs(back.x - cursor.x) < 1e-9 && Math.abs(back.y - cursor.y) < 1e-9)
  assert.equal(layout.zoomAt(vp, cursor, 100).zoom, layout.MAX_ZOOM)
  assert.equal(layout.clampZoom(0.01), layout.MIN_ZOOM)
  assert.equal(layout.snapToGrid(23), 16)
  assert.equal(layout.snapToGrid(25), 32)
  assert.equal(Object.is(layout.snapToGrid(-3), 0), true)
  const start = node(g, 'start')
  assert.deepEqual(layout.nodesInRect(g, { x: start.x - 5, y: start.y - 5, width: 10, height: 10 }), ['start'])
  assert.deepEqual(layout.nodesInRect(g, { x: -5000, y: -5000, width: 1, height: 1 }), [])

  // auto layout: deterministic, lanes in order, no overlap inside a lane, local LLMs first
  let busy = run(g, { type: 'addNode', nodeType: 'project', refId: 'p2', autoWire: false }, base)
  busy = run(busy, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base)
  busy = run(busy, { type: 'addNode', nodeType: 'params' }, base)
  const a1 = layout.autoLayout(busy, base)
  assert.deepEqual(a1, layout.autoLayout(busy, base))
  const lanes = new Map()
  for (const n of a1.nodes) {
    assert.equal(n.y % 16, 0, n.id)
    lanes.set(n.x, [...(lanes.get(n.x) ?? []), layout.nodeRect(n)])
  }
  for (const [, rects] of lanes) {
    for (let i = 0; i < rects.length; i += 1) for (let j = i + 1; j < rects.length; j += 1) assert.equal(rectsOverlap(rects[i], rects[j]), false)
  }
  const x = (id) => node(a1, id).x
  assert.ok(x('start') < x('project:p1') && x('project:p1') < x('policy:p3') && x('policy:p3') < x('guardrail'))
  assert.ok(x('guardrail') < x('prompt') && x('prompt') < x('cache') && x('cache') < x('params') && x('params') < x('router'))
  assert.ok(x('router') < x('prompt:a') && x('prompt:a') < x('deployment:a') && x('deployment:a') < x('endpoint:ep1'))
  assert.equal(x('deployment:a'), x('deployment:x'))
  assert.equal(node(a1, 'prompt:a').y, node(a1, 'deployment:a').y)
  assert.ok(node(a1, 'deployment:a').y < node(a1, 'deployment:b').y && node(a1, 'deployment:b').y < node(a1, 'deployment:x').y, 'local first, then by priority')
  assert.equal(node(a1, 'masker').y, 208)
  assert.ok(node(a1, 'project:p1').y < node(a1, 'project:p3').y && node(a1, 'project:p2').y > node(a1, 'project:p3').y, 'granted projects first')
  assert.equal(layout.placeNewNode(a1, 'project:p2', base).nodes.find((n) => n.id === 'project:p2').y, node(a1, 'project:p2').y)

  // sanitize / extract
  const raw = {
    version: 1,
    viewport: { x: Number.NaN, y: 5, zoom: 10 },
    nodes: {
      start: { x: 1, y: 2 },
      'bad id': { x: 1, y: 2 },
      'target:abc': { x: Number.NaN, y: 1 },
      'project:p1': { x: 2e6, y: 0 },
      'deployment:a': { x: 3, y: 4, collapsed: true, extra: 'drop' },
      router: { x: 1, y: 2, collapsed: false },
      'prompt:../x': { x: 1, y: 1 },
    },
  }
  assert.deepEqual(layout.sanitizeLayout(raw), { version: 1, viewport: { x: 0, y: 5, zoom: 2 }, nodes: { 'deployment:a': { x: 3, y: 4, collapsed: true }, router: { x: 1, y: 2 }, start: { x: 1, y: 2 } } })
  assert.equal(layout.sanitizeLayout({ ...raw, version: 2 }), null)
  assert.equal(layout.sanitizeLayout(null), null)
  assert.equal(layout.sanitizeLayout('x'), null)
  const extracted = layout.extractLayout(run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base), { x: 1, y: 2, zoom: 1 })
  assert.ok(extracted.nodes['deployment:b'] && extracted.nodes['prompt:a'] && extracted.nodes['policy:p3'] && extracted.nodes.start)
  const text = JSON.stringify(extracted)
  for (const secret of ['Text Pro', 'You are helpful', 'Be brief', 'http://', 'GPU Box', 'Model A', 'text-pro']) assert.ok(!text.includes(secret), secret)
  const restored = layout.applyLayout(g, { ...extracted, nodes: { ...extracted.nodes, start: { x: 7, y: 9, collapsed: true } } }, base)
  assert.equal(node(restored, 'start').x, 7)
  assert.equal(node(restored, 'start').collapsed, true)
  assert.ok(restored.nodes.every((n) => Number.isFinite(n.x) && Number.isFinite(n.y)))

  // edge hit testing
  const from = layout.portAnchor(node(g, 'guardrail'), 'out', 'out')
  const to = layout.portAnchor(node(g, 'prompt'), 'in', 'in')
  const mid = { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 }
  assert.equal(layout.nearestEdge(g, mid, 16, ['flow']), 'guardrail/out->prompt/in')
  assert.equal(layout.nearestEdge(g, mid, 16, ['access']), null)
  assert.equal(layout.nearestEdge(g, { x: mid.x, y: mid.y + 500 }, 16, ['flow']), null)
  assert.equal(layout.nearestEdge(g, { x: -9999, y: -9999 }, 16, ['flow', 'route', 'access']), null)

  // makeRoom: a spliced node pushes the downstream nodes right and never overlaps
  const spliced = layout.placeSpliced({ ...g, nodes: [...g.nodes, makeNode('params', 'params', null)], edges: g.edges }, 'params', 'guardrail', 'prompt')
  assert.equal(node(spliced, 'params').x, node(g, 'guardrail').x + layout.COLUMN_WIDTH)
  assert.ok(node(spliced, 'prompt').x >= node(spliced, 'params').x + layout.COLUMN_WIDTH)
  assert.ok(node(spliced, 'deployment:a').x > node(g, 'deployment:a').x)
  assert.equal(node(spliced, 'guardrail').x, node(g, 'guardrail').x)
})

/* ---------------- 22. palette ---------------- */

test('22. palette groups, hiding, sorting, search and mode', () => {
  const g = baseGraph()
  const opts = { query: '', showInactive: false, mode: 'advanced' }
  const groups = buildPalette(base, g, opts)
  assert.deepEqual(groups.map((x) => x.id), ['entry', 'guard', 'prompt', 'cache', 'routing', 'llm-local', 'llm-external'])
  assert.deepEqual(groups.map((x) => x.label), ['시작 · 프로젝트', '가드레일 · 데이터 보호', '프롬프트', '캐시', '라우팅', '로컬 LLM', '외부 LLM'])
  const group = (id, o = opts, gr = g, s = base) => buildPalette(s, gr, o).find((x) => x.id === id)
  assert.deepEqual(group('entry').projects.map((p) => [p.projectId, p.granted, p.onCanvas, p.guarded]), [
    ['p1', true, true, false], ['p2', false, false, false], ['p3', true, true, true], ['p4', false, false, true],
  ].sort((a, b) => ['Alpha', 'Beta', 'Delta', 'Gamma'].indexOf(base.catalog.projects.find((p) => p.id === a[0]).name) - ['Alpha', 'Beta', 'Delta', 'Gamma'].indexOf(base.catalog.projects.find((p) => p.id === b[0]).name)))

  const local = group('llm-local')
  assert.deepEqual(local.models.map((m) => [m.deploymentId, m.availability]), [['a', 'active'], ['c', 'degraded']])
  assert.equal(local.hiddenCount, 1)
  assert.deepEqual(group('llm-local', { ...opts, showInactive: true }).models.map((m) => [m.deploymentId, m.availability, m.reason]), [['a', 'active', null], ['c', 'degraded', '상태 확인 필요'], ['b', 'inactive', '미로드']])
  const external = group('llm-external')
  assert.deepEqual(external.models.map((m) => m.deploymentId), ['x'])
  assert.equal(external.hiddenCount, 1)
  assert.equal(external.models[0].sourceName, 'Cloud AI')
  assert.equal(external.models[0].onCanvas, true)
  assert.deepEqual(group('llm-external', { ...opts, showInactive: true }).models.map((m) => [m.deploymentId, m.reason]), [['x', null], ['y', 'API 키 미설정']])
  assert.equal(local.models[0].sourceName, 'GPU Box')

  // an inactive model that is already in the workflow stays visible
  const withB = run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base)
  assert.deepEqual(group('llm-local', opts, withB).models.map((m) => m.deploymentId), ['a', 'c', 'b'])
  assert.equal(group('llm-local', opts, withB).hiddenCount, 0)

  // availability reasons
  const cat = base.catalog
  assert.deepEqual(modelAvailability({ ...cat.deployments[0], enabled: false }, cat), { availability: 'inactive', reason: '비활성 모델' })
  assert.deepEqual(modelAvailability(cat.deployments[0], { ...cat, endpoints: [{ ...cat.endpoints[0], enabled: false }] }), { availability: 'inactive', reason: 'Runtime 비활성' })
  assert.deepEqual(modelAvailability(cat.deployments.find((d) => d.id === 'x'), { ...cat, providers: [{ ...cat.providers[0], enabled: false }, cat.providers[1]] }), { availability: 'inactive', reason: 'Provider 비활성' })
  assert.deepEqual(modelAvailability({ ...cat.deployments[0], healthStatus: 'UNKNOWN' }, cat), { availability: 'active', reason: null })

  // node items
  const items = (id, gr = g, s = base) => Object.fromEntries(group(id, opts, gr, s).nodes.map((n) => [n.nodeType, n]))
  assert.deepEqual(items('guard').guardrail, { nodeType: 'guardrail', label: '외부 노출 방지 가드레일', description: items('guard').guardrail.description, icon: '⛨', clickDisabledReason: '이미 워크플로우에 있습니다. 클릭하면 선택합니다.', draggable: false, onCanvas: true })
  assert.equal(items('guard').masker.clickDisabledReason, '이미 있습니다.')
  assert.equal(items('prompt').prompt.clickDisabledReason, '공통 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선에 끌어 놓거나 ＋를 누르세요.')
  assert.equal(items('prompt').prompt.draggable, true)
  assert.equal(items('prompt').params.clickDisabledReason, null)
  assert.equal(items('cache').cache.clickDisabledReason, '이미 워크플로우에 있습니다.')
  assert.equal(items('routing').router.onCanvas, true)
  const empty = decompile(draftSnapshot(), null)
  assert.equal(items('guard', empty, draftSnapshot()).masker.clickDisabledReason, '가드레일을 먼저 추가하세요.')
  assert.equal(items('guard', empty, draftSnapshot()).guardrail.clickDisabledReason, null)
  assert.equal(items('routing', empty, draftSnapshot()).router.draggable, true)

  // search
  const found = buildPalette(base, g, { ...opts, query: ' CLOUD ' })
  assert.deepEqual(found.map((x) => x.id), ['llm-external'])
  assert.deepEqual(found[0].models.map((m) => m.deploymentId), ['x'])
  assert.equal(found[0].hiddenCount, 0)
  assert.deepEqual(buildPalette(base, g, { ...opts, query: 'gpt-y' }).map((x) => [x.id, x.models.map((m) => m.deploymentId)]), [['llm-external', ['y']]])
  assert.deepEqual(buildPalette(base, g, { ...opts, query: '캐시' }).map((x) => x.id), ['cache'])
  assert.deepEqual(buildPalette(base, g, { ...opts, query: 'beta' }).map((x) => [x.id, x.projects.map((p) => p.projectId)]), [['entry', ['p2']]])
  assert.deepEqual(buildPalette(base, g, { ...opts, query: '로컬' }).map((x) => x.id), ['llm-local'])
  assert.deepEqual(buildPalette(base, g, { ...opts, query: 'zzz-no-match' }), [])
  assert.deepEqual(buildPalette(base, g, { ...opts, query: 'dlp' }).map((x) => x.id), ['guard'])

  // basic mode hides advanced node items
  const basic = buildPalette(base, g, { ...opts, mode: 'basic' })
  assert.deepEqual(basic.find((x) => x.id === 'guard').nodes.map((n) => n.nodeType), ['guardrail'])
  assert.deepEqual(basic.find((x) => x.id === 'prompt').nodes.map((n) => n.nodeType), ['prompt'])
  assert.deepEqual(group('prompt').nodes.map((n) => n.nodeType), ['prompt', 'params'])
  assert.deepEqual(buildPalette({ ...base, projectsHidden: true }, g, opts).find((x) => x.id === 'entry').projects, [])
})

/* ---------------- 23. templates ---------------- */

test('23. applyTemplate builds the three starter workflows', () => {
  const s = draftSnapshot()
  const empty = decompile(s, null)
  assert.equal(isEmptyWorkflow(empty), true)
  const basic = run(empty, { type: 'applyTemplate', template: 'basic', projectIds: ['p1', 'p2'], deploymentIds: ['a'] }, s)
  assert.deepEqual(compile(basic, s).accessProjectIds, ['p1', 'p2'])
  assert.deepEqual(compile(basic, s).targets.map((t) => t.deploymentId), ['a'])
  assert.equal(node(basic, 'router'), undefined)
  assert.equal(isEmptyWorkflow(basic), false)
  assert.deepEqual(applyCommand(empty, { type: 'applyTemplate', template: 'basic', projectIds: [], deploymentIds: ['a', 'x'] }, s).graph.nodes.some((n) => n.id === 'router'), true)
  assert.equal(applyCommand(empty, { type: 'applyTemplate', template: 'basic', projectIds: [], deploymentIds: [] }, s).error, 'LLM을 하나 이상 고르세요.')
  assert.deepEqual(applyCommand(empty, { type: 'applyTemplate', template: 'basic', projectIds: [], deploymentIds: ['a'] }, s).select, { nodeIds: ['start'], edgeIds: [] })

  const lf = run(empty, { type: 'applyTemplate', template: 'local-first', projectIds: ['p1'], deploymentIds: ['x', 'a'] }, s)
  const lfc = compile(lf, s)
  assert.equal(lfc.service.guardrailAction, 'REDACT_RESTORE')
  assert.equal(lfc.service.dataBoundaryPolicy, 'EXTERNAL_ALLOWED')
  assert.deepEqual(lfc.targets.map((t) => [t.deploymentId, t.priority]).sort(), [['a', 1], ['x', 2]])
  assert.ok(node(lf, 'router'))
  assert.equal(lfc.service.failoverPolicy, 'COMPATIBLE')
  assert.equal(compile(run(empty, { type: 'applyTemplate', template: 'basic', projectIds: [], deploymentIds: ['a', 'x'] }, s), s).service.failoverPolicy, 'STRICT')
  assert.equal(analyzeWorkflow(lf, s).head, 'guardrail')
  assert.equal(presetOf(node(lf, 'guardrail').props), 'mask-restore')
  assert.equal(applyCommand(empty, { type: 'applyTemplate', template: 'local-first', projectIds: [], deploymentIds: ['a'] }, s).error, '로컬 LLM과 외부 LLM을 하나 이상씩 고르세요.')
  assert.equal(applyCommand(empty, { type: 'applyTemplate', template: 'local-first', projectIds: [], deploymentIds: ['x'] }, s).error, '로컬 LLM과 외부 LLM을 하나 이상씩 고르세요.')

  const io = run(empty, { type: 'applyTemplate', template: 'internal-only', projectIds: [], deploymentIds: ['a'] }, s)
  assert.equal(compile(io, s).service.dataBoundaryPolicy, 'INTERNAL_ONLY')
  assert.equal(compile(io, s).service.guardrailAction, 'LOCAL_ONLY')
  assert.equal(node(io, 'router'), undefined)
  assert.equal(applyCommand(empty, { type: 'applyTemplate', template: 'internal-only', projectIds: [], deploymentIds: ['a', 'x'] }, s).error, '사내 전용 템플릿에는 로컬 LLM만 쓸 수 있습니다.')
  assert.equal(applyCommand(basic, { type: 'applyTemplate', template: 'basic', projectIds: [], deploymentIds: ['a'] }, s).error, '빈 워크플로우에서만 템플릿을 적용할 수 있습니다.')
  const projectsOnly = run(empty, { type: 'addNode', nodeType: 'project', refId: 'p1' }, s)
  assert.equal(isEmptyWorkflow(projectsOnly), true)
  assert.equal(validateGraph(lf, s).filter((i) => i.severity === 'error' && !['SERVICE_KEY_INVALID', 'DISPLAY_NAME_REQUIRED'].includes(i.code)).length, 0)
})

/* ---------------- 24. node change badges ---------------- */

test('24. computeNodeChanges for v2 nodes', () => {
  const g = baseGraph()
  assert.deepEqual(computeNodeChanges(g, base), {})
  const modifiedGuardrail = props(g, 'guardrail', { maskPersonNames: false }, base)
  assert.deepEqual(computeNodeChanges(modifiedGuardrail, base), { guardrail: 'modified' })
  const noGuard = variant((c) => { c.service = { ...c.service, guardrailAction: 'ALLOW', maskerDeploymentId: null } })
  const added = run(decompile(noGuard, null), { type: 'addNode', nodeType: 'guardrail' }, noGuard)
  assert.deepEqual(computeNodeChanges(added, noGuard), { guardrail: 'added' })
  const effectless = props(added, 'guardrail', { guardrailAction: 'ALLOW', dataBoundaryPolicy: 'EXTERNAL_ALLOWED' }, noGuard)
  assert.deepEqual(computeNodeChanges(effectless, noGuard), {})
  const withNew = run(run(g, { type: 'addNode', nodeType: 'llm', refId: 'b' }, base), { type: 'addNode', nodeType: 'project', refId: 'p2' }, base)
  assert.deepEqual(computeNodeChanges(withNew, base), { 'deployment:b': 'added', 'project:p2': 'added' })
  const edited = props(run(g, { type: 'setAccess', projectNodeId: 'project:p1', granted: false }, base), 'deployment:x', { weight: 5 }, base)
  assert.deepEqual(computeNodeChanges(edited, base), { 'deployment:x': 'modified', 'project:p1': 'modified' })
  const name = props(g, 'start', { displayName: 'Other' }, base)
  assert.deepEqual(computeNodeChanges(name, base), { start: 'modified' })
  const lean = variant((c) => { c.targets = [c.targets[0]]; c.service.failoverPolicy = 'STRICT' })
  const newRouter = run(decompile(lean, null), { type: 'addNode', nodeType: 'router' }, lean)
  assert.equal(computeNodeChanges(newRouter, lean).router, undefined, 'a router with default values changes nothing')
  assert.ok(validateGraph(newRouter, lean).some((i) => i.code === 'WF_NODE_NO_EFFECT' && i.nodeId === 'router'))
})

/* ---------------- 25. view models ---------------- */

test('25. describeNode / nodeFacts / describeEdge work for every node type', () => {
  const g = baseGraph()
  for (const n of g.nodes) {
    const view = describeNode(n, g, base)
    assert.ok(view.title && view.typeLabel && view.icon, n.id)
    assert.ok(view.summary.length <= 2)
    assert.ok(['healthy', 'warning', 'error', 'muted', 'unknown'].includes(view.tone))
    assert.ok(Array.isArray(nodeFacts(n, g, base)))
    assert.equal(typeof view.protected, 'boolean')
    assert.ok(view.promptScope === null || n.type === 'prompt')
  }
  const view = (id) => describeNode(node(g, id), g, base)
  assert.equal(view('start').locked, true)
  assert.equal(view('start').lockReason, '시작 노드는 항상 있습니다.')
  assert.equal(view('start').subtitle, 'model: text-pro')
  assert.deepEqual(view('start').summary, ['허용 프로젝트 2개', 'Prompt v3'])
  assert.equal(view('deployment:a').title, 'Model A')
  assert.equal(view('deployment:a').subtitle, '로컬 · GPU Box')
  assert.equal(view('deployment:a').summary[0], 'P1 · W80 (100%)')
  assert.equal(view('deployment:x').subtitle, '외부 · Cloud AI')
  assert.equal(view('deployment:x').badges.includes('모델 고정'), false)
  assert.equal(view('prompt').promptScope, 'common')
  assert.equal(view('prompt:a').promptScope, 'model')
  assert.equal(view('prompt:a').subtitle, '모델별 · Model A')
  assert.equal(view('guardrail').protected, true)
  assert.equal(view('guardrail').subtitle, '민감정보 요청 차단')
  assert.ok(view('guardrail').badges.includes('외부 허용'))
  assert.equal(view('policy:p3').locked, true)
  assert.equal(view('policy:p3').title, '보호 정책 · Gamma')
  assert.equal(view('project:p3').badges.includes('보호 정책'), true)
  assert.equal(view('endpoint:ep1').locked, true)
  assert.equal(view('router').summary[1], '사용 중 LLM 2개')
  assert.equal(nodeFacts(node(g, 'deployment:a'), g, base).find((r) => r.label === 'Target ID').value, 't1')
  assert.equal(nodeFacts(node(g, 'start'), g, base).find((r) => r.label === '조직 데이터 보호').value, '정보 없음')
  assert.ok(nodeFacts(node(g, 'policy:p3'), g, base)[0].label.includes('외부 AI 전송 금지'))
  for (const e of g.edges) assert.ok(describeEdge(e).label, e.id)
  assert.equal(describeEdge(edge(g, 'start/out->project:p1/in')).label, '접근 허용')
  assert.equal(describeEdge(edge(g, 'project:p3/out->policy:p3/in')).label, '보호 정책 적용')
  assert.equal(describeEdge(edge(g, 'project:p1/out->guardrail/in')).label, '처리 시작')
  assert.equal(describeEdge(edge(g, 'router/out->deployment:x/in')).label, 'LLM 분기')
  const unknown = { ...node(g, 'deployment:a'), refId: 'nope' }
  assert.equal(describeNode(unknown, g, base).title, '알 수 없는 모델')
})

/* ---------------- 26. connection reasons ---------------- */

test('26. canConnect reasons and nearestCompatiblePort', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'llm', refId: 'b', autoWire: false }, base)
  g = run(g, { type: 'addNode', nodeType: 'params', autoWire: false }, base)
  const cc = (from, to) => canConnect(g, from, to, base)
  const reason = (from, to) => { const r = cc(from, to); assert.equal(r.ok, false, JSON.stringify([from, to])); return r.reason }
  const P = (nodeId, portId) => ({ nodeId, portId })
  assert.equal(reason(P('nope', 'out'), P('cache', 'in')), '간선을 이을 노드를 찾을 수 없습니다.')
  assert.equal(reason(P('cache', 'out'), P('cache', 'in')), '같은 노드끼리 연결할 수 없습니다.')
  assert.equal(reason(P('deployment:a', 'in'), P('cache', 'in')), '출력 포트에서 입력 포트로만 연결할 수 있습니다.')
  assert.equal(reason(P('cache', 'out'), P('cache', 'out')), '같은 노드끼리 연결할 수 없습니다.')
  assert.equal(reason(P('start', 'out'), P('guardrail', 'in')), '시작 노드는 프로젝트에만 연결합니다.')
  assert.equal(reason(P('project:p1', 'out'), P('cache', 'in')), '자동으로 관리되는 간선입니다. 시작 노드에서 프로젝트로 간선을 그리면 접근이 허용됩니다.')
  assert.equal(reason(P('policy:p3', 'out'), P('cache', 'in')), '자동으로 관리되는 간선입니다. 시작 노드에서 프로젝트로 간선을 그리면 접근이 허용됩니다.')
  assert.equal(reason(P('prompt:a', 'out'), P('deployment:b', 'in')), '모델별 시스템 프롬프트는 자기 LLM에만 연결됩니다.')
  assert.equal(reason(P('deployment:a', 'host'), P('provider:pv1', 'in')), '자동으로 관리되는 간선입니다.')
  assert.equal(reason(P('deployment:a', 'host'), P('cache', 'in')), '이 포트 조합은 연결할 수 없습니다.')
  assert.equal(reason(P('masker', 'masker'), P('guardrail', 'masker')), '이미 간선이 있습니다.')
  assert.equal(reason(P('router', 'out'), P('guardrail', 'in')), '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.')
  assert.equal(reason(P('cache', 'out'), P('guardrail', 'in')), '순환 간선은 만들 수 없습니다.')
  assert.equal(reason(P('router', 'out'), P('deployment:x', 'in')), '이미 간선이 있습니다.')
  // replacements: a single out port gives up its edge, the in port keeps at most one flow edge
  assert.deepEqual(cc(P('router', 'out'), P('deployment:b', 'in')), { ok: true, replaces: [] })
  const rewire = cc(P('guardrail', 'out'), P('params', 'in'))
  assert.deepEqual(rewire, { ok: true, replaces: ['guardrail/out->prompt/in'] })
  const toLlm = cc(P('cache', 'out'), P('deployment:x', 'in'))
  assert.deepEqual(toLlm, { ok: true, replaces: ['cache/out->router/in', 'router/out->deployment:x/in'].filter((id) => ['cache/out->router/in', 'router/out->deployment:x/in'].includes(id)).sort() })
  assert.equal(cc(P('masker', 'masker'), P('guardrail', 'masker')).ok, false)

  const target2 = node(g, 'deployment:b')
  const anchor = layout.portAnchor(target2, 'in', 'in')
  const from = P('router', 'out')
  assert.deepEqual(nearestCompatiblePort(g, from, { x: anchor.x + 5, y: anchor.y - 5 }, 24, base), P('deployment:b', 'in'))
  assert.equal(nearestCompatiblePort(g, from, { x: anchor.x + 400, y: anchor.y }, 24, base), null)
  assert.equal(nearestCompatiblePort(g, P('deployment:b', 'host'), anchor, 24, base), null)
  assert.equal(nearestCompatiblePort(g, P('cache', 'out'), layout.portAnchor(node(g, 'cache'), 'in', 'in'), 24, base), null, 'own input is not compatible')

  // connect: a second LLM behind a stage without a router inserts the router
  const { s, g: draft } = draftWith(['a'], [])
  let h = run(draft, { type: 'addNode', nodeType: 'guardrail' }, s)
  h = run(h, { type: 'addNode', nodeType: 'llm', refId: 'b', autoWire: false }, s)
  assert.equal(node(h, 'router'), undefined)
  h = run(h, { type: 'connect', from: P('guardrail', 'out'), to: P('deployment:b', 'in') }, s)
  assert.ok(node(h, 'router'))
  assert.deepEqual(analyzeWorkflow(h, s).branches.map((b) => b.llmNodeId), ['deployment:a', 'deployment:b'])
  const connectError = applyCommand(g, { type: 'connect', from: P('router', 'out'), to: P('cache', 'in') }, base)
  assert.equal(connectError.error, '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.')
  assert.deepEqual(connectError.graph, g)
  const ext = run(run(decompile(s, null), { type: 'addNode', nodeType: 'guardrail' }, s), { type: 'addNode', nodeType: 'llm', refId: 'x', autoWire: false }, s)
  const wired = run(ext, { type: 'connect', from: P('guardrail', 'out'), to: P('deployment:x', 'in') }, s)
  assert.equal(node(wired, 'deployment:x').props.followModelChanges, false)
  const cleaned = issueCountsByNode(validateGraph(run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['prompt/out->cache/in'] }, base), base))
  assert.ok(cleaned.guardrail.error >= 1 && cleaned.prompt.error >= 1, 'the cut-off part of the path is reported')
})

/* ---------------- 27. history, geometry helpers, suggestions ---------------- */

test('27. history, clientToLocal and suggestNodePosition', () => {
  let h = history.createHistory()
  assert.equal(h.limit, 100)
  assert.equal(history.undoHistory(h, 'now'), null)
  assert.equal(history.redoHistory(h, 'now'), null)
  h = history.recordHistory(h, 'v1')
  h = history.recordHistory(h, 'v2')
  const u = history.undoHistory(h, 'v3')
  assert.equal(u.state, 'v2')
  assert.deepEqual(u.history.future, ['v3'])
  const r = history.redoHistory(u.history, 'v2')
  assert.equal(r.state, 'v3')
  assert.deepEqual(r.history.past, ['v1', 'v2'])
  assert.deepEqual(h.past, ['v1', 'v2'], 'input untouched')
  assert.deepEqual(history.recordHistory(u.history, 'x').future, [])
  let big = history.createHistory()
  for (let i = 0; i < 105; i += 1) big = history.recordHistory(big, i)
  assert.equal(big.past.length, 100)
  assert.equal(big.past[0], 5)
  assert.equal(history.createHistory(3).limit, 3)

  const rect = { left: 100, top: 50, width: 1150 }
  assert.deepEqual(layout.clientToLocal({ x: 100, y: 50 }, rect, 1000), { x: 0, y: 0 })
  const p = layout.clientToLocal({ x: 100 + 575, y: 50 + 115 }, rect, 1000)
  assert.ok(Math.abs(p.x - 500) < 1e-9 && Math.abs(p.y - 100) < 1e-9)
  assert.deepEqual(layout.clientToLocal({ x: 30, y: 40 }, { left: 10, top: 10, width: 400 }, 400), { x: 20, y: 30 })
  assert.deepEqual(layout.clientToLocal({ x: 30, y: 40 }, { left: 10, top: 10, width: 400 }, 0), { x: 20, y: 30 })
  assert.equal(layout.clientScale(0, 0), 1)
  assert.equal(layout.clientScale(1150, 1000), 1.15)

  // suggestNodePosition accepts both node and addable types and stays below same-lane nodes
  const mk = (id, type, x, y) => ({ id, type, refId: null, targetId: null, x, y, collapsed: false, props: {} })
  const lane = 9 * layout.COLUMN_WIDTH
  const g = { schemaVersion: 2, serviceId: null, nodes: [mk('deployment:a', 'llm', lane, 0)], edges: [] }
  const bottom = layout.nodeRect(g.nodes[0])
  const near = layout.suggestNodePosition(g, 'llm', { x: 0, y: 0 }, { x: 0, y: 0, width: 9000, height: 9000 })
  assert.equal(near.x, lane)
  assert.ok(near.y >= bottom.y + bottom.height)
  assert.equal(near.y % catalog.GRID_SIZE, 0)
  const far = layout.suggestNodePosition(g, 'llm', { x: lane + 100, y: 40 }, { x: 0, y: 0, width: 300, height: 300 })
  const size = layout.nodeSize(mk('n', 'llm', 0, 0))
  assert.equal(far.x < bottom.x + bottom.width && far.x + size.width > bottom.x && far.y < bottom.y + bottom.height && far.y + size.height > bottom.y, false)
  assert.equal(layout.suggestNodePosition({ schemaVersion: 2, serviceId: null, nodes: [], edges: [] }, 'project', { x: 0, y: 0 }, null).y, 0)
  assert.equal(layout.suggestNodePosition(g, 'cache', { x: 0, y: 0 }, null).x, 5 * layout.COLUMN_WIDTH)
})

/* ---------------- extras ---------------- */

test('mergeConfigs, configsEqual and isConfigDirty', () => {
  const l = clone(base.config)
  l.service.displayName = 'Mine'
  const r = clone(base.config)
  r.service.reasoningEffort = 'HIGH'
  r.accessProjectIds = ['p1', 'p2']
  const { merged, conflicts } = mergeConfigs(base.config, l, r)
  assert.equal(conflicts.length, 0)
  assert.equal(merged.service.displayName, 'Mine')
  assert.equal(merged.service.reasoningEffort, 'HIGH')
  assert.deepEqual(merged.accessProjectIds, ['p1', 'p2'])
  assert.equal(configsEqual(base.config, clone(base.config)), true)
  assert.equal(configsEqual(base.config, merged), false)
  const promptDiff = clone(base.config)
  promptDiff.targets[0].systemPromptTemplate = 'Changed'
  assert.equal(configsEqual(base.config, promptDiff), false)
  const promptSpaces = clone(base.config)
  promptSpaces.targets[0].systemPromptTemplate = '  Be brief '
  assert.equal(configsEqual(base.config, promptSpaces), true)

  assert.equal(diff.isConfigDirty(baseGraph(), base), false)
  const draft = draftSnapshot()
  const fresh = decompile(draft, null)
  assert.equal(diff.isConfigDirty(fresh, draft), false)
  const edited = props(fresh, 'start', { displayName: 'Changed' }, draft)
  assert.equal(diff.isConfigDirty(edited, draft), true)
  assert.equal(diff.isConfigDirty(structuredClone(edited), draft), true)
  assert.equal(diff.isConfigDirty(props(baseGraph(), 'start', { displayName: 'Changed' }, base), base), true)
})

/* ---------------- body whitelist (runs after all plans) ---------------- */

test('every generated body only uses keys from the body types', () => {
  const allowed = {
    createService: ['organizationId', 'serviceKey', 'displayName', 'failoverPolicy', 'retryPolicy', 'allowDegraded', 'requiredCapabilitiesJson', 'inputPricePerMillion', 'outputPricePerMillion', 'currency', 'temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode', 'dataBoundaryPolicy'],
    patchService: ['displayName', 'enabled', 'dataBoundaryPolicy', 'requiredCapabilitiesJson', 'currency', 'inputPricePerMillion', 'outputPricePerMillion', 'guardrailAction', 'maskerDeploymentId', 'clearMaskerDeployment', 'maskPersonNames', 'systemPromptTemplate', 'clearSystemPromptTemplate', 'temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode', 'responseCacheEnabled', 'responseCacheTtlSeconds', 'failoverPolicy', 'retryPolicy', 'allowDegraded'],
    updateScheduling: ['mode', 'localWaitMs', 'maxWaitMs', 'maxQueuedRequests', 'maxQueuedBytes', 'maxQueuedPerKey'],
    createTarget: ['deploymentId', 'priority', 'weight', 'degraded', 'followModelChanges', 'maxConcurrencyOverride', 'systemPromptTemplate'],
    patchTarget: ['priority', 'weight', 'degraded', 'enabled', 'maxConcurrencyOverride', 'followModelChanges', 'systemPromptTemplate', 'clearSystemPromptTemplate'],
  }
  assert.ok(allOps.length > 20)
  const seen = new Set()
  for (const op of allOps) {
    seen.add(op.kind)
    if (!allowed[op.kind]) { assert.equal('body' in op, false, op.kind); continue }
    for (const key of Object.keys(op.body)) assert.ok(allowed[op.kind].includes(key), `${op.kind}.${key}`)
    assert.ok(op.opId && op.summary && Array.isArray(op.changes) && op.risk)
  }
  for (const kind of ['createService', 'patchService', 'updateScheduling', 'createTarget', 'patchTarget', 'deleteTarget', 'grantAccess', 'revokeAccess']) assert.ok(seen.has(kind), kind)
  assert.ok(allOps.some((o) => o.kind === 'createTarget' && 'systemPromptTemplate' in o.body))
  assert.ok(allOps.some((o) => o.kind === 'patchTarget' && 'clearSystemPromptTemplate' in o.body))
})

/* ---------------- 22b. palette connection groups ---------------- */

test('22b. palette groups models by connection; helpers shortModelName / groupModelsByConnection', () => {
  const { shortModelName, groupModelsByConnection } = palette
  assert.equal(shortModelName('/opt/llm/models/gemma-4-12b-it-qat-q4_0.gguf', 'x'), 'gemma-4-12b-it-qat-q4_0')
  assert.equal(shortModelName('C:\\models\\Llama-3.SAFETENSORS', 'x'), 'Llama-3')
  assert.equal(shortModelName('models/gemini-3.8-flash', 'x'), 'gemini-3.8-flash') // path-like: directories stripped
  assert.equal(shortModelName('gpt-4.1.bin-v2', 'x'), 'gpt-4.1.bin-v2') // not path-like: unchanged
  assert.equal(shortModelName('Model A.gguf', 'x'), 'Model A.gguf') // not path-like: extension kept
  assert.equal(shortModelName('', '/m/a.gguf'), 'a') // empty name falls back to providerModelId
  assert.equal(shortModelName('/trailing/', 'x'), 'trailing')

  const g = baseGraph()
  const opts = { query: '', showInactive: false, mode: 'advanced' }
  const snap = JSON.parse(JSON.stringify(base))
  snap.catalog.endpoints.push(
    { id: 'ep2', displayName: 'bc250', runtimeType: 'LLAMA_CPP', enabled: true, healthStatus: 'HEALTHY', addressLabel: 'http://10.0.0.2:4040' },
    { id: 'ep3', displayName: 'Offline Box', runtimeType: 'OLLAMA', enabled: false, healthStatus: 'UNKNOWN', addressLabel: 'http://10.0.0.3:11434' },
  )
  snap.catalog.deployments.push(
    dep('d', { runtimeEndpointId: 'ep2', displayName: '/opt/llm/models/zeta.gguf', providerModelId: '/opt/llm/models/zeta.gguf' }),
    dep('e', { runtimeEndpointId: 'ep2', loaded: false }),
    dep('f', { runtimeEndpointId: 'ep3', enabled: false }),
    dep('g', { runtimeEndpointId: 'gone', displayName: 'Orphan' }),
    dep('h', { runtimeEndpointId: null, externalProviderId: 'nope', external: true, displayName: 'Ext Orphan' }),
  )
  const local = (o = opts, s = snap) => buildPalette(s, g, o).find((x) => x.id === 'llm-local')
  const external = (o = opts, s = snap) => buildPalette(s, g, o).find((x) => x.id === 'llm-external')

  // grouping, ordering (connections with an active model first, then label in ko order), unknown connection
  const conns = local().connections
  assert.deepEqual(conns.map((c) => c.id), ['endpoint:ep2', 'endpoint:ep1', 'unknown-local'])
  assert.deepEqual(conns.map((c) => c.label), ['bc250', 'GPU Box', '알 수 없는 연결'])
  assert.deepEqual(conns[0], { ...conns[0], kind: 'endpoint', detail: 'LLAMA_CPP · http://10.0.0.2:4040', tone: 'healthy', enabled: true, activeCount: 1, totalCount: 2, hiddenCount: 1 })
  assert.deepEqual(conns[0].models.map((m) => m.deploymentId), ['d'])
  assert.deepEqual(conns[1].models.map((m) => m.deploymentId), ['a', 'c']) // active, degraded; inactive hidden
  assert.equal(conns[1].totalCount, 3)
  assert.equal(conns[1].hiddenCount, 1)
  assert.equal(conns[2].kind, 'endpoint')
  assert.deepEqual(conns[2].models.map((m) => m.deploymentId), ['g'])
  const ext = external().connections
  assert.deepEqual(ext.map((c) => [c.id, c.kind, c.detail]), [['provider:pv1', 'provider', 'OPENAI'], ['unknown-external', 'provider', ''], ['provider:pv2', 'provider', 'OPENAI']]) // pv2 has no active model (header only)

  // flattened models / hiddenCount keep the historical semantics
  assert.equal(local().hiddenCount, 3) // b, e and f (the disabled connection is dropped as a header but still counted)
  assert.deepEqual(local().models.map((m) => m.availability), ['active', 'active', 'active', 'degraded'])
  assert.equal(local().models.length, conns.reduce((n, c) => n + c.models.length, 0))
  assert.ok(!conns.some((c) => c.id === 'endpoint:ep3'), 'a disabled connection with all models hidden is omitted')

  // showInactive reveals everything, including the disabled connection
  const all = local({ ...opts, showInactive: true })
  assert.equal(all.hiddenCount, 0)
  assert.ok(all.connections.some((c) => c.id === 'endpoint:ep3' && c.enabled === false && c.tone === 'muted' && c.models.length === 1))
  assert.equal(all.models.length, snap.catalog.deployments.filter((d) => !d.external).length)

  // an enabled connection whose models are all hidden stays as a header
  const noActive = JSON.parse(JSON.stringify(snap))
  noActive.catalog.deployments = noActive.catalog.deployments.map((d) => (d.runtimeEndpointId === 'ep2' ? { ...d, loaded: false } : d))
  const hiddenOnly = local(opts, noActive).connections.find((c) => c.id === 'endpoint:ep2')
  assert.deepEqual([hiddenOnly.models.length, hiddenOnly.hiddenCount, hiddenOnly.activeCount, hiddenOnly.totalCount], [0, 2, 0, 2])

  // search: a connection label / detail match shows all of its models
  const bySearch = local({ ...opts, query: ' BC250 ' })
  assert.deepEqual(bySearch.connections.map((c) => c.id), ['endpoint:ep2'])
  assert.deepEqual(bySearch.connections[0].models.map((m) => m.deploymentId), ['d', 'e'])
  assert.deepEqual(bySearch.models.map((m) => m.deploymentId), ['d', 'e'])
  assert.equal(bySearch.hiddenCount, 0)
  assert.deepEqual(local({ ...opts, query: '10.0.0.1' }).connections.map((c) => c.id), ['endpoint:ep1'])
  // model-name search keeps only matching models and drops empty connections
  assert.deepEqual(local({ ...opts, query: 'zeta' }).connections.map((c) => [c.id, c.models.map((m) => m.deploymentId)]), [['endpoint:ep2', ['d']]])
  // non-LLM groups have no connections
  assert.ok(buildPalette(snap, g, opts).filter((x) => !x.id.startsWith('llm')).every((x) => x.connections.length === 0))

  // shared helper used by the quick start and the inspector
  const grouped = groupModelsByConnection(snap.catalog.deployments.filter((d) => !d.external), snap.catalog)
  assert.deepEqual(grouped.map((x) => [x.connection.id, x.items.map((d) => d.id), x.activeCount]), [
    ['endpoint:ep2', ['d', 'e'], 1], ['endpoint:ep1', ['a', 'c', 'b'], 1], ['unknown-local', ['g'], 1], ['endpoint:ep3', ['f'], 0],
  ])
})
