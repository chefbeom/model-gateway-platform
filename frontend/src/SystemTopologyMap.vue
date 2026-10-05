<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import SystemTopologyIcon from './SystemTopologyIcon.vue'
import { buildSystemTopology, relatedTopologyNodes, type SystemSnapshot, type TopologyEdge, type TopologyKind, type TopologyTone } from './systemTopology'

const props = defineProps<{ snapshot: SystemSnapshot; busy?: boolean }>()
const projectId = ref('')
const serviceId = ref('')
const includeUnlinkedModels = ref(false)
const selectedId = ref('')
const selectedEdgeId = ref('')
const zoom = ref(1)
const automaticFit = ref(true)
const viewport = ref<HTMLElement | null>(null)
const inspector = ref<HTMLElement | null>(null)
const markerPrefix = 'topology-' + useId().replace(/[^\w-]/g, '')
const tones: TopologyTone[] = ['healthy', 'warning', 'error', 'muted', 'unknown']
const graph = computed(() => buildSystemTopology(props.snapshot, { projectId: projectId.value, serviceId: serviceId.value, includeUnlinkedModels: includeUnlinkedModels.value }))
const selectedNode = computed(() => graph.value.nodes.find(node => node.id === selectedId.value))
const selectedEdge = computed(() => graph.value.edges.find(edge => edge.id === selectedEdgeId.value))
const highlight = computed(() => selectedEdge.value ? new Set([selectedEdge.value.source, selectedEdge.value.target]) : relatedTopologyNodes(graph.value, selectedId.value, props.snapshot))
const hasSelection = computed(() => !!(selectedNode.value || selectedEdge.value))
const incidentEdges = computed(() => graph.value.edges.filter(edge => edge.source === selectedId.value || edge.target === selectedId.value))
const typeLabels: Record<TopologyKind, string> = { project: '호출 프로젝트', gateway: 'Gateway', service: '논리 서비스', model: '모델 Deployment', runtime: 'Local Runtime', provider: 'External Provider', database: '영속 저장소', shared: '공유 상태', observability: '요청 관측' }
const warningCount = computed(() => graph.value.edges.filter(edge => edge.kind !== 'dependency' && ['error', 'warning'].includes(edge.status.tone)).length)
const nodeName = (id: string) => graph.value.nodes.find(node => node.id === id)?.title || id
function edgeFocused(edge: TopologyEdge) { return selectedEdgeId.value ? selectedEdgeId.value === edge.id : highlight.value.has(edge.source) && highlight.value.has(edge.target) }
function selectNode(id: string) { selectedId.value = id; selectedEdgeId.value = '' }
function selectEdge(edge: TopologyEdge) { selectedEdgeId.value = edge.id; selectedId.value = '' }
function clearSelection() { selectedId.value = ''; selectedEdgeId.value = '' }
function changeZoom(step: number) { automaticFit.value = false; zoom.value = Math.max(.65, Math.min(1.4, Math.round((zoom.value + step) * 100) / 100)) }
function fitToView() {
  automaticFit.value = true
  const width = viewport.value?.clientWidth ?? graph.value.width
  zoom.value = width < 720 ? 1 : Math.max(.65, Math.min(1, (width - 24) / graph.value.width))
}
async function showDetails() { await nextTick(); inspector.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }) }
let observer: ResizeObserver | undefined
onMounted(() => { observer = new ResizeObserver(() => { if (automaticFit.value) fitToView() }); if (viewport.value) observer.observe(viewport.value); fitToView() })
onBeforeUnmount(() => observer?.disconnect())
watch(graph, value => { if (selectedId.value && !value.nodes.some(node => node.id === selectedId.value)) selectedId.value = ''; if (selectedEdgeId.value && !value.edges.some(edge => edge.id === selectedEdgeId.value)) selectedEdgeId.value = '' })
watch(() => props.snapshot.projects, projects => { if (!projects.some(project => project.id === projectId.value)) projectId.value = '' })
watch(() => props.snapshot.services, services => { if (!services.some(service => service.id === serviceId.value)) serviceId.value = '' })
</script>

<template>
  <section class="topology-map" aria-label="현재 워크스페이스 시스템 연결도" :aria-busy="busy">
    <div class="topology-toolbar">
      <div class="topology-filters">
        <label>호출 프로젝트<select v-model="projectId" :disabled="busy"><option value="">전체 프로젝트</option><option v-for="project in snapshot.projects" :key="project.id" :value="project.id">{{ project.name }}</option></select></label>
        <label>논리 서비스<select v-model="serviceId" :disabled="busy"><option value="">전체 서비스</option><option v-for="service in snapshot.services" :key="service.id" :value="service.id">{{ service.displayName || service.serviceKey }}</option></select></label>
        <label class="unlinked-toggle"><input v-model="includeUnlinkedModels" type="checkbox" :disabled="busy" /><span>미연결 모델 포함</span></label>
      </div>
      <div class="zoom-controls" role="group" aria-label="구성도 확대·축소">
        <button type="button" aria-label="구성도 축소" :disabled="zoom <= .65" @click="changeZoom(-.1)">−</button>
        <span>{{ Math.round(zoom * 100) }}%</span>
        <button type="button" aria-label="구성도 확대" :disabled="zoom >= 1.4" @click="changeZoom(.1)">+</button>
        <button type="button" class="fit-button" @click="fitToView">화면 맞춤</button>
      </div>
    </div>
    <div class="topology-caption">
      <p>프로젝트·API 키 → Gateway → 논리 서비스 → 모델 Target → Runtime / Provider</p>
      <span>노드·연결선을 선택하면 상세를 확인할 수 있습니다. <b>P 숫자가 작을수록 우선</b> · 큰 구성은 연결도 안에서 스크롤하세요.</span>
    </div>
    <div class="topology-legend" aria-label="연결 상태 범례">
      <span class="tone-healthy"><i></i>정상 기록·활성 설정</span><span class="tone-warning"><i></i>주의·미로드</span><span class="tone-error"><i></i>장애·권한 확인</span><span class="tone-muted"><i></i>비활성·미확인</span><span class="dependency-legend"><i></i>기능·설정 관계</span>
      <b v-if="warningCount" class="warning-summary">주의 연결 {{ warningCount }}건</b>
    </div>
    <p v-if="graph.emptyServiceScope" class="topology-scope-notice">{{ graph.scopeReadFailed ? '서비스 또는 권한 목록을 조회하지 못해 선택한 프로젝트의 연결을 확인할 수 없습니다. 아래 조회 실패 항목을 확인하세요.' : '선택한 프로젝트에 표시 조건과 일치하는 논리 서비스 권한이 없습니다. 프로젝트의 서비스 권한을 확인하세요.' }}</p>
    <div v-if="hasSelection" class="selection-bar" aria-live="polite"><span>선택: <strong>{{ selectedNode?.title || selectedEdge?.detail }}</strong></span><button type="button" @click="showDetails">상세 정보 ↓</button><button type="button" @click="clearSelection">선택 해제</button></div>
    <div ref="viewport" class="map-viewport" tabindex="0" role="region" aria-label="시스템 연결도. 가로 또는 세로 스크롤로 전체 구성을 볼 수 있습니다.">
      <div class="map-scale" :style="{ width: graph.width * zoom + 'px', height: graph.height * zoom + 'px' }">
        <div class="map-canvas" :style="{ width: graph.width + 'px', height: graph.height + 'px', transform: `scale(${zoom})` }">
          <div v-for="(column, index) in graph.columns" :key="column.kicker" class="topology-column" :style="{ left: column.x + 'px', width: column.width + 'px', height: graph.mainHeight + 116 + 'px' }">
            <header><small>{{ String(index + 1).padStart(2, '0') }} · {{ column.kicker }}</small><strong>{{ column.title }}</strong><span>{{ column.count }}개</span></header>
            <p v-if="!column.count" class="column-empty">{{ index === 0 ? '프로젝트 연결 없음' : index === 2 ? '서비스 연결 없음' : index === 3 ? 'Target 모델 없음' : '조회된 자원 없음' }}</p>
          </div>
          <div class="dependency-heading" :style="{ top: graph.dependencyY - 34 + 'px' }"><strong>Gateway 저장·공유·관측 기능</strong><span>배포/기능 관계 · 통신 상태는 별도 검사</span></div>
          <svg class="topology-edges" :width="graph.width" :height="graph.height" :viewBox="`0 0 ${graph.width} ${graph.height}`" aria-label="구성요소 사이의 연결">
            <defs>
              <marker v-for="tone in tones" :id="`${markerPrefix}-${tone}`" :key="tone" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto" :class="`tone-${tone}`"><path d="M0,0 L7,3.5 L0,7 Z" fill="currentColor" /></marker>
              <marker :id="`${markerPrefix}-dependency`" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto" class="dependency-legend"><path d="M0,0 L7,3.5 L0,7 Z" fill="currentColor" /></marker>
            </defs>
            <g v-for="edge in graph.edges" :key="edge.id" :class="['topology-edge', `tone-${edge.status.tone}`, { 'dependency-edge': edge.kind === 'dependency', 'edge-dimmed': hasSelection && !edgeFocused(edge), 'edge-selected': hasSelection && edgeFocused(edge) }]">
              <title>{{ edge.detail }} · {{ edge.status.label }} · {{ edge.status.reason }}</title>
              <path :d="edge.path" class="connection-path" :marker-end="`url(#${markerPrefix}-${edge.kind === 'dependency' ? 'dependency' : edge.status.tone})`" />
              <path :d="edge.path" class="connection-hit" @click="selectEdge(edge)" />
              <g v-if="edge.kind !== 'dependency'" class="edge-label" role="button" tabindex="0" :aria-label="`${edge.detail} · ${edge.status.label}. 연결 상세 보기`" :aria-pressed="selectedEdgeId === edge.id" @click="selectEdge(edge)" @keydown.enter.prevent="selectEdge(edge)" @keydown.space.prevent="selectEdge(edge)">
                <rect :x="edge.labelX - 19" :y="edge.labelY - 11" width="38" height="22" rx="5" /><text :x="edge.labelX" :y="edge.labelY + 4" text-anchor="middle">{{ edge.label }}</text>
              </g>
            </g>
          </svg>
          <button v-for="node in graph.nodes" :key="node.id" type="button" :class="['topology-node', `kind-${node.kind}`, `tone-${node.status.tone}`, { selected: selectedId === node.id, 'node-dimmed': hasSelection && !highlight.has(node.id) }]" :style="{ left: node.x + 'px', top: node.y + 'px', width: node.width + 'px', height: node.height + 'px' }" :aria-pressed="selectedId === node.id" :aria-label="`${typeLabels[node.kind]} ${node.title}. ${node.status.label}. 상세 보기`" :title="`${node.title}\n${node.subtitle}\n${node.status.reason}`" @click="selectNode(node.id)">
            <span class="topology-node-top"><i class="topology-icon"><SystemTopologyIcon :kind="node.kind" /></i><small>{{ typeLabels[node.kind] }}</small><b class="node-status"><i></i>{{ node.status.label }}</b></span>
            <strong class="node-title">{{ node.title }}</strong>
            <span class="node-subtitle">{{ node.subtitle }}</span>
            <span class="node-lines"><span v-for="line in node.lines" :key="line">{{ line }}</span></span>
          </button>
        </div>
      </div>
    </div>
    <div class="topology-footer"><span>표시 노드 {{ graph.nodes.length }} · 연결 {{ graph.edges.length }}<template v-if="graph.hiddenModelCount"> · 미연결 모델 {{ graph.hiddenModelCount }}개 접힘</template></span><span>화살표는 저장된 구성입니다. 실시간 트래픽·최종 라우팅 결과가 아닙니다.</span></div>
    <aside v-if="selectedNode || selectedEdge" ref="inspector" class="topology-inspector" aria-live="polite">
      <header><div><span class="card-kicker">CONNECTION DETAILS</span><h3>{{ selectedNode?.title || '연결 상세' }}</h3></div><button type="button" class="inspector-close" aria-label="상세 정보 닫기" @click="clearSelection">×</button></header>
      <template v-if="selectedNode">
        <p :class="['inspector-status', `tone-${selectedNode.status.tone}`]"><b>{{ selectedNode.status.label }}</b>{{ selectedNode.status.reason }}</p>
        <dl class="inspector-facts"><div v-for="item in selectedNode.facts" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl>
        <div v-if="incidentEdges.length" class="inspector-connections"><strong>연결 관계 {{ incidentEdges.length }}개</strong><button v-for="edge in incidentEdges" :key="edge.id" type="button" @click="selectEdge(edge)"><span>{{ nodeName(edge.source) }} → {{ nodeName(edge.target) }}</span><b :class="`tone-${edge.status.tone}`">{{ edge.label }} · {{ edge.status.label }}</b></button></div>
        <ul class="inspector-notes"><li v-for="note in selectedNode.notes" :key="note">{{ note }}</li></ul>
      </template>
      <template v-else-if="selectedEdge">
        <p class="edge-detail-title">{{ selectedEdge.detail }}</p>
        <p :class="['inspector-status', `tone-${selectedEdge.status.tone}`]"><b>{{ selectedEdge.status.label }}</b>{{ selectedEdge.status.reason }}</p>
        <div class="edge-endpoints"><button type="button" @click="selectNode(selectedEdge.source)">{{ nodeName(selectedEdge.source) }}</button><span>→</span><button type="button" @click="selectNode(selectedEdge.target)">{{ nodeName(selectedEdge.target) }}</button></div>
      </template>
    </aside>
  </section>
</template>

<style scoped>
.topology-map { min-width:0; }
.topology-toolbar { display:flex; justify-content:space-between; align-items:flex-end; flex-wrap:wrap; gap:16px; padding:18px 22px; border-bottom:1px solid var(--border); }
.topology-filters { display:flex; align-items:flex-end; flex-wrap:wrap; gap:12px 16px; min-width:0; }
.topology-filters label:not(.unlinked-toggle) { display:grid; gap:7px; min-width:0; color:var(--text-soft); font-size:11px; font-weight:600; }
.topology-filters select { width:220px; max-width:100%; min-height:38px; border-radius:9px; font-size:12px; }
.unlinked-toggle { display:flex; align-items:center; gap:8px; min-height:38px; color:var(--text-soft); font-size:11px; cursor:pointer; }
.unlinked-toggle input { width:16px; height:16px; min-height:16px; padding:0; margin:0; flex:0 0 16px; accent-color:var(--accent-strong); }
.zoom-controls { display:flex; align-items:center; gap:6px; flex-shrink:0; }
.zoom-controls button { min-width:34px; min-height:34px; padding:0 8px; border:1px solid var(--border); border-radius:8px; background:var(--surface-2); color:var(--text); font-size:16px; }
.zoom-controls span { min-width:42px; color:var(--muted); font-size:11px; text-align:center; }
.zoom-controls .fit-button { font-size:11px; padding:0 11px; }
.topology-caption { display:grid; gap:6px; padding:18px 22px 12px; }
.topology-caption p { margin:0; color:var(--text-soft); font-size:12px; line-height:1.6; font-weight:600; }
.topology-caption > span { color:var(--muted); font-size:11px; line-height:1.6; }
.topology-caption b { color:var(--text-soft); font-weight:600; }
.topology-legend { display:flex; align-items:center; flex-wrap:wrap; gap:10px 16px; padding:0 22px 16px; font-size:10px; }
.topology-legend > span { display:inline-flex; align-items:center; gap:7px; }
.topology-legend i { width:18px; height:2px; background:currentColor; }
.tone-healthy { color:var(--accent-strong); --node-tone:var(--accent-strong); }
.tone-warning { color:var(--warning); --node-tone:var(--warning); }
.tone-error { color:var(--danger); --node-tone:var(--danger); }
.tone-muted { color:var(--muted); --node-tone:var(--muted); }
.tone-unknown { color:var(--muted); --node-tone:var(--muted); }
.dependency-legend { color:var(--info); }
.warning-summary { margin-left:auto; color:var(--warning); font-size:10px; font-weight:600; }
.topology-scope-notice { margin:0 22px 16px; padding:12px 14px; border:1px solid color-mix(in srgb,var(--warning) 35%,var(--border)); border-radius:9px; background:var(--warning-dim); color:var(--text-soft); font-size:12px; line-height:1.6; }
.selection-bar { display:flex; align-items:center; flex-wrap:wrap; gap:9px; margin:0 22px 14px; padding:10px 12px; border:1px solid var(--accent-border); border-radius:9px; background:var(--accent-dim); font-size:11px; }
.selection-bar > span { flex:1; min-width:160px; color:var(--text-soft); overflow-wrap:anywhere; line-height:1.5; }
.selection-bar button { min-height:28px; padding:0 8px; border:1px solid var(--border); border-radius:6px; background:var(--surface); color:var(--accent-strong); font-size:10px; }
.map-viewport { max-width:100%; max-height:1000px; min-width:0; overflow:auto; overscroll-behavior:contain; border-block:1px solid var(--border); scrollbar-width:thin; background-color:var(--bg-soft); background-image:radial-gradient(var(--border) 1px,transparent 1px); background-size:18px 18px; }
.map-scale { position:relative; margin:12px auto; }
.map-canvas { position:relative; transform-origin:top left; }
.topology-column { position:absolute; top:14px; border:1px dashed var(--border-strong); border-radius:12px; background:color-mix(in srgb,var(--surface) 65%,transparent); pointer-events:none; }
.topology-column header { position:relative; display:grid; gap:5px; padding:14px 12px; }
.topology-column header small { color:var(--accent-strong); font-size:8px; letter-spacing:.1em; font-weight:700; }
.topology-column header strong { color:var(--text-soft); font-size:12px; }
.topology-column header > span { position:absolute; right:12px; top:16px; color:var(--muted); font-size:9px; }
.column-empty { padding:0 12px; color:var(--faint); font-size:11px; }
.dependency-heading { position:absolute; left:294px; display:flex; gap:12px; align-items:center; color:var(--muted); font-size:10px; }
.dependency-heading strong { color:var(--text-soft); font-size:11px; }
.topology-edges { position:absolute; inset:0; overflow:visible; }
.connection-path { fill:none; stroke:currentColor; stroke-width:1.6; }
.tone-muted .connection-path,.tone-unknown .connection-path { stroke-dasharray:5 5; }
.dependency-edge { color:var(--info); }
.dependency-edge .connection-path { stroke-dasharray:5 7; stroke-width:1.3; opacity:.7; }
.connection-hit { fill:none; stroke:transparent; stroke-width:12; cursor:pointer; }
.edge-label { cursor:pointer; }
.edge-label rect { fill:var(--surface); stroke:currentColor; stroke-opacity:.4; }
.edge-label text { fill:currentColor; font-size:10px; font-weight:700; }
.edge-label:focus-visible { outline:none; }
.edge-label:focus-visible rect { stroke-width:2.5; stroke-opacity:1; }
.edge-dimmed { opacity:.16; }
.edge-selected .connection-path { stroke-width:2.7; }
.topology-node { position:absolute; display:flex; flex-direction:column; gap:8px; padding:13px; border:1px solid color-mix(in srgb,var(--node-tone) 40%,var(--border)); border-left:3px solid var(--node-tone); border-radius:10px; background:var(--surface-2); text-align:left; box-shadow:0 4px 12px color-mix(in srgb,var(--bg) 40%,transparent); transition:opacity .15s,border-color .15s; }
.topology-node:hover,.topology-node.selected { border-color:var(--accent-strong); box-shadow:0 0 0 2px var(--accent-dim); }
.topology-node.kind-gateway { border-color:var(--accent-border); border-left-color:var(--accent-strong); background:color-mix(in srgb,var(--accent-dim) 40%,var(--surface-2)); }
.topology-node-top { display:flex; align-items:center; gap:6px; min-width:0; }
.topology-icon { display:inline-flex; width:25px; height:25px; flex:0 0 25px; padding:4px; border:1px solid var(--border); border-radius:6px; background:var(--surface); color:var(--node-tone); }
.topology-node.kind-gateway .topology-icon { color:var(--accent-strong); }
.topology-icon svg { width:100%; height:100%; }
.topology-node-top > small { min-width:0; overflow:hidden; color:var(--muted); font-size:9px; text-overflow:ellipsis; white-space:nowrap; }
.node-status { display:flex; align-items:center; gap:4px; margin-left:auto; color:var(--node-tone); font-size:9px; white-space:nowrap; font-weight:600; }
.node-status i { width:5px; height:5px; flex:0 0 5px; border-radius:50%; background:currentColor; }
.node-title { display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; min-height:36px; color:var(--text); font-size:13px; font-weight:700; line-height:1.4; overflow-wrap:anywhere; }
.node-subtitle { overflow:hidden; color:var(--muted); font:10px/1.5 Consolas,monospace; text-overflow:ellipsis; white-space:nowrap; }
.node-lines { display:grid; gap:4px; padding-top:6px; border-top:1px solid var(--border); color:var(--text-soft); font-size:10px; line-height:1.4; }
.node-lines > span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.node-dimmed { opacity:.33; }
.topology-footer { display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px; padding:14px 22px; color:var(--muted); font-size:10px; line-height:1.6; }
.topology-inspector { margin:0 22px 22px; padding:18px; border:1px solid var(--accent-border); border-radius:12px; background:var(--surface-2); scroll-margin:90px; }
.topology-inspector > header { display:flex; align-items:flex-start; justify-content:space-between; gap:14px; margin-bottom:14px; }
.topology-inspector h3 { margin:5px 0 0; color:var(--text); font-size:16px; line-height:1.5; overflow-wrap:anywhere; }
.inspector-close { width:30px; height:30px; flex:0 0 30px; border:1px solid var(--border); border-radius:8px; background:var(--surface); font-size:18px; }
.inspector-status { display:flex; align-items:flex-start; gap:10px; margin:0 0 16px; padding:10px 12px; border-left:3px solid currentColor; border-radius:4px; background:var(--surface); font-size:12px; line-height:1.6; }
.inspector-status b { flex:0 0 auto; }
.inspector-facts { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; margin:0; }
.inspector-facts > div { min-width:0; padding:11px 12px; border:1px solid var(--border); border-radius:8px; background:var(--surface); }
.inspector-facts dt { margin-bottom:6px; color:var(--muted); font-size:10px; }
.inspector-facts dd { margin:0; color:var(--text-soft); font-size:12px; line-height:1.6; white-space:pre-line; overflow-wrap:anywhere; }
.inspector-connections { display:grid; gap:7px; margin-top:16px; }
.inspector-connections > strong { color:var(--text-soft); font-size:12px; }
.inspector-connections button { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px; min-height:40px; padding:9px 11px; border:1px solid var(--border); border-radius:7px; background:var(--surface); text-align:left; font-size:11px; line-height:1.5; }
.inspector-connections button span { min-width:0; overflow-wrap:anywhere; }
.inspector-connections button b { font-size:10px; }
.inspector-notes { margin:16px 0 0; padding-left:18px; color:var(--muted); font-size:11px; line-height:1.7; }
.edge-detail-title { margin:0 0 14px; font-size:13px; line-height:1.6; overflow-wrap:anywhere; }
.edge-endpoints { display:flex; align-items:center; gap:10px; }
.edge-endpoints button { min-width:0; flex:1; min-height:40px; padding:10px; border:1px solid var(--border); border-radius:8px; background:var(--surface); color:var(--text); font-size:12px; line-height:1.5; overflow-wrap:anywhere; }
@media(max-width:720px) { .topology-toolbar,.topology-caption { padding:16px; }.topology-legend,.topology-footer { padding-inline:16px; }.topology-filters { width:100%; }.topology-filters label:not(.unlinked-toggle) { flex:1; min-width:150px; }.topology-filters select { width:100%; }.unlinked-toggle { width:100%; }.warning-summary { margin-left:0; }.selection-bar,.topology-scope-notice { margin-inline:16px; }.topology-inspector { margin:0 16px 16px; padding:14px; }.inspector-facts { grid-template-columns:minmax(0,1fr); }.inspector-status { display:grid; gap:4px; }.map-viewport { max-height:640px; } }
</style>
