<script setup lang="ts">
import { ref } from 'vue'
import { copyText } from '../clipboard'
import type { DocPage, DocsDestination } from './types'

defineProps<{ page: DocPage }>()
const emit = defineEmits<{ navigate: [target: DocsDestination] }>()
const copied = ref('')
const copyError = ref('')
async function copyCode(code: string, key: string) {
  copyError.value = ''
  try {
    await copyText(code)
    copied.value = key
    window.setTimeout(() => { if (copied.value === key) copied.value = '' }, 1600)
  } catch { copied.value = ''; copyError.value = '복사하지 못했습니다. 코드 영역에서 직접 선택해 복사해 주세요.' }
}
</script>

<template>
  <article class="doc-article">
    <header class="article-header">
      <p class="article-meta"><span>{{ page.group }}</span><span>{{ page.audience }}</span><span>약 {{ page.minutes }}분</span></p>
      <h2>{{ page.title }}</h2><p class="article-lead">{{ page.description }}</p>
    </header>

    <section v-for="(section, sectionIndex) in page.sections" :id="'doc-section-' + section.id" :key="section.id" class="article-section">
      <header class="section-heading"><span aria-hidden="true">{{ String(sectionIndex + 1).padStart(2, '0') }}</span><div><h3>{{ section.title }}</h3><p v-if="section.description">{{ section.description }}</p></div></header>
      <div class="section-content">
        <template v-for="(block, blockIndex) in section.blocks" :key="section.id + '-' + blockIndex">
          <p v-if="block.type === 'paragraph'" class="body-copy">{{ block.text }}</p>
          <aside v-else-if="block.type === 'callout'" class="doc-callout" :class="block.tone"><strong>{{ block.title }}</strong><p>{{ block.text }}</p></aside>
          <ol v-else-if="block.type === 'steps'" class="steps-list"><li v-for="item in block.items" :key="item.title"><strong>{{ item.title }}</strong><p>{{ item.text }}</p><button v-if="item.action" type="button" class="inline-action" @click="emit('navigate', item.action.destination)">{{ item.action.label }} →</button></li></ol>
          <ul v-else-if="block.type === 'checklist'" class="check-list"><li v-for="item in block.items" :key="item"><span aria-hidden="true">✓</span><span>{{ item }}</span></li></ul>
          <div v-else-if="block.type === 'cards'" class="doc-summaries"><div v-for="item in block.items" :key="item.title"><span v-if="item.label">{{ item.label }}</span><h4>{{ item.title }}</h4><p>{{ item.text }}</p></div></div>
          <ol v-else-if="block.type === 'flow'" class="doc-flow" aria-label="처리 순서"><li v-for="item in block.items" :key="item.title"><span>{{ item.label }}</span><div><strong>{{ item.title }}</strong><p>{{ item.text }}</p></div></li></ol>
          <div v-else-if="block.type === 'table'" class="docs-table-wrap" role="region" :aria-label="section.title + ' 표 ' + (blockIndex + 1)" tabindex="0"><table><thead><tr><th v-for="column in block.columns" :key="column" scope="col">{{ column }}</th></tr></thead><tbody><tr v-for="(row, rowIndex) in block.rows" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div>
          <div v-else-if="block.type === 'code'" class="code-panel"><header><div><span>{{ block.language }}</span><strong>{{ block.title }}</strong></div><button type="button" @click="copyCode(block.code, section.id + '-' + blockIndex)">{{ copied === section.id + '-' + blockIndex ? '복사됨' : '코드 복사' }}</button></header><pre tabindex="0" :aria-label="block.title + ' 코드'"><code>{{ block.code }}</code></pre></div>
          <ul v-else-if="block.type === 'links'" class="link-list"><li v-for="item in block.items" :key="item.href"><a :href="item.href" target="_blank" rel="noopener noreferrer">{{ item.label }} ↗</a><p>{{ item.description }}</p></li></ul>
        </template>
      </div>
    </section>
    <p v-if="copyError" class="copy-error" role="status">{{ copyError }}</p>
  </article>
</template>

<style scoped>
.doc-article{min-width:0;overflow-wrap:anywhere}
.article-header{padding-bottom:28px;border-bottom:1px solid var(--border)}
.article-meta{display:flex;flex-wrap:wrap;gap:14px;margin:0 0 12px;color:var(--muted);font-size:11px;line-height:1.6}
.article-header h2{margin:0 0 14px;font-size:30px;font-weight:750;letter-spacing:-.035em;line-height:1.3}
.article-lead{margin:0;color:var(--text-soft);font-size:15px;line-height:1.8}
.article-section{padding-top:34px;scroll-margin-top:88px}
.section-heading{display:grid;grid-template-columns:26px minmax(0,1fr);gap:9px;align-items:baseline;margin-bottom:20px}
.section-heading>span{color:var(--accent-strong);font:700 10px 'Space Grotesk',sans-serif}
.section-heading h3{margin:0;color:var(--text);font-size:20px;line-height:1.5;letter-spacing:-.02em}
.section-heading p{margin:7px 0 0;color:var(--muted);font-size:13px;line-height:1.7}
.section-content{display:grid;gap:20px;min-width:0}
.body-copy{margin:0;color:var(--text-soft);font-size:14px;line-height:1.9}
.doc-callout{border:0;border-left:3px solid var(--accent-strong);padding:14px 18px;background:var(--surface-2)}
.doc-callout strong{font-size:13px;line-height:1.7}.doc-callout p{margin:6px 0 0;color:var(--text-soft);font-size:13px;line-height:1.8}
.doc-callout.warning{border-left-color:var(--warning)}.doc-callout.danger{border-left-color:var(--danger)}
.steps-list{display:grid;gap:20px;margin:0;padding:0 0 0 24px;color:var(--text-soft);font-size:14px}
.steps-list li{padding-left:7px}.steps-list li::marker{color:var(--accent-strong);font-weight:700}
.steps-list strong{color:var(--text);font-size:14px}.steps-list p{margin:5px 0 0;font-size:13px;line-height:1.8}
.inline-action{display:inline-block;margin-top:8px;border:0;border-bottom:1px solid var(--accent-border);border-radius:0;padding:3px 0;background:transparent;color:var(--accent-strong);font-size:12px;line-height:1.6;text-align:left}
.inline-action:hover{border-bottom-color:var(--accent-strong)}
.check-list{display:grid;gap:11px;margin:0;padding:0;list-style:none}
.check-list li{display:grid;grid-template-columns:16px minmax(0,1fr);gap:10px;color:var(--text-soft);font-size:13px;line-height:1.8}.check-list li>span:first-child{color:var(--accent-strong)}
.doc-summaries{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px}
.doc-summaries>div{padding-top:13px;border-top:2px solid var(--accent-border)}.doc-summaries>div>span{color:var(--accent-strong);font-size:9px;letter-spacing:.1em;font-weight:700}
.doc-summaries h4{margin:8px 0;font-size:14px;line-height:1.6}.doc-summaries p{margin:0;color:var(--text-soft);font-size:12px;line-height:1.8}
.doc-flow{margin:0;padding:0;list-style:none;display:grid}
.doc-flow li{display:grid;grid-template-columns:60px minmax(0,1fr);gap:14px;padding:0 0 20px;position:relative}
.doc-flow li:last-child{padding-bottom:0}.doc-flow li:not(:last-child)::before{content:'';position:absolute;left:28px;top:24px;bottom:5px;border-left:1px solid var(--accent-border)}
.doc-flow li>span{color:var(--accent-strong);font-size:10px;font-weight:800;line-height:2;text-align:center}
.doc-flow strong{font-size:14px;line-height:1.6}.doc-flow p{margin:5px 0 0;color:var(--text-soft);font-size:12px;line-height:1.8}
.docs-table-wrap{width:100%;min-width:0;overflow:auto;border-block:1px solid var(--border);scrollbar-width:thin}
table{width:100%;min-width:490px;border-collapse:collapse;table-layout:auto}
th,td{padding:13px 14px;text-align:left;vertical-align:top;border-bottom:1px solid var(--border);font-size:12px;line-height:1.8}
th{color:var(--muted);background:var(--surface-2);font-size:11px;font-weight:700}td{color:var(--text-soft)}td:first-child{font-weight:600;color:var(--text)}tbody tr:last-child td{border-bottom:0}
.code-panel{min-width:0;border:1px solid var(--border);border-radius:6px;overflow:hidden;background:var(--surface)}
.code-panel header{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 14px;border-bottom:1px solid var(--border);background:var(--surface-2)}
.code-panel header>div{min-width:0;display:flex;gap:10px;flex-wrap:wrap;align-items:baseline}
.code-panel header span{color:var(--accent-strong);font-size:10px;text-transform:uppercase}.code-panel header strong{font-size:12px}
.code-panel button{flex-shrink:0;min-height:30px;padding:4px 8px;border:1px solid var(--border-strong);border-radius:4px;background:transparent;color:var(--text-soft);font-size:11px}
.code-panel pre{max-width:100%;overflow:auto;margin:0;padding:18px;font-size:12px;line-height:1.8;tab-size:2;color:var(--text-soft);scrollbar-width:thin}
.code-panel code{font-family:'JetBrains Mono','Cascadia Code',monospace}
.link-list{list-style:none;margin:0;padding:0;display:grid;gap:16px}.link-list a{color:var(--accent-strong);font-size:13px;text-underline-offset:4px}.link-list p{margin:5px 0 0;color:var(--muted);font-size:12px;line-height:1.7}
.copy-error{color:var(--danger);font-size:12px;line-height:1.7}
@media(max-width:1100px){.doc-summaries{grid-template-columns:minmax(0,1fr);gap:18px}}
@media(max-width:560px){.article-header h2{font-size:25px}.article-lead{font-size:14px}.section-heading h3{font-size:18px}.body-copy{font-size:13px}.doc-callout{padding:12px 14px}.article-section{padding-top:28px}.code-panel header{padding:10px}.code-panel pre{padding:12px;font-size:11px}.doc-flow li{grid-template-columns:45px minmax(0,1fr);gap:10px}.doc-flow li:not(:last-child)::before{left:21px}}
</style>
