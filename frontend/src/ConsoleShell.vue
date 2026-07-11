<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import DashboardPage from './DashboardPage.vue'
import InfrastructurePage from './InfrastructurePage.vue'
import ServicesPage from './ServicesPage.vue'
import ProjectsPage from './ProjectsPage.vue'
import ObservabilityPage from './ObservabilityPage.vue'
import NotificationsPage from './NotificationsPage.vue'
import UsagePage from './UsagePage.vue'
import { adminFetch, type AdminAuth, type User } from './api'

type Theme = 'dark' | 'light'
type PageKey = 'dashboard' | 'infrastructure' | 'services' | 'projects' | 'observability' | 'usage' | 'notifications'
type Organization = { id: string; name: string; status: string }
type NavItem = { id: PageKey; label: string; description: string; keywords: string; icon: string; group: string }

const props = defineProps<{ user: User | null; platformTokenSession: boolean; theme: Theme }>()
const emit = defineEmits<{ logout: []; toggleTheme: [] }>()

const auth = computed<AdminAuth>(() => ({
  accessToken: sessionStorage.getItem('aiconnect.accessToken') ?? undefined,
  platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? undefined
}))

const navItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '플랫폼 상태와 핵심 지표', keywords: '홈 현황 메트릭 상태 요약 overview', icon: '⌁', group: '운영' },
  { id: 'infrastructure', label: '인프라스트럭처', description: '노드, 엔드포인트, 배포 모델', keywords: '서버 gpu lm studio endpoint deployment 런타임', icon: '◇', group: '운영' },
  { id: 'services', label: 'LLM 서비스', description: '논리 모델과 라우팅 정책', keywords: '모델 target failover route routing 서비스', icon: '◫', group: '운영' },
  { id: 'projects', label: '프로젝트 & API 키', description: '프로젝트, 권한, API 키', keywords: 'project key access credential 조직', icon: '⌘', group: '개발자 도구' },
  { id: 'observability', label: '관측성', description: '요청, 장애, Failover 추적', keywords: 'request incident log monitor 장애 요청', icon: '◉', group: '관측' },
  { id: 'usage', label: '사용량', description: '토큰, 비용, API 호출 이력', keywords: 'token cost billing usage 비용 토큰', icon: '⌇', group: '관측' },
  { id: 'notifications', label: '알림 채널', description: 'Discord와 Telegram 연동', keywords: 'notification discord telegram webhook 알림', icon: '♢', group: '시스템' }
]

const groups = [...new Set(navItems.map(item => item.group))]
const page = ref<PageKey>('dashboard')
const organizations = ref<Organization[]>([])
const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const sidebarOpen = ref(false)
const userMenuOpen = ref(false)
const searchOpen = ref(false)
const searchInput = ref<HTMLInputElement | null>(null)
const search = ref('')
const loadingOrganizations = ref(false)
const shellMessage = ref('')

const currentNav = computed(() => navItems.find(item => item.id === page.value) ?? navItems[0])
const currentOrganization = computed(() => organizations.value.find(item => item.id === organizationId.value) ?? null)
const searchResults = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  if (!query) return navItems
  return navItems.filter(item => `${item.label} ${item.description} ${item.keywords}`.toLocaleLowerCase().includes(query))
})

function resolveHash(): PageKey {
  const hash = window.location.hash.replace(/^#\/?/, '') as PageKey
  return navItems.some(item => item.id === hash) ? hash : 'dashboard'
}
function onHashChange() {
  page.value = resolveHash()
  sidebarOpen.value = false
  searchOpen.value = false
  userMenuOpen.value = false
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
function navigate(target: string) {
  if (!navItems.some(item => item.id === target)) return
  const safeTarget = target as PageKey
  if (window.location.hash !== `#${safeTarget}`) window.location.hash = safeTarget
  else onHashChange()
}
function selectOrganization(id: string) {
  organizationId.value = id
  if (id) sessionStorage.setItem('aiconnect.setup.organizationId', id)
  else sessionStorage.removeItem('aiconnect.setup.organizationId')
}
async function loadOrganizations(preferredId?: string) {
  loadingOrganizations.value = true
  shellMessage.value = ''
  try {
    organizations.value = await adminFetch<Organization[]>('/api/admin/organizations', auth.value)
    const requested = preferredId ?? organizationId.value
    if (requested && organizations.value.some(item => item.id === requested)) selectOrganization(requested)
    else if (organizations.value.length) selectOrganization(organizations.value[0].id)
    else selectOrganization('')
  } catch (error) {
    shellMessage.value = error instanceof Error ? error.message : '조직 목록을 불러오지 못했습니다.'
  } finally {
    loadingOrganizations.value = false
  }
}
function openSearch() {
  searchOpen.value = true
  nextTick(() => searchInput.value?.focus())
}
function chooseSearch(item: NavItem) {
  search.value = ''
  navigate(item.id)
}
function onGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === 'k') {
    event.preventDefault()
    openSearch()
  }
  if (event.key === 'Escape') {
    searchOpen.value = false
    userMenuOpen.value = false
    sidebarOpen.value = false
  }
}

onMounted(() => {
  page.value = resolveHash()
  if (!window.location.hash) window.history.replaceState(null, '', '#dashboard')
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('keydown', onGlobalKeydown)
  loadOrganizations()
})
onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <div class="console-shell">
    <button v-if="sidebarOpen" class="sidebar-scrim" aria-label="메뉴 닫기" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ open: sidebarOpen }">
      <div class="sidebar-brand">
        <span class="brand-mark"><i></i><i></i><i></i></span>
        <span><strong>AICONNECT</strong><small>LLM CONTROL PLANE</small></span>
      </div>

      <div class="scope-card">
        <span>WORKSPACE</span>
        <select :value="organizationId" :disabled="loadingOrganizations" @change="selectOrganization(($event.target as HTMLSelectElement).value)">
          <option value="">조직을 선택하세요</option>
          <option v-for="organization in organizations" :key="organization.id" :value="organization.id">
            {{ organization.name }} · {{ organization.status }}
          </option>
        </select>
        <small v-if="currentOrganization">{{ currentOrganization.name }} 리소스만 표시 중</small>
        <small v-else>프로젝트에서 조직을 생성할 수 있습니다.</small>
      </div>

      <nav class="side-nav" aria-label="주요 기능">
        <section v-for="group in groups" :key="group" class="nav-group">
          <p>{{ group }}</p>
          <button
            v-for="item in navItems.filter(nav => nav.group === group)"
            :key="item.id"
            :class="{ active: page === item.id }"
            @click="navigate(item.id)"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
            <i></i>
          </button>
        </section>
      </nav>

      <div class="sidebar-foot">
        <span class="live-dot"></span>
        <div><strong>Gateway online</strong><small>Tailscale private mesh</small></div>
      </div>
    </aside>

    <div class="console-stage">
      <header class="topbar">
        <div class="topbar-leading">
          <button class="icon-button mobile-menu" aria-label="메뉴 열기" @click="sidebarOpen = true">☰</button>
          <div class="breadcrumb"><span>AICONNECT</span><b>/</b><strong>{{ currentNav.label }}</strong></div>
        </div>
        <div class="topbar-actions">
          <button class="feature-search" @click="openSearch">
            <span>⌕</span><em>기능 검색</em><kbd>⌘ K</kbd>
          </button>
          <button class="icon-button" :aria-label="theme === 'dark' ? '라이트 모드' : '다크 모드'" @click="emit('toggleTheme')">
            {{ theme === 'dark' ? '☼' : '◐' }}
          </button>
          <div class="user-menu-wrap">
            <button class="user-chip" @click="userMenuOpen = !userMenuOpen">
              <span>{{ (user?.email ?? 'P').slice(0, 1).toUpperCase() }}</span>
              <span><strong>{{ user?.email ?? 'Platform token' }}</strong><small>{{ user?.platformAdmin || platformTokenSession ? 'Platform administrator' : 'Organization member' }}</small></span>
              <i>⌄</i>
            </button>
            <div v-if="userMenuOpen" class="user-popover">
              <div><strong>{{ user?.email ?? 'Emergency session' }}</strong><small>{{ platformTokenSession ? 'X-Admin-Token 인증' : '보안 세션 활성' }}</small></div>
              <button @click="emit('logout')">로그아웃</button>
            </div>
          </div>
        </div>
      </header>

      <main class="console-content">
        <div v-if="shellMessage" class="inline-alert">{{ shellMessage }} <button class="text-button" @click="loadOrganizations()">다시 시도</button></div>
        <div v-if="!organizationId && page !== 'projects'" class="scope-notice">
          <span>◇</span><div><strong>먼저 조직이 필요합니다.</strong><p>프로젝트 & API 키 화면에서 조직을 만들거나 접근 가능한 조직을 선택하세요.</p></div>
          <button class="secondary-button" @click="navigate('projects')">프로젝트로 이동</button>
        </div>
        <DashboardPage v-if="page === 'dashboard'" :organization-id="organizationId" :auth="auth" @navigate="navigate" />
        <InfrastructurePage v-else-if="page === 'infrastructure'" :organization-id="organizationId" :auth="auth" />
        <ServicesPage v-else-if="page === 'services'" :organization-id="organizationId" :auth="auth" />
        <ProjectsPage
          v-else-if="page === 'projects'"
          :organization-id="organizationId"
          :auth="auth"
          :platform-admin="Boolean(user?.platformAdmin || platformTokenSession)"
          @organizations-changed="loadOrganizations()"
          @organization-selected="loadOrganizations($event)"
        />
        <ObservabilityPage v-else-if="page === 'observability'" :organization-id="organizationId" :auth="auth" />
        <UsagePage v-else-if="page === 'usage'" />
        <NotificationsPage v-else-if="page === 'notifications'" :organization-id="organizationId" :auth="auth" />
      </main>
    </div>

    <div v-if="searchOpen" class="command-backdrop" @mousedown.self="searchOpen = false">
      <section class="command-palette" role="dialog" aria-modal="true" aria-label="기능 검색">
        <div class="command-input"><span>⌕</span><input ref="searchInput" v-model="search" placeholder="기능, 모델, API 키, 장애 검색..." /><kbd>ESC</kbd></div>
        <div class="command-results">
          <p>기능으로 이동</p>
          <button v-for="item in searchResults" :key="item.id" @click="chooseSearch(item)">
            <span class="nav-icon">{{ item.icon }}</span><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span><kbd>↵</kbd>
          </button>
          <div v-if="!searchResults.length" class="empty-command"><span>⌕</span><p>일치하는 기능이 없습니다.</p></div>
        </div>
      </section>
    </div>
  </div>
</template>
