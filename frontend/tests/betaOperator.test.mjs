import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, createSSRApp, nextTick } from 'vue'
import { renderToString } from '@vue/server-renderer'
import ts from 'typescript'
import { typeScriptModuleUrl } from './loadTypeScript.mjs'

const source = readFileSync(new URL('../src/BetaOperatorPage.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
let script = ts.transpileModule(
  compileScript(descriptor, { id: 'beta-operator-test', inlineTemplate: true }).content,
  { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }
).outputText
script = script
  .replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
  .replace(/from ['"]\.\/api['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/api.ts')))
  .replace(/from ['"]\.\/clipboard['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/clipboard.ts')))
  .replace(/from ['"]\.\/dateTime['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/dateTime.ts')))

const { default: BetaOperatorPage } = await import(
  'data:text/javascript;base64,' + Buffer.from(script).toString('base64')
)
const { formatDateTime } = await import(typeScriptModuleUrl('../src/dateTime.ts'))

function createMemoryDom() {
  const doc = { activeElement: null }
  const node = tag => {
    const element = {
      tag,
      props: {},
      children: [],
      text: '',
      value: '',
      selected: false,
      composing: false,
      parent: null,
      listeners: new Map(),
      getRootNode() { return doc },
      addEventListener(type, fn) {
        const list = element.listeners.get(type) ?? []
        list.push(fn)
        element.listeners.set(type, list)
      },
      removeEventListener(type, fn) {
        const list = element.listeners.get(type) ?? []
        element.listeners.set(type, list.filter(item => item !== fn))
      },
      dispatch(type) {
        for (const fn of element.listeners.get(type) ?? []) fn({ target: element })
      },
      get options() {
        return element.children.filter(child => child.tag === 'option')
      }
    }
    return element
  }
  const renderer = createRenderer({
    createElement: node,
    createText: text => ({ ...node('#text'), text }),
    createComment: text => ({ ...node('#comment'), text }),
    patchProp: (element, key, oldValue, value) => {
      element.props[key] = value
      if (key === 'value') element.value = value ?? ''
    },
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
  const walk = (element, match) => {
    const out = match(element) ? [element] : []
    for (const child of element.children ?? []) out.push(...walk(child, match))
    return out
  }
  const textContent = element => {
    if (element.tag === '#text') return element.text
    if (element.tag === '#comment') return ''
    return (element.text || '') + (element.children ?? []).map(textContent).join('')
  }
  return { renderer, root, walk, textContent }
}

async function withDomGlobals(run) {
  const previousFetch = globalThis.fetch
  const previousWindow = globalThis.window
  const previousSessionStorage = globalThis.sessionStorage
  const previousDocument = globalThis.Document
  const previousShadowRoot = globalThis.ShadowRoot
  globalThis.Document = class Document {}
  globalThis.ShadowRoot = class ShadowRoot {}
  globalThis.sessionStorage = { getItem: () => null }
  globalThis.window = { confirm: () => true }
  try {
    await run()
  } finally {
    globalThis.fetch = previousFetch
    if (previousWindow === undefined) delete globalThis.window
    else globalThis.window = previousWindow
    if (previousSessionStorage === undefined) delete globalThis.sessionStorage
    else globalThis.sessionStorage = previousSessionStorage
    if (previousDocument === undefined) delete globalThis.Document
    else globalThis.Document = previousDocument
    if (previousShadowRoot === undefined) delete globalThis.ShadowRoot
    else globalThis.ShadowRoot = previousShadowRoot
  }
}

async function flush() {
  for (let i = 0; i < 8; i++) {
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}

test('requires user login for platform token sessions and hides platform-admin controls from operators', async () => {
  const noUserHtml = await renderToString(createSSRApp(BetaOperatorPage, {
    auth: { platformToken: 'emergency-token' },
    platformAdmin: true
  }))
  assert.match(noUserHtml, /초대 서비스 운영/)
  assert.match(noUserHtml, /로컬 전용 베타 · 외부 AI 호출은 지원하지 않습니다/)
  assert.match(noUserHtml, /사용자 계정 로그인이 필요합니다/)
  assert.doesNotMatch(noUserHtml, /플랫폼 관리자: 베타 생성 및 운영자 지정/)

  const operatorHtml = await renderToString(createSSRApp(BetaOperatorPage, {
    auth: { accessToken: 'user-jwt' },
    platformAdmin: false
  }))
  assert.doesNotMatch(operatorHtml, /플랫폼 관리자: 베타 생성 및 운영자 지정/)

  const adminHtml = await renderToString(createSSRApp(BetaOperatorPage, {
    auth: { accessToken: 'admin-jwt' },
    platformAdmin: true
  }))
  assert.match(adminHtml, /플랫폼 관리자: 베타 생성 및 운영자 지정/)
})

test('supports LOCAL_ONLY/PAUSED switching, one-time invite code reveal, revocation and member suspension', async () => {
  await withDomGlobals(async () => {
    const calls = []
    let serviceMode = 'PAUSED'
    let invitations = [
      { id: 'inv-pending', email: 'pending@example.com', expiresAt: '2026-11-22T15:30:00Z', status: 'PENDING' },
      { id: 'inv-used', email: 'used@example.com', expiresAt: '2026-11-21T09:00:00Z', status: 'CONSUMED' }
    ]
    let members = [
      { id: 'mem-active', email: 'active@example.com', projectId: 'proj-1', active: true },
      { id: 'mem-suspended', email: 'stopped@example.com', projectId: 'proj-2', active: false }
    ]

    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      const okJson = payload => ({
        ok: true,
        status: 200,
        text: async () => JSON.stringify(payload)
      })
      if (url === '/api/beta/operator/services' && method === 'GET') {
        return okJson([{ id: 'beta-svc-1', organizationId: 'org-1', logicalServiceId: 'llm-1', mode: serviceMode }])
      }
      if (url === '/api/beta/operator/services/beta-svc-1/members' && method === 'GET') {
        return okJson(members)
      }
      if (url === '/api/beta/operator/services/beta-svc-1/invitations' && method === 'GET') {
        return okJson(invitations)
      }
      if (url === '/api/beta/operator/services/beta-svc-1/mode' && method === 'PATCH') {
        serviceMode = body.mode
        return { ok: true, status: 204, text: async () => '' }
      }
      if (url === '/api/beta/operator/services/beta-svc-1/invitations' && method === 'POST') {
        invitations = [
          ...invitations,
          { id: 'inv-new', email: body.email, expiresAt: body.expiresAt, status: 'PENDING' }
        ]
        return okJson({ id: 'inv-new', token: 'one-time-beta-invite-secret-token', expiresAt: body.expiresAt })
      }
      if (url === '/api/beta/operator/services/beta-svc-1/invitations/inv-pending/revoke' && method === 'POST') {
        invitations = invitations.map(item => item.id === 'inv-pending' ? { ...item, status: 'REVOKED' } : item)
        return { ok: true, status: 204, text: async () => '' }
      }
      if (url === '/api/beta/operator/services/beta-svc-1/members/mem-active/suspend' && method === 'POST') {
        members = members.map(item => item.id === 'mem-active' ? { ...item, active: false } : item)
        return { ok: true, status: 204, text: async () => '' }
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp(BetaOperatorPage, {
      auth: { accessToken: 'operator-jwt' },
      platformAdmin: false
    })
    try {
      app.mount(root)
      await flush()

      const findButton = label => walk(root, el => el.tag === 'button' && textContent(el).includes(label))
      assert.match(textContent(root), /PAUSED · 신규 요청 중지/)
      assert.equal(findButton('신규 요청 중지')[0].props.disabled, true)
      assert.equal(findButton('로컬 전용 시작')[0].props.disabled, false)

      // Formatted timestamp (shared dateTime formatter, not raw ISO string)
      assert.ok(textContent(root).includes(formatDateTime('2026-11-22T15:30:00Z')))
      assert.doesNotMatch(textContent(root), /2026-11-22T15:30:00Z/)

      // Switch PAUSED -> LOCAL_ONLY -> PAUSED
      findButton('로컬 전용 시작')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.method === 'PATCH' && c.url.endsWith('/mode') && c.body.mode === 'LOCAL_ONLY'))
      assert.match(textContent(root), /LOCAL_ONLY · 로컬 전용 활성/)
      assert.equal(findButton('로컬 전용 시작')[0].props.disabled, true)
      assert.equal(findButton('신규 요청 중지')[0].props.disabled, false)

      findButton('신규 요청 중지')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.method === 'PATCH' && c.url.endsWith('/mode') && c.body.mode === 'PAUSED'))
      assert.match(textContent(root), /PAUSED · 신규 요청 중지/)

      // Issue one-time invitation
      const inputs = walk(root, el => el.tag === 'input')
      assert.equal(inputs.length, 2)
      inputs[0].value = 'beta-user@example.com'
      inputs[0].dispatch('input')
      inputs[1].value = '2026-11-23T12:00'
      inputs[1].dispatch('input')
      await nextTick()
      findButton('1회용 초대 발급')[0].props.onClick()
      await flush()
      assert.match(textContent(root), /초대 코드 \(한 번만 표시\)/)
      assert.match(textContent(root), /one-time-beta-invite-secret-token/)

      // Dismiss one-time token
      findButton('닫기')[0].props.onClick()
      await nextTick()
      assert.doesNotMatch(textContent(root), /one-time-beta-invite-secret-token/)

      // Only PENDING invitations have revoke action; only active members have suspend action
      assert.equal(findButton('초대 회수').length, 2)
      findButton('초대 회수')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.method === 'POST' && c.url.endsWith('/invitations/inv-pending/revoke')))

      assert.equal(findButton('정지 (키 모두 거부)').length, 1)
      findButton('정지 (키 모두 거부)')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.method === 'POST' && c.url.endsWith('/members/mem-active/suspend')))
      assert.equal(findButton('정지 (키 모두 거부)').length, 0)
    } finally {
      app.unmount()
    }
  })
})

test('platform admin can create PAUSED beta service, assign operator, and surfaces queue precondition errors', async () => {
  await withDomGlobals(async () => {
    const calls = []
    let services = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      const okJson = payload => ({
        ok: true,
        status: 200,
        text: async () => JSON.stringify(payload)
      })
      if (url === '/api/beta/operator/services' && method === 'GET') return okJson(services)
      if (url === '/api/admin/platform/beta-services' && method === 'POST') {
        const created = { id: 'beta-created-1', organizationId: body.organizationId, logicalServiceId: body.logicalServiceId, mode: 'PAUSED' }
        services = [created]
        return okJson(created)
      }
      if (url === '/api/beta/operator/services/beta-created-1/members' && method === 'GET') return okJson([])
      if (url === '/api/beta/operator/services/beta-created-1/invitations' && method === 'GET') return okJson([])
      if (url === '/api/admin/platform/beta-services/beta-created-1/operators/user-op-1' && method === 'PUT') {
        return { ok: true, status: 204, text: async () => '' }
      }
      if (url === '/api/beta/operator/services/beta-created-1/mode' && method === 'PATCH') {
        return {
          ok: false,
          status: 409,
          statusText: 'Conflict',
          json: async () => ({ code: 'BETA_QUEUE_REQUIRED', message: 'Configure bounded local-only scheduling before enabling beta.' })
        }
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp(BetaOperatorPage, {
      auth: { accessToken: 'platform-admin-jwt' },
      platformAdmin: true
    })
    try {
      app.mount(root)
      await flush()

      const findButton = label => walk(root, el => el.tag === 'button' && textContent(el).includes(label))
      assert.match(textContent(root), /등록된 초대 서비스가 없습니다/)

      const inputs = walk(root, el => el.tag === 'input')
      inputs[0].value = 'org-uuid-1'
      inputs[0].dispatch('input')
      inputs[1].value = 'llm-uuid-1'
      inputs[1].dispatch('input')
      await nextTick()

      findButton('베타 생성')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.method === 'POST' && c.url === '/api/admin/platform/beta-services' && c.body.organizationId === 'org-uuid-1' && c.body.logicalServiceId === 'llm-uuid-1'))
      assert.match(textContent(root), /PAUSED · 신규 요청 중지/)

      const updatedInputs = walk(root, el => el.tag === 'input')
      updatedInputs[2].value = 'user-op-1'
      updatedInputs[2].dispatch('input')
      await nextTick()
      findButton('선택한 베타에 운영자 지정')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.method === 'PUT' && c.url === '/api/admin/platform/beta-services/beta-created-1/operators/user-op-1'))

      findButton('로컬 전용 시작')[0].props.onClick()
      await flush()
      assert.match(textContent(root), /Configure bounded local-only scheduling before enabling beta\./)
    } finally {
      app.unmount()
    }
  })
})

test('ConsoleShell exposes 초대 서비스 운영 tab exclusively to platformAdmin or assigned betaOperator', () => {
  const shell = readFileSync(new URL('../src/ConsoleShell.vue', import.meta.url), 'utf8')
  assert.match(shell, /platformAdmin\.value \|\| betaOperator\.value \? \[\{id:'beta' as const,label:'초대 서비스 운영'/)
  assert.match(shell, /function isAllowedPage\(target: PageKey\)\s*\{\s*return target === 'docs' \|\| navItems\.value\.some\(item => item\.id === target\)/)
  assert.match(shell, /<BetaOperatorPage v-else-if="page === 'beta' && \(platformAdmin \|\| betaOperator\)" :auth="auth" :platform-admin="platformAdmin" \/>/)
  assert.match(shell, /if \(auth\.value\.accessToken\)\s*\{\s*try\s*\{\s*const scoped\s*=\s*await adminFetch<unknown\[\]>\('\/api\/beta\/operator\/services',\s*auth\.value\);\s*betaOperator\.value\s*=\s*scoped\.length\s*>\s*0/)
})
