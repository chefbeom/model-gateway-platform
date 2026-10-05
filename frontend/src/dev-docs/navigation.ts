import type { DocAudience, DocBlock, DocPage } from './types'

export type AudienceFilter = '전체' | DocAudience

export function documentHash(id: string) {
  return '#docs/' + encodeURIComponent(id)
}

export function documentIdFromHash(hash: string): string | null {
  const match = /^#\/?docs\/([^#]+)$/.exec(hash)
  if (!match) return null
  try { return decodeURIComponent(match[1]) }
  catch { return null }
}

export function filterDocuments(documents: DocPage[], audience: AudienceFilter) {
  return documents.filter(doc => audience === '전체' || doc.audience === '공통' || doc.audience === audience)
}

function blockText(block: DocBlock): string {
  switch (block.type) {
    case 'paragraph': return block.text
    case 'callout': return block.title + ' ' + block.text
    case 'steps': return block.items.map(item => item.title + ' ' + item.text + ' ' + (item.action?.label ?? '')).join(' ')
    case 'checklist': return block.items.join(' ')
    case 'cards':
    case 'flow': return block.items.map(item => (item.label ?? '') + ' ' + item.title + ' ' + item.text).join(' ')
    case 'table': return [...block.columns, ...block.rows.flat()].join(' ')
    case 'code': return block.language + ' ' + block.title + ' ' + block.code
    case 'links': return block.items.map(item => item.label + ' ' + item.description + ' ' + item.href).join(' ')
  }
}

export function searchDocuments(documents: DocPage[], query: string, audience: AudienceFilter = '전체') {
  const terms = query.trim().toLocaleLowerCase('ko-KR').split(/\s+/).filter(Boolean)
  if (!terms.length) return []
  return filterDocuments(documents, audience).filter(doc => {
    const text = [doc.title, doc.shortTitle, doc.description, ...doc.keywords,
      ...doc.sections.flatMap(section => [section.title, section.description ?? '', ...section.blocks.map(blockText)])
    ].join(' ').toLocaleLowerCase('ko-KR')
    return terms.every(term => text.includes(term))
  })
}
