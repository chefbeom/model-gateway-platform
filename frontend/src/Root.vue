<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import ConsoleShell from './ConsoleShell.vue'
import LoginView from './LoginView.vue'
import { logout, refreshAuthentication, type Session, type User } from './api'

type Theme = 'dark' | 'light'
type FontScale = '100' | '115' | '125' | '135'

const checkingSession = ref(true)
const user = ref<User | null>(readStoredUser())
const accessToken = ref(sessionStorage.getItem('aiconnect.accessToken') ?? '')
const platformToken = ref(sessionStorage.getItem('aiconnect.platformToken') ?? '')
const theme = ref<Theme>(initialTheme())
const fontScale = ref<FontScale>(initialFontScale())

function readStoredUser(): User | null {
  try { return JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null') as User | null }
  catch { return null }
}

function initialTheme(): Theme {
  const stored = localStorage.getItem('aiconnect.theme')
  if (stored === 'light' || stored === 'dark') return stored
  return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark'
}

function initialFontScale(): FontScale {
  const stored = localStorage.getItem('aiconnect.fontScale')
  return stored === '100' || stored === '115' || stored === '125' || stored === '135'
    ? stored
    : '115'
}

function applyPreferences() {
  document.documentElement.dataset.theme = theme.value
  document.documentElement.dataset.fontScale = fontScale.value
  document.documentElement.style.colorScheme = theme.value
  localStorage.setItem('aiconnect.theme', theme.value)
  localStorage.setItem('aiconnect.fontScale', fontScale.value)
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  applyPreferences()
}

function setFontScale(value: FontScale) {
  fontScale.value = value
  applyPreferences()
}

function acceptSession(session: Session) {
  accessToken.value = session.accessToken
  user.value = session.user
  platformToken.value = ''
  sessionStorage.setItem('aiconnect.accessToken', session.accessToken)
  sessionStorage.setItem('aiconnect.user', JSON.stringify(session.user))
  sessionStorage.removeItem('aiconnect.platformToken')
}

function acceptPlatformToken(token: string) {
  platformToken.value = token
  accessToken.value = ''
  user.value = null
  sessionStorage.setItem('aiconnect.platformToken', token)
  sessionStorage.removeItem('aiconnect.accessToken')
  sessionStorage.removeItem('aiconnect.user')
}

function clearSession() {
  accessToken.value = ''
  platformToken.value = ''
  user.value = null
  sessionStorage.removeItem('aiconnect.accessToken')
  sessionStorage.removeItem('aiconnect.user')
  sessionStorage.removeItem('aiconnect.platformToken')
}

async function signOut() {
  try { if (accessToken.value) await logout() }
  finally {
    clearSession()
    window.history.replaceState(null, '', window.location.pathname)
  }
}

function onSessionEvent(event: Event) {
  const session = (event as CustomEvent<Session>).detail
  if (session) acceptSession(session)
}

async function restoreSession() {
  applyPreferences()
  if (platformToken.value || (accessToken.value && user.value)) {
    checkingSession.value = false
    return
  }
  clearSession()
  try { acceptSession(await refreshAuthentication()) }
  catch { clearSession() }
  finally { checkingSession.value = false }
}

onMounted(() => {
  window.addEventListener('aiconnect:session', onSessionEvent)
  restoreSession()
})

onBeforeUnmount(() => window.removeEventListener('aiconnect:session', onSessionEvent))
</script>

<template>
  <div v-if="checkingSession" class="session-splash">
    <div class="splash-mark"><span></span><span></span><span></span></div>
    <strong>AICONNECT</strong><small>보안 세션을 확인하고 있습니다.</small>
  </div>
  <ConsoleShell
    v-else-if="accessToken || platformToken"
    :key="accessToken || platformToken"
    :user="user"
    :platform-token-session="Boolean(platformToken)"
    :theme="theme"
    :font-scale="fontScale"
    @logout="signOut"
    @toggle-theme="toggleTheme"
    @font-scale-changed="setFontScale"
  />
  <LoginView
    v-else
    :theme="theme"
    @authenticated="acceptSession"
    @platform-token="acceptPlatformToken"
    @toggle-theme="toggleTheme"
  />
</template>
