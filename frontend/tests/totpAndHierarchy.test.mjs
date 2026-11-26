import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, defineComponent, h, nextTick } from 'vue'
import ts from 'typescript'
import { typeScriptModuleUrl } from './loadTypeScript.mjs'

function compileVueSourceToDataUrl(relativePath, id, extraReplacements = {}) {
  const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  let script = ts.transpileModule(
    compileScript(descriptor, { id, inlineTemplate: true }).content,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }
  ).outputText
  script = script
    .replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
    .replace(/from ['"]\.\/api['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/api.ts')))
    .replace(/from ['"]\.\/clipboard['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/clipboard.ts')))
    .replace(/from ['"]\.\/dateTime['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/dateTime.ts')))
    .replace(/from ['"]\.\/qrCode['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/qrCode.ts')))
  for (const [pattern, replacementUrl] of Object.entries(extraReplacements)) {
    script = script.replace(new RegExp(`from ['"]${pattern}['"]`, 'g'), 'from ' + JSON.stringify(replacementUrl))
  }
  return 'data:text/javascript;base64,' + Buffer.from(script).toString('base64')
}

const loginAuthStepsModuleUrl = compileVueSourceToDataUrl('../src/LoginAuthSteps.vue', 'login-auth-steps')

function compileVueComponent(relativePath, id, extraReplacements = {}) {
  return import(compileVueSourceToDataUrl(relativePath, id, {
    '\\./LoginAuthSteps\\.vue': loginAuthStepsModuleUrl,
    ...extraReplacements
  }))
}

function stubModuleUrl(exportCode) {
  const vueUrl = JSON.stringify(import.meta.resolve('vue'))
  return 'data:text/javascript;base64,' + Buffer.from(`import { defineComponent, h } from ${vueUrl}\n${exportCode}`).toString('base64')
}

const baseModalStubUrl = stubModuleUrl(`
export default defineComponent({
  name: 'BaseModal',
  props: { open: Boolean, title: String, description: String, size: String },
  emits: ['close'],
  setup(props, { slots }) {
    return () => props.open
      ? h('div', { class: 'modal-stub', 'data-title': props.title }, [
          h('h2', props.title),
          slots.default ? slots.default() : null,
          slots.footer ? h('div', { class: 'modal-footer-stub' }, slots.footer()) : null
        ])
      : null
  }
})
`)

const connectionRequestsStubUrl = stubModuleUrl(`
export default defineComponent({
  name: 'ConnectionRequests',
  props: { auth: Object, organizationId: String, platformAdmin: Boolean },
  setup: () => () => h('div', { class: 'connection-requests-stub' })
})
`)

function createMemoryDom() {
  const doc = { activeElement: null }
  const node = tag => {
    const element = {
      tag,
      props: {},
      children: [],
      text: '',
      value: '',
      checked: false,
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
      dispatch(type, extra = {}) {
        const event = { target: element, preventDefault() {}, stopPropagation() {}, ...extra }
        for (const fn of element.listeners.get(type) ?? []) {
          fn(event)
        }
        const propName = 'on' + type[0].toUpperCase() + type.slice(1)
        if (typeof element.props[propName] === 'function') {
          element.props[propName](event)
        }
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
      if (key === 'checked') element.checked = Boolean(value)
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
    nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null,
    insertStaticContent(content, parent, anchor = null) {
      const staticEl = { ...node('#static'), text: content.replace(/<[^>]+>/g, ' ') }
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, staticEl)
      staticEl.parent = parent
      return [staticEl, staticEl]
    }
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

function createMemoryStorage(initial = {}) {
  const store = new Map(Object.entries(initial))
  return {
    getItem: key => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => { store.set(key, String(value)) },
    removeItem: key => { store.delete(key) },
    clear: () => { store.clear() }
  }
}

async function withDomGlobals(run, initialSession = {}, initialHash = '') {
  const previousFetch = globalThis.fetch
  const previousWindow = globalThis.window
  const previousSessionStorage = globalThis.sessionStorage
  const previousLocalStorage = globalThis.localStorage
  const previousDocument = globalThis.document
  const previousDocumentClass = globalThis.Document
  const previousShadowRoot = globalThis.ShadowRoot
  const previousCustomEvent = globalThis.CustomEvent

  const listeners = new Map()
  globalThis.Document = class Document {}
  globalThis.ShadowRoot = class ShadowRoot {}
  globalThis.CustomEvent = class CustomEvent {
    constructor(type, init = {}) {
      this.type = type
      this.detail = init.detail
    }
  }
  globalThis.sessionStorage = createMemoryStorage(initialSession)
  globalThis.localStorage = createMemoryStorage()
  globalThis.document = {
    documentElement: { dataset: {}, style: {} }
  }
  globalThis.window = {
    confirm: () => true,
    location: { origin: 'https://console.example.com', pathname: '/', hash: initialHash },
    history: {
      replaceState(_state, _title, url) {
        globalThis.window.location.hash = url.includes('#') ? url.slice(url.indexOf('#')) : ''
      }
    },
    matchMedia: () => ({ matches: false }),
    addEventListener(type, fn) {
      const list = listeners.get(type) ?? []
      list.push(fn)
      listeners.set(type, list)
    },
    removeEventListener(type, fn) {
      const list = listeners.get(type) ?? []
      listeners.set(type, list.filter(item => item !== fn))
    },
    dispatchEvent(event) {
      for (const fn of listeners.get(event.type) ?? []) fn(event)
      return true
    }
  }

  try {
    await run()
  } finally {
    globalThis.fetch = previousFetch
    globalThis.window = previousWindow
    globalThis.sessionStorage = previousSessionStorage
    globalThis.localStorage = previousLocalStorage
    globalThis.document = previousDocument
    globalThis.Document = previousDocumentClass
    globalThis.ShadowRoot = previousShadowRoot
    globalThis.CustomEvent = previousCustomEvent
  }
}

async function flush() {
  for (let i = 0; i < 10; i++) {
    await new Promise(resolve => setTimeout(resolve, 0))
    await nextTick()
  }
}

const okJson = payload => ({
  ok: true,
  status: 200,
  text: async () => JSON.stringify(payload),
  json: async () => payload
})

test('qrCode generates valid local QR matrices and crisp SVGs without external calls', async () => {
  const qrSource = readFileSync(new URL('../src/qrCode.ts', import.meta.url), 'utf8')
  assert.doesNotMatch(qrSource.replace(/http:\/\/www\.w3\.org\/2000\/svg/g, ''), /https?:\/\//i)
  assert.doesNotMatch(qrSource, /\bfetch\s*\(/i)

  const { encodeQrMatrix, buildQrSvgModel, renderQrSvg } = await import(typeScriptModuleUrl('../src/qrCode.ts'))
  const uri = 'otpauth://totp/AICONNECT:director%40company.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=AICONNECT&algorithm=SHA1&digits=6&period=30'
  const matrix = encodeQrMatrix(uri, 'M')
  assert.ok(matrix.version >= 5 && matrix.version <= 10)
  assert.equal(matrix.size, 4 * matrix.version + 17)

  // Verify 3 finder patterns (top-left, top-right, bottom-left) outer ring & inner 3x3 dark block
  for (const [baseR, baseC] of [[0, 0], [0, matrix.size - 7], [matrix.size - 7, 0]]) {
    for (let dr = 0; dr < 7; dr++) {
      for (let dc = 0; dc < 7; dc++) {
        const expectedDark = dr === 0 || dr === 6 || dc === 0 || dc === 6 || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4)
        assert.equal(matrix.modules[baseR + dr][baseC + dc], expectedDark, `finder(${baseR + dr},${baseC + dc})`)
      }
    }
  }

  // Verify timing patterns and fixed dark module
  for (let i = 8; i < matrix.size - 8; i++) {
    assert.equal(matrix.modules[6][i], i % 2 === 0)
    assert.equal(matrix.modules[i][6], i % 2 === 0)
  }
  assert.equal(matrix.modules[4 * matrix.version + 9][8], true)

  const svg = renderQrSvg(uri)
  assert.match(svg, /^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg" viewBox="0 0 \d+ \d+"/)
  assert.match(svg, /fill="#ffffff"/)
  assert.match(svg, /<path d="M/)
  assert.doesNotMatch(svg.replace(/http:\/\/www\.w3\.org\/2000\/svg/g, ''), /https?:\/\//i)

  // Ensure larger otpauth URI still fits cleanly
  const longUri = uri + '&label=' + 'a'.repeat(180)
  const model = buildQrSvgModel(longUri)
  assert.ok(model.totalSize > model.size)
  assert.ok(model.pathD.length > 20)
})

test('TotpOnboardingView enforces local SVG QR setup, 6-digit enroll, and 8 recovery codes confirmation before console entry', async () => {
  const onboardingSource = readFileSync(new URL('../src/TotpOnboardingView.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(onboardingSource, /https?:\/\//i)

  const { default: TotpOnboardingView } = await compileVueComponent('../src/TotpOnboardingView.vue', 'totp-onboarding-test')

  await withDomGlobals(async () => {
    const calls = []
    const completedEvents = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body, auth: init.headers?.Authorization })
      if (url === '/api/auth/totp/setup' && method === 'POST') {
        return okJson({
          secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
          otpauthUri: 'otpauth://totp/AICONNECT:director%40company.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=AICONNECT',
          issuer: 'AICONNECT',
          email: 'director@company.com'
        })
      }
      if (url === '/api/auth/totp/enroll' && method === 'POST') {
        return okJson({
          session: {
            accessToken: 'jwt-verified-token',
            user: {
              id: 'user-1',
              email: 'director@company.com',
              platformAdmin: false,
              accountTier: 'PROJECT_DIRECTOR',
              totpEnabled: true
            },
            mfaState: 'VERIFIED'
          },
          recoveryCodes: [
            'AAAA-1111-BBBB', 'CCCC-2222-DDDD', 'EEEE-3333-FFFF', 'GGGG-4444-HHHH',
            'JJJJ-5555-KKKK', 'LLLL-6666-MMMM', 'NNNN-7777-PPPP', 'QQQQ-8888-RRRR'
          ]
        })
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(TotpOnboardingView, {
        accessToken: 'jwt-onboarding-token',
        user: {
          id: 'user-1',
          email: 'director@company.com',
          platformAdmin: false,
          accountTier: 'PROJECT_DIRECTOR',
          totpEnabled: false
        },
        theme: 'dark',
        onCompleted: s => completedEvents.push(s)
      })
    })
    try {
      app.mount(root)
      await flush()

      assert.ok(calls.some(c => c.url === '/api/auth/totp/setup' && c.auth === 'Bearer jwt-onboarding-token'))
      const svgs = walk(root, el => el.tag === 'svg')
      assert.equal(svgs.length, 1)
      assert.ok(walk(svgs[0], el => el.tag === 'path').length === 1)
      assert.match(textContent(root), /1\. 로그인 진행/)
      assert.match(textContent(root), /아이디·비밀번호 확인 완료/)
      assert.match(textContent(root), /2\. QR 2차 확인 · 최종 승인/)
      assert.match(textContent(root), /STEP 2-1 · QR 스캔 2차 확인/)
      assert.match(textContent(root), /JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP/)
      assert.match(textContent(root), /프로젝트 총괄/)

      const codeInput = walk(root, el => el.tag === 'input')[0]
      codeInput.value = ' 123-456 '
      codeInput.dispatch('input')
      await nextTick()

      const form = walk(root, el => el.tag === 'form')[0]
      form.dispatch('submit')
      await flush()

      assert.ok(calls.some(c => c.url === '/api/auth/totp/enroll' && c.body.code === '123456'))
      // Must NOT complete yet before confirming recovery codes!
      assert.equal(completedEvents.length, 0)
      assert.match(textContent(root), /STEP 2-2 · 복구 코드 보관 및 최종 승인/)
      assert.match(textContent(root), /AAAA-1111-BBBB/)
      assert.match(textContent(root), /QQQQ-8888-RRRR/)

      const proceedBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('최종 승인 · 콘솔 진입'))[0]
      assert.equal(proceedBtn.props.disabled, true)

      const checkbox = walk(root, el => el.tag === 'input' && el.props.type === 'checkbox')[0]
      checkbox.checked = true
      checkbox.dispatch('change')
      await nextTick()

      const enabledBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('최종 승인 · 콘솔 진입'))[0]
      assert.equal(enabledBtn.props.disabled, false)
      enabledBtn.props.onClick()
      await flush()

      assert.equal(completedEvents.length, 1)
      assert.equal(completedEvents[0].mfaState, 'VERIFIED')
      assert.equal(globalThis.sessionStorage.getItem('aiconnect.mfaState'), 'VERIFIED')
    } finally {
      app.unmount()
    }
  })
})

test('LoginView switches to 2FA verification on VERIFICATION_REQUIRED and verifies TOTP or recovery code', async () => {
  const { default: LoginView } = await compileVueComponent(
    '../src/LoginView.vue',
    'login-view-test',
    { '\\./BaseModal\\.vue': baseModalStubUrl }
  )

  await withDomGlobals(async () => {
    const calls = []
    const authEvents = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body, auth: init.headers?.Authorization })
      if (url === '/api/auth/login' && method === 'POST') {
        return okJson({
          accessToken: 'jwt-pre-mfa',
          user: {
            id: 'u-1',
            email: 'admin@company.com',
            platformAdmin: true,
            accountTier: 'SYSTEM_ADMIN',
            totpEnabled: true
          },
          mfaState: 'VERIFICATION_REQUIRED'
        })
      }
      if (url === '/api/auth/totp/verify' && method === 'POST') {
        return okJson({
          accessToken: 'jwt-mfa-verified',
          user: {
            id: 'u-1',
            email: 'admin@company.com',
            platformAdmin: true,
            accountTier: 'SYSTEM_ADMIN',
            totpEnabled: true
          },
          mfaState: 'VERIFIED'
        })
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(LoginView, {
        theme: 'dark',
        onAuthenticated: s => authEvents.push(s)
      })
    })
    try {
      app.mount(root)
      await flush()

      const inputs = walk(root, el => el.tag === 'input')
      assert.match(textContent(root), /STEP 1 · 아이디 · 비밀번호 로그인/)
      assert.match(textContent(root), /1차 로그인 진행/)
      inputs[0].value = 'admin@company.com'
      inputs[0].dispatch('input')
      inputs[1].value = 'SuperSecretPassword123!'
      inputs[1].dispatch('input')
      await nextTick()

      walk(root, el => el.tag === 'form')[0].dispatch('submit')
      await flush()

      assert.equal(authEvents.length, 1)
      assert.equal(authEvents[0].mfaState, 'VERIFICATION_REQUIRED')
      assert.match(textContent(root), /아이디·비밀번호 확인 완료/)
      assert.match(textContent(root), /2\. QR 2차 확인 · 최종 승인/)
      assert.match(textContent(root), /2차 확인 및 최종 승인/)
      assert.match(textContent(root), /시스템 관리자/)

      // Toggle recovery mode and back
      const toggleBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('1회용 복구 코드로 승인하기'))[0]
      toggleBtn.props.onClick()
      await nextTick()
      assert.match(textContent(root), /1회용 복구 코드/)
      walk(root, el => el.tag === 'button' && textContent(el).includes('6자리 앱 코드로 승인하기'))[0].props.onClick()
      await nextTick()

      const mfaInput = walk(root, el => el.tag === 'input')[0]
      mfaInput.value = ' 654-321 '
      mfaInput.dispatch('input')
      await nextTick()

      walk(root, el => el.tag === 'form')[0].dispatch('submit')
      await flush()

      assert.ok(calls.some(c => c.url === '/api/auth/totp/verify' && c.auth === 'Bearer jwt-pre-mfa' && c.body.code === '654321'))
      assert.equal(authEvents.length, 2)
      assert.equal(authEvents[1].mfaState, 'VERIFIED')
    } finally {
      app.unmount()
    }
  })
})

test('InviteRegistrationView inspects invite code, displays hierarchy metadata, and registers into ENROLLMENT_REQUIRED', async () => {
  const { default: InviteRegistrationView } = await compileVueComponent('../src/InviteRegistrationView.vue', 'invite-reg-test')

  await withDomGlobals(async () => {
    const calls = []
    const registeredEvents = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/auth/invitations/inspect' && method === 'POST') {
        return okJson({
          valid: true,
          targetTier: 'GENERAL_USER',
          organizationId: 'org-1',
          organizationName: 'CalenLedger',
          teamId: 'team-1',
          teamName: 'Core Team',
          projectId: 'proj-1',
          projectName: 'Ledger AI',
          expectedEmail: 'dev1@calen.example',
          expiresAt: '2026-11-25T12:00:00Z'
        })
      }
      if (url === '/api/auth/invitations/accept' && method === 'POST') {
        return okJson({
          accessToken: 'jwt-new-invite-user',
          user: {
            id: 'user-dev-1',
            email: 'dev1@calen.example',
            platformAdmin: false,
            accountTier: 'GENERAL_USER',
            totpEnabled: false
          },
          mfaState: 'ENROLLMENT_REQUIRED'
        })
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(InviteRegistrationView, {
        initialCode: 'INVITE-XYZ-001',
        theme: 'dark',
        onRegistered: s => registeredEvents.push(s)
      })
    })
    try {
      app.mount(root)
      await flush()

      assert.ok(calls.some(c => c.url === '/api/auth/invitations/inspect' && c.body.code === 'INVITE-XYZ-001'))
      assert.match(textContent(root), /기본 계정/)
      assert.match(textContent(root), /CalenLedger/)
      assert.match(textContent(root), /Core Team/)
      assert.match(textContent(root), /Ledger AI/)
      assert.match(textContent(root), /dev1@calen\.example/)

      const inputs = walk(root, el => el.tag === 'input')
      // inputs: [code, email, password, passwordConfirm]
      assert.equal(inputs.length, 4)
      inputs[2].value = 'ValidPass12345!'
      inputs[2].dispatch('input')
      inputs[3].value = 'ValidPass12345!'
      inputs[3].dispatch('input')
      await nextTick()

      const forms = walk(root, el => el.tag === 'form')
      forms[1].dispatch('submit')
      await flush()

      assert.ok(calls.some(c => c.url === '/api/auth/invitations/accept' && c.body.email === 'dev1@calen.example'))
      assert.equal(registeredEvents.length, 1)
      assert.equal(registeredEvents[0].mfaState, 'ENROLLMENT_REQUIRED')
      assert.equal(registeredEvents[0].user.totpEnabled, false)
    } finally {
      app.unmount()
    }
  })
})

test('Root routes fail-closed: blocks ConsoleShell until TOTP verified and mounts InviteRegistrationView on #register?code=...', async () => {
  const stubs = {
    '\\./ConsoleShell\\.vue': stubModuleUrl('export default defineComponent({ name: "ConsoleShell", props: ["user", "platformTokenSession", "theme", "fontScale"], setup: p => () => h("div", { "data-view": "console" }, p.platformTokenSession ? "CONSOLE_PLATFORM_TOKEN" : "CONSOLE_USER") })'),
    '\\./TotpOnboardingView\\.vue': stubModuleUrl('export default defineComponent({ name: "TotpOnboardingView", props: ["accessToken", "user", "theme"], setup: () => () => h("div", { "data-view": "totp-onboarding" }, "TOTP_ONBOARDING_VIEW") })'),
    '\\./InviteRegistrationView\\.vue': stubModuleUrl('export default defineComponent({ name: "InviteRegistrationView", props: ["initialCode", "theme"], setup: p => () => h("div", { "data-view": "invite-register" }, "INVITE_REG:" + (p.initialCode || "")) })'),
    '\\./LoginView\\.vue': stubModuleUrl('export default defineComponent({ name: "LoginView", props: ["theme", "mfaRequired", "pendingAccessToken", "pendingUser", "sessionNotice"], setup: p => () => h("div", { "data-view": "login" }, p.mfaRequired ? "LOGIN_MFA_STEP" : (p.sessionNotice ? "LOGIN_STEP:" + p.sessionNotice : "LOGIN_STEP")) })')
  }
  const { default: Root } = await compileVueComponent('../src/Root.vue', 'root-routing-test', stubs)

  const errJson = (status, payload) => ({
    ok: false,
    status,
    text: async () => JSON.stringify(payload),
    json: async () => payload
  })

  // 1. #register?code=ABC123 -> InviteRegistrationView
  await withDomGlobals(async () => {
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'INVITE_REG:ABC123')
    app.unmount()
  }, {}, '#register?code=ABC123')

  // 2. ENROLLMENT_REQUIRED -> TotpOnboardingView (never ConsoleShell)
  await withDomGlobals(async () => {
    globalThis.fetch = async url => {
      if (url === '/api/portal/session') return errJson(403, { code: 'TOTP_ENROLLMENT_REQUIRED', message: 'Enrollment required' })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'TOTP_ONBOARDING_VIEW')
    app.unmount()
  }, {
    'aiconnect.accessToken': 'jwt-1',
    'aiconnect.mfaState': 'ENROLLMENT_REQUIRED',
    'aiconnect.user': JSON.stringify({ id: 'u1', email: 'u@x.com', platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: false })
  })

  // 3. VERIFICATION_REQUIRED -> LoginView MFA step (never ConsoleShell)
  await withDomGlobals(async () => {
    globalThis.fetch = async url => {
      if (url === '/api/portal/session') return errJson(403, { code: 'TOTP_VERIFICATION_REQUIRED', message: 'Verification required' })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'LOGIN_MFA_STEP')
    app.unmount()
  }, {
    'aiconnect.accessToken': 'jwt-2',
    'aiconnect.mfaState': 'VERIFICATION_REQUIRED',
    'aiconnect.user': JSON.stringify({ id: 'u1', email: 'u@x.com', platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true })
  })

  // 4. VERIFIED + totpEnabled: true -> ConsoleShell
  await withDomGlobals(async () => {
    globalThis.fetch = async url => {
      if (url === '/api/portal/session') return okJson({ platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true, mfaState: 'VERIFIED', memberships: [] })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'CONSOLE_USER')
    app.unmount()
  }, {
    'aiconnect.accessToken': 'jwt-3',
    'aiconnect.mfaState': 'VERIFIED',
    'aiconnect.user': JSON.stringify({ id: 'u1', email: 'u@x.com', platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true })
  })

  // 5. Emergency platformToken -> ConsoleShell preserved
  await withDomGlobals(async () => {
    globalThis.fetch = async url => {
      if (url === '/api/admin/organizations') return okJson([])
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'CONSOLE_PLATFORM_TOKEN')
    app.unmount()
  }, {
    'aiconnect.platformToken': 'break-glass-admin-token'
  })
})

test('Root and api cleanly exit to LoginView on time expiration, server shutdown, and redeployment invalidation', async () => {
  const mountCounts = { console: 0 }
  const stubs = {
    '\\./ConsoleShell\\.vue': stubModuleUrl('export default defineComponent({ name: "ConsoleShell", props: ["user", "platformTokenSession", "theme", "fontScale"], setup: p => () => h("div", { "data-view": "console" }, p.platformTokenSession ? "CONSOLE_PLATFORM_TOKEN" : "CONSOLE_USER") })'),
    '\\./TotpOnboardingView\\.vue': stubModuleUrl('export default defineComponent({ name: "TotpOnboardingView", props: ["accessToken", "user", "theme"], setup: () => () => h("div", { "data-view": "totp-onboarding" }, "TOTP_ONBOARDING_VIEW") })'),
    '\\./InviteRegistrationView\\.vue': stubModuleUrl('export default defineComponent({ name: "InviteRegistrationView", props: ["initialCode", "theme"], setup: p => () => h("div", { "data-view": "invite-register" }, "INVITE_REG:" + (p.initialCode || "")) })'),
    '\\./LoginView\\.vue': stubModuleUrl('export default defineComponent({ name: "LoginView", props: ["theme", "mfaRequired", "pendingAccessToken", "pendingUser", "sessionNotice"], setup: p => () => h("div", { "data-view": "login" }, p.mfaRequired ? "LOGIN_MFA_STEP" : (p.sessionNotice ? "LOGIN_STEP:" + p.sessionNotice : "LOGIN_STEP")) })')
  }
  const { default: Root } = await compileVueComponent('../src/Root.vue', 'root-expiration-test', stubs)
  const { default: LoginView } = await compileVueComponent('../src/LoginView.vue', 'login-expiration-test', { '\\./BaseModal\\.vue': baseModalStubUrl })
  const api = await import(typeScriptModuleUrl('../src/api.ts'))

  const makeJwt = offsetSeconds => {
    const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url')
    const payload = Buffer.from(JSON.stringify({ sub: 'u1', exp: Math.floor(Date.now() / 1000) + offsetSeconds })).toString('base64url')
    return `${header}.${payload}.sig`
  }
  const errJson = (status, payload) => ({
    ok: false,
    status,
    text: async () => JSON.stringify(payload),
    json: async () => payload
  })
  const sampleUser = { id: 'u1', email: 'u@x.com', platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true }

  // A. Expired JWT + failed refresh on page load -> cleanly exits to LoginView, clears storage & hash
  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async url => {
      calls.push(url)
      if (url === '/api/auth/refresh') return errJson(401, { code: 'REFRESH_TOKEN_REQUIRED', message: 'Expired' })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.match(textContent(root), /^LOGIN_STEP:.*만료/)
    assert.equal(globalThis.sessionStorage.getItem('aiconnect.accessToken'), null)
    assert.equal(globalThis.sessionStorage.getItem('aiconnect.setup.organizationId'), null)
    assert.equal(globalThis.window.location.hash, '')
    assert.deepEqual(calls, ['/api/auth/refresh'])
    app.unmount()
  }, {
    'aiconnect.accessToken': makeJwt(-30),
    'aiconnect.mfaState': 'VERIFIED',
    'aiconnect.user': JSON.stringify(sampleUser),
    'aiconnect.setup.organizationId': 'org-1'
  }, '#projects')

  // B. Locally expired JWT recovers silently when refresh token is still valid
  await withDomGlobals(async () => {
    const freshJwt = makeJwt(900)
    globalThis.fetch = async url => {
      if (url === '/api/auth/refresh') return okJson({ accessToken: freshJwt, user: sampleUser, mfaState: 'VERIFIED' })
      if (url === '/api/portal/session') return okJson({ platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true, mfaState: 'VERIFIED', memberships: [] })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'CONSOLE_USER')
    assert.equal(globalThis.sessionStorage.getItem('aiconnect.accessToken'), freshJwt)
    app.unmount()
  }, {
    'aiconnect.accessToken': makeJwt(-15),
    'aiconnect.mfaState': 'VERIFIED',
    'aiconnect.user': JSON.stringify(sampleUser)
  }, '#projects')

  // C. Server unreachable (network TypeError or 503) on reload -> session is kept with a connection warning
  for (const failureMode of ['network', '503']) {
    await withDomGlobals(async () => {
      globalThis.fetch = async url => {
        if (url === '/api/portal/session') {
          if (failureMode === 'network') throw new TypeError('Failed to fetch')
          return { ok: false, status: 503, text: async () => 'Service Unavailable', json: async () => { throw new SyntaxError('html') } }
        }
        throw new Error(`Unexpected fetch: ${url}`)
      }
      const { renderer, root, textContent } = createMemoryDom()
      const app = renderer.createApp(Root)
      app.mount(root)
      await flush()
      assert.match(textContent(root), /^CONSOLE_USER.*서버 연결이 불안정/)
      assert.notEqual(globalThis.sessionStorage.getItem('aiconnect.accessToken'), null)
      app.unmount()
    }, {
      'aiconnect.accessToken': makeJwt(600),
      'aiconnect.mfaState': 'VERIFIED',
      'aiconnect.user': JSON.stringify(sampleUser)
    }, '#dashboard')
  }

  // D. Stale / redeployed platformToken rejected with 401 -> cleanly exits to LoginView
  await withDomGlobals(async () => {
    globalThis.fetch = async url => {
      if (url === '/api/admin/organizations') return errJson(401, { code: 'ADMIN_AUTH_REQUIRED', message: 'Invalid token' })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.match(textContent(root), /^LOGIN_STEP:.*재배포 또는 인증 정보 변경/)
    assert.equal(globalThis.sessionStorage.getItem('aiconnect.platformToken'), null)
    assert.equal(globalThis.window.location.hash, '')
    app.unmount()
  }, {
    'aiconnect.platformToken': 'stale-break-glass-token'
  }, '#platform-admin')

  // E. Active ConsoleShell exits when adminFetch fails after refresh (redeploy/expiry); a 502 keeps the session
  for (const mode of ['redeploy-401', 'gateway-502']) {
    const initialJwt = makeJwt(600)
    await withDomGlobals(async () => {
      let failRequests = false
      globalThis.fetch = async url => {
        if (url === '/api/portal/session' && !failRequests) {
          return okJson({ platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true, mfaState: 'VERIFIED', memberships: [] })
        }
        if (mode === 'redeploy-401') {
          if (url === '/api/admin/organizations' || url === '/api/auth/refresh') {
            return errJson(401, { code: 'INVALID_ACCESS_TOKEN', message: 'Invalid' })
          }
        }
        if (mode === 'gateway-502') {
          return { ok: false, status: 502, text: async () => 'Bad Gateway', json: async () => { throw new SyntaxError('html') } }
        }
        throw new Error(`Unexpected fetch: ${url}`)
      }
      const { renderer, root, textContent } = createMemoryDom()
      const app = renderer.createApp(Root)
      app.mount(root)
      await flush()
      assert.equal(textContent(root), 'CONSOLE_USER')

      failRequests = true
      await assert.rejects(() => api.adminFetch('/api/admin/organizations', { accessToken: initialJwt }))
      await flush()
      if (mode === 'gateway-502') {
        assert.match(textContent(root), /^CONSOLE_USER.*서버 연결이 불안정/)
        assert.equal(globalThis.sessionStorage.getItem('aiconnect.accessToken'), initialJwt)
      } else {
        assert.match(textContent(root), /^LOGIN_STEP:/)
        assert.equal(globalThis.sessionStorage.getItem('aiconnect.accessToken'), null)
        assert.equal(globalThis.window.location.hash, '')
      }
      app.unmount()
    }, {
      'aiconnect.accessToken': initialJwt,
      'aiconnect.mfaState': 'VERIFIED',
      'aiconnect.user': JSON.stringify(sampleUser)
    }, '#projects')
  }

  // Wait: ensure live active session seeded with real non-expired JWT also works cleanly on focus event when server goes down or revokes platformToken
  await withDomGlobals(async () => {
    let alive = true
    globalThis.fetch = async url => {
      if (url === '/api/admin/organizations') {
        if (alive) return okJson([])
        return errJson(401, { code: 'ADMIN_AUTH_REQUIRED', message: 'Rotated' })
      }
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp(Root)
    app.mount(root)
    await flush()
    assert.equal(textContent(root), 'CONSOLE_PLATFORM_TOKEN')

    alive = false
    globalThis.window.dispatchEvent(new globalThis.CustomEvent('focus'))
    await flush()
    assert.match(textContent(root), /^LOGIN_STEP:.*재배포 또는 인증 정보 변경/)
    assert.equal(globalThis.sessionStorage.getItem('aiconnect.platformToken'), null)
    assert.equal(globalThis.window.location.hash, '')
    app.unmount()
  }, {
    'aiconnect.platformToken': 'active-platform-token'
  }, '#infrastructure')

  // F. Silent token rotation preserves mount without unmounting and resolves stale auth token on subsequent adminFetch
  {
    const jwt1 = makeJwt(600)
    const jwt2 = makeJwt(900)
    await withDomGlobals(async () => {
      const sentAuthHeaders = []
      const trackedConsoleStub = stubModuleUrl('export default defineComponent({ name: "ConsoleShell", props: ["user", "platformTokenSession", "theme", "fontScale"], setup() { globalThis.__consoleMounts = (globalThis.__consoleMounts || 0) + 1; return () => h("div", "CONSOLE_STABLE") } })')
      const { default: TrackedRoot } = await compileVueComponent('../src/Root.vue', 'root-stable-mount-test', {
        ...stubs,
        '\\./ConsoleShell\\.vue': trackedConsoleStub
      })
      globalThis.__consoleMounts = 0
      globalThis.fetch = async (url, init = {}) => {
        if (url === '/api/portal/session') return okJson({ platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true, mfaState: 'VERIFIED', memberships: [] })
        if (url === '/api/auth/refresh') return okJson({ accessToken: jwt2, user: sampleUser, mfaState: 'VERIFIED' })
        if (url === '/api/admin/organizations') {
          sentAuthHeaders.push(init.headers?.Authorization)
          return okJson([])
        }
        throw new Error(`Unexpected fetch: ${url}`)
      }
      const { renderer, root, textContent } = createMemoryDom()
      const app = renderer.createApp(TrackedRoot)
      app.mount(root)
      await flush()
      assert.equal(textContent(root), 'CONSOLE_STABLE')
      assert.equal(globalThis.__consoleMounts, 1)

      await api.refreshAuthentication()
      await flush()
      assert.equal(globalThis.__consoleMounts, 1)
      assert.equal(globalThis.sessionStorage.getItem('aiconnect.accessToken'), jwt2)

      await api.adminFetch('/api/admin/organizations', { accessToken: jwt1 })
      assert.deepEqual(sentAuthHeaders, [`Bearer ${jwt2}`])
      app.unmount()
      delete globalThis.__consoleMounts
    }, {
      'aiconnect.accessToken': jwt1,
      'aiconnect.mfaState': 'VERIFIED',
      'aiconnect.user': JSON.stringify(sampleUser)
    })
  }

  // G. Pre-MFA LoginView Step 2: wrong OTP code stays on Step 2; expired session returns to Step 1
  await withDomGlobals(async () => {
    let phase = 'wrong-otp'
    const expiredEvents = []
    globalThis.window.addEventListener('aiconnect:session-expired', e => expiredEvents.push(e.detail?.reason))
    globalThis.fetch = async url => {
      if (url === '/api/auth/login') {
        return okJson({ accessToken: 'pre-mfa-jwt', user: sampleUser, mfaState: 'VERIFICATION_REQUIRED' })
      }
      if (url === '/api/auth/totp/verify') {
        if (phase === 'wrong-otp') return errJson(401, { code: 'INVALID_TOTP_CODE', message: '6자리 코드가 틀렸습니다.' })
        return errJson(401, { code: 'INVALID_ACCESS_TOKEN', message: 'Pre-MFA session expired' })
      }
      if (url === '/api/auth/refresh') {
        return errJson(401, { code: 'REFRESH_TOKEN_REQUIRED', message: 'Expired' })
      }
      throw new Error(`Unexpected fetch: ${url}`)
    }
    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(LoginView, { theme: 'dark' }) })
    app.mount(root)
    await flush()

    const loginInputs = walk(root, el => el.tag === 'input')
    loginInputs[0].value = 'u@x.com'
    loginInputs[0].dispatch('input')
    loginInputs[1].value = 'ValidPassword123!'
    loginInputs[1].dispatch('input')
    await nextTick()
    walk(root, el => el.tag === 'form')[0].dispatch('submit')
    await flush()
    assert.match(textContent(root), /STEP 2 · QR 2차 확인 및 최종 승인/)

    // Wrong OTP -> remains on Step 2, no session-expired event
    const otpInput1 = walk(root, el => el.tag === 'input')[0]
    otpInput1.value = '000000'
    otpInput1.dispatch('input')
    await nextTick()
    walk(root, el => el.tag === 'form')[0].dispatch('submit')
    await flush()
    assert.equal(expiredEvents.length, 0)
    assert.match(textContent(root), /STEP 2 · QR 2차 확인 및 최종 승인/)
    assert.match(textContent(root), /6자리 코드가 틀렸습니다/)

    // Expired pre-MFA session -> dispatches session-expired and returns to Step 1
    phase = 'expired-session'
    const otpInput2 = walk(root, el => el.tag === 'input')[0]
    otpInput2.value = '123456'
    otpInput2.dispatch('input')
    await nextTick()
    walk(root, el => el.tag === 'form')[0].dispatch('submit')
    await flush()
    assert.deepEqual(expiredEvents, ['expired'])
    assert.match(textContent(root), /STEP 1 · 아이디 · 비밀번호 로그인/)
    app.unmount()
  })

  // H. TotpOnboardingView pre-MFA setupTotp / enrollTotp 401 or 503 exits cleanly to LoginView
  await withDomGlobals(async () => {
    const expiredEvents = []
    globalThis.window.addEventListener('aiconnect:session-expired', e => expiredEvents.push(e.detail?.reason))
    globalThis.fetch = async url => {
      if (url === '/api/auth/totp/setup') return errJson(401, { code: 'INVALID_ACCESS_TOKEN', message: 'Expired' })
      if (url === '/api/auth/refresh') return errJson(401, { code: 'REFRESH_TOKEN_REQUIRED', message: 'Expired' })
      throw new Error(`Unexpected fetch: ${url}`)
    }
    await assert.rejects(() => api.setupTotp('expired-onboarding-jwt'))
    assert.deepEqual(expiredEvents, ['expired'])

    const unstableEvents = []
    globalThis.window.addEventListener('aiconnect:connection-unstable', e => unstableEvents.push(e.detail?.reason))
    globalThis.fetch = async () => ({ ok: false, status: 503, text: async () => 'Down', json: async () => { throw new SyntaxError('html') } })
    await assert.rejects(() => api.enrollTotp('onboarding-jwt', '123456'))
    assert.deepEqual(expiredEvents, ['expired'])
    assert.deepEqual(unstableEvents, ['server_unavailable'])
  })
})

test('PlatformAdminPage supports PROJECT_DIRECTOR invite issuance, revocation, tier/TOTP display, manager revocation, and totp-reset', async () => {
  const { default: PlatformAdminPage } = await compileVueComponent(
    '../src/PlatformAdminPage.vue',
    'platform-admin-test',
    {
      '\\./BaseModal\\.vue': baseModalStubUrl,
      '\\./ConnectionRequests\\.vue': connectionRequestsStubUrl
    }
  )

  await withDomGlobals(async () => {
    const calls = []
    let users = [
      { id: 'u-sys', email: 'sys@company.com', platformAdmin: true, accountTier: 'SYSTEM_ADMIN', totpEnabled: true, enabled: true, organizationCount: 1, issuedApiKeyCount: 0 },
      { id: 'u-dir', email: 'dir@company.com', platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true, enabled: true, organizationCount: 1, issuedApiKeyCount: 2 }
    ]
    let invitations = [
      { id: 'pinv-1', codePrefix: 'PD12', targetTier: 'PROJECT_DIRECTOR', organizationId: 'org-1', organizationName: 'CalenLedger', expectedEmail: 'newdir@company.com', status: 'PENDING', expiresAt: '2026-11-26T00:00:00Z' }
    ]
    let managers = [
      { userId: 'u-dir', email: 'dir@company.com', accountTier: 'PROJECT_DIRECTOR', role: 'MANAGER', canIssueApiKeys: true, enabled: true, activeApiKeyCount: 2, createdAt: '2026-11-19T00:00:00Z' }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/platform/overview') return okJson({ organizationCount: 1, userCount: 2, projectCount: 1, providerCount: 1, nodeCount: 1, endpointCount: 1, deploymentCount: 1, serviceCount: 1, apiKeyCount: 2, requestCount: 10 })
      if (url === '/api/admin/platform/organizations') return okJson([{ id: 'org-1', name: 'CalenLedger', status: 'ACTIVE', projectCount: 1, providerCount: 1, nodeCount: 1, memberCount: 2 }])
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'proj-1', name: 'Calen Project' }])
      if (url === '/api/admin/platform/projects/proj-1/managers' && method === 'GET') return okJson(managers)
      if (url === '/api/admin/platform/projects/proj-1/managers/u-dir' && method === 'DELETE') {
        managers = managers.filter(m => m.userId !== 'u-dir')
        return { ok: true, status: 204, text: async () => '' }
      }
      if (url === '/api/admin/platform/users') return okJson(users)
      if (url === '/api/admin/platform/teams') return okJson([{ id: 'team-1', organizationId: 'org-1', organizationName: 'CalenLedger', name: 'Platform', status: 'ACTIVE', projectCount: 1 }])
      if (url === '/api/admin/platform/api-keys') return okJson([])
      if (url === '/api/admin/platform/account-invitations' && method === 'GET') return okJson(invitations)
      if (url === '/api/admin/platform/account-invitations' && method === 'POST') {
        invitations = [...invitations, { id: 'pinv-2', codePrefix: 'PD99', targetTier: body.targetTier, organizationId: body.organizationId, organizationName: 'CalenLedger', expectedEmail: body.expectedEmail, status: 'PENDING', expiresAt: '2026-11-28T00:00:00Z' }]
        return okJson({ id: 'pinv-2', code: 'DIRECTOR-ONE-TIME-CODE', registrationPath: '/#register?code=DIRECTOR-ONE-TIME-CODE', targetTier: body.targetTier, organizationId: body.organizationId, expiresAt: '2026-11-28T00:00:00Z' })
      }
      if (url === '/api/admin/platform/account-invitations/pinv-1/revoke' && method === 'POST') {
        invitations = invitations.map(i => i.id === 'pinv-1' ? { ...i, status: 'REVOKED' } : i)
        return { ok: true, status: 204, text: async () => '' }
      }
      if (url === '/api/admin/platform/users/u-dir/totp-reset' && method === 'POST') {
        users = users.map(u => u.id === 'u-dir' ? { ...u, totpEnabled: false } : u)
        return { ok: true, status: 204, text: async () => '' }
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(PlatformAdminPage, { auth: { accessToken: 'sys-jwt' } }) })
    try {
      app.mount(root)
      await flush()

      assert.match(textContent(root), /시스템 관리자/)
      assert.match(textContent(root), /프로젝트 총괄/)
      assert.match(textContent(root), /TOTP 활성/)
      assert.ok(calls.some(c => c.url === '/api/admin/platform/projects/proj-1/managers' && c.method === 'GET'))

      // Select target project for PROJECT_DIRECTOR invitation
      const projectSelect = walk(root, el => el.tag === 'select' && el.options.some(opt => opt.props.value === 'proj-1'))[0]
      assert.ok(projectSelect)
      for (const opt of projectSelect.options) opt.selected = opt.value === 'proj-1'
      projectSelect.value = 'proj-1'
      projectSelect.dispatch('change')
      await nextTick()

      const findButton = label => walk(root, el => el.tag === 'button' && textContent(el).includes(label))
      findButton('초대 코드/링크 발급')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/platform/account-invitations' && c.method === 'POST' && c.body.targetTier === 'PROJECT_DIRECTOR' && c.body.projectId === 'proj-1'))
      assert.match(textContent(root), /DIRECTOR-ONE-TIME-CODE/)
      assert.match(textContent(root), /https:\/\/console\.example\.com\/#register\?code=DIRECTOR-ONE-TIME-CODE/)

      findButton('초대 회수')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/platform/account-invitations/pinv-1/revoke'))

      // Reset TOTP on PROJECT_DIRECTOR (second TOTP 초기화 button)
      const resetButtons = findButton('TOTP 초기화')
      assert.equal(resetButtons.length, 2)
      resetButtons[1].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/platform/users/u-dir/totp-reset'))
      assert.match(textContent(root), /미등록/)
    } finally {
      app.unmount()
    }
  })
})

test('TeamsPage issues GENERAL_USER invitations, revokes pending invites, guards org calls for non-platform admins, and supports totp-reset for platform admins', async () => {
  const { default: TeamsPage } = await compileVueComponent(
    '../src/TeamsPage.vue',
    'teams-page-test',
    {
      '\\./BaseModal\\.vue': baseModalStubUrl,
      '\\./ConnectionRequests\\.vue': connectionRequestsStubUrl
    }
  )

  // 1. Non-platform admin (director scope): avoids org-only /teams & /users calls, revokes pending invite, issues project-scoped GENERAL_USER invite
  await withDomGlobals(async () => {
    const calls = []
    let invitations = [
      { id: 'oinv-1', codePrefix: 'GU01', targetTier: 'GENERAL_USER', organizationId: 'org-1', teamName: 'Backend', projectName: 'Gateway', expectedEmail: 'newdev@org.com', status: 'PENDING', expiresAt: '2026-11-26T00:00:00Z' }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Gateway', status: 'ACTIVE' }])
      if (url === '/api/admin/organizations/org-1/account-invitations' && method === 'GET') return okJson(invitations)
      if (url === '/api/admin/organizations/org-1/account-invitations' && method === 'POST') {
        invitations = [...invitations, { id: 'oinv-2', codePrefix: 'GU88', targetTier: body.targetTier, organizationId: 'org-1', teamId: body.teamId, projectId: body.projectId, canIssueApiKeys: body.canIssueApiKeys, expectedEmail: body.expectedEmail, status: 'PENDING', expiresAt: '2026-11-28T00:00:00Z' }]
        return okJson({ id: 'oinv-2', code: 'DEV-INVITE-8888', registrationPath: '/#register?code=DEV-INVITE-8888', targetTier: 'GENERAL_USER', organizationId: 'org-1', projectId: body.projectId, canIssueApiKeys: body.canIssueApiKeys, expiresAt: '2026-11-28T00:00:00Z' })
      }
      if (url === '/api/admin/organizations/org-1/account-invitations/oinv-1/revoke' && method === 'POST') {
        invitations = invitations.map(i => i.id === 'oinv-1' ? { ...i, status: 'REVOKED' } : i)
        return { ok: true, status: 204, text: async () => '' }
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(TeamsPage, { organizationId: 'org-1', auth: { accessToken: 'dir-jwt' }, platformAdmin: false })
    })
    try {
      app.mount(root)
      await flush()

      assert.equal(calls.some(c => c.url.endsWith('/teams') || c.url.endsWith('/users')), false)

      const findButton = label => walk(root, el => el.tag === 'button' && textContent(el).includes(label))
      findButton('초대 회수')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/organizations/org-1/account-invitations/oinv-1/revoke'))

      // Open invite modal and issue GENERAL_USER invite with canIssueApiKeys checkbox
      findButton('+ 초대 코드/링크 발급')[0].props.onClick()
      await nextTick()
      const modal = walk(root, el => el.props?.class === 'modal-stub')[0]
      assert.ok(modal)
      const delegateCheckbox = walk(modal, el => el.tag === 'input' && el.props.type === 'checkbox')[0]
      assert.ok(delegateCheckbox)
      delegateCheckbox.checked = true
      delegateCheckbox.dispatch('change')
      await nextTick()

      const modalIssueBtn = walk(modal, el => el.tag === 'button' && textContent(el).includes('초대 코드/링크 발급'))[0]
      modalIssueBtn.props.onClick()
      await flush()

      assert.ok(calls.some(c =>
        c.url === '/api/admin/organizations/org-1/account-invitations'
        && c.method === 'POST'
        && c.body.targetTier === 'GENERAL_USER'
        && c.body.projectId === 'p-1'
        && c.body.canIssueApiKeys === true
      ))
      assert.match(textContent(root), /DEV-INVITE-8888/)
    } finally {
      app.unmount()
    }
  })

  // 2. Platform admin loads org teams & users and can execute TOTP reset
  await withDomGlobals(async () => {
    const calls = []
    let orgUsers = [
      { id: 'u-dir', email: 'director@org.com', organizationRole: 'ORGANIZATION_ADMIN', accountTier: 'PROJECT_DIRECTOR', totpEnabled: true },
      { id: 'u-dev', email: 'dev@org.com', organizationRole: 'DEVELOPER', accountTier: 'GENERAL_USER', totpEnabled: true }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/teams') return okJson([{ id: 't-1', organizationId: 'org-1', name: 'Backend', status: 'ACTIVE' }])
      if (url === '/api/admin/organizations/org-1/users') return okJson(orgUsers)
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Gateway', status: 'ACTIVE' }])
      if (url === '/api/admin/organizations/org-1/teams/t-1/members') return okJson([])
      if (url === '/api/admin/organizations/org-1/account-invitations' && method === 'GET') return okJson([])
      if (url === '/api/admin/organizations/org-1/users/u-dev/totp-reset' && method === 'POST') {
        orgUsers = orgUsers.map(u => u.id === 'u-dev' ? { ...u, totpEnabled: false } : u)
        return { ok: true, status: 204, text: async () => '' }
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(TeamsPage, { organizationId: 'org-1', auth: { accessToken: 'sys-jwt' }, platformAdmin: true })
    })
    try {
      app.mount(root)
      await flush()

      assert.doesNotMatch(textContent(root), /\+ 직접 사용자 추가/)
      const resetBtns = walk(root, el => el.tag === 'button' && textContent(el).includes('TOTP 초기화'))
      assert.equal(resetBtns.length, 2)
      resetBtns[1].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/organizations/org-1/users/u-dev/totp-reset'))
    } finally {
      app.unmount()
    }
  })
})

test('ConsoleShell displays 3-tier role labels, TOTP active badge, and invitation-backed quick account creation', async () => {
  const shellSource = readFileSync(new URL('../src/ConsoleShell.vue', import.meta.url), 'utf8')
  assert.match(shellSource, /accountTierLabel/)
  assert.match(shellSource, /TOTP 활성/)
  assert.match(shellSource, /account-invitations/)
  assert.match(shellSource, /:platform-admin="platformAdmin"/)

  const { accountTierLabel } = await import(typeScriptModuleUrl('../src/api.ts'))
  assert.equal(accountTierLabel('SYSTEM_ADMIN', true), '시스템 관리자')
  assert.equal(accountTierLabel('PROJECT_DIRECTOR', false), '프로젝트 총괄')
  assert.equal(accountTierLabel('GENERAL_USER', false), '기본 계정')
})
