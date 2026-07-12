<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

type AuditEvent = {
  id: string
  organizationId?: string | null
  actorUserId?: string | null
  actorEmail: string
  action: string
  resourceType: string
  resourceId?: string | null
  detailJson?: string | null
  createdAt: string
}
type AuditPage = { items: AuditEvent[]; page: number; size: number; totalElements: number; totalPages: number }

const props = defineProps<{ organizationId: string; auth: AdminAuth; platformAdmin: boolean }>()
const result = ref<AuditPage>({ items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
const action = ref('')
const resourceType = ref('')
const period = ref<'today' | '7d' | '30d' | 'all'>('30d')
const platformScope = ref(false)
const busy = ref(false)
const message = ref('감사 이벤트를 준비하고 있습니다.')
const selected = ref<AuditEvent | null>(null)
const detailOpen = ref(false)

const scopeLabel = computed(() => platformScope.value ? '전체 플랫폼' : '선택 조직')
const canLoad = computed(() => platformScope.value ? props.platformAdmin : Boolean(props.organizationId))

function dateRange(query: URLSearchParams) {
  if (period.value === 'all') return
  const end = new Date()
  const start = new Date(end)
  if (period.value === 'today') start.setHours(0, 0, 0, 0)
  if (period.value === '7d') start.setDate(start.getDate() - 7)
  if (period.value === '30d') start.setDate(start.getDate() - 30)
  query.set('from', start.toISOString())
  query.set('to', end.toISOString())
}

async function load(page = 0) {
  if (!canLoad.value) {
    result.value = { items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }
    message.value = platformScope.value ? '전체 플랫폼 감사 기록은 플랫폼 관리자만 조회할 수 있습니다.' : 'Workspace에서 조직을 선택하세요.'
    return
  }
  busy.value = true
  try {
    const query = new URLSearchParams({ page: String(Math.max(0, page)), size: '50' })
    if (action.value.trim()) query.set('action', action.value.trim())
    if (resourceType.value.trim()) query.set('resourceType', resourceType.value.trim())
    dateRange(query)
    const path = platformScope.value
      ? `/api/admin/audit-logs?${query}`
      : `/api/admin/organizations/${props.organizationId}/audit-logs?${query}`
    result.value = await adminFetch<AuditPage>(path, props.auth)
    message.value = `${scopeLabel.value} · 감사 이벤트 ${new Intl.NumberFormat('ko-KR').format(result.value.totalElements)}건`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '감사 기록을 조회하지 못했습니다.'
  } finally {
    busy.value = false
  }
}

function inspect(item: AuditEvent) { selected.value = item; detailOpen.value = true }
function formattedDetails(item?: AuditEvent | null) {
  if (!item?.detailJson) return '상세 정보 없음'
  try { return JSON.stringify(JSON.parse(item.detailJson), null, 2) }
  catch { return item.detailJson }
}
function resetFilters() { action.value = ''; resourceType.value = ''; period.value = '30d'; void load(0) }

watch(() => props.organizationId, () => { if (!platformScope.value) void load(0) })
watch(() => props.platformAdmin, value => { if (!value && platformScope.value) { platformScope.value = false; void load(0) } })
onMounted(() => { void load(0) })
</script>

<template>
  <section class="page-stack audit-page">
    <div class="page-hero">
      <div><p class="eyebrow">AUDIT TRAIL</p><h1>관리자 감사 로그</h1><p>권한·프로젝트·Runtime·서비스 설정 변경과 민감 정보 열람 이력을 추적합니다.</p></div>
      <button class="secondary-button" :disabled="busy || !canLoad" @click="load(result.page)">↻ 새로고침</button>
    </div>

    <div class="scope-banner">
      <span>AUDIT</span>
      <div><strong>{{ scopeLabel }}</strong><small>감사 로그는 변경할 수 없는 운영 증적입니다. API 키·Token 원문은 저장하거나 표시하지 않습니다.</small></div>
      <label v-if="platformAdmin" class="scope-toggle"><input v-model="platformScope" type="checkbox" @change="load(0)" /> 모든 조직 포함</label>
    </div>
    <p class="inline-alert">{{ message }}</p>

    <article class="surface-card">
      <div class="filter-bar audit-filters">
        <label><span>작업</span><input v-model.trim="action" placeholder="PROJECT, USER, RUNTIME…" @keyup.enter="load(0)" /></label>
        <label><span>리소스</span><input v-model.trim="resourceType" placeholder="PROJECT, APP_USER…" @keyup.enter="load(0)" /></label>
        <label><span>기간</span><select v-model="period"><option value="today">오늘</option><option value="7d">최근 7일</option><option value="30d">최근 30일</option><option value="all">전체 기간</option></select></label>
        <button class="secondary-button" :disabled="busy || !canLoad" @click="load(0)">필터 적용</button>
        <button class="text-button" :disabled="busy" @click="resetFilters">초기화</button>
      </div>

      <div v-if="result.items.length" class="data-table-wrap">
        <table class="data-table audit-table">
          <thead><tr><th>시간</th><th>실행자</th><th>작업</th><th>리소스</th><th>식별자</th><th></th></tr></thead>
          <tbody><tr v-for="item in result.items" :key="item.id">
            <td>{{ new Date(item.createdAt).toLocaleString() }}</td>
            <td><strong>{{ item.actorEmail }}</strong><small v-if="item.actorUserId" class="mono">{{ item.actorUserId }}</small></td>
            <td><span class="status-chip tiny unknown">{{ item.action }}</span></td>
            <td>{{ item.resourceType }}</td>
            <td class="mono clipped">{{ item.resourceId ?? '-' }}</td>
            <td><button class="text-button" @click="inspect(item)">상세</button></td>
          </tr></tbody>
        </table>
      </div>
      <div v-else class="empty-state"><span>◎</span><h3>조건에 맞는 감사 기록이 없습니다</h3><p>기간이나 필터를 변경해 보세요.</p></div>

      <footer v-if="result.totalPages > 1" class="pagination-bar">
        <button class="secondary-button" :disabled="busy || result.page <= 0" @click="load(result.page - 1)">이전</button>
        <span>{{ result.page + 1 }} / {{ result.totalPages }}</span>
        <button class="secondary-button" :disabled="busy || result.page + 1 >= result.totalPages" @click="load(result.page + 1)">다음</button>
      </footer>
    </article>

    <BaseModal :open="detailOpen" title="감사 이벤트 상세" :description="selected?.action" size="lg" @close="detailOpen = false">
      <div v-if="selected" class="audit-detail">
        <dl><div><dt>시간</dt><dd>{{ new Date(selected.createdAt).toLocaleString() }}</dd></div><div><dt>실행자</dt><dd>{{ selected.actorEmail }}</dd></div><div><dt>작업</dt><dd>{{ selected.action }}</dd></div><div><dt>리소스</dt><dd>{{ selected.resourceType }} · {{ selected.resourceId ?? '-' }}</dd></div><div><dt>조직</dt><dd class="mono">{{ selected.organizationId ?? '플랫폼 공통' }}</dd></div></dl>
        <div><span class="detail-label">저장된 상세 정보</span><pre>{{ formattedDetails(selected) }}</pre></div>
      </div>
      <template #footer><button class="primary-button" @click="detailOpen = false">확인</button></template>
    </BaseModal>
  </section>
</template>

<style scoped>
.audit-page .scope-banner{display:grid;grid-template-columns:58px minmax(0,1fr) auto;gap:13px;align-items:center;padding:13px 15px;border:1px solid var(--accent-border);border-radius:13px;background:var(--accent-dim)}.audit-page .scope-banner>span{height:34px;display:grid;place-items:center;border-radius:9px;background:var(--surface);color:var(--accent-strong);font-size:9px;font-weight:900;letter-spacing:.08em}.audit-page .scope-banner strong,.audit-page .scope-banner small{display:block}.audit-page .scope-banner strong{font-size:12px}.audit-page .scope-banner small{margin-top:4px;color:var(--muted);font-size:9px;line-height:1.5}.scope-toggle{display:flex;align-items:center;gap:7px;color:var(--muted);font-size:10px;font-weight:800}.audit-filters{display:grid;grid-template-columns:minmax(190px,1fr) minmax(190px,1fr) 150px auto auto;align-items:end}.audit-filters label{display:grid;gap:6px}.audit-filters label>span,.detail-label{color:var(--muted);font-size:9px;font-weight:800}.audit-filters input,.audit-filters select{min-height:40px;padding:0 12px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--text);font:inherit;font-size:10px}.audit-table td small{display:block;margin-top:4px;color:var(--faint);font-size:7px}.pagination-bar{display:flex;justify-content:center;align-items:center;gap:14px;padding:14px;border-top:1px solid var(--border)}.pagination-bar span{color:var(--muted);font-size:10px}.audit-detail{display:grid;gap:18px}.audit-detail dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:0}.audit-detail dl>div{padding:12px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2)}.audit-detail dt{color:var(--muted);font-size:8px}.audit-detail dd{margin:5px 0 0;font-size:10px;font-weight:800}.audit-detail pre{max-height:330px;overflow:auto;margin:7px 0 0;padding:14px;border:1px solid var(--border);border-radius:10px;background:var(--surface-2);color:var(--text);font-size:9px;line-height:1.6;white-space:pre-wrap;word-break:break-word}@media(max-width:980px){.audit-filters{grid-template-columns:1fr 1fr}.audit-page .scope-banner{grid-template-columns:58px 1fr}.scope-toggle{grid-column:1/-1}}@media(max-width:620px){.audit-filters,.audit-detail dl{grid-template-columns:1fr}.audit-page .scope-banner{grid-template-columns:1fr}.audit-page .scope-banner>span{width:58px}}
</style>
