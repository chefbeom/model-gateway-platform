export interface HistoryState<T> { past: T[]; future: T[]; limit: number }

export function createHistory<T>(limit = 100): HistoryState<T> {
  return { past: [], future: [], limit: Math.max(1, Math.floor(limit)) }
}

function capped<T>(items: T[], limit: number): T[] {
  return items.length > limit ? items.slice(items.length - limit) : items
}

/** Records `previous` (the state before an edit); clears the redo stack. */
export function recordHistory<T>(history: HistoryState<T>, previous: T): HistoryState<T> {
  return { past: capped([...history.past, previous], history.limit), future: [], limit: history.limit }
}

export function undoHistory<T>(history: HistoryState<T>, current: T): { history: HistoryState<T>; state: T } | null {
  if (history.past.length === 0) return null
  const state = history.past[history.past.length - 1]
  return {
    history: { past: history.past.slice(0, -1), future: capped([...history.future, current], history.limit), limit: history.limit },
    state,
  }
}

export function redoHistory<T>(history: HistoryState<T>, current: T): { history: HistoryState<T>; state: T } | null {
  if (history.future.length === 0) return null
  const state = history.future[history.future.length - 1]
  return {
    history: { past: capped([...history.past, current], history.limit), future: history.future.slice(0, -1), limit: history.limit },
    state,
  }
}
