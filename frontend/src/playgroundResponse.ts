export type ResponseInfo = { finishReason?: string | null; reasoningPresent?: boolean | null; outputLimit?: number | null }
export type DiagnosticTurn = ResponseInfo & { role: string; status?: string; httpStatus?: number; content: string; outputTokens?: number | null; error?: string | null }
export type ResponseDiagnostic = { label: string; title: string; detail: string }

export function responseInfo(choice: unknown, streaming = false): ResponseInfo {
  if (!choice || typeof choice !== 'object') return {}
  const value = choice as { finish_reason?: unknown; message?: Record<string, unknown>; delta?: Record<string, unknown> }
  const fields = streaming ? value.delta : value.message
  const reason = value.finish_reason
  const present = (item: unknown) => typeof item === 'string' ? item.trim().length > 0
    : Array.isArray(item) ? item.length > 0 : item != null && typeof item === 'object' && Object.keys(item).length > 0
  return {
    finishReason: typeof reason === 'string' && /^[a-zA-Z0-9_-]{1,64}$/.test(reason) ? reason : undefined,
    reasoningPresent: present(fields?.reasoning_content) || present(fields?.reasoning)
  }
}

export function responseDiagnostic(turn: DiagnosticTurn): ResponseDiagnostic | null {
  if (turn.role !== 'assistant' || !['SUCCEEDED', 'FAILED'].includes(turn.status ?? '') || (turn.httpStatus != null && turn.httpStatus >= 400)) return null
  if (turn.status === 'FAILED' && turn.httpStatus == null) return null
  if (turn.status === 'FAILED' && turn.error && turn.error !== '모델이 표시할 답변 본문을 반환하지 않았습니다.') return null
  const empty = !turn.content.trim()
  const reason = turn.finishReason
  const reachedLimit = reason === 'length' || reason === 'max_tokens'
  const limitPossible = !reason && turn.outputLimit != null && turn.outputTokens != null && turn.outputTokens >= turn.outputLimit
  const reasoning = turn.reasoningPresent ? ' 별도 추론 필드는 감지됐지만 원문은 표시하거나 저장하지 않습니다.' : ''
  const increase = ' 요청 옵션에서 Max completion tokens를 늘려 다시 시도하고, 필요하면 모델의 추론 설정을 확인하세요.'
  if (empty && reachedLimit) return { label: '응답 없음', title: '출력 한도에 도달해 답변이 비어 있습니다', detail: '모델이 출력 한도로 생성을 종료했습니다.' + reasoning + increase }
  if (empty && limitPossible) return { label: '응답 없음', title: '표시할 답변이 없습니다', detail: '출력 토큰이 요청 한도에 도달했을 가능성이 있습니다. 종료 사유가 없어 확정할 수 없습니다.' + reasoning + increase }
  if (empty && reason === 'content_filter') return { label: '응답 없음', title: '안전 필터로 응답이 제한됐습니다', detail: '모델 서버의 응답 정책을 확인하세요.' }
  if (empty) return { label: '응답 없음', title: '표시할 답변 본문이 없습니다', detail: 'HTTP 요청은 완료됐지만 모델이 표시 가능한 답변을 반환하지 않았습니다.' + reasoning + (reason ? ' 종료 사유와 모델 서버 로그를 확인하세요.' : ' 종료 사유가 기록되지 않아 원인을 확정할 수 없습니다. 서버 로그와 출력 한도를 확인하세요.') }
  if (reachedLimit) return { label: '응답 잘림', title: '출력 한도에 도달했습니다', detail: '위 답변은 미완성일 수 있습니다.' + increase }
  if (reason === 'content_filter') return { label: '응답 제한', title: '안전 필터로 응답이 제한됐습니다', detail: '위 답변은 완전하지 않을 수 있습니다. 모델 서버의 응답 정책을 확인하세요.' }
  return null
}
