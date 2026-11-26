<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { connectionModelChoices } from './connections'
import { shortModelName } from './palette'
import type { FlowCommand, FlowGraph, HealthTone, ServiceFlowSnapshot } from './types'

const props = defineProps<{ snapshot: ServiceFlowSnapshot; graph: FlowGraph; connectionId: string; readOnly: boolean }>()
const emit = defineEmits<{ command: [FlowCommand] }>()

const TONE_LABEL: Record<HealthTone, string> = { healthy: '정상', warning: '주의', error: '오류', muted: '비활성', unknown: '미확인' }

// In-memory only (never browser storage).
const showInactive = ref(false)
const picked = ref<string[]>([])
watch(() => props.connectionId, () => { picked.value = []; showInactive.value = false })

const choices = computed(() => connectionModelChoices(props.snapshot, props.graph, props.connectionId, showInactive.value))
const pickable = computed(() => new Set(choices.value.items.filter((item) => !item.onCanvas).map((item) => item.deploymentId)))
const selected = computed(() => picked.value.filter((id) => pickable.value.has(id)))

function onPick(id: string, event: Event) {
  const on = (event.target as HTMLInputElement).checked
  picked.value = on ? (picked.value.includes(id) ? picked.value : [...picked.value, id]) : picked.value.filter((x) => x !== id)
}

function add() {
  if (props.readOnly || selected.value.length === 0) return
  emit('command', { type: 'addConnectionModels', connectionId: props.connectionId, deploymentIds: [...selected.value], position: null })
  picked.value = []
}
</script>

<template>
  <section class="fd-cm" aria-label="이 연결에 모델 추가">
    <h3 class="fd-cm-title">모델 추가 · {{ choices.connection.label }}</h3>
    <p v-if="choices.items.length === 0" class="fd-cm-empty">이 연결에서 추가할 수 있는 모델이 없습니다.</p>
    <label
      v-for="item in choices.items"
      :key="item.deploymentId"
      class="fd-cm-item"
      :class="{ 'is-off': item.availability === 'inactive' }"
      :title="item.displayName"
    >
      <input
        type="checkbox"
        :checked="item.onCanvas || picked.includes(item.deploymentId)"
        :disabled="readOnly || item.onCanvas"
        :aria-label="`모델 ${item.displayName}`"
        @change="onPick(item.deploymentId, $event)"
      />
      <span class="fd-cm-name">{{ shortModelName(item.displayName, item.providerModelId) }}</span>
      <small class="fd-cm-chip">{{ item.onCanvas ? '워크플로우에 있음' : item.availability === 'inactive' ? item.reason : TONE_LABEL[item.tone] }}</small>
    </label>
    <button v-if="choices.hiddenCount > 0" type="button" class="fd-cm-link" @click="showInactive = true">중지·미로드 {{ choices.hiddenCount }}개 표시</button>
    <button v-else-if="showInactive" type="button" class="fd-cm-link" @click="showInactive = false">중지·미로드 숨기기</button>
    <button type="button" class="primary-button fd-cm-add" :disabled="readOnly || selected.length === 0" @click="add">선택한 모델 추가 ({{ selected.length }})</button>
  </section>
</template>

<style scoped>
.fd-cm { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.fd-cm-title { margin: 0 0 2px; color: var(--muted); font-size: 10px; font-weight: 800; overflow-wrap: anywhere; }
.fd-cm-empty { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.5; }
.fd-cm-item { display: flex; align-items: center; gap: 8px; min-height: 30px; padding: 0 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); font-size: 11px; cursor: pointer; }
.fd-cm-item:hover { border-color: var(--accent-border); }
.fd-cm-item.is-off { opacity: .6; }
.fd-cm-item input[type='checkbox'] { flex: none; width: 16px; height: 16px; min-height: 16px; padding: 0; }
.fd-cm-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 700; }
.fd-cm-chip { flex: none; color: var(--muted); font-size: 9px; white-space: nowrap; }
.fd-cm-link { justify-self: start; padding: 2px 0; border: 0; background: transparent; color: var(--accent-strong); font-size: 10px; font-weight: 700; cursor: pointer; }
.fd-cm-link:hover { text-decoration: underline; }
.fd-cm-add { min-height: 32px; }
.fd-cm-add:disabled { cursor: not-allowed; opacity: .5; }
</style>
