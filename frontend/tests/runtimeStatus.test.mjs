import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

async function load(path) {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
}
const { runtimeStatus } = await load('../src/runtimeStatus.ts')
const { resourceStatus, modelStatus } = await load('../src/systemTopology.ts')
const endpoint = (healthStatus, enabled = true) => ({ healthStatus, enabled })

test('standby explains a connected API without a loaded model, not a failed server', () => {
  const state = runtimeStatus(endpoint('STANDBY'))
  assert.equal(state.label, '모델 대기')
  assert.match(state.description, /API 연결은 정상/)
  assert.match(state.description, /로드된 모델이 없어/)
  assert.equal(state.className, 'standby')
  assert.equal(resourceStatus(endpoint('STANDBY')).label, '모델 대기')
  assert.notEqual(resourceStatus(endpoint('STANDBY')).tone, 'error')
})

test('loading and unknown loaded state do not claim inference readiness', () => {
  assert.equal(runtimeStatus(endpoint('LOADING')).label, '모델 준비 중')
  assert.equal(runtimeStatus(endpoint('CONNECTED')).label, '모델 상태 미확인')
  for (const code of ['LOADING', 'CONNECTED', 'STANDBY']) {
    assert.notEqual(resourceStatus(endpoint(code)).tone, 'healthy')
    assert.notEqual(modelStatus({ enabled: true, loaded: true, healthStatus: 'HEALTHY' }, endpoint(code), false).tone, 'healthy')
  }
})

test('disabled and draining override misleading saved readiness', () => {
  assert.equal(runtimeStatus(endpoint('HEALTHY', false)).code, 'DISABLED')
  assert.equal(runtimeStatus(endpoint('DRAINING')).label, '신규 요청 중지')
})

test('failure and recovery remain distinct from normal standby', () => {
  assert.equal(runtimeStatus(endpoint('SUSPECT')).label, '연결 불안정')
  assert.equal(runtimeStatus(endpoint('UNHEALTHY')).label, '연결·복구 실패')
  assert.equal(runtimeStatus(endpoint('RECOVERING')).label, '복구 확인 중')
  assert.equal(runtimeStatus(endpoint('HEALTHY')).label, '준비됨')
})

test('new states have visible colors and infrastructure shows explicit descriptions', () => {
  const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
  const page = readFileSync(new URL('../src/InfrastructurePage.vue', import.meta.url), 'utf8')
  assert.match(css, /\.node-light\.standby/)
  assert.match(css, /\.status-chip\.loading/)
  assert.match(page, /runtimeStatus\(selected\)\.description/)
  assert.match(page, /setInterval\(refreshEndpointStates, 15000\)/)
  assert.match(page, /clearInterval\(statusTimer\)/)
})
