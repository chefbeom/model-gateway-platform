<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { adminFetch, adminResponse, type AdminAuth } from './api'
import ModelFeatureBadges from './ModelFeatureBadges.vue'

type Target = {
  id: string; sourceId: string; targetType: 'RUNTIME' | 'EXTERNAL_PROVIDER'; providerName: string; protocol: string
  endpointUrl: string; modelId: string; displayName: string; status: string; enabled: boolean; loaded: boolean; loadState: string; canChat: boolean
  contextLength?: number | null; maxConcurrency: number; capabilities: string[]; featureSupportJson?: string | null; defaultReasoningEffort?: string | null; defaultServiceTier?: string | null; inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null; currency?: string | null; nodeName?: string | null
}
type Trace = {
  requestId: string; source: string; targetType: string; targetName: string; modelId: string; endpointUrl: string
  status: string; stream: boolean; httpStatus?: number | null; latencyMs?: number | null
  inputTokens?: number | null; outputTokens?: number | null; errorCode?: string | null; startedAt: string
}
type Probe = { reachable: boolean; httpStatus: number; latencyMs: number; modelAvailable: boolean; modelCount: number; message?: string | null }
type Turn = { id: string; role: 'user' | 'assistant'; content: string; files?: string[]; attachmentPayloads?: Attachment[]; status?: string; httpStatus?: number; latencyMs?: number; requestId?: string; inputTokens?: number | null; outputTokens?: number | null; totalTokens?: number | null; stream?: boolean; responseStream?: boolean; error?: string }
type Attachment = { name: string; mediaType: string; base64: string; messageIndex?: number }
type SavedTurn = Omit<Turn, 'attachmentPayloads'> & { createdAt: string }
type SavedConversation = { conversationId: string; targetId: string; targetName: string; modelId: string; title: string; preview: string; messageCount: number; createdAt: string; updatedAt: string }

const props = defineProps<{ organizationId?: string; auth: AdminAuth; initialTargetId?: string }>()
const targets = ref<Target[]>([])
const selectedTargetId = ref('')
const conversation = ref<Turn[]>([])
const conversationsByTarget = new Map<string, Turn[]>()
const conversationIdsByTarget = new Map<string, string>()
const activeConversationTargetId = ref('')
const activeConversationId = ref('')
const savedConversations = ref<SavedConversation[]>([])
const historyQuery = ref('')
const historyModelFilter = ref('ALL')
const historyLoading = ref(false)
const historyError = ref('')
const restoringConversationId = ref('')
const prompt = ref('')
const selectedFiles = ref<File[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const chatPane = ref<HTMLElement | null>(null)
const loading = ref(false)
const sending = ref(false)
const probing = ref(false)
const stream = ref(false)
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
  if (!targets.value.length) { activateTarget(''); return }
  const initial = props.initialTargetId?.trim()
  if (initial) {
    const direct = targets.value.find(item => item.id === initial)
    const sourceModels = targets.value.filter(item => item.sourceId === initial)
    const source = sourceModels.find(item => item.canChat) ?? sourceModels[0]
    const found = direct ?? source
    if (found) { activateTarget(found.id); return }
    activateTarget('')
    return
  }
  const nextTargetId = targets.value.some(item => item.id === selectedTargetId.value)
    ? selectedTargetId.value
    : availableTargets.value[0]?.id ?? targets.value[0].id
  activateTarget(nextTargetId)
}
async function load() {
  if (!props.organizationId) { targets.value = []; traces.value = []; savedConversations.value = []; return }
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
    await loadSavedConversations()
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
async function loadSavedConversations() {
  if (!props.organizationId) { savedConversations.value = []; return }
  historyLoading.value = true
  historyError.value = ''
  try {
    const query = new URLSearchParams()
    if (historyQuery.value.trim()) query.set('query', historyQuery.value.trim())
    const suffix = query.size ? `?${query.toString()}` : ''
    savedConversations.value = await adminFetch<SavedConversation[]>(apiPath(`conversations${suffix}`), props.auth)
  } catch (error) {
    historyError.value = error instanceof Error ? error.message : '저장된 대화를 불러오지 못했습니다.'
  } finally { historyLoading.value = false }
}
function activateTarget(targetId: string, announce = false) {
  const previousTargetId = activeConversationTargetId.value
  if (previousTargetId === targetId) {
    selectedTargetId.value = targetId
    return
  }
  if (previousTargetId) conversationsByTarget.set(previousTargetId, conversation.value)
  if (previousTargetId && activeConversationId.value) conversationIdsByTarget.set(previousTargetId, activeConversationId.value)
  activeConversationTargetId.value = targetId
  selectedTargetId.value = targetId
  conversation.value = targetId ? [...(conversationsByTarget.get(targetId) ?? [])] : []
  activeConversationId.value = targetId ? (conversationIdsByTarget.get(targetId) ?? '') : ''
  selectedFiles.value = []
  if (fileInput.value) fileInput.value.value = ''
  connection.value = null
  if (announce) void loadSavedConversations()
  if (announce && previousTargetId && targetId) {
    notice.value = '모델별 대화는 분리되어 있습니다. 이전 대화와 첨부 파일은 새 모델로 전송되지 않습니다. 다른 모델에서 파일을 테스트하려면 다시 첨부하세요.'
  }
}
function onTargetChanged() { activateTarget(selectedTargetId.value, true) }
function resetPlaygroundSession() {
  conversationsByTarget.clear()
  conversationIdsByTarget.clear()
  activeConversationTargetId.value = ''
  activeConversationId.value = ''
  selectedTargetId.value = ''
  conversation.value = []
  selectedFiles.value = []
  if (fileInput.value) fileInput.value.value = ''
  connection.value = null
}
function loadStateLabel(state: string) {
  return ({ LOADED: '로드됨 · 테스트 가능', UNLOADED: '미로드 · 먼저 로드하세요', LOADING: '로딩 중', DOWNLOADING: '다운로드 중', SLEEPING: '절전 상태 · 깨운 뒤 테스트', FAILED: '로드 실패', NOT_FOUND: 'Runtime에서 찾을 수 없음', UNKNOWN: '현재 로드 상태 확인 필요' } as Record<string, string>)[state] ?? state
}
function clearConversation() {
  conversation.value = []
  if (activeConversationTargetId.value) {
    conversationsByTarget.set(activeConversationTargetId.value, conversation.value)
    conversationIdsByTarget.delete(activeConversationTargetId.value)
  }
  activeConversationId.value = ''
  selectedFiles.value = []
  if (fileInput.value) fileInput.value.value = ''
  notice.value = '새 대화를 시작합니다. 이전 대화는 저장된 대화 목록에서 계속 확인할 수 있습니다.'
}
async function restoreConversation(item: SavedConversation) {
  if (sending.value) return
  restoringConversationId.value = item.conversationId
  historyError.value = ''
  try {
    const detail = await adminFetch<{ conversation: SavedConversation; turns: SavedTurn[] }>(apiPath(`conversations/${item.conversationId}`), props.auth)
    const target = targets.value.find(candidate => candidate.id === detail.conversation.targetId)
    if (target) activateTarget(target.id)
    else {
      if (activeConversationTargetId.value) {
        conversationsByTarget.set(activeConversationTargetId.value, conversation.value)
        if (activeConversationId.value) conversationIdsByTarget.set(activeConversationTargetId.value, activeConversationId.value)
      }
      selectedTargetId.value = ''
      activeConversationTargetId.value = detail.conversation.targetId
    }
    activeConversationId.value = item.conversationId
    conversationIdsByTarget.set(detail.conversation.targetId, item.conversationId)
    conversation.value = detail.turns.map(turn => ({ ...turn, id: turn.id || newTurnId(), role: turn.role === 'assistant' ? 'assistant' : 'user' }))
    selectedFiles.value = []
    if (fileInput.value) fileInput.value.value = ''
    connection.value = null
    conversation.value = conversation.value.map(turn => turn.status === 'IN_PROGRESS'
      ? { ...turn, status: 'FAILED', error: turn.error || '서버 응답이 완료되기 전에 연결이 끊겼습니다.' }
      : turn)
    conversationsByTarget.set(detail.conversation.targetId, conversation.value)
    notice.value = !target
      ? '저장된 기록은 열렸지만 해당 모델은 현재 등록되어 있지 않아 읽기 전용입니다.'
      : conversation.value.some(turn => turn.files?.length)
        ? '저장된 대화를 불러왔습니다. 첨부 파일 원본은 저장되지 않아 이어서 질문할 때 파일을 다시 첨부해야 합니다.'
        : '저장된 대화를 불러왔습니다.'
    await nextTick(() => { if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight })
  } catch (error) {
    historyError.value = error instanceof Error ? error.message : '대화를 불러오지 못했습니다.'
  } finally { restoringConversationId.value = '' }
}
async function deleteSavedConversation(item: SavedConversation) {
  if (sending.value || !globalThis.confirm(`${item.title || item.modelId} 대화를 영구 삭제할까요?`)) return
  try {
    const response = await adminResponse(apiPath(`conversations/${item.conversationId}`), props.auth, { method: 'DELETE' })
    if (!response.ok) throw new Error(`대화를 삭제하지 못했습니다. (HTTP ${response.status})`)
    if (activeConversationId.value === item.conversationId) clearConversation()
    await loadSavedConversations()
    notice.value = '대화 기록을 삭제했습니다.'
  } catch (error) {
    historyError.value = error instanceof Error ? error.message : '대화 삭제에 실패했습니다.'
  }
}
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
function withRuntimeCrashGuidance(message: string, target: Target) {
  if (target.targetType !== 'RUNTIME' || !/model has crashed|exit code/i.test(message)) return message
  return `${message}\n\n선택한 로컬 Runtime에서 모델 프로세스가 종료된 것으로 보입니다. AIConnect의 연결·이미지 요청 형식과 별개로 Runtime 서버 로그와 GPU 메모리/비전 모델 지원을 확인하고, 더 작은 이미지 또는 일반 응답(JSON) 모드로 다시 테스트해 주세요.`
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
  if (!response.body) throw new Error('서버가 SSE 응답을 반환했지만 응답 본문이 비어 있습니다.')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const processFrame = async (frame: string) => {
    const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n').trim()
    if (!data || data === '[DONE]') return
    let event: { error?: unknown; choices?: Array<{ delta?: { content?: unknown } }>; usage?: Record<string, unknown> }
    try { event = JSON.parse(data) as typeof event }
    catch {
      turn.error = '스트리밍 응답을 해석할 수 없습니다. SSE 이벤트 형식이 올바른지 확인하세요.'
      turn.status = 'FAILED'
      return
    }
    if (event.error) {
      turn.error = errorText(event.error, response.status)
      turn.status = 'FAILED'
      return
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
  }
  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      buffer += decoder.decode()
      if (buffer.trim()) await processFrame(buffer)
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() ?? ''
    for (const frame of frames) await processFrame(frame)
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
  const target = selectedTarget.value
  const targetId = target.id
  const organizationId = props.organizationId
  const useStream = stream.value
  const requestTemperature = Number(temperature.value)
  const useTemperature = includeTemperature.value
  const requestMaxCompletionTokens = Number(maxCompletionTokens.value)
  const requestResponseMode = responseMode.value
  const text = prompt.value.trim()
  if (!text && !selectedFiles.value.length) return
  sending.value = true
  notice.value = ''
  const files = [...selectedFiles.value]
  const requestHistory = conversation.value.filter(turn => turn.role === 'user' || (turn.status === 'SUCCEEDED' && Boolean(turn.content.trim())))
  const requestMessages = requestHistory.map(turn => ({ role: turn.role, content: turn.content }))
  const currentMessageIndex = requestMessages.length
  const historyAttachments = requestHistory.flatMap((turn, messageIndex) => turn.role === 'user'
    ? (turn.attachmentPayloads ?? []).map(attachment => ({ ...attachment, messageIndex }))
    : [])
  const started = performance.now()
  const userTurn: Turn = { id: newTurnId(), role: 'user', content: text || '첨부 파일을 확인해 주세요.', files: files.map(file => file.name) }
  const assistantTurn: Turn = { id: newTurnId(), role: 'assistant', content: '', status: '요청 중', stream: useStream, responseStream: false }
  requestMessages.push({ role: 'user', content: userTurn.content })
  conversation.value.push(userTurn, assistantTurn)
  prompt.value = ''
  selectedFiles.value = []
  await nextTick(() => { if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight })
  try {
    const attachments = await Promise.all(files.map(toAttachment))
    userTurn.attachmentPayloads = attachments
    const allAttachments = [...historyAttachments, ...attachments.map(attachment => ({ ...attachment, messageIndex: currentMessageIndex }))]
    const attachmentBytes = allAttachments.reduce((total, attachment) => total + Math.floor(attachment.base64.length * 3 / 4), 0)
    if (allAttachments.length > 20 || attachmentBytes > 8 * 1024 * 1024) {
      throw new Error('현재 대화에 포함된 첨부 파일은 총 20개·8MB까지 보낼 수 있습니다. 새 대화를 시작하거나 기존 첨부 대화를 지워 주세요.')
    }
    const body: Record<string, unknown> = {
      targetId,
      messages: requestMessages,
      attachments: allAttachments,
      stream: useStream,
      maxCompletionTokens: requestMaxCompletionTokens,
      responseMode: requestResponseMode
    }
    if (activeConversationId.value) body.conversationId = activeConversationId.value
    if (useTemperature) body.temperature = requestTemperature
    const response = await adminResponse(`/api/admin/organizations/${organizationId}/playground/chat`, props.auth, {
      method: 'POST',
      headers: { Accept: useStream ? 'text/event-stream, application/json' : 'application/json' },
      body: JSON.stringify(body)
    })
    assistantTurn.httpStatus = response.status
    assistantTurn.requestId = response.headers.get('X-Request-Id') ?? undefined
    const savedConversationId = response.headers.get('X-Playground-Conversation-Id')
    if (savedConversationId) {
      activeConversationId.value = savedConversationId
      conversationIdsByTarget.set(targetId, savedConversationId)
    }
    assistantTurn.latencyMs = Math.round(performance.now() - started)
    if (!response.ok) {
      const errorBody = await parseResponse(response)
      assistantTurn.error = withRuntimeCrashGuidance(errorText(errorBody, response.status), target)
      assistantTurn.status = 'FAILED'
    } else if (useStream && response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) {
      assistantTurn.responseStream = true
      assistantTurn.status = 'STREAMING'
      await readStream(response, assistantTurn)
      assistantTurn.status = assistantTurn.error ? 'FAILED' : 'SUCCEEDED'
    } else {
      assistantTurn.responseStream = false
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
    assistantTurn.error = withRuntimeCrashGuidance(error instanceof Error ? error.message : '요청을 완료하지 못했습니다.', target)
    assistantTurn.latencyMs = Math.round(performance.now() - started)
    notice.value = '요청 실패 · ' + assistantTurn.error
  } finally {
    sending.value = false
    await loadRequests()
    await loadSavedConversations()
    await nextTick(() => { if (chatPane.value) chatPane.value.scrollTop = chatPane.value.scrollHeight })
  }
}
function handleEnter(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  void send()
}
function formatTime(value: string) { return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) }
function costLabel(target: Target) {
  const symbol = target.currency === 'USD' ? '$' : target.currency === 'KRW' ? '₩' : ''
  if (target.inputPricePerMillion == null && target.outputPricePerMillion == null) return '단가 미설정'
  return `입력 ${symbol}${target.inputPricePerMillion ?? '-'} / 출력 ${symbol}${target.outputPricePerMillion ?? '-'} · 1M`
}

watch(() => [props.organizationId, props.initialTargetId], () => { resetPlaygroundSession(); void load() }, { immediate: true })
</script>

<template>
  <section class="page-stack model-playground-page">
    <div class="page-hero model-playground-hero">
      <div><p class="eyebrow">MODEL CHAT PLAYGROUND</p><h1>모델 직접 테스트</h1><p>등록된 Runtime 또는 외부 Provider의 인증 설정을 이용해 특정 모델에 직접 요청합니다.</p></div>
      <div class="playground-top-actions"><button class="secondary-button" :disabled="loading || sending" @click="load">{{ loading ? '불러오는 중…' : '새로고침' }}</button><button class="secondary-button" :disabled="!conversation.length || sending" @click="clearConversation">새 대화</button></div>
    </div>

    <div class="model-playground-warning"><strong>직접 테스트 안내</strong><span>이 경로는 관리자 직접 호출이며 프로젝트 권한·총량제·데이터 보호 정책을 거치지 않습니다. 대화 본문은 암호화 저장되며 조직 및 로그인 사용자 범위로 조회가 제한됩니다. 공용 Platform Admin 토큰은 같은 조직의 토큰 사용자 간 기록이 공유됩니다. 첨부 원본 파일은 저장하지 않고 파일명만 남깁니다(모델 응답에 파일 내용이 포함되면 그 응답은 대화 기록에 암호화 저장됩니다). 기록은 직접 삭제하기 전까지 보관되므로 민감한 운영 데이터는 입력하지 마세요.</span></div>

    <div v-if="loadError" class="inline-alert error-alert">{{ loadError }}</div>
    <article v-if="!organizationId" class="surface-card playground-empty"><strong>조직을 선택하세요</strong><p>조직에 등록된 Runtime, Deployment와 외부 Provider 모델을 불러옵니다.</p></article>
    <div v-else class="model-playground-layout">
      <aside class="surface-card model-playground-sidebar">
        <div class="playground-sidebar-head"><div><span class="card-kicker">REGISTERED MODELS</span><h2>테스트 대상</h2></div><span class="count-pill">{{ availableTargets.length }} 활성</span></div>
        <label class="field">모델 선택<select v-model="selectedTargetId" :disabled="loading || sending || !targets.length" @change="onTargetChanged"><option value="" disabled>모델을 선택하세요</option><optgroup v-if="availableTargets.length" label="현재 대화 테스트 가능"><option v-for="target in availableTargets" :key="target.id" :value="target.id">{{ target.displayName }} · {{ target.providerName }}</option></optgroup><optgroup v-if="candidateTargets.length" label="Runtime 후보 · 로드 후 테스트"><option v-for="target in candidateTargets" :key="target.id" :value="target.id">{{ target.displayName }} · {{ loadStateLabel(target.loadState) }}</option></optgroup><optgroup v-if="unavailableTargets.length" label="미발견·접속 불가 모델 기록"><option v-for="target in unavailableTargets" :key="target.id" :value="target.id" disabled>{{ target.displayName }} · {{ loadStateLabel(target.loadState) }}</option></optgroup><optgroup v-if="targets.some(target => target.targetType === 'EXTERNAL_PROVIDER' && !target.canChat)" label="비활성 Provider 모델"><option v-for="target in targets.filter(item => item.targetType === 'EXTERNAL_PROVIDER' && !item.canChat)" :key="target.id" :value="target.id" disabled>{{ target.displayName }} · 비활성</option></optgroup></select><small class="muted">모델별 대화와 첨부 파일은 분리되어 다른 모델에 자동 전달되지 않습니다.</small></label>
        <div v-if="selectedTarget" class="playground-target-info">
          <div class="target-info-row"><span>연결 상태</span><b :class="connection?.reachable ? 'good' : ''">{{ statusLabel }}</b></div>
          <div v-if="selectedTarget.targetType === 'RUNTIME'" class="target-info-row"><span>모델 상태</span><b>{{ loadStateLabel(selectedTarget.loadState) }}</b></div>
          <div class="target-info-row"><span>Provider / Runtime</span><b>{{ selectedTarget.providerName }} · {{ selectedTarget.protocol }}</b></div>
          <div class="target-info-row"><span>모델</span><b>{{ selectedTarget.displayName }} <code>{{ selectedTarget.modelId }}</code></b></div>
          <div class="target-info-row"><span>Endpoint</span><code>{{ selectedTarget.endpointUrl }}</code></div>
          <div class="target-info-row"><span>Context · 동시성</span><b>{{ selectedTarget.contextLength?.toLocaleString() ?? '-' }} · {{ selectedTarget.maxConcurrency }}</b></div>
          <div class="target-info-row"><span>요금표</span><b>{{ costLabel(selectedTarget) }}</b></div>
          <div class="target-capabilities"><span v-for="capability in selectedTarget.capabilities" :key="capability" class="capability-pill">{{ capability }}</span><span v-if="!selectedTarget.capabilities.length" class="muted">Capability 정보 없음</span></div>
          <ModelFeatureBadges :capabilities="selectedTarget.capabilities" :feature-support-json="selectedTarget.featureSupportJson" :reasoning-effort="selectedTarget.defaultReasoningEffort" :service-tier="selectedTarget.defaultServiceTier" />
          <p v-if="selectedTarget.targetType === 'RUNTIME' && !selectedTarget.loaded" class="target-warning">모델 상태가 LOADED가 아닙니다. Runtime에 모델을 준비한 뒤에도 테스트 요청은 실행되며, 실제 오류를 그대로 표시합니다.</p>
          <p v-if="!selectedTarget.canChat" class="target-warning">이 후보는 현재 채팅 대상이 아닙니다. Infrastructure에서 모델을 로드하고 동기화하면 활성 테스트 목록으로 이동합니다.</p>
          <button class="secondary-button full-width" :disabled="probing" @click="checkConnection">{{ probing ? '확인 중…' : '연결 및 모델 확인' }}</button>
          <div v-if="connection" class="probe-detail" :class="connection.reachable ? 'good' : 'bad'">{{ connection.reachable ? `HTTP ${connection.httpStatus} · ${connection.latencyMs}ms · ${connection.modelCount}개 모델` : connection.message }}</div>
        </div>
        <div v-else class="empty-state compact"><span>◈</span><p>{{ loading ? '등록 모델을 불러오는 중입니다.' : initialTargetMissing ? '선택한 Runtime 또는 Provider에 테스트할 등록 모델이 없습니다. Runtime은 모델 동기화를, 외부 Provider는 모델 등록을 먼저 완료하세요.' : '조직에 등록된 모델이 없습니다. 인프라스트럭처에서 모델 동기화를 하거나 외부 AI에서 모델을 등록하세요.' }}</p></div>
      </aside>

      <main class="surface-card chat-playground">
        <header class="chat-playground-header"><div><span class="card-kicker">LIVE MODEL SESSION</span><h2>{{ selectedTarget?.displayName ?? '모델을 선택하세요' }}</h2><small>{{ selectedTarget ? `${selectedTarget.providerName} · ${selectedTarget.endpointUrl}` : '테스트할 모델을 왼쪽에서 선택하세요.' }}</small></div><div class="chat-header-controls"><div class="stream-control"><label class="stream-toggle"><input v-model="stream" type="checkbox" :disabled="sending" /><span>{{ stream ? '스트리밍 (SSE)' : '일반 응답 (JSON)' }}</span></label><small>{{ stream ? '응답 토큰을 생성되는 대로 표시합니다. 생성 속도가 빨라지는 것은 아니며 SSE 호환 서버가 필요합니다.' : '응답이 끝난 뒤 한 번에 표시합니다. 기본 권장 모드입니다.' }}</small></div><button class="secondary-button" :disabled="!selectedTarget || probing" @click="checkConnection">연결 확인</button></div></header>
        <div ref="chatPane" class="chat-transcript" aria-live="polite">
          <div v-if="!conversation.length" class="chat-welcome"><span>◈</span><strong>실제 모델에 테스트 요청을 보내세요</strong><p>대화 본문은 암호화되어 저장됩니다. 파일 원본은 저장하지 않습니다.</p></div>
          <article v-for="turn in conversation" :key="turn.id" class="chat-turn" :class="[turn.role, { failed: turn.status === 'FAILED' }]">
            <div class="chat-turn-avatar">{{ turn.role === 'user' ? 'ME' : 'AI' }}</div>
            <div class="chat-turn-body"><div class="chat-turn-heading"><strong>{{ turn.role === 'user' ? '사용자' : '모델 응답' }}</strong><span v-if="turn.status" :class="turn.status === 'FAILED' ? 'bad' : turn.status === 'SUCCEEDED' ? 'good' : ''">{{ turn.status }}</span></div>
              <p v-if="turn.role === 'user'" class="chat-turn-content">{{ turn.content }}</p>
              <pre v-else-if="turn.content" class="chat-turn-content">{{ turn.content }}</pre>
              <p v-else-if="sending" class="chat-loading-copy">{{ turn.status === 'STREAMING' ? '응답 토큰을 실시간으로 받는 중…' : turn.stream ? 'SSE 스트림을 여는 중…' : '모델 응답을 기다리는 중…' }}</p>
              <p v-if="turn.files?.length" class="chat-files">첨부: {{ turn.files.join(', ') }}</p>
              <div v-if="turn.error" class="chat-turn-error"><strong>실패 사유</strong><p>{{ turn.error }}</p></div>
              <div v-if="turn.role === 'assistant' && turn.httpStatus" class="chat-turn-meta"><span>HTTP {{ turn.httpStatus }}</span><span>{{ turn.latencyMs?.toLocaleString() ?? '-' }} ms</span><span v-if="turn.inputTokens != null">입력 {{ turn.inputTokens }} · 출력 {{ turn.outputTokens ?? 0 }} · 전체 {{ turn.totalTokens ?? (turn.inputTokens + (turn.outputTokens ?? 0)) }} 토큰</span><span>{{ turn.stream ? turn.responseStream ? 'SSE 실시간' : 'SSE 요청 · JSON 응답' : 'JSON' }}</span><code v-if="turn.requestId">{{ turn.requestId }}</code></div>
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

    <section class="surface-card saved-conversations">
      <header class="saved-conversations-header"><div><span class="card-kicker">SAVED PLAYGROUND CHATS</span><h2>저장된 모델 대화</h2><p>모델별 테스트 대화를 검색하고 다시 열 수 있습니다. 전체 기록을 최신순으로 표시합니다.</p></div><span class="count-pill">{{ savedConversations.length }}</span></header>
      <div class="saved-conversation-search"><input v-model="historyQuery" type="search" placeholder="대화 제목·질문·응답 내용 검색" @keydown.enter.prevent="loadSavedConversations" /><button class="secondary-button" :disabled="historyLoading" @click="loadSavedConversations">{{ historyLoading ? '검색 중…' : '검색' }}</button><button v-if="historyQuery" class="secondary-button" @click="historyQuery=''; loadSavedConversations()">초기화</button></div>
      <p class="saved-conversation-privacy">대화 본문은 암호화 저장됩니다. 첨부 파일은 이름만 남고 원본은 저장되지 않지만, 모델 답변에 포함된 내용은 답변 본문과 함께 기록됩니다. 대화는 직접 삭제하기 전까지 보관됩니다.</p>
      <p v-if="historyError" class="history-error">{{ historyError }}</p>
      <div v-if="historyLoading && !savedConversations.length" class="history-empty">대화 기록을 불러오는 중입니다.</div>
      <div v-else-if="savedConversations.length" class="saved-conversation-list">
        <article v-for="item in savedConversations" :key="item.conversationId" class="saved-conversation-item" :class="{ selected: item.conversationId === activeConversationId }">
          <button class="saved-conversation-open" :disabled="sending || restoringConversationId === item.conversationId" @click="restoreConversation(item)"><span class="saved-conversation-model">{{ item.targetName }} · {{ item.modelId }}</span><strong>{{ item.title || '첨부 파일 테스트' }}</strong><small>{{ item.preview }}<template v-if="item.messageCount"> · {{ item.messageCount }}개 메시지</template></small><time>{{ formatTime(item.updatedAt) }}</time></button>
          <button class="saved-conversation-delete" :disabled="sending" title="대화 영구 삭제" @click="deleteSavedConversation(item)">삭제</button>
        </article>
      </div>
      <p v-else-if="!historyLoading" class="history-empty">{{ historyQuery ? '검색 결과가 없습니다.' : '저장된 대화가 없습니다. 모델과의 테스트 대화가 여기에 나타납니다.' }}</p>
    </section>

    <details class="surface-card playground-history"><summary><span><b>PLAYGROUND 요청 이력</b><small>요청 ID·상태·모델·토큰 사용량 등 메타데이터입니다.</small></span><i>{{ traceLoading ? '갱신 중…' : `${traces.length}건` }}</i></summary><div v-if="traces.length" class="playground-history-table"><div class="playground-history-row history-heading"><span>시간 / 모델</span><span>상태</span><span>방식 / HTTP</span><span>응답 시간 / 토큰</span><span>오류</span></div><div v-for="trace in traces" :key="trace.requestId" class="playground-history-row"><span><b>{{ trace.modelId }}</b><small>{{ formatTime(trace.startedAt) }} · {{ trace.targetName }}</small></span><span :class="trace.status === 'SUCCEEDED' ? 'good' : trace.status === 'FAILED' ? 'bad' : ''">{{ trace.status }}</span><span>{{ trace.stream ? 'SSE' : 'JSON' }} · HTTP {{ trace.httpStatus ?? '-' }}</span><span>{{ trace.latencyMs?.toLocaleString() ?? '-' }}ms · {{ trace.inputTokens ?? '-' }}/{{ trace.outputTokens ?? '-' }}</span><code>{{ trace.errorCode ?? trace.requestId }}</code></div></div><p v-else class="history-empty">아직 테스트 요청이 없습니다.</p></details>
  </section>
</template>

<style scoped>
.model-playground-page{max-width:1600px;margin-inline:auto}.model-playground-hero{align-items:center}.playground-top-actions{display:flex;gap:8px;flex-wrap:wrap}.model-playground-warning{display:flex;gap:12px;align-items:baseline;padding:13px 15px;border:1px solid color-mix(in srgb,var(--warning) 35%,var(--border));border-radius:12px;background:var(--surface-2);color:var(--text-soft);font-size:11px;line-height:1.6}.model-playground-warning strong{color:var(--warning);white-space:nowrap}.model-playground-warning code{color:var(--accent-strong)}.model-playground-layout{display:grid;grid-template-columns:minmax(280px,340px) minmax(0,1fr);gap:14px;align-items:stretch}.model-playground-sidebar{padding:17px;display:grid;align-content:start;gap:16px}.playground-sidebar-head,.chat-playground-header{display:flex;align-items:center;justify-content:space-between;gap:14px}.playground-sidebar-head h2,.chat-playground-header h2{margin:5px 0;font-size:17px}.count-pill{min-width:30px;height:27px;display:grid;place-items:center;border:1px solid var(--border);border-radius:8px;color:var(--muted);font-size:10px}.playground-target-info{display:grid;gap:11px;padding:13px;border:1px solid var(--border);border-radius:11px;background:var(--surface-2)}.target-info-row{display:grid;gap:4px}.target-info-row>span{color:var(--muted);font-size:9px}.target-info-row>b,.target-info-row>code{overflow-wrap:anywhere;font-size:10px}.target-info-row>b code{display:block;margin-top:3px;color:var(--accent-strong);font-size:9px}.target-info-row>code{color:var(--accent-strong)}.target-capabilities{display:flex;gap:5px;flex-wrap:wrap;padding-top:3px}.capability-pill{padding:4px 7px;border:1px solid var(--accent-border);border-radius:999px;color:var(--accent-strong);font-size:8px;font-weight:800}.muted{color:var(--muted);font-size:9px}.good{color:var(--accent-strong)!important}.bad{color:var(--danger)!important}.target-warning{margin:0;color:var(--warning);font-size:9px;line-height:1.5}.full-width{width:100%;justify-content:center}.probe-detail{padding:9px;border:1px solid var(--border);border-radius:8px;background:var(--surface);font-size:10px;line-height:1.5}.probe-detail.bad{border-color:color-mix(in srgb,var(--danger) 35%,var(--border))}.chat-playground{min-height:700px;display:grid;grid-template-rows:auto minmax(320px,1fr) auto auto;overflow:hidden}.chat-playground-header{padding:17px 19px;border-bottom:1px solid var(--border)}.chat-playground-header>div:first-child{min-width:0}.chat-playground-header h2,.chat-playground-header small{overflow-wrap:anywhere}.chat-playground-header small{color:var(--muted);font-size:9px}.chat-header-controls{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.stream-toggle{display:flex;gap:6px;align-items:center;color:var(--text-soft);font-size:10px;font-weight:700}.stream-toggle input{accent-color:var(--accent-strong)}.chat-transcript{min-height:320px;max-height:56vh;overflow:auto;padding:20px;display:grid;align-content:start;gap:16px}.chat-welcome{min-height:280px;display:grid;align-content:center;justify-items:center;text-align:center;gap:9px;color:var(--text-soft)}.chat-welcome>span{width:44px;height:44px;display:grid;place-items:center;border:1px solid var(--accent-border);border-radius:13px;color:var(--accent-strong);font-size:21px}.chat-welcome strong{font-size:14px}.chat-welcome p{max-width:420px;margin:0;color:var(--muted);font-size:10px;line-height:1.6}.chat-turn{display:grid;grid-template-columns:32px minmax(0,1fr);gap:10px;align-items:start}.chat-turn.user{grid-template-columns:minmax(0,1fr) 32px}.chat-turn.user .chat-turn-avatar{grid-column:2;grid-row:1;background:var(--accent-dim);color:var(--accent-strong)}.chat-turn.user .chat-turn-body{grid-column:1;grid-row:1;justify-self:end;max-width:min(88%,760px);background:var(--surface-2)}.chat-turn-avatar{width:30px;height:30px;display:grid;place-items:center;border:1px solid var(--border);border-radius:9px;background:var(--surface);color:var(--muted);font-size:8px;font-weight:900}.chat-turn-body{min-width:0;padding:12px 14px;border:1px solid var(--border);border-radius:12px;background:var(--bg-soft)}.chat-turn.failed .chat-turn-body{border-color:color-mix(in srgb,var(--danger) 35%,var(--border))}.chat-turn-heading{display:flex;align-items:center;justify-content:space-between;gap:9px;margin-bottom:7px}.chat-turn-heading strong{font-size:10px}.chat-turn-heading span{color:var(--muted);font-size:8px;font-weight:800}.chat-turn-content{margin:0;color:var(--text-soft);font:12px/1.7 inherit;white-space:pre-wrap;overflow-wrap:anywhere}.chat-turn pre.chat-turn-content{font-family:inherit}.chat-files{margin:8px 0 0;color:var(--accent-strong);font-size:9px;overflow-wrap:anywhere}.chat-loading-copy{margin:0;color:var(--muted);font-size:10px;animation:pulse 1.2s infinite alternate}.chat-turn-error{margin-top:9px;padding:9px 10px;border-left:3px solid var(--danger);background:var(--danger-dim);color:var(--text-soft)}.chat-turn-error strong{color:var(--danger);font-size:9px}.chat-turn-error p{margin:4px 0 0;font-size:10px;line-height:1.5;overflow-wrap:anywhere}.chat-turn-meta{margin-top:9px;padding-top:8px;display:flex;gap:9px;flex-wrap:wrap;border-top:1px solid var(--border);color:var(--muted);font-size:8px}.chat-turn-meta code{max-width:100%;overflow-wrap:anywhere}.playground-inline-notice{padding:8px 17px;color:var(--accent-strong);font-size:10px}.playground-inline-notice.failed{color:var(--danger)}.chat-composer{padding:13px 16px 15px;border-top:1px solid var(--border);background:var(--surface)}.chat-composer textarea{width:100%;min-height:82px;resize:vertical;padding:12px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--text);font:12px/1.6 inherit}.chat-composer textarea:focus{outline:2px solid var(--accent-border)}.composer-actions{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-top:9px}.composer-tools{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.composer-tools .secondary-button,.send-button{min-height:34px;padding-inline:11px;font-size:10px}.file-support-hint{color:var(--muted);font-size:8px}.send-button{min-width:94px;justify-content:space-between}.send-button span{font-size:14px}.selected-files{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px}.file-pill{max-width:100%;padding:4px 8px;display:flex;align-items:center;gap:8px;border:1px solid var(--border);border-radius:999px;background:var(--surface-2);font-size:9px}.file-pill span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.file-pill button{border:0;background:transparent;color:var(--danger);cursor:pointer;font-size:14px}.chat-advanced{margin-top:10px;color:var(--muted);font-size:9px}.chat-advanced summary{cursor:pointer}.advanced-options{margin-top:9px;padding:10px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;border:1px solid var(--border);border-radius:9px;background:var(--surface-2)}.playground-history{overflow:hidden}.playground-history>summary{padding:14px 17px;display:flex;justify-content:space-between;align-items:center;gap:10px;cursor:pointer;list-style:none}.playground-history>summary::-webkit-details-marker{display:none}.playground-history>summary span{display:grid;gap:4px}.playground-history>summary b{font-size:11px}.playground-history>summary small{color:var(--muted);font-size:9px}.playground-history>summary i{color:var(--accent-strong);font-size:9px;font-style:normal}.playground-history-table{border-top:1px solid var(--border)}.playground-history-row{padding:10px 14px;display:grid;grid-template-columns:minmax(140px,1.5fr) .7fr .8fr 1fr minmax(100px,1fr);gap:10px;align-items:center;border-bottom:1px solid var(--border);font-size:9px}.playground-history-row>span:first-child{min-width:0;display:grid;gap:4px}.playground-history-row b,.playground-history-row small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.playground-history-row small{color:var(--muted)}.playground-history-row code{overflow-wrap:anywhere;color:var(--muted);font-size:8px}.history-heading{color:var(--muted);font-size:8px;font-weight:800}.history-empty{margin:0;padding:14px;color:var(--muted);font-size:10px}.playground-empty{padding:24px}.playground-empty p{color:var(--muted);font-size:11px}.error-alert{color:var(--danger)}@keyframes pulse{to{opacity:.45}}@media(max-width:980px){.model-playground-layout{grid-template-columns:1fr}.model-playground-sidebar{order:0}.chat-playground{order:1;min-height:600px}.playground-history{order:2}.target-info-row{grid-template-columns:130px minmax(0,1fr);align-items:baseline}.model-playground-sidebar{grid-template-columns:1fr 1.4fr}.playground-sidebar-head{grid-column:1/-1}.model-playground-sidebar>.field{grid-column:1}.playground-target-info{grid-column:2;grid-row:2/5}}@media(max-width:700px){.model-playground-warning{display:grid;gap:4px}.chat-playground-header{align-items:flex-start;flex-direction:column}.chat-header-controls{width:100%;justify-content:space-between}.chat-transcript{padding:12px;max-height:60vh}.model-playground-sidebar{display:grid;grid-template-columns:1fr}.playground-sidebar-head,.model-playground-sidebar>.field,.playground-target-info{grid-column:1;grid-row:auto}.target-info-row{grid-template-columns:1fr}.advanced-options{grid-template-columns:1fr 1fr}.playground-history-row{grid-template-columns:minmax(110px,1.3fr) .8fr 1fr;gap:6px}.playground-history-row>:nth-child(4),.playground-history-row>:nth-child(5){grid-column:span 1}.history-heading{display:none}.playground-top-actions{width:100%}}
/* Keep inherited app typography valid; `font: size/line-height inherit` is not valid CSS. */
.chat-turn-content{font:inherit;font-size:12px;line-height:1.7}.chat-composer textarea{font:inherit;font-size:12px;line-height:1.6}
.stream-control{max-width:320px;display:grid;gap:4px}.stream-control>small{color:var(--muted);font-size:8px;line-height:1.4}.stream-toggle input:disabled{opacity:.6}
.saved-conversations{overflow:hidden}.saved-conversations-header{padding:16px 18px;display:flex;align-items:center;justify-content:space-between;gap:12px;border-bottom:1px solid var(--border)}.saved-conversations-header h2{margin:5px 0;font-size:15px}.saved-conversations-header p{margin:0;color:var(--muted);font-size:9px}.saved-conversation-search{padding:12px 16px;display:flex;gap:8px;border-bottom:1px solid var(--border)}.saved-conversation-search input{flex:1;min-width:150px;padding:9px 11px;border:1px solid var(--border);border-radius:9px;background:var(--surface-2);color:var(--text);font:inherit;font-size:10px}.saved-conversation-search .secondary-button{min-height:34px;padding-inline:11px;font-size:9px}.saved-conversation-privacy{margin:0;padding:8px 16px;border-bottom:1px solid var(--border);color:var(--muted);font-size:9px;line-height:1.5}.history-error{margin:0;padding:10px 16px;color:var(--danger);font-size:10px}.saved-conversation-list{max-height:520px;overflow:auto}.saved-conversation-item{padding:9px 13px;display:flex;align-items:stretch;gap:9px;border-bottom:1px solid var(--border)}.saved-conversation-item.selected{background:var(--surface-2)}.saved-conversation-open{flex:1;min-width:0;padding:4px;display:grid;gap:4px;text-align:left;border:0;background:transparent;color:var(--text);cursor:pointer}.saved-conversation-open:disabled{opacity:.65}.saved-conversation-model{overflow:hidden;color:var(--accent-strong);font-size:8px;font-weight:800;text-overflow:ellipsis;white-space:nowrap}.saved-conversation-open strong{overflow:hidden;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.saved-conversation-open small{display:-webkit-box;overflow:hidden;color:var(--muted);font-size:9px;line-height:1.45;-webkit-box-orient:vertical;-webkit-line-clamp:2}.saved-conversation-open time{color:var(--muted);font-size:8px}.saved-conversation-delete{align-self:center;padding:6px 9px;border:1px solid var(--border);border-radius:8px;background:transparent;color:var(--danger);font-size:9px;cursor:pointer}.saved-conversation-delete:disabled{opacity:.5;cursor:default}
@media(max-width:700px){.saved-conversation-search{flex-wrap:wrap}.saved-conversation-search input{flex-basis:100%}.saved-conversation-search .secondary-button{flex:1}.saved-conversation-item{padding-inline:9px}}
</style>
