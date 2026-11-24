import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

// Source-level checks for the Workflow Designer v2 Vue components (no DOM available in node:test).
function read(name) {
  return readFileSync(new URL(`../src/flow-designer/${name}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')
}

const FILES = ['FlowCanvas.vue', 'FlowNodeCard.vue', 'FlowPalette.vue', 'FlowInspector.vue', 'FlowToolbar.vue', 'FlowQuickStart.vue']

test('FlowCanvas: v2 addable types, dependency drag, edge insert and terminology', () => {
  const text = read('FlowCanvas.vue')
  const addable = text.match(/const ADDABLE[^=]*=\s*\[([^\]]*)\]/)
  assert.ok(addable, 'ADDABLE must exist')
  for (const type of ['llm', 'guardrail', 'prompt', 'params', 'cache', 'router', 'masker', 'project']) {
    assert.ok(addable[1].includes(`'${type}'`), `ADDABLE must include ${type}`)
  }
  assert.doesNotMatch(addable[1], /'target'|'binding'/)
  assert.doesNotMatch(text, /'target'|'binding'|'deployment'/, 'v1 node/edge names must be gone from FlowCanvas')
  assert.match(text, /import \{[^}]*\bdependencyClosure\b[^}]*\} from '\.\/workflow'/)
  assert.match(text, /event\.ctrlKey \|\| event\.metaKey/)
  assert.match(text, /data-edge-insert/)
  assert.match(text, /el\.closest\('\[data-edge-insert\]'\)/, 'pointerdown must ignore the edge insert button')
  assert.match(text, /data-insert-menu/)
  assert.match(text, /role="menu"/)
  assert.match(text, /nearestEdge\(/)
  assert.match(text, /spliceEdgeId/)
  assert.match(text, /autoWire: !event\.altKey/)
  assert.match(text, /mode\?: DesignerMode/)
  assert.match(text, /\{ mode: 'advanced' \}/)
  assert.match(text, /선택한 간선 삭제/)
  assert.doesNotMatch(text, /선택한 연결 삭제/)
  assert.match(text, /워크플로우 캔버스/)
  assert.doesNotMatch(text, /서비스 플로우 캔버스/)
  assert.match(text, /간선을 추가했습니다\./)
  assert.match(text, /간선을 제거했습니다\./)
})

test('FlowCanvas: basic mode hides infrastructure nodes and host edges from rendering', () => {
  const text = read('FlowCanvas.vue')
  assert.match(text, /props\.mode !== 'basic'/)
  assert.match(text, /e\.kind !== 'host'/)
  assert.match(text, /v-for="node in visibleGraph\.nodes"/)
})

test('FlowInspector: v2 fields, protected deletion, model replace and edge panel', () => {
  const text = read('FlowInspector.vue')
  assert.doesNotMatch(text, /bindTarget|deployment-binding/)
  for (const needle of ['replaceModel', 'force: true', 'guardrail-preset', 'model-replace', '간선 삭제', '보호 해제 후 삭제']) {
    assert.ok(text.includes(needle), `FlowInspector must contain ${needle}`)
  }
  assert.match(text, /window\.confirm\('외부 노출 방지 정책을 해제하고 가드레일을 삭제할까요\?/)
  assert.match(text, /mode\?: DesignerMode/)
  assert.match(text, /field\.advanced && props\.mode === 'basic'/)
  assert.match(text, /field\.basicOnly/)
  assert.match(text, /issue\.fix/)
  assert.match(text, /이 간선에 노드 넣기/)
  assert.match(text, /프로젝트 화면에서 변경/)
})

test('FlowPalette: renders buildPalette output without its own sorting', () => {
  const text = read('FlowPalette.vue')
  assert.match(text, /import \{ buildPalette \} from '\.\/palette'/)
  assert.doesNotMatch(text, /localeCompare/)
  assert.doesNotMatch(text, /\.sort\(/)
  assert.match(text, /중지·미로드/)
  assert.match(text, /aria-expanded/)
  assert.match(text, /mode\?: DesignerMode/)
  assert.match(text, /select-node/)
})

test('FlowToolbar: mode toggle, normalize order and preserved zoom buttons', () => {
  const text = read('FlowToolbar.vue')
  assert.match(text, /워크플로우 디자이너 도구 모음/)
  assert.doesNotMatch(text, /aria-label="플로우 디자이너/)
  assert.match(text, /fire\('mode-basic'\)/)
  assert.match(text, /fire\('mode-advanced'\)/)
  assert.match(text, /fire\('normalize-order'\)/)
  assert.match(text, /canNormalize/)
  assert.match(text, /aria-pressed/)
  for (const action of ['zoom-out', 'zoom-reset', 'zoom-in']) assert.ok(text.includes(`fire('${action}')`), `fire('${action}') must stay`)
})

test('FlowNodeCard: protected, locked and prompt scope chips with v2 category colours', () => {
  const text = read('FlowNodeCard.vue')
  assert.match(text, /view\.protected/)
  assert.match(text, /view\.locked/)
  assert.match(text, /view\.promptScope/)
  for (const label of ['보호', '정책', '자동', '공통', '모델별']) assert.ok(text.includes(label), `chip ${label}`)
  for (const category of ['guard', 'prompt', 'cache', 'llm']) assert.ok(text.includes(`data-category='${category}'`), `category ${category}`)
  assert.doesNotMatch(text, /data-category='pipeline'/)
})

test('FlowQuickStart: sends applyTemplate and uses the template catalog', () => {
  const text = read('FlowQuickStart.vue')
  assert.match(text, /import \{[^}]*\bWORKFLOW_TEMPLATES\b[^}]*\} from '\.\/catalog'/)
  assert.match(text, /type: 'applyTemplate'/)
  assert.match(text, /SERVICE_KEY_PATTERN/)
  assert.match(text, /emit\('dismiss'\)/)
  assert.match(text, /직접 구성/)
  assert.match(text, /만들기/)
})

test('no browser storage, raw HTML injection or hardcoded colours in the Vue components', () => {
  for (const name of FILES) {
    const text = read(name)
    for (const forbidden of ['localStorage', 'sessionStorage', 'v-html', 'innerHTML']) {
      assert.ok(!text.includes(forbidden), `${name} must not use ${forbidden}`)
    }
    assert.doesNotMatch(text, /#[0-9a-fA-F]{3,8}\b|rgba?\(/, `${name} must use CSS variables only`)
    assert.doesNotMatch(text, /bindTarget|deployment-binding/, `${name} must not use legacy v1 members`)
  }
})
