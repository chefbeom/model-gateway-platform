<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import DevDocsArticle from './dev-docs/DevDocsArticle.vue'
import { devDocs, docGroups } from './dev-docs/catalog'
import { runtimeDiagnosticsDoc } from './dev-docs/runtimeDiagnostics'
import type { DocAudience, DocsDestination } from './dev-docs/types'

const emit = defineEmits<{ navigate: [target: DocsDestination] }>()
const documents = [...devDocs, runtimeDiagnosticsDoc]
const savedDoc = sessionStorage.getItem('aiconnect.devdocs.article')
const query = ref('')
const audience = ref<'전체' | DocAudience>('전체')
const articleTop = ref<HTMLElement | null>(null)

function findDocument(id?: string | null) {
  return documents.find(doc => doc.id === id) ?? documents[0]
}

function searchDocuments(value: string) {
  const normalized = value.trim().toLowerCase()
  if (!normalized) return []
  return documents.filter(doc => [doc.title, doc.shortTitle, doc.description, ...doc.keywords]
    .some(item => item.toLowerCase().includes(normalized)))
}

const activeId = ref(findDocument(savedDoc).id)
const activePage = computed(() => findDocument(activeId.value))
const activeIndex = computed(() => documents.findIndex(doc => doc.id === activePage.value.id))
const previousPage = computed(() => activeIndex.value > 0 ? documents[activeIndex.value - 1] : null)
const nextPage = computed(() => activeIndex.value < documents.length - 1 ? documents[activeIndex.value + 1] : null)
const results = computed(() => searchDocuments(query.value).slice(0, 8))

function docsForGroup(group: typeof docGroups[number]) {
  return documents.filter(doc => doc.group === group && (audience.value === '전체' || doc.audience === audience.value || doc.audience === '공통'))
}

async function selectDoc(id: string) {
  activeId.value = id
  query.value = ''
  sessionStorage.setItem('aiconnect.devdocs.article', id)
  await nextTick()
  articleTop.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function scrollToSection(id: string) {
  document.getElementById(`doc-section-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <section ref="articleTop" class="docs-site">
    <header class="docs-page-hero">
      <div>
        <p class="docs-eyebrow">KNOWLEDGE BASE</p>
        <h1>Dev-Docs</h1>
        <p>AICONNECT를 처음 연결하는 개발자부터 인프라를 운영하는 관리자까지, 현재 제품 흐름을 한곳에서 확인합니다.</p>
      </div>
      <div class="docs-page-meta">
        <span><small>문서 기준</small><strong>2026. 07. 13.</strong></span>
        <span><small>현재 문서</small><strong>{{ documents.length }}개</strong></span>
        <a href="https://lmstudio.ai/docs/developer" target="_blank" rel="noreferrer">LM Studio 개발자 문서 ↗</a>
      </div>
    </header>

    <section class="docs-controls surface-card" aria-label="문서 검색과 대상 필터">
      <div class="docs-search">
        <span>⌕</span>
        <input v-model="query" aria-label="문서 검색" placeholder="API 키, Runtime, 외부 AI, 배포 명령어 검색" />
        <kbd>DOCS</kbd>
        <div v-if="query.trim()" class="search-results">
          <button v-for="item in results" :key="item.id" @click="selectDoc(item.id)"><span>{{ item.icon }}</span><div><strong>{{ item.title }}</strong><small>{{ item.description }}</small></div><b>→</b></button>
          <p v-if="!results.length">일치하는 문서가 없습니다.</p>
        </div>
      </div>
      <div class="audience-bar">
        <span>문서 대상</span>
        <button v-for="item in (['전체', '사용자', '관리자'] as const)" :key="item" :class="{ active: audience === item }" @click="audience = item">{{ item }}</button>
        <i></i>
        <small>기능 설명 · 설정 예시 · 운영 점검을 현재 구현 기준으로 제공합니다.</small>
      </div>
    </section>

    <div class="docs-layout">
      <aside class="docs-sidebar surface-card">
        <div class="docs-rail-title"><span>&lt;/&gt;</span><div><strong>문서 탐색</strong><small>{{ audience }} 대상</small></div></div>
        <nav aria-label="Dev-Docs 문서 목록">
          <section v-for="group in docGroups" :key="group">
            <p>{{ group }}</p>
            <button v-for="doc in docsForGroup(group)" :key="doc.id" :class="{ active: activePage.id === doc.id }" @click="selectDoc(doc.id)">
              <span>{{ doc.icon }}</span>
              <div><strong>{{ doc.shortTitle }}</strong><small>{{ doc.audience }} · {{ doc.minutes }}분</small></div>
              <i></i>
            </button>
          </section>
        </nav>
        <div class="sidebar-help"><span>i</span><p><strong>문서 유지 원칙</strong><small>새 기능, 설정 변경, 운영 절차는 관련 문서에 반영하고 현재 동작만 남깁니다.</small></p></div>
      </aside>

      <main class="docs-main">
        <DevDocsArticle :key="activePage.id" :page="activePage" @navigate="emit('navigate', $event)" />
        <footer class="article-pagination">
          <button v-if="previousPage" @click="selectDoc(previousPage.id)"><span>← 이전</span><strong>{{ previousPage.shortTitle }}</strong></button>
          <i></i>
          <button v-if="nextPage" class="next" @click="selectDoc(nextPage.id)"><span>다음 →</span><strong>{{ nextPage.shortTitle }}</strong></button>
        </footer>
      </main>

      <aside class="page-toc surface-card">
        <p>이 페이지에서</p>
        <button v-for="(section, index) in activePage.sections" :key="section.id" @click="scrollToSection(section.id)"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}</button>
        <div><strong>문서를 찾지 못했나요?</strong><small>오류 코드, 화면 이름 또는 설정 필드명으로 검색해 보세요.</small></div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.docs-site { width: 100%; display: grid; gap: 22px; animation: docs-in .28s ease both; }
@keyframes docs-in { from { opacity: 0; transform: translateY(5px); } }
.docs-page-hero { min-height: 116px; display: flex; justify-content: space-between; align-items: flex-start; gap: 34px; }
.docs-eyebrow { margin: 0 0 10px; color: var(--accent-strong); font-size: 9px; font-weight: 900; letter-spacing: .18em; }
.docs-page-hero h1 { margin: 0 0 10px; font-size: clamp(32px, 3.4vw, 46px); letter-spacing: -.05em; }
.docs-page-hero > div:first-child > p:last-child { max-width: 760px; margin: 0; color: var(--muted); font-size: 13px; line-height: 1.7; }
.docs-page-meta { display: flex; align-items: stretch; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.docs-page-meta > span, .docs-page-meta > a { min-height: 52px; padding: 10px 13px; display: grid; align-content: center; gap: 4px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface-2); text-decoration: none; }
.docs-page-meta small { color: var(--faint); font-size: 8px; font-weight: 800; letter-spacing: .08em; }
.docs-page-meta strong, .docs-page-meta a { color: var(--text); font-size: 10px; }
.docs-page-meta a { color: var(--accent-strong); font-weight: 800; }
.docs-controls { padding: 14px; display: grid; grid-template-columns: minmax(320px, 1.3fr) minmax(460px, 1fr); gap: 14px; align-items: center; box-shadow: none; }
.docs-search { position: relative; display: grid; grid-template-columns: 18px 1fr auto; gap: 9px; align-items: center; padding: 0 12px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface-2); }
.docs-search > span { color: var(--accent-strong); }
.docs-search input { height: 42px; min-height: 42px; padding: 0; border: 0; outline: 0; background: transparent; box-shadow: none; color: var(--text); font-size: 11px; }
.docs-search kbd { padding: 3px 5px; border: 1px solid var(--border); border-radius: 5px; color: var(--faint); font-size: 7px; }
.search-results { position: absolute; z-index: 20; top: 49px; right: 0; left: 0; overflow: hidden; padding: 6px; border: 1px solid var(--border); border-radius: 13px; background: var(--surface); box-shadow: var(--shadow); }
.search-results button { width: 100%; display: grid; grid-template-columns: 30px 1fr auto; gap: 9px; align-items: center; padding: 10px; border: 0; border-radius: 9px; background: transparent; color: var(--text); text-align: left; }
.search-results button:hover { background: var(--accent-dim); }
.search-results button > span { color: var(--accent-strong); font-weight: 900; }
.search-results strong, .search-results small { display: block; }
.search-results strong { font-size: 10px; }
.search-results small { margin-top: 3px; overflow: hidden; color: var(--muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.search-results b { color: var(--faint); font-size: 10px; }
.search-results p { margin: 0; padding: 15px; color: var(--muted); font-size: 10px; text-align: center; }
.audience-bar { min-width: 0; display: flex; align-items: center; gap: 7px; }
.audience-bar > span { margin-right: 3px; color: var(--muted); font-size: 9px; font-weight: 800; }
.audience-bar button { padding: 7px 11px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface-2); color: var(--muted); font-size: 9px; font-weight: 800; }
.audience-bar button.active { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.audience-bar i { flex: 1; }
.audience-bar small { max-width: 235px; color: var(--faint); font-size: 8px; line-height: 1.5; }
.docs-layout { display: grid; grid-template-columns: 230px minmax(0, 1fr) 210px; gap: 22px; align-items: start; }
.docs-sidebar, .page-toc { position: sticky; top: 92px; max-height: calc(100vh - 116px); overflow: auto; box-shadow: none; }
.docs-sidebar { padding: 13px; }
.docs-rail-title { min-height: 54px; margin-bottom: 12px; padding: 7px 8px 13px; display: grid; grid-template-columns: 31px 1fr; gap: 9px; align-items: center; border-bottom: 1px solid var(--border); }
.docs-rail-title > span { width: 31px; height: 31px; display: grid; place-items: center; border: 1px solid var(--accent-border); border-radius: 9px; background: var(--accent-dim); color: var(--accent-strong); font-size: 9px; font-weight: 900; }
.docs-rail-title strong, .docs-rail-title small { display: block; }
.docs-rail-title strong { font-size: 11px; }
.docs-rail-title small { margin-top: 3px; color: var(--faint); font-size: 8px; }
.docs-sidebar nav { display: grid; gap: 17px; }
.docs-sidebar nav section > p, .page-toc > p { margin: 0 0 7px; padding: 0 8px; color: var(--faint); font-size: 8px; font-weight: 900; letter-spacing: .12em; text-transform: uppercase; }
.docs-sidebar nav button { position: relative; width: 100%; min-height: 48px; display: grid; grid-template-columns: 29px 1fr 3px; gap: 9px; align-items: center; padding: 8px; border: 0; border-radius: 10px; background: transparent; color: var(--muted); text-align: left; }
.docs-sidebar nav button:hover, .docs-sidebar nav button.active { background: var(--accent-dim); color: var(--text); }
.docs-sidebar nav button > span { width: 29px; height: 29px; display: grid; place-items: center; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: var(--accent-strong); font-size: 9px; font-weight: 900; }
.docs-sidebar nav button > i { height: 0; border-radius: 3px; background: var(--accent-strong); transition: height .18s; }
.docs-sidebar nav button.active > i { height: 21px; box-shadow: 0 0 12px var(--accent-strong); }
.docs-sidebar strong, .docs-sidebar small { display: block; }
.docs-sidebar strong { font-size: 10px; }
.docs-sidebar small { margin-top: 3px; color: var(--faint); font-size: 8px; }
.sidebar-help { display: grid; grid-template-columns: 25px 1fr; gap: 9px; margin-top: 18px; padding: 11px; border: 1px solid var(--accent-border); border-radius: 11px; background: var(--accent-dim); }
.sidebar-help > span { width: 23px; height: 23px; display: grid; place-items: center; border-radius: 7px; background: var(--surface); color: var(--accent-strong); font-size: 9px; font-weight: 900; }
.sidebar-help p { margin: 0; }
.sidebar-help strong, .sidebar-help small { display: block; }
.sidebar-help strong { font-size: 9px; }
.sidebar-help small { margin-top: 4px; color: var(--muted); font-size: 8px; line-height: 1.55; }
.docs-main { min-width: 0; }
.page-toc { display: grid; gap: 3px; padding: 16px 13px; }
.page-toc button { display: grid; grid-template-columns: 25px 1fr; gap: 5px; padding: 8px 9px; border: 0; border-left: 1px solid var(--border); background: transparent; color: var(--muted); font-size: 9px; line-height: 1.4; text-align: left; }
.page-toc button:hover { border-left-color: var(--accent); color: var(--text); }
.page-toc button span { color: var(--faint); font-size: 8px; }
.page-toc > div { margin-top: 15px; padding: 12px; border: 1px solid var(--border); border-radius: 11px; background: var(--surface-2); }
.page-toc strong, .page-toc small { display: block; }
.page-toc strong { font-size: 9px; }
.page-toc small { margin-top: 5px; color: var(--muted); font-size: 8px; line-height: 1.55; }
.article-pagination { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr); gap: 12px; padding-top: 18px; border-top: 1px solid var(--border); }
.article-pagination button { display: grid; gap: 4px; padding: 14px; border: 1px solid var(--border); border-radius: 12px; background: var(--surface); color: var(--text); text-align: left; }
.article-pagination button:hover { border-color: var(--accent-border); background: var(--accent-dim); }
.article-pagination button.next { text-align: right; }
.article-pagination span { color: var(--muted); font-size: 8px; }
.article-pagination strong { font-size: 11px; }
@media (max-width: 1260px) { .docs-layout { grid-template-columns: 220px minmax(0, 1fr); } .page-toc { display: none; } .docs-controls { grid-template-columns: 1fr; } }
@media (max-width: 860px) { .docs-page-hero { display: grid; } .docs-page-meta { justify-content: flex-start; } .docs-layout { grid-template-columns: 1fr; } .docs-sidebar { position: static; max-height: none; overflow: visible; } .docs-sidebar nav { grid-template-columns: repeat(2, minmax(0, 1fr)); } .docs-main { grid-row: 2; } .audience-bar small { display: none; } }
@media (max-width: 560px) { .docs-sidebar nav { grid-template-columns: 1fr; } .audience-bar > span { display: none; } .docs-page-meta > span { flex: 1; } .docs-page-meta > a { width: 100%; } }
</style>
