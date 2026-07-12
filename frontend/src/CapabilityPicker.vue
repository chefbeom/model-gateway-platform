<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const options = [
  { value: 'CHAT_COMPLETION', label: '채팅 요청 처리', description: 'OpenAI Chat Completions 형식의 요청을 처리합니다.' },
  { value: 'STREAMING', label: 'SSE 스트리밍', description: 'stream: true 요청의 토큰 스트림을 중계합니다.' },
  { value: 'VISION', label: '이미지 입력', description: 'image_url, input_image 등 멀티모달 입력을 처리합니다.' },
  { value: 'TOOL_CALLING', label: '함수 및 도구 호출', description: 'tools 배열과 tool_calls 응답을 사용하는 요청입니다.' },
  { value: 'STRUCTURED_OUTPUT', label: 'JSON Schema 형식 응답', description: 'response_format.type=json_schema 계약을 적용합니다.' },
  { value: 'REASONING', label: '추론 특화 출력', description: '추론 기능이 검증된 Deployment만 사용합니다.' }
] as const

function parse(value: string): string[] {
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? [...new Set(parsed.filter(item => typeof item === 'string'))] : []
  } catch {
    return []
  }
}

const selected = computed<string[]>({
  get: () => parse(props.modelValue),
  set: value => emit('update:modelValue', JSON.stringify(value))
})
const summary = computed(() => JSON.stringify(selected.value))
const summaryDescription = computed(() => selected.value.length
  ? `${selected.value.length}개 기능을 모든 요청에 필수로 적용`
  : '요청 본문에 따라 필요한 기능을 자동 판별하는 기본값')

function clear() {
  selected.value = []
}
</script>

<template>
  <div class="capability-field">
    <span class="capability-label">필수 Capability</span>
    <details class="capability-picker">
      <summary>
        <span><code>{{ summary }}</code><small>{{ summaryDescription }}</small></span>
        <b aria-hidden="true">⌄</b>
      </summary>
      <div class="capability-menu">
        <button type="button" class="capability-option" :class="{ selected: !selected.length }" @click="clear">
          <span><strong><code>[]</code> 자동 판별</strong><small>서비스가 항상 강제하는 기능이 없습니다. 요청 내용에 따라 VISION, TOOL_CALLING, STRUCTURED_OUTPUT 등을 자동으로 요구합니다. 권장 기본값입니다.</small></span>
          <i>{{ !selected.length ? '✓' : '' }}</i>
        </button>
        <label v-for="option in options" :key="option.value" class="capability-option" :class="{ selected: selected.includes(option.value) }">
          <input v-model="selected" type="checkbox" :value="option.value" />
          <span><strong><code>{{ option.value }}</code> {{ option.label }}</strong><small>{{ option.description }}</small></span>
          <i>{{ selected.includes(option.value) ? '✓' : '' }}</i>
        </label>
      </div>
    </details>
  </div>
</template>

<style scoped>
.capability-field { display: grid; gap: 7px; }
.capability-label { color: var(--text-soft); font-size: 10px; font-weight: 700; }
.capability-picker { border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); }
.capability-picker[open] { border-color: var(--accent-border); box-shadow: 0 0 0 2px var(--accent-dim); }
.capability-picker summary { min-height: 54px; padding: 10px 13px; display: flex; justify-content: space-between; align-items: center; gap: 12px; cursor: pointer; list-style: none; }
.capability-picker summary::-webkit-details-marker { display: none; }
.capability-picker summary > span { min-width: 0; display: grid; gap: 4px; }
.capability-picker summary code { overflow: hidden; color: var(--accent-strong); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.capability-picker summary small { color: var(--muted); font-size: 8px; }
.capability-picker summary b { color: var(--muted); font-size: 15px; transition: transform .16s ease; }
.capability-picker[open] summary b { transform: rotate(180deg); }
.capability-menu { padding: 8px; display: grid; gap: 6px; border-top: 1px solid var(--border); }
.capability-option { width: 100%; min-height: 56px; padding: 10px; display: grid; grid-template-columns: auto minmax(0, 1fr) 18px; gap: 10px; align-items: center; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); color: var(--text); text-align: left; cursor: pointer; }
button.capability-option { grid-template-columns: minmax(0, 1fr) 18px; font: inherit; }
.capability-option:hover, .capability-option.selected { border-color: var(--accent-border); background: var(--accent-dim); }
.capability-option input { width: 16px; min-height: auto; accent-color: var(--accent-strong); }
.capability-option > span { min-width: 0; display: grid; gap: 4px; }
.capability-option strong { color: var(--text-soft); font-size: 9px; }
.capability-option code { color: var(--accent-strong); font-size: 9px; }
.capability-option small { color: var(--muted); font-size: 8px; font-weight: 500; line-height: 1.5; }
.capability-option i { color: var(--accent-strong); font-size: 12px; font-style: normal; font-weight: 900; }
</style>
