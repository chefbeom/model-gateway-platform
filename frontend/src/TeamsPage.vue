<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaseModal from './BaseModal.vue'
import { adminFetch, type AdminAuth } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth }>()
type Team = { id: string; organizationId: string; name: string; status: string }

const teams = ref<Team[]>([])
const busy = ref(false)
const message = ref('')
const modalOpen = ref(false)
const name = ref('')
const activeTeams = computed(() => teams.value.filter(team => team.status === 'ACTIVE'))

async function load() {
  if (!props.organizationId) {
    teams.value = []
    return
  }
  busy.value = true
  try {
    teams.value = await adminFetch<Team[]>(`/api/admin/organizations/${props.organizationId}/teams`, props.auth)
  } catch (error) {
    message.value = error instanceof Error ? error.message : '팀 목록을 불러오지 못했습니다.'
  } finally {
    busy.value = false
  }
}

async function create() {
  if (!name.value.trim()) return
  busy.value = true
  try {
    const created = await adminFetch<Team>(`/api/admin/organizations/${props.organizationId}/teams`, props.auth, {
      method: 'POST',
      body: JSON.stringify({ name: name.value.trim() })
    })
    teams.value = [...teams.value, created].sort((a, b) => a.name.localeCompare(b.name))
    name.value = ''
    modalOpen.value = false
    message.value = '팀이 생성되었습니다. 설정 메뉴에서 사용자를 팀에 배정할 수 있습니다.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '팀 생성에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

watch(() => props.organizationId, load)
onMounted(load)
</script>

<template>
  <section class="page-stack">
    <div class="page-hero">
      <div>
        <p class="eyebrow">ORGANIZATION ACCESS</p>
        <h1>팀 및 부서</h1>
        <p>팀은 프로젝트, API 키, 사용량, 감사 범위를 함께 묶는 회사 내부 운영 단위입니다.</p>
      </div>
      <button class="primary-button" :disabled="!organizationId" @click="modalOpen = true">+ 팀 생성</button>
    </div>

    <p v-if="message" class="inline-alert">{{ message }}</p>

    <div v-if="activeTeams.length" class="quick-grid">
      <article v-for="team in activeTeams" :key="team.id" class="quick-action">
        <span class="policy-icon">◈</span>
        <div>
          <span class="card-kicker">TEAM SPACE</span>
          <b>{{ team.name }}</b>
          <small>프로젝트 소유권·사용자 역할·감사 범위 관리</small>
        </div>
        <span class="status-chip tiny healthy">{{ team.status }}</span>
      </article>
    </div>

    <div v-else class="surface-card empty-state">
      <span>◈</span>
      <h3>등록된 팀이 없습니다</h3>
      <p>예: AI Platform, 고객지원, 마케팅. 팀을 만든 뒤 프로젝트와 사용자 계정을 연결하세요.</p>
      <button class="text-button" :disabled="!organizationId" @click="modalOpen = true">첫 팀 생성</button>
    </div>

    <article class="surface-card">
      <header class="card-header"><div><span class="card-kicker">ROLE GUIDE</span><h2>팀 역할 기준</h2></div></header>
      <div class="data-table-wrap">
        <table class="data-table">
          <thead><tr><th>역할</th><th>권한</th><th>권장 대상</th></tr></thead>
          <tbody>
            <tr><td><strong>TEAM_ADMIN</strong></td><td>팀 사용자 배정 및 팀 프로젝트 관리</td><td>부서 운영 담당자</td></tr>
            <tr><td><strong>PROJECT_OWNER</strong></td><td>자기 팀 프로젝트·API 키·쿼터 관리</td><td>서비스 개발 리더</td></tr>
            <tr><td><strong>DEVELOPER</strong></td><td>자기 팀 프로젝트의 사용량·요청 내역 확인</td><td>일반 개발자</td></tr>
            <tr><td><strong>AUDITOR</strong></td><td>요청 감사 및 암호화된 원문 열람</td><td>보안·감사 담당자</td></tr>
          </tbody>
        </table>
      </div>
    </article>

    <BaseModal :open="modalOpen" title="새 팀 만들기" description="프로젝트 소유권과 사용자 권한을 나누는 회사 내부 팀 또는 부서를 등록합니다." size="sm" @close="modalOpen = false">
      <label class="field">팀 이름<input v-model.trim="name" maxlength="120" placeholder="AI Platform Team" @keyup.enter="create" /></label>
      <template #footer>
        <button class="secondary-button" @click="modalOpen = false">취소</button>
        <button class="primary-button" :disabled="busy || !name" @click="create">팀 생성</button>
      </template>
    </BaseModal>
  </section>
</template>
