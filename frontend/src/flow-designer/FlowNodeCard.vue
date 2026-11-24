<script setup lang="ts">
import { computed } from 'vue'
import { COLLAPSED_HEIGHT, HEADER_HEIGHT, NODE_PADDING, NODE_WIDTH, PORT_ROW_HEIGHT, SUMMARY_HEIGHT } from './layout'
import type { FlowNode, HealthTone, NodeChangeKind, NodeView, PortSpec } from './types'

const props = defineProps<{
  node: FlowNode
  view: NodeView
  ports: PortSpec[]
  selected: boolean
  change: NodeChangeKind | null
  counts: { error: number; warning: number; info: number }
  /** Entries are `in:<portId>` / `out:<portId>` (compatible) or `hot:in:<portId>` (snap target). */
  highlightPortIds: string[]
  readOnly: boolean
}>()

const emit = defineEmits<{
  'toggle-collapse': [nodeId: string, collapsed: boolean]
  activate: [nodeId: string]
}>()

const TONE_LABEL: Record<HealthTone, string> = {
  healthy: '정상',
  warning: '주의',
  error: '오류',
  muted: '비활성',
  unknown: '상태 미확인',
}

const inPorts = computed(() => props.ports.filter((p) => p.direction === 'in'))
const outPorts = computed(() => props.ports.filter((p) => p.direction === 'out'))
const rows = computed(() => Math.max(inPorts.value.length, outPorts.value.length, 1))

function portTop(port: PortSpec): number {
  if (props.node.collapsed) return COLLAPSED_HEIGHT / 2
  const index = (port.direction === 'in' ? inPorts.value : outPorts.value).indexOf(port)
  return HEADER_HEIGHT + PORT_ROW_HEIGHT * index + PORT_ROW_HEIGHT / 2
}

function portStyle(port: PortSpec): Record<string, string> {
  // A 20px hit box centred on the anchor (x = 0 for in, NODE_WIDTH for out).
  const left = port.direction === 'in' ? 0 : NODE_WIDTH
  return { left: `${left - 10}px`, top: `${portTop(port) - 10}px` }
}

function portState(port: PortSpec): { compatible: boolean; hot: boolean } {
  const key = `${port.direction}:${port.id}`
  return { compatible: props.highlightPortIds.includes(key), hot: props.highlightPortIds.includes(`hot:${key}`) }
}

// locked: start = 고정, policy = 정책, derived hosts = 자동.
const lockChip = computed(() => (props.node.type === 'policy' ? '정책' : props.node.type === 'endpoint' || props.node.type === 'provider' ? '자동' : '고정'))

const bodyHeight = computed(() => HEADER_HEIGHT + rows.value * PORT_ROW_HEIGHT + SUMMARY_HEIGHT + NODE_PADDING)
const ariaLabel = computed(() => `${props.view.typeLabel} ${props.view.title}, ${TONE_LABEL[props.view.tone]}`)

function onKeydown(event: KeyboardEvent) {
  if (event.target !== event.currentTarget) return
  if (event.key === 'Enter') {
    event.preventDefault()
    emit('activate', props.node.id)
  }
}
</script>

<template>
  <div
    class="fd-node"
    :class="{ 'is-selected': selected, 'is-collapsed': node.collapsed, 'is-readonly': readOnly }"
    :data-node-id="node.id"
    :data-category="view.category"
    :data-tone="view.tone"
    role="group"
    tabindex="0"
    :aria-label="ariaLabel"
    :aria-selected="selected"
    @keydown="onKeydown"
  >
    <div class="fd-node-body" :style="{ height: `${node.collapsed ? COLLAPSED_HEIGHT : bodyHeight}px` }">
      <header class="fd-node-header" :style="{ height: `${HEADER_HEIGHT}px` }">
        <span class="fd-node-strip" aria-hidden="true"></span>
        <span class="fd-node-icon" aria-hidden="true">{{ view.icon }}</span>
        <span class="fd-node-heading">
          <strong class="fd-node-title" :title="view.title">{{ view.title }}</strong>
          <small class="fd-node-subtitle" :title="view.subtitle">{{ view.subtitle || view.typeLabel }}</small>
        </span>
        <span class="fd-node-marks">
          <i v-if="change === 'modified'" class="fd-change-dot" title="변경됨" aria-label="변경됨"></i>
          <span v-if="change === 'added'" class="fd-chip fd-chip--accent">신규</span>
          <span v-if="view.protected" class="fd-chip fd-chip--warning" title="서버 정책으로 보호 중입니다.">보호</span>
          <span v-if="view.locked" class="fd-chip" :title="view.lockReason ?? undefined">{{ lockChip }}</span>
          <span v-if="view.promptScope" class="fd-chip fd-chip--accent">{{ view.promptScope === 'common' ? '공통' : '모델별' }}</span>
          <span v-if="counts.error > 0" class="fd-chip fd-chip--danger" :title="`오류 ${counts.error}건`">{{ counts.error }}</span>
          <span v-if="counts.warning > 0" class="fd-chip fd-chip--warning" :title="`경고 ${counts.warning}건`">{{ counts.warning }}</span>
        </span>
        <button
          type="button"
          class="fd-collapse"
          :aria-label="node.collapsed ? `${view.title} 펼치기` : `${view.title} 접기`"
          :aria-expanded="!node.collapsed"
          :title="node.collapsed ? '펼치기' : '접기'"
          :disabled="readOnly"
          @click.stop="emit('toggle-collapse', node.id, !node.collapsed)"
        >
          {{ node.collapsed ? '▸' : '▾' }}
        </button>
        <span class="fd-tone" :class="`fd-tone--${view.tone}`" aria-hidden="true"></span>
      </header>

      <template v-if="!node.collapsed">
        <div class="fd-ports" :style="{ height: `${rows * PORT_ROW_HEIGHT}px` }">
          <div v-for="i in rows" :key="i" class="fd-row" :style="{ height: `${PORT_ROW_HEIGHT}px` }">
            <span class="fd-port-label">{{ inPorts[i - 1]?.label ?? '' }}</span>
            <span class="fd-port-label">{{ outPorts[i - 1]?.label ?? '' }}</span>
          </div>
        </div>
        <div class="fd-summary" :style="{ height: `${SUMMARY_HEIGHT}px` }">
          <div v-if="view.badges.length > 0" class="fd-badges">
            <span v-for="badge in view.badges" :key="badge" class="fd-chip">{{ badge }}</span>
          </div>
          <div v-for="(line, index) in view.summary.slice(0, 2)" :key="index" class="fd-summary-line" :title="line">{{ line }}</div>
          <div v-if="view.summary.length === 0 && view.badges.length === 0" class="fd-summary-line fd-muted">{{ view.typeLabel }}</div>
        </div>
      </template>
    </div>

    <span
      v-for="port in ports"
      :key="`${port.direction}:${port.id}`"
      class="fd-port"
      :class="[`fd-port--${port.type}`, { 'is-compatible': portState(port).compatible, 'is-hot': portState(port).hot }]"
      :style="portStyle(port)"
      :data-node-id="node.id"
      :data-port-id="port.id"
      :data-port-dir="port.direction"
      :title="`${port.label} (${port.direction === 'in' ? '입력' : '출력'})`"
      tabindex="-1"
    ></span>
  </div>
</template>

<style scoped>
.fd-node {
  position: absolute;
  box-sizing: border-box;
  color: var(--text);
  border-radius: 12px;
  outline: none;
  user-select: none;
  touch-action: none;
  text-rendering: geometricPrecision;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  --fd-cat: var(--muted);
}
.fd-node[data-category='entry'] { --fd-cat: var(--info); }
.fd-node[data-category='guard'] { --fd-cat: var(--warning); }
.fd-node[data-category='prompt'] { --fd-cat: var(--accent-strong); }
.fd-node[data-category='cache'] { --fd-cat: var(--accent-strong); }
.fd-node[data-category='routing'] { --fd-cat: var(--warning); }
.fd-node[data-category='llm'] { --fd-cat: var(--accent); }
.fd-node[data-category='infra'] { --fd-cat: var(--muted); }

.fd-node-body {
  position: relative;
  box-sizing: border-box;
  width: 100%;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
  cursor: grab;
}
.fd-node.is-readonly .fd-node-body { cursor: default; }
.fd-node:hover .fd-node-body { border-color: var(--border-strong); }
.fd-node.is-selected .fd-node-body {
  border-color: var(--accent-strong);
  box-shadow: 0 0 0 1px var(--accent-border), var(--shadow-soft);
}
.fd-node:focus-visible .fd-node-body {
  border-color: var(--accent-strong);
  box-shadow: 0 0 0 1px var(--accent-strong);
}

.fd-node-header {
  position: relative;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 14px;
  border-bottom: 1px solid var(--border);
  background: var(--surface-2);
}
.fd-node.is-collapsed .fd-node-header { border-bottom: 0; max-height: 100%; }
.fd-node-strip { position: absolute; left: 0; top: 0; bottom: 0; width: 4px; background: var(--fd-cat); }
.fd-node-icon { flex: none; width: 18px; text-align: center; font-size: 14px; color: var(--fd-cat); }
.fd-node-heading { flex: 1; min-width: 0; display: grid; gap: 1px; }
.fd-node-title { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 11.5px; font-weight: 800; line-height: 15px; }
.fd-node-subtitle { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 9.5px; line-height: 12px; color: var(--muted); }
.fd-node-marks { flex: none; display: inline-flex; align-items: center; gap: 3px; }
.fd-change-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 1px var(--accent-strong); }
.fd-collapse {
  flex: none;
  width: 22px;
  height: 22px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
}
.fd-collapse:hover:not(:disabled) { background: var(--accent-dim); color: var(--text); }
.fd-collapse:disabled { cursor: default; opacity: .5; }
.fd-tone { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--muted); }
.fd-tone--healthy { background: var(--accent-strong); }
.fd-tone--warning { background: var(--warning); }
.fd-tone--error { background: var(--danger); }
.fd-tone--muted, .fd-tone--unknown { background: var(--muted); }

.fd-chip {
  display: inline-flex;
  align-items: center;
  height: 16px;
  padding: 0 5px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--surface-3);
  color: var(--muted);
  font-size: 8.5px;
  font-weight: 800;
  line-height: 1;
  white-space: nowrap;
}
.fd-chip--accent { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.fd-chip--danger { border-color: var(--danger); background: var(--danger-dim); color: var(--danger); }
.fd-chip--warning { border-color: var(--warning); background: var(--warning-dim); color: var(--warning); }

.fd-ports { position: relative; }
.fd-row { position: relative; box-sizing: border-box; display: flex; align-items: center; justify-content: space-between; padding: 0 14px; }
.fd-port-label { font-size: 10px; font-weight: 700; color: var(--text-soft); white-space: nowrap; }

.fd-summary {
  box-sizing: border-box;
  overflow: hidden;
  padding: 4px 12px 0;
  display: flex;
  flex-direction: column;
}
.fd-badges { height: 16px; margin-bottom: 2px; flex: none; display: flex; gap: 4px; overflow: hidden; }
.fd-summary-line { height: 15px; flex: none; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 10.5px; line-height: 15px; color: var(--text-soft); }
.fd-muted { color: var(--muted); }

.fd-port {
  position: absolute;
  box-sizing: border-box;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  cursor: crosshair;
  touch-action: none;
}
.fd-port[data-port-dir='in'] { cursor: pointer; }
.fd-port::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 4px;
  width: 12px;
  height: 12px;
  box-sizing: border-box;
  border-radius: 50%;
  border: 2px solid var(--fd-cat);
  background: var(--surface);
  transition: transform .12s, background .12s;
}
.fd-port:hover::after { transform: scale(1.25); background: var(--fd-cat); }
.fd-port.is-compatible::after { background: var(--accent-dim); border-color: var(--accent-strong); transform: scale(1.2); box-shadow: 0 0 0 3px var(--accent-dim); }
.fd-port.is-hot::after { background: var(--accent-strong); transform: scale(1.5); }

@media (prefers-reduced-motion: reduce) {
  .fd-port::after { transition: none; }
}
</style>
