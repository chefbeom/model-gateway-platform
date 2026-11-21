<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'

type RelationshipUser = {
  userId: string
  email: string
  accountTier?: string
  role: string
  roleLabel: string
  canIssueApiKeys: boolean
  currentUser?: boolean
}

type RelationshipProject = {
  id: string
  name: string
  status: string
  canIssueApiKeys: boolean
  managers: RelationshipUser[]
  members: RelationshipUser[]
}

type LegacyOrStructuredProject = Partial<RelationshipProject> & {
  id: string
  name: string
  status: string
  canIssueApiKeys: boolean
}

type RelationshipResponse = {
  organizationId?: string
  organizationName?: string | null
  topUsers?: RelationshipUser[]
  projects?: LegacyOrStructuredProject[]
}

const MAX_VISIBLE_TOP_USERS = 2
const MAX_VISIBLE_PROJECTS = 4
const MAX_VISIBLE_MANAGERS = 2
const MAX_VISIBLE_MEMBERS = 3

const props = defineProps<{
  organizationId: string
  organizationName?: string
  userName?: string
  roleLabel?: string
  auth: AdminAuth
  standalone?: boolean
}>()

const projects = ref<RelationshipProject[]>([])
const topUsers = ref<RelationshipUser[]>([])
const serverOrgName = ref('')
const state = ref<'loading' | 'ready' | 'error'>('loading')

const expandAll = ref(false)
const topExpanded = ref(false)
const projectsExpanded = ref(false)
const expandedManagers = ref<Record<string, boolean>>({})
const expandedMembers = ref<Record<string, boolean>>({})
const collapsedProjects = ref<Record<string, boolean>>({})

let sequence = 0

function normalizeProject(project: LegacyOrStructuredProject): RelationshipProject {
  return {
    id: project.id,
    name: project.name,
    status: project.status,
    canIssueApiKeys: Boolean(project.canIssueApiKeys),
    managers: Array.isArray(project.managers) ? project.managers : [],
    members: Array.isArray(project.members) ? project.members : []
  }
}

function resetExpansion() {
  expandAll.value = false
  topExpanded.value = false
  projectsExpanded.value = false
  expandedManagers.value = {}
  expandedMembers.value = {}
  collapsedProjects.value = {}
}

async function load() {
  const current = ++sequence
  projects.value = []
  topUsers.value = []
  serverOrgName.value = ''
  if (!props.organizationId) {
    state.value = 'ready'
    return
  }
  state.value = 'loading'
  try {
    const result = await adminFetch<RelationshipResponse | LegacyOrStructuredProject[]>(
      `/api/portal/organizations/${encodeURIComponent(props.organizationId)}/relationship`,
      props.auth
    )
    if (current !== sequence) return
    if (Array.isArray(result)) {
      projects.value = result.map(normalizeProject)
      topUsers.value = []
    } else {
      projects.value = Array.isArray(result.projects) ? result.projects.map(normalizeProject) : []
      topUsers.value = Array.isArray(result.topUsers) ? result.topUsers : []
      serverOrgName.value = result.organizationName ?? ''
    }
    state.value = 'ready'
  } catch {
    if (current === sequence) state.value = 'error'
  }
}

watch(() => props.organizationId, () => {
  resetExpansion()
})

watch(() => [props.organizationId, props.auth.accessToken, props.auth.platformToken], () => void load(), { immediate: true })

const displayTopUsers = computed<RelationshipUser[]>(() => {
  if (topUsers.value.length > 0) return topUsers.value
  return [{
    userId: 'current-user',
    email: props.userName || '로그인 계정',
    role: 'CURRENT',
    roleLabel: props.roleLabel || '시스템 관리자',
    canIssueApiKeys: true,
    currentUser: true
  }]
})

const hasAnyCompaction = computed(() =>
  displayTopUsers.value.length > MAX_VISIBLE_TOP_USERS
  || projects.value.length > MAX_VISIBLE_PROJECTS
  || projects.value.some(p => p.managers.length > MAX_VISIBLE_MANAGERS || p.members.length > MAX_VISIBLE_MEMBERS)
)

const visibleTopUsers = computed(() =>
  expandAll.value || topExpanded.value
    ? displayTopUsers.value
    : displayTopUsers.value.slice(0, MAX_VISIBLE_TOP_USERS)
)

const visibleProjects = computed(() =>
  expandAll.value || projectsExpanded.value
    ? projects.value
    : projects.value.slice(0, MAX_VISIBLE_PROJECTS)
)

const uniqueManagerCount = computed(() =>
  new Set(projects.value.flatMap(p => p.managers.map(m => m.userId))).size
)

const uniqueMemberCount = computed(() =>
  new Set(projects.value.flatMap(p => p.members.map(m => m.userId))).size
)

function visibleManagers(project: RelationshipProject): RelationshipUser[] {
  return expandAll.value || expandedManagers.value[project.id]
    ? project.managers
    : project.managers.slice(0, MAX_VISIBLE_MANAGERS)
}

function visibleMembers(project: RelationshipProject): RelationshipUser[] {
  return expandAll.value || expandedMembers.value[project.id]
    ? project.members
    : project.members.slice(0, MAX_VISIBLE_MEMBERS)
}

function isProjectCollapsed(projectId: string): boolean {
  return !expandAll.value && Boolean(collapsedProjects.value[projectId])
}

function toggleProjectCollapse(projectId: string) {
  collapsedProjects.value = {
    ...collapsedProjects.value,
    [projectId]: !collapsedProjects.value[projectId]
  }
}

function toggleExpandedManagers(projectId: string) {
  expandedManagers.value = {
    ...expandedManagers.value,
    [projectId]: !expandedManagers.value[projectId]
  }
}

function toggleExpandedMembers(projectId: string) {
  expandedMembers.value = {
    ...expandedMembers.value,
    [projectId]: !expandedMembers.value[projectId]
  }
}

function toggleExpandAll() {
  if (expandAll.value) {
    resetExpansion()
  } else {
    expandAll.value = true
  }
}
</script>

<template>
  <section class="relationship surface-card" :class="{ standalone }" aria-labelledby="relationship-title">
    <header class="relationship-header">
      <div>
        <span class="relationship-kicker">WORKSPACE RELATIONSHIP</span>
        <h2 id="relationship-title">내 조직 관계도</h2>
        <p>최고 등급 관리자부터 조직, 프로젝트, 프로젝트 총관리자 및 팀원까지의 소속과 지휘 체계를 트리 구조로 표시합니다.</p>
      </div>
      <div class="relationship-actions">
        <button
          v-if="hasAnyCompaction"
          type="button"
          class="secondary-button"
          @click="toggleExpandAll"
        >
          {{ expandAll ? '축약 보기' : '전체 펼치기' }}
        </button>
        <button type="button" class="secondary-button" :disabled="state === 'loading'" @click="load">
          새로고침
        </button>
      </div>
    </header>

    <div v-if="!organizationId" class="relationship-message">먼저 왼쪽에서 조직을 선택하세요.</div>
    <div v-else-if="state === 'loading'" class="relationship-message" role="status">관계도를 불러오는 중입니다.</div>
    <div v-else-if="state === 'error'" class="relationship-message" role="alert">관계도를 불러오지 못했습니다. 로그인 상태와 조직 접근 권한을 확인해 주세요.</div>
    <div v-else class="relationship-body">
      <div class="hierarchy-legend" aria-label="조직 계층 구조 요약">
        <div class="legend-step legend-top">
          <span>1단계 · 최고 등급</span>
          <strong>시스템·조직 관리자 {{ displayTopUsers.length }}명</strong>
        </div>
        <span class="legend-arrow" aria-hidden="true">→</span>
        <div class="legend-step legend-org">
          <span>2단계 · 조직</span>
          <strong>{{ organizationName || serverOrgName || '현재 조직' }}</strong>
        </div>
        <span class="legend-arrow" aria-hidden="true">→</span>
        <div class="legend-step legend-proj">
          <span>3단계 · 프로젝트</span>
          <strong>접근 가능한 프로젝트 {{ projects.length }}개</strong>
        </div>
        <span class="legend-arrow" aria-hidden="true">→</span>
        <div class="legend-step legend-mgr">
          <span>4단계 · 프로젝트 총관리자</span>
          <strong>프로젝트 총괄 {{ uniqueManagerCount }}명</strong>
        </div>
        <span class="legend-arrow" aria-hidden="true">→</span>
        <div class="legend-step legend-mem">
          <span>5단계 · 팀원</span>
          <strong>소속 구성원 {{ uniqueMemberCount }}명</strong>
        </div>
      </div>

      <div class="org-chart-scroll">
        <div class="org-chart-tree">
          <!-- 1단계: 최고 등급 · 상위 관리자 -->
          <div class="org-level top-level">
            <div class="org-node-card top-admin-card">
              <div class="node-card-head">
                <span class="stage-tag top-tag">1단계 · 최고 등급 (상위 관리자)</span>
                <span v-if="displayTopUsers.length > 1" class="tier-count">{{ displayTopUsers.length }}명</span>
              </div>
              <ul class="org-user-list">
                <li v-for="user in visibleTopUsers" :key="user.userId" class="org-user-pill">
                  <span class="pill-icon" aria-hidden="true">◎</span>
                  <strong>{{ user.email }}</strong>
                  <span class="role-chip">{{ user.roleLabel }}</span>
                  <b v-if="user.currentUser" class="current-user-badge">현재 계정</b>
                </li>
              </ul>
              <button
                v-if="displayTopUsers.length > MAX_VISIBLE_TOP_USERS && !expandAll"
                type="button"
                class="more-toggle"
                @click="topExpanded = !topExpanded"
              >
                {{ topExpanded ? '상위 관리자 접기' : `+ 외 ${displayTopUsers.length - MAX_VISIBLE_TOP_USERS}명 더보기` }}
              </button>
            </div>
          </div>

          <div class="org-vertical-stem" aria-hidden="true"></div>

          <!-- 2단계: 선택된 조직 -->
          <div class="org-level organization-level">
            <div class="org-node-card organization-card">
              <div class="node-card-head">
                <span class="stage-tag org-tag">2단계 · 선택된 조직 (WORKSPACE)</span>
              </div>
              <strong class="org-title">{{ organizationName || serverOrgName || '현재 조직' }}</strong>
              <span class="org-summary">
                접근 가능한 프로젝트 {{ projects.length }}개 · 프로젝트 총관리자 {{ uniqueManagerCount }}명 · 팀원 {{ uniqueMemberCount }}명
              </span>
            </div>
          </div>

          <!-- 3~5단계: 프로젝트 분기 -> 프로젝트 총관리자 -> 팀원 -->
          <template v-if="projects.length">
            <div class="org-vertical-stem" aria-hidden="true"></div>

            <ul class="org-tree-branches project-branches">
              <li v-for="item in visibleProjects" :key="item.id" class="org-branch-col project-col">
                <div class="org-node-card project-dept-card">
                  <div class="node-card-head">
                    <span class="stage-tag project-tag">3단계 · 프로젝트</span>
                    <span class="project-state" :class="{ paused: item.status !== 'ACTIVE' }">
                      {{ item.status === 'ACTIVE' ? '활성' : item.status === 'SUSPENDED' ? '중지' : item.status }}
                    </span>
                  </div>
                  <strong class="project-title">{{ item.name }}</strong>
                  <span class="project-perm">{{ item.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }}</span>
                  <div class="project-card-foot">
                    <small>총관리자 {{ item.managers.length }}명 · 팀원 {{ item.members.length }}명</small>
                    <button type="button" class="collapse-toggle" @click="toggleProjectCollapse(item.id)">
                      {{ isProjectCollapsed(item.id) ? `구성원 펼치기 (${item.managers.length + item.members.length}명)` : '구성원 접기' }}
                    </button>
                  </div>
                </div>

                <template v-if="!isProjectCollapsed(item.id)">
                  <div class="org-vertical-stem" aria-hidden="true"></div>

                  <!-- 4단계: 프로젝트 총관리자 -->
                  <div class="org-manager-section">
                    <div class="section-pill-header">
                      <span class="stage-tag manager-tag">4단계 · 프로젝트 총관리자</span>
                      <small>{{ item.managers.length }}명</small>
                    </div>
                    <template v-if="item.managers.length">
                      <ul class="org-tree-branches manager-branches">
                        <li v-for="manager in visibleManagers(item)" :key="manager.userId" class="org-branch-col manager-col">
                          <div class="org-person-box manager-box">
                            <span class="person-avatar manager-avatar" aria-hidden="true">◉</span>
                            <strong>{{ manager.email }}</strong>
                            <div class="person-badges">
                              <span class="role-chip">{{ manager.roleLabel || '프로젝트 총관리자' }}</span>
                              <span v-if="manager.currentUser" class="current-user-badge">현재 계정</span>
                            </div>
                            <small class="perm-label">{{ manager.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }}</small>
                          </div>
                        </li>
                      </ul>
                      <button
                        v-if="item.managers.length > MAX_VISIBLE_MANAGERS && !expandAll"
                        type="button"
                        class="more-toggle centered-toggle"
                        @click="toggleExpandedManagers(item.id)"
                      >
                        {{ expandedManagers[item.id] ? '총관리자 접기' : `+ 외 ${item.managers.length - MAX_VISIBLE_MANAGERS}명 총관리자 더보기` }}
                      </button>
                    </template>
                    <div v-else class="org-empty-box">
                      <p class="empty-tier-note">배정된 프로젝트 총관리자 없음</p>
                    </div>
                  </div>

                  <div class="org-vertical-stem" aria-hidden="true"></div>

                  <!-- 5단계: 팀원 -->
                  <div class="org-member-section">
                    <div class="section-pill-header">
                      <span class="stage-tag member-tag">5단계 · 팀원</span>
                      <small>{{ item.members.length }}명</small>
                    </div>
                    <template v-if="item.members.length">
                      <ul class="org-tree-branches member-branches">
                        <li v-for="member in visibleMembers(item)" :key="member.userId" class="org-branch-col member-col">
                          <div class="org-person-box member-box">
                            <span class="person-avatar member-avatar" aria-hidden="true">○</span>
                            <strong>{{ member.email }}</strong>
                            <div class="person-badges">
                              <span class="role-chip">{{ member.roleLabel || '팀원' }}</span>
                              <span v-if="member.currentUser" class="current-user-badge">현재 계정</span>
                            </div>
                            <small class="perm-label" :class="{ muted: !member.canIssueApiKeys }">
                              {{ member.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }}
                            </small>
                          </div>
                        </li>
                      </ul>
                      <button
                        v-if="item.members.length > MAX_VISIBLE_MEMBERS && !expandAll"
                        type="button"
                        class="more-toggle centered-toggle"
                        @click="toggleExpandedMembers(item.id)"
                      >
                        {{ expandedMembers[item.id] ? '팀원 접기' : `+ 외 ${item.members.length - MAX_VISIBLE_MEMBERS}명 팀원 더보기` }}
                      </button>
                    </template>
                    <div v-else class="org-empty-box">
                      <p class="empty-tier-note">소속 팀원 없음</p>
                    </div>
                  </div>
                </template>
              </li>
            </ul>

            <div v-if="projects.length > MAX_VISIBLE_PROJECTS && !expandAll" class="projects-more-wrap">
              <button
                type="button"
                class="more-projects-toggle"
                @click="projectsExpanded = !projectsExpanded"
              >
                {{ projectsExpanded ? '프로젝트 목록 접기' : `+ 외 ${projects.length - MAX_VISIBLE_PROJECTS}개 프로젝트 더보기 (전체 ${projects.length}개)` }}
              </button>
            </div>
          </template>
          <div v-else class="relationship-message empty-chart">이 조직에서 접근 가능한 프로젝트가 없습니다.</div>
        </div>
      </div>

      <p class="relationship-footnote">이 관계도는 현재 계정이 접근 가능한 범위 내의 최고 등급 관리자, 조직, 프로젝트 총관리자 및 팀원 구성을 표시합니다.</p>
    </div>
  </section>
</template>

<style scoped>
.relationship { overflow: hidden; }
.relationship.standalone { min-height: calc(100vh - 140px); }
.relationship-header { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:22px 24px; border-bottom:1px solid var(--border); }
.relationship-header h2 { margin:6px 0; font-size:18px; }
.relationship-header p, .relationship-footnote { margin:0; color:var(--muted); font-size:12px; line-height:1.6; }
.relationship-kicker { color:var(--accent-strong); font-size:10px; font-weight:800; letter-spacing:.12em; }
.relationship-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; flex:none; }
.relationship-body { padding:20px 24px 24px; display:grid; gap:20px; }

/* 5단계 범례 & 요약 바 */
.hierarchy-legend { display:flex; align-items:center; flex-wrap:wrap; gap:8px; padding:12px 14px; border:1px solid var(--border); border-radius:12px; background:var(--surface-2); }
.legend-step { display:grid; gap:2px; padding:6px 10px; border-radius:8px; border:1px solid var(--border); background:var(--surface); min-width:118px; }
.legend-step span { font-size:10px; font-weight:800; color:var(--muted); }
.legend-step strong { font-size:11px; color:var(--text); }
.legend-top { border-color:color-mix(in srgb, #f59e0b 45%, var(--border)); }
.legend-top span { color:#f59e0b; }
.legend-org { border-color:var(--accent-border); background:var(--accent-dim); }
.legend-org span { color:var(--accent-strong); }
.legend-proj { border-color:color-mix(in srgb, #38bdf8 40%, var(--border)); }
.legend-proj span { color:#38bdf8; }
.legend-mgr { border-color:var(--accent-border); }
.legend-mgr span { color:var(--accent-strong); }
.legend-arrow { color:var(--muted); font-weight:700; font-size:13px; }

/* 상하 분기 조직도 캔버스 */
.org-chart-scroll { overflow-x:auto; padding:12px 8px 16px; border:1px solid var(--border); border-radius:14px; background:radial-gradient(circle at top center, color-mix(in srgb, var(--accent-dim) 40%, transparent), transparent 65%), var(--surface-2); }
.org-chart-tree { display:flex; flex-direction:column; align-items:center; min-width:max-content; margin:0 auto; padding:8px 16px; }
.org-level { display:flex; justify-content:center; width:100%; }

.org-vertical-stem { width:2px; height:24px; background:var(--accent-border); margin:0 auto; flex:none; }

.org-node-card { display:grid; gap:8px; padding:14px 16px; border:1px solid var(--border); border-radius:12px; background:var(--surface); box-shadow:0 4px 16px rgba(0, 0, 0, 0.14); text-align:center; }
.top-admin-card { min-width:290px; max-width:540px; border-top:3px solid #f59e0b; }
.organization-card { min-width:300px; max-width:460px; border-color:var(--accent-border); border-top:3px solid var(--accent); background:linear-gradient(180deg, var(--accent-dim), var(--surface)); }
.project-dept-card { width:100%; min-width:250px; max-width:340px; border-top:3px solid #38bdf8; text-align:left; }

.node-card-head { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap; }
.top-admin-card .node-card-head, .organization-card .node-card-head { justify-content:center; }

.stage-tag { padding:2px 8px; border-radius:999px; font-size:10px; font-weight:800; letter-spacing:.02em; }
.top-tag { border:1px solid color-mix(in srgb, #f59e0b 45%, var(--border)); background:color-mix(in srgb, #f59e0b 14%, transparent); color:#f59e0b; }
.org-tag { border:1px solid var(--accent-border); background:var(--accent-dim); color:var(--accent-strong); }
.project-tag { border:1px solid color-mix(in srgb, #38bdf8 45%, var(--border)); background:color-mix(in srgb, #38bdf8 12%, transparent); color:#38bdf8; }
.manager-tag { border:1px solid var(--accent-border); background:var(--accent-dim); color:var(--accent-strong); }
.member-tag { border:1px solid var(--border); background:var(--surface); color:var(--muted); }

.tier-count { padding:2px 7px; border-radius:999px; border:1px solid var(--border); background:var(--surface-2); color:var(--muted); font-size:10px; font-weight:700; }
.org-user-list { list-style:none; margin:0; padding:0; display:flex; justify-content:center; flex-wrap:wrap; gap:8px; }
.org-user-pill { display:inline-flex; align-items:center; flex-wrap:wrap; gap:6px; padding:6px 10px; border:1px solid var(--border); border-radius:9px; background:var(--surface-2); }
.org-user-pill strong { font-size:13px; overflow-wrap:anywhere; }
.pill-icon { color:#f59e0b; font-size:12px; }

.org-title { font-size:16px; overflow-wrap:anywhere; }
.org-summary { color:var(--muted); font-size:11px; }

.project-title { font-size:15px; overflow-wrap:anywhere; }
.project-perm { color:var(--muted); font-size:11px; }
.project-card-foot { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-top:2px; padding-top:8px; border-top:1px solid var(--border); }
.project-card-foot small { color:var(--muted); font-size:11px; }
.project-state { flex:none; color:var(--accent-strong); font-size:11px; font-weight:700; }
.project-state.paused { color:var(--muted); }

.role-chip { padding:2px 7px; border-radius:999px; border:1px solid var(--border); background:var(--surface-2); color:var(--muted); font-size:10px; font-weight:700; }
.current-user-badge { padding:2px 7px; border-radius:999px; border:1px solid var(--accent-border); background:var(--accent-dim); color:var(--accent-strong); font-size:10px; font-weight:800; }

/* 정교한 직교 트리 연결선 */
.org-tree-branches { list-style:none; margin:0; padding:0; display:flex; justify-content:center; align-items:flex-start; }
.org-branch-col { position:relative; display:flex; flex-direction:column; align-items:center; padding:22px 12px 0; }
.org-branch-col::before, .org-branch-col::after { content:''; position:absolute; top:0; right:50%; width:50%; height:22px; border-top:2px solid var(--accent-border); pointer-events:none; }
.org-branch-col::after { right:auto; left:50%; border-left:2px solid var(--accent-border); }
.org-branch-col:first-child::before { border-top:none; }
.org-branch-col:last-child::after { border-top:none; }
.org-branch-col:only-child::before { display:none; }
.org-branch-col:only-child::after { border-top:none; }

.project-col { padding:22px 14px 0; }
.manager-col, .member-col { padding:18px 6px 0; }
.manager-col::before, .manager-col::after, .member-col::before, .member-col::after { height:18px; }

/* 4단계 총관리자 & 5단계 팀원 섹션 */
.org-manager-section, .org-member-section { display:flex; flex-direction:column; align-items:center; width:100%; }
.section-pill-header { display:inline-flex; align-items:center; gap:6px; padding:5px 10px; border:1px solid var(--border); border-radius:999px; background:var(--surface); margin-bottom:2px; }
.section-pill-header small { color:var(--muted); font-size:11px; font-weight:700; }

.manager-branches, .member-branches { position:relative; padding-top:16px; }
.manager-branches::before, .member-branches::before { content:''; position:absolute; top:0; left:50%; transform:translateX(-50%); width:2px; height:16px; background:var(--accent-border); }

.org-person-box { display:grid; justify-items:center; gap:5px; width:176px; padding:11px 10px; border:1px solid var(--border); border-radius:10px; background:var(--surface); text-align:center; }
.manager-box { border-color:var(--accent-border); background:color-mix(in srgb, var(--accent-dim) 45%, var(--surface)); }
.member-box { width:160px; }
.person-avatar { display:grid; place-items:center; width:26px; height:26px; border-radius:999px; font-size:12px; }
.manager-avatar { border:1px solid var(--accent-border); background:var(--accent-dim); color:var(--accent-strong); }
.member-avatar { border:1px solid var(--border); background:var(--surface-2); color:var(--muted); }
.org-person-box strong { font-size:12px; line-height:1.35; overflow-wrap:anywhere; }
.person-badges { display:flex; justify-content:center; flex-wrap:wrap; gap:4px; }
.perm-label { color:var(--accent-strong); font-size:10px; font-weight:700; }
.perm-label.muted { color:var(--muted); font-weight:500; }

.org-empty-box { margin-top:8px; padding:8px 14px; border:1px dashed var(--border); border-radius:9px; background:var(--surface); }
.empty-tier-note { margin:0; color:var(--muted); font-size:11px; }

.collapse-toggle { padding:4px 9px; border:1px solid var(--border); border-radius:7px; background:var(--surface-2); color:var(--muted); font-size:10px; font-weight:700; cursor:pointer; }
.collapse-toggle:hover { border-color:var(--accent-border); color:var(--text); }

.more-toggle, .more-projects-toggle { width:max-content; padding:5px 11px; border:1px dashed var(--accent-border); border-radius:8px; background:var(--surface); color:var(--accent-strong); font-size:11px; font-weight:700; cursor:pointer; }
.centered-toggle { margin-top:10px; }
.projects-more-wrap { display:flex; justify-content:center; margin-top:16px; }
.more-toggle:hover, .more-projects-toggle:hover { background:var(--accent-dim); }

.relationship-message { padding:24px; color:var(--muted); font-size:13px; line-height:1.6; }
.relationship-message.empty-chart { text-align:center; padding:24px 16px; }

@media (max-width:600px) {
  .relationship-header { flex-wrap:wrap; align-items:flex-start; padding:18px; }
  .relationship-body { padding:16px; }
  .legend-arrow { display:none; }
}
</style>
