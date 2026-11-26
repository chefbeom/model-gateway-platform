<script setup lang="ts">
import { computed, ref } from 'vue'
import { SERVICE_KEY_PATTERN, WORKFLOW_TEMPLATES } from './catalog'
import { buildPalette, shortModelName } from './palette'
import type { FlowCommand, FlowGraph, PaletteConnectionGroup, PaletteModelItem, ServiceFlowSnapshot, WorkflowTemplateId } from './types'

const props = defineProps<{ snapshot: ServiceFlowSnapshot; graph: FlowGraph; readOnly: boolean }>()
const emit = defineEmits<{ command: [FlowCommand]; dismiss: [] }>()

const template = ref<WorkflowTemplateId | null>(null)
const projectIds = ref<string[]>([])
const deploymentIds = ref<string[]>([])

const isDraft = computed(() => props.graph.serviceId === null)
const startProps = computed(() => props.graph.nodes.find((n) => n.id === 'start')?.props ?? {})
const serviceKey = ref(typeof startProps.value.serviceKey === 'string' ? startProps.value.serviceKey : '')
const displayName = ref(typeof startProps.value.displayName === 'string' ? startProps.value.displayName : '')

const projectsHidden = computed(() => props.snapshot.projectsHidden === true)
const projects = computed(() => buildPalette(props.snapshot, props.graph, { query: '', showInactive: true, mode: 'basic' }).find((g) => g.id === 'entry')?.projects ?? [])

// Only models that can serve requests right now (availability !== 'inactive').
// Grouped by connection (same grouping as the palette).
function usableGroups(groupId: 'llm-local' | 'llm-external'): PaletteConnectionGroup[] {
  const group = buildPalette(props.snapshot, props.graph, { query: '', showInactive: true, mode: 'basic' }).find((g) => g.id === groupId)
  return (group?.connections ?? [])
    .map((c) => ({ ...c, models: c.models.filter((m) => m.availability !== 'inactive') }))
    .filter((c) => c.models.length > 0)
}
const localGroups = computed(() => usableGroups('llm-local'))
const externalGroups = computed(() => usableGroups('llm-external'))
const localModels = computed<PaletteModelItem[]>(() => localGroups.value.flatMap((c) => c.models))
const externalModels = computed<PaletteModelItem[]>(() => externalGroups.value.flatMap((c) => c.models))

const externalDisabled = computed(() => template.value === 'internal-only')

const selectedLocal = computed(() => deploymentIds.value.filter((id) => localModels.value.some((m) => m.deploymentId === id)))
const selectedExternal = computed(() => deploymentIds.value.filter((id) => externalModels.value.some((m) => m.deploymentId === id)))

const templateHint = computed(() => {
  switch (template.value) {
    case 'local-first': return '로컬 LLM과 외부 LLM을 하나 이상씩 고르세요. 로컬이 먼저, 외부는 Failover로 쓰입니다.'
    case 'internal-only': return '로컬 LLM만 고를 수 있습니다. 2개 이상이면 라우터가 자동으로 추가됩니다.'
    case 'basic': return 'LLM을 하나 이상 고르세요. 2개 이상이면 라우터가 자동으로 추가됩니다.'
    default: return ''
  }
})

const modelError = computed<string | null>(() => {
  if (template.value === 'basic' && deploymentIds.value.length === 0) return 'LLM을 하나 이상 고르세요.'
  if (template.value === 'local-first' && (selectedLocal.value.length === 0 || selectedExternal.value.length === 0)) return '로컬 LLM과 외부 LLM을 하나 이상씩 고르세요.'
  if (template.value === 'internal-only') {
    if (selectedExternal.value.length > 0) return '사내 전용 템플릿에는 로컬 LLM만 쓸 수 있습니다.'
    if (selectedLocal.value.length === 0) return '로컬 LLM을 하나 이상 고르세요.'
  }
  return null
})

const keyError = computed<string | null>(() => {
  if (!isDraft.value) return null
  const key = serviceKey.value.trim()
  if (key === '') return '호출 이름을 입력하세요.'
  if (!SERVICE_KEY_PATTERN.test(key)) return '호출 이름은 영문 소문자·숫자·하이픈만 쓸 수 있고 소문자나 숫자로 시작해야 합니다.'
  return null
})
const nameError = computed<string | null>(() => (isDraft.value && displayName.value.trim() === '' ? '표시 이름을 입력하세요.' : null))

const canSubmit = computed(() => !props.readOnly && template.value !== null && modelError.value === null && keyError.value === null && nameError.value === null)

function chooseTemplate(id: WorkflowTemplateId) {
  template.value = id
  if (id === 'internal-only') deploymentIds.value = deploymentIds.value.filter((d) => localModels.value.some((m) => m.deploymentId === d))
}

function toggle(list: string[], id: string, on: boolean): string[] {
  return on ? (list.includes(id) ? list : [...list, id]) : list.filter((x) => x !== id)
}
function onProject(id: string, event: Event) {
  projectIds.value = toggle(projectIds.value, id, (event.target as HTMLInputElement).checked)
}
function onModel(id: string, event: Event) {
  deploymentIds.value = toggle(deploymentIds.value, id, (event.target as HTMLInputElement).checked)
}

function create() {
  if (!canSubmit.value || template.value === null) return
  emit('command', { type: 'applyTemplate', template: template.value, projectIds: [...projectIds.value], deploymentIds: [...deploymentIds.value] })
  if (isDraft.value) {
    emit('command', { type: 'updateProps', nodeId: 'start', patch: { serviceKey: serviceKey.value.trim(), displayName: displayName.value.trim() } })
  }
}
</script>

<template>
  <section class="fd-quick" aria-labelledby="fd-quick-title">
    <header class="fd-quick-head">
      <div>
        <h2 id="fd-quick-title" class="fd-quick-title">워크플로우 빠른 시작</h2>
        <p class="fd-quick-lead">템플릿을 고르면 노드와 간선이 한 번에 만들어집니다. 변경은 '변경 검토 → 적용' 후에만 저장됩니다.</p>
      </div>
      <button type="button" class="secondary-button fd-small" @click="emit('dismiss')">직접 구성</button>
    </header>

    <div class="fd-templates" role="group" aria-label="템플릿 선택">
      <button
        v-for="item in WORKFLOW_TEMPLATES"
        :key="item.id"
        type="button"
        class="fd-template"
        :class="{ 'is-active': template === item.id }"
        :aria-pressed="template === item.id"
        :disabled="readOnly"
        @click="chooseTemplate(item.id)"
      >
        <strong>{{ item.label }}</strong>
        <small>{{ item.description }}</small>
      </button>
    </div>

    <div v-if="template" class="fd-quick-form">
      <p class="fd-hint">{{ templateHint }}</p>

      <div v-if="isDraft" class="fd-identity">
        <label class="fd-field">
          <span class="fd-label">호출 이름 (model 값)</span>
          <input v-model="serviceKey" type="text" maxlength="120" placeholder="text-pro" aria-label="호출 이름 (model 값)" :disabled="readOnly" autocomplete="off" />
          <small v-if="keyError" class="fd-error">{{ keyError }}</small>
        </label>
        <label class="fd-field">
          <span class="fd-label">표시 이름</span>
          <input v-model="displayName" type="text" maxlength="200" placeholder="텍스트 프로" aria-label="표시 이름" :disabled="readOnly" autocomplete="off" />
          <small v-if="nameError" class="fd-error">{{ nameError }}</small>
        </label>
      </div>

      <fieldset class="fd-fieldset">
        <legend class="fd-label">호출을 허용할 프로젝트</legend>
        <p v-if="projectsHidden" class="fd-hint">플랫폼 토큰 세션에서는 프로젝트가 보이지 않습니다. 프로젝트 접근은 나중에 설정하세요.</p>
        <p v-else-if="projects.length === 0" class="fd-hint">표시할 프로젝트가 없습니다.</p>
        <div v-else class="fd-choices">
          <label v-for="item in projects" :key="item.projectId" class="fd-choice">
            <input type="checkbox" :checked="projectIds.includes(item.projectId)" :disabled="readOnly" :aria-label="`프로젝트 ${item.name}`" @change="onProject(item.projectId, $event)" />
            <span>{{ item.name }}</span>
            <small v-if="item.guarded" class="fd-tag">보호 정책</small>
          </label>
        </div>
      </fieldset>

      <fieldset class="fd-fieldset">
        <legend class="fd-label">LLM (사용 가능한 모델만 표시)</legend>
        <p v-if="localModels.length + externalModels.length === 0" class="fd-hint">사용 가능한 모델이 없습니다. 인프라 화면에서 모델을 활성화하세요.</p>
        <div v-if="localModels.length > 0" class="fd-choices">
          <span class="fd-subhead">로컬</span>
          <template v-for="conn in localGroups" :key="conn.id">
            <span class="fd-conn-head">{{ conn.label }}<small v-if="conn.detail"> · {{ conn.detail }}</small></span>
            <label v-for="item in conn.models" :key="item.deploymentId" class="fd-choice" :title="item.displayName">
              <input type="checkbox" :checked="deploymentIds.includes(item.deploymentId)" :disabled="readOnly" :aria-label="`로컬 LLM ${item.displayName}`" @change="onModel(item.deploymentId, $event)" />
              <span>{{ shortModelName(item.displayName, item.providerModelId) }}</span>
              <small v-if="item.availability === 'degraded'" class="fd-source">상태 확인 필요</small>
            </label>
          </template>
        </div>
        <div v-if="externalModels.length > 0" class="fd-choices">
          <span class="fd-subhead">외부</span>
          <template v-for="conn in externalGroups" :key="conn.id">
            <span class="fd-conn-head">{{ conn.label }}<small v-if="conn.detail"> · {{ conn.detail }}</small></span>
            <label v-for="item in conn.models" :key="item.deploymentId" class="fd-choice" :class="{ 'is-off': externalDisabled }" :title="item.displayName">
              <input type="checkbox" :checked="deploymentIds.includes(item.deploymentId)" :disabled="readOnly || externalDisabled" :aria-label="`외부 LLM ${item.displayName}`" @change="onModel(item.deploymentId, $event)" />
              <span>{{ shortModelName(item.displayName, item.providerModelId) }}</span>
              <small v-if="item.availability === 'degraded'" class="fd-source">상태 확인 필요</small>
            </label>
          </template>
        </div>
        <small v-if="modelError" class="fd-error" role="status">{{ modelError }}</small>
      </fieldset>

      <div class="fd-quick-actions">
        <button type="button" class="primary-button" :disabled="!canSubmit" @click="create">만들기</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.fd-quick {
  box-sizing: border-box;
  display: grid;
  gap: 14px;
  width: 100%;
  padding: 18px;
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow);
}
.fd-quick-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.fd-quick-title { margin: 0; font-size: 15px; font-weight: 800; }
.fd-quick-lead { margin: 4px 0 0; color: var(--muted); font-size: 11px; line-height: 1.5; }
.fd-small { min-height: 32px; padding: 0 12px; flex: none; }

.fd-templates { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
.fd-template {
  display: grid;
  gap: 4px;
  padding: 11px 12px;
  text-align: left;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface-2);
  color: var(--text);
  cursor: pointer;
  transition: border-color .15s, background .15s;
}
.fd-template strong { font-size: 12px; font-weight: 800; }
.fd-template small { color: var(--muted); font-size: 10px; line-height: 1.45; }
.fd-template:hover:not(:disabled) { border-color: var(--accent-border); background: var(--accent-dim); }
.fd-template.is-active { border-color: var(--accent-strong); background: var(--accent-dim); box-shadow: 0 0 0 1px var(--accent-border); }
.fd-template:disabled { cursor: not-allowed; opacity: .55; }

.fd-quick-form { display: grid; gap: 12px; }
.fd-hint { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.5; }
.fd-identity { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 10px; }
.fd-field { display: grid; gap: 5px; }
.fd-label { color: var(--text-soft); font-size: 10px; font-weight: 800; }
.fd-field input { min-height: 36px; font-size: 11px; }
.fd-error { color: var(--danger); font-size: 10px; line-height: 1.4; }

.fd-fieldset { margin: 0; padding: 0; border: 0; display: grid; gap: 6px; min-width: 0; }
.fd-fieldset legend { padding: 0; margin-bottom: 4px; }
.fd-choices { display: grid; gap: 4px; max-height: 150px; overflow-y: auto; padding: 2px; }
.fd-subhead { color: var(--muted); font-size: 9px; font-weight: 800; letter-spacing: .04em; }
.fd-conn-head { margin-top: 4px; color: var(--text-soft); font-size: 10px; font-weight: 800; }
.fd-conn-head small { color: var(--muted); font-size: 9px; font-weight: 600; }
.fd-choice { display: flex; align-items: center; gap: 8px; min-height: 30px; padding: 0 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-2); font-size: 11px; cursor: pointer; }
.fd-choice input[type='checkbox'] { flex: none; width: 16px; min-height: 16px; height: 16px; padding: 0; }
.fd-choice:hover { border-color: var(--accent-border); }
.fd-choice.is-off { opacity: .5; cursor: not-allowed; }
.fd-choice span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 700; }
.fd-source { color: var(--muted); font-size: 9px; white-space: nowrap; }
.fd-tag { margin-left: auto; padding: 1px 6px; border: 1px solid var(--warning); border-radius: 6px; background: var(--warning-dim); color: var(--warning); font-size: 8px; font-weight: 800; }

.fd-quick-actions { display: flex; justify-content: flex-end; }
.fd-quick-actions .primary-button:disabled { cursor: not-allowed; opacity: .5; }
</style>
