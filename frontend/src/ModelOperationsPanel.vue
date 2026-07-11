<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth, type Deployment, type Endpoint } from './api'

const props = defineProps<{ endpoint: Endpoint; deployments: Deployment[]; auth: AdminAuth }>()
const emit = defineEmits<{ changed: [] }>()

type Profile = { id: string; name: string; modelKey: string; configJson: string }
type Operation = { id: string; modelKey: string; operationType: string; status: string; message?: string | null; createdAt: string }
type Preflight = { displayName: string; modelSizeBytes: number; heuristicMemoryBytes: number; maxContextLength: number; requestedContextLength: number; compatible: boolean; warnings: string[] }

const profiles = ref<Profile[]>([])
const operations = ref<Operation[]>([])
const busy = ref(false)
const message = ref('')
const loadOpen = ref(false)
const downloadOpen = ref(false)
const preflight = ref<Preflight | null>(null)
const command = ref({ modelKey: '', contextLength: null as number | null, flashAttention: true })
const profileName = ref('')
const download = ref({ modelKey: '', quantization: '' })

const modelOptions = computed(() => props.deployments.map(item => ({ key: item.providerModelId, label: item.displayName || item.providerModelId, loaded: item.loaded })))
function bytes(value: number) {
  if (!value) return '확인 불가'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']; let size = value; let unit = 0
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++ }
  return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}`
}
function resetCommand(modelKey?: string) {
  const deployment = props.deployments.find(item => item.providerModelId === modelKey) ?? props.deployments[0]
  command.value = { modelKey: modelKey ?? deployment?.providerModelId ?? '', contextLength: deployment?.contextLength ?? null, flashAttention: true }
  preflight.value = null; profileName.value = ''
}
async function refresh() {
  try {
    const [nextProfiles, nextOperations] = await Promise.all([
      adminFetch<Profile[]>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-profiles`, props.auth),
      adminFetch<Operation[]>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations`, props.auth)
    ])
    profiles.value = nextProfiles; operations.value = nextOperations
  } catch (error) { message.value = error instanceof Error ? error.message : '모델 작업 정보를 불러오지 못했습니다.' }
}
async function inspect() {
  busy.value = true; message.value = ''
  try { preflight.value = await adminFetch<Preflight>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/preflight`, props.auth, { method: 'POST', body: JSON.stringify(command.value) }) }
  catch (error) { message.value = error instanceof Error ? error.message : '사전 점검에 실패했습니다.' }
  finally { busy.value = false }
}
async function runLoad() {
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/load`, props.auth, { method: 'POST', body: JSON.stringify(command.value) })
    message.value = operation.message ?? '로드 작업을 요청했습니다.'; loadOpen.value = false; await refresh(); emit('changed')
  } catch (error) { message.value = error instanceof Error ? error.message : '모델 로드에 실패했습니다.' }
  finally { busy.value = false }
}
async function saveProfile() {
  if (!profileName.value.trim()) { message.value = '프로필 이름을 입력하세요.'; return }
  busy.value = true; message.value = ''
  try {
    await adminFetch<Profile>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-profiles`, props.auth, { method: 'POST', body: JSON.stringify({ name: profileName.value.trim(), command: command.value }) })
    message.value = '설정 프로필을 저장했습니다.'; await refresh()
  } catch (error) { message.value = error instanceof Error ? error.message : '프로필 저장에 실패했습니다.' }
  finally { busy.value = false }
}
async function applyProfile(profile: Profile) {
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-profiles/${profile.id}/apply`, props.auth, { method: 'POST' })
    message.value = operation.message ?? `${profile.name} 프로필을 적용했습니다.`; await refresh(); emit('changed')
  } catch (error) { message.value = error instanceof Error ? error.message : '프로필 적용에 실패했습니다.' }
  finally { busy.value = false }
}
async function unload(modelKey: string) {
  if (!window.confirm(`'${modelKey}' 모델을 메모리에서 내리시겠습니까? 진행 중인 요청이 있으면 작업이 보류됩니다.`)) return
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/unload`, props.auth, { method: 'POST', body: JSON.stringify({ modelKey }) })
    message.value = operation.message ?? '언로드 작업을 완료했습니다.'; await refresh(); emit('changed')
  } catch (error) { message.value = error instanceof Error ? error.message : '모델 언로드에 실패했습니다.' }
  finally { busy.value = false }
}
async function requestDownload() {
  busy.value = true; message.value = ''
  try {
    const operation = await adminFetch<Operation>(`/api/admin/runtime-endpoints/${props.endpoint.id}/model-operations/download`, props.auth, { method: 'POST', body: JSON.stringify(download.value) })
    message.value = operation.message ?? '다운로드 작업을 요청했습니다.'; downloadOpen.value = false; await refresh()
  } catch (error) { message.value = error instanceof Error ? error.message : '다운로드 요청에 실패했습니다.' }
  finally { busy.value = false }
}
function openLoad(modelKey?: string) { resetCommand(modelKey); loadOpen.value = true }
watch(() => props.endpoint.id, refresh)
onMounted(refresh)
</script>

<template>
  <section class="model-operations">
    <div class="section-divider"><span>MODEL OPERATIONS</span><div class="operation-actions"><button class="secondary-button" :disabled="busy" @click="openLoad()">모델 로드 설정</button><button class="text-button" :disabled="busy" @click="downloadOpen = true">다운로드 요청</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <div class="operation-grid">
      <article class="operation-card">
        <header><div><span class="card-kicker">SAFE CONTROL</span><h3>메모리 모델 상태</h3></div><button class="text-button" :disabled="busy" @click="refresh">새로고침</button></header>
        <div v-if="deployments.length" class="model-state-list"><div v-for="deployment in deployments" :key="deployment.id" class="model-state-row"><div><strong>{{ deployment.displayName }}</strong><small class="mono">{{ deployment.providerModelId }}</small></div><div class="state-actions"><span class="status-chip tiny" :class="deployment.loaded ? 'healthy' : 'unknown'">{{ deployment.loaded ? 'LOADED' : 'NOT LOADED' }}</span><button class="text-button" :disabled="busy" @click="deployment.loaded ? unload(deployment.providerModelId) : openLoad(deployment.providerModelId)">{{ deployment.loaded ? '언로드' : '로드' }}</button></div></div></div>
        <p v-else class="field-help">먼저 ‘모델 동기화’를 실행하면 이 Runtime에서 발견된 모델을 선택할 수 있습니다.</p>
      </article>
      <article class="operation-card">
        <header><div><span class="card-kicker">CONFIGURATION PROFILES</span><h3>저장된 로드 프로필</h3></div><span class="count-badge">{{ profiles.length }}</span></header>
        <div v-if="profiles.length" class="profile-list"><div v-for="profile in profiles" :key="profile.id"><div><strong>{{ profile.name }}</strong><small class="mono">{{ profile.modelKey }}</small></div><button class="text-button" :disabled="busy" @click="applyProfile(profile)">적용</button></div></div>
        <p v-else class="field-help">컨텍스트와 Flash Attention 설정을 프로필로 저장할 수 있습니다.</p>
      </article>
    </div>
    <article class="agentless-note"><strong>현재는 Node Agent 없이 운영 중입니다.</strong><span>GPU Offload, CPU Thread Pool, Unified KV Cache, 모델 메모리 유지, K/V Cache 양자화는 이 화면에서 변경하지 않으며 해당 GPU 서버의 LM Studio 기본값을 그대로 사용합니다.</span></article>
    <article class="operation-card operation-history"><header><div><span class="card-kicker">AUDIT TRAIL</span><h3>최근 모델 작업</h3></div></header><div v-if="operations.length" class="history-list"><div v-for="operation in operations.slice(0, 6)" :key="operation.id"><span class="status-chip tiny" :class="operation.status === 'SUCCEEDED' ? 'healthy' : operation.status === 'FAILED' ? 'unhealthy' : 'suspect'">{{ operation.status }}</span><strong>{{ operation.operationType }} · {{ operation.modelKey }}</strong><small>{{ operation.message || '처리 중' }} · {{ new Date(operation.createdAt).toLocaleString() }}</small></div></div><p v-else class="field-help">아직 기록된 모델 작업이 없습니다.</p></article>

    <BaseModal :open="loadOpen" title="LM Studio 모델 로드 설정" description="요청한 핵심 항목만 적용합니다. 나머지 고급 설정은 GPU 서버에 저장된 LM Studio 기본값을 사용합니다." size="lg" @close="loadOpen = false">
      <div class="modal-form"><label class="field">모델<select v-model="command.modelKey" required><option disabled value="">동기화된 모델 선택</option><option v-for="option in modelOptions" :key="option.key" :value="option.key">{{ option.label }} {{ option.loaded ? '(로드됨)' : '' }}</option></select></label><div class="form-grid"><label class="field">컨텍스트 길이<input v-model.number="command.contextLength" type="number" min="1" placeholder="LM Studio 기본값" /><small class="field-help">비워 두면 모델/LM Studio 기본 설정을 사용합니다.</small></label><label class="field">프로필 이름<input v-model.trim="profileName" placeholder="예: gemma-e4b-32k" /><small class="field-help">선택: 현재 핵심 설정만 저장합니다.</small></label></div><label class="toggle-field"><span>Flash Attention<small>지원 모델에서는 메모리 사용량과 생성 속도에 영향을 줍니다.</small></span><input v-model="command.flashAttention" type="checkbox" /></label><div v-if="preflight" class="preflight-result" :class="{ warning: !preflight.compatible }"><strong>{{ preflight.displayName }}</strong><span>모델 {{ bytes(preflight.modelSizeBytes) }} · 보수적 필요 메모리 약 {{ bytes(preflight.heuristicMemoryBytes) }}</span><span>컨텍스트 {{ preflight.requestedContextLength.toLocaleString() }} / 최대 {{ preflight.maxContextLength ? preflight.maxContextLength.toLocaleString() : '미확인' }}</span><small v-for="warning in preflight.warnings" :key="warning">{{ warning }}</small></div></div>
      <template #footer><button class="secondary-button" :disabled="busy || !command.modelKey" @click="inspect">사전 점검</button><button class="secondary-button" :disabled="busy || !profileName" @click="saveProfile">프로필 저장</button><button class="primary-button" :disabled="busy || !command.modelKey || (preflight !== null && !preflight.compatible)" @click="runLoad">Drain 후 로드</button></template>
    </BaseModal>
    <BaseModal :open="downloadOpen" title="LM Studio 모델 다운로드" description="다운로드가 끝나면 ‘모델 동기화’ 후 로드 설정을 적용하세요." @close="downloadOpen = false"><div class="modal-form"><label class="field">모델 식별자<input v-model.trim="download.modelKey" placeholder="publisher/model-name" required /></label><label class="field">양자화 (선택)<input v-model.trim="download.quantization" placeholder="Q4_K_M" /></label></div><template #footer><button class="secondary-button" @click="downloadOpen = false">취소</button><button class="primary-button" :disabled="busy || !download.modelKey" @click="requestDownload">다운로드 요청</button></template></BaseModal>
  </section>
</template>

<style scoped>
.model-operations { display: grid; gap: 16px; margin-top: 22px; }
.operation-actions, .state-actions { display: flex; gap: 8px; align-items: center; }
.operation-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(280px, .8fr); gap: 14px; }
.operation-card, .agentless-note { padding: 17px; border: 1px solid var(--border); border-radius: 16px; background: var(--surface-2); }
.operation-card > header { display: flex; justify-content: space-between; align-items: start; gap: 12px; margin-bottom: 14px; }
.operation-card h3 { margin: 3px 0 0; font-size: 15px; }
.model-state-list, .profile-list, .history-list { display: grid; gap: 8px; }
.model-state-row, .profile-list > div { display: flex; min-width: 0; justify-content: space-between; gap: 10px; align-items: center; padding: 10px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface); }
.model-state-row > div:first-child, .profile-list > div > div { display: grid; min-width: 0; gap: 4px; }
.mono { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agentless-note { display: grid; gap: 5px; border-color: var(--accent-border); background: var(--accent-dim); color: var(--text-soft); font-size: 11px; line-height: 1.6; }
.agentless-note strong { color: var(--accent-strong); }
.history-list > div { display: grid; grid-template-columns: auto 1fr; column-gap: 9px; row-gap: 3px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--border); }
.history-list > div:last-child { border-bottom: 0; }
.history-list small { grid-column: 2; color: var(--muted); }
.preflight-result { display: grid; gap: 5px; padding: 12px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); color: var(--text); }
.preflight-result.warning { border-color: var(--warning-border); background: var(--warning-dim); }
.preflight-result small { color: var(--muted); }
@media (max-width: 900px) { .operation-grid { grid-template-columns: 1fr; } }
@media (max-width: 580px) { .model-state-row, .profile-list > div { align-items: flex-start; flex-direction: column; } .operation-actions { flex-wrap: wrap; } }
</style>
