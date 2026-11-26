// Console sidebar information architecture. Pure data and functions only:
// no Vue, DOM or storage access, so node --test can import it directly.

export type NavPageId =
  | 'beta' | 'dashboard' | 'relationship' | 'infrastructure' | 'system' | 'external' | 'services'
  | 'flow-designer' | 'teams' | 'projects' | 'observability' | 'usage' | 'quotas' | 'data-protection'
  | 'notifications' | 'audit' | 'platform' | 'portal' | 'playground' | 'model-playground'
export type NavRole = 'admin' | 'director' | 'developer'
export type NavCategoryId = 'models' | 'access' | 'observe' | 'devtools' | 'platform'
export type NavExpansionMode = 'single' | 'multi'
export type NavItem = { id: NavPageId; label: string; description: string; keywords: string; icon: string }
export type NavCategory = { id: NavCategoryId; label: string; icon: string; items: NavItem[] }
export type NavSections = { pinned: NavItem[]; categories: NavCategory[] }
export type NavRoleInput = { role: NavRole; platformAdmin: boolean; betaOperator: boolean }
export type SearchableNavItem = { id: string; label: string; description: string; keywords: string }

/** Sidebar category order. Empty categories are omitted per role. */
export const NAV_CATEGORIES: ReadonlyArray<{ id: NavCategoryId; label: string; icon: string }> = [
  { id: 'models', label: '모델·라우팅', icon: '▣' },
  { id: 'access', label: '조직·보안', icon: '◈' },
  { id: 'observe', label: '관측·비용', icon: '◉' },
  { id: 'devtools', label: '개발자 도구', icon: '▷' },
  { id: 'platform', label: '플랫폼 운영', icon: '⚡' }
]

/** One category per page for every role. null = pinned above the categories. */
export const PAGE_CATEGORY: Readonly<Record<NavPageId, NavCategoryId | null>> = {
  dashboard: null,
  infrastructure: 'models',
  external: 'models',
  services: 'models',
  'flow-designer': 'models',
  system: 'models',
  projects: 'access',
  teams: 'access',
  relationship: 'access',
  'data-protection': 'access',
  audit: 'access',
  observability: 'observe',
  usage: 'observe',
  quotas: 'observe',
  notifications: 'observe',
  portal: 'devtools',
  playground: 'devtools',
  'model-playground': 'devtools',
  platform: 'platform',
  beta: 'platform'
}

// Item arrays are written in sidebar order (category order, then item order),
// so the command palette lists results in the same order as the sidebar.
export const adminNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '요청·장애·런타임 핵심 지표', keywords: 'overview health dashboard', icon: '◔' },
  { id: 'infrastructure', label: '인프라스트럭처', description: '노드·Runtime·배포 모델', keywords: 'server gpu lm studio endpoint model', icon: '◇' },
  { id: 'external', label: '외부 AI', description: 'OpenAI·승인·수동·자동 Failover', keywords: 'openai external provider cloud approval failover', icon: '◎' },
  { id: 'services', label: 'LLM 서비스', description: '논리 모델과 라우팅 정책', keywords: 'service model failover routing', icon: '▣' },
  { id: 'flow-designer', label: '워크플로우 디자이너', description: '노드와 간선으로 서비스 워크플로우 구성 · 실험', keywords: 'workflow designer flow canvas node edge graph pipeline routing 워크플로우 노드 간선 플로우 디자이너 배치 캔버스 실험 experimental', icon: '⌬' },
  { id: 'system', label: '시스템 구조', description: '요청 경로 · Target · 서버 연결 상태', keywords: '시스템 구조 연결도 상태 architecture structure topology infrastructure provider llm team project api key', icon: '~' },
  { id: 'projects', label: '프로젝트', description: '프로젝트·권한·API 키', keywords: 'project key quota credential', icon: '⌘' },
  { id: 'teams', label: '팀과 부서', description: '부서·역할·프로젝트 소유', keywords: 'team department role audit', icon: '◫' },
  { id: 'relationship', label: '조직 관계도', description: '최고 관리자·조직·프로젝트·구성원 계층도', keywords: 'organization relationship hierarchy chart tree member manager 조직 관계도 계층도', icon: '◈' },
  { id: 'data-protection', label: '데이터 보호', description: '민감정보 탐지·외부 전송 정책', keywords: 'security privacy dlp pii secret local only external block', icon: '◇' },
  { id: 'audit', label: '감사 로그', description: '관리자 변경·열람 증적', keywords: 'audit log admin change security', icon: '◎' },
  { id: 'observability', label: '관측성', description: '요청·장애·Failover 추적', keywords: 'request incident failover monitor', icon: '◉' },
  { id: 'usage', label: '사용량', description: '토큰·비용·API 호출 이력', keywords: 'usage token cost billing', icon: '◴' },
  { id: 'quotas', label: '요금·한도', description: '범위별 예산과 초과 차단', keywords: 'budget quota limit spend cost', icon: '₩' },
  { id: 'notifications', label: '알림 채널', description: 'Discord와 Telegram 연동', keywords: 'notification discord telegram alert', icon: '◇' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷' },
  { id: 'model-playground', label: '모델 테스트 콘솔', description: '등록된 Runtime·Provider 모델 직접 대화 테스트', keywords: 'model playground chat runtime provider streaming file test', icon: '◈' }
]
export const directorNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '접근 범위 요약', keywords: 'dashboard overview', icon: '◔' },
  { id: 'projects', label: '프로젝트', description: '프로젝트·멤버·권한·API 키', keywords: 'project key member delegation policy', icon: '⌘' },
  { id: 'relationship', label: '조직 관계도', description: '최고 관리자·조직·프로젝트·구성원 계층도', keywords: 'organization relationship hierarchy chart tree member manager 조직 관계도 계층도', icon: '◈' },
  { id: 'data-protection', label: '데이터 보호', description: '프로젝트·API 키 민감정보 정책', keywords: 'security privacy dlp pii secret local only', icon: '◇' },
  { id: 'observability', label: '관측성', description: '접근 범위 요청 이력', keywords: 'request monitor', icon: '◉' },
  { id: 'usage', label: 'API 사용량', description: '프로젝트·API 키 토큰·비용 이력', keywords: 'usage token cost request', icon: '◴' },
  { id: 'quotas', label: '요금·한도', description: '프로젝트 예산과 관리자 상한', keywords: 'budget quota limit spend cost ceiling', icon: '₩' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷' }
]
export const developerNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '접근 범위 요약', keywords: 'dashboard overview', icon: '◔' },
  { id: 'relationship', label: '조직 관계도', description: '최고 관리자·조직·프로젝트·구성원 계층도', keywords: 'organization relationship hierarchy chart tree member manager 조직 관계도 계층도', icon: '◈' },
  { id: 'observability', label: '관측성', description: '내 API 키 요청 이력', keywords: 'request monitor', icon: '◉' },
  { id: 'usage', label: 'API 사용량', description: '내 API 키의 토큰·비용·요청', keywords: 'usage token cost request', icon: '◴' },
  { id: 'quotas', label: '요금·한도', description: '내 API 키 추정 비용', keywords: 'budget quota cost', icon: '₩' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷' }
]
export const platformNavItem: NavItem = { id: 'platform', label: 'Platform Admin', description: '조직·사용자·키·데이터 정리', keywords: 'platform admin organization user api key cleanup purge', icon: '⚡' }
export const betaNavItem: NavItem = { id: 'beta', label: '초대 서비스 운영', description: '초대·접근·운영 상태', keywords: 'beta invitation invite', icon: '◇' }
export const docsNavItem = { id: 'docs' as const, label: 'Dev-Docs', description: '제품 가이드 · API 참조', keywords: 'docs manual guide api lm studio' }

/** Role filtering. Keeps the pre-redesign access rules exactly. */
export function navItemsFor(input: NavRoleInput): NavItem[] {
  const base = input.role === 'admin'
    ? (input.platformAdmin ? [...adminNavItems, platformNavItem] : adminNavItems)
    : input.role === 'director' ? directorNavItems : developerNavItems
  return [...base, ...(input.platformAdmin || input.betaOperator ? [betaNavItem] : [])]
}

export function categoryOf(id: string): NavCategoryId | null {
  return Object.prototype.hasOwnProperty.call(PAGE_CATEGORY, id) ? PAGE_CATEGORY[id as NavPageId] : null
}

/** 'dashboard' → '홈', 'docs' → '도움말', otherwise the category label ('' when unknown). */
export function navCategoryLabel(id: string): string {
  if (id === 'docs') return '도움말'
  if (id === 'dashboard') return '홈'
  const category = categoryOf(id)
  return NAV_CATEGORIES.find(entry => entry.id === category)?.label ?? ''
}

export function buildNavSections(items: readonly NavItem[]): NavSections {
  return {
    pinned: items.filter(item => categoryOf(item.id) === null),
    categories: NAV_CATEGORIES
      .map(category => ({ ...category, items: items.filter(item => categoryOf(item.id) === category.id) }))
      .filter(category => category.items.length > 0)
  }
}

/** Admins see ~20 pages → accordion. Directors/developers see ≤10 → everything open. */
export function navExpansionMode(role: NavRole): NavExpansionMode {
  return role === 'admin' ? 'single' : 'multi'
}

export function initialExpanded(sections: NavSections, activeId: string, mode: NavExpansionMode): NavCategoryId[] {
  if (mode === 'multi') return sections.categories.map(category => category.id)
  const active = categoryOf(activeId)
  return active && sections.categories.some(category => category.id === active) ? [active] : []
}

export function toggleExpanded(expanded: NavCategoryId[], id: NavCategoryId, mode: NavExpansionMode): NavCategoryId[] {
  if (expanded.includes(id)) return expanded.filter(entry => entry !== id)
  return mode === 'single' ? [id] : [...expanded, id]
}

/** Opens the active page's category (sidebar click, palette, hash/deep link). Never closes on its own in multi mode. */
export function revealActive(expanded: NavCategoryId[], activeId: string, mode: NavExpansionMode): NavCategoryId[] {
  const active = categoryOf(activeId)
  if (!active || expanded.includes(active)) return expanded
  return mode === 'single' ? [active] : [...expanded, active]
}

/** Command palette search. Independent of sidebar collapse state; also matches the category label. */
export function searchNavItems<T extends SearchableNavItem>(items: readonly T[], query: string): T[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return [...items]
  return items.filter(item => `${item.label} ${item.description} ${item.keywords} ${navCategoryLabel(item.id)}`.toLowerCase().includes(needle))
}
