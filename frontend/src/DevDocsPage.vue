<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DevDocsArticle from './dev-docs/DevDocsArticle.vue'
import { devDocs, docGroups, DOCS_REVIEWED_AT } from './dev-docs/catalog'
import { documentHash, documentIdFromHash, filterDocuments, searchDocuments, type AudienceFilter } from './dev-docs/navigation'
import type { DocsDestination } from './dev-docs/types'

const emit = defineEmits<{ navigate: [target: DocsDestination] }>()
const storageKey = 'aiconnect.devdocs.article'
function savedDocument() { try { return sessionStorage.getItem(storageKey) } catch { return null } }
function rememberDocument(id: string) { try { sessionStorage.setItem(storageKey, id) } catch { /* Storage is optional. */ } }
function findDocument(id?: string | null) { return devDocs.find(doc => doc.id === id) ?? devDocs[0]! }

const activeId = ref(findDocument(documentIdFromHash(window.location.hash) ?? savedDocument()).id)
const query = ref('')
const audience = ref<AudienceFilter>('전체')
const articleTop = ref<HTMLElement | null>(null)
const mobile = ref(window.matchMedia('(max-width: 900px)').matches)
const navigationOpen = ref(!mobile.value)
const activeSection = ref('')
const activePage = computed(() => findDocument(activeId.value))
const visibleDocuments = computed(() => filterDocuments(devDocs, audience.value))
const activeIndex = computed(() => visibleDocuments.value.findIndex(doc => doc.id === activeId.value))
const previousPage = computed(() => activeIndex.value > 0 ? visibleDocuments.value[activeIndex.value - 1] : null)
const nextPage = computed(() => activeIndex.value >= 0 ? visibleDocuments.value[activeIndex.value + 1] : null)
const results = computed(() => searchDocuments(devDocs, query.value, audience.value))
const visibleGroups = computed(() => docGroups.map(group => ({ group, documents: visibleDocuments.value.filter(doc => doc.group === group) })).filter(item => item.documents.length))

async function selectDoc(id: string, replace = false) {
  const target = findDocument(id)
  if (!visibleDocuments.value.some(doc => doc.id === target.id)) audience.value = '전체'
  activeId.value = target.id
  query.value = ''
  rememberDocument(target.id)
  const hash = documentHash(target.id)
  if (window.location.hash !== hash) window.history[replace ? 'replaceState' : 'pushState'](null, '', hash)
  if (mobile.value) navigationOpen.value = false
  await nextTick()
  articleTop.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

watch(audience, () => {
  if (!visibleDocuments.value.some(doc => doc.id === activeId.value) && visibleDocuments.value[0]) {
    void selectDoc(visibleDocuments.value[0].id, true)
  }
})
watch(activeId, () => { activeSection.value = activePage.value.sections[0]?.id ?? '' }, { immediate: true })

function syncFromHash() {
  const id = documentIdFromHash(window.location.hash)
  if (!id) return
  const target = findDocument(id)
  if (!visibleDocuments.value.some(doc => doc.id === target.id)) audience.value = '전체'
  activeId.value = target.id
  query.value = ''
  rememberDocument(target.id)
  if (mobile.value) navigationOpen.value = false
  void nextTick(() => articleTop.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
function updateViewport(event: MediaQueryListEvent) {
  mobile.value = event.matches
  navigationOpen.value = !event.matches
}
const viewport = window.matchMedia('(max-width: 900px)')
onMounted(() => {
  window.addEventListener('popstate', syncFromHash)
  window.addEventListener('hashchange', syncFromHash)
  viewport.addEventListener('change', updateViewport)
})
onBeforeUnmount(() => {
  window.removeEventListener('popstate', syncFromHash)
  window.removeEventListener('hashchange', syncFromHash)
  viewport.removeEventListener('change', updateViewport)
})
function scrollToSection(id: string) {
  activeSection.value = id
  document.getElementById('doc-section-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function searchKeydown(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Escape') query.value = ''
  else if (event.key === 'Enter' && results.value[0]) { event.preventDefault(); void selectDoc(results.value[0].id) }
}
</script>

<template>
  <section class="docs-site">
    <header class="docs-page-header">
      <div><p class="docs-eyebrow">KNOWLEDGE BASE</p><h1>Dev-Docs</h1><p class="docs-intro">설정부터 실제 호출·진단까지, 현재 AICONNECT를 사용하는 방법.</p></div>
      <div class="docs-review"><span>구현 검토 <time datetime="2026-10-05">{{ DOCS_REVIEWED_AT }}</time></span><span>문서 {{ devDocs.length }}개</span></div>
    </header>

    <section class="docs-toolbar" aria-label="문서 검색과 대상 필터">
      <div class="docs-search"><span aria-hidden="true">⌕</span><input v-model="query" type="search" aria-label="문서 검색" aria-describedby="docs-search-help" placeholder="기능, 설정, 오류 코드 검색" @keydown="searchKeydown" /><button v-if="query" type="button" aria-label="검색 초기화" @click="query = ''">×</button></div>
      <div class="audience-bar" aria-label="문서 대상"><span>대상</span><button v-for="item in (['전체', '사용자', '관리자'] as const)" :key="item" type="button" :aria-pressed="audience === item" :class="{ active: audience === item }" @click="audience = item">{{ item }}</button></div>
      <span id="docs-search-help" class="docs-sr-only">본문과 예제를 함께 검색합니다. Enter로 첫 결과를 열고 Escape로 검색을 지웁니다.</span>
    </section>
    <section v-if="query.trim()" class="docs-search-results" aria-label="검색 결과">
      <p role="status">{{ audience }} 대상 · 검색 결과 {{ results.length }}개</p>
      <ul v-if="results.length"><li v-for="item in results" :key="item.id"><a :href="documentHash(item.id)" @click.prevent="selectDoc(item.id)"><strong>{{ item.title }}</strong><span>{{ item.description }}</span><small>{{ item.group }} · {{ item.audience }}</small></a></li></ul>
      <p v-else class="search-empty">일치하는 문서가 없습니다. 다른 검색어를 사용하거나 대상 필터를 ‘전체’로 바꿔 보세요.</p>
    </section>

    <div class="docs-layout">
      <details class="docs-navigation" :open="navigationOpen">
        <summary :aria-expanded="navigationOpen" @click.prevent="navigationOpen = !navigationOpen"><span>문서 목록</span><strong>{{ activePage.shortTitle }}</strong></summary>
        <nav aria-label="Dev-Docs 문서 목록">
          <section v-for="item in visibleGroups" :key="item.group"><h2>{{ item.group }} <span>{{ item.documents.length }}</span></h2><ul><li v-for="doc in item.documents" :key="doc.id"><a :href="documentHash(doc.id)" :aria-current="activeId === doc.id ? 'page' : undefined" @click.prevent="selectDoc(doc.id)"><span>{{ doc.shortTitle }}</span><small>{{ doc.audience }} · {{ doc.minutes }}분</small></a></li></ul></section>
        </nav>
      </details>

      <div ref="articleTop" class="docs-main">
        <details class="mobile-toc"><summary>이 페이지 목차 · {{ activePage.sections.length }}개</summary><nav aria-label="모바일 페이지 목차"><button v-for="section in activePage.sections" :key="section.id" type="button" @click="scrollToSection(section.id)">{{ section.title }}</button></nav></details>
        <DevDocsArticle :key="activePage.id" :page="activePage" @navigate="emit('navigate', $event)" />
        <nav class="article-pagination" aria-label="이전 다음 문서"><a v-if="previousPage" :href="documentHash(previousPage.id)" @click.prevent="selectDoc(previousPage.id)"><small>← 이전 문서</small><strong>{{ previousPage.shortTitle }}</strong></a><a v-if="nextPage" class="next" :href="documentHash(nextPage.id)" @click.prevent="selectDoc(nextPage.id)"><small>다음 문서 →</small><strong>{{ nextPage.shortTitle }}</strong></a></nav>
      </div>

      <aside class="page-toc"><p>이 페이지에서</p><nav aria-label="페이지 목차"><button v-for="(section, index) in activePage.sections" :key="section.id" type="button" :class="{ active: activeSection === section.id }" @click="scrollToSection(section.id)"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}</button></nav><small>오류 코드나 설정 이름으로 검색하면 관련 본문과 예제를 찾을 수 있습니다.</small></aside>
    </div>
  </section>
</template>

<style scoped>
.docs-site{width:100%;min-width:0;max-width:1440px;margin-inline:auto;color:var(--text);container:dev-docs / inline-size}
.docs-page-header{display:flex;justify-content:space-between;align-items:flex-end;gap:24px;padding-bottom:28px}
.docs-eyebrow{margin:0 0 10px;color:var(--accent-strong);font-size:10px;font-weight:800;letter-spacing:.15em}
.docs-page-header h1{margin:0 0 10px;font-size:36px;line-height:1.15;letter-spacing:-.04em}
.docs-intro{margin:0;color:var(--muted);font-size:14px;line-height:1.7}
.docs-review{display:flex;gap:16px;flex-wrap:wrap;justify-content:flex-end;color:var(--muted);font-size:11px;line-height:1.6}
.docs-review time{margin-left:5px;color:var(--text-soft)}
.docs-toolbar{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:18px 0;border-block:1px solid var(--border)}
.docs-search{min-width:0;flex:1;max-width:660px;display:flex;align-items:center;gap:10px;border:1px solid var(--border-strong);border-radius:6px;background:var(--surface);padding:0 13px}
.docs-search:focus-within{border-color:var(--accent-strong)}
.docs-search>span{color:var(--accent-strong);font-size:20px}
.docs-search input{min-width:0;width:100%;height:44px;border:0;padding:0;background:transparent;color:var(--text);font-size:13px;outline:0;box-shadow:none}
.docs-search input::-webkit-search-cancel-button{display:none}
.docs-search button{flex:none;width:28px;height:28px;padding:0;border:0;background:transparent;color:var(--muted);font-size:20px}
.audience-bar{display:flex;align-items:center;gap:16px;flex:none}
.audience-bar>span{color:var(--muted);font-size:11px}
.audience-bar button{padding:8px 0;border:0;border-bottom:2px solid transparent;border-radius:0;background:transparent;color:var(--muted);font-size:12px;white-space:nowrap}
.audience-bar button.active{border-bottom-color:var(--accent-strong);color:var(--text);font-weight:700}
.docs-search-results{padding:16px 0 22px;border-bottom:1px solid var(--border)}
.docs-search-results>p{margin:0 0 12px;color:var(--muted);font-size:12px}
.docs-search-results ul{margin:0;padding:0;list-style:none;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 32px}
.docs-search-results a{display:grid;gap:4px;padding:12px 0;color:var(--text);text-decoration:none;border-bottom:1px solid var(--border)}
.docs-search-results a:hover strong{color:var(--accent-strong);text-decoration:underline}
.docs-search-results strong{font-size:14px}.docs-search-results a>span{color:var(--text-soft);font-size:12px;line-height:1.6}.docs-search-results small{color:var(--muted);font-size:10px}
.docs-search-results .search-empty{margin:0;line-height:1.7}
.docs-layout{display:grid;grid-template-columns:205px minmax(0,1fr) 190px;gap:32px;align-items:start;padding-top:32px}
.docs-navigation,.page-toc{position:sticky;top:88px;max-height:calc(100vh - 112px);overflow:auto;scrollbar-width:thin}
.docs-navigation{padding-right:18px;border-right:1px solid var(--border)}
.docs-navigation>summary{display:none}.docs-navigation nav{display:grid;gap:25px}
.docs-navigation h2{display:flex;justify-content:space-between;gap:8px;margin:0 0 9px;color:var(--muted);font-size:11px;font-weight:700}.docs-navigation h2 span{font-weight:400;color:var(--faint)}
.docs-navigation ul{margin:0;padding:0;list-style:none}
.docs-navigation a{display:grid;gap:3px;padding:9px 10px;margin-left:-10px;border-left:2px solid transparent;color:var(--text-soft);text-decoration:none}
.docs-navigation a:hover{color:var(--text);background:var(--surface-2)}
.docs-navigation a[aria-current='page']{border-left-color:var(--accent-strong);color:var(--accent-strong);background:var(--accent-dim)}
.docs-navigation a>span{font-size:13px;line-height:1.45}.docs-navigation a>small{font-size:10px;color:var(--muted);line-height:1.5}
.docs-main{min-width:0;scroll-margin-top:88px}
.page-toc>p{margin:0 0 12px;font-size:11px;font-weight:700;color:var(--muted)}
.page-toc nav{display:grid}.page-toc button{display:flex;align-items:baseline;gap:9px;padding:9px 12px;border:0;border-left:1px solid var(--border);border-radius:0;background:transparent;text-align:left;color:var(--muted);font-size:11px;line-height:1.6;overflow-wrap:anywhere}
.page-toc button span{font-size:9px;color:var(--faint)}.page-toc button.active,.page-toc button:hover{border-left-color:var(--accent-strong);color:var(--text)}
.page-toc>small{display:block;margin-top:20px;color:var(--muted);font-size:11px;line-height:1.7}
.article-pagination{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin-top:36px;padding-top:22px;border-top:1px solid var(--border)}
.article-pagination a{display:grid;gap:6px;color:var(--text);text-decoration:none;overflow-wrap:anywhere}.article-pagination a:hover strong{color:var(--accent-strong);text-decoration:underline}.article-pagination small{color:var(--muted);font-size:11px}.article-pagination strong{font-size:14px}.article-pagination .next{grid-column:2;text-align:right}
.mobile-toc{display:none}.docs-sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@container dev-docs (max-width:1120px){.docs-layout{grid-template-columns:185px minmax(0,1fr);gap:26px}.page-toc{display:none}.mobile-toc{display:block;margin-bottom:24px;padding-bottom:14px;border-bottom:1px solid var(--border);font-size:12px;color:var(--muted)}.mobile-toc summary{cursor:pointer}.mobile-toc nav{display:grid;gap:7px;margin-top:12px}.mobile-toc button{border:0;padding:4px 0;background:transparent;text-align:left;font-size:12px;color:var(--text-soft)}}
@media(max-width:900px){.docs-page-header{display:grid;gap:14px}.docs-review{justify-content:flex-start}.docs-layout{grid-template-columns:minmax(0,1fr);gap:25px;padding-top:24px}.docs-navigation{position:static;max-height:none;overflow:visible;border:0;border-bottom:1px solid var(--border);padding:0 0 14px}.docs-navigation>summary{display:list-item;cursor:pointer;color:var(--muted);font-size:12px;line-height:1.8}.docs-navigation>summary strong{margin-left:12px;color:var(--text);font-size:12px;font-weight:500}.docs-navigation nav{grid-template-columns:repeat(2,minmax(0,1fr));gap:22px;margin-top:22px;padding:0 10px 8px}.docs-toolbar{gap:16px;flex-wrap:wrap}.docs-search{max-width:none;flex-basis:100%}.audience-bar{gap:20px}.docs-main{scroll-margin-top:74px}}
@media(max-width:560px){.docs-page-header h1{font-size:30px}.docs-intro{font-size:13px}.docs-navigation nav,.docs-search-results ul{grid-template-columns:minmax(0,1fr)}.docs-review{gap:8px 16px}.docs-layout{gap:20px}.article-pagination{gap:12px}.article-pagination strong{font-size:12px}}
</style>
