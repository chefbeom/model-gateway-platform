<script setup lang="ts">
import { onMounted, ref } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

type Props = { auth: AdminAuth }
type Overview = { organizationCount:number; userCount:number; projectCount:number; providerCount:number; nodeCount:number; endpointCount:number; deploymentCount:number; serviceCount:number; apiKeyCount:number; requestCount:number }
type Organization = { id:string; name:string; status:string; projectCount:number; providerCount:number; nodeCount:number; memberCount:number }
type CleanupPreview = { organizationId:string; name:string; status:string; projectCount:number; providerCount:number; nodeCount:number; endpointCount:number; memberCount:number; requestCount:number; behavior:string }
type PlatformUser = { id:string; email:string; platformAdmin:boolean; enabled:boolean; organizationCount:number; issuedApiKeyCount:number }
type PlatformTeam = { id:string; organizationId:string; organizationName:string; name:string; status:string; projectCount:number }
type PlatformApiKey = { id:string; projectId:string; projectName:string; organizationName:string; name:string; keyPrefix:string; status:string; expiresAt?:string; lastUsedAt?:string; createdAt:string }

const props = defineProps<Props>()
const overview = ref<Overview | null>(null)
const organizations = ref<Organization[]>([])
const users = ref<PlatformUser[]>([])
const teams = ref<PlatformTeam[]>([])
const apiKeys = ref<PlatformApiKey[]>([])
const busy = ref(false)
const message = ref('')
const cleanupOpen = ref(false)
const deleteOpen = ref(false)
const userDeleteOpen = ref(false)
const teamDeleteOpen = ref(false)
const cleanup = ref<CleanupPreview | null>(null)
const orgConfirmation = ref('')
const userConfirmation = ref('')
const selectedUser = ref<PlatformUser | null>(null)
const selectedTeam = ref<PlatformTeam | null>(null)
const teamConfirmation = ref('')

function showError(error: unknown, fallback: string) { message.value = error instanceof Error ? error.message : fallback }
function date(value?: string) { return value ? new Date(value).toLocaleString() : '-' }
function metric(value?: number) { return (value ?? 0).toLocaleString() }
async function load() {
  busy.value = true
  try {
    const [overviewValue, organizationItems, userItems, teamItems, keyItems] = await Promise.all([
      adminFetch<Overview>('/api/admin/platform/overview', props.auth),
      adminFetch<Organization[]>('/api/admin/platform/organizations', props.auth),
      adminFetch<PlatformUser[]>('/api/admin/platform/users', props.auth),
      adminFetch<PlatformTeam[]>('/api/admin/platform/teams', props.auth),
      adminFetch<PlatformApiKey[]>('/api/admin/platform/api-keys', props.auth)
    ])
    overview.value = overviewValue
    organizations.value = organizationItems
    users.value = userItems
    teams.value = teamItems
    apiKeys.value = keyItems
  } catch (error) { showError(error, '플랫폼 관리자 정보를 불러오지 못했습니다.') }
  finally { busy.value = false }
}
async function setOrganizationStatus(item: Organization) {
  busy.value = true
  try {
    await adminFetch(`/api/admin/platform/organizations/${item.id}/${item.status === 'SUSPENDED' ? 'restore' : 'suspend'}`, props.auth, { method: 'POST' })
    await load()
    message.value = item.status === 'SUSPENDED' ? '조직을 복구했습니다.' : '조직을 일시 중지했습니다.'
  } catch (error) { showError(error, '조직 상태 변경에 실패했습니다.') }
  finally { busy.value = false }
}
async function openCleanup(item: Organization) {
  busy.value = true
  try {
    cleanup.value = await adminFetch<CleanupPreview>(`/api/admin/platform/organizations/${item.id}/cleanup-preview`, props.auth)
    cleanupOpen.value = true
  } catch (error) { showError(error, '정리 범위를 확인하지 못했습니다.') }
  finally { busy.value = false }
}
function openDelete() { orgConfirmation.value = ''; cleanupOpen.value = false; deleteOpen.value = true }
async function deleteOrganization() {
  if (!cleanup.value || orgConfirmation.value !== cleanup.value.name) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/platform/organizations/${cleanup.value.organizationId}?confirmation=${encodeURIComponent(orgConfirmation.value)}&purgeHistory=true`, props.auth, { method: 'DELETE' })
    deleteOpen.value = false; cleanup.value = null; await load(); message.value = '조직과 연결된 운영 데이터를 영구 삭제했습니다.'
  } catch (error) { showError(error, '조직 영구 삭제에 실패했습니다.') }
  finally { busy.value = false }
}
async function toggleUser(user: PlatformUser) {
  busy.value = true
  try {
    await adminFetch(`/api/admin/platform/users/${user.id}`, props.auth, { method:'PATCH', body:JSON.stringify({ enabled: !user.enabled }) })
    await load(); message.value = user.enabled ? '사용자 계정을 중지했습니다.' : '사용자 계정을 활성화했습니다.'
  } catch (error) { showError(error, '사용자 상태 변경에 실패했습니다.') }
  finally { busy.value = false }
}
function openUserDelete(user: PlatformUser) { selectedUser.value = user; userConfirmation.value = ''; userDeleteOpen.value = true }
async function deleteUser() {
  if (!selectedUser.value || userConfirmation.value.trim().toLowerCase() !== selectedUser.value.email.toLowerCase()) return
  busy.value = true
  try {
    await adminFetch(`/api/admin/platform/users/${selectedUser.value.id}?confirmation=${encodeURIComponent(userConfirmation.value.trim())}`, props.auth, { method:'DELETE' })
    userDeleteOpen.value = false; selectedUser.value = null; await load(); message.value = '사용자와 조직·팀 멤버십을 삭제했습니다.'
  } catch (error) { showError(error, '사용자 삭제에 실패했습니다.') }
  finally { busy.value = false }
}
function openTeamDelete(team: PlatformTeam) { selectedTeam.value = team; teamConfirmation.value = ''; teamDeleteOpen.value = true }
async function deleteTeam() {
  if (!selectedTeam.value || teamConfirmation.value !== selectedTeam.value.name) return
  busy.value = true
  try { await adminFetch(`/api/admin/platform/teams/${selectedTeam.value.id}?confirmation=${encodeURIComponent(teamConfirmation.value)}`, props.auth, { method:'DELETE' }); teamDeleteOpen.value = false; selectedTeam.value = null; await load(); message.value = '팀과 연결된 자격 증명을 정리했습니다.' }
  catch (error) { showError(error, '팀 삭제에 실패했습니다.') }
  finally { busy.value = false }
}

async function deleteApiKey(key: PlatformApiKey) {
  if (!window.confirm(`API 키 '${key.name}' (${key.keyPrefix})를 영구 삭제할까요? 요청 이력은 별도로 보존될 수 있습니다.`)) return
  busy.value = true
  try { await adminFetch(`/api/admin/platform/api-keys/${key.id}`, props.auth, { method:'DELETE' }); apiKeys.value = apiKeys.value.filter(item => item.id !== key.id); message.value = 'API 키를 영구 삭제했습니다.' }
  catch (error) { showError(error, 'API 키 삭제에 실패했습니다.') }
  finally { busy.value = false }
}
onMounted(() => void load())
</script>

<template>
  <section class="page-stack platform-admin-page">
    <div class="page-hero"><div><p class="eyebrow">PLATFORM ADMINISTRATION</p><h1>플랫폼 관리자</h1><p>모든 조직·사용자·API 키를 확인하고, 운영 중지와 정리 작업을 안전하게 실행합니다.</p></div><div class="hero-actions"><span class="status-chip healthy"><i></i>최종 권한</span><button class="secondary-button" :disabled="busy" @click="load">새로고침</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div v-if="overview" class="metric-grid platform-metrics">
      <article class="metric-card accent"><span>조직</span><strong>{{ metric(overview.organizationCount) }}</strong><small>워크스페이스 수</small></article>
      <article class="metric-card"><span>사용자</span><strong>{{ metric(overview.userCount) }}</strong><small>플랫폼 전체 계정</small></article>
      <article class="metric-card"><span>프로젝트</span><strong>{{ metric(overview.projectCount) }}</strong><small>조직별 작업 공간</small></article>
      <article class="metric-card"><span>API 키</span><strong>{{ metric(overview.apiKeyCount) }}</strong><small>발급된 자격 증명</small></article>
      <article class="metric-card"><span>Runtime</span><strong>{{ metric(overview.endpointCount) }}</strong><small>연결된 실행 엔드포인트</small></article>
      <article class="metric-card warning"><span>요청 이력</span><strong>{{ metric(overview.requestCount) }}</strong><small>관측 원본 데이터</small></article>
    </div>

    <article class="surface-card"><header class="card-header"><div><span class="card-kicker">ORGANIZATIONS</span><h2>조직·워크스페이스 관리</h2></div><span class="count-badge">{{ organizations.length }}</span></header><div class="data-table-wrap"><table class="data-table platform-table"><thead><tr><th>조직</th><th>상태</th><th>리소스</th><th>멤버</th><th>관리</th></tr></thead><tbody><tr v-for="item in organizations" :key="item.id"><td><strong>{{ item.name }}</strong><small>{{ item.id }}</small></td><td><span class="status-chip tiny" :class="item.status === 'ACTIVE' ? 'healthy' : 'draining'"><i></i>{{ item.status }}</span></td><td><small>프로젝트 {{ item.projectCount }} · Provider {{ item.providerCount }} · Node {{ item.nodeCount }}</small></td><td>{{ item.memberCount }}</td><td><div class="table-actions"><button class="text-button" @click="openCleanup(item)">정리 범위</button><button class="text-button" @click="setOrganizationStatus(item)">{{ item.status === 'SUSPENDED' ? '복구' : '중지' }}</button></div></td></tr><tr v-if="!organizations.length"><td colspan="5" class="table-empty">조직이 없습니다.</td></tr></tbody></table></div></article>

    <article class="surface-card"><header class="card-header"><div><span class="card-kicker">USERS</span><h2>전체 사용자 관리</h2></div><span class="count-badge">{{ users.length }}</span></header><div class="data-table-wrap"><table class="data-table platform-table"><thead><tr><th>사용자</th><th>권한</th><th>상태</th><th>소속·발급 키</th><th>관리</th></tr></thead><tbody><tr v-for="user in users" :key="user.id"><td><strong>{{ user.email }}</strong><small>{{ user.id }}</small></td><td>{{ user.platformAdmin ? 'Platform administrator' : '일반 사용자' }}</td><td><span class="status-chip tiny" :class="user.enabled ? 'healthy' : 'unhealthy'"><i></i>{{ user.enabled ? 'ENABLED' : 'DISABLED' }}</span></td><td>{{ user.organizationCount }}개 조직 · {{ user.issuedApiKeyCount }}개 발급 키</td><td><div class="table-actions"><button class="text-button" @click="toggleUser(user)">{{ user.enabled ? '중지' : '활성화' }}</button><button class="text-button danger-text" :disabled="user.platformAdmin" @click="openUserDelete(user)">삭제</button></div></td></tr><tr v-if="!users.length"><td colspan="5" class="table-empty">사용자가 없습니다.</td></tr></tbody></table></div></article>

    <article class="surface-card"><header class="card-header"><div><span class="card-kicker">TEAMS</span><h2>전체 팀·부서 관리</h2><p class="card-subtitle">팀을 삭제하면 연결 프로젝트는 중지되고 팀 프로젝트의 API 키가 폐기됩니다.</p></div><span class="count-badge">{{ teams.length }}</span></header><div class="data-table-wrap"><table class="data-table platform-table"><thead><tr><th>팀</th><th>조직</th><th>상태</th><th>연결 프로젝트</th><th>관리</th></tr></thead><tbody><tr v-for="team in teams" :key="team.id"><td><strong>{{ team.name }}</strong><small>{{ team.id }}</small></td><td>{{ team.organizationName }}</td><td><span class="status-chip tiny" :class="team.status === 'ACTIVE' ? 'healthy' : 'draining'"><i></i>{{ team.status }}</span></td><td>{{ team.projectCount }}</td><td><button class="text-button danger-text" @click="openTeamDelete(team)">팀 삭제</button></td></tr><tr v-if="!teams.length"><td colspan="5" class="table-empty">팀이 없습니다.</td></tr></tbody></table></div></article>

<article class="surface-card"><header class="card-header"><div><span class="card-kicker">API KEYS</span><h2>전체 API 키 정리</h2><p class="card-subtitle">원문은 표시하지 않고 prefix·소속·상태만 관리합니다.</p></div><span class="count-badge">{{ apiKeys.length }}</span></header><div class="data-table-wrap"><table class="data-table platform-table"><thead><tr><th>키</th><th>조직·프로젝트</th><th>상태</th><th>최근 사용</th><th>관리</th></tr></thead><tbody><tr v-for="key in apiKeys" :key="key.id"><td><strong>{{ key.name }}</strong><small>{{ key.keyPrefix }} · 생성 {{ date(key.createdAt) }}</small></td><td><strong>{{ key.organizationName }}</strong><small>{{ key.projectName }}</small></td><td><span class="status-chip tiny" :class="key.status === 'ACTIVE' ? 'healthy' : 'draining'"><i></i>{{ key.status }}</span></td><td>{{ date(key.lastUsedAt) }}</td><td><button class="text-button danger-text" @click="deleteApiKey(key)">영구 삭제</button></td></tr><tr v-if="!apiKeys.length"><td colspan="5" class="table-empty">API 키가 없습니다.</td></tr></tbody></table></div></article>

    <BaseModal :open="teamDeleteOpen" title="팀·부서 영구 삭제" description="팀 이름을 입력해야 삭제됩니다. 연결 프로젝트는 중지되고 해당 프로젝트 API 키가 폐기됩니다." size="sm" @close="teamDeleteOpen=false"><div v-if="selectedTeam" class="modal-form"><div class="delete-summary danger-alert"><strong>{{ selectedTeam.name }}</strong><p>{{ selectedTeam.organizationName }} · 연결 프로젝트 {{ selectedTeam.projectCount }}개</p></div><label class="field">확인 입력<input v-model.trim="teamConfirmation" :placeholder="selectedTeam.name" /></label></div><template #footer><button class="secondary-button" @click="teamDeleteOpen=false">취소</button><button class="danger-button primary-button" :disabled="busy || !selectedTeam || teamConfirmation !== selectedTeam.name" @click="deleteTeam">팀 삭제</button></template></BaseModal><BaseModal :open="cleanupOpen" title="조직 정리 범위" description="삭제 전 연결된 리소스와 요청 이력을 확인합니다." size="sm" @close="cleanupOpen=false"><div v-if="cleanup" class="platform-preview"><strong>{{ cleanup.name }}</strong><p>상태: {{ cleanup.status }}</p><div class="preview-grid"><span>프로젝트 <b>{{ cleanup.projectCount }}</b></span><span>Provider <b>{{ cleanup.providerCount }}</b></span><span>Node <b>{{ cleanup.nodeCount }}</b></span><span>Endpoint <b>{{ cleanup.endpointCount }}</b></span><span>멤버 <b>{{ cleanup.memberCount }}</b></span><span>요청 이력 <b>{{ cleanup.requestCount }}</b></span></div><p class="danger-alert">{{ cleanup.behavior }}</p></div><template #footer><button class="secondary-button" @click="cleanupOpen=false">닫기</button><button class="danger-button primary-button" @click="openDelete">영구 삭제</button></template></BaseModal>
    <BaseModal :open="deleteOpen" title="조직 영구 삭제" description="되돌릴 수 없습니다. 조직 이름을 정확히 입력해야 합니다." size="sm" @close="deleteOpen=false"><div v-if="cleanup" class="modal-form"><div class="delete-summary danger-alert"><strong>{{ cleanup.name }}</strong><p>프로젝트, Runtime, 외부 Provider, 팀·멤버십과 요청 이력을 모두 삭제합니다.</p></div><label class="field">확인 입력<input v-model.trim="orgConfirmation" :placeholder="cleanup.name" /></label></div><template #footer><button class="secondary-button" @click="deleteOpen=false">취소</button><button class="danger-button primary-button" :disabled="busy || !cleanup || orgConfirmation !== cleanup.name" @click="deleteOrganization">조직 삭제</button></template></BaseModal>
    <BaseModal :open="userDeleteOpen" title="사용자 영구 삭제" description="멤버십과 로그인 세션을 삭제합니다. 요청 이력의 감사 정보는 보존될 수 있습니다." size="sm" @close="userDeleteOpen=false"><div v-if="selectedUser" class="modal-form"><div class="delete-summary danger-alert"><strong>{{ selectedUser.email }}</strong><p>현재 로그인한 계정은 삭제할 수 없습니다.</p></div><label class="field">이메일 확인<input v-model.trim="userConfirmation" :placeholder="selectedUser.email" /></label></div><template #footer><button class="secondary-button" @click="userDeleteOpen=false">취소</button><button class="danger-button primary-button" :disabled="busy || !selectedUser || userConfirmation.toLowerCase() !== selectedUser.email.toLowerCase()" @click="deleteUser">사용자 삭제</button></template></BaseModal>
  </section>
</template>

<style scoped>
.platform-admin-page{gap:18px}.platform-metrics{grid-template-columns:repeat(6,minmax(130px,1fr))}.platform-table{min-width:900px}.platform-table .table-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.platform-table .table-empty{text-align:center;padding:35px;color:var(--muted)}.card-subtitle{margin:6px 0 0;color:var(--muted);font-size:10px}.danger-text{color:var(--danger)!important}.platform-preview{display:grid;gap:12px}.platform-preview>strong{font-size:18px}.platform-preview>p{margin:0;color:var(--muted);font-size:11px;line-height:1.6}.preview-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.preview-grid span{padding:10px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--muted);font-size:10px}.preview-grid b{display:block;margin-top:3px;color:var(--text);font-size:15px}.platform-preview .danger-alert{padding:11px;border-radius:10px;border:1px solid color-mix(in srgb,var(--danger) 34%,transparent);background:var(--danger-dim);color:var(--danger)}.delete-summary{padding:12px;border-radius:10px}.delete-summary p{margin:6px 0 0;color:inherit;font-size:11px;line-height:1.55}@media(max-width:1200px){.platform-metrics{grid-template-columns:repeat(3,minmax(130px,1fr))}}@media(max-width:650px){.platform-metrics{grid-template-columns:repeat(2,minmax(120px,1fr))}.preview-grid{grid-template-columns:1fr}}
</style>