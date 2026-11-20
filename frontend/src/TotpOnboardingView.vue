<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import LoginAuthSteps from './LoginAuthSteps.vue'
import {
  accountTierLabel,
  enrollTotp,
  normalizeTotpDigits,
  persistSession,
  setupTotp,
  type Session,
  type TotpSetup,
  type User
} from './api'
import { copyText } from './clipboard'
import { buildQrSvgModel } from './qrCode'

const props = defineProps<{
  accessToken: string
  user: User | null
  theme: 'dark' | 'light'
}>()

const emit = defineEmits<{
  completed: [session: Session]
  logout: []
  toggleTheme: []
}>()

const loadingSetup = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const setupData = ref<TotpSetup | null>(null)
const totpCode = ref('')
const enrolledSession = ref<Session | null>(null)
const recoveryCodes = ref<string[]>([])
const savedConfirmed = ref(false)

const qrModel = computed(() => {
  if (!setupData.value?.otpauthUri) return null
  return buildQrSvgModel(setupData.value.otpauthUri, {
    ecc: 'M',
    margin: 4,
    label: `${setupData.value.email} Google Authenticator QR 코드`
  })
})

const tierDisplay = computed(() => accountTierLabel(props.user?.accountTier, props.user?.platformAdmin))
const normalizedCode = computed(() => normalizeTotpDigits(totpCode.value))

async function loadSetup() {
  if (!props.accessToken) return
  loadingSetup.value = true
  error.value = ''
  notice.value = ''
  try {
    setupData.value = await setupTotp(props.accessToken)
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'TOTP 등록 정보를 불러오지 못했습니다.'
  } finally {
    loadingSetup.value = false
  }
}

async function copySecret() {
  if (!setupData.value?.secret) return
  error.value = ''
  try {
    await copyText(setupData.value.secret)
    notice.value = '수동 입력용 시크릿 키를 클립보드에 복사했습니다.'
  } catch {
    error.value = '시크릿 키를 복사하지 못했습니다. 표시된 키를 직접 복사하세요.'
  }
}

async function copyRecoveryCodes() {
  if (!recoveryCodes.value.length) return
  error.value = ''
  try {
    await copyText(recoveryCodes.value.join('\n'))
    notice.value = '복구 코드 8개를 클립보드에 복사했습니다.'
  } catch {
    error.value = '복구 코드를 복사하지 못했습니다. 표시된 8개 코드를 직접 저장하세요.'
  }
}

async function submitEnrollment() {
  if (normalizedCode.value.length !== 6) {
    error.value = 'Google Authenticator에 표시된 6자리 숫자 코드를 입력하세요.'
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await enrollTotp(props.accessToken, normalizedCode.value)
    enrolledSession.value = result.session
    recoveryCodes.value = result.recoveryCodes
    savedConfirmed.value = false
    totpCode.value = ''
    notice.value = 'Google Authenticator 등록이 완료되었습니다. 아래 복구 코드를 반드시 보관하세요.'
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'TOTP 코드 검증에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

function finishOnboarding() {
  if (!enrolledSession.value || !savedConfirmed.value) return
  const finalSession = persistSession(enrolledSession.value)
  emit('completed', finalSession)
}

watch(() => props.accessToken, nextToken => {
  if (nextToken && !enrolledSession.value) {
    void loadSetup()
  }
})

onMounted(() => {
  void loadSetup()
})
</script>

<template>
  <div class="login-page totp-onboarding-page">
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
      <div class="brand-mark large"><span></span><b>2FA</b></div>
      <p class="brand-overline">MANDATORY GOOGLE AUTHENTICATOR</p>
      <h1>콘솔 접근 전<br><em>2차 인증 등록</em>이<br>필수입니다.</h1>
      <p class="brand-copy">
        모든 계정은 Google Authenticator(TOTP) 등록을 마쳐야 관리 콘솔과 API 자원에 접근할 수 있습니다.
        QR 코드는 브라우저 내부에서 직접 SVG로 생성되며 외부 서버로 전송되지 않습니다.
      </p>
      <div class="login-signals">
        <div><span class="signal-dot online"></span><strong>Zero External QR</strong><small>브라우저 로컬 SVG 생성</small></div>
        <div><span class="signal-dot online"></span><strong>RFC 6238 TOTP</strong><small>6자리 · 30초 회전 코드</small></div>
        <div><span class="signal-dot"></span><strong>8 Recovery Codes</strong><small>기기 분실 대비 1회용 보관</small></div>
      </div>
    </section>

    <section class="login-card-wrap totp-card-wrap">
      <div class="login-card glass-panel totp-card">
        <LoginAuthSteps :step="2" :phase="enrolledSession ? 'recovery' : 'enroll'" />

        <div class="totp-account-bar">
          <div>
            <strong>{{ user?.email ?? setupData?.email ?? '사용자 계정' }}</strong>
            <small>계정 계층: {{ tierDisplay }}</small>
          </div>
          <span class="status-chip draining"><i></i>{{ enrolledSession ? '최종 승인 대기' : 'QR 2차 확인 필요' }}</span>
        </div>

        <template v-if="!enrolledSession">
          <div class="login-card-head">
            <p class="eyebrow">STEP 2-1 · QR 스캔 2차 확인</p>
            <h2>QR 등록 및 코드 검증</h2>
            <p>1단계 아이디·비밀번호 확인이 완료되었습니다. Google Authenticator 앱으로 아래 QR 코드를 스캔(또는 수동 키 입력)한 뒤 생성된 6자리 코드를 입력하세요.</p>
          </div>

          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <p v-if="notice" class="inline-alert" role="status">{{ notice }}</p>

          <div v-if="loadingSetup" class="totp-loading-box">
            <strong>QR 코드와 시크릿 키를 생성하고 있습니다…</strong>
            <small>브라우저 내에서 직접 암호화 키를 받아 SVG 매트릭스를 계산 중입니다.</small>
          </div>

          <template v-else-if="setupData && qrModel">
            <div class="qr-stage">
              <div class="qr-frame">
                <svg
                  class="qr-svg"
                  :viewBox="qrModel.viewBox"
                  role="img"
                  :aria-label="qrModel.label"
                  shape-rendering="crispEdges"
                >
                  <rect :width="qrModel.totalSize" :height="qrModel.totalSize" fill="#ffffff" />
                  <path :d="qrModel.pathD" fill="#111827" />
                </svg>
              </div>
              <div class="qr-meta">
                <span>ISSUER: <b>{{ setupData.issuer }}</b></span>
                <span>ACCOUNT: <b>{{ setupData.email }}</b></span>
                <small>카메라 인식이 어렵다면 아래 수동 시크릿 키를 Google Authenticator에 "설정 키 입력"으로 등록하세요.</small>
              </div>
            </div>

            <div class="secret-reveal totp-secret-box">
              <span>수동 입력용 시크릿 키 (BASE32)</span>
              <code>{{ setupData.secret }}</code>
              <div class="totp-secret-actions">
                <button type="button" class="secondary-button" @click="copySecret">시크릿 키 복사</button>
                <button type="button" class="ghost-button" :disabled="busy || loadingSetup" @click="loadSetup">새 키 다시 생성</button>
              </div>
            </div>

            <form class="login-form totp-verify-form" @submit.prevent="submitEnrollment">
              <label>
                6자리 인증 코드 (Google Authenticator)
                <input
                  v-model="totpCode"
                  class="otp-code-input"
                  type="text"
                  inputmode="numeric"
                  autocomplete="one-time-code"
                  maxlength="12"
                  placeholder="000000"
                  required
                />
              </label>
              <button
                class="primary-button login-submit"
                type="submit"
                :disabled="busy || normalizedCode.length !== 6"
              >
                <span>{{ busy ? 'QR 코드 확인 중…' : '2차 코드 확인 · 다음(최종 승인)' }}</span>
                <b>→</b>
              </button>
            </form>
          </template>

          <div v-else class="totp-retry-row">
            <button type="button" class="secondary-button" :disabled="loadingSetup" @click="loadSetup">TOTP 설정 다시 불러오기</button>
          </div>
        </template>

        <template v-else>
          <div class="login-card-head">
            <p class="eyebrow">STEP 2-2 · 복구 코드 보관 및 최종 승인</p>
            <h2>복구 코드 저장 후 최종 승인</h2>
            <p>QR 등록이 완료되었습니다. 아래 1회용 복구 코드 8개를 안전하게 저장한 뒤 최종 승인하여 콘솔로 이동하세요.</p>
          </div>

          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <p v-if="notice" class="inline-alert" role="status">{{ notice }}</p>

          <ol class="recovery-grid" aria-label="1회용 복구 코드 목록">
            <li v-for="code in recoveryCodes" :key="code">
              <code>{{ code }}</code>
            </li>
          </ol>

          <div class="totp-recovery-actions">
            <button type="button" class="secondary-button" @click="copyRecoveryCodes">복구 코드 8개 모두 복사</button>
          </div>

          <label class="totp-confirm-check">
            <input v-model="savedConfirmed" type="checkbox" />
            <span>복구 코드 8개를 안전한 곳에 저장했으며, 이후 다시 조회할 수 없음을 확인했습니다.</span>
          </label>

          <button
            type="button"
            class="primary-button login-submit"
            :disabled="!savedConfirmed"
            @click="finishOnboarding"
          >
            <span>최종 승인 · 콘솔 진입</span>
            <b>→</b>
          </button>
        </template>

        <button class="emergency-link" type="button" @click="emit('logout')">1단계로 돌아가기 (다른 계정 로그인)</button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.totp-onboarding-page {
  grid-template-columns: minmax(0, 1fr) minmax(400px, 560px);
}
.totp-card-wrap {
  width: min(560px, 100%);
}
.totp-card {
  display: grid;
  gap: 18px;
}
.totp-account-bar {
  padding: 12px 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--surface-2);
}
.totp-account-bar > div {
  min-width: 0;
  display: grid;
  gap: 3px;
}
.totp-account-bar strong {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.totp-account-bar small {
  color: var(--muted);
  font-size: 11px;
}
.totp-loading-box {
  padding: 28px 18px;
  display: grid;
  gap: 6px;
  text-align: center;
  border: 1px dashed var(--border-strong);
  border-radius: 16px;
  background: var(--surface-2);
}
.totp-loading-box small {
  color: var(--muted);
  font-size: 12px;
}
.qr-stage {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 18px;
  align-items: center;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 16px;
  background: var(--surface-2);
}
.qr-frame {
  width: 176px;
  height: 176px;
  padding: 8px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.16);
}
.qr-svg {
  width: 100%;
  height: 100%;
  display: block;
}
.qr-meta {
  display: grid;
  gap: 7px;
  font-size: 12px;
  color: var(--text-soft);
}
.qr-meta b {
  color: var(--text);
  word-break: break-all;
}
.qr-meta small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.5;
}
.totp-secret-box {
  grid-template-columns: 1fr;
}
.totp-secret-box code {
  letter-spacing: 0.12em;
  font-size: 13px;
  word-break: break-all;
}
.totp-secret-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.totp-verify-form {
  margin-top: 4px;
}
.recovery-grid {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.recovery-grid li {
  padding: 11px 12px;
  border: 1px solid var(--accent-border);
  border-radius: 11px;
  background: var(--surface-2);
  text-align: center;
}
.recovery-grid code {
  font-size: 13px;
  font-weight: 700;
  color: var(--accent-strong);
  letter-spacing: 0.08em;
}
.totp-recovery-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.totp-confirm-check {
  padding: 13px 14px;
  display: grid;
  grid-template-columns: 18px 1fr;
  gap: 10px;
  align-items: start;
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--surface-2);
  font-size: 12px;
  color: var(--text-soft);
  line-height: 1.5;
  cursor: pointer;
}
.totp-confirm-check input {
  width: 16px;
  min-height: auto;
  margin-top: 2px;
  accent-color: var(--accent-strong);
}
@media (max-width: 1020px) {
  .totp-onboarding-page {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .qr-stage {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
  }
  .recovery-grid {
    grid-template-columns: 1fr;
  }
}
</style>
