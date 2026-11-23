export type RuntimeType = 'LM_STUDIO' | 'OLLAMA' | 'LLAMA_CPP' | 'OPENAI_COMPATIBLE'
export type AccountTier = 'SYSTEM_ADMIN' | 'PROJECT_DIRECTOR' | 'GENERAL_USER'
export type MfaState = 'ENROLLMENT_REQUIRED' | 'VERIFICATION_REQUIRED' | 'VERIFIED'
export type Currency = 'KRW' | 'USD'
export type DataBoundaryPolicy = 'INTERNAL_ONLY' | 'APPROVED_EXTERNAL_NO_RETENTION' | 'EXTERNAL_ALLOWED'
export type SpendQuotaScopeType = 'ORGANIZATION' | 'TEAM' | 'PROJECT' | 'API_KEY'
export type SpendQuotaPeriod = 'MONTHLY' | 'DAILY' | 'TOTAL'

export type AdminAuth = { accessToken?: string; platformToken?: string }

export type User = {
  id: string
  email: string
  platformAdmin: boolean
  accountTier: AccountTier
  totpEnabled: boolean
}

export type Session = {
  accessToken: string
  user: User
  mfaState: MfaState
}

export type TotpSetup = {
  secret: string
  otpauthUri: string
  issuer: string
  email: string
}

export type TotpEnrollResponse = {
  session: Session
  recoveryCodes: string[]
}

export type InvitationInspection = {
  valid: boolean
  targetTier: AccountTier
  organizationId: string
  organizationName: string
  teamId?: string | null
  teamName?: string | null
  projectId?: string | null
  projectName?: string | null
  canIssueApiKeys?: boolean
  expectedEmail?: string | null
  expiresAt: string
}

export type IssuedAccountInvitation = {
  id: string
  code: string
  registrationPath: string
  targetTier: AccountTier
  organizationId: string
  teamId?: string | null
  teamRole?: string | null
  projectId?: string | null
  betaServiceId?: string | null
  canIssueApiKeys?: boolean
  expectedEmail?: string | null
  expiresAt: string
}

export type AccountInvitationSummary = {
  id: string
  codePrefix?: string
  targetTier: AccountTier
  organizationId: string
  organizationName?: string | null
  teamId?: string | null
  teamName?: string | null
  teamRole?: string | null
  projectId?: string | null
  projectName?: string | null
  betaServiceId?: string | null
  canIssueApiKeys?: boolean
  expectedEmail?: string | null
  issuedByUserId?: string | null
  issuedByEmail?: string | null
  issuedByTier?: string | null
  status?: string
  expiresAt: string
  consumedAt?: string | null
  revokedAt?: string | null
  createdAt?: string
}

export type ProjectManagerView = {
  userId: string
  email: string
  accountTier?: AccountTier | string
  role?: string
  canIssueApiKeys?: boolean
  enabled?: boolean
  activeApiKeyCount?: number
  createdAt?: string
}

export type ProjectMemberView = {
  userId: string
  email: string
  accountTier?: AccountTier | string
  role: 'MANAGER' | 'MEMBER' | string
  canIssueApiKeys: boolean
  activeApiKeyCount?: number
  createdAt?: string
}

export type ProjectGovernanceView = {
  id: string
  organizationId: string
  teamId?: string | null
  name: string
  status: string
  externalAiBlocked: boolean
  dataBoundaryPolicy?: DataBoundaryPolicy
  maxDataBoundaryPolicy?: DataBoundaryPolicy
}

export type ProjectQuotaView = {
  projectId: string
  requestsPerMinute: number
  monthlyTokenLimit: number | null
  maxRequestsPerMinute?: number | null
  maxMonthlyTokenLimit?: number | null
}

export type SpendQuotaView = {
  id: string
  organizationId?: string
  scopeType: SpendQuotaScopeType
  scopeId?: string | null
  scopeLabel?: string | null
  name: string
  currency: Currency
  limitAmount: number
  period: SpendQuotaPeriod
  enabled: boolean
  adminCeiling?: boolean
  usedAmount: number
  usagePercent: number
  exceeded: boolean
  periodFrom?: string | null
  periodTo?: string | null
  createdAt?: string
  updatedAt?: string
}

export type ProjectSpendQuotaOverview = {
  projectId: string
  ceilings: SpendQuotaView[]
  quotas: SpendQuotaView[]
}

export type RuntimeTrustView = {
  endpointId: string
  classification: string
  valid: boolean
  evidence: string | null
  expiresAt: string | null
  approvedBy?: string | null
  decidedAt?: string | null
  revokedAt?: string | null
  vendorName?: string | null
  processingLocation?: string | null
  retentionPolicy?: string | null
  approvalScope?: string | null
}

export type ApiError = Error & { code?: string; status?: number }

export type Endpoint = { id: string; nodeId: string; displayName: string; runtimeType: RuntimeType; baseUrl: string; enabled: boolean; healthStatus: string; lastCheckedAt?: string; inputPricePerMillion?: number | null; outputPricePerMillion?: number | null; currency?: 'KRW' | 'USD' | null }
export type Deployment = { id: string; runtimeEndpointId: string; externalProviderId?: string | null; providerModelId: string; compatibilityKey: string; displayName: string; modelFamily?: string; quantization?: string; contextLength?: number; loaded: boolean; enabled: boolean; healthStatus: string; maxConcurrency: number; capabilitiesJson: string; capabilityOverridesJson?: string | null; featureSupportJson?: string | null; inputPricePerMillion?: number | null; outputPricePerMillion?: number | null; currency?: 'KRW' | 'USD' | null; metadataJson?: string | null }

let refreshInFlight: Promise<Session> | null = null

export function accountTierLabel(tier?: string | null, platformAdmin?: boolean): string {
  if (platformAdmin || tier === 'SYSTEM_ADMIN') return '시스템 관리자'
  if (tier === 'PROJECT_DIRECTOR') return '프로젝트 총괄'
  return '기본 계정'
}

export function registrationUrl(registrationPath: string): string {
  const path = registrationPath.startsWith('/') ? registrationPath : `/${registrationPath}`
  if (typeof window !== 'undefined' && window.location?.origin && window.location.origin !== 'null') {
    return `${window.location.origin}${path}`
  }
  return path
}

export function normalizeSession(raw: Session): Session {
  const accountTier: AccountTier = raw.user.accountTier ?? (raw.user.platformAdmin ? 'SYSTEM_ADMIN' : 'GENERAL_USER')
  const totpEnabled = Boolean(raw.user.totpEnabled)
  const mfaState: MfaState = raw.mfaState ?? (totpEnabled ? 'VERIFIED' : 'ENROLLMENT_REQUIRED')
  return {
    accessToken: raw.accessToken,
    user: {
      ...raw.user,
      accountTier,
      totpEnabled
    },
    mfaState
  }
}

export type SessionExpiredReason = 'expired' | 'server_unavailable' | 'invalidated'

const replacedAccessTokens = new Map<string, string>()

export function clearReplacedAccessTokens(): void {
  replacedAccessTokens.clear()
}

function dispatchWindowEvent<T>(name: string, detail?: T): void {
  if (name === 'aiconnect:session-expired') {
    replacedAccessTokens.clear()
  }
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function' && typeof CustomEvent === 'function') {
    window.dispatchEvent(new CustomEvent<T>(name, detail === undefined ? undefined : { detail }))
  }
}

export function notifySessionExpired(reason: SessionExpiredReason = 'expired'): void {
  dispatchWindowEvent<{ reason: SessionExpiredReason }>('aiconnect:session-expired', { reason })
}

export function isServerUnavailableStatus(status: number): boolean {
  return status === 502 || status === 503 || status === 504
}

async function hasJsonBody(response: Response): Promise<boolean> {
  try {
    const target = typeof response.clone === 'function' ? response.clone() : response
    const parsed = await target.json()
    return parsed !== null && typeof parsed === 'object'
  } catch {
    return false
  }
}

function serverUnavailableError(message: string, status?: number): ApiError {
  const error = new Error(message) as ApiError
  error.code = 'SERVER_UNAVAILABLE'
  error.status = status
  return error
}

function isServerUnavailableError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const candidate = error as { code?: unknown; status?: unknown }
  return candidate.code === 'SERVER_UNAVAILABLE'
    || (typeof candidate.status === 'number' && isServerUnavailableStatus(candidate.status))
}

function resolveAccessToken(token?: string): string | undefined {
  if (!token) return token
  let current = token
  for (let i = 0; i < 8; i++) {
    const next = replacedAccessTokens.get(current)
    if (!next) break
    current = next
  }
  if (current === token) return token
  if (typeof sessionStorage !== 'undefined' && sessionStorage.getItem('aiconnect.accessToken') === current) {
    return current
  }
  return token
}

function resolveAdminAuth(auth: AdminAuth): AdminAuth {
  if (!auth.accessToken) return auth
  const resolved = resolveAccessToken(auth.accessToken)
  return resolved === auth.accessToken ? auth : { ...auth, accessToken: resolved }
}

function adminHeaders(auth: AdminAuth): HeadersInit {
  if (auth.accessToken) return { Authorization: `Bearer ${auth.accessToken}` }
  return { 'X-Admin-Token': auth.platformToken ?? '' }
}

function scopedPath(path: string, auth: AdminAuth, init: RequestInit): string {
  if (!auth.accessToken || (init.method && init.method !== 'GET')) return path
  const user: User | null = typeof sessionStorage !== 'undefined'
    ? JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null')
    : null
  const organizationId = typeof sessionStorage !== 'undefined'
    ? sessionStorage.getItem('aiconnect.setup.organizationId')
    : null
  if (!user || user.platformAdmin || !organizationId) return path
  if (path === '/api/admin/runtime-endpoints') return `/api/admin/organizations/${organizationId}/runtime-endpoints`
  return path
}

export function persistSession(raw: Session): Session {
  const session = normalizeSession(raw)
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem('aiconnect.accessToken', session.accessToken)
    sessionStorage.setItem('aiconnect.user', JSON.stringify(session.user))
    sessionStorage.setItem('aiconnect.mfaState', session.mfaState)
    sessionStorage.removeItem('aiconnect.platformToken')
  }
  dispatchWindowEvent<Session>('aiconnect:session', session)
  return session
}

function responseError(response: Response, fallback: string): Promise<ApiError> {
  return response.json()
    .catch(() => ({ message: response.statusText, code: undefined }))
    .then(body => {
      const code = typeof body?.code === 'string' ? body.code : undefined
      if (code === 'TOTP_ENROLLMENT_REQUIRED') {
        dispatchWindowEvent<MfaState>('aiconnect:mfa-required', 'ENROLLMENT_REQUIRED')
      } else if (code === 'TOTP_VERIFICATION_REQUIRED') {
        dispatchWindowEvent<MfaState>('aiconnect:mfa-required', 'VERIFICATION_REQUIRED')
      }
      const error = new Error(body?.message ?? fallback) as ApiError
      error.code = code
      error.status = response.status
      return error
    })
}

export async function authenticate(
  mode: 'login' | 'bootstrap',
  email: string,
  password: string,
  options?: { totpCode?: string; recoveryCode?: string }
): Promise<Session> {
  const payload: Record<string, string> = { email, password }
  if (options?.totpCode) payload.totpCode = options.totpCode
  if (options?.recoveryCode) payload.recoveryCode = options.recoveryCode
  const response = await fetch(`/api/auth/${mode}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw await responseError(response, '로그인에 실패했습니다.')
  return persistSession(await response.json() as Session)
}

export function refreshAuthentication(): Promise<Session> {
  if (refreshInFlight) return refreshInFlight
  const previousToken = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('aiconnect.accessToken') : null
  refreshInFlight = fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'X-AIConnect-Auth-Client': '1' },
    credentials: 'include'
  })
    .catch(() => {
      throw serverUnavailableError('인증 서버와의 연결이 끊어졌습니다.')
    })
    .then(async response => {
      if (isServerUnavailableStatus(response.status) || (response.status >= 500 && !(await hasJsonBody(response)))) {
        throw serverUnavailableError('인증 서버와 연결할 수 없습니다.', response.status)
      }
      if (!response.ok) throw await responseError(response, '관리자 세션을 갱신할 수 없습니다.')
      const nextSession = persistSession(await response.json() as Session)
      if (previousToken && previousToken !== nextSession.accessToken) {
        replacedAccessTokens.set(previousToken, nextSession.accessToken)
      }
      return nextSession
    })
    .finally(() => { refreshInFlight = null })
  return refreshInFlight
}

export async function logout(): Promise<void> {
  await fetch('/api/auth/logout', {
    method: 'POST',
    headers: { 'X-AIConnect-Auth-Client': '1' },
    credentials: 'include'
  })
}

const NON_SESSION_401_CODES = new Set([
  'INVALID_TOTP_CODE',
  'TOTP_CODE_ALREADY_USED',
  'INVALID_RECOVERY_CODE',
  'TOTP_CODE_REQUIRED'
])

async function isServerUnavailableResponse(response: Response): Promise<boolean> {
  return isServerUnavailableStatus(response.status) || (response.status >= 500 && !(await hasJsonBody(response)))
}

async function sendWithSessionGuard(
  send: (overrideAccessToken?: string) => Promise<Response>,
  options: {
    allowRefresh: boolean
    platformTokenPresent?: boolean
    networkMessage: string
    parseNonSession401?: (response: Response) => Promise<Error | null>
  }
): Promise<Response> {
  const invoke = async (overrideToken?: string): Promise<Response> => {
    let response: Response
    try {
      response = await send(overrideToken)
    } catch {
      notifySessionExpired('server_unavailable')
      throw serverUnavailableError(options.networkMessage)
    }
    if (await isServerUnavailableResponse(response)) {
      notifySessionExpired('server_unavailable')
      throw serverUnavailableError('서버 점검 중이거나 연결이 종료되었습니다.', response.status)
    }
    return response
  }

  const first = await invoke()
  if (first.status !== 401) return first

  if (!options.allowRefresh) {
    if (options.platformTokenPresent) notifySessionExpired('invalidated')
    return first
  }

  let cachedNonOtpError: Error | null = null
  if (options.parseNonSession401) {
    cachedNonOtpError = await options.parseNonSession401(first)
    if (cachedNonOtpError) throw cachedNonOtpError
  }

  let session: Session
  try {
    session = await refreshAuthentication()
  } catch (refreshError) {
    notifySessionExpired(isServerUnavailableError(refreshError) ? 'server_unavailable' : 'expired')
    throw refreshError
  }

  const retried = await invoke(session.accessToken)
  if (retried.status === 401) {
    if (options.parseNonSession401) {
      const retryErr = await options.parseNonSession401(retried)
      if (retryErr) throw retryErr
    }
    notifySessionExpired('invalidated')
  }
  return retried
}

async function fetchTotpAuth(url: string, accessToken: string, fallback: string, payload?: unknown): Promise<Response> {
  let consumed401Error: ApiError | null = null
  const response = await sendWithSessionGuard(
    overrideToken => fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${overrideToken ?? resolveAccessToken(accessToken) ?? accessToken}`
      },
      credentials: 'include',
      ...(payload !== undefined ? { body: JSON.stringify(payload) } : {})
    }),
    {
      allowRefresh: true,
      networkMessage: '서버에 연결할 수 없어 2차 인증을 진행할 수 없습니다.',
      parseNonSession401: async res => {
        const err = await responseError(res, fallback)
        if (err.code && NON_SESSION_401_CODES.has(err.code)) return err
        consumed401Error = err
        return null
      }
    }
  )
  if (response.status === 401 && consumed401Error) throw consumed401Error
  if (!response.ok) throw await responseError(response, fallback)
  return response
}

export async function setupTotp(accessToken: string): Promise<TotpSetup> {
  const response = await fetchTotpAuth('/api/auth/totp/setup', accessToken, 'TOTP 설정 정보를 생성하지 못했습니다.')
  return await response.json() as TotpSetup
}

export async function enrollTotp(accessToken: string, code: string): Promise<TotpEnrollResponse> {
  const response = await fetchTotpAuth('/api/auth/totp/enroll', accessToken, 'TOTP 등록 인증에 실패했습니다.', { code })
  const result = await response.json() as TotpEnrollResponse
  return {
    session: normalizeSession(result.session),
    recoveryCodes: result.recoveryCodes
  }
}

export async function verifyTotp(accessToken: string, code: string): Promise<Session> {
  const response = await fetchTotpAuth('/api/auth/totp/verify', accessToken, '2차 인증 코드가 올바르지 않습니다.', { code })
  return persistSession(await response.json() as Session)
}

export async function inspectAccountInvitation(code: string): Promise<InvitationInspection> {
  const response = await fetch('/api/auth/invitations/inspect', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ code })
  })
  if (!response.ok) throw await responseError(response, '초대 코드를 확인할 수 없습니다.')
  return await response.json() as InvitationInspection
}

export async function acceptAccountInvitation(payload: {
  code: string
  email: string
  password: string
}): Promise<Session> {
  const response = await fetch('/api/auth/invitations/accept', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(payload)
  })
  if (!response.ok) throw await responseError(response, '초대 코드 기반 계정 생성에 실패했습니다.')
  return persistSession(await response.json() as Session)
}

async function fetchAdmin(path: string, auth: AdminAuth, init: RequestInit): Promise<Response> {
  const resolved = resolveAdminAuth(auth)
  return fetch(scopedPath(path, resolved, init), {
    ...init,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...adminHeaders(resolved), ...(init.headers ?? {}) }
  })
}

/** Makes an authenticated admin request without consuming the response body (used for SSE relays). */
export async function adminResponse(path: string, auth: AdminAuth, init: RequestInit = {}): Promise<Response> {
  return sendWithSessionGuard(
    overrideToken => fetchAdmin(path, overrideToken ? { accessToken: overrideToken } : auth, init),
    {
      allowRefresh: Boolean(auth.accessToken),
      platformTokenPresent: Boolean(auth.platformToken),
      networkMessage: '서버와의 연결이 끊어져 세션을 종료합니다.'
    }
  )
}

export async function adminFetch<T>(path: string, auth: AdminAuth, init: RequestInit = {}): Promise<T> {
  const response = await adminResponse(path, auth, init)
  if (!response.ok) throw await responseError(response, '요청을 처리할 수 없습니다.')
  if (response.status === 204) return undefined as T
  const body = await response.text()
  return body.trim() ? JSON.parse(body) as T : undefined as T
}

export const DATA_BOUNDARY_OPTIONS: Array<{
  value: DataBoundaryPolicy
  label: string
  shortLabel: string
  description: string
}> = [
  {
    value: 'INTERNAL_ONLY',
    label: '사내 GPU 전용 (INTERNAL_ONLY)',
    shortLabel: '사내 GPU 전용',
    description: '민감정보 여부와 무관하게 사내 GPU Runtime만 사용하며 외부 GPU·상용 API를 차단합니다.'
  },
  {
    value: 'APPROVED_EXTERNAL_NO_RETENTION',
    label: '승인된 외부 비보존 GPU 허용 (APPROVED_EXTERNAL_NO_RETENTION)',
    shortLabel: '외부 비보존 GPU 허용',
    description: '사내 GPU와 검증·승인된 외부 비보존 GPU만 허용하며 상용 외부 API는 차단합니다.'
  },
  {
    value: 'EXTERNAL_ALLOWED',
    label: '외부 AI 허용 (EXTERNAL_ALLOWED)',
    shortLabel: '외부 AI 허용',
    description: '데이터 보호 정책과 승인된 외부 Provider 범위 내에서 외부 AI를 허용합니다.'
  }
]

const DATA_BOUNDARY_RANK: Record<DataBoundaryPolicy, number> = {
  INTERNAL_ONLY: 0,
  APPROVED_EXTERNAL_NO_RETENTION: 1,
  EXTERNAL_ALLOWED: 2
}

export function normalizeDataBoundaryPolicy(policy?: DataBoundaryPolicy | string | null): DataBoundaryPolicy {
  if (policy === 'INTERNAL_ONLY' || policy === 'APPROVED_EXTERNAL_NO_RETENTION' || policy === 'EXTERNAL_ALLOWED') {
    return policy
  }
  return 'EXTERNAL_ALLOWED'
}

export function dataBoundaryRank(policy?: DataBoundaryPolicy | string | null): number {
  return DATA_BOUNDARY_RANK[normalizeDataBoundaryPolicy(policy)]
}

export function dataBoundaryLabel(policy?: DataBoundaryPolicy | string | null): string {
  const normalized = normalizeDataBoundaryPolicy(policy)
  return DATA_BOUNDARY_OPTIONS.find(option => option.value === normalized)?.label ?? normalized
}

export function dataBoundaryShortLabel(policy?: DataBoundaryPolicy | string | null): string {
  const normalized = normalizeDataBoundaryPolicy(policy)
  return DATA_BOUNDARY_OPTIONS.find(option => option.value === normalized)?.shortLabel ?? normalized
}

export function strictestDataBoundary(...policies: Array<DataBoundaryPolicy | string | null | undefined>): DataBoundaryPolicy {
  let strictest: DataBoundaryPolicy = 'EXTERNAL_ALLOWED'
  for (const policy of policies) {
    if (!policy) continue
    const normalized = normalizeDataBoundaryPolicy(policy)
    if (DATA_BOUNDARY_RANK[normalized] < DATA_BOUNDARY_RANK[strictest]) {
      strictest = normalized
    }
  }
  return strictest
}

export function exceedsDataBoundary(
  candidate?: DataBoundaryPolicy | string | null,
  ceiling?: DataBoundaryPolicy | string | null
): boolean {
  if (!ceiling) return false
  return dataBoundaryRank(candidate) > dataBoundaryRank(ceiling)
}

export function resolveOrgAdminAccess(auth: AdminAuth, platformAdmin?: boolean, orgAdmin?: boolean): boolean {
  if (platformAdmin || auth.platformToken) return true
  if (orgAdmin !== undefined) return orgAdmin
  if (typeof sessionStorage !== 'undefined') {
    try {
      const user = JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null') as Partial<User> | null
      if (user?.platformAdmin || user?.accountTier === 'SYSTEM_ADMIN') return true
      if (user?.accountTier === 'PROJECT_DIRECTOR' || user?.accountTier === 'GENERAL_USER') return false
    } catch { /* ignore malformed sessionStorage */ }
  }
  return false
}

export function listProjectManagers(projectId: string, auth: AdminAuth): Promise<ProjectManagerView[]> {
  return adminFetch<ProjectManagerView[]>(`/api/admin/platform/projects/${projectId}/managers`, auth)
}

export function revokeProjectManager(projectId: string, userId: string, auth: AdminAuth): Promise<void> {
  return adminFetch<void>(`/api/admin/platform/projects/${projectId}/managers/${userId}`, auth, { method: 'DELETE' })
}

export function listProjectMembers(projectId: string, auth: AdminAuth): Promise<ProjectMemberView[]> {
  return adminFetch<ProjectMemberView[]>(`/api/admin/projects/${projectId}/members`, auth)
}

export function updateProjectMemberDelegation(
  projectId: string,
  userId: string,
  canIssueApiKeys: boolean,
  auth: AdminAuth
): Promise<ProjectMemberView> {
  return adminFetch<ProjectMemberView>(`/api/admin/projects/${projectId}/members/${userId}`, auth, {
    method: 'PATCH',
    body: JSON.stringify({ canIssueApiKeys })
  })
}

export function revokeProjectMember(projectId: string, userId: string, auth: AdminAuth): Promise<void> {
  return adminFetch<void>(`/api/admin/projects/${projectId}/members/${userId}`, auth, { method: 'DELETE' })
}

export function updateProject(
  projectId: string,
  payload: {
    name: string
    teamId?: string | null
    externalAiBlocked?: boolean
    dataBoundaryPolicy?: DataBoundaryPolicy
    maxDataBoundaryPolicy?: DataBoundaryPolicy
  },
  auth: AdminAuth
): Promise<ProjectGovernanceView> {
  return adminFetch<ProjectGovernanceView>(`/api/admin/projects/${projectId}`, auth, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export function updateApiKeyBoundaryPolicy<T = unknown>(
  projectId: string,
  apiKeyId: string,
  dataBoundaryPolicy: DataBoundaryPolicy,
  auth: AdminAuth
): Promise<T> {
  return adminFetch<T>(`/api/admin/projects/${projectId}/api-keys/${apiKeyId}/policy`, auth, {
    method: 'PATCH',
    body: JSON.stringify({ dataBoundaryPolicy })
  })
}

export function updateServicePolicy<T = unknown>(
  serviceId: string,
  payload: Record<string, unknown>,
  auth: AdminAuth
): Promise<T> {
  return adminFetch<T>(`/api/admin/services/${serviceId}`, auth, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export function getProjectQuotaCeiling(projectId: string, auth: AdminAuth): Promise<ProjectQuotaView> {
  return adminFetch<ProjectQuotaView>(`/api/admin/platform/projects/${projectId}/quota-ceiling`, auth)
}

export function setProjectQuotaCeiling(
  projectId: string,
  payload: { maxRequestsPerMinute: number | null; maxMonthlyTokenLimit: number | null },
  auth: AdminAuth
): Promise<ProjectQuotaView> {
  return adminFetch<ProjectQuotaView>(`/api/admin/platform/projects/${projectId}/quota-ceiling`, auth, {
    method: 'PUT',
    body: JSON.stringify(payload)
  })
}

export function listProjectSpendQuotas(projectId: string, auth: AdminAuth): Promise<ProjectSpendQuotaOverview> {
  return adminFetch<ProjectSpendQuotaOverview>(`/api/admin/projects/${projectId}/spend-quotas`, auth)
}

export function createProjectSpendQuota(
  projectId: string,
  payload: {
    scopeType: 'PROJECT' | 'API_KEY'
    scopeId: string
    name: string
    period: SpendQuotaPeriod
    currency: Currency
    limitAmount: number
    enabled?: boolean
  },
  auth: AdminAuth
): Promise<SpendQuotaView> {
  return adminFetch<SpendQuotaView>(`/api/admin/projects/${projectId}/spend-quotas`, auth, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

export function updateProjectSpendQuota(
  projectId: string,
  quotaId: string,
  payload: { name?: string; limitAmount?: number; enabled?: boolean },
  auth: AdminAuth
): Promise<SpendQuotaView> {
  return adminFetch<SpendQuotaView>(`/api/admin/projects/${projectId}/spend-quotas/${quotaId}`, auth, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export function deleteProjectSpendQuota(projectId: string, quotaId: string, auth: AdminAuth): Promise<void> {
  return adminFetch<void>(`/api/admin/projects/${projectId}/spend-quotas/${quotaId}`, auth, { method: 'DELETE' })
}

export function decideExternalAccess<T = unknown>(
  projectId: string,
  providerId: string,
  payload: {
    status: 'APPROVED' | 'REJECTED'
    manualAllowed: boolean
    autoFailoverEnabled: boolean
    monthlyCostLimit: number | null
    monthlyCostCurrency?: Currency | null
    expiresAt?: string | null
  },
  auth: AdminAuth
): Promise<T> {
  return adminFetch<T>(`/api/admin/projects/${projectId}/external-access/${providerId}`, auth, {
    method: 'PATCH',
    body: JSON.stringify(payload)
  })
}

export function getRuntimeTrust(endpointId: string, auth: AdminAuth): Promise<RuntimeTrustView> {
  return adminFetch<RuntimeTrustView>(`/api/admin/platform/runtime-endpoints/${endpointId}/trust`, auth)
}

export function attestRuntimeTrust(
  endpointId: string,
  payload: {
    classification: 'INTERNAL' | 'EXTERNAL_NO_RETENTION' | 'EXTERNAL_UNVERIFIED'
    evidence: string
    expiresAt: string | null
    vendorName?: string | null
    processingLocation?: string | null
    retentionPolicy?: string | null
    approvalScope?: string | null
  },
  auth: AdminAuth
): Promise<RuntimeTrustView> {
  return adminFetch<RuntimeTrustView>(`/api/admin/platform/runtime-endpoints/${endpointId}/trust`, auth, {
    method: 'PUT',
    body: JSON.stringify(payload)
  })
}

export function revokeRuntimeTrust(endpointId: string, auth: AdminAuth): Promise<RuntimeTrustView> {
  return adminFetch<RuntimeTrustView>(`/api/admin/platform/runtime-endpoints/${endpointId}/trust/revoke`, auth, {
    method: 'POST'
  })
}

export function createAccountInvitation(
  organizationId: string,
  payload: {
    targetTier: 'PROJECT_DIRECTOR' | 'GENERAL_USER'
    organizationId?: string | null
    projectId?: string | null
    teamId?: string | null
    teamRole?: string | null
    betaServiceId?: string | null
    expectedEmail?: string | null
    expiresInHours?: number
    canIssueApiKeys?: boolean
  },
  auth: AdminAuth,
  platformScope = false
): Promise<IssuedAccountInvitation> {
  const path = platformScope
    ? '/api/admin/platform/account-invitations'
    : `/api/admin/organizations/${organizationId}/account-invitations`
  return adminFetch<IssuedAccountInvitation>(path, auth, {
    method: 'POST',
    body: JSON.stringify({ organizationId, ...payload })
  })
}

/** Normalizes pasted or typed Google Authenticator OTP input to at most 6 digits. */
export function normalizeTotpDigits(value: string): string {
  return value.replace(/\D/g, '').slice(0, 6)
}

export type DataProtectionAction = 'ALLOW' | 'REDACT_RESTORE' | 'REDACT' | 'LOCAL_ONLY' | 'BLOCK'

export type MaskerDeploymentOption = {
  deploymentId: string
  displayName: string
  providerModelId: string
  sourceType: 'LOCAL_RUNTIME' | 'EXTERNAL_ZERO_RETENTION' | 'EXTERNAL_UNVERIFIED' | string
  sourceName: string
  eligible: boolean
  trustReason: string
}

export type RedactionEntry = {
  category: string
  original: string
  surrogate: string
  source: string
}

export type PipelinePreviewRequest = {
  sampleText: string
  externalAction?: DataProtectionAction
  maskPersonNames?: boolean
  maskerDeploymentId?: string | null
  invokeMaskerLlm?: boolean
}

export type PipelinePreviewResponse = {
  originalText: string
  outboundTextForExternalLlm: string
  simulatedExternalLlmReply: string
  finalUserResponse: string
  action: DataProtectionAction | string
  externalAllowed: boolean
  restoreOnReturn: boolean
  maskerUsed: string
  detectedClassifications: string[]
  redactions: RedactionEntry[]
  promptVersion: number
  responseCacheEnabled: boolean
}

export function listMaskerDeployments(organizationId: string, auth: AdminAuth): Promise<MaskerDeploymentOption[]> {
  return adminFetch<MaskerDeploymentOption[]>(`/api/admin/organizations/${organizationId}/masker-deployments`, auth)
}

export function previewServicePipeline(
  serviceId: string,
  payload: PipelinePreviewRequest,
  auth: AdminAuth
): Promise<PipelinePreviewResponse> {
  return adminFetch<PipelinePreviewResponse>(`/api/admin/services/${serviceId}/pipeline-preview`, auth, {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}
