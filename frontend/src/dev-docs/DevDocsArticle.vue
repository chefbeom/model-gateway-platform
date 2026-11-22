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
    window.setTimeout(() => {
      if (copied.value === key) copied.value = ''
    }, 1600)
  } catch {
    copied.value = ''
    copyError.value = '복사하지 못했습니다. 코드 영역에서 직접 선택해 복사해 주세요.'
  }
}
</script>

<template>
  <article class="doc-article">
    <header class="article-header">
      <p class="article-meta">
        <span>{{ page.group }}</span>
        <span class="audience-pill">{{ page.audiences?.length ? page.audiences.join(' · ') : page.audience }}</span>
        <span>약 {{ page.minutes }}분</span>
      </p>
      <h2>{{ page.title }}</h2>
      <p class="article-lead">{{ page.description }}</p>
    </header>

    <section
      v-for="(section, sectionIndex) in page.sections"
      :id="'doc-section-' + section.id"
      :key="section.id"
      class="article-section"
    >
      <header class="section-heading">
        <span aria-hidden="true">{{ String(sectionIndex + 1).padStart(2, '0') }}</span>
        <div>
          <h3>{{ section.title }}</h3>
          <p v-if="section.description">{{ section.description }}</p>
        </div>
      </header>
      <div class="section-content">
        <template v-for="(block, blockIndex) in section.blocks" :key="section.id + '-' + blockIndex">
          <p v-if="block.type === 'paragraph'" class="body-copy">{{ block.text }}</p>

          <aside v-else-if="block.type === 'callout'" class="doc-callout" :class="block.tone">
            <strong>{{ block.title }}</strong>
            <p>{{ block.text }}</p>
          </aside>

          <ol v-else-if="block.type === 'steps'" class="steps-list">
            <li v-for="item in block.items" :key="item.title">
              <strong>{{ item.title }}</strong>
              <p>{{ item.text }}</p>
              <button
                v-if="item.action"
                type="button"
                class="inline-action"
                @click="emit('navigate', item.action.destination)"
              >
                {{ item.action.label }} →
              </button>
            </li>
          </ol>

          <ul v-else-if="block.type === 'checklist'" class="check-list">
            <li v-for="item in block.items" :key="item">
              <span aria-hidden="true">✓</span>
              <span>{{ item }}</span>
            </li>
          </ul>

          <div v-else-if="block.type === 'cards'" class="doc-summaries">
            <div v-for="item in block.items" :key="item.title">
              <span v-if="item.label">{{ item.label }}</span>
              <h4>{{ item.title }}</h4>
              <p>{{ item.text }}</p>
            </div>
          </div>

          <ol v-else-if="block.type === 'flow'" class="doc-flow" aria-label="처리 순서">
            <li v-for="item in block.items" :key="item.title">
              <span>{{ item.label }}</span>
              <div>
                <strong>{{ item.title }}</strong>
                <p>{{ item.text }}</p>
              </div>
            </li>
          </ol>

          <div
            v-else-if="block.type === 'table'"
            class="docs-table-wrap"
            role="region"
            :aria-label="section.title + ' 표 ' + (blockIndex + 1)"
            tabindex="0"
          >
            <table>
              <thead>
                <tr>
                  <th v-for="column in block.columns" :key="column" scope="col">{{ column }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                  <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else-if="block.type === 'code'" class="code-panel">
            <header>
              <div>
                <span>{{ block.language }}</span>
                <strong>{{ block.title }}</strong>
              </div>
              <button type="button" @click="copyCode(block.code, section.id + '-' + blockIndex)">
                {{ copied === section.id + '-' + blockIndex ? '복사됨' : '코드 복사' }}
              </button>
            </header>
            <pre tabindex="0" :aria-label="block.title + ' 코드'"><code>{{ block.code }}</code></pre>
          </div>

          <ul v-else-if="block.type === 'links'" class="link-list">
            <li v-for="item in block.items" :key="item.href">
              <a :href="item.href" target="_blank" rel="noopener noreferrer">{{ item.label }} ↗</a>
              <p>{{ item.description }}</p>
            </li>
          </ul>

          <section v-else-if="block.type === 'mockup'" class="mockup-card" :aria-label="block.screen + ' 화면 예시'">
            <header class="mockup-topbar">
              <div class="mockup-title-group">
                <span class="mockup-dots" aria-hidden="true"><i></i><i></i><i></i></span>
                <span v-if="block.stepLabel" class="mockup-step">{{ block.stepLabel }}</span>
                <strong class="mockup-screen">{{ block.screen }}</strong>
                <span v-if="block.roleTag" class="mockup-role">{{ block.roleTag }}</span>
              </div>
              <button
                v-if="block.navigate"
                type="button"
                class="mockup-nav-btn"
                @click="emit('navigate', block.navigate.destination)"
              >
                {{ block.navigate.label }} →
              </button>
            </header>

            <div class="mockup-body">
              <p v-if="block.caption" class="mockup-caption">{{ block.caption }}</p>

              <div class="mockup-fields">
                <div v-for="(field, fieldIndex) in block.fields" :key="fieldIndex" class="mockup-field">
                  <div class="mockup-field-head">
                    <span class="mockup-label">{{ field.label }}</span>
                    <em v-if="field.badge" class="mockup-badge" :class="field.badgeTone ?? 'neutral'">
                      {{ field.badge }}
                    </em>
                  </div>
                  <div class="mockup-value" :class="{ mono: field.mono }">{{ field.value }}</div>
                  <small v-if="field.hint" class="mockup-hint">{{ field.hint }}</small>
                </div>
              </div>

              <div v-if="block.actions?.length" class="mockup-actions" aria-label="화면 버튼 예시">
                <span class="mockup-preview-label">화면 버튼 예시</span>
                <div class="mockup-btn-row">
                  <span
                    v-for="act in block.actions"
                    :key="act.label"
                    class="mockup-btn"
                    :class="{ primary: act.primary }"
                  >
                    {{ act.label }}
                  </span>
                </div>
              </div>

              <div v-if="block.result" class="mockup-result" :class="block.result.tone ?? 'info'">
                <strong>{{ block.result.title }}</strong>
                <ul v-if="block.result.items?.length">
                  <li v-for="item in block.result.items" :key="item">{{ item }}</li>
                </ul>
              </div>
            </div>
          </section>
        </template>
      </div>
    </section>

    <p v-if="copyError" class="copy-error" role="status">{{ copyError }}</p>
  </article>
</template>

<style scoped>
.doc-article{min-width:0;overflow-wrap:anywhere}
.article-header{padding-bottom:24px;border-bottom:1px solid var(--border)}
.article-meta{display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:0 0 12px;color:var(--muted);font-size:11px;line-height:1.6}
.audience-pill{padding:2px 9px;border:1px solid var(--accent-border);border-radius:999px;background:var(--accent-dim);color:var(--accent-strong);font-size:10px;font-weight:700}
.article-header h2{margin:0 0 12px;font-size:29px;font-weight:750;letter-spacing:-.035em;line-height:1.3}
.article-lead{margin:0;color:var(--text-soft);font-size:15px;line-height:1.8}
.article-section{padding-top:32px;scroll-margin-top:88px}
.section-heading{display:grid;grid-template-columns:26px minmax(0,1fr);gap:9px;align-items:baseline;margin-bottom:18px}
.section-heading>span{color:var(--accent-strong);font:700 10px 'Space Grotesk',sans-serif}
.section-heading h3{margin:0;color:var(--text);font-size:19px;line-height:1.5;letter-spacing:-.02em}
.section-heading p{margin:6px 0 0;color:var(--muted);font-size:13px;line-height:1.7}
.section-content{display:grid;gap:18px;min-width:0}
.body-copy{margin:0;color:var(--text-soft);font-size:14px;line-height:1.85}
.doc-callout{border:0;border-left:3px solid var(--accent-strong);padding:13px 16px;background:var(--surface-2);border-radius:0 6px 6px 0}
.doc-callout strong{font-size:13px;line-height:1.6;color:var(--text)}.doc-callout p{margin:5px 0 0;color:var(--text-soft);font-size:13px;line-height:1.75}
.doc-callout.warning{border-left-color:var(--warning)}.doc-callout.danger{border-left-color:var(--danger)}.doc-callout.success{border-left-color:var(--accent-strong)}
.steps-list{display:grid;gap:16px;margin:0;padding:0 0 0 22px;color:var(--text-soft);font-size:14px}
.steps-list li{padding-left:6px}.steps-list li::marker{color:var(--accent-strong);font-weight:700}
.steps-list strong{color:var(--text);font-size:14px}.steps-list p{margin:4px 0 0;font-size:13px;line-height:1.75}
.inline-action{display:inline-block;margin-top:7px;border:0;border-bottom:1px solid var(--accent-border);border-radius:0;padding:2px 0;background:transparent;color:var(--accent-strong);font-size:12px;line-height:1.5;text-align:left;cursor:pointer}
.inline-action:hover{border-bottom-color:var(--accent-strong)}
.check-list{display:grid;gap:10px;margin:0;padding:0;list-style:none}
.check-list li{display:grid;grid-template-columns:16px minmax(0,1fr);gap:10px;color:var(--text-soft);font-size:13px;line-height:1.75}.check-list li>span:first-child{color:var(--accent-strong)}
.doc-summaries{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px}
.doc-summaries>div{padding-top:12px;border-top:2px solid var(--accent-border)}.doc-summaries>div>span{color:var(--accent-strong);font-size:9px;letter-spacing:.1em;font-weight:700}
.doc-summaries h4{margin:7px 0;font-size:14px;line-height:1.5}.doc-summaries p{margin:0;color:var(--text-soft);font-size:12px;line-height:1.75}
.doc-flow{margin:0;padding:0;list-style:none;display:grid}
.doc-flow li{display:grid;grid-template-columns:62px minmax(0,1fr);gap:14px;padding:0 0 18px;position:relative}
.doc-flow li:last-child{padding-bottom:0}.doc-flow li:not(:last-child)::before{content:'';position:absolute;left:30px;top:24px;bottom:4px;border-left:1px solid var(--accent-border)}
.doc-flow li>span{color:var(--accent-strong);font-size:10px;font-weight:800;line-height:2;text-align:center}
.doc-flow strong{font-size:14px;line-height:1.6}.doc-flow p{margin:4px 0 0;color:var(--text-soft);font-size:12px;line-height:1.75}
.docs-table-wrap{width:100%;min-width:0;overflow:auto;border-block:1px solid var(--border);scrollbar-width:thin}
table{width:100%;min-width:490px;border-collapse:collapse;table-layout:auto}
th,td{padding:12px 14px;text-align:left;vertical-align:top;border-bottom:1px solid var(--border);font-size:12px;line-height:1.75}
th{color:var(--muted);background:var(--surface-2);font-size:11px;font-weight:700}td{color:var(--text-soft)}td:first-child{font-weight:600;color:var(--text)}tbody tr:last-child td{border-bottom:0}
.code-panel{min-width:0;border:1px solid var(--border);border-radius:8px;overflow:hidden;background:var(--surface)}
.code-panel header{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 14px;border-bottom:1px solid var(--border);background:var(--surface-2)}
.code-panel header>div{min-width:0;display:flex;gap:10px;flex-wrap:wrap;align-items:baseline}
.code-panel header span{color:var(--accent-strong);font-size:10px;text-transform:uppercase}.code-panel header strong{font-size:12px}
.code-panel button{flex-shrink:0;min-height:30px;padding:4px 10px;border:1px solid var(--border-strong);border-radius:5px;background:transparent;color:var(--text-soft);font-size:11px;cursor:pointer}
.code-panel button:hover{border-color:var(--accent-strong);color:var(--accent-strong)}
.code-panel pre{max-width:100%;overflow:auto;margin:0;padding:16px 18px;font-size:12px;line-height:1.75;tab-size:2;color:var(--text-soft);scrollbar-width:thin}
.code-panel code{font-family:'JetBrains Mono','Cascadia Code',monospace}
.link-list{list-style:none;margin:0;padding:0;display:grid;gap:14px}.link-list a{color:var(--accent-strong);font-size:13px;text-underline-offset:4px}.link-list p{margin:4px 0 0;color:var(--muted);font-size:12px;line-height:1.7}

.mockup-card{border:1px solid var(--border-strong);border-radius:10px;background:var(--surface);box-shadow:var(--shadow-soft);overflow:hidden}
.mockup-topbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;padding:11px 16px;border-bottom:1px solid var(--border);background:var(--surface-3)}
.mockup-title-group{display:flex;align-items:center;gap:9px;flex-wrap:wrap;min-width:0}
.mockup-dots{display:inline-flex;gap:4px;align-items:center}
.mockup-dots i{display:block;width:7px;height:7px;border-radius:50%;background:var(--border-strong)}
.mockup-step{padding:2px 7px;border-radius:4px;background:var(--accent-dim);border:1px solid var(--accent-border);color:var(--accent-strong);font:700 10px 'Space Grotesk',sans-serif;letter-spacing:.04em}
.mockup-screen{font-size:13px;color:var(--text);font-weight:700}
.mockup-role{padding:2px 8px;border-radius:999px;border:1px solid var(--border-strong);background:var(--surface);color:var(--muted);font-size:10px;font-weight:600}
.mockup-nav-btn{border:1px solid var(--accent-border);border-radius:6px;padding:5px 11px;background:var(--accent-dim);color:var(--accent-strong);font-size:11px;font-weight:700;cursor:pointer;white-space:nowrap}
.mockup-nav-btn:hover{background:var(--accent-strong);color:var(--accent-ink)}
.mockup-body{display:grid;gap:14px;padding:16px}
.mockup-caption{margin:0;color:var(--muted);font-size:12px;line-height:1.65}
.mockup-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}
.mockup-field{display:grid;gap:5px;padding:10px 12px;border:1px solid var(--border);border-radius:7px;background:var(--surface-2);align-content:start}
.mockup-field-head{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}
.mockup-label{color:var(--muted);font-size:11px;font-weight:600}
.mockup-badge{font-style:normal;font-size:10px;font-weight:700;padding:1px 7px;border-radius:999px;border:1px solid var(--border)}
.mockup-badge.accent{color:var(--accent-strong);border-color:var(--accent-border);background:var(--accent-dim)}
.mockup-badge.info{color:var(--info);border-color:color-mix(in srgb,var(--info) 35%,transparent);background:color-mix(in srgb,var(--info) 12%,transparent)}
.mockup-badge.warning{color:var(--warning);border-color:color-mix(in srgb,var(--warning) 40%,transparent);background:var(--warning-dim)}
.mockup-badge.danger{color:var(--danger);border-color:color-mix(in srgb,var(--danger) 40%,transparent);background:var(--danger-dim)}
.mockup-badge.neutral{color:var(--muted);background:var(--surface)}
.mockup-value{color:var(--text);font-size:13px;font-weight:500;line-height:1.5;word-break:break-word}
.mockup-value.mono{font-family:'JetBrains Mono','Cascadia Code',monospace;font-size:12px}
.mockup-hint{color:var(--muted);font-size:11px;line-height:1.5}
.mockup-actions{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;padding-top:10px;border-top:1px dashed var(--border)}
.mockup-preview-label{color:var(--faint);font-size:10px;font-weight:600;letter-spacing:.03em}
.mockup-btn-row{display:flex;gap:8px;flex-wrap:wrap}
.mockup-btn{padding:5px 11px;border-radius:6px;border:1px solid var(--border-strong);background:var(--surface-2);color:var(--text-soft);font-size:11px;font-weight:600;user-select:none}
.mockup-btn.primary{border-color:var(--accent-border);background:var(--accent-dim);color:var(--accent-strong);font-weight:700}
.mockup-result{padding:11px 14px;border-radius:6px;border-left:3px solid var(--info);background:var(--surface-2)}
.mockup-result.success{border-left-color:var(--accent-strong)}
.mockup-result.warning{border-left-color:var(--warning)}
.mockup-result.danger{border-left-color:var(--danger)}
.mockup-result strong{display:block;font-size:12px;color:var(--text);margin-bottom:6px}
.mockup-result ul{margin:0;padding-left:16px;display:grid;gap:4px;color:var(--text-soft);font-size:12px;line-height:1.6}

.copy-error{color:var(--danger);font-size:12px;line-height:1.7}
@media(max-width:1100px){.doc-summaries{grid-template-columns:minmax(0,1fr);gap:16px}}
@media(max-width:700px){.mockup-fields{grid-template-columns:minmax(0,1fr)}}
@media(max-width:560px){.article-header h2{font-size:24px}.article-lead{font-size:14px}.section-heading h3{font-size:17px}.body-copy{font-size:13px}.doc-callout{padding:12px 14px}.article-section{padding-top:26px}.code-panel header{padding:10px}.code-panel pre{padding:12px;font-size:11px}.doc-flow li{grid-template-columns:46px minmax(0,1fr);gap:10px}.doc-flow li:not(:last-child)::before{left:22px}.mockup-body{padding:12px}.mockup-topbar{padding:10px 12px}}
</style>
