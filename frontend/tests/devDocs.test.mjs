import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

const modules = new Map()
async function moduleUrl(url) {
  if (modules.has(url.href)) return modules.get(url.href)
  let source = ts.transpileModule(readFileSync(url, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
  for (const match of [...source.matchAll(/from\s+['"](\.[^'"]+)['"]/g)]) {
    source = source.replace(match[0], 'from ' + JSON.stringify(await moduleUrl(new URL(match[1] + '.ts', url))))
  }
  const result = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  modules.set(url.href, result)
  return result
}
const { devDocs, DOCS_REVIEWED_AT, searchDocs } = await import(await moduleUrl(new URL('../src/dev-docs/catalog.ts', import.meta.url)))
const { documentHash, documentIdFromHash, filterDocuments, searchDocuments } = await import(await moduleUrl(new URL('../src/dev-docs/navigation.ts', import.meta.url)))

test('catalog has unique documents, sections and valid navigation destinations', () => {
  assert.equal(new Set(devDocs.map(doc => doc.id)).size, devDocs.length)
  const destinations = new Set(['portal', 'usage', 'projects', 'infrastructure', 'services', 'teams', 'observability', 'audit', 'notifications', 'external', 'quotas', 'playground', 'model-playground', 'data-protection', 'system'])
  for (const doc of devDocs) {
    assert.ok(doc.sections.length, doc.id)
    assert.equal(new Set(doc.sections.map(section => section.id)).size, doc.sections.length, doc.id)
    for (const section of doc.sections) for (const block of section.blocks) {
      if (block.type === 'steps') for (const item of block.items) if (item.action) assert.ok(destinations.has(item.action.destination), item.action.destination)
      if (block.type === 'table') for (const row of block.rows) assert.equal(row.length, block.columns.length, doc.id)
    }
  }
})
test('current features are included once in the central catalog', () => {
  for (const id of ['model-playground', 'data-protection', 'request-diagnostics', 'runtime-diagnostics']) assert.equal(devDocs.filter(doc => doc.id === id).length, 1)
  assert.equal(DOCS_REVIEWED_AT, '2026. 10. 05.')
  assert.ok(searchDocs('Ollama').some(doc => doc.id === 'runtime'))
  assert.ok(searchDocs('llama.cpp').some(doc => doc.id === 'runtime'))
})
test('search includes body, tables, code and multiple case-insensitive terms', () => {
  assert.ok(searchDocuments(devDocs, 'REDACT').some(doc => doc.id === 'data-protection'))
  assert.ok(searchDocuments(devDocs, '  FAST 가격  ').some(doc => doc.id === 'external-provider'))
  assert.ok(searchDocuments(devDocs, 'AICONNECT_API_KEY').some(doc => doc.id === 'api'))
  assert.ok(searchDocuments(devDocs, 'CONTEXT_LENGTH_EXCEEDED').some(doc => doc.id === 'request-diagnostics'))
  assert.deepEqual(searchDocuments(devDocs, ' '), [])
  assert.deepEqual(searchDocuments(devDocs, 'a-nonexistent-document-query-123'), [])
})
test('search respects audience and retains common guides', () => {
  assert.ok(filterDocuments(devDocs, '사용자').every(doc => doc.audience !== '관리자'))
  assert.ok(filterDocuments(devDocs, '관리자').every(doc => doc.audience !== '사용자'))
  assert.ok(searchDocuments(devDocs, '오류', '사용자').some(doc => doc.id === 'request-diagnostics'))
  assert.ok(searchDocuments(devDocs, '모델', '사용자').every(doc => doc.audience !== '관리자'))
})
test('document hashes handle encoded ids, malformed values and unrelated pages', () => {
  for (const id of ['runtime', 'data-protection', '문서 / 샘플']) assert.equal(documentIdFromHash(documentHash(id)), id)
  assert.equal(documentIdFromHash('#/docs/runtime'), 'runtime')
  for (const hash of ['#docs', '#docs/', '#services', '#docs/%E0%A4%A', '#docs/runtime#other']) assert.equal(documentIdFromHash(hash), null)
})
test('API examples avoid unsupported optional temperature and malformed shell continuations', () => {
  for (const doc of devDocs) for (const section of doc.sections) for (const block of section.blocks) {
    if (block.type !== 'code') continue
    assert.doesNotMatch(block.code, /\n\+\s+-f /)
    if (block.title === 'cURL') assert.doesNotMatch(block.code, /"temperature"/)
  }
})
