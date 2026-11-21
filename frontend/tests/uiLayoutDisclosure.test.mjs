import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, defineComponent } from 'vue'
import ts from 'typescript'

function readSrc(file) {
  return readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
}

async function compileGuide() {
  const source = readSrc('IssuedConnectionGuide.vue')
  const { descriptor } = parse(source)
  let script = ts.transpileModule(
    compileScript(descriptor, { id: 'guide-disclosure', inlineTemplate: true }).content,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }
  ).outputText
  script = script
    .replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
    .replace(/from ['"]\.\/clipboard['"]/g, 'from ' + JSON.stringify('data:text/javascript,export async function copyText(){}'))
  return (await import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))).default
}

async function compileFeatureManager() {
  const source = readSrc('ModelFeatureSupportManager.vue')
  const { descriptor } = parse(source)
  let script = ts.transpileModule(
    compileScript(descriptor, { id: 'feature-disclosure', inlineTemplate: true }).content,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }
  ).outputText
  const badgesStub = 'data:text/javascript,' + encodeURIComponent(
    `import { h, defineComponent } from ${JSON.stringify(import.meta.resolve('vue'))}; export default defineComponent({ props: ['capabilities','featureSupportJson','reasoningEffort','serviceTier'], render() { return h('span', { class: 'badges-stub' }, 'BADGES') } })`
  )
  script = script
    .replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
    .replace(/from ['"]\.\/api['"]/g, 'from ' + JSON.stringify('data:text/javascript,export async function adminFetch(){return {}}'))
    .replace(/from ['"]\.\/ModelFeatureBadges\.vue['"]/g, 'from ' + JSON.stringify(badgesStub))
  return (await import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))).default
}

function renderComponent(Component, props) {
  const makeNode = tag => {
    const el = {
      tag,
      children: [],
      props: {},
      addEventListener: () => {},
      removeEventListener: () => {},
      get options() { return el.children.filter(child => child.tag === 'option') }
    }
    return el
  }
  const renderer = createRenderer({
    createElement: makeNode,
    createText: text => ({ ...makeNode('#text'), text }),
    createComment: text => ({ ...makeNode('#comment'), text }),
    setText: (node, text) => { node.text = text },
    setElementText: (node, text) => { node.text = text; node.children = [] },
    patchProp: (node, key, _old, value) => {
      node.props[key] = value
      if (key === 'value') node.value = value
    },
    insert: (node, parent) => { parent.children.push(node) },
    remove: () => {},
    parentNode: () => null,
    nextSibling: () => null
  })
  const root = makeNode('root')
  renderer.createApp({ render: () => h(Component, props) }).mount(root)
  const walk = (node, pred) => [
    ...(pred(node) ? [node] : []),
    ...(node.children ?? []).flatMap(child => walk(child, pred))
  ]
  const textOf = node => (node.text ?? '') + (node.children ?? []).map(textOf).join('')
  return { root, walk, textOf }
}

test('IssuedConnectionGuide keeps SDK Base URLs visible and tucks Calen paths inside closed details', async () => {
  const Guide = await compileGuide()
  const { root, walk, textOf } = renderComponent(Guide, {
    endpoints: [
      { scope: 'INTERNAL', label: 'Internal', url: 'http://100.100.9.74/v1' },
      { scope: 'EXTERNAL', label: 'External', url: 'https://api.aiconnectgateway.com/v1' }
    ],
    models: [],
    hideModels: true,
    embedded: true
  })

  assert.equal(walk(root, el => el.tag === 'h3').length, 0, 'embedded mode skips duplicate h3 header')
  const detailsList = walk(root, el => el.tag === 'details')
  assert.equal(detailsList.length, 1)
  assert.ok(!detailsList[0].props.open, 'Calen/path details must default closed')

  const endpointGrid = walk(root, el => el.tag === 'div' && el.props.class === 'endpoint-grid')[0]
  assert.ok(endpointGrid)
  assert.match(textOf(endpointGrid), /http:\/\/100\.100\.9\.74\/v1/)
  assert.match(textOf(endpointGrid), /https:\/\/api\.aiconnectgateway\.com\/v1/)
  assert.doesNotMatch(textOf(endpointGrid), /Chat Completions path/)

  const detailsText = textOf(detailsList[0])
  assert.match(detailsText, /Calen OpenAI API 주소/)
  assert.match(detailsText, /\/v1\/chat\/completions/)
  assert.match(detailsText, /\/v1\/models/)
})

test('ModelFeatureSupportManager defaults to closed details while summarizing active model in summary', async () => {
  const Manager = await compileFeatureManager()
  const { root, walk, textOf } = renderComponent(Manager, {
    providerId: 'prov-1',
    models: [
      {
        id: 'm-1',
        providerModelId: 'models/gemini-3.8-flash',
        displayName: 'gemini-3.8-flash',
        capabilitiesJson: '["VISION","STRUCTURED_OUTPUT"]'
      }
    ],
    auth: { accessToken: 'jwt' }
  })

  const details = walk(root, el => el.tag === 'details')[0]
  assert.ok(details)
  assert.ok(!details.props.open, 'ModelFeatureSupportManager should start collapsed')

  const summary = walk(details, el => el.tag === 'summary')[0]
  assert.ok(summary)
  assert.match(textOf(summary), /MODEL FEATURE SUPPORT/)
  assert.match(textOf(summary), /gemini-3\.8-flash/)
  assert.match(textOf(summary), /BADGES/)

  const selects = walk(details, el => el.tag === 'select')
  assert.equal(selects.length, 7, 'Model selector + 6 capability dropdowns stay mounted inside details')
})

test('ExternalProvidersPageImpl prioritizes provider workspace over collapsible feature manager and fixes stretch/min-height', () => {
  const page = readSrc('ExternalProvidersPageImpl.vue')
  const layoutIdx = page.indexOf('<div class="external-layout">')
  const managerIdx = page.indexOf('<ModelFeatureSupportManager')
  assert.ok(layoutIdx > -1 && managerIdx > layoutIdx, 'Provider list/detail must appear before ModelFeatureSupportManager')
  assert.match(page, /\.external-layout\{[^}]*align-items:start/)
  assert.match(page, /\.provider-list,\.provider-detail\{min-height:0\}/)
  assert.doesNotMatch(page, /\.provider-list,\.provider-detail\{min-height:460px\}/)
})

test('DeveloperPortalPage groups AVAILABLE MODELS and EXTERNAL AI ACCESS in left stack with align-items: start', () => {
  const page = readSrc('DeveloperPortalPage.vue')
  assert.match(page, /<div class="developer-left-stack">[\s\S]*AVAILABLE MODELS[\s\S]*EXTERNAL AI ACCESS[\s\S]*<\/div>[\s\S]*CONNECT/)
  assert.match(page, /embedded\s*\/>/)
  assert.match(page, /\.developer-summary-grid\s*\{[^}]*align-items:\s*start/)
})

test('PlatformAdminPage and TeamsPage place page-hero before ConnectionRequests and fold secondary inline forms', () => {
  const platform = readSrc('PlatformAdminPage.vue')
  assert.ok(platform.indexOf('<div class="page-hero">') < platform.indexOf('<ConnectionRequests'))
  assert.match(platform, /<details[^>]*invite-issuer-disclosure/)

  const teams = readSrc('TeamsPage.vue')
  assert.ok(teams.indexOf('<div class="page-hero">') < teams.indexOf('<ConnectionRequests'))
  assert.match(teams, /\.team-layout\s*\{[^}]*align-items:\s*start/)
  assert.match(teams, /\.team-list-card,\.team-detail-card\{min-height:0\}/)

  const conn = readSrc('ConnectionRequests.vue')
  assert.match(conn, /<details[^>]*connection-request-form/)

  const trust = readSrc('RuntimeTrustControl.vue')
  assert.match(trust, /<details[^>]*trust-disclosure/)

  const scheduling = readSrc('ServiceSchedulingPanel.vue')
  assert.match(scheduling, /<details[^>]*collapsible-subform/)

  const beta = readSrc('BetaOperatorPage.vue')
  assert.match(beta, /<details[^>]*beta-invite-panel[^>]*:open="Boolean\(token\)"/)
  assert.match(beta, /class="beta-two-col"/)

  const ops = readSrc('ModelOperationsPanel.vue')
  assert.doesNotMatch(ops, /<details class="advanced-options" open>/)
  assert.match(ops, /\.operation-grid\s*\{[^}]*align-items:\s*start/)

  const dlp = readSrc('DataProtectionPage.vue')
  assert.match(dlp, /<details[^>]*custom-pattern-disclosure/)

  const playground = readSrc('ApiPlaygroundPage.vue')
  assert.match(playground, /<details[^>]*sdk-address-disclosure/)

  const css = readSrc('style.css')
  assert.match(css, /\.split-layout\s*\{[^}]*align-items:\s*start/)
  assert.match(css, /\.dashboard-grid\s*\{[^}]*align-items:\s*start/)
  assert.match(css, /\.collapsible-panel/)
  assert.match(css, /\.collapsible-subform/)
})
