<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import ConsoleShell from './ConsoleShell.vue'
import InviteRegistrationView from './InviteRegistrationView.vue'
import LoginView from './LoginView.vue'
import TotpOnboardingView from './TotpOnboardingView.vue'
import { logout, normalizeSession, refreshAuthentication, type MfaState, type Session, type User } from './api'

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
const currentHash = ref(typeof window !== 'undefined' ? window.location?.hash ?? '' : '')
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

function clearSession() {
  accessToken.value = ''
  platformToken.value = ''
  user.value = null
  mfaState.value = ''
  if (typeof sessionStorage !== 'undefined') {
    sessionStorage.removeItem('aiconnect.accessToken')
    sessionStorage.removeItem('aiconnect.user')
    sessionStorage.removeItem('aiconnect.mfaState')
    sessionStorage.removeItem('aiconnect.platformToken')
  }
}

async function signOut() {
  try {
    if (accessToken.value) await logout()
  } finally {
    clearSession()
    setLocationHash('')
  }
}

function onInviteRegistered(session: Session) {
  acceptSession(session)
  setLocationHash('')
}

function onTotpEnrollmentCompleted(session: Session) {
  acceptSession(session)
}

async function openRegisterFromLogin() {
  try {
    if (accessToken.value) await logout()
  } catch {
    // Ignore logout failures while switching to invite registration.
  } finally {
    clearSession()
    setLocationHash('#register')
  }
}

function backToLoginFromRegister() {
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

function onSessionExpiredEvent() {
  clearSession()
}

function onHashChange() {
  currentHash.value = window.location?.hash ?? ''
}

async function restoreSession() {
  applyPreferences()
  if (platformToken.value) {
    checkingSession.value = false
    return
  }
  if (
    accessToken.value
    && user.value
    && mfaState.value
    && typeof user.value.totpEnabled === 'boolean'
    && Boolean(user.value.accountTier)
  ) {
    checkingSession.value = false
    return
  }
  if (registerRoute.value.isRegister) {
    clearSession()
    checkingSession.value = false
    return
  }
  clearSession()
  try {
    acceptSession(await refreshAuthentication())
  } catch {
    clearSession()
  } finally {
    checkingSession.value = false
  }
}

onMounted(() => {
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
    <strong>AICONNECT</strong><small>보안 세션을 확인하고 있습니다.</small>
  </div>
  <ConsoleShell
    v-else-if="activeView === 'console'"
    :key="accessToken || platformToken"
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
    :initial-code="registerRoute.code"
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
    @authenticated="acceptSession"
    @platform-token="acceptPlatformToken"
    @toggle-theme="toggleTheme"
    @open-register="openRegisterFromLogin"
    @cancel-mfa="signOut"
  />
</template>
