<script setup lang="ts">
import { ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'

type Project = { id: string; name: string; status: string; canIssueApiKeys: boolean }
const props = defineProps<{ organizationId: string; organizationName?: string; userName?: string; roleLabel?: string; auth: AdminAuth }>()
const projects = ref<Project[]>([])
const state = ref<'loading' | 'ready' | 'error'>('loading')
let sequence = 0
async function load() {
  const current = ++sequence
  projects.value = []
  if (!props.organizationId) { state.value = 'ready'; return }
  state.value = 'loading'
  try {
    const result = await adminFetch<Project[]>(`/api/portal/organizations/${encodeURIComponent(props.organizationId)}/projects`, props.auth)
    if (current !== sequence) return
    projects.value = result
    state.value = 'ready'
  } catch {
    if (current === sequence) state.value = 'error'
  }
}
watch(() => [props.organizationId, props.auth.accessToken, props.auth.platformToken], () => void load(), { immediate: true })
</script>

<template>
  <section class="relationship surface-card" aria-labelledby="relationship-title">
    <header class="relationship-header"><div><span class="relationship-kicker">WORKSPACE RELATIONSHIP</span><h2 id="relationship-title">내 조직 관계도</h2><p>선택한 조직에서 현재 계정이 접근할 수 있는 프로젝트입니다.</p></div><button type="button" class="secondary-button" :disabled="state === 'loading'" @click="load">새로고침</button></header>
    <div v-if="!organizationId" class="relationship-message">먼저 왼쪽에서 조직을 선택하세요.</div>
    <div v-else-if="state === 'loading'" class="relationship-message" role="status">관계도를 불러오는 중입니다.</div>
    <div v-else-if="state === 'error'" class="relationship-message" role="alert">관계도를 불러오지 못했습니다. 로그인 상태와 조직 접근 권한을 확인해 주세요.</div>
    <div v-else class="relationship-tree">
      <div class="tree-node person-node"><span class="node-icon" aria-hidden="true">◎</span><div><small>현재 계정</small><strong>{{ userName || '로그인 계정' }}</strong><span>{{ roleLabel || '사용자' }}</span></div></div>
      <div class="tree-connector" aria-hidden="true"></div>
      <div class="tree-node organization-node"><span class="node-icon" aria-hidden="true">▦</span><div><small>선택된 조직</small><strong>{{ organizationName || '현재 조직' }}</strong><span>접근 가능한 프로젝트 {{ projects.length }}개</span></div></div>
      <div v-if="projects.length" class="project-branch"><div class="branch-label">접근 가능한 프로젝트</div><ul class="project-list"><li v-for="item in projects" :key="item.id"><div class="project-node"><span class="project-icon" aria-hidden="true">◇</span><div class="project-copy"><strong>{{ item.name }}</strong><span>{{ item.canIssueApiKeys ? 'API 키 발급 가능' : 'API 키 발급 권한 없음' }}</span></div><span class="project-state" :class="{ paused: item.status !== 'ACTIVE' }">{{ item.status === 'ACTIVE' ? '활성' : item.status === 'SUSPENDED' ? '중지' : item.status }}</span></div></li></ul></div>
      <div v-else class="relationship-message empty">이 조직에서 접근 가능한 프로젝트가 없습니다.</div>
      <p class="relationship-footnote">이 관계도는 현재 계정의 접근 범위를 나타냅니다. 팀 소속이나 다른 구성원은 표시하지 않습니다.</p>
    </div>
  </section>
</template>

<style scoped>
.relationship { overflow: hidden; }.relationship-header { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:22px 24px; border-bottom:1px solid var(--border); }.relationship-header h2 { margin:6px 0; font-size:18px; }.relationship-header p,.relationship-footnote { margin:0; color:var(--muted); font-size:12px; line-height:1.6; }.relationship-kicker { color:var(--accent-strong); font-size:10px; font-weight:800; letter-spacing:.12em; }.relationship-tree { padding:24px; }.tree-node { display:flex; align-items:center; gap:14px; max-width:560px; padding:15px; border:1px solid var(--border); border-radius:12px; background:var(--surface-2); }.organization-node { border-color:var(--accent-border); background:var(--accent-dim); }.node-icon,.project-icon { display:grid; flex:none; place-items:center; width:38px; height:38px; border:1px solid var(--accent-border); border-radius:10px; color:var(--accent-strong); }.tree-node div,.project-copy { display:grid; min-width:0; gap:4px; }.tree-node small,.tree-node span:not(.node-icon),.project-copy span { color:var(--muted); font-size:11px; }.tree-node strong,.project-copy strong { overflow-wrap:anywhere; font-size:14px; }.tree-connector { height:22px; margin-left:34px; border-left:1px solid var(--accent-border); }.project-branch { margin-left:34px; padding:18px 0 0 24px; border-left:1px solid var(--accent-border); }.branch-label { color:var(--muted); font-size:11px; font-weight:700; margin-bottom:12px; }.project-list { list-style:none; padding:0; margin:0; display:grid; gap:10px; }.project-list li { position:relative; }.project-list li::before { content:''; position:absolute; width:24px; top:50%; left:-24px; border-top:1px solid var(--accent-border); }.project-node { display:flex; align-items:center; gap:12px; padding:12px 14px; border:1px solid var(--border); border-radius:10px; background:var(--surface-2); }.project-copy { flex:1; }.project-state { flex:none; color:var(--accent-strong); font-size:11px; font-weight:700; }.project-state.paused { color:var(--muted); }.relationship-message { padding:24px; color:var(--muted); font-size:13px; line-height:1.6; }.relationship-message.empty { margin-left:34px; border-left:1px solid var(--accent-border); }.relationship-footnote { margin-top:18px; }@media(max-width:600px){.relationship-header { align-items:flex-start; padding:18px; }.relationship-tree { padding:18px; }.project-branch { margin-left:18px; padding-left:16px; }.project-list li::before { width:16px; left:-16px; }.tree-connector { margin-left:18px; }.project-node { flex-wrap:wrap; }.project-state { margin-left:auto; }}
</style>
