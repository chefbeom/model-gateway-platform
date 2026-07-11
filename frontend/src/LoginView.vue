<script setup lang="ts">
import { ref } from 'vue'
import BaseModal from './BaseModal.vue'
import { authenticate, type Session } from './api'

defineProps<{ theme: 'dark' | 'light' }>()
const emit = defineEmits<{ authenticated: [session: Session]; platformToken: [token: string]; toggleTheme: [] }>()

const mode = ref<'login' | 'bootstrap'>('login')
const email = ref('')
const password = ref('')
const busy = ref(false)
const message = ref('')
const emergencyOpen = ref(false)
const emergencyToken = ref('')

async function submit() {
  busy.value = true; message.value = ''
  try {
    const session = await authenticate(mode.value, email.value, password.value)
    password.value = ''
    emit('authenticated', session)
  } catch (error) { message.value = error instanceof Error ? error.message : '로그인할 수 없습니다.' }
  finally { busy.value = false }
}
function useEmergencyToken() {
  if (!emergencyToken.value.trim()) return
  emit('platformToken', emergencyToken.value.trim())
  emergencyToken.value = ''; emergencyOpen.value = false
}
</script>

<template>
  <div class="login-page">
    <div class="login-orb orb-one"></div><div class="login-orb orb-two"></div>
    <button class="theme-fab" type="button" :aria-label="theme === 'dark' ? '라이트 모드' : '다크 모드'" @click="emit('toggleTheme')">
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
        <div><span class="signal-dot"></span><strong>Private by Design</strong><small>Tailnet 내부 연산</small></div>
      </div>
    </section>
    <section class="login-card-wrap">
      <div class="login-card glass-panel">
        <div class="login-card-head"><p class="eyebrow">CONTROL PLANE ACCESS</p><h2>{{ mode === 'login' ? '다시 오신 것을 환영합니다' : '첫 관리자를 생성합니다' }}</h2><p>{{ mode === 'login' ? '관리 콘솔을 계속하려면 로그인하세요.' : '비어 있는 설치에서 한 번만 실행할 수 있습니다.' }}</p></div>
        <form class="login-form" @submit.prevent="submit">
          <label>이메일 주소<input v-model.trim="email" type="email" autocomplete="username" placeholder="admin@example.com" required /></label>
          <label>비밀번호<input v-model="password" type="password" autocomplete="current-password" placeholder="12자 이상 입력" minlength="12" required /></label>
          <p v-if="message" class="form-error" role="alert">{{ message }}</p>
          <button class="primary-button login-submit" :disabled="busy || !email || password.length < 12" type="submit"><span>{{ busy ? '확인 중…' : mode === 'login' ? '관리 콘솔 로그인' : '관리자 생성 및 로그인' }}</span><b>→</b></button>
        </form>
        <div class="login-switch"><span>{{ mode === 'login' ? '새 설치인가요?' : '이미 계정이 있나요?' }}</span><button class="text-button" type="button" @click="mode = mode === 'login' ? 'bootstrap' : 'login'">{{ mode === 'login' ? '첫 관리자 생성' : '로그인으로 돌아가기' }}</button></div>
        <button class="emergency-link" type="button" @click="emergencyOpen = true">비상용 플랫폼 토큰으로 연결</button>
      </div>
      <p class="login-foot">요청 데이터는 Gateway를 통과하지만 기본 정책에서는 원문을 저장하지 않습니다.</p>
    </section>
    <BaseModal :open="emergencyOpen" title="비상 관리자 연결" description="일반 로그인 장애 시에만 사용하는 Break-glass 접근입니다." size="sm" @close="emergencyOpen = false">
      <label class="field">ADMIN_API_TOKEN<input v-model="emergencyToken" type="password" autocomplete="off" placeholder="비상용 토큰 입력" @keyup.enter="useEmergencyToken" /></label>
      <template #footer><button class="secondary-button" type="button" @click="emergencyOpen = false">취소</button><button class="primary-button" type="button" :disabled="!emergencyToken" @click="useEmergencyToken">연결</button></template>
    </BaseModal>
  </div>
</template>
