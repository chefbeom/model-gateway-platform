<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

type ModelItem = {
  id: string
  object?: string
  owned_by?: string
  [key: string]: unknown
}

type ChatResult = {
  status: number
  elapsedMs: number
  requestId: string
  body: unknown
  text: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
}

const props = defineProps<{ organizationId?: string }>()
type TargetMode = 'gateway' | 'local' | 'external'
type ProbeStatus = 'idle' | 'loading' | 'ok' | 'failed'
const targetMode = ref<TargetMode>('gateway')
const probeStatus = ref<ProbeStatus>('idle')
const probeLatencyMs = ref(0)
const lastProbeAt = ref('')
const curlCopied = ref(false)
const targetPresets: Array<{ id: TargetMode; label: string; description: string; icon: string }> = [
  { id: 'gateway', label: 'AICONNECT Gateway', description: 'Auth / quota / routing enabled', icon: '>' },
  { id: 'local', label: 'Local LM Studio', description: 'OpenAI-compatible local endpoint', icon: 'L' },
  { id: 'external', label: 'External AI Provider', description: 'OpenAI-compatible external API', icon: 'E' }
]
const selectedTarget = computed(() => targetPresets.find(item => item.id === targetMode.value) || targetPresets[0])
const directTargetWarning = computed(() => targetMode.value !== 'gateway')
const curlExample = computed(() => 'curl ' + normalizedBaseUrl() + '/chat/completions -H "Authorization: Bearer $AICONNECT_API_KEY" -H "Content-Type: application/json" -d \'{"model":"' + (model.value.trim() || 'text-pro') + '","messages":[{"role":"user","content":"hello"}]}\'')

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
const loadingModels = ref(false)
const running = ref(false)
const message = ref('API 키를 입력하고 모델 목록을 확인한 뒤 테스트를 실행하세요.')
const result = ref<ChatResult | null>(null)
const rawError = ref('')

const canRun = computed(() => Boolean(apiKey.value.trim() && model.value.trim() && prompt.value.trim() && !running.value))
const selectedModel = computed(() => models.value.find(item => item.id === model.value.trim()))

function defaultBaseUrl() {
  if (typeof window === 'undefined') return '/v1'
  return window.location.origin + '/v1'
}

function applyTarget(mode: TargetMode) {
  targetMode.value = mode
  models.value = []
  result.value = null
  rawError.value = ''
  probeStatus.value = 'idle'
  if (mode === 'gateway') baseUrl.value = defaultBaseUrl()
  if (mode === 'local') baseUrl.value = 'http://127.0.0.1:1234/v1'
  if (mode === 'external') baseUrl.value = 'https://api.openai.com/v1'
  message.value = selectedTarget.value.label + ' 테스트 대상이 선택되었습니다. API 키와 모델을 입력한 뒤 요청을 실행하세요.'
}
function normalizedBaseUrl() {
  const value = baseUrl.value.trim().replace(/\/+$/, '')
  if (!value) return '/v1'
  return /\/v1$/i.test(value) ? value : `${value}/v1`
}

function jsonHeaders() {
  return {
    Authorization: `Bearer ${apiKey.value.trim()}`,
    'Content-Type': 'application/json',
    Accept: stream.value ? 'text/event-stream' : 'application/json'
  }
}

async function parseResponse(response: Response) {
  const text = await response.text()
  if (!text.trim()) return { text, body: null as unknown }
  try { return { text, body: JSON.parse(text) as unknown } } catch { return { text, body: text } }
}

function requestError(response: Response, body: unknown, fallback: string) {
  if (body && typeof body === 'object') {
    const error = (body as { error?: { message?: string; code?: string }; message?: string })
    return error.error?.message || error.message || `${fallback} (HTTP ${response.status})`
  }
  return `${fallback} (HTTP ${response.status})`
}

function usageFrom(body: unknown) {
  const usage = body && typeof body === 'object' ? (body as { usage?: Record<string, unknown> }).usage : undefined
  const inputTokens = Number(usage?.prompt_tokens ?? usage?.input_tokens ?? 0)
  const outputTokens = Number(usage?.completion_tokens ?? usage?.output_tokens ?? 0)
  const totalTokens = Number(usage?.total_tokens ?? inputTokens + outputTokens)
  return { inputTokens, outputTokens, totalTokens }
}

async function loadModels() {
  rawError.value = ''
  if (!apiKey.value.trim()) {
    message.value = '모델 목록을 조회하려면 AICONNECT API 키가 필요합니다.'
    return
  }
  loadingModels.value = true
  probeStatus.value = 'loading'
  const started = performance.now()
  try {
    const response = await fetch(`${normalizedBaseUrl()}/models`, { headers: jsonHeaders() })
    const parsed = await parseResponse(response)
    if (!response.ok) throw new Error(requestError(response, parsed.body, '모델 목록 조회에 실패했습니다.'))
    const data = parsed.body && typeof parsed.body === 'object' ? (parsed.body as { data?: ModelItem[] }).data : []
    models.value = Array.isArray(data) ? data : []
    probeStatus.value = 'ok'
    probeLatencyMs.value = Math.round(performance.now() - started)
    lastProbeAt.value = new Date().toLocaleTimeString()
    if (!model.value && models.value.length) model.value = models.value[0].id
    message.value = `${models.value.length}개의 사용 가능한 논리 모델을 확인했습니다. (${Math.round(performance.now() - started)}ms)`
  } catch (error) {
    models.value = []
    probeStatus.value = 'failed'
    probeLatencyMs.value = Math.round(performance.now() - started)
    lastProbeAt.value = new Date().toLocaleTimeString()
    rawError.value = error instanceof Error ? error.message : '모델 목록 조회에 실패했습니다.'
    message.value = '모델 목록을 확인하지 못했습니다. Base URL, API 키, Tailnet/사설망 연결을 확인하세요.'
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
      } catch {
        // Ignore keep-alive lines and continue assembling the SSE response.
      }
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
  const body: Record<string, unknown> = {
    model: model.value.trim(),
    messages,
    temperature: Number(temperature.value),
    max_completion_tokens: Number(maxTokens.value),
    stream: stream.value
  }
  if (responseMode.value === 'json') body.response_format = { type: 'json_object' }
  try {
    const response = await fetch(`${normalizedBaseUrl()}/chat/completions`, {
      method: 'POST', headers: jsonHeaders(), body: JSON.stringify(body)
    })
    const requestId = response.headers.get('X-Request-Id') ?? response.headers.get('x-request-id') ?? ''
    const parsed = stream.value && response.ok ? await readStream(response) : await parseResponse(response)
    const elapsedMs = Math.round(performance.now() - started)
    if (!response.ok) throw new Error(requestError(response, parsed.body, 'Chat Completions 요청이 실패했습니다.'))
    const usage = usageFrom(parsed.body)
    result.value = {
      status: response.status,
      elapsedMs,
      requestId,
      body: parsed.body,
      text: stream.value ? parsed.text : extractAssistantText(parsed.body),
      ...usage
    }
    message.value = `요청 성공 · ${elapsedMs}ms · ${usage.totalTokens ? `${usage.totalTokens.toLocaleString()} 토큰` : '사용량 미제공'}`
  } catch (error) {
    rawError.value = error instanceof Error ? error.message : 'Chat Completions 요청에 실패했습니다.'
    message.value = '요청이 실패했습니다. 아래 오류와 관측성의 Request ID를 함께 확인하세요.'
  } finally { running.value = false }
}

function clearResult() {
  result.value = null
  rawError.value = ''
  message.value = 'API 키를 입력하고 모델 목록을 확인한 뒤 테스트를 실행하세요.'
}

async function copyCurl() {
  try {
    if (!navigator.clipboard) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(curlExample.value)
    curlCopied.value = true
    message.value = 'curl 명령을 클립보드에 복사했습니다.'
    window.setTimeout(() => { curlCopied.value = false }, 1800)
  } catch {
    message.value = 'curl 명령을 복사하지 못했습니다. 아래 명령을 직접 복사해 주세요.'
  }
}
onMounted(() => {
  // The key is intentionally not restored from storage. A playground should never retain credentials.
  if (typeof window !== 'undefined' && window.location.origin.startsWith('http')) baseUrl.value = defaultBaseUrl()
})
</script>

<template>
  <section class="page-stack playground-page">
    <div class="page-hero playground-hero">
      <div>
        <p class="eyebrow">API PLAYGROUND</p>
        <h1>연결 테스트</h1>
        <p>실제 프로젝트를 수정하지 않고 AICONNECT Gateway를 통해 Local LM Studio 또는 외부 AI Provider를 점검합니다.</p>
      </div>
      <div class="playground-badge"><span class="live-dot"></span><span>키는 저장하지 않음</span></div>
    </div>

    <div class="playground-notice">
      <strong>안전한 점검 공간</strong>
      <span>입력한 API 키와 프롬프트는 브라우저 메모리에만 유지됩니다. 요청은 일반 API 호출과 동일하게 사용량·관측성에 기록됩니다.</span>
    </div>

    <div class="playground-grid">
    <div class="target-picker"><div class="target-picker-head"><div><span class="card-kicker">TEST TARGET</span><strong>Target selection</strong><small>Choose the request path to validate.</small></div><code>{{ normalizedBaseUrl() }}</code></div><div class="target-options"><button v-for="target in targetPresets" :key="target.id" type="button" :class="{ active: targetMode === target.id }" @click="applyTarget(target.id)"><span class="target-icon">{{ target.icon }}</span><span><strong>{{ target.label }}</strong><small>{{ target.description }}</small></span><i>{{ target.id === 'gateway' ? 'RECOMMENDED' : 'DIRECT' }}</i></button></div><p v-if="directTargetWarning" class="target-warning">Direct browser calls require CORS and network access. Gateway is recommended for production validation.</p></div>
      <article class="surface-card playground-card connection-card">
        <header class="card-header"><div><span class="card-kicker">CONNECTION</span><h2>Gateway 연결</h2></div><span class="status-chip tiny" :class="models.length ? 'healthy' : 'unknown'">{{ models.length ? 'READY' : '대기' }}</span></header>
        <div class="playground-form">
          <label class="field">Base URL <small>/v1가 포함된 Gateway 주소</small><input v-model.trim="baseUrl" placeholder="https://ai.company.example/v1" /></label>
          <label class="field">AICONNECT API Key <small>프로젝트에서 발급한 키를 입력하세요. 키는 저장되지 않습니다.</small><input v-model="apiKey" type="password" autocomplete="off" placeholder="sk_llmg_..." @keyup.enter="loadModels" /></label>
          <div class="form-actions"><button class="secondary-button" :disabled="loadingModels || !apiKey.trim()" @click="loadModels">{{ loadingModels ? '조회 중…' : '모델 목록 확인' }}</button><button class="ghost-button" :disabled="!result && !rawError" @click="clearResult">결과 지우기</button></div>
        </div>
        <p class="field-hint">현재 주소: <code>{{ normalizedBaseUrl() }}</code></p>
        <div v-if="probeStatus !== 'idle'" class="probe-summary"><span class="status-chip tiny" :class="probeStatus === 'ok' ? 'healthy' : probeStatus === 'failed' ? 'unhealthy' : 'suspect'"><i></i>{{ probeStatus === 'ok' ? '연결됨' : probeStatus === 'failed' ? '연결 실패' : '확인 중' }}</span><div><strong>GET /models</strong><small>{{ probeStatus === 'ok' ? models.length + '개 모델 확인' : '모델 목록을 조회하지 못했습니다.' }}</small></div><time v-if="probeLatencyMs">{{ probeLatencyMs }}ms · {{ lastProbeAt }}</time></div>
        <div v-if="models.length" class="model-list"><button v-for="item in models" :key="item.id" type="button" :class="{ selected: model === item.id }" @click="model = item.id"><strong>{{ item.id }}</strong><small>{{ item.owned_by ?? 'aiconnect' }}</small></button></div>
        <div v-else class="empty-state compact"><span>◇</span><p>키를 입력하면 프로젝트에 허용된 논리 모델이 표시됩니다.</p></div>
      </article>

      <article class="surface-card playground-card request-card">
        <header class="card-header"><div><span class="card-kicker">CHAT COMPLETIONS</span><h2>요청 보내기</h2></div><span class="request-method">POST</span></header>
        <div class="playground-form">
          <label class="field">Model <small>위에서 확인한 논리 모델명 또는 직접 입력</small><input v-model.trim="model" list="playground-model-list" placeholder="text-pro" /><datalist id="playground-model-list"><option v-for="item in models" :key="item.id" :value="item.id" /></datalist></label>
          <label class="field">System Prompt <small>선택 사항</small><textarea v-model="systemPrompt" rows="2" placeholder="응답 규칙을 입력하세요 (선택)" /></label>
          <label class="field">User Prompt <textarea v-model="prompt" rows="5" placeholder="테스트할 요청을 입력하세요" /></label>
          <div class="option-grid"><label class="field">Temperature<input v-model.number="temperature" type="number" min="0" max="2" step="0.1" /></label><label class="field">Max Completion Tokens<input v-model.number="maxTokens" type="number" min="1" max="32768" step="1" /></label><label class="field">응답 형식<select v-model="responseMode"><option value="text">일반 텍스트</option><option value="json">JSON object</option></select></label></div>
          <div class="toggle-row"><label><input v-model="stream" type="checkbox" /> SSE 스트리밍으로 테스트</label><span v-if="selectedModel">선택됨: <code>{{ selectedModel.id }}</code></span></div>
          <button class="primary-button run-button" :disabled="!canRun" @click="runTest"><span>{{ running ? '요청 처리 중…' : '테스트 요청 실행' }}</span><b>↗</b></button>
           <details class="request-contract"><summary>요청용 curl 보기</summary><div class="contract-grid"><span><b>1</b> GET {{ normalizedBaseUrl() }}/models</span><span><b>2</b> POST {{ normalizedBaseUrl() }}/chat/completions</span><span><b>3</b> Bearer API Key 인증</span><span><b>4</b> {{ stream ? 'SSE event stream' : 'JSON response' }}</span></div><pre>{{ curlExample }}</pre><button class="ghost-button" @click="copyCurl">{{ curlCopied ? '복사됨' : 'curl 복사' }}</button></details>
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
    <article v-else-if="rawError" class="surface-card playground-card result-card error-result"><header class="card-header"><div><span class="card-kicker">DIAGNOSTICS</span><h2>요청 실패</h2></div><span class="status-chip danger">ERROR</span></header><p>{{ rawError }}</p><p>Base URL과 키의 프로젝트 권한, 서비스 Target 상태, Runtime/Provider 상태를 확인하세요.</p></article>
  </section>
</template>

<style scoped>
.playground-page { max-width: 1480px; margin-inline: auto; }
.playground-hero { align-items: center; }
.playground-badge { display: inline-flex; align-items: center; gap: 9px; padding: 11px 14px; border: 1px solid var(--accent-border); border-radius: 999px; background: var(--accent-dim); color: var(--text-soft); font-size: 11px; font-weight: 700; white-space: nowrap; }
.playground-badge .live-dot { margin: 0; }
.playground-notice { padding: 14px 16px; display: flex; gap: 12px; align-items: baseline; border: 1px solid var(--accent-border); border-radius: 13px; background: var(--accent-dim); color: var(--text-soft); font-size: 12px; line-height: 1.55; }
.playground-notice strong { color: var(--accent-strong); white-space: nowrap; }
.playground-grid { display: grid; grid-template-columns: minmax(320px, .84fr) minmax(0, 1.16fr); gap: 18px; align-items: start; }
.playground-card { padding: 23px; }
.playground-card .card-header { margin-bottom: 20px; }
.playground-card .card-header h2 { margin: 6px 0 0; font-size: 20px; }
.request-method { padding: 5px 8px; border-radius: 7px; background: var(--surface-3); color: var(--accent-strong); font: 700 10px 'SFMono-Regular', Consolas, monospace; }
.playground-form { display: grid; gap: 15px; }
.field small { color: var(--muted); font-size: 10px; font-weight: 500; line-height: 1.4; }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; }
.field-hint { margin: 16px 0 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.field-hint code { color: var(--accent-strong); overflow-wrap: anywhere; }
.model-list { margin-top: 17px; display: grid; gap: 8px; max-height: 230px; overflow: auto; }
.model-list button { display: grid; gap: 5px; padding: 11px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); text-align: left; }
.model-list button:hover, .model-list button.selected { border-color: var(--accent-border); background: var(--accent-dim); }
.model-list strong { overflow: hidden; font: 600 12px 'SFMono-Regular', Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.model-list small { color: var(--muted); font-size: 10px; }
.option-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: var(--text-soft); font-size: 11px; }
.toggle-row label { display: inline-flex; gap: 8px; align-items: center; font-weight: 700; }
.toggle-row input { width: 16px; height: 16px; accent-color: var(--accent-strong); }
.toggle-row span { color: var(--muted); }
.toggle-row code { color: var(--accent-strong); }
.run-button { width: 100%; min-height: 48px; justify-content: space-between; margin-top: 3px; }
.run-button b { font-size: 18px; }
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
@media (max-width: 620px) { .playground-notice { display: grid; gap: 5px; } .option-grid, .result-metrics { grid-template-columns: 1fr 1fr; } .toggle-row { align-items: flex-start; flex-direction: column; } .playground-card { padding: 17px; } }
</style>
.target-picker { grid-column: 1 / -1; padding: 16px 18px; border: 1px solid var(--border); border-radius: 15px; background: var(--surface); }
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
.probe-summary { margin-top: 12px; padding: 10px; display: flex; align-items: center; gap: 9px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.probe-summary > div { min-width: 0; display: grid; gap: 3px; }
.probe-summary strong, .probe-summary small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.probe-summary strong { font-size: 10px; }
.probe-summary small, .probe-summary time { color: var(--muted); font-size: 8px; }
.probe-summary time { margin-left: auto; white-space: nowrap; }
.request-contract { padding-top: 13px; border-top: 1px solid var(--border); color: var(--muted); font-size: 10px; }
.request-contract summary { cursor: pointer; color: var(--text-soft); font-weight: 700; }
.contract-grid { margin-top: 11px; display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px; }
.contract-grid span { padding: 7px 8px; border-radius: 7px; background: var(--surface-2); font-size: 8px; }
.contract-grid b { display: inline-grid; width: 16px; height: 16px; margin-right: 5px; place-content: center; border-radius: 5px; background: var(--accent-dim); color: var(--accent-strong); }
.request-contract pre { margin: 10px 0; padding: 10px; border-radius: 8px; background: var(--bg-soft); color: var(--accent-strong); font-size: 8px; line-height: 1.5; }
.request-contract .ghost-button { min-height: 30px; padding-inline: 9px; font-size: 9px; }
@media (max-width: 700px) { .target-options { grid-template-columns: 1fr; } .target-picker-head { align-items: flex-start; flex-direction: column; } .contract-grid { grid-template-columns: 1fr; } .probe-summary { align-items: flex-start; flex-wrap: wrap; } .probe-summary time { margin-left: 0; width: 100%; } }
