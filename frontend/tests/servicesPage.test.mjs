import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const source = readFileSync(new URL('../src/ServicesPage.vue', import.meta.url), 'utf8')

function fnBody(name) {
  const start = source.indexOf(`async function ${name}(`)
  assert.ok(start >= 0, `${name} exists`)
  const next = source.indexOf('\nasync function ', start + 10)
  return source.slice(start, next < 0 ? undefined : next)
}

test('cache TTL lower bound matches the server @Min(10)', () => {
  assert.match(source, /Math\.max\(10,\s*Math\.min\(86400/)
  assert.doesNotMatch(source, /Math\.max\(5,\s*Math\.min\(86400/)
  assert.match(source, /responseCacheTtlSeconds"[^>]*min="10"/)
})

test('guardrailAction is only sent when it changed', () => {
  const body = fnBody('savePipeline')
  assert.match(body, /draft\.guardrailAction !== \(svc\.guardrailAction \?\? 'ALLOW'\)/)
  assert.doesNotMatch(body, /^\s*guardrailAction: draft\.guardrailAction,/m)
})

test('runtime endpoints are loaded through the organization-scoped path', () => {
  const body = fnBody('loadDeployments')
  assert.match(body, /`\/api\/admin\/organizations\/\$\{props\.organizationId\}\/runtime-endpoints`/)
  assert.doesNotMatch(body, /'\/api\/admin\/runtime-endpoints'/)
  assert.match(body, /organizations\/\$\{props\.organizationId\}\/external-providers/)
})
