<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'
import { copyText } from './clipboard'

type TargetMode = 'gateway' | 'local' | 'external'
type KeyMode = 'temporary' | 'manual'
type ProbeStatus = 'idle' | 'loading' | 'ok' | 'failed'
type ModelItem = { id: string; object?: string; owned_by?: string; [key: string]: unknown }
type PortalProject = { id: string; name: string; status: string; canIssueApiKeys: boolean; services: Array<{ serviceKey: string; displayName: string }> }
type IssuedApiKey = { id: string; name: string; keyPrefix: string; secret: string; expiresAt?: string | null }
type ChatResult = { status: number; elapsedMs: number; requestId: string; body: unknown; text: string; inputTokens: number; outputTokens: number; totalTokens: number }

const props = defineProps<{ organizationId?: string; auth: AdminAuth }>()
const targetMode = ref<TargetMode>('gateway')
const keyMode = ref<KeyMode>('temporary')
const baseUrl = ref(defaultBaseUrl())
const apiKey = ref('')
const model = ref('')
const systemPrompt = ref('')
const prompt = ref('AICONNECT 연결 테스트입니다. 한 문장으로 응답해 주세요.')
const temperature = ref(0.2)
const maxTokens = ref(512)
const responseMode = ref<'text' | 'json'>('text')
const stream = ref(false)
const models = ref<ModelItem[]>([])
const projects = ref<PortalProject[]>([])
const selectedProjectId = ref('')
const tempDurationSeconds = ref(60 * 60)
const issuedKey = ref<IssuedApiKey | null>(null)
const issuedKeyProjectId = ref('')
const probeStatus = ref<ProbeStatus>('idle')
const probeLatencyMs = ref(0)
const lastProbeAt = ref('')
const loadingModels = ref(false)
const projectLoading = ref(false)
const keyLoading = ref(false)
const running = ref(false)
const showApiKey = ref(false)
const keyCopied = ref(false)
const curlCopied = ref(false)
const message = ref('1단계에서 대상을 선택하고, 2단계에서 테스트 키를 준비하세요.')
const projectError = ref('')
const rawError = ref('')
const result = ref<ChatResult | null>(null)

const targetPresets: Array<{ id: TargetMode; label: string; description: string; icon: string }> = [
  { id: 'gateway', label: 'AICONNECT Gateway', description: '권한 · 총량제 · 라우팅 · 사용량', icon: '>' },
  { id: 'local', label: 'Local LM Studio', description: 'OpenAI 호환 로컬 엔드포인트', icon: 'L' },
  { id: 'external', label: 'External AI Provider', description: 'OpenAI 호환 외부 API', icon: 'E' }
]
const durationOptions = [
  { value: 60 * 60, label: '1시간' },
  { value: 4 * 60 * 60, label: '4시간' },
  { value: 24 * 60 * 60, label: '24시간' }
]
const selectedTarget = computed(() => targetPresets.find(item => item.id === targetMode.value) ?? targetPresets[0])
const selectedProject = computed(() => projects.value.find(item => item.id === selectedProjectId.value) ?? null)
const issuedProject = computed(() => projects.value.find(item => item.id === issuedKeyProjectId.value) ?? null)
const selectedModel = computed(() => models.value.find(item => item.id === model.value.trim()))
const directTarget = computed(() => targetMode.value !== 'gateway')
const keyReady = computed(() => Boolean(apiKey.value.trim()))
const canIssueTemporaryKey = computed(() => Boolean(props.organizationId && props.auth.accessToken && selectedProject.value?.canIssueApiKeys && !keyLoading.value))
const canRun = computed(() => Boolean(keyReady.value && model.value.trim() && prompt.value.trim() && !running.value && !(directTarget.value && keyMode.value === 'temporary')))
const temporaryKeyLabel = computed(() => issuedKey.value ? issuedKey.value.keyPrefix + ' · ' + formatDate(issuedKey.value.expiresAt) : '아직 발급되지 않음')
const curlExample = computed(() => 'curl "' + normalizedBaseUrl() + '/chat/completions" -H "Authorization: Bearer $AICONNECT_API_KEY" -H "Content-Type: application/json" --data @payload.json')

function defaultBaseUrl() {
  if (typeof window === 'undefined') return '/v1'
  return window.location.origin + '/v1'
}
function normalizedBaseUrl() {
  const value = baseUrl.value.trim().replace(/\/+$/, '')
  if (!value) return '/v1'
  return /\/v1$/i.test(value) ? value : value + '/v1'
}
function formatDate(value?: string | null) {
  if (!value) return '만료 없음'
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
function resetProbe() {
  models.value = []
  result.value = null
  rawError.value = ''
  probeStatus.value = 'idle'
  probeLatencyMs.value = 0
  lastProbeAt.value = ''
}
function applyTarget(mode: TargetMode) {
  targetMode.value = mode
  resetProbe()
  if (mode === 'gateway') baseUrl.value = defaultBaseUrl()
  if (mode === 'local') baseUrl.value = 'http://127.0.0.1:1234/v1'
  if (mode === 'external') baseUrl.value = 'https://api.openai.com/v1'
  message.value = mode !== 'gateway' && keyMode.value === 'temporary'
    ? '임시 테스트 키는 Gateway 전용입니다. 직접 호출하려면 기존 키 사용으로 전환하세요.'
    : selectedTarget.value.label + ' 테스트 대상이 선택되었습니다.'
}
function selectKeyMode(mode: KeyMode) {
  keyMode.value = mode
  resetProbe()
  showApiKey.value = false
  if (mode === 'manual') {
    apiKey.value = ''
    message.value = '실사용 프로젝트 키 또는 Provider 키를 입력하세요. 브라우저에는 저장되지 않습니다.'
  } else {
    apiKey.value = issuedKey.value?.secret ?? ''
    message.value = issuedKey.value?.secret ? '발급된 임시 키를 사용합니다. Gateway에서 모델 목록을 확인하세요.' : '조직과 프로젝트를 선택한 뒤 임시 테스트 키를 발급하세요.'
  }
}
function jsonHeaders() {
  return { Authorization: 'Bearer ' + apiKey.value.trim(), 'Content-Type': 'application/json', Accept: stream.value ? 'text/event-stream' : 'application/json' }
}
async function parseResponse(response: Response) {
  const text = await response.text()
  if (!text.trim()) return { text, body: null as unknown }
  try { return { text, body: JSON.parse(text) as unknown } } catch { return { text, body: text } }
}
function requestError(response: Response, body: unknown, fallback: string) {
  if (body && typeof body === 'object') {
    const error = body as { error?: { message?: string }; message?: string }
    return error.error?.message || error.message || fallback + ' (HTTP ' + response.status + ')'
  }
  return fallback + ' (HTTP ' + response.status + ')'
}
function usageFrom(body: unknown) {
  const usage = body && typeof body === 'object' ? (body as { usage?: Record<string, unknown> }).usage : undefined
  const inputTokens = Number(usage?.prompt_tokens ?? usage?.input_tokens ?? 0)
  const outputTokens = Number(usage?.completion_tokens ?? usage?.output_tokens ?? 0)
  const totalTokens = Number(usage?.total_tokens ?? inputTokens + outputTokens)
  return { inputTokens, outputTokens, totalTokens }
}
async function loadProjects() {
  projects.value = []
  projectError.value = ''
  if (!props.organizationId) { projectError.value = '먼저 좌측 WORKSPACE에서 조직을 선택하세요.'; return }
  if (!props.auth.accessToken) { projectError.value = '로그인 세션이 없어 프로젝트를 조회할 수 없습니다.'; return }
  projectLoading.value = true
  try {
    projects.value = await adminFetch<PortalProject[]>('/api/portal/organizations/' + props.organizationId + '/projects', props.auth)
    const issuable = projects.value.find(item => item.canIssueApiKeys)
    if (!projects.value.some(item => item.id === selectedProjectId.value)) selectedProjectId.value = issuable?.id ?? projects.value[0]?.id ?? ''
    if (!projects.value.length) projectError.value = '현재 조직에서 접근 가능한 프로젝트가 없습니다.'
    else if (!issuable) projectError.value = '접근 가능한 프로젝트에 API 키 발급 권한이 없습니다.'
  } catch (error) {
    projectError.value = error instanceof Error ? error.message : '프로젝트 목록을 불러오지 못했습니다.'
  } finally { projectLoading.value = false }
}
async function issueTemporaryKey() {
  if (!selectedProject.value || !canIssueTemporaryKey.value) return
  keyLoading.value = true
  projectError.value = ''
  rawError.value = ''
  try {
    const issued = await adminFetch<IssuedApiKey>('/api/portal/projects/' + selectedProject.value.id + '/api-keys/temporary', props.auth, {
      method: 'POST',
      body: JSON.stringify({ name: 'playground-temp-' + Date.now(), durationSeconds: tempDurationSeconds.value })
    })
    issuedKey.value = issued
    issuedKeyProjectId.value = selectedProject.value.id
    apiKey.value = issued.secret
    keyMode.value = 'temporary'
    showApiKey.value = true
    if (targetMode.value !== 'gateway') { targetMode.value = 'gateway'; baseUrl.value = defaultBaseUrl() }
    resetProbe()
    message.value = '임시 키가 발급되어 Gateway에 적용되었습니다. 원문은 이 화면 메모리에만 있습니다.'
  } catch (error) {
    rawError.value = error instanceof Error ? error.message : '임시 테스트 키 발급에 실패했습니다.'
    message.value = '임시 키를 발급하지 못했습니다. 조직·프로젝트 권한을 확인하세요.'
  } finally { keyLoading.value = false }
}
async function revokeTemporaryKey() {
  if (!issuedKey.value || !issuedKeyProjectId.value) return
  try {
    await adminFetch<void>('/api/portal/projects/' + issuedKeyProjectId.value + '/api-keys/' + issuedKey.value.id, props.auth, { method: 'DELETE' })
    issuedKey.value = null
    issuedKeyProjectId.value = ''
    apiKey.value = ''
    resetProbe()
    message.value = '임시 테스트 키를 폐기했습니다.'
  } catch (error) {
    rawError.value = error instanceof Error ? error.message : '임시 키 폐기에 실패했습니다.'
  }
}
function clearApiKey() {
  apiKey.value = ''
  if (issuedKey.value) issuedKey.value = { ...issuedKey.value, secret: '' }
  resetProbe()
  showApiKey.value = false
  message.value = '현재 입력한 원문 키를 화면 메모리에서 제거했습니다.'
}
async function copyApiKey() {
  const secret = apiKey.value.trim() || issuedKey.value?.secret || ''
  if (!secret) { message.value = '복사할 원문 키가 없습니다. 임시 키를 다시 발급하거나 키를 입력하세요.'; return }
  try {
    await copyText(secret)
    keyCopied.value = true
    message.value = 'API 키를 클립보드에 복사했습니다. 사용 후 클립보드에서도 삭제하세요.'
    window.setTimeout(() => { keyCopied.value = false }, 1800)
  } catch { message.value = 'API 키를 복사하지 못했습니다.' }
}
async function loadModels() {
  rawError.value = ''
  if (!apiKey.value.trim()) { message.value = keyMode.value === 'temporary' ? '먼저 임시 테스트 키를 발급하세요.' : '실사용 키를 입력하세요.'; return }
  if (directTarget.value && keyMode.value === 'temporary') { message.value = '임시 Gateway 키는 직접 Provider 주소에서 사용할 수 없습니다. AICONNECT Gateway를 선택하세요.'; return }
  loadingModels.value = true
  probeStatus.value = 'loading'
  const started = performance.now()
  try {
    const response = await fetch(normalizedBaseUrl() + '/models', { headers: jsonHeaders() })
    const parsed = await parseResponse(response)
    if (!response.ok) throw new Error(requestError(response, parsed.body, '모델 목록 조회에 실패했습니다.'))
    const data = parsed.body && typeof parsed.body === 'object' ? (parsed.body as { data?: ModelItem[] }).data : []
    models.value = Array.isArray(data) ? data : []
    probeStatus.value = 'ok'
    probeLatencyMs.value = Math.round(performance.now() - started)
    lastProbeAt.value = new Date().toLocaleTimeString()
    if (!model.value && models.value.length) model.value = models.value[0].id
    message.value = models.value.length ? models.value.length + '개의 사용 가능한 모델을 확인했습니다.' : '연결은 성공했지만 사용할 수 있는 모델이 없습니다.'
  } catch (error) {
    models.value = []
    probeStatus.value = 'failed'
    probeLatencyMs.value = Math.round(performance.now() - started)
    lastProbeAt.value = new Date().toLocaleTimeString()
    rawError.value = error instanceof Error ? error.message : '모델 목록 조회에 실패했습니다.'
    message.value = '모델 목록을 확인하지 못했습니다. Base URL, API 키, CORS 또는 사설망 연결을 확인하세요.'
  } finally { loadingModels.value = false }
}
function extractAssistantText(body: unknown) {
  if (!body || typeof body !== 'object') return ''
  const choices = (body as { choices?: Array<{ message?: { content?: unknown } }> }).choices
  const content = choices?.[0]?.message?.content
  return typeof content === 'string' ? content : content == null ? '' : JSON.stringify(content)
}
async function readStream(response: Response) {
  if (!response.body) return { text: '', body: null as unknown }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let text = ''
  let lastBody: unknown = null
  while (true) {
    const chunk = await reader.read()
    if (chunk.done) break
    buffer += decoder.decode(chunk.value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      const payload = line.trim()
      if (!payload.startsWith('data:')) continue
      const value = payload.slice(5).trim()
      if (!value || value === '[DONE]') continue
      try {
        const item = JSON.parse(value) as { choices?: Array<{ delta?: { content?: string } }>; usage?: Record<string, unknown> }
        lastBody = item
        const delta = item.choices?.[0]?.delta?.content
        if (delta) text += delta
      } catch { /* Ignore malformed keep-alive lines. */ }
    }
  }
  return { text, body: lastBody }
}
async function runTest() {
  if (!canRun.value) return
  rawError.value = ''
  result.value = null
  running.value = true
  const started = performance.now()
  const messages: Array<{ role: string; content: string }> = []
  if (systemPrompt.value.trim()) messages.push({ role: 'system', content: systemPrompt.value.trim() })
  messages.push({ role: 'user', content: prompt.value.trim() })
  const body: Record<string, unknown> = { model: model.value.trim(), messages, temperature: Number(temperature.value), max_completion_tokens: Number(maxTokens.value), stream: stream.value }
  if (responseMode.value === 'json') body.response_format = { type: 'json_object' }
  try {
    const response = await fetch(normalizedBaseUrl() + '/chat/completions', { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(body) })
    const requestId = response.headers.get('X-Request-Id') ?? response.headers.get('x-request-id') ?? ''
    const parsed = stream.value && response.ok ? await readStream(response) : await parseResponse(response)
    const elapsedMs = Math.round(performance.now() - started)
    if (!response.ok) throw new Error(requestError(response, parsed.body, 'Chat Completions 요청이 실패했습니다.'))
    const usage = usageFrom(parsed.body)
    result.value = { status: response.status, elapsedMs, requestId, body: parsed.body, text: stream.value ? parsed.text : extractAssistantText(parsed.body), ...usage }
    message.value = '요청 성공 · ' + elapsedMs + 'ms · ' + (usage.totalTokens ? usage.totalTokens.toLocaleString() + ' 토큰' : '사용량 미제공')
  } catch (error) {
    rawError.value = error instanceof Error ? error.message : 'Chat Completions 요청에 실패했습니다.'
    message.value = '요청이 실패했습니다. 아래 오류와 Request ID를 함께 확인하세요.'
  } finally { running.value = false }
}
function clearResult() {
  result.value = null
  rawError.value = ''
  message.value = '입력값은 유지한 채 결과만 지웠습니다.'
}
async function copyCurl() {
  try {
    await copyText(curlExample.value)
    curlCopied.value = true
    message.value = 'curl 명령을 클립보드에 복사했습니다.'
    window.setTimeout(() => { curlCopied.value = false }, 1800)
  } catch { message.value = 'curl 명령을 복사하지 못했습니다.' }
}
watch(() => [props.organizationId, props.auth.accessToken], () => { void loadProjects() }, { immediate: true })
</script>

<template>
  <section class="page-stack playground-page">
    <div class="page-hero playground-hero">
      <div>
        <p class="eyebrow">API PLAYGROUND</p>
        <h1>연결 테스트</h1>
        <p>대상 선택 → 테스트 키 준비 → 모델 확인 → 실제 요청 실행까지 한 화면에서 확인합니다.</p>
      </div>
      <div class="playground-badge"><span class="live-dot"></span><span>원문 키는 저장하지 않음</span></div>
    </div>

    <div class="playground-notice">
      <strong>무엇을 검증하나요?</strong>
      <span><b>Gateway</b>를 선택하면 인증·프로젝트 권한·총량제·라우팅·사용량 기록까지 실제 운영 경로로 확인합니다. Local/External 직접 호출은 Provider 연결만 검증하며 중앙 사용량과 총량제에는 기록되지 않습니다.</span>
    </div>

    <div class="workflow-guide">
      <div class="workflow-step"><b>1</b><span><strong>대상 선택</strong><small>Gateway / Local / External</small></span></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><b>2</b><span><strong>키 준비</strong><small>임시 키 발급 또는 기존 키</small></span></div>
      <div class="workflow-arrow">→</div>
      <div class="workflow-step"><b>3</b><span><strong>요청 실행</strong><small>모델 · JSON · SSE · 결과</small></span></div>
    </div>

    <div class="target-picker">
      <div class="target-picker-head">
        <div><span class="card-kicker">STEP 1 · TEST TARGET</span><strong>어디로 요청할까요?</strong><small>운영 경로를 검증하려면 AICONNECT Gateway를 권장합니다.</small></div>
        <code>{{ normalizedBaseUrl() }}</code>
      </div>
      <div class="target-options">
        <button v-for="target in targetPresets" :key="target.id" type="button" :class="{ active: targetMode === target.id }" @click="applyTarget(target.id)">
          <span class="target-icon">{{ target.icon }}</span>
          <span><strong>{{ target.label }}</strong><small>{{ target.description }}</small></span>
          <i>{{ target.id === 'gateway' ? 'RECOMMENDED' : 'DIRECT' }}</i>
        </button>
      </div>
      <p v-if="directTarget" class="target-warning">직접 호출은 브라우저 CORS와 네트워크 접근이 필요하며 AICONNECT 총량제·사용량 집계를 우회합니다. Provider 키를 직접 입력할 때만 사용하세요.</p>
    </div>

    <div class="playground-grid">
      <article class="surface-card playground-card connection-card">
        <header class="card-header">
          <div><span class="card-kicker">STEP 2 · CONNECTION</span><h2>키와 모델 준비</h2></div>
          <span class="status-chip tiny" :class="probeStatus === 'ok' ? 'healthy' : probeStatus === 'failed' ? 'unhealthy' : 'unknown'">{{ probeStatus === 'ok' ? '연결됨' : probeStatus === 'failed' ? '실패' : '대기' }}</span>
        </header>

        <div class="playground-form">
          <label class="field">Base URL <small>{{ selectedTarget.label }} 요청 주소 · /v1 자동 보정</small><input v-model.trim="baseUrl" placeholder="https://ai.company.example/v1" /></label>

          <div class="panel-heading"><span>인증 키 선택</span><small>원문은 현재 브라우저 메모리에만 보관됩니다.</small></div>
          <div class="key-mode-grid">
            <button type="button" :class="{ active: keyMode === 'temporary' }" @click="selectKeyMode('temporary')"><span>⚡</span><strong>임시 테스트 키</strong><small>현재 조직 프로젝트에서 1~24시간 발급</small></button>
            <button type="button" :class="{ active: keyMode === 'manual' }" @click="selectKeyMode('manual')"><span>⌘</span><strong>기존 키 사용</strong><small>실사용 프로젝트 키 또는 Provider 키 입력</small></button>
          </div>

          <div v-if="keyMode === 'temporary'" class="temporary-key-panel">
            <div class="panel-heading"><span>임시 키 발급</span><button class="text-button" type="button" :disabled="projectLoading" @click="loadProjects">{{ projectLoading ? '새로고침 중…' : '프로젝트 새로고침' }}</button></div>
            <label class="field">프로젝트 <small>현재 로그인 계정으로 접근 가능한 프로젝트만 표시됩니다.</small><select v-model="selectedProjectId" :disabled="projectLoading || !projects.length"><option value="" disabled>프로젝트를 선택하세요</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }} · {{ project.status }}</option></select></label>
            <div class="form-grid">
              <label class="field">유효 기간 <small>최대 24시간</small><select v-model.number="tempDurationSeconds"><option v-for="option in durationOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
              <div class="action-field"><span>발급 상태</span><strong>{{ issuedKey ? '발급됨' : '미발급' }}</strong><small>{{ temporaryKeyLabel }}</small></div>
            </div>
            <button class="secondary-button full-button" type="button" :disabled="!canIssueTemporaryKey" @click="issueTemporaryKey">{{ keyLoading ? '발급 중…' : issuedKey ? '새 임시 키 발급' : '임시 테스트 키 발급' }}</button>
            <p v-if="projectError" class="field-error">{{ projectError }}</p>
            <p v-else class="field-hint">발급 즉시 Gateway 요청에 적용됩니다. 만료되면 서버가 자동으로 401 차단합니다.</p>
          </div>

          <div v-else class="manual-key-panel">
            <label class="field">실사용 API 키 <small>{{ directTarget ? '선택한 Provider에서 발급한 Bearer 키' : '프로젝트에서 발급한 sk_llmg 키' }}</small><div class="secret-input"><input v-model="apiKey" :type="showApiKey ? 'text' : 'password'" autocomplete="off" placeholder="sk_llmg_ 또는 Provider key" /><button type="button" class="icon-button" :aria-label="showApiKey ? '키 숨기기' : '키 보기'" @click="showApiKey = !showApiKey">{{ showApiKey ? '숨김' : '보기' }}</button></div></label>
            <p class="field-hint">실제 운영 키도 이 화면에서만 사용하고 저장하지 않습니다. 테스트가 끝나면 키를 지우세요.</p>
          </div>

          <div v-if="issuedKey" class="issued-key-panel">
            <div><span class="card-kicker">TEMPORARY KEY</span><strong>{{ issuedProject?.name ?? '선택 프로젝트' }}</strong><small>{{ issuedKey.keyPrefix }} · {{ formatDate(issuedKey.expiresAt) }}</small></div>
            <div class="issued-key-actions"><button class="ghost-button" type="button" :disabled="!issuedKey.secret" @click="showApiKey = !showApiKey">{{ showApiKey ? '키 숨기기' : '키 보기' }}</button><button class="ghost-button" type="button" :disabled="!issuedKey.secret" @click="copyApiKey">{{ keyCopied ? '복사됨' : '키 복사' }}</button><button class="danger-button" type="button" :disabled="keyLoading" @click="revokeTemporaryKey">임시 키 폐기</button></div>
            <input class="issued-key-value" :type="showApiKey && issuedKey.secret ? 'text' : 'password'" :value="issuedKey.secret || '원문 키는 제거되었습니다. 재발급하세요.'" readonly />
          </div>

          <div class="form-actions">
            <button class="secondary-button" type="button" :disabled="loadingModels || !keyReady" @click="loadModels">{{ loadingModels ? '확인 중…' : '모델 목록 확인' }}</button>
            <button class="ghost-button" type="button" :disabled="!apiKey && !issuedKey" @click="clearApiKey">현재 키 지우기</button>
          </div>
        </div>

        <div class="connection-meta"><span>현재 주소</span><code>{{ normalizedBaseUrl() }}</code><span class="meta-separator">·</span><span>{{ keyMode === 'temporary' ? '임시 Gateway 키' : '기존 키' }}</span></div>
        <div v-if="probeStatus !== 'idle'" class="probe-summary"><span class="status-chip tiny" :class="probeStatus === 'ok' ? 'healthy' : probeStatus === 'failed' ? 'unhealthy' : 'suspect'"><i></i>{{ probeStatus === 'ok' ? '연결됨' : probeStatus === 'failed' ? '연결 실패' : '확인 중' }}</span><div><strong>GET /models</strong><small>{{ probeStatus === 'ok' ? models.length + '개 모델 확인' : '모델 목록을 조회하지 못했습니다.' }}</small></div><time v-if="probeLatencyMs">{{ probeLatencyMs }}ms · {{ lastProbeAt }}</time></div>
        <div v-if="models.length" class="model-list"><button v-for="item in models" :key="item.id" type="button" :class="{ selected: model === item.id }" @click="model = item.id"><strong>{{ item.id }}</strong><small>{{ item.owned_by ?? 'aiconnect' }}</small></button></div>
        <div v-else class="empty-state compact"><span>◇</span><p>모델 목록을 확인하면 프로젝트에 허용된 논리 모델을 선택할 수 있습니다.</p></div>
      </article>

      <article class="surface-card playground-card request-card">
        <header class="card-header"><div><span class="card-kicker">STEP 3 · CHAT COMPLETIONS</span><h2>요청 보내기</h2></div><span class="request-method">{{ stream ? 'SSE' : 'JSON' }}</span></header>
        <div class="playground-form">
          <label class="field">Model <small>모델 목록에서 선택하거나 호환되는 모델 ID를 직접 입력</small><input v-model.trim="model" list="playground-model-list" placeholder="text-pro" /><datalist id="playground-model-list"><option v-for="item in models" :key="item.id" :value="item.id" /></datalist></label>
          <label class="field">System Prompt <small>선택 사항</small><textarea v-model="systemPrompt" rows="2" placeholder="응답 규칙을 입력하세요 (선택)" /></label>
          <label class="field">User Prompt <textarea v-model="prompt" rows="5" placeholder="테스트할 요청을 입력하세요" /></label>
          <div class="option-grid"><label class="field">Temperature<input v-model.number="temperature" type="number" min="0" max="2" step="0.1" /></label><label class="field">Max Completion Tokens<input v-model.number="maxTokens" type="number" min="1" max="32768" step="1" /></label><label class="field">응답 형식<select v-model="responseMode"><option value="text">일반 텍스트</option><option value="json">JSON object</option></select></label></div>
          <div class="toggle-row"><label><input v-model="stream" type="checkbox" /> SSE 스트리밍으로 테스트</label><span v-if="selectedModel">선택됨: <code>{{ selectedModel.id }}</code></span></div>
          <button class="primary-button run-button" :disabled="!canRun" @click="runTest"><span>{{ running ? '요청 처리 중…' : '테스트 요청 실행' }}</span><b>↗</b></button>
          <p v-if="!keyReady" class="request-hint">먼저 2단계에서 API 키를 준비하세요.</p>
          <p v-else-if="directTarget && keyMode === 'temporary'" class="request-hint warning">직접 호출에는 임시 Gateway 키를 사용할 수 없습니다. 기존 키 사용으로 전환하세요.</p>
          <details class="request-contract"><summary>요청용 curl 보기</summary><div class="contract-grid"><span><b>1</b> GET {{ normalizedBaseUrl() }}/models</span><span><b>2</b> POST {{ normalizedBaseUrl() }}/chat/completions</span><span><b>3</b> Bearer API Key 인증</span><span><b>4</b> {{ stream ? 'SSE event stream' : 'JSON response' }}</span></div><pre>{{ curlExample }}</pre><button class="ghost-button" type="button" @click="copyCurl">{{ curlCopied ? '복사됨' : 'curl 복사' }}</button></details>
        </div>
      </article>
    </div>

    <p class="inline-alert" :class="{ 'playground-error': rawError }">{{ rawError || message }}</p>

    <article v-if="result" class="surface-card playground-card result-card">
      <header class="card-header"><div><span class="card-kicker">RESPONSE</span><h2>응답 결과</h2></div><span class="status-chip healthy">HTTP {{ result.status }}</span></header>
      <div class="result-metrics"><div><small>지연 시간</small><strong>{{ result.elapsedMs.toLocaleString() }} ms</strong></div><div><small>입력 토큰</small><strong>{{ result.inputTokens.toLocaleString() }}</strong></div><div><small>출력 토큰</small><strong>{{ result.outputTokens.toLocaleString() }}</strong></div><div><small>전체 토큰</small><strong>{{ result.totalTokens.toLocaleString() }}</strong></div><div><small>Request ID</small><code>{{ result.requestId || '제공되지 않음' }}</code></div></div>
      <div class="assistant-result"><span>ASSISTANT</span><pre>{{ result.text || '(텍스트 응답 없음 — 원문 JSON을 확인하세요.)' }}</pre></div>
      <details class="raw-response"><summary>원문 JSON 보기</summary><pre>{{ JSON.stringify(result.body, null, 2) }}</pre></details>
    </article>

    <article v-else-if="rawError" class="surface-card playground-card result-card error-result">
      <header class="card-header"><div><span class="card-kicker">DIAGNOSTICS</span><h2>요청 실패</h2></div><span class="status-chip danger">ERROR</span></header>
      <p>{{ rawError }}</p>
      <p>Gateway라면 프로젝트 권한·총량제·서비스 Target 상태를, 직접 호출이라면 Provider 키·Base URL·CORS를 확인하세요.</p>
    </article>
  </section>
</template>

<style scoped>
.playground-page { max-width: 1480px; margin-inline: auto; }
.playground-hero { align-items: center; }
.playground-badge { display: inline-flex; align-items: center; gap: 9px; padding: 11px 14px; border: 1px solid var(--accent-border); border-radius: 999px; background: var(--accent-dim); color: var(--text-soft); font-size: 11px; font-weight: 700; white-space: nowrap; }
.playground-badge .live-dot { margin: 0; }
.playground-notice { padding: 14px 16px; display: flex; gap: 12px; align-items: baseline; border: 1px solid var(--accent-border); border-radius: 13px; background: var(--accent-dim); color: var(--text-soft); font-size: 12px; line-height: 1.55; }
.playground-notice strong { color: var(--accent-strong); white-space: nowrap; }
.playground-notice b { color: var(--accent-strong); }
.workflow-guide { display: flex; align-items: center; gap: 10px; padding: 11px 15px; border: 1px solid var(--border); border-radius: 13px; background: var(--surface); }
.workflow-step { min-width: 0; display: flex; align-items: center; gap: 9px; flex: 1; }
.workflow-step > b { width: 25px; height: 25px; display: grid; flex: 0 0 auto; place-content: center; border-radius: 8px; background: var(--accent); color: var(--on-accent); font-size: 11px; }
.workflow-step > span { min-width: 0; display: grid; gap: 2px; }
.workflow-step strong { font-size: 11px; }
.workflow-step small { overflow: hidden; color: var(--muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.workflow-arrow { color: var(--muted); font-size: 16px; }
.target-picker { padding: 16px 18px; border: 1px solid var(--border); border-radius: 15px; background: var(--surface); }
.target-picker-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.target-picker-head > div { display: grid; gap: 4px; }
.target-picker-head strong { font-size: 13px; }
.target-picker-head small { color: var(--muted); font-size: 9px; line-height: 1.4; }
.target-picker-head code { color: var(--accent-strong); font-size: 9px; overflow-wrap: anywhere; }
.target-options { margin-top: 12px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.target-options button { min-width: 0; padding: 10px; display: grid; grid-template-columns: 29px minmax(0, 1fr) auto; align-items: center; gap: 8px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); text-align: left; }
.target-options button:hover, .target-options button.active { border-color: var(--accent-border); background: var(--accent-dim); }
.target-options button > span:nth-child(2) { min-width: 0; display: grid; gap: 3px; }
.target-icon { width: 29px; height: 29px; display: grid; place-content: center; border-radius: 8px; background: var(--surface-3); color: var(--accent-strong); font-size: 14px; }
.target-options strong { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.target-options small { overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.target-options i { padding: 3px 5px; border-radius: 5px; background: var(--surface-3); color: var(--muted); font-size: 7px; font-style: normal; font-weight: 800; }
.target-warning { margin: 11px 0 0; color: var(--warning); font-size: 9px; line-height: 1.5; }
.playground-grid { display: grid; grid-template-columns: minmax(360px, .9fr) minmax(0, 1.1fr); gap: 18px; align-items: start; }
.playground-card { padding: 23px; }
.playground-card .card-header { margin-bottom: 20px; }
.playground-card .card-header h2 { margin: 6px 0 0; font-size: 20px; }
.request-method { padding: 5px 8px; border-radius: 7px; background: var(--surface-3); color: var(--accent-strong); font: 700 10px 'SFMono-Regular', Consolas, monospace; }
.playground-form { display: grid; gap: 15px; }
.panel-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.panel-heading > span { color: var(--text-soft); font-size: 12px; font-weight: 800; }
.panel-heading > small { color: var(--muted); font-size: 9px; }
.text-button { border: 0; background: transparent; color: var(--accent-strong); cursor: pointer; font-size: 10px; font-weight: 800; }
.text-button:disabled { color: var(--muted); cursor: wait; }
.key-mode-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }
.key-mode-grid button { min-width: 0; padding: 13px; display: grid; grid-template-columns: 25px minmax(0, 1fr); gap: 3px 8px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface-2); text-align: left; }
.key-mode-grid button:hover, .key-mode-grid button.active { border-color: var(--accent-border); background: var(--accent-dim); }
.key-mode-grid button > span { grid-row: span 2; width: 25px; height: 25px; display: grid; place-content: center; border-radius: 7px; background: var(--surface-3); color: var(--accent-strong); font-size: 13px; }
.key-mode-grid strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.key-mode-grid small { color: var(--muted); font-size: 9px; line-height: 1.45; }
.temporary-key-panel, .manual-key-panel { padding: 14px; display: grid; gap: 13px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); }
.manual-key-panel { border-color: var(--border); background: var(--surface-2); }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px; }
.action-field { min-width: 0; padding: 10px 11px; display: grid; gap: 4px; align-content: center; border: 1px solid var(--border); border-radius: 9px; background: var(--surface-2); }
.action-field > span, .action-field > small { color: var(--muted); font-size: 9px; }
.action-field strong { font-size: 11px; }
.action-field small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.full-button { width: 100%; justify-content: center; }
.field-error { margin: 0; color: var(--danger); font-size: 10px; line-height: 1.5; }
.secret-input { display: flex; gap: 7px; }
.secret-input input { min-width: 0; flex: 1; }
.secret-input .icon-button { flex: 0 0 auto; width: auto; padding-inline: 10px; color: var(--text-soft); font-size: 10px; }
.issued-key-panel { padding: 13px; display: grid; gap: 10px; border: 1px solid color-mix(in srgb, var(--accent-strong) 48%, var(--border)); border-radius: 11px; background: var(--surface-2); }
.issued-key-panel > div:first-child { min-width: 0; display: grid; gap: 4px; }
.issued-key-panel strong { font-size: 11px; }
.issued-key-panel small { color: var(--muted); font-size: 9px; }
.issued-key-actions { display: flex; flex-wrap: wrap; gap: 7px; }
.issued-key-actions button { min-height: 30px; padding-inline: 10px; font-size: 9px; }
.issued-key-value { width: 100%; color: var(--accent-strong); font: 10px 'SFMono-Regular', Consolas, monospace; }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; }
.field-hint { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.field-hint code { color: var(--accent-strong); overflow-wrap: anywhere; }
.connection-meta { margin-top: 16px; display: flex; align-items: center; flex-wrap: wrap; gap: 7px; color: var(--muted); font-size: 9px; }
.connection-meta code { max-width: 100%; overflow: hidden; color: var(--accent-strong); text-overflow: ellipsis; white-space: nowrap; }
.meta-separator { color: var(--border-strong, var(--border)); }
.model-list { margin-top: 17px; display: grid; gap: 8px; max-height: 230px; overflow: auto; }
.model-list button { display: grid; gap: 5px; padding: 11px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); text-align: left; }
.model-list button:hover, .model-list button.selected { border-color: var(--accent-border); background: var(--accent-dim); }
.model-list strong { overflow: hidden; font: 600 12px 'SFMono-Regular', Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.model-list small { color: var(--muted); font-size: 10px; }
.probe-summary { margin-top: 12px; padding: 10px; display: flex; align-items: center; gap: 9px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.probe-summary > div { min-width: 0; display: grid; gap: 3px; }
.probe-summary strong, .probe-summary small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.probe-summary strong { font-size: 10px; }
.probe-summary small, .probe-summary time { color: var(--muted); font-size: 8px; }
.probe-summary time { margin-left: auto; white-space: nowrap; }
.empty-state.compact { min-height: 100px; margin-top: 16px; }
.option-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--text-soft); font-size: 11px; }
.toggle-row label { display: inline-flex; gap: 8px; align-items: center; font-weight: 700; }
.toggle-row input { width: 16px; height: 16px; accent-color: var(--accent-strong); }
.toggle-row span { color: var(--muted); }
.toggle-row code { color: var(--accent-strong); }
.run-button { width: 100%; min-height: 48px; justify-content: space-between; margin-top: 3px; }
.run-button b { font-size: 18px; }
.request-hint { margin: -3px 0 0; color: var(--muted); font-size: 10px; }
.request-hint.warning { color: var(--warning); }
.request-contract { padding-top: 13px; border-top: 1px solid var(--border); color: var(--muted); font-size: 10px; }
.request-contract summary { cursor: pointer; color: var(--text-soft); font-weight: 700; }
.contract-grid { margin-top: 11px; display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px; }
.contract-grid span { padding: 7px 8px; border-radius: 7px; background: var(--surface-2); font-size: 8px; }
.contract-grid b { display: inline-grid; width: 16px; height: 16px; margin-right: 5px; place-content: center; border-radius: 5px; background: var(--accent-dim); color: var(--accent-strong); }
.request-contract pre { margin: 10px 0; padding: 10px; border-radius: 8px; background: var(--bg-soft); color: var(--accent-strong); font-size: 8px; line-height: 1.5; }
.request-contract .ghost-button { min-height: 30px; padding-inline: 9px; font-size: 9px; }
.inline-alert.playground-error { border-color: color-mix(in srgb, var(--danger) 45%, transparent); background: var(--danger-dim); color: var(--danger); }
.result-card { display: grid; gap: 18px; }
.result-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.result-metrics > div { min-width: 0; padding: 13px; display: grid; gap: 7px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface-2); }
.result-metrics small { color: var(--muted); font-size: 10px; }
.result-metrics strong { font-size: 16px; }
.result-metrics code { overflow: hidden; color: var(--accent-strong); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.assistant-result { padding: 16px; border: 1px solid var(--accent-border); border-radius: 13px; background: var(--accent-dim); }
.assistant-result > span { color: var(--accent-strong); font: 800 10px 'Space Grotesk'; letter-spacing: .16em; }
pre { margin: 10px 0 0; overflow: auto; white-space: pre-wrap; word-break: break-word; font: 12px/1.65 'SFMono-Regular', Consolas, monospace; }
.raw-response { border-top: 1px solid var(--border); padding-top: 13px; color: var(--muted); font-size: 11px; }
.raw-response summary { cursor: pointer; color: var(--text-soft); font-weight: 700; }
.raw-response pre { max-height: 340px; color: var(--text-soft); }
.error-result { border-color: color-mix(in srgb, var(--danger) 30%, transparent); }
.error-result p { margin: 0; color: var(--text-soft); font-size: 12px; line-height: 1.6; }
@media (max-width: 980px) { .playground-grid { grid-template-columns: 1fr; } .result-metrics { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 700px) { .target-options { grid-template-columns: 1fr; } .target-picker-head { align-items: flex-start; flex-direction: column; } .contract-grid { grid-template-columns: 1fr; } .probe-summary { align-items: flex-start; flex-wrap: wrap; } .probe-summary time { margin-left: 0; width: 100%; } }
@media (max-width: 620px) { .playground-notice { display: grid; gap: 5px; } .option-grid, .result-metrics, .form-grid, .key-mode-grid { grid-template-columns: 1fr 1fr; } .toggle-row { align-items: flex-start; flex-direction: column; } .playground-card { padding: 17px; } }
@media (max-width: 430px) { .option-grid, .result-metrics, .form-grid, .key-mode-grid { grid-template-columns: 1fr; } .playground-card { padding: 14px; } }
</style>
