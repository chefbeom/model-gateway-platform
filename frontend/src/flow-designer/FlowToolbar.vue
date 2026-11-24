<script setup lang="ts">
import type { DesignerMode, FlowToolbarAction } from './types'

const props = withDefaults(defineProps<{
  canUndo: boolean
  canRedo: boolean
  changeCount: number
  errorCount: number
  warningCount: number
  layoutDirty: boolean
  layoutAvailable: boolean
  busy: boolean
  readOnly: boolean
  compact: boolean
  zoom?: number
  mode?: DesignerMode
  canNormalize?: boolean
}>(), { zoom: 1, mode: 'advanced', canNormalize: false })

const emit = defineEmits<{ action: [FlowToolbarAction] }>()

function fire(action: FlowToolbarAction) {
  emit('action', action)
}

const editBlocked = () => props.readOnly || props.busy
</script>

<template>
  <div class="fd-toolbar" role="toolbar" aria-label="워크플로우 디자이너 도구 모음">
    <button v-if="compact" type="button" class="fd-toggle" aria-label="팔레트 열기/닫기" title="팔레트 열기/닫기" @click="fire('toggle-palette')">▤ 팔레트</button>

    <div class="fd-group">
      <button type="button" class="fd-btn fd-btn--icon" aria-label="실행 취소" title="실행 취소 (Ctrl+Z)" :disabled="!canUndo || editBlocked()" @click="fire('undo')">↶</button>
      <button type="button" class="fd-btn fd-btn--icon" aria-label="다시 실행" title="다시 실행 (Ctrl+Shift+Z)" :disabled="!canRedo || editBlocked()" @click="fire('redo')">↷</button>
    </div>

    <div class="fd-group">
      <button type="button" class="fd-btn" aria-label="화면 맞춤" title="화면 맞춤 (F)" @click="fire('fit')">화면 맞춤</button>
      <button type="button" class="fd-btn" aria-label="자동 정렬" title="노드를 처리 순서대로 자동 정렬합니다." :disabled="editBlocked()" @click="fire('auto-layout')">자동 정렬</button>
      <button
        v-if="canNormalize"
        type="button"
        class="fd-btn"
        aria-label="순서 정리"
        title="간선을 서버 처리 순서에 맞게 정리합니다."
        :disabled="editBlocked()"
        @click="fire('normalize-order')"
      >순서 정리</button>
    </div>

    <div class="fd-group" aria-label="확대/축소">
      <button type="button" class="fd-btn fd-btn--icon" aria-label="축소" title="축소 (-)" @click="fire('zoom-out')">−</button>
      <button type="button" class="fd-btn fd-zoom-readout" aria-label="배율 100%로 초기화" title="100%로 보기 (0)" @click="fire('zoom-reset')">{{ Math.round((zoom ?? 1) * 100) }}%</button>
      <button type="button" class="fd-btn fd-btn--icon" aria-label="확대" title="확대 (+)" @click="fire('zoom-in')">＋</button>
    </div>

    <div class="fd-group fd-mode" role="group" aria-label="표시 모드">
      <button type="button" class="fd-btn fd-mode-btn" :class="{ 'is-active': mode === 'basic' }" :aria-pressed="mode === 'basic'" title="핵심 노드만 보여 줍니다." @click="fire('mode-basic')">기본</button>
      <button type="button" class="fd-btn fd-mode-btn" :class="{ 'is-active': mode === 'advanced' }" :aria-pressed="mode === 'advanced'" title="마스킹 워커, 생성 파라미터, 실행 위치 등 고급 항목을 보여 줍니다." @click="fire('mode-advanced')">고급</button>
    </div>

    <button
      type="button"
      class="fd-btn fd-validate"
      :class="{ 'has-error': errorCount > 0, 'has-warning': errorCount === 0 && warningCount > 0 }"
      :aria-label="`검증 결과 보기: 오류 ${errorCount}건, 경고 ${warningCount}건`"
      title="검증 결과 보기"
      @click="fire('validate')"
    >
      검증 <span class="fd-count fd-count--error">E {{ errorCount }}</span><span class="fd-count fd-count--warning">W {{ warningCount }}</span>
    </button>

    <span class="fd-spacer"></span>

    <div class="fd-group">
      <button type="button" class="fd-btn" aria-label="변경 되돌리기" title="모든 로컬 변경을 서버 상태로 되돌립니다." :disabled="editBlocked() || changeCount === 0" @click="fire('revert')">되돌리기</button>
      <button type="button" class="fd-btn" aria-label="서버에서 새로고침" title="서버 상태를 다시 불러옵니다." :disabled="busy" @click="fire('reload')">새로고침</button>
      <button
        v-if="layoutAvailable"
        type="button"
        class="fd-btn"
        aria-label="배치 저장"
        title="노드 배치를 서버에 저장합니다."
        :disabled="editBlocked() || !layoutDirty"
        @click="fire('save-layout')"
      >
        배치 저장<i v-if="layoutDirty" class="fd-dirty" aria-label="저장되지 않음" title="저장되지 않음"></i>
      </button>
    </div>

    <button type="button" class="primary-button fd-plan" aria-label="변경 검토" title="변경 사항을 검토하고 적용합니다." :disabled="changeCount === 0 || busy" @click="fire('plan')">
      변경 검토 ({{ changeCount }})
    </button>

    <button v-if="compact" type="button" class="fd-toggle" aria-label="인스펙터 열기/닫기" title="인스펙터 열기/닫기" @click="fire('toggle-inspector')">인스펙터 ▥</button>
  </div>
</template>

<style scoped>
.fd-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
}
.fd-group { display: inline-flex; align-items: center; gap: 4px; padding-right: 12px; border-right: 1px solid var(--border); }
.fd-group:last-of-type { border-right: 0; }
.fd-spacer { flex: 1; }
.fd-mode { gap: 0; }
.fd-mode-btn { border-radius: 0; }
.fd-mode-btn:first-child { border-radius: 9px 0 0 9px; }
.fd-mode-btn:last-child { border-radius: 0 9px 9px 0; margin-left: -1px; }
.fd-mode-btn.is-active { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.fd-zoom-readout { min-width: 54px; font-variant-numeric: tabular-nums; }

.fd-btn, .fd-toggle {
  min-height: 32px;
  padding: 0 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--surface-2);
  color: var(--text);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
  transition: border-color .15s, background .15s;
}
.fd-btn--icon { width: 32px; padding: 0; font-size: 15px; }
.fd-btn:hover:not(:disabled), .fd-toggle:hover { border-color: var(--accent-border); background: var(--accent-dim); }
.fd-btn:disabled { cursor: not-allowed; opacity: .45; }

.fd-validate.has-error { border-color: var(--danger); }
.fd-validate.has-warning { border-color: var(--warning); }
.fd-count { font-size: 9px; font-weight: 800; color: var(--muted); }
.fd-validate.has-error .fd-count--error { color: var(--danger); }
.fd-validate.has-warning .fd-count--warning { color: var(--warning); }

.fd-dirty { width: 7px; height: 7px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 1px var(--accent-strong); }
.fd-plan { min-height: 34px; }
.fd-plan:disabled { cursor: not-allowed; opacity: .5; }

</style>
