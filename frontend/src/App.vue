<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { adminFetch, authenticate, logout, refreshAuthentication, type AdminAuth, type Deployment, type Endpoint, type Session, type User } from './api'

const accessToken = ref(sessionStorage.getItem('aiconnect.accessToken') ?? '')
const user = ref<User | null>(JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null'))
const platformToken = ref(sessionStorage.getItem('aiconnect.platformToken') ?? '')
const email = ref('')
const password = ref('')
const authMode = ref<'login' | 'bootstrap'>('login')
const endpoints = ref<Endpoint[]>([])
const deployments = ref<Record<string, Deployment[]>>({})
const selectedEndpoint = ref<string | null>(null)
const busy = ref(false)
const message = ref('로그인하거나 플랫폼 토큰을 입력해 제어 계층에 연결하세요.')
const currentDeployments = computed(() => selectedEndpoint.value ? deployments.value[selectedEndpoint.value] ?? [] : [])
const auth = computed<AdminAuth>(() => accessToken.value ? { accessToken: accessToken.value } : { platformToken: platformToken.value })
const authorized = computed(() => Boolean(accessToken.value || platformToken.value))

function applySession(session: Session) {
  accessToken.value = session.accessToken
  user.value = session.user
  platformToken.value = ''
}
function sessionChanged(event: Event) {
  applySession((event as CustomEvent<Session>).detail)
}
async function signIn() {
  busy.value = true
  try {
    const session = await authenticate(authMode.value, email.value, password.value)
    applySession(session)
    password.value = ''
    message.value = `${session.user.email} 계정으로 인증되었습니다.`
    await refresh()
  } catch (error) { message.value = error instanceof Error ? error.message : '인증에 실패했습니다.' }
  finally { busy.value = false }
}
async function usePlatformToken() {
  sessionStorage.setItem('aiconnect.platformToken', platformToken.value)
  accessToken.value = ''
  user.value = null
  sessionStorage.removeItem('aiconnect.accessToken')
  sessionStorage.removeItem('aiconnect.user')
  await refresh()
}
async function signOut() {
  try {
    if (accessToken.value) await logout()
  } finally {
    accessToken.value = ''
    platformToken.value = ''
    user.value = null
    endpoints.value = []
    deployments.value = {}
    selectedEndpoint.value = null
    sessionStorage.removeItem('aiconnect.accessToken')
    sessionStorage.removeItem('aiconnect.user')
    sessionStorage.removeItem('aiconnect.platformToken')
    message.value = '관리자 세션을 종료했습니다.'
  }
}
async function refresh() {
  if (!authorized.value) return
  busy.value = true
  try {
    endpoints.value = await adminFetch<Endpoint[]>('/api/admin/runtime-endpoints', auth.value)
    if (selectedEndpoint.value && !endpoints.value.some(item => item.id === selectedEndpoint.value)) selectedEndpoint.value = null
    if (!selectedEndpoint.value && endpoints.value.length) selectedEndpoint.value = endpoints.value[0].id
    if (selectedEndpoint.value) await loadDeployments(selectedEndpoint.value)
    message.value = `${endpoints.value.length}개의 런타임 엔드포인트를 불러왔습니다.`
  } catch (error) { message.value = error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.' }
  finally { busy.value = false }
}
async function loadDeployments(endpointId: string) {
  selectedEndpoint.value = endpointId
  deployments.value[endpointId] = await adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpointId}/deployments`, auth.value)
}
async function probe(endpoint: Endpoint) {
  busy.value = true
  try {
    const result = await adminFetch<{ reachable: boolean, modelIds: string[] }>(`/api/admin/runtime-endpoints/${endpoint.id}/probe`, auth.value, { method: 'POST' })
    message.value = result.reachable ? `연결 성공: ${result.modelIds.length}개 모델 발견` : '연결할 수 없습니다. Tailscale, 포트, LM Studio 토큰을 확인하세요.'
    await refresh()
  } catch (error) { message.value = error instanceof Error ? error.message : 'Probe 실패' }
  finally { busy.value = false }
}
async function sync(endpoint: Endpoint) {
  busy.value = true
  try {
    const created = await adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpoint.id}/sync-models`, auth.value, { method: 'POST' })
    message.value = `${created.length}개의 모델 배포를 동기화했습니다.`
    await loadDeployments(endpoint.id)
  } catch (error) { message.value = error instanceof Error ? error.message : '동기화 실패' }
  finally { busy.value = false }
}
async function configure(deployment: Deployment) {
  busy.value = true
  try {
    await adminFetch(`/api/admin/model-deployments/${deployment.id}`, auth.value, {
      method: 'PATCH',
      body: JSON.stringify({ compatibilityKey: deployment.compatibilityKey, enabled: deployment.enabled, maxConcurrency: deployment.maxConcurrency, capabilityOverridesJson: deployment.capabilityOverridesJson ?? '[]' })
    })
    message.value = `${deployment.displayName} 배포 정책을 저장했습니다.`
    await loadDeployments(deployment.runtimeEndpointId)
  } catch (error) { message.value = error instanceof Error ? error.message : '배포 정책 저장 실패' }
  finally { busy.value = false }
}

onMounted(async () => {
  window.addEventListener('aiconnect:session', sessionChanged)
  if (authorized.value) {
    await refresh()
    return
  }
  try {
    const session = await refreshAuthentication()
    applySession(session)
    message.value = `${session.user.email} 세션을 복원했습니다.`
    await refresh()
  } catch {
    // No refresh cookie is normal for a first visit or an expired session.
  }
})
onUnmounted(() => window.removeEventListener('aiconnect:session', sessionChanged))
</script>

<template>
  <main>
    <header>
      <div><p class="eyebrow">AICONNECT</p><h1>LLM Control Plane</h1><p>GPU 사양이 아닌 런타임 엔드포인트와 모델 배포를 관리합니다.</p></div>
      <div class="token-box">
        <template v-if="user"><strong>{{ user.email }}</strong><small>{{ user.platformAdmin ? 'Platform Administrator' : 'Organization Administrator' }}</small><button :disabled="busy" @click="signOut">로그아웃</button></template>
        <template v-else-if="platformToken && authorized"><strong>비상 관리자 세션</strong><small>이 토큰은 현재 탭을 닫을 때 삭제됩니다.</small><button :disabled="busy" @click="signOut">연결 해제</button></template>
        <template v-else>
          <label><input v-model="authMode" type="radio" value="login" /> 로그인</label><label><input v-model="authMode" type="radio" value="bootstrap" /> 첫 관리자 생성</label>
          <input v-model="email" type="email" placeholder="관리자 이메일" /><input v-model="password" type="password" placeholder="비밀번호 (12자 이상)" @keyup.enter="signIn" />
          <button :disabled="busy || !email || password.length < 12" @click="signIn">{{ authMode === 'login' ? '로그인' : '첫 관리자 생성' }}</button>
          <small>또는 비상용 플랫폼 토큰</small><input v-model="platformToken" type="password" placeholder="ADMIN_API_TOKEN" @keyup.enter="usePlatformToken" /><button :disabled="busy || !platformToken" @click="usePlatformToken">토큰으로 연결</button>
        </template>
      </div>
    </header>
    <p class="notice" role="status">{{ message }}</p>
    <section class="grid">
      <article>
        <div class="section-heading"><h2>Runtime Endpoints</h2><span>{{ endpoints.length }}</span></div>
        <p v-if="!authorized" class="empty">인증 후 등록된 Runtime Endpoint를 확인할 수 있습니다.</p>
        <p v-else-if="!endpoints.length" class="empty">등록된 엔드포인트가 없습니다. Node와 LM Studio Endpoint를 먼저 등록하세요.</p>
        <div v-for="endpoint in endpoints" :key="endpoint.id" class="endpoint" :class="{ selected: selectedEndpoint === endpoint.id }" @click="loadDeployments(endpoint.id)">
          <div><strong>{{ endpoint.baseUrl }}</strong><small>{{ endpoint.runtimeType }} · 마지막 점검 {{ endpoint.lastCheckedAt ? new Date(endpoint.lastCheckedAt).toLocaleString() : '없음' }}</small></div>
          <span class="status" :class="endpoint.healthStatus.toLowerCase()">{{ endpoint.healthStatus }}</span>
          <div class="actions"><button @click.stop="probe(endpoint)">Probe</button><button @click.stop="sync(endpoint)">모델 동기화</button></div>
        </div>
      </article>
      <article>
        <div class="section-heading"><h2>Selected Deployments</h2><span>{{ currentDeployments.length }}</span></div>
        <p v-if="!selectedEndpoint" class="empty">왼쪽에서 Runtime Endpoint를 선택하세요.</p>
        <p v-else-if="!currentDeployments.length" class="empty">발견된 모델이 없습니다. 모델 동기화를 실행하세요.</p>
        <div v-for="deployment in currentDeployments" :key="deployment.id" class="deployment">
          <div><strong>{{ deployment.displayName }}</strong><small>{{ deployment.providerModelId }}</small><small>{{ deployment.modelFamily ?? 'unknown' }} · {{ deployment.quantization ?? 'unknown' }} · context {{ deployment.contextLength ?? '-' }}</small></div>
          <span class="status" :class="deployment.healthStatus.toLowerCase()">{{ deployment.healthStatus }}</span>
          <label>호환 키<input v-model="deployment.compatibilityKey" /></label>
          <label>최대 동시 요청<input v-model.number="deployment.maxConcurrency" type="number" min="1" /></label>
          <label>관리자 검증 기능<input v-model="deployment.capabilityOverridesJson" placeholder='["STRUCTURED_OUTPUT"]' /></label>
          <label><input v-model="deployment.enabled" type="checkbox" /> 라우팅 활성화</label>
          <small>자동 발견 기능 {{ deployment.capabilitiesJson }}</small>
          <button :disabled="busy" @click="configure(deployment)">배포 정책 저장</button>
        </div>
      </article>
    </section>
  </main>
</template>
