// In-memory draft keeper (module scope only). Never touches browser storage.
import type { FlowGraph, ServiceFlowSnapshot, Viewport } from './types'

export interface FlowDraft {
  userId: string
  organizationId: string
  serviceId: string | null
  base: ServiceFlowSnapshot
  graph: FlowGraph
  viewport: Viewport
  savedAt: number
}

export const MAX_DRAFTS = 10

const drafts = new Map<string, FlowDraft>()

export function draftKey(userId: string, organizationId: string, serviceId: string | null): string {
  return [userId, organizationId, serviceId ?? '\u0000new'].join('\u001f')
}

export function stashDraft(draft: FlowDraft): void {
  const key = draftKey(draft.userId, draft.organizationId, draft.serviceId)
  drafts.delete(key)
  drafts.set(key, draft)
  while (drafts.size > MAX_DRAFTS) {
    const oldest = drafts.keys().next().value
    if (oldest === undefined) break
    drafts.delete(oldest)
  }
}

/** Returns and removes the draft; null when none exists or it belongs to someone else. */
export function takeDraft(userId: string, organizationId: string, serviceId: string | null): FlowDraft | null {
  const key = draftKey(userId, organizationId, serviceId)
  const draft = drafts.get(key)
  if (!draft) return null
  drafts.delete(key)
  if (draft.userId !== userId || draft.organizationId !== organizationId || draft.serviceId !== serviceId) return null
  return draft
}

export function hasDraft(userId: string, organizationId: string, serviceId: string | null): boolean {
  return drafts.has(draftKey(userId, organizationId, serviceId))
}

export function clearDrafts(): void {
  drafts.clear()
}

type EventTargetLike = {
  addEventListener(type: string, listener: (event: Event) => void): void
  removeEventListener(type: string, listener: (event: Event) => void): void
}

/** Clears drafts on session expiry and drops drafts of other users on session changes. Returns an unsubscribe function. */
export function installDraftAutoClear(target: EventTargetLike): () => void {
  const onExpired = () => clearDrafts()
  const onSession = (event: Event) => {
    const detail = (event as { detail?: { user?: { id?: unknown } } }).detail
    const userId = typeof detail?.user?.id === 'string' ? detail.user.id : null
    if (userId === null) { clearDrafts(); return }
    for (const [key, draft] of [...drafts.entries()]) {
      if (draft.userId !== userId) drafts.delete(key)
    }
  }
  target.addEventListener('aiconnect:session-expired', onExpired)
  target.addEventListener('aiconnect:session', onSession)
  return () => {
    target.removeEventListener('aiconnect:session-expired', onExpired)
    target.removeEventListener('aiconnect:session', onSession)
  }
}
