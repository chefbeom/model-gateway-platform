import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

const source = readFileSync(new URL('../src/quotaVisualization.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText
const { buildUsageChart, buildUsageCalendar, formatSpend } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`)
const day = (date, cost = {}, requestCount = 0, inputTokens = 0, outputTokens = 0) => ({ date, byCurrency: cost, requestCount, inputTokens, outputTokens })

test('sub-cent USD costs use the full plot height rather than a one-dollar floor', () => {
  const chart = buildUsageChart([day('2026-10-01'), day('2026-10-02', { USD: .00022 })], 'cost', 'USD')
  assert.equal(chart.maximum, .00022)
  assert.equal(chart.points[1].relativePercent, 100)
  assert.ok(chart.points[0].y - chart.points[1].y > 150)
  assert.equal(buildUsageChart([day('2026-10-01', { USD: 1e-12 })], 'cost', 'USD').points[0].relativePercent, 100)
})

test('different currencies are never added together in a cost chart', () => {
  const days = [day('2026-10-01', { USD: .002, KRW: 1000 })]
  assert.equal(buildUsageChart(days, 'cost', 'USD').maximum, .002)
  assert.equal(buildUsageChart(days, 'cost', 'KRW').maximum, 1000)
})

test('requests and tokens remain visible when no cost was recorded', () => {
  const days = [day('2026-10-01', {}, 4, 150, 50)]
  assert.equal(buildUsageChart(days, 'requests', 'USD').maximum, 4)
  assert.equal(buildUsageChart(days, 'tokens', 'USD').maximum, 200)
  assert.equal(buildUsageChart(days, 'cost', 'USD').maximum, 0)
})

test('calendar intensity uses only the displayed month and distinguishes unqueried days', () => {
  const cells = buildUsageCalendar([day('2026-09-30', { USD: 50 }), day('2026-10-01', { USD: .002 }), day('2026-10-02')], '2026-10', 'cost', 'USD')
  assert.equal(cells.find(cell => cell.day === 1).level, 4)
  assert.equal(cells.find(cell => cell.day === 1).relativePercent, 100)
  assert.equal(cells.find(cell => cell.day === 2).inRange, true)
  assert.equal(cells.find(cell => cell.day === 3).inRange, false)
})

test('zero activity is finite and costs retain the stored twelve-decimal precision', () => {
  const chart = buildUsageChart([day('2026-10-01')], 'cost', 'USD')
  assert.equal(chart.maximum, 0)
  assert.equal(chart.points[0].relativePercent, 0)
  assert.ok(Number.isFinite(chart.points[0].y))
  assert.match(formatSpend(1e-12, 'USD'), /0\.000000000001/)
})
