<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { adminFetch, adminResponse, type AdminAuth } from './api'

type Target = {
  id: string; sourceId: string; targetType: 'RUNTIME' | 'EXTERNAL_PROVIDER'; providerName: string; protocol: string
  endpointUrl: string; modelId: string; displayName: string; status: string; enabled: boolean; loaded: boolean; loadState: string; canChat: boolean
  contextLength?: number | null; maxConcurrency: number; capabilities: string[]; inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null; currency?: string | null; nodeName?: string | null
}
type Trace = {
  requestId: string; source: string; targetType: string; targetName: string; modelId: string; endpointUrl: string
  status: string; stream: boolean; httpStatus?: number | null; latencyMs?: number | null
  inputTokens?: number | null; outputTokens?: number | null; errorCode?: string | null; startedAt: string
}
type Probe = { reachable: boolean; httpStatus: number; latencyMs: number; modelAvailable: boolean; modelCount: number; message?: string | null }
type Turn = { id: string; role: 'user' | 'assistant'; content: string; files?: string[]; attachmentPayloads?: Attachment[]; status?: string; httpStatus?: number; latencyMs?: number; requestId?: string; inputTokens?: number | null; outputTokens?: number | null; totalTokens?: number | null; stream?: boolean; error?: string }
type Attachment = { name: string; mediaType: string; base64: string; messageIndex?: number }

const props = defineProps<{ organizationId?: string; auth: AdminAuth; initialTargetId?: string }>()
const targets = ref<Target[]>([])
const selectedTargetId = ref('')
const conversation = ref<Turn[]>([])
const prompt = ref('')
const selectedFiles = ref<File[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const chatPane = ref<HTMLElement | null>(null)
const loading = ref(false)
const sending = ref(false)
const probing = ref(false)
const stream = ref(true)
const includeTemperature = ref(false)
const temperature = ref(0.2)
const maxCompletionTokens = ref(512)
const responseMode = ref<'text' | 'json'>('text')
const connection = ref<Probe | null>(null)
const loadError = ref('')
const notice = ref('')
const traces = ref<Trace[]>([])
const traceLoading = ref(false)

const selectedTarget = computed(() => targets.value.find(item => item.id === selectedTargetId.value) ?? null)
const availableTargets = computed(() => targets.value.filter(item => item.canChat))
const candidateTargets = computed(() => targets.value.filter(item => item.targetType === 'RUNTIME' && !item.canChat && !['NOT_FOUND', 'UNAVAILABLE', 'FAILED'].includes(item.loadState)))
const unavailableTargets = computed(() => targets.value.filter(item => item.targetType === 'RUNTIME' && ['NOT_FOUND', 'UNAVAILABLE', 'FAILED'].includes(item.loadState)))
const initialTargetMissing = computed(() => Boolean(props.initialTargetId?.trim() && targets.value.length
  && !targets.value.some(item => item.id === props.initialTargetId || item.sourceId === props.initialTargetId)))
const supportsVision = computed(() => selectedTarget.value?.capabilities.includes('VISION') ?? false)
const canSend = computed(() => Boolean(props.organizationId && selectedTarget.value?.canChat && !sending.value && (prompt.value.trim() || selectedFiles.value.length)))
const statusLabel = computed(() => {
  if (!selectedTarget.value) return '모델 선택 필요'
  if (!selectedTarget.value.enabled) return '비활성'
  if (!selectedTarget.value.canChat) return loadStateLabel(selectedTarget.value.loadState)
  if (connection.value) return connection.value.reachable ? connection.value.modelAvailable ? '연결됨 · 모델 확인' : '연결됨 · 모델 미확인' : '연결 실패'
  return selectedTarget.value.status
})

function apiPath(path: string) { return `/api/admin/organizations/${props.organizationId}/playground/${path}` }
function pickInitialTarget() {
  if (!targets.value.length) { selectedTargetId.value = ''; return }
  const initial = props.initialTargetId?.trim()
  if (initial) {
    const direct = targets.value.find(item => item.id === initial)
    const sourceModels = targets.value.filter(item => item.sourceId === initial)
    const source = sourceModels.find(item => item.canChat) ?? sourceModels[0]
    const found = direct ?? source
    if (found) { selectedTargetId.value = found.id; return }
    selectedTargetId.value = ''
    return
  }
  if (!targets.value.some(item => item.id === selectedTargetId.value)) selectedTargetId.value = availableTargets.value[0]?.id ?? targets.value[0].id
}
async function load() {
  if (!props.organizationId) { targets.value = []; traces.value = []; return }
  loading.value = true
  loadError.value = ''
  try {
    const [catalog, history] = await Promise.all([
      adminFetch<Target[]>(apiPath('targets'), props.auth),
      adminFetch<Trace[]>(apiPath('requests'), props.auth)
    ])
    targets.value = catalog
    traces.value = history
    pickInitialTarget()
  } catch (error) { loadError.value = error instanceof Error ? error.message : '등록된 모델 목록을 불러오지 못했습니다.' }
  finally { loading.value = false }
}
async function loadRequests() {
  if (!props.organizationId) return
  traceLoading.value = true
  try { traces.value = await adminFetch<Trace[]>(apiPath('requests'), props.auth) }
  catch { /* Chat result remains available even when history refresh fails. */ }
  finally { traceLoading.value = false }
}
function onTargetChanged() { connection.value = null }
function loadStateLabel(state: string) {
  return ({ LOADED: '로드됨 · 테스트 가능', UNLOADED: '미로드 · 먼저 로드하세요', LOADING: '로딩 중', DOWNLOADING: '다운로드 중', SLEEPING: '절전 상태 · 깨운 뒤 테스트', FAILED: '로드 실패', NOT_FOUND: 'Runtime에서 찾을 수 없음', UNKNOWN: '현재 로드 상태 확인 필요' } as Record<string, string>)[state] ?? state
}
function clearConversation() { conversation.value = []; notice.value = '대화를 초기화했습니다.' }
async function checkConnection() {
  if (!selectedTarget.value || !props.organizationId) return
  probing.value = true
  connection.value = null
  try {
    connection.value = await adminFetch<Probe>(apiPath(`targets/${selectedTarget.value.id}/probe`), props.auth, { method: 'POST' })
    notice.value = connection.value.reachable
      ? connection.value.modelAvailable ? `연결 확인 완료 · 등록 모델 ${connection.value.modelCount}개` : 'Endpoint는 연결되었지만 선택된 모델 ID는 현재 목록에서 확인되지 않습니다.'
      : connection.value.message || '모델 서버에 연결하지 못했습니다.'
  } catch (error) { notice.value = error instanceof Error ? error.message : '연결 확인에 실패했습니다.' }
  finally { probing.value = false }
}
function errorText(body: unknown, status: number) {
  if (body && typeof body === 'object') {
    const value = body as { error?: { message?: string; code?: string } | string; message?: string; code?: string }
    const err = typeof value.error === 'object' ? value.error : undefined
    const message = err?.message || (typeof value.error === 'string' ? value.error : '') || value.message || `요청이 실패했습니다. (HTTP ${status})`
    const code = err?.code || value.code
    return code ? `${message} [${code}]` : message
  }
  return typeof body === 'string' && body.trim() ? body : `요청이 실패했습니다. (HTTP ${status})`
}
async function parseResponse(response: Response) {
  const text = await response.text()
  if (!text) return null as unknown
  try { return JSON.parse(text) as unknown } catch { return text }
}
function responseText(body: unknown) {
  if (!body || typeof body !== 'object') return ''
  const content = (body as { choices?: Array<{ message?: { content?: unknown } }> }).choices?.[0]?.message?.content
  if (typeof content === 'string') return content
  if (Array.isArray(content)) return content.map(item => item && typeof item === 'object' && 'text' in item ? String((item as { text: unknown }).text) : '').filter(Boolean).join('\n')
  return content == null ? '' : JSON.stringify(content)
}
function tokenCount(value: unknown): number | null {
  if (value == null || !Number.isFinite(Number(value))) return null
  return Math.max(0, Number(value))
}
function usage(body: unknown) {
  const raw = body && typeof body === 'object' ? (body as { usage?: Record<string, unknown> }).usage : undefined
  const input = raw?.prompt_tokens ?? raw?.input_tokens
  const output = raw?.completion_tokens ?? raw?.output_tokens
  const inputTokens = tokenCount(input)
  const outputTokens = tokenCount(output)
  const total = raw?.total_tokens
  return { inputTokens, outputTokens, totalTokens: tokenCount(total) ?? (inputTokens == null && outputTokens == null ? null : (inputTokens ?? 0) + (outputTokens ?? 0)) }
}
function newTurnId() { return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}` }
async function readStream(response: Response, turn: Turn) {
  if (!response.body) return
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (!data || data === '[DONE]') continue
      try {
        const event = JSON.parse(data) as { error?: unknown; choices?: Array<{ delta?: { content?: unknown } }>; usage?: Record<string, unknown> }
        if (event.error) {
          turn.error = errorText(event.error, response.status)
          turn.status = 'FAILED'
        }
        const delta = event.choices?.[0]?.delta?.content
        if (typeof delta === 'string') turn.content += delta
        else if (Array.isArray(delta)) turn.content += delta.map(part => part && typeof part === 'object' && 'text' in part ? String((part as { text: unknown }).text) : '').join('')
        if (event.usage) {
          turn.inputTokens = tokenCount(event.usage.prompt_tokens ?? event.usage.input_tokens)
          turn.outputTokens = tokenCount(event.usage.completion_tokens ?? event.usage.output_tokens)
          turn.totalTokens = tokenCount(event.usage.total_tokens) ?? (turn.inputTokens == null && turn.outputTokens == null ? null : (turn.inputTokens ?? 0) + (turn.outputTokens ?? 0))
        }
        await nextTick(() => { if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight })
      } catch { /* Ignore SSE comments/keep-alive lines. */ }
    }
  }
}
async function toAttachment(file: File): Promise<Attachment> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  const chunkSize = 0x8000
  for (let i = 0; i < bytes.length; i += chunkSize) binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize))
  return { name: file.name, mediaType: file.type || mimeFromExtension(file.name), base64: btoa(binary) }
}
function mimeFromExtension(name: string) {
  const ext = name.split('.').pop()?.toLowerCase()
  return ({ png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif', pdf: 'application/pdf', json: 'application/json', csv: 'text/csv', tsv: 'text/tab-separated-values', md: 'text/markdown', txt: 'text/plain', xml: 'application/xml', log: 'text/plain' } as Record<string, string>)[ext ?? ''] ?? 'application/octet-stream'
}
function addFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const incoming = Array.from(input.files ?? [])
  const allowed = /\.(pdf|txt|text|md|markdown|json|csv|tsv|xml|log|jpe?g|png|webp|gif)$/i
  const rejected = incoming.filter(file => !allowed.test(file.name) || file.size === 0 || file.size > 4 * 1024 * 1024)
  if (rejected.length) notice.value = '지원하지 않는 파일이 있거나 파일이 4MB를 초과합니다. 이미지·텍스트 파일·PDF(텍스트 추출 가능)만 추가할 수 있습니다.'
  const accepted = incoming.filter(file => allowed.test(file.name) && file.size > 0 && file.size <= 4 * 1024 * 1024)
  if (selectedFiles.value.length + accepted.length > 5) notice.value = '한 번에 최대 5개 파일까지 첨부할 수 있습니다.'
  selectedFiles.value = [...selectedFiles.value, ...accepted].slice(0, 5)
  if (!supportsVision.value && selectedFiles.value.some(file => file.type.startsWith('image/') || /\.(jpe?g|png|webp|gif)$/i.test(file.name))) {
    notice.value = '선택 모델에 VISION capability가 등록되지 않아 이미지를 전송할 수 없습니다. 모델을 바꾸거나 이미지 파일을 제거하세요.'
  }
  input.value = ''
}
function removeFile(index: number) { selectedFiles.value.splice(index, 1) }
async function send() {
  if (!canSend.value || !selectedTarget.value || !props.organizationId) return
  if (selectedFiles.value.some(file => (file.type.startsWith('image/') || /\.(jpe?g|png|webp|gif)$/i.test(file.name)) && !supportsVision.value)) {
    notice.value = '선택 모델이 VISION을 지원하지 않아 이미지 파일을 보낼 수 없습니다.'
    return
  }
  const text = prompt.value.trim()
  if (!text && !selectedFiles.value.length) return
  sending.value = true
  notice.value = ''
  const files = [...selectedFiles.value]
  const currentMessageIndex = conversation.value.length
  const historyAttachments = conversation.value.flatMap((turn, messageIndex) => turn.role === 'user'
    ? (turn.attachmentPayloads ?? []).map(attachment => ({ ...attachment, messageIndex }))
    : [])
  const started = performance.now()
  const userTurn: Turn = { id: newTurnId(), role: 'user', content: text || '첨부 파일을 확인해 주세요.', files: files.map(file => file.name) }
  const assistantTurn: Turn = { id: newTurnId(), role: 'assistant', content: '', status: '요청 중', stream: stream.value }
  const requestMessages = [...conversation.value.map(turn => ({ role: turn.role, content: turn.content })), { role: 'user', content: userTurn.content }]
  conversation.value.push(userTurn, assistantTurn)
  prompt.value = ''
  selectedFiles.value = []
  await nextTick(() => { if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight })
  try {
    const attachments = await Promise.all(files.map(toAttachment))
    conversation.value[currentMessageIndex].attachmentPayloads = attachments
    const allAttachments = [...historyAttachments, ...attachments.map(attachment => ({ ...attachment, messageIndex: currentMessageIndex }))]
    const attachmentBytes = allAttachments.reduce((total, attachment) => total + Math.floor(attachment.base64.length * 3 / 4), 0)
    if (allAttachments.length > 20 || attachmentBytes > 8 * 1024 * 1024) {
      throw new Error('현재 대화에 포함된 첨부 파일은 총 20개·8MB까지 보낼 수 있습니다. 새 대화를 시작하거나 기존 첨부 대화를 지워 주세요.')
    }
    const body: Record<string, unknown> = {
      targetId: selectedTarget.value.id,
      messages: requestMessages,
      attachments: allAttachments,
      stream: stream.value,
      maxCompletionTokens: Number(maxCompletionTokens.value),
      responseMode: responseMode.value
    }
    if (includeTemperature.value) body.temperature = Number(temperature.value)
    const response = await adminResponse(apiPath('chat'), props.auth, {
      method: 'POST',
      headers: { Accept: stream.value ? 'text/event-stream, application/json' : 'application/json' },
      body: JSON.stringify(body)
    })
    assistantTurn.httpStatus = response.status
    assistantTurn.requestId = response.headers.get('X-Request-Id') ?? undefined
    assistantTurn.latencyMs = Math.round(performance.now() - started)
    if (!response.ok) {
      const errorBody = await parseResponse(response)
      assistantTurn.error = errorText(errorBody, response.status)
      assistantTurn.status = 'FAILED'
    } else if (stream.value && response.headers.get('content-type')?.includes('text/event-stream')) {
      assistantTurn.status = 'STREAMING'
      await readStream(response, assistantTurn)
      assistantTurn.status = assistantTurn.error ? 'FAILED' : 'SUCCEEDED'
    } else {
      const result = await parseResponse(response)
      assistantTurn.content = responseText(result)
      Object.assign(assistantTurn, usage(result))
      assistantTurn.status = response.status >= 200 && response.status < 300 ? 'SUCCEEDED' : 'FAILED'
      if (response.status >= 400) assistantTurn.error = errorText(result, response.status)
    }
    assistantTurn.latencyMs = Math.round(performance.now() - started)
    if (assistantTurn.error) notice.value = '요청 실패 · ' + assistantTurn.error
    else notice.value = `응답 완료 · ${assistantTurn.latencyMs.toLocaleString()}ms${assistantTurn.inputTokens != null || assistantTurn.outputTokens != null ? ` · 입력 ${assistantTurn.inputTokens ?? '-'} / 출력 ${assistantTurn.outputTokens ?? '-'} 토큰` : ''}`
  } catch (error) {
    assistantTurn.status = 'FAILED'
    assistantTurn.error = error instanceof Error ? error.message : '요청을 완료하지 못했습니다.'
    assistantTurn.latencyMs = Math.round(performance.now() - started)
    notice.value = '요청 실패 · ' + assistantTurn.error
  } finally {
    sending.value = false
    await loadRequests()
    await nextTick(() => { if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight })
  }
}
function handleEnter(event: KeyboardEvent) { if (!event.shiftKey && !event.isComposing) { event.preventDefault(); void send() } }
function formatTime(value: string) { return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) }
function costLabel(target: Target) {
  const symbol = target.currency === 'USD' ? '$' : target.currency === 'KRW' ? '₩' : ''
  if (target.inputPricePerMillion == null && target.outputPricePerMillion == null) return '단가 미설정'
  return `입력 ${symbol}${target.inputPricePerMillion ?? '-'} / 출력 ${symbol}${target.outputPricePerMillion ?? '-'} · 1M`
}

watch(() => [props.organizationId, props.initialTargetId], () => { void load() }, { immediate: true })
</script>

<template>
  <section class="page-stack model-playground-page">
    <div class="page-hero model-playground-hero">
      <div><p class="eyebrow">MODEL CHAT PLAYGROUND</p><h1>모델 직접 테스트</h1><p>등록된 Runtime 또는 외부 Provider의 인증 설정을 이용해 특정 모델에 직접 요청합니다.</p></div>
      <div class="playground-top-actions"><button class="secondary-button" :disabled="loading" @click="load">{{ loading ? '불러오는 중…' : '새로고침' }}</button><button class="secondary-button" :disabled="!conversation.length || sending" @click="clearConversation">새 대화</button></div>
    </div>

    <div class="model-playground-warning"><strong>직접 테스트 안내</strong><span>이 경로는 관리자 직접 호출이며 프로젝트 권한·총량제·데이터 보호 정책을 거치지 않습니다. 프롬프트와 파일 내용은 저장하지 않으며, 테스트 메타데이터만 <code>PLAYGROUND</code> 이력으로 기록합니다. 민감한 운영 데이터는 입력하지 마세요.</span></div>

    <div v-if="loadError" class="inline-alert error-alert">{{ loadError }}</div>
    <article v-if="!organizationId" class="surface-card playground-empty"><strong>조직을 선택하세요</strong><p>조직에 등록된 Runtime, Deployment와 외부 Provider 모델을 불러옵니다.</p></article>
    <div v-else class="model-playground-layout">
      <aside class="surface-card model-playground-sidebar">
        <div class="playground-sidebar-head"><div><span class="card-kicker">REGISTERED MODELS</span><h2>테스트 대상</h2></div><span class="count-pill">{{ availableTargets.length }} 활성</span></div>
        <label class="field">모델 선택<select v-model="selectedTargetId" :disabled="loading || !targets.length" @change="onTargetChanged"><option value="" disabled>모델을 선택하세요</option><optgroup v-if="availableTargets.length" label="현재 대화 테스트 가능"><option v-for="target in availableTargets" :key="target.id" :value="target.id">{{ target.displayName }} · {{ target.providerName }}</option></optgroup><optgroup v-if="candidateTargets.length" label="Runtime 후보 · 로드 후 테스트"><option v-for="target in candidateTargets" :key="target.id" :value="target.id">{{ target.displayName }} · {{ loadStateLabel(target.loadState) }}</option></optgroup><optgroup v-if="unavailableTargets.length" label="미발견·접속 불가 모델 기록"><option v-for="target in unavailableTargets" :key="target.id" :value="target.id" disabled>{{ target.displayName }} · {{ loadStateLabel(target.loadState) }}</option></optgroup><optgroup v-if="targets.some(target => target.targetType === 'EXTERNAL_PROVIDER' && !target.canChat)" label="비활성 Provider 모델"><option v-for="target in targets.filter(item => item.targetType === 'EXTERNAL_PROVIDER' && !item.canChat)" :key="target.id" :value="target.id" disabled>{{ target.displayName }} · 비활성</option></optgroup></select></label>
        <div v-if="selectedTarget" class="playground-target-info">
          <div class="target-info-row"><span>연결 상태</span><b :class="connection?.reachable ? 'good' : ''">{{ statusLabel }}</b></div>
          <div v-if="selectedTarget.targetType === 'RUNTIME'" class="target-info-row"><span>모델 상태</span><b>{{ loadStateLabel(selectedTarget.loadState) }}</b></div>
          <div class="target-info-row"><span>Provider / Runtime</span><b>{{ selectedTarget.providerName }} · {{ selectedTarget.protocol }}</b></div>
          <div class="target-info-row"><span>모델</span><b>{{ selectedTarget.displayName }} <code>{{ selectedTarget.modelId }}</code></b></div>
          <div class="target-info-row"><span>Endpoint</span><code>{{ selectedTarget.endpointUrl }}</code></div>
          <div class="target-info-row"><span>Context · 동시성</span><b>{{ selectedTarget.contextLength?.toLocaleString() ?? '-' }} · {{ selectedTarget.maxConcurrency }}</b></div>
          <div class="target-info-row"><span>요금표</span><b>{{ costLabel(selectedTarget) }}</b></div>
          <div class="target-capabilities"><span v-for="capability in selectedTarget.capabilities" :key="capability" class="capability-pill">{{ capability }}</span><span v-if="!selectedTarget.capabilities.length" class="muted">Capability 정보 없음</span></div>
          <p v-if="selectedTarget.targetType === 'RUNTIME' && !selectedTarget.loaded" class="target-warning">모델 상태가 LOADED가 아닙니다. Runtime에 모델을 준비한 뒤에도 테스트 요청은 실행되며, 실제 오류를 그대로 표시합니다.</p>
          <p v-if="!selectedTarget.canChat" class="target-warning">이 후보는 현재 채팅 대상이 아닙니다. Infrastructure에서 모델을 로드하고 동기화하면 활성 테스트 목록으로 이동합니다.</p>
          <button class="secondary-button full-width" :disabled="probing" @click="checkConnection">{{ probing ? '확인 중…' : '연결 및 모델 확인' }}</button>
          <div v-if="connection" class="probe-detail" :class="connection.reachable ? 'good' : 'bad'">{{ connection.reachable ? `HTTP ${connection.httpStatus} · ${connection.latencyMs}ms · ${connection.modelCount}개 모델` : connection.message }}</div>
        </div>
        <div v-else class="empty-state compact"><span>◈</span><p>{{ loading ? '등록 모델을 불러오는 중입니다.' : initialTargetMissing ? '선택한 Runtime 또는 Provider에 테스트할 등록 모델이 없습니다. Runtime은 모델 동기화를, 외부 Provider는 모델 등록을 먼저 완료하세요.' : '조직에 등록된 모델이 없습니다. 인프라스트럭처에서 모델 동기화를 하거나 외부 AI에서 모델을 등록하세요.' }}</p></div>
      </aside>

      <main class="surface-card chat-playground">
        <header class="chat-playground-header"><div><span class="card-kicker">LIVE MODEL SESSION</span><h2>{{ selectedTarget?.displayName ?? '모델을 선택하세요' }}</h2><small>{{ selectedTarget ? `${selectedTarget.providerName} · ${selectedTarget.endpointUrl}` : '테스트할 모델을 왼쪽에서 선택하세요.' }}</small></div><div class="chat-header-controls"><label class="stream-toggle"><input v-model="stream" type="checkbox" /> 스트리밍</label><button class="secondary-button" :disabled="!selectedTarget || probing" @click="checkConnection">연결 확인</button></div></header>
        <div ref="chatPane" class="chat-transcript" aria-live="polite">
          <div v-if="!conversation.length" class="chat-welcome"><span>◈</span><strong>실제 모델에 테스트 요청을 보내세요</strong><p>대화는 현재 화면 메모리에만 유지됩니다. 선택 모델은 변경하지 않고 정확한 등록 모델 ID로 호출합니다.</p></div>
          <article v-for="turn in conversation" :key="turn.id" class="chat-turn" :class="[turn.role, { failed: turn.status === 'FAILED' }]">
            <div class="chat-turn-avatar">{{ turn.role === 'user' ? 'ME' : 'AI' }}</div>
            <div class="chat-turn-body"><div class="chat-turn-heading"><strong>{{ turn.role === 'user' ? '사용자' : '모델 응답' }}</strong><span v-if="turn.status" :class="turn.status === 'FAILED' ? 'bad' : turn.status === 'SUCCEEDED' ? 'good' : ''">{{ turn.status }}</span></div>
              <p v-if="turn.role === 'user'" class="chat-turn-content">{{ turn.content }}</p>
              <pre v-else-if="turn.content" class="chat-turn-content">{{ turn.content }}</pre>
              <p v-else-if="sending" class="chat-loading-copy">모델 응답을 기다리는 중…</p>
              <p v-if="turn.files?.length" class="chat-files">첨부: {{ turn.files.join(', ') }}</p>
              <div v-if="turn.error" class="chat-turn-error"><strong>실패 사유</strong><p>{{ turn.error }}</p></div>
              <div v-if="turn.role === 'assistant' && turn.httpStatus" class="chat-turn-meta"><span>HTTP {{ turn.httpStatus }}</span><span>{{ turn.latencyMs?.toLocaleString() ?? '-' }} ms</span><span v-if="turn.inputTokens != null">입력 {{ turn.inputTokens }} · 출력 {{ turn.outputTokens ?? 0 }} · 전체 {{ turn.totalTokens ?? (turn.inputTokens + (turn.outputTokens ?? 0)) }} 토큰</span><span>{{ turn.stream ? 'SSE' : 'JSON' }}</span><code v-if="turn.requestId">{{ turn.requestId }}</code></div>
            </div>
          </article>
        </div>
        <div v-if="notice" class="playground-inline-notice" :class="{ failed: notice.startsWith('요청 실패') || notice.startsWith('연결 실패') }">{{ notice }}</div>
        <div class="chat-composer">
          <div v-if="selectedFiles.length" class="selected-files"><span v-for="(file, index) in selectedFiles" :key="`${file.name}-${index}`" class="file-pill"><span>▧ {{ file.name }} · {{ (file.size / 1024).toFixed(0) }} KB</span><button type="button" :aria-label="`${file.name} 제거`" @click="removeFile(index)">×</button></span></div>
          <textarea v-model="prompt" rows="3" :disabled="sending" :placeholder="selectedTarget?.canChat ? '메시지를 입력하세요. Enter 전송 · Shift+Enter 줄바꿈' : '모델을 선택하기 전에도 입력할 수 있습니다. 활성 모델을 선택하면 전송할 수 있습니다.'" @keydown="handleEnter" />
          <div class="composer-actions"><div class="composer-tools"><input ref="fileInput" type="file" multiple accept="image/jpeg,image/png,image/webp,image/gif,.pdf,.txt,.text,.md,.markdown,.json,.csv,.tsv,.xml,.log" hidden @change="addFiles" /><button class="secondary-button" type="button" :disabled="sending" @click="fileInput?.click()">파일 첨부</button><span class="file-support-hint">이미지 {{ supportsVision ? '지원' : 'VISION 모델 필요' }} · 텍스트/PDF 최대 4MB</span></div><button class="primary-button send-button" :disabled="!canSend" @click="send">{{ sending ? '응답 중…' : '전송' }} <span>↗</span></button></div>
          <details class="chat-advanced"><summary>요청 옵션</summary><div class="advanced-options"><label class="toggle-field"><span>Temperature 전송<small>기본은 생략해 모델 기본값을 사용합니다.</small></span><input v-model="includeTemperature" type="checkbox" /></label><label v-if="includeTemperature" class="field">Temperature<input v-model.number="temperature" type="number" min="0" max="2" step="0.1" /></label><label class="field">Max completion tokens<input v-model.number="maxCompletionTokens" type="number" min="1" max="32768" step="1" /></label><label class="field">응답 형식<select v-model="responseMode"><option value="text">일반 텍스트</option><option value="json">JSON object</option></select></label></div></details>
        </div>
      </main>
    </div>

    <details class="surface-card playground-history"><summary><span><b>PLAYGROUND 요청 이력</b><small>요청 내용과 파일은 저장하지 않습니다. 화면에는 최근 50건의 상태 메타데이터가 표시됩니다.</small></span><i>{{ traceLoading ? '갱신 중…' : `${traces.length}건` }}</i></summary><div v-if="traces.length" class="playground-history-table"><div class="playground-history-row history-heading"><span>시간 / 모델</span><span>상태</span><span>방식 / HTTP</span><span>응답 시간 / 토큰</span><span>오류</span></div><div v-for="trace in traces" :key="trace.requestId" class="playground-history-row"><span><b>{{ trace.modelId }}</b><small>{{ formatTime(trace.startedAt) }} · {{ trace.targetName }}</small></span><span :class="trace.status === 'SUCCEEDED' ? 'good' : trace.status === 'FAILED' ? 'bad' : ''">{{ trace.status }}</span><span>{{ trace.stream ? 'SSE' : 'JSON' }} · HTTP {{ trace.httpStatus ?? '-' }}</span><span>{{ trace.latencyMs?.toLocaleString() ?? '-' }}ms · {{ trace.inputTokens ?? '-' }}/{{ trace.outputTokens ?? '-' }}</span><code>{{ trace.errorCode ?? trace.requestId }}</code></div></div><p v-else class="history-empty">아직 테스트 요청이 없습니다.</p></details>
  </section>
</template>

<style scoped>
.model-playground-page{max-width:1600px;margin-inline:auto}.model-playground-hero{align-items:center}.playground-top-actions{display:flex;gap:8px;flex-wrap:wrap}.model-playground-warning{display:flex;gap:12px;align-items:baseline;padding:13px 15px;border:1px solid color-mix(in srgb,var(--warning) 35%,var(--border));border-radius:12px;background:var(--surface-2);color:var(--text-soft);font-size:11px;line-height:1.6}.model-playground-warning strong{color:var(--warning);white-space:nowrap}.model-playground-warning code{color:var(--accent-strong)}.model-playground-layout{display:grid;grid-template-columns:minmax(280px,340px) minmax(0,1fr);gap:14px;align-items:stretch}.model-playground-sidebar{padding:17px;display:grid;align-content:start;gap:16px}.playground-sidebar-head,.chat-playground-header{display:flex;align-items:center;justify-content:space-between;gap:14px}.playground-sidebar-head h2,.chat-playground-header h2{margin:5px 0;font-size:17px}.count-pill{min-width:30px;height:27px;display:grid;place-items:center;border:1px solid var(--border);border-radius:8px;color:var(--muted);font-size:10px}.playground-target-info{display:grid;gap:11px;padding:13px;border:1px solid var(--border);border-radius:11px;background:var(--surface-2)}.target-info-row{display:grid;gap:4px}.target-info-row>span{color:var(--muted);font-size:9px}.target-info-row>b,.target-info-row>code{overflow-wrap:anywhere;font-size:10px}.target-info-row>b code{display:block;margin-top:3px;color:var(--accent-strong);font-size:9px}.target-info-row>code{color:var(--accent-strong)}.target-capabilities{display:flex;gap:5px;flex-wrap:wrap;padding-top:3px}.capability-pill{padding:4px 7px;border:1px solid var(--accent-border);border-radius:999px;color:var(--accent-strong);font-size:8px;font-weight:800}.muted{color:var(--muted);font-size:9px}.good{color:var(--accent-strong)!important}.bad{color:var(--danger)!important}.target-warning{margin:0;color:var(--warning);font-size:9px;line-height:1.5}.full-width{width:100%;justify-content:center}.probe-detail{padding:9px;border:1px solid var(--border);border-radius:8px;background:var(--surface);font-size:10px;line-height:1.5}.probe-detail.bad{border-color:color-mix(in srgb,var(--danger) 35%,var(--border))}.chat-playground{min-height:700px;display:grid;grid-template-rows:auto minmax(320px,1fr) auto auto;overflow:hidden}.chat-playground-header{padding:17px 19px;border-bottom:1px solid var(--border)}.chat-playground-header>div:first-child{min-width:0}.chat-playground-header h2,.chat-playground-header small{overflow-wrap:anywhere}.chat-playground-header small{color:var(--muted);font-size:9px}.chat-header-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.stream-toggle{display:flex;gap:6px;align-items:center;color:var(--text-soft);font-size:10px;font-weight:700}.stream-toggle input{accent-color:var(--accent-strong)}.chat-transcript{min-height:320px;max-height:56vh;overflow:auto;padding:20px;display:grid;align-content:start;gap:16px}.chat-welcome{min-height:280px;display:grid;align-content:center;justify-items:center;text-align:center;gap:9px;color:var(--text-soft)}.chat-welcome>span{width:44px;height:44px;display:grid;place-items:center;border:1px solid var(--accent-border);border-radius:13px;color:var(--accent-strong);font-size:21px}.chat-welcome strong{font-size:14px}.chat-welcome p{max-width:420px;margin:0;color:var(--muted);font-size:10px;line-height:1.6}.chat-turn{display:grid;grid-template-columns:32px minmax(0,1fr);gap:10px;align-items:start}.chat-turn.user{grid-template-columns:minmax(0,1fr) 32px}.chat-turn.user .chat-turn-avatar{grid-column:2;grid-row:1;background:var(--accent-dim);color:var(--accent-strong)}.chat-turn.user .chat-turn-body{grid-column:1;grid-row:1;justify-self:end;max-width:min(88%,760px);background:var(--surface-2)}.chat-turn-avatar{width:30px;height:30px;display:grid;place-items:center;border:1px solid var(--border);border-radius:9px;background:var(--surface);color:var(--muted);font-size:8px;font-weight:900}.chat-turn-body{min-width:0;padding:12px 14px;border:1px solid var(--border);border-radius:12px;background:var(--bg-soft)}.chat-turn.failed .chat-turn-body{border-color:color-mix(in srgb,var(--danger) 35%,var(--border))}.chat-turn-heading{display:flex;align-items:center;justify-content:space-between;gap:9px;margin-bottom:7px}.chat-turn-heading strong{font-size:10px}.chat-turn-heading span{color:var(--muted);font-size:8px;font-weight:800}.chat-turn-content{margin:0;color:var(--text-soft);font:12px/1.7 inherit;white-space:pre-wrap;overflow-wrap:anywhere}.chat-turn pre.chat-turn-content{font-family:inherit}.chat-files{margin:8px 0 0;color:var(--accent-strong);font-size:9px;overflow-wrap:anywhere}.chat-loading-copy{margin:0;color:var(--muted);font-size:10px;animation:pulse 1.2s infinite alternate}.chat-turn-error{margin-top:9px;padding:9px 10px;border-left:3px solid var(--danger);background:var(--danger-dim);color:var(--text-soft)}.chat-turn-error strong{color:var(--danger);font-size:9px}.chat-turn-error p{margin:4px 0 0;font-size:10px;line-height:1.5;overflow-wrap:anywhere}.chat-turn-meta{margin-top:9px;padding-top:8px;display:flex;gap:9px;flex-wrap:wrap;border-top:1px solid var(--border);color:var(--muted);font-size:8px}.chat-turn-meta code{max-width:100%;overflow-wrap:anywhere}.playground-inline-notice{padding:8px 17px;color:var(--accent-strong);font-size:10px}.playground-inline-notice.failed{color:var(--danger)}.chat-composer{padding:13px 16px 15px;border-top:1px solid var(--border);background:var(--surface)}.chat-composer textarea{width:100%;min-height:82px;resize:vertical;padding:12px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--text);font:12px/1.6 inherit}.chat-composer textarea:focus{outline:2px solid var(--accent-border)}.composer-actions{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:9px}.composer-tools{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.composer-tools .secondary-button,.send-button{min-height:34px;padding-inline:11px;font-size:10px}.file-support-hint{color:var(--muted);font-size:8px}.send-button{min-width:94px;justify-content:space-between}.send-button span{font-size:14px}.selected-files{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px}.file-pill{max-width:100%;padding:4px 8px;display:flex;align-items:center;gap:8px;border:1px solid var(--border);border-radius:999px;background:var(--surface-2);font-size:9px}.file-pill span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-pill button{border:0;background:transparent;color:var(--danger);cursor:pointer;font-size:14px}.chat-advanced{margin-top:10px;color:var(--muted);font-size:9px}.chat-advanced summary{cursor:pointer}.advanced-options{margin-top:9px;padding:10px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;border:1px solid var(--border);border-radius:9px;background:var(--surface-2)}.playground-history{overflow:hidden}.playground-history>summary{padding:14px 17px;display:flex;justify-content:space-between;align-items:center;gap:10px;cursor:pointer;list-style:none}.playground-history>summary::-webkit-details-marker{display:none}.playground-history>summary span{display:grid;gap:4px}.playground-history>summary b{font-size:11px}.playground-history>summary small{color:var(--muted);font-size:9px}.playground-history>summary i{color:var(--accent-strong);font-size:9px;font-style:normal}.playground-history-table{border-top:1px solid var(--border)}.playground-history-row{padding:10px 14px;display:grid;grid-template-columns:minmax(140px,1.5fr) .7fr .8fr 1fr minmax(100px,1fr);gap:10px;align-items:center;border-bottom:1px solid var(--border);font-size:9px}.playground-history-row>span:first-child{min-width:0;display:grid;gap:4px}.playground-history-row b,.playground-history-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.playground-history-row small{color:var(--muted)}.playground-history-row code{overflow-wrap:anywhere;color:var(--muted);font-size:8px}.history-heading{color:var(--muted);font-size:8px;font-weight:800}.history-empty{margin:0;padding:14px;color:var(--muted);font-size:10px}.playground-empty{padding:24px}.playground-empty p{color:var(--muted);font-size:11px}.error-alert{color:var(--danger)}@keyframes pulse{to{opacity:.45}}@media(max-width:980px){.model-playground-layout{grid-template-columns:1fr}.model-playground-sidebar{order:0}.chat-playground{order:1;min-height:600px}.playground-history{order:2}.target-info-row{grid-template-columns:130px minmax(0,1fr);align-items:baseline}.model-playground-sidebar{grid-template-columns:1fr 1.4fr}.playground-sidebar-head{grid-column:1/-1}.model-playground-sidebar>.field{grid-column:1}.playground-target-info{grid-column:2;grid-row:2/5}}@media(max-width:700px){.model-playground-warning{display:grid;gap:4px}.chat-playground-header{align-items:flex-start;flex-direction:column}.chat-header-controls{width:100%;justify-content:space-between}.chat-transcript{padding:12px;max-height:60vh}.model-playground-sidebar{display:grid;grid-template-columns:1fr}.playground-sidebar-head,.model-playground-sidebar>.field,.playground-target-info{grid-column:1;grid-row:auto}.target-info-row{grid-template-columns:1fr}.advanced-options{grid-template-columns:1fr 1fr}.playground-history-row{grid-template-columns:minmax(110px,1.3fr) .8fr 1fr;gap:6px}.playground-history-row>:nth-child(4),.playground-history-row>:nth-child(5){grid-column:span 1}.history-heading{display:none}.playground-top-actions{width:100%}}
/* Keep inherited app typography valid; `font: size/line-height inherit` is not valid CSS. */
.chat-turn-content{font:inherit;font-size:12px;line-height:1.7}.chat-composer textarea{font:inherit;font-size:12px;line-height:1.6}
</style>
