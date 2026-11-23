import assert from 'node:assert/strict'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

const catalog = await loadTypeScript('../src/flow-designer/catalog.ts', import.meta.url)
const layout = await loadTypeScript('../src/flow-designer/layout.ts', import.meta.url)
const graphOps = await loadTypeScript('../src/flow-designer/graphOps.ts', import.meta.url)
const { decompile } = await loadTypeScript('../src/flow-designer/decompile.ts', import.meta.url)
const { compile, normalizePrompt, canonicalCapabilities, sameValue } = await loadTypeScript('../src/flow-designer/compile.ts', import.meta.url)
const { validateGraph, issueCountsByNode } = await loadTypeScript('../src/flow-designer/validate.ts', import.meta.url)
const diff = await loadTypeScript('../src/flow-designer/diff.ts', import.meta.url)
const history = await loadTypeScript('../src/flow-designer/history.ts', import.meta.url)

const { NODE_TYPES, CONNECTION_RULES, DEFAULT_CONFIG, portSpec, describeNode, nodeFacts, describeEdge } = catalog
const { applyCommand, canConnect, nearestCompatiblePort, createSkeletonGraph } = graphOps
const { planChanges, rebase, mergeConfigs, computeNodeChanges, configsEqual, stableStringify } = diff

/* ---------------- fixtures ---------------- */

const target = (id, deploymentId, extra = {}) => ({
  key: `target:${id}`, id, deploymentId, priority: 1, weight: 100, degraded: false, enabled: true,
  maxConcurrencyOverride: null, followModelChanges: true, ...extra,
})

function fixture() {
  return {
    organizationId: 'org1',
    serviceId: 'svc1',
    promptVersion: 3,
    accessComplete: true,
    loadedAt: '2026-01-01T00:00:00.000Z',
    unavailable: [],
    dataProtection: { mode: 'ENFORCE', level: 'STANDARD', externalAction: 'MASK', allowExternalFailover: false, detectSecrets: true, detectPii: true, detectFinancial: true, detectConfidential: false, detectMedia: false },
    config: {
      service: {
        ...DEFAULT_CONFIG.service,
        serviceKey: 'text-pro', displayName: 'Text Pro', guardrailAction: 'BLOCK', maskerDeploymentId: 'a',
        systemPromptTemplate: 'You are helpful', responseCacheEnabled: true, responseCacheTtlSeconds: 300, failoverPolicy: 'COMPATIBLE',
      },
      scheduling: { ...DEFAULT_CONFIG.scheduling },
      targets: [
        target('t1', 'a', { priority: 1, weight: 80 }),
        target('t2', 'x', { priority: 2, weight: 100, maxConcurrencyOverride: 2, followModelChanges: false }),
      ],
      accessProjectIds: ['p1'],
    },
    catalog: {
      services: [{ id: 'svc1', serviceKey: 'text-pro', displayName: 'Text Pro', enabled: true }],
      deployments: [
        { id: 'a', displayName: 'Model A', providerModelId: 'model-a', runtimeEndpointId: 'ep1', externalProviderId: null, external: false, enabled: true, loaded: true, healthStatus: 'HEALTHY', maxConcurrency: 4, contextLength: 8192 },
        { id: 'b', displayName: 'Model B', providerModelId: 'model-b', runtimeEndpointId: 'ep1', externalProviderId: null, external: false, enabled: true, loaded: false, healthStatus: 'HEALTHY', maxConcurrency: 2, contextLength: null },
        { id: 'x', displayName: 'Model X', providerModelId: 'gpt-x', runtimeEndpointId: null, externalProviderId: 'pv1', external: true, enabled: true, loaded: true, healthStatus: 'HEALTHY', maxConcurrency: 8, contextLength: 128000 },
      ],
      endpoints: [{ id: 'ep1', displayName: 'GPU Box', runtimeType: 'LLAMA_CPP', enabled: true, healthStatus: 'HEALTHY', addressLabel: 'http://10.0.0.1:4040' }],
      providers: [{ id: 'pv1', displayName: 'Cloud AI', providerType: 'OPENAI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true, zeroRetentionVerified: true }],
      projects: [
        { id: 'p1', name: 'Alpha', status: 'ACTIVE', dataBoundaryPolicy: null },
        { id: 'p2', name: 'Beta', status: 'ACTIVE', dataBoundaryPolicy: null },
      ],
      maskers: [
        { deploymentId: 'a', displayName: 'Model A', providerModelId: 'model-a', sourceType: 'LOCAL', sourceName: 'GPU Box', eligible: true, trustReason: '사내 Runtime' },
        { deploymentId: 'x', displayName: 'Model X', providerModelId: 'gpt-x', sourceType: 'EXTERNAL', sourceName: 'Cloud AI', eligible: false, trustReason: '외부 Provider' },
      ],
    },
  }
}
const clone = (v) => structuredClone(v)
const draftSnapshot = () => {
  const s = fixture()
  return { ...s, serviceId: null, promptVersion: 1, config: clone(DEFAULT_CONFIG), accessComplete: true, dataProtection: null }
}

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

/* ---------------- 1. catalog integrity ---------------- */

test('1. catalog integrity: rules reference existing ports, singletons are fixed, every field is consumed by compile', () => {
  for (const rule of CONNECTION_RULES) {
    assert.ok(portSpec(rule.fromType, rule.fromPort, 'out'), `${rule.fromType}.${rule.fromPort} out`)
    assert.ok(portSpec(rule.toType, rule.toPort, 'in'), `${rule.toType}.${rule.toPort} in`)
  }
  for (const type of ['service', 'guardrail', 'prompt', 'cache', 'router']) {
    assert.equal(NODE_TYPES[type].deletable, false)
    assert.equal(NODE_TYPES[type].singleton, true)
  }
  assert.equal(Object.keys(NODE_TYPES).length, 11)

  const s = draftSnapshot()
  s.config = clone(base.config)
  let graph = decompile(s, null)
  graph = props(graph, 'prompt', { temperaturePolicy: 'FIXED', temperatureValue: 0.5 }, s)
  const baseline = stableStringify(compile(graph, s))
  const nodeIds = { service: 'service', masker: 'masker', guardrail: 'guardrail', prompt: 'prompt', cache: 'cache', router: 'router', target: 'target:t1' }
  for (const [type, id] of Object.entries(nodeIds)) {
    for (const field of NODE_TYPES[type].fields) {
      if (field.key.startsWith('$')) continue
      const current = graph.nodes.find((n) => n.id === id).props[field.key]
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
  // virtual fields are driven by commands
  assert.ok(NODE_TYPES.target.fields.some((f) => f.key === '$binding'))
  assert.ok(NODE_TYPES.project.fields.some((f) => f.key === '$access'))
})

/* ---------------- 2/3. decompile ---------------- */

test('2. decompile is deterministic and follows the id rules', () => {
  const a = decompile(base, null)
  const b = decompile(base, null)
  assert.deepEqual(a, b)
  const ids = a.nodes.map((n) => n.id)
  for (const id of ['service', 'guardrail', 'prompt', 'cache', 'router', 'masker', 'project:p1', 'target:t1', 'target:t2', 'deployment:a', 'deployment:x', 'endpoint:ep1', 'provider:pv1']) {
    assert.ok(ids.includes(id), id)
  }
  assert.ok(!ids.includes('project:p2'))
  assert.equal(ids.length, 13)
  const locked = a.edges.filter((e) => e.locked).map((e) => e.kind)
  assert.equal(locked.filter((k) => k === 'pipeline').length, 4)
  assert.equal(locked.filter((k) => k === 'host').length, 2)
  assert.equal(locked.filter((k) => k === 'route').length, 2)
  assert.equal(a.edges.filter((e) => e.kind === 'access').map((e) => e.from.nodeId).join(), 'project:p1')
  assert.equal(a.edges.filter((e) => e.kind === 'masker').length, 1)
  assert.equal(a.edges.find((e) => e.kind === 'binding' && e.from.nodeId === 'target:t1').id, 'target:t1/binding->deployment:a/binding')
  const sorted = [...a.nodes].sort((p, q) => NODE_TYPES[p.type].column - NODE_TYPES[q.type].column || (p.id < q.id ? -1 : 1))
  assert.deepEqual(a.nodes.map((n) => n.id), sorted.map((n) => n.id))
  assert.equal(a.serviceId, 'svc1')
  assert.equal(a.nextSeq, 1)
})

test('3. round trip: compile(decompile(s)) equals s.config', () => {
  assert.deepEqual(compile(decompile(base, null), base), base.config)
  const withLayout = layout.extractLayout(decompile(base, null), { x: 0, y: 0, zoom: 1 })
  assert.deepEqual(compile(decompile(base, withLayout), base), base.config)
  const draft = draftSnapshot()
  assert.deepEqual(compile(decompile(draft, null), draft), draft.config)
  // inputs are never mutated
  assert.deepEqual(base, fixture())
})

/* ---------------- 4-9. planning basics ---------------- */

test('4. no change produces an empty, stable plan', () => {
  const g = baseGraph()
  const p1 = plan(base, g)
  const p2 = plan(base, g)
  assert.equal(p1.operations.length, 0)
  assert.equal(p1.conflicts.length, 0)
  assert.equal(p1.blockingIssues.length, 0)
  assert.equal(p1.remoteDrift, false)
  assert.equal(p1.fingerprint, p2.fingerprint)
  assert.deepEqual(computeNodeChanges(g, base), {})
})

test('5. changing only a target weight produces a single minimal patch', () => {
  const g = props(baseGraph(), 'target:t1', { weight: 50 }, base)
  const p = plan(base, g)
  assert.equal(p.operations.length, 1)
  const op = p.operations[0]
  assert.equal(op.kind, 'patchTarget')
  assert.deepEqual(op.target, { kind: 'existing', id: 't1' })
  assert.deepEqual(op.body, { weight: 50 })
  assert.equal(op.risk, 'safe')
  assert.equal(op.opId, 'patch-target:t1')
  assert.deepEqual(computeNodeChanges(g, base), { 'target:t1': 'modified' })
})

test('6. guardrailAction is sent only when it changes and carries the DLP side-effect note', () => {
  const g = props(baseGraph(), 'guardrail', { guardrailAction: 'ALLOW' }, base)
  const p = plan(base, g)
  assert.equal(p.operations.length, 1)
  assert.deepEqual(p.operations[0].body, { guardrailAction: 'ALLOW' })
  assert.equal(p.operations[0].risk, 'danger')
  assert.ok(p.notes.some((n) => n.level === 'warning' && n.message.startsWith('가드레일 처리 정책을 바꾸면')))
  const other = props(baseGraph(), 'prompt', { reasoningEffort: 'HIGH' }, base)
  const p2 = plan(base, other)
  assert.equal(p2.operations.length, 1)
  assert.ok(!('guardrailAction' in p2.operations[0].body))
  assert.ok(!p2.notes.some((n) => n.message.startsWith('가드레일')))
})

test('7. masker removal clears the deployment; ineligible maskers are blocked', () => {
  const g = run(baseGraph(), { type: 'removeItems', nodeIds: ['masker'], edgeIds: [] }, base)
  const p = plan(base, g)
  assert.equal(p.operations.length, 1)
  assert.deepEqual(p.operations[0].body, { clearMaskerDeployment: true })
  assert.equal(p.operations[0].risk, 'danger')
  const bad = props(baseGraph(), 'masker', { deploymentId: 'x' }, base)
  const issues = validateGraph(bad, base)
  assert.ok(codes(issues).includes('MASKER_NOT_ELIGIBLE'))
  assert.ok(plan(base, bad).blockingIssues.some((i) => i.code === 'MASKER_NOT_ELIGIBLE'))
  const empty = props(baseGraph(), 'masker', { deploymentId: '' }, base)
  assert.ok(codes(validateGraph(empty, base)).includes('MASKER_NOT_SELECTED'))
})

test('8. prompt normalization: blank clears, whitespace-only edits are no-ops, real edits bump the version', () => {
  const cleared = plan(base, props(baseGraph(), 'prompt', { systemPromptTemplate: '  ' }, base))
  assert.deepEqual(cleared.operations.map((o) => o.body), [{ clearSystemPromptTemplate: true }])
  const padded = plan(base, props(baseGraph(), 'prompt', { systemPromptTemplate: '  You are helpful \n' }, base))
  assert.equal(padded.operations.length, 0)
  const changed = plan(base, props(baseGraph(), 'prompt', { systemPromptTemplate: 'New prompt' }, base))
  assert.deepEqual(changed.operations[0].body, { systemPromptTemplate: 'New prompt' })
  assert.ok(changed.notes.some((n) => n.message.includes('v3에서 v4')))
  assert.ok(!JSON.stringify(changed.operations[0].changes).includes('New prompt'))
  assert.equal(normalizePrompt('  a '), 'a')
  assert.equal(normalizePrompt('   '), null)
  assert.equal(canonicalCapabilities('["b","a","b"]'), '["a","b"]')
  assert.equal(canonicalCapabilities('not json'), 'not json')
  assert.ok(sameValue(null, undefined) && sameValue(0.1 + 0.2, 0.3))
})

test('9. temperature: FIXED needs a value and sends both fields', () => {
  const missing = props(baseGraph(), 'prompt', { temperaturePolicy: 'FIXED' }, base)
  assert.ok(codes(validateGraph(missing, base)).includes('TEMPERATURE_VALUE_REQUIRED'))
  assert.ok(plan(base, missing).blockingIssues.some((i) => i.code === 'TEMPERATURE_VALUE_REQUIRED'))
  const range = props(baseGraph(), 'prompt', { temperaturePolicy: 'FIXED', temperatureValue: 3 }, base)
  assert.ok(codes(validateGraph(range, base)).includes('TEMPERATURE_VALUE_RANGE'))
  const ok = props(baseGraph(), 'prompt', { temperaturePolicy: 'FIXED', temperatureValue: 0.7 }, base)
  const p = plan(base, ok)
  assert.deepEqual(p.operations[0].body, { temperaturePolicy: 'FIXED', temperatureValue: 0.7 })
  assert.equal(p.blockingIssues.length, 0)
})

/* ---------------- 10-15. target and access editing ---------------- */

test('10. router -> deployment inserts a new target node with defaults', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'deployment', refId: 'b' }, base)
  const check = canConnect(g, { nodeId: 'router', portId: 'targets' }, { nodeId: 'deployment:b', portId: 'binding' }, base)
  assert.deepEqual(check, { ok: true, replaces: [] })
  const r = applyCommand(g, { type: 'connect', from: { nodeId: 'router', portId: 'targets' }, to: { nodeId: 'deployment:b', portId: 'binding' } }, base)
  assert.equal(r.error, null)
  assert.deepEqual(r.select, { nodeIds: ['target:new-1'], edgeIds: [] })
  g = r.graph
  assert.equal(g.nextSeq, 2)
  const node = g.nodes.find((n) => n.id === 'target:new-1')
  assert.equal(node.refId, null)
  assert.equal(node.props.priority, 3)
  assert.equal(node.props.weight, 100)
  assert.equal(node.props.followModelChanges, true)
  assert.ok(g.edges.some((e) => e.id === 'router/targets->target:new-1/route' && e.locked))
  assert.ok(g.edges.some((e) => e.id === 'target:new-1/binding->deployment:b/binding'))
  const p = plan(base, g)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-target:target:new-1'])
  assert.deepEqual(p.operations[0].body, { deploymentId: 'b', priority: 3, weight: 100, degraded: false, followModelChanges: true })
  assert.equal(p.operations[0].risk, 'safe')
  assert.deepEqual(computeNodeChanges(g, base)['target:new-1'], 'added')

  // external deployment: model following is forced off
  let h = run(baseGraph(), { type: 'removeItems', nodeIds: ['target:t2'], edgeIds: [] }, base)
  h = run(h, { type: 'connect', from: { nodeId: 'router', portId: 'targets' }, to: { nodeId: 'deployment:x', portId: 'binding' } }, base)
  assert.equal(h.nodes.find((n) => n.id === 'target:new-1').props.followModelChanges, false)
  const viaAdd = run(baseGraph(), { type: 'addNode', nodeType: 'target', refId: 'b' }, base)
  assert.ok(viaAdd.edges.some((e) => e.id === 'target:new-1/binding->deployment:b/binding'))
  assert.equal(applyCommand(baseGraph(), { type: 'addNode', nodeType: 'target', refId: 'a' }, base).error, '이 Deployment에는 이미 Target이 있습니다(서비스당 Deployment 1개).')
  assert.equal(applyCommand(baseGraph(), { type: 'addNode', nodeType: 'deployment', refId: 'nope' }, base).error, '알 수 없는 Deployment입니다.')
})

test('11. a new disabled target is disabled right after it is created', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'target', refId: 'b' }, base)
  g = props(g, 'target:new-1', { enabled: false }, base)
  const p = plan(base, g)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-target:target:new-1', 'disable-created-target:target:new-1'])
  assert.deepEqual(p.operations[1].target, { kind: 'created', key: 'target:new-1' })
  assert.deepEqual(p.operations[1].body, { enabled: false })
  assert.equal(p.operations[1].risk, 'safe')
  assert.deepEqual(p.operations[0].service, { kind: 'existing', id: 'svc1' })
})

test('12. rebinding replaces the target; swaps delete first', () => {
  const g = run(baseGraph(), { type: 'bindTarget', targetNodeId: 'target:t1', deploymentId: 'b' }, base)
  const p = plan(base, g)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-target:target:t1', 'delete-target:t1'])
  assert.equal(p.operations[0].body.deploymentId, 'b')
  assert.equal(p.operations[0].body.weight, 80)
  assert.equal(p.operations[1].risk, 'danger')
  assert.ok(p.notes.some((n) => n.message.startsWith('Deployment를 바꾼 Target')))

  let swap = run(baseGraph(), { type: 'bindTarget', targetNodeId: 'target:t1', deploymentId: 'x' }, base)
  swap = run(swap, { type: 'bindTarget', targetNodeId: 'target:t2', deploymentId: 'a' }, base)
  assert.ok(!codes(validateGraph(swap, base)).includes('DUPLICATE_DEPLOYMENT_TARGET'))
  const ps = plan(base, swap)
  const ids = ps.operations.map((o) => o.opId)
  assert.deepEqual(ids, ['delete-target:t1', 'delete-target:t2', 'create-target:target:t1', 'create-target:target:t2'])
  assert.ok(ps.notes.some((n) => n.level === 'warning' && n.message.startsWith('같은 Deployment를 다시 쓰려고')))
  assert.equal(ps.operations[3].body.maxConcurrencyOverride, 2)
})

test('13. two targets on one deployment is an error', () => {
  const g = run(baseGraph(), { type: 'bindTarget', targetNodeId: 'target:t1', deploymentId: 'x' }, base)
  const dup = validateGraph(g, base).filter((i) => i.code === 'DUPLICATE_DEPLOYMENT_TARGET')
  assert.deepEqual(dup.map((i) => i.nodeId).sort(), ['target:t1', 'target:t2'])
  assert.ok(dup.every((i) => i.severity === 'error'))
  assert.ok(plan(base, g).blockingIssues.length >= 2)
})

test('14. deleting targets and protected nodes', () => {
  const g = run(baseGraph(), { type: 'removeItems', nodeIds: ['target:t2'], edgeIds: [] }, base)
  const p = plan(base, g)
  assert.deepEqual(p.operations.map((o) => o.opId), ['delete-target:t2'])
  assert.equal(p.operations[0].risk, 'danger')

  const original = baseGraph()
  const r = applyCommand(original, { type: 'removeItems', nodeIds: ['service'], edgeIds: [] }, base)
  assert.equal(r.error, '고정 노드는 삭제할 수 없습니다.')
  assert.equal(r.changed, false)
  assert.deepEqual(r.graph, original)
  const d = applyCommand(original, { type: 'removeItems', nodeIds: ['deployment:a'], edgeIds: [] }, base)
  assert.equal(d.error, 'Target이 연결된 Deployment는 제거할 수 없습니다.')
  assert.deepEqual(d.graph, original)
  // target + its deployment together can be removed; the orphaned host disappears
  const both = applyCommand(original, { type: 'removeItems', nodeIds: ['target:t2', 'deployment:x'], edgeIds: [] }, base)
  assert.equal(both.error, null)
  assert.ok(!both.graph.nodes.some((n) => n.id === 'deployment:x' || n.id === 'provider:pv1'))
  // locked edges cannot be deleted
  const locked = applyCommand(original, { type: 'removeItems', nodeIds: [], edgeIds: ['service/request->guardrail/request'] }, base)
  assert.ok(locked.error)
  assert.deepEqual(locked.graph, original)
})

test('15. project access grants and revokes; incomplete access blocks edits', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'project', refId: 'p2' }, base)
  g = run(g, { type: 'setAccess', projectNodeId: 'project:p2', granted: true }, base)
  g = run(g, { type: 'setAccess', projectNodeId: 'project:p1', granted: false }, base)
  const p = plan(base, g)
  assert.deepEqual(p.operations.map((o) => o.opId), ['grant-access:p2', 'revoke-access:p1'])
  assert.equal(p.operations[0].risk, 'caution')
  assert.equal(p.operations[1].risk, 'danger')
  assert.ok(p.operations[0].summary.includes('Beta'))
  assert.deepEqual(computeNodeChanges(g, base)['project:p2'], 'added')
  assert.equal(applyCommand(baseGraph(), { type: 'addNode', nodeType: 'project', refId: 'zzz' }, base).error, '알 수 없는 프로젝트입니다.')

  const partial = { ...fixture(), accessComplete: false }
  const issues = validateGraph(g, partial)
  assert.ok(codes(issues).includes('ACCESS_EDIT_UNAVAILABLE'))
  const pp = plan(partial, g)
  assert.ok(pp.blockingIssues.some((i) => i.code === 'ACCESS_EDIT_UNAVAILABLE'))
  assert.equal(pp.operations.filter((o) => o.kind === 'grantAccess' || o.kind === 'revokeAccess').length, 0)
})

/* ---------------- 16-19. three-way merge ---------------- */

function remoteWith(mutator) {
  const r = fixture()
  mutator(r)
  return r
}

test('16. three-way planning', () => {
  // remote-only change: nothing to do
  const remoteWeight = remoteWith((r) => { r.config.targets[0].weight = 50 })
  const same = plan(base, baseGraph(), remoteWeight)
  assert.equal(same.operations.length, 0)
  assert.equal(same.remoteDrift, true)
  assert.ok(same.notes.some((n) => n.message.startsWith('불러온 이후 다른 곳에서')))

  // local priority, remote weight
  const localPriority = props(baseGraph(), 'target:t1', { priority: 5 }, base)
  const mixed = plan(base, localPriority, remoteWeight)
  assert.deepEqual(mixed.operations.map((o) => o.body), [{ priority: 5 }])
  assert.equal(mixed.conflicts.length, 0)

  // both change weight differently
  const localWeight = props(baseGraph(), 'target:t1', { weight: 10 }, base)
  const conflict = plan(base, localWeight, remoteWith((r) => { r.config.targets[0].weight = 20 }))
  assert.equal(conflict.conflicts.length, 1)
  assert.equal(conflict.conflicts[0].code, 'FIELD_CONFLICT')
  assert.equal(conflict.conflicts[0].key, 'target:t1')
  assert.equal(conflict.conflicts[0].field, 'weight')
  assert.equal(conflict.operations.length, 0)

  // both converge on the same value
  const converged = plan(base, localWeight, remoteWith((r) => { r.config.targets[0].weight = 10 }))
  assert.equal(converged.operations.length, 0)
  assert.equal(converged.conflicts.length, 0)

  // remote followed a model change, local only changed weight
  const followed = plan(base, localWeight, remoteWith((r) => { r.config.targets[0].deploymentId = 'b' }))
  assert.deepEqual(followed.operations.map((o) => o.body), [{ weight: 10 }])
  assert.equal(followed.conflicts.length, 0)

  // service level fields behave the same way
  const localName = props(baseGraph(), 'service', { displayName: 'Mine' }, base)
  const svcConflict = plan(base, localName, remoteWith((r) => { r.config.service.displayName = 'Theirs' }))
  assert.equal(svcConflict.conflicts[0].field, 'displayName')
  assert.equal(svcConflict.operations.length, 0)
})

test('17. remote removal / remote change versus local edits', () => {
  const removed = remoteWith((r) => { r.config.targets = r.config.targets.filter((t) => t.id !== 't1') })
  const edited = props(baseGraph(), 'target:t1', { weight: 10 }, base)
  const p = plan(base, edited, removed)
  assert.equal(p.conflicts.length, 1)
  assert.equal(p.conflicts[0].code, 'TARGET_REMOVED_REMOTELY')
  assert.equal(p.operations.length, 0)
  // untouched target removed remotely is not a conflict
  assert.equal(plan(base, baseGraph(), removed).conflicts.length, 0)

  const deleted = run(baseGraph(), { type: 'removeItems', nodeIds: ['target:t2'], edgeIds: [] }, base)
  const changed = remoteWith((r) => { r.config.targets[1].weight = 7 })
  const q = plan(base, deleted, changed)
  assert.equal(q.conflicts.length, 1)
  assert.equal(q.conflicts[0].code, 'TARGET_CHANGED_REMOTELY')
  assert.equal(q.operations.length, 0)
  // already deleted remotely: skipped
  assert.equal(plan(base, deleted, remoteWith((r) => { r.config.targets = r.config.targets.filter((t) => t.id !== 't2') })).operations.length, 0)
})

test('18. a target created remotely for the same deployment conflicts, then rebase adopts it', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'target', refId: 'b' }, base)
  const remote = remoteWith((r) => {
    r.config.targets.push(target('rb', 'b', { priority: 3, weight: 100 }))
    r.config.targets.sort((a, b) => (a.key < b.key ? -1 : 1))
  })
  const p = plan(base, g, remote)
  assert.equal(p.conflicts.length, 1)
  assert.equal(p.conflicts[0].code, 'DEPLOYMENT_TARGETED_REMOTELY')
  assert.equal(p.operations.filter((o) => o.kind === 'createTarget').length, 0)

  const result = rebase({ graph: g, base, remote })
  assert.ok(result.graph.nodes.some((n) => n.id === 'target:rb'))
  assert.ok(!result.graph.nodes.some((n) => n.id === 'target:new-1'))
  assert.equal(result.graph.nodes.find((n) => n.id === 'target:rb').refId, 'rb')
  const again = plan(remote, result.graph, remote)
  assert.equal(again.conflicts.length, 0)
  assert.equal(again.operations.length, 0)
  g = null
})

test('19. partial apply rebase keeps coordinates and leaves only the unapplied work', () => {
  let g = run(baseGraph(), { type: 'addNode', nodeType: 'target', refId: 'b', position: { x: 1600, y: 480 } }, base)
  g = props(g, 'target:new-1', { enabled: false }, base)
  const before = g.nodes.find((n) => n.id === 'target:new-1')
  const remote = remoteWith((r) => {
    r.config.targets.push(target('real-9', 'b', { priority: 3, weight: 100 }))
    r.config.targets.sort((a, b) => (a.key < b.key ? -1 : 1))
  })
  const result = rebase({ graph: g, base, remote, createdTargetIds: { 'target:new-1': 'real-9' } })
  assert.equal(result.conflicts.length, 0)
  const after = result.graph.nodes.find((n) => n.id === 'target:real-9')
  assert.ok(after)
  assert.equal(after.x, before.x)
  assert.equal(after.y, before.y)
  assert.equal(after.refId, 'real-9')
  assert.ok(result.graph.edges.some((e) => e.id === 'target:real-9/binding->deployment:b/binding'))
  const again = plan(remote, result.graph, remote)
  assert.deepEqual(again.operations.map((o) => o.opId), ['patch-target:real-9'])
  assert.deepEqual(again.operations[0].body, { enabled: false })

  const renamed = graphOps.renameTargetNodes(g, { 'target:new-1': 'real-9' })
  assert.ok(!renamed.nodes.some((n) => n.id === 'target:new-1'))
  assert.ok(renamed.edges.every((e) => !e.id.includes('new-1')))
  assert.ok(g.nodes.some((n) => n.id === 'target:new-1'), 'input graph untouched')
})

/* ---------------- 20-23. new service, scheduling, ranges, ordering ---------------- */

test('20. new service plan order and key validation', () => {
  const s = draftSnapshot()
  let g = decompile(s, null)
  g = props(g, 'service', { serviceKey: 'text-new', displayName: 'New Svc' }, s)
  g = props(g, 'guardrail', { guardrailAction: 'LOCAL_ONLY' }, s)
  g = run(g, { type: 'addNode', nodeType: 'target', refId: 'a' }, s)
  g = run(g, { type: 'addNode', nodeType: 'project', refId: 'p1' }, s)
  g = run(g, { type: 'setAccess', projectNodeId: 'project:p1', granted: true }, s)
  const p = plan(s, g)
  assert.equal(p.blockingIssues.length, 0)
  assert.deepEqual(p.operations.map((o) => o.opId), ['create-service', 'patch-service', 'create-target:target:new-1', 'grant-access:p1'])
  const [create, patch, createTarget, grant] = p.operations
  assert.equal(create.body.serviceKey, 'text-new')
  assert.equal(create.body.organizationId, 'org1')
  assert.equal(create.body.temperatureValue, null)
  assert.deepEqual(patch.body, { guardrailAction: 'LOCAL_ONLY' })
  assert.ok(!('displayName' in patch.body))
  assert.deepEqual(createTarget.service, { kind: 'created' })
  assert.deepEqual(grant.service, { kind: 'created' })
  assert.ok(p.notes.some((n) => n.message.startsWith('새 서비스는 프로젝트 접근')))

  const invalid = props(g, 'service', { serviceKey: 'Text_New' }, s)
  assert.ok(codes(validateGraph(invalid, s)).includes('SERVICE_KEY_INVALID'))
  assert.ok(plan(s, invalid).blockingIssues.some((i) => i.code === 'SERVICE_KEY_INVALID'))
  const taken = props(g, 'service', { serviceKey: 'text-pro' }, s)
  assert.ok(codes(validateGraph(taken, s)).includes('SERVICE_KEY_TAKEN'))
  // a key that appeared remotely after loading blocks the plan as well
  const remote = clone(s)
  remote.catalog.services.push({ id: 'zz', serviceKey: 'text-new', displayName: 'Z', enabled: true })
  assert.ok(plan(s, g, remote).blockingIssues.some((i) => i.code === 'SERVICE_KEY_TAKEN'))
  // existing services keep their key regardless of props
  const locked = applyCommand(baseGraph(), { type: 'updateProps', nodeId: 'service', patch: { serviceKey: 'hacked' } }, base)
  assert.equal(locked.graph.nodes.find((n) => n.id === 'service').props.serviceKey, 'text-pro')
})

test('21. scheduling validation and merged body', () => {
  const tooLong = props(baseGraph(), 'router', { schedulingMode: 'LOCAL_FIRST_WAIT', localWaitMs: 40000 }, base)
  assert.ok(codes(validateGraph(tooLong, base)).includes('SCHEDULING_LOCAL_WAIT_EXCEEDS_MAX'))
  const perKey = props(baseGraph(), 'router', { maxQueuedPerKey: 200 }, base)
  assert.ok(codes(validateGraph(perKey, base)).includes('SCHEDULING_PER_KEY_EXCEEDS_TOTAL'))
  const range = props(baseGraph(), 'router', { maxQueuedRequests: 9999 }, base)
  assert.ok(validateGraph(range, base).some((i) => i.code === 'SCHEDULING_RANGE' && i.field === 'maxQueuedRequests'))

  const remote = remoteWith((r) => { r.config.scheduling.maxQueuedBytes = 1048576 })
  const g = props(baseGraph(), 'router', { schedulingMode: 'LOCAL_ONLY_QUEUE' }, base)
  const p = plan(base, g, remote)
  assert.equal(p.operations.length, 1)
  assert.equal(p.operations[0].kind, 'updateScheduling')
  assert.deepEqual(p.operations[0].body, { ...remote.config.scheduling, mode: 'LOCAL_ONLY_QUEUE' })
  assert.deepEqual(computeNodeChanges(g, base), { router: 'modified' })
})

test('22. cache TTL range and concurrency override clearing', () => {
  const ttl = props(baseGraph(), 'cache', { responseCacheTtlSeconds: 5 }, base)
  assert.ok(codes(validateGraph(ttl, base)).includes('CACHE_TTL_RANGE'))
  assert.ok(plan(base, ttl).blockingIssues.some((i) => i.code === 'CACHE_TTL_RANGE'))
  const clear = props(baseGraph(), 'target:t2', { maxConcurrencyOverride: '' }, base)
  assert.equal(clear.nodes.find((n) => n.id === 'target:t2').props.maxConcurrencyOverride, null)
  assert.ok(codes(validateGraph(clear, base)).includes('TARGET_CONCURRENCY_CLEAR_UNSUPPORTED'))
  const bad = props(baseGraph(), 'target:t1', { weight: 0 }, base)
  assert.ok(codes(validateGraph(bad, base)).includes('TARGET_NUMBER_INVALID'))
  const name = props(baseGraph(), 'service', { displayName: '   ' }, base)
  assert.ok(codes(validateGraph(name, base)).includes('DISPLAY_NAME_REQUIRED'))
  const caps = props(baseGraph(), 'service', { requiredCapabilitiesJson: '{"a":1}' }, base)
  assert.ok(codes(validateGraph(caps, base)).includes('CAPABILITIES_INVALID'))
  const price = props(baseGraph(), 'service', { inputPricePerMillion: -1 }, base)
  assert.ok(codes(validateGraph(price, base)).includes('PRICE_INVALID'))
})

test('23. plan size limit, phase order and determinism', () => {
  const targets = []
  for (let i = 0; i < 101; i += 1) targets.push({ key: `target:new-${i + 1}`, id: null, deploymentId: `d${i}`, priority: i + 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true })
  const big = planChanges({ base, desired: { ...clone(base.config), targets }, remote: base, issues: [] })
  assert.ok(big.operations.length > 100)
  assert.ok(big.blockingIssues.some((i) => i.code === 'PLAN_TOO_LARGE'))

  const desired = clone(base.config)
  desired.service.displayName = 'Renamed'
  desired.scheduling.mode = 'LOCAL_FIRST_WAIT'
  desired.targets = [
    { ...desired.targets[1], weight: 55 },
    { key: 'target:new-1', id: null, deploymentId: 'a', priority: 4, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true },
    { key: 'target:new-2', id: null, deploymentId: 'b', priority: 3, weight: 100, degraded: false, enabled: false, maxConcurrencyOverride: null, followModelChanges: true },
  ]
  desired.accessProjectIds = ['p2']
  const input = { base, desired, remote: base, issues: [] }
  const p = planChanges(input)
  assert.deepEqual(p.operations.map((o) => o.opId), [
    'patch-service', 'update-scheduling', 'delete-target:t1',
    'create-target:target:new-2', 'disable-created-target:target:new-2', 'create-target:target:new-1',
    'patch-target:t2', 'grant-access:p2', 'revoke-access:p1',
  ])
  const shuffled = planChanges({ ...input, desired: { ...desired, targets: [...desired.targets].reverse() } })
  assert.equal(shuffled.fingerprint, p.fingerprint)
  assert.deepEqual(planChanges(input), p)
  assert.equal(stableStringify({ b: 1, a: { d: undefined, c: [1, undefined] } }), '{"a":{"c":[1,null]},"b":1}')
})

/* ---------------- 24. layout ---------------- */

test('24. layout geometry, auto layout and persistence', () => {
  const g = baseGraph()
  const targetNode = g.nodes.find((n) => n.id === 'target:t1')
  assert.deepEqual(layout.nodeSize(targetNode), { width: 232, height: 40 + 24 + 56 + 8 })
  const guard = g.nodes.find((n) => n.id === 'guardrail')
  assert.equal(layout.nodeSize(guard).height, 40 + 48 + 56 + 8)
  assert.deepEqual(layout.portAnchor(targetNode, 'route'), { x: targetNode.x, y: targetNode.y + 52 })
  assert.deepEqual(layout.portAnchor(targetNode, 'binding'), { x: targetNode.x + 232, y: targetNode.y + 52 })
  assert.deepEqual(layout.portAnchor(guard, 'masker'), { x: guard.x, y: guard.y + 76 })
  assert.deepEqual(layout.portAnchor(guard, 'request', 'out'), { x: guard.x + 232, y: guard.y + 52 })
  const collapsed = { ...targetNode, collapsed: true }
  assert.equal(layout.nodeSize(collapsed).height, 40)
  assert.equal(layout.portAnchor(collapsed, 'binding').y, collapsed.y + 20)

  assert.equal(layout.bezierPath({ x: 0, y: 0 }, { x: 100, y: 50 }), 'M 0 0 C 50 0, 50 50, 100 50')
  assert.equal(layout.bezierPath({ x: 0, y: 0 }, { x: 10, y: 0 }), 'M 0 0 C 40 0, -30 0, 10 0')

  const huge = layout.fitViewport({ x: 0, y: 0, width: 100000, height: 80000 }, { width: 800, height: 600 })
  assert.equal(huge.zoom, layout.MIN_ZOOM)
  const small = layout.fitViewport({ x: 0, y: 0, width: 100, height: 100 }, { width: 800, height: 600 })
  assert.equal(small.zoom, 1)
  assert.equal(small.x, 350)
  assert.equal(small.y, 250)
  const vp = { x: 30, y: -20, zoom: 0.8 }
  const cursor = { x: 400, y: 300 }
  const world = layout.screenToWorld(cursor, vp)
  const zoomed = layout.zoomAt(vp, cursor, 1.5)
  const back = layout.worldToScreen(world, zoomed)
  assert.ok(Math.abs(back.x - cursor.x) < 1e-9 && Math.abs(back.y - cursor.y) < 1e-9)
  assert.equal(layout.zoomAt(vp, cursor, 100).zoom, layout.MAX_ZOOM)
  assert.equal(layout.clampZoom(0.01), layout.MIN_ZOOM)
  assert.equal(layout.snapToGrid(23), 16)
  assert.equal(layout.snapToGrid(25), 32)
  assert.equal(Object.is(layout.snapToGrid(-3), 0), true)
  assert.equal(layout.snapToGrid(50, 10), 50)

  const service = g.nodes.find((n) => n.id === 'service')
  assert.deepEqual(layout.nodesInRect(g, { x: service.x - 5, y: service.y - 5, width: 10, height: 10 }), ['service'])
  assert.deepEqual(layout.nodesInRect(g, { x: -5000, y: -5000, width: 1, height: 1 }), [])

  // auto layout: deterministic, grid aligned, no overlap inside a column
  let busy = baseGraph()
  busy = run(busy, { type: 'addNode', nodeType: 'project', refId: 'p2' }, base)
  busy = run(busy, { type: 'addNode', nodeType: 'deployment', refId: 'b' }, base)
  busy = run(busy, { type: 'addNode', nodeType: 'target' }, base)
  const a1 = layout.autoLayout(busy, base)
  assert.deepEqual(a1, layout.autoLayout(busy, base))
  const byColumn = new Map()
  for (const n of a1.nodes) {
    const c = NODE_TYPES[n.type].column
    assert.equal(n.x, c * 300 === n.x ? n.x : -1)
    assert.equal(n.y % 16, 0)
    byColumn.set(c, [...(byColumn.get(c) ?? []), layout.nodeRect(n)])
  }
  for (const [, rects] of byColumn) {
    for (let i = 0; i < rects.length; i += 1) {
      for (let j = i + 1; j < rects.length; j += 1) {
        const p = rects[i]
        const q = rects[j]
        const overlap = p.x < q.x + q.width && q.x < p.x + p.width && p.y < q.y + q.height && q.y < p.y + p.height
        assert.equal(overlap, false)
      }
    }
  }
  const t1 = a1.nodes.find((n) => n.id === 'target:t1')
  const t2 = a1.nodes.find((n) => n.id === 'target:t2')
  assert.ok(t1.y < t2.y, 'priority order')
  assert.equal(a1.nodes.find((n) => n.id === 'masker').y, 176)
  assert.equal(a1.nodes.find((n) => n.id === 'project:p1').y < a1.nodes.find((n) => n.id === 'project:p2').y, true)
  assert.equal(layout.placeNewNode(a1, 'project:p2').nodes.find((n) => n.id === 'project:p2').y, a1.nodes.find((n) => n.id === 'project:p2').y)

  // sanitize
  const raw = {
    version: 1,
    viewport: { x: Number.NaN, y: 5, zoom: 10 },
    nodes: {
      service: { x: 1, y: 2 },
      'bad id': { x: 1, y: 2 },
      'target:abc': { x: Number.NaN, y: 1 },
      'project:p1': { x: 2e6, y: 0 },
      'deployment:a': { x: 3, y: 4, collapsed: true, extra: 'drop' },
      router: { x: 1, y: 2, collapsed: false },
    },
  }
  const clean = layout.sanitizeLayout(raw)
  assert.deepEqual(clean, { version: 1, viewport: { x: 0, y: 5, zoom: 2 }, nodes: { 'deployment:a': { x: 3, y: 4, collapsed: true }, router: { x: 1, y: 2 }, service: { x: 1, y: 2 } } })
  assert.equal(layout.sanitizeLayout({ ...raw, version: 2 }), null)
  assert.equal(layout.sanitizeLayout(null), null)
  assert.equal(layout.sanitizeLayout('x'), null)

  // extract: no new targets, no names, addresses or prompts
  const withNew = run(baseGraph(), { type: 'addNode', nodeType: 'target', refId: 'b' }, base)
  const extracted = layout.extractLayout(withNew, { x: 1, y: 2, zoom: 1 })
  assert.ok(!Object.keys(extracted.nodes).some((k) => k.startsWith('target:new-')))
  assert.ok(extracted.nodes['target:t1'])
  const text = JSON.stringify(extracted)
  for (const secret of ['Text Pro', 'You are helpful', 'http://', 'GPU Box', 'Model A', 'text-pro']) assert.ok(!text.includes(secret), secret)
  const restored = layout.applyLayout(baseGraph(), { ...extracted, nodes: { service: { x: 7, y: 9, collapsed: true } } }, base)
  assert.equal(restored.nodes.find((n) => n.id === 'service').x, 7)
  assert.equal(restored.nodes.find((n) => n.id === 'service').collapsed, true)
  assert.ok(restored.nodes.every((n) => Number.isFinite(n.x) && Number.isFinite(n.y)))
})

/* ---------------- 25. ports and cycles ---------------- */

test('25. canConnect reasons, nearestCompatiblePort and cycle detection', () => {
  const g = run(baseGraph(), { type: 'addNode', nodeType: 'deployment', refId: 'b' }, base)
  const cc = (from, to) => canConnect(g, from, to, base)
  const reason = (from, to) => { const r = cc(from, to); assert.equal(r.ok, false); return r.reason }
  assert.equal(reason({ nodeId: 'nope', portId: 'x' }, { nodeId: 'service', portId: 'access' }), '연결할 노드를 찾을 수 없습니다.')
  assert.equal(reason({ nodeId: 'service', portId: 'request' }, { nodeId: 'service', portId: 'access' }), '같은 노드끼리 연결할 수 없습니다.')
  assert.equal(reason({ nodeId: 'deployment:a', portId: 'binding' }, { nodeId: 'target:t1', portId: 'route' }), '출력 포트에서 입력 포트로만 연결할 수 있습니다.')
  assert.equal(reason({ nodeId: 'router', portId: 'targets' }, { nodeId: 'deployment:a', portId: 'binding' }), '이 Deployment에는 이미 Target이 있습니다(서비스당 Deployment 1개).')
  assert.equal(reason({ nodeId: 'project:p1', portId: 'access' }, { nodeId: 'router', portId: 'request' }), '이 포트 조합은 연결할 수 없습니다.')
  assert.equal(reason({ nodeId: 'service', portId: 'request' }, { nodeId: 'guardrail', portId: 'request' }), '자동으로 관리되는 연결입니다.')
  assert.equal(reason({ nodeId: 'project:p1', portId: 'access' }, { nodeId: 'service', portId: 'access' }), '이미 연결되어 있습니다.')
  // single-port replacement
  const rebind = cc({ nodeId: 'target:t1', portId: 'binding' }, { nodeId: 'deployment:b', portId: 'binding' })
  assert.deepEqual(rebind, { ok: true, replaces: ['target:t1/binding->deployment:a/binding'] })
  const swapMasker = run(g, { type: 'removeItems', nodeIds: ['masker'], edgeIds: [] }, base)
  const added = run(swapMasker, { type: 'addNode', nodeType: 'masker', refId: 'a' }, base)
  assert.ok(added.edges.some((e) => e.kind === 'masker'))
  assert.equal(applyCommand(added, { type: 'addNode', nodeType: 'masker' }, base).error, '마스킹 워커는 하나만 둘 수 있습니다.')

  // nearestCompatiblePort follows radius and canConnect
  const depB = g.nodes.find((n) => n.id === 'deployment:b')
  const anchor = layout.portAnchor(depB, 'binding', 'in')
  const from = { nodeId: 'target:t1', portId: 'binding' }
  assert.deepEqual(nearestCompatiblePort(g, from, { x: anchor.x + 5, y: anchor.y - 5 }, 24, base), { nodeId: 'deployment:b', portId: 'binding' })
  assert.equal(nearestCompatiblePort(g, from, { x: anchor.x + 200, y: anchor.y }, 24, base), null)
  assert.equal(nearestCompatiblePort(g, { nodeId: 'deployment:b', portId: 'binding' }, anchor, 24, base), null)

  // hand-made cycle
  const cyclic = { ...g, edges: [...g.edges, { id: 'deployment:a/host->target:t1/route', kind: 'host', from: { nodeId: 'deployment:a', portId: 'host' }, to: { nodeId: 'target:t1', portId: 'route' }, locked: false }] }
  assert.ok(codes(validateGraph(cyclic, base)).includes('CYCLE_DETECTED'))
  const broken = { ...g, nodes: g.nodes.filter((n) => n.id !== 'cache') }
  assert.ok(codes(validateGraph(broken, base)).includes('STRUCTURE_BROKEN'))
  const unbound = run(g, { type: 'addNode', nodeType: 'target' }, base)
  assert.ok(codes(validateGraph(unbound, base)).includes('TARGET_UNBOUND'))
  const counts = issueCountsByNode(validateGraph(unbound, base))
  assert.equal(counts['target:new-1'].error, 1)
})

/* ---------------- 26. history ---------------- */

test('26. history record/undo/redo with a bounded past', () => {
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
})

/* ---------------- 27. body whitelist (runs after all plans) ---------------- */

test('27. every generated body only uses keys from the §4.2 body types', () => {
  const allowed = {
    createService: ['organizationId', 'serviceKey', 'displayName', 'failoverPolicy', 'retryPolicy', 'allowDegraded', 'requiredCapabilitiesJson', 'inputPricePerMillion', 'outputPricePerMillion', 'currency', 'temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode', 'dataBoundaryPolicy'],
    patchService: ['displayName', 'enabled', 'dataBoundaryPolicy', 'requiredCapabilitiesJson', 'currency', 'inputPricePerMillion', 'outputPricePerMillion', 'guardrailAction', 'maskerDeploymentId', 'clearMaskerDeployment', 'maskPersonNames', 'systemPromptTemplate', 'clearSystemPromptTemplate', 'temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode', 'responseCacheEnabled', 'responseCacheTtlSeconds', 'failoverPolicy', 'retryPolicy', 'allowDegraded'],
    updateScheduling: ['mode', 'localWaitMs', 'maxWaitMs', 'maxQueuedRequests', 'maxQueuedBytes', 'maxQueuedPerKey'],
    createTarget: ['deploymentId', 'priority', 'weight', 'degraded', 'followModelChanges', 'maxConcurrencyOverride'],
    patchTarget: ['priority', 'weight', 'degraded', 'enabled', 'maxConcurrencyOverride', 'followModelChanges'],
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
})

/* ---------------- extras ---------------- */

test('describeNode / nodeFacts / describeEdge produce view models for every node', () => {
  const g = baseGraph()
  for (const node of g.nodes) {
    const view = describeNode(node, g, base)
    assert.ok(view.title && view.typeLabel && view.icon, node.id)
    assert.ok(view.summary.length <= 2)
    assert.ok(['healthy', 'warning', 'error', 'muted', 'unknown'].includes(view.tone))
    assert.ok(Array.isArray(nodeFacts(node, g, base)))
  }
  const t1 = describeNode(g.nodes.find((n) => n.id === 'target:t1'), g, base)
  assert.equal(t1.title, 'Model A')
  assert.equal(t1.subtitle, 'P1 · W80 (100%)')
  assert.equal(describeNode(g.nodes.find((n) => n.id === 'service'), g, base).locked, true)
  assert.equal(describeNode(g.nodes.find((n) => n.id === 'deployment:x'), g, base).badges.includes('외부'), true)
  for (const e of g.edges) assert.ok(describeEdge(e).label)
  assert.equal(describeNode(g.nodes.find((n) => n.id === 'target:t1'), createSkeletonGraph('svc1'), base).title, '미연결 Target')
})

test('mergeConfigs and configsEqual', () => {
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
})

test('clientToLocal compensates the console CSS zoom', () => {
  const rect = { left: 100, top: 50, width: 1150 }
  assert.deepEqual(layout.clientToLocal({ x: 100, y: 50 }, rect, 1000), { x: 0, y: 0 })
  const p = layout.clientToLocal({ x: 100 + 575, y: 50 + 115 }, rect, 1000)
  assert.ok(Math.abs(p.x - 500) < 1e-9 && Math.abs(p.y - 100) < 1e-9)
  // scale 1
  assert.deepEqual(layout.clientToLocal({ x: 30, y: 40 }, { left: 10, top: 10, width: 400 }, 400), { x: 20, y: 30 })
  // zero / NaN layout width falls back to scale 1
  assert.deepEqual(layout.clientToLocal({ x: 30, y: 40 }, { left: 10, top: 10, width: 400 }, 0), { x: 20, y: 30 })
  assert.equal(layout.clientScale(0, 0), 1)
  assert.equal(layout.clientScale(1150, 1000), 1.15)
})

test('suggestNodePosition places in the catalog column below existing nodes, else nudges from the view center', () => {
  const mk = (id, type, x, y) => ({ id, type, refId: null, x, y, collapsed: false, props: {} })
  const g = { nodes: [mk('deployment:a', 'deployment', 7 * layout.COLUMN_WIDTH, 0)], edges: [], nextSeq: 1 }
  const bottom = layout.nodeRect(g.nodes[0])
  const p = layout.suggestNodePosition(g, 'deployment', { x: 0, y: 0 }, { x: 0, y: 0, width: 5000, height: 5000 })
  assert.equal(p.x, 7 * layout.COLUMN_WIDTH)
  assert.ok(p.y >= bottom.y + bottom.height)
  assert.equal(p.y % catalog.GRID_SIZE, 0)
  // column spot far outside the visible rect -> view center, not overlapping the existing node
  const q = layout.suggestNodePosition(g, 'deployment', { x: 7 * layout.COLUMN_WIDTH + 100, y: 40 }, { x: 0, y: 0, width: 300, height: 300 })
  const size = layout.nodeSize({ ...mk('n', 'deployment', 0, 0) })
  const overlaps = q.x < bottom.x + bottom.width && q.x + size.width > bottom.x && q.y < bottom.y + bottom.height && q.y + size.height > bottom.y
  assert.equal(overlaps, false)
  // empty column starts at y = 0
  assert.equal(layout.suggestNodePosition({ nodes: [], edges: [], nextSeq: 1 }, 'project', { x: 0, y: 0 }, null).y, 0)
})

/* ---------------- partial apply after a swap / config dirtiness ---------------- */

function swapScenario() {
  let swap = run(baseGraph(), { type: 'bindTarget', targetNodeId: 'target:t1', deploymentId: 'x' }, base)
  swap = run(swap, { type: 'bindTarget', targetNodeId: 'target:t2', deploymentId: 'a' }, base)
  return swap
}

test('rebase after a swap whose pre-deletes succeeded keeps the unapplied replacements as pending creates', () => {
  const swap = swapScenario()
  const remote = remoteWith((r) => { r.config.targets = [] })
  const naive = rebase({ graph: swap, base, remote })
  assert.equal(naive.graph.nodes.filter((n) => n.type === 'target').length, 0, 'documents the old data loss without deletedTargetKeys')

  const result = rebase({ graph: swap, base, remote, createdTargetIds: {}, deletedTargetKeys: ['target:t1', 'target:t2'] })
  const targets = result.graph.nodes.filter((n) => n.type === 'target')
  assert.equal(targets.length, 2)
  assert.ok(targets.every((n) => n.refId === null && /^target:new-\d+$/.test(n.id)))
  const byDep = Object.fromEntries(compile(result.graph, remote).targets.map((t) => [t.deploymentId, t]))
  assert.equal(byDep.x.weight, 80)
  assert.equal(byDep.a.weight, 100)
  assert.equal(byDep.a.maxConcurrencyOverride, 2)
  const again = plan(remote, result.graph, remote)
  assert.deepEqual(again.operations.filter((o) => o.kind !== 'createTarget'), [])
  assert.equal(again.operations.filter((o) => o.kind === 'createTarget').length, 2)
  assert.equal(again.conflicts.length, 0)
})

test('rebase does not detach targets that were re-created, and intentional deletions stay deleted', () => {
  const swap = swapScenario()
  const remote = remoteWith((r) => { r.config.targets = [target('n1', 'x', { priority: 1, weight: 80 })] })
  const result = rebase({ graph: swap, base, remote, createdTargetIds: { 'target:t1': 'n1' }, deletedTargetKeys: ['target:t1', 'target:t2'] })
  const ids = result.graph.nodes.filter((n) => n.type === 'target').map((n) => n.id).sort()
  assert.equal(ids.length, 2)
  assert.ok(ids.includes('target:n1'))

  const removed = run(baseGraph(), { type: 'removeItems', nodeIds: ['target:t2'], edgeIds: [] }, base)
  const remote2 = remoteWith((r) => { r.config.targets = r.config.targets.filter((t) => t.id !== 't2') })
  const kept = rebase({ graph: removed, base, remote: remote2, deletedTargetKeys: ['target:t2'] })
  assert.deepEqual(kept.graph.nodes.filter((n) => n.type === 'target').map((n) => n.id), ['target:t1'])
  assert.equal(plan(remote2, kept.graph, remote2).operations.length, 0)
})

test('isConfigDirty is independent of undo history for existing services and new drafts', () => {
  assert.equal(diff.isConfigDirty(baseGraph(), base), false)
  const draft = draftSnapshot()
  const fresh = decompile(draft, null)
  assert.equal(diff.isConfigDirty(fresh, draft), false)
  const edited = props(fresh, 'service', { displayName: 'Changed' }, draft)
  assert.equal(diff.isConfigDirty(edited, draft), true)
  // a stash/restore round-trip drops history but the graph itself stays dirty
  assert.equal(diff.isConfigDirty(structuredClone(edited), draft), true)
  const existing = props(baseGraph(), 'service', { displayName: 'Changed' }, base)
  assert.equal(diff.isConfigDirty(existing, base), true)
})
