<script setup lang="ts">
import { ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'
import { formatDateTime } from './dateTime'

const props = defineProps<{ auth: AdminAuth; organizationId: string; platformAdmin: boolean }>()
type Project = { id: string; name: string }
type Entry = { id: string; projectId: string; requesterId: string; kind: string; displayName: string; baseUrl: string; runtimeType: string | null; reason: string; status: string; createdAt: string; resultId: string | null }
const projects = ref<Project[]>([]), entries = ref<Entry[]>([]), projectId = ref(''), busy = ref(false), message = ref('')
const form = ref({ kind: 'RUNTIME', displayName: '', baseUrl: '', runtimeType: 'LM_STUDIO', secret: '', reason: '' })
const decisionReasons = ref<Record<string,string>>({})
let generation = 0
async function load() {
  const seq = ++generation; entries.value = []; message.value = ''
  try {
    if (props.platformAdmin) {
      const items = await adminFetch<Entry[]>('/api/admin/platform/connection-requests', props.auth)
      if (seq === generation) entries.value = items
    } else if (props.organizationId) {
      const list = await adminFetch<Project[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth)
      if (seq !== generation) return
      projects.value = list
      if (!list.some(project => project.id === projectId.value)) projectId.value = list[0]?.id ?? ''
      if (projectId.value) {
        const items = await adminFetch<Entry[]>(`/api/portal/projects/${projectId.value}/connection-requests`, props.auth)
        if (seq === generation) entries.value = items
      }
    }
  } catch (error) { if (seq === generation) message.value = error instanceof Error ? error.message : '신청 내역 조회 실패' }
}
watch(() => [props.organizationId, props.platformAdmin, projectId.value], load, { immediate: true })
async function submit() {
  if (!projectId.value) return
  busy.value = true; message.value = ''
  try {
    await adminFetch(`/api/portal/projects/${projectId.value}/connection-requests`, props.auth, { method: 'POST', body: JSON.stringify({
      ...form.value, runtimeType: form.value.kind === 'RUNTIME' ? form.value.runtimeType : null,
      secret: form.value.secret || null
    }) })
    form.value.secret = ''; await load(); message.value = '신청을 제출했습니다. 최종 관리자 승인 전에는 서버에 연결하지 않습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '신청 실패' } finally { busy.value = false }
}
async function decide(item: Entry, approved: boolean) {
  if (!decisionReasons.value[item.id]?.trim()) { message.value = '승인 또는 반려 사유를 입력하세요.'; return }
  busy.value = true; message.value = ''
  try {
    await adminFetch(`/api/admin/platform/connection-requests/${item.id}/decision`, props.auth, { method: 'POST', body: JSON.stringify({ approved, reason: decisionReasons.value[item.id] }) })
    await load(); message.value = approved ? '등록을 승인했습니다. Runtime은 보안 분류와 프로젝트 사용 설정을 별도로 확인하세요.' : '신청을 반려했습니다.'
  } catch (error) { message.value = error instanceof Error ? error.message : '결정 처리 실패' } finally { busy.value = false }
}
</script>

<template>
  <article class="surface-card connection-requests">
    <header><span class="card-kicker">CONNECTION APPROVAL</span><h2>{{ platformAdmin ? '연결 등록 승인 대기' : 'GPU · 외부 API 연결 신청' }}</h2></header>
    <p>신청 단계에서는 서버에 연결하지 않습니다. Runtime 등록 승인 후에도 보안 분류와 프로젝트별 사용 승인을 별도로 설정해야 합니다.</p>
    <div v-if="!platformAdmin" class="request-fields">
      <label class="field">프로젝트<select v-model="projectId"><option value="">프로젝트 선택</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
      <label class="field">연결 종류<select v-model="form.kind"><option value="RUNTIME">GPU Runtime</option><option value="EXTERNAL_PROVIDER">외부 AI API</option></select></label>
      <label class="field">이름<input v-model.trim="form.displayName" maxlength="160" /></label>
      <label class="field">Endpoint URL<input v-model.trim="form.baseUrl" maxlength="500" placeholder="http://100.x.x.x:1234" /></label>
      <label v-if="form.kind === 'RUNTIME'" class="field">Runtime 종류<select v-model="form.runtimeType"><option value="LM_STUDIO">LM Studio</option><option value="OLLAMA">Ollama</option><option value="LLAMA_CPP">llama.cpp</option><option value="OPENAI_COMPATIBLE">OpenAI 호환</option></select></label>
      <label class="field">API 키 / 토큰 {{ form.kind === 'EXTERNAL_PROVIDER' ? '(필수)' : '(선택)' }}<input v-model="form.secret" type="password" autocomplete="new-password" maxlength="2000" /></label>
      <label class="field">사용 목적<input v-model.trim="form.reason" maxlength="1000" /></label>
      <button type="button" class="primary-button" :disabled="busy || !projectId || !form.displayName || !form.baseUrl || !form.reason || (form.kind === 'EXTERNAL_PROVIDER' && !form.secret)" @click="submit">등록 신청</button>
    </div>
    <div v-if="entries.length" class="request-list">
      <div v-for="item in entries" :key="item.id" class="request-item">
        <div><strong>{{ item.displayName }} · {{ item.kind }}</strong><p>{{ item.baseUrl }} · {{ item.status }} · {{ formatDateTime(item.createdAt) }}</p><p>프로젝트 {{ item.projectId }} · 목적 {{ item.reason }}</p></div>
        <div v-if="platformAdmin" class="request-actions"><label class="field">승인·반려 사유<input v-model.trim="decisionReasons[item.id]" maxlength="1000" /></label><button type="button" class="secondary-button" :disabled="busy" @click="decide(item, false)">반려</button><button type="button" class="primary-button" :disabled="busy" @click="decide(item, true)">등록 승인</button></div>
      </div>
    </div>
    <p v-else>신청 내역이 없습니다.</p><p role="status">{{ message }}</p>
  </article>
</template>
<style scoped>
.connection-requests{padding:24px}.connection-requests h2{margin:8px 0}.connection-requests p{font-size:12px;line-height:1.6;color:var(--muted);margin:8px 0}.request-fields{display:flex;gap:12px;flex-wrap:wrap;align-items:end}.request-fields .field{min-width:190px;flex:1}.request-list{margin-top:18px}.request-item{padding:14px 0;border-top:1px solid var(--border);display:flex;gap:12px;flex-wrap:wrap;justify-content:space-between}.request-actions{display:flex;gap:8px;align-items:end;flex-wrap:wrap}.connection-requests [role=status]{color:var(--foreground)}
</style>
