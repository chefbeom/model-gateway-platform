<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import DevDocsArticle from './dev-docs/DevDocsArticle.vue'
import { devDocs, docGroups, findDoc, searchDocs } from './dev-docs/catalog'
import type { DocAudience, DocsDestination } from './dev-docs/types'

const emit = defineEmits<{ navigate: [target: DocsDestination] }>()
const savedDoc = sessionStorage.getItem('aiconnect.devdocs.article')
const activeId = ref(findDoc(savedDoc).id)
const query = ref('')
const audience = ref<'전체' | DocAudience>('전체')
const articleTop = ref<HTMLElement | null>(null)

const activePage = computed(() => findDoc(activeId.value))
const activeIndex = computed(() => devDocs.findIndex(doc => doc.id === activePage.value.id))
const previousPage = computed(() => activeIndex.value > 0 ? devDocs[activeIndex.value - 1] : null)
const nextPage = computed(() => activeIndex.value < devDocs.length - 1 ? devDocs[activeIndex.value + 1] : null)
const results = computed(() => searchDocs(query.value).slice(0, 8))

function docsForGroup(group: typeof docGroups[number]) {
  return devDocs.filter(doc => doc.group === group && (audience.value === '전체' || doc.audience === audience.value || doc.audience === '공통'))
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
    <header class="docs-toolbar surface-card">
      <div class="docs-brand"><span>?</span><div><strong>AICONNECT Dev-Docs</strong><small>사용자와 관리자를 위한 제품 문서</small></div></div>
      <div class="docs-search">
        <span>⌕</span>
        <input v-model="query" aria-label="문서 검색" placeholder="API 키, Runtime, Timeout 검색" />
        <kbd>DOCS</kbd>
        <div v-if="query.trim()" class="search-results">
          <button v-for="item in results" :key="item.id" @click="selectDoc(item.id)"><span>{{ item.icon }}</span><div><strong>{{ item.title }}</strong><small>{{ item.description }}</small></div><b>↵</b></button>
          <p v-if="!results.length">일치하는 문서가 없습니다.</p>
        </div>
      </div>
      <a href="https://lmstudio.ai/docs/developer" target="_blank" rel="noreferrer">LM Studio Docs ↗</a>
    </header>

    <div class="audience-bar">
      <span>문서 대상</span>
      <button v-for="item in (['전체', '사용자', '관리자'] as const)" :key="item" :class="{ active: audience === item }" @click="audience = item">{{ item }}</button>
      <i></i>
      <small>총 {{ devDocs.length }}개 문서 · 현재 구현 기준</small>
    </div>

    <div class="docs-layout">
      <aside class="docs-sidebar">
        <nav aria-label="Dev-Docs 문서 목록">
          <section v-for="group in docGroups" :key="group">
            <p>{{ group }}</p>
            <button v-for="doc in docsForGroup(group)" :key="doc.id" :class="{ active: activePage.id === doc.id }" @click="selectDoc(doc.id)">
              <span>{{ doc.icon }}</span>
              <div><strong>{{ doc.shortTitle }}</strong><small>{{ doc.audience }} · {{ doc.minutes }}분</small></div>
            </button>
          </section>
        </nav>
        <div class="sidebar-help"><span>i</span><p><strong>문서 유지 원칙</strong><small>새 기능은 관련 문서를 수정하고, 누적 공지 형태로 추가하지 않습니다.</small></p></div>
      </aside>

      <main class="docs-main">
        <DevDocsArticle :key="activePage.id" :page="activePage" @navigate="emit('navigate', $event)" />
        <footer class="article-pagination">
          <button v-if="previousPage" @click="selectDoc(previousPage.id)"><span>← 이전</span><strong>{{ previousPage.shortTitle }}</strong></button>
          <i></i>
          <button v-if="nextPage" class="next" @click="selectDoc(nextPage.id)"><span>다음 →</span><strong>{{ nextPage.shortTitle }}</strong></button>
        </footer>
      </main>

      <aside class="page-toc">
        <p>이 페이지에서</p>
        <button v-for="(section, index) in activePage.sections" :key="section.id" @click="scrollToSection(section.id)"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}</button>
        <div><strong>문서를 찾지 못했나요?</strong><small>오류 코드, 화면 이름 또는 설정 필드명으로 검색해 보세요.</small></div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.docs-site{display:grid;gap:15px;max-width:1780px;margin:0 auto}.docs-toolbar{position:sticky;z-index:12;top:0;display:grid;grid-template-columns:auto minmax(260px,620px) auto;gap:18px;align-items:center;padding:12px 15px;border-radius:16px}.docs-brand{display:flex;gap:10px;align-items:center;min-width:220px}.docs-brand>span{width:34px;height:34px;display:grid;place-items:center;border:1px solid var(--accent-border);border-radius:10px;background:var(--accent-dim);color:var(--accent-strong);font-weight:900}.docs-brand strong,.docs-brand small{display:block}.docs-brand strong{font-size:12px}.docs-brand small{margin-top:2px;color:var(--muted);font-size:8px}.docs-search{position:relative;display:grid;grid-template-columns:18px 1fr auto;gap:7px;align-items:center;padding:0 11px;border:1px solid var(--border);border-radius:11px;background:var(--surface-2)}.docs-search>span{color:var(--muted)}.docs-search input{height:37px;border:0;outline:0;background:transparent;color:var(--text);font-size:10px}.docs-search kbd{padding:3px 5px;border:1px solid var(--border);border-radius:5px;color:var(--faint);font-size:7px}.search-results{position:absolute;z-index:20;top:44px;right:0;left:0;overflow:hidden;padding:6px;border:1px solid var(--border);border-radius:13px;background:var(--surface);box-shadow:0 18px 50px rgba(0,0,0,.16)}.search-results button{width:100%;display:grid;grid-template-columns:28px 1fr auto;gap:9px;align-items:center;padding:10px;border:0;border-radius:9px;background:transparent;color:var(--text);text-align:left}.search-results button:hover{background:var(--accent-dim)}.search-results button>span{color:var(--accent-strong);font-weight:900}.search-results strong,.search-results small{display:block}.search-results strong{font-size:10px}.search-results small{margin-top:3px;overflow:hidden;color:var(--muted);font-size:8px;text-overflow:ellipsis;white-space:nowrap}.search-results b{color:var(--faint);font-size:9px}.search-results p{margin:0;padding:15px;color:var(--muted);font-size:10px;text-align:center}.docs-toolbar>a{color:var(--accent-strong);font-size:9px;font-weight:800;text-decoration:none}.audience-bar{display:flex;align-items:center;gap:7px;padding:0 5px}.audience-bar>span{margin-right:4px;color:var(--muted);font-size:9px;font-weight:800}.audience-bar button{padding:6px 10px;border:1px solid var(--border);border-radius:999px;background:var(--surface);color:var(--muted);font-size:9px;font-weight:800}.audience-bar button.active{border-color:var(--accent-border);background:var(--accent-dim);color:var(--accent-strong)}.audience-bar i{flex:1}.audience-bar small{color:var(--faint);font-size:8px}.docs-layout{display:grid;grid-template-columns:215px minmax(0,1fr) 190px;gap:25px;align-items:start}.docs-sidebar,.page-toc{position:sticky;top:77px;max-height:calc(100vh - 95px);overflow:auto}.docs-sidebar nav{display:grid;gap:17px}.docs-sidebar nav section>p,.page-toc>p{margin:0 0 7px;padding:0 9px;color:var(--faint);font-size:8px;font-weight:900;letter-spacing:.12em;text-transform:uppercase}.docs-sidebar nav button{width:100%;display:grid;grid-template-columns:27px 1fr;gap:9px;align-items:center;padding:8px;border:0;border-radius:10px;background:transparent;color:var(--muted);text-align:left}.docs-sidebar nav button:hover,.docs-sidebar nav button.active{background:var(--accent-dim);color:var(--text)}.docs-sidebar nav button>span{width:27px;height:27px;display:grid;place-items:center;border:1px solid var(--border);border-radius:8px;background:var(--surface);color:var(--accent-strong);font-size:9px;font-weight:900}.docs-sidebar strong,.docs-sidebar small{display:block}.docs-sidebar strong{font-size:10px}.docs-sidebar small{margin-top:3px;color:var(--faint);font-size:8px}.sidebar-help{display:grid;grid-template-columns:25px 1fr;gap:9px;margin-top:20px;padding:11px;border:1px solid var(--accent-border);border-radius:11px;background:var(--accent-dim)}.sidebar-help>span{width:23px;height:23px;display:grid;place-items:center;border-radius:7px;background:var(--surface);color:var(--accent-strong);font-size:9px;font-weight:900}.sidebar-help p{margin:0}.sidebar-help strong,.sidebar-help small{display:block}.sidebar-help strong{font-size:9px}.sidebar-help small{margin-top:4px;color:var(--muted);font-size:8px;line-height:1.55}.docs-main{min-width:0}.page-toc{display:grid;gap:3px}.page-toc button{display:grid;grid-template-columns:25px 1fr;gap:5px;padding:7px 9px;border:0;border-left:1px solid var(--border);background:transparent;color:var(--muted);font-size:9px;line-height:1.35;text-align:left}.page-toc button:hover{border-left-color:var(--accent);color:var(--text)}.page-toc button span{color:var(--faint);font-size:8px}.page-toc>div{margin-top:17px;padding:12px;border:1px solid var(--border);border-radius:11px;background:var(--surface-2)}.page-toc strong,.page-toc small{display:block}.page-toc strong{font-size:9px}.page-toc small{margin-top:5px;color:var(--muted);font-size:8px;line-height:1.55}.article-pagination{display:grid;grid-template-columns:minmax(0,1fr) auto minmax(0,1fr);gap:12px;padding-top:18px;border-top:1px solid var(--border)}.article-pagination button{display:grid;gap:4px;padding:14px;border:1px solid var(--border);border-radius:12px;background:var(--surface);color:var(--text);text-align:left}.article-pagination button:hover{border-color:var(--accent-border);background:var(--accent-dim)}.article-pagination button.next{text-align:right}.article-pagination span{color:var(--muted);font-size:8px}.article-pagination strong{font-size:11px}@media(max-width:1180px){.docs-layout{grid-template-columns:200px minmax(0,1fr)}.page-toc{display:none}.docs-toolbar{grid-template-columns:auto 1fr}.docs-toolbar>a{display:none}}@media(max-width:780px){.docs-toolbar{position:static;grid-template-columns:1fr}.docs-brand{min-width:0}.docs-layout{grid-template-columns:1fr}.docs-sidebar{position:static;max-height:none;overflow:visible}.docs-sidebar nav{grid-template-columns:repeat(2,minmax(0,1fr))}.docs-sidebar nav section{min-width:0}.docs-main{grid-row:2}.audience-bar small{display:none}}@media(max-width:520px){.docs-sidebar nav{grid-template-columns:1fr}.audience-bar>span{display:none}}
</style>
