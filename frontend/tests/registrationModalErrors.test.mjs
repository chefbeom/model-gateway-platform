import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

const feedback = await loadTypeScript('../src/requestFeedback.ts')
const api = await loadTypeScript('../src/api.ts')
const read = path => readFileSync(new URL(path, import.meta.url), 'utf8')

test('describeRequestError includes message and HTTP status', () => {
  const error = Object.assign(new Error('권한이 없습니다.'), { status: 403 })
  assert.equal(feedback.describeRequestError(error, '외부 모델 등록', 30000), '외부 모델 등록에 실패했습니다: 권한이 없습니다. (HTTP 403)')
  assert.equal(feedback.describeRequestError(new Error('boom'), '저장', 30000), '저장에 실패했습니다: boom')
})

test('timeouts and aborts map to the no-response message', () => {
  const expected = '서버 응답이 없습니다(30초). 네트워크 또는 프록시 상태를 확인하세요.'
  assert.equal(feedback.describeRequestError(new Error('x'), '저장', 30000, true), expected)
  assert.equal(feedback.describeRequestError(new DOMException('aborted', 'AbortError'), '저장', 30000), expected)
  assert.match(feedback.timeoutMessage(90000), /90초/)
  assert.equal(feedback.SAVE_REQUEST_TIMEOUT_MS, 30000)
  assert.equal(feedback.PROVIDER_REQUEST_TIMEOUT_MS, 90000)
})

test('createRequestTimeout aborts the signal and reports timedOut', async () => {
  const timeout = feedback.createRequestTimeout(5)
  await new Promise(resolve => setTimeout(resolve, 30))
  assert.equal(timeout.signal.aborted, true)
  assert.equal(timeout.timedOut(), true)
  const cleared = feedback.createRequestTimeout(5)
  cleared.clear()
  await new Promise(resolve => setTimeout(resolve, 30))
  assert.equal(cleared.signal.aborted, false)
})

test('an aborted admin request does not raise session-expired, a network failure does', async () => {
  const previousFetch = globalThis.fetch
  const previousWindow = globalThis.window
  const events = []
  globalThis.window = { dispatchEvent: event => { events.push(event.detail?.reason); return true }, location: { origin: 'http://localhost' } }
  try {
    globalThis.fetch = (_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })
    const timeout = feedback.createRequestTimeout(10)
    await assert.rejects(
      api.adminFetch('/api/admin/external-providers/p/models', { accessToken: 'tok' }, { method: 'POST', body: '{}', signal: timeout.signal }),
      error => error.name === 'AbortError'
    )
    assert.equal(timeout.timedOut(), true)
    assert.deepEqual(events, [])

    globalThis.fetch = async () => { throw new TypeError('Failed to fetch') }
    await assert.rejects(api.adminFetch('/api/admin/x', { accessToken: 'tok' }), /연결/)
    assert.deepEqual(events, ['server_unavailable'])
  } finally {
    globalThis.fetch = previousFetch
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
  }
})

test('post-save refresh failure is routed to the page-level message, not the closed provider modal', () => {
  const page = read('../src/ExternalProvidersPageImpl.vue')
  const start = page.indexOf('async function saveProvider')
  const saveProvider = page.slice(start, page.indexOf('async function probe', start))
  const refresh = saveProvider.slice(saveProvider.indexOf('try { await loadModels() }'))
  assert.match(refresh, /catch \(error\) \{ message\.value = .*describeRequestError\(error, '모델 목록 새로고침'/)
  assert.doesNotMatch(refresh, /providerModalError/)
  assert.match(refresh, /if \(shouldProbe\) await probe\(\)/)
  // the save request itself is the only thing that writes to the modal error
  assert.equal(saveProvider.match(/providerModalError\.value = describeRequestError/g).length, 1)
  assert.ok(saveProvider.indexOf('saveSucceeded = true') < saveProvider.indexOf('try { await loadModels() }'))
})

test('modal actions write errors to modal-scoped refs and use the timeout', () => {
  const page = read('../src/ExternalProvidersPageImpl.vue')
  assert.match(page, /modelModalError\.value = describeRequestError\(error, '외부 모델 등록\/수정'/)
  assert.match(page, /providerModalError\.value = describeRequestError\(error, '외부 Provider 저장'/)
  assert.match(page, /probeModalError\.value = describeRequestError\(error, '외부 모델 일괄 등록'/)
  assert.match(page, /modelModalError\.value = describeRequestError\(error, 'Provider 기능 확인'/)
  assert.match(page, /v-if="modelModalError" class="inline-alert" role="alert"/)
  assert.match(page, /v-if="probeModalError" class="inline-alert" role="alert"/)
  assert.match(page, /signal:timeout\.signal/)
  assert.match(page, /등록 중…/)
  const saveModel = page.slice(page.indexOf('async function saveModel'), page.indexOf('\n', page.indexOf('async function saveModel')))
  assert.doesNotMatch(saveModel, /showError/)

  const infra = read('../src/InfrastructurePage.vue')
  const createRuntime = infra.slice(infra.indexOf('async function createRuntime'), infra.indexOf('async function action'))
  assert.match(createRuntime, /runtimeModalError\.value = describeRequestError\(error, 'Runtime 등록'/)
  assert.doesNotMatch(createRuntime, /message\.value = error/)
  assert.match(infra, /v-if="runtimeModalError" class="inline-alert" role="alert"/)
})
