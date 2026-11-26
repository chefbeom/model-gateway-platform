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
const consoleNav = readFileSync(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8')

test('ConsoleShell wires the flow designer page for organization and platform admins only', () => {
  assert.match(shell, /defineAsyncComponent\(\(\) => import\('\.\/flow-designer\/FlowDesignerPage\.vue'\)\)/)
  assert.match(shell, /type PageKey = [^\n]*'flow-designer'/)
  assert.match(shell, /function knownPage\(value: string\): value is PageKey \{\s*return \[[^\]]*'flow-designer'/)
  assert.match(shell, /<FlowDesignerPage v-else-if="isOrgOrPlatformAdmin && page === 'flow-designer'"/)
  const block = name => {
    const start = consoleNav.indexOf(`const ${name}`)
    assert.ok(start >= 0, `${name} exists`)
    return consoleNav.slice(start, consoleNav.indexOf('\n]', start))
  }
  assert.match(block('adminNavItems: NavItem[]'), /id: 'flow-designer'/)
  assert.doesNotMatch(block('directorNavItems: NavItem[]'), /flow-designer/)
  assert.doesNotMatch(block('developerNavItems: NavItem[]'), /flow-designer/)
  assert.equal(consoleNav.match(/id: 'flow-designer'/g).length, 1)
  assert.doesNotMatch(shell, /id: 'flow-designer'/, 'nav items live only in consoleNavigation.ts')
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
  const validScheduling = { settings: { mode: 'IMMEDIATE', localWaitMs: 100, maxWaitMs: 200, maxQueuedRequests: 20, maxQueuedBytes: 4096, maxQueuedPerKey: 5 }, queue: {} }
  const base = buildSnapshot(raw({ scheduling: validScheduling }))
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
    scheduling: validScheduling,
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

test('FlowCanvas avoids permanent will-change rasterization lock and isolates layer painting', () => {
  const canvas = readFileSync(new URL('../src/flow-designer/FlowCanvas.vue', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
  const worldRuleMatch = canvas.match(/\.fd-world\s*\{[^}]*\}/)
  assert.ok(worldRuleMatch, '.fd-world CSS rule must exist')
  assert.doesNotMatch(worldRuleMatch[0], /will-change\s*:/, '.fd-world must not permanently lock compositor rasterization')
  assert.match(canvas, /\.fd-canvas\.is-panning\s+\.fd-world,\s*\.fd-canvas\.is-zooming\s+\.fd-world\s*\{[^}]*will-change:\s*transform;/)
  assert.match(canvas, /\.fd-canvas\s*\{[^}]*contain:\s*layout paint style;/)
  assert.match(canvas, /const isZooming = ref\(false\)/)
})

test('FlowDesignerPage and style.css provide QHD full-width and zoom-compensated adaptive workspace', () => {
  const style = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
  const page = readFileSync(new URL('../src/flow-designer/FlowDesignerPage.vue', import.meta.url), 'utf8').replace(/\r\n/g, '\n')
  const toolbar = readFileSync(new URL('../src/flow-designer/FlowToolbar.vue', import.meta.url), 'utf8').replace(/\r\n/g, '\n')

  assert.match(style, /\.console-content:has\(>\s*\.fd-page\)\s*\{[^}]*max-width:\s*none;/)
  assert.match(style, /\.console-stage:has\(\.fd-page\)\s*\{[^}]*calc\(100dvh\s*\/\s*var\(--console-zoom,\s*1\)\)/)
  assert.match(page, /\.fd-stage\s*\{[^}]*grid-template-columns:\s*clamp\([^)]+\)\s+minmax\(480px,\s*1fr\)\s+clamp\([^)]+\)/)
  assert.match(page, /calc\(100dvh\s*\/\s*var\(--console-zoom,\s*1\)\s*-\s*240px\)/)
  assert.match(toolbar, /fire\('zoom-out'\)/)
  assert.match(toolbar, /fire\('zoom-reset'\)/)
  assert.match(toolbar, /fire\('zoom-in'\)/)
})

/* ---------------- 8. Workflow Designer v2 adapter (WP-C) ---------------- */

const orgDlp = (extra = {}) => ({
  effective: { mode: 'ENFORCE', externalAction: 'REDACT', customPatternsJson: '[{"secret":"x"}]', detectSecrets: true },
  policies: [
    { scopeType: 'PROJECT', scopeId: 'p1', mode: 'ENFORCE', externalAction: 'LOCAL_ONLY', customPatternsJson: '[{"regex":"leak"}]', detectSecrets: true },
    { scopeType: 'ORGANIZATION', scopeId: 'org1', mode: 'ENFORCE', externalAction: 'BLOCK' },
    { scopeType: 'PROJECT', scopeId: 'other', mode: 'ENFORCE', externalAction: 'BLOCK' }
  ],
  ...extra
})

test('buildSnapshot maps the Target systemPromptTemplate and detects backend support (section 8.6)', () => {
  const withKey = buildSnapshot(raw({ targets: [
    { id: 't1', deploymentId: 'dep-local', priority: 1, weight: 1, systemPromptTemplate: '  Be brief  ' },
    { id: 't2', deploymentId: 'dep-ext', priority: 2, weight: 1, systemPromptTemplate: null }
  ] }))
  assert.equal(withKey.config.targets.find(t => t.id === 't1').systemPromptTemplate, 'Be brief')
  assert.equal(withKey.config.targets.find(t => t.id === 't2').systemPromptTemplate, null)
  assert.equal(withKey.features.targetPrompt, true)
  const legacy = buildSnapshot(raw())
  assert.equal(legacy.config.targets[0].systemPromptTemplate, null)
  assert.equal(legacy.features.targetPrompt, false, 'rows without the key => older backend')
  assert.equal(buildSnapshot(raw({ targets: [] })).features.targetPrompt, true)
  assert.equal(buildSnapshot(raw({ serviceId: null, targets: [], scheduling: null, dataProtection: null, projectAccess: {} })).features.targetPrompt, true)
})

test('buildSnapshot reads project policies from the organization data-protection response only', () => {
  const snapshot = buildSnapshot(raw({
    orgDataProtection: orgDlp(),
    projects: [
      { id: 'p1', name: 'Alpha', status: 'ACTIVE', dataBoundaryPolicy: 'INTERNAL_ONLY', maxDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', externalAiBlocked: true },
      { id: 'p2', name: 'Beta', status: 'ACTIVE', maxDataBoundaryPolicy: 'bogus' }
    ]
  }))
  const [p1, p2] = snapshot.catalog.projects
  assert.equal(p1.externalAiBlocked, true)
  assert.equal(p1.maxDataBoundaryPolicy, 'APPROVED_EXTERNAL_NO_RETENTION')
  assert.deepEqual(p1.dlp, { mode: 'ENFORCE', externalAction: 'LOCAL_ONLY' })
  assert.equal(p2.externalAiBlocked, false)
  assert.equal(p2.maxDataBoundaryPolicy, null)
  assert.equal(p2.dlp, null, 'only the project own PROJECT-scope row counts')
  assert.deepEqual(snapshot.orgDataProtection, { mode: 'ENFORCE', externalAction: 'REDACT' })
  assert.equal(snapshot.projectPoliciesComplete, true)
  const text = JSON.stringify(snapshot)
  assert.doesNotMatch(text, /customPatternsJson|leak|"secret"/, 'organization policy details are never copied')
  assert.equal(snapshot.dataProtection.detectSecrets, true, 'service-level detail comes only from the service endpoint')
})

test('buildSnapshot marks policies unverified when the organization read failed and hides projects for break-glass sessions', () => {
  const failed = buildSnapshot(raw({ orgDataProtection: null }))
  assert.equal(failed.projectPoliciesComplete, false)
  assert.equal(failed.orgDataProtection, null)
  assert.equal(failed.catalog.projects[0].dlp, null)
  const hidden = buildSnapshot(raw({ projectsHidden: true, projects: [], projectAccess: {} }))
  assert.equal(hidden.projectsHidden, true)
  assert.equal(hidden.accessComplete, false)
  assert.equal(buildSnapshot(raw()).projectsHidden, false)
  assert.equal(buildSnapshot(raw()).accessComplete, true)
})

test('temperatureValue is normalized to null unless the policy is FIXED', () => {
  const data = raw()
  data.services[0] = { ...data.services[0], temperaturePolicy: 'REQUEST', temperatureValue: 0.7 }
  assert.equal(buildSnapshot(data).config.service.temperatureValue, null)
  data.services[0] = { ...data.services[0], temperaturePolicy: 'FIXED', temperatureValue: 0.7 }
  assert.equal(buildSnapshot(data).config.service.temperatureValue, 0.7)
})

test('operationRequest passes the Target system prompt on create and clear on patch', () => {
  const ctx = { serviceId: 'svc1', createdTargetIds: {} }
  const common = { risk: 'safe', summary: '', changes: [] }
  const existing = { kind: 'existing', id: 'svc1' }
  assert.deepEqual(operationRequest({ ...common, opId: 'create-target:target:new-d', kind: 'createTarget', service: existing, key: 'target:new-d',
    body: { deploymentId: 'd', priority: 1, weight: 1, degraded: false, followModelChanges: true, systemPromptTemplate: 'Be brief' } }, ctx),
    { method: 'POST', path: '/api/admin/services/svc1/targets', body: { deploymentId: 'd', priority: 1, weight: 1, degraded: false, followModelChanges: true, systemPromptTemplate: 'Be brief' } })
  assert.deepEqual(operationRequest({ ...common, opId: 'patch-target:t1', kind: 'patchTarget', service: existing, target: { kind: 'existing', id: 't1' }, body: { clearSystemPromptTemplate: true } }, ctx),
    { method: 'PATCH', path: '/api/admin/services/svc1/targets/t1', body: { clearSystemPromptTemplate: true } })
})

test('a model-specific prompt round-trips through compile, plan and request', () => {
  const base = buildSnapshot(raw({ targets: [{ id: 'tB', deploymentId: 'dep-local', priority: 2, weight: 10, systemPromptTemplate: 'Local only' }] }))
  const graph = decompile(base, null)
  assert.ok(graph.nodes.some(node => node.id === 'prompt:dep-local'))
  assert.equal(compile(graph, base).targets[0].systemPromptTemplate, 'Local only')
  const edited = applyCommand(graph, { type: 'updateProps', nodeId: 'prompt:dep-local', patch: { systemPromptTemplate: 'Changed' } }, base).graph
  const plan = planChanges({ base, desired: compile(edited, base), remote: base, issues: validateGraph(edited, base) })
  assert.deepEqual(plan.operations.map(op => op.opId), ['patch-target:tB'])
  assert.deepEqual(plan.operations[0].body, { systemPromptTemplate: 'Changed' })
})

test('ConsoleShell nav item uses the workflow terminology and keeps the old search words', () => {
  const line = consoleNav.split('\n').find(row => row.includes("id: 'flow-designer'"))
  assert.ok(line)
  assert.match(line, /label: '워크플로우 디자이너'/)
  assert.match(line, /description: '[^']*워크플로우[^']*'/)
  assert.match(line, /keywords: '[^']*\bworkflow\b[^']*'/)
  assert.match(line, /keywords: '[^']*플로우[^']*'/)
  assert.match(line, /keywords: '[^']*flow designer[^']*'/, 'old search words stay')
})

test('page terminology: workflow designer, no service-flow wording, plan modal uses workflow wording', () => {
  const page = readFileSync(new URL('../src/flow-designer/FlowDesignerPage.vue', import.meta.url), 'utf8')
  assert.match(page, /워크플로우 디자이너/)
  assert.doesNotMatch(page, /서비스 플로우 디자이너/)
  assert.match(page, /새 워크플로우 초안/)
  const modal = readFileSync(new URL('../src/flow-designer/FlowPlanModal.vue', import.meta.url), 'utf8')
  assert.match(modal, /워크플로우에 그대로/)
  assert.match(modal, /외부 노출 방지 정책 완화/)
})

test('page and editor wire mode, quick start, normalize-order and the v2 compatibility paths', () => {
  const page = readFileSync(new URL('../src/flow-designer/FlowDesignerPage.vue', import.meta.url), 'utf8')
  assert.match(page, /<FlowQuickStart[\s\S]*?v-if="showQuickStart"/)
  assert.match(page, /:can-normalize="canNormalize"/)
  assert.equal((page.match(/:mode="mode"/g) ?? []).length >= 3, true, 'palette, canvas and inspector receive the mode')
  assert.match(page, /:mode="mode" :can-normalize="canNormalize"/)
  const editor = readFileSync(new URL('../src/flow-designer/useFlowEditor.ts', import.meta.url), 'utf8')
  assert.match(editor, /let lastMode: DesignerMode = 'basic'/)
  assert.match(editor, /case 'normalize-order': handleCommand\(\{ type: 'normalizeOrder' \}\)/)
  assert.match(editor, /layoutGeneration\(layoutResult\.layout\) === 1/)
  assert.match(editor, /schemaVersion\?: number \}\)\.schemaVersion !== 2/)
  assert.match(editor, /case 'zoom-in': canvas\.value\?\.zoomCenter/)
  const api = readFileSync(new URL('../src/flow-designer/flowApi.ts', import.meta.url), 'utf8')
  assert.match(api, /organizations\/\$\{org\}\/data-protection/)
  assert.match(api, /FLOW_LAYOUT_INVALID/)
  assert.match(api, /LAYOUT_NODE_ID_PATTERN_V1/)
})

test('Root and useFlowEditor isolate drafts across logout, session expiry, and platform-token switch', () => {
  const root = readFileSync(new URL('../src/Root.vue', import.meta.url), 'utf8')
  const store = readFileSync(new URL('../src/flow-designer/draftStore.ts', import.meta.url), 'utf8')
  const editor = readFileSync(new URL('../src/flow-designer/useFlowEditor.ts', import.meta.url), 'utf8')
  assert.match(store, /aiconnect:clear-drafts/)
  assert.match(root, /aiconnect:clear-drafts/)
  assert.match(root, /function acceptPlatformToken\([\s\S]*?clearStashedDrafts\(\)/)
  assert.match(root, /function clearSession\(\)[\s\S]*?clearStashedDrafts\(\)/)
  assert.match(editor, /let loadedUserId = currentUserId\(\)/)
  assert.match(editor, /activeUser === 'anonymous' \|\| activeUser !== loadedUserId/)
})
