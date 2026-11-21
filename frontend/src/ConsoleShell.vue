<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseModal from './BaseModal.vue'
import DateTimePreferences from './DateTimePreferences.vue'
import AuditLogPage from './AuditLogPage.vue'
import DashboardPage from './DashboardPage.vue'
import DeveloperPortalPage from './DeveloperPortalPage.vue'
import DevDocsPage from './DevDocsPage.vue'
import InfrastructurePage from './InfrastructurePage.vue'
import SystemStructurePage from './SystemStructurePage.vue'
import ExternalProvidersPage from './ExternalProvidersPage.vue'
import ServicesPage from './ServicesPage.vue'
import ProjectsPage from './ProjectsPage.vue'
import TeamsPage from './TeamsPage.vue'
import ObservabilityPage from './ObservabilityPage.vue'
import NotificationsPage from './NotificationsPage.vue'
import UsagePage from './UsagePage.vue'
import QuotaPage from './QuotaPage.vue'
import ScopedOverviewPage from './ScopedOverviewPage.vue'
import DataProtectionPage from './DataProtectionPage.vue'
import ApiPlaygroundPage from './ApiPlaygroundPage.vue'
import ModelPlaygroundPage from './ModelPlaygroundPage.vue'
import PlatformAdminPage from './PlatformAdminPage.vue'
import BetaOperatorPage from './BetaOperatorPage.vue'
import {
  accountTierLabel,
  adminFetch,
  registrationUrl,
  type AccountTier,
  type AdminAuth,
  type IssuedAccountInvitation,
  type MfaState,
  type User
} from './api'
import { copyText } from './clipboard'
import { formatDateTime } from './dateTime'

type Theme = 'dark' | 'light'
type FontScale = '100' | '115' | '125' | '135'
type PageKey = 'beta' | 'dashboard' | 'infrastructure' | 'system' | 'external' | 'services' | 'teams' | 'projects' | 'observability' | 'usage' | 'quotas' | 'data-protection' | 'notifications' | 'audit' | 'platform' | 'portal' | 'playground' | 'model-playground' | 'docs'
type OrganizationRole = 'ORGANIZATION_ADMIN' | 'DEVELOPER'
type Organization = { id: string; name: string; status: string }
type AccessibleProject = { id: string; name: string; status: string }
type Membership = { organizationId: string; role: OrganizationRole }
type SessionContext = { platformAdmin: boolean; accountTier?: AccountTier; totpEnabled?: boolean; mfaState?: MfaState; memberships: Membership[] }
type NavItem = { id: Exclude<PageKey, 'docs'>; label: string; description: string; keywords: string; icon: string; group: string }

const props = defineProps<{ user: User | null; platformTokenSession: boolean; theme: Theme; fontScale: FontScale }>()
const emit = defineEmits<{ logout: []; toggleTheme: []; fontScaleChanged: [value: FontScale] }>()

const auth = computed<AdminAuth>(() => ({ accessToken: sessionStorage.getItem('aiconnect.accessToken') ?? undefined, platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? undefined }))
const adminNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '요청·장애·런타임 핵심 지표', keywords: 'overview health dashboard', icon: '◔', group: '운영' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘', group: '워크스페이스' },
  { id: 'infrastructure', label: '인프라스트럭처', description: '노드·Runtime·배포 모델', keywords: 'server gpu lm studio endpoint model', icon: '◇', group: '운영' },
  { id: 'system', label: '시스템 구조', description: '요청 경로 · Target · 서버 연결 상태', keywords: '시스템 구조 연결도 상태 architecture structure topology infrastructure provider llm team project api key', icon: '~', group: 'Operations' },
  { id: 'external', label: '외부 AI', description: 'OpenAI·승인·수동·자동 Failover', keywords: 'openai external provider cloud approval failover', icon: '◎', group: '운영' },
  { id: 'services', label: 'LLM 서비스', description: '논리 모델과 라우팅 정책', keywords: 'service model failover routing', icon: '▣', group: '운영' },
  { id: 'teams', label: '팀과 부서', description: '부서·역할·프로젝트 소유', keywords: 'team department role audit', icon: '⌘', group: '워크스페이스 관리' },
  { id: 'projects', label: '프로젝트', description: '프로젝트·권한·API 키', keywords: 'project key quota credential', icon: '⌘', group: '워크스페이스 관리' },
  { id: 'observability', label: '관측성', description: '요청·장애·Failover 추적', keywords: 'request incident failover monitor', icon: '◉', group: '관측' },
  { id: 'usage', label: '사용량', description: '토큰·비용·API 호출 이력', keywords: 'usage token cost billing', icon: '◴', group: '관측' },
  { id: 'notifications', label: '알림 채널', description: 'Discord와 Telegram 연동', keywords: 'notification discord telegram alert', icon: '◇', group: '시스템' },
  { id: 'audit', label: '감사 로그', description: '관리자 변경·열람 증적', keywords: 'audit log admin change security', icon: '◎', group: '시스템' },
  { id: 'quotas', label: '요금·한도', description: '범위별 예산과 초과 차단', keywords: 'budget quota limit spend cost', icon: '₩', group: '관측' },
  { id: 'data-protection', label: '데이터 보호', description: '민감정보 탐지·외부 전송 정책', keywords: 'security privacy dlp pii secret local only external block', icon: '◇', group: '시스템' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷', group: '개발자 도구' },
  { id: 'model-playground', label: '모델 테스트 콘솔', description: '등록된 Runtime·Provider 모델 직접 대화 테스트', keywords: 'model playground chat runtime provider streaming file test', icon: '◈', group: '개발자 도구' }
]
const directorNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '접근 범위 요약', keywords: 'dashboard overview', icon: '◔', group: '홈' },
  { id: 'observability', label: '관측성', description: '접근 범위 요청 이력', keywords: 'request monitor', icon: '◉', group: '관측·비용' },
  { id: 'projects', label: '프로젝트', description: '프로젝트·멤버·권한·API 키', keywords: 'project key member delegation policy', icon: '⌘', group: '워크스페이스 관리' },
  { id: 'quotas', label: '요금·한도', description: '프로젝트 예산과 관리자 상한', keywords: 'budget quota limit spend cost ceiling', icon: '₩', group: '내 작업' },
  { id: 'data-protection', label: '데이터 보호', description: '프로젝트·API 키 민감정보 정책', keywords: 'security privacy dlp pii secret local only', icon: '◇', group: '내 작업' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘', group: '내 작업' },
  { id: 'usage', label: 'API 사용량', description: '프로젝트·API 키 토큰·비용 이력', keywords: 'usage token cost request', icon: '◴', group: '내 작업' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷', group: '내 작업' }
]
const platformNavItem: NavItem = { id: 'platform', label: 'Platform Admin', description: '조직·사용자·키·데이터 정리', keywords: 'platform admin organization user api key cleanup purge', icon: '⚡', group: '시스템' }
const developerNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '접근 범위 요약', keywords: 'dashboard overview', icon: '◔', group: '홈' },
  { id: 'observability', label: '관측성', description: '내 API 키 요청 이력', keywords: 'request monitor', icon: '◉', group: '관측·비용' },
  { id: 'quotas', label: '요금·한도', description: '내 API 키 추정 비용', keywords: 'budget quota cost', icon: '₩', group: '관측·비용' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘', group: '내 작업' },
  { id: 'usage', label: 'API 사용량', description: '내 API 키의 토큰·비용·요청', keywords: 'usage token cost request', icon: '◴', group: '내 작업' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷', group: '내 작업' }
]
const docsItem = { id: 'docs' as const, label: 'Dev-Docs', description: '제품 가이드 · API 참조', keywords: 'docs manual guide api lm studio' }
const fontScaleOptions: Array<{ value: FontScale; label: string; description: string }> = [
  { value: '100', label: '기본', description: '100%' }, { value: '115', label: '권장', description: '115%' },
  { value: '125', label: '크게', description: '125%' }, { value: '135', label: '매우 크게', description: '135%' }
]

const page = ref<PageKey>('dashboard')
const organizations = ref<Organization[]>([])
const accessibleProjects = ref<AccessibleProject[]>([])
const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const memberships = ref<Membership[]>([])
const contextPlatformAdmin = ref(false)
const contextAccountTier = ref<AccountTier | null>(null)
const contextTotpEnabled = ref<boolean | null>(null)
const betaOperator = ref(false)
const sidebarOpen = ref(false)
const settingsOpen = ref(false)
const userMenuOpen = ref(false)
const searchOpen = ref(false)
const search = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const profileOpen = ref(false)
const environmentOpen = ref(false)
const accountOpen = ref(false)
const busy = ref(false)
const message = ref('')
const profileLabel = ref(localStorage.getItem('aiconnect.profileLabel') ?? '')
const inviteForm = ref({
  targetTier: 'GENERAL_USER' as 'PROJECT_DIRECTOR' | 'GENERAL_USER',
  organizationId: '',
  projectId: '',
  canIssueApiKeys: false,
  expectedEmail: '',
  expiresInHours: 72
})
const inviteModalProjects = ref<AccessibleProject[]>([])
const issuedAccountInvite = ref<IssuedAccountInvitation | null>(null)
const lockedInviteProject = ref<{ organizationId: string; projectId: string; projectName: string } | null>(null)
const playgroundInitialTargetId = ref('')

const selectedMembership = computed(() => memberships.value.find(member => member.organizationId === organizationId.value) ?? null)
const platformAdmin = computed(() => Boolean(props.user?.platformAdmin || props.platformTokenSession || contextPlatformAdmin.value))
const effectiveAccountTier = computed<AccountTier>(() => {
  if (props.platformTokenSession || platformAdmin.value) return 'SYSTEM_ADMIN'
  if (contextAccountTier.value) return contextAccountTier.value
  if (props.user?.accountTier) return props.user.accountTier
  return selectedMembership.value?.role === 'ORGANIZATION_ADMIN' ? 'PROJECT_DIRECTOR' : 'GENERAL_USER'
})
const isOrgOrPlatformAdmin = computed(() =>
  platformAdmin.value
  || (effectiveAccountTier.value !== 'PROJECT_DIRECTOR'
    && effectiveAccountTier.value !== 'GENERAL_USER'
    && selectedMembership.value?.role === 'ORGANIZATION_ADMIN')
)
const isProjectDirector = computed(() => !isOrgOrPlatformAdmin.value && effectiveAccountTier.value === 'PROJECT_DIRECTOR')
const canInviteAccounts = computed(() => isOrgOrPlatformAdmin.value || isProjectDirector.value)
const navItems = computed(() => [
  ...(isOrgOrPlatformAdmin.value
    ? (platformAdmin.value ? [...adminNavItems, platformNavItem] : adminNavItems)
    : isProjectDirector.value
      ? directorNavItems
      : developerNavItems),
  ...(platformAdmin.value || betaOperator.value ? [{id:'beta' as const,label:'초대 서비스 운영',description:'초대·접근·운영 상태',keywords:'beta invitation invite',icon:'◇',group:'운영'}] : [])
].map(item => ({ ...item, group: item.id === 'dashboard' ? '홈'
  : ['projects', 'teams', 'portal', 'data-protection'].includes(item.id) ? '워크스페이스'
  : ['playground', 'model-playground'].includes(item.id) ? '개발자 도구'
  : ['observability', 'usage', 'quotas'].includes(item.id) ? '관측·비용' : '인프라·시스템' })))
const groups = computed(() => ['홈', '워크스페이스', '개발자 도구', '관측·비용', '인프라·시스템'].filter(group => navItems.value.some(item => item.group === group)))
const allNavigation = computed(() => [...navItems.value, docsItem])
const currentNav = computed(() => allNavigation.value.find(item => item.id === page.value) ?? navItems.value[0] ?? docsItem)
const currentOrganization = computed(() => organizations.value.find(item => item.id === organizationId.value) ?? null)
const profileName = computed(() => profileLabel.value.trim() || props.user?.email || 'Platform session')
const profileRole = computed(() => {
  if (props.platformTokenSession) return '시스템 관리자 (비상 토큰)'
  return accountTierLabel(effectiveAccountTier.value, platformAdmin.value)
})
const totpActive = computed(() => !props.platformTokenSession && Boolean(contextTotpEnabled.value ?? props.user?.totpEnabled))
const totpBadgeLabel = computed(() => {
  if (props.platformTokenSession) return '비상 토큰'
  return totpActive.value ? 'TOTP 활성' : 'TOTP 미등록'
})
const issuedInviteLink = computed(() =>
  issuedAccountInvite.value ? registrationUrl(issuedAccountInvite.value.registrationPath) : ''
)
const searchResults = computed(() => {
  const query = search.value.trim().toLowerCase()
  return query ? allNavigation.value.filter(item => `${item.label} ${item.description} ${item.keywords}`.toLowerCase().includes(query)) : allNavigation.value
})

function knownPage(value: string): value is PageKey {
  return ['beta', 'dashboard', 'infrastructure', 'system', 'external', 'services', 'teams', 'projects', 'observability', 'usage', 'quotas', 'data-protection', 'notifications', 'audit', 'platform', 'portal', 'playground', 'model-playground', 'docs'].includes(value)
}
function isAllowedPage(target: PageKey) { return target === 'docs' || navItems.value.some(item => item.id === target) }
function fallbackPage(): PageKey { return 'dashboard' }
function resolveHash(): PageKey {
  const candidate = window.location.hash.replace(/^#\/?/, '')
  const pageCandidate = candidate.split('/')[0]
  return knownPage(pageCandidate) ? pageCandidate : fallbackPage()
}
function ensureAllowedPage() {
  if (!isAllowedPage(page.value)) navigate(fallbackPage())
}
function navigate(target: PageKey) {
  const allowedTarget = isAllowedPage(target) ? target : fallbackPage()
  window.location.hash = `#${allowedTarget}`
  page.value = allowedTarget
  sidebarOpen.value = false
  searchOpen.value = false
}
function openModelPlayground(targetId: string) {
  const value = targetId.trim()
  playgroundInitialTargetId.value = value
  const allowedTarget = isAllowedPage('model-playground') ? 'model-playground' : fallbackPage()
  window.location.hash = allowedTarget === 'model-playground' && value ? `#model-playground/${encodeURIComponent(value)}` : `#${allowedTarget}`
  page.value = allowedTarget
  sidebarOpen.value = false
  searchOpen.value = false
}
function selectOrganization(id: string) {
  if (id !== organizationId.value && !busy.value) closeAccount()
  organizationId.value = id
  if (id) sessionStorage.setItem('aiconnect.setup.organizationId', id)
  else sessionStorage.removeItem('aiconnect.setup.organizationId')
  void loadAccessibleProjects()
  ensureAllowedPage()
}
async function loadSessionContext() {
  if (!auth.value.accessToken) return
  try {
    const context = await adminFetch<SessionContext>('/api/portal/session', auth.value)
    memberships.value = context.memberships
    contextPlatformAdmin.value = context.platformAdmin
    contextAccountTier.value = context.accountTier ?? null
    contextTotpEnabled.value = typeof context.totpEnabled === 'boolean' ? context.totpEnabled : null
  } catch (error) {
    message.value = error instanceof Error ? error.message : '사용자 권한 정보를 확인하지 못했습니다.'
  }
}
async function loadOrganizations(preferred?: string) {
  try {
    organizations.value = await adminFetch<Organization[]>('/api/admin/organizations', auth.value)
    const requested = preferred ?? organizationId.value
    selectOrganization(organizations.value.some(item => item.id === requested) ? requested : organizations.value[0]?.id ?? '')
  } catch (error) { message.value = error instanceof Error ? error.message : '조직 목록을 불러오지 못했습니다.' }
}
async function loadAccessibleProjects() {
  if (!organizationId.value || !canInviteAccounts.value) { accessibleProjects.value = []; return }
  const orgId = organizationId.value
  accessibleProjects.value = []
  try { const items = await adminFetch<AccessibleProject[]>(`/api/admin/organizations/${orgId}/projects`, auth.value); if (orgId === organizationId.value) accessibleProjects.value = items }
  catch { if (orgId === organizationId.value) accessibleProjects.value = [] }
}
function openSearch() { settingsOpen.value = false; userMenuOpen.value = false; searchOpen.value = true; nextTick(() => searchInput.value?.focus()) }
function saveProfile() {
  if (profileLabel.value.trim()) localStorage.setItem('aiconnect.profileLabel', profileLabel.value.trim())
  else localStorage.removeItem('aiconnect.profileLabel')
  profileOpen.value = false
}
async function loadInviteModalProjects(orgId: string) {
  if (!orgId) { inviteModalProjects.value = []; inviteForm.value.projectId = ''; return }
  if (orgId === organizationId.value && accessibleProjects.value.length) {
    inviteModalProjects.value = accessibleProjects.value
    if (!inviteModalProjects.value.some(project => project.id === inviteForm.value.projectId)) {
      inviteForm.value.projectId = inviteModalProjects.value[0]?.id ?? ''
    }
    return
  }
  try {
    const loaded = await adminFetch<AccessibleProject[]>(`/api/admin/organizations/${orgId}/projects`, auth.value)
    if (orgId !== inviteForm.value.organizationId || lockedInviteProject.value) return
    inviteModalProjects.value = loaded
    inviteForm.value.projectId = loaded[0]?.id ?? ''
  } catch {
    if (orgId !== inviteForm.value.organizationId || lockedInviteProject.value) return
    inviteModalProjects.value = []
    inviteForm.value.projectId = ''
  }
}
function closeAccount() { if (busy.value) return; accountOpen.value = false; issuedAccountInvite.value = null; lockedInviteProject.value = null }
function openAccount(project?: { id: string; name: string }) {
  if (!canInviteAccounts.value) return
  settingsOpen.value = false
  issuedAccountInvite.value = null
  const defaultOrgId = organizationId.value || organizations.value[0]?.id || ''
  lockedInviteProject.value = project ? { organizationId: defaultOrgId, projectId: project.id, projectName: project.name } : null
  inviteForm.value = {
    targetTier: platformAdmin.value ? 'PROJECT_DIRECTOR' : 'GENERAL_USER',
    organizationId: defaultOrgId,
    projectId: project?.id ?? (defaultOrgId === organizationId.value ? (accessibleProjects.value[0]?.id ?? '') : ''),
    canIssueApiKeys: false,
    expectedEmail: '',
    expiresInHours: 72
  }
  inviteModalProjects.value = defaultOrgId === organizationId.value ? accessibleProjects.value : []
  if (!project && defaultOrgId && (defaultOrgId !== organizationId.value || !inviteModalProjects.value.length)) {
    void loadInviteModalProjects(defaultOrgId)
  }
  accountOpen.value = true
}
function onInviteModalOrgChange() {
  inviteForm.value.projectId = ''
  void loadInviteModalProjects(inviteForm.value.organizationId)
}
async function issueQuickAccountInvitation() {
  if (!canInviteAccounts.value) return
  const targetOrgId = lockedInviteProject.value?.organizationId || inviteForm.value.organizationId || organizationId.value
  if (!targetOrgId) {
    message.value = '초대할 조직을 먼저 선택하세요.'
    return
  }
  if (!inviteForm.value.projectId) {
    message.value = '초대할 프로젝트를 먼저 선택하세요.'
    return
  }
  busy.value = true
  try {
    const targetTier = platformAdmin.value ? inviteForm.value.targetTier : 'GENERAL_USER'
    const endpoint = platformAdmin.value && targetTier === 'PROJECT_DIRECTOR'
      ? '/api/admin/platform/account-invitations'
      : `/api/admin/organizations/${targetOrgId}/account-invitations`
    const payload = {
      targetTier,
      organizationId: targetOrgId,
      projectId: lockedInviteProject.value?.projectId || inviteForm.value.projectId,
      canIssueApiKeys: targetTier === 'PROJECT_DIRECTOR' ? true : Boolean(inviteForm.value.canIssueApiKeys),
      expectedEmail: inviteForm.value.expectedEmail.trim() || null,
      expiresInHours: Math.max(1, Math.min(720, Number(inviteForm.value.expiresInHours) || 72))
    }
    const created = await adminFetch<IssuedAccountInvitation>(endpoint, auth.value, {
      method: 'POST',
      body: JSON.stringify(payload)
    })
    issuedAccountInvite.value = created
    inviteForm.value.expectedEmail = ''
    message.value = `${accountTierLabel(created.targetTier)} 초대 코드와 가입 링크를 발급했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '계정 초대 발급에 실패했습니다.'
  } finally {
    busy.value = false
  }
}
async function copyQuickInviteValue(value: string, label: string) {
  if (!value) return
  try {
    await copyText(value)
    message.value = `${label}를 클립보드에 복사했습니다.`
  } catch {
    message.value = `${label}를 복사하지 못했습니다. 표시된 값을 직접 선택해 복사하세요.`
  }
}
function openInvitationManagementPage() {
  accountOpen.value = false
  navigate(platformAdmin.value ? 'platform' : isOrgOrPlatformAdmin.value ? 'teams' : 'projects')
}
function onHashChange() {
  page.value = resolveHash()
  if (page.value === 'model-playground') {
    const part = window.location.hash.replace(/^#\/?/, '').split('/')[1]
    try { playgroundInitialTargetId.value = part ? decodeURIComponent(part) : '' } catch { playgroundInitialTargetId.value = '' }
  } else playgroundInitialTargetId.value = ''
  ensureAllowedPage(); sidebarOpen.value = false; searchOpen.value = false
}
function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); openSearch() }
  if (event.key === 'Escape') { searchOpen.value = false; settingsOpen.value = false; userMenuOpen.value = false }
}

onMounted(async () => {
  page.value = resolveHash()
  if (page.value === 'model-playground') {
    const part = window.location.hash.replace(/^#\/?/, '').split('/')[1]
    try { playgroundInitialTargetId.value = part ? decodeURIComponent(part) : '' } catch { playgroundInitialTargetId.value = '' }
  }
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('keydown', onGlobalKeydown)
  await loadSessionContext()
  if (auth.value.accessToken) {
    try { const scoped=await adminFetch<unknown[]>('/api/beta/operator/services',auth.value); betaOperator.value=scoped.length>0 } catch { betaOperator.value=false }
  } else {
    betaOperator.value=false
  }
  await loadOrganizations()
  ensureAllowedPage()
  if (!window.location.hash) window.history.replaceState(null, '', `#${page.value}`)
})
onBeforeUnmount(() => { window.removeEventListener('hashchange', onHashChange); window.removeEventListener('keydown', onGlobalKeydown) })
</script>

<template>
  <div class="console-shell">
    <button v-if="sidebarOpen" class="sidebar-scrim" aria-label="메뉴 닫기" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="sidebar-brand"><span class="brand-mark"><i></i><i></i><i></i></span><span><strong>AICONNECT</strong><small>LLM CONTROL PLANE</small></span></div>
      <div class="scope-card"><span>WORKSPACE</span><select :value="organizationId" :disabled="busy" @change="selectOrganization(($event.target as HTMLSelectElement).value)"><option value="">조직 선택</option><option v-for="organization in organizations" :key="organization.id" :value="organization.id">{{ organization.name }} · {{ organization.status }}</option></select><small>{{ currentOrganization?.name ?? '배정된 조직을 선택하세요.' }}</small></div>
      <nav class="side-nav" aria-label="주요 기능"><section v-for="group in groups" :key="group" class="nav-group"><p>{{ group }}</p><button v-for="item in navItems.filter(nav => nav.group === group)" :key="item.id" :class="{ active: page === item.id }" @click="navigate(item.id)"><span class="nav-icon">{{ item.icon }}</span><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span><i></i></button></section></nav>
      <div class="sidebar-support">
        <p>도움말</p>
        <button class="sidebar-doc-link" :class="{ active: page === 'docs' }" @click="navigate('docs')">
          <span class="nav-icon docs-nav-icon">&lt;/&gt;</span>
          <span><strong>Dev-Docs</strong><small>제품 가이드 · API 참조</small></span>
          <i></i>
        </button>
      </div>
      <div class="sidebar-foot"><span class="live-dot"></span><div><strong>Gateway online</strong><small>Tailscale private mesh</small></div></div>
    </aside>

    <div class="console-stage">
      <header class="topbar">
        <div class="topbar-leading"><button class="icon-button mobile-menu" aria-label="메뉴 열기" @click="sidebarOpen = true">☰</button><div class="breadcrumb"><span>AICONNECT</span><b>/</b><strong>{{ currentNav.label }}</strong></div></div>
        <div class="topbar-actions">
          <button class="feature-search" @click="openSearch"><span>⌕</span><em>기능 검색</em><kbd>⌘K</kbd></button>
          <button class="icon-button" :aria-label="theme === 'dark' ? '라이트 모드' : '다크 모드'" @click="emit('toggleTheme')">{{ theme === 'dark' ? '☀' : '◐' }}</button>
          <div class="settings-wrap"><button class="icon-button settings-trigger" :class="{ active: settingsOpen }" aria-label="설정" @click="settingsOpen = !settingsOpen; userMenuOpen = false">⚙</button><div v-if="settingsOpen" class="settings-popover"><header><span>빠른 설정</span><small>프로필과 환경을 관리합니다.</small></header><button class="setting-action" @click="settingsOpen = false; profileOpen = true"><i>◉</i><span><strong>프로필 설정</strong><small>표시 이름과 현재 계정 확인</small></span><b>›</b></button><button class="setting-action" @click="settingsOpen = false; environmentOpen = true"><i>◐</i><span><strong>환경 설정</strong><small>테마·글자 크기·시각 표시 조절</small></span><b>›</b></button><button v-if="canInviteAccounts" class="setting-action" @click="openAccount()"><i>+</i><span><strong>계정 추가</strong><small>초대 코드와 가입 링크 발급</small></span><b>›</b></button></div></div>
          <div class="user-menu-wrap">
            <button class="user-chip" @click="userMenuOpen = !userMenuOpen; settingsOpen = false">
              <span>{{ profileName.slice(0, 1).toUpperCase() }}</span>
              <span><strong>{{ profileName }}</strong><small>{{ profileRole }}</small></span>
              <span class="totp-chip" :class="{ active: totpActive }">{{ totpBadgeLabel }}</span>
              <i>⌄</i>
            </button>
            <div v-if="userMenuOpen" class="user-popover">
              <div>
                <strong>{{ user?.email ?? 'Emergency session' }}</strong>
                <small>{{ profileRole }}</small>
                <span class="status-chip tiny" :class="totpActive ? 'healthy' : 'draining'">
                  <i></i>{{ platformTokenSession ? '비상 플랫폼 토큰 세션' : totpActive ? 'Google Authenticator TOTP 활성' : 'TOTP 미등록' }}
                </span>
              </div>
              <button class="profile-shortcut" @click="userMenuOpen = false; profileOpen = true">프로필 설정</button>
              <button @click="emit('logout')">로그아웃</button>
            </div>
          </div>
        </div>
      </header>
      <main class="console-content">
        <p v-if="message" class="inline-alert">{{ message }}</p>
        <DashboardPage v-if="isOrgOrPlatformAdmin && page === 'dashboard'" :organization-id="organizationId" :auth="auth" @navigate="navigate" />
        <ScopedOverviewPage v-else-if="!isOrgOrPlatformAdmin && (page === 'dashboard' || page === 'observability' || (page === 'quotas' && !isProjectDirector))" :key="page" :mode="page" :organization-id="organizationId" :auth="auth" />
        <InfrastructurePage v-else-if="isOrgOrPlatformAdmin && page === 'infrastructure'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" @open-playground="openModelPlayground" />
        <SystemStructurePage v-else-if="isOrgOrPlatformAdmin && page === 'system'" :organization-id="organizationId" :auth="auth" />
        <ExternalProvidersPage v-else-if="isOrgOrPlatformAdmin && page === 'external'" :organization-id="organizationId" :auth="auth" @open-playground="openModelPlayground" />
        <ServicesPage v-else-if="isOrgOrPlatformAdmin && page === 'services'" :organization-id="organizationId" :auth="auth" />
        <TeamsPage v-else-if="isOrgOrPlatformAdmin && page === 'teams'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" />
        <ProjectsPage v-else-if="(isOrgOrPlatformAdmin || isProjectDirector) && page === 'projects'" :organization-id="organizationId" :organization-name="currentOrganization?.name ?? ''" :current-user-id="user?.id" :auth="auth" :platform-admin="platformAdmin" :org-admin="isOrgOrPlatformAdmin" @invite="openAccount" @open-platform="navigate('platform')" />
        <ObservabilityPage v-else-if="isOrgOrPlatformAdmin && page === 'observability'" :organization-id="organizationId" :auth="auth" />
        <NotificationsPage v-else-if="isOrgOrPlatformAdmin && page === 'notifications'" :organization-id="organizationId" :auth="auth" />
        <AuditLogPage v-else-if="isOrgOrPlatformAdmin && page === 'audit'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" />
        <PlatformAdminPage v-else-if="platformAdmin && page === 'platform'" :auth="auth" @organizations-changed="loadOrganizations($event)" />
        <BetaOperatorPage v-else-if="page === 'beta' && (platformAdmin || betaOperator)" :auth="auth" :platform-admin="platformAdmin" />
        <UsagePage v-else-if="page === 'usage'" :organization-id="organizationId" :auth="auth" />
        <QuotaPage v-else-if="(isOrgOrPlatformAdmin || isProjectDirector) && page === 'quotas'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" :org-admin="isOrgOrPlatformAdmin" />
        <DataProtectionPage v-else-if="(isOrgOrPlatformAdmin || isProjectDirector) && page === 'data-protection'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" :org-admin="isOrgOrPlatformAdmin" />
        <ApiPlaygroundPage v-else-if="page === 'playground'" :organization-id="organizationId" :auth="auth" />
        <ModelPlaygroundPage v-else-if="isOrgOrPlatformAdmin && page === 'model-playground'" :organization-id="organizationId" :auth="auth" :initial-target-id="playgroundInitialTargetId" />
        <DevDocsPage v-else-if="page === 'docs'" @navigate="navigate" />
        <DeveloperPortalPage v-else :organization-id="organizationId" :auth="auth" />
      </main>
    </div>

    <div v-if="searchOpen" class="command-backdrop" @mousedown.self="searchOpen = false"><section class="command-palette" role="dialog" aria-modal="true" aria-label="기능 검색"><div class="command-input"><span>⌕</span><input ref="searchInput" v-model="search" placeholder="기능, 모델, API 키 검색" /><kbd>ESC</kbd></div><div class="command-results"><p>기능으로 이동</p><button v-for="item in searchResults" :key="item.id" @click="navigate(item.id)"><span class="nav-icon">{{ item.id === 'docs' ? '?' : ('icon' in item ? item.icon : '') }}</span><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span><kbd>↵</kbd></button></div></section></div>

    <BaseModal :open="profileOpen" title="프로필 설정" description="콘솔에 표시되는 이름만 이 브라우저에 저장됩니다." size="sm" @close="profileOpen = false">
      <div class="modal-form">
        <div class="settings-profile-summary">
          <span>{{ profileName.slice(0, 1).toUpperCase() }}</span>
          <div>
            <strong>{{ user?.email ?? 'Emergency session' }}</strong>
            <small>{{ profileRole }} · {{ totpBadgeLabel }}</small>
          </div>
        </div>
        <label class="field">표시 이름<input v-model.trim="profileLabel" maxlength="60" placeholder="AI 운영 관리자" /></label>
      </div>
      <template #footer><button class="secondary-button" @click="profileOpen = false">취소</button><button class="primary-button" @click="saveProfile">저장</button></template>
    </BaseModal>

    <BaseModal :open="environmentOpen" title="환경 설정" description="테마, 글자 크기와 시각 표시 형식은 즉시 적용됩니다." @close="environmentOpen = false">
      <div class="settings-section"><span class="settings-section-title">테마</span><div class="settings-choice-grid"><button :class="{ active: theme === 'light' }" @click="theme !== 'light' && emit('toggleTheme')"><strong>라이트 모드</strong><small>밝은 작업 환경</small></button><button :class="{ active: theme === 'dark' }" @click="theme !== 'dark' && emit('toggleTheme')"><strong>다크 모드</strong><small>어두운 작업 환경</small></button></div></div>
      <div class="settings-section"><span class="settings-section-title">글자 크기</span><div class="font-scale-grid"><button v-for="option in fontScaleOptions" :key="option.value" :class="{ active: fontScale === option.value }" @click="emit('fontScaleChanged', option.value)"><strong>{{ option.label }}</strong><small>{{ option.description }}</small></button></div></div>
      <DateTimePreferences />
      <template #footer><button class="primary-button" @click="environmentOpen = false">확인</button></template>
    </BaseModal>

    <BaseModal v-if="canInviteAccounts" :open="accountOpen" title="계정 초대 코드·링크 발급" description="하위 계정을 직접 생성하지 않고 1회용 초대 코드와 가입 링크를 발급합니다. 가입자는 Google Authenticator 2차 인증을 필수로 등록합니다." @close="closeAccount">
      <div class="modal-form">
        <label v-if="platformAdmin" class="field">
          부여 계층
          <select v-model="inviteForm.targetTier">
            <option value="PROJECT_DIRECTOR">프로젝트 총괄 (PROJECT_DIRECTOR)</option>
            <option value="GENERAL_USER">기본 계정 (GENERAL_USER)</option>
          </select>
        </label>
        <div v-else class="profile-tier-note">
          <strong>부여 계층: 기본 계정 (GENERAL_USER)</strong>
          <small>프로젝트 총괄 계정은 담당 프로젝트의 하위 기본 계정 초대 코드만 발급할 수 있습니다.</small>
        </div>

        <label v-if="platformAdmin" class="field">
          대상 조직
          <select v-model="inviteForm.organizationId" :disabled="!!lockedInviteProject" @change="onInviteModalOrgChange">
            <option value="">조직 선택</option>
            <option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option>
          </select>
        </label>

        <label class="field">
          대상 프로젝트
          <select v-model="inviteForm.projectId" :disabled="!!lockedInviteProject">
            <option value="">프로젝트 선택</option>
            <option v-if="lockedInviteProject" :value="lockedInviteProject.projectId">{{ lockedInviteProject.projectName }}</option>
            <option v-for="project in lockedInviteProject ? [] : inviteModalProjects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.status }}</option>
          </select>
        </label>

        <label v-if="!platformAdmin || inviteForm.targetTier === 'GENERAL_USER'" class="toggle-field">
          <span>
            API 키 발급 권한 위임
            <small>체크하면 가입자가 선택한 프로젝트에서 직접 API 키를 발급할 수 있습니다.</small>
          </span>
          <input v-model="inviteForm.canIssueApiKeys" type="checkbox" />
        </label>

        <label class="field">
          지정 이메일 (선택)
          <input v-model.trim="inviteForm.expectedEmail" type="email" placeholder="developer@company.com" />
        </label>

        <label class="field">
          만료 시간 (시간)
          <input v-model.number="inviteForm.expiresInHours" type="number" min="1" max="720" />
        </label>

        <div v-if="issuedAccountInvite" role="status" class="secret-reveal quick-invite-reveal">
          <span>1회용 초대 코드 및 가입 링크 (한 번만 표시 · 만료 {{ formatDateTime(issuedAccountInvite.expiresAt) }})</span>
          <div class="quick-invite-row">
            <div>
              <small>초대 코드</small>
              <code>{{ issuedAccountInvite.code }}</code>
            </div>
            <button type="button" class="secondary-button" @click="copyQuickInviteValue(issuedAccountInvite.code, '초대 코드')">코드 복사</button>
          </div>
          <div class="quick-invite-row">
            <div>
              <small>가입 링크</small>
              <code>{{ issuedInviteLink }}</code>
            </div>
            <button type="button" class="secondary-button" @click="copyQuickInviteValue(issuedInviteLink, '가입 링크')">링크 복사</button>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="ghost-button" type="button" @click="openInvitationManagementPage">초대 이력·사용자 관리</button>
        <button class="secondary-button" type="button" @click="closeAccount">닫기</button>
        <button class="primary-button" type="button" :disabled="busy || (!inviteForm.organizationId && !organizationId) || !inviteForm.projectId" @click="issueQuickAccountInvitation">초대 코드/링크 발급</button>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.totp-chip {
  padding: 3px 7px;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  background: var(--surface);
  color: var(--muted);
  font-size: 9px;
  font-weight: 800;
  white-space: nowrap;
}
.totp-chip.active {
  border-color: var(--accent-border);
  background: var(--accent-dim);
  color: var(--accent-strong);
}
.profile-tier-note {
  padding: 12px 13px;
  display: grid;
  gap: 4px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface-2);
}
.profile-tier-note strong {
  font-size: 12px;
}
.profile-tier-note small {
  color: var(--muted);
  font-size: 10px;
}
.quick-invite-reveal {
  grid-template-columns: 1fr;
}
.quick-invite-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  align-items: center;
}
.quick-invite-row > div {
  min-width: 0;
  display: grid;
  gap: 4px;
}
.quick-invite-row small {
  color: var(--muted);
  font-size: 10px;
  font-weight: 700;
}
.quick-invite-row code {
  word-break: break-all;
}
@media (max-width: 760px) {
  .totp-chip {
    display: none;
  }
}
@media (max-width: 620px) {
  .quick-invite-row {
    grid-template-columns: 1fr;
  }
}
</style>
