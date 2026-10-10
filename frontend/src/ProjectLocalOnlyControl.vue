<script setup lang="ts">
defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <div class="local-only-control" :class="{ enabled: modelValue }">
    <label>
      <input type="checkbox" :checked="modelValue" @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)" />
      <span><strong>외부 AI 전송 금지</strong><small>이 프로젝트의 모든 API 키는 로컬 LLM만 사용합니다.</small></span>
    </label>
    <p v-if="modelValue" role="status">민감정보 탐지 여부와 관계없이 외부 Provider를 제외합니다. 논리 서비스에 정상·로드된 로컬 Target이 없거나 요청 기능을 지원하지 않으면 요청이 실패하며, 외부로 전환하지 않습니다.</p>
    <p v-else>외부 전송을 허용해도 기존 데이터 보호 정책과 Provider 사용 권한은 그대로 적용됩니다.</p>
  </div>
</template>

<style scoped>
.local-only-control { border: 1px solid var(--border); border-radius: 12px; padding: 16px; }
.local-only-control.enabled { border-color: #4e8a5c; }
.local-only-control label { display: flex; align-items: flex-start; gap: 12px; cursor: pointer; }
.local-only-control input[type="checkbox"] { flex: 0 0 18px; width: 18px; height: 18px; min-height: 18px; margin: 3px 0 0; padding: 0; accent-color: #85d99a; }
.local-only-control span { min-width: 0; display: grid; gap: 5px; }
.local-only-control strong { font-size: 14px; }
.local-only-control small, .local-only-control p { font-size: 12px; line-height: 1.65; color: var(--muted); overflow-wrap: anywhere; }
.local-only-control p { margin: 12px 0 0; }
</style>
