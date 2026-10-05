<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth; platformAdmin?: boolean }>()

type Mode = 'OFF' | 'MONITOR' | 'ENFORCE'
type Level = 'RELAXED' | 'BALANCED' | 'STRICT' | 'CUSTOM'
type Action = 'ALLOW' | 'REDACT' | 'LOCAL_ONLY' | 'BLOCK'
type Policy = {
  id?: string | null
  organizationId?: string | null
  scopeType: string
  scopeId?: string | null
  mode: Mode
  level: Level
  externalAction: Action
  allowExternalFailover: boolean
  detectSecrets: boolean
  detectPii: boolean
  detectFinancial: boolean
  detectConfidential: boolean
  detectMedia: boolean
  customPatternsJson: string
}
type Project = { id: string; name: string; status: string }
type Service = { id: string; serviceKey: string; displayName: string; enabled: boolean }
type ApiKey = { id: string; name: string; keyPrefix: string; status: string }
type Overview = { organizationId: string; policies: Policy[]; effective: Omit<Policy, 'scopeType'> & { scopeType?: string } }

const platformPolicy = ref<Policy | null>(null)
const projects = ref<Project[]>([])
const services = ref<Service[]>([])
const keys = ref<ApiKey[]>([])
const overview = ref<Overview | null>(null)
const selectedProjectId = ref('')
const selectedKeyId = ref('')
const selectedServiceId = ref('')
const projectPolicy = ref<Policy | null>(null)
const keyPolicy = ref<Policy | null>(null)
const servicePolicy = ref<Policy | null>(null)
const editorScope = ref<'PLATFORM' | 'ORGANIZATION' | 'PROJECT' | 'SERVICE' | 'API_KEY'>('ORGANIZATION')
const editor = ref<Policy>(blankPolicy('ORGANIZATION'))
const busy = ref(false)
const message = ref('')

function blankPolicy(scopeType: string): Policy {
  return { scopeType, mode: 'OFF', level: 'RELAXED', externalAction: 'ALLOW', allowExternalFailover: true, detectSecrets: true, detectPii: false, detectFinancial: false, detectConfidential: false, detectMedia: true, customPatternsJson: '[]' }
}
function copyPolicy(policy: Policy | null, scopeType: string) {
  editorScope.value = scopeType as typeof editorScope.value
  editor.value = { ...blankPolicy(scopeType), ...(policy ?? {}) }
}
function policyTitle(scope: string) { return scope === 'PLATFORM' ? '플랫폼 전역 정책' : scope === 'ORGANIZATION' ? '조직 기본 정책' : scope === 'PROJECT' ? '프로젝트 정책' : scope === 'SERVICE' ? 'AI 기능(서비스) 정책' : 'API 키 정책' }

async function load() {
  if (!props.organizationId) { overview.value = null; projects.value = []; services.value = []; keys.value = []; return }
  busy.value = true
  try {
    const [summary, projectItems, serviceItems] = await Promise.all([
      adminFetch<Overview>(`/api/admin/organizations/${props.organizationId}/data-protection`, props.auth),
      adminFetch<Project[]>(`/api/admin/organizations/${props.organizationId}/projects`, props.auth),
      adminFetch<Service[]>(`/api/admin/organizations/${props.organizationId}/services`, props.auth)
    ])
    overview.value = summary; projects.value = projectItems; services.value = serviceItems
    if (props.platformAdmin) platformPolicy.value = await adminFetch<Policy>('/api/admin/platform/data-protection', props.auth)
    if (!projects.value.some(item => item.id === selectedProjectId.value)) selectedProjectId.value = projects.value[0]?.id ?? ''
    copyPolicy(summary.policies.find(item => item.scopeType === 'ORGANIZATION') ?? null, 'ORGANIZATION')
    if (selectedProjectId.value) await loadProject(selectedProjectId.value)
  } catch (error) { message.value = error instanceof Error ? error.message : '데이터 보호 설정을 불러오지 못했습니다.' }
  finally { busy.value = false }
}

async function loadProject(id: string) {
  selectedProjectId.value = id; selectedKeyId.value = ''; keyPolicy.value = null
  try {
    const [policy, projectKeys] = await Promise.all([
      adminFetch<Policy>(`/api/admin/projects/${id}/data-protection`, props.auth),
      adminFetch<ApiKey[]>(`/api/admin/projects/${id}/api-keys`, props.auth)
    ])
    projectPolicy.value = policy; keys.value = projectKeys
    if (editorScope.value === 'PROJECT') copyPolicy(policy, 'PROJECT')
  } catch (error) { message.value = error instanceof Error ? error.message : '프로젝트 보호 설정을 불러오지 못했습니다.' }
}

async function selectProject(id: string) {
  await loadProject(id)
  if (selectedProjectId.value === id) copyPolicy(projectPolicy.value, 'PROJECT')
}

async function loadService(id: string) {
  selectedServiceId.value = id
  editorScope.value = 'SERVICE'
  try {
    servicePolicy.value = await adminFetch<Policy>(`/api/admin/services/${id}/data-protection`, props.auth)
    copyPolicy(servicePolicy.value, 'SERVICE')
  } catch (error) { message.value = error instanceof Error ? error.message : 'AI 기능 보호 설정을 불러오지 못했습니다.' }
}

async function selectKey(keyId: string) {
  if (!selectedProjectId.value || !keyId) return
  selectedKeyId.value = keyId; editorScope.value = 'API_KEY'
  try {
    keyPolicy.value = await adminFetch<Policy>(`/api/admin/projects/${selectedProjectId.value}/api-keys/${keyId}/data-protection`, props.auth)
    copyPolicy(keyPolicy.value, 'API_KEY')
  } catch (error) { message.value = error instanceof Error ? error.message : 'API 키 보호 설정을 불러오지 못했습니다.' }
}

function editPlatform() { copyPolicy(platformPolicy.value, 'PLATFORM') }

function editOrganization() { copyPolicy(overview.value?.policies.find(item => item.scopeType === 'ORGANIZATION') ?? null, 'ORGANIZATION') }
function editProject() { copyPolicy(projectPolicy.value, 'PROJECT') }

async function save() {
  if (!props.organizationId && editorScope.value !== 'PLATFORM') return
  let path = `/api/admin/organizations/${props.organizationId}/data-protection`
  if (editorScope.value === 'PLATFORM') path = '/api/admin/platform/data-protection'
  if (editorScope.value === 'PROJECT' && selectedProjectId.value) path = `/api/admin/projects/${selectedProjectId.value}/data-protection`
  if (editorScope.value === 'SERVICE' && selectedServiceId.value) path = `/api/admin/services/${selectedServiceId.value}/data-protection`
  if (editorScope.value === 'API_KEY' && selectedProjectId.value && selectedKeyId.value) path = `/api/admin/projects/${selectedProjectId.value}/api-keys/${selectedKeyId.value}/data-protection`
  busy.value = true
  try {
    const saved = await adminFetch<Policy>(path, props.auth, { method: 'PUT', body: JSON.stringify(editor.value) })
    if (editorScope.value === 'PLATFORM') platformPolicy.value = saved
    if (editorScope.value === 'ORGANIZATION') { overview.value = overview.value ? { ...overview.value, policies: [...overview.value.policies.filter(item => item.scopeType !== 'ORGANIZATION'), saved] } : overview.value }
    if (editorScope.value === 'PROJECT') projectPolicy.value = saved
    if (editorScope.value === 'SERVICE') servicePolicy.value = saved
    if (editorScope.value === 'API_KEY') keyPolicy.value = saved
    message.value = `${policyTitle(editorScope.value)}을 저장했습니다.`
  } catch (error) { message.value = error instanceof Error ? error.message : '정책 저장에 실패했습니다.' }
  finally { busy.value = false }
}

function applyPreset(level: Level) {
  editor.value.level = level
  if (level === 'RELAXED') { editor.value.mode = 'OFF'; editor.value.externalAction = 'ALLOW'; editor.value.allowExternalFailover = true }
  if (level === 'BALANCED') { editor.value.mode = 'MONITOR'; editor.value.externalAction = 'ALLOW'; editor.value.allowExternalFailover = true; editor.value.detectPii = true; editor.value.detectFinancial = true }
  if (level === 'STRICT') { editor.value.mode = 'ENFORCE'; editor.value.externalAction = 'LOCAL_ONLY'; editor.value.allowExternalFailover = false; editor.value.detectPii = true; editor.value.detectFinancial = true; editor.value.detectConfidential = true; editor.value.detectMedia = true }
}

watch(() => props.organizationId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <section class="page-stack data-protection-page">
    <div class="page-hero"><div><p class="eyebrow">SECURITY PLANE</p><h1>데이터 보호</h1><p>민감정보를 탐지하고, 프로젝트·API 키별로 외부 전송과 자동 Failover 경계를 설정합니다.</p></div><div class="hero-actions"><button class="secondary-button" :disabled="busy" @click="load">새로고침</button></div></div>
    <p v-if="message" class="inline-alert">{{ message }}</p>
    <article class="surface-card protection-explainer"><div><span class="card-kicker">REQUEST GATE</span><h2>요청이 외부 AI로 나가기 전 적용됩니다</h2><p>플랫폼 → 조직 → 프로젝트 → 서비스·API 키 순서로 정책을 합치며, 더 엄격한 설정이 우선합니다. 원문은 저장하지 않고 감지된 분류명만 진단 메타데이터에 남깁니다.</p></div><div class="protection-flow"><span>탐지</span><b>→</b><span>정책 평가</span><b>→</b><span>외부 허용 / 로컬 전용 / 차단</span></div></article>
    <div class="protection-layout">
      <div class="protection-sidebar">
        <article class="surface-card scope-list">
          <header class="card-header"><div><span class="card-kicker">POLICY SCOPES</span><h2>적용 범위</h2></div></header>
          <button v-if="platformAdmin" type="button" class="scope-item" :class="{ active: editorScope === 'PLATFORM' }" :aria-pressed="editorScope === 'PLATFORM'" @click="editPlatform"><span>플랫폼 전역</span><small>모든 조직의 최소 기준선</small></button>
          <button type="button" class="scope-item" :class="{ active: editorScope === 'ORGANIZATION' }" :aria-pressed="editorScope === 'ORGANIZATION'" @click="editOrganization"><span>조직 기본</span><small>모든 프로젝트의 기본값</small></button>
          <button v-for="project in projects" :key="project.id" type="button" class="scope-item" :class="{ active: editorScope === 'PROJECT' && selectedProjectId === project.id }" :aria-pressed="editorScope === 'PROJECT' && selectedProjectId === project.id" @click="selectProject(project.id)"><span>{{ project.name }}</span><small>프로젝트 · {{ project.status }}</small></button>
          <p v-if="!projects.length" class="empty-copy">관리 가능한 프로젝트가 없습니다.</p>
        </article>
        <article v-if="selectedProjectId" class="surface-card key-list">
          <header class="card-header"><div><span class="card-kicker">CREDENTIAL OVERRIDES</span><h2>API 키별 예외</h2></div><span class="count-badge">{{ keys.length }}</span></header>
          <button v-for="key in keys" :key="key.id" type="button" class="key-item" :class="{ active: editorScope === 'API_KEY' && selectedKeyId === key.id }" :aria-pressed="editorScope === 'API_KEY' && selectedKeyId === key.id" @click="selectKey(key.id)"><span>{{ key.name }}</span><small>{{ key.keyPrefix }} · {{ key.status }}</small></button>
          <p v-if="!keys.length" class="empty-copy">이 프로젝트에 API 키가 없습니다.</p>
        </article>
        <article class="surface-card service-list">
          <header class="card-header"><div><span class="card-kicker">AI FEATURE OVERRIDES</span><h2>AI 기능(서비스)별</h2></div><span class="count-badge">{{ services.length }}</span></header>
          <button v-for="service in services" :key="service.id" type="button" class="scope-item" :class="{ active: editorScope === 'SERVICE' && selectedServiceId === service.id }" :aria-pressed="editorScope === 'SERVICE' && selectedServiceId === service.id" @click="loadService(service.id)"><span>{{ service.displayName }}</span><small>{{ service.serviceKey }} · {{ service.enabled ? '활성' : '비활성' }}</small></button>
          <p v-if="!services.length" class="empty-copy">등록된 AI 기능이 없습니다.</p>
        </article>
      </div>
      <article class="surface-card protection-editor">
        <header class="card-header"><div><span class="card-kicker">{{ policyTitle(editorScope).toUpperCase() }}</span><h2>{{ policyTitle(editorScope) }}</h2><p>하위 범위에서 더 엄격하게 덮어쓸 수 있습니다.</p></div><span class="status-chip" :class="editor.mode === 'ENFORCE' ? 'unhealthy' : editor.mode === 'MONITOR' ? 'unknown' : 'healthy'">{{ editor.mode }}</span></header>
        <div class="preset-row" role="group" aria-label="보호 정책 프리셋">
          <button type="button" :class="{ active: editor.level === 'RELAXED' }" :aria-pressed="editor.level === 'RELAXED'" @click="applyPreset('RELAXED')"><strong>Relaxed</strong><small>기존 호환 동작</small></button>
          <button type="button" :class="{ active: editor.level === 'BALANCED' }" :aria-pressed="editor.level === 'BALANCED'" @click="applyPreset('BALANCED')"><strong>Balanced</strong><small>감지·기록</small></button>
          <button type="button" :class="{ active: editor.level === 'STRICT' }" :aria-pressed="editor.level === 'STRICT'" @click="applyPreset('STRICT')"><strong>Strict</strong><small>로컬 전용</small></button>
        </div>
        <div class="form-grid">
          <label class="field">보호 모드<select v-model="editor.mode"><option value="OFF">OFF · 제한 없음</option><option value="MONITOR">MONITOR · 감지 후 허용</option><option value="ENFORCE">ENFORCE · 정책 적용</option></select></label>
          <label class="field">보호 수준<select v-model="editor.level"><option value="RELAXED">RELAXED</option><option value="BALANCED">BALANCED</option><option value="STRICT">STRICT</option><option value="CUSTOM">CUSTOM</option></select></label>
          <label class="field">민감정보 감지 시<select v-model="editor.externalAction"><option value="ALLOW">외부 전송 허용</option><option value="REDACT">마스킹 후 허용 (현재 로컬 전용)</option><option value="LOCAL_ONLY">로컬 Target만 사용</option><option value="BLOCK">요청 차단</option></select></label>
          <label class="toggle-field"><span>외부 자동 Failover 허용<small>민감정보가 감지되면 자동 외부 전환을 제한합니다.</small></span><input v-model="editor.allowExternalFailover" type="checkbox" /></label>
        </div>
        <section class="detector-section" aria-labelledby="detector-heading">
          <div class="detector-heading"><span class="card-kicker">DETECTORS</span><h3 id="detector-heading">민감정보 감지 항목</h3></div>
          <div class="detector-grid">
            <label><input v-model="editor.detectSecrets" type="checkbox" /><span><b>Secret / API 키</b><small>토큰·비밀번호·개인키</small></span></label>
            <label><input v-model="editor.detectPii" type="checkbox" /><span><b>개인정보</b><small>이메일·전화번호·주민번호</small></span></label>
            <label><input v-model="editor.detectFinancial" type="checkbox" /><span><b>금융정보</b><small>카드번호 형식</small></span></label>
            <label><input v-model="editor.detectConfidential" type="checkbox" /><span><b>기밀 키워드</b><small>대외비·confidential</small></span></label>
            <label><input v-model="editor.detectMedia" type="checkbox" /><span><b>미디어</b><small>이미지·파일 URL 또는 data URI</small></span></label>
          </div>
        </section>
        <label class="field"><span>사용자 정의 정규식 (JSON 배열)</span><textarea v-model="editor.customPatternsJson" rows="3" placeholder='["customer-[0-9]+", "internal-only"]'></textarea><small>매칭된 원문은 저장하지 않고 CONFIDENTIAL 분류만 기록합니다.</small></label>
        <footer class="editor-footer"><span>현재 범위: {{ policyTitle(editorScope) }}</span><button type="button" class="primary-button" :disabled="busy || (editorScope === 'PROJECT' && !selectedProjectId) || (editorScope === 'SERVICE' && !selectedServiceId) || (editorScope === 'API_KEY' && !selectedKeyId)" @click="save">정책 저장</button></footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.data-protection-page { min-width:0; container:protection-page / inline-size; }
.protection-explainer { display:flex; justify-content:space-between; gap:24px; align-items:center; padding:20px; }
.protection-explainer > div { min-width:0; }
.protection-explainer h2 { margin:5px 0 8px; font-size:16px; line-height:1.5; }
.protection-explainer p { margin:0; max-width:720px; color:var(--muted); font-size:12px; line-height:1.7; }
.protection-flow { display:flex; align-items:center; gap:8px; flex-wrap:wrap; color:var(--accent-strong); font-size:11px; font-weight:700; line-height:1.6; }
.protection-flow b { color:var(--muted); }
.protection-layout { display:grid; grid-template-columns:minmax(240px,300px) minmax(0,1fr); gap:18px; align-items:start; }
.protection-sidebar { display:grid; gap:16px; min-width:0; }
.scope-list,.key-list,.service-list { display:grid; gap:6px; padding:18px; min-width:0; }
.data-protection-page .card-header { min-height:0; padding:0 0 16px; margin-bottom:6px; align-items:flex-start; gap:12px; }
.data-protection-page .card-header > div { min-width:0; }
.data-protection-page .card-header h2 { line-height:1.5; }
.data-protection-page .status-chip,.data-protection-page .count-badge { flex-shrink:0; }
.scope-item,.key-item { width:100%; min-width:0; min-height:62px; display:grid; align-content:center; gap:5px; text-align:left; padding:12px; border:1px solid transparent; border-radius:10px; background:transparent; color:var(--text); cursor:pointer; }
.scope-item:hover,.key-item:hover,.scope-item.active,.key-item.active { border-color:var(--accent-border); background:var(--accent-dim); }
.scope-item span,.key-item span { font-size:12px; font-weight:700; line-height:1.5; overflow-wrap:anywhere; }
.scope-item small,.key-item small { color:var(--muted); font-size:11px; line-height:1.5; overflow-wrap:anywhere; }
.empty-copy { margin:8px 0 0; color:var(--muted); font-size:11px; line-height:1.6; }
.protection-editor { display:grid; gap:20px; min-width:0; padding:22px; container:protection-editor / inline-size; }
.protection-editor .card-header { margin:0; }
.protection-editor .card-header p { margin:5px 0 0; color:var(--muted); font-size:12px; line-height:1.6; }
.preset-row { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }
.preset-row button { display:grid; align-content:center; gap:5px; min-width:0; min-height:70px; padding:13px 14px; border:1px solid var(--border); border-radius:10px; background:var(--surface-2); color:var(--text); text-align:left; cursor:pointer; }
.preset-row button:hover,.preset-row button.active { border-color:var(--accent-border); background:var(--accent-dim); }
.preset-row strong { font-size:13px; line-height:1.4; }
.preset-row small { color:var(--muted); font-size:11px; line-height:1.5; }
.form-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16px; }
.field { min-width:0; line-height:1.5; }
.field select { min-width:0; min-height:44px; border-radius:10px; }
.toggle-field { min-width:0; min-height:72px; align-self:end; display:flex; align-items:center; justify-content:space-between; gap:14px; padding:12px 14px; border:1px solid var(--border); border-radius:10px; background:var(--surface-2); cursor:pointer; }
.toggle-field > span { display:grid; flex:1; min-width:0; gap:5px; font-size:12px; font-weight:700; line-height:1.5; }
.toggle-field small { color:var(--muted); font-size:11px; font-weight:400; line-height:1.5; overflow-wrap:anywhere; }
.data-protection-page input[type='checkbox'] { width:18px; height:18px; min-width:18px; min-height:18px; flex:0 0 18px; padding:0; margin:0; accent-color:var(--accent-strong); cursor:pointer; }
.toggle-field:hover,.detector-grid label:hover { border-color:var(--accent-border); }
.toggle-field:focus-within,.detector-grid label:focus-within { border-color:var(--accent-strong); }
.detector-section { display:grid; gap:12px; padding-top:18px; border-top:1px solid var(--border); }
.detector-heading h3 { margin:5px 0 0; font-size:13px; line-height:1.5; }
.detector-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; }
.detector-grid label { display:grid; grid-template-columns:18px minmax(0,1fr); gap:12px; align-items:center; min-width:0; min-height:72px; padding:13px 14px; border:1px solid var(--border); border-radius:10px; background:var(--surface-2); cursor:pointer; }
.detector-grid label:has(input:checked) { border-color:var(--accent-border); background:var(--accent-dim); }
.detector-grid label > span { display:grid; min-width:0; gap:5px; }
.detector-grid b { color:var(--text); font-size:12px; line-height:1.5; }
.detector-grid small { color:var(--muted); font-size:11px; line-height:1.5; overflow-wrap:anywhere; }
.field textarea { width:100%; min-height:86px; padding:12px 14px; border-radius:10px; resize:vertical; }
.field > small { color:var(--muted); font-size:11px; font-weight:400; line-height:1.6; }
.editor-footer { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:12px; padding-top:18px; border-top:1px solid var(--border); color:var(--muted); font-size:11px; line-height:1.5; }
.editor-footer > span { min-width:0; overflow-wrap:anywhere; }
.editor-footer .primary-button { min-width:110px; min-height:42px; flex-shrink:0; border-radius:10px; white-space:nowrap; }
@media (max-width:1000px) {
  .protection-explainer { display:grid; gap:14px; }
  .protection-layout { grid-template-columns:minmax(210px,250px) minmax(0,1fr); gap:14px; }
  .protection-editor { padding:18px; }
}
@media (max-width:800px) {
  .protection-layout { grid-template-columns:minmax(0,1fr); }
  .form-grid,.detector-grid { grid-template-columns:minmax(0,1fr); }
}
@media (max-width:480px) {
  .scope-list,.key-list,.service-list,.protection-editor,.protection-explainer { padding:16px; }
  .preset-row { grid-template-columns:minmax(0,1fr); gap:8px; }
  .preset-row button { grid-template-columns:86px minmax(0,1fr); align-items:center; min-height:48px; padding:11px 12px; }
  .editor-footer { align-items:stretch; }
  .editor-footer .primary-button { width:100%; }
}
@container protection-page (max-width:760px) {
  .protection-layout { grid-template-columns:minmax(0,1fr); }
  .protection-explainer { display:grid; gap:14px; }
}
@container protection-page (max-width:480px) {
  .page-hero { display:grid; gap:16px; }
}
@container protection-editor (max-width:480px) {
  .form-grid,.detector-grid { grid-template-columns:minmax(0,1fr); }
}
@container protection-editor (max-width:380px) {
  .preset-row { grid-template-columns:minmax(0,1fr); gap:8px; }
  .preset-row button { grid-template-columns:86px minmax(0,1fr); align-items:center; min-height:48px; padding:11px 12px; }
  .editor-footer { align-items:stretch; }
  .editor-footer .primary-button { width:100%; }
}
</style>
