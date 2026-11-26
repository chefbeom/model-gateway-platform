<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  initialExpanded,
  revealActive,
  toggleExpanded,
  type NavCategory,
  type NavCategoryId,
  type NavExpansionMode,
  type NavPageId,
  type NavSections
} from './consoleNavigation'

const props = defineProps<{ sections: NavSections; activeId: string; mode: NavExpansionMode }>()
const emit = defineEmits<{ navigate: [id: NavPageId] }>()

const expanded = ref<NavCategoryId[]>([])
// Reset to the role default whenever the role (mode) or the visible category set changes,
// e.g. after the session context finishes loading and a developer list becomes an admin list.
const layoutKey = computed(() => `${props.mode}|${props.sections.categories.map(category => category.id).join(',')}`)

watch(layoutKey, () => { expanded.value = initialExpanded(props.sections, props.activeId, props.mode) }, { immediate: true })
watch(() => props.activeId, id => { expanded.value = revealActive(expanded.value, id, props.mode) })

// Keep the active row inside the nav's own scroll area (small screens / 135% font scale).
// Only nav.scrollTop changes, never the window, so the off-canvas mobile drawer cannot shift the page.
const navEl = ref<HTMLElement | null>(null)
function keepActiveVisible() {
  const container = navEl.value
  if (!container || typeof container.querySelector !== 'function') return
  const active = container.querySelector<HTMLElement>('.nav-item.active')
  if (!active) return
  const top = active.offsetTop - container.offsetTop
  const bottom = top + active.offsetHeight
  if (top < container.scrollTop) container.scrollTop = top
  else if (bottom > container.scrollTop + container.clientHeight) container.scrollTop = bottom - container.clientHeight
}
onMounted(keepActiveVisible)
watch([() => props.activeId, layoutKey], keepActiveVisible, { flush: 'post' })

function isExpanded(id: NavCategoryId) { return expanded.value.includes(id) }
function toggle(id: NavCategoryId) { expanded.value = toggleExpanded(expanded.value, id, props.mode) }
function containsActive(category: NavCategory) { return category.items.some(item => item.id === props.activeId) }
</script>

<template>
  <nav ref="navEl" class="side-nav" aria-label="주요 기능">
    <button
      v-for="item in sections.pinned"
      :key="item.id"
      type="button"
      class="nav-item nav-pinned"
      :class="{ active: activeId === item.id }"
      :title="item.description"
      :aria-current="activeId === item.id ? 'page' : undefined"
      @click="emit('navigate', item.id)"
    ><span class="nav-icon" aria-hidden="true">{{ item.icon }}</span><strong>{{ item.label }}</strong><i aria-hidden="true"></i></button>
    <section v-for="category in sections.categories" :key="category.id" class="nav-category">
      <button
        :id="`nav-category-${category.id}`"
        type="button"
        class="nav-category-toggle"
        :class="{ 'has-active': containsActive(category) }"
        :aria-expanded="isExpanded(category.id) ? 'true' : 'false'"
        :aria-controls="`nav-category-items-${category.id}`"
        @click="toggle(category.id)"
      ><span class="nav-icon" aria-hidden="true">{{ category.icon }}</span><strong>{{ category.label }}</strong><span class="nav-category-count" aria-hidden="true">{{ category.items.length }}</span><span class="nav-category-chevron" aria-hidden="true">›</span></button>
      <div
        :id="`nav-category-items-${category.id}`"
        class="nav-category-items"
        role="group"
        :aria-labelledby="`nav-category-${category.id}`"
        :hidden="!isExpanded(category.id)"
      >
        <button
          v-for="item in category.items"
          :key="item.id"
          type="button"
          class="nav-item"
          :class="{ active: activeId === item.id }"
          :title="item.description"
          :aria-current="activeId === item.id ? 'page' : undefined"
          @click="emit('navigate', item.id)"
        ><strong>{{ item.label }}</strong><i aria-hidden="true"></i></button>
      </div>
    </section>
  </nav>
</template>
