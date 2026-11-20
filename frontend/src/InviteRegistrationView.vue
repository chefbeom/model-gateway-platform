<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  acceptAccountInvitation,
  accountTierLabel,
  inspectAccountInvitation,
  type InvitationInspection,
  type Session
} from './api'
import { formatDateTime } from './dateTime'

const props = defineProps<{
  initialCode?: string
  theme: 'dark' | 'light'
}>()

const emit = defineEmits<{
  registered: [session: Session]
  backToLogin: []
  toggleTheme: []
}>()

const code = ref((props.initialCode ?? '').trim())
const inspecting = ref(false)
const busy = ref(false)
const error = ref('')
const inspection = ref<InvitationInspection | null>(null)
const email = ref('')
const password = ref('')
const passwordConfirm = ref('')

const canSubmit = computed(() =>
  Boolean(inspection.value?.valid)
  && Boolean(email.value.trim())
  && password.value.length >= 12
  && password.value === passwordConfirm.value
  && !busy.value
)

const passwordMismatch = computed(() =>
  passwordConfirm.value.length > 0 && password.value !== passwordConfirm.value
)

async function inspectCode() {
  const trimmed = code.value.trim()
  if (!trimmed) {
    error.value = '초대 코드를 입력하세요.'
    inspection.value = null
    return
  }
  inspecting.value = true
  error.value = ''
  try {
    const result = await inspectAccountInvitation(trimmed)
    inspection.value = result
    if (!result.valid) {
      error.value = '유효하지 않거나 이미 사용·회수·만료된 초대 코드입니다.'
      return
    }
    if (result.expectedEmail) {
      email.value = result.expectedEmail
    }
  } catch (err) {
    inspection.value = null
    error.value = err instanceof Error ? err.message : '초대 코드를 확인할 수 없습니다.'
  } finally {
    inspecting.value = false
  }
}

function onCodeInput() {
  if (inspection.value) {
    inspection.value = null
  }
}

async function submitRegistration() {
  if (!inspection.value?.valid) {
    error.value = '먼저 유효한 초대 코드를 확인하세요.'
    return
  }
  if (password.value !== passwordConfirm.value) {
    error.value = '비밀번호와 비밀번호 확인이 일치하지 않습니다.'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const session = await acceptAccountInvitation({
      code: code.value.trim(),
      email: email.value.trim(),
      password: password.value
    })
    password.value = ''
    passwordConfirm.value = ''
    emit('registered', session)
  } catch (err) {
    error.value = err instanceof Error ? err.message : '계정 생성에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

watch(() => props.initialCode, nextCode => {
  const normalized = (nextCode ?? '').trim()
  if (normalized && normalized !== code.value.trim()) {
    code.value = normalized
    void inspectCode()
  }
})

onMounted(() => {
  if (code.value) {
    void inspectCode()
  }
})
</script>

<template>
  <div class="login-page invite-register-page">
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
      <p class="brand-overline">INVITED ACCOUNT PROVISIONING</p>
      <h1>초대 코드로<br><em>새 계정</em>을<br>시작하세요.</h1>
      <p class="brand-copy">
        상위 관리자에게 전달받은 1회용 초대 코드 또는 가입 링크로 계정을 생성합니다.
        계정 생성 직후 Google Authenticator 2차 인증 등록을 완료해야 콘솔을 사용할 수 있습니다.
      </p>
      <div class="login-signals">
        <div><span class="signal-dot online"></span><strong>3-Tier Hierarchy</strong><small>시스템 관리자 · 총괄 · 기본</small></div>
        <div><span class="signal-dot online"></span><strong>Scoped Access</strong><small>조직·팀·프로젝트 자동 배정</small></div>
        <div><span class="signal-dot"></span><strong>Mandatory TOTP</strong><small>가입 직후 2차 인증 등록</small></div>
      </div>
    </section>

    <section class="login-card-wrap invite-card-wrap">
      <div class="login-card glass-panel invite-card">
        <div class="login-card-head">
          <p class="eyebrow">INVITATION REGISTRATION</p>
          <h2>초대 계정 생성</h2>
          <p>초대 코드에 연결된 계층·소속 정보를 확인한 뒤 사용할 이메일과 비밀번호를 설정하세요.</p>
        </div>

        <form class="invite-code-form" @submit.prevent="inspectCode">
          <label class="field">
            1회용 초대 코드
            <div class="invite-code-row">
              <input
                v-model.trim="code"
                type="text"
                autocomplete="off"
                placeholder="초대 코드 입력"
                required
                @input="onCodeInput"
              />
              <button class="secondary-button" type="submit" :disabled="inspecting || !code.trim()">
                {{ inspecting ? '확인 중…' : '코드 확인' }}
              </button>
            </div>
          </label>
        </form>

        <p v-if="error" class="form-error" role="alert">{{ error }}</p>

        <div v-if="inspection?.valid" class="invite-summary" role="status">
          <div class="invite-summary-head">
            <span class="status-chip healthy"><i></i>유효한 초대</span>
            <strong>{{ accountTierLabel(inspection.targetTier) }}</strong>
          </div>
          <dl class="invite-meta-grid">
            <div><dt>부여 계층</dt><dd>{{ accountTierLabel(inspection.targetTier) }} ({{ inspection.targetTier }})</dd></div>
            <div><dt>소속 조직</dt><dd>{{ inspection.organizationName }}</dd></div>
            <div v-if="inspection.teamName"><dt>배정 팀</dt><dd>{{ inspection.teamName }}</dd></div>
            <div v-if="inspection.projectName"><dt>연결 프로젝트</dt><dd>{{ inspection.projectName }}</dd></div>
            <div v-if="inspection.expectedEmail"><dt>지정 이메일</dt><dd>{{ inspection.expectedEmail }}</dd></div>
            <div><dt>만료 시각</dt><dd>{{ formatDateTime(inspection.expiresAt) }}</dd></div>
          </dl>
        </div>

        <form v-if="inspection?.valid" class="login-form invite-account-form" @submit.prevent="submitRegistration">
          <label>
            아이디 (이메일 주소)
            <input
              v-model.trim="email"
              type="email"
              autocomplete="username"
              placeholder="user@company.com"
              :readonly="Boolean(inspection.expectedEmail)"
              required
            />
          </label>
          <label>
            비밀번호
            <input
              v-model="password"
              type="password"
              autocomplete="new-password"
              placeholder="12자 이상 입력"
              minlength="12"
              required
            />
          </label>
          <label>
            비밀번호 확인
            <input
              v-model="passwordConfirm"
              type="password"
              autocomplete="new-password"
              placeholder="비밀번호 다시 입력"
              minlength="12"
              required
            />
          </label>
          <p v-if="passwordMismatch" class="form-error" role="alert">비밀번호가 일치하지 않습니다.</p>
          <button class="primary-button login-submit" type="submit" :disabled="!canSubmit">
            <span>{{ busy ? '계정 생성 중…' : '계정 생성 및 TOTP 등록 진행' }}</span>
            <b>→</b>
          </button>
        </form>

        <div class="login-switch">
          <span>이미 계정이 있나요?</span>
          <button class="text-button" type="button" @click="emit('backToLogin')">로그인으로 돌아가기</button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.invite-register-page {
  grid-template-columns: minmax(0, 1fr) minmax(380px, 520px);
}
.invite-card-wrap {
  width: min(520px, 100%);
}
.invite-card {
  display: grid;
  gap: 18px;
}
.invite-code-form {
  display: grid;
}
.invite-code-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: center;
}
.invite-summary {
  padding: 14px 16px;
  display: grid;
  gap: 12px;
  border: 1px solid var(--accent-border);
  border-radius: 14px;
  background: var(--accent-dim);
}
.invite-summary-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.invite-summary-head strong {
  font-size: 14px;
  color: var(--text);
}
.invite-meta-grid {
  margin: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
}
.invite-meta-grid div {
  display: grid;
  gap: 2px;
}
.invite-meta-grid dt {
  color: var(--muted);
  font-size: 10px;
  font-weight: 700;
}
.invite-meta-grid dd {
  margin: 0;
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
  word-break: break-word;
}
.invite-account-form {
  margin-top: 2px;
}
@media (max-width: 1020px) {
  .invite-register-page {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 520px) {
  .invite-code-row {
    grid-template-columns: 1fr;
  }
  .invite-meta-grid {
    grid-template-columns: 1fr;
  }
}
</style>
