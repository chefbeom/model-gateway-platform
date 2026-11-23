<script setup lang="ts">
import { computed, ref } from 'vue'
import { deploymentNodeId, projectNodeId, toneOf } from './catalog'
import type { AddableNodeType, DeploymentInfo, FlowCommand, FlowGraph, HealthTone, ProjectInfo, ServiceFlowSnapshot } from './types'

const DND_MIME = 'application/x-aiconnect-flow-node'

const props = defineProps<{ snapshot: ServiceFlowSnapshot; graph: FlowGraph; readOnly: boolean }>()
const emit = defineEmits<{ command: [FlowCommand]; 'select-node': [string] }>()

const query = ref('')

const TONE_LABEL: Record<HealthTone, string> = { healthy: '정상', warning: '주의', error: '오류', muted: '비활성', unknown: '미확인' }

const nodeIds = computed(() => new Set(props.graph.nodes.map((n) => n.id)))
const hasMasker = computed(() => nodeIds.value.has('masker'))
const accessProjectIds = computed(() => {
  const ids = new Set<string>()
  for (const edge of props.graph.edges) {
    if (edge.kind === 'access' && edge.from.nodeId.startsWith('project:')) ids.add(edge.from.nodeId.slice('project:'.length))
  }
  return ids
})

function matches(...values: Array<string | null | undefined>): boolean {
  const q = query.value.trim().toLowerCase()
  if (!q) return true
  return values.some((v) => !!v && v.toLowerCase().includes(q))
}

const showTarget = computed(() => matches('target', '타깃', '타겟', '라우팅', '라우팅 Target 추가'))
const showMasker = computed(() => matches('masker', '마스킹', '마스킹 워커', '마스커', '라우팅'))

function sourceName(d: DeploymentInfo): string {
  if (d.runtimeEndpointId) return props.snapshot.catalog.endpoints.find((e) => e.id === d.runtimeEndpointId)?.displayName ?? '로컬 런타임'
  if (d.externalProviderId) return props.snapshot.catalog.providers.find((p) => p.id === d.externalProviderId)?.displayName ?? '외부 제공자'
  return '알 수 없는 소스'
}

function deploymentTone(d: DeploymentInfo): HealthTone {
  if (!d.external && !d.loaded && d.enabled) return 'warning'
  return toneOf(d.enabled, d.healthStatus)
}

const deployments = computed(() =>
  props.snapshot.catalog.deployments
    .filter((d) => matches(d.displayName, d.providerModelId, sourceName(d), d.external ? '외부' : '로컬'))
    .slice()
    .sort((a, b) => Number(a.external) - Number(b.external) || a.displayName.localeCompare(b.displayName, 'ko') || a.id.localeCompare(b.id))
    .map((d) => ({ info: d, source: sourceName(d), tone: deploymentTone(d), onCanvas: nodeIds.value.has(deploymentNodeId(d.id)) })),
)

const projects = computed(() =>
  props.snapshot.catalog.projects
    .filter((p) => matches(p.name, p.status))
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name, 'ko') || a.id.localeCompare(b.id))
    .map((p) => ({ info: p, onCanvas: nodeIds.value.has(projectNodeId(p.id)), granted: accessProjectIds.value.has(p.id) })),
)

function add(nodeType: AddableNodeType, refId: string | null) {
  if (props.readOnly) return
  emit('command', { type: 'addNode', nodeType, refId, position: null })
}

function clickDeployment(d: DeploymentInfo, onCanvas: boolean) {
  if (onCanvas) emit('select-node', deploymentNodeId(d.id))
  else add('deployment', d.id)
}

function clickProject(p: ProjectInfo, onCanvas: boolean) {
  if (onCanvas) emit('select-node', projectNodeId(p.id))
  else add('project', p.id)
}

function dragStart(event: DragEvent, nodeType: AddableNodeType, refId: string | null) {
  if (props.readOnly || !event.dataTransfer) { event.preventDefault(); return }
  event.dataTransfer.setData(DND_MIME, JSON.stringify({ nodeType, refId }))
  event.dataTransfer.effectAllowed = 'copy'
}
</script>

<template>
  <aside class="fd-palette" aria-label="노드 팔레트">
    <div class="fd-palette-search">
      <input v-model="query" type="search" placeholder="노드 검색" aria-label="팔레트 검색" autocomplete="off" />
    </div>

    <div class="fd-palette-scroll">
      <section v-if="showTarget || showMasker" class="fd-section" aria-labelledby="fd-pal-routing">
        <h3 id="fd-pal-routing" class="fd-section-title">라우팅</h3>
        <button
          v-if="showTarget"
          type="button"
          class="fd-item fd-item--action"
          :disabled="readOnly"
          :draggable="!readOnly"
          title="클릭하거나 캔버스로 끌어 놓아 추가합니다."
          @click="add('target', null)"
          @dragstart="dragStart($event, 'target', null)"
        >
          <span class="fd-item-icon" aria-hidden="true">↝</span>
          <span class="fd-item-main"><strong>Target 추가</strong><small>라우터에 연결되는 새 Target</small></span>
        </button>
        <button
          v-if="showMasker"
          type="button"
          class="fd-item fd-item--action"
          :disabled="readOnly || hasMasker"
          :draggable="!readOnly && !hasMasker"
          :title="hasMasker ? '마스킹 워커는 하나만 둘 수 있습니다.' : '클릭하거나 캔버스로 끌어 놓아 추가합니다.'"
          @click="add('masker', null)"
          @dragstart="dragStart($event, 'masker', null)"
        >
          <span class="fd-item-icon" aria-hidden="true">◍</span>
          <span class="fd-item-main"><strong>마스킹 워커 추가</strong><small>{{ hasMasker ? '이미 캔버스에 있음' : '가드레일에 연결하는 워커' }}</small></span>
        </button>
      </section>

      <section class="fd-section" aria-labelledby="fd-pal-deployment">
        <h3 id="fd-pal-deployment" class="fd-section-title">Deployment <span class="fd-count">{{ deployments.length }}</span></h3>
        <p v-if="deployments.length === 0" class="fd-empty">표시할 Deployment가 없습니다.</p>
        <button
          v-for="item in deployments"
          :key="item.info.id"
          type="button"
          class="fd-item"
          :disabled="readOnly && !item.onCanvas"
          :draggable="!readOnly && !item.onCanvas"
          :aria-label="`${item.info.displayName} ${item.onCanvas ? '선택' : '캔버스에 추가'}`"
          @click="clickDeployment(item.info, item.onCanvas)"
          @dragstart="dragStart($event, 'deployment', item.info.id)"
        >
          <span class="fd-item-icon fd-item-icon--infra" aria-hidden="true">◇</span>
          <span class="fd-item-main">
            <strong :title="item.info.displayName">{{ item.info.displayName }}</strong>
            <small :title="item.info.providerModelId">{{ item.info.providerModelId }}</small>
            <small class="fd-source">{{ item.info.external ? '외부' : '로컬' }} · {{ item.source }}</small>
            <span class="fd-chips">
              <span class="fd-chip" :class="`fd-chip--${item.tone}`">{{ item.info.external ? '' : (!item.info.loaded ? '미로드 · ' : '') }}{{ TONE_LABEL[item.tone] }}</span>
              <span v-if="item.onCanvas" class="fd-chip fd-chip--accent">캔버스에 있음</span>
            </span>
          </span>
        </button>
      </section>

      <section class="fd-section" aria-labelledby="fd-pal-project">
        <h3 id="fd-pal-project" class="fd-section-title">프로젝트 <span class="fd-count">{{ projects.length }}</span></h3>
        <p v-if="projects.length === 0" class="fd-empty">표시할 프로젝트가 없습니다.</p>
        <button
          v-for="item in projects"
          :key="item.info.id"
          type="button"
          class="fd-item"
          :disabled="readOnly && !item.onCanvas"
          :draggable="!readOnly && !item.onCanvas"
          :aria-label="`${item.info.name} ${item.onCanvas ? '선택' : '캔버스에 추가'}`"
          @click="clickProject(item.info, item.onCanvas)"
          @dragstart="dragStart($event, 'project', item.info.id)"
        >
          <span class="fd-item-icon fd-item-icon--entry" aria-hidden="true">⌘</span>
          <span class="fd-item-main">
            <strong :title="item.info.name">{{ item.info.name }}</strong>
            <span class="fd-chips">
              <span class="fd-chip" :class="item.info.status === 'ACTIVE' ? 'fd-chip--healthy' : 'fd-chip--muted'">{{ item.info.status === 'ACTIVE' ? '활성' : item.info.status }}</span>
              <span v-if="item.granted" class="fd-chip fd-chip--accent">접근 허용됨</span>
              <span v-if="item.onCanvas && !item.granted" class="fd-chip fd-chip--accent">캔버스에 있음</span>
            </span>
          </span>
        </button>
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
.fd-section { display: grid; gap: 6px; margin-top: 12px; }
.fd-section-title { margin: 0 0 2px; display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 9px; font-weight: 800; letter-spacing: .04em; }
.fd-count { padding: 1px 6px; border-radius: 999px; background: var(--surface-3); color: var(--muted); font-size: 8px; }
.fd-empty { margin: 0; color: var(--faint); font-size: 10px; }

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
.fd-item:hover:not(:disabled) { border-color: var(--accent-border); background: var(--accent-dim); }
.fd-item:disabled { cursor: not-allowed; opacity: .55; }
.fd-item--action { align-items: center; }
.fd-item-icon { flex: none; width: 18px; text-align: center; font-size: 14px; color: var(--warning); line-height: 18px; }
.fd-item-icon--infra { color: var(--muted); }
.fd-item-icon--entry { color: var(--info); }
.fd-item-main { min-width: 0; display: grid; gap: 2px; }
.fd-item-main strong { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: 11px; font-weight: 800; }
.fd-item-main small { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; color: var(--muted); font-size: 9px; }
.fd-source { color: var(--text-soft); }
.fd-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 2px; }
.fd-chip { display: inline-flex; align-items: center; height: 17px; padding: 0 6px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface-3); color: var(--muted); font-size: 8px; font-weight: 800; white-space: nowrap; }
.fd-chip--healthy { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.fd-chip--warning { border-color: var(--warning); background: var(--warning-dim); color: var(--warning); }
.fd-chip--error { border-color: var(--danger); background: var(--danger-dim); color: var(--danger); }
.fd-chip--accent { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
</style>
