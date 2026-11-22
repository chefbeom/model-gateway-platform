import type { DocAudience, DocBlock, DocPage } from './types'

export type AudienceFilter = '전체' | DocAudience

export const legacyDocAliases: Record<string, string> = {
  quickstart: 'guide-member',
  concepts: 'overview',
  'user-portal': 'guide-member',
  api: 'guide-member',
  'admin-first-run': 'guide-admin',
  'resource-ownership-and-onboarding': 'guide-admin',
  'api-playground-and-structure': 'guide-member',
  runtime: 'runtime-routing',
  routing: 'runtime-routing',
  'external-provider': 'runtime-routing',
  'model-playground': 'runtime-routing',
  'data-protection': 'data-protection-quotas',
  'cost-and-quotas': 'data-protection-quotas',
  'request-diagnostics': 'diagnostics',
  'runtime-diagnostics': 'diagnostics',
  troubleshooting: 'diagnostics',
  operations: 'operations-deploy',
  'security-timeout': 'operations-deploy',
  'deployment-profiles': 'operations-deploy'
}

export function resolveDocumentId(id: string): string {
  return legacyDocAliases[id] ?? id
}

export function documentHash(id: string) {
  return '#docs/' + encodeURIComponent(id)
}

export function documentIdFromHash(hash: string): string | null {
  const match = /^#\/?docs\/([^#]+)$/.exec(hash)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

export function filterDocuments(documents: DocPage[], audience: AudienceFilter) {
  if (audience === '전체') return documents
  return documents.filter(
    doc => doc.audience === '공통' || doc.audience === audience || Boolean(doc.audiences?.includes(audience))
  )
}

function blockText(block: DocBlock): string {
  switch (block.type) {
    case 'paragraph':
      return block.text
    case 'callout':
      return `${block.title} ${block.text}`
    case 'steps':
      return block.items.map(item => `${item.title} ${item.text} ${item.action?.label ?? ''}`).join(' ')
    case 'checklist':
      return block.items.join(' ')
    case 'cards':
    case 'flow':
      return block.items.map(item => `${item.label ?? ''} ${item.title} ${item.text}`).join(' ')
    case 'table':
      return [...block.columns, ...block.rows.flat()].join(' ')
    case 'code':
      return `${block.language} ${block.title} ${block.code}`
    case 'links':
      return block.items.map(item => `${item.label} ${item.description} ${item.href}`).join(' ')
    case 'mockup':
      return [
        block.screen,
        block.roleTag ?? '',
        block.stepLabel ?? '',
        block.caption ?? '',
        ...block.fields.flatMap(f => [f.label, f.value, f.hint ?? '', f.badge ?? '']),
        ...(block.actions ?? []).map(a => a.label),
        block.result?.title ?? '',
        ...(block.result?.items ?? []),
        block.navigate?.label ?? ''
      ].join(' ')
  }
}

export function searchDocuments(documents: DocPage[], query: string, audience: AudienceFilter = '전체') {
  const terms = query.trim().toLocaleLowerCase('ko-KR').split(/\s+/).filter(Boolean)
  if (!terms.length) return []
  return filterDocuments(documents, audience).filter(doc => {
    const text = [
      doc.title,
      doc.shortTitle,
      doc.description,
      doc.audience,
      ...(doc.audiences ?? []),
      ...doc.keywords,
      ...doc.sections.flatMap(section => [section.title, section.description ?? '', ...section.blocks.map(blockText)])
    ]
      .join(' ')
      .toLocaleLowerCase('ko-KR')
    return terms.every(term => text.includes(term))
  })
}
