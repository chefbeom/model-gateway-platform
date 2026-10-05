export type TopologyTone = 'healthy' | 'warning' | 'error' | 'muted' | 'unknown'
export type TopologyKind = 'project' | 'gateway' | 'service' | 'model' | 'runtime' | 'provider' | 'database' | 'shared' | 'observability'
export type TopologyStatus = { label: string; tone: TopologyTone; reason: string }
export type SystemModel = { id: string; displayName: string; providerModelId: string; enabled: boolean; healthStatus: string; loaded?: boolean; contextLength?: number | null; quantization?: string | null; maxConcurrency?: number; capabilitiesJson?: string; metadataJson?: string | null }
export type SystemRuntime = { id: string; nodeId?: string; displayName: string; runtimeType: string; baseUrl: string; enabled: boolean; healthStatus: string; lastCheckedAt?: string | null }
export type SystemProvider = { id: string; displayName: string; providerType: string; baseUrl: string; enabled: boolean; healthStatus: string; apiKeyConfigured: boolean; lastCheckedAt?: string | null }
export type SystemService = { id: string; serviceKey: string; displayName: string; enabled: boolean; failoverPolicy?: string; retryPolicy?: string }
export type SystemTarget = { id: string; deploymentId: string; priority: number; weight: number; degraded: boolean; enabled: boolean; followModelChanges?: boolean }
export type SystemProject = { id: string; name: string; status: string; teamId?: string | null }
export type SystemGrant = { id: string; serviceKey: string; displayName: string; enabled: boolean }
export type SystemKey = { id: string; name: string; keyPrefix: string; status: string }
export type SystemAccess = { id: string; projectId: string; providerId: string; status: string; autoFailoverEnabled: boolean; manualAllowed?: boolean; expiresAt?: string | null }
export type SystemSnapshot = {
  profile: { profile: string; sharedStateProvider: string; instanceId?: string; redisConfigured?: boolean } | null
  endpoints: SystemRuntime[]; endpointDeployments: Record<string, SystemModel[]>
  providers: SystemProvider[]; providerModels: Record<string, SystemModel[]>
  services: SystemService[]; targets: Record<string, SystemTarget[]>
  projects: SystemProject[]; keys: Record<string, SystemKey[]>; grants: Record<string, SystemGrant[]>
  teams: Array<{ id: string; name: string }>; accesses: SystemAccess[]; unavailablePaths?: string[]
}
export type TopologyFact = { label: string; value: string }
export type TopologyNode = {
  id: string; entityId?: string; kind: TopologyKind; title: string; subtitle: string; status: TopologyStatus
  lines: string[]; facts: TopologyFact[]; notes: string[]; column: number
  x: number; y: number; width: number; height: number
}
export type TopologyEdge = {
  id: string; source: string; target: string; kind: 'request' | 'target' | 'execution' | 'dependency'
  label: string; detail: string; status: TopologyStatus; path: string; labelX: number; labelY: number
}
export type TopologyOptions = { projectId?: string; serviceId?: string; includeUnlinkedModels?: boolean; now?: number }
const ok = (label: string, reason: string): TopologyStatus => ({ label, reason, tone: 'healthy' })
const uncertain = (reason: string, label = '미확인'): TopologyStatus => ({ label, reason, tone: 'unknown' })
const warning = (label: string, reason: string): TopologyStatus => ({ label, reason, tone: 'warning' })
const disabled = (reason: string): TopologyStatus => ({ label: '비활성', reason, tone: 'muted' })
const failed = (label: string, reason: string): TopologyStatus => ({ label, reason, tone: 'error' })
const count = (value: number) => value.toLocaleString('ko-KR')
const fact = (label: string, value: unknown): TopologyFact => ({ label, value: value == null || value === '' ? '정보 없음' : String(value) })
const checkedAt = (value?: string | null) => value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('ko-KR') : '확인 기록 없음'
export function safeEndpoint(value: string) {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && url.hostname ? url.origin + url.pathname.replace(/\/$/, '') : 'Endpoint 주소 확인 필요' }
  catch { return 'Endpoint 주소 확인 필요' }
}
export function resourceStatus(resource: { enabled: boolean; healthStatus: string }): TopologyStatus {
  if (!resource.enabled) return disabled('관리 설정에서 비활성화되어 있습니다.')
  const health = resource.healthStatus.toUpperCase()
  if (['HEALTHY', 'READY', 'ACTIVE', 'CONNECTED'].includes(health)) return ok('정상 기록', '마지막 저장된 연결 검사 상태가 정상입니다. 현재 요청의 성공을 보장하지 않습니다.')
  if (['UNHEALTHY', 'FAILED', 'ERROR'].includes(health)) return failed('연결 실패', '마지막 연결 검사에서 장애 상태가 기록되었습니다.')
  if (health === 'DRAINING') return warning('Drain', '신규 요청 후보에서 제외하는 Drain 상태입니다.')
  if (['RECOVERING', 'DEGRADED', 'SUSPECT'].includes(health)) return warning('복구·주의', '복구 또는 불안정 상태가 기록되었습니다.')
  return uncertain('정상 연결 검사 결과를 아직 확인할 수 없습니다.')
}
export function modelStatus(model: SystemModel, source: SystemRuntime | SystemProvider | undefined, external: boolean): TopologyStatus {
  if (!model.enabled) return disabled('모델 배포가 비활성화되어 있습니다.')
  if (!source) return uncertain('모델에 연결된 Runtime 또는 Provider 정보를 조회하지 못했습니다.')
  const parent = resourceStatus(source)
  if (parent.tone !== 'healthy') return parent
  if (external && !(source as SystemProvider).apiKeyConfigured) return failed('키 미설정', 'Provider에 인증 키가 설정되어 있지 않습니다.')
  if (['UNHEALTHY', 'ERROR', 'FAILED'].includes(model.healthStatus.toUpperCase())) return failed('모델 장애', '모델 배포의 마지막 상태가 장애입니다.')
  if (external) return ['HEALTHY', 'READY'].includes(model.healthStatus.toUpperCase()) ? ok('등록·정상', '외부 모델의 등록 상태와 Provider 연결 상태를 표시합니다. 로컬 메모리 로드 상태는 적용하지 않습니다.') : uncertain('외부 모델의 마지막 상태를 확인해야 합니다.')
  if (!model.loaded) {
    let state = ''
    try { const metadata = JSON.parse(model.metadataJson || '{}'); const value = metadata.runtimeState ?? metadata.runtime_state; state = String(typeof value === 'object' && value ? value.status ?? value.value ?? '' : value ?? '').toUpperCase() } catch { /* Unknown runtime metadata is not evidence of a loaded model. */ }
    if (['LOADING', 'UNLOADING'].includes(state)) return warning(state === 'LOADING' ? '로드 중' : '언로드 중', '런타임에서 모델 상태 전환이 진행 중입니다.')
    if (['UNAVAILABLE', 'ERROR', 'FAILED'].includes(state)) return warning('확인 필요', '과거 모델 등록은 유지되지만 현재 Runtime에서 사용 가능 여부를 확인하지 못했습니다.')
    return warning('미로드', '등록된 모델이지만 현재 메모리에 로드되어 있지 않습니다.')
  }
  return ['HEALTHY', 'READY', 'LOADED'].includes(model.healthStatus.toUpperCase()) ? ok('로드됨', '마지막 동기화에서 loaded=true와 정상 Runtime 상태가 확인되었습니다.') : uncertain('로드 기록은 있으나 모델 상태가 아직 정상으로 확인되지 않았습니다.')
}

export function buildSystemTopology(data: SystemSnapshot, options: TopologyOptions = {}) {
  const now = options.now ?? Date.now()
  const failedRead = (fragment: string) => (data.unavailablePaths ?? []).some(path => path.includes(fragment))
  const serviceListFailed = (data.unavailablePaths ?? []).some(path => path.endsWith('/services'))
  const allModels = new Map<string, { model: SystemModel; parentId: string; external: boolean }>()
  for (const [id, models] of Object.entries(data.endpointDeployments)) for (const model of models) allModels.set(model.id, { model, parentId: id, external: false })
  for (const [id, models] of Object.entries(data.providerModels)) for (const model of models) allModels.set(model.id, { model, parentId: id, external: true })
  const projects = data.projects.filter(project => !options.projectId || project.id === options.projectId)
  const grantedIds = new Set(projects.flatMap(project => (data.grants[project.id] ?? []).map(grant => grant.id)))
  const services = data.services.filter(service => (!options.serviceId || service.id === options.serviceId) && (!options.projectId || grantedIds.has(service.id)))
  const serviceIds = new Set(services.map(service => service.id))
  const shownProjects = options.serviceId ? projects.filter(project => (data.grants[project.id] ?? []).some(grant => grant.id === options.serviceId)) : projects
  const selectedTargets = services.flatMap(service => [...(data.targets[service.id] ?? [])].sort((a, b) => a.priority - b.priority || a.id.localeCompare(b.id)).map(target => ({ service, target })))
  const linkedIds = new Set(selectedTargets.map(item => item.target.deploymentId))
  const modelIds = options.includeUnlinkedModels ? new Set([...linkedIds, ...allModels.keys()]) : linkedIds
  const parentIds = new Set([...modelIds].flatMap(id => { const model = allModels.get(id); return model ? [model.parentId] : [] }))
  const focused = !!(options.projectId || options.serviceId)
  const runtimes = data.endpoints.filter(item => !focused || options.includeUnlinkedModels || parentIds.has(item.id))
  const providers = data.providers.filter(item => !focused || options.includeUnlinkedModels || parentIds.has(item.id))
  const nodes: TopologyNode[] = []
  const edges: TopologyEdge[] = []
  function node(value: Omit<TopologyNode, 'x' | 'y' | 'width' | 'height'>) { nodes.push({ ...value, x: 0, y: 0, width: 218, height: 172 }) }
  function edge(id: string, source: string, target: string, kind: TopologyEdge['kind'], label: string, detail: string, status: TopologyStatus) { edges.push({ id, source, target, kind, label, detail, status, path: '', labelX: 0, labelY: 0 }) }
  for (const project of shownProjects) {
    const keyItems = data.keys[project.id] ?? []
    const active = keyItems.filter(key => key.status === 'ACTIVE')
    const projectGrants = (data.grants[project.id] ?? []).filter(grant => serviceIds.has(grant.id))
    const team = data.teams.find(team => team.id === project.teamId)?.name ?? '조직 공용'
    const status = project.status !== 'ACTIVE' ? disabled('프로젝트가 활성 상태가 아닙니다.') : failedRead(`/projects/${project.id}/api-keys`) ? uncertain('프로젝트 API 키 목록 조회에 실패했습니다.') : failedRead(`/projects/${project.id}/service-access`) || serviceListFailed ? uncertain('프로젝트 서비스 권한 또는 논리 서비스 목록 조회에 실패했습니다.') : !active.length ? warning('활성 키 없음', '활성 프로젝트 API 키를 발급해야 Gateway를 호출할 수 있습니다.') : !projectGrants.length ? warning('서비스 권한 없음', '표시 중인 논리 서비스에 부여된 권한이 없습니다.') : ok('호출 설정', '활성 API 키와 표시 중인 서비스 권한이 등록되어 있습니다. 키 만료·한도·요청 정책은 요청 시 별도 검사됩니다.')
    node({ id: `project:${project.id}`, entityId: project.id, kind: 'project', title: project.name, subtitle: team, status, column: 0, lines: [`활성 키 ${active.length} / 전체 ${keyItems.length}`, `서비스 권한 ${projectGrants.length}개`], facts: [fact('프로젝트 상태', project.status), fact('소유 팀', team), fact('API 키', keyItems.map(key => `${key.name} (${key.status})`).join('\n') || '등록된 키 없음'), fact('논리 서비스 권한', projectGrants.map(grant => grant.displayName || grant.serviceKey).join('\n') || '연결 없음'), fact('외부 AI 권한', data.accesses.filter(item => item.projectId === project.id).map(item => `${data.providers.find(provider => provider.id === item.providerId)?.displayName ?? item.providerId} · ${item.status} · 자동 ${item.autoFailoverEnabled ? 'ON' : 'OFF'}`).join('\n') || '승인 연결 없음')], notes: [status.reason, 'API 키 원문은 이 구성도에 포함하지 않습니다.'] })
    edge(`project-request:${project.id}`, `project:${project.id}`, 'gateway', 'request', 'API 키', `${project.name} → /v1 · 활성 키 ${active.length}개 · 표시 서비스 권한 ${projectGrants.length}개`, status)
  }
  node({ id: 'gateway', kind: 'gateway', title: 'AICONNECT Gateway', subtitle: 'Spring Boot · OpenAI 호환 /v1', status: uncertain('관리 API에서 저장된 구성을 조회한 화면입니다. Gateway·DB·Redis의 실시간 헬스체크 결과를 대신하지 않습니다.', '구성 조회'), column: 1, lines: ['인증 · 권한 · 요금 한도', '데이터 보호 · 라우팅 · 요청 추적'], facts: [fact('배포 프로필', data.profile?.profile), fact('현재 응답 인스턴스', data.profile?.instanceId), fact('Chat API', 'POST /v1/chat/completions'), fact('모델 API', 'GET /v1/models'), fact('요청 정책', '프로젝트 권한, Quota, 데이터 보호, Retry/Failover')], notes: ['화살표는 저장된 연결과 요청 후보를 나타내며, 실시간 요청이 흐르고 있다는 뜻은 아닙니다.', '같은 Gateway를 거쳐도 프로젝트별 서비스·외부 Provider 권한이 각각 검사됩니다.'] })
  for (const service of services) {
    const items = data.targets[service.id] ?? []
    const active = items.filter(target => target.enabled)
    const status = !service.enabled ? disabled('논리 서비스가 비활성화되어 있습니다.') : failedRead(`/services/${service.id}/targets`) ? uncertain('서비스 Target 목록 조회에 실패했습니다.') : !active.length ? warning('활성 Target 없음', '실행할 활성 Target이 연결되어 있지 않습니다.') : ok('활성 설정', '논리 서비스와 Target이 활성화되어 있습니다. 실제 모델·서버 상태는 연결된 노드에서 별도로 확인하세요.')
    const grantedProjects = shownProjects.filter(project => (data.grants[project.id] ?? []).some(grant => grant.id === service.id))
    node({ id: `service:${service.id}`, entityId: service.id, kind: 'service', title: service.displayName || service.serviceKey, subtitle: `model: ${service.serviceKey}`, status, column: 2, lines: [`${service.failoverPolicy || 'STRICT'} · ${service.retryPolicy || 'SAFE'}`, `활성 Target ${active.length} / 전체 ${items.length}`], facts: [fact('논리 모델명', service.serviceKey), fact('Failover / Retry', `${service.failoverPolicy || 'STRICT'} / ${service.retryPolicy || 'SAFE'}`), fact('권한 프로젝트', grantedProjects.map(project => project.name).join('\n') || '연결 없음'), fact('Target', [...items].sort((a, b) => a.priority - b.priority).map(target => `P${target.priority} · ${allModels.get(target.deploymentId)?.model.displayName ?? target.deploymentId} · 가중치 ${target.weight} · ${target.enabled ? '활성' : '비활성'}${target.degraded ? ' · Degraded' : ''}`).join('\n') || '등록 없음')], notes: [status.reason, 'P 숫자가 작을수록 우선순위가 높습니다. 가중치는 같은 우선순위의 상대 선택 가중치이며 확률(%)이 아닙니다.'] })
    const grantStatus = !grantedProjects.length ? warning('권한 연결 없음', '현재 표시 범위에 이 서비스를 사용할 프로젝트가 없습니다.') : status
    edge(`gateway-service:${service.id}`, 'gateway', `service:${service.id}`, 'request', 'model', `model=${service.serviceKey} · 권한 프로젝트 ${grantedProjects.length}개`, grantStatus)
  }
  for (const id of modelIds) {
    const entry = allModels.get(id)
    const usedBy = selectedTargets.filter(item => item.target.deploymentId === id)
    if (!entry) {
      const partial = failedRead('/deployments') || failedRead('/models') || failedRead('/runtime-endpoints') || failedRead('/external-providers')
      const status = partial ? uncertain('모델 또는 Runtime/Provider 목록 조회에 실패해 Target의 모델 정보를 확인할 수 없습니다.') : failed('참조 확인', 'Target이 가리키는 모델이 현재 조회된 등록 모델 목록에 없습니다. 인프라 동기화와 Target 연결을 확인하세요.')
      node({ id: `model:${id}`, entityId: id, kind: 'model', title: 'Target 모델 정보 없음', subtitle: id, status, column: 3, lines: ['Target 참조 유지', '자동 삭제·재연결하지 않음'], facts: [fact('Deployment ID', id), fact('연결 서비스', usedBy.map(item => item.service.displayName).join('\n'))], notes: [status.reason] })
      continue
    }
    const { model, parentId, external } = entry
    const parent = external ? data.providers.find(item => item.id === parentId) : data.endpoints.find(item => item.id === parentId)
    const status = modelStatus(model, parent, external)
    let capabilities: string[] = []
    try { const value = JSON.parse(model.capabilitiesJson || '[]'); if (Array.isArray(value)) capabilities = value.filter(item => typeof item === 'string') } catch { /* Invalid metadata stays unknown. */ }
    node({ id: `model:${id}`, entityId: id, kind: 'model', title: model.displayName || model.providerModelId, subtitle: model.providerModelId, status, column: 3, lines: [external ? '외부 Provider 모델' : `${model.quantization || '양자화 미확인'} · ${model.loaded ? '로드 기록 있음' : '미로드'}`, model.contextLength ? `Context ${count(model.contextLength)}` : 'Context 미제공'], facts: [fact('모델 ID', model.providerModelId), fact('배포 ID', model.id), fact('Runtime / Provider', parent?.displayName), fact('컨텍스트 길이', model.contextLength == null ? 'Runtime/Provider에서 미제공' : count(model.contextLength)), fact('양자화', external ? '외부 API · 해당 없음' : model.quantization), fact('최대 동시성 설정', model.maxConcurrency), fact('등록 Capability', capabilities.join(', ') || '확인 정보 없음'), fact('연결 서비스', usedBy.map(item => `${item.service.displayName} · P${item.target.priority}`).join('\n') || 'Target 연결 없음')], notes: [status.reason, '런타임의 마지막 동기화·검사 결과입니다. 현재 모델 상태와 다르면 인프라에서 동기화하세요.'] })
    if (parent) edge(`model-resource:${id}`, `model:${id}`, `${external ? 'provider' : 'runtime'}:${parentId}`, 'execution', external ? '외부' : '실행', `${model.providerModelId} → ${parent.displayName || safeEndpoint(parent.baseUrl)}`, status)
  }
  for (const runtime of runtimes) {
    const models = data.endpointDeployments[runtime.id] ?? []
    const status = resourceStatus(runtime)
    node({ id: `runtime:${runtime.id}`, entityId: runtime.id, kind: 'runtime', title: runtime.displayName || safeEndpoint(runtime.baseUrl), subtitle: runtime.runtimeType.replaceAll('_', ' '), status, column: 4, lines: [safeEndpoint(runtime.baseUrl), `로드 기록 ${models.filter(model => model.loaded).length} · 등록 ${models.length}`], facts: [fact('Endpoint', safeEndpoint(runtime.baseUrl)), fact('Runtime 종류', runtime.runtimeType), fact('마지막 연결 확인', checkedAt(runtime.lastCheckedAt)), fact('저장된 상태', runtime.healthStatus), fact('등록 모델', models.map(model => `${model.displayName} · ${model.loaded ? '로드 기록' : '미로드'}`).join('\n') || (failedRead(`/runtime-endpoints/${runtime.id}/deployments`) ? '모델 목록 조회 실패' : '등록 없음'))], notes: [status.reason, '오프라인이어도 등록 모델과 Target 참조는 보존됩니다. GPU·Inference Node는 실행 대상으로 중복 계산하지 않습니다.'] })
  }
  for (const provider of providers) {
    const models = data.providerModels[provider.id] ?? []
    const status = !provider.enabled ? disabled('Provider가 비활성화되어 있습니다.') : !provider.apiKeyConfigured ? failed('키 미설정', 'Provider API 키를 등록해야 합니다.') : resourceStatus(provider)
    node({ id: `provider:${provider.id}`, entityId: provider.id, kind: 'provider', title: provider.displayName, subtitle: provider.providerType, status, column: 4, lines: [safeEndpoint(provider.baseUrl), `등록 모델 ${models.length} · 키 ${provider.apiKeyConfigured ? '설정됨' : '미설정'}`], facts: [fact('Endpoint', safeEndpoint(provider.baseUrl)), fact('Provider 종류', provider.providerType), fact('마지막 연결 확인', checkedAt(provider.lastCheckedAt)), fact('등록 모델', models.map(model => model.displayName).join('\n') || '등록 없음'), fact('프로젝트 권한', data.accesses.filter(item => item.providerId === provider.id).map(item => `${data.projects.find(project => project.id === item.projectId)?.name ?? item.projectId} · ${item.status}`).join('\n') || '승인 연결 없음')], notes: [status.reason, 'Provider 연결 정상과 프로젝트의 외부 AI 사용 승인은 별개입니다. 자동 Failover 설정과 데이터 보호 정책도 요청 시 적용됩니다.'] })
  }
  for (const { service, target } of selectedTargets) {
    const destination = nodes.find(node => node.id === `model:${target.deploymentId}`)!
    let status = !target.enabled ? disabled('이 Target은 비활성화되어 실행 후보에서 제외됩니다.') : !service.enabled ? disabled('상위 논리 서비스가 비활성화되어 있습니다.') : destination.status
    const entry = allModels.get(target.deploymentId)
    if (target.enabled && service.enabled && entry?.external && options.projectId) {
      const access = data.accesses.find(item => item.projectId === options.projectId && item.providerId === entry.parentId)
      const expires = access?.expiresAt ? Date.parse(access.expiresAt) : undefined
      if (failedRead('/external-access')) status = uncertain('외부 Provider 권한 조회에 실패했습니다.')
      else if (!access || access.status !== 'APPROVED' || (expires !== undefined && expires <= now)) status = failed('외부 권한 없음', '선택한 프로젝트에 활성·승인된 외부 Provider 권한이 없습니다.')
      else if (expires !== undefined && !Number.isFinite(expires)) status = uncertain('외부 Provider 사용 권한의 만료 시각을 확인할 수 없습니다.')
      else if (!access.autoFailoverEnabled) {
        const reason = `선택한 프로젝트의 자동 외부 Failover가 꺼져 있습니다.${access.manualAllowed ? ' 수동 외부 요청은 별도 허용되어 있습니다.' : ''}`
        status = status.tone === 'healthy' ? warning('자동 전환 OFF', reason) : { ...status, reason: `${status.reason} ${reason}` }
      }
    }
    if (target.degraded && status.tone === 'healthy') status = warning('Degraded', 'Degraded 대체 Target입니다. 서비스 Failover 정책과 허용 설정에 따라 선택됩니다.')
    edge(`target:${target.id}`, `service:${service.id}`, destination.id, 'target', `P${target.priority}`, `P${target.priority} · 가중치 ${target.weight} · ${target.enabled ? '활성' : '비활성'}${target.degraded ? ' · Degraded' : ''}${target.followModelChanges === false ? ' · 모델 고정' : target.followModelChanges === true ? ' · 모델 추적' : ' · 추적 설정 미제공'} · ${destination.title}`, status)
  }
  const columnNames = [['CALLERS', '프로젝트 · API 키'], ['REQUEST GATE', '인증 · 요청 정책'], ['LOGICAL SERVICES', '논리 LLM 서비스'], ['MODEL TARGETS', '실제 모델 배포'], ['EXECUTION', 'Runtime · 외부 AI']]
  const xPositions = [28, 294, 560, 826, 1092]
  const rowGap = 32
  const mainHeight = Math.max(1, ...[0, 1, 2, 3, 4].map(column => nodes.filter(node => node.column === column).length)) * (172 + rowGap) - rowGap
  const columns = columnNames.map(([kicker, title], column) => {
    const items = nodes.filter(node => node.column === column)
    items.forEach((node, index) => { node.x = xPositions[column]; node.y = 86 + (mainHeight - items.length * (172 + rowGap) + rowGap) / 2 + index * (172 + rowGap) })
    return { kicker, title, x: xPositions[column] - 12, width: 242, count: items.length }
  })
  const dependencyY = mainHeight + 188
  node({ id: 'database', kind: 'database', title: 'MariaDB', subtitle: '구성 · 요청 · 사용량 기록', status: uncertain('저장소는 프로젝트의 표준 배포 구성 요소입니다. 이 화면에서는 DB 연결 상태를 검사하지 않습니다.', '배포 구성'), column: 5, lines: ['JPA · Flyway', '정책·Target·이력 영속 저장'], facts: [fact('역할', '구성, 프로젝트, API 키 해시, 요청 이력, 토큰·비용 기록')], notes: ['표준 Compose 배포는 MariaDB를 사용합니다. 이 카드의 상태는 실제 DB health가 아닙니다.'] })
  const redis = data.profile?.sharedStateProvider === 'REDIS'
  node({ id: 'shared', kind: 'shared', title: redis ? 'Redis 공유 상태' : data.profile?.sharedStateProvider === 'LOCAL' ? '프로세스 로컬 상태' : '공유 상태 미확인', subtitle: redis ? '분산 카운터 · 잠금' : '동시 요청 카운터 · 잠금', status: redis && !data.profile?.redisConfigured ? warning('설정 확인', 'REDIS 공유 상태가 선택되어 있지만 Redis 연결 객체가 구성되지 않았습니다.') : uncertain(redis ? 'Redis 사용 설정은 확인됐지만 PING·인증·ACL 상태는 별도 검사해야 합니다.' : '배포 프로필의 공유 상태 설정만 표시하며 실행 상태를 검사하지 않습니다.', '설정 정보'), column: 5, lines: [`공유 상태 ${data.profile?.sharedStateProvider || 'UNKNOWN'}`, redis ? `Redis 구성 ${data.profile?.redisConfigured ? '있음' : '미확인'}` : '인스턴스별 메모리 상태'], facts: [fact('공유 상태 제공자', data.profile?.sharedStateProvider), fact('배포 프로필', data.profile?.profile), fact('Redis 구성 여부', redis ? data.profile?.redisConfigured ? '설정됨 · 통신 상태 별도' : '설정 미확인' : 'Redis 미사용')], notes: ['설정됨과 연결 정상은 다른 의미입니다. 비밀번호·사용자명·접속 비밀값은 노출하지 않습니다.'] })
  node({ id: 'observability', kind: 'observability', title: '요청 추적 · Usage', subtitle: 'Attempt · 오류 진단 · 비용', status: uncertain('요청 추적과 사용량은 Gateway 기능입니다. 모니터링 서버의 실제 상태는 관측성 화면에서 확인하세요.', '기능 구성'), column: 5, lines: ['Gateway 요청과 Playground 구분', '요청·실패·토큰·비용 분석'], facts: [fact('운영 기능', '요청/Attempt 진단, Runtime·모델 통계, 토큰·예상 비용'), fact('메트릭', 'Actuator / Prometheus 형식 제공')], notes: ['Prometheus·Grafana의 배포 여부나 건강 상태를 이 화면에서 추정하지 않습니다.'] })
  nodes.filter(node => node.column === 5).forEach((node, index) => { node.x = xPositions[1] + index * 266; node.y = dependencyY; node.height = 148 })
  for (const id of ['database', 'shared', 'observability']) edge(`dependency:${id}`, 'gateway', id, 'dependency', '기능 연결', 'Gateway → ' + nodes.find(node => node.id === id)!.title + ' · 기능/설정 관계 (실시간 통신 미검사)', nodes.find(node => node.id === id)!.status)
  const index = new Map(nodes.map(node => [node.id, node]))
  const outgoing = new Map<string, TopologyEdge[]>(); const incoming = new Map<string, TopologyEdge[]>()
  for (const edge of edges) { outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge]); incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge]) }
  for (const edge of edges) {
    const from = index.get(edge.source)!; const to = index.get(edge.target)!
    if (edge.kind === 'dependency') {
      const sx = from.x + from.width / 2; const sy = from.y + from.height; const tx = to.x + to.width / 2; const ty = to.y
      const bend = dependencyY - 46 + ['database', 'shared', 'observability'].indexOf(to.id) * 12
      edge.path = `M ${sx} ${sy} L ${sx} ${bend} L ${tx} ${bend} L ${tx} ${ty}`
      edge.labelX = tx; edge.labelY = bend - 8
    } else {
      const siblings = outgoing.get(edge.source)!.filter(item => item.kind !== 'dependency'); const arrivals = incoming.get(edge.target)!
      const sy = from.y + from.height / 2 + (siblings.indexOf(edge) - (siblings.length - 1) / 2) * Math.min(14, 100 / Math.max(1, siblings.length))
      const ty = to.y + to.height / 2 + (arrivals.indexOf(edge) - (arrivals.length - 1) / 2) * Math.min(14, 100 / Math.max(1, arrivals.length))
      const sx = from.x + from.width; const tx = to.x; const mid = (sx + tx) / 2
      edge.path = `M ${sx} ${sy} C ${mid} ${sy}, ${mid} ${ty}, ${tx} ${ty}`
      edge.labelX = mid; edge.labelY = (sy + ty) / 2
    }
  }
  return { nodes, edges, columns, width: 1338, height: dependencyY + 180, mainHeight, dependencyY, hiddenModelCount: allModels.size - [...modelIds].filter(id => allModels.has(id)).length, emptyServiceScope: !!options.projectId && !services.length, scopeReadFailed: !!options.projectId && (serviceListFailed || failedRead(`/projects/${options.projectId}/service-access`)) }
}

export function relatedTopologyNodes(graph: ReturnType<typeof buildSystemTopology>, selectedId: string, data: SystemSnapshot) {
  if (!selectedId || selectedId === 'gateway') return new Set(graph.nodes.map(node => node.id))
  const node = graph.nodes.find(node => node.id === selectedId)
  if (!node) return new Set<string>()
  if (node.column === 5) return new Set([node.id, 'gateway'])
  const result = new Set([node.id, 'gateway'])
  const services = new Set<string>()
  const modelIds = new Set<string>()
  if (node.kind === 'project') for (const grant of data.grants[node.entityId!] ?? []) services.add(`service:${grant.id}`)
  if (node.kind === 'service') services.add(node.id)
  if (node.kind === 'model') modelIds.add(node.id)
  if (['runtime', 'provider'].includes(node.kind)) for (const edge of graph.edges) if (edge.kind === 'execution' && edge.target === node.id) modelIds.add(edge.source)
  if (modelIds.size) for (const edge of graph.edges) if (edge.kind === 'target' && modelIds.has(edge.target)) services.add(edge.source)
  for (const edge of graph.edges) if (edge.kind === 'target' && services.has(edge.source) && (node.kind === 'project' || node.kind === 'service')) modelIds.add(edge.target)
  for (const id of services) result.add(id)
  for (const id of modelIds) result.add(id)
  for (const edge of graph.edges) if (edge.kind === 'execution' && modelIds.has(edge.source)) result.add(edge.target)
  if (node.kind !== 'project') for (const project of data.projects) if ((data.grants[project.id] ?? []).some(grant => services.has(`service:${grant.id}`))) result.add(`project:${project.id}`)
  return result
}
