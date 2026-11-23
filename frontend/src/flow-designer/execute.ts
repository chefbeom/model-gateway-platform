// Plan executor. Pure apart from the injected transport: no vue, no ../api, no DOM.
import type {
  ExecutionReport,
  FlowOperation,
  FlowPlan,
  FlowTransport,
  HttpMethod,
  OperationResult,
  ServiceRef,
  TargetRef,
  TransportError
} from './types'

export interface RequestContext {
  serviceId: string | null
  createdTargetIds: Record<string, string>
}
export interface BuiltRequest { method: HttpMethod; path: string; body?: unknown }

const MISSING_CREATE = '선행 생성 작업 결과가 없습니다.'
const enc = encodeURIComponent

function serviceSegment(ref: ServiceRef, ctx: RequestContext, lenient: boolean): string {
  if (ref.kind === 'existing') return enc(ref.id)
  if (ctx.serviceId) return enc(ctx.serviceId)
  if (lenient) return '{새 서비스 ID}'
  throw new Error(MISSING_CREATE)
}

function targetSegment(ref: TargetRef, ctx: RequestContext, lenient: boolean): string {
  if (ref.kind === 'existing') return enc(ref.id)
  const id = ctx.createdTargetIds[ref.key]
  if (id) return enc(id)
  if (lenient) return '{새 Target ID}'
  throw new Error(MISSING_CREATE)
}

function build(op: FlowOperation, ctx: RequestContext, lenient: boolean): BuiltRequest {
  switch (op.kind) {
    case 'createService':
      return { method: 'POST', path: '/api/admin/services', body: op.body }
    case 'patchService':
      return { method: 'PATCH', path: `/api/admin/services/${serviceSegment(op.service, ctx, lenient)}`, body: op.body }
    case 'updateScheduling':
      return { method: 'PATCH', path: `/api/admin/services/${serviceSegment(op.service, ctx, lenient)}/scheduling`, body: op.body }
    case 'createTarget': {
      const body: Record<string, unknown> = { ...op.body }
      if (body.maxConcurrencyOverride === null || body.maxConcurrencyOverride === undefined) delete body.maxConcurrencyOverride
      return { method: 'POST', path: `/api/admin/services/${serviceSegment(op.service, ctx, lenient)}/targets`, body }
    }
    case 'patchTarget':
      return {
        method: 'PATCH',
        path: `/api/admin/services/${serviceSegment(op.service, ctx, lenient)}/targets/${targetSegment(op.target, ctx, lenient)}`,
        body: op.body
      }
    case 'deleteTarget':
      return { method: 'DELETE', path: `/api/admin/services/${serviceSegment(op.service, ctx, lenient)}/targets/${enc(op.targetId)}` }
    case 'grantAccess':
      return {
        method: 'POST',
        path: `/api/admin/projects/${enc(op.projectId)}/service-access`,
        body: { serviceId: op.service.kind === 'existing' ? op.service.id : ctx.serviceId ?? (lenient ? '{새 서비스 ID}' : failMissing()) }
      }
    case 'revokeAccess':
      return { method: 'DELETE', path: `/api/admin/projects/${enc(op.projectId)}/service-access/${serviceSegment(op.service, ctx, lenient)}` }
  }
}

function failMissing(): never {
  throw new Error(MISSING_CREATE)
}

/** The exact request for an operation (spec 7.5). Throws when a created reference cannot be resolved yet. */
export function operationRequest(op: FlowOperation, ctx: RequestContext): BuiltRequest {
  return build(op, ctx, false)
}

/** Display-only request with placeholders for ids that do not exist until execution. */
export function previewRequest(op: FlowOperation): BuiltRequest {
  return build(op, { serviceId: null, createdTargetIds: {} }, true)
}

function asTransportError(error: unknown): TransportError {
  if (error && typeof error === 'object') {
    const e = error as { message?: unknown; code?: unknown; status?: unknown }
    return {
      message: typeof e.message === 'string' && e.message ? e.message : '요청을 처리하지 못했습니다.',
      code: typeof e.code === 'string' ? e.code : undefined,
      status: typeof e.status === 'number' ? e.status : undefined
    }
  }
  return { message: typeof error === 'string' && error ? error : '요청을 처리하지 못했습니다.' }
}

function idOf(response: unknown): string | null {
  if (response && typeof response === 'object') {
    const id = (response as { id?: unknown }).id
    if (typeof id === 'string' && id) return id
  }
  return null
}

export async function executePlan(
  plan: FlowPlan,
  transport: FlowTransport,
  options: { serviceId: string | null; onProgress?: (results: OperationResult[]) => void }
): Promise<ExecutionReport> {
  const results: OperationResult[] = plan.operations.map((op) => ({ opId: op.opId, status: 'pending', message: null, code: null, httpStatus: null }))
  const emit = () => options.onProgress?.(results.map((item) => ({ ...item })))
  const ctx: RequestContext = { serviceId: options.serviceId, createdTargetIds: {} }
  let failedOpId: string | null = null
  emit()

  for (let index = 0; index < plan.operations.length; index += 1) {
    const op = plan.operations[index]
    if (failedOpId !== null) {
      results[index] = { ...results[index], status: 'skipped' }
      continue
    }
    results[index] = { ...results[index], status: 'running' }
    emit()
    let request: BuiltRequest
    try {
      request = operationRequest(op, ctx)
    } catch (error) {
      const info = asTransportError(error)
      results[index] = { opId: op.opId, status: 'failed', message: info.message, code: null, httpStatus: null }
      failedOpId = op.opId
      emit()
      continue
    }
    try {
      const response = await transport.request<unknown>(request.method, request.path, request.body)
      if (op.kind === 'createService') {
        const id = idOf(response)
        if (!id) throw Object.assign(new Error('서비스 생성 응답에 ID가 없습니다.'), { code: 'MISSING_ID' })
        ctx.serviceId = id
      } else if (op.kind === 'createTarget') {
        const id = idOf(response)
        if (!id) throw Object.assign(new Error('Target 생성 응답에 ID가 없습니다.'), { code: 'MISSING_ID' })
        ctx.createdTargetIds[op.key] = id
      }
      results[index] = { opId: op.opId, status: 'succeeded', message: null, code: null, httpStatus: null }
    } catch (error) {
      const info = asTransportError(error)
      if ((op.kind === 'deleteTarget' || op.kind === 'revokeAccess') && info.status === 404) {
        results[index] = { opId: op.opId, status: 'already-applied', message: '이미 적용된 상태입니다.', code: info.code ?? null, httpStatus: 404 }
      } else {
        results[index] = { opId: op.opId, status: 'failed', message: info.message, code: info.code ?? null, httpStatus: info.status ?? null }
        failedOpId = op.opId
      }
    }
    emit()
  }

  const deletedTargetKeys: string[] = []
  plan.operations.forEach((op, i) => {
    if (op.kind === 'deleteTarget' && (results[i].status === 'succeeded' || results[i].status === 'already-applied')) deletedTargetKeys.push(`target:${op.targetId}`)
  })
  const completed = results.every((item) => item.status === 'succeeded' || item.status === 'already-applied')
  return {
    results: results.map((item) => ({ ...item })),
    serviceId: ctx.serviceId,
    createdTargetIds: { ...ctx.createdTargetIds },
    deletedTargetKeys,
    completed,
    failedOpId
  }
}
