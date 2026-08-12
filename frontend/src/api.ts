export type AdminAuth = { accessToken?: string; platformToken?: string }

export type User = { id: string; email: string; platformAdmin: boolean }
export type Session = { accessToken: string; user: User }
export type Endpoint = { id: string; nodeId: string; displayName: string; runtimeType: string; baseUrl: string; enabled: boolean; healthStatus: string; lastCheckedAt?: string; inputPricePerMillion?: number | null; outputPricePerMillion?: number | null; currency?: 'KRW' | 'USD' | null }
export type Deployment = { id: string; runtimeEndpointId: string; externalProviderId?: string | null; providerModelId: string; compatibilityKey: string; displayName: string; modelFamily?: string; quantization?: string; contextLength?: number; loaded: boolean; enabled: boolean; healthStatus: string; maxConcurrency: number; capabilitiesJson: string; capabilityOverridesJson?: string | null; inputPricePerMillion?: number | null; outputPricePerMillion?: number | null; currency?: 'KRW' | 'USD' | null }

let refreshInFlight: Promise<Session> | null = null

function adminHeaders(auth: AdminAuth): HeadersInit {
  if (auth.accessToken) return { Authorization: `Bearer ${auth.accessToken}` }
  return { 'X-Admin-Token': auth.platformToken ?? '' }
}
function scopedPath(path: string, auth: AdminAuth, init: RequestInit): string {
  if (!auth.accessToken || (init.method && init.method !== 'GET')) return path
  const user: User | null = JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null')
  const organizationId = sessionStorage.getItem('aiconnect.setup.organizationId')
  if (!user || user.platformAdmin || !organizationId) return path
  if (path === '/api/admin/runtime-endpoints') return `/api/admin/organizations/${organizationId}/runtime-endpoints`
  return path
}
function persistSession(session: Session): Session {
  sessionStorage.setItem('aiconnect.accessToken', session.accessToken)
  sessionStorage.setItem('aiconnect.user', JSON.stringify(session.user))
  sessionStorage.removeItem('aiconnect.platformToken')
  window.dispatchEvent(new CustomEvent<Session>('aiconnect:session', { detail: session }))
  return session
}
function responseError(response: Response, fallback: string): Promise<Error> {
  return response.json()
    .catch(() => ({ message: response.statusText }))
    .then(body => new Error(body.message ?? fallback))
}

export async function authenticate(mode: 'login' | 'bootstrap', email: string, password: string): Promise<Session> {
  const response = await fetch(`/api/auth/${mode}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include', body: JSON.stringify({ email, password }) })
  if (!response.ok) throw await responseError(response, '로그인에 실패했습니다.')
  return persistSession(await response.json() as Session)
}

export function refreshAuthentication(): Promise<Session> {
  if (refreshInFlight) return refreshInFlight
  refreshInFlight = fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
    .then(async response => {
      if (!response.ok) throw await responseError(response, '관리자 세션을 갱신할 수 없습니다.')
      return persistSession(await response.json() as Session)
    })
    .finally(() => { refreshInFlight = null })
  return refreshInFlight
}

export async function logout(): Promise<void> {
  await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' })
}

async function fetchAdmin(path: string, auth: AdminAuth, init: RequestInit): Promise<Response> {
  return fetch(scopedPath(path, auth, init), {
    ...init,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...adminHeaders(auth), ...(init.headers ?? {}) }
  })
}

export async function adminFetch<T>(path: string, auth: AdminAuth, init: RequestInit = {}): Promise<T> {
  let response = await fetchAdmin(path, auth, init)
  if (response.status === 401 && auth.accessToken) {
    const session = await refreshAuthentication()
    response = await fetchAdmin(path, { accessToken: session.accessToken }, init)
  }
  if (!response.ok) throw await responseError(response, '요청을 처리할 수 없습니다.')
  if (response.status === 204) return undefined as T
  const body = await response.text()
  return body.trim() ? JSON.parse(body) as T : undefined as T
}
