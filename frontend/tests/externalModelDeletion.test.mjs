import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const source = readFileSync(new URL('../src/ExternalProvidersPageImpl.vue', import.meta.url), 'utf8')

test('each registered model has an accessible delete action and a separate confirmation', () => {
  assert.match(source, /:aria-label="`\$\{model\.displayName\} 모델 삭제`"/)
  assert.match(source, /@click="openModelDelete\(model\)"/)
  assert.match(source, /title="외부 모델 삭제"/)
  assert.match(source, /Provider 연결과 API 키, 다른 모델은 변경하지 않습니다/)
  assert.match(source, /기존 요청 및 테스트 이력은 보존됩니다/)
})

test('preview scopes deletion to the selected provider and prevents deleting referenced models', () => {
  assert.match(source, /models\/\$\{model\.id\}\/deletion-preview/)
  assert.match(source, /modelDeletePreview\.targetCount > 0/)
  assert.match(source, /Target을 먼저 해제하세요/)
  assert.match(source, /models\/\$\{item\.modelId\}`.*method:'DELETE'/)
  assert.match(source, /selectedId\.value === providerId/)
  assert.match(source, /modelDeleteError/)
})
