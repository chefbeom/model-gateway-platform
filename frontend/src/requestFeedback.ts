/** Timeouts for admin requests whose feedback is shown inside an open modal. */
export const SAVE_REQUEST_TIMEOUT_MS = 30_000
export const PROVIDER_REQUEST_TIMEOUT_MS = 90_000

export interface RequestTimeout {
  signal: AbortSignal
  timedOut: () => boolean
  clear: () => void
}

export function createRequestTimeout(ms: number): RequestTimeout {
  const controller = new AbortController()
  let expired = false
  const timer = setTimeout(() => { expired = true; controller.abort() }, ms)
  return { signal: controller.signal, timedOut: () => expired, clear: () => clearTimeout(timer) }
}

export function isAbortError(error: unknown): boolean {
  const name = typeof error === 'object' && error !== null ? (error as { name?: string }).name : undefined
  return name === 'AbortError' || name === 'TimeoutError'
}

export function timeoutMessage(timeoutMs: number): string {
  return `서버 응답이 없습니다(${Math.round(timeoutMs / 1000)}초). 네트워크 또는 프록시 상태를 확인하세요.`
}

/** Maps a failed modal action to a readable Korean message including the HTTP status when known. */
export function describeRequestError(error: unknown, action: string, timeoutMs: number, timedOut = false): string {
  if (timedOut || isAbortError(error)) return timeoutMessage(timeoutMs)
  const detail = error instanceof Error && error.message ? error.message : '알 수 없는 오류가 발생했습니다.'
  const status = (error as { status?: number } | null)?.status
  return `${action}에 실패했습니다: ${detail}${typeof status === 'number' ? ` (HTTP ${status})` : ''}`
}
