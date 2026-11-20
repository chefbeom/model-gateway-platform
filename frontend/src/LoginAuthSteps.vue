<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  step: 1 | 2
  phase?: 'credentials' | 'enroll' | 'recovery' | 'verify'
}>()

const step2Copy = computed(() => {
  if (props.phase === 'enroll') return 'QR 스캔 및 6자리 코드 검증 (최초 1회)'
  if (props.phase === 'recovery') return '복구 코드 보관 및 최종 승인'
  if (props.phase === 'verify') return 'Google Authenticator 확인 및 최종 승인'
  return 'QR 2차 확인 및 최종 승인'
})
</script>

<template>
  <ol class="login-stepper" aria-label="2단계 로그인 진행 상태">
    <li class="login-step" :class="{ active: step === 1, done: step === 2 }">
      <span class="login-step-badge">{{ step === 2 ? '✓' : '1' }}</span>
      <div>
        <strong>1. 로그인 진행</strong>
        <small>{{ step === 2 ? '아이디·비밀번호 확인 완료' : '아이디 · 비밀번호 입력' }}</small>
      </div>
    </li>
    <li class="login-step" :class="{ active: step === 2 }">
      <span class="login-step-badge">2</span>
      <div>
        <strong>2. QR 2차 확인 · 최종 승인</strong>
        <small>{{ step2Copy }}</small>
      </div>
    </li>
  </ol>
</template>
