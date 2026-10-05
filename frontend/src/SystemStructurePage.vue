<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'
import SystemTopologyMap from './SystemTopologyMap.vue'
import { safeEndpoint, type SystemSnapshot, type SystemModel, type SystemTarget } from './systemTopology'

type Profile = { profile: string; sharedStateProvider: string; instanceId?: string; redisConfigured?: boolean }
type Provider = { id: string; displayName: string; providerType: string; baseUrl: string; enabled: boolean; healthStatus: string; apiKeyConfigured: boolean; lastCheckedAt?: string | null }
type ProviderModel = SystemModel & { externalProviderId?: string | null }
type Access = { id: string; projectId: string; projectName: string; providerId: string; providerName: string; status: string; autoFailoverEnabled: boolean; manualAllowed?: boolean; expiresAt?: string | null }
type Service = { id: string; serviceKey: string; displayName: string; enabled: boolean; failoverPolicy?: string; retryPolicy?: string }
type Target = SystemTarget
type Team = { id: string; name: string; status: string }
type Member = { teamId: string; userId: string; role: string }
type User = { id: string; email: string; organizationRole: string }
type Project = { id: string; name: string; status: string; teamId?: string | null }
type ApiKey = { id: string; name: string; keyPrefix: string; status: string }
type Grant = { id: string; serviceKey: string; displayName: string; enabled: boolean }
type LoadError = { section: string; path: string; message: string }

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()

const profile = ref<Profile | null>(null)
const endpoints = ref<Endpoint[]>([])
const endpointDeployments = ref<Record<string, Deployment[]>>({})
const providers = ref<Provider[]>([])
const providerModels = ref<Record<string, ProviderModel[]>>({})
const accesses = ref<Access[]>([])
const services = ref<Service[]>([])
const targets = ref<Record<string, Target[]>>({})
const teams = ref<Team[]>([])
const members = ref<Record<string, Member[]>>({})
const users = ref<User[]>([])
const projects = ref<Project[]>([])
const keys = ref<Record<string, ApiKey[]>>({})
const grants = ref<Record<string, Grant[]>>({})
const errors = ref<LoadError[]>([])
const busy = ref(false)
const message = ref('현재 시스템 구성을 불러오는 중입니다.')
const lastLoadedAt = ref('')
const layer = ref<'all' | 'runtime' | 'routing' | 'access'>('all')
let loadVersion = 0

const snapshot = computed<SystemSnapshot>(() => ({
  profile: profile.value, endpoints: endpoints.value, endpointDeployments: endpointDeployments.value,
  providers: providers.value, providerModels: providerModels.value, services: services.value, targets: targets.value,
  projects: projects.value, keys: keys.value, grants: grants.value, teams: teams.value, accesses: accesses.value,
  unavailablePaths: errors.value.map(item => item.path)
}))

const healthyEndpoints = computed(() => endpoints.value.filter(item => item.enabled && ['HEALTHY', 'READY', 'ACTIVE'].includes(item.healthStatus.toUpperCase())).length)
const deploymentCount = computed(() => Object.values(endpointDeployments.value).reduce((total, items) => total + items.length, 0))
const loadedDeploymentCount = computed(() => Object.values(endpointDeployments.value).flat().filter(item => item.enabled && item.loaded).length)
const providerCount = computed(() => providers.value.filter(item => item.enabled).length)
const runnableResourceCount = computed(() => endpoints.value.length + providers.value.length)
const enabledServiceCount = computed(() => services.value.filter(item => item.enabled).length)
const activeKeyCount = computed(() => Object.values(keys.value).flat().filter(item => item.status === 'ACTIVE').length)
const grantCount = computed(() => Object.values(grants.value).reduce((total, items) => total + items.length, 0))
const targetCount = computed(() => Object.values(targets.value).reduce((total, items) => total + items.length, 0))

function textError(error: unknown) { return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.' }
function reset() {
  profile.value = null; endpoints.value = []; endpointDeployments.value = {}; providers.value = []; providerModels.value = {}
  accesses.value = []; services.value = []; targets.value = {}; teams.value = []; members.value = {}; users.value = []
  projects.value = []; keys.value = {}; grants.value = {}; errors.value = []; lastLoadedAt.value = ''
}
async function load() {
  const version = ++loadVersion
  const organizationId = props.organizationId
  const auth = props.auth
  if (!organizationId) { reset(); busy.value = false; message.value = '워크스페이스를 선택하면 현재 시스템 구조를 확인할 수 있습니다.'; return }
  busy.value = true; message.value = '현재 저장된 구성과 연결을 확인하고 있습니다.'
  const loadErrors: LoadError[] = []
  async function read<T>(path: string, section: string, fallback: T): Promise<T> {
    try { return await adminFetch<T>(path, auth) }
    catch (error) { loadErrors.push({ section, path, message: textError(error) }); return fallback }
  }
  try {
    const values = await Promise.all([
      read<Profile>('/api/admin/deployment-profile', '배포 프로필', { profile: 'UNKNOWN', sharedStateProvider: 'UNKNOWN' }),
      read<Endpoint[]>('/api/admin/organizations/' + organizationId + '/runtime-endpoints', 'Runtime Endpoint', []),
      read<Provider[]>('/api/admin/organizations/' + organizationId + '/external-providers', '외부 AI Provider', []),
      read<Access[]>('/api/admin/organizations/' + organizationId + '/external-access', '외부 AI 권한', []),
      read<Service[]>('/api/admin/organizations/' + organizationId + '/services', 'LLM 서비스', []),
      read<Team[]>('/api/admin/organizations/' + organizationId + '/teams', '팀과 부서', []),
      read<User[]>('/api/admin/organizations/' + organizationId + '/users', '조직 사용자', []),
      read<Project[]>('/api/admin/organizations/' + organizationId + '/projects', '프로젝트', [])
    ])
    if (version !== loadVersion) return
    const [nextProfile, nextEndpoints, nextProviders, nextAccesses, nextServices, nextTeams, nextUsers, nextProjects] = values

    const [endpointRows, providerRows, serviceRows, teamRows, projectRows] = await Promise.all([
      Promise.all(nextEndpoints.map(async item => [item.id, await read<Deployment[]>('/api/admin/runtime-endpoints/' + item.id + '/deployments', '모델 배포 · ' + (item.displayName || safeEndpoint(item.baseUrl)), [])] as const)),
      Promise.all(nextProviders.map(async item => [item.id, await read<ProviderModel[]>('/api/admin/external-providers/' + item.id + '/models', '외부 모델 · ' + item.displayName, [])] as const)),
      Promise.all(nextServices.map(async item => [item.id, await read<Target[]>('/api/admin/services/' + item.id + '/targets', '서비스 Target · ' + item.serviceKey, [])] as const)),
      Promise.all(nextTeams.map(async item => [item.id, await read<Member[]>('/api/admin/organizations/' + organizationId + '/teams/' + item.id + '/members', '팀 구성원 · ' + item.name, [])] as const)),
      Promise.all(nextProjects.map(async item => {
        const result = await Promise.all([
          read<ApiKey[]>('/api/admin/projects/' + item.id + '/api-keys', 'API 키 · ' + item.name, []),
          read<Grant[]>('/api/admin/projects/' + item.id + '/service-access', '서비스 권한 · ' + item.name, [])
        ])
        return [item.id, result] as const
      }))
    ])
    if (version !== loadVersion) return
    profile.value = nextProfile; endpoints.value = nextEndpoints; providers.value = nextProviders; accesses.value = nextAccesses
    services.value = nextServices; teams.value = nextTeams; users.value = nextUsers; projects.value = nextProjects
    endpointDeployments.value = Object.fromEntries(endpointRows)
    providerModels.value = Object.fromEntries(providerRows)
    targets.value = Object.fromEntries(serviceRows)
    members.value = Object.fromEntries(teamRows)
    keys.value = Object.fromEntries(projectRows.map(([id, value]) => [id, value[0]]))
    grants.value = Object.fromEntries(projectRows.map(([id, value]) => [id, value[1]]))
    errors.value = loadErrors
    lastLoadedAt.value = new Date().toLocaleString()
    message.value = errors.value.length
      ? '확인 가능한 구성은 표시했지만 일부 데이터는 권한 또는 연결 상태로 불러오지 못했습니다.'
      : '현재 저장된 인프라·AI·라우팅·조직 연결을 시각화했습니다.'
  } finally { if (version === loadVersion) busy.value = false }
}
function statusClass(value?: string | null) {
  const status = (value || 'UNKNOWN').toUpperCase()
  if (['ACTIVE', 'HEALTHY', 'READY', 'LOADED', 'APPROVED', 'CONNECTED'].includes(status)) return 'healthy'
  if (['FAILED', 'UNHEALTHY', 'ERROR', 'SUSPENDED', 'REVOKED', 'DISABLED'].includes(status)) return 'unhealthy'
  if (['DEGRADED', 'DRAINING', 'PENDING', 'RECOVERING'].includes(status)) return 'suspect'
  return 'unknown'
}
function statusLabel(value?: string | null) {
  const status = (value || 'UNKNOWN').toUpperCase()
  const labels: Record<string, string> = { HEALTHY: '정상', READY: '준비됨', ACTIVE: '활성', APPROVED: '승인됨', LOADED: '로딩됨', UNKNOWN: '확인 필요', DISABLED: '비활성', SUSPENDED: '중지됨', DRAINING: 'Drain' }
  return labels[status] || status
}
function endpointName(item: Endpoint) { return item.displayName || safeEndpoint(item.baseUrl).replace(/^https?:\/\//, '') }
function modelsFor(item: Endpoint) { return endpointDeployments.value[item.id] || [] }
function providerModelsFor(item: Provider) { return providerModels.value[item.id] || [] }
function targetsFor(item: Service) { return targets.value[item.id] || [] }
function membersFor(item: Team) { return members.value[item.id] || [] }
function keysFor(item: Project) { return keys.value[item.id] || [] }
function grantsFor(item: Project) { return grants.value[item.id] || [] }
function teamName(teamId?: string | null) { return teams.value.find(item => item.id === teamId)?.name || '조직 공용' }
function projectsFor(teamId: string) { return projects.value.filter(item => item.teamId === teamId).length }
function providerName(providerId: string) { return providers.value.find(item => item.id === providerId)?.displayName || providerId.slice(0, 8) }
const deploymentIndex = computed(() => {
  const index: Record<string, { label: string; providerId?: string | null }> = {}
  for (const deployment of Object.values(endpointDeployments.value).flat()) {
    index[deployment.id] = { label: deployment.displayName || deployment.providerModelId, providerId: deployment.externalProviderId }
  }
  for (const [providerId, models] of Object.entries(providerModels.value)) for (const model of models) {
    index[model.id] = { label: model.displayName || model.providerModelId, providerId }
  }
  return index
})
function targetLabel(target: Target) {
  const deployment = deploymentIndex.value[target.deploymentId]
  if (!deployment) return target.deploymentId.slice(0, 8)
  return deployment.label + (deployment.providerId ? ' · ' + providerName(deployment.providerId) : '')
}
function grantLabel(grant: Grant) { return grant.displayName || grant.serviceKey }
function show(item: 'runtime' | 'routing' | 'access') { return layer.value === 'all' || layer.value === item }
function profileLabel(value?: string) { return (value || 'UNKNOWN').replaceAll('_', ' ') }

watch(() => props.organizationId, () => { reset(); void load() })
onMounted(() => { void load() })
onBeforeUnmount(() => { loadVersion++ })
</script>

<template>
  <section class="page-stack system-structure-page">
    <div class="page-hero">
      <div><p class="eyebrow">SYSTEM TOPOLOGY</p><h1>현재 시스템 구조</h1><p>누가 어떤 서비스를 호출하고, 어느 모델·서버로 연결되는지 실제 등록 구성과 마지막 확인 상태로 살펴봅니다.</p></div>
      <div class="hero-actions"><span class="live-pill"><i></i>{{ busy ? '구성 조회 중' : errors.length ? '일부 조회 실패' : '구성 스냅샷' }}</span><button class="secondary-button" :disabled="busy || !organizationId" @click="load">{{ busy ? '불러오는 중…' : '새로고침' }}</button></div>
    </div>
    <div v-if="!organizationId" class="workspace-required"><span>WORKSPACE REQUIRED</span><div><strong>워크스페이스를 먼저 선택하세요</strong><p>조직을 선택하면 현재 저장된 시스템 구성과 연결 관계가 표시됩니다.</p></div></div>
    <template v-else>
      <p class="inline-alert" :class="{ 'structure-warning': errors.length }">{{ message }}<small v-if="lastLoadedAt">마지막 확인 {{ lastLoadedAt }}</small></p>
      <div class="metric-grid structure-metrics">
        <article class="metric-card accent"><span>Runtime Endpoint</span><strong>{{ healthyEndpoints }}<small>/ {{ endpoints.length }}</small></strong><small>정상 연결 기록 / 등록</small></article>
        <article class="metric-card"><span>Local 모델 배포</span><strong>{{ loadedDeploymentCount }}<small>/ {{ deploymentCount }}</small></strong><small>활성 모델 로드 기록 / 등록</small></article>
        <article class="metric-card"><span>외부 Provider</span><strong>{{ providerCount }}<small>/ {{ providers.length }}</small></strong><small>OpenAI 호환 Provider</small></article>
        <article class="metric-card"><span>논리 LLM 서비스</span><strong>{{ enabledServiceCount }}<small>/ {{ services.length }}</small></strong><small>{{ grantCount }}개 프로젝트 권한 연결</small></article>
        <article class="metric-card"><span>프로젝트 / API 키</span><strong>{{ projects.length }}<small> / {{ activeKeyCount }}</small></strong><small>프로젝트 / 활성 키</small></article>
        <article class="metric-card"><span>팀 / 사용자</span><strong>{{ teams.length }}<small> / {{ users.length }}</small></strong><small>부서·팀 / 조직 사용자</small></article>
      </div>

      <article class="surface-card architecture-card">
        <header class="card-header"><div><span class="card-kicker">CONNECTED ARCHITECTURE</span><h2>요청 경로와 연결 상태</h2></div><span class="status-chip" :class="errors.length ? 'suspect' : 'healthy'"><i></i>{{ errors.length ? '일부 확인 필요' : '구성 조회 완료' }}</span></header>
        <SystemTopologyMap :snapshot="snapshot" :busy="busy" />
        <details class="configuration-list">
          <summary><span>전체 구성 목록</span><small>팀·프로젝트·키·모델·Target 상세 목록 펼치기</small></summary>
        <div class="layer-tabs" role="group" aria-label="구성 계층 필터"><button :class="{ active: layer === 'all' }" :aria-pressed="layer === 'all'" @click="layer = 'all'">전체 구조</button><button :class="{ active: layer === 'runtime' }" :aria-pressed="layer === 'runtime'" @click="layer = 'runtime'">Runtime & Provider</button><button :class="{ active: layer === 'routing' }" :aria-pressed="layer === 'routing'" @click="layer = 'routing'">LLM Routing</button><button :class="{ active: layer === 'access' }" :aria-pressed="layer === 'access'" @click="layer = 'access'">팀 & 프로젝트</button></div>
        <div class="architecture-map">
          <section v-if="show('runtime')" class="architecture-layer">
            <div class="layer-heading"><div><span class="card-kicker">COMPUTE & PROVIDERS</span><h3>등록된 AI 실행 자원</h3></div><small>{{ runnableResourceCount }}개 등록</small></div>
            <div class="resource-grid">
              <article v-for="item in endpoints" :key="item.id" class="resource-card" :class="{ muted: !item.enabled }"><div class="resource-icon">◇</div><div class="resource-content"><div class="resource-heading"><strong>{{ endpointName(item) }}</strong><span class="status-chip tiny" :class="statusClass(item.healthStatus)">{{ statusLabel(item.healthStatus) }}</span></div><small>{{ item.runtimeType }} · {{ safeEndpoint(item.baseUrl) }}</small><div class="resource-meta"><span>모델 {{ modelsFor(item).length }}</span><span>노드 {{ item.nodeId.slice(0, 8) }}</span></div><div v-if="modelsFor(item).length" class="resource-tags"><span v-for="model in modelsFor(item).slice(0, 3)" :key="model.id">{{ model.displayName }}</span><span v-if="modelsFor(item).length > 3">+{{ modelsFor(item).length - 3 }}</span></div><div v-else class="resource-empty">동기화된 모델 없음</div></div></article>
              <article v-for="item in providers" :key="item.id" class="resource-card" :class="{ muted: !item.enabled }"><div class="resource-icon provider">◎</div><div class="resource-content"><div class="resource-heading"><strong>{{ item.displayName }}</strong><span class="status-chip tiny" :class="statusClass(item.healthStatus)">{{ statusLabel(item.healthStatus) }}</span></div><small>{{ item.providerType }} · {{ safeEndpoint(item.baseUrl) }}</small><div class="resource-meta"><span>모델 {{ providerModelsFor(item).length }}</span><span>{{ item.apiKeyConfigured ? 'API 키 설정됨' : 'API 키 미설정' }}</span></div><div v-if="providerModelsFor(item).length" class="resource-tags"><span v-for="model in providerModelsFor(item).slice(0, 3)" :key="model.id">{{ model.displayName }}</span><span v-if="providerModelsFor(item).length > 3">+{{ providerModelsFor(item).length - 3 }}</span></div><div v-else class="resource-empty">등록된 외부 모델 없음</div></div></article>
              <div v-if="!endpoints.length && !providers.length" class="empty-state compact layer-empty"><span>◇</span><p>연결된 Runtime Endpoint 또는 외부 Provider가 없습니다.</p></div>
            </div>
            <div class="layer-footnote"><span class="status-chip tiny" :class="profile?.profile === 'UNKNOWN' ? 'unknown' : 'healthy'">{{ profileLabel(profile?.profile) }}</span><span>공유 상태 {{ profileLabel(profile?.sharedStateProvider) }}</span><span v-if="profile?.instanceId">Instance <code>{{ profile.instanceId }}</code></span><span v-if="profile?.redisConfigured !== undefined">Redis {{ profile.redisConfigured ? 'configured' : 'not configured' }}</span><b>외부 모델 {{ Object.values(providerModels).flat().length }}개</b></div>
          </section>
          <section v-if="show('routing')" class="architecture-layer">
            <div class="layer-heading"><div><span class="card-kicker">MODEL ROUTING</span><h3>논리 LLM 서비스와 Target</h3></div><small>{{ services.length }}개 서비스 · {{ targetCount }}개 Target</small></div>
            <div class="service-grid">
              <article v-for="item in services" :key="item.id" class="service-node" :class="{ muted: !item.enabled }"><div class="service-node-top"><span class="service-glyph">▣</span><span class="status-chip tiny" :class="item.enabled ? 'healthy' : 'unknown'">{{ item.enabled ? 'ACTIVE' : 'DISABLED' }}</span></div><strong>{{ item.displayName }}</strong><code>{{ item.serviceKey }}</code><div class="service-policy"><span>{{ item.failoverPolicy || 'STRICT' }}</span><span>{{ item.retryPolicy || 'SAFE' }}</span><b>{{ targetsFor(item).length }} targets</b></div><div v-if="targetsFor(item).length" class="target-stack"><span v-for="target in targetsFor(item).slice(0, 4)" :key="target.id" :class="{ disabled: !target.enabled, degraded: target.degraded }"><i></i>{{ targetLabel(target) }} · P{{ target.priority }} · 가중치 {{ target.weight }}</span><small v-if="targetsFor(item).length > 4">+{{ targetsFor(item).length - 4 }}개 Target</small></div><div v-else class="resource-empty">연결된 Target 없음</div></article>
              <div v-if="!services.length" class="empty-state compact layer-empty"><span>▣</span><p>등록된 논리 LLM 서비스가 없습니다.</p></div>
            </div>
          </section>
          <section v-if="show('access')" class="architecture-layer">
            <div class="layer-heading"><div><span class="card-kicker">ACCESS PLANE</span><h3>팀·프로젝트·API 키</h3></div><small>{{ projects.filter(item => item.teamId).length }}개 팀 소유 프로젝트</small></div>
            <div class="access-grid">
              <article v-for="item in teams" :key="item.id" class="access-node"><div class="access-node-top"><span class="access-icon">⌘</span><span class="status-chip tiny" :class="statusClass(item.status)">{{ statusLabel(item.status) }}</span></div><strong>{{ item.name }}</strong><small>{{ membersFor(item).length }}명 · 프로젝트 {{ projectsFor(item.id) }}개</small><div class="access-tags"><span v-for="member in membersFor(item).slice(0, 3)" :key="member.userId + member.role">{{ member.role }}</span><span v-if="membersFor(item).length > 3">+{{ membersFor(item).length - 3 }}</span></div></article>
              <article v-for="item in projects" :key="item.id" class="access-node project-node"><div class="access-node-top"><span class="access-icon project">⌘</span><span class="status-chip tiny" :class="statusClass(item.status)">{{ statusLabel(item.status) }}</span></div><strong>{{ item.name }}</strong><small>{{ teamName(item.teamId) }} · 키 {{ keysFor(item).length }}개 · 서비스 {{ grantsFor(item).length }}개</small><div class="access-tags"><span v-for="key in keysFor(item).slice(0, 3)" :key="key.id" :class="{ revoked: key.status !== 'ACTIVE' }">{{ key.keyPrefix }}••••</span><span v-if="keysFor(item).length > 3">+{{ keysFor(item).length - 3 }}</span><span v-if="!keysFor(item).length">API 키 없음</span><span v-for="grant in grantsFor(item).slice(0, 3)" :key="'grant-' + grant.id">{{ grantLabel(grant) }}</span><span v-if="grantsFor(item).length > 3">+{{ grantsFor(item).length - 3 }}</span></div></article>
              <article v-for="item in accesses" :key="'access-' + item.id" class="access-node provider-access"><div class="access-node-top"><span class="access-icon provider">↗</span><span class="status-chip tiny" :class="statusClass(item.status)">{{ statusLabel(item.status) }}</span></div><strong>{{ item.projectName }}</strong><small>→ {{ item.providerName }}</small><div class="access-tags"><span>{{ item.autoFailoverEnabled ? 'AUTO FAILOVER' : 'MANUAL ROUTE' }}</span><span>{{ item.status }}</span></div></article>
              <div v-if="!teams.length && !projects.length" class="empty-state compact layer-empty"><span>⌘</span><p>팀 또는 프로젝트가 없습니다.</p></div>
            </div>
            <div class="access-foot"><span>외부 Provider 권한 {{ accesses.length }}건</span><span>활성 API 키 {{ activeKeyCount }}개</span><span>조직 사용자 {{ users.length }}명</span><span>서비스 권한 {{ grantCount }}건</span><span v-if="accesses.length" class="access-links">{{ accesses.slice(0, 4).map(item => item.projectName + ' → ' + providerName(item.providerId)).join(' · ') }}</span></div>
          </section>
        </div>
        </details>
      </article>
      <article v-if="errors.length" class="surface-card partial-data-card"><header class="card-header"><div><span class="card-kicker">DIAGNOSTICS</span><h2>확인하지 못한 구성</h2></div><span class="status-chip suspect">{{ errors.length }}건</span></header><div class="partial-error-list"><div v-for="item in errors" :key="item.section + item.path"><strong>{{ item.section }}</strong><small>{{ item.message }}</small><code>{{ item.path }}</code></div></div><p>페이지는 확인 가능한 데이터로 계속 표시됩니다. 권한이 없는 범위는 기존 관리 페이지에서 같은 권한으로 확인하세요.</p></article>
    </template>
  </section>
</template>

<style scoped>
.system-structure-page { max-width: 1540px; width: 100%; min-width: 0; margin-inline: auto; }
.hero-actions { align-items: center; }
.structure-warning { border-color: color-mix(in srgb, var(--warning) 45%, transparent); background: var(--warning-dim); }
.inline-alert { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.inline-alert small { color: var(--muted); white-space: nowrap; }
.structure-metrics { grid-template-columns: repeat(6, minmax(0, 1fr)); }
.structure-metrics .metric-card > strong { display: flex; align-items: baseline; gap: 6px; }
.structure-metrics .metric-card > strong small { color: var(--muted); font-size: 12px; font-weight: 600; }
.architecture-card { min-width: 0; overflow: hidden; }
.architecture-card > .card-header { flex-wrap: wrap; gap: 12px; }
.configuration-list { border-top: 1px solid var(--border); }
.configuration-list > summary { padding: 18px 22px; color: var(--text-soft); font-size: 12px; font-weight: 700; cursor: pointer; }
.configuration-list > summary small { margin-left: 12px; color: var(--muted); font-size: 11px; font-weight: 400; }
.configuration-list[open] > summary { border-bottom: 1px solid var(--border); }
.layer-tabs { padding: 0 22px; display: flex; gap: 4px; border-bottom: 1px solid var(--border); overflow-x: auto; }
.layer-tabs button { min-height: 45px; padding: 0 14px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--muted); font-size: 10px; font-weight: 700; white-space: nowrap; }
.layer-tabs button:hover, .layer-tabs button.active { border-color: var(--accent-strong); color: var(--text); }
.architecture-map { padding: 25px; background: radial-gradient(circle at 50% 0, var(--accent-dim), transparent 35%); }
.architecture-layer { padding: 18px; border: 1px solid var(--border); border-radius: 17px; background: color-mix(in srgb, var(--surface) 84%, transparent); }
.architecture-layer + .architecture-layer { margin-top: 16px; }
.layer-heading { margin-bottom: 15px; display: flex; align-items: flex-end; justify-content: space-between; gap: 15px; }
.layer-heading h3 { margin: 5px 0 0; font-size: 16px; }
.layer-heading > small { color: var(--muted); font-size: 9px; white-space: nowrap; }
.resource-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.resource-card { min-width: 0; padding: 14px; display: grid; grid-template-columns: 34px minmax(0, 1fr); gap: 11px; border: 1px solid var(--border); border-radius: 13px; background: var(--surface-2); }
.resource-card.muted, .service-node.muted { opacity: .62; }
.resource-icon { width: 34px; height: 34px; display: grid; place-content: center; border: 1px solid var(--accent-border); border-radius: 10px; background: var(--accent-dim); color: var(--accent-strong); font-size: 16px; }
.resource-icon.provider { border-color: color-mix(in srgb, var(--info) 45%, var(--border)); background: color-mix(in srgb, var(--info) 12%, var(--surface)); color: var(--info); }
.resource-content { min-width: 0; display: grid; gap: 7px; }
.resource-heading { min-width: 0; display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.resource-heading strong, .resource-content > small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-heading strong { font-size: 11px; }
.resource-content > small { color: var(--muted); font-size: 8px; }
.resource-meta { display: flex; gap: 6px; flex-wrap: wrap; color: var(--text-soft); font-size: 8px; }
.resource-meta span { padding: 4px 6px; border-radius: 6px; background: var(--surface-3); }
.resource-tags, .access-tags { display: flex; gap: 5px; flex-wrap: wrap; }
.resource-tags span, .access-tags span { max-width: 170px; padding: 4px 6px; overflow: hidden; border: 1px solid var(--border); border-radius: 6px; color: var(--muted); font: 8px 'SFMono-Regular', Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.resource-empty { color: var(--faint); font-size: 8px; }
.layer-footnote, .access-foot { margin-top: 12px; padding-top: 11px; display: flex; align-items: center; gap: 9px; flex-wrap: wrap; border-top: 1px solid var(--border); color: var(--muted); font-size: 8px; }
.layer-footnote > span:not(.status-chip), .layer-footnote > b, .access-foot > span { padding: 4px 7px; border-radius: 6px; background: var(--surface-2); font-weight: 600; }
.layer-footnote code { color: var(--accent-strong); overflow-wrap: anywhere; }
.service-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.service-node { min-width: 0; padding: 14px; display: grid; gap: 8px; border: 1px solid var(--border); border-radius: 13px; background: var(--surface-2); }
.service-node-top, .access-node-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.service-glyph, .access-icon { width: 31px; height: 31px; display: grid; place-content: center; border: 1px solid var(--accent-border); border-radius: 9px; background: var(--accent-dim); color: var(--accent-strong); }
.access-icon.project { color: var(--info); border-color: color-mix(in srgb, var(--info) 45%, var(--border)); background: color-mix(in srgb, var(--info) 12%, var(--surface)); }
.service-node > strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.service-node > code { overflow: hidden; color: var(--accent-strong); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.service-policy { display: flex; gap: 5px; flex-wrap: wrap; color: var(--muted); font-size: 8px; }
.service-policy span, .service-policy b { padding: 4px 6px; border-radius: 6px; background: var(--surface-3); font-weight: 600; }
.service-policy b { color: var(--text-soft); }
.target-stack { display: grid; gap: 4px; }
.target-stack span, .target-stack small { overflow: hidden; color: var(--muted); font: 8px 'SFMono-Regular', Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.target-stack span { display: flex; align-items: center; gap: 5px; }
.target-stack i { width: 5px; height: 5px; flex: 0 0 auto; border-radius: 50%; background: var(--accent-strong); }
.target-stack span.disabled, .target-stack span.degraded { color: var(--warning); }
.target-stack span.disabled i, .target-stack span.degraded i { background: var(--warning); }
.target-stack small { color: var(--faint); }
.access-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.access-node { min-width: 0; padding: 13px; display: grid; gap: 8px; border: 1px solid var(--border); border-radius: 13px; background: var(--surface-2); }
.access-node strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.access-node > small { overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.access-tags span { color: var(--text-soft); font-family: Inter, sans-serif; }
.access-tags span.revoked { color: var(--danger); text-decoration: line-through; }
.access-links { flex: 1; min-width: 0; overflow: hidden; color: var(--accent-strong) !important; text-overflow: ellipsis; white-space: nowrap; }
.layer-empty { grid-column: 1 / -1; min-height: 130px; }
.partial-data-card { overflow: hidden; }
.partial-error-list { padding: 14px 20px 4px; display: grid; gap: 7px; }
.partial-error-list > div { padding: 10px 11px; display: grid; gap: 4px; border: 1px solid color-mix(in srgb, var(--warning) 35%, var(--border)); border-radius: 10px; background: var(--warning-dim); }
.partial-error-list strong { font-size: 10px; }
.partial-error-list small { color: var(--text-soft); font-size: 9px; }
.partial-error-list code { overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.partial-data-card > p { margin: 12px 20px 18px; color: var(--muted); font-size: 9px; line-height: 1.5; }
@media (max-width: 1280px) { .structure-metrics { grid-template-columns: repeat(3, minmax(140px, 1fr)); } .access-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 980px) { .resource-grid, .service-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .access-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 680px) { .structure-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .architecture-map { padding: 14px; } .resource-grid, .service-grid, .access-grid { grid-template-columns: 1fr; } .layer-heading { align-items: flex-start; flex-direction: column; gap: 6px; } .inline-alert { align-items: flex-start; flex-direction: column; gap: 5px; } }
@media (max-width: 450px) { .structure-metrics { grid-template-columns: 1fr; } }
</style>
