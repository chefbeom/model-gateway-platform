import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

const { buildSnapshot, safeAddressLabel, FlowLoadError } = await loadTypeScript('../src/flow-designer/flowSnapshot.ts', import.meta.url)
const { operationRequest, executePlan, previewRequest } = await loadTypeScript('../src/flow-designer/execute.ts', import.meta.url)
const drafts = await loadTypeScript('../src/flow-designer/draftStore.ts', import.meta.url)
const { decompile } = await loadTypeScript('../src/flow-designer/decompile.ts', import.meta.url)
const { compile } = await loadTypeScript('../src/flow-designer/compile.ts', import.meta.url)
const { applyCommand } = await loadTypeScript('../src/flow-designer/graphOps.ts', import.meta.url)
const { validateGraph } = await loadTypeScript('../src/flow-designer/validate.ts', import.meta.url)
const { planChanges, rebase } = await loadTypeScript('../src/flow-designer/diff.ts', import.meta.url)

/* ---------------- fixtures ---------------- */

function raw(extra = {}) {
  return {
    organizationId: 'org1',
    serviceId: 'svc1',
    loadedAt: '2026-11-23T00:00:00.000Z',
    unavailable: [],
    services: [
      // maskerDeploymentId, temperatureValue, systemPromptTemplate and guardrailAction are omitted (non_null).
      { id: 'svc1', organizationId: 'org1', serviceKey: 'chat', displayName: 'Chat', failoverPolicy: 'STRICT', retryPolicy: 'SAFE', allowDegraded: false,
        requiredCapabilitiesJson: '["tools","chat","chat"]', inputPricePerMillion: 1, outputPricePerMillion: 2, currency: 'KRW', enabled: true,
        temperaturePolicy: 'REQUEST', reasoningEffort: 'REQUEST', openAiFastMode: false, dataBoundaryPolicy: 'EXTERNAL_ALLOWED', maskPersonNames: true,
        responseCacheEnabled: false, responseCacheTtlSeconds: 300 },
      { id: 'svc2', serviceKey: 'other', displayName: 'Other' }
    ],
    targets: [
      { id: 'tB', serviceId: 'svc1', deploymentId: 'dep-local', priority: 2, weight: 10, degraded: false, enabled: true },
      { id: 'tA', serviceId: 'svc1', deploymentId: 'dep-ext', priority: 1, weight: 5, degraded: true, enabled: false, maxConcurrencyOverride: 3, followModelChanges: false }
    ],
    scheduling: { settings: { mode: 'LOCAL_FIRST_WAIT', localWaitMs: 100, maxWaitMs: 200, maxQueuedRequests: 5, maxQueuedBytes: 6, maxQueuedPerKey: 7 }, queue: {} },
    dataProtection: { mode: 'ENFORCE', level: 'STRICT', externalAction: 'BLOCK', allowExternalFailover: false, detectSecrets: true, detectPii: true, detectFinancial: true },
    endpoints: [{ id: 'ep1', nodeId: 'n1', displayName: 'GPU', runtimeType: 'LM_STUDIO', baseUrl: 'http://user:pw@10.0.0.1:1234/v1?token=x#a', enabled: true, healthStatus: 'HEALTHY' }],
    endpointDeployments: { ep1: [{ id: 'dep-local', runtimeEndpointId: 'ep1', providerModelId: 'llama', displayName: 'Llama', loaded: true, enabled: true, healthStatus: 'HEALTHY', maxConcurrency: 2, contextLength: 8192 }] },
    providers: [{ id: 'pv1', providerType: 'OPENAI', displayName: 'OpenAI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true, zeroRetentionVerified: false }],
    providerModels: { pv1: [{ id: 'dep-ext', externalProviderId: 'pv1', providerModelId: 'gpt', displayName: 'GPT', enabled: true, healthStatus: 'HEALTHY', maxConcurrency: 4 }] },
    projects: [{ id: 'p2', name: 'Beta', status: 'ACTIVE' }, { id: 'p1', name: 'Alpha', status: 'ACTIVE' }],
    projectAccess: { p1: [{ id: 'svc1', serviceKey: 'chat' }], p2: [{ id: 'svc2' }] },
    maskers: [{ deploymentId: 'dep-local', displayName: 'Llama', providerModelId: 'llama', sourceType: 'LOCAL_RUNTIME', sourceName: 'GPU', eligible: true, trustReason: '' }],
    ...extra
  }
}

/* ---------------- 1. buildSnapshot ---------------- */

test('buildSnapshot fills non_null omissions with defaults and sorts collections', () => {
  const snapshot = buildSnapshot(raw())
  const { service } = snapshot.config
  assert.equal(service.maskerDeploymentId, null)
  assert.equal(service.temperatureValue, null)
  assert.equal(service.systemPromptTemplate, null)
  assert.equal(service.guardrailAction, 'ALLOW')
  assert.equal(service.requiredCapabilitiesJson, '["chat","tools"]')
  assert.equal(snapshot.promptVersion, 1)
  const [first, second] = snapshot.config.targets
  assert.deepEqual([first.key, second.key], ['target:tA', 'target:tB'])
  assert.equal(second.maxConcurrencyOverride, null)
  assert.equal(second.followModelChanges, true)
  assert.equal(first.followModelChanges, false)
  assert.equal(first.maxConcurrencyOverride, 3)
  assert.equal(snapshot.config.scheduling.mode, 'LOCAL_FIRST_WAIT')
  assert.deepEqual(snapshot.config.accessProjectIds, ['p1'])
  assert.equal(snapshot.accessComplete, true)
  assert.deepEqual(snapshot.catalog.projects.map(p => p.id), ['p1', 'p2'])
  assert.equal(snapshot.dataProtection.detectSecrets, true)
  assert.equal(snapshot.dataProtection.detectMedia, false)
  assert.equal(snapshot.catalog.endpoints[0].addressLabel, 'http://10.0.0.1:1234/v1')
  assert.equal(JSON.stringify(snapshot).includes('pw@'), false)
})

test('buildSnapshot maps promptVersion and normalizes the prompt', () => {
  const data = raw()
  data.services[0] = { ...data.services[0], promptVersion: 4, systemPromptTemplate: '  hello  ' }
  const snapshot = buildSnapshot(data)
  assert.equal(snapshot.promptVersion, 4)
  assert.equal(snapshot.config.service.systemPromptTemplate, 'hello')
})

test('external models are external, loaded and keep the provider id', () => {
  const snapshot = buildSnapshot(raw())
  const external = snapshot.catalog.deployments.find(item => item.id === 'dep-ext')
  const local = snapshot.catalog.deployments.find(item => item.id === 'dep-local')
  assert.equal(external.external, true)
  assert.equal(external.loaded, true)
  assert.equal(external.externalProviderId, 'pv1')
  assert.equal(external.runtimeEndpointId, null)
  assert.equal(local.external, false)
  assert.equal(local.runtimeEndpointId, 'ep1')
  assert.equal(local.contextLength, 8192)
  assert.equal(snapshot.catalog.deployments[0].id, 'dep-local', 'local deployments come first')
})

test('accessComplete is false when an access read fails or projects exceed 200', () => {
  assert.equal(buildSnapshot(raw({ projectAccess: { p1: [{ id: 'svc1' }], p2: null } })).accessComplete, false)
  assert.equal(buildSnapshot(raw({ projectAccess: { p1: [{ id: 'svc1' }] } })).accessComplete, false)
  assert.equal(buildSnapshot(raw({ projects: null })).accessComplete, false)
  const many = Array.from({ length: 201 }, (_, index) => ({ id: `p${index}`, name: `P${index}`, status: 'ACTIVE' }))
  assert.equal(buildSnapshot(raw({ projects: many, projectAccess: {} })).accessComplete, false)
  const exactly = Array.from({ length: 200 }, (_, index) => ({ id: `p${index}`, name: `P${index}`, status: 'ACTIVE' }))
  const access = Object.fromEntries(exactly.map(item => [item.id, []]))
  assert.equal(buildSnapshot(raw({ projects: exactly, projectAccess: access })).accessComplete, true)
})

test('buildSnapshot errors: unknown service and missing required reads', () => {
  assert.throws(() => buildSnapshot(raw({ serviceId: 'missing' })), error => error instanceof FlowLoadError && error.code === 'SERVICE_NOT_FOUND')
  assert.throws(() => buildSnapshot(raw({ targets: null })), error => error instanceof FlowLoadError && error.code === 'REQUIRED_READ_FAILED')
  assert.throws(() => buildSnapshot(raw({ scheduling: null })), error => error instanceof FlowLoadError && error.code === 'REQUIRED_READ_FAILED')
})

test('new service snapshot is a default draft with a loaded catalog', () => {
  const snapshot = buildSnapshot(raw({ serviceId: null, targets: [], scheduling: null, dataProtection: null, projectAccess: {} }))
  assert.equal(snapshot.serviceId, null)
  assert.equal(snapshot.promptVersion, 1)
  assert.equal(snapshot.accessComplete, true)
  assert.equal(snapshot.config.service.serviceKey, '')
  assert.equal(snapshot.config.service.dataBoundaryPolicy, 'EXTERNAL_ALLOWED')
  assert.deepEqual(snapshot.config.targets, [])
  assert.equal(snapshot.catalog.services.length, 2)
})

test('unavailable paths are carried through', () => {
  const snapshot = buildSnapshot(raw({ unavailable: ['/api/admin/organizations/org1/masker-deployments'] }))
  assert.deepEqual(snapshot.unavailable, ['/api/admin/organizations/org1/masker-deployments'])
})

/* ---------------- 2. safeAddressLabel ---------------- */

test('safeAddressLabel strips credentials, query and fragment', () => {
  assert.equal(safeAddressLabel('http://user:pw@10.0.0.1:1234/v1?token=x#a'), 'http://10.0.0.1:1234/v1')
  assert.equal(safeAddressLabel('https://api.example.com/'), 'https://api.example.com')
  assert.equal(safeAddressLabel('not a url'), '주소 확인 필요')
  assert.equal(safeAddressLabel(''), '주소 확인 필요')
  assert.equal(safeAddressLabel('javascript:alert(1)'), '주소 확인 필요')
})

/* ---------------- 3. operationRequest ---------------- */

const common = { risk: 'safe', summary: 's', changes: [] }
const existing = { kind: 'existing', id: 'svc 1/x' }
const ctx = { serviceId: 'new-svc', createdTargetIds: { 'target:new-1': 'created-t' } }

test('operationRequest builds the exact requests of spec 7.5', () => {
  const createBody = { organizationId: 'org1', serviceKey: 'k', displayName: 'K' }
  assert.deepEqual(operationRequest({ ...common, opId: 'create-service', kind: 'createService', body: createBody }, ctx),
    { method: 'POST', path: '/api/admin/services', body: createBody })
  assert.deepEqual(operationRequest({ ...common, opId: 'patch-service', kind: 'patchService', service: existing, body: { enabled: false } }, ctx),
    { method: 'PATCH', path: '/api/admin/services/svc%201%2Fx', body: { enabled: false } })
  const scheduling = { mode: 'IMMEDIATE', localWaitMs: 1, maxWaitMs: 2, maxQueuedRequests: 3, maxQueuedBytes: 4, maxQueuedPerKey: 5 }
  assert.deepEqual(operationRequest({ ...common, opId: 'update-scheduling', kind: 'updateScheduling', service: existing, body: scheduling }, ctx),
    { method: 'PATCH', path: '/api/admin/services/svc%201%2Fx/scheduling', body: scheduling })
  assert.deepEqual(operationRequest({ ...common, opId: 'create-target:target:new-1', kind: 'createTarget', service: { kind: 'created' }, key: 'target:new-1',
    body: { deploymentId: 'd', priority: 1, weight: 2, degraded: false, followModelChanges: true } }, ctx),
    { method: 'POST', path: '/api/admin/services/new-svc/targets', body: { deploymentId: 'd', priority: 1, weight: 2, degraded: false, followModelChanges: true } })
  assert.deepEqual(operationRequest({ ...common, opId: 'create-target:target:new-1', kind: 'createTarget', service: existing, key: 'target:new-1',
    body: { deploymentId: 'd', priority: 1, weight: 2, degraded: false, followModelChanges: true, maxConcurrencyOverride: null } }, ctx).body,
    { deploymentId: 'd', priority: 1, weight: 2, degraded: false, followModelChanges: true })
  assert.deepEqual(operationRequest({ ...common, opId: 'disable-created-target:target:new-1', kind: 'patchTarget', service: { kind: 'created' }, target: { kind: 'created', key: 'target:new-1' }, body: { enabled: false } }, ctx),
    { method: 'PATCH', path: '/api/admin/services/new-svc/targets/created-t', body: { enabled: false } })
  assert.deepEqual(operationRequest({ ...common, opId: 'patch-target:t/1', kind: 'patchTarget', service: existing, target: { kind: 'existing', id: 't/1' }, body: { weight: 3 } }, ctx),
    { method: 'PATCH', path: '/api/admin/services/svc%201%2Fx/targets/t%2F1', body: { weight: 3 } })
  assert.deepEqual(operationRequest({ ...common, opId: 'delete-target:t1', kind: 'deleteTarget', service: existing, targetId: 't 1' }, ctx),
    { method: 'DELETE', path: '/api/admin/services/svc%201%2Fx/targets/t%201' })
  assert.deepEqual(operationRequest({ ...common, opId: 'grant-access:p', kind: 'grantAccess', service: existing, projectId: 'p/1' }, ctx),
    { method: 'POST', path: '/api/admin/projects/p%2F1/service-access', body: { serviceId: 'svc 1/x' } })
  assert.deepEqual(operationRequest({ ...common, opId: 'grant-access:p', kind: 'grantAccess', service: { kind: 'created' }, projectId: 'p1' }, ctx),
    { method: 'POST', path: '/api/admin/projects/p1/service-access', body: { serviceId: 'new-svc' } })
  assert.deepEqual(operationRequest({ ...common, opId: 'revoke-access:p', kind: 'revokeAccess', service: existing, projectId: 'p1' }, ctx),
    { method: 'DELETE', path: '/api/admin/projects/p1/service-access/svc%201%2Fx' })
})

test('operationRequest throws for unresolved created references and previewRequest uses placeholders', () => {
  const op = { ...common, opId: 'patch-service', kind: 'patchService', service: { kind: 'created' }, body: {} }
  assert.throws(() => operationRequest(op, { serviceId: null, createdTargetIds: {} }), /선행 생성 작업 결과가 없습니다/)
  assert.throws(() => operationRequest({ ...common, opId: 'x', kind: 'patchTarget', service: existing, target: { kind: 'created', key: 'k' }, body: {} }, { serviceId: 's', createdTargetIds: {} }), /선행 생성/)
  assert.equal(previewRequest(op).method, 'PATCH')
  assert.match(previewRequest(op).path, /\{새 서비스 ID\}/)
})

/* ---------------- 4. executePlan ---------------- */

function fakeTransport(handler) {
  const calls = []
  return {
    calls,
    async request(method, path, body) {
      calls.push({ method, path, body })
      return handler({ method, path, body }, calls.length)
    }
  }
}
const httpError = (status, message, code) => Object.assign(new Error(message), { status, code })

function samplePlan() {
  return {
    operations: [
      { ...common, opId: 'create-service', kind: 'createService', body: { organizationId: 'org1', serviceKey: 'k' } },
      { ...common, opId: 'create-target:target:new-1', kind: 'createTarget', service: { kind: 'created' }, key: 'target:new-1', body: { deploymentId: 'd', priority: 1, weight: 1, degraded: false, followModelChanges: true } },
      { ...common, opId: 'disable-created-target:target:new-1', kind: 'patchTarget', service: { kind: 'created' }, target: { kind: 'created', key: 'target:new-1' }, body: { enabled: false } },
      { ...common, opId: 'delete-target:old', kind: 'deleteTarget', service: { kind: 'created' }, targetId: 'old' },
      { ...common, opId: 'grant-access:p1', kind: 'grantAccess', service: { kind: 'created' }, projectId: 'p1' }
    ],
    conflicts: [], blockingIssues: [], notes: [], remoteDrift: false, fingerprint: 'f'
  }
}

test('executePlan runs sequentially, resolves created ids and treats 404 deletes as already applied', async () => {
  const transport = fakeTransport(({ method, path }) => {
    if (method === 'POST' && path === '/api/admin/services') return { id: 'svc-new' }
    if (method === 'POST' && path.endsWith('/targets')) return { id: 'tgt-new' }
    if (method === 'DELETE') throw httpError(404, 'gone', 'TARGET_NOT_FOUND')
    return undefined
  })
  const progress = []
  const report = await executePlan(samplePlan(), transport, { serviceId: null, onProgress: results => progress.push(results.map(item => item.status).join(',')) })
  assert.deepEqual(transport.calls.map(call => `${call.method} ${call.path}`), [
    'POST /api/admin/services',
    'POST /api/admin/services/svc-new/targets',
    'PATCH /api/admin/services/svc-new/targets/tgt-new',
    'DELETE /api/admin/services/svc-new/targets/old',
    'POST /api/admin/projects/p1/service-access'
  ])
  assert.equal(report.serviceId, 'svc-new')
  assert.deepEqual(report.createdTargetIds, { 'target:new-1': 'tgt-new' })
  assert.deepEqual(report.results.map(item => item.status), ['succeeded', 'succeeded', 'succeeded', 'already-applied', 'succeeded'])
  assert.equal(report.completed, true)
  assert.equal(report.failedOpId, null)
  assert.ok(progress.length >= 5 * 2, 'progress fires on every status change')
  assert.equal(progress[0], 'pending,pending,pending,pending,pending')
  assert.ok(progress.some(line => line.startsWith('running,')))
})

test('executePlan stops at the first failure and skips the rest', async () => {
  const transport = fakeTransport(({ method, path }) => {
    if (method === 'POST' && path === '/api/admin/services') return { id: 'svc-new' }
    if (method === 'POST' && path.endsWith('/targets')) throw httpError(500, 'boom', 'INTERNAL')
    return undefined
  })
  const seen = []
  const report = await executePlan(samplePlan(), transport, { serviceId: null, onProgress: results => seen.push(results) })
  assert.equal(transport.calls.length, 2)
  assert.deepEqual(report.results.map(item => item.status), ['succeeded', 'failed', 'skipped', 'skipped', 'skipped'])
  assert.equal(report.completed, false)
  assert.equal(report.failedOpId, 'create-target:target:new-1')
  assert.equal(report.results[1].message, 'boom')
  assert.equal(report.results[1].code, 'INTERNAL')
  assert.equal(report.results[1].httpStatus, 500)
  assert.equal(report.serviceId, 'svc-new')
  assert.deepEqual(report.createdTargetIds, {})
  assert.notEqual(seen[0], seen[1], 'progress receives copies')
})

test('executePlan fails the third operation on HTTP 500 and reports a non-404 delete failure', async () => {
  const plan = {
    operations: [
      { ...common, opId: 'patch-service', kind: 'patchService', service: { kind: 'existing', id: 's' }, body: { enabled: true } },
      { ...common, opId: 'update-scheduling', kind: 'updateScheduling', service: { kind: 'existing', id: 's' }, body: {} },
      { ...common, opId: 'delete-target:t', kind: 'deleteTarget', service: { kind: 'existing', id: 's' }, targetId: 't' },
      { ...common, opId: 'revoke-access:p', kind: 'revokeAccess', service: { kind: 'existing', id: 's' }, projectId: 'p' }
    ],
    conflicts: [], blockingIssues: [], notes: [], remoteDrift: false, fingerprint: 'f'
  }
  const transport = fakeTransport((_, count) => { if (count === 3) throw httpError(500, 'server error'); return undefined })
  const report = await executePlan(plan, transport, { serviceId: 's' })
  assert.deepEqual(report.results.map(item => item.status), ['succeeded', 'succeeded', 'failed', 'skipped'])
  assert.equal(report.completed, false)
  assert.equal(report.failedOpId, 'delete-target:t')
})

test('executePlan fails an operation whose created reference cannot be resolved', async () => {
  const plan = { ...samplePlan(), operations: samplePlan().operations.slice(2, 3) }
  const transport = fakeTransport(() => undefined)
  const report = await executePlan(plan, transport, { serviceId: 's' })
  assert.equal(transport.calls.length, 0)
  assert.equal(report.results[0].status, 'failed')
  assert.equal(report.results[0].message, '선행 생성 작업 결과가 없습니다.')
  assert.equal(report.completed, false)
})

/* ---------------- 5. draftStore ---------------- */

function draft(userId, organizationId, serviceId, savedAt = 1) {
  return { userId, organizationId, serviceId, base: { marker: serviceId }, graph: { marker: serviceId }, viewport: { x: 0, y: 0, zoom: 1 }, savedAt }
}

test('drafts are isolated by user, organization and service and are taken once', () => {
  drafts.clearDrafts()
  drafts.stashDraft(draft('u1', 'o1', 's1'))
  drafts.stashDraft(draft('u1', 'o1', null))
  assert.equal(drafts.hasDraft('u1', 'o1', 's1'), true)
  assert.equal(drafts.hasDraft('u1', 'o1', null), true)
  assert.equal(drafts.takeDraft('u2', 'o1', 's1'), null)
  assert.equal(drafts.takeDraft('u1', 'o2', 's1'), null)
  assert.equal(drafts.hasDraft('u1', 'o1', 's1'), true)
  const taken = drafts.takeDraft('u1', 'o1', 's1')
  assert.equal(taken.graph.marker, 's1')
  assert.equal(drafts.hasDraft('u1', 'o1', 's1'), false)
  assert.equal(drafts.takeDraft('u1', 'o1', 's1'), null)
  assert.notEqual(drafts.draftKey('u1', 'o1', null), drafts.draftKey('u1', 'o1', 'null'))
  drafts.clearDrafts()
})

test('drafts keep at most 10 entries (LRU)', () => {
  drafts.clearDrafts()
  assert.equal(drafts.MAX_DRAFTS, 10)
  for (let index = 0; index < 12; index += 1) drafts.stashDraft(draft('u', 'o', `s${index}`))
  assert.equal(drafts.hasDraft('u', 'o', 's0'), false)
  assert.equal(drafts.hasDraft('u', 'o', 's1'), false)
  assert.equal(drafts.hasDraft('u', 'o', 's2'), true)
  assert.equal(drafts.hasDraft('u', 'o', 's11'), true)
  drafts.stashDraft(draft('u', 'o', 's2')) // refresh recency
  drafts.stashDraft(draft('u', 'o', 's12'))
  assert.equal(drafts.hasDraft('u', 'o', 's2'), true)
  assert.equal(drafts.hasDraft('u', 'o', 's3'), false)
  drafts.clearDrafts()
})

test('installDraftAutoClear clears on session expiry, drops other users on session change and unsubscribes', () => {
  drafts.clearDrafts()
  const listeners = new Map()
  const target = {
    addEventListener: (type, listener) => listeners.set(type, listener),
    removeEventListener: (type, listener) => { if (listeners.get(type) === listener) listeners.delete(type) }
  }
  const uninstall = drafts.installDraftAutoClear(target)
  drafts.stashDraft(draft('u1', 'o', 's'))
  drafts.stashDraft(draft('u2', 'o', 's'))
  listeners.get('aiconnect:session')({ detail: { user: { id: 'u1' } } })
  assert.equal(drafts.hasDraft('u1', 'o', 's'), true)
  assert.equal(drafts.hasDraft('u2', 'o', 's'), false)
  listeners.get('aiconnect:session-expired')({})
  assert.equal(drafts.hasDraft('u1', 'o', 's'), false)
  drafts.stashDraft(draft('u1', 'o', 's'))
  uninstall()
  assert.equal(listeners.size, 0)
  assert.equal(drafts.hasDraft('u1', 'o', 's'), true)
  drafts.clearDrafts()
})

/* ---------------- 6. ConsoleShell source ---------------- */

const shell = readFileSync(new URL('../src/ConsoleShell.vue', import.meta.url), 'utf8')

test('ConsoleShell wires the flow designer page for organization and platform admins only', () => {
  assert.match(shell, /defineAsyncComponent\(\(\) => import\('\.\/flow-designer\/FlowDesignerPage\.vue'\)\)/)
  assert.match(shell, /type PageKey = [^\n]*'flow-designer'/)
  assert.match(shell, /function knownPage\(value: string\): value is PageKey \{\s*return \[[^\]]*'flow-designer'/)
  assert.match(shell, /<FlowDesignerPage v-else-if="isOrgOrPlatformAdmin && page === 'flow-designer'"/)
  const block = name => {
    const start = shell.indexOf(`const ${name}`)
    assert.ok(start >= 0, `${name} exists`)
    return shell.slice(start, shell.indexOf('\n]', start))
  }
  assert.match(block('adminNavItems: NavItem[]'), /id: 'flow-designer'/)
  assert.doesNotMatch(block('directorNavItems: NavItem[]'), /flow-designer/)
  assert.doesNotMatch(block('developerNavItems: NavItem[]'), /flow-designer/)
  assert.equal(shell.match(/id: 'flow-designer'/g).length, 1)
})

/* ---------------- 7. static safety ---------------- */

test('flow-designer sources never use local storage, session writes or raw HTML', () => {
  const dir = new URL('../src/flow-designer/', import.meta.url)
  const files = readdirSync(dir).filter(name => /\.(ts|vue)$/.test(name))
  assert.ok(files.length >= 10)
  for (const name of files) {
    const text = readFileSync(new URL(name, dir), 'utf8')
    assert.doesNotMatch(text, /localStorage/, `${name} must not use localStorage`)
    assert.doesNotMatch(text, /sessionStorage\.setItem/, `${name} must not write sessionStorage`)
    assert.doesNotMatch(text, /v-html/, `${name} must not use v-html`)
    assert.doesNotMatch(text, /innerHTML/, `${name} must not use innerHTML`)
  }
})

test('pure adapter modules stay free of vue, api and DOM dependencies', () => {
  for (const name of ['flowSnapshot.ts', 'execute.ts', 'draftStore.ts']) {
    const text = readFileSync(new URL(`../src/flow-designer/${name}`, import.meta.url), 'utf8')
    assert.doesNotMatch(text, /from ['"]vue['"]|from ['"]\.\.\/api['"]|\bwindow\b|\bdocument\b|sessionStorage/, `${name} must stay pure`)
  }
})

test('existing pages are untouched by the flow designer', () => {
  for (const name of ['ServicesPage.vue', 'RoutingPolicyPanel.vue', 'InfrastructurePage.vue', 'ProjectsPage.vue', 'DataProtectionPage.vue']) {
    const text = readFileSync(new URL(`../src/${name}`, import.meta.url), 'utf8')
    assert.equal(text.includes('flow-designer'), false, `${name} must not reference flow-designer`)
  }
})

test('FlowCanvas onPointerUp never reads `moved` after resetInteraction()', () => {
  const text = readFileSync(new URL('../src/flow-designer/FlowCanvas.vue', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
  const start = text.indexOf('function onPointerUp(')
  const end = text.indexOf('\nfunction ', start + 1)
  assert.ok(start >= 0 && end > start, 'onPointerUp must exist')
  const body = text.slice(start, end)
  // resetInteraction() clears the module-level `moved`, so each mode branch must capture it first.
  for (const branch of body.split(/\}\s*else if \(mode ===|if \(mode ===/).slice(1)) {
    const reset = branch.indexOf('resetInteraction()')
    if (reset < 0) continue
    assert.doesNotMatch(branch.slice(reset + 'resetInteraction()'.length), /\bmoved\b/, 'read `moved` into a local before resetInteraction()')
  }
})

test('project access node add, grant, revoke executes plan and rebases cleanly', async () => {
  const base = buildSnapshot(raw())
  let graph = decompile(base, null)

  graph = applyCommand(graph, { type: 'addNode', nodeType: 'project', refId: 'p2', position: { x: 40, y: 200 } }, base).graph
  graph = applyCommand(graph, { type: 'setAccess', projectNodeId: 'project:p2', granted: true }, base).graph
  graph = applyCommand(graph, { type: 'setAccess', projectNodeId: 'project:p1', granted: false }, base).graph

  const desired = compile(graph, base)
  assert.deepEqual(desired.accessProjectIds, ['p2'])

  const issues = validateGraph(graph, base)
  assert.equal(issues.filter(issue => issue.severity === 'error').length, 0)

  const plan = planChanges({ base, desired, remote: base, issues })
  assert.equal(plan.blockingIssues.length, 0)
  assert.equal(plan.conflicts.length, 0)
  assert.deepEqual(plan.operations.map(op => op.opId), ['grant-access:p2', 'revoke-access:p1'])

  const transport = fakeTransport(() => undefined)
  const report = await executePlan(plan, transport, { serviceId: 'svc1' })
  assert.equal(report.completed, true)
  assert.deepEqual(transport.calls, [
    { method: 'POST', path: '/api/admin/projects/p2/service-access', body: { serviceId: 'svc1' } },
    { method: 'DELETE', path: '/api/admin/projects/p1/service-access/svc1', body: undefined }
  ])

  const updatedRemote = buildSnapshot(raw({
    projectAccess: { p1: [], p2: [{ id: 'svc1', serviceKey: 'chat' }] }
  }))
  const rebased = rebase({ graph, base, remote: updatedRemote })
  assert.equal(rebased.conflicts.length, 0)
  assert.deepEqual(compile(rebased.graph, updatedRemote).accessProjectIds, ['p2'])
  assert.equal(rebased.graph.edges.some(edge => edge.kind === 'access' && edge.from.nodeId === 'project:p2'), true)
  assert.equal(rebased.graph.edges.some(edge => edge.kind === 'access' && edge.from.nodeId === 'project:p1'), false)
})

test('executePlan reports deletedTargetKeys for succeeded and already-applied deletes only', async () => {
  const mk = (id) => ({ ...common, opId: `delete-target:${id}`, kind: 'deleteTarget', service: { kind: 'existing', id: 's' }, targetId: id })
  const plan = { ...samplePlan(), operations: [mk('a'), mk('b'), mk('c'), mk('d')] }
  const transport = fakeTransport(({ path }) => {
    if (path.endsWith('/b')) throw httpError(404, 'gone', 'TARGET_NOT_FOUND')
    if (path.endsWith('/c')) throw httpError(500, 'boom', 'INTERNAL')
    return undefined
  })
  const report = await executePlan(plan, transport, { serviceId: 's' })
  assert.deepEqual(report.results.map(item => item.status), ['succeeded', 'already-applied', 'failed', 'skipped'])
  assert.deepEqual(report.deletedTargetKeys, ['target:a', 'target:b'])
})
