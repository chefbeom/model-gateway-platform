export type RequestAttemptDetail = {
  deploymentId: string
  attemptNumber: number
  status: string
  startedAt: string
  completedAt?: string | null
  latencyMs?: number | null
  httpStatus?: number | null
  errorType?: string | null
  responseStarted: boolean
}

export type RequestDiagnostic = {
  schemaVersion: number
  request: {
    logicalModel?: string | null
    requestType: string
    capabilities: string[]
    stream: boolean
    messageCount: number
    toolCount: number
    hasResponseFormat: boolean
    responseFormatType?: string | null
    hasMaxTokens: boolean
    hasMaxCompletionTokens: boolean
  }
  service: {
    failoverPolicy: string
    retryPolicy: string
    degradedAllowed: boolean
    requiredCapabilities: string[]
  }
  finalCode: string
  httpStatus?: number | null
  attemptedCount: number
  summary: string
  targets: Array<{
    targetId: string
    deploymentId: string
    displayName: string
    providerType: string
    priority: number
    weight: number
    targetEnabled: boolean
    degraded: boolean
    deploymentEnabled: boolean
    loaded: boolean
    deploymentHealth?: string | null
    endpointDisplayName?: string | null
    providerDisplayName?: string | null
    activeRequests?: number | null
    maxConcurrency: number
    requiredCapabilities: string[]
    availableCapabilities: string[]
    missingCapabilities: string[]
    eligible: boolean
    reasonCodes: string[]
  }>
  recommendations: Array<{ code: string; title: string; detail: string }>
}

export type RequestDetail = {
  requestId: string
  projectId: string
  serviceId: string
  serviceKey?: string | null
  serviceDisplayName?: string | null
  requestType: string
  capabilities: string[]
  endpoint: string
  stream: boolean
  status: string
  finalDeploymentId?: string | null
  deploymentDisplayName?: string | null
  providerType?: string | null
  routingReason?: string | null
  inputTokens?: number | null
  outputTokens?: number | null
  estimatedCost?: number | null
  costCurrency?: 'KRW' | 'USD' | null
  inputUnitPrice?: number | null
  outputUnitPrice?: number | null
  latencyMs?: number | null
  failoverCount: number
  httpStatus?: number | null
  errorCode?: string | null
  startedAt: string
  completedAt?: string | null
  attempts: RequestAttemptDetail[]
  diagnostic?: RequestDiagnostic | null
}

const requestTypeLabels: Record<string, string> = {
  TEXT_CHAT: '텍스트 · 채팅',
  CHAT_COMPLETION: '텍스트 · 채팅',
  VISION: '비전 · 이미지',
  TOOL_CALLING: '도구 호출',
  STRUCTURED_OUTPUT: '구조화 출력'
}

export function requestTypeLabel(type?: string | null) {
  if (!type) return '텍스트 · 채팅'
  return type.split('+').map(value => requestTypeLabels[value] ?? value).join(' · ')
}

export function formatRequestCost(value?: number | null, currency?: string | null) {
  if (currency !== 'KRW' && currency !== 'USD') return '통화 정보 없음'
  return new Intl.NumberFormat(currency === 'USD' ? 'en-US' : 'ko-KR', {
    style: 'currency',
    currency,
    maximumFractionDigits: 6
  }).format(Number(value ?? 0))
}

export function formatRequestDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('ko-KR') : '-'
}

export function formatRequestDuration(value?: number | null) {
  return value == null ? '-' : String(new Intl.NumberFormat('ko-KR').format(value)) + ' ms'
}