<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseModal from './BaseModal.vue'
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
import ApiPlaygroundPage from './ApiPlaygroundPage.vue'
import PlatformAdminPage from './PlatformAdminPage.vue'
import { adminFetch, type AdminAuth, type User } from './api'

type Theme = 'dark' | 'light'
type FontScale = '100' | '115' | '125' | '135'
type PageKey = 'dashboard' | 'infrastructure' | 'system' | 'external' | 'services' | 'teams' | 'projects' | 'observability' | 'usage' | 'quotas' | 'notifications' | 'audit' | 'platform' | 'portal' | 'playground' | 'docs'
type OrganizationRole = 'ORGANIZATION_ADMIN' | 'DEVELOPER'
type TeamRole = 'TEAM_ADMIN' | 'PROJECT_OWNER' | 'DEVELOPER' | 'AUDITOR'
type Organization = { id: string; name: string; status: string }
type Team = { id: string; name: string; status: string }
type Membership = { organizationId: string; role: OrganizationRole }
type SessionContext = { platformAdmin: boolean; memberships: Membership[] }
type NavItem = { id: Exclude<PageKey, 'docs'>; label: string; description: string; keywords: string; icon: string; group: string }

const props = defineProps<{ user: User | null; platformTokenSession: boolean; theme: Theme; fontScale: FontScale }>()
const emit = defineEmits<{ logout: []; toggleTheme: []; fontScaleChanged: [value: FontScale] }>()

const auth = computed<AdminAuth>(() => ({ accessToken: sessionStorage.getItem('aiconnect.accessToken') ?? undefined, platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? undefined }))
const adminNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '요청·장애·런타임 핵심 지표', keywords: 'overview health dashboard', icon: '◔', group: '운영' },
  { id: 'infrastructure', label: '인프라스트럭처', description: '노드·Runtime·배포 모델', keywords: 'server gpu lm studio endpoint model', icon: '◇', group: '운영' },
  { id: 'system', label: 'System Structure', description: 'Infrastructure / AI / Access map', keywords: 'architecture structure topology infrastructure provider llm team project api key', icon: '~', group: 'Operations' },
  { id: 'external', label: '외부 AI', description: 'OpenAI·승인·수동·자동 Failover', keywords: 'openai external provider cloud approval failover', icon: '◎', group: '운영' },
  { id: 'services', label: 'LLM 서비스', description: '논리 모델과 라우팅 정책', keywords: 'service model failover routing', icon: '▣', group: '운영' },
  { id: 'teams', label: '팀과 부서', description: '부서·역할·프로젝트 소유', keywords: 'team department role audit', icon: '⌘', group: '개발자 도구' },
  { id: 'projects', label: '프로젝트 & API 키', description: '프로젝트·권한·API 키', keywords: 'project key quota credential', icon: '⌘', group: '개발자 도구' },
  { id: 'observability', label: '관측성', description: '요청·장애·Failover 추적', keywords: 'request incident failover monitor', icon: '◉', group: '관측' },
  { id: 'usage', label: '사용량', description: '토큰·비용·API 호출 이력', keywords: 'usage token cost billing', icon: '◴', group: '관측' },
  { id: 'notifications', label: '알림 채널', description: 'Discord와 Telegram 연동', keywords: 'notification discord telegram alert', icon: '◇', group: '시스템' },
  { id: 'audit', label: '감사 로그', description: '관리자 변경·열람 증적', keywords: 'audit log admin change security', icon: '◎', group: '시스템' },
  { id: 'quotas', label: '요금·한도', description: '범위별 예산과 초과 차단', keywords: 'budget quota limit spend cost', icon: '₩', group: '관측' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷', group: '개발자 도구' }
]
const platformNavItem: NavItem = { id: 'platform', label: 'Platform Admin', description: '조직·사용자·키·데이터 정리', keywords: 'platform admin organization user api key cleanup purge', icon: '⚡', group: '시스템' }
const developerNavItems: NavItem[] = [
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘', group: '내 작업' },
  { id: 'usage', label: 'API 사용량', description: '내 API 키의 토큰·비용·요청', keywords: 'usage token cost request', icon: '◴', group: '내 작업' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷', group: '내 작업' }
]
const docsItem = { id: 'docs' as const, label: 'Dev-Docs', description: '제품 가이드 · API 참조', keywords: 'docs manual guide api lm studio' }
const fontScaleOptions: Array<{ value: FontScale; label: string; description: string }> = [
  { value: '100', label: '기본', description: '100%' }, { value: '115', label: '권장', description: '115%' },
  { value: '125', label: '크게', description: '125%' }, { value: '135', label: '매우 크게', description: '135%' }
]

const page = ref<PageKey>('portal')
const organizations = ref<Organization[]>([])
const teams = ref<Team[]>([])
const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const memberships = ref<Membership[]>([])
const contextPlatformAdmin = ref(false)
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
const accountForm = ref({ email: '', password: '', organizationRole: 'DEVELOPER' as OrganizationRole, teamId: '', teamRole: 'DEVELOPER' as TeamRole })

const selectedMembership = computed(() => memberships.value.find(member => member.organizationId === organizationId.value) ?? null)
const platformAdmin = computed(() => Boolean(props.user?.platformAdmin || props.platformTokenSession || contextPlatformAdmin.value))
const isAdminConsole = computed(() => platformAdmin.value || selectedMembership.value?.role === 'ORGANIZATION_ADMIN')
const navItems = computed(() => isAdminConsole.value ? (platformAdmin.value ? [...adminNavItems, platformNavItem] : adminNavItems) : developerNavItems)
const groups = computed(() => [...new Set(navItems.value.map(item => item.group))])
const allNavigation = computed(() => [...navItems.value, docsItem])
const currentNav = computed(() => allNavigation.value.find(item => item.id === page.value) ?? navItems.value[0] ?? docsItem)
const currentOrganization = computed(() => organizations.value.find(item => item.id === organizationId.value) ?? null)
const profileName = computed(() => profileLabel.value.trim() || props.user?.email || 'Platform session')
const profileRole = computed(() => platformAdmin.value ? 'Platform administrator' : isAdminConsole.value ? 'Organization administrator' : 'Developer portal')
const searchResults = computed(() => {
  const query = search.value.trim().toLowerCase()
  return query ? allNavigation.value.filter(item => `${item.label} ${item.description} ${item.keywords}`.toLowerCase().includes(query)) : allNavigation.value
})

function knownPage(value: string): value is PageKey {
  return ['dashboard', 'infrastructure', 'system', 'external', 'services', 'teams', 'projects', 'observability', 'usage', 'quotas', 'notifications', 'audit', 'platform', 'portal', 'playground', 'docs'].includes(value)
}
function isAllowedPage(target: PageKey) { return isAdminConsole.value || target === 'portal' || target === 'usage' || target === 'playground' || target === 'docs' }
function fallbackPage(): PageKey { return isAdminConsole.value ? 'dashboard' : 'portal' }
function resolveHash(): PageKey {
  const candidate = window.location.hash.replace(/^#\/?/, '')
  return knownPage(candidate) ? candidate : fallbackPage()
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
function selectOrganization(id: string) {
  organizationId.value = id
  if (id) sessionStorage.setItem('aiconnect.setup.organizationId', id)
  else sessionStorage.removeItem('aiconnect.setup.organizationId')
  loadTeams()
  ensureAllowedPage()
}
async function loadSessionContext() {
  if (!auth.value.accessToken) return
  try {
    const context = await adminFetch<SessionContext>('/api/portal/session', auth.value)
    memberships.value = context.memberships
    contextPlatformAdmin.value = context.platformAdmin
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
async function loadTeams() {
  if (!organizationId.value || !isAdminConsole.value) { teams.value = []; return }
  try { teams.value = await adminFetch<Team[]>(`/api/admin/organizations/${organizationId.value}/teams`, auth.value) }
  catch { teams.value = [] }
}
function openSearch() { settingsOpen.value = false; userMenuOpen.value = false; searchOpen.value = true; nextTick(() => searchInput.value?.focus()) }
function saveProfile() {
  if (profileLabel.value.trim()) localStorage.setItem('aiconnect.profileLabel', profileLabel.value.trim())
  else localStorage.removeItem('aiconnect.profileLabel')
  profileOpen.value = false
}
function openAccount() {
  if (!isAdminConsole.value) return
  settingsOpen.value = false
  accountForm.value = { email: '', password: '', organizationRole: 'DEVELOPER', teamId: teams.value[0]?.id ?? '', teamRole: 'DEVELOPER' }
  accountOpen.value = true
}
async function createAccount() {
  if (!isAdminConsole.value || !accountForm.value.email || accountForm.value.password.length < 12) return
  busy.value = true
  try {
    if (organizationId.value) {
      await adminFetch(`/api/admin/organizations/${organizationId.value}/users`, auth.value, { method: 'POST', body: JSON.stringify({ email: accountForm.value.email, password: accountForm.value.password, organizationRole: accountForm.value.organizationRole, teamId: accountForm.value.teamId || null, teamRole: accountForm.value.teamId ? accountForm.value.teamRole : null }) })
      message.value = '조직 사용자 계정을 생성하고 역할을 부여했습니다.'
    } else if (platformAdmin.value) {
      await adminFetch('/api/admin/users', auth.value, { method: 'POST', body: JSON.stringify({ email: accountForm.value.email, password: accountForm.value.password, platformAdmin: false }) })
      message.value = '플랫폼 사용자 계정을 생성했습니다.'
    }
    accountOpen.value = false
  } catch (error) { message.value = error instanceof Error ? error.message : '계정 생성에 실패했습니다.' }
  finally { busy.value = false }
}
function onHashChange() { page.value = resolveHash(); ensureAllowedPage(); sidebarOpen.value = false; searchOpen.value = false }
function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); openSearch() }
  if (event.key === 'Escape') { searchOpen.value = false; settingsOpen.value = false; userMenuOpen.value = false }
}

onMounted(async () => {
  page.value = resolveHash()
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('keydown', onGlobalKeydown)
  await loadSessionContext()
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
      <div class="scope-card"><span>WORKSPACE</span><select :value="organizationId" @change="selectOrganization(($event.target as HTMLSelectElement).value)"><option value="">조직 선택</option><option v-for="organization in organizations" :key="organization.id" :value="organization.id">{{ organization.name }} · {{ organization.status }}</option></select><small>{{ currentOrganization?.name ?? '배정된 조직을 선택하세요.' }}</small></div>
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
          <div class="settings-wrap"><button class="icon-button settings-trigger" :class="{ active: settingsOpen }" aria-label="설정" @click="settingsOpen = !settingsOpen; userMenuOpen = false">⚙</button><div v-if="settingsOpen" class="settings-popover"><header><span>빠른 설정</span><small>프로필과 환경을 관리합니다.</small></header><button class="setting-action" @click="settingsOpen = false; profileOpen = true"><i>◉</i><span><strong>프로필 설정</strong><small>표시 이름과 현재 계정 확인</small></span><b>›</b></button><button class="setting-action" @click="settingsOpen = false; environmentOpen = true"><i>◐</i><span><strong>환경 설정</strong><small>테마와 글자 크기 조절</small></span><b>›</b></button><button v-if="isAdminConsole" class="setting-action" @click="openAccount"><i>+</i><span><strong>계정 추가</strong><small>조직 사용자와 역할 생성</small></span><b>›</b></button></div></div>
          <div class="user-menu-wrap"><button class="user-chip" @click="userMenuOpen = !userMenuOpen; settingsOpen = false"><span>{{ profileName.slice(0, 1).toUpperCase() }}</span><span><strong>{{ profileName }}</strong><small>{{ profileRole }}</small></span><i>⌄</i></button><div v-if="userMenuOpen" class="user-popover"><div><strong>{{ user?.email ?? 'Emergency session' }}</strong><small>{{ profileRole }}</small></div><button @click="userMenuOpen = false; profileOpen = true">프로필 설정</button><button @click="emit('logout')">로그아웃</button></div></div>
        </div>
      </header>
      <main class="console-content">
        <p v-if="message" class="inline-alert">{{ message }}</p>
        <DashboardPage v-if="isAdminConsole && page === 'dashboard'" :organization-id="organizationId" :auth="auth" @navigate="navigate" />
        <InfrastructurePage v-else-if="isAdminConsole && page === 'infrastructure'" :organization-id="organizationId" :auth="auth" />
        <SystemStructurePage v-else-if="isAdminConsole && page === 'system'" :organization-id="organizationId" :auth="auth" />
        <ExternalProvidersPage v-else-if="isAdminConsole && page === 'external'" :organization-id="organizationId" :auth="auth" />
        <ServicesPage v-else-if="isAdminConsole && page === 'services'" :organization-id="organizationId" :auth="auth" />
        <TeamsPage v-else-if="isAdminConsole && page === 'teams'" :organization-id="organizationId" :auth="auth" />
        <ProjectsPage v-else-if="isAdminConsole && page === 'projects'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" @organizations-changed="loadOrganizations" @organization-selected="loadOrganizations($event)" />
        <ObservabilityPage v-else-if="isAdminConsole && page === 'observability'" :organization-id="organizationId" :auth="auth" />
        <NotificationsPage v-else-if="isAdminConsole && page === 'notifications'" :organization-id="organizationId" :auth="auth" />
        <AuditLogPage v-else-if="isAdminConsole && page === 'audit'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" />
        <PlatformAdminPage v-else-if="platformAdmin && page === 'platform'" :auth="auth" />
        <UsagePage v-else-if="page === 'usage'" :organization-id="organizationId" :auth="auth" />
        <QuotaPage v-else-if="isAdminConsole && page === 'quotas'" :organization-id="organizationId" :auth="auth" />
        <ApiPlaygroundPage v-else-if="page === 'playground'" />
        <DevDocsPage v-else-if="page === 'docs'" @navigate="navigate" />
        <DeveloperPortalPage v-else :organization-id="organizationId" :auth="auth" />
      </main>
    </div>

    <div v-if="searchOpen" class="command-backdrop" @mousedown.self="searchOpen = false"><section class="command-palette" role="dialog" aria-modal="true" aria-label="기능 검색"><div class="command-input"><span>⌕</span><input ref="searchInput" v-model="search" placeholder="기능, 모델, API 키 검색" /><kbd>ESC</kbd></div><div class="command-results"><p>기능으로 이동</p><button v-for="item in searchResults" :key="item.id" @click="navigate(item.id)"><span class="nav-icon">{{ item.id === 'docs' ? '?' : ('icon' in item ? item.icon : '') }}</span><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span><kbd>↵</kbd></button></div></section></div>

    <BaseModal :open="profileOpen" title="프로필 설정" description="콘솔에 표시되는 이름만 이 브라우저에 저장됩니다." size="sm" @close="profileOpen = false"><div class="modal-form"><label class="field">표시 이름<input v-model.trim="profileLabel" maxlength="60" placeholder="AI 운영 관리자" /></label></div><template #footer><button class="secondary-button" @click="profileOpen = false">취소</button><button class="primary-button" @click="saveProfile">저장</button></template></BaseModal>
    <BaseModal :open="environmentOpen" title="환경 설정" description="테마와 글자 크기는 즉시 적용됩니다." @close="environmentOpen = false"><div class="settings-section"><span class="settings-section-title">테마</span><div class="settings-choice-grid"><button :class="{ active: theme === 'light' }" @click="theme !== 'light' && emit('toggleTheme')"><strong>라이트 모드</strong><small>밝은 작업 환경</small></button><button :class="{ active: theme === 'dark' }" @click="theme !== 'dark' && emit('toggleTheme')"><strong>다크 모드</strong><small>어두운 작업 환경</small></button></div></div><div class="settings-section"><span class="settings-section-title">글자 크기</span><div class="font-scale-grid"><button v-for="option in fontScaleOptions" :key="option.value" :class="{ active: fontScale === option.value }" @click="emit('fontScaleChanged', option.value)"><strong>{{ option.label }}</strong><small>{{ option.description }}</small></button></div></div><template #footer><button class="primary-button" @click="environmentOpen = false">확인</button></template></BaseModal>
    <BaseModal v-if="isAdminConsole" :open="accountOpen" title="조직 사용자 추가" description="사용자는 조직 역할과 선택한 팀 역할에 따라 필요한 기능만 볼 수 있습니다." @close="accountOpen = false"><div class="modal-form"><label class="field">이메일<input v-model.trim="accountForm.email" type="email" placeholder="developer@company.com" /></label><label class="field">임시 비밀번호<input v-model="accountForm.password" type="password" minlength="12" placeholder="12자 이상" /></label><label class="field">조직 역할<select v-model="accountForm.organizationRole"><option value="DEVELOPER">Developer</option><option value="ORGANIZATION_ADMIN">Organization Admin</option></select></label><label v-if="organizationId" class="field">팀<select v-model="accountForm.teamId"><option value="">팀 미지정</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select></label><label v-if="accountForm.teamId" class="field">팀 역할<select v-model="accountForm.teamRole"><option value="TEAM_ADMIN">Team Admin</option><option value="PROJECT_OWNER">Project Owner</option><option value="DEVELOPER">Developer</option><option value="AUDITOR">Auditor</option></select></label></div><template #footer><button class="secondary-button" @click="accountOpen = false">취소</button><button class="primary-button" :disabled="busy || !accountForm.email || accountForm.password.length < 12" @click="createAccount">계정 생성</button></template></BaseModal>
  </div>
</template>
