import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

const source = readFileSync(new URL('../src/systemTopology.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { buildSystemTopology, modelStatus, resourceStatus, relatedTopologyNodes, safeEndpoint } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)

const model = (id, extra = {}) => ({ id, displayName: id, providerModelId: id, enabled: true, healthStatus: 'HEALTHY', loaded: true, ...extra })
const target = (id, deploymentId, priority = 1, extra = {}) => ({ id, deploymentId, priority, weight: 100, enabled: true, degraded: false, followModelChanges: true, ...extra })
function snapshot() {
  return {
    profile: { profile: 'HA', sharedStateProvider: 'REDIS', instanceId: 'gateway-1', redisConfigured: true },
    endpoints: [{ id: 'local', displayName: 'bc250', runtimeType: 'LLAMA_CPP', baseUrl: 'http://100.106.74.36:4040', enabled: true, healthStatus: 'HEALTHY' }],
    endpointDeployments: { local: [model('gemma'), model('waiting', { loaded: false })] },
    providers: [{ id: 'cloud', displayName: 'OpenAI', providerType: 'OPENAI', baseUrl: 'https://api.openai.com/v1', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true }],
    providerModels: { cloud: [model('gpt', { loaded: undefined })] },
    services: [{ id: 'chat', serviceKey: 'text-pro', displayName: 'TextPro', enabled: true, failoverPolicy: 'DEGRADED', retryPolicy: 'SAFE' }, { id: 'vision', serviceKey: 'vision-pro', displayName: 'VisionPro', enabled: true }],
    targets: { chat: [target('cloud-target', 'gpt', 2), target('local-target', 'gemma')], vision: [target('vision-target', 'gpt')] },
    projects: [{ id: 'calen', name: 'CalenLedger', status: 'ACTIVE', teamId: 'dev' }, { id: 'map', name: 'Map', status: 'ACTIVE' }],
    keys: { calen: [{ id: 'key-calen', name: 'Calen key', keyPrefix: 'sk_llmg_masked', status: 'ACTIVE' }], map: [{ id: 'key-map', name: 'Map key', keyPrefix: 'sk_llmg_masked', status: 'ACTIVE' }] },
    grants: { calen: [{ id: 'chat', serviceKey: 'text-pro', displayName: 'TextPro', enabled: true }], map: [{ id: 'vision', serviceKey: 'vision-pro', displayName: 'VisionPro', enabled: true }] },
    teams: [{ id: 'dev', name: '개발팀' }],
    accesses: [{ id: 'access', projectId: 'calen', providerId: 'cloud', status: 'APPROVED', autoFailoverEnabled: true }]
  }
}
const findNode = (graph, id) => graph.nodes.find(node => node.id === id)
const findEdge = (graph, id) => graph.edges.find(edge => edge.id === id)

test('edges preserve exact service, deployment and runtime/provider relationships', () => {
  const data = snapshot()
  const graph = buildSystemTopology(data)
  assert.equal(findEdge(graph, 'project-request:calen').target, 'gateway')
  assert.equal(findEdge(graph, 'gateway-service:chat').target, 'service:chat')
  assert.equal(findEdge(graph, 'target:local-target').source, 'service:chat')
  assert.equal(findEdge(graph, 'target:local-target').target, 'model:gemma')
  assert.equal(findEdge(graph, 'target:local-target').label, 'P1')
  assert.match(findEdge(graph, 'target:local-target').detail, /가중치 100/)
  assert.doesNotMatch(findEdge(graph, 'target:local-target').detail, /100%/)
  assert.equal(findEdge(graph, 'model-resource:gemma').target, 'runtime:local')
  assert.equal(findEdge(graph, 'model-resource:gpt').target, 'provider:cloud')
  assert.equal(findNode(graph, 'model:gpt').status.label, '등록·정상')
  assert.equal(graph.nodes.filter(node => node.kind === 'runtime').length, 1)
})

test('a healthy but unloaded local model is not shown as loaded', () => {
  const data = snapshot()
  const unloaded = modelStatus(data.endpointDeployments.local[1], data.endpoints[0], false)
  assert.equal(unloaded.label, '미로드')
  assert.equal(unloaded.tone, 'warning')
  const loading = modelStatus(model('loading', { loaded: false, metadataJson: '{"runtimeState":"LOADING"}' }), data.endpoints[0], false)
  assert.equal(loading.label, '로드 중')
  assert.equal(modelStatus(model('unknown', { healthStatus: 'UNKNOWN' }), data.endpoints[0], false).tone, 'unknown')
})

test('offline runtimes retain historical models and target references without mutation', () => {
  const data = snapshot()
  data.endpoints[0].healthStatus = 'UNHEALTHY'
  const before = JSON.stringify(data)
  const graph = buildSystemTopology(data)
  assert.equal(findNode(graph, 'model:gemma').status.tone, 'error')
  assert.equal(findEdge(graph, 'target:local-target').status.tone, 'error')
  assert.ok(findNode(graph, 'runtime:local'))
  assert.equal(findNode(graph, 'runtime:local').facts.find(fact => fact.label === '등록 모델').value, 'gemma · 로드 기록\nwaiting · 미로드')
  assert.equal(JSON.stringify(data), before)
})

test('missing model references stay visible and partial reads are not misreported as deletion', () => {
  const data = snapshot()
  data.targets.chat.push(target('missing', 'missing-model', 3))
  let graph = buildSystemTopology(data)
  assert.equal(findNode(graph, 'model:missing-model').status.tone, 'error')
  assert.equal(findEdge(graph, 'target:missing').target, 'model:missing-model')
  data.unavailablePaths = ['/api/admin/runtime-endpoints/local/deployments']
  graph = buildSystemTopology(data)
  assert.equal(findNode(graph, 'model:missing-model').status.tone, 'unknown')
})

test('project and service filters use service-access service IDs and do not invent grants', () => {
  const data = snapshot()
  const graph = buildSystemTopology(data, { projectId: 'calen' })
  assert.ok(findNode(graph, 'project:calen'))
  assert.equal(findNode(graph, 'project:map'), undefined)
  assert.ok(findNode(graph, 'service:chat'))
  assert.equal(findNode(graph, 'service:vision'), undefined)
  assert.equal(findEdge(graph, 'target:vision-target'), undefined)
  const impossible = buildSystemTopology(data, { projectId: 'calen', serviceId: 'vision' })
  assert.equal(impossible.emptyServiceScope, true)
  assert.equal(impossible.nodes.some(node => node.kind === 'service'), false)
  const serviceGraph = buildSystemTopology(data, { serviceId: 'vision' })
  assert.ok(findNode(serviceGraph, 'project:map'))
  assert.equal(findNode(serviceGraph, 'project:calen'), undefined)
})

test('selecting a project highlights only its allowed path, not all paths through the gateway', () => {
  const data = snapshot()
  const graph = buildSystemTopology(data)
  const highlight = relatedTopologyNodes(graph, 'project:calen', data)
  assert.ok(highlight.has('gateway'))
  assert.ok(highlight.has('service:chat'))
  assert.ok(highlight.has('model:gpt'))
  assert.ok(highlight.has('provider:cloud'))
  assert.equal(highlight.has('service:vision'), false)
  assert.equal(highlight.has('project:map'), false)
  assert.equal(highlight.has('database'), false)
})

test('external permission, expiry and automatic failover are evaluated only for a selected project', () => {
  const data = snapshot()
  const now = Date.parse('2026-10-05T12:00:00Z')
  data.accesses[0].expiresAt = '2026-10-01T00:00:00Z'
  assert.equal(findEdge(buildSystemTopology(data, { projectId: 'calen', now }), 'target:cloud-target').status.label, '외부 권한 없음')
  assert.equal(findEdge(buildSystemTopology(data, { now }), 'target:cloud-target').status.tone, 'healthy')
  data.accesses[0].expiresAt = null
  data.accesses[0].autoFailoverEnabled = false
  data.accesses[0].manualAllowed = true
  const edge = findEdge(buildSystemTopology(data, { projectId: 'calen', now }), 'target:cloud-target')
  assert.equal(edge.status.label, '자동 전환 OFF')
  assert.match(edge.status.reason, /수동 외부 요청/)
  data.providers[0].healthStatus = 'UNHEALTHY'
  const offline = findEdge(buildSystemTopology(data, { projectId: 'calen', now }), 'target:cloud-target')
  assert.equal(offline.status.tone, 'error')
  assert.match(offline.status.reason, /자동 외부 Failover/)
  data.accesses[0].expiresAt = 'invalid'
  assert.equal(findEdge(buildSystemTopology(data, { projectId: 'calen', now }), 'target:cloud-target').status.tone, 'unknown')
})

test('disabled targets, providers, Drain and Degraded have explicit distinct statuses', () => {
  const data = snapshot()
  data.targets.chat[0].enabled = false
  assert.equal(findEdge(buildSystemTopology(data), 'target:cloud-target').status.tone, 'muted')
  data.providers[0].enabled = false
  data.providers[0].apiKeyConfigured = false
  assert.equal(findNode(buildSystemTopology(data), 'provider:cloud').status.tone, 'muted')
  assert.equal(resourceStatus({ enabled: true, healthStatus: 'DRAINING' }).label, 'Drain')
  data.targets.chat[1].degraded = true
  assert.equal(findEdge(buildSystemTopology(data), 'target:local-target').status.label, 'Degraded')
})

test('unlinked models are folded by default but remain in resource inventory counts', () => {
  const data = snapshot()
  const graph = buildSystemTopology(data)
  assert.equal(findNode(graph, 'model:waiting'), undefined)
  assert.equal(graph.hiddenModelCount, 1)
  assert.match(findNode(graph, 'runtime:local').lines[1], /등록 2/)
  const expanded = buildSystemTopology(data, { includeUnlinkedModels: true })
  assert.equal(expanded.hiddenModelCount, 0)
  assert.equal(findNode(expanded, 'model:waiting').status.label, '미로드')
  assert.equal(findEdge(expanded, 'target:waiting'), undefined)
})

test('Redis configuration is not labeled healthy and LOCAL does not invent a Redis service', () => {
  const data = snapshot()
  let graph = buildSystemTopology(data)
  assert.equal(findNode(graph, 'gateway').status.tone, 'unknown')
  assert.equal(findNode(graph, 'database').status.tone, 'unknown')
  assert.equal(findNode(graph, 'shared').title, 'Redis 공유 상태')
  assert.equal(findNode(graph, 'shared').status.tone, 'unknown')
  data.profile.redisConfigured = false
  assert.equal(findNode(buildSystemTopology(data), 'shared').status.tone, 'warning')
  data.profile.sharedStateProvider = 'LOCAL'
  graph = buildSystemTopology(data)
  assert.equal(findNode(graph, 'shared').title, '프로세스 로컬 상태')
  assert.equal(graph.nodes.some(node => node.title === 'Redis 공유 상태'), false)
})

test('unknown project key/grant reads remain unknown rather than claiming no permission', () => {
  const data = snapshot()
  data.unavailablePaths = ['/api/admin/projects/calen/service-access']
  data.grants.calen = []
  assert.equal(findNode(buildSystemTopology(data), 'project:calen').status.tone, 'unknown')
  assert.equal(buildSystemTopology(data, { projectId: 'calen' }).scopeReadFailed, true)
  data.unavailablePaths = ['/api/admin/organizations/org/services']
  data.services = []
  assert.equal(findNode(buildSystemTopology(data), 'project:calen').status.tone, 'unknown')
  assert.equal(buildSystemTopology(data, { projectId: 'calen' }).scopeReadFailed, true)
})

test('endpoint credentials, query tokens and API key prefixes are absent from graph text', () => {
  assert.equal(safeEndpoint('https://user:secret@example.com/v1?api_key=private#hidden'), 'https://example.com/v1')
  assert.equal(safeEndpoint('user:secret@example.com'), 'Endpoint 주소 확인 필요')
  assert.equal(safeEndpoint('not a url?api_key=private'), 'Endpoint 주소 확인 필요')
  const data = snapshot()
  data.providers[0].baseUrl = 'https://user:private@example.com/v1?token=private'
  const graph = buildSystemTopology(data)
  const serialized = JSON.stringify(graph)
  assert.doesNotMatch(serialized, /private|sk_llmg_masked|user:/)
})

test('all graph nodes and edge coordinates stay finite, in bounds, and non-overlapping', () => {
  for (const count of [0, 1, 12, 40]) {
    const data = snapshot()
    data.endpointDeployments.local = Array.from({ length: count }, (_, index) => model(`model-${index}`))
    data.targets.chat = data.endpointDeployments.local.map((item, index) => target(`target-${index}`, item.id, index + 1))
    const graph = buildSystemTopology(data, { includeUnlinkedModels: true })
    const ids = new Set(graph.nodes.map(node => node.id))
    assert.equal(ids.size, graph.nodes.length)
    for (const node of graph.nodes) {
      assert.ok([node.x, node.y, node.width, node.height].every(Number.isFinite))
      assert.ok(node.x >= 0 && node.y >= 0 && node.x + node.width <= graph.width && node.y + node.height <= graph.height)
    }
    for (const edge of graph.edges) {
      assert.ok(ids.has(edge.source) && ids.has(edge.target))
      assert.doesNotMatch(edge.path, /NaN|undefined/)
      assert.ok(edge.labelX >= 0 && edge.labelX <= graph.width && edge.labelY >= 0 && edge.labelY <= graph.height)
    }
    for (let i = 0; i < graph.nodes.length; i++) for (let j = i + 1; j < graph.nodes.length; j++) {
      const a = graph.nodes[i], b = graph.nodes[j]
      assert.ok(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y)
    }
  }
})
