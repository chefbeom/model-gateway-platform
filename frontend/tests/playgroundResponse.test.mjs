import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

const { responseDiagnostic, responseInfo } = await loadTypeScript('../src/playgroundResponse.ts')
const turn = extra => ({ role: 'assistant', status: 'SUCCEEDED', httpStatus: 200, content: '', ...extra })

test('empty content with a confirmed length finish warns and suggests a larger limit', () => {
  const info = responseInfo({ finish_reason: 'length', message: { content: '', reasoning_content: 'private' } })
  assert.deepEqual(info, { finishReason: 'length', reasoningPresent: true })
  const diagnostic = responseDiagnostic(turn({ ...info, outputTokens: 512, outputLimit: 512 }))
  assert.equal(diagnostic.label, '응답 없음')
  assert.match(diagnostic.title, /출력 한도/)
  assert.match(diagnostic.detail, /Max completion tokens/)
  assert.doesNotMatch(JSON.stringify(info) + JSON.stringify(diagnostic), /private/)
})

test('matching token count without finish reason is only a possibility', () => {
  const diagnostic = responseDiagnostic(turn({ outputTokens: 512, outputLimit: 512 }))
  assert.match(diagnostic.detail, /가능성/)
  assert.match(diagnostic.detail, /확정할 수 없습니다/)
})

test('partial answer with length is shown but marked incomplete', () => {
  const diagnostic = responseDiagnostic(turn({ content: 'partial text', finishReason: 'length' }))
  assert.equal(diagnostic.label, '응답 잘림')
  assert.match(diagnostic.detail, /미완성/)
  assert.equal(responseDiagnostic(turn({ content: 'complete', finishReason: 'stop' })), null)
})

test('legacy saved empty success does not invent a finish reason or token limit', () => {
  const diagnostic = responseDiagnostic(turn({ outputTokens: 512 }))
  assert.equal(diagnostic.label, '응답 없음')
  assert.match(diagnostic.detail, /종료 사유가 기록되지 않아/)
})

test('network and upstream failures keep their actual error message instead of false empty-success diagnosis', () => {
  assert.equal(responseDiagnostic(turn({ status: 'FAILED', httpStatus: 500 })), null)
  assert.equal(responseDiagnostic(turn({ status: 'FAILED', httpStatus: undefined })), null)
  assert.equal(responseDiagnostic(turn({ status: 'FAILED', httpStatus: 200, content: 'partial', finishReason: 'length', error: 'stream interrupted' })), null)
  assert.equal(responseDiagnostic(turn({ status: 'STREAMING' })), null)
  assert.equal(responseDiagnostic({ role: 'user', status: 'SUCCEEDED', content: '' }), null)
})

test('streaming metadata can arrive separately from content without exposing reasoning', () => {
  assert.deepEqual(responseInfo({ delta: { reasoning_content: 'private' }, finish_reason: null }, true), { finishReason: undefined, reasoningPresent: true })
  assert.deepEqual(responseInfo({ delta: {}, finish_reason: 'length' }, true), { finishReason: 'length', reasoningPresent: false })
  assert.equal(responseInfo({ finish_reason: 'unsafe private content!', message: {} }).finishReason, undefined)
})

test('content-filtered answers are distinguished from token exhaustion', () => {
  assert.match(responseDiagnostic(turn({ finishReason: 'content_filter' })).title, /안전 필터/)
  assert.match(responseDiagnostic(turn({ content: 'partial', finishReason: 'content_filter' })).title, /안전 필터/)
})

test('console attaches diagnostics to live and restored turns without claiming success on empty output', () => {
  const source = readFileSync(new URL('../src/ModelPlaygroundPage.vue', import.meta.url), 'utf8')
  assert.match(source, /responseDiagnostic\(turn\)/)
  assert.match(source, /responseInfo\(event\.choices\?\.\[0\], true\)/)
  assert.match(source, /!assistantTurn\.content\.trim\(\) \? 'FAILED'/)
  assert.match(source, /receivedDone/)
  assert.doesNotMatch(source, /reasoning_content\s*\}\}/)
})
