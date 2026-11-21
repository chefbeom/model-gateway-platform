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
  <section class="relationship surface-card" aria-labelledby="relationship-title">
    <header class="relationship-header">
      <div>
        <span class="relationship-kicker">WORKSPACE RELATIONSHIP</span>
        <h2 id="relationship-title">내 조직 관계도</h2>
        <p>최고 등급 관리자부터 조직, 프로젝트, 프로젝트 총관리자 및 팀원 구성을 확인합니다.</p>
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
    <div v-else class="relationship-tree">
      <div class="tree-node top-tier-node">
        <span class="node-icon" aria-hidden="true">◎</span>
        <div class="top-tier-content">
          <div class="top-tier-heading">
            <small>최고 등급 · 상위 관리자</small>
            <span v-if="displayTopUsers.length > 1" class="tier-count">{{ displayTopUsers.length }}명</span>
          </div>
          <ul class="top-user-list">
            <li v-for="user in visibleTopUsers" :key="user.userId" class="top-user-chip">
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

      <div class="tree-connector" aria-hidden="true"></div>

      <div class="tree-node organization-node">
        <span class="node-icon" aria-hidden="true">▦</span>
        <div>
          <small>선택된 조직</small>
          <strong>{{ organizationName || serverOrgName || '현재 조직' }}</strong>
          <span>접근 가능한 프로젝트 {{ projects.length }}개 · 프로젝트 총관리자 {{ uniqueManagerCount }}명 · 팀원 {{ uniqueMemberCount }}명</span>
        </div>
      </div>

      <div v-if="projects.length" class="project-branch">
        <div class="branch-label">조직 소속 프로젝트 및 구성원</div>
        <ul class="project-list">
          <li v-for="item in visibleProjects" :key="item.id">
            <div class="project-card">
              <div class="project-node">
                <span class="project-icon" aria-hidden="true">◇</span>
                <div class="project-copy">
                  <strong>{{ item.name }}</strong>
                  <span>{{ item.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }} · 총관리자 {{ item.managers.length }}명 · 팀원 {{ item.members.length }}명</span>
                </div>
                <div class="project-meta">
                  <button type="button" class="collapse-toggle" @click="toggleProjectCollapse(item.id)">
                    {{ isProjectCollapsed(item.id) ? `구성원 펼치기 (${item.managers.length + item.members.length}명)` : '구성원 접기' }}
                  </button>
                  <span class="project-state" :class="{ paused: item.status !== 'ACTIVE' }">
                    {{ item.status === 'ACTIVE' ? '활성' : item.status === 'SUSPENDED' ? '중지' : item.status }}
                  </span>
                </div>
              </div>

              <div v-if="!isProjectCollapsed(item.id)" class="project-hierarchy">
                <div class="hierarchy-level manager-level">
                  <div class="level-header">
                    <span class="level-tag manager-tag">프로젝트 총관리자</span>
                    <small>{{ item.managers.length }}명</small>
                  </div>
                  <div v-if="item.managers.length" class="person-list-wrap">
                    <ul class="person-list">
                      <li v-for="manager in visibleManagers(item)" :key="manager.userId" class="person-card manager-card">
                        <div class="person-identity">
                          <strong>{{ manager.email }}</strong>
                          <span class="role-chip">{{ manager.roleLabel || '프로젝트 총관리자' }}</span>
                          <span v-if="manager.currentUser" class="current-user-badge">현재 계정</span>
                        </div>
                        <small class="perm-label">{{ manager.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }}</small>
                      </li>
                    </ul>
                    <button
                      v-if="item.managers.length > MAX_VISIBLE_MANAGERS && !expandAll"
                      type="button"
                      class="more-toggle"
                      @click="toggleExpandedManagers(item.id)"
                    >
                      {{ expandedManagers[item.id] ? '총관리자 접기' : `+ 외 ${item.managers.length - MAX_VISIBLE_MANAGERS}명 총관리자 더보기` }}
                    </button>
                  </div>
                  <p v-else class="empty-tier-note">배정된 프로젝트 총관리자 없음</p>

                  <div class="hierarchy-level member-level">
                    <div class="level-header">
                      <span class="level-tag member-tag">팀원</span>
                      <small>{{ item.members.length }}명</small>
                    </div>
                    <div v-if="item.members.length" class="person-list-wrap">
                      <ul class="person-list">
                        <li v-for="member in visibleMembers(item)" :key="member.userId" class="person-card member-card">
                          <div class="person-identity">
                            <strong>{{ member.email }}</strong>
                            <span class="role-chip">{{ member.roleLabel || '팀원' }}</span>
                            <span v-if="member.currentUser" class="current-user-badge">현재 계정</span>
                          </div>
                          <small class="perm-label" :class="{ muted: !member.canIssueApiKeys }">
                            {{ member.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }}
                          </small>
                        </li>
                      </ul>
                      <button
                        v-if="item.members.length > MAX_VISIBLE_MEMBERS && !expandAll"
                        type="button"
                        class="more-toggle"
                        @click="toggleExpandedMembers(item.id)"
                      >
                        {{ expandedMembers[item.id] ? '팀원 접기' : `+ 외 ${item.members.length - MAX_VISIBLE_MEMBERS}명 팀원 더보기` }}
                      </button>
                    </div>
                    <p v-else class="empty-tier-note">소속 팀원 없음</p>
                  </div>
                </div>
              </div>
            </div>
          </li>
        </ul>

        <button
          v-if="projects.length > MAX_VISIBLE_PROJECTS && !expandAll"
          type="button"
          class="more-projects-toggle"
          @click="projectsExpanded = !projectsExpanded"
        >
          {{ projectsExpanded ? '프로젝트 목록 접기' : `+ 외 ${projects.length - MAX_VISIBLE_PROJECTS}개 프로젝트 더보기 (전체 ${projects.length}개)` }}
        </button>
      </div>
      <div v-else class="relationship-message empty">이 조직에서 접근 가능한 프로젝트가 없습니다.</div>

      <p class="relationship-footnote">이 관계도는 현재 계정이 접근 가능한 범위 내의 최고 등급 관리자, 조직, 프로젝트 총관리자 및 팀원 구성을 표시합니다.</p>
    </div>
  </section>
</template>

<style scoped>
.relationship { overflow: hidden; }
.relationship-header { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:22px 24px; border-bottom:1px solid var(--border); }
.relationship-header h2 { margin:6px 0; font-size:18px; }
.relationship-header p, .relationship-footnote { margin:0; color:var(--muted); font-size:12px; line-height:1.6; }
.relationship-kicker { color:var(--accent-strong); font-size:10px; font-weight:800; letter-spacing:.12em; }
.relationship-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; flex:none; }
.relationship-tree { padding:24px; }

.tree-node { display:flex; align-items:flex-start; gap:14px; max-width:720px; padding:15px; border:1px solid var(--border); border-radius:12px; background:var(--surface-2); }
.top-tier-node { border-color:var(--border); }
.organization-node { align-items:center; border-color:var(--accent-border); background:var(--accent-dim); }
.node-icon, .project-icon { display:grid; flex:none; place-items:center; width:38px; height:38px; border:1px solid var(--accent-border); border-radius:10px; color:var(--accent-strong); }
.tree-node > div, .project-copy { display:grid; min-width:0; gap:4px; flex:1; }
.tree-node small, .tree-node span:not(.node-icon):not(.tier-count):not(.role-chip), .project-copy span { color:var(--muted); font-size:11px; }
.tree-node strong, .project-copy strong { overflow-wrap:anywhere; font-size:14px; }

.top-tier-content { display:grid; gap:8px; min-width:0; flex:1; }
.top-tier-heading { display:flex; align-items:center; gap:8px; }
.tier-count { padding:2px 7px; border-radius:999px; border:1px solid var(--border); background:var(--surface); color:var(--muted); font-size:10px; font-weight:700; }
.top-user-list { list-style:none; margin:0; padding:0; display:flex; flex-wrap:wrap; gap:8px; }
.top-user-chip { display:inline-flex; align-items:center; flex-wrap:wrap; gap:7px; padding:6px 10px; border:1px solid var(--border); border-radius:9px; background:var(--surface); }
.top-user-chip strong { font-size:13px; }

.role-chip { padding:2px 7px; border-radius:999px; border:1px solid var(--border); background:var(--surface-2); color:var(--muted); font-size:10px; font-weight:700; }
.current-user-badge { padding:2px 7px; border-radius:999px; border:1px solid var(--accent-border); background:var(--accent-dim); color:var(--accent-strong); font-size:10px; font-weight:800; }

.tree-connector { height:22px; margin-left:34px; border-left:1px solid var(--accent-border); }
.project-branch { margin-left:34px; padding:18px 0 0 24px; border-left:1px solid var(--accent-border); }
.branch-label { color:var(--muted); font-size:11px; font-weight:700; margin-bottom:12px; }
.project-list { list-style:none; padding:0; margin:0; display:grid; gap:12px; }
.project-list > li { position:relative; }
.project-list > li::before { content:''; position:absolute; width:24px; top:28px; left:-24px; border-top:1px solid var(--accent-border); }

.project-card { border:1px solid var(--border); border-radius:12px; background:var(--surface-2); overflow:hidden; }
.project-node { display:flex; align-items:center; gap:12px; padding:13px 15px; }
.project-meta { display:flex; align-items:center; gap:10px; flex:none; }
.collapse-toggle { padding:5px 10px; border:1px solid var(--border); border-radius:8px; background:var(--surface); color:var(--muted); font-size:11px; font-weight:600; cursor:pointer; }
.collapse-toggle:hover { border-color:var(--accent-border); color:var(--text); }
.project-state { flex:none; color:var(--accent-strong); font-size:11px; font-weight:700; }
.project-state.paused { color:var(--muted); }

.project-hierarchy { padding:4px 15px 15px 28px; border-top:1px solid var(--border); background:color-mix(in srgb, var(--surface) 55%, transparent); }
.hierarchy-level { position:relative; margin-top:12px; padding-left:18px; border-left:1px solid var(--accent-border); }
.member-level { margin-top:12px; margin-left:8px; }
.level-header { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
.level-header small { color:var(--muted); font-size:11px; font-weight:600; }
.level-tag { padding:2px 8px; border-radius:999px; font-size:10px; font-weight:800; letter-spacing:.02em; }
.manager-tag { border:1px solid var(--accent-border); background:var(--accent-dim); color:var(--accent-strong); }
.member-tag { border:1px solid var(--border); background:var(--surface); color:var(--muted); }

.person-list-wrap { display:grid; gap:8px; }
.person-list { list-style:none; margin:0; padding:0; display:grid; gap:7px; }
.person-card { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px 14px; padding:9px 12px; border:1px solid var(--border); border-radius:9px; background:var(--surface); }
.manager-card { border-color:color-mix(in srgb, var(--accent-border) 65%, var(--border)); }
.person-identity { display:flex; align-items:center; flex-wrap:wrap; gap:7px; min-width:0; }
.person-identity strong { font-size:12px; overflow-wrap:anywhere; }
.perm-label { color:var(--accent-strong); font-size:11px; font-weight:600; }
.perm-label.muted { color:var(--muted); font-weight:500; }
.empty-tier-note { margin:0; padding:4px 0; color:var(--muted); font-size:11px; }

.more-toggle, .more-projects-toggle { width:max-content; padding:5px 11px; border:1px dashed var(--accent-border); border-radius:8px; background:transparent; color:var(--accent-strong); font-size:11px; font-weight:700; cursor:pointer; }
.more-projects-toggle { margin-top:12px; }
.more-toggle:hover, .more-projects-toggle:hover { background:var(--accent-dim); }

.relationship-message { padding:24px; color:var(--muted); font-size:13px; line-height:1.6; }
.relationship-message.empty { margin-left:34px; border-left:1px solid var(--accent-border); }
.relationship-footnote { margin-top:18px; }

@media (max-width:600px) {
  .relationship-header { flex-wrap:wrap; align-items:flex-start; padding:18px; }
  .relationship-tree { padding:18px; }
  .project-branch { margin-left:18px; padding-left:16px; }
  .project-list > li::before { width:16px; left:-16px; }
  .tree-connector { margin-left:18px; }
  .project-node { flex-wrap:wrap; }
  .project-meta { margin-left:auto; }
  .project-hierarchy { padding-left:16px; }
}
</style>
