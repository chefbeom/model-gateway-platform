<script setup lang="ts">
import { computed, ref } from 'vue'
import BaseModal from './BaseModal.vue'
import LoginAuthSteps from './LoginAuthSteps.vue'
import { accountTierLabel, authenticate, normalizeTotpDigits, verifyTotp, type Session, type User } from './api'

const props = defineProps<{
  theme: 'dark' | 'light'
  mfaRequired?: boolean
  pendingAccessToken?: string
  pendingUser?: User | null
}>()

const emit = defineEmits<{
  authenticated: [session: Session]
  platformToken: [token: string]
  toggleTheme: []
  openRegister: []
  cancelMfa: []
}>()

const mode = ref<'login' | 'bootstrap'>('login')
const email = ref('')
const password = ref('')
const busy = ref(false)
const message = ref('')
const emergencyOpen = ref(false)
const emergencyToken = ref('')
const localMfaSession = ref<Session | null>(null)
const verificationMode = ref<'totp' | 'recovery'>('totp')
const mfaCode = ref('')

const inMfaStep = computed(() => Boolean(props.mfaRequired || localMfaSession.value))
const mfaAccessToken = computed(() => props.pendingAccessToken || localMfaSession.value?.accessToken || '')
const mfaUser = computed(() => props.pendingUser ?? localMfaSession.value?.user ?? null)
const mfaEmail = computed(() => mfaUser.value?.email || email.value)
const normalizedMfaOtp = computed(() => normalizeTotpDigits(mfaCode.value))
const submittedMfaCode = computed(() =>
  verificationMode.value === 'totp' ? normalizedMfaOtp.value : mfaCode.value.trim()
)
const canSubmitMfa = computed(() => {
  if (verificationMode.value === 'totp') {
    return normalizedMfaOtp.value.length === 6
  }
  return submittedMfaCode.value.length >= 6
})

async function submit() {
  busy.value = true
  message.value = ''
  try {
    const session = await authenticate(mode.value, email.value, password.value)
    password.value = ''
    if (session.mfaState === 'VERIFICATION_REQUIRED') {
      localMfaSession.value = session
      mfaCode.value = ''
      verificationMode.value = 'totp'
    }
    emit('authenticated', session)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '로그인할 수 없습니다.'
  } finally {
    busy.value = false
  }
}

async function submitMfaVerification() {
  if (!mfaAccessToken.value || !canSubmitMfa.value) return
  busy.value = true
  message.value = ''
  try {
    const session = await verifyTotp(mfaAccessToken.value, submittedMfaCode.value)
    localMfaSession.value = null
    mfaCode.value = ''
    emit('authenticated', session)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '2차 인증 코드가 올바르지 않습니다.'
  } finally {
    busy.value = false
  }
}

function toggleVerificationMode() {
  verificationMode.value = verificationMode.value === 'totp' ? 'recovery' : 'totp'
  mfaCode.value = ''
  message.value = ''
}

function cancelMfaStep() {
  localMfaSession.value = null
  mfaCode.value = ''
  message.value = ''
  emit('cancelMfa')
}

function switchAuthMode() {
  mode.value = mode.value === 'login' ? 'bootstrap' : 'login'
  message.value = ''
}

function useEmergencyToken() {
  if (!emergencyToken.value.trim()) return
  emit('platformToken', emergencyToken.value.trim())
  emergencyToken.value = ''
  emergencyOpen.value = false
}
</script>

<template>
  <div class="login-page">
    <div class="login-orb orb-one"></div>
    <div class="login-orb orb-two"></div>
    <button
      class="theme-fab"
      type="button"
      :aria-label="theme === 'dark' ? '라이트 모드' : '다크 모드'"
      @click="emit('toggleTheme')"
    >
      <span>{{ theme === 'dark' ? '☼' : '◐' }}</span>
    </button>
    <section class="login-brand">
      <div class="brand-mark large"><span></span><b>AI</b></div>
      <p class="brand-overline">LOCAL INTELLIGENCE FABRIC</p>
      <h1>모든 AI 연산을<br><em>하나의 흐름</em>으로.</h1>
      <p class="brand-copy">서로 다른 GPU와 LM Studio 런타임을 연결하고, 논리 모델 하나로 안전하게 제어하세요.</p>
      <div class="login-signals">
        <div><span class="signal-dot online"></span><strong>OpenAI Compatible</strong><small>표준 API 인터페이스</small></div>
        <div><span class="signal-dot"></span><strong>Adaptive Routing</strong><small>자동 장애 전환</small></div>
        <div><span class="signal-dot online"></span><strong>2-Step QR Auth</strong><small>1. ID/PW 로그인 → 2. QR 2차 승인</small></div>
      </div>
    </section>
    <section class="login-card-wrap">
      <div class="login-card glass-panel">
        <LoginAuthSteps :step="inMfaStep ? 2 : 1" :phase="inMfaStep ? 'verify' : 'credentials'" />

        <template v-if="inMfaStep">
          <div class="login-card-head">
            <p class="eyebrow">STEP 2 · QR 2차 확인 및 최종 승인</p>
            <h2>2차 확인 및 최종 승인</h2>
            <p>
              <strong>{{ mfaEmail }}</strong>
              <span v-if="mfaUser"> ({{ accountTierLabel(mfaUser.accountTier, mfaUser.platformAdmin) }})</span>
              의 1차 로그인이 완료되었습니다.
              {{
                verificationMode === 'totp'
                  ? '최초 1회 QR로 연결한 Google Authenticator 앱의 6자리 코드를 입력해 최종 승인하세요.'
                  : '보관해 둔 1회용 복구 코드를 입력해 최종 승인하세요.'
              }}
            </p>
          </div>
          <form class="login-form" @submit.prevent="submitMfaVerification">
            <label v-if="verificationMode === 'totp'">
              6자리 Google Authenticator 코드
              <input
                v-model="mfaCode"
                class="otp-code-input"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="12"
                placeholder="000000"
                required
              />
            </label>
            <label v-else>
              1회용 복구 코드
              <input
                v-model.trim="mfaCode"
                type="text"
                autocomplete="off"
                placeholder="XXXX-XXXX-XXXX"
                required
              />
            </label>
            <p v-if="message" class="form-error" role="alert">{{ message }}</p>
            <button class="primary-button login-submit" :disabled="busy || !canSubmitMfa" type="submit">
              <span>{{ busy ? '최종 승인 확인 중…' : '최종 승인 · 콘솔 진입' }}</span>
              <b>→</b>
            </button>
          </form>
          <div class="login-switch">
            <button class="text-button" type="button" @click="toggleVerificationMode">
              {{ verificationMode === 'totp' ? '1회용 복구 코드로 승인하기' : '6자리 앱 코드로 승인하기' }}
            </button>
          </div>
          <div class="login-switch">
            <button class="text-button" type="button" @click="cancelMfaStep">1단계로 돌아가기 (다른 계정 로그인)</button>
          </div>
        </template>

        <template v-else>
          <div class="login-card-head">
            <p class="eyebrow">STEP 1 · 아이디 · 비밀번호 로그인</p>
            <h2>{{ mode === 'login' ? '1차 로그인 진행' : '첫 관리자를 생성합니다' }}</h2>
            <p>{{ mode === 'login' ? '아이디(이메일)와 비밀번호를 확인한 뒤 2단계 QR 2차 확인·최종 승인으로 이어집니다.' : '비어 있는 설치에서 한 번만 실행할 수 있습니다.' }}</p>
          </div>
          <form class="login-form" @submit.prevent="submit">
            <label>이메일 주소<input v-model.trim="email" type="email" autocomplete="username" placeholder="admin@example.com" required /></label>
            <label>비밀번호<input v-model="password" type="password" autocomplete="current-password" placeholder="12자 이상 입력" minlength="12" required /></label>
            <p v-if="message" class="form-error" role="alert">{{ message }}</p>
            <button class="primary-button login-submit" :disabled="busy || !email || password.length < 12" type="submit">
              <span>{{ busy ? '1차 확인 중…' : mode === 'login' ? '1차 로그인 · 2차 확인으로 이동' : '관리자 생성 · 2차 확인으로 이동' }}</span>
              <b>→</b>
            </button>
          </form>
          <div class="login-switch">
            <span>초대 코드 또는 링크를 받으셨나요?</span>
            <button class="text-button" type="button" @click="emit('openRegister')">초대 코드로 계정 생성</button>
          </div>
          <div class="login-switch">
            <span>{{ mode === 'login' ? '새 설치인가요?' : '이미 계정이 있나요?' }}</span>
            <button class="text-button" type="button" @click="switchAuthMode">{{ mode === 'login' ? '첫 관리자 생성' : '로그인으로 돌아가기' }}</button>
          </div>
          <button class="emergency-link" type="button" @click="emergencyOpen = true">비상용 플랫폼 토큰으로 연결</button>
        </template>
      </div>
      <p class="login-foot">요청 데이터는 Gateway를 통과하지만 기본 정책에서는 원문을 저장하지 않습니다.</p>
    </section>
    <BaseModal :open="emergencyOpen" title="비상 관리자 연결" description="일반 로그인 장애 시에만 사용하는 Break-glass 접근입니다." size="sm" @close="emergencyOpen = false">
      <label class="field">ADMIN_API_TOKEN<input v-model="emergencyToken" type="password" autocomplete="off" placeholder="비상용 토큰 입력" @keyup.enter="useEmergencyToken" /></label>
      <template #footer><button class="secondary-button" type="button" @click="emergencyOpen = false">취소</button><button class="primary-button" type="button" :disabled="!emergencyToken" @click="useEmergencyToken">연결</button></template>
    </BaseModal>
  </div>
</template>
