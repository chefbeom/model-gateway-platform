<script setup lang="ts">
import { ref } from 'vue'
import type { AdminAuth } from '../api'
import { formatDateTime } from '../dateTime'
import FlowCanvas from './FlowCanvas.vue'
import FlowInspector from './FlowInspector.vue'
import FlowPalette from './FlowPalette.vue'
import FlowPlanModal from './FlowPlanModal.vue'
import FlowToolbar from './FlowToolbar.vue'
import { NEW_SERVICE_KEY, useFlowEditor, type CanvasHandle } from './useFlowEditor'

const props = defineProps<{ organizationId: string; auth: AdminAuth; platformAdmin: boolean }>()

const canvasRef = ref<CanvasHandle | null>(null)
const stageRef = ref<HTMLElement | null>(null)
const editor = useFlowEditor(props, canvasRef, stageRef)
const {
  loadState, loadError, message, base, graph, viewport, selection, serviceOptions, selectedKey, restoredAt, hasNewDraft,
  layoutAvailable, layoutDirty, planOpen, planPhase, plan, planResults, planBusy,
  narrow, compact, paletteOpen, inspectorOpen,
  issues, nodeChanges, changeCount, errorCount, warningCount, busy, readOnly, canUndo, canRedo, serviceLabel
} = editor

function onServiceChange(event: Event) {
  editor.selectService((event.target as HTMLSelectElement).value)
}
</script>

<template>
  <section class="page-stack fd-page">
    <div class="page-hero">
      <div>
        <p class="eyebrow">SERVICE FLOW</p>
        <h1>서비스 플로우 디자이너 <span class="status-chip standby fd-badge">EXPERIMENTAL</span></h1>
        <p>기존 화면과 같은 API를 사용합니다. 변경은 '변경 검토 → 적용' 후에만 저장됩니다.</p>
      </div>
      <div v-if="organizationId" class="hero-actions fd-service-picker">
        <label for="fd-service-select">서비스</label>
        <select id="fd-service-select" :value="selectedKey" :disabled="busy || planOpen" @change="onServiceChange">
          <option v-for="service in serviceOptions" :key="service.id" :value="service.id">{{ service.displayName || service.serviceKey }} ({{ service.serviceKey }})</option>
          <option :value="NEW_SERVICE_KEY">＋ 새 서비스 초안{{ hasNewDraft ? ' · 초안 있음' : '' }}</option>
        </select>
      </div>
    </div>

    <div v-if="!organizationId" class="workspace-required">
      <span>WORKSPACE REQUIRED</span>
      <div><strong>워크스페이스를 먼저 선택하세요</strong><p>조직을 선택하면 서비스 파이프라인을 캔버스로 구성할 수 있습니다.</p></div>
    </div>

    <template v-else>
      <p v-if="narrow" class="inline-alert" role="status">편집은 작업 영역 폭 640px 이상에서 지원합니다. 지금은 보기 전용입니다.</p>
      <p v-if="restoredAt" class="inline-alert" role="status">저장되지 않은 초안을 복원했습니다(불러온 시각 {{ formatDateTime(restoredAt) }}). 새로고침하면 서버 상태와 병합합니다.</p>
      <p v-if="message" class="inline-alert" role="status">{{ message }}</p>

      <div v-if="loadState === 'error'" class="fd-error" role="alert">
        <strong>{{ loadError }}</strong>
        <p>서비스 구성을 불러오지 못했습니다. 다른 서비스를 선택하거나 다시 시도하세요.</p>
        <button class="secondary-button" type="button" @click="editor.retry()">다시 시도</button>
      </div>
      <p v-else-if="loadState === 'loading' && !graph" class="inline-alert" role="status">서비스 구성을 불러오고 있습니다…</p>

      <template v-if="base && graph">
        <FlowToolbar
          :can-undo="canUndo" :can-redo="canRedo" :change-count="changeCount"
          :error-count="errorCount" :warning-count="warningCount"
          :layout-dirty="layoutDirty" :layout-available="layoutAvailable"
          :busy="busy" :read-only="readOnly" :compact="compact" :zoom="viewport.zoom"
          @action="editor.onToolbarAction"
        />
        <div ref="stageRef" class="fd-stage" :class="{ 'fd-compact': compact }" aria-busy="false">
          <FlowPalette
            v-if="!compact || paletteOpen"
            class="fd-aside fd-aside-left"
            :snapshot="base" :graph="graph" :read-only="readOnly"
            @command="editor.handleCommand" @select-node="editor.focusNode"
          />
          <FlowCanvas
            ref="canvasRef"
            class="fd-canvas"
            :graph="graph" :snapshot="base" :viewport="viewport" :selection="selection"
            :issues="issues" :node-changes="nodeChanges" :read-only="readOnly"
            @open-inspector="editor.openInspector" @update:viewport="editor.updateViewport" @update:selection="editor.updateSelection"
            @command="editor.handleCommand" @action="(action: 'undo' | 'redo') => (action === 'undo' ? editor.undo() : editor.redo())"
          />
          <FlowInspector
            v-if="!compact || inspectorOpen"
            class="fd-aside fd-aside-right"
            :graph="graph" :snapshot="base" :selection="selection" :issues="issues" :read-only="readOnly"
            @command="editor.handleCommand" @update:selection="editor.updateSelection" @focus-node="editor.focusNode"
          />
        </div>
        <footer class="fd-statusbar" aria-label="상태바">
          <span>변경 {{ changeCount }}건</span>
          <span :class="{ 'fd-bad': errorCount > 0 }">오류 {{ errorCount }}</span>
          <span :class="{ 'fd-warn': warningCount > 0 }">경고 {{ warningCount }}</span>
          <span>불러온 시각 {{ formatDateTime(base.loadedAt) }}</span>
          <span>확대 {{ Math.round(viewport.zoom * 100) }}%</span>
          <span v-if="layoutDirty" class="fd-warn">배치 미저장</span>
        </footer>
      </template>
    </template>

    <FlowPlanModal
      :open="planOpen" :plan="plan" :phase="planPhase" :results="planResults"
      :service-label="serviceLabel" :busy="planBusy"
      @close="editor.closePlan" @apply="editor.applyPlan" @rebase="editor.mergeRemote"
    />
  </section>
</template>

<style scoped>
.fd-page { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; gap: 10px; }
.fd-page > .page-hero { flex: none; min-height: 0; align-items: center; gap: 18px; }
.fd-page > .page-hero .eyebrow { margin-bottom: 4px; }
.fd-page > .page-hero h1 { margin-bottom: 4px; font-size: clamp(20px, 1.7vw, 26px); }
.fd-page > .page-hero > div > p:last-child { font-size: 12px; line-height: 1.45; }
.fd-badge { margin-left: 10px; vertical-align: middle; letter-spacing: .12em; }
.fd-service-picker { display: grid; gap: 6px; min-width: 260px; max-width: 360px; }
.fd-service-picker label { color: var(--muted); font-size: 10px; font-weight: 700; }
.fd-error { display: grid; gap: 8px; justify-items: start; padding: 18px; border: 1px solid color-mix(in srgb, var(--danger) 40%, transparent); border-radius: 14px; background: var(--danger-dim); color: var(--text-soft); }
.fd-error strong { color: var(--danger); font-size: 13px; }
.fd-error p { margin: 0; font-size: 11px; color: var(--muted); }
.fd-stage { position: relative; flex: 1 1 520px; min-height: 420px; display: grid; grid-template-columns: clamp(232px, 14vw, 310px) minmax(480px, 1fr) clamp(280px, 17vw, 380px); height: clamp(480px, calc(100dvh / var(--console-zoom, 1) - 240px), 1600px); border: 1px solid var(--border); border-radius: 14px; background: var(--surface); overflow: hidden; }
.fd-stage.fd-compact { grid-template-columns: minmax(0, 1fr); }
.fd-canvas { min-width: 0; min-height: 0; }
.fd-aside { min-height: 0; overflow: auto; background: var(--surface); }
.fd-aside-left { border-right: 1px solid var(--border); }
.fd-aside-right { border-left: 1px solid var(--border); }
.fd-compact .fd-aside { position: absolute; top: 0; bottom: 0; z-index: 5; width: min(300px, 92%); box-shadow: var(--shadow); }
.fd-compact .fd-aside-left { left: 0; }
.fd-compact .fd-aside-right { right: 0; }
.fd-statusbar { flex: none; display: flex; flex-wrap: wrap; gap: 6px 18px; padding: 8px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); color: var(--muted); font-size: 10px; font-weight: 600; }
.fd-bad { color: var(--danger); }
.fd-warn { color: var(--warning); }
</style>
