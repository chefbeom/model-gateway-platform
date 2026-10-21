import { readonly, ref } from 'vue'

export type ClockFormat = '24h' | '12h'
type DateValue = string | number | Date | null | undefined

export const CLOCK_FORMAT_STORAGE_KEY = 'aiconnect.clockFormat'

function normalizeClockFormat(value: unknown): ClockFormat {
  return value === '12h' ? '12h' : '24h'
}

function readClockFormat(): ClockFormat {
  try {
    return normalizeClockFormat(window.localStorage.getItem(CLOCK_FORMAT_STORAGE_KEY))
  } catch {
    return '24h'
  }
}

const preference = ref<ClockFormat>(readClockFormat())
export const clockFormat = readonly(preference)

export function setClockFormat(value: ClockFormat) {
  preference.value = normalizeClockFormat(value)
  try {
    window.localStorage.setItem(CLOCK_FORMAT_STORAGE_KEY, preference.value)
  } catch {
    // Storage can be unavailable; keep the display setting for this session.
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('storage', event => {
    if (event.key !== CLOCK_FORMAT_STORAGE_KEY && event.key !== null) return
    try {
      if (event.storageArea === window.localStorage) preference.value = normalizeClockFormat(event.newValue)
    } catch {
      // Ignore cross-tab updates if this browser blocks access to storage.
    }
  })
}

function parseDate(value: DateValue): Date | null {
  if (value == null || value === '') return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isFinite(date.getTime()) ? date : null
}

const pad = (value: number) => String(value).padStart(2, '0')
const datePart = (date: Date) => `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`

export function formatDate(value: DateValue): string {
  const date = parseDate(value)
  return date ? datePart(date) : '-'
}

/** Display the same instant in the browser's local timezone, with seconds. */
export function formatDateTime(value: DateValue, format: ClockFormat = clockFormat.value): string {
  const date = parseDate(value)
  if (!date) return '-'
  const hours = date.getHours()
  const time = `${pad(format === '12h' ? hours % 12 || 12 : hours)}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  return `${datePart(date)} ${format === '12h' ? (hours < 12 ? '오전 ' : '오후 ') : ''}${time}`
}

export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || '브라우저 현지 시간'
}
