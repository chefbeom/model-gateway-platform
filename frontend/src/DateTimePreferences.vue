<script setup lang="ts">
import { browserTimeZone, clockFormat, formatDateTime, setClockFormat, type ClockFormat } from './dateTime'

const options: { value: ClockFormat; label: string; description: string }[] = [
  { value: '24h', label: '24시간제 · 기본', description: 'yyyy-MM-dd HH:mm:ss' },
  { value: '12h', label: '12시간제', description: 'yyyy-MM-dd 오전/오후 hh:mm:ss' }
]
const example = new Date(2026, 9, 21, 23, 8, 39)
const timeZone = browserTimeZone()
</script>

<template>
  <section class="settings-section clock-preferences" aria-labelledby="clock-format-title">
    <span id="clock-format-title" class="settings-section-title">날짜·시각 표시</span>
    <div class="settings-choice-grid" role="group" aria-label="시각 표시 형식">
      <button v-for="option in options" :key="option.value" type="button"
        :class="{ active: clockFormat === option.value }" :aria-pressed="clockFormat === option.value"
        @click="setClockFormat(option.value)">
        <strong>{{ option.label }}</strong>
        <small>{{ option.description }}</small>
        <span class="clock-format-example">{{ formatDateTime(example, option.value) }}</span>
      </button>
    </div>
    <p class="clock-format-note">요청·장애·감사 로그와 운영 화면에 즉시 적용되며 이 브라우저에 저장됩니다.<br />시간대: {{ timeZone }} · 표시 형식만 변경하며 실제 요청 시각은 바뀌지 않습니다.</p>
  </section>
</template>

<style scoped>
.clock-format-example { font-size: 12px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.clock-format-note { margin: 0; color: var(--muted); font-size: 11px; line-height: 1.7; }
</style>
