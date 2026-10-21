import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { test } from 'node:test'
import { computed, createRenderer, createSSRApp, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import { loadTypeScript, typeScriptModuleUrl } from './loadTypeScript.mjs'

const dates = await loadTypeScript('../src/dateTime.ts')
const { formatRequestDate } = await loadTypeScript('../src/requestDetail.ts')
const sample = new Date(2026, 9, 21, 23, 8, 39)
let isolatedModule = 0

async function withBrowser(initial, run, { readDenied = false, writeDenied = false, storageDenied = false } = {}) {
  const values = new Map(initial == null ? [] : [[dates.CLOCK_FORMAT_STORAGE_KEY, initial]])
  const listeners = new Map()
  const storage = {
    getItem(key) { if (readDenied) throw new Error('Storage blocked'); return values.get(key) ?? null },
    setItem(key, value) { if (writeDenied) throw new Error('Storage full'); values.set(key, value) }
  }
  const browser = {
    get localStorage() { if (storageDenied) throw new Error('Storage denied'); return storage },
    addEventListener(name, listener) { listeners.set(name, listener) }
  }
  const previous = globalThis.window
  globalThis.window = browser
  try {
    const source = readFileSync(new URL('../src/dateTime.ts', import.meta.url), 'utf8')
    const script = ts.transpileModule(source, {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 }
    }).outputText.replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
    const module = await import('data:text/javascript;base64,' + Buffer.from(script + `\n// fixture ${++isolatedModule}`).toString('base64'))
    await run(module, { values, storage, listeners })
  } finally {
    if (previous === undefined) delete globalThis.window
    else globalThis.window = previous
  }
}

test('developer timestamps default to exact zero-padded 24-hour format, with seconds', () => {
  assert.equal(dates.clockFormat.value, '24h')
  assert.equal(dates.formatDateTime(sample), '2026-10-21 23:08:39')
  assert.equal(dates.formatDateTime(new Date(2026, 0, 2, 3, 4, 5)), '2026-01-02 03:04:05')
  assert.equal(dates.formatDateTime(new Date(2026, 0, 2, 0, 0, 0)), '2026-01-02 00:00:00')
})

test('12-hour display unambiguously distinguishes midnight, noon and evening', () => {
  assert.equal(dates.formatDateTime(new Date(2026, 9, 21, 0, 0, 0), '12h'), '2026-10-21 오전 12:00:00')
  assert.equal(dates.formatDateTime(new Date(2026, 9, 21, 12, 0, 0), '12h'), '2026-10-21 오후 12:00:00')
  assert.equal(dates.formatDateTime(new Date(2026, 9, 21, 1, 2, 3), '12h'), '2026-10-21 오전 01:02:03')
  assert.equal(dates.formatDateTime(sample, '12h'), '2026-10-21 오후 11:08:39')
})

test('missing or invalid timestamps do not crash log rendering', () => {
  for (const value of [undefined, null, '', 'not-a-date', NaN, new Date(NaN)]) {
    assert.equal(dates.formatDateTime(value), '-')
    assert.equal(dates.formatDate(value), '-')
  }
  assert.notEqual(dates.formatDateTime(0), '-')
})

test('formatting preserves the instant and the browser timezone rather than shifting stored dates', () => {
  const iso = sample.toISOString()
  const before = sample.getTime()
  assert.equal(dates.formatDateTime(iso, '24h'), '2026-10-21 23:08:39')
  assert.equal(dates.formatDateTime(before, '24h'), '2026-10-21 23:08:39')
  const offsetInput = '2026-10-21T23:08:39+09:00'
  assert.equal(dates.formatDateTime(offsetInput), dates.formatDateTime('2026-10-21T14:08:39Z'))
  assert.equal(sample.getTime(), before)
  assert.equal(sample.toISOString(), iso)
  assert.equal(dates.browserTimeZone(), Intl.DateTimeFormat().resolvedOptions().timeZone)
})

test('existing reactive log timestamps update immediately, including request details', () => {
  const rendered = computed(() => formatRequestDate(sample.toISOString()))
  assert.equal(rendered.value, '2026-10-21 23:08:39')
  dates.setClockFormat('12h')
  assert.equal(rendered.value, '2026-10-21 오후 11:08:39')
  assert.equal(dates.formatDate(sample), '2026-10-21')
  dates.setClockFormat('24h')
  assert.equal(rendered.value, '2026-10-21 23:08:39')
})

test('preference is read from browser storage and changes are persisted', async () => {
  await withBrowser('12h', async (module, { values }) => {
    assert.equal(module.clockFormat.value, '12h')
    assert.equal(module.formatDateTime(sample), '2026-10-21 오후 11:08:39')
    module.setClockFormat('24h')
    assert.equal(values.get(dates.CLOCK_FORMAT_STORAGE_KEY), '24h')
    assert.equal(module.formatDateTime(sample), '2026-10-21 23:08:39')
  })
})

test('missing or invalid saved preferences safely default to 24-hour display', async () => {
  for (const saved of [null, 'unsupported', '', '12']) {
    await withBrowser(saved, async module => assert.equal(module.clockFormat.value, '24h'))
  }
})

test('blocked or full browser storage cannot prevent display changes', async () => {
  for (const options of [{ readDenied: true }, { writeDenied: true }, { storageDenied: true }]) {
    await withBrowser(null, async (module, { listeners, storage }) => {
      assert.equal(module.clockFormat.value, '24h')
      assert.doesNotThrow(() => module.setClockFormat('12h'))
      assert.equal(module.formatDateTime(sample), '2026-10-21 오후 11:08:39')
      assert.doesNotThrow(() => listeners.get('storage')({ key: dates.CLOCK_FORMAT_STORAGE_KEY, storageArea: storage, newValue: '24h' }))
    }, options)
  }
})

test('other browser tabs synchronize the preference; unrelated storage events are ignored', async () => {
  await withBrowser(null, async (module, { storage, listeners }) => {
    const event = listeners.get('storage')
    event({ key: 'aiconnect.theme', storageArea: storage, newValue: '12h' })
    event({ key: dates.CLOCK_FORMAT_STORAGE_KEY, storageArea: {}, newValue: '12h' })
    assert.equal(module.clockFormat.value, '24h')
    event({ key: dates.CLOCK_FORMAT_STORAGE_KEY, storageArea: storage, newValue: '12h' })
    assert.equal(module.clockFormat.value, '12h')
    event({ key: null, storageArea: storage, newValue: null })
    assert.equal(module.clockFormat.value, '24h')
  })
})

const controlSource = readFileSync(new URL('../src/DateTimePreferences.vue', import.meta.url), 'utf8')
const { descriptor } = parse(controlSource)
let controlScript = ts.transpileModule(compileScript(descriptor, { id: 'clock-control-test', inlineTemplate: true }).content, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 }
}).outputText
controlScript = controlScript.replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
  .replace(/from ['"]\.\/dateTime['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/dateTime.ts')))
const { default: control } = await import('data:text/javascript;base64,' + Buffer.from(controlScript).toString('base64'))

test('settings explain both formats, exact examples, persistence and unchanged timezone', async () => {
  const html = await renderToString(createSSRApp(control))
  assert.match(html, /24시간제 · 기본/)
  assert.match(html, /12시간제/)
  assert.match(html, /2026-10-21 23:08:39/)
  assert.match(html, /2026-10-21 오후 11:08:39/)
  assert.match(html, /이 브라우저에 저장/)
  assert.match(html, /실제 요청 시각은 바뀌지 않습니다/)
  assert.match(html, /aria-pressed="true"/)
  assert.match(html, /aria-pressed="false"/)
})

test('clicking format choices updates the selected button and an already mounted log', async () => {
  const node = tag => ({ tag, props: {}, children: [], text: '', parent: null })
  const renderer = createRenderer({
    createElement: node,
    createText: text => ({ ...node('#text'), text }),
    createComment: text => ({ ...node('#comment'), text }),
    patchProp: (element, key, oldValue, value) => { element.props[key] = value },
    insert(element, parent, anchor = null) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, element)
      element.parent = parent
    },
    remove(element) { element.parent?.children.splice(element.parent.children.indexOf(element), 1) },
    setElementText(element, text) { element.text = text; element.children = [] },
    setText(element, text) { element.text = text },
    parentNode: element => element.parent,
    nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null
  })
  const root = node('root')
  const log = computed(() => dates.formatDateTime(sample))
  const app = renderer.createApp(control)
  app.mount(root)
  const findButtons = element => element.children.flatMap(child => child.tag === 'button' ? [child] : findButtons(child))
  try {
    const buttons = findButtons(root)
    assert.equal(buttons.length, 2)
    assert.equal(buttons[0].props['aria-pressed'], true)
    buttons[1].props.onClick()
    await nextTick()
    assert.equal(log.value, '2026-10-21 오후 11:08:39')
    assert.equal(buttons[0].props['aria-pressed'], false)
    assert.equal(buttons[1].props['aria-pressed'], true)
    buttons[0].props.onClick()
    await nextTick()
    assert.equal(log.value, '2026-10-21 23:08:39')
    assert.equal(buttons[0].props['aria-pressed'], true)
  } finally {
    app.unmount()
    dates.setClockFormat('24h')
  }
})

test('all timestamp views use shared formatting, not independent 12-hour locale defaults', () => {
  const files = readdirSync(new URL('../src/', import.meta.url)).filter(file => /\.(vue|ts)$/.test(file))
  for (const file of files) {
    const source = readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /\.toLocale(?:TimeString|DateString)\(/, file)
    assert.doesNotMatch(source, /new Date\([^\n]*?\)\.toLocaleString\(/, file)
  }
  const consoleSource = readFileSync(new URL('../src/ConsoleShell.vue', import.meta.url), 'utf8')
  assert.match(consoleSource, /<DateTimePreferences\s*\/>/)
  assert.match(consoleSource, /title="환경 설정"/)
})
