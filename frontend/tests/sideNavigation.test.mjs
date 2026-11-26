import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, ref } from 'vue'
import ts from 'typescript'
import { loadTypeScript, typeScriptModuleUrl } from './loadTypeScript.mjs'

const nav = await loadTypeScript('../src/consoleNavigation.ts')
const ids = items => items.map(item => item.id)
const sectionIds = sections => ({
  pinned: ids(sections.pinned),
  categories: sections.categories.map(category => [category.id, ids(category.items)])
})
const ORG_ONLY = ['infrastructure', 'system', 'external', 'services', 'flow-designer', 'teams', 'notifications', 'audit', 'model-playground']

/* ---------------- 1. information architecture ---------------- */

test('every navigable page maps to exactly one known category; only dashboard is pinned', () => {
  const categoryIds = nav.NAV_CATEGORIES.map(category => category.id)
  assert.deepEqual(categoryIds, ['models', 'access', 'observe', 'devtools', 'platform'])
  const all = [...nav.adminNavItems, ...nav.directorNavItems, ...nav.developerNavItems, nav.platformNavItem, nav.betaNavItem]
  for (const item of all) {
    const category = nav.categoryOf(item.id)
    if (item.id === 'dashboard') assert.equal(category, null)
    else assert.ok(categoryIds.includes(category), `${item.id} has a category`)
  }
  assert.deepEqual(Object.keys(nav.PAGE_CATEGORY).sort(), [...new Set(ids(all))].sort())
  assert.equal(nav.categoryOf('docs'), null)
  assert.equal(nav.categoryOf('unknown-page'), null)
  assert.equal(nav.categoryOf('constructor'), null, 'prototype keys are not categories')
})

test('role lists keep the pre-redesign access boundaries and have no duplicates', () => {
  assert.deepEqual(ids(nav.adminNavItems).sort(), ['audit', 'dashboard', 'data-protection', 'external', 'flow-designer', 'infrastructure', 'model-playground', 'notifications', 'observability', 'playground', 'portal', 'projects', 'quotas', 'relationship', 'services', 'system', 'teams', 'usage'])
  assert.deepEqual(ids(nav.directorNavItems).sort(), ['dashboard', 'data-protection', 'observability', 'playground', 'portal', 'projects', 'quotas', 'relationship', 'usage'])
  assert.deepEqual(ids(nav.developerNavItems).sort(), ['dashboard', 'observability', 'playground', 'portal', 'quotas', 'relationship', 'usage'])
  for (const list of [nav.adminNavItems, nav.directorNavItems, nav.developerNavItems]) {
    assert.equal(new Set(ids(list)).size, list.length)
    assert.equal(list[0].id, 'dashboard', 'dashboard stays first (currentNav fallback)')
  }
  for (const id of ORG_ONLY) {
    assert.equal(ids(nav.directorNavItems).includes(id), false, `director must not see ${id}`)
    assert.equal(ids(nav.developerNavItems).includes(id), false, `developer must not see ${id}`)
  }
  assert.equal(ids(nav.developerNavItems).includes('projects'), false)
  assert.equal(ids(nav.developerNavItems).includes('data-protection'), false)
})

test('navItemsFor adds Platform Admin only for platform admins and beta for platform admins or beta operators', () => {
  const run = (role, platformAdmin, betaOperator) => ids(nav.navItemsFor({ role, platformAdmin, betaOperator }))
  assert.deepEqual(run('admin', true, false), [...ids(nav.adminNavItems), 'platform', 'beta'])
  assert.deepEqual(run('admin', false, false), ids(nav.adminNavItems))
  assert.deepEqual(run('admin', false, true), [...ids(nav.adminNavItems), 'beta'])
  assert.deepEqual(run('director', false, false), ids(nav.directorNavItems))
  assert.deepEqual(run('director', false, true), [...ids(nav.directorNavItems), 'beta'])
  assert.deepEqual(run('developer', false, false), ids(nav.developerNavItems))
  assert.deepEqual(run('developer', false, true), [...ids(nav.developerNavItems), 'beta'])
  assert.equal(run('director', true, false).includes('platform'), false, 'platform item is admin-only')
})

test('buildNavSections: platform admin gets pinned dashboard + 5 ordered categories', () => {
  const sections = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }))
  assert.deepEqual(sectionIds(sections), {
    pinned: ['dashboard'],
    categories: [
      ['models', ['infrastructure', 'external', 'services', 'flow-designer', 'system']],
      ['access', ['projects', 'teams', 'relationship', 'data-protection', 'audit']],
      ['observe', ['observability', 'usage', 'quotas', 'notifications']],
      ['devtools', ['portal', 'playground', 'model-playground']],
      ['platform', ['platform', 'beta']]
    ]
  })
  assert.deepEqual(sections.categories.map(category => category.label), ['모델·라우팅', '조직·보안', '관측·비용', '개발자 도구', '플랫폼 운영'])
  const orgAdmin = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: false, betaOperator: false }))
  assert.deepEqual(orgAdmin.categories.map(category => category.id), ['models', 'access', 'observe', 'devtools'])
})

test('buildNavSections: director and developer omit empty categories', () => {
  assert.deepEqual(sectionIds(nav.buildNavSections(nav.navItemsFor({ role: 'director', platformAdmin: false, betaOperator: false }))), {
    pinned: ['dashboard'],
    categories: [
      ['access', ['projects', 'relationship', 'data-protection']],
      ['observe', ['observability', 'usage', 'quotas']],
      ['devtools', ['portal', 'playground']]
    ]
  })
  assert.deepEqual(sectionIds(nav.buildNavSections(nav.navItemsFor({ role: 'developer', platformAdmin: false, betaOperator: true }))), {
    pinned: ['dashboard'],
    categories: [
      ['access', ['relationship']],
      ['observe', ['observability', 'usage', 'quotas']],
      ['devtools', ['portal', 'playground']],
      ['platform', ['beta']]
    ]
  })
})

test('category labels never collide with page labels (labels stay unique search targets)', () => {
  const pageLabels = [...nav.adminNavItems, ...nav.directorNavItems, ...nav.developerNavItems, nav.platformNavItem, nav.betaNavItem].map(item => item.label)
  for (const category of nav.NAV_CATEGORIES) {
    for (const label of pageLabels) {
      assert.equal(category.label.includes(label), false, `${category.label} contains ${label}`)
    }
  }
})

/* ---------------- 2. expansion rules ---------------- */

test('navExpansionMode: admin accordion, director/developer multi-open', () => {
  assert.equal(nav.navExpansionMode('admin'), 'single')
  assert.equal(nav.navExpansionMode('director'), 'multi')
  assert.equal(nav.navExpansionMode('developer'), 'multi')
})

test('initialExpanded: single opens only the active category, multi opens all', () => {
  const admin = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }))
  assert.deepEqual(nav.initialExpanded(admin, 'usage', 'single'), ['observe'])
  assert.deepEqual(nav.initialExpanded(admin, 'dashboard', 'single'), [])
  assert.deepEqual(nav.initialExpanded(admin, 'docs', 'single'), [])
  const developer = nav.buildNavSections(nav.developerNavItems)
  assert.deepEqual(nav.initialExpanded(developer, 'dashboard', 'multi'), ['access', 'observe', 'devtools'])
  assert.deepEqual(nav.initialExpanded(developer, 'services', 'single'), [], 'a category the role cannot see is never opened')
})

test('toggleExpanded: single keeps at most one open, multi toggles independently', () => {
  assert.deepEqual(nav.toggleExpanded(['models'], 'observe', 'single'), ['observe'])
  assert.deepEqual(nav.toggleExpanded(['observe'], 'observe', 'single'), [])
  assert.deepEqual(nav.toggleExpanded([], 'access', 'single'), ['access'])
  assert.deepEqual(nav.toggleExpanded(['access'], 'observe', 'multi'), ['access', 'observe'])
  assert.deepEqual(nav.toggleExpanded(['access', 'observe'], 'access', 'multi'), ['observe'])
  const input = ['models']
  nav.toggleExpanded(input, 'observe', 'multi')
  assert.deepEqual(input, ['models'], 'input is not mutated')
})

test('revealActive opens the active page category after palette, hash or deep-link navigation', () => {
  assert.deepEqual(nav.revealActive([], 'services', 'single'), ['models'])
  assert.deepEqual(nav.revealActive(['access'], 'services', 'single'), ['models'])
  assert.deepEqual(nav.revealActive(['access'], 'services', 'multi'), ['access', 'models'])
  const open = ['models']
  assert.equal(nav.revealActive(open, 'external', 'single'), open, 'already open → same array')
  const state = ['observe']
  assert.equal(nav.revealActive(state, 'dashboard', 'single'), state, 'pinned page keeps state')
  assert.equal(nav.revealActive(state, 'docs', 'single'), state, 'docs keeps state')
  assert.equal(nav.revealActive(state, 'nope', 'multi'), state, 'unknown id keeps state')
})

/* ---------------- 3. search ---------------- */

test('searchNavItems finds every page regardless of sidebar collapse state', () => {
  const all = [...nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }), nav.docsNavItem]
  assert.equal(nav.searchNavItems(all, '').length, all.length)
  assert.equal(nav.searchNavItems(all, '   ').length, all.length)
  for (const item of all) {
    assert.ok(ids(nav.searchNavItems(all, item.label)).includes(item.id), `label finds ${item.id}`)
  }
  assert.deepEqual(ids(nav.searchNavItems(all, 'DISCORD')), ['notifications'], 'case-insensitive keyword match')
  assert.deepEqual(ids(nav.searchNavItems(all, '조직·보안')), ['projects', 'teams', 'relationship', 'data-protection', 'audit'], 'category label match')
  assert.ok(ids(nav.searchNavItems(all, '플로우')).includes('flow-designer'))
  assert.deepEqual(nav.searchNavItems(all, 'zz-no-match'), [])
})

test('navCategoryLabel labels palette results', () => {
  assert.equal(nav.navCategoryLabel('dashboard'), '홈')
  assert.equal(nav.navCategoryLabel('docs'), '도움말')
  assert.equal(nav.navCategoryLabel('quotas'), '관측·비용')
  assert.equal(nav.navCategoryLabel('beta'), '플랫폼 운영')
  assert.equal(nav.navCategoryLabel('unknown'), '')
})

/* ---------------- 4. SideNav.vue ---------------- */

function compileSideNav() {
  const source = readFileSync(new URL('../src/SideNav.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  let script = ts.transpileModule(
    compileScript(descriptor, { id: 'side-nav-test', inlineTemplate: true }).content,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }
  ).outputText
  script = script
    .replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
    .replace(/from ['"]\.\/consoleNavigation['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/consoleNavigation.ts')))
  return import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))
}

function createMemoryRenderer() {
  const node = tag => ({ tag, props: {}, children: [], text: '', parent: null })
  const renderer = createRenderer({
    createElement: node,
    createText: text => ({ ...node('#text'), text }),
    createComment: text => ({ ...node('#comment'), text }),
    patchProp: (element, key, _old, value) => { element.props[key] = value },
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
  const walk = (element, match) => [...(match(element) ? [element] : []), ...(element.children ?? []).flatMap(child => walk(child, match))]
  const text = element => element.tag === '#comment' ? '' : (element.text || '') + (element.children ?? []).map(text).join('')
  return { renderer, root: node('root'), walk, text }
}

test('SideNav renders accessible disclosure buttons, accordion toggling and active reveal', async () => {
  const { default: SideNav } = await compileSideNav()
  const { renderer, root, walk, text } = createMemoryRenderer()
  const activeId = ref('usage')
  const navigated = []
  const sections = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }))
  const app = renderer.createApp({
    render: () => h(SideNav, { sections, activeId: activeId.value, mode: 'single', onNavigate: id => navigated.push(id) })
  })
  app.mount(root)
  try {
    const navEl = walk(root, el => el.tag === 'nav')[0]
    assert.equal(navEl.props['aria-label'], '주요 기능')
    const toggles = () => walk(root, el => el.tag === 'button' && String(el.props.class ?? '').split(' ').includes('nav-category-toggle'))
    const toggle = id => walk(root, el => el.props.id === `nav-category-${id}`)[0]
    const panel = id => walk(root, el => el.props.id === `nav-category-items-${id}`)[0]
    assert.equal(toggles().length, 5)
    for (const button of toggles()) {
      const controlled = walk(root, el => el.props.id === button.props['aria-controls'])[0]
      assert.ok(controlled, `${button.props.id} controls an existing element`)
      assert.equal(controlled.props.role, 'group')
      assert.equal(controlled.props['aria-labelledby'], button.props.id)
      assert.equal(controlled.props.hidden, button.props['aria-expanded'] !== 'true')
    }
    assert.equal(toggle('observe').props['aria-expanded'], 'true', 'active page category starts open')
    assert.equal(toggle('models').props['aria-expanded'], 'false')
    assert.match(String(toggle('observe').props.class), /has-active/)
    const usage = walk(root, el => el.tag === 'button' && el.props['aria-current'] === 'page')
    assert.equal(usage.length, 1)
    assert.equal(text(usage[0]), '사용량')
    assert.equal(usage[0].props.title, '토큰·비용·API 호출 이력', 'description moves to the tooltip')
    assert.ok(text(panel('models')).includes('LLM 서비스'), 'collapsed items stay in the DOM (hidden attribute)')

    toggle('models').props.onClick()
    await nextTick()
    assert.equal(toggle('models').props['aria-expanded'], 'true')
    assert.equal(toggle('observe').props['aria-expanded'], 'false', 'accordion closes the previous category')
    assert.match(String(toggle('observe').props.class), /has-active/, 'collapsed category still marks the active page')

    walk(panel('models'), el => el.tag === 'button' && text(el) === 'LLM 서비스')[0].props.onClick()
    assert.deepEqual(navigated, ['services'])

    activeId.value = 'audit'
    await nextTick()
    assert.equal(toggle('access').props['aria-expanded'], 'true', 'palette/hash navigation reveals the category')
    assert.equal(toggle('models').props['aria-expanded'], 'false')

    toggle('access').props.onClick()
    await nextTick()
    assert.equal(toggles().every(button => button.props['aria-expanded'] === 'false'), true, 'the open category can be closed')

    const pinned = walk(root, el => el.tag === 'button' && String(el.props.class ?? '').split(' ').includes('nav-pinned'))
    assert.equal(pinned.length, 1)
    assert.ok(text(pinned[0]).includes('대시보드'))
    pinned[0].props.onClick()
    assert.deepEqual(navigated, ['services', 'dashboard'])
  } finally {
    app.unmount()
  }
})

test('SideNav resets to the role default when the role changes (multi-open for directors)', async () => {
  const { default: SideNav } = await compileSideNav()
  const { renderer, root, walk } = createMemoryRenderer()
  const role = ref('developer')
  const app = renderer.createApp({
    render: () => h(SideNav, {
      sections: nav.buildNavSections(nav.navItemsFor({ role: role.value, platformAdmin: false, betaOperator: false })),
      activeId: 'dashboard',
      mode: nav.navExpansionMode(role.value)
    })
  })
  app.mount(root)
  try {
    const expandedIds = () => walk(root, el => el.tag === 'button' && el.props['aria-expanded'] === 'true').map(el => el.props.id)
    assert.deepEqual(expandedIds(), ['nav-category-access', 'nav-category-observe', 'nav-category-devtools'])
    role.value = 'admin'
    await nextTick()
    assert.deepEqual(expandedIds(), [], 'admin on dashboard starts fully collapsed')
  } finally {
    app.unmount()
  }
})

test('SideNav source keeps memory-DOM-safe, storage-free markup', () => {
  const source = readFileSync(new URL('../src/SideNav.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /v-show/, 'use the hidden attribute, not v-show')
  assert.doesNotMatch(source, /<Transition/)
  assert.doesNotMatch(source, /localStorage|sessionStorage/)
  assert.doesNotMatch(source, /v-html/)
  const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
  assert.match(css, /\.nav-category-items\[hidden\]\s*\{\s*display:\s*none;\s*\}/, 'hidden must beat display:grid')
  assert.doesNotMatch(css, /\.nav-group/)
  assert.doesNotMatch(readFileSync(new URL('../src/accessibility.css', import.meta.url), 'utf8'), /\.nav-group/)
})
