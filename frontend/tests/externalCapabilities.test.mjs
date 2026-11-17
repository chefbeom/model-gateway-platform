import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

const source = readFileSync(new URL('../src/externalCapabilities.ts', import.meta.url), 'utf8')
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ES2022 } }).outputText
const { unknownCapabilityPolicy, withUnknownCapabilityPolicy } = await import(`data:text/javascript,${encodeURIComponent(js)}`)
const page = readFileSync(new URL('../src/ExternalProvidersPageImpl.vue', import.meta.url), 'utf8')
const manager = readFileSync(new URL('../src/ModelFeatureSupportManager.vue', import.meta.url), 'utf8')

test('unknown capabilities are strict by default and optional policy preserves other metadata', () => {
  assert.equal(unknownCapabilityPolicy(null), 'STRICT')
  assert.equal(unknownCapabilityPolicy('not json'), 'STRICT')
  const updated = withUnknownCapabilityPolicy('{"vision":"UNSUPPORTED","custom":"retained"}', 'TRY_UNCONFIRMED')
  assert.deepEqual(JSON.parse(updated), { vision:'UNSUPPORTED', custom:'retained', routingUnknownCapabilities:'TRY_UNCONFIRMED' })
  assert.equal(unknownCapabilityPolicy(updated), 'TRY_UNCONFIRMED')
  assert.deepEqual(JSON.parse(withUnknownCapabilityPolicy(updated, 'STRICT')), { vision:'UNSUPPORTED', custom:'retained' })
})

test('model settings distinguish provider metadata from unconfirmed trial and preserve policy', () => {
  assert.match(page, /mode="model"/)
  assert.match(page, /미확인 기능도 Provider에 시도/)
  assert.match(page, /model\?\.providerCapabilities/)
  assert.match(page, /지원 여부는 미확인/)
  assert.match(page, /데이터 보호와 외부 사용 권한은 그대로 적용/)
  assert.match(manager, /\.\.\.previous, \.\.\.support\.value/)
})
