<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

type Service = { serviceKey: string; displayName: string }
type Project = { id: string; name: string; status: string; canManage: boolean; services: Service[] }
type ApiKey = { id: string; name: string; keyPrefix: string; status: string; expiresAt?: string | null; lastUsedAt?: string | null; createdAt: string }

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
const projects = ref<Project[]>([])
const selectedId = ref(sessionStorage.getItem('aiconnect.portal.projectId') ?? '')
const keys = ref<ApiKey[]>([])
const busy = ref(false)
const message = ref('')
const keyOpen = ref(false)
const secretOpen = ref(false)
const keyName = ref('')
const expiresAt = ref('')
const issuedSecret = ref('')
const selected = computed(() => projects.value.find(project => project.id === selectedId.value) ?? null)

async function loadProjects() {
  if (!props.organizationId) { projects.value = []; keys.value = []; return }
  busy.value = true
  try {
    projects.value = await adminFetch<Project[]>(`/api/portal/organizations/${props.organizationId}/projects`, props.auth)
    if (!projects.value.some(project => project.id === selectedId.value)) selectedId.value = projects.value[0]?.id ?? ''
    if (selectedId.value) await loadKeys()
    else keys.value = []
    if (!projects.value.length) message.value = '현재 계정에 연결된 프로젝트가 없습니다. 프로젝트 관리자에게 팀과 프로젝트 권한을 요청하세요.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '프로젝트를 불러오지 못했습니다.'
  } finally { busy.value = false }
}

async function loadKeys() {
  if (!selectedId.value) { keys.value = []; return }
  try { keys.value = await adminFetch<ApiKey[]>(`/api/portal/projects/${selectedId.value}/api-keys`, props.auth) }
  catch (error) { message.value = error instanceof Error ? error.message : 'API 키를 불러오지 못했습니다.' }
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
      method: 'POST', body: JSON.stringify({ name: keyName.value.trim(), expiresAt: expiresAt.value ? new Date(expiresAt.value).toISOString() : null })
    })
    issuedSecret.value = result.secret
    keyName.value = ''
    expiresAt.value = ''
    keyOpen.value = false
    secretOpen.value = true
    await loadKeys()
  } catch (error) { message.value = error instanceof Error ? error.message : 'API 키 발급에 실패했습니다.' }
  finally { busy.value = false }
}

async function copySecret() {
  await navigator.clipboard.writeText(issuedSecret.value)
  message.value = 'API 키를 복사했습니다. 키는 이 창을 닫으면 다시 볼 수 없습니다.'
}

watch(() => props.organizationId, loadProjects)
onMounted(loadProjects)
</script>

<template>
  <section class="page-stack developer-portal">
    <div class="page-hero">
      <div>
        <p class="eyebrow">DEVELOPER PORTAL</p>
        <h1>내 API</h1>
        <p>프로젝트를 선택하고 사용할 모델과 API 키를 확인하세요. 인프라와 라우팅 설정은 관리자만 관리합니다.</p>
      </div>
      <div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="loadProjects">↻ 새로고침</button><button v-if="selected?.canManage" class="primary-button" @click="keyOpen = true">+ API 키 발급</button></div>
    </div>

    <p v-if="message" class="inline-alert">{{ message }}</p>

    <article class="surface-card project-switcher">
      <div><span class="card-kicker">MY PROJECT</span><h2>{{ selected?.name ?? '프로젝트 선택' }}</h2><small>API 키는 프로젝트 단위로 관리됩니다.</small></div>
      <select v-model="selectedId" @change="selectProject"><option value="">프로젝트 선택</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.status }}</option></select>
      <span v-if="selected" class="status-chip healthy">{{ selected.canManage ? '키 관리 가능' : '조회 전용' }}</span>
    </article>

    <template v-if="selected">
      <div class="developer-summary-grid">
        <article class="surface-card simple-card"><span class="card-kicker">AVAILABLE MODELS</span><h2>사용 가능한 모델</h2><div v-if="selected.services.length" class="model-chip-list"><span v-for="service in selected.services" :key="service.serviceKey" class="model-chip"><b>{{ service.serviceKey }}</b><small>{{ service.displayName }}</small></span></div><p v-else>이 프로젝트에 연결된 모델이 없습니다. 프로젝트 관리자에게 모델 권한을 요청하세요.</p></article>
        <article class="surface-card simple-card"><span class="card-kicker">CONNECT</span><h2>빠른 연결</h2><p>OpenAI SDK에서 Base URL을 AICONNECT 주소로 바꾸고, 아래에서 발급한 API 키와 논리 모델명을 사용하세요.</p><code class="portal-code">model: '{{ selected.services[0]?.serviceKey ?? 'your-logical-model' }}'</code></article>
      </div>

      <article class="surface-card">
        <header class="card-header"><div><span class="card-kicker">PROJECT CREDENTIALS</span><h2>API 키</h2></div><button v-if="selected.canManage" class="primary-button" @click="keyOpen = true">+ API 키 발급</button></header>
        <div v-if="keys.length" class="data-table-wrap"><table class="data-table"><thead><tr><th>이름</th><th>키 식별자</th><th>상태</th><th>마지막 사용</th><th>만료</th></tr></thead><tbody><tr v-for="key in keys" :key="key.id"><td><strong>{{ key.name }}</strong><small>{{ new Date(key.createdAt).toLocaleDateString() }} 생성</small></td><td class="mono">{{ key.keyPrefix }}••••••••</td><td><span class="status-chip tiny" :class="key.status === 'ACTIVE' ? 'healthy' : 'unknown'">{{ key.status }}</span></td><td>{{ key.lastUsedAt ? new Date(key.lastUsedAt).toLocaleString() : '사용 전' }}</td><td>{{ key.expiresAt ? new Date(key.expiresAt).toLocaleDateString() : '만료 없음' }}</td></tr></tbody></table></div>
        <div v-else class="empty-state"><span>⌘</span><h3>아직 발급된 API 키가 없습니다</h3><p v-if="selected.canManage">서비스에 연결할 첫 API 키를 발급하세요.</p><p v-else>프로젝트 관리자에게 API 키 발급을 요청하세요.</p><button v-if="selected.canManage" class="text-button" @click="keyOpen = true">첫 API 키 발급</button></div>
      </article>
    </template>

    <BaseModal :open="keyOpen" title="API 키 발급" description="키 원문은 발급 직후 한 번만 표시됩니다. 안전한 비밀 관리 도구에 보관하세요." size="sm" @close="keyOpen = false"><div class="modal-form"><label class="field">키 이름<input v-model.trim="keyName" placeholder="production-backend" /></label><label class="field">만료일 (선택)<input v-model="expiresAt" type="datetime-local" /></label></div><template #footer><button class="secondary-button" @click="keyOpen = false">취소</button><button class="primary-button" :disabled="busy || !keyName" @click="issueKey">키 발급</button></template></BaseModal>
    <BaseModal :open="secretOpen" title="API 키를 발급했습니다" description="이 창을 닫으면 키 원문을 다시 표시할 수 없습니다." @close="secretOpen = false"><div class="secret-reveal"><span>PROJECT API KEY</span><code>{{ issuedSecret }}</code><button class="secondary-button" @click="copySecret">복사</button></div><template #footer><button class="primary-button" @click="secretOpen = false">안전하게 보관했습니다</button></template></BaseModal>
  </section>
</template>

<style scoped>
.developer-summary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.simple-card { min-height: 184px; padding: 22px; }
.simple-card h2 { margin: 7px 0 10px; font-size: 18px; }
.simple-card p { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.7; }
.model-chip-list { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 16px; }
.model-chip { min-width: 132px; padding: 10px 12px; display: grid; gap: 4px; border: 1px solid var(--accent-border); border-radius: 10px; background: var(--accent-dim); }
.model-chip b { color: var(--accent-strong); font-size: 12px; }
.model-chip small { color: var(--text-soft); font-size: 10px; }
.portal-code { display: block; margin-top: 16px; padding: 11px 12px; overflow-x: auto; border: 1px solid var(--border); border-radius: 9px; background: var(--surface-2); color: var(--accent-strong); font-size: 11px; }
@media (max-width: 760px) { .developer-summary-grid { grid-template-columns: 1fr; } .simple-card { min-height: 0; } }
</style>
