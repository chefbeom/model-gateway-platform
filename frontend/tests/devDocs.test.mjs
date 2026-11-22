import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

const modules = new Map()
async function moduleUrl(url) {
  if (modules.has(url.href)) return modules.get(url.href)
  let source = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 }
  }).outputText
  for (const match of [...source.matchAll(/from\s+['"](\.[^'"]+)['"]/g)]) {
    source = source.replace(match[0], 'from ' + JSON.stringify(await moduleUrl(new URL(match[1] + '.ts', url))))
  }
  const result = 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
  modules.set(url.href, result)
  return result
}

const { devDocs, DOCS_REVIEWED_AT, findDoc, searchDocs } = await import(
  await moduleUrl(new URL('../src/dev-docs/catalog.ts', import.meta.url))
)
const { documentHash, documentIdFromHash, filterDocuments, resolveDocumentId, searchDocuments } = await import(
  await moduleUrl(new URL('../src/dev-docs/navigation.ts', import.meta.url))
)

test('request scheduling documents opt-in modes, shared capacity and no external privacy bypass', () => {
  const section = devDocs
    .find(doc => doc.id === 'runtime-routing')
    .sections.find(section => section.id === 'request-scheduling')
  assert.ok(section)
  const text = JSON.stringify(section)
  for (const term of [
    'IMMEDIATE',
    'LOCAL_FIRST_WAIT',
    'LOCAL_ONLY_QUEUE',
    '60초',
    'Chat 테스트',
    'Redis',
    '본문',
    '외부로 전송하지 않습니다'
  ]) {
    assert.ok(text.includes(term), term)
  }
  assert.ok(searchDocuments(devDocs, '대기열').some(doc => doc.id === 'runtime-routing'))
})

test('catalog has 9 concise guides, unique ids, valid destinations and intact mockup blocks', () => {
  assert.equal(DOCS_REVIEWED_AT, '2026. 11. 22.')
  assert.equal(devDocs.length, 9)
  assert.equal(new Set(devDocs.map(doc => doc.id)).size, devDocs.length)

  const destinations = new Set([
    'dashboard',
    'relationship',
    'platform',
    'portal',
    'usage',
    'projects',
    'infrastructure',
    'services',
    'teams',
    'observability',
    'audit',
    'notifications',
    'external',
    'quotas',
    'playground',
    'model-playground',
    'data-protection',
    'system'
  ])

  let mockupCount = 0
  for (const doc of devDocs) {
    assert.ok(doc.sections.length > 0, doc.id)
    assert.equal(new Set(doc.sections.map(section => section.id)).size, doc.sections.length, doc.id)
    for (const section of doc.sections) {
      for (const block of section.blocks) {
        if (block.type === 'steps') {
          for (const item of block.items) {
            if (item.action) assert.ok(destinations.has(item.action.destination), item.action.destination)
          }
        }
        if (block.type === 'table') {
          for (const row of block.rows) assert.equal(row.length, block.columns.length, `${doc.id}:${section.id}`)
        }
        if (block.type === 'mockup') {
          mockupCount += 1
          assert.ok(block.screen.trim().length > 0, `${doc.id}:${section.id}:screen`)
          assert.ok(Array.isArray(block.fields) && block.fields.length > 0, `${doc.id}:${section.id}:fields`)
          if (block.navigate) assert.ok(destinations.has(block.navigate.destination), block.navigate.destination)
        }
      }
    }
  }
  assert.ok(mockupCount >= 10, `expected >= 10 mockup walkthroughs, got ${mockupCount}`)
})

test('role filtering supports 관리자, 프로젝트 총괄자, 팀원 and retains common guides', () => {
  const adminDocs = filterDocuments(devDocs, '관리자').map(d => d.id)
  const directorDocs = filterDocuments(devDocs, '프로젝트 총괄자').map(d => d.id)
  const memberDocs = filterDocuments(devDocs, '팀원').map(d => d.id)

  assert.ok(adminDocs.includes('guide-admin'))
  assert.ok(adminDocs.includes('data-protection-quotas'))
  assert.ok(!adminDocs.includes('guide-director'))
  assert.ok(!adminDocs.includes('guide-member'))

  assert.ok(directorDocs.includes('guide-director'))
  assert.ok(directorDocs.includes('data-protection-quotas'))
  assert.ok(!directorDocs.includes('guide-admin'))
  assert.ok(!directorDocs.includes('guide-member'))

  assert.ok(memberDocs.includes('guide-member'))
  assert.ok(memberDocs.includes('overview'))
  assert.ok(memberDocs.includes('onboarding-auth'))
  assert.ok(memberDocs.includes('diagnostics'))
  assert.ok(!memberDocs.includes('guide-admin'))
  assert.ok(!memberDocs.includes('guide-director'))
  assert.ok(!memberDocs.includes('data-protection-quotas'))

  assert.ok(searchDocuments(devDocs, '오류', '팀원').some(doc => doc.id === 'diagnostics'))
  assert.ok(searchDocuments(devDocs, '모델', '팀원').every(doc => doc.audience === '공통' || doc.audience === '팀원'))
})

test('search includes body, tables, code and mockup example values', () => {
  assert.ok(searchDocs('Ollama').some(doc => doc.id === 'runtime-routing'))
  assert.ok(searchDocs('llama.cpp').some(doc => doc.id === 'runtime-routing'))
  assert.ok(searchDocuments(devDocs, 'REDACT').some(doc => doc.id === 'data-protection-quotas'))
  assert.ok(searchDocuments(devDocs, 'INTERNAL_ONLY').some(doc => doc.id === 'data-protection-quotas'))
  assert.ok(searchDocuments(devDocs, '  FAST 가격  ').some(doc => doc.id === 'runtime-routing'))
  assert.ok(searchDocuments(devDocs, 'AICONNECT_API_KEY').some(doc => doc.id === 'guide-member'))
  assert.ok(searchDocuments(devDocs, 'CONTEXT_LENGTH_EXCEEDED').some(doc => doc.id === 'diagnostics'))
  assert.ok(searchDocuments(devDocs, 'Google Authenticator').some(doc => doc.id === 'onboarding-auth'))
  assert.ok(searchDocuments(devDocs, 'cs-copilot-prod').some(doc => doc.id === 'guide-member'))
  assert.ok(searchDocuments(devDocs, 'lead.park@acme.example', '프로젝트 총괄자').some(doc => doc.id === 'guide-director'))
  assert.ok(searchDocuments(devDocs, 'sk_llmg_example_9f8a').some(doc => doc.id === 'guide-member'))
  assert.deepEqual(searchDocuments(devDocs, ' '), [])
  assert.deepEqual(searchDocuments(devDocs, 'a-nonexistent-document-query-123'), [])
})

test('document hashes and legacy doc aliases resolve cleanly', () => {
  for (const id of ['guide-admin', 'guide-director', 'guide-member', '문서 / 샘플']) {
    assert.equal(documentIdFromHash(documentHash(id)), id)
  }
  assert.equal(documentIdFromHash('#/docs/guide-member'), 'guide-member')
  for (const hash of ['#docs', '#docs/', '#services', '#docs/%E0%A4%A', '#docs/runtime#other']) {
    assert.equal(documentIdFromHash(hash), null)
  }

  assert.equal(resolveDocumentId('quickstart'), 'guide-member')
  assert.equal(findDoc('quickstart').id, 'guide-member')
  assert.equal(findDoc('admin-first-run').id, 'guide-admin')
  assert.equal(findDoc('request-diagnostics').id, 'diagnostics')
  assert.equal(findDoc('data-protection').id, 'data-protection-quotas')
  assert.equal(findDoc('runtime').id, 'runtime-routing')
})

test('examples avoid unsupported optional temperature, malformed shells, real IPs and real emails', () => {
  const serialized = JSON.stringify(devDocs)
  for (const forbidden of ['100.100.9.74', '100.92.170.22', '192.168.35.101', 'kjs990515', 'h002374']) {
    assert.ok(!serialized.includes(forbidden), `forbidden string found: ${forbidden}`)
  }

  for (const doc of devDocs) {
    for (const section of doc.sections) {
      for (const block of section.blocks) {
        if (block.type !== 'code') continue
        assert.doesNotMatch(block.code, /\n\+\s+-f /)
        if (block.title === 'cURL') assert.doesNotMatch(block.code, /"temperature"/)
      }
    }
  }
})
