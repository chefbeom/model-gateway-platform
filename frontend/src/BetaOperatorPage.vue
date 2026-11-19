<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminFetch, type AdminAuth } from './api'
import { copyText } from './clipboard'
import { formatDateTime } from './dateTime'

type Service = { id: string; organizationId: string; logicalServiceId: string; mode: string }
type Member = { id: string; email: string; projectId: string; active: boolean }
type Invitation = { id: string; email: string; expiresAt: string; status: string }

const props = defineProps<{ auth: AdminAuth; platformAdmin: boolean }>()

const services = ref<Service[]>([])
const members = ref<Member[]>([])
const invitations = ref<Invitation[]>([])
const selected = ref('')
const email = ref('')
const expires = ref('')
const token = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)
const org = ref('')
const logical = ref('')
const operator = ref('')

const selectedService = computed(() => services.value.find(item => item.id === selected.value) ?? null)

async function load() {
  error.value = ''
  try {
    services.value = await adminFetch<Service[]>('/api/beta/operator/services', props.auth)
    if (!services.value.some(item => item.id === selected.value)) {
      selected.value = services.value[0]?.id ?? ''
    }
    await loadMembers()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '서비스를 불러오지 못했습니다.'
  }
}

async function loadMembers() {
  if (!selected.value) {
    members.value = []
    invitations.value = []
    return
  }
  const id = selected.value
  const [memberItems, invitationItems] = await Promise.all([
    adminFetch<Member[]>(`/api/beta/operator/services/${id}/members`, props.auth),
    adminFetch<Invitation[]>(`/api/beta/operator/services/${id}/invitations`, props.auth)
  ])
  if (id === selected.value) {
    members.value = memberItems
    invitations.value = invitationItems
  }
}

async function action(fn: () => Promise<void>) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '요청에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

function onServiceChange() {
  token.value = ''
  void action(loadMembers)
}

function invite() {
  void action(async () => {
    const expiryDate = new Date(expires.value)
    if (!expires.value || !Number.isFinite(expiryDate.getTime())) {
      throw new Error('유효한 만료 시각을 입력하세요.')
    }
    const result = await adminFetch<{ token: string }>(
      `/api/beta/operator/services/${selected.value}/invitations`,
      props.auth,
      { method: 'POST', body: JSON.stringify({ email: email.value, expiresAt: expiryDate.toISOString() }) }
    )
    token.value = result.token
    email.value = ''
    notice.value = '1회용 초대 코드를 발급했습니다. 이 화면을 닫으면 다시 조회할 수 없습니다.'
    await loadMembers()
  })
}

async function copyInviteToken() {
  if (!token.value) return
  try {
    await copyText(token.value)
    notice.value = '초대 코드를 클립보드에 복사했습니다.'
  } catch {
    error.value = '초대 코드를 복사하지 못했습니다. 값을 직접 선택해 복사하세요.'
  }
}

function revoke(id: string) {
  if (!window.confirm('초대를 회수하시겠습니까?')) return
  void action(async () => {
    await adminFetch(`/api/beta/operator/services/${selected.value}/invitations/${id}/revoke`, props.auth, { method: 'POST' })
    notice.value = '초대를 회수했습니다.'
    await loadMembers()
  })
}

function setMode(mode: 'LOCAL_ONLY' | 'PAUSED') {
  void action(async () => {
    await adminFetch(`/api/beta/operator/services/${selected.value}/mode`, props.auth, {
      method: 'PATCH',
      body: JSON.stringify({ mode })
    })
    await load()
    notice.value = mode === 'LOCAL_ONLY'
      ? '초대 서비스를 로컬 전용(LOCAL_ONLY) 모드로 시작했습니다.'
      : '초대 서비스의 신규 요청을 중지(PAUSED)했습니다.'
  })
}

function suspend(id: string) {
  if (!window.confirm('사용자의 접근을 중지하시겠습니까?')) return
  void action(async () => {
    await adminFetch(`/api/beta/operator/services/${selected.value}/members/${id}/suspend`, props.auth, { method: 'POST' })
    notice.value = '사용자 접근을 중지하고 연결된 API 키 사용을 차단했습니다.'
    await loadMembers()
  })
}

function create() {
  void action(async () => {
    const created = await adminFetch<Service>('/api/admin/platform/beta-services', props.auth, {
      method: 'POST',
      body: JSON.stringify({ organizationId: org.value, logicalServiceId: logical.value })
    })
    logical.value = ''
    await load()
    if (created?.id) {
      selected.value = created.id
      await loadMembers()
    }
    notice.value = '새 초대 서비스를 생성했습니다. 기본 상태는 신규 요청 중지(PAUSED)입니다.'
  })
}

function assign() {
  void action(async () => {
    await adminFetch(`/api/admin/platform/beta-services/${selected.value}/operators/${operator.value}`, props.auth, {
      method: 'PUT'
    })
    operator.value = ''
    notice.value = '선택한 초대 서비스에 운영자를 지정했습니다.'
  })
}

onMounted(() => {
  if (props.auth.accessToken) void load()
})
</script>

<template>
  <section class="page-stack beta-console">
    <div class="page-hero">
      <div>
        <p class="eyebrow">INVITATION SERVICE</p>
        <h1>초대 서비스 운영</h1>
        <p>로컬 전용 베타 · 외부 AI 호출은 지원하지 않습니다. 요청·응답 원문은 저장하지 않으며(METADATA_ONLY), 로컬 전용 대기열(LOCAL_ONLY_QUEUE)이 설정된 서비스만 시작할 수 있습니다.</p>
      </div>
      <div v-if="selectedService" class="hero-actions">
        <span class="status-chip" :class="selectedService.mode === 'LOCAL_ONLY' ? 'healthy' : 'draining'">
          <i></i>{{ selectedService.mode === 'LOCAL_ONLY' ? 'LOCAL_ONLY · 로컬 전용 활성' : 'PAUSED · 신규 요청 중지' }}
        </span>
      </div>
    </div>

    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="notice" role="status" class="inline-alert">{{ notice }}</p>

    <details v-if="platformAdmin && auth.accessToken" class="surface-card beta-card" :open="!services.length">
      <summary>플랫폼 관리자: 베타 생성 및 운영자 지정</summary>
      <div class="beta-admin-body">
        <p class="beta-note">조직과 논리 서비스 ID는 동일 조직에 속해야 합니다. 새 베타는 기본 중지(PAUSED) 상태로 생성됩니다.</p>
        <div class="beta-form-grid">
          <label class="field">조직 ID<input v-model.trim="org" placeholder="조직 UUID" /></label>
          <label class="field">논리 서비스 ID<input v-model.trim="logical" placeholder="논리 서비스 UUID" /></label>
        </div>
        <button type="button" class="primary-button" :disabled="busy || !org || !logical" @click="create">베타 생성</button>
        <div class="beta-assign-row">
          <label class="field">운영자 사용자 ID<input v-model.trim="operator" placeholder="사용자 UUID" /></label>
          <button type="button" class="secondary-button" :disabled="busy || !selected || !operator" @click="assign">선택한 베타에 운영자 지정</button>
        </div>
      </div>
    </details>

    <article v-if="!auth.accessToken" class="surface-card beta-card">
      <p class="beta-note">초대 서비스 운영은 사용자 계정 로그인이 필요합니다. 플랫폼 토큰 세션에서 로그아웃한 뒤 플랫폼 관리자 계정으로 로그인하세요.</p>
    </article>
    <article v-else-if="!services.length" class="surface-card beta-card">
      <p class="beta-note">
        {{ platformAdmin ? '등록된 초대 서비스가 없습니다. 위 플랫폼 관리자 패널에서 베타 서비스를 생성하세요.' : '관리할 초대 서비스가 없습니다. 플랫폼 관리자에게 운영 권한을 요청하세요.' }}
      </p>
    </article>

    <template v-else>
      <article class="surface-card beta-card">
        <div class="beta-toolbar">
          <label class="field">초대 서비스
            <select v-model="selected" @change="onServiceChange">
              <option v-for="s in services" :key="s.id" :value="s.id">{{ s.id }} · {{ s.mode }}</option>
            </select>
          </label>
          <div class="actions">
            <button type="button" class="primary-button" :disabled="busy || selectedService?.mode === 'LOCAL_ONLY'" @click="setMode('LOCAL_ONLY')">로컬 전용 시작</button>
            <button type="button" class="secondary-button" :disabled="busy || selectedService?.mode === 'PAUSED'" @click="setMode('PAUSED')">신규 요청 중지</button>
          </div>
        </div>
      </article>

      <article class="surface-card beta-card">
        <header class="beta-section-head">
          <span class="card-kicker">ONE-TIME INVITATION</span>
          <h2>초대 발급</h2>
          <p class="beta-note">이메일 주소 확인은 자동 수행하지 않습니다. 1회용 초대 코드는 신뢰할 수 있는 별도 경로로 전달하세요.</p>
        </header>
        <div class="beta-form-grid">
          <label class="field">초대 이메일<input v-model.trim="email" type="email" placeholder="tester@example.com" /></label>
          <label class="field">초대 만료 시각<input v-model="expires" type="datetime-local" /></label>
        </div>
        <button type="button" class="primary-button" :disabled="busy || !email || !expires" @click="invite">1회용 초대 발급</button>

        <div v-if="token" role="status" class="secret-reveal">
          <span>초대 코드 (한 번만 표시)</span>
          <code>{{ token }}</code>
          <div class="actions">
            <button type="button" class="secondary-button" @click="copyInviteToken">복사</button>
            <button type="button" class="secondary-button" @click="token = ''">닫기</button>
          </div>
        </div>
      </article>

      <article class="surface-card beta-card">
        <header class="beta-section-head">
          <span class="card-kicker">INVITATION HISTORY</span>
          <h2>초대 이력</h2>
        </header>
        <p v-if="!invitations.length" class="beta-note">발급된 초대가 없습니다.</p>
        <ul v-else class="beta-list">
          <li v-for="i in invitations" :key="i.id">
            <span>{{ i.email }} · {{ i.status }} · {{ formatDateTime(i.expiresAt) }}</span>
            <button v-if="i.status === 'PENDING'" type="button" class="secondary-button danger-text" :disabled="busy" @click="revoke(i.id)">초대 회수</button>
          </li>
        </ul>
      </article>

      <article class="surface-card beta-card">
        <header class="beta-section-head">
          <span class="card-kicker">BETA MEMBERS</span>
          <h2>초대 사용자</h2>
        </header>
        <p v-if="!members.length" class="beta-note">등록된 사용자가 없습니다.</p>
        <ul v-else class="beta-list">
          <li v-for="m in members" :key="m.id">
            <span>{{ m.email }} · {{ m.active ? '활성' : '중지' }}</span>
            <button v-if="m.active" type="button" class="secondary-button danger-text" :disabled="busy" @click="suspend(m.id)">정지 (키 모두 거부)</button>
          </li>
        </ul>
      </article>
    </template>
  </section>
</template>

<style scoped>
.beta-console { gap: 16px; }
.beta-card { padding: 20px 22px; display: grid; gap: 14px; }
.beta-card summary { cursor: pointer; font-weight: 700; font-size: 13px; }
.beta-admin-body { padding-top: 12px; display: grid; gap: 12px; }
.beta-section-head { display: grid; gap: 4px; }
.beta-section-head h2 { margin: 0; font-size: 16px; }
.beta-note { margin: 0; color: var(--muted); font-size: 12px; line-height: 1.55; }
.beta-form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.beta-assign-row { display: grid; grid-template-columns: minmax(220px, 1fr) auto; gap: 12px; align-items: end; }
.beta-toolbar { display: grid; grid-template-columns: minmax(240px, 1fr) auto; gap: 14px; align-items: end; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.beta-card > button { justify-self: start; }
.beta-admin-body > button { justify-self: start; }
.beta-list { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; }
.beta-list li { padding: 10px 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; font-size: 12px; }
@media (max-width: 720px) {
  .beta-toolbar, .beta-assign-row { grid-template-columns: 1fr; }
}
</style>
