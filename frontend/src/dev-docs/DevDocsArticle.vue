<script setup lang="ts">
import { ref } from 'vue'
import { copyText } from '../clipboard'
import type { DocPage, DocsDestination } from './types'

defineProps<{ page: DocPage }>()
const emit = defineEmits<{ navigate: [target: DocsDestination] }>()
const copied = ref('')

async function copyCode(code: string, key: string) {
  try {
    await copyText(code)
    copied.value = key
    window.setTimeout(() => { if (copied.value === key) copied.value = '' }, 1600)
  } catch {
    copied.value = ''
  }
}
</script>

<template>
  <article class="doc-article">
    <header class="article-hero">
      <div class="article-icon">{{ page.icon }}</div>
      <div>
        <div class="article-meta"><span>{{ page.group }}</span><i>·</i><span>{{ page.audience }}</span><i>·</i><span>약 {{ page.minutes }}분</span></div>
        <h1>{{ page.title }}</h1>
        <p>{{ page.description }}</p>
      </div>
    </header>

    <section v-for="(section, sectionIndex) in page.sections" :id="`doc-section-${section.id}`" :key="section.id" class="article-section">
      <header class="section-heading">
        <span>{{ String(sectionIndex + 1).padStart(2, '0') }}</span>
        <div><h2>{{ section.title }}</h2><p v-if="section.description">{{ section.description }}</p></div>
      </header>

      <div class="section-content">
        <template v-for="(block, blockIndex) in section.blocks" :key="`${section.id}-${blockIndex}`">
          <p v-if="block.type === 'paragraph'" class="body-copy">{{ block.text }}</p>

          <aside v-else-if="block.type === 'callout'" class="callout" :class="block.tone">
            <span>{{ block.tone === 'danger' ? '!' : block.tone === 'warning' ? '△' : block.tone === 'success' ? '✓' : 'i' }}</span>
            <div><strong>{{ block.title }}</strong><p>{{ block.text }}</p></div>
          </aside>

          <ol v-else-if="block.type === 'steps'" class="steps-list">
            <li v-for="(item, itemIndex) in block.items" :key="item.title">
              <span>{{ itemIndex + 1 }}</span>
              <div><strong>{{ item.title }}</strong><p>{{ item.text }}</p><button v-if="item.action" class="inline-action" @click="emit('navigate', item.action.destination)">{{ item.action.label }} →</button></div>
            </li>
          </ol>

          <ul v-else-if="block.type === 'checklist'" class="check-list">
            <li v-for="item in block.items" :key="item"><span>✓</span>{{ item }}</li>
          </ul>

          <div v-else-if="block.type === 'cards'" class="docs-cards">
            <article v-for="item in block.items" :key="item.title"><span v-if="item.label">{{ item.label }}</span><h3>{{ item.title }}</h3><p>{{ item.text }}</p></article>
          </div>

          <div v-else-if="block.type === 'flow'" class="docs-flow" role="img" :aria-label="block.items.map(item => item.title).join('에서 ')">
            <template v-for="(item, itemIndex) in block.items" :key="item.title">
              <article><span>{{ item.label }}</span><strong>{{ item.title }}</strong><small>{{ item.text }}</small></article>
              <i v-if="itemIndex < block.items.length - 1">→</i>
            </template>
          </div>

          <div v-else-if="block.type === 'table'" class="docs-table-wrap">
            <table><thead><tr><th v-for="column in block.columns" :key="column">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table>
          </div>

          <div v-else-if="block.type === 'code'" class="code-panel">
            <header><div><span>{{ block.language }}</span><strong>{{ block.title }}</strong></div><button @click="copyCode(block.code, `${section.id}-${blockIndex}`)">{{ copied === `${section.id}-${blockIndex}` ? '복사됨' : '코드 복사' }}</button></header>
            <pre><code>{{ block.code }}</code></pre>
          </div>

          <div v-else-if="block.type === 'links'" class="link-list">
            <a v-for="item in block.items" :key="item.href" :href="item.href" target="_blank" rel="noreferrer"><span>↗</span><div><strong>{{ item.label }}</strong><small>{{ item.description }}</small></div></a>
          </div>
        </template>
      </div>
    </section>
  </article>
</template>

<style scoped>
.doc-article{min-width:0;padding:0 0 70px}.article-hero{position:relative;overflow:hidden;display:grid;grid-template-columns:58px minmax(0,1fr);gap:22px;padding:38px;border:1px solid var(--accent-border);border-radius:22px;background:radial-gradient(circle at 92% 0,var(--accent-dim),transparent 32%),linear-gradient(135deg,var(--surface),var(--surface-2))}.article-hero:after{content:'';position:absolute;right:-90px;bottom:-120px;width:250px;height:250px;border:1px solid var(--accent-border);border-radius:50%;box-shadow:0 0 0 32px color-mix(in srgb,var(--accent-dim) 55%,transparent)}.article-icon{position:relative;z-index:1;width:58px;height:58px;display:grid;place-items:center;border:1px solid var(--accent-border);border-radius:17px;background:var(--accent-dim);color:var(--accent-strong);font:800 20px 'Space Grotesk',sans-serif}.article-hero>div:last-child{position:relative;z-index:1}.article-meta{display:flex;flex-wrap:wrap;gap:8px;color:var(--accent-strong);font-size:10px;font-weight:800;letter-spacing:.08em;text-transform:uppercase}.article-meta i{color:var(--faint);font-style:normal}.article-hero h1{margin:12px 0 11px;font-size:clamp(30px,4vw,50px);letter-spacing:-.055em;line-height:1.04}.article-hero p{max-width:740px;margin:0;color:var(--text-soft);font-size:14px;line-height:1.75}.article-section{scroll-margin-top:92px;padding:48px 5px 0}.section-heading{display:grid;grid-template-columns:38px 1fr;gap:13px;align-items:start;margin-bottom:20px}.section-heading>span{padding-top:5px;color:var(--accent-strong);font:800 10px 'Space Grotesk',sans-serif;letter-spacing:.12em}.section-heading h2{margin:0;font-size:clamp(21px,2.5vw,29px);letter-spacing:-.035em}.section-heading p{margin:7px 0 0;color:var(--muted);font-size:12px}.section-content{display:grid;gap:15px;padding-left:51px}.body-copy{margin:0;color:var(--text-soft);font-size:13px;line-height:1.85}.callout{display:grid;grid-template-columns:30px 1fr;gap:12px;padding:16px 17px;border:1px solid var(--accent-border);border-radius:14px;background:var(--accent-dim)}.callout>span{width:28px;height:28px;display:grid;place-items:center;border-radius:9px;background:var(--surface);color:var(--accent-strong);font-weight:900}.callout strong{font-size:12px}.callout p{margin:5px 0 0;color:var(--text-soft);font-size:11px;line-height:1.7}.callout.warning{border-color:color-mix(in srgb,#d89528 45%,var(--border));background:color-mix(in srgb,#d89528 10%,var(--surface))}.callout.warning>span{color:#c27a10}.callout.danger{border-color:color-mix(in srgb,var(--danger) 45%,var(--border));background:color-mix(in srgb,var(--danger) 8%,var(--surface))}.callout.danger>span{color:var(--danger)}.callout.success{border-color:var(--accent-border)}.steps-list{display:grid;gap:10px;margin:0;padding:0;list-style:none}.steps-list li{display:grid;grid-template-columns:34px 1fr;gap:13px;padding:15px;border:1px solid var(--border);border-radius:14px;background:var(--surface)}.steps-list li>span{width:31px;height:31px;display:grid;place-items:center;border-radius:10px;background:var(--accent-dim);color:var(--accent-strong);font-size:11px;font-weight:900}.steps-list strong{font-size:12px}.steps-list p{margin:5px 0 0;color:var(--muted);font-size:11px;line-height:1.65}.inline-action{margin-top:9px;padding:0;border:0;background:transparent;color:var(--accent-strong);font-size:10px;font-weight:800}.check-list{display:grid;gap:8px;margin:0;padding:0;list-style:none}.check-list li{display:grid;grid-template-columns:23px 1fr;gap:10px;align-items:center;padding:11px 13px;border:1px solid var(--border);border-radius:11px;background:var(--surface);color:var(--text-soft);font-size:11px;line-height:1.55}.check-list span{width:21px;height:21px;display:grid;place-items:center;border-radius:7px;background:var(--accent-dim);color:var(--accent-strong);font-weight:900}.docs-cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.docs-cards article{padding:17px;border:1px solid var(--border);border-radius:14px;background:var(--surface)}.docs-cards span{color:var(--accent-strong);font-size:9px;font-weight:900;letter-spacing:.12em}.docs-cards h3{margin:10px 0 6px;font-size:13px}.docs-cards p{margin:0;color:var(--muted);font-size:10px;line-height:1.65}.docs-flow{display:flex;align-items:stretch;gap:8px}.docs-flow article{min-width:0;flex:1;padding:15px;border:1px solid var(--accent-border);border-radius:14px;background:linear-gradient(145deg,var(--surface),var(--accent-dim))}.docs-flow article span{display:inline-grid;min-width:26px;height:24px;padding:0 7px;place-items:center;border-radius:7px;background:var(--accent-dim);color:var(--accent-strong);font-size:9px;font-weight:900}.docs-flow strong,.docs-flow small{display:block}.docs-flow strong{margin-top:13px;font-size:11px}.docs-flow small{margin-top:5px;color:var(--muted);font-size:9px;line-height:1.55}.docs-flow>i{align-self:center;color:var(--accent-strong);font-style:normal}.docs-table-wrap{overflow:auto;border:1px solid var(--border);border-radius:14px;background:var(--surface)}table{width:100%;border-collapse:collapse;font-size:10px}th,td{padding:12px 14px;border-bottom:1px solid var(--border);text-align:left;vertical-align:top;line-height:1.55}th{background:var(--surface-2);color:var(--muted);font-size:9px;letter-spacing:.04em}td{color:var(--text-soft)}tbody tr:last-child td{border-bottom:0}td:first-child{color:var(--text);font-weight:800}.code-panel{overflow:hidden;border:1px solid var(--border);border-radius:15px;background:#0d1713;color:#d8efe2}.code-panel header{display:flex;justify-content:space-between;align-items:center;padding:10px 13px;border-bottom:1px solid rgba(190,255,214,.12);background:#111f19}.code-panel header>div{display:flex;align-items:center;gap:9px}.code-panel header span{padding:4px 7px;border-radius:6px;background:rgba(113,255,162,.1);color:#7ff1a5;font-size:8px;font-weight:900;text-transform:uppercase}.code-panel header strong{font-size:10px}.code-panel button{padding:5px 8px;border:1px solid rgba(190,255,214,.17);border-radius:7px;background:transparent;color:#a8c8b4;font-size:8px}.code-panel pre{overflow:auto;margin:0;padding:18px;font-size:10px;line-height:1.75;tab-size:2}.code-panel code{font-family:'JetBrains Mono','Cascadia Code',monospace}.link-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.link-list a{display:grid;grid-template-columns:28px 1fr;gap:10px;align-items:center;padding:13px;border:1px solid var(--border);border-radius:12px;background:var(--surface);color:var(--text);text-decoration:none}.link-list a:hover{border-color:var(--accent-border);background:var(--accent-dim)}.link-list a>span{color:var(--accent-strong);font-weight:900}.link-list strong,.link-list small{display:block}.link-list strong{font-size:11px}.link-list small{margin-top:3px;color:var(--muted);font-size:9px}@media(max-width:850px){.article-hero{grid-template-columns:1fr;padding:27px}.article-icon{width:48px;height:48px}.section-content{padding-left:0}.docs-cards{grid-template-columns:1fr}.docs-flow{display:grid;grid-template-columns:1fr}.docs-flow>i{justify-self:center;transform:rotate(90deg)}.link-list{grid-template-columns:1fr}}@media(max-width:560px){.article-section{padding-top:35px}.section-heading{grid-template-columns:28px 1fr}.article-hero h1{font-size:30px}}
.doc-article .article-hero { padding: 27px 29px; border-color: var(--border); border-radius: 14px; background: var(--surface); box-shadow: none; }
.doc-article .article-hero:after { display: none; }
.doc-article .article-icon { border-radius: 10px; background: var(--surface-2); }
.doc-article .docs-flow article { border-color: var(--border); border-radius: 10px; background: var(--surface); }
.doc-article .docs-cards article { border-radius: 10px; }
</style>
