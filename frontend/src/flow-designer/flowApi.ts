// Network layer of the flow designer. The only flow-designer module that imports ../api.
import { adminFetch, type AdminAuth } from '../api'
import { LIMITS } from './catalog'
import { sanitizeLayout } from './layout'
import { buildSnapshot, FlowLoadError, type RawFlowData } from './flowSnapshot'
import type { FlowLayout, FlowTransport, HttpMethod, ServiceFlowSnapshot } from './types'

const enc = encodeURIComponent
const CONCURRENCY = 6

export function createAdminTransport(auth: AdminAuth): FlowTransport {
  return {
    request<T>(method: HttpMethod, path: string, body?: unknown): Promise<T> {
      return adminFetch<T>(path, auth, { method, body: body === undefined ? undefined : JSON.stringify(body) })
    }
  }
}

/** Runs async jobs with a bounded parallelism. */
async function runLimited<T>(jobs: Array<() => Promise<T>>, limit = CONCURRENCY): Promise<T[]> {
  const output: T[] = new Array(jobs.length)
  let cursor = 0
  async function worker() {
    while (cursor < jobs.length) {
      const index = cursor
      cursor += 1
      output[index] = await jobs[index]()
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, jobs.length) }, worker))
  return output
}

function rowsOf(value: unknown): Array<Record<string, unknown>> {
  return Array.isArray(value) ? value.filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null) : []
}

/** Lists the services of an organization (organization-scoped path). */
export async function loadServiceList(organizationId: string, auth: AdminAuth): Promise<Array<{ id: string; serviceKey: string; displayName: string }>> {
  try {
    const list = await adminFetch<unknown[]>(`/api/admin/organizations/${enc(organizationId)}/services`, auth)
    return rowsOf(list).map((row) => ({ id: String(row.id ?? ''), serviceKey: String(row.serviceKey ?? ''), displayName: String(row.displayName ?? '') })).filter((row) => row.id)
  } catch {
    throw new FlowLoadError('REQUIRED_READ_FAILED', '서비스 목록을 불러오지 못했습니다.')
  }
}

export async function loadFlowSnapshot(organizationId: string, serviceId: string | null, auth: AdminAuth): Promise<ServiceFlowSnapshot> {
  const org = enc(organizationId)
  const unavailable: string[] = []
  async function read<T>(path: string): Promise<T | null> {
    try {
      const value = await adminFetch<T>(path, auth)
      return value === undefined ? null : value
    } catch {
      unavailable.push(path)
      return null
    }
  }

  // 1) Services: required.
  const servicesPath = `/api/admin/organizations/${org}/services`
  let services: unknown[]
  try {
    services = rowsOf(await adminFetch<unknown[]>(servicesPath, auth))
  } catch {
    throw new FlowLoadError('REQUIRED_READ_FAILED', '서비스 목록을 불러오지 못했습니다.')
  }
  if (serviceId !== null && !services.some((row) => (row as { id?: unknown }).id === serviceId)) {
    throw new FlowLoadError('SERVICE_NOT_FOUND')
  }

  // 2-6) Independent first-level reads (organization scoped paths only).
  const sid = serviceId === null ? null : enc(serviceId)
  const [targets, scheduling, dataProtection, endpoints, providers, projects, maskers] = await runLimited<unknown>([
    () => (sid ? read<unknown[]>(`/api/admin/services/${sid}/targets`) : Promise.resolve([])),
    () => (sid ? read<unknown>(`/api/admin/services/${sid}/scheduling`) : Promise.resolve(null)),
    () => (sid ? read<unknown>(`/api/admin/services/${sid}/data-protection`) : Promise.resolve(null)),
    () => read<unknown[]>(`/api/admin/organizations/${org}/runtime-endpoints`),
    () => read<unknown[]>(`/api/admin/organizations/${org}/external-providers`),
    () => read<unknown[]>(`/api/admin/organizations/${org}/projects`),
    () => read<unknown[]>(`/api/admin/organizations/${org}/masker-deployments`)
  ])

  const endpointRows = rowsOf(endpoints)
  const providerRows = rowsOf(providers)
  const projectRows = projects === null ? null : rowsOf(projects)
  const readAccess = sid !== null && projectRows !== null && projectRows.length <= LIMITS.maxProjectsForAccess

  // Second level: per endpoint deployments, per provider models, per project access.
  const jobs: Array<() => Promise<void>> = []
  const endpointDeployments: Record<string, unknown[]> = {}
  const providerModels: Record<string, unknown[]> = {}
  const projectAccess: Record<string, unknown[] | null> = {}
  for (const row of endpointRows) {
    const id = String(row.id ?? '')
    if (!id) continue
    jobs.push(async () => { endpointDeployments[id] = (await read<unknown[]>(`/api/admin/runtime-endpoints/${enc(id)}/deployments`)) ?? [] })
  }
  for (const row of providerRows) {
    const id = String(row.id ?? '')
    if (!id) continue
    jobs.push(async () => { providerModels[id] = (await read<unknown[]>(`/api/admin/external-providers/${enc(id)}/models`)) ?? [] })
  }
  if (readAccess && projectRows) {
    for (const row of projectRows) {
      const id = String(row.id ?? '')
      if (!id) continue
      jobs.push(async () => { projectAccess[id] = await read<unknown[]>(`/api/admin/projects/${enc(id)}/service-access`) })
    }
  }
  await runLimited(jobs)

  const raw: RawFlowData = {
    organizationId,
    serviceId,
    loadedAt: new Date().toISOString(),
    unavailable,
    services,
    targets: serviceId === null ? [] : (targets as unknown[] | null),
    scheduling: serviceId === null ? null : scheduling,
    dataProtection,
    endpoints: endpointRows,
    endpointDeployments,
    providers: providerRows,
    providerModels,
    projects: projectRows,
    projectAccess,
    maskers: rowsOf(maskers)
  }
  return buildSnapshot(raw)
}

export async function loadFlowLayout(serviceId: string, auth: AdminAuth): Promise<{ available: boolean; layout: FlowLayout | null; revision: number }> {
  try {
    const response = await adminFetch<{ revision?: unknown; layout?: unknown } | undefined>(`/api/admin/services/${enc(serviceId)}/flow-layout`, auth)
    if (!response || typeof response !== 'object') return { available: false, layout: null, revision: 0 }
    const revision = typeof response.revision === 'number' && Number.isFinite(response.revision) ? response.revision : 0
    return { available: true, layout: sanitizeLayout(response.layout ?? null), revision }
  } catch {
    return { available: false, layout: null, revision: 0 }
  }
}

export async function saveFlowLayout(serviceId: string, layout: FlowLayout, auth: AdminAuth): Promise<{ revision: number }> {
  // Unsaved new targets have no stable server identity yet and are never persisted.
  const nodes = Object.fromEntries(Object.entries(layout.nodes).filter(([id]) => !id.startsWith('target:new-')))
  const body: FlowLayout = { version: 1, viewport: layout.viewport, nodes }
  const response = await adminFetch<{ revision?: unknown } | undefined>(`/api/admin/services/${enc(serviceId)}/flow-layout`, auth, {
    method: 'PUT',
    body: JSON.stringify(body)
  })
  const revision = response && typeof response.revision === 'number' ? response.revision : 0
  return { revision }
}

/** Read-only look at the session identity used to key in-memory drafts. */
export function currentUserId(): string {
  try {
    if (typeof sessionStorage === 'undefined') return 'anonymous'
    const raw = sessionStorage.getItem('aiconnect.user')
    if (raw) {
      const user = JSON.parse(raw) as { id?: unknown } | null
      if (user && typeof user.id === 'string' && user.id) return user.id
    }
    if (sessionStorage.getItem('aiconnect.platformToken')) return 'platform-token'
  } catch {
    /* fall through */
  }
  return 'anonymous'
}
