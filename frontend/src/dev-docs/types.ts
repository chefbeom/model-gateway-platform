export type DocsDestination =
  | 'dashboard'
  | 'relationship'
  | 'platform'
  | 'portal'
  | 'usage'
  | 'projects'
  | 'infrastructure'
  | 'services'
  | 'teams'
  | 'observability'
  | 'audit'
  | 'notifications'
  | 'external'
  | 'quotas'
  | 'playground'
  | 'model-playground'
  | 'data-protection'
  | 'system'

export type DocAudience = '공통' | '관리자' | '프로젝트 총괄자' | '팀원'
export type DocGroup = '시작하기' | '역할별 실전 가이드' | '인프라·정책 운영' | '진단·운영 참조'
export type CalloutTone = 'info' | 'success' | 'warning' | 'danger'

export type DocAction = {
  label: string
  destination: DocsDestination
}

export type MockupField = {
  label: string
  value: string
  hint?: string
  badge?: string
  badgeTone?: 'accent' | 'info' | 'warning' | 'danger' | 'neutral'
  mono?: boolean
}

export type MockupAction = {
  label: string
  primary?: boolean
}

export type MockupBlock = {
  type: 'mockup'
  screen: string
  roleTag?: string
  stepLabel?: string
  caption?: string
  fields: MockupField[]
  actions?: MockupAction[]
  result?: {
    tone?: CalloutTone
    title: string
    items?: string[]
  }
  navigate?: DocAction
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
  | MockupBlock

export type DocSection = {
  id: string
  title: string
  description?: string
  blocks: DocBlock[]
}

export type DocPage = {
  id: string
  group: DocGroup
  title: string
  shortTitle: string
  description: string
  audience: DocAudience
  audiences?: DocAudience[]
  minutes: number
  icon: string
  keywords: string[]
  sections: DocSection[]
}
