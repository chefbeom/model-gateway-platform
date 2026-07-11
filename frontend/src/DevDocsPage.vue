<script setup lang="ts">
import DevDocsLegacy from './DevDocsLegacy.vue'

type Destination = 'portal' | 'usage' | 'projects' | 'infrastructure' | 'services' | 'teams' | 'notifications'
const emit = defineEmits<{ navigate: [target: Destination] }>()
</script>

<template>
  <section class="docs-introduction">
    <article class="role-guide surface-card">
      <div>
        <p class="eyebrow">ROLE-BASED CONSOLE</p>
        <h2>내게 필요한 화면만 표시됩니다</h2>
        <p>일반 사용자는 <strong>내 API</strong>, <strong>API 사용량</strong>, <strong>Dev-Docs</strong>만 사용합니다. GPU 서버, LM Studio Runtime, 라우팅, 알림과 조직 운영은 관리자 전용입니다.</p>
      </div>
      <div class="role-guide-actions"><button class="primary-button" @click="emit('navigate', 'portal')">내 API 열기</button><button class="secondary-button" @click="emit('navigate', 'usage')">사용량 보기</button></div>
      <div class="role-steps"><article><span>01</span><div><strong>일반 사용자</strong><small>프로젝트 선택 → 사용 모델 확인 → API 키 발급(권한이 있는 경우) → OpenAI 호환 API 연결</small></div></article><article><span>02</span><div><strong>프로젝트 소유자 / 팀 관리자</strong><small>자신이 관리하는 프로젝트에서만 API 키를 발급합니다. 키 원문은 한 번만 표시됩니다.</small></div></article><article><span>03</span><div><strong>조직 관리자</strong><small>인프라, 런타임, 모델 라우팅, 사용자와 알림을 운영합니다.</small></div></article></div>
    </article>
    <DevDocsLegacy @navigate="emit('navigate', $event)" />
  </section>
</template>

<style scoped>
.docs-introduction { display: grid; gap: 20px; }
.role-guide { padding: 24px; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 20px; border-color: var(--accent-border); background: linear-gradient(125deg, var(--surface), var(--accent-dim)); }
.role-guide h2 { margin: 7px 0 9px; font-size: clamp(21px, 2.5vw, 29px); letter-spacing: -.04em; }
.role-guide p:not(.eyebrow) { max-width: 770px; margin: 0; color: var(--text-soft); font-size: 12px; line-height: 1.75; }
.role-guide-actions { display: flex; align-items: start; flex-wrap: wrap; gap: 8px; }
.role-steps { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; }
.role-steps article { min-height: 84px; padding: 13px; display: grid; grid-template-columns: 27px 1fr; gap: 9px; border: 1px solid var(--border); border-radius: 12px; background: color-mix(in srgb, var(--surface) 88%, transparent); }
.role-steps span { width: 27px; height: 27px; display: grid; place-content: center; border-radius: 8px; background: var(--accent-dim); color: var(--accent-strong); font-size: 9px; font-weight: 900; }
.role-steps div { display: grid; gap: 4px; }.role-steps strong { font-size: 11px; }.role-steps small { color: var(--muted); font-size: 9px; line-height: 1.5; }
@media (max-width: 760px) { .role-guide { grid-template-columns: 1fr; }.role-guide-actions { align-items: stretch; }.role-steps { grid-template-columns: 1fr; } }
</style>
