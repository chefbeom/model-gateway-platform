export type DocsDestination =
  | 'portal'
  | 'usage'
  | 'projects'
  | 'infrastructure'
  | 'services'
  | 'teams'
  | 'observability'
  | 'notifications'

export type DocAudience = '공통' | '사용자' | '관리자'
export type CalloutTone = 'info' | 'success' | 'warning' | 'danger'

export type DocAction = {
  label: string
  destination: DocsDestination
}

export type DocBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'callout'; tone: CalloutTone; title: string; text: string }
  | { type: 'steps'; items: Array<{ title: string; text: string; action?: DocAction }> }
  | { type: 'checklist'; items: string[] }
  | { type: 'cards'; items: Array<{ label?: string; title: string; text: string }> }
  | { type: 'flow'; items: Array<{ label: string; title: string; text: string }> }
  | { type: 'table'; columns: string[]; rows: string[][] }
  | { type: 'code'; language: string; title: string; code: string }
  | { type: 'links'; items: Array<{ label: string; href: string; description: string }> }

export type DocSection = {
  id: string
  title: string
  description?: string
  blocks: DocBlock[]
}

export type DocPage = {
  id: string
  group: '시작하기' | '사용자 가이드' | '관리자 가이드' | '운영과 참조'
  title: string
  shortTitle: string
  description: string
  audience: DocAudience
  minutes: number
  icon: string
  keywords: string[]
  sections: DocSection[]
}
