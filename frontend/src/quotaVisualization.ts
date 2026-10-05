export type UsageCurrency = 'KRW' | 'USD'
export type UsageMetric = 'cost' | 'requests' | 'tokens'
export type UsageDay = {
  date: string
  requestCount: number
  inputTokens: number
  outputTokens: number
  byCurrency?: Record<string, number>
}

export function formatSpend(value: number | null | undefined, currency: UsageCurrency) {
  return new Intl.NumberFormat(currency === 'USD' ? 'en-US' : 'ko-KR', {
    style: 'currency', currency, maximumFractionDigits: 12
  }).format(Number(value ?? 0))
}

export function usageValue(day: UsageDay, metric: UsageMetric, currency: UsageCurrency) {
  if (metric === 'requests') return day.requestCount
  if (metric === 'tokens') return day.inputTokens + day.outputTokens
  return Number(day.byCurrency?.[currency] ?? 0)
}

export function buildUsageChart(days: UsageDay[], metric: UsageMetric, currency: UsageCurrency) {
  const maximum = Math.max(0, ...days.map(day => usageValue(day, metric, currency)))
  const width = 720; const height = 210; const pad = 22
  const step = days.length > 1 ? (width - pad * 2) / (days.length - 1) : 0
  const points = days.map((day, index) => {
    const value = usageValue(day, metric, currency)
    const ratio = maximum > 0 ? value / maximum : 0
    return { ...day, value, relativePercent: ratio * 100,
      x: days.length === 1 ? width / 2 : pad + index * step,
      y: height - pad - ratio * (height - pad * 2) }
  })
  const path = points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' ')
  const area = points.length ? `${path} L ${points[points.length - 1].x} ${height - pad} L ${points[0].x} ${height - pad} Z` : ''
  return { points, path, area, width, height, pad, maximum }
}

export type UsageCalendarCell = UsageDay & {
  day: number; inMonth: boolean; inRange: boolean; value: number; level: number; relativePercent: number
}

export function buildUsageCalendar(days: UsageDay[], month: string, metric: UsageMetric, currency: UsageCurrency): UsageCalendarCell[] {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) return []
  const [year, monthNumber] = month.split('-').map(Number)
  const first = new Date(Date.UTC(year, monthNumber - 1, 1))
  const last = new Date(Date.UTC(year, monthNumber, 0))
  const byDate = new Map(days.map(day => [day.date.slice(0, 10), day]))
  const visibleDays = days.filter(day => day.date.startsWith(month + '-'))
  const maximum = Math.max(0, ...visibleDays.map(day => usageValue(day, metric, currency)))
  const empty = (): UsageCalendarCell => ({ date: '', day: 0, requestCount: 0, inputTokens: 0, outputTokens: 0,
    byCurrency: {}, value: 0, level: 0, relativePercent: 0, inMonth: false, inRange: false })
  const cells: UsageCalendarCell[] = Array.from({ length: first.getUTCDay() }, empty)
  for (let dayNumber = 1; dayNumber <= last.getUTCDate(); dayNumber++) {
    const date = `${month}-${String(dayNumber).padStart(2, '0')}`
    const recorded = byDate.get(date)
    const day = recorded ?? { date, requestCount: 0, inputTokens: 0, outputTokens: 0, byCurrency: {} }
    const value = usageValue(day, metric, currency)
    const ratio = maximum > 0 ? value / maximum : 0
    cells.push({ ...day, date, day: dayNumber, value, relativePercent: ratio * 100,
      level: value > 0 ? Math.min(4, Math.ceil(ratio * 4)) : 0, inMonth: true, inRange: !!recorded })
  }
  while (cells.length % 7) cells.push(empty())
  return cells
}
