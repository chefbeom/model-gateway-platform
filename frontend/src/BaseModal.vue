<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'

const props = withDefaults(defineProps<{ open: boolean; title: string; description?: string; size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })
const emit = defineEmits<{ close: [] }>()

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) emit('close')
}
function lockBody(open: boolean) { document.body.classList.toggle('modal-open', open) }

watch(() => props.open, lockBody)
onMounted(() => { window.addEventListener('keydown', onKeydown); lockBody(props.open) })
onUnmounted(() => { window.removeEventListener('keydown', onKeydown); lockBody(false) })
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-backdrop" @mousedown.self="emit('close')">
        <section class="modal-panel" :class="`modal-${size}`" role="dialog" aria-modal="true" :aria-label="title">
          <header class="modal-header">
            <div><span class="modal-kicker">AICONNECT CONTROL</span><h2>{{ title }}</h2><p v-if="description">{{ description }}</p></div>
            <button class="icon-button" type="button" aria-label="닫기" @click="emit('close')">×</button>
          </header>
          <div class="modal-body"><slot /></div>
          <footer v-if="$slots.footer" class="modal-footer"><slot name="footer" /></footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
