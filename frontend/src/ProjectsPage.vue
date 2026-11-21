<script setup lang="ts">
import { formatDate, formatDateTime } from './dateTime'
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import IssuedConnectionGuide from './IssuedConnectionGuide.vue'
import {
  accountTierLabel,
  adminFetch,
  DATA_BOUNDARY_OPTIONS,
  dataBoundaryLabel,
  dataBoundaryShortLabel,
  exceedsDataBoundary,
  listProjectMembers,
  resolveOrgAdminAccess,
  revokeProjectMember,
  setProjectQuotaCeiling,
  strictestDataBoundary,
  updateApiKeyBoundaryPolicy,
  updateProject as updateProjectGovernance,
  updateProjectMemberDelegation,
  type AdminAuth,
  type DataBoundaryPolicy,
  type ProjectMemberView,
  type ProjectQuotaView
} from './api'
import { copyText } from './clipboard'

const props = defineProps<{ organizationId: string; organizationName?: string; currentUserId?: string; auth: AdminAuth; platformAdmin: boolean; orgAdmin?: boolean }>()
const emit = defineEmits<{ invite: [project: { id: string; name: string }]; openPlatform: [] }>()
const isOrgOrPlatformAdmin = computed(() => resolveOrgAdminAccess(props.auth, props.platformAdmin, props.orgAdmin))

type Project = {
  id: string; organizationId: string; teamId?: string | null; name: string; status: string; externalAiBlocked: boolean
  dataBoundaryPolicy?: DataBoundaryPolicy; maxDataBoundaryPolicy?: DataBoundaryPolicy
}
type Team = { id: string; name: string; status: string }
type Service = { id: string; serviceKey: string; displayName: string; enabled: boolean }
type ApiKey = {
  id: string; name: string; keyPrefix: string; status: string
  dataBoundaryPolicy?: DataBoundaryPolicy; effectiveDataBoundaryPolicy?: DataBoundaryPolicy
  expiresAt?: string; lastUsedAt?: string; createdAt: string
}
type Quota = ProjectQuotaView
type ContentPolicy = { projectId: string; mode: 'METADATA_ONLY' | 'FULL_ENCRYPTED'; retentionDays: number | null }
type AlertPolicy = { projectId: string; requestsPerMinuteThreshold: number | null; errorRatePercentThreshold: number | null; monthlyTokenUsagePercentThreshold: number | null; cooldownSeconds: number }
type ProjectControl = { projectId: string; projectName: string; status: string; activeApiKeyCount: number; revokedApiKeyCount: number }
type ProjectDeletionPreview = { projectId: string; projectName: string; status: string; teamId?: string | null; apiKeys: { id: string; name: string; keyPrefix: string; status: string }[]; services: { id: string; serviceKey: string; displayName: string }[]; requestHistoryCount: number; deletable: boolean; reason: string }
type GrantedService = { id: string; serviceKey: string; displayName: string; enabled: boolean }
type ConnectionEndpoint = { scope: 'INTERNAL' | 'EXTERNAL'; label: string; url: string }

const projects = ref<Project[]>([]); const teams = ref<Team[]>([]); const services = ref<Service[]>([]); const keys = ref<ApiKey[]>([]); const projectServices = ref<GrantedService[]>([]); const members = ref<ProjectMemberView[]>([])
const selectedId = ref(sessionStorage.getItem('aiconnect.setup.projectId') ?? ''); const selected = computed(() => projects.value.find(item => item.id === selectedId.value))
const activeTab = ref<'overview' | 'members' | 'services' | 'keys' | 'policies'>('overview')
const tabs = [{ id: 'overview', label: '개요' }, { id: 'members', label: '구성원' }, { id: 'services', label: '서비스 권한' }, { id: 'keys', label: 'API 키' }, { id: 'policies', label: '정책' }] as const
const canInvite = computed(() => isOrgOrPlatformAdmin.value || members.value.some(member => member.userId === props.currentUserId && member.role === 'MANAGER'))
const effectiveProjectBoundary = computed<DataBoundaryPolicy>(() => {
  if (!selected.value) return 'EXTERNAL_ALLOWED'
  return strictestDataBoundary(
    selected.value.maxDataBoundaryPolicy,
    selected.value.dataBoundaryPolicy,
    selected.value.externalAiBlocked ? 'INTERNAL_ONLY' : undefined
  )
})
const selectedMaxBoundary = computed<DataBoundaryPolicy>(() =>
  selected.value?.maxDataBoundaryPolicy ?? (selected.value?.externalAiBlocked ? 'INTERNAL_ONLY' : 'EXTERNAL_ALLOWED')
)
const quota = ref<Quota | null>(null); const retention = ref<ContentPolicy | null>(null); const alert = ref<AlertPolicy | null>(null); const control = ref<ProjectControl | null>(null)
const busy = ref(false); const message = ref(''); const projectOpen = ref(false); const keyOpen = ref(false); const serviceOpen = ref(false); const policyOpen = ref(false); const ceilingOpen = ref(false); const secretOpen = ref(false); const controlOpen = ref(false); const projectEditOpen = ref(false); const projectDeleteOpen = ref(false); const deletionPreview = ref<ProjectDeletionPreview | null>(null)
const projectName = ref(''); const projectTeamId = ref(''); const keyName = ref(''); const expiresAt = ref(''); const keyBoundaryPolicy = ref<DataBoundaryPolicy>('EXTERNAL_ALLOWED'); const serviceId = ref(''); const issuedSecret = ref(''); const revokeActiveKeys = ref(false); const editProjectName = ref(''); const editProjectTeamId = ref('')
const issuedConnections = ref<ConnectionEndpoint[]>([]); const issuedModels = ref<GrantedService[]>([])
const issuedProjectName = ref('')
const projectBoundaryPolicy = ref<DataBoundaryPolicy>('EXTERNAL_ALLOWED'); const editDataBoundaryPolicy = ref<DataBoundaryPolicy>('EXTERNAL_ALLOWED'); const editMaxDataBoundaryPolicy = ref<DataBoundaryPolicy>('EXTERNAL_ALLOWED')
const ceilingForm = ref({ maxRequestsPerMinute: null as number | null, maxMonthlyTokenLimit: null as number | null })
const form = ref({ requestsPerMinute: 60, monthlyTokenLimit: null as number | null, mode: 'METADATA_ONLY' as ContentPolicy['mode'], retentionDays: 30, requestsPerMinuteThreshold: null as number | null, errorRatePercentThreshold: null as number | null, monthlyTokenUsagePercentThreshold: null as number | null, cooldownSeconds: 900 })
let loadGeneration = 0
let selectionGeneration = 0

async function load() {
  const generation = ++loadGeneration
  const orgId = props.organizationId
  if (!props.organizationId) { projects.value = []; teams.value = []; services.value = []; keys.value = []; members.value = []; control.value = null; return }
  busy.value = true
  try {
    const [projectItems, teamItems, serviceItems] = await Promise.all([
      adminFetch<Project[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth),
      isOrgOrPlatformAdmin.value ? adminFetch<Team[]>(`/api/admin/organizations/${props.organizationId}/teams`, props.auth) : Promise.resolve([] as Team[]),
      isOrgOrPlatformAdmin.value ? adminFetch<Service[]>(`/api/admin/organizations/${props.organizationId}/services`, props.auth) : Promise.resolve([] as Service[])
    ])
    if (generation !== loadGeneration || orgId !== props.organizationId) return
    projects.value = projectItems; teams.value = teamItems; services.value = serviceItems
    if (!projects.value.some(item => item.id === selectedId.value)) selectedId.value = projects.value[0]?.id ?? ''
    if (selectedId.value) await selectProject(selectedId.value)
    else { keys.value = []; members.value = []; projectServices.value = []; quota.value = null; retention.value = null; alert.value = null; control.value = null }
  } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 정보를 불러오지 못했습니다.' }
  finally { busy.value = false }
}

async function selectProject(id: string) {
  const generation = ++selectionGeneration
  const orgId = props.organizationId
  if (id !== selectedId.value && !busy.value) { secretOpen.value = false; issuedSecret.value = ''; keyOpen.value = false }
  members.value = []; keys.value = []; projectServices.value = []; quota.value = null; retention.value = null; alert.value = null; control.value = null
  selectedId.value = id; sessionStorage.setItem('aiconnect.setup.projectId', id)
  if (!id) return
  const [keyItems, quotaValue, retentionValue, alertValue, controlValue, serviceAccessItems, memberItems] = await Promise.all([
    adminFetch<ApiKey[]>(`/api/admin/projects/${id}/api-keys`, props.auth),
    adminFetch<Quota>(`/api/admin/projects/${id}/quota`, props.auth),
    adminFetch<ContentPolicy>(`/api/admin/projects/${id}/content-policy`, props.auth),
    adminFetch<AlertPolicy>(`/api/admin/projects/${id}/alert-policy`, props.auth),
    adminFetch<ProjectControl>(`/api/admin/projects/${id}/control`, props.auth),
    adminFetch<GrantedService[]>(`/api/admin/projects/${id}/service-access`, props.auth),
    listProjectMembers(id, props.auth).catch(() => [] as ProjectMemberView[])
  ])
  if (generation !== selectionGeneration || orgId !== props.organizationId || id !== selectedId.value) return
  keys.value = keyItems; quota.value = quotaValue; retention.value = retentionValue; alert.value = alertValue; control.value = controlValue; projectServices.value = serviceAccessItems; members.value = memberItems
}

async function createProject() {
  busy.value = true
  try {
    const value = await adminFetch<Project>('/api/admin/projects', props.auth, {
      method: 'POST',
      body: JSON.stringify({
        organizationId: props.organizationId,
        teamId: projectTeamId.value || null,
        name: projectName.value,
        externalAiBlocked: projectBoundaryPolicy.value === 'INTERNAL_ONLY',
        dataBoundaryPolicy: projectBoundaryPolicy.value
      })
    })
    projectOpen.value = false; projectName.value = ''; projectTeamId.value = ''; projectBoundaryPolicy.value = 'EXTERNAL_ALLOWED'
    await load(); await selectProject(value.id)
    message.value = `프로젝트를 생성했습니다 (${dataBoundaryShortLabel(value.dataBoundaryPolicy ?? (value.externalAiBlocked ? 'INTERNAL_ONLY' : 'EXTERNAL_ALLOWED'))}).`
  } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 생성 실패' } finally { busy.value = false }
}
function openProjectEdit() {
  if (!selected.value) return
  editProjectName.value = selected.value.name
  editProjectTeamId.value = selected.value.teamId ?? ''
  editMaxDataBoundaryPolicy.value = selected.value.maxDataBoundaryPolicy ?? (selected.value.externalAiBlocked ? 'INTERNAL_ONLY' : 'EXTERNAL_ALLOWED')
  editDataBoundaryPolicy.value = selected.value.dataBoundaryPolicy ?? (selected.value.externalAiBlocked ? 'INTERNAL_ONLY' : 'EXTERNAL_ALLOWED')
  if (exceedsDataBoundary(editDataBoundaryPolicy.value, editMaxDataBoundaryPolicy.value)) {
    editDataBoundaryPolicy.value = editMaxDataBoundaryPolicy.value
  }
  projectEditOpen.value = true
}
function onMaxBoundaryChange() {
  if (exceedsDataBoundary(editDataBoundaryPolicy.value, editMaxDataBoundaryPolicy.value)) {
    editDataBoundaryPolicy.value = editMaxDataBoundaryPolicy.value
  }
}
async function updateProject() {
  if (!selectedId.value || !editProjectName.value.trim()) return
  if (!isOrgOrPlatformAdmin.value && exceedsDataBoundary(editDataBoundaryPolicy.value, selectedMaxBoundary.value)) {
    message.value = '관리자가 설정한 프로젝트 데이터 경계 상한을 완화할 수 없습니다.'
    return
  }
  busy.value = true
  try {
    const payload: {
      name: string
      teamId: string | null
      externalAiBlocked?: boolean
      dataBoundaryPolicy: DataBoundaryPolicy
      maxDataBoundaryPolicy?: DataBoundaryPolicy
    } = {
      name: editProjectName.value.trim(),
      teamId: editProjectTeamId.value || null,
      dataBoundaryPolicy: editDataBoundaryPolicy.value
    }
    if (isOrgOrPlatformAdmin.value) {
      payload.maxDataBoundaryPolicy = editMaxDataBoundaryPolicy.value
      payload.externalAiBlocked = editDataBoundaryPolicy.value === 'INTERNAL_ONLY'
    }
    const updated = await updateProjectGovernance(selectedId.value, payload, props.auth)
    projects.value = projects.value.map(project => project.id === updated.id ? updated : project)
    projectEditOpen.value = false
    await selectProject(selectedId.value)
    message.value = `프로젝트를 수정했습니다 (${dataBoundaryShortLabel(updated.dataBoundaryPolicy ?? (updated.externalAiBlocked ? 'INTERNAL_ONLY' : 'EXTERNAL_ALLOWED'))}).`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '프로젝트 수정에 실패했습니다.'
  } finally { busy.value = false }
}
async function openProjectControlFromEdit() {
  projectEditOpen.value = false
  await openControl()
}
async function openProjectDeletionFromEdit() {
  projectEditOpen.value = false
  await openProjectDeletion()
}
async function openProjectDeletion() {
  if (!selectedId.value) return
  busy.value = true
  try {
    deletionPreview.value = await adminFetch<ProjectDeletionPreview>(`/api/admin/projects/${selectedId.value}/deletion-preview`, props.auth, { method: 'POST' })
    projectDeleteOpen.value = true
  } catch (error) {
    message.value = error instanceof Error ? error.message : '프로젝트 삭제 영향을 불러오지 못했습니다.'
  } finally { busy.value = false }
}
async function deleteProject() {
  if (!deletionPreview.value?.deletable) return
  const removedId = deletionPreview.value.projectId
  busy.value = true
  try {
    await adminFetch(`/api/admin/projects/${removedId}`, props.auth, { method: 'DELETE' })
    projectDeleteOpen.value = false
    deletionPreview.value = null
    selectedId.value = ''
    sessionStorage.removeItem('aiconnect.setup.projectId')
    await load()
    message.value = '프로젝트와 연결된 API 키·서비스 권한 설정을 삭제했습니다.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '프로젝트 삭제에 실패했습니다.'
  } finally { busy.value = false }
}
function openKeyCreate() {
  keyName.value = ''
  expiresAt.value = ''
  keyBoundaryPolicy.value = effectiveProjectBoundary.value
  keyOpen.value = true
}
async function issueKey() {
  if (!selectedId.value) return
  const issuingProjectId = selectedId.value
  const issuingOrgId = props.organizationId
  const issuingProjectName = selected.value?.name ?? issuingProjectId
  const issuingModels = projectServices.value.filter(service => service.enabled)
  if (exceedsDataBoundary(keyBoundaryPolicy.value, effectiveProjectBoundary.value)) {
    message.value = '프로젝트 데이터 경계 상한을 초과하는 키 정책은 선택할 수 없습니다.'
    return
  }
  busy.value = true
  try {
    const value = await adminFetch<{ secret: string }>(`/api/admin/projects/${issuingProjectId}/api-keys`, props.auth, {
      method: 'POST',
      body: JSON.stringify({
        name: keyName.value,
        expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null,
        dataBoundaryPolicy: keyBoundaryPolicy.value
      })
    })
    issuedProjectName.value = issuingProjectName; issuedSecret.value = value.secret; issuedModels.value = issuingModels; issuedConnections.value = []
    keyName.value = ''; expiresAt.value = ''; keyOpen.value = false; secretOpen.value = true
    try { issuedConnections.value = (await adminFetch<{ endpoints: ConnectionEndpoint[] }>('/api/portal/connection', props.auth)).endpoints }
    catch { message.value = 'API 키는 발급되었지만 연결 주소를 불러오지 못했습니다.' }
    try { if (props.organizationId === issuingOrgId && selectedId.value === issuingProjectId) await selectProject(issuingProjectId) }
    catch { message.value = 'API 키는 발급되었지만 프로젝트 정보를 새로 고치지 못했습니다.' }
  } catch (error) { message.value = error instanceof Error ? error.message : '키 발급 실패' } finally { busy.value = false }
}
async function changeKeyBoundary(key: ApiKey, nextPolicy: DataBoundaryPolicy) {
  if (!selectedId.value) return
  if (exceedsDataBoundary(nextPolicy, effectiveProjectBoundary.value)) {
    message.value = '프로젝트 데이터 경계 상한을 초과하는 정책으로 완화할 수 없습니다.'
    return
  }
  busy.value = true
  try {
    await updateApiKeyBoundaryPolicy(selectedId.value, key.id, nextPolicy, props.auth)
    await selectProject(selectedId.value)
    message.value = `'${key.name}' API 키 데이터 경계를 ${dataBoundaryShortLabel(nextPolicy)}로 변경했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'API 키 데이터 경계 변경에 실패했습니다.'
  } finally { busy.value = false }
}
async function toggleMemberDelegation(member: ProjectMemberView) {
  if (!selectedId.value || member.role === 'MANAGER') return
  busy.value = true
  try {
    const updated = await updateProjectMemberDelegation(selectedId.value, member.userId, !member.canIssueApiKeys, props.auth)
    members.value = members.value.map(item => item.userId === updated.userId ? updated : item)
    message.value = updated.canIssueApiKeys
      ? `${member.email} 계정에 이 프로젝트의 API 키 발급 권한을 위임했습니다.`
      : `${member.email} 계정의 API 키 발급 권한을 회수했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '멤버 위임 설정 변경에 실패했습니다.'
  } finally { busy.value = false }
}
async function removeProjectMember(member: ProjectMemberView) {
  if (!selectedId.value || member.role === 'MANAGER') return
  if (!window.confirm(`${member.email} 계정의 프로젝트 멤버십을 회수할까요? 이 프로젝트에서 해당 사용자가 발급한 활성 API 키와 대기 초대가 즉시 폐기됩니다.`)) return
  busy.value = true
  try {
    await revokeProjectMember(selectedId.value, member.userId, props.auth)
    await selectProject(selectedId.value)
    message.value = `${member.email} 계정의 프로젝트 권한을 회수했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '프로젝트 멤버 회수에 실패했습니다.'
  } finally { busy.value = false }
}
async function grantService() { if (!selectedId.value || !serviceId.value) return; busy.value = true; try { await adminFetch(`/api/admin/projects/${selectedId.value}/service-access`, props.auth, { method: 'POST', body: JSON.stringify({ serviceId: serviceId.value }) }); serviceId.value = ''; await selectProject(selectedId.value); message.value = '논리 모델 권한이 추가되었습니다.' } catch (error) { message.value = error instanceof Error ? error.message : '서비스 권한 설정 실패' } finally { busy.value = false } }
async function revokeService(service: GrantedService) { if (!selectedId.value) return; busy.value = true; try { await adminFetch(`/api/admin/projects/${selectedId.value}/service-access/${service.id}`, props.auth, { method: 'DELETE' }); projectServices.value = projectServices.value.filter(item => item.id !== service.id); message.value = `'${service.serviceKey}' 프로젝트 권한을 해제했습니다.` } catch (error) { message.value = error instanceof Error ? error.message : '서비스 권한 해제에 실패했습니다.' } finally { busy.value = false } }
function openCeiling() {
  ceilingForm.value = {
    maxRequestsPerMinute: quota.value?.maxRequestsPerMinute ?? null,
    maxMonthlyTokenLimit: quota.value?.maxMonthlyTokenLimit ?? null
  }
  ceilingOpen.value = true
}
async function saveCeiling() {
  if (!selectedId.value || !props.platformAdmin) return
  busy.value = true
  try {
    const maxRpm = ceilingForm.value.maxRequestsPerMinute != null && Number(ceilingForm.value.maxRequestsPerMinute) > 0
      ? Number(ceilingForm.value.maxRequestsPerMinute)
      : null
    const maxMonthly = ceilingForm.value.maxMonthlyTokenLimit != null && Number(ceilingForm.value.maxMonthlyTokenLimit) > 0
      ? Number(ceilingForm.value.maxMonthlyTokenLimit)
      : null
    quota.value = await setProjectQuotaCeiling(selectedId.value, {
      maxRequestsPerMinute: maxRpm,
      maxMonthlyTokenLimit: maxMonthly
    }, props.auth)
    ceilingOpen.value = false
    message.value = '프로젝트 관리자 쿼터 상한을 저장했습니다. 상한을 초과한 기존 설정은 자동 조정되었습니다.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '쿼터 상한 저장에 실패했습니다.'
  } finally { busy.value = false }
}
function openPolicy() { form.value = { requestsPerMinute: quota.value?.requestsPerMinute ?? 60, monthlyTokenLimit: quota.value?.monthlyTokenLimit ?? null, mode: retention.value?.mode ?? 'METADATA_ONLY', retentionDays: retention.value?.retentionDays ?? 30, requestsPerMinuteThreshold: alert.value?.requestsPerMinuteThreshold ?? null, errorRatePercentThreshold: alert.value?.errorRatePercentThreshold ?? null, monthlyTokenUsagePercentThreshold: alert.value?.monthlyTokenUsagePercentThreshold ?? null, cooldownSeconds: alert.value?.cooldownSeconds ?? 900 }; policyOpen.value = true }
async function savePolicy() {
  if (!selectedId.value) return
  const rpm = Number(form.value.requestsPerMinute)
  const limit = Number(form.value.monthlyTokenLimit)
  const normalizedMonthly = limit > 0 ? limit : null
  if (!isOrgOrPlatformAdmin.value) {
    if (quota.value?.maxRequestsPerMinute != null && rpm > quota.value.maxRequestsPerMinute) {
      message.value = `분당 요청 수 상한(${quota.value.maxRequestsPerMinute} RPM)을 초과할 수 없습니다.`
      return
    }
    if (quota.value?.maxMonthlyTokenLimit != null && (normalizedMonthly == null || normalizedMonthly > quota.value.maxMonthlyTokenLimit)) {
      message.value = `월 토큰 한도 상한(${quota.value.maxMonthlyTokenLimit.toLocaleString()}) 이하로 설정해야 합니다.`
      return
    }
  }
  busy.value = true
  try {
    const [q, r, a] = await Promise.all([
      adminFetch<Quota>(`/api/admin/projects/${selectedId.value}/quota`, props.auth, { method: 'PUT', body: JSON.stringify({ requestsPerMinute: rpm, monthlyTokenLimit: normalizedMonthly }) }),
      adminFetch<ContentPolicy>(`/api/admin/projects/${selectedId.value}/content-policy`, props.auth, { method: 'PUT', body: JSON.stringify({ mode: form.value.mode, retentionDays: form.value.mode === 'FULL_ENCRYPTED' ? form.value.retentionDays : null }) }),
      adminFetch<AlertPolicy>(`/api/admin/projects/${selectedId.value}/alert-policy`, props.auth, { method: 'PUT', body: JSON.stringify({ requestsPerMinuteThreshold: form.value.requestsPerMinuteThreshold || null, errorRatePercentThreshold: form.value.errorRatePercentThreshold || null, monthlyTokenUsagePercentThreshold: form.value.monthlyTokenUsagePercentThreshold || null, cooldownSeconds: form.value.cooldownSeconds }) })
    ])
    quota.value = q; retention.value = r; alert.value = a; policyOpen.value = false
    message.value = '쿼터·경보·보관 정책이 저장되었습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '정책 저장 실패' } finally { busy.value = false }
}
async function revoke(key: ApiKey) { if (!selectedId.value || !window.confirm(`${key.name} 키를 폐기할까요?`)) return; busy.value = true; try { await adminFetch(`/api/admin/projects/${selectedId.value}/api-keys/${key.id}/revoke`, props.auth, { method: 'POST' }); await selectProject(selectedId.value); message.value = `'${key.name}' API 키를 폐기했습니다.` } catch (error) { message.value = error instanceof Error ? error.message : 'API 키 폐기에 실패했습니다.' } finally { busy.value = false } }
async function openControl() { if (!selectedId.value) return; busy.value = true; try { control.value = await adminFetch<ProjectControl>(`/api/admin/projects/${selectedId.value}/control`, props.auth); revokeActiveKeys.value = false; controlOpen.value = true } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 제어 정보를 불러오지 못했습니다.' } finally { busy.value = false } }
async function changeProjectStatus() { if (!selectedId.value || !control.value) return; const nextStatus = control.value.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED'; busy.value = true; try { const updated = await adminFetch<ProjectControl>(`/api/admin/projects/${selectedId.value}/status`, props.auth, { method: 'PATCH', body: JSON.stringify({ status: nextStatus, revokeActiveApiKeys: nextStatus === 'SUSPENDED' && revokeActiveKeys.value }) }); control.value = updated; projects.value = projects.value.map(project => project.id === selectedId.value ? { ...project, status: updated.status } : project); await selectProject(selectedId.value); controlOpen.value = false; message.value = nextStatus === 'SUSPENDED' ? `프로젝트를 강제 중지했습니다.${updated.revokedApiKeyCount ? ` 활성 키 ${updated.revokedApiKeyCount}개도 폐기했습니다.` : ''}` : '프로젝트를 재개했습니다. 폐기된 API 키는 자동으로 복구되지 않습니다.' } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 상태 변경에 실패했습니다.' } finally { busy.value = false } }
async function copySecret() { try { await copyText(issuedSecret.value); message.value = 'API 키를 복사했습니다.' } catch { message.value = 'API 키를 복사하지 못했습니다. 값을 직접 선택해 복사해 주세요.' } }
watch(selectedId, (id, previous) => { if (id !== previous && !busy.value) { secretOpen.value = false; issuedSecret.value = ''; keyOpen.value = false } })
watch(() => [props.organizationId, isOrgOrPlatformAdmin.value], () => { ++selectionGeneration; if (!busy.value) { secretOpen.value = false; issuedSecret.value = ''; keyOpen.value = false }; selectedId.value = ''; projects.value = []; members.value = []; keys.value = []; projectServices.value = []; void load() }); onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack">
    <div class="page-hero"><div><p class="eyebrow">WORKSPACE MANAGEMENT</p><h1>프로젝트</h1><p>프로젝트별 멤버, 서비스 권한, API 키와 정책을 관리합니다.</p></div><div class="hero-actions"><button v-if="isOrgOrPlatformAdmin" class="primary-button" :disabled="!organizationId" @click="projectOpen = true">+ 프로젝트</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <article v-if="selected && activeTab === 'overview'" class="surface-card project-local-boundary">
      <span class="status-chip" :class="effectiveProjectBoundary === 'INTERNAL_ONLY' ? 'healthy' : effectiveProjectBoundary === 'APPROVED_EXTERNAL_NO_RETENTION' ? 'unknown' : 'draining'">
        {{ dataBoundaryShortLabel(effectiveProjectBoundary) }}
      </span>
      <div>
        <strong>프로젝트 데이터 경계: {{ dataBoundaryLabel(effectiveProjectBoundary) }}</strong>
        <p>관리자 허용 상한: <b>{{ dataBoundaryLabel(selectedMaxBoundary) }}</b> · 모든 API 키와 요청은 <code>프로젝트 ∩ 논리 서비스 ∩ API 키</code> 교집합 정책을 초과할 수 없습니다.</p>
        <p v-if="!projectServices.length">아직 사용 가능한 논리 서비스 권한이 없습니다. 서비스 권한이 부여된 뒤 요청하세요.</p>
      </div>
      <button class="secondary-button" :disabled="busy" @click="openProjectEdit">보안 설정</button>
    </article>
    <div class="project-switcher surface-card"><div><span class="card-kicker">PROJECT SPACE</span><h2>{{ selected?.name ?? '프로젝트 선택' }}</h2><small>조직: {{ organizationName || organizationId || '선택 안 됨' }} · {{ selected?.teamId ? `팀: ${teams.find(team => team.id === selected?.teamId)?.name ?? '팀 소유 프로젝트'}` : '조직 공용 프로젝트' }}</small></div><select v-model="selectedId" @change="selectProject(selectedId)"><option value="">프로젝트 선택</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.status }}</option></select><div class="hero-actions"><span v-if="selected" class="status-chip" :class="selected.status === 'ACTIVE' ? 'healthy' : 'unhealthy'">{{ selected.status }}</span><button class="secondary-button" :disabled="!selectedId || busy" @click="openProjectEdit">프로젝트 수정</button></div></div>
    <nav v-if="selected" class="project-tabs" aria-label="프로젝트 보기"><button v-for="tab in tabs" :key="tab.id" type="button" class="secondary-button" :aria-current="activeTab === tab.id ? 'page' : undefined" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button></nav>
    <div v-if="selected && activeTab === 'policies'" class="project-policy-grid">
      <article class="surface-card policy-card">
        <span class="policy-icon">◔</span>
        <div>
          <span class="card-kicker">RATE & TOKEN</span>
          <h3>{{ quota?.requestsPerMinute ?? 60 }} RPM</h3>
          <p>{{ quota?.monthlyTokenLimit ? `${quota.monthlyTokenLimit.toLocaleString()} 월 토큰 한도` : '월 토큰 한도 없음' }}</p>
          <p v-if="quota?.maxRequestsPerMinute != null || quota?.maxMonthlyTokenLimit != null" class="ceiling-summary">
            관리자 상한: {{ quota?.maxRequestsPerMinute != null ? `${quota.maxRequestsPerMinute} RPM` : 'RPM 무제한' }} · {{ quota?.maxMonthlyTokenLimit != null ? `${quota.maxMonthlyTokenLimit.toLocaleString()} 토큰` : '월 토큰 상한 없음' }}
          </p>
        </div>
        <div class="policy-card-actions">
          <button class="text-button" @click="openPolicy">변경</button>
          <button v-if="platformAdmin" class="text-button" @click="openCeiling">상한 설정</button>
        </div>
      </article>
      <article class="surface-card policy-card"><span class="policy-icon">!</span><div><span class="card-kicker">USAGE ALERT</span><h3>{{ alert?.requestsPerMinuteThreshold ? `${alert.requestsPerMinuteThreshold} RPM 경보` : '경보 미설정' }}</h3><p>오류율·월 토큰 사용률 경보</p></div><button class="text-button" @click="openPolicy">변경</button></article>
      <article class="surface-card policy-card"><span class="policy-icon">⌁</span><div><span class="card-kicker">CONTENT RETENTION</span><h3>{{ retention?.mode === 'FULL_ENCRYPTED' ? `암호화 원문 ${retention.retentionDays}일` : '메타데이터만' }}</h3><p>감사·소유자 역할에만 원문 열람 허용</p></div><button class="text-button" @click="openPolicy">변경</button></article>
    </div>

    <article v-if="selected && activeTab === 'members'" class="surface-card">
      <header class="card-header">
        <div>
          <span class="card-kicker">PROJECT MEMBERS & DELEGATION</span>
          <h2>프로젝트 멤버 · API 키 발급 위임</h2>
        </div>
        <div class="hero-actions"><span class="count-badge">{{ members.length }}</span><button v-if="platformAdmin" class="secondary-button" @click="emit('openPlatform')">총괄 배정 관리</button><button v-if="canInvite" class="primary-button" :disabled="busy || selected.status === 'SUSPENDED'" @click="emit('invite', { id: selected.id, name: selected.name })">구성원 초대</button></div>
      </header>
      <div v-if="members.length" class="data-table-wrap">
        <table class="data-table">
          <thead><tr><th>이메일</th><th>계층 · 역할</th><th>API 키 발급 권한</th><th>발급 활성 키</th><th>배정일</th><th>관리</th></tr></thead>
          <tbody>
            <tr v-for="member in members" :key="member.userId">
              <td><strong>{{ member.email }}</strong><small>{{ member.userId }}</small></td>
              <td><strong>{{ accountTierLabel(member.accountTier) }}</strong><small>{{ member.role }}</small></td>
              <td>
                <span class="status-chip tiny" :class="member.canIssueApiKeys ? 'healthy' : 'unknown'">
                  {{ member.canIssueApiKeys ? '발급 허용' : '조회 전용' }}
                </span>
              </td>
              <td>{{ member.activeApiKeyCount ?? 0 }}개</td>
              <td>{{ member.createdAt ? formatDate(member.createdAt) : '-' }}</td>
              <td>
                <div v-if="member.role === 'MEMBER'" class="table-actions">
                  <button type="button" class="text-button" :disabled="busy" @click="toggleMemberDelegation(member)">
                    {{ member.canIssueApiKeys ? '발급 권한 회수' : '발급 권한 위임' }}
                  </button>
                  <button type="button" class="text-button danger-text" :disabled="busy" @click="removeProjectMember(member)">멤버 회수</button>
                </div>
                <small v-else class="muted-copy">관리자 회수는 플랫폼 관리에서 실행</small>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state compact"><span>⌘</span><p>배정된 프로젝트 멤버가 없습니다.</p></div>
    </article>

    <article v-if="selected && activeTab === 'services'" class="surface-card"><header class="card-header"><div><span class="card-kicker">SERVICE ACCESS</span><h2>서비스 권한</h2><p class="card-subtitle">이 프로젝트에서 사용할 수 있는 논리 서비스입니다.</p></div><button class="secondary-button" @click="serviceOpen = true">서비스 권한 관리</button></header><div class="project-service-access-list"><p v-for="service in projectServices" :key="service.id"><strong>{{ service.serviceKey }}</strong> · {{ service.displayName }} · {{ service.enabled ? 'ACTIVE' : 'DISABLED' }}</p><p v-if="!projectServices.length" class="muted-copy">연결된 논리 서비스가 없습니다.</p></div></article>

    <article v-if="selected && activeTab === 'keys'" class="surface-card">
      <header class="card-header"><div><span class="card-kicker">PROJECT CREDENTIALS</span><h2>API 키</h2></div><div class="hero-actions"><span class="count-badge">{{ keys.length }}</span><button class="primary-button" :disabled="selected.status === 'SUSPENDED'" @click="openKeyCreate">API 키 발급</button></div></header>
      <div v-if="keys.length" class="data-table-wrap">
        <table class="data-table">
          <thead><tr><th>이름</th><th>Prefix</th><th>데이터 경계</th><th>상태</th><th>마지막 사용</th><th>만료</th><th></th></tr></thead>
          <tbody>
            <tr v-for="key in keys" :key="key.id">
              <td><strong>{{ key.name }}</strong><small>{{ formatDate(key.createdAt) }} 생성</small></td>
              <td class="mono">{{ key.keyPrefix }}••••</td>
              <td>
                <select
                  class="inline-boundary-select"
                  :aria-label="`${key.name} 데이터 경계`"
                  :value="key.dataBoundaryPolicy ?? effectiveProjectBoundary"
                  :disabled="key.status !== 'ACTIVE' || busy"
                  @change="changeKeyBoundary(key, ($event.target as HTMLSelectElement).value as DataBoundaryPolicy)"
                >
                  <option
                    v-for="option in DATA_BOUNDARY_OPTIONS"
                    :key="option.value"
                    :value="option.value"
                    :disabled="exceedsDataBoundary(option.value, effectiveProjectBoundary)"
                  >
                    {{ option.shortLabel }}
                  </option>
                </select>
                <small class="effective-boundary-note">실효: {{ dataBoundaryShortLabel(key.effectiveDataBoundaryPolicy ?? strictestDataBoundary(effectiveProjectBoundary, key.dataBoundaryPolicy)) }}</small>
              </td>
              <td><span class="status-chip tiny" :class="key.status === 'ACTIVE' ? 'healthy' : 'unknown'">{{ key.status }}</span></td>
              <td>{{ key.lastUsedAt ? formatDateTime(key.lastUsedAt) : '사용 전' }}</td>
              <td>{{ key.expiresAt ? formatDate(key.expiresAt) : '만료 없음' }}</td>
              <td><button class="text-button danger-text" :disabled="key.status !== 'ACTIVE' || busy" @click="revoke(key)">폐기</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state"><span>⌘</span><h3>발급된 API 키가 없습니다</h3><p>키 원문은 발급 직후 한 번만 표시됩니다.</p><button class="text-button" :disabled="selected?.status === 'SUSPENDED'" @click="openKeyCreate">첫 API 키 발급</button></div>
    </article>

    <BaseModal :open="projectOpen" title="새 프로젝트" description="팀과 3단계 데이터 경계 정책을 설정합니다." size="sm" @close="projectOpen = false"><div class="modal-form"><label class="field">프로젝트 이름<input v-model.trim="projectName" placeholder="customer-support-api" maxlength="120" /></label><label v-if="isOrgOrPlatformAdmin" class="field">소유 팀<select v-model="projectTeamId"><option value="">조직 공용</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select></label><label class="field">데이터 경계 정책<select v-model="projectBoundaryPolicy"><option v-for="option in DATA_BOUNDARY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><small class="muted-copy">{{ DATA_BOUNDARY_OPTIONS.find(o => o.value === projectBoundaryPolicy)?.description }}</small></div><template #footer><button class="secondary-button" @click="projectOpen = false">취소</button><button class="primary-button" :disabled="busy || !projectName" @click="createProject">프로젝트 생성</button></template></BaseModal>
    <BaseModal :open="projectEditOpen" title="프로젝트 수정" description="이름·데이터 경계 정책을 변경합니다. 프로젝트 총괄은 관리자 상한보다 완화할 수 없습니다." size="sm" @close="projectEditOpen = false">
      <div class="modal-form">
        <label class="field">프로젝트 이름<input v-model.trim="editProjectName" maxlength="120" /></label>
        <label v-if="isOrgOrPlatformAdmin" class="field">소유 팀<select v-model="editProjectTeamId"><option value="">조직 공용</option><option v-for="team in teams" :key="team.id" :value="team.id">{{ team.name }}</option></select></label>
        <label v-if="isOrgOrPlatformAdmin" class="field">
          관리자 허용 상한 (maxDataBoundaryPolicy)
          <select v-model="editMaxDataBoundaryPolicy" @change="onMaxBoundaryChange">
            <option v-for="option in DATA_BOUNDARY_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <div v-else class="ceiling-readonly-box">
          <span>관리자 허용 상한</span>
          <strong>{{ dataBoundaryLabel(selectedMaxBoundary) }}</strong>
          <small>프로젝트 총괄은 상한 이하의 더 엄격한 정책으로만 조정할 수 있습니다.</small>
        </div>
        <label class="field">
          프로젝트 데이터 경계 정책
          <select v-model="editDataBoundaryPolicy">
            <option
              v-for="option in DATA_BOUNDARY_OPTIONS"
              :key="option.value"
              :value="option.value"
              :disabled="exceedsDataBoundary(option.value, isOrgOrPlatformAdmin ? editMaxDataBoundaryPolicy : selectedMaxBoundary)"
            >
              {{ option.label }}{{ exceedsDataBoundary(option.value, isOrgOrPlatformAdmin ? editMaxDataBoundaryPolicy : selectedMaxBoundary) ? ' (상한 초과)' : '' }}
            </option>
          </select>
        </label>
      </div>
      <div v-if="isOrgOrPlatformAdmin" class="project-edit-lifecycle"><div><span class="card-kicker">LIFECYCLE CONTROL</span><b>프로젝트 상태 관리</b><small>중지·재개와 영구 삭제는 프로젝트 설정과 분리해 안전하게 처리합니다.</small></div><div><button class="secondary-button" :disabled="busy" @click="openProjectControlFromEdit">{{ selected?.status === 'SUSPENDED' ? '프로젝트 재개' : '프로젝트 중지' }}</button><button class="secondary-button danger-text" :disabled="busy" @click="openProjectDeletionFromEdit">프로젝트 삭제</button></div></div>
      <template #footer><button class="secondary-button" @click="projectEditOpen = false">취소</button><button class="primary-button" :disabled="busy || !editProjectName" @click="updateProject">수정 저장</button></template>
    </BaseModal>
    <BaseModal :open="projectDeleteOpen" title="프로젝트 영구 삭제" description="영향 범위를 확인한 뒤에만 삭제를 실행하세요." size="lg" @close="projectDeleteOpen = false"><template v-if="deletionPreview"><div class="project-delete-impact" :class="{ blocked: !deletionPreview.deletable }"><span>{{ deletionPreview.deletable ? '삭제 가능' : '삭제 차단' }}</span><b>{{ deletionPreview.projectName }}</b><p>{{ deletionPreview.reason }}</p><div><small>API 키 {{ deletionPreview.apiKeys.length }}개</small><small>서비스 권한 {{ deletionPreview.services.length }}개</small><small>요청 이력 {{ deletionPreview.requestHistoryCount }}건</small></div></div><section class="delete-impact-section"><h3>삭제할 API 키</h3><div v-if="deletionPreview.apiKeys.length" class="delete-impact-list"><div v-for="key in deletionPreview.apiKeys" :key="key.id"><b>{{ key.name }}</b><small><code>{{ key.keyPrefix }}</code> · {{ key.status }}</small></div></div><p v-else class="muted-copy">등록된 API 키가 없습니다.</p></section><section class="delete-impact-section"><h3>해제할 LLM 서비스 권한</h3><div v-if="deletionPreview.services.length" class="delete-impact-list"><div v-for="service in deletionPreview.services" :key="service.id"><b>{{ service.serviceKey }}</b><small>{{ service.displayName }}</small></div></div><p v-else class="muted-copy">연결된 서비스 권한이 없습니다.</p></section><p v-if="!deletionPreview.deletable" class="delete-blocked-note">요청 이력이 있는 프로젝트는 삭제할 수 없습니다. <b>강제 중지</b>를 사용하면 API 요청을 차단하면서 사용량·감사 이력은 보존할 수 있습니다.</p></template><template #footer><button class="secondary-button" @click="projectDeleteOpen = false">취소</button><button class="danger-button primary-button" :disabled="busy || !deletionPreview?.deletable" @click="deleteProject">프로젝트 및 연결 설정 삭제</button></template></BaseModal>
    <BaseModal :open="keyOpen" title="API 키 발급" description="키 원문은 한 번만 표시되며 서비스 서버의 환경 변수에 보관해야 합니다." size="sm" @close="keyOpen = false"><div class="modal-form"><label class="field">키 이름<input v-model.trim="keyName" placeholder="production-backend" /></label><label class="field">데이터 경계 정책 (프로젝트 상한: {{ dataBoundaryShortLabel(effectiveProjectBoundary) }})<select v-model="keyBoundaryPolicy"><option v-for="option in DATA_BOUNDARY_OPTIONS" :key="option.value" :value="option.value" :disabled="exceedsDataBoundary(option.value, effectiveProjectBoundary)">{{ option.label }}{{ exceedsDataBoundary(option.value, effectiveProjectBoundary) ? ' (상한 초과)' : '' }}</option></select></label><label class="field">만료일 (선택)<input v-model="expiresAt" type="datetime-local" /></label></div><template #footer><button class="secondary-button" @click="keyOpen = false">취소</button><button class="primary-button" :disabled="busy || !keyName || exceedsDataBoundary(keyBoundaryPolicy, effectiveProjectBoundary)" @click="issueKey">키 발급</button></template></BaseModal>
    <BaseModal :open="serviceOpen" title="LLM 서비스 권한" description="이 프로젝트의 API 키가 호출할 수 있는 논리 모델을 확인합니다." size="md" @close="serviceOpen = false"><div class="modal-form"><label v-if="isOrgOrPlatformAdmin" class="field">논리 모델 추가<select v-model="serviceId"><option value="">서비스 선택</option><option v-for="service in services.filter(item => item.enabled && !projectServices.some(granted => granted.id === item.id))" :key="service.id" :value="service.id">{{ service.serviceKey }} · {{ service.displayName }}</option></select></label><section class="project-service-access-list"><div><span class="card-kicker">CURRENT ACCESS</span><h3>현재 연결된 서비스</h3><small>여기서 권한을 해제해도 프로젝트와 API 키는 삭제되지 않습니다.</small></div><div v-if="projectServices.length" class="project-service-access-items"><article v-for="service in projectServices" :key="service.id"><div><b>{{ service.serviceKey }}</b><small>{{ service.displayName }} · {{ service.enabled ? 'ACTIVE' : 'DISABLED' }}</small></div><button v-if="isOrgOrPlatformAdmin" class="text-button danger-text" :disabled="busy" @click="revokeService(service)">권한 해제</button></article></div><p v-else class="muted-copy">연결된 논리 서비스가 없습니다.</p></section></div><template #footer><button class="secondary-button" @click="serviceOpen = false">닫기</button><button v-if="isOrgOrPlatformAdmin" class="primary-button" :disabled="busy || !serviceId" @click="grantService">권한 부여</button></template></BaseModal>
    <BaseModal :open="policyOpen" title="프로젝트 정책" description="쿼터, 사용량 급증 알림, 암호화 보관 정책을 한 번에 설정합니다." @close="policyOpen = false"><div class="modal-form"><div class="form-grid"><label class="field">분당 요청 수<input v-model.number="form.requestsPerMinute" type="number" min="1" :max="!isOrgOrPlatformAdmin ? (quota?.maxRequestsPerMinute ?? undefined) : undefined" /></label><label class="field">월 토큰 한도<input v-model.number="form.monthlyTokenLimit" type="number" min="1" :max="!isOrgOrPlatformAdmin ? (quota?.maxMonthlyTokenLimit ?? undefined) : undefined" placeholder="비우면 제한 없음" /></label><label class="field">RPM 경보<input v-model.number="form.requestsPerMinuteThreshold" type="number" min="1" placeholder="미설정" /></label><label class="field">오류율 경보 (%)<input v-model.number="form.errorRatePercentThreshold" type="number" min="0.01" max="100" placeholder="미설정" /></label><label class="field">월 토큰 경보 (%)<input v-model.number="form.monthlyTokenUsagePercentThreshold" type="number" min="1" max="100" placeholder="미설정" /></label><label class="field">알림 재전송 간격 (초)<input v-model.number="form.cooldownSeconds" type="number" min="60" /></label></div><p v-if="quota?.maxRequestsPerMinute != null || quota?.maxMonthlyTokenLimit != null" class="muted-copy">관리자 상한: RPM {{ quota?.maxRequestsPerMinute ?? '제한 없음' }} · 월 토큰 {{ quota?.maxMonthlyTokenLimit ? quota.maxMonthlyTokenLimit.toLocaleString() : '제한 없음' }}</p><div class="retention-options"><label :class="{ active: form.mode === 'METADATA_ONLY' }"><input v-model="form.mode" type="radio" value="METADATA_ONLY" /><span><b>메타데이터만</b><small>토큰·비용·상태만 저장합니다.</small></span></label><label :class="{ active: form.mode === 'FULL_ENCRYPTED' }"><input v-model="form.mode" type="radio" value="FULL_ENCRYPTED" /><span><b>암호화된 원문</b><small>프롬프트와 응답을 AES-GCM으로 보관합니다.</small></span></label></div><label v-if="form.mode === 'FULL_ENCRYPTED'" class="field">원문 보관 일수<input v-model.number="form.retentionDays" type="number" min="1" max="365" /></label></div><template #footer><button class="secondary-button" @click="policyOpen = false">취소</button><button class="primary-button" :disabled="busy || form.requestsPerMinute < 1" @click="savePolicy">정책 저장</button></template></BaseModal>
    <BaseModal :open="ceilingOpen" title="관리자 프로젝트 쿼터 상한" description="시스템 관리자가 프로젝트 총괄의 최대 RPM 및 월 토큰 한도 상한을 설정합니다. 상한보다 높은 기존 값은 자동 조정됩니다." size="sm" @close="ceilingOpen = false">
      <div class="modal-form">
        <label class="field">최대 RPM 상한 (비우면 상한 해제)<input v-model.number="ceilingForm.maxRequestsPerMinute" type="number" min="1" placeholder="상한 없음" /></label>
        <label class="field">최대 월 토큰 한도 상한 (비우면 상한 해제)<input v-model.number="ceilingForm.maxMonthlyTokenLimit" type="number" min="1" placeholder="상한 없음" /></label>
      </div>
      <template #footer><button class="secondary-button" @click="ceilingOpen = false">취소</button><button class="primary-button" :disabled="busy" @click="saveCeiling">상한 저장</button></template>
    </BaseModal>
    <BaseModal :open="controlOpen" :title="control?.status === 'SUSPENDED' ? '프로젝트 재개' : '프로젝트 강제 중지'" :description="control?.status === 'SUSPENDED' ? '프로젝트 요청 수신을 다시 허용합니다. 이미 폐기된 API 키는 자동으로 복구되지 않습니다.' : '중지 즉시 이 프로젝트의 모든 API 요청이 Gateway에서 차단됩니다.'" size="sm" @close="controlOpen = false"><div class="project-control-note"><span>{{ control?.status === 'SUSPENDED' ? '재개 대상' : '중지 대상' }}</span><strong>{{ control?.projectName }}</strong><p v-if="control?.status === 'SUSPENDED'">현재 프로젝트가 중지 상태입니다. 재개하면 ACTIVE 상태의 기존 API 키는 다시 호출할 수 있습니다.</p><template v-else><p>현재 활성 API 키: <b>{{ control?.activeApiKeyCount ?? 0 }}개</b>. 프로젝트 중지는 키를 삭제하지 않고 호출만 차단합니다.</p><label class="toggle-field"><span>활성 API 키도 모두 폐기<small>재개 후에도 기존 키를 사용하지 못하게 하는 긴급 대응입니다.</small></span><input v-model="revokeActiveKeys" type="checkbox" /></label></template></div><template #footer><button class="secondary-button" :disabled="busy" @click="controlOpen = false">취소</button><button class="secondary-button danger-text" :disabled="busy" @click="changeProjectStatus">{{ control?.status === 'SUSPENDED' ? '프로젝트 재개' : '강제 중지 실행' }}</button></template></BaseModal>
    <BaseModal :open="secretOpen" title="API 키가 발급되었습니다" description="이 창을 닫으면 키 원문은 다시 표시되지 않습니다." @close="secretOpen = false; issuedSecret = ''"><div class="secret-reveal"><span>PROJECT API KEY · {{ issuedProjectName }}</span><code>{{ issuedSecret }}</code><button class="secondary-button" @click="copySecret">복사</button></div><IssuedConnectionGuide :endpoints="issuedConnections" :models="issuedModels" /><template #footer><button class="primary-button" @click="secretOpen = false; issuedSecret = ''">안전하게 보관했습니다</button></template></BaseModal>
  </section>
</template>

<style scoped>
.project-tabs { display: flex; flex-wrap: wrap; gap: 8px; }
.project-tabs .active { border-color: var(--accent); font-weight: 700; }
.project-local-boundary { display: flex; align-items: flex-start; gap: 16px; padding: 20px; }
.project-local-boundary > div { flex: 1; min-width: 0; }
.project-local-boundary strong { font-size: 14px; }
.project-local-boundary p { margin: 6px 0 0; font-size: 12px; line-height: 1.65; color: var(--muted); overflow-wrap: anywhere; }
@media (max-width: 640px) { .project-local-boundary { flex-wrap: wrap; } .project-local-boundary > div { flex-basis: calc(100% - 110px); } }
.ceiling-summary { margin-top: 4px; color: var(--accent-strong); font-size: 11px; }
.policy-card-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.inline-boundary-select { min-height: 32px; min-width: 150px; padding: 0 26px 0 9px; font-size: 11px; }
.effective-boundary-note { display: block; margin-top: 3px; color: var(--muted); font-size: 9px; }
.ceiling-readonly-box { padding: 11px 13px; display: grid; gap: 4px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.ceiling-readonly-box span { color: var(--muted); font-size: 10px; font-weight: 700; }
.ceiling-readonly-box strong { font-size: 12px; }
.ceiling-readonly-box small { color: var(--muted); font-size: 10px; }
.table-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.project-control-note { padding: 15px; display: grid; gap: 9px; border: 1px solid color-mix(in srgb, var(--danger) 40%, var(--border)); border-radius: 12px; background: var(--danger-dim); }.project-control-note > span { color: var(--danger); font-size: 9px; font-weight: 900; letter-spacing: .12em; }.project-control-note strong { font-size: 15px; }.project-control-note p { margin: 0; color: var(--text-soft); font-size: 11px; line-height: 1.6; }.project-control-note .toggle-field { margin-top: 3px; }

.project-service-access-list { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border); }.project-service-access-list h3 { margin: 4px 0; font-size: 13px; }.project-service-access-list > div:first-child > small, .project-service-access-list > .muted-copy { color: var(--muted); font-size: 10px; }.project-service-access-items { margin-top: 11px; display: grid; gap: 7px; }.project-service-access-items article { padding: 10px 11px; display: flex; align-items: center; justify-content: space-between; gap: 10px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }.project-service-access-items article div { display: grid; gap: 3px; }.project-service-access-items b { font-size: 11px; }.project-service-access-items small { color: var(--muted); font-size: 10px; }
.project-edit-lifecycle { margin-top: 18px; padding: 13px; display: flex; justify-content: space-between; gap: 14px; align-items: center; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }.project-edit-lifecycle > div:first-child { display: grid; gap: 4px; }.project-edit-lifecycle b { font-size: 12px; }.project-edit-lifecycle small { color: var(--muted); font-size: 10px; line-height: 1.5; }.project-edit-lifecycle > div:last-child { display: flex; flex-wrap: wrap; justify-content: end; gap: 7px; }.project-edit-lifecycle .danger-text { color: var(--danger); }
.project-delete-impact { padding: 14px; display: grid; gap: 7px; border: 1px solid var(--danger); border-radius: 12px; background: var(--danger-dim); }.project-delete-impact.blocked { border-color: var(--border); background: var(--surface-2); }.project-delete-impact > span { width: max-content; padding: 4px 7px; border-radius: 999px; background: var(--surface); color: var(--danger); font-size: 9px; font-weight: 900; }.project-delete-impact.blocked > span { color: var(--text-soft); }.project-delete-impact b { font-size: 15px; }.project-delete-impact p { margin: 0; color: var(--text-soft); font-size: 11px; line-height: 1.6; }.project-delete-impact > div { display: flex; flex-wrap: wrap; gap: 7px; }.project-delete-impact small { padding: 4px 7px; border-radius: 999px; background: var(--surface); color: var(--text-soft); font-size: 10px; }.delete-impact-section { margin-top: 18px; }.delete-impact-section h3 { margin: 0 0 8px; font-size: 12px; }.delete-impact-list { display: grid; gap: 7px; max-height: 150px; overflow: auto; }.delete-impact-list > div { padding: 10px 11px; display: grid; gap: 3px; border: 1px solid var(--border); border-radius: 9px; background: var(--surface-2); }.delete-impact-list b { font-size: 11px; }.delete-impact-list small, .muted-copy, .delete-blocked-note { color: var(--muted); font-size: 10px; line-height: 1.5; }.delete-impact-list code { color: var(--accent-strong); }.muted-copy, .delete-blocked-note { margin: 0; }.delete-blocked-note { margin-top: 14px; color: var(--danger); }
</style>
