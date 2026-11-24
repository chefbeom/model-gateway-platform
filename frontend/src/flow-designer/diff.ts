import {
  BOUNDARY_RANK,
  GUARDRAIL_STRICTNESS,
  LIMITS,
  SCHEDULING_FIELDS,
  SERVICE_FIELDS,
  TARGET_FIELDS,
  branchPromptDeploymentId,
  fieldLabel,
  presenceOf,
  targetKeyOf,
  targetNodeId,
} from './catalog'
import { compile, sameFieldValue } from './compile'
import { syncGraphToConfig } from './decompile'
import { detachTargetNodes, renameTargetNodes } from './graphOps'
import { isBranchPrompt } from './workflow'
import type {
  CreateServiceBody,
  CreateTargetBody,
  FlowConfigState,
  FlowConflict,
  FlowGraph,
  FlowOperation,
  FlowPlan,
  NodeChangeKind,
  OperationChange,
  OperationRisk,
  PatchServiceBody,
  PatchTargetBody,
  PlanInput,
  PlanNote,
  RebaseInput,
  RebaseResult,
  SchedulingField,
  SchedulingState,
  ServiceField,
  ServiceFlowSnapshot,
  ServiceRef,
  TargetField,
  TargetState,
  ValidationIssue,
} from './types'

/* ------------------------------------------------------------------ */
/* Utilities                                                           */
/* ------------------------------------------------------------------ */

export function stableStringify(value: unknown): string {
  if (value === undefined) return 'null'
  if (value === null || typeof value !== 'object') {
    const text = JSON.stringify(value)
    return text === undefined ? 'null' : text
  }
  if (Array.isArray(value)) return `[${value.map((x) => (x === undefined ? 'null' : stableStringify(x))).join(',')}]`
  const record = value as Record<string, unknown>
  const parts: string[] = []
  for (const key of Object.keys(record).sort()) {
    if (record[key] === undefined) continue
    parts.push(`${JSON.stringify(key)}:${stableStringify(record[key])}`)
  }
  return `{${parts.join(',')}}`
}

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }
function cmpNum(a: number, b: number): number {
  const fa = Number.isFinite(a)
  const fb = Number.isFinite(b)
  if (fa && fb) return a - b
  return fa ? -1 : fb ? 1 : 0
}

export function configsEqual(a: FlowConfigState, b: FlowConfigState): boolean {
  for (const f of SERVICE_FIELDS) if (!sameFieldValue(f, a.service[f], b.service[f])) return false
  for (const f of SCHEDULING_FIELDS) if (!sameFieldValue(f, a.scheduling[f], b.scheduling[f])) return false
  if (a.targets.length !== b.targets.length) return false
  const bt = new Map(b.targets.map((t) => [t.key, t]))
  for (const t of a.targets) {
    const o = bt.get(t.key)
    if (!o || t.id !== o.id) return false
    for (const f of TARGET_FIELDS) if (!sameFieldValue(f, t[f], o[f])) return false
  }
  if (a.accessProjectIds.length !== b.accessProjectIds.length) return false
  const as = [...a.accessProjectIds].sort()
  const bs = [...b.accessProjectIds].sort()
  return as.every((id, i) => id === bs[i])
}

/** True when the graph's compiled configuration differs from the snapshot's own configuration. */
export function isConfigDirty(graph: FlowGraph, base: ServiceFlowSnapshot): boolean {
  return !configsEqual(compile(graph, base), base.config)
}

type NameOf = (deploymentId: string) => string

function fmt(field: string, value: unknown, nameOf: NameOf): string {
  if (value === null || value === undefined) return '없음'
  if (typeof value === 'boolean') return value ? '사용' : '사용 안 함'
  if (field === 'systemPromptTemplate') return `${String(value).length}자`
  if (field === 'maskerDeploymentId' || field === 'deploymentId') return nameOf(String(value))
  return String(value)
}

function describeTarget(t: TargetState, nameOf: NameOf): string {
  return `${nameOf(t.deploymentId)} · P${t.priority} · W${t.weight}${t.enabled ? '' : ' · 중지'}`
}

type Resolution = 'none' | 'converged' | 'apply' | 'conflict'
function resolve(field: string, b: unknown, l: unknown, r: unknown): Resolution {
  if (sameFieldValue(field, l, b)) return 'none'
  if (sameFieldValue(field, l, r)) return 'converged'
  if (sameFieldValue(field, r, b)) return 'apply'
  return 'conflict'
}

/* ------------------------------------------------------------------ */
/* Three-way analysis (shared by planChanges and mergeConfigs)         */
/* ------------------------------------------------------------------ */

type TargetDecision =
  | { type: 'patch'; key: string; b: TargetState; l: TargetState; r: TargetState; applied: TargetField[]; conflicted: TargetField[] }
  | { type: 'removedRemotely'; key: string; b: TargetState; l: TargetState; changed: boolean }
  | { type: 'delete'; key: string; b: TargetState; r: TargetState }
  | { type: 'changedRemotely'; key: string; b: TargetState; r: TargetState }
  | { type: 'create'; key: string; l: TargetState }
  | { type: 'targetedRemotely'; key: string; l: TargetState; r: TargetState }
  | { type: 'localCreated'; key: string; l: TargetState; r: TargetState }

interface Analysis {
  serviceApplied: ServiceField[]
  serviceConflicted: ServiceField[]
  schedulingApplied: SchedulingField[]
  schedulingConflicted: SchedulingField[]
  decisions: TargetDecision[]
  /** Remote-only targets that were not matched to a local new target. */
  remoteOnly: TargetState[]
  conflicts: FlowConflict[]
}

function analyze(base: FlowConfigState, local: FlowConfigState, remote: FlowConfigState, nameOf: NameOf): Analysis {
  const conflicts: FlowConflict[] = []
  const serviceApplied: ServiceField[] = []
  const serviceConflicted: ServiceField[] = []
  for (const f of SERVICE_FIELDS) {
    const res = resolve(f, base.service[f], local.service[f], remote.service[f])
    if (res === 'apply') serviceApplied.push(f)
    if (res === 'conflict') {
      serviceConflicted.push(f)
      if (f !== 'serviceKey') {
        conflicts.push({
          code: 'FIELD_CONFLICT', entity: 'service', key: f, field: f,
          base: fmt(f, base.service[f], nameOf), local: fmt(f, local.service[f], nameOf), remote: fmt(f, remote.service[f], nameOf),
          message: `${fieldLabel('service', f)}: 불러온 이후 서버에서도 다른 값으로 변경되었습니다.`,
        })
      }
    }
  }
  const schedulingApplied: SchedulingField[] = []
  const schedulingConflicted: SchedulingField[] = []
  for (const f of SCHEDULING_FIELDS) {
    const res = resolve(f, base.scheduling[f], local.scheduling[f], remote.scheduling[f])
    if (res === 'apply') schedulingApplied.push(f)
    if (res === 'conflict') {
      schedulingConflicted.push(f)
      conflicts.push({
        code: 'FIELD_CONFLICT', entity: 'scheduling', key: f, field: f,
        base: fmt(f, base.scheduling[f], nameOf), local: fmt(f, local.scheduling[f], nameOf), remote: fmt(f, remote.scheduling[f], nameOf),
        message: `${fieldLabel('scheduling', f)}: 불러온 이후 서버에서도 다른 값으로 변경되었습니다.`,
      })
    }
  }

  const B = new Map(base.targets.map((t) => [t.key, t]))
  const L = new Map(local.targets.map((t) => [t.key, t]))
  const R = new Map(remote.targets.map((t) => [t.key, t]))
  const decisions: TargetDecision[] = []
  const remoteOnly = remote.targets.filter((t) => !B.has(t.key) && !L.has(t.key)).sort((a, b) => cmp(a.key, b.key))
  const used = new Set<string>()
  const differs = (a: TargetState, b: TargetState): boolean => TARGET_FIELDS.some((f) => !sameFieldValue(f, a[f], b[f]))

  for (const l of [...local.targets].sort((a, b) => cmp(a.key, b.key))) {
    if (l.id === null) {
      const hit = remoteOnly.find((r) => !used.has(r.key) && r.deploymentId === l.deploymentId)
      if (hit) {
        used.add(hit.key)
        decisions.push({ type: 'targetedRemotely', key: l.key, l, r: hit })
        conflicts.push({
          code: 'DEPLOYMENT_TARGETED_REMOTELY', entity: 'target', key: l.key, field: null,
          base: '없음', local: describeTarget(l, nameOf), remote: describeTarget(hit, nameOf),
          message: `${nameOf(l.deploymentId)}: 불러온 이후 서버에 같은 모델의 LLM 연결(Target)이 이미 만들어졌습니다.`,
        })
      } else {
        decisions.push({ type: 'create', key: l.key, l })
      }
      continue
    }
    const b = B.get(l.key)
    const r = R.get(l.key)
    if (b && r) {
      const applied: TargetField[] = []
      const conflicted: TargetField[] = []
      for (const f of TARGET_FIELDS) {
        const res = resolve(f, b[f], l[f], r[f])
        if (res === 'apply') applied.push(f)
        if (res === 'conflict') {
          conflicted.push(f)
          conflicts.push({
            code: 'FIELD_CONFLICT', entity: 'target', key: l.key, field: f,
            base: fmt(f, b[f], nameOf), local: fmt(f, l[f], nameOf), remote: fmt(f, r[f], nameOf),
            message: `${nameOf(r.deploymentId)} LLM 연결의 ${fieldLabel('target', f)}: 불러온 이후 서버에서도 다른 값으로 변경되었습니다.`,
          })
        }
      }
      decisions.push({ type: 'patch', key: l.key, b, l, r, applied, conflicted })
    } else if (b && !r) {
      const changed = differs(b, l)
      decisions.push({ type: 'removedRemotely', key: l.key, b, l, changed })
      if (changed) {
        conflicts.push({
          code: 'TARGET_REMOVED_REMOTELY', entity: 'target', key: l.key, field: null,
          base: describeTarget(b, nameOf), local: describeTarget(l, nameOf), remote: '삭제됨',
          message: `${nameOf(b.deploymentId)} LLM 연결(Target)이 불러온 이후 서버에서 삭제되었는데 로컬에서 수정했습니다.`,
        })
      }
    } else if (!b && r) {
      decisions.push({ type: 'localCreated', key: l.key, l, r })
    }
  }
  for (const b of base.targets) {
    if (L.has(b.key)) continue
    const r = R.get(b.key)
    if (!r) continue
    if (differs(b, r)) {
      decisions.push({ type: 'changedRemotely', key: b.key, b, r })
      conflicts.push({
        code: 'TARGET_CHANGED_REMOTELY', entity: 'target', key: b.key, field: null,
        base: describeTarget(b, nameOf), local: '삭제', remote: describeTarget(r, nameOf),
        message: `${nameOf(b.deploymentId)} LLM 연결(Target)을 로컬에서 삭제했지만 불러온 이후 서버에서 변경되었습니다.`,
      })
    } else {
      decisions.push({ type: 'delete', key: b.key, b, r })
    }
  }
  return {
    serviceApplied, serviceConflicted, schedulingApplied, schedulingConflicted, decisions,
    remoteOnly: remoteOnly.filter((t) => !used.has(t.key)), conflicts,
  }
}

function cloneConfig(config: FlowConfigState): FlowConfigState {
  return {
    service: { ...config.service },
    scheduling: { ...config.scheduling },
    targets: config.targets.map((t) => ({ ...t })),
    accessProjectIds: [...config.accessProjectIds],
  }
}

function mergeAccess(base: string[], local: string[], remote: string[]): string[] {
  const b = new Set(base)
  const l = new Set(local)
  const merged = new Set(remote)
  for (const id of local) if (!b.has(id)) merged.add(id)
  for (const id of base) if (!l.has(id)) merged.delete(id)
  return [...merged].sort()
}

function mergeWith(base: FlowConfigState, local: FlowConfigState, remote: FlowConfigState, nameOf: NameOf) {
  const a = analyze(base, local, remote, nameOf)
  const merged = cloneConfig(remote)
  const service = merged.service as unknown as Record<string, unknown>
  for (const f of a.serviceApplied) service[f] = local.service[f]
  const scheduling = merged.scheduling as unknown as Record<string, unknown>
  for (const f of a.schedulingApplied) scheduling[f] = local.scheduling[f]

  const adopted: Record<string, string> = {}
  const targets: TargetState[] = []
  for (const d of a.decisions) {
    switch (d.type) {
      case 'patch': {
        const t = { ...d.r } as unknown as Record<string, unknown>
        for (const f of d.applied) t[f] = d.l[f]
        targets.push(t as unknown as TargetState)
        break
      }
      case 'changedRemotely': targets.push({ ...d.r }); break
      case 'create': targets.push({ ...d.l }); break
      case 'localCreated': targets.push({ ...d.l }); break
      case 'targetedRemotely':
        if (d.r.id !== null) {
          adopted[d.l.key] = d.r.id
          targets.push({ ...d.l, key: targetNodeId(d.r.id), id: d.r.id })
        } else {
          targets.push({ ...d.l })
        }
        break
      case 'removedRemotely':
      case 'delete':
        break
    }
  }
  for (const r of a.remoteOnly) targets.push({ ...r })
  targets.sort((x, y) => cmp(x.key, y.key))
  merged.targets = targets
  merged.accessProjectIds = mergeAccess(base.accessProjectIds, local.accessProjectIds, remote.accessProjectIds)
  return { merged, conflicts: a.conflicts, adopted }
}

export function mergeConfigs(base: FlowConfigState, local: FlowConfigState, remote: FlowConfigState): { merged: FlowConfigState; conflicts: FlowConflict[]; adopted: Record<string, string> } {
  return mergeWith(base, local, remote, (id) => id)
}

export function rebase(input: RebaseInput): RebaseResult {
  const { graph, base, remote, createdTargetIds, deletedTargetKeys } = input
  // Targets whose server row we deleted ourselves (and did not re-create) become pending creates instead of "removed remotely".
  const detachKeys = (deletedTargetKeys ?? []).filter((k) => !(createdTargetIds && k in createdTargetIds))
  const g0 = detachKeys.length > 0 ? detachTargetNodes(graph, detachKeys) : graph
  const g1 = createdTargetIds && Object.keys(createdTargetIds).length > 0 ? renameTargetNodes(g0, createdTargetIds) : g0
  const local = compile(g1, base)
  const { merged, conflicts, adopted } = mergeConfigs(base.config, local, remote.config)
  const g2 = Object.keys(adopted).length > 0 ? renameTargetNodes(g1, adopted) : g1
  return { graph: syncGraphToConfig(g2, merged, remote), conflicts }
}

/* ------------------------------------------------------------------ */
/* planChanges                                                         */
/* ------------------------------------------------------------------ */

const CREATE_COVERED_FIELDS: ServiceField[] = [
  'serviceKey', 'displayName', 'failoverPolicy', 'retryPolicy', 'allowDegraded', 'requiredCapabilitiesJson',
  'inputPricePerMillion', 'outputPricePerMillion', 'currency', 'temperaturePolicy', 'temperatureValue',
  'reasoningEffort', 'openAiFastMode', 'dataBoundaryPolicy',
]

const NOTE_GUARDRAIL = '가드레일 처리 정책을 바꾸면 서버가 서비스 범위 데이터 보호 정책을 다시 저장합니다. 모드·수준·외부 Failover 허용이 새 정책에 맞게 재설정되고 비밀정보·개인정보·금융 탐지가 켜집니다. 기밀·미디어 탐지와 사용자 정의 패턴은 유지됩니다.'
const NOTE_REPLACE = 'LLM 노드의 모델이 바뀌어 새 연결(Target)을 만든 뒤 기존 연결을 삭제합니다. 서버 ID가 바뀝니다.'
const NOTE_PREDELETE = '같은 모델을 다시 쓰려고 기존 연결을 먼저 삭제합니다. 적용하는 동안 잠시 라우팅 대상이 줄어듭니다.'
const NOTE_NO_TARGET = '적용 후 사용 중인 LLM이 없어 이 워크플로우의 요청은 실패합니다.'
const NOTE_NEW_SERVICE = '새 워크플로우는 시작 노드에 프로젝트를 연결(접근 허용)하기 전까지 호출되지 않습니다.'
const NOTE_PROTECTION_RELAXED = '외부 노출 방지 정책이 완화됩니다(데이터 경계 또는 민감정보 처리). 영향을 받는 프로젝트와 모델을 확인한 뒤 적용하세요.'
const NOTE_DRIFT = '불러온 이후 다른 곳에서 변경된 항목이 있습니다. 충돌하지 않는 원격 변경은 유지됩니다.'

function issueKey(i: ValidationIssue): string { return `${i.code}|${i.nodeId ?? ''}|${i.edgeId ?? ''}|${i.field ?? ''}` }

export function planChanges(input: PlanInput): FlowPlan {
  const { base, desired, remote, issues } = input
  const isNew = base.serviceId === null
  const B = base.config
  const L = desired
  const R = isNew ? base.config : remote.config

  const deploymentName: NameOf = (id) =>
    remote.catalog.deployments.find((d) => d.id === id)?.displayName ?? base.catalog.deployments.find((d) => d.id === id)?.displayName ?? id
  const isExternal = (id: string): boolean => (remote.catalog.deployments.find((d) => d.id === id) ?? base.catalog.deployments.find((d) => d.id === id))?.external === true
  const projectName = (id: string): string =>
    remote.catalog.projects.find((p) => p.id === id)?.name ?? base.catalog.projects.find((p) => p.id === id)?.name ?? id

  const a = analyze(B, L, R, deploymentName)
  const serviceRef: ServiceRef = isNew ? { kind: 'created' } : { kind: 'existing', id: base.serviceId as string }
  const notes: PlanNote[] = []
  const blocking: ValidationIssue[] = []
  const pushBlocking = (issue: ValidationIssue) => {
    if (!blocking.some((x) => issueKey(x) === issueKey(issue))) blocking.push(issue)
  }
  for (const issue of issues) if (issue.severity === 'error') pushBlocking(issue)

  const createServiceOps: FlowOperation[] = []
  const patchServiceOps: FlowOperation[] = []
  const schedulingOps: FlowOperation[] = []
  const creates: Array<{ ops: FlowOperation[]; priority: number; key: string; deploymentId: string }> = []
  const patches: Array<{ op: FlowOperation; priority: number; id: string }> = []
  const deletes: Array<{ op: FlowOperation; id: string; remoteDeploymentId: string; replacement: boolean }> = []
  const grants: FlowOperation[] = []
  const revokes: FlowOperation[] = []

  /* ---- service ---- */
  const s = L.service
  if (isNew) {
    const body: CreateServiceBody = {
      organizationId: base.organizationId,
      serviceKey: s.serviceKey,
      displayName: s.displayName,
      failoverPolicy: s.failoverPolicy,
      retryPolicy: s.retryPolicy,
      allowDegraded: s.allowDegraded,
      requiredCapabilitiesJson: s.requiredCapabilitiesJson,
      inputPricePerMillion: s.inputPricePerMillion,
      outputPricePerMillion: s.outputPricePerMillion,
      currency: s.currency,
      temperaturePolicy: s.temperaturePolicy,
      temperatureValue: s.temperaturePolicy === 'FIXED' ? s.temperatureValue : null,
      reasoningEffort: s.reasoningEffort,
      openAiFastMode: s.openAiFastMode,
      dataBoundaryPolicy: s.dataBoundaryPolicy,
    }
    createServiceOps.push({
      kind: 'createService', opId: 'create-service', risk: 'caution',
      summary: `새 워크플로우(논리 서비스) 생성: ${s.serviceKey}`,
      changes: [
        { field: 'serviceKey', label: fieldLabel('service', 'serviceKey'), before: '없음', after: s.serviceKey },
        { field: 'displayName', label: fieldLabel('service', 'displayName'), before: '없음', after: s.displayName },
      ],
      body,
    })
    if (remote.catalog.services.some((x) => x.serviceKey === s.serviceKey)) {
      pushBlocking({ code: 'SERVICE_KEY_TAKEN', severity: 'error', message: '이 조직에 이미 있는 호출 이름입니다.', nodeId: 'start', edgeId: null, field: 'serviceKey' })
    }
  }

  const skip = new Set<ServiceField>(isNew ? CREATE_COVERED_FIELDS : ['serviceKey'])
  const applied = a.serviceApplied.filter((f) => !skip.has(f))
  if (applied.length > 0) {
    const has = (f: ServiceField) => applied.includes(f)
    const body: Record<string, unknown> = {}
    for (const f of ['displayName', 'enabled', 'dataBoundaryPolicy', 'requiredCapabilitiesJson', 'currency', 'inputPricePerMillion', 'outputPricePerMillion', 'guardrailAction', 'maskPersonNames', 'reasoningEffort', 'openAiFastMode', 'responseCacheEnabled', 'responseCacheTtlSeconds', 'failoverPolicy', 'retryPolicy', 'allowDegraded'] as ServiceField[]) {
      if (has(f)) body[f] = s[f]
    }
    if (has('maskerDeploymentId')) {
      if (s.maskerDeploymentId) body.maskerDeploymentId = s.maskerDeploymentId
      else body.clearMaskerDeployment = true
    }
    if (has('systemPromptTemplate')) {
      if (s.systemPromptTemplate !== null) body.systemPromptTemplate = s.systemPromptTemplate
      else body.clearSystemPromptTemplate = true
    }
    if (has('temperaturePolicy') || has('temperatureValue')) {
      body.temperaturePolicy = s.temperaturePolicy
      body.temperatureValue = s.temperaturePolicy === 'FIXED' ? s.temperatureValue : null
    }
    const changes: OperationChange[] = applied.map((f) => ({
      field: f, label: fieldLabel('service', f),
      before: fmt(f, R.service[f], deploymentName), after: fmt(f, s[f], deploymentName),
    }))
    let risk: OperationRisk = 'caution'
    let protectionRelaxed = false
    if (!isNew) {
      if (has('enabled') && R.service.enabled && !s.enabled) risk = 'danger'
      if (has('dataBoundaryPolicy') && BOUNDARY_RANK[s.dataBoundaryPolicy] > BOUNDARY_RANK[R.service.dataBoundaryPolicy]) { risk = 'danger'; protectionRelaxed = true }
      if (has('guardrailAction') && GUARDRAIL_STRICTNESS[s.guardrailAction] < GUARDRAIL_STRICTNESS[R.service.guardrailAction]) { risk = 'danger'; protectionRelaxed = true }
      if (has('maskerDeploymentId') && R.service.maskerDeploymentId !== null && s.maskerDeploymentId === null) risk = 'danger'
    }
    patchServiceOps.push({
      kind: 'patchService', opId: 'patch-service', risk,
      summary: `워크플로우 설정 변경 (${applied.length}개 항목)`,
      changes, service: serviceRef, body: body as PatchServiceBody,
    })
    if (has('guardrailAction')) notes.push({ level: 'warning', message: NOTE_GUARDRAIL })
    if (protectionRelaxed) notes.push({ level: 'warning', message: NOTE_PROTECTION_RELAXED })
    if (has('systemPromptTemplate')) {
      const n = remote.promptVersion
      notes.push({ level: 'info', message: `시스템 프롬프트가 바뀌면 프롬프트 버전이 v${n}에서 v${n + 1}로 올라가고 기존 응답 캐시는 더 이상 쓰이지 않습니다.` })
    }
  }

  /* ---- scheduling ---- */
  if (a.schedulingApplied.length > 0) {
    const body: SchedulingState = { ...R.scheduling }
    const target = body as unknown as Record<string, unknown>
    for (const f of a.schedulingApplied) target[f] = L.scheduling[f]
    schedulingOps.push({
      kind: 'updateScheduling', opId: 'update-scheduling', risk: 'caution',
      summary: '라우터 배분·대기열 정책 변경',
      changes: a.schedulingApplied.map((f) => ({ field: f, label: fieldLabel('scheduling', f), before: fmt(f, R.scheduling[f], deploymentName), after: fmt(f, L.scheduling[f], deploymentName) })),
      service: serviceRef, body,
    })
  }

  /* ---- targets ---- */
  const resolvedProps = (l: TargetState, r: TargetState, appliedFields: TargetField[]): TargetState => {
    const out = { ...r } as unknown as Record<string, unknown>
    for (const f of appliedFields) out[f] = l[f]
    return out as unknown as TargetState
  }
  let promptTouched = false
  const addCreate = (key: string, t: TargetState, replacementOf: string | null) => {
    const body: CreateTargetBody = {
      deploymentId: t.deploymentId, priority: t.priority, weight: t.weight, degraded: t.degraded, followModelChanges: t.followModelChanges,
    }
    if (t.maxConcurrencyOverride !== null) body.maxConcurrencyOverride = t.maxConcurrencyOverride
    const prompt = t.systemPromptTemplate ?? null
    if (prompt !== null) body.systemPromptTemplate = prompt
    const name = deploymentName(t.deploymentId)
    const createChanges: OperationChange[] = [
      { field: 'deploymentId', label: fieldLabel('target', 'deploymentId'), before: replacementOf ? deploymentName(replacementOf) : '없음', after: name },
      { field: 'priority', label: fieldLabel('target', 'priority'), before: '없음', after: String(t.priority) },
      { field: 'weight', label: fieldLabel('target', 'weight'), before: '없음', after: String(t.weight) },
    ]
    if (prompt !== null) {
      createChanges.push({ field: 'systemPromptTemplate', label: fieldLabel('target', 'systemPromptTemplate'), before: '없음', after: fmt('systemPromptTemplate', prompt, deploymentName) })
      promptTouched = true
    }
    const ops: FlowOperation[] = [{
      kind: 'createTarget', opId: `create-target:${key}`, risk: isExternal(t.deploymentId) ? 'caution' : 'safe',
      summary: `LLM 연결 추가: ${name} (P${t.priority} · W${t.weight})`,
      changes: createChanges,
      service: serviceRef, key, body,
    }]
    if (!t.enabled) {
      ops.push({
        kind: 'patchTarget', opId: `disable-created-target:${key}`, risk: 'safe',
        summary: `새 LLM 연결 사용 중지: ${name}`,
        changes: [{ field: 'enabled', label: fieldLabel('target', 'enabled'), before: '사용', after: '사용 안 함' }],
        service: serviceRef, target: { kind: 'created', key }, body: { enabled: false },
      })
    }
    creates.push({ ops, priority: t.priority, key, deploymentId: t.deploymentId })
  }

  let replaced = false
  const hadPrompt = (t: TargetState | undefined): boolean => !!t && (t.systemPromptTemplate ?? null) !== null
  for (const d of a.decisions) {
    switch (d.type) {
      case 'create':
        addCreate(d.l.key, d.l, null)
        break
      case 'patch': {
        const changedLocally = TARGET_FIELDS.some((f) => !sameFieldValue(f, d.b[f], d.l[f]))
        if (!changedLocally || d.applied.length === 0 || d.r.id === null) break
        if (d.applied.includes('deploymentId')) {
          const resolved = resolvedProps(d.l, d.r, d.applied)
          addCreate(d.l.key, resolved, d.r.deploymentId)
          deletes.push({
            op: {
              kind: 'deleteTarget', opId: `delete-target:${d.r.id}`, risk: 'danger',
              summary: `LLM 연결 삭제: ${deploymentName(d.r.deploymentId)}`,
              changes: [], service: serviceRef, targetId: d.r.id,
            },
            id: d.r.id, remoteDeploymentId: d.r.deploymentId, replacement: true,
          })
          if (hadPrompt(d.r)) promptTouched = true
          replaced = true
          break
        }
        const body: PatchTargetBody = {}
        const changes: OperationChange[] = []
        let disabling = false
        let promptChanged = false
        for (const f of d.applied) {
          if (f === 'maxConcurrencyOverride') {
            if (d.l.maxConcurrencyOverride === null) continue
            body.maxConcurrencyOverride = d.l.maxConcurrencyOverride
          } else if (f === 'priority') body.priority = d.l.priority
          else if (f === 'weight') body.weight = d.l.weight
          else if (f === 'degraded') body.degraded = d.l.degraded
          else if (f === 'enabled') { body.enabled = d.l.enabled; if (d.r.enabled && !d.l.enabled) disabling = true }
          else if (f === 'followModelChanges') body.followModelChanges = d.l.followModelChanges
          else if (f === 'systemPromptTemplate') {
            const value = d.l.systemPromptTemplate ?? null
            if (value !== null) body.systemPromptTemplate = value
            else body.clearSystemPromptTemplate = true
            promptChanged = true
          } else continue
          changes.push({ field: f, label: fieldLabel('target', f), before: fmt(f, d.r[f], deploymentName), after: fmt(f, d.l[f], deploymentName) })
        }
        if (changes.length === 0) break
        if (promptChanged) promptTouched = true
        patches.push({
          op: {
            kind: 'patchTarget', opId: `patch-target:${d.r.id}`, risk: disabling ? 'danger' : promptChanged ? 'caution' : 'safe',
            summary: `LLM 설정 변경: ${deploymentName(d.r.deploymentId)}`,
            changes, service: serviceRef, target: { kind: 'existing', id: d.r.id }, body,
          },
          priority: body.priority ?? d.r.priority,
          id: d.r.id,
        })
        break
      }
      case 'delete':
        if (d.b.id === null) break
        deletes.push({
          op: {
            kind: 'deleteTarget', opId: `delete-target:${d.b.id}`, risk: 'danger',
            summary: `LLM 연결 삭제: ${deploymentName(d.r.deploymentId)}`,
            changes: [], service: serviceRef, targetId: d.b.id,
          },
          id: d.b.id, remoteDeploymentId: d.r.deploymentId, replacement: false,
        })
        if (hadPrompt(d.r)) promptTouched = true
        break
      default:
        break
    }
  }

  creates.sort((x, y) => cmpNum(x.priority, y.priority) || cmp(x.key, y.key))
  patches.sort((x, y) => cmpNum(x.priority, y.priority) || cmp(x.id, y.id))
  const createdDeployments = new Set(creates.map((c) => c.deploymentId))
  const preDeletes = deletes.filter((x) => createdDeployments.has(x.remoteDeploymentId)).sort((x, y) => cmp(x.id, y.id))
  const postDeletes = deletes.filter((x) => !createdDeployments.has(x.remoteDeploymentId)).sort((x, y) => cmp(x.id, y.id))
  if (replaced) notes.push({ level: 'info', message: NOTE_REPLACE })
  if (promptTouched) {
    notes.push({ level: 'info', message: `모델별 시스템 프롬프트가 바뀌어 프롬프트 버전(현재 v${remote.promptVersion})이 올라가고 기존 응답 캐시는 더 이상 쓰이지 않습니다.` })
  }
  if (preDeletes.length > 0) notes.push({ level: 'warning', message: NOTE_PREDELETE })

  /* ---- access ---- */
  const accessChanged = L.accessProjectIds.length !== B.accessProjectIds.length || L.accessProjectIds.some((id) => !B.accessProjectIds.includes(id))
  if (accessChanged) {
    if (!base.accessComplete || !remote.accessComplete) {
      pushBlocking({ code: 'ACCESS_EDIT_UNAVAILABLE', severity: 'error', message: '프로젝트 접근 정보를 모두 불러오지 못해 접근 변경을 적용할 수 없습니다.', nodeId: null, edgeId: null, field: null })
    } else {
      const baseSet = new Set(B.accessProjectIds)
      const localSet = new Set(L.accessProjectIds)
      const remoteSet = new Set(R.accessProjectIds)
      for (const id of [...L.accessProjectIds].sort()) {
        if (baseSet.has(id) || remoteSet.has(id)) continue
        grants.push({
          kind: 'grantAccess', opId: `grant-access:${id}`, risk: 'caution',
          summary: `프로젝트 접근 허용: ${projectName(id)}`, changes: [], service: serviceRef, projectId: id,
        })
      }
      for (const id of [...B.accessProjectIds].sort()) {
        if (localSet.has(id) || !remoteSet.has(id)) continue
        revokes.push({
          kind: 'revokeAccess', opId: `revoke-access:${id}`, risk: 'danger',
          summary: `프로젝트 접근 해제: ${projectName(id)}`, changes: [], service: serviceRef, projectId: id,
        })
      }
    }
  }

  const operations: FlowOperation[] = [
    ...createServiceOps,
    ...patchServiceOps,
    ...schedulingOps,
    ...preDeletes.map((x) => x.op),
    ...creates.flatMap((c) => c.ops),
    ...patches.map((x) => x.op),
    ...postDeletes.map((x) => x.op),
    ...grants,
    ...revokes,
  ]

  if (operations.length > LIMITS.maxOperations) {
    pushBlocking({ code: 'PLAN_TOO_LARGE', severity: 'error', message: '한 번에 적용할 수 있는 작업은 100개까지입니다. 나누어 적용하세요.', nodeId: null, edgeId: null, field: null })
  }

  if (operations.length > 0) {
    const merged = mergeConfigs(B, L, R).merged
    if (merged.service.enabled && !merged.targets.some((t) => t.enabled)) notes.push({ level: 'warning', message: NOTE_NO_TARGET })
  }
  if (isNew) notes.push({ level: 'info', message: NOTE_NEW_SERVICE })
  const remoteDrift = !configsEqual(base.config, remote.config)
  if (remoteDrift) notes.push({ level: 'info', message: NOTE_DRIFT })

  const fingerprint = stableStringify(operations.map((op) => {
    const x = op as unknown as Record<string, unknown>
    return { kind: op.kind, opId: op.opId, body: x.body, service: x.service, target: x.target, targetId: x.targetId, projectId: x.projectId, key: x.key }
  }))

  return { operations, conflicts: a.conflicts, blockingIssues: blocking, notes, remoteDrift, fingerprint }
}

/* ------------------------------------------------------------------ */
/* Node change badges                                                  */
/* ------------------------------------------------------------------ */

export function computeNodeChanges(graph: FlowGraph, base: ServiceFlowSnapshot): Record<string, NodeChangeKind> {
  const L = compile(graph, base)
  const B = base.config
  const out: Record<string, NodeChangeKind> = {}
  const anyDiff = (fields: ServiceField[]) => fields.some((f) => !sameFieldValue(f, L.service[f], B.service[f]))
  const schedDiff = SCHEDULING_FIELDS.some((f) => !sameFieldValue(f, L.scheduling[f], B.scheduling[f]))
  const has = (id: string) => graph.nodes.some((n) => n.id === id)
  const presentL = presenceOf(L)
  const presentB = presenceOf(B)

  if (has('start') && anyDiff(['serviceKey', 'displayName', 'enabled', 'requiredCapabilitiesJson', 'currency', 'inputPricePerMillion', 'outputPricePerMillion'])) out.start = 'modified'
  const trunk: Array<['guardrail' | 'prompt' | 'params' | 'cache' | 'router', ServiceField[], boolean]> = [
    ['guardrail', ['dataBoundaryPolicy', 'guardrailAction', 'maskPersonNames'], false],
    ['prompt', ['systemPromptTemplate'], false],
    ['params', ['temperaturePolicy', 'temperatureValue', 'reasoningEffort', 'openAiFastMode'], false],
    ['cache', ['responseCacheEnabled', 'responseCacheTtlSeconds'], false],
    ['router', ['failoverPolicy', 'retryPolicy', 'allowDegraded'], true],
  ]
  for (const [id, fields, withScheduling] of trunk) {
    if (!has(id) || !presentL[id]) continue
    if (!presentB[id]) out[id] = 'added'
    else if (anyDiff(fields) || (withScheduling && schedDiff)) out[id] = 'modified'
  }
  if (has('masker') && L.service.maskerDeploymentId !== null) {
    if (B.service.maskerDeploymentId === null) out.masker = 'added'
    else if (!sameFieldValue('maskerDeploymentId', L.service.maskerDeploymentId, B.service.maskerDeploymentId)) out.masker = 'modified'
  }

  const baseTargets = new Map(B.targets.map((t) => [t.key, t]))
  const localTargets = new Map(L.targets.map((t) => [t.key, t]))
  const llmFields = TARGET_FIELDS.filter((f) => f !== 'systemPromptTemplate')
  for (const node of graph.nodes) {
    if (node.type === 'llm') {
      const key = targetKeyOf(node)
      const before = baseTargets.get(key)
      const now = localTargets.get(key)
      if (node.targetId === null || !before) { out[node.id] = 'added'; continue }
      if (!now || llmFields.some((f) => !sameFieldValue(f, before[f], now[f]))) out[node.id] = 'modified'
    } else if (isBranchPrompt(node)) {
      const deploymentId = branchPromptDeploymentId(node.id) as string
      const before = B.targets.find((t) => t.deploymentId === deploymentId)?.systemPromptTemplate ?? null
      const now = L.targets.find((t) => t.deploymentId === deploymentId)?.systemPromptTemplate ?? null
      if (now === null) continue
      if (before === null) out[node.id] = 'added'
      else if (!sameFieldValue('systemPromptTemplate', before, now)) out[node.id] = 'modified'
    } else if (node.type === 'project' && node.refId) {
      const inBase = B.accessProjectIds.includes(node.refId)
      const inLocal = L.accessProjectIds.includes(node.refId)
      if (inLocal && !inBase) out[node.id] = 'added'
      else if (!inLocal && inBase) out[node.id] = 'modified'
    }
  }
  return out
}
