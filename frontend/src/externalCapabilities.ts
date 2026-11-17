export type UnknownCapabilityPolicy = 'STRICT' | 'TRY_UNCONFIRMED'

function parse(value?: string | null): Record<string, unknown> {
  if (!value) return {}
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
  } catch { return {} }
}

export function unknownCapabilityPolicy(value?: string | null): UnknownCapabilityPolicy {
  return parse(value).routingUnknownCapabilities === 'TRY_UNCONFIRMED' ? 'TRY_UNCONFIRMED' : 'STRICT'
}

export function withUnknownCapabilityPolicy(value: string | null | undefined, mode: UnknownCapabilityPolicy): string {
  const result = parse(value)
  if (mode === 'TRY_UNCONFIRMED') result.routingUnknownCapabilities = mode
  else delete result.routingUnknownCapabilities
  return JSON.stringify(result)
}
