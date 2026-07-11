<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

type Service = { serviceKey: string; displayName: string }
type Project = { id: string; name: string; status: string; canIssueApiKeys: boolean; services: Service[] }
type ApiKey = {
  id: string; name: string; keyPrefix: string; status: string
  expiresAt?: string | null; lastUsedAt?: string | null; createdAt: string
  canRevoke: boolean; canDelete: boolean
}
type ConnectionEndpoint = { scope: 'INTERNAL' | 'EXTERNAL'; label: string; url: string }

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const projects = ref<Project[]>([])
const selectedId = ref(sessionStorage.getItem('aiconnect.portal.projectId') ?? '')
const keys = ref<ApiKey[]>([])
const connections = ref<ConnectionEndpoint[]>([])
const issuedConnections = ref<ConnectionEndpoint[]>([])
const busy = ref(false)
const message = ref('')
const keyOpen = ref(false)
const secretOpen = ref(false)
const revocationOpen = ref(false)
const recordDeletionOpen = ref(false)
const keyToRevoke = ref<ApiKey | null>(null)
const keyRecordToDelete = ref<ApiKey | null>(null)
const keyName = ref('')
const expiresAt = ref('')
const issuedSecret = ref('')
const issuedModels = ref<Service[]>([])
const selected = computed(() => projects.value.find(project => project.id === selectedId.value) ?? null)

async function loadConnections() {
  try {
    const response = await adminFetch<{ endpoints: ConnectionEndpoint[] }>('/api/portal/connection', props.auth)
    connections.value = response.endpoints
  } catch (error) {
    connections.value = []
    message.value = error instanceof Error ? error.message : '연결 주소를 불러오지 못했습니다.'
  }
}

async function loadProjects() {
  if (!props.organizationId) { projects.value = []; keys.value = []; return }
  busy.value = true
  try {
    projects.value = await adminFetch<Project[]>(`/api/portal/organizations/${props.organizationId}/projects`, props.auth)
    if (!projects.value.some(project => project.id === selectedId.value)) selectedId.value = projects.value[0]?.id ?? ''
    if (selectedId.value) await loadKeys()
    else keys.value = []
    if (!projects.value.length) message.value = '현재 계정에 연결된 프로젝트가 없습니다. 관리자에게 프로젝트 권한을 요청하세요.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '프로젝트를 불러오지 못했습니다.'
  } finally { busy.value = false }
}

async function loadKeys() {
  if (!selectedId.value) { keys.value = []; return }
  try {
    keys.value = await adminFetch<ApiKey[]>(`/api/portal/projects/${selectedId.value}/api-keys`, props.auth)
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'API 키를 불러오지 못했습니다.'
  }
}

async function selectProject() {
  if (selectedId.value) sessionStorage.setItem('aiconnect.portal.projectId', selectedId.value)
  else sessionStorage.removeItem('aiconnect.portal.projectId')
  await loadKeys()
}

async function issueKey() {
  if (!selected.value || !keyName.value.trim()) return
  busy.value = true
  try {
    const result = await adminFetch<{ secret: string }>(`/api/portal/projects/${selected.value.id}/api-keys`, props.auth, {
      method: 'POST',
      body: JSON.stringify({ name: keyName.value.trim(), expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null })
    })
    await loadConnections()
    issuedSecret.value = result.secret
    issuedModels.value = [...selected.value.services]
    issuedConnections.value = [...connections.value]
    keyName.value = ''
    expiresAt.value = ''
    keyOpen.value = false
    secretOpen.value = true
    await loadKeys()
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'API 키 발급에 실패했습니다.'
  } finally { busy.value = false }
}

function openRevocation(key: ApiKey) {
  keyToRevoke.value = key
  revocationOpen.value = true
}

async function revokeKey() {
  if (!selected.value || !keyToRevoke.value) return
  busy.value = true
  try {
    const revokedId = keyToRevoke.value.id
    const revokedName = keyToRevoke.value.name
    await adminFetch(`/api/portal/projects/${selected.value.id}/api-keys/${revokedId}`, props.auth, { method: 'DELETE' })
    keys.value = keys.value.map(key => key.id === revokedId ? { ...key, status: 'REVOKED', canRevoke: false, canDelete: true } : key)
    message.value = `'${revokedName}' API 키를 즉시 폐기했습니다. 이 키로는 더 이상 요청할 수 없습니다.`
    revocationOpen.value = false
    keyToRevoke.value = null
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'API 키 폐기에 실패했습니다.'
  } finally { busy.value = false }
}

function openRecordDeletion(key: ApiKey) {
  keyRecordToDelete.value = key
  recordDeletionOpen.value = true
}

async function deleteKeyRecord() {
  if (!selected.value || !keyRecordToDelete.value) return
  busy.value = true
  try {
    const deletedId = keyRecordToDelete.value.id
    const deletedName = keyRecordToDelete.value.name
    await adminFetch(`/api/portal/projects/${selected.value.id}/api-keys/${deletedId}/record`, props.auth, { method: 'DELETE' })
    keys.value = keys.value.filter(key => key.id !== deletedId)
    message.value = `'${deletedName}' API 키 기록을 영구 삭제했습니다. 사용량과 장애 통계는 보존됩니다.`
    recordDeletionOpen.value = false
    keyRecordToDelete.value = null
  } catch (error) {
    message.value = error instanceof Error ? error.message : 'API 키 기록 삭제에 실패했습니다.'
  } finally { busy.value = false }
}

async function copyValue(value: string, label: string) {
  await navigator.clipboard.writeText(value)
  message.value = `${label}을(를) 복사했습니다.`
}

watch(() => props.organizationId, () => { void loadProjects() })
onMounted(() => { void loadProjects(); void loadConnections() })
</script>

<template>
  <section class="page-stack developer-portal">
    <div class="page-hero">
      <div>
        <p class="eyebrow">DEVELOPER PORTAL</p>
        <h1>내 API</h1>
        <p>프로젝트를 선택하고 사용 모델과 API 키를 확인하세요. 프로젝트에 접근 가능한 사용자는 자신의 API 키를 발급하고 폐기할 수 있습니다.</p>
      </div>
      <div class="hero-actions">
        <button class="secondary-button" :disabled="busy" @click="loadProjects">새로고침</button>
        <button v-if="selected?.canIssueApiKeys" class="primary-button" @click="keyOpen = true">+ API 키 발급</button>
      </div>
    </div>
    <p v-if="message" class="inline-alert">{{ message }}</p>

    <article class="surface-card project-switcher">
      <div>
        <span class="card-kicker">MY PROJECT</span>
        <h2>{{ selected?.name ?? '프로젝트 선택' }}</h2>
        <small>API 키는 프로젝트 단위로 관리되며, 원문은 발급 직후 한 번만 표시됩니다.</small>
      </div>
      <select v-model="selectedId" @change="selectProject">
        <option value="">프로젝트 선택</option>
        <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.status }}</option>
      </select>
      <span v-if="selected" class="status-chip healthy">{{ selected.canIssueApiKeys ? '키 발급 가능' : '조회 전용' }}</span>
    </article>

    <template v-if="selected">
      <div class="developer-summary-grid">
        <article class="surface-card simple-card">
          <span class="card-kicker">AVAILABLE MODELS</span><h2>사용 가능한 모델</h2>
          <div v-if="selected.services.length" class="model-chip-list">
            <span v-for="service in selected.services" :key="service.serviceKey" class="model-chip"><b>{{ service.serviceKey }}</b><small>{{ service.displayName }}</small></span>
          </div>
          <p v-else>이 프로젝트에 연결된 모델이 없습니다. 관리자에게 모델 권한을 요청하세요.</p>
        </article>
        <article class="surface-card simple-card">
          <span class="card-kicker">CONNECT</span><h2>연결 정보</h2>
          <p>발급 후에는 접속 위치에 맞는 Base URL과 논리 모델명을 복사해 OpenAI SDK에 사용하세요.</p>
          <div v-if="connections.length" class="quick-endpoints"><code v-for="endpoint in connections" :key="endpoint.scope">{{ endpoint.scope === 'INTERNAL' ? '내부망' : '외부' }}: {{ endpoint.url }}</code></div>
          <small v-else class="configuration-warning">관리자가 내부망/외부 Base URL을 아직 설정하지 않았습니다.</small>
        </article>
      </div>

      <article class="surface-card">
        <header class="card-header">
          <div><span class="card-kicker">PROJECT CREDENTIALS</span><h2>API 키</h2></div>
          <button v-if="selected.canIssueApiKeys" class="primary-button" @click="keyOpen = true">+ API 키 발급</button>
        </header>
        <div v-if="keys.length" class="data-table-wrap">
          <table class="data-table">
            <thead><tr><th>이름</th><th>키 식별자</th><th>상태</th><th>마지막 사용</th><th>만료</th><th>관리</th></tr></thead>
            <tbody>
              <tr v-for="key in keys" :key="key.id">
                <td><strong>{{ key.name }}</strong><small>{{ new Date(key.createdAt).toLocaleDateString() }} 생성</small></td>
                <td class="mono">{{ key.keyPrefix }}••••••••</td>
                <td><span class="status-chip tiny" :class="key.status === 'ACTIVE' ? 'healthy' : 'unknown'">{{ key.status }}</span></td>
                <td>{{ key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleString() : '사용 전' }}</td>
                <td>{{ key.expiresAt ? new Date(key.expiresAt).toLocaleDateString() : '만료 없음' }}</td>
                <td>
                  <button v-if="key.canRevoke && key.status === 'ACTIVE'" class="text-button danger-text" :disabled="busy" @click="openRevocation(key)">폐기</button>
                  <button v-else-if="key.canDelete && key.status === 'REVOKED'" class="text-button danger-text" :disabled="busy" @click="openRecordDeletion(key)">기록 삭제</button>
                  <small v-else-if="key.status === 'REVOKED'">폐기됨</small>
                  <small v-else-if="key.status !== 'ACTIVE'">처리 완료</small>
                  <small v-else>폐기 권한 없음</small>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty-state"><span>⌘</span><h3>아직 발급한 API 키가 없습니다</h3><p>서비스에 연결할 첫 API 키를 발급하세요.</p><button v-if="selected.canIssueApiKeys" class="text-button" @click="keyOpen = true">첫 API 키 발급</button></div>
      </article>
    </template>

    <BaseModal :open="keyOpen" title="API 키 발급" description="키 원문과 연결 정보는 발급 직후 한 번만 표시됩니다. 안전한 비밀 관리 도구에 보관하세요." size="sm" @close="keyOpen = false">
      <div class="modal-form"><label class="field">키 이름<input v-model.trim="keyName" placeholder="production-backend" /></label><label class="field">만료일 (선택)<input v-model="expiresAt" type="datetime-local" /></label></div>
      <template #footer><button class="secondary-button" @click="keyOpen = false">취소</button><button class="primary-button" :disabled="busy || !keyName" @click="issueKey">키 발급</button></template>
    </BaseModal>

    <BaseModal :open="secretOpen" title="API 키와 연결 정보를 준비했습니다" description="API 키 원문은 이 창을 닫으면 다시 표시할 수 없습니다." @close="secretOpen = false">
      <div class="connection-reveal">
        <div class="connection-value"><span>PROJECT API KEY</span><code>{{ issuedSecret }}</code><button class="secondary-button" @click="copyValue(issuedSecret, 'API 키')">복사</button></div>
        <template v-if="issuedConnections.length">
          <div v-for="endpoint in issuedConnections" :key="endpoint.scope" class="connection-value"><span>{{ endpoint.scope === 'INTERNAL' ? 'INTERNAL NETWORK / VPN BASE URL' : 'EXTERNAL HTTPS BASE URL' }}</span><code>{{ endpoint.url }}</code><button class="secondary-button" @click="copyValue(endpoint.url, endpoint.label + ' Base URL')">복사</button><small>{{ endpoint.scope === 'INTERNAL' ? '사내망 또는 VPN/Tailscale에 연결된 개발 서버에서 사용하세요.' : '회사 외부 또는 인터넷에서 HTTPS로 접속할 때 사용하세요.' }}</small></div>
        </template>
        <div v-else class="connection-value unconfigured"><span>OPENAI BASE URL</span><small>관리자가 내부망/외부 Base URL을 설정하지 않았습니다. localhost는 API 호출자의 컴퓨터를 가리키므로 다른 서버나 외부 사용자에게 안내하면 안 됩니다.</small></div>
        <div class="connection-value"><span>ALLOWED MODEL{{ issuedModels.length === 1 ? '' : 'S' }}</span><div v-if="issuedModels.length" class="issued-model-list"><div v-for="service in issuedModels" :key="service.serviceKey"><code>{{ service.serviceKey }}</code><button class="secondary-button" @click="copyValue(service.serviceKey, '모델명')">복사</button></div></div><small v-else>이 프로젝트에 연결된 모델이 없습니다. 관리자에게 모델 권한을 요청하세요.</small></div>
      </div>
      <template #footer><button class="primary-button" @click="secretOpen = false">안전하게 보관했습니다</button></template>
    </BaseModal>

    <BaseModal :open="revocationOpen" title="API 키 폐기" description="노출되었거나 더 이상 사용하지 않는 키는 즉시 무효화합니다." size="sm" @close="revocationOpen = false">
      <div class="revocation-note"><span>폐기 대상</span><strong>{{ keyToRevoke?.name }}</strong><code>{{ keyToRevoke?.keyPrefix }}••••••••</code><p>폐기 후에는 이 키로 새 API 요청을 보낼 수 없으며 다시 활성화할 수 없습니다. 필요하면 새 키를 발급하세요.</p></div>
      <template #footer><button class="secondary-button" :disabled="busy" @click="revocationOpen = false">취소</button><button class="secondary-button danger-text" :disabled="busy" @click="revokeKey">즉시 폐기</button></template>
    </BaseModal>

    <BaseModal :open="recordDeletionOpen" title="폐기된 API 키 기록 삭제" description="한 번 삭제한 API 키 기록은 되돌릴 수 없습니다." size="sm" @close="recordDeletionOpen = false">
      <div class="revocation-note"><span>영구 삭제 대상</span><strong>{{ keyRecordToDelete?.name }}</strong><code>{{ keyRecordToDelete?.keyPrefix }}••••••••</code><p>API 키 이름, 식별자, 해시와 상태 기록이 영구 삭제됩니다. 요청·사용량·장애 통계는 보존되지만 이 API 키와의 연결은 제거됩니다.</p></div>
      <template #footer><button class="secondary-button" :disabled="busy" @click="recordDeletionOpen = false">취소</button><button class="secondary-button danger-text" :disabled="busy" @click="deleteKeyRecord">기록 영구 삭제</button></template>
    </BaseModal>
  </section>
</template>

<style scoped>
.developer-summary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.simple-card { min-height: 184px; padding: 22px; }.simple-card h2 { margin: 7px 0 10px; font-size: 18px; }.simple-card p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.model-chip-list { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 16px; }.model-chip { min-width: 132px; padding: 10px 12px; display: grid; gap: 4px; border: 1px solid var(--accent-border); border-radius: 10px; background: var(--accent-dim); }.model-chip b { color: var(--accent-strong); font-size: 12px; }.model-chip small { color: var(--text-soft); font-size: 10px; }
.quick-endpoints { margin-top: 12px; display: grid; gap: 6px; }.quick-endpoints code { padding: 8px; overflow-x: auto; border: 1px solid var(--border); border-radius: 7px; background: var(--surface-2); color: var(--accent-strong); font-size: 10px; }.configuration-warning { display: block; margin-top: 14px; color: var(--warning); font-size: 10px; line-height: 1.55; }
.connection-reveal { display: grid; gap: 12px; }.connection-value { padding: 14px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 9px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); }.connection-value.unconfigured { border-color: color-mix(in srgb, var(--warning) 35%, transparent); background: var(--warning-dim); }.connection-value > span { grid-column: 1 / -1; color: var(--muted); font-size: 9px; font-weight: 800; letter-spacing: .14em; }.connection-value > code, .issued-model-list code { min-width: 0; padding: 10px; overflow-x: auto; border-radius: 8px; background: var(--surface); color: var(--accent-strong); font-size: 10px; }.connection-value > small { grid-column: 1 / -1; color: var(--muted); font-size: 10px; line-height: 1.5; }.issued-model-list { grid-column: 1 / -1; display: grid; gap: 7px; }.issued-model-list > div { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 9px; }
.revocation-note { padding: 16px; display: grid; gap: 8px; border: 1px solid color-mix(in srgb, var(--danger) 38%, transparent); border-radius: 12px; background: var(--danger-dim); }.revocation-note span { color: var(--danger); font-size: 9px; font-weight: 800; letter-spacing: .12em; }.revocation-note strong { font-size: 14px; }.revocation-note code { padding: 8px; overflow: auto; border-radius: 7px; background: var(--surface); color: var(--text-soft); font-size: 10px; }.revocation-note p { margin: 2px 0 0; color: var(--text-soft); font-size: 11px; line-height: 1.6; }
@media (max-width: 760px) { .developer-summary-grid { grid-template-columns: 1fr; }.simple-card { min-height: 0; } }
</style>
