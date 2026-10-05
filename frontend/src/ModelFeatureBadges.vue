<script setup lang="ts">
import { computed } from 'vue'

type FeatureSupport = {
  vision?: string
  thinking?: string
  fast?: string
  reasoningLevels?: string
  reasoningEfforts?: string[]
}

const props = defineProps<{
  capabilities?: string[] | string | null
  featureSupportJson?: string | null
  reasoningEffort?: string | null
  serviceTier?: string | null
}>()

const knownStates = ['SUPPORTED', 'UNSUPPORTED', 'UNKNOWN'] as const
type SupportState = typeof knownStates[number]

const parsed = computed<FeatureSupport>(() => {
  if (!props.featureSupportJson) return {}
  try {
    const value = JSON.parse(props.featureSupportJson) as FeatureSupport
    if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
    return value
  } catch { return {} }
})

const capabilitySet = computed(() => {
  let values: unknown = props.capabilities
  if (typeof values === 'string') {
    try { values = JSON.parse(values) as unknown } catch { values = [] }
  }
  return new Set(Array.isArray(values) ? values.filter((item): item is string => typeof item === 'string').map(item => item.toUpperCase()) : [])
})

function state(key: keyof Pick<FeatureSupport, 'vision' | 'thinking' | 'fast' | 'reasoningLevels'>,
               capability?: string): SupportState {
  const value = parsed.value[key]?.toUpperCase()
  if (knownStates.includes(value as SupportState)) return value as SupportState
  return capability && capabilitySet.value.has(capability) ? 'SUPPORTED' : 'UNKNOWN'
}

function label(value: SupportState) {
  return value === 'SUPPORTED' ? '지원 확인' : value === 'UNSUPPORTED' ? '미지원 확인' : '확인 안 됨'
}
function style(value: SupportState) {
  return value === 'SUPPORTED' ? 'supported' : value === 'UNSUPPORTED' ? 'unsupported' : 'unknown'
}
function reasoningLabel(value?: string | null) {
  if (!value || value === 'REQUEST') return ''
  if (value === 'NONE') return 'NONE'
  return value
}

const features = computed(() => [
  { key: 'VISION', title: 'Vision', status: state('vision', 'VISION'), hint: '이미지 입력 capability' },
  { key: 'THINKING', title: 'Thinking', status: state('thinking', 'REASONING'), hint: '추론 특화 capability' },
  { key: 'FAST', title: 'Fast', status: state('fast'), hint: props.serviceTier === 'FAST' ? '기본 tier는 FAST로 설정됐지만 지원 여부는 별도 확인이 필요합니다.' : 'Fast 요금/기본 tier 설정만으로 지원을 단정하지 않습니다.' },
  { key: 'REASONING_LEVELS', title: '추론 단계', status: state('reasoningLevels'), hint: parsed.value.reasoningEfforts?.length ? `확인된 단계: ${parsed.value.reasoningEfforts.join(', ')}` : reasoningLabel(props.reasoningEffort) ? `요청 기본값 ${reasoningLabel(props.reasoningEffort)} · 지원 단계 목록은 미확인` : 'LOW/HIGH 등 단계별 지원 정보가 등록되지 않았습니다.' }
])
</script>

<template>
  <div class="model-feature-badges" aria-label="모델 기능 지원 정보">
    <span v-for="feature in features" :key="feature.key" class="model-feature-badge" :class="style(feature.status)" :title="feature.hint">
      <span>{{ feature.title }}</span><b>{{ label(feature.status) }}</b>
    </span>
  </div>
</template>

<style scoped>
.model-feature-badges{display:flex;flex-wrap:wrap;gap:5px;margin:8px 0}.model-feature-badge{display:inline-flex;align-items:center;gap:6px;padding:4px 7px;border:1px solid var(--border);border-radius:999px;font-size:8px;line-height:1.2}.model-feature-badge>span{color:var(--text-soft);font-weight:700}.model-feature-badge>b{font-size:7px}.model-feature-badge.supported{border-color:var(--accent-border);background:var(--accent-dim)}.model-feature-badge.supported>b{color:var(--accent-strong)}.model-feature-badge.unsupported{border-color:color-mix(in srgb,var(--danger) 35%,var(--border));background:var(--danger-dim)}.model-feature-badge.unsupported>b{color:var(--danger)}.model-feature-badge.unknown>b{color:var(--muted)}
</style>
