<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import ConnectionRequests from './ConnectionRequests.vue'
import {
  accountTierLabel,
  adminFetch,
  registrationUrl,
  type AccountInvitationSummary,
  type AccountTier,
  type AdminAuth,
  type IssuedAccountInvitation
} from './api'
import { copyText } from './clipboard'
import { formatDateTime } from './dateTime'

const props = defineProps<{ organizationId: string; auth: AdminAuth; platformAdmin?: boolean }>()
type TeamRole = 'TEAM_ADMIN' | 'PROJECT_OWNER' | 'DEVELOPER' | 'AUDITOR'
type Team = { id: string; organizationId: string; name: string; status: string }
type Project = { id: string; name: string; status: string }
type OrganizationUser = { id: string; email: string; organizationRole: 'ORGANIZATION_ADMIN' | 'DEVELOPER'; accountTier?: AccountTier; totpEnabled?: boolean }
type TeamMember = { teamId: string; userId: string; role: TeamRole }
type KeyReference = { id: string; name: string; keyPrefix: string; status: string; projectId: string; projectName: string }
type TeamDeletionPreview = { teamId: string; teamName: string; members: { userId: string; email: string; role: TeamRole }[]; projects: { id: string; name: string; status: string; apiKeyCount: number }[]; apiKeys: KeyReference[]; behavior: string }
type UserRemovalPreview = { userId: string; email: string; organizationRole: string; teamMemberships: { teamId: string; teamName: string; role: TeamRole }[]; apiKeys: KeyReference[]; behavior: string }

const teams = ref<Team[]>([])
const projects = ref<Project[]>([])
const organizationUsers = ref<OrganizationUser[]>([])
const members = ref<TeamMember[]>([])
const accountInvitations = ref<AccountInvitationSummary[]>([])
const selectedId = ref('')
const busy = ref(false)
const message = ref('')

const teamOpen = ref(false)
const inviteOpen = ref(false)
const memberOpen = ref(false)
const memberRemoveOpen = ref(false)
const teamDeleteOpen = ref(false)
const userRemoveOpen = ref(false)

const teamName = ref('')
const memberUserId = ref('')
const memberRole = ref<TeamRole>('DEVELOPER')
const memberForRemoval = ref<{ userId: string; email: string; role: TeamRole } | null>(null)
const teamDeletionPreview = ref<TeamDeletionPreview | null>(null)
const userRemovalPreview = ref<UserRemovalPreview | null>(null)

const inviteForm = ref({
  targetTier: 'GENERAL_USER' as 'GENERAL_USER' | 'PROJECT_DIRECTOR',
  teamId: '',
  teamRole: 'DEVELOPER' as TeamRole,
  projectId: '',
  canIssueApiKeys: false,
  expectedEmail: '',
  expiresInHours: 72
})
const issuedInvitation = ref<IssuedAccountInvitation | null>(null)

const selected = computed(() => teams.value.find(team => team.id === selectedId.value) ?? null)
const usersById = computed(() => new Map(organizationUsers.value.map(user => [user.id, user])))
const teamNameById = computed(() => new Map(teams.value.map(team => [team.id, team.name])))
const projectNameById = computed(() => new Map(projects.value.map(project => [project.id, project.name])))
const memberViews = computed(() => members.value.map(member => ({
  ...member,
  email: usersById.value.get(member.userId)?.email ?? '알 수 없는 사용자',
  organizationRole: usersById.value.get(member.userId)?.organizationRole ?? 'DEVELOPER'
})))
const availableUsers = computed(() => organizationUsers.value.filter(user => !members.value.some(member => member.userId === user.id)))
const issuedLink = computed(() => issuedInvitation.value ? registrationUrl(issuedInvitation.value.registrationPath) : '')

const fail = (error: unknown, fallback: string) => {
  message.value = error instanceof Error ? error.message : fallback
}

function userTier(user: OrganizationUser): AccountTier {
  if (user.accountTier) return user.accountTier
  return user.organizationRole === 'ORGANIZATION_ADMIN' ? 'PROJECT_DIRECTOR' : 'GENERAL_USER'
}

function canResetUserTotp(user: OrganizationUser): boolean {
  if (props.platformAdmin) return true
  return userTier(user) === 'GENERAL_USER'
}

function invitationStatus(item: AccountInvitationSummary): 'PENDING' | 'CONSUMED' | 'REVOKED' | 'EXPIRED' {
  if (item.status === 'PENDING' || item.status === 'CONSUMED' || item.status === 'REVOKED' || item.status === 'EXPIRED') {
    return item.status
  }
  if (item.revokedAt) return 'REVOKED'
  if (item.consumedAt) return 'CONSUMED'
  if (item.expiresAt && new Date(item.expiresAt).getTime() <= Date.now()) return 'EXPIRED'
  return 'PENDING'
}

function invitationStatusLabel(status: string): string {
  if (status === 'PENDING') return '대기 중'
  if (status === 'CONSUMED') return '가입 완료'
  if (status === 'REVOKED') return '회수됨'
  if (status === 'EXPIRED') return '만료됨'
  return status
}

async function loadMembers() {
  if (!props.organizationId || !selectedId.value) {
    members.value = []
    return
  }
  members.value = await adminFetch<TeamMember[]>(`/api/admin/organizations/${props.organizationId}/teams/${selectedId.value}/members`, props.auth)
}

async function load() {
  if (!props.organizationId) {
    teams.value = []
    projects.value = []
    organizationUsers.value = []
    members.value = []
    accountInvitations.value = []
    return
  }
  busy.value = true
  try {
    const [teamItems, userItems, projectItems, invitationItems] = await Promise.all([
      props.platformAdmin ? adminFetch<Team[]>(`/api/admin/organizations/${props.organizationId}/teams`, props.auth) : Promise.resolve([] as Team[]),
      props.platformAdmin ? adminFetch<OrganizationUser[]>(`/api/admin/organizations/${props.organizationId}/users`, props.auth) : Promise.resolve([] as OrganizationUser[]),
      adminFetch<Project[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth),
      adminFetch<AccountInvitationSummary[]>(`/api/admin/organizations/${props.organizationId}/account-invitations`, props.auth)
    ])
    teams.value = teamItems
    organizationUsers.value = userItems
    projects.value = projectItems
    accountInvitations.value = invitationItems
    if (!teamItems.some(team => team.id === selectedId.value)) {
      selectedId.value = teamItems[0]?.id ?? ''
    }
    await loadMembers()
  } catch (error) {
    fail(error, '팀과 사용자 목록을 불러오지 못했습니다.')
  } finally {
    busy.value = false
  }
}

async function selectTeam(teamId: string) {
  selectedId.value = teamId
  try {
    await loadMembers()
  } catch (error) {
    fail(error, '팀 구성원을 불러오지 못했습니다.')
  }
}

async function createTeam() {
  if (!teamName.value.trim()) return
  busy.value = true
  try {
    const created = await adminFetch<Team>(`/api/admin/organizations/${props.organizationId}/teams`, props.auth, {
      method: 'POST',
      body: JSON.stringify({ name: teamName.value.trim() })
    })
    teams.value = [...teams.value, created].sort((a, b) => a.name.localeCompare(b.name))
    selectedId.value = created.id
    members.value = []
    teamName.value = ''
    teamOpen.value = false
    message.value = '팀 또는 부서를 만들었습니다. 이제 구성원과 프로젝트를 연결할 수 있습니다.'
  } catch (error) {
    fail(error, '팀 생성에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

function openInviteCreate() {
  issuedInvitation.value = null
  inviteForm.value = {
    targetTier: 'GENERAL_USER',
    teamId: selectedId.value,
    teamRole: 'DEVELOPER',
    projectId: projects.value[0]?.id ?? '',
    canIssueApiKeys: false,
    expectedEmail: '',
    expiresInHours: 72
  }
  inviteOpen.value = true
}

async function issueAccountInvitation() {
  if (!props.organizationId) return
  if (!inviteForm.value.projectId && (!props.platformAdmin || inviteForm.value.targetTier === 'PROJECT_DIRECTOR')) {
    message.value = '초대할 프로젝트를 선택하세요.'
    return
  }
  busy.value = true
  try {
    const targetTier = props.platformAdmin ? inviteForm.value.targetTier : 'GENERAL_USER'
    const payload = {
      targetTier,
      organizationId: props.organizationId,
      teamId: inviteForm.value.projectId ? null : (inviteForm.value.teamId || null),
      teamRole: !inviteForm.value.projectId && inviteForm.value.teamId ? inviteForm.value.teamRole : null,
      projectId: inviteForm.value.projectId || null,
      canIssueApiKeys: targetTier === 'PROJECT_DIRECTOR' ? true : Boolean(inviteForm.value.canIssueApiKeys),
      expectedEmail: inviteForm.value.expectedEmail.trim() || null,
      expiresInHours: Math.max(1, Math.min(720, Number(inviteForm.value.expiresInHours) || 72))
    }
    const created = await adminFetch<IssuedAccountInvitation>(
      `/api/admin/organizations/${props.organizationId}/account-invitations`,
      props.auth,
      { method: 'POST', body: JSON.stringify(payload) }
    )
    issuedInvitation.value = created
    inviteForm.value.expectedEmail = ''
    message.value = `${accountTierLabel(created.targetTier)} 생성 코드와 링크를 발급했습니다. 이 화면을 닫으면 다시 조회할 수 없습니다.`
    accountInvitations.value = await adminFetch<AccountInvitationSummary[]>(
      `/api/admin/organizations/${props.organizationId}/account-invitations`,
      props.auth
    )
  } catch (error) {
    fail(error, '계정 초대 코드 발급에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

async function copyInviteValue(value: string, label: string) {
  if (!value) return
  try {
    await copyText(value)
    message.value = `${label}를 클립보드에 복사했습니다.`
  } catch {
    message.value = `${label}를 복사하지 못했습니다. 표시된 값을 직접 선택해 복사하세요.`
  }
}

async function revokeInvitation(item: AccountInvitationSummary) {
  if (!window.confirm('이 계정 생성 초대 코드·링크를 회수하시겠습니까?')) return
  busy.value = true
  try {
    await adminFetch(
      `/api/admin/organizations/${props.organizationId}/account-invitations/${item.id}/revoke`,
      props.auth,
      { method: 'POST' }
    )
    accountInvitations.value = await adminFetch<AccountInvitationSummary[]>(
      `/api/admin/organizations/${props.organizationId}/account-invitations`,
      props.auth
    )
    message.value = '계정 생성 초대를 회수했습니다.'
  } catch (error) {
    fail(error, '초대 회수에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

async function resetUserTotp(user: OrganizationUser) {
  if (!canResetUserTotp(user)) {
    message.value = '상위 또는 동급 계정의 TOTP는 초기화할 수 없습니다.'
    return
  }
  if (!window.confirm(`${user.email} 계정의 Google Authenticator TOTP 등록과 복구 코드를 초기화하고 활성 세션을 종료할까요?`)) return
  busy.value = true
  try {
    await adminFetch(
      `/api/admin/organizations/${props.organizationId}/users/${user.id}/totp-reset`,
      props.auth,
      { method: 'POST' }
    )
    organizationUsers.value = await adminFetch<OrganizationUser[]>(
      `/api/admin/organizations/${props.organizationId}/users`,
      props.auth
    )
    message.value = `${user.email} 계정의 TOTP 인증을 초기화했습니다. 다음 로그인 시 재등록이 필요합니다.`
  } catch (error) {
    fail(error, 'TOTP 초기화에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

function openMemberAdd() {
  memberUserId.value = availableUsers.value[0]?.id ?? ''
  memberRole.value = 'DEVELOPER'
  memberOpen.value = true
}

async function addMember() {
  if (!selectedId.value || !memberUserId.value) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/organizations/${props.organizationId}/teams/${selectedId.value}/members`, props.auth, {
      method: 'POST',
      body: JSON.stringify({ userId: memberUserId.value, role: memberRole.value })
    })
    memberOpen.value = false
    await loadMembers()
    message.value = '팀 구성원을 배정했습니다.'
  } catch (error) {
    fail(error, '팀 구성원 배정에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

function openMemberRemoval(member: { userId: string; email: string; role: TeamRole }) {
  memberForRemoval.value = member
  memberRemoveOpen.value = true
}

async function removeMember() {
  if (!selectedId.value || !memberForRemoval.value) return
  busy.value = true
  try {
    const userId = memberForRemoval.value.userId
    await adminFetch(`/api/admin/organizations/${props.organizationId}/teams/${selectedId.value}/members/${userId}`, props.auth, {
      method: 'DELETE'
    })
    members.value = members.value.filter(member => member.userId !== userId)
    memberRemoveOpen.value = false
    memberForRemoval.value = null
    message.value = '사용자를 이 팀에서만 제거했습니다. 조직 계정과 API 키는 유지됩니다.'
  } catch (error) {
    fail(error, '팀 구성원 제거에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

async function openTeamDeletion() {
  if (!selectedId.value) return
  busy.value = true
  try {
    teamDeletionPreview.value = await adminFetch<TeamDeletionPreview>(
      `/api/admin/organizations/${props.organizationId}/teams/${selectedId.value}/deletion-preview`,
      props.auth
    )
    teamDeleteOpen.value = true
  } catch (error) {
    fail(error, '팀 삭제 영향을 불러오지 못했습니다.')
  } finally {
    busy.value = false
  }
}

async function deleteTeam() {
  if (!teamDeletionPreview.value) return
  busy.value = true
  try {
    const removedId = teamDeletionPreview.value.teamId
    await adminFetch(`/api/admin/organizations/${props.organizationId}/teams/${removedId}`, props.auth, { method: 'DELETE' })
    teamDeleteOpen.value = false
    teamDeletionPreview.value = null
    if (selectedId.value === removedId) selectedId.value = ''
    await load()
    message.value = '팀을 삭제했고, 연결된 프로젝트의 API 키 기록을 제거했습니다. 프로젝트는 중지 상태로 보존됩니다.'
  } catch (error) {
    fail(error, '팀 삭제에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

async function openUserRemoval(user: OrganizationUser) {
  busy.value = true
  try {
    userRemovalPreview.value = await adminFetch<UserRemovalPreview>(
      `/api/admin/organizations/${props.organizationId}/users/${user.id}/removal-preview`,
      props.auth
    )
    userRemoveOpen.value = true
  } catch (error) {
    fail(error, '사용자 제거 영향을 불러오지 못했습니다.')
  } finally {
    busy.value = false
  }
}

async function removeUser() {
  if (!userRemovalPreview.value) return
  busy.value = true
  try {
    const userId = userRemovalPreview.value.userId
    await adminFetch(`/api/admin/organizations/${props.organizationId}/users/${userId}`, props.auth, { method: 'DELETE' })
    userRemoveOpen.value = false
    userRemovalPreview.value = null
    organizationUsers.value = organizationUsers.value.filter(user => user.id !== userId)
    members.value = members.value.filter(member => member.userId !== userId)
    message.value = '사용자를 조직과 모든 팀에서 제거했고, 이 조직에서 해당 사용자가 발급한 API 키 기록도 제거했습니다.'
  } catch (error) {
    fail(error, '사용자 제거에 실패했습니다.')
  } finally {
    busy.value = false
  }
}

watch(() => props.organizationId, () => {
  void load()
})
onMounted(() => {
  void load()
})
</script>

<template>
  <section class="page-stack">
    <div class="page-hero">
      <div>
        <p class="eyebrow">ORGANIZATION ACCESS</p>
        <h1>팀 및 부서</h1>
        <p>부서별 구성원과 프로젝트를 관리합니다. 하위 기본 계정은 1회용 초대 코드·링크로 생성하고 TOTP 2차 인증을 관리합니다.</p>
      </div>
      <div class="hero-actions">
        <button class="secondary-button" :disabled="!organizationId" @click="openInviteCreate">+ 초대 코드/링크 발급</button>
        <button class="primary-button" :disabled="!organizationId" @click="teamOpen = true">+ 팀 생성</button>
      </div>
    </div>
    <p v-if="message" class="inline-alert">{{ message }}</p>

    <div class="team-layout">
      <article class="surface-card team-list-card">
        <header class="card-header"><div><span class="card-kicker">TEAM DIRECTORY</span><h2>팀과 부서</h2></div><span class="count-pill">{{ teams.length }}</span></header>
        <div v-if="teams.length" class="team-list">
          <button v-for="team in teams" :key="team.id" class="team-row" :class="{ selected: team.id === selectedId }" @click="selectTeam(team.id)">
            <span class="policy-icon">⌘</span>
            <span><b>{{ team.name }}</b><small>구성원 {{ team.id === selectedId ? members.length : '조회' }}명</small></span>
            <span class="status-chip tiny healthy">{{ team.status }}</span>
          </button>
        </div>
        <div v-else class="compact-empty"><b>등록된 팀이 없습니다.</b><small>먼저 팀 또는 부서를 만들고 구성원을 배정하세요.</small></div>
      </article>
      <article class="surface-card team-detail-card">
        <template v-if="selected">
          <header class="card-header team-detail-header">
            <div><span class="card-kicker">TEAM MEMBERSHIP</span><h2>{{ selected.name }}</h2><p>팀에서 제거하면 조직 계정은 유지됩니다. 조직에서 제거할 때만 관련 API 키가 함께 삭제됩니다.</p></div>
            <div class="hero-actions"><button class="secondary-button" :disabled="busy || !availableUsers.length" @click="openMemberAdd">+ 구성원 배정</button><button class="secondary-button danger-text" :disabled="busy" @click="openTeamDeletion">팀 삭제</button></div>
          </header>
          <div v-if="memberViews.length" class="data-table-wrap">
            <table class="data-table">
              <thead><tr><th>사용자</th><th>조직 역할</th><th>팀 역할</th><th>관리</th></tr></thead>
              <tbody>
                <tr v-for="member in memberViews" :key="member.userId">
                  <td><strong>{{ member.email }}</strong></td>
                  <td>{{ member.organizationRole }}</td>
                  <td><span class="role-chip">{{ member.role }}</span></td>
                  <td><button class="text-button danger-text" :disabled="busy" @click="openMemberRemoval(member)">팀에서 제거</button></td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="compact-empty"><b>배정된 구성원이 없습니다.</b><small>조직 사용자를 이 팀에 배정하면 프로젝트 범위와 권한을 나눌 수 있습니다.</small></div>
        </template>
        <div v-else class="empty-state"><span>⌘</span><h3>팀을 선택하세요</h3><p>선택한 팀의 구성원, 역할, 삭제 영향을 확인할 수 있습니다.</p></div>
      </article>
    </div>

    <article class="surface-card">
      <header class="card-header">
        <div>
          <span class="card-kicker">ACCOUNT INVITATIONS</span>
          <h2>하위 계정 초대 코드·링크 이력</h2>
          <p>초대 링크를 받은 사용자는 이메일·비밀번호 설정 후 Google Authenticator 2차 인증을 필수로 등록합니다.</p>
        </div>
        <div class="hero-actions">
          <span class="count-pill">{{ accountInvitations.length }}</span>
          <button type="button" class="secondary-button" :disabled="!organizationId" @click="openInviteCreate">+ 기본 계정 초대</button>
        </div>
      </header>
      <div v-if="accountInvitations.length" class="data-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>계층</th>
              <th>코드 접두어</th>
              <th>배정 팀 · 프로젝트</th>
              <th>지정 이메일</th>
              <th>상태</th>
              <th>만료 시각</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in accountInvitations" :key="item.id">
              <td><strong>{{ accountTierLabel(item.targetTier) }}</strong><small>{{ item.targetTier }}</small></td>
              <td><code>{{ item.codePrefix ?? item.id.slice(0, 8) }}</code></td>
              <td>
                <strong>{{ item.teamName ?? (item.teamId ? (teamNameById.get(item.teamId) ?? item.teamId) : '팀 미지정') }}</strong>
                <small>{{ item.projectName ?? (item.projectId ? (projectNameById.get(item.projectId) ?? item.projectId) : '프로젝트 미지정') }}{{ item.canIssueApiKeys ? ' · 키 발급 위임' : '' }}</small>
              </td>
              <td>{{ item.expectedEmail || '미지정' }}</td>
              <td>
                <span
                  class="status-chip tiny"
                  :class="invitationStatus(item) === 'CONSUMED' ? 'healthy' : invitationStatus(item) === 'PENDING' ? 'connected' : 'draining'"
                >
                  <i></i>{{ invitationStatusLabel(invitationStatus(item)) }}
                </span>
              </td>
              <td>{{ formatDateTime(item.expiresAt) }}</td>
              <td>
                <button
                  v-if="invitationStatus(item) === 'PENDING'"
                  type="button"
                  class="text-button danger-text"
                  :disabled="busy"
                  @click="revokeInvitation(item)"
                >
                  초대 회수
                </button>
                <span v-else class="muted-copy">-</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="compact-empty">
        <b>발급된 계정 초대가 없습니다.</b>
        <small>+ 초대 코드/링크 발급 버튼으로 하위 기본 계정 가입 링크를 생성하세요.</small>
      </div>
    </article>

    <article class="surface-card">
      <header class="card-header">
        <div>
          <span class="card-kicker">ORGANIZATION USERS</span>
          <h2>조직 사용자</h2>
          <p>계정 계층과 TOTP 2차 인증 상태를 확인하고, 기기 분실 시 하위 기본 계정의 TOTP를 초기화할 수 있습니다.</p>
        </div>
        <span class="count-pill">{{ organizationUsers.length }}</span>
      </header>
      <div v-if="organizationUsers.length" class="data-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>사용자</th>
              <th>계층</th>
              <th>조직 역할</th>
              <th>TOTP 2차 인증</th>
              <th>현재 팀</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="user in organizationUsers" :key="user.id">
              <td><strong>{{ user.email }}</strong></td>
              <td><span class="role-chip">{{ accountTierLabel(userTier(user)) }}</span></td>
              <td>{{ user.organizationRole }}</td>
              <td>
                <span class="status-chip tiny" :class="user.totpEnabled ? 'healthy' : 'draining'">
                  <i></i>{{ user.totpEnabled ? 'TOTP 활성' : '미등록' }}
                </span>
              </td>
              <td>{{ members.some(member => member.userId === user.id) ? selected?.name : '다른 팀 또는 미배정' }}</td>
              <td>
                <div class="table-actions">
                  <button
                    type="button"
                    class="text-button"
                    :disabled="busy || !canResetUserTotp(user)"
                    @click="resetUserTotp(user)"
                  >
                    TOTP 초기화
                  </button>
                  <button type="button" class="text-button danger-text" :disabled="busy" @click="openUserRemoval(user)">조직에서 제거</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="compact-empty"><b>조직 사용자가 없습니다.</b><small>초대 코드를 발급하거나 사용자를 추가한 후 팀 역할을 배정하세요.</small></div>
    </article>

    <ConnectionRequests v-if="!platformAdmin" :auth="auth" :organization-id="organizationId" :platform-admin="false" />

    <article class="surface-card role-guide-card"><header class="card-header"><div><span class="card-kicker">SAFE OFFBOARDING</span><h2>삭제 범위 안내</h2></div></header><div class="role-guide-grid"><article><b>팀에서 제거</b><small>선택한 팀 배정만 제거합니다. 조직 계정과 API 키는 유지됩니다.</small></article><article><b>조직에서 제거</b><small>모든 팀 배정을 해제하고, 이 조직에서 해당 사용자가 발급한 API 키 기록을 삭제합니다.</small></article><article><b>팀 삭제</b><small>소속 구성원을 팀에서 해제하고, 팀 프로젝트를 중지·조직 공용으로 전환하며 해당 프로젝트 API 키를 삭제합니다.</small></article></div></article>

    <BaseModal :open="inviteOpen" title="계정 생성 초대 코드/링크 발급" description="1회용 초대 코드와 /#register 가입 링크를 발급합니다. 가입자는 첫 로그인 전 Google Authenticator 2차 인증 등록을 완료해야 합니다." @close="inviteOpen = false">
      <div class="modal-form">
        <label v-if="platformAdmin" class="field">
          부여 계층
          <select v-model="inviteForm.targetTier">
            <option value="GENERAL_USER">기본 계정 (GENERAL_USER)</option>
            <option value="PROJECT_DIRECTOR">프로젝트 총괄 (PROJECT_DIRECTOR)</option>
          </select>
        </label>
        <div v-else class="impact-box">
          <b>부여 계층: 기본 계정 (GENERAL_USER)</b>
          <p>프로젝트 총괄은 현재 조직의 하위 기본 계정 초대 코드만 발급할 수 있습니다.</p>
        </div>

        <label v-if="platformAdmin && !inviteForm.projectId" class="field">
          소속 팀 (선택)
          <select v-model="inviteForm.teamId">
            <option value="">팀 미지정</option>
            <option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option>
          </select>
        </label>

        <label v-if="platformAdmin && !inviteForm.projectId && inviteForm.teamId" class="field">
          팀 역할
          <select v-model="inviteForm.teamRole">
            <option value="DEVELOPER">DEVELOPER</option>
            <option value="PROJECT_OWNER">PROJECT_OWNER</option>
            <option value="TEAM_ADMIN">TEAM_ADMIN</option>
            <option value="AUDITOR">AUDITOR</option>
          </select>
        </label>

        <label class="field">
          연결 프로젝트
          <select v-model="inviteForm.projectId">
            <option value="">프로젝트 미지정</option>
            <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.status }}</option>
          </select>
        </label>

        <label v-if="!platformAdmin || inviteForm.targetTier === 'GENERAL_USER'" class="toggle-field">
          <span>
            API 키 발급 권한 위임
            <small>체크하면 가입한 기본 계정이 선택한 프로젝트에서 직접 API 키를 발급할 수 있습니다.</small>
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

        <div v-if="issuedInvitation" role="status" class="secret-reveal invite-reveal-box">
          <span>1회용 초대 코드 및 가입 링크 (한 번만 표시 · 만료 {{ formatDateTime(issuedInvitation.expiresAt) }})</span>
          <div class="invite-reveal-row">
            <div>
              <small>초대 코드</small>
              <code>{{ issuedInvitation.code }}</code>
            </div>
            <button type="button" class="secondary-button" @click="copyInviteValue(issuedInvitation.code, '초대 코드')">코드 복사</button>
          </div>
          <div class="invite-reveal-row">
            <div>
              <small>가입 링크</small>
              <code>{{ issuedLink }}</code>
            </div>
            <button type="button" class="secondary-button" @click="copyInviteValue(issuedLink, '가입 링크')">링크 복사</button>
          </div>
        </div>
      </div>
      <template #footer>
        <button class="secondary-button" @click="inviteOpen = false">닫기</button>
        <button class="primary-button" :disabled="busy || !organizationId" @click="issueAccountInvitation">초대 코드/링크 발급</button>
      </template>
    </BaseModal>

    <BaseModal :open="teamOpen" title="새 팀 만들기" description="프로젝트 소유권과 구성원 권한을 나누는 회사 내부 팀 또는 부서를 등록합니다." size="sm" @close="teamOpen = false"><label class="field">팀 이름<input v-model.trim="teamName" maxlength="120" placeholder="AI Platform Team" @keyup.enter="createTeam" /></label><template #footer><button class="secondary-button" @click="teamOpen = false">취소</button><button class="primary-button" :disabled="busy || !teamName" @click="createTeam">팀 생성</button></template></BaseModal>
    <BaseModal :open="memberOpen" title="팀 구성원 배정" description="조직에 이미 등록된 사용자를 현재 팀에 연결합니다. 조직 권한은 바뀌지 않습니다." size="sm" @close="memberOpen = false"><div class="modal-form"><label class="field">사용자<select v-model="memberUserId"><option value="">사용자 선택</option><option v-for="user in availableUsers" :key="user.id" :value="user.id">{{ user.email }} · {{ user.organizationRole }}</option></select></label><label class="field">팀 역할<select v-model="memberRole"><option value="TEAM_ADMIN">Team Admin</option><option value="PROJECT_OWNER">Project Owner</option><option value="DEVELOPER">Developer</option><option value="AUDITOR">Auditor</option></select></label></div><template #footer><button class="secondary-button" @click="memberOpen = false">취소</button><button class="primary-button" :disabled="busy || !memberUserId" @click="addMember">구성원 배정</button></template></BaseModal>
    <BaseModal :open="memberRemoveOpen" title="팀 구성원 제거" description="이 작업은 선택한 팀 배정만 해제합니다." size="sm" @close="memberRemoveOpen = false"><div class="impact-box"><b>{{ memberForRemoval?.email }}</b><p>조직 계정, 다른 팀 배정, API 키는 유지됩니다.</p></div><template #footer><button class="secondary-button" @click="memberRemoveOpen = false">취소</button><button class="danger-button" :disabled="busy" @click="removeMember">팀에서 제거</button></template></BaseModal>
    <BaseModal :open="teamDeleteOpen" title="팀 및 부서 삭제" description="아래 영향 범위를 확인한 뒤에만 삭제를 진행하세요." size="lg" @close="teamDeleteOpen = false"><template v-if="teamDeletionPreview"><div class="impact-box danger"><b>{{ teamDeletionPreview.teamName }}</b><p>{{ teamDeletionPreview.behavior }}</p><div class="impact-counts"><span>구성원 {{ teamDeletionPreview.members.length }}명</span><span>프로젝트 {{ teamDeletionPreview.projects.length }}개</span><span>삭제할 API 키 {{ teamDeletionPreview.apiKeys.length }}개</span></div></div><section class="impact-section"><h3>영향받는 프로젝트</h3><div v-if="teamDeletionPreview.projects.length" class="impact-list"><div v-for="project in teamDeletionPreview.projects" :key="project.id"><b>{{ project.name }}</b><small>{{ project.status }} → 조직 공용 · SUSPENDED / API 키 {{ project.apiKeyCount }}개 제거</small></div></div><p v-else class="muted-copy">연결된 프로젝트가 없습니다.</p></section><section class="impact-section"><h3>삭제할 API 키 등록 위치</h3><div v-if="teamDeletionPreview.apiKeys.length" class="impact-list"><div v-for="key in teamDeletionPreview.apiKeys" :key="key.id"><b>{{ key.projectName }} · {{ key.name }}</b><small><code>{{ key.keyPrefix }}</code> · {{ key.status }}</small></div></div><p v-else class="muted-copy">삭제할 API 키가 없습니다.</p></section></template><template #footer><button class="secondary-button" @click="teamDeleteOpen = false">취소</button><button class="danger-button" :disabled="busy" @click="deleteTeam">팀 삭제 및 API 키 제거</button></template></BaseModal>
    <BaseModal :open="userRemoveOpen" title="조직 사용자 제거" description="계정 자체는 삭제하지 않지만, 이 조직에 대한 접근 권한과 발급 API 키는 제거됩니다." size="lg" @close="userRemoveOpen = false"><template v-if="userRemovalPreview"><div class="impact-box danger"><b>{{ userRemovalPreview.email }}</b><p>{{ userRemovalPreview.behavior }}</p><div class="impact-counts"><span>해제할 팀 배정 {{ userRemovalPreview.teamMemberships.length }}개</span><span>삭제할 API 키 {{ userRemovalPreview.apiKeys.length }}개</span></div></div><section class="impact-section"><h3>해제할 팀 배정</h3><div v-if="userRemovalPreview.teamMemberships.length" class="impact-list"><div v-for="membership in userRemovalPreview.teamMemberships" :key="membership.teamId"><b>{{ membership.teamName }}</b><small>{{ membership.role }}</small></div></div><p v-else class="muted-copy">연결된 팀이 없습니다.</p></section><section class="impact-section"><h3>삭제할 API 키 등록 위치</h3><div v-if="userRemovalPreview.apiKeys.length" class="impact-list"><div v-for="key in userRemovalPreview.apiKeys" :key="key.id"><b>{{ key.projectName }} · {{ key.name }}</b><small><code>{{ key.keyPrefix }}</code> · {{ key.status }}</small></div></div><p v-else class="muted-copy">이 사용자가 발급한 API 키가 없습니다.</p></section></template><template #footer><button class="secondary-button" @click="userRemoveOpen = false">취소</button><button class="danger-button" :disabled="busy" @click="removeUser">조직에서 제거 및 API 키 삭제</button></template></BaseModal>
  </section>
</template>

<style scoped>
.team-layout { display:grid; grid-template-columns:minmax(270px,.75fr) minmax(0,1.45fr); gap:18px; align-items:start }.team-list-card,.team-detail-card{min-height:0}.team-list{display:grid;gap:8px;padding:12px}.team-row{width:100%;padding:12px;display:grid;grid-template-columns:32px minmax(0,1fr) auto;gap:10px;align-items:center;border:1px solid transparent;border-radius:12px;background:transparent;color:inherit;text-align:left;cursor:pointer}.team-row:hover,.team-row.selected{border-color:var(--accent-border);background:var(--accent-dim)}.team-row b{display:block;font-size:13px}.team-row small{display:block;margin-top:3px;color:var(--muted);font-size:10px}.team-detail-header{align-items:start}.team-detail-header p,.card-header p{max-width:580px;margin:5px 0 0;color:var(--muted);font-size:11px;line-height:1.6}.policy-icon{width:31px;height:31px;display:grid;place-items:center;border:1px solid var(--accent-border);border-radius:9px;background:var(--surface-2);color:var(--accent-strong);font-weight:900}.compact-empty{min-height:140px;padding:28px;display:grid;place-content:center;gap:6px;color:var(--text-soft);text-align:center}.compact-empty small{color:var(--muted);font-size:11px}.role-chip{display:inline-flex;padding:4px 7px;border:1px solid var(--accent-border);border-radius:999px;background:var(--accent-dim);color:var(--accent-strong);font-size:10px;font-weight:800}.role-guide-card{padding-bottom:22px}.role-guide-grid{padding:0 21px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.role-guide-grid article{padding:14px;display:grid;gap:6px;border:1px solid var(--border);border-radius:12px;background:var(--surface-2)}.role-guide-grid b{font-size:12px}.role-guide-grid small{color:var(--muted);font-size:10px;line-height:1.55}.impact-box{padding:13px;display:grid;gap:6px;border:1px solid var(--border);border-radius:12px;background:var(--surface-2)}.impact-box.danger{border-color:color-mix(in srgb,var(--danger) 42%,var(--border));background:var(--danger-dim)}.impact-box b{font-size:14px}.impact-box p{margin:0;color:var(--text-soft);font-size:11px;line-height:1.6}.impact-counts{display:flex;flex-wrap:wrap;gap:7px}.impact-counts span{padding:4px 7px;border-radius:999px;background:var(--surface);color:var(--text-soft);font-size:10px}.impact-section{margin-top:18px}.impact-section h3{margin:0 0 8px;font-size:12px}.impact-list{display:grid;gap:7px;max-height:180px;overflow:auto}.impact-list>div{padding:10px 11px;display:grid;gap:3px;border:1px solid var(--border);border-radius:9px;background:var(--surface-2)}.impact-list b{font-size:11px}.impact-list small,.muted-copy{color:var(--muted);font-size:10px;line-height:1.5}.impact-list code{color:var(--accent-strong)}.muted-copy{margin:0}.danger-button{border:1px solid color-mix(in srgb,var(--danger) 45%,var(--border));border-radius:10px;background:var(--danger);color:white;padding:10px 13px;font-weight:800;cursor:pointer}.danger-button:disabled{opacity:.5;cursor:not-allowed}.table-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.invite-reveal-box{grid-template-columns:1fr}.invite-reveal-row{display:grid;grid-template-columns:1fr auto;gap:10px;align-items:center}.invite-reveal-row>div{min-width:0;display:grid;gap:4px}.invite-reveal-row small{color:var(--muted);font-size:10px;font-weight:700}.invite-reveal-row code{word-break:break-all}@media(max-width:900px){.team-layout{grid-template-columns:1fr}.team-list-card,.team-detail-card{min-height:auto}.role-guide-grid{grid-template-columns:1fr}}@media(max-width:620px){.team-detail-header{display:grid;gap:12px}.role-guide-grid{padding:0 14px}.impact-counts,.invite-reveal-row{display:grid;grid-template-columns:1fr}}
</style>
