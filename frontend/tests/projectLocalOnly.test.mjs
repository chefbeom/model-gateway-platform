import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createSSRApp } from 'vue'
import { renderToString } from '@vue/server-renderer'
import ts from 'typescript'

const source = readFileSync(new URL('../src/ProjectLocalOnlyControl.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
let script = ts.transpileModule(compileScript(descriptor, { id: 'test-local-boundary', inlineTemplate: true }).content,
  { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
script = script.replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
const { default: control } = await import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))

test('enabled boundary displays checked control and fail-closed warning', async () => {
  const html = await renderToString(createSSRApp(control, { modelValue: true }))
  assert.match(html, /type="checkbox"[^>]*checked/)
  assert.match(html, /모든 API 키/)
  assert.match(html, /민감정보 탐지 여부와 관계없이/)
  assert.match(html, /외부로 전환하지 않습니다/)
})

test('disabled boundary does not promise unrestricted provider access', async () => {
  const html = await renderToString(createSSRApp(control, { modelValue: false }))
  assert.doesNotMatch(html, /type="checkbox"[^>]*checked/)
  assert.match(html, /기존 데이터 보호 정책과 Provider 사용 권한/)
})
