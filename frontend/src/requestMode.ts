export function formatExecutionMode(effort?: string | null, requestedTier?: string | null, actualTier?: string | null) {
  const effortLabel = effort ? effort.toUpperCase() : '기본 추론'
  const tierLabel = (value: string) => {
    const normalized = value.toLowerCase()
    if (normalized === 'fast' || normalized === 'priority') return 'Fast'
    if (normalized === 'default' || normalized === 'standard') return '표준'
    return value
  }

  if (actualTier) {
    const actual = tierLabel(actualTier)
    const requested = requestedTier ? tierLabel(requestedTier) : null
    return `${effortLabel} · ${actual} 실제${requested && requested !== actual ? ` (${requested} 요청)` : ''}`
  }
  return `${effortLabel} · ${requestedTier ? `${tierLabel(requestedTier)} 요청` : 'tier 기본값'}`
}
