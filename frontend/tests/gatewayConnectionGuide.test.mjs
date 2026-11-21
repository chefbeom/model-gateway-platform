import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick } from 'vue'
import ts from 'typescript'

const source = readFileSync(new URL('../src/IssuedConnectionGuide.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
let script = ts.transpileModule(compileScript(descriptor, { id: 'guide', inlineTemplate: true }).content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
script = script.replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
script = script.replace(/from ['"]\.\/clipboard['"]/g, 'from ' + JSON.stringify('data:text/javascript,export async function copyText(value){globalThis.copied=value}'))
const Guide = (await import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))).default
function render(props) {
  const renderer = createRenderer({
    createElement: tag => ({ tag, children: [], props: {} }), createText: text => ({ text }), createComment: text => ({ text }),
    setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text },
    patchProp: (node, key, old, value) => { node.props[key] = value },
    insert: (node, parent) => { parent.children.push(node) }, remove: () => {}, parentNode: () => null, nextSibling: () => null
  })
  const root = { children: [] }
  renderer.createApp({ render: () => h(Guide, props) }).mount(root)
  const flatten = node => [node, ...(node.children ?? []).flatMap(flatten)]
  return flatten(root)
}
const visible = nodes => nodes.map(node => node.text ?? '').join(' ')

test('independent internal and external settings show absent entries and preserve prefix for Calen', async () => {
  const nodes = render({ endpoints: [{ scope: 'EXTERNAL', label: 'External', url: 'https://api.example/prefix/v1' }], models: [] })
  const text = visible(nodes)
  assert.match(text, /내부 Gateway/); assert.match(text, /설정되지 않았습니다/)
  assert.match(text, /https:\/\/api.example\/prefix\/v1/); assert.match(text, /https:\/\/api.example\/prefix/)
  const button = nodes.find(node => node.tag === 'button' && node.text === '복사')
  await button.props.onClick(); await nextTick()
  assert.equal(globalThis.copied, 'https://api.example/prefix/v1')
})
test('fetch failure is distinct from missing settings', () => {
  const text = visible(render({ endpoints: [], models: [], connectionError: true }))
  assert.match(text, /조회에 실패/); assert.doesNotMatch(text, /설정되지 않았습니다/)
})
test('both missing and internal-only addresses are represented separately', () => {
  const missing = visible(render({ endpoints: [], models: [] }))
  assert.equal((missing.match(/설정되지 않았습니다/g) ?? []).length, 2)
  const internal = visible(render({ endpoints: [{ scope: 'INTERNAL', label: 'Internal', url: 'http://internal.example/v1' }], models: [] }))
  assert.match(internal, /http:\/\/internal.example\/v1/)
  assert.equal((internal.match(/설정되지 않았습니다/g) ?? []).length, 1)
})
test('playground keeps browser test route distinct from advertised SDK addresses', () => {
  const playground = readFileSync(new URL('../src/ApiPlaygroundPage.vue', import.meta.url), 'utf8')
  assert.match(playground, /window\.location\.origin \+ '\/v1'/)
  assert.match(playground, /SDK용 주소는 브라우저 테스트 대상에 자동 적용되지 않습니다/)
  assert.match(playground, /선택한 SDK 주소 복사/)
  assert.doesNotMatch(playground, /baseUrl\.value\s*=\s*selectedConnectionUrl/)
})
