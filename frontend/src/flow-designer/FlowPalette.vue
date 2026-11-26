<script setup lang="ts">
import { computed, ref } from 'vue'
import { llmNodeId, projectNodeId } from './catalog'
import { buildPalette, shortModelName } from './palette'
import type { AddableNodeType, DesignerMode, FlowCommand, FlowGraph, HealthTone, PaletteConnectionGroup, PaletteGroupId, PaletteModelItem, PaletteNodeItem, PaletteProjectItem, ServiceFlowSnapshot } from './types'

const DND_MIME = 'application/x-aiconnect-flow-node'

const props = withDefaults(defineProps<{ snapshot: ServiceFlowSnapshot; graph: FlowGraph; readOnly: boolean; mode?: DesignerMode }>(), { mode: 'advanced' })
const emit = defineEmits<{ command: [FlowCommand]; 'select-node': [string] }>()

const query = ref('')
const showInactive = ref(false)
// In-memory only: group id -> collapsed.
const collapsed = ref<Record<string, boolean>>({})

// In-memory only: 'groupId/connectionId' -> user override (true = open).
// Default: open while searching or when the connection shows <= CONN_OPEN_LIMIT models, otherwise collapsed.
const CONN_OPEN_LIMIT = 8
const connOverride = ref<Record<string, boolean>>({})

const TONE_LABEL: Record<HealthTone, string> = { healthy: '정상', warning: '주의', error: '오류', muted: '비활성', unknown: '미확인' }

const searching = computed(() => query.value.trim() !== '')
const groups = computed(() => buildPalette(props.snapshot, props.graph, { query: query.value, showInactive: showInactive.value, mode: props.mode }))
const projectsHidden = computed(() => props.snapshot.projectsHidden === true)

function isOpen(id: PaletteGroupId): boolean {
  return searching.value || !collapsed.value[id]
}

function toggleGroup(id: PaletteGroupId) {
  if (searching.value) return
  collapsed.value = { ...collapsed.value, [id]: !collapsed.value[id] }
}

function itemCount(g: { nodes: unknown[]; projects: unknown[]; models: unknown[] }): number {
  return g.nodes.length + g.projects.length + g.models.length
}

function connKey(groupId: PaletteGroupId, conn: PaletteConnectionGroup): string {
  return groupId + '/' + conn.id
}

function connOpen(groupId: PaletteGroupId, conn: PaletteConnectionGroup): boolean {
  if (searching.value) return true
  return connOverride.value[connKey(groupId, conn)] ?? conn.models.length <= CONN_OPEN_LIMIT
}

function toggleConn(groupId: PaletteGroupId, conn: PaletteConnectionGroup) {
  if (searching.value) return
  connOverride.value = { ...connOverride.value, [connKey(groupId, conn)]: !connOpen(groupId, conn) }
}

function connTitle(conn: PaletteConnectionGroup): string {
  return [conn.label, conn.detail, '활성 ' + conn.activeCount + ' / 전체 ' + conn.totalCount].filter((x) => x !== '').join(' · ')
}

/* ------------------------------ click / drag ------------------------------ */
function clickNode(item: PaletteNodeItem) {
  if (item.onCanvas) {
    // Singleton node ids equal their type name ('prompt' selects the common prompt).
    emit('select-node', item.nodeType)
    return
  }
  if (props.readOnly || item.clickDisabledReason !== null) return
  emit('command', { type: 'addNode', nodeType: item.nodeType, refId: null, position: null })
}

function clickProject(item: PaletteProjectItem) {
  if (item.onCanvas) emit('select-node', projectNodeId(item.projectId))
  else if (!props.readOnly) emit('command', { type: 'addNode', nodeType: 'project', refId: item.projectId, position: null })
}

function clickModel(item: PaletteModelItem) {
  if (item.onCanvas) emit('select-node', llmNodeId(item.deploymentId))
  else if (!props.readOnly) emit('command', { type: 'addNode', nodeType: 'llm', refId: item.deploymentId, position: null })
}

function nodeClickable(item: PaletteNodeItem): boolean {
  return item.onCanvas || (!props.readOnly && item.clickDisabledReason === null)
}

function dragStart(event: DragEvent, nodeType: AddableNodeType, refId: string | null) {
  if (props.readOnly || !event.dataTransfer) { event.preventDefault(); return }
  event.dataTransfer.setData(DND_MIME, JSON.stringify({ nodeType, refId }))
  event.dataTransfer.effectAllowed = 'copy'
}

function nodeTitle(item: PaletteNodeItem): string {
  if (item.clickDisabledReason) return item.clickDisabledReason
  return item.draggable ? '클릭하거나 워크플로우로 끌어 놓아 추가합니다.' : item.description
}
</script>

<template>
  <aside class="fd-palette" aria-label="노드 팔레트">
    <div class="fd-palette-search">
      <input v-model="query" type="search" placeholder="노드 검색" aria-label="팔레트 검색" autocomplete="off" />
    </div>

    <div class="fd-palette-scroll">
      <p v-if="searching && groups.length === 0" class="fd-empty">검색 결과가 없습니다.</p>

      <section v-for="group in groups" :key="group.id" class="fd-section" :aria-labelledby="`fd-pal-${group.id}`">
        <h3 :id="`fd-pal-${group.id}`" class="fd-section-title">
          <button type="button" class="fd-section-toggle" :aria-expanded="isOpen(group.id)" @click="toggleGroup(group.id)">
            <span class="fd-caret" aria-hidden="true">{{ isOpen(group.id) ? '▾' : '▸' }}</span>
            <span>{{ group.label }}</span>
            <span class="fd-count">{{ itemCount(group) }}</span>
          </button>
        </h3>

        <div v-if="isOpen(group.id)" class="fd-section-body">
          <p v-if="group.id === 'entry' && projectsHidden" class="fd-empty">플랫폼 토큰 세션에서는 프로젝트가 표시되지 않습니다.</p>
          <p v-else-if="group.id === 'entry' && group.projects.length === 0" class="fd-empty">표시할 프로젝트가 없습니다.</p>

          <button
            v-for="item in group.nodes"
            :key="item.nodeType"
            type="button"
            class="fd-item fd-item--action"
            :class="{ 'is-dimmed': !nodeClickable(item) }"
            :aria-disabled="!nodeClickable(item)"
            :draggable="!readOnly && item.draggable"
            :title="nodeTitle(item)"
            :aria-label="`${item.label} ${item.onCanvas ? '선택' : '워크플로우에 추가'}`"
            @click="clickNode(item)"
            @dragstart="dragStart($event, item.nodeType, null)"
          >
            <span class="fd-item-icon" :class="`fd-item-icon--${group.id}`" aria-hidden="true">{{ item.icon }}</span>
            <span class="fd-item-main">
              <strong>{{ item.label }}</strong>
              <small>{{ item.clickDisabledReason ?? item.description }}</small>
              <span v-if="item.onCanvas" class="fd-chips"><span class="fd-chip fd-chip--accent">워크플로우에 있음</span></span>
            </span>
          </button>

          <button
            v-for="item in group.projects"
            :key="item.projectId"
            type="button"
            class="fd-item"
            :disabled="readOnly && !item.onCanvas"
            :draggable="!readOnly && !item.onCanvas"
            :aria-label="`${item.name} ${item.onCanvas ? '선택' : '워크플로우에 추가'}`"
            @click="clickProject(item)"
            @dragstart="dragStart($event, 'project', item.projectId)"
          >
            <span class="fd-item-icon fd-item-icon--entry" aria-hidden="true">⌘</span>
            <span class="fd-item-main">
              <strong :title="item.name">{{ item.name }}</strong>
              <span class="fd-chips">
                <span class="fd-chip" :class="item.status === 'ACTIVE' ? 'fd-chip--healthy' : 'fd-chip--muted'">{{ item.status === 'ACTIVE' ? '활성' : item.status }}</span>
                <span v-if="item.granted" class="fd-chip fd-chip--accent">접근 허용됨</span>
                <span v-else-if="item.onCanvas" class="fd-chip fd-chip--accent">워크플로우에 있음</span>
                <span v-if="item.guarded" class="fd-chip fd-chip--warning">보호 정책</span>
              </span>
            </span>
          </button>

          <div v-for="conn in group.connections" :key="conn.id" class="fd-conn" :data-connection="conn.id">
            <button type="button" class="fd-conn-head" :aria-expanded="connOpen(group.id, conn)" :title="connTitle(conn)" @click="toggleConn(group.id, conn)">
              <span class="fd-caret" aria-hidden="true">{{ connOpen(group.id, conn) ? '▾' : '▸' }}</span>
              <span class="fd-dot" :class="`fd-dot--${conn.tone}`" aria-hidden="true"></span>
              <span class="fd-conn-main">
                <strong>{{ conn.label }}</strong>
                <small v-if="conn.detail">{{ conn.detail }}</small>
              </span>
              <span class="fd-count" :title="`활성 ${conn.activeCount} / 전체 ${conn.totalCount}`">{{ conn.activeCount }}/{{ conn.totalCount }}</span>
              <span v-if="conn.hiddenCount > 0" class="fd-conn-hidden">숨김 {{ conn.hiddenCount }}</span>
            </button>
            <div v-if="connOpen(group.id, conn)" class="fd-conn-body">
              <button
                v-for="item in conn.models"
                :key="item.deploymentId"
                type="button"
                class="fd-item"
                :class="{ 'is-dimmed': item.availability === 'inactive' }"
                :disabled="readOnly && !item.onCanvas"
                :draggable="!readOnly && !item.onCanvas"
                :aria-label="`${item.displayName} ${item.onCanvas ? '선택' : '워크플로우에 추가'}`"
                :title="item.displayName"
                @click="clickModel(item)"
                @dragstart="dragStart($event, 'llm', item.deploymentId)"
              >
                <span class="fd-item-icon fd-item-icon--llm" aria-hidden="true">◆</span>
                <span class="fd-item-main">
                  <strong>{{ shortModelName(item.displayName, item.providerModelId) }}</strong>
                  <small v-if="shortModelName(item.displayName, item.providerModelId) !== item.displayName">{{ item.displayName }}</small>
                  <small v-else>{{ item.providerModelId }}</small>
                  <span class="fd-chips">
                    <span class="fd-chip" :class="`fd-chip--${item.tone}`">{{ item.availability === 'inactive' ? item.reason : TONE_LABEL[item.tone] }}</span>
                    <span v-if="item.onCanvas" class="fd-chip fd-chip--accent">워크플로우에 있음</span>
                  </span>
                </span>
              </button>
              <p v-if="conn.models.length === 0" class="fd-empty">표시할 모델이 없습니다.</p>
            </div>
          </div>

          <button v-if="group.hiddenCount > 0" type="button" class="fd-hidden-link" @click="showInactive = true">중지·미로드 {{ group.hiddenCount }}개 숨김 · 표시</button>
          <button v-else-if="showInactive && (group.id === 'llm-local' || group.id === 'llm-external') && group.models.some((m) => m.availability === 'inactive' && !m.onCanvas)" type="button" class="fd-hidden-link" @click="showInactive = false">중지·미로드 숨기기</button>
        </div>
      </section>
    </div>
  </aside>
</template>

<style scoped>
.fd-palette {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  color: var(--text);
}
.fd-palette-search { padding: 10px; border-bottom: 1px solid var(--border); }
.fd-palette-search input { min-height: 36px; font-size: 11px; }
.fd-palette-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 10px 14px; }
.fd-section { display: grid; gap: 6px; margin-top: 10px; }
.fd-section-title { margin: 0; }
.fd-section-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 2px;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: .04em;
  text-align: left;
  cursor: pointer;
}
.fd-section-toggle:hover { color: var(--text); }
.fd-caret { width: 10px; }
.fd-section-body { display: grid; gap: 6px; }
.fd-count { padding: 1px 6px; border-radius: 999px; background: var(--surface-3); color: var(--muted); font-size: 8px; }
.fd-empty { margin: 0; color: var(--faint); font-size: 10px; line-height: 1.5; }

.fd-item {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 9px 10px;
  text-align: left;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface-2);
  color: var(--text);
  cursor: pointer;
  transition: border-color .15s, background .15s;
}
.fd-item[draggable='true'] { cursor: grab; }
.fd-item:hover:not(:disabled):not(.is-dimmed) { border-color: var(--accent-border); background: var(--accent-dim); }
.fd-item:disabled { cursor: not-allowed; opacity: .55; }
.fd-item.is-dimmed { opacity: .6; }
.fd-item.is-dimmed:not([draggable='true']) { cursor: not-allowed; }
.fd-item--action { align-items: center; }
.fd-item-icon { flex: none; width: 18px; text-align: center; font-size: 14px; color: var(--warning); line-height: 18px; }
.fd-item-icon--entry { color: var(--info); }
.fd-item-icon--prompt, .fd-item-icon--cache { color: var(--accent-strong); }
.fd-item-icon--llm, .fd-item-icon--llm-local, .fd-item-icon--llm-external { color: var(--accent); }
.fd-item-main { min-width: 0; display: grid; gap: 2px; }
.fd-item-main strong { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 11px; font-weight: 800; }
.fd-item-main small { overflow: hidden; text-overflow: ellipsis; color: var(--muted); font-size: 9px; line-height: 1.4; }
.fd-item--action .fd-item-main small { white-space: normal; }
.fd-item:not(.fd-item--action) .fd-item-main small { white-space: nowrap; }
.fd-source { color: var(--text-soft); }
.fd-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 2px; }
.fd-chip { display: inline-flex; align-items: center; height: 17px; padding: 0 6px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface-3); color: var(--muted); font-size: 8px; font-weight: 800; white-space: nowrap; }
.fd-chip--healthy { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.fd-chip--warning { border-color: var(--warning); background: var(--warning-dim); color: var(--warning); }
.fd-chip--error { border-color: var(--danger); background: var(--danger-dim); color: var(--danger); }
.fd-chip--accent { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.fd-conn { display: grid; gap: 6px; }
.fd-conn-head { width: 100%; display: flex; align-items: center; gap: 6px; padding: 6px 8px; text-align: left; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-3); color: var(--text); cursor: pointer; }
.fd-conn-head:hover { border-color: var(--accent-border); }
.fd-conn-main { flex: 1; min-width: 0; display: grid; gap: 1px; }
.fd-conn-main strong { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 10px; font-weight: 800; }
.fd-conn-main small { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; color: var(--muted); font-size: 8px; }
.fd-conn-hidden { color: var(--faint); font-size: 8px; white-space: nowrap; }
.fd-conn-body { display: grid; gap: 6px; padding-left: 8px; border-left: 2px solid var(--border); }
.fd-dot { flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--faint); }
.fd-dot--healthy { background: var(--accent); }
.fd-dot--warning { background: var(--warning); }
.fd-dot--error { background: var(--danger); }
.fd-hidden-link { padding: 4px 2px; border: 0; background: transparent; color: var(--accent-strong); font-size: 10px; font-weight: 700; text-align: left; cursor: pointer; }
.fd-hidden-link:hover { text-decoration: underline; }
</style>
