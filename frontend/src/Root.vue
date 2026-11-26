<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import ConsoleShell from './ConsoleShell.vue'
import InviteRegistrationView from './InviteRegistrationView.vue'
import LoginView from './LoginView.vue'
import TotpOnboardingView from './TotpOnboardingView.vue'
import { clearReplacedAccessTokens, logout, normalizeSession, refreshAuthentication, type MfaState, type Session, type User } from './api'

function clearStashedDrafts() {
  if (typeof window !== 'undefined' && typeof window.dispatchEvent === 'function' && typeof CustomEvent === 'function') {
    window.dispatchEvent(new CustomEvent('aiconnect:clear-drafts'))
  }
}

type Theme = 'dark' | 'light'
type FontScale = '100' | '115' | '125' | '135'

function parseRegisterHash(rawHash: string): { isRegister: boolean; code: string } {
  const trimmed = rawHash.replace(/^#\/?/, '')
  const [path, query = ''] = trimmed.split('?')
  if (path !== 'register') return { isRegister: false, code: '' }
  const params = new URLSearchParams(query)
  return { isRegister: true, code: (params.get('code') ?? '').trim() }
}

function readStoredUser(): User | null {
  try {
    if (typeof sessionStorage === 'undefined') return null
    return JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null') as User | null
  } catch {
    return null
  }
}

function readStoredMfaState(): MfaState | '' {
  if (typeof sessionStorage === 'undefined') return ''
  const raw = sessionStorage.getItem('aiconnect.mfaState')
  return raw === 'ENROLLMENT_REQUIRED' || raw === 'VERIFICATION_REQUIRED' || raw === 'VERIFIED' ? raw : ''
}

const checkingSession = ref(true)
const user = ref<User | null>(readStoredUser())
const accessToken = ref(typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('aiconnect.accessToken') ?? '' : '')
const mfaState = ref<MfaState | ''>(readStoredMfaState())
const platformToken = ref(typeof sessionStorage !== 'undefined' ? sessionStorage.getItem('aiconnect.platformToken') ?? '' : '')
const platformSessionVersion = ref(0)
const currentHash = ref(typeof window !== 'undefined' ? window.location?.hash ?? '' : '')
const pendingInviteCode = ref(parseRegisterHash(currentHash.value).code)
const theme = ref<Theme>(initialTheme())
const fontScale = ref<FontScale>(initialFontScale())

const registerRoute = computed(() => parseRegisterHash(currentHash.value))

const activeView = computed<'splash' | 'console' | 'totp-onboarding' | 'invite-register' | 'login-mfa' | 'login'>(() => {
  if (checkingSession.value) return 'splash'
  if (platformToken.value) return 'console'
  if (accessToken.value && user.value) {
    if (mfaState.value === 'ENROLLMENT_REQUIRED' || !user.value.totpEnabled) {
      return 'totp-onboarding'
    }
    if (mfaState.value === 'VERIFIED' && user.value.totpEnabled) {
      return 'console'
    }
    return 'login-mfa'
  }
  if (registerRoute.value.isRegister) return 'invite-register'
  return 'login'
})

function initialTheme(): Theme {
  if (typeof localStorage === 'undefined') return 'dark'
  const stored = localStorage.getItem('aiconnect.theme')
  if (stored === 'light' || stored === 'dark') return stored
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

function initialFontScale(): FontScale {
  if (typeof localStorage === 'undefined') return '115'
  const stored = localStorage.getItem('aiconnect.fontScale')
  return stored === '100' || stored === '115' || stored === '125' || stored === '135'
    ? stored
    : '115'
}

function applyPreferences() {
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.theme = theme.value
    document.documentElement.dataset.fontScale = fontScale.value
    document.documentElement.style.colorScheme = theme.value
  }
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem('aiconnect.theme', theme.value)
    localStorage.setItem('aiconnect.fontScale', fontScale.value)
  }
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  applyPreferences()
}

function setFontScale(value: FontScale) {
  fontScale.value = value
  applyPreferences()
}

function setLocationHash(nextHash: string) {
  currentHash.value = nextHash
  if (typeof window === 'undefined') return
  if (nextHash) {
    window.location.hash = nextHash
  } else if (window.history?.replaceState && window.location) {
    window.history.replaceState(null, '', window.location.pathname)
  } else if (window.location) {
    window.location.hash = ''
  }
}

function acceptSession(raw: Session) {
  const session = normalizeSession(raw)
  accessToken.value = session.accessToken
  user.value = session.user
  mfaState.value = session.mfaState
  platformToken.value = ''
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem('aiconnect.accessToken', session.accessToken)
    sessionStorage.setItem('aiconnect.user', JSON.stringify(session.user))
    sessionStorage.setItem('aiconnect.mfaState', session.mfaState)
    sessionStorage.removeItem('aiconnect.platformToken')
  }
}

function acceptPlatformToken(token: string) {
  clearStashedDrafts()
  platformSessionVersion.value += 1
  platformToken.value = token
  accessToken.value = ''
  user.value = null
  mfaState.value = ''
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem('aiconnect.platformToken', token)
    sessionStorage.removeItem('aiconnect.accessToken')
    sessionStorage.removeItem('aiconnect.user')
    sessionStorage.removeItem('aiconnect.mfaState')
  }
}

const sessionNotice = ref('')
let jwtRefreshTimer: ReturnType<typeof setTimeout> | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let sessionCheckInFlight: Promise<boolean> | null = null
let monitorArmed = false
let lastFocusCheckAt = 0

function clearJwtRefreshTimer() {
  if (jwtRefreshTimer !== null) {
    clearTimeout(jwtRefreshTimer)
    jwtRefreshTimer = null
  }
}

function sessionExpiredNotice(reason?: string): string {
  if (reason === 'server_unavailable') {
    return '서버와의 연결이 끊어졌거나 종료되어 안전하게 로그아웃되었습니다. 서버 상태를 확인한 뒤 다시 로그인하세요.'
  }
  if (reason === 'invalidated') {
    return '서버 재배포 또는 인증 정보 변경으로 세션이 만료되었습니다. 다시 로그인하세요.'
  }
  return '로그인 세션이 만료되어 로그아웃되었습니다. 다시 로그인하세요.'
}

function clearSession() {
  clearStashedDrafts()
  clearJwtRefreshTimer()
  clearReplacedAccessTokens()
  accessToken.value = ''
  platformToken.value = ''
  user.value = null
  mfaState.value = ''
  if (typeof sessionStorage !== 'undefined') {
    for (const key of [
      'aiconnect.accessToken',
      'aiconnect.user',
      'aiconnect.mfaState',
      'aiconnect.platformToken',
      'aiconnect.consumerApiKey',
      'aiconnect.setup.organizationId',
      'aiconnect.setup.projectId',
      'aiconnect.portal.projectId',
      'aiconnect.requestExplorerOrganization'
    ]) {
      sessionStorage.removeItem(key)
    }
  }
}

function expireSession(reason?: string) {
  const hadSession = Boolean(accessToken.value || platformToken.value)
  clearSession()
  if (!registerRoute.value.isRegister) {
    setLocationHash('')
  }
  if (hadSession || reason) {
    sessionNotice.value = sessionExpiredNotice(reason)
  }
}

async function signOut() {
  try {
    if (!platformToken.value) await logout()
  } finally {
    clearSession()
    sessionNotice.value = ''
    setLocationHash('')
  }
}

function onInviteRegistered(session: Session) {
  pendingInviteCode.value = ''
  acceptSession(session)
  setLocationHash('')
}

function onTotpEnrollmentCompleted(session: Session) {
  acceptSession(session)
}

async function openRegisterFromLogin() {
  try {
    if (!platformToken.value) await logout()
  } catch {
    // Ignore logout failures while switching to invite registration.
  } finally {
    clearSession()
    pendingInviteCode.value = ''
    setLocationHash('#register')
  }
}

function backToLoginFromRegister() {
  pendingInviteCode.value = ''
  setLocationHash('')
}

function onSessionEvent(event: Event) {
  const session = (event as CustomEvent<Session>).detail
  if (session) acceptSession(session)
}

function onMfaRequiredEvent(event: Event) {
  const requiredState = (event as CustomEvent<MfaState>).detail
  if (!requiredState) return
  mfaState.value = requiredState
  if (requiredState === 'ENROLLMENT_REQUIRED' && user.value) {
    user.value = { ...user.value, totpEnabled: false }
  }
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.setItem('aiconnect.mfaState', requiredState)
    if (user.value) sessionStorage.setItem('aiconnect.user', JSON.stringify(user.value))
  }
}

function onSessionExpiredEvent(event?: Event) {
  const reason = event && typeof event === 'object' && 'detail' in event
    ? (event as CustomEvent<{ reason?: string }>).detail?.reason
    : undefined
  expireSession(reason)
}

function captureAndScrubInviteCode(rawHash: string) {
  const parsed = parseRegisterHash(rawHash)
  if (parsed.isRegister && parsed.code) {
    pendingInviteCode.value = parsed.code
    if (typeof window !== 'undefined' && window.history?.replaceState && window.location) {
      window.history.replaceState(null, '', window.location.pathname + '#register')
      currentHash.value = '#register'
    }
  }
}

function onHashChange() {
  const next = window.location?.hash ?? ''
  currentHash.value = next
  captureAndScrubInviteCode(next)
}

function parseJwtExpiryMs(token: string): number | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4)
    const nodeBuffer = (globalThis as { Buffer?: { from: (value: string, encoding: string) => { toString: (encoding: string) => string } } }).Buffer
    const raw = typeof atob === 'function'
      ? atob(padded)
      : nodeBuffer
        ? nodeBuffer.from(padded, 'base64').toString('utf8')
        : ''
    if (!raw) return null
    const payload = JSON.parse(raw) as { exp?: unknown }
    return typeof payload.exp === 'number' && Number.isFinite(payload.exp) ? payload.exp * 1000 : null
  } catch {
    return null
  }
}

function isServerUnavailableFailure(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const candidate = error as { code?: unknown; status?: unknown }
  return candidate.code === 'SERVER_UNAVAILABLE'
    || candidate.status === 502
    || candidate.status === 503
    || candidate.status === 504
}

async function readJsonSafely(response: Response): Promise<{ ok: boolean; value: unknown }> {
  try {
    const target = typeof response.clone === 'function' ? response.clone() : response
    const value = await target.json()
    return { ok: value !== null && typeof value === 'object', value }
  } catch {
    return { ok: false, value: null }
  }
}

async function isServerDownResponse(response: Response): Promise<boolean> {
  if (response.status === 502 || response.status === 503 || response.status === 504) return true
  if (response.status >= 500) {
    const parsed = await readJsonSafely(response)
    return !parsed.ok
  }
  return false
}

function scheduleJwtRefresh() {
  clearJwtRefreshTimer()
  if (!accessToken.value || platformToken.value) return
  const expMs = parseJwtExpiryMs(accessToken.value)
  if (expMs === null) return
  const remainingMs = expMs - Date.now()
  const leadMs = remainingMs > 0 ? Math.min(25_000, Math.floor(remainingMs * 0.25)) : 0
  const delay = remainingMs <= 0 ? 0 : Math.max(1000, Math.min(remainingMs - leadMs, 2_147_483_647))
  const boundWindow = typeof window !== 'undefined' ? window : null
  jwtRefreshTimer = setTimeout(() => {
    if (typeof window === 'undefined' || window !== boundWindow) return
    void verifyActiveSession(true)
  }, delay)
  ;(jwtRefreshTimer as { unref?: () => void }).unref?.()
}

function verifyActiveSession(forceRefresh = false): Promise<boolean> {
  if (sessionCheckInFlight) return sessionCheckInFlight
  sessionCheckInFlight = (async () => {
    const expectedPlatformToken = platformToken.value
    if (expectedPlatformToken) {
      let response: Response
      try {
        response = await fetch('/api/admin/organizations', {
          headers: { 'Content-Type': 'application/json', 'X-Admin-Token': expectedPlatformToken },
          credentials: 'include'
        })
      } catch {
        if (platformToken.value === expectedPlatformToken) expireSession('server_unavailable')
        return false
      }
      if (await isServerDownResponse(response)) {
        if (platformToken.value === expectedPlatformToken) expireSession('server_unavailable')
        return false
      }
      if (response.status === 401 || response.status === 403) {
        if (platformToken.value === expectedPlatformToken) expireSession('invalidated')
        return false
      }
      if (!response.ok) {
        if (platformToken.value === expectedPlatformToken) expireSession('server_unavailable')
        return false
      }
      const parsed = await readJsonSafely(response)
      if (!parsed.ok) {
        if (platformToken.value === expectedPlatformToken) expireSession('invalidated')
        return false
      }
      return true
    }

    const expectedAccessToken = accessToken.value
    if (!expectedAccessToken) return true

    const expMs = parseJwtExpiryMs(expectedAccessToken)
    let needsRefresh = forceRefresh || (expMs !== null && Date.now() >= expMs)

    if (!needsRefresh) {
      let response: Response
      try {
        response = await fetch('/api/portal/session', {
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${expectedAccessToken}` },
          credentials: 'include'
        })
      } catch {
        if (accessToken.value === expectedAccessToken) expireSession('server_unavailable')
        return false
      }
      if (await isServerDownResponse(response)) {
        if (accessToken.value === expectedAccessToken) expireSession('server_unavailable')
        return false
      }
      if (response.ok) {
        const parsed = await readJsonSafely(response)
        const data = parsed.ok ? (parsed.value as { platformAdmin?: boolean; accountTier?: string; totpEnabled?: boolean; mfaState?: string }) : null
        if (!data || typeof data.mfaState !== 'string') {
          if (accessToken.value === expectedAccessToken) expireSession('invalidated')
          return false
        }
        if (accessToken.value === expectedAccessToken) {
          if (user.value) {
            user.value = {
              ...user.value,
              platformAdmin: typeof data.platformAdmin === 'boolean' ? data.platformAdmin : user.value.platformAdmin,
              accountTier: (data.accountTier as typeof user.value.accountTier) ?? user.value.accountTier,
              totpEnabled: typeof data.totpEnabled === 'boolean' ? data.totpEnabled : user.value.totpEnabled
            }
          }
          mfaState.value = data.mfaState as typeof mfaState.value
          if (typeof sessionStorage !== 'undefined') {
            if (user.value) sessionStorage.setItem('aiconnect.user', JSON.stringify(user.value))
            sessionStorage.setItem('aiconnect.mfaState', mfaState.value)
          }
          scheduleJwtRefresh()
        }
        return true
      }
      if (response.status === 403) {
        const parsed = await readJsonSafely(response)
        const code = parsed.ok ? (parsed.value as { code?: unknown }).code : undefined
        if (code === 'TOTP_ENROLLMENT_REQUIRED' || code === 'TOTP_VERIFICATION_REQUIRED') {
          if (accessToken.value === expectedAccessToken) {
            const nextMfa = code === 'TOTP_ENROLLMENT_REQUIRED' ? 'ENROLLMENT_REQUIRED' : 'VERIFICATION_REQUIRED'
            if (user.value) {
              user.value = { ...user.value, totpEnabled: code === 'TOTP_VERIFICATION_REQUIRED' }
            }
            mfaState.value = nextMfa
            if (typeof sessionStorage !== 'undefined') {
              if (user.value) sessionStorage.setItem('aiconnect.user', JSON.stringify(user.value))
              sessionStorage.setItem('aiconnect.mfaState', nextMfa)
            }
            scheduleJwtRefresh()
          }
          return true
        }
        if (accessToken.value === expectedAccessToken) expireSession('invalidated')
        return false
      }
      if (response.status === 401) {
        needsRefresh = true
      } else {
        if (accessToken.value === expectedAccessToken) expireSession('server_unavailable')
        return false
      }
    }

    if (needsRefresh) {
      if (accessToken.value !== expectedAccessToken) return true
      try {
        acceptSession(await refreshAuthentication())
        sessionNotice.value = ''
        scheduleJwtRefresh()
        return true
      } catch (error) {
        if (accessToken.value === expectedAccessToken) {
          expireSession(isServerUnavailableFailure(error) ? 'server_unavailable' : 'expired')
        }
        return false
      }
    }
    return true
  })().finally(() => {
    sessionCheckInFlight = null
  })
  return sessionCheckInFlight
}

function armSessionMonitor() {
  if (monitorArmed || typeof window === 'undefined') return
  monitorArmed = true
  const boundWindow = window
  const boundDocument = typeof document !== 'undefined' ? document : null

  const triggerCheck = () => {
    if (detachIfStaleWindow()) return
    if (checkingSession.value || (!accessToken.value && !platformToken.value)) return
    const now = Date.now()
    if (now - lastFocusCheckAt < 5000) return
    lastFocusCheckAt = now
    void verifyActiveSession()
  }

  const onVisibilityChange = () => {
    if (boundDocument && boundDocument.visibilityState === 'visible') {
      triggerCheck()
    }
  }

  const onSessionUpdated = () => {
    if (detachIfStaleWindow()) return
    sessionNotice.value = ''
    scheduleJwtRefresh()
  }

  const detachIfStaleWindow = (): boolean => {
    if (typeof window !== 'undefined' && window === boundWindow) return false
    if (heartbeatTimer !== null) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    clearJwtRefreshTimer()
    boundWindow.removeEventListener('aiconnect:session', onSessionUpdated)
    boundWindow.removeEventListener('focus', triggerCheck)
    if (boundDocument && typeof boundDocument.removeEventListener === 'function') {
      boundDocument.removeEventListener('visibilitychange', onVisibilityChange)
    }
    return true
  }

  boundWindow.addEventListener('aiconnect:session', onSessionUpdated)
  boundWindow.addEventListener('focus', triggerCheck)
  if (boundDocument && typeof boundDocument.addEventListener === 'function') {
    boundDocument.addEventListener('visibilitychange', onVisibilityChange)
  }

  heartbeatTimer = setInterval(() => {
    if (detachIfStaleWindow()) return
    if (!checkingSession.value && (accessToken.value || platformToken.value)) {
      void verifyActiveSession()
    }
  }, 30_000)
  ;(heartbeatTimer as { unref?: () => void }).unref?.()
}

async function restoreSession() {
  applyPreferences()
  armSessionMonitor()
  if (registerRoute.value.isRegister) {
    clearSession()
    checkingSession.value = false
    return
  }
  if (platformToken.value) {
    try {
      await verifyActiveSession()
    } finally {
      checkingSession.value = false
    }
    return
  }
  if (
    accessToken.value
    && user.value
    && mfaState.value
    && typeof user.value.totpEnabled === 'boolean'
    && Boolean(user.value.accountTier)
  ) {
    try {
      await verifyActiveSession()
    } finally {
      checkingSession.value = false
    }
    return
  }
  clearSession()
  try {
    acceptSession(await refreshAuthentication())
    sessionNotice.value = ''
    scheduleJwtRefresh()
  } catch {
    clearSession()
  } finally {
    checkingSession.value = false
  }
}

onMounted(() => {
  captureAndScrubInviteCode(currentHash.value)
  window.addEventListener('aiconnect:session', onSessionEvent)
  window.addEventListener('aiconnect:mfa-required', onMfaRequiredEvent)
  window.addEventListener('aiconnect:session-expired', onSessionExpiredEvent)
  window.addEventListener('hashchange', onHashChange)
  void restoreSession()
})

onBeforeUnmount(() => {
  window.removeEventListener('aiconnect:session', onSessionEvent)
  window.removeEventListener('aiconnect:mfa-required', onMfaRequiredEvent)
  window.removeEventListener('aiconnect:session-expired', onSessionExpiredEvent)
  window.removeEventListener('hashchange', onHashChange)
})
</script>

<template>
  <div v-if="activeView === 'splash'" class="session-splash">
    <div class="splash-mark"><span></span><span></span><span></span></div>
    <strong>AiConnect</strong><small>보안 세션을 확인하고 있습니다.</small>
  </div>
  <ConsoleShell
    v-else-if="activeView === 'console'"
    :key="platformToken ? `platform:${platformSessionVersion}` : `${user?.id ?? 'guest'}:${mfaState}`"
    :user="user"
    :platform-token-session="Boolean(platformToken)"
    :theme="theme"
    :font-scale="fontScale"
    @logout="signOut"
    @toggle-theme="toggleTheme"
    @font-scale-changed="setFontScale"
  />
  <TotpOnboardingView
    v-else-if="activeView === 'totp-onboarding'"
    :access-token="accessToken"
    :user="user"
    :theme="theme"
    @completed="onTotpEnrollmentCompleted"
    @logout="signOut"
    @toggle-theme="toggleTheme"
  />
  <InviteRegistrationView
    v-else-if="activeView === 'invite-register'"
    :initial-code="pendingInviteCode || registerRoute.code"
    :theme="theme"
    @registered="onInviteRegistered"
    @back-to-login="backToLoginFromRegister"
    @toggle-theme="toggleTheme"
  />
  <LoginView
    v-else
    :theme="theme"
    :mfa-required="activeView === 'login-mfa'"
    :pending-access-token="accessToken"
    :pending-user="user"
    :session-notice="sessionNotice"
    @authenticated="acceptSession"
    @platform-token="acceptPlatformToken"
    @toggle-theme="toggleTheme"
    @open-register="openRegisterFromLogin"
    @cancel-mfa="signOut"
  />
</template>
