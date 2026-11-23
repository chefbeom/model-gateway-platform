import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, defineComponent, h, nextTick, ref } from 'vue'
import ts from 'typescript'
import { typeScriptModuleUrl } from './loadTypeScript.mjs'

function compileVueComponent(relativePath, id, extraReplacements = {}) {
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
    .replace(/from ['"]\.\/quotaVisualization['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/quotaVisualization.ts')))
    .replace(/from ['"]\.\/externalCapabilities['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/externalCapabilities.ts')))
  for (const [pattern, replacementUrl] of Object.entries(extraReplacements)) {
    script = script
      .replace(new RegExp(`from ['"]${pattern}['"]`, 'g'), 'from ' + JSON.stringify(replacementUrl))
      .replace(new RegExp(`import\\(['"]${pattern}['"]\\)`, 'g'), `import(${JSON.stringify(replacementUrl)})`)
  }
  return import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))
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

const emptyComponentStub = name => stubModuleUrl(`
export default defineComponent({
  name: ${JSON.stringify(name)},
  props: ['modelValue', 'auth', 'organizationId', 'platformAdmin', 'serviceId', 'providerId', 'models', 'capabilities', 'featureSupportJson', 'reasoningEffort', 'serviceTier', 'mode', 'runtime'],
  emits: ['update:modelValue', 'saved'],
  setup: () => () => h('div', { 'data-stub': ${JSON.stringify(name)} })
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
        for (const fn of element.listeners.get(type) ?? []) fn(event)
        const propName = 'on' + type[0].toUpperCase() + type.slice(1)
        if (typeof element.props[propName] === 'function') element.props[propName](event)
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
    patchProp: (element, key, _oldValue, value) => {
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
  const setSelect = async (selectEl, value) => {
    for (const opt of selectEl.options) opt.selected = opt.value === value
    selectEl.value = value
    selectEl.dispatch('change')
    await nextTick()
  }
  const setInput = async (inputEl, value) => {
    inputEl.value = value
    inputEl.dispatch('input')
    await nextTick()
  }
  const setCheckbox = async (checkboxEl, checked) => {
    checkboxEl.checked = checked
    checkboxEl.dispatch('change')
    await nextTick()
  }
  return { renderer, root, walk, textContent, setSelect, setInput, setCheckbox }
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
  const prev = {
    fetch: globalThis.fetch,
    window: globalThis.window,
    sessionStorage: globalThis.sessionStorage,
    localStorage: globalThis.localStorage,
    document: globalThis.document,
    Document: globalThis.Document,
    ShadowRoot: globalThis.ShadowRoot,
    CustomEvent: globalThis.CustomEvent
  }
  const listeners = new Map()
  globalThis.Document = class Document {}
  globalThis.ShadowRoot = class ShadowRoot {}
  globalThis.CustomEvent = class CustomEvent {
    constructor(type, init = {}) { this.type = type; this.detail = init.detail }
  }
  globalThis.sessionStorage = createMemoryStorage(initialSession)
  globalThis.localStorage = createMemoryStorage()
  globalThis.document = { documentElement: { dataset: {}, style: {} } }
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
    Object.assign(globalThis, prev)
  }
}

async function flush() {
  for (let i = 0; i < 12; i++) {
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
const noContent = () => ({ ok: true, status: 204, text: async () => '' })

test('api.ts governance helpers enforce 3-tier boundary monotonicity and org-admin distinction', async () => {
  const api = await import(typeScriptModuleUrl('../src/api.ts'))
  for (const fn of [
    'listProjectManagers', 'revokeProjectManager',
    'listProjectMembers', 'updateProjectMemberDelegation', 'revokeProjectMember',
    'updateProject', 'updateApiKeyBoundaryPolicy', 'updateServicePolicy',
    'getProjectQuotaCeiling', 'setProjectQuotaCeiling',
    'listProjectSpendQuotas', 'createProjectSpendQuota', 'updateProjectSpendQuota', 'deleteProjectSpendQuota',
    'decideExternalAccess', 'getRuntimeTrust', 'attestRuntimeTrust', 'revokeRuntimeTrust',
    'createAccountInvitation'
  ]) {
    assert.equal(typeof api[fn], 'function', `Expected export ${fn}`)
  }

  assert.equal(api.strictestDataBoundary('EXTERNAL_ALLOWED', 'APPROVED_EXTERNAL_NO_RETENTION', 'EXTERNAL_ALLOWED'), 'APPROVED_EXTERNAL_NO_RETENTION')
  assert.equal(api.strictestDataBoundary('APPROVED_EXTERNAL_NO_RETENTION', 'INTERNAL_ONLY'), 'INTERNAL_ONLY')
  assert.equal(api.exceedsDataBoundary('EXTERNAL_ALLOWED', 'APPROVED_EXTERNAL_NO_RETENTION'), true)
  assert.equal(api.exceedsDataBoundary('APPROVED_EXTERNAL_NO_RETENTION', 'APPROVED_EXTERNAL_NO_RETENTION'), false)
  assert.equal(api.exceedsDataBoundary('INTERNAL_ONLY', 'APPROVED_EXTERNAL_NO_RETENTION'), false)

  await withDomGlobals(() => {
    globalThis.sessionStorage.setItem('aiconnect.user', JSON.stringify({ id: 'u1', email: 'd@x.com', platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true }))
    assert.equal(api.resolveOrgAdminAccess({ accessToken: 'jwt' }, false), false)
    globalThis.sessionStorage.setItem('aiconnect.user', JSON.stringify({ id: 'u2', email: 'a@x.com', platformAdmin: true, accountTier: 'SYSTEM_ADMIN', totpEnabled: true }))
    assert.equal(api.resolveOrgAdminAccess({ accessToken: 'jwt' }, false), true)
    assert.equal(api.resolveOrgAdminAccess({ platformToken: 'break-glass' }, false), true)
  })
})

test('ConsoleShell hides org-only nav from PROJECT_DIRECTOR/GENERAL_USER and quick-invite uses projectId + canIssueApiKeys', async () => {
  const stubs = {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./DateTimePreferences\\.vue': emptyComponentStub('DateTimePreferences'),
    '\\./AuditLogPage\\.vue': emptyComponentStub('AuditLogPage'),
    '\\./DashboardPage\\.vue': emptyComponentStub('DashboardPage'),
    '\\./ScopedOverviewPage\\.vue': emptyComponentStub('ScopedOverviewPage'),
    '\\./DeveloperPortalPage\\.vue': emptyComponentStub('DeveloperPortalPage'),
    '\\./DevDocsPage\\.vue': emptyComponentStub('DevDocsPage'),
    '\\./InfrastructurePage\\.vue': emptyComponentStub('InfrastructurePage'),
    '\\./SystemStructurePage\\.vue': emptyComponentStub('SystemStructurePage'),
    '\\./ExternalProvidersPage\\.vue': emptyComponentStub('ExternalProvidersPage'),
    '\\./ServicesPage\\.vue': emptyComponentStub('ServicesPage'),
    '\\./ProjectsPage\\.vue': stubModuleUrl(`export default defineComponent({ name: 'ProjectsPage', emits: ['invite'], setup(_, { emit }) { return () => h('button', { 'data-context-invite': true, onClick: () => emit('invite', { id: 'p-2', name: 'Other Project' }) }, 'Context invite') } })`),
    '\\./TeamsPage\\.vue': emptyComponentStub('TeamsPage'),
    '\\./ObservabilityPage\\.vue': emptyComponentStub('ObservabilityPage'),
    '\\./NotificationsPage\\.vue': emptyComponentStub('NotificationsPage'),
    '\\./UsagePage\\.vue': emptyComponentStub('UsagePage'),
    '\\./QuotaPage\\.vue': emptyComponentStub('QuotaPage'),
    '\\./DataProtectionPage\\.vue': emptyComponentStub('DataProtectionPage'),
    '\\./ApiPlaygroundPage\\.vue': emptyComponentStub('ApiPlaygroundPage'),
    '\\./ModelPlaygroundPage\\.vue': emptyComponentStub('ModelPlaygroundPage'),
    '\\./PlatformAdminPage\\.vue': emptyComponentStub('PlatformAdminPage'),
    '\\./BetaOperatorPage\\.vue': emptyComponentStub('BetaOperatorPage'),
    '\\./WorkspaceRelationship\\.vue': emptyComponentStub('WorkspaceRelationship')
  }
  const { default: ConsoleShell } = await compileVueComponent('../src/ConsoleShell.vue', 'shell-gov-test', stubs)

  // 1. PROJECT_DIRECTOR view
  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/portal/session') {
        return okJson({ platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true, mfaState: 'VERIFIED', memberships: [{ organizationId: 'org-1', role: 'DEVELOPER' }] })
      }
      if (url === '/api/beta/operator/services') return okJson([])
      if (url === '/api/admin/organizations') return okJson([{ id: 'org-1', name: 'Calen', status: 'ACTIVE' }])
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Docs AI', status: 'ACTIVE' }, { id: 'p-2', name: 'Other Project', status: 'ACTIVE' }])
      if (url === '/api/admin/organizations/org-1/account-invitations' && method === 'POST') {
        return okJson({ id: 'inv-1', code: 'CODE-1', registrationPath: '/#register?code=CODE-1', targetTier: 'GENERAL_USER', organizationId: 'org-1', projectId: body.projectId, canIssueApiKeys: body.canIssueApiKeys, expiresAt: '2026-11-30T00:00:00Z' })
      }
      throw new Error(`Unexpected fetch for director: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setCheckbox } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(ConsoleShell, {
        user: { id: 'u-dir', email: 'dir@org.com', platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true },
        platformTokenSession: false,
        theme: 'dark',
        fontScale: '100'
      })
    })
    try {
      app.mount(root)
      await flush()

      assert.equal(calls.some(c => c.url.includes('/teams')), false)
      const sideNav = walk(root, el => el.tag === 'nav')[0]
      const navText = textContent(sideNav)
      for (const allowed of ['대시보드', '관측성', '프로젝트', '조직 관계도', '요금·한도', '데이터 보호', '내 API', 'API 사용량', 'API 테스트']) {
        assert.ok(navText.includes(allowed), `Missing allowed nav item: ${allowed}`)
      }
      for (const hidden of ['팀과 부서', 'LLM 서비스', '인프라스트럭처', '외부 AI', '모델 테스트 콘솔', 'Platform Admin', '알림 채널', '감사 로그', '시스템 구조']) {
        assert.equal(navText.includes(hidden), false, `Org-only nav leaked to director: ${hidden}`)
      }
      assert.equal(globalThis.window.location.hash, '#dashboard')
      walk(sideNav, el => el.tag === 'button' && textContent(el).includes('프로젝트·멤버·권한·API 키'))[0].props.onClick()
      await nextTick()

      // Open settings -> 계정 추가 -> quick-invite modal
      walk(root, el => el.tag === 'button' && el.props['aria-label'] === '설정')[0].props.onClick()
      await nextTick()
      walk(root, el => el.tag === 'button' && textContent(el).includes('계정 추가'))[0].props.onClick()
      await flush()

      const modal = walk(root, el => el.props?.class === 'modal-stub')[0]
      assert.ok(modal)
      const modalText = textContent(modal)
      assert.match(modalText, /대상 프로젝트/)
      assert.match(modalText, /API 키 발급 권한 위임/)
      assert.doesNotMatch(modalText, /팀 역할/)

      const checkbox = walk(modal, el => el.tag === 'input' && el.props.type === 'checkbox')[0]
      await setCheckbox(checkbox, true)
      walk(modal, el => el.tag === 'button' && textContent(el).includes('초대 코드/링크 발급'))[0].props.onClick()
      await flush()

      assert.ok(calls.some(c =>
        c.url === '/api/admin/organizations/org-1/account-invitations'
        && c.method === 'POST'
        && c.body.projectId === 'p-1'
        && c.body.canIssueApiKeys === true
        && c.body.teamId === undefined
      ))
      walk(modal, el => el.tag === 'button' && textContent(el) === '닫기')[0].props.onClick()
      await nextTick()
      walk(root, el => el.props?.['data-context-invite'])[0].props.onClick()
      await nextTick()
      const contextModal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const contextSelects = walk(contextModal, el => el.tag === 'select')
      assert.equal(contextSelects[0].props.disabled, true)
      assert.match(textContent(contextModal), /Other Project/)
      walk(contextModal, el => el.tag === 'button' && textContent(el).includes('초대 코드/링크 발급'))[0].props.onClick()
      await flush()
      assert.equal(calls.filter(c => c.url === '/api/admin/organizations/org-1/account-invitations' && c.method === 'POST').at(-1).body.projectId, 'p-2')
    } finally {
      app.unmount()
    }
  }, { 'aiconnect.accessToken': 'dir-jwt' }, '#teams')

  // 2. GENERAL_USER view
  await withDomGlobals(async () => {
    globalThis.fetch = async url => {
      if (url === '/api/portal/session') return okJson({ platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true, mfaState: 'VERIFIED', memberships: [{ organizationId: 'org-1', role: 'DEVELOPER' }] })
      if (url === '/api/beta/operator/services') return okJson([])
      if (url === '/api/admin/organizations') return okJson([{ id: 'org-1', name: 'Calen', status: 'ACTIVE' }])
      throw new Error(`Unexpected fetch for general user: ${url}`)
    }
    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(ConsoleShell, {
        user: { id: 'u-gen', email: 'gen@org.com', platformAdmin: false, accountTier: 'GENERAL_USER', totpEnabled: true },
        platformTokenSession: false,
        theme: 'dark',
        fontScale: '100'
      })
    })
    try {
      app.mount(root)
      await flush()
      const navText = textContent(walk(root, el => el.tag === 'nav')[0])
      assert.equal(walk(walk(root, el => el.tag === 'nav')[0], el => el.tag === 'button' && textContent(el).startsWith('프로젝트')).length, 0)
      assert.equal(globalThis.window.location.hash, '#dashboard')
      assert.ok(navText.includes('대시보드'))
      assert.ok(navText.includes('관측성'))
      assert.ok(navText.includes('요금·한도'))
      assert.ok(navText.includes('조직 관계도'))
      assert.equal(navText.includes('데이터 보호'), false)
      assert.ok(navText.includes('내 API'))
    } finally {
      app.unmount()
    }
  }, { 'aiconnect.accessToken': 'gen-jwt' }, '#projects')
})

test('relationship displays only accessible project data and distinguishes lookup failure', async () => {
  const { default: Relationship } = await compileVueComponent('../src/WorkspaceRelationship.vue', 'relationship-test')
  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async url => {
      calls.push(String(url))
      if (String(url).includes('/org-error/')) return { ok: false, status: 403, text: async () => '{}' }
      return okJson([{ id: 'p-1', name: '승인 프로젝트', status: 'ACTIVE', canIssueApiKeys: true }])
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(Relationship, { organizationId: 'org-1', organizationName: '우리 조직', userName: '나', roleLabel: '팀원', auth: { accessToken: 'token' } }) })
    try {
      app.mount(root); await flush()
      assert.match(textContent(root), /우리 조직/)
      assert.match(textContent(root), /승인 프로젝트/)
      assert.match(textContent(root), /API 키 발급 가능/)
      assert.equal(calls.some(url => url.startsWith('/api/admin/')), false)
    } finally { app.unmount() }
    const failed = createMemoryDom()
    const failureApp = failed.renderer.createApp({ render: () => h(Relationship, { organizationId: 'org-error', auth: { accessToken: 'token' } }) })
    try {
      failureApp.mount(failed.root); await flush()
      assert.match(failed.textContent(failed.root), /관계도를 불러오지 못했습니다/)
      assert.doesNotMatch(failed.textContent(failed.root), /접근 가능한 프로젝트가 없습니다/)
    } finally { failureApp.unmount() }
  })
})

test('scoped quota page shows only key budget and avoids admin APIs', async () => {
  const { default: Page } = await compileVueComponent('../src/ScopedOverviewPage.vue', 'scoped-overview-test', {
    '\\./RequestDetailModal\\.vue': emptyComponentStub('RequestDetailModal'),
    '\\./WorkspaceRelationship\\.vue': emptyComponentStub('WorkspaceRelationship')
  })
  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async url => {
      calls.push(String(url))
      if (String(url).includes('/own-key-quotas')) return okJson([{ keyName: '내 키', name: '개인 상한', currency: 'KRW', limitAmount: 100, usedAmount: 12, period: 'MONTHLY', enabled: true }])
      return okJson({ scopeLabel: '내가 발급한 API 키', availableProjects: [], total: { requestCount: 1, succeeded: 1, failed: 0, inputTokens: 3, outputTokens: 2, estimatedCostByCurrency: { KRW: 12 } } })
    }
    const { renderer, root, textContent } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(Page, { organizationId: 'org-1', auth: { accessToken: 'token' }, mode: 'quotas' }) })
    try {
      app.mount(root); await flush()
      assert.match(textContent(root), /내 키/)
      assert.match(textContent(root), /개인 상한/)
      assert.match(textContent(root), /키에 직접 설정된 한도/)
      assert.ok(calls.some(url => url.includes('/own-key-quotas')))
      assert.equal(calls.some(url => url.startsWith('/api/admin/')), false)
    } finally { app.unmount() }
  })
})

test('director dashboard loads only authorized project budget and keeps currency and missing values distinct', async () => {
  const { default: Page } = await compileVueComponent('../src/ScopedOverviewPage.vue', 'director-dashboard-test', {
    '\\./RequestDetailModal\\.vue': emptyComponentStub('RequestDetailModal'),
    '\\./WorkspaceRelationship\\.vue': emptyComponentStub('WorkspaceRelationship')
  })
  await withDomGlobals(async () => {
    const calls = []
    const projects = [{ id: 'a', name: 'Managed A', access: 'PROJECT_ALL', accessLabel: '관리' }, { id: 'b', name: 'Own B', access: 'OWN_KEYS', accessLabel: '내 키' }, { id: 'c', name: 'Managed C', access: 'PROJECT_ALL', accessLabel: '관리' }]
    const q = (id, currency, usedAmount, scopeType = 'PROJECT') => ({ id, name: id, currency, usedAmount, scopeType, period: 'MONTHLY', enabled: true, limitAmount: 100, exceeded: false })
    globalThis.fetch = async url => {
      calls.push(String(url))
      if (String(url).includes('spend-quotas')) return okJson({ ceilings: [q('zero', 'USD', 0), q('hidden', 'USD', null)], quotas: [q('zero', 'USD', 0), q('won', 'KRW', 30), q('key', 'KRW', 20, 'API_KEY')] })
      if (String(url).includes('/requests')) return okJson([])
      return okJson({ scopeLabel: '접근 범위', availableProjects: projects, total: { requestCount: 0, succeeded: 0, failed: 0, inputTokens: 0, outputTokens: 0, estimatedCostByCurrency: { USD: 0, KRW: 30 } } })
    }
    const dom = createMemoryDom(); const app = dom.renderer.createApp({ render: () => h(Page, { organizationId: 'org', auth: { accessToken: 'token' }, mode: 'dashboard', isDirector: true }) })
    try {
      app.mount(dom.root); await flush()
      assert.match(dom.textContent(dom.root), /프로젝트를 선택하세요/)
      assert.match(dom.textContent(dom.root), /기록 없음/)
      assert.equal(calls.some(url => url.includes('spend-quotas')), false)
      const select = dom.walk(dom.root, el => el.tag === 'select')[0]
      await dom.setSelect(select, 'a'); await flush()
      assert.equal(calls.filter(url => url.includes('spend-quotas')).length, 1)
      assert.match(dom.textContent(dom.root), /USD 0 \/ USD 100/)
      assert.match(dom.textContent(dom.root), /KRW 30 \/ KRW 100/)
      assert.doesNotMatch(dom.textContent(dom.root), /hidden|key/)
      assert.equal(dom.walk(dom.root, el => el.tag === 'button' && dom.textContent(el).includes('조직 관계도')).length, 1)
      await dom.setSelect(select, 'b'); await flush()
      assert.equal(calls.filter(url => url.includes('spend-quotas')).length, 1)
      assert.match(dom.textContent(dom.root), /전체 관리 권한이 없습니다/)
    } finally { app.unmount() }
  })
})

test('dashboard isolates request failure and rejects old organization responses without admin calls for general users', async () => {
  const { default: Page } = await compileVueComponent('../src/ScopedOverviewPage.vue', 'dashboard-race-test', {
    '\\./RequestDetailModal\\.vue': emptyComponentStub('RequestDetailModal'),
    '\\./WorkspaceRelationship\\.vue': emptyComponentStub('WorkspaceRelationship')
  })
  await withDomGlobals(async () => {
    const calls = []; let resolveOld
    const summary = label => ({ scopeLabel: label, availableProjects: [], total: { requestCount: 0, succeeded: 0, failed: 0, inputTokens: 0, outputTokens: 0, estimatedCostByCurrency: {} } })
    globalThis.fetch = async url => {
      calls.push(String(url))
      if (String(url).includes('/requests')) throw new Error('history unavailable')
      if (String(url).includes('/old/')) return new Promise(resolve => { resolveOld = () => resolve(okJson(summary('OLD SCOPE'))) })
      return okJson(summary('NEW SCOPE'))
    }
    const org = ref('old'), dom = createMemoryDom()
    const auth = { accessToken: 'token' }
    const app = dom.renderer.createApp({ render: () => h(Page, { organizationId: org.value, auth, mode: 'dashboard', isDirector: false }) })
    try {
      app.mount(dom.root); await flush(); org.value = 'new'; await flush()
      assert.match(dom.textContent(dom.root), /NEW SCOPE/)
      assert.match(dom.textContent(dom.root), /요청 조회 실패/)
      assert.match(dom.textContent(dom.root), /기록 없음/)
      resolveOld(); await flush()
      assert.doesNotMatch(dom.textContent(dom.root), /OLD SCOPE/)
      assert.equal(calls.some(url => url.startsWith('/api/admin/')), false)
    } finally { app.unmount() }
  })
})

test('issued connection guide distinguishes SDK and separate-path client URLs', async () => {
  const { default: Guide } = await compileVueComponent('../src/IssuedConnectionGuide.vue', 'issued-guide-test')
  const { renderer, root, textContent } = createMemoryDom()
  const app = renderer.createApp({ render: () => h(Guide, {
    endpoints: [{ scope: 'EXTERNAL', label: 'External', url: 'https://api.example.test/v1' }],
    models: [{ serviceKey: 'text-pro', displayName: 'Text Pro' }]
  }) })
  try {
    app.mount(root)
    const text = textContent(root)
    assert.match(text, /text-pro/)
    assert.match(text, /https:\/\/api\.example\.test\/v1/)
    assert.match(text, /https:\/\/api\.example\.test/)
    assert.match(text, /\/v1\/chat\/completions/)
    assert.match(text, /\/v1\/models/)
    assert.doesNotMatch(text, /\/v1\/v1/)
  } finally { app.unmount() }
})

test('ProjectsPage avoids 403s for PROJECT_DIRECTOR, clamps DataBoundaryPolicy, manages members/delegation, and supports quota ceilings', async () => {
  const { default: ProjectsPage } = await compileVueComponent('../src/ProjectsPage.vue', 'projects-gov-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./IssuedConnectionGuide\\.vue': emptyComponentStub('IssuedConnectionGuide')
  })

  // Director flow
  await withDomGlobals(async () => {
    const calls = []
    let project = { id: 'p-1', organizationId: 'org-1', name: 'Doc Analysis', status: 'ACTIVE', externalAiBlocked: false, dataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', maxDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION' }
    let members = [
      { userId: 'u-dir', email: 'dir@org.com', accountTier: 'PROJECT_DIRECTOR', role: 'MANAGER', canIssueApiKeys: true, activeApiKeyCount: 1, createdAt: '2026-11-18T00:00:00Z' },
      { userId: 'u-mem', email: 'mem@org.com', accountTier: 'GENERAL_USER', role: 'MEMBER', canIssueApiKeys: false, activeApiKeyCount: 1, createdAt: '2026-11-19T00:00:00Z' }
    ]
    let keys = [
      { id: 'k-1', name: 'hr-key', keyPrefix: 'sk_hr', status: 'ACTIVE', dataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', effectiveDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', createdAt: '2026-11-19T00:00:00Z' }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/projects') return okJson([project])
      if (url === '/api/admin/projects/p-1/api-keys' && method === 'GET') return okJson(keys)
      if (url === '/api/admin/projects/p-1/api-keys' && method === 'POST') return okJson({ secret: 'sk_new_123' })
      if (url === '/api/admin/projects/p-1/api-keys/k-1/policy' && method === 'PATCH') {
        keys = keys.map(k => k.id === 'k-1' ? { ...k, dataBoundaryPolicy: body.dataBoundaryPolicy, effectiveDataBoundaryPolicy: body.dataBoundaryPolicy } : k)
        return okJson(keys[0])
      }
      if (url === '/api/admin/projects/p-1/quota') return okJson({ projectId: 'p-1', requestsPerMinute: 40, monthlyTokenLimit: 50000, maxRequestsPerMinute: 60, maxMonthlyTokenLimit: 100000 })
      if (url === '/api/admin/projects/p-1/content-policy') return okJson({ projectId: 'p-1', mode: 'METADATA_ONLY', retentionDays: null })
      if (url === '/api/admin/projects/p-1/alert-policy') return okJson({ projectId: 'p-1', requestsPerMinuteThreshold: null, errorRatePercentThreshold: null, monthlyTokenUsagePercentThreshold: null, cooldownSeconds: 900 })
      if (url === '/api/admin/projects/p-1/control') return okJson({ projectId: 'p-1', projectName: 'Doc Analysis', status: 'ACTIVE', activeApiKeyCount: 1, revokedApiKeyCount: 0 })
      if (url === '/api/admin/projects/p-1/service-access') return okJson([])
      if (url === '/api/admin/projects/p-1/members' && method === 'GET') return okJson(members)
      if (url === '/api/admin/projects/p-1/members/u-mem' && method === 'PATCH') {
        members = members.map(m => m.userId === 'u-mem' ? { ...m, canIssueApiKeys: body.canIssueApiKeys } : m)
        return okJson(members[1])
      }
      if (url === '/api/admin/projects/p-1/members/u-mem' && method === 'DELETE') {
        members = members.filter(m => m.userId !== 'u-mem')
        return noContent()
      }
      if (url === '/api/admin/projects/p-1' && method === 'PATCH') {
        project = { ...project, name: body.name, dataBoundaryPolicy: body.dataBoundaryPolicy, externalAiBlocked: body.dataBoundaryPolicy === 'INTERNAL_ONLY' }
        return okJson(project)
      }
      throw new Error(`Unexpected fetch in ProjectsPage director test: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setSelect, setInput } = createMemoryDom()
    let invitedProject = null
    const app = renderer.createApp({ render: () => h(ProjectsPage, { organizationId: 'org-1', organizationName: 'Calen', currentUserId: 'u-dir', auth: { accessToken: 'dir-jwt' }, platformAdmin: false, orgAdmin: false, onInvite: project => { invitedProject = project } }) })
    try {
      app.mount(root)
      await flush()

      assert.equal(calls.some(c => c.url.endsWith('/teams') || c.url.endsWith('/services')), false)
      walk(root, el => el.tag === 'button' && textContent(el) === '정책')[0].props.onClick()
      await nextTick()
      assert.match(textContent(root), /관리자 상한: 60 RPM/)

      // Toggle member API key delegation
      walk(root, el => el.tag === 'button' && textContent(el) === '구성원')[0].props.onClick()
      await nextTick()
      assert.match(textContent(root), /조직: Calen/)
      walk(root, el => el.tag === 'button' && textContent(el) === '구성원 초대')[0].props.onClick()
      assert.deepEqual(invitedProject, { id: 'p-1', name: 'Doc Analysis' })
      walk(root, el => el.tag === 'button' && textContent(el).includes('발급 권한 위임'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/members/u-mem' && c.method === 'PATCH' && c.body.canIssueApiKeys === true))

      // Update API key boundary policy (verify EXTERNAL_ALLOWED is disabled)
      walk(root, el => el.tag === 'button' && textContent(el) === 'API 키')[0].props.onClick()
      await nextTick()
      const inlineBoundary = walk(root, el => el.tag === 'select' && el.props['aria-label'] === 'hr-key 데이터 경계')[0]
      assert.equal(inlineBoundary.options.find(opt => opt.value === 'EXTERNAL_ALLOWED').props.disabled, true)
      await setSelect(inlineBoundary, 'INTERNAL_ONLY')
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/api-keys/k-1/policy' && c.method === 'PATCH' && c.body.dataBoundaryPolicy === 'INTERNAL_ONLY'))

      // Revoke member
      walk(root, el => el.tag === 'button' && textContent(el) === '구성원')[0].props.onClick()
      await nextTick()
      walk(root, el => el.tag === 'button' && textContent(el).includes('멤버 회수'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/members/u-mem' && c.method === 'DELETE'))

      // Project edit modal clamps options above maxDataBoundaryPolicy and omits maxDataBoundaryPolicy for director
      walk(root, el => el.tag === 'button' && textContent(el).includes('프로젝트 수정'))[0].props.onClick()
      await nextTick()
      const editModal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const boundarySelect = walk(editModal, el => el.tag === 'select')[0]
      assert.equal(boundarySelect.options.find(opt => opt.value === 'EXTERNAL_ALLOWED').props.disabled, true)
      await setSelect(boundarySelect, 'INTERNAL_ONLY')
      walk(editModal, el => el.tag === 'button' && textContent(el).includes('수정 저장'))[0].props.onClick()
      await flush()

      const patchCall = calls.find(c => c.url === '/api/admin/projects/p-1' && c.method === 'PATCH')
      assert.ok(patchCall)
      assert.equal(patchCall.body.dataBoundaryPolicy, 'INTERNAL_ONLY')
      assert.equal('maxDataBoundaryPolicy' in patchCall.body, false)

      // Issue new key modal enforces ceiling
      walk(root, el => el.tag === 'button' && textContent(el) === 'API 키')[0].props.onClick()
      await nextTick()
      walk(root, el => el.tag === 'button' && textContent(el).includes('API 키 발급'))[0].props.onClick()
      await nextTick()
      const keyModal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const keyNameInput = walk(keyModal, el => el.tag === 'input')[0]
      await setInput(keyNameInput, 'local-only-key')
      const keySelect = walk(keyModal, el => el.tag === 'select')[0]
      assert.equal(keySelect.options.find(opt => opt.value === 'EXTERNAL_ALLOWED').props.disabled, true)
      await setSelect(keySelect, 'INTERNAL_ONLY')
      walk(keyModal, el => el.tag === 'button' && textContent(el).includes('키 발급'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/api-keys' && c.method === 'POST' && c.body.dataBoundaryPolicy === 'INTERNAL_ONLY'))
    } finally {
      app.unmount()
    }
  }, {
    'aiconnect.user': JSON.stringify({ id: 'u-dir', email: 'dir@org.com', platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true })
  })

  // Platform admin quota ceiling edit
  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', organizationId: 'org-1', name: 'Doc Analysis', status: 'ACTIVE', externalAiBlocked: false }])
      if (url === '/api/admin/organizations/org-1/teams') return okJson([])
      if (url === '/api/admin/organizations/org-1/services') return okJson([])
      if (url === '/api/admin/projects/p-1/api-keys') return okJson([])
      if (url === '/api/admin/projects/p-1/quota') return okJson({ projectId: 'p-1', requestsPerMinute: 60, monthlyTokenLimit: 100000, maxRequestsPerMinute: null, maxMonthlyTokenLimit: null })
      if (url === '/api/admin/projects/p-1/content-policy') return okJson({ projectId: 'p-1', mode: 'METADATA_ONLY', retentionDays: null })
      if (url === '/api/admin/projects/p-1/alert-policy') return okJson({ projectId: 'p-1', requestsPerMinuteThreshold: null, errorRatePercentThreshold: null, monthlyTokenUsagePercentThreshold: null, cooldownSeconds: 900 })
      if (url === '/api/admin/projects/p-1/control') return okJson({ projectId: 'p-1', projectName: 'Doc Analysis', status: 'ACTIVE', activeApiKeyCount: 0, revokedApiKeyCount: 0 })
      if (url === '/api/admin/projects/p-1/service-access') return okJson([])
      if (url === '/api/admin/projects/p-1/members') return okJson([])
      if (url === '/api/admin/platform/projects/p-1/quota-ceiling' && method === 'PUT') {
        return okJson({ projectId: 'p-1', requestsPerMinute: 30, monthlyTokenLimit: 50000, maxRequestsPerMinute: body.maxRequestsPerMinute, maxMonthlyTokenLimit: body.maxMonthlyTokenLimit })
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setInput } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(ProjectsPage, { organizationId: 'org-1', auth: { accessToken: 'sys-jwt' }, platformAdmin: true, orgAdmin: true }) })
    try {
      app.mount(root)
      await flush()
      walk(root, el => el.tag === 'button' && textContent(el) === '정책')[0].props.onClick()
      await nextTick()
      walk(root, el => el.tag === 'button' && textContent(el).includes('상한 설정'))[0].props.onClick()
      await nextTick()
      const ceilingModal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const inputs = walk(ceilingModal, el => el.tag === 'input')
      await setInput(inputs[0], '30')
      await setInput(inputs[1], '50000')
      walk(ceilingModal, el => el.tag === 'button' && textContent(el).includes('상한 저장'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/platform/projects/p-1/quota-ceiling' && c.method === 'PUT' && c.body.maxRequestsPerMinute === 30 && c.body.maxMonthlyTokenLimit === 50000))
    } finally {
      app.unmount()
    }
  })
})

test('QuotaPage avoids org-only calls for PROJECT_DIRECTOR, shows read-only admin ceilings, and enforces sub-quota ceiling clamping', async () => {
  const { default: QuotaPage } = await compileVueComponent('../src/QuotaPage.vue', 'quota-gov-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl
  })

  await withDomGlobals(async () => {
    const calls = []
    let subQuotas = [
      { id: 'sq-sub', organizationId: 'org-1', scopeType: 'PROJECT', scopeId: 'p-1', scopeLabel: 'Doc Analysis', name: '프로젝트 월 예산', currency: 'KRW', limitAmount: 40000, period: 'MONTHLY', enabled: true, adminCeiling: false, usedAmount: 10000, usagePercent: 25, exceeded: false }
    ]
    const ceilings = [
      { id: 'sq-ceil', organizationId: 'org-1', scopeType: 'ORGANIZATION', scopeId: 'org-1', scopeLabel: 'Organization', name: '조직 월 상한', currency: 'KRW', limitAmount: 100000, period: 'MONTHLY', enabled: true, adminCeiling: true, usedAmount: 10000, usagePercent: 10, exceeded: false }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Doc Analysis', status: 'ACTIVE' }])
      if (url === '/api/admin/projects/p-1/spend-quotas' && method === 'GET') return okJson({ projectId: 'p-1', ceilings, quotas: subQuotas })
      if (url === '/api/admin/projects/p-1/quota' && method === 'GET') return okJson({ projectId: 'p-1', requestsPerMinute: 40, monthlyTokenLimit: 50000, maxRequestsPerMinute: 60, maxMonthlyTokenLimit: 100000 })
      if (url === '/api/admin/projects/p-1/api-keys') return okJson([{ id: 'k-1', name: 'app-key', keyPrefix: 'sk_1' }])
      if (url === '/api/admin/projects/p-1/spend-quotas' && method === 'POST') {
        const created = { id: 'sq-new', organizationId: 'org-1', ...body, scopeLabel: 'app-key', adminCeiling: false, usedAmount: 0, usagePercent: 0, exceeded: false }
        subQuotas = [...subQuotas, created]
        return okJson(created)
      }
      if (url === '/api/admin/projects/p-1/spend-quotas/sq-new' && method === 'PATCH') {
        subQuotas = subQuotas.map(q => q.id === 'sq-new' ? { ...q, ...body } : q)
        return okJson(subQuotas.find(q => q.id === 'sq-new'))
      }
      if (url === '/api/admin/projects/p-1/spend-quotas/sq-sub' && method === 'DELETE') {
        subQuotas = subQuotas.filter(q => q.id !== 'sq-sub')
        return noContent()
      }
      throw new Error(`Unexpected fetch in QuotaPage director test: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setInput } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(QuotaPage, { organizationId: 'org-1', auth: { accessToken: 'dir-jwt' }, platformAdmin: false, orgAdmin: false }) })
    try {
      app.mount(root)
      await flush()

      assert.equal(calls.some(c => c.url.includes('/quota-overview') || c.url.endsWith('/quotas') || c.url.endsWith('/teams')), false)
      assert.match(textContent(root), /조직 월 상한/)
      assert.match(textContent(root), /ADMIN CEILING/)
      assert.match(textContent(root), /프로젝트 월 예산/)

      // Delete director sub-quota (only 1 delete button, none on admin ceiling)
      const deleteButtons = walk(root, el => el.tag === 'button' && textContent(el).trim() === '삭제')
      assert.equal(deleteButtons.length, 1)
      deleteButtons[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/spend-quotas/sq-sub' && c.method === 'DELETE'))

      // Create sub-quota and test client-side ceiling guard
      walk(root, el => el.tag === 'button' && textContent(el).includes('+ 한도 추가'))[0].props.onClick()
      await nextTick()
      const modal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const inputs = walk(modal, el => el.tag === 'input' && el.props.type !== 'checkbox')
      await setInput(inputs[0], '팀 하위 예산')
      await setInput(inputs[2], '200000')
      const saveBtn = () => walk(modal, el => el.tag === 'button' && textContent(el).trim() === '저장')[0]
      assert.equal(saveBtn().props.disabled, true)

      await setInput(inputs[2], '50000')
      assert.equal(saveBtn().props.disabled, false)
      saveBtn().props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/spend-quotas' && c.method === 'POST' && c.body.limitAmount === 50000))

      // Edit created sub-quota
      walk(root, el => el.tag === 'button' && textContent(el).trim() === '수정')[0].props.onClick()
      await nextTick()
      const editModal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const editInputs = walk(editModal, el => el.tag === 'input' && el.props.type !== 'checkbox')
      await setInput(editInputs[2], '45000')
      walk(editModal, el => el.tag === 'button' && textContent(el).trim() === '저장')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/spend-quotas/sq-new' && c.method === 'PATCH' && c.body.limitAmount === 45000))
    } finally {
      app.unmount()
    }
  }, {
    'aiconnect.user': JSON.stringify({ id: 'u-dir', email: 'dir@org.com', platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true })
  })
})

test('DataProtectionPage skips org-level DLP/services fetches for PROJECT_DIRECTOR and saves project/key policies', async () => {
  const { default: DataProtectionPage } = await compileVueComponent('../src/DataProtectionPage.vue', 'dlp-gov-test')

  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Doc Analysis', status: 'ACTIVE', externalAiBlocked: false, dataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', maxDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION' }])
      if (url === '/api/admin/projects/p-1/data-protection' && method === 'GET') return okJson({ scopeType: 'PROJECT', scopeId: 'p-1', mode: 'MONITOR', level: 'BALANCED', externalAction: 'ALLOW', allowExternalFailover: true, detectSecrets: true, detectPii: true, detectFinancial: false, detectConfidential: false, detectMedia: true, customPatternsJson: '[]' })
      if (url === '/api/admin/projects/p-1/api-keys') return okJson([{ id: 'k-1', name: 'hr-key', keyPrefix: 'sk_hr', status: 'ACTIVE' }])
      if (url === '/api/admin/projects/p-1/api-keys/k-1/data-protection' && method === 'GET') return okJson({ scopeType: 'API_KEY', scopeId: 'k-1', mode: 'ENFORCE', level: 'STRICT', externalAction: 'LOCAL_ONLY', allowExternalFailover: false, detectSecrets: true, detectPii: true, detectFinancial: true, detectConfidential: true, detectMedia: true, customPatternsJson: '[]' })
      if (url === '/api/admin/projects/p-1/api-keys/k-1/data-protection' && method === 'PUT') return okJson({ ...body, scopeType: 'API_KEY', scopeId: 'k-1' })
      if (url === '/api/admin/projects/p-1/data-protection' && method === 'PUT') return okJson({ ...body, scopeType: 'PROJECT', scopeId: 'p-1' })
      throw new Error(`Unexpected fetch in DataProtectionPage director test: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(DataProtectionPage, { organizationId: 'org-1', auth: { accessToken: 'dir-jwt' }, platformAdmin: false, orgAdmin: false }) })
    try {
      app.mount(root)
      await flush()

      assert.equal(calls.some(c => c.url.includes('/organizations/org-1/data-protection') || c.url.endsWith('/services') || c.url.includes('/platform/data-protection')), false)
      assert.match(textContent(root), /승인된 외부 비보존 GPU 허용/)
      assert.equal(walk(root, el => el.tag === 'button' && textContent(el).includes('조직 기본')).length, 0)

      walk(root, el => el.tag === 'button' && textContent(el).includes('정책 저장'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/data-protection' && c.method === 'PUT'))

      walk(root, el => el.tag === 'button' && textContent(el).includes('hr-key'))[0].props.onClick()
      await flush()
      walk(root, el => el.tag === 'button' && textContent(el).includes('정책 저장'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/projects/p-1/api-keys/k-1/data-protection' && c.method === 'PUT'))
    } finally {
      app.unmount()
    }
  }, {
    'aiconnect.user': JSON.stringify({ id: 'u-dir', email: 'dir@org.com', platformAdmin: false, accountTier: 'PROJECT_DIRECTOR', totpEnabled: true })
  })
})

test('PlatformAdminPage lists and revokes project managers', async () => {
  const { default: PlatformAdminPage } = await compileVueComponent('../src/PlatformAdminPage.vue', 'platform-mgr-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./ConnectionRequests\\.vue': emptyComponentStub('ConnectionRequests')
  })

  await withDomGlobals(async () => {
    const calls = []
    let managers = [
      { userId: 'u-dir', email: 'dir@org.com', accountTier: 'PROJECT_DIRECTOR', role: 'MANAGER', canIssueApiKeys: true, enabled: true, activeApiKeyCount: 2, createdAt: '2026-11-19T00:00:00Z' }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      calls.push({ url, method, body: init.body ? JSON.parse(init.body) : null })
      if (url === '/api/admin/organizations' && method === 'POST') return okJson({ id: 'org-new' })
      if (url === '/api/admin/platform/overview') return okJson({ organizationCount: 1, userCount: 2, projectCount: 1, providerCount: 0, nodeCount: 0, endpointCount: 0, deploymentCount: 0, serviceCount: 0, apiKeyCount: 2, requestCount: 0 })
      if (url === '/api/admin/platform/organizations') return okJson([{ id: 'org-1', name: 'Calen', status: 'ACTIVE', projectCount: 1, providerCount: 0, nodeCount: 0, memberCount: 2 }])
      if (url === '/api/admin/platform/users') return okJson([])
      if (url === '/api/admin/platform/teams') return okJson([])
      if (url === '/api/admin/platform/api-keys') return okJson([])
      if (url === '/api/admin/platform/account-invitations') return okJson([])
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'proj-1', name: 'Doc Analysis' }])
      if (url === '/api/admin/platform/projects/proj-1/managers' && method === 'GET') return okJson(managers)
      if (url === '/api/admin/platform/projects/proj-1/managers/u-dir' && method === 'DELETE') {
        managers = []
        return noContent()
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setInput } = createMemoryDom()
    const changed = []
    const app = renderer.createApp({ render: () => h(PlatformAdminPage, { auth: { accessToken: 'sys-jwt' }, onOrganizationsChanged: id => changed.push(id) }) })
    try {
      app.mount(root)
      await flush()
      walk(root, el => el.tag === 'button' && textContent(el) === '+ 조직')[0].props.onClick()
      await nextTick()
      const createModal = walk(root, el => el.props?.['data-title'] === '새 조직')[0]
      await setInput(walk(createModal, el => el.tag === 'input')[0], 'New Org')
      walk(createModal, el => el.tag === 'button' && textContent(el) === '조직 생성')[0].props.onClick()
      await flush()
      assert.deepEqual(changed, ['org-new'])
      assert.deepEqual(calls.filter(c => c.url === '/api/admin/organizations' && c.method === 'POST').map(c => c.body), [{ name: 'New Org' }])
      assert.match(textContent(root), /dir@org\.com/)
      walk(root, el => el.tag === 'button' && textContent(el).includes('관리자 권한 회수'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/platform/projects/proj-1/managers/u-dir' && c.method === 'DELETE'))
      assert.match(textContent(root), /배정된 프로젝트 관리자가 없습니다/)
    } finally {
      app.unmount()
    }
  })
})

test('RuntimeTrustControl enforces 4 EXTERNAL_NO_RETENTION metadata fields and supports explicit revoke', async () => {
  const { default: RuntimeTrustControl } = await compileVueComponent('../src/RuntimeTrustControl.vue', 'trust-gov-test')

  await withDomGlobals(async () => {
    const calls = []
    let trustState = { endpointId: 'ep-1', classification: 'UNCLASSIFIED', valid: false, evidence: null, expiresAt: null }
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/platform/runtime-endpoints/ep-1/trust' && method === 'GET') return okJson(trustState)
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Doc Analysis' }])
      if (url === '/api/admin/platform/runtime-endpoints/ep-1/trust' && method === 'PUT') {
        trustState = { endpointId: 'ep-1', ...body, valid: true }
        return okJson(trustState)
      }
      if (url === '/api/admin/platform/runtime-endpoints/ep-1/trust/revoke' && method === 'POST') {
        trustState = { ...trustState, valid: false, revokedAt: '2026-11-20T12:00:00Z' }
        return okJson(trustState)
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setSelect, setInput } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(RuntimeTrustControl, { endpointId: 'ep-1', organizationId: 'org-1', auth: { accessToken: 'sys-jwt' } }) })
    try {
      app.mount(root)
      await flush()

      const classSelect = walk(root, el => el.tag === 'select')[0]
      await setSelect(classSelect, 'EXTERNAL_NO_RETENTION')

      const saveBtn = () => walk(root, el => el.tag === 'button' && textContent(el).includes('분류 저장'))[0]
      const inputs = walk(root, el => el.tag === 'input')
      // [0] evidence, [1] trustExpiry, [2] vendorName, [3] processingLocation, [4] retentionPolicy, [5] approvalScope
      await setInput(inputs[0], 'DPA-2026')
      await setInput(inputs[1], '2026-12-31T23:59')
      assert.equal(saveBtn().props.disabled, true, 'Must stay disabled without all 4 metadata fields')

      await setInput(inputs[2], 'CoreWeave')
      await setInput(inputs[3], 'KR / Seoul')
      await setInput(inputs[4], 'Zero retention')
      await setInput(inputs[5], 'De-identified document summary')
      assert.equal(saveBtn().props.disabled, false)

      saveBtn().props.onClick()
      await flush()
      assert.ok(calls.some(c =>
        c.url === '/api/admin/platform/runtime-endpoints/ep-1/trust'
        && c.method === 'PUT'
        && c.body.vendorName === 'CoreWeave'
        && c.body.processingLocation === 'KR / Seoul'
        && c.body.retentionPolicy === 'Zero retention'
        && c.body.approvalScope === 'De-identified document summary'
      ))

      walk(root, el => el.tag === 'button' && textContent(el).includes('신뢰 분류 철회'))[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/platform/runtime-endpoints/ep-1/trust/revoke' && c.method === 'POST'))
      assert.match(textContent(root), /철회했습니다/)
    } finally {
      app.unmount()
    }
  })
})

test('ExternalProvidersPageImpl warns on legacy cap without currency and sends monthlyCostCurrency & expiresAt', async () => {
  const { default: ExternalProvidersPageImpl } = await compileVueComponent('../src/ExternalProvidersPageImpl.vue', 'ext-gov-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./CapabilityPicker\\.vue': emptyComponentStub('CapabilityPicker'),
    '\\./ModelFeatureSupportManager\\.vue': emptyComponentStub('ModelFeatureSupportManager'),
    '\\./ModelFeatureBadges\\.vue': emptyComponentStub('ModelFeatureBadges')
  })

  await withDomGlobals(async () => {
    const calls = []
    let accesses = [
      { id: 'acc-1', projectId: 'p-1', projectName: 'Doc Analysis', providerId: 'prov-1', providerName: 'OpenAI', status: 'APPROVED', requestedReason: 'backup', manualAllowed: true, autoFailoverEnabled: false, monthlyCostLimit: 100000, monthlyCostCurrency: null, expiresAt: null }
    ]
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/external-providers') return okJson([{ id: 'prov-1', organizationId: 'org-1', providerType: 'OPENAI', displayName: 'OpenAI', baseUrl: 'https://api.openai.com/v1', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true }])
      if (url === '/api/admin/organizations/org-1/projects') return okJson([{ id: 'p-1', name: 'Doc Analysis' }])
      if (url === '/api/admin/organizations/org-1/external-access') return okJson(accesses)
      if (url === '/api/admin/external-providers/prov-1/models') return okJson([])
      if (url === '/api/admin/projects/p-1/external-access/prov-1' && method === 'PATCH') {
        const updated = { ...accesses[0], ...body }
        accesses = [updated]
        return okJson(updated)
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    }

    const { renderer, root, walk, textContent, setSelect, setInput } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(ExternalProvidersPageImpl, { organizationId: 'org-1', auth: { accessToken: 'sys-jwt' }, platformAdmin: true }) })
    try {
      app.mount(root)
      await flush()

      assert.match(textContent(root), /통화 누락 · 차단됨/)
      walk(root, el => el.tag === 'button' && textContent(el).includes('정책 설정'))[0].props.onClick()
      await nextTick()

      const modal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const selects = walk(modal, el => el.tag === 'select')
      // selects: [0] projectId, [1] providerId, [2] status, [3] monthlyCostCurrency
      await setSelect(selects[3], 'USD')
      const numberInput = walk(modal, el => el.tag === 'input' && el.props.type === 'number')[0]
      await setInput(numberInput, '250')
      const dateInput = walk(modal, el => el.tag === 'input' && el.props.type === 'datetime-local')[0]
      await setInput(dateInput, '2026-12-31T23:59')

      walk(modal, el => el.tag === 'button' && textContent(el).includes('권한 저장'))[0].props.onClick()
      await flush()

      assert.ok(calls.some(c =>
        c.url === '/api/admin/projects/p-1/external-access/prov-1'
        && c.method === 'PATCH'
        && c.body.monthlyCostLimit === 250
        && c.body.monthlyCostCurrency === 'USD'
        && Boolean(c.body.expiresAt)
      ))
    } finally {
      app.unmount()
    }
  })
})

test('ServicesPage sets dataBoundaryPolicy and DeveloperPortalPage enforces canIssueApiKeys & clamped key boundary', async () => {
  const { default: ServicesPage } = await compileVueComponent('../src/ServicesPage.vue', 'services-gov-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./CapabilityPicker\\.vue': emptyComponentStub('CapabilityPicker'),
    '\\./ServiceSchedulingPanel\\.vue': emptyComponentStub('ServiceSchedulingPanel')
  })
  const { default: DeveloperPortalPage } = await compileVueComponent('../src/DeveloperPortalPage.vue', 'portal-gov-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./IssuedConnectionGuide\\.vue': emptyComponentStub('IssuedConnectionGuide')
  })

  // 1. ServicesPage
  await withDomGlobals(async () => {
    const calls = []
    let svc = { id: 'svc-1', organizationId: 'org-1', serviceKey: 'doc-summary', displayName: 'Doc Summary', failoverPolicy: 'STRICT', retryPolicy: 'SAFE', allowDegraded: false, dataBoundaryPolicy: 'EXTERNAL_ALLOWED', requiredCapabilitiesJson: '[]', inputPricePerMillion: 0, outputPricePerMillion: 0, currency: 'KRW', enabled: true }
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/admin/organizations/org-1/services') return okJson([svc])
      if (url === '/api/admin/services/svc-1/targets') return okJson([])
      if (url === '/api/admin/organizations/org-1/runtime-endpoints') return okJson([])
      if (url === '/api/admin/organizations/org-1/external-providers') return okJson([])
      if (url === '/api/admin/services/svc-1' && method === 'PATCH') {
        svc = { ...svc, ...body }
        return okJson(svc)
      }
      throw new Error(`Unexpected fetch in ServicesPage: ${method} ${url}`)
    }
    const { renderer, root, walk, textContent, setSelect } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(ServicesPage, { organizationId: 'org-1', auth: { accessToken: 'sys-jwt' } }) })
    try {
      app.mount(root)
      await flush()
      walk(root, el => el.tag === 'button' && textContent(el).includes('정책 편집'))[0].props.onClick()
      await nextTick()
      const modal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const boundarySelect = walk(modal, el => el.tag === 'select')[2]
      await setSelect(boundarySelect, 'INTERNAL_ONLY')
      walk(modal, el => el.tag === 'button' && textContent(el).trim() === '저장')[0].props.onClick()
      await flush()
      assert.ok(calls.some(c => c.url === '/api/admin/services/svc-1' && c.method === 'PATCH' && c.body.dataBoundaryPolicy === 'INTERNAL_ONLY'))
    } finally {
      app.unmount()
    }
  })

  // 2. DeveloperPortalPage
  await withDomGlobals(async () => {
    const calls = []
    globalThis.fetch = async (url, init = {}) => {
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(init.body) : null
      calls.push({ url, method, body })
      if (url === '/api/portal/connection') return okJson({ endpoints: [{ scope: 'INTERNAL', label: 'VPN', url: 'https://internal/v1' }] })
      if (url === '/api/portal/organizations/org-1/projects') {
        return okJson([
          { id: 'p-ro', name: 'Readonly Project', status: 'ACTIVE', canIssueApiKeys: false, dataBoundaryPolicy: 'INTERNAL_ONLY', maxDataBoundaryPolicy: 'INTERNAL_ONLY', effectiveDataBoundaryPolicy: 'INTERNAL_ONLY', services: [] },
          { id: 'p-rw', name: 'Delegated Project', status: 'ACTIVE', canIssueApiKeys: true, dataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', maxDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', effectiveDataBoundaryPolicy: 'APPROVED_EXTERNAL_NO_RETENTION', services: [] }
        ])
      }
      if (url.includes('/api-keys') && method === 'GET') return okJson([])
      if (url.includes('/external-access')) return okJson([])
      if (url === '/api/portal/projects/p-rw/api-keys' && method === 'POST') return okJson({ secret: 'sk_portal_1' })
      throw new Error(`Unexpected fetch in DeveloperPortalPage: ${method} ${url}`)
    }
    const { renderer, root, walk, textContent, setSelect, setInput } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(DeveloperPortalPage, { organizationId: 'org-1', auth: { accessToken: 'gen-jwt' } }) })
    try {
      app.mount(root)
      await flush()

      assert.match(textContent(root), /API 키 발급 권한이 위임되지 않았습니다/)
      assert.equal(walk(root, el => el.tag === 'button' && textContent(el).includes('API 키 발급')).length, 0)

      // Switch to delegated project p-rw
      const projectSelect = walk(root, el => el.tag === 'select')[0]
      await setSelect(projectSelect, 'p-rw')
      await flush()

      walk(root, el => el.tag === 'button' && textContent(el).includes('+ API 키 발급'))[0].props.onClick()
      await nextTick()
      const modal = walk(root, el => el.props?.class === 'modal-stub')[0]
      const keyBoundarySelect = walk(modal, el => el.tag === 'select')[0]
      assert.equal(keyBoundarySelect.options.find(opt => opt.value === 'EXTERNAL_ALLOWED').props.disabled, true)
      await setSelect(keyBoundarySelect, 'INTERNAL_ONLY')
      await setInput(walk(modal, el => el.tag === 'input')[0], 'my-scoped-key')
      walk(modal, el => el.tag === 'button' && textContent(el).includes('키 발급'))[0].props.onClick()
      await flush()

      assert.ok(calls.some(c => c.url === '/api/portal/projects/p-rw/api-keys' && c.method === 'POST' && c.body.dataBoundaryPolicy === 'INTERNAL_ONLY'))
    } finally {
      app.unmount()
    }
  })
})

test('relationship renders top tier, organization, managers, members and compacts large lists', async () => {
  const { default: Relationship } = await compileVueComponent('../src/WorkspaceRelationship.vue', 'relationship-hierarchy-test')
  await withDomGlobals(async () => {
    const calls = []
    const payload = {
      organizationId: 'org-1',
      organizationName: 'Calen-Ledger-Project',
      topUsers: [
        { userId: 'u-admin', email: 'admin@aiconnect.test', accountTier: 'SYSTEM_ADMIN', role: 'SYSTEM_ADMIN', roleLabel: '시스템 관리자', canIssueApiKeys: true, currentUser: true }
      ],
      projects: [
        {
          id: 'p-1',
          name: 'calen-ledger-ai',
          status: 'ACTIVE',
          canIssueApiKeys: true,
          managers: [
            { userId: 'u-dir-1', email: 'director@aiconnect.test', accountTier: 'PROJECT_DIRECTOR', role: 'MANAGER', roleLabel: '프로젝트 총관리자', canIssueApiKeys: true, currentUser: false }
          ],
          members: [
            { userId: 'u-m-1', email: 'member1@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: true, currentUser: false },
            { userId: 'u-m-2', email: 'member2@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: false, currentUser: false },
            { userId: 'u-m-3', email: 'member3@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: true, currentUser: false },
            { userId: 'u-m-4', email: 'member4@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: false, currentUser: false },
            { userId: 'u-m-5', email: 'member5@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: true, currentUser: false }
          ]
        },
        { id: 'p-2', name: 'project-two', status: 'ACTIVE', canIssueApiKeys: true, managers: [], members: [] },
        { id: 'p-3', name: 'project-three', status: 'ACTIVE', canIssueApiKeys: true, managers: [], members: [] },
        { id: 'p-4', name: 'project-four', status: 'ACTIVE', canIssueApiKeys: true, managers: [], members: [] },
        { id: 'p-5', name: 'project-five', status: 'ACTIVE', canIssueApiKeys: false, managers: [], members: [] }
      ]
    }
    globalThis.fetch = async url => {
      calls.push(String(url))
      return okJson(payload)
    }
    const { renderer, root, walk, textContent } = createMemoryDom()
    const app = renderer.createApp({ render: () => h(Relationship, { organizationId: 'org-1', organizationName: 'Calen-Ledger-Project', auth: { accessToken: 'token' } }) })
    try {
      app.mount(root)
      await flush()

      assert.ok(calls.some(url => url === '/api/portal/organizations/org-1/relationship'))
      assert.equal(walk(root, el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('org-chart-tree')).length, 1)
      assert.ok(walk(root, el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('org-branch-col')).length >= 4)
      const orgEnclosure = walk(root, el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('org-scope-enclosure'))
      assert.equal(orgEnclosure.length, 1)
      const projEnclosures = walk(orgEnclosure[0], el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('project-scope-enclosure'))
      assert.equal(projEnclosures.length, 4)
      assert.equal(walk(projEnclosures[0], el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('org-manager-section')).length, 1)
      assert.equal(walk(projEnclosures[0], el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('org-member-section')).length, 1)
      const rendered = textContent(root)
      assert.match(rendered, /1단계/)
      assert.match(rendered, /2단계/)
      assert.match(rendered, /3단계/)
      assert.match(rendered, /4단계/)
      assert.match(rendered, /5단계/)
      assert.match(rendered, /admin@aiconnect\.test/)
      assert.match(rendered, /시스템 관리자/)
      assert.match(rendered, /현재 계정/)
      assert.match(rendered, /Calen-Ledger-Project/)
      assert.match(rendered, /calen-ledger-ai/)
      assert.match(rendered, /director@aiconnect\.test/)
      assert.match(rendered, /프로젝트 총관리자/)
      assert.match(rendered, /member1@aiconnect\.test/)
      assert.match(rendered, /member3@aiconnect\.test/)
      assert.doesNotMatch(rendered, /member4@aiconnect\.test/)
      assert.doesNotMatch(rendered, /project-five/)
      assert.match(rendered, /\+ 외 2명 팀원 더보기/)
      assert.match(rendered, /\+ 외 1개 프로젝트 더보기/)

      const expandMembersBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('팀원 더보기'))[0]
      expandMembersBtn.props.onClick()
      await nextTick()
      assert.match(textContent(root), /member4@aiconnect\.test/)
      assert.match(textContent(root), /member5@aiconnect\.test/)

      const expandAllBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('전체 펼치기'))[0]
      expandAllBtn.props.onClick()
      await nextTick()
      assert.match(textContent(root), /project-five/)
      assert.match(textContent(root), /축약 보기/)

      const collapseAllBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('축약 보기'))[0]
      collapseAllBtn.props.onClick()
      await nextTick()
      assert.doesNotMatch(textContent(root), /member4@aiconnect\.test/)
      assert.doesNotMatch(textContent(root), /project-five/)
    } finally {
      app.unmount()
    }
  })
})

test('relationship supports team chip, search/filter modes, compact density, and project navigation', async () => {
  const { default: Relationship } = await compileVueComponent('../src/WorkspaceRelationship.vue', 'relationship-expansion-test')
  await withDomGlobals(async () => {
    const opened = []
    globalThis.fetch = async () => okJson({
      organizationId: 'org-1',
      organizationName: 'Calen-Ledger-Project',
      topUsers: [
        { userId: 'u-admin', email: 'admin@aiconnect.test', accountTier: 'SYSTEM_ADMIN', role: 'SYSTEM_ADMIN', roleLabel: '시스템 관리자', canIssueApiKeys: true, currentUser: false }
      ],
      projects: [
        {
          id: 'p-1',
          name: 'calen-ledger-ai',
          teamName: 'Core Platform Team',
          status: 'ACTIVE',
          canIssueApiKeys: true,
          managers: [
            { userId: 'u-dir', email: 'director@aiconnect.test', accountTier: 'PROJECT_DIRECTOR', role: 'MANAGER', roleLabel: '프로젝트 총관리자', canIssueApiKeys: true, currentUser: true }
          ],
          members: [
            { userId: 'u-dev', email: 'alice@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: true, currentUser: false }
          ]
        },
        {
          id: 'p-2',
          name: 'orphan-sandbox',
          teamName: null,
          status: 'ACTIVE',
          canIssueApiKeys: false,
          managers: [],
          members: [
            { userId: 'u-bob', email: 'bob@aiconnect.test', accountTier: 'GENERAL_USER', role: 'MEMBER', roleLabel: '팀원', canIssueApiKeys: false, currentUser: false }
          ]
        }
      ]
    })

    const { renderer, root, walk, textContent, setInput } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(Relationship, {
        organizationId: 'org-1',
        organizationName: 'Calen-Ledger-Project',
        auth: { accessToken: 'token' },
        canNavigateProject: true,
        onOpenProject: id => opened.push(id)
      })
    })
    try {
      app.mount(root)
      await flush()

      assert.match(textContent(root), /팀: Core Platform Team/)

      // Project navigation button emits project id
      const openBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('프로젝트 열기'))[0]
      assert.ok(openBtn)
      openBtn.props.onClick()
      assert.deepEqual(opened, ['p-1'])

      // Compact mode toggles .compact class on .org-chart-tree
      const compactBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('컴팩트 보기'))[0]
      compactBtn.props.onClick()
      await nextTick()
      assert.equal(walk(root, el => typeof el.props?.class === 'string' && el.props.class.split(' ').includes('compact')).length, 1)

      // Filter: 내 소속만 shows only p-1
      walk(root, el => el.tag === 'button' && textContent(el).includes('내 소속만'))[0].props.onClick()
      await nextTick()
      assert.match(textContent(root), /calen-ledger-ai/)
      assert.doesNotMatch(textContent(root), /orphan-sandbox/)

      // Filter: 총관리자 미배정 shows only p-2
      walk(root, el => el.tag === 'button' && textContent(el).includes('총관리자 미배정'))[0].props.onClick()
      await nextTick()
      assert.doesNotMatch(textContent(root), /calen-ledger-ai/)
      assert.match(textContent(root), /orphan-sandbox/)

      // Back to 전체 and search by member email
      walk(root, el => el.tag === 'button' && textContent(el).includes('전체 ('))[0].props.onClick()
      await nextTick()
      const searchInput = walk(root, el => el.tag === 'input' && el.props['aria-label'] === '관계도 검색')[0]
      await setInput(searchInput, 'alice@aiconnect.test')
      await nextTick()
      assert.match(textContent(root), /calen-ledger-ai/)
      assert.doesNotMatch(textContent(root), /orphan-sandbox/)
    } finally {
      app.unmount()
    }
  })
})

test('ServicesPage visual pipeline configures REDACT_RESTORE, ZDR masker, 80:20 A/B weights, and runs simulator', async () => {
  const { default: ServicesPage } = await compileVueComponent('../src/ServicesPage.vue', 'services-pipeline-test', {
    '\\./BaseModal\\.vue': baseModalStubUrl,
    '\\./CapabilityPicker\\.vue': emptyComponentStub('CapabilityPicker'),
    '\\./ServiceSchedulingPanel\\.vue': emptyComponentStub('ServiceSchedulingPanel')
  })

  await withDomGlobals(async () => {
    let currentService = {
      id: 'svc-1',
      organizationId: 'org-1',
      serviceKey: 'support-ai',
      displayName: 'Support AI',
      failoverPolicy: 'COMPATIBLE',
      retryPolicy: 'SAFE',
      allowDegraded: false,
      dataBoundaryPolicy: 'EXTERNAL_ALLOWED',
      requiredCapabilitiesJson: '[]',
      inputPricePerMillion: 0,
      outputPricePerMillion: 0,
      currency: 'KRW',
      enabled: true,
      temperaturePolicy: 'REQUEST',
      temperatureValue: null,
      reasoningEffort: 'REQUEST',
      openAiFastMode: false,
      maskerDeploymentId: null,
      maskPersonNames: true,
      guardrailAction: 'ALLOW',
      systemPromptTemplate: '',
      promptVersion: 1,
      responseCacheEnabled: false,
      responseCacheTtlSeconds: 300
    }
    let currentTargets = [
      { id: 't-1', deploymentId: 'dep-local', priority: 1, weight: 50, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true },
      { id: 't-2', deploymentId: 'dep-ext-zdr', priority: 1, weight: 50, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true }
    ]
    const calls = []

    globalThis.fetch = async (url, init = {}) => {
      const path = new URL(String(url), 'https://console.example.com').pathname
      const method = (init.method ?? 'GET').toUpperCase()
      const body = init.body ? JSON.parse(String(init.body)) : null
      calls.push({ method, path, body })

      if (method === 'GET' && path === '/api/admin/organizations/org-1/services') return okJson([currentService])
      if (method === 'GET' && path === '/api/admin/services/svc-1/targets') return okJson(currentTargets)
      if (method === 'GET' && path === '/api/admin/organizations/org-1/runtime-endpoints') {
        return okJson([{ id: 'ep-local', displayName: '사내 Ollama', baseUrl: 'http://127.0.0.1:11434/v1', enabled: true }])
      }
      if (method === 'GET' && path === '/api/admin/runtime-endpoints/ep-local/deployments') {
        return okJson([{ id: 'dep-local', displayName: 'Qwen3-Local', providerModelId: 'qwen3:14b', enabled: true }])
      }
      if (method === 'GET' && path === '/api/admin/organizations/org-1/external-providers') {
        return okJson([
          { id: 'prov-zdr', displayName: 'OpenAI ZDR', zeroRetentionVerified: true, zeroRetentionEvidence: 'Enterprise ZDR DPA', enabled: true },
          { id: 'prov-raw', displayName: 'Public API', zeroRetentionVerified: false, enabled: true }
        ])
      }
      if (method === 'GET' && path === '/api/admin/external-providers/prov-zdr/models') {
        return okJson([{ id: 'dep-ext-zdr', displayName: 'GPT-ZDR', providerModelId: 'gpt-4o-zdr', enabled: true }])
      }
      if (method === 'GET' && path === '/api/admin/external-providers/prov-raw/models') {
        return okJson([{ id: 'dep-ext-raw', displayName: 'Public-GPT', providerModelId: 'gpt-4o-mini', enabled: true }])
      }
      if (method === 'PATCH' && path === '/api/admin/services/svc-1') {
        currentService = {
          ...currentService,
          guardrailAction: body.guardrailAction,
          maskerDeploymentId: body.maskerDeploymentId,
          maskPersonNames: body.maskPersonNames,
          systemPromptTemplate: body.systemPromptTemplate,
          promptVersion: body.systemPromptTemplate ? 2 : 1,
          responseCacheEnabled: body.responseCacheEnabled,
          responseCacheTtlSeconds: body.responseCacheTtlSeconds
        }
        return okJson(currentService)
      }
      if (method === 'PATCH' && path.startsWith('/api/admin/services/svc-1/targets/')) {
        const id = path.split('/').pop()
        currentTargets = currentTargets.map(t => (t.id === id ? { ...t, priority: body.priority, weight: body.weight } : t))
        return okJson(currentTargets.find(t => t.id === id))
      }
      if (method === 'POST' && path === '/api/admin/services/svc-1/pipeline-preview') {
        return okJson({
          originalText: body.sampleText,
          outboundTextForExternalLlm: '김가명 123456-7890123 , 010-0000-0000 어제 학교를 갔다',
          simulatedExternalLlmReply: '확인된 내용: 김가명 (123456-7890123, 010-0000-0000) 어제 학교를 갔다',
          finalUserResponse: '확인된 내용: 홍길동 (965034-3231111, 01012344321) 어제 학교를 갔다',
          action: body.externalAction,
          externalAllowed: true,
          restoreOnReturn: body.externalAction === 'REDACT_RESTORE',
          maskerUsed: 'EXTERNAL_ZERO_RETENTION:GPT-ZDR',
          detectedClassifications: ['PII'],
          redactions: [
            { category: 'PII_NAME', original: '홍길동', surrogate: '김가명', source: 'REGEX' },
            { category: 'PII_RESIDENT_NUMBER', original: '965034-3231111', surrogate: '123456-7890123', source: 'REGEX' },
            { category: 'PII_PHONE', original: '01012344321', surrogate: '010-0000-0000', source: 'REGEX' }
          ],
          promptVersion: 2,
          responseCacheEnabled: true
        })
      }
      throw new Error(`Unexpected fetch: ${method} ${path}`)
    }

    const { renderer, root, walk, textContent, setSelect, setInput, setCheckbox } = createMemoryDom()
    const app = renderer.createApp({
      render: () => h(ServicesPage, { organizationId: 'org-1', auth: { accessToken: 'token' } })
    })
    try {
      app.mount(root)
      await flush()

      // Unverified external model is excluded from eligible masker <select>
      const maskerSelect = walk(root, el => el.tag === 'select' && el.props['aria-label'] === '1차 마스킹 AI 워커')[0]
      assert.ok(maskerSelect)
      assert.equal(maskerSelect.options.some(opt => opt.value === 'dep-ext-raw'), false)
      assert.equal(maskerSelect.options.some(opt => opt.value === 'dep-ext-zdr'), true)

      const policySelect = walk(root, el => el.tag === 'select' && el.props['aria-label'] === '가드레일 처리 정책')[0]
      await setSelect(policySelect, 'REDACT_RESTORE')
      await setSelect(maskerSelect, 'dep-ext-zdr')

      const promptBox = walk(root, el => el.tag === 'textarea' && !el.props['aria-label'])[0]
      await setInput(promptBox, '항상 한국어로 3줄 요약하세요.')

      const cacheCheckbox = walk(root, el => el.tag === 'input' && el.props.type === 'checkbox')[0]
      await setCheckbox(cacheCheckbox, true)

      const savePipelineBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('파이프라인 저장'))[0]
      savePipelineBtn.props.onClick()
      await flush()

      const patchSvc = calls.find(c => c.method === 'PATCH' && c.path === '/api/admin/services/svc-1')
      assert.ok(patchSvc)
      assert.equal(patchSvc.body.guardrailAction, 'REDACT_RESTORE')
      assert.equal(patchSvc.body.maskerDeploymentId, 'dep-ext-zdr')
      assert.equal(patchSvc.body.responseCacheEnabled, true)
      assert.match(textContent(root), /Prompt v2/)

      const ab8020Btn = walk(root, el => el.tag === 'button' && textContent(el).trim() === '80% : 20%')[0]
      assert.ok(ab8020Btn)
      ab8020Btn.props.onClick()
      await flush()

      const t1Patch = calls.find(c => c.method === 'PATCH' && c.path === '/api/admin/services/svc-1/targets/t-1')
      const t2Patch = calls.find(c => c.method === 'PATCH' && c.path === '/api/admin/services/svc-1/targets/t-2')
      assert.equal(t1Patch?.body?.weight, 80)
      assert.equal(t2Patch?.body?.weight, 20)

      const simBtn = walk(root, el => el.tag === 'button' && textContent(el).includes('가드레일 시뮬레이션 실행'))[0]
      simBtn.props.onClick()
      await flush()

      const finalText = textContent(root)
      assert.match(finalText, /123456-7890123/)
      assert.match(finalText, /010-0000-0000/)
      assert.match(finalText, /965034-3231111/)
      assert.match(finalText, /원본 복원됨/)
    } finally {
      app.unmount()
    }
  })
})
