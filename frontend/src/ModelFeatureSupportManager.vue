<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { adminFetch, type AdminAuth } from './api'
import ModelFeatureBadges from './ModelFeatureBadges.vue'

type SupportState = 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN'
type Model = { id:string; providerModelId:string; displayName:string; capabilitiesJson:string; featureSupportJson?:string|null; reasoningEffort?:string|null; serviceTier?:string|null }
type Support = { vision:SupportState; thinking:SupportState; fast:SupportState; reasoningLevels:SupportState; reasoningEfforts:string[] }

const props = defineProps<{ providerId?:string; runtime?:boolean; models:Model[]; auth:AdminAuth }>()
const emit = defineEmits<{ saved:[model:Model] }>()
const selectedModelId = ref('')
const support = ref<Support>(emptySupport())
const saving = ref(false)
const error = ref('')
const message = ref('')
const selected = computed(() => props.models.find(model => model.id === selectedModelId.value) ?? null)
const efforts = ['MINIMAL','LOW','MEDIUM','HIGH','XHIGH','MAX']

function emptySupport():Support { return { vision:'UNKNOWN',thinking:'UNKNOWN',fast:'UNKNOWN',reasoningLevels:'UNKNOWN',reasoningEfforts:[] } }
function readSupport(model:Model|null|undefined):Support {
  if (!model) return emptySupport()
  let capabilities:string[]=[]
  try { const value=JSON.parse(model.capabilitiesJson||'[]'); if(Array.isArray(value))capabilities=value.filter((item):item is string=>typeof item==='string').map(item=>item.toUpperCase()) } catch { /* unknown */ }
  let saved:Partial<Support>={}
  try { const value=model.featureSupportJson?JSON.parse(model.featureSupportJson):{}; if(value&&typeof value==='object'&&!Array.isArray(value))saved=value } catch { /* fall back to registered capability policy */ }
  const get=(key:keyof Omit<Support,'reasoningEfforts'>, capability?:string):SupportState=>{
    const value=saved[key]
    if(value==='SUPPORTED'||value==='UNSUPPORTED'||value==='UNKNOWN')return value
    return capability&&capabilities.includes(capability)?'SUPPORTED':'UNKNOWN'
  }
  return {vision:get('vision','VISION'),thinking:get('thinking','REASONING'),fast:get('fast'),reasoningLevels:get('reasoningLevels'),reasoningEfforts:Array.isArray(saved.reasoningEfforts)?saved.reasoningEfforts.filter((value):value is string=>typeof value==='string'&&efforts.includes(value)):[]}
}
function encode() { return JSON.stringify({ ...support.value, reasoningEfforts:support.value.reasoningLevels==='SUPPORTED'?support.value.reasoningEfforts:[] }) }
function syncModel() { support.value=readSupport(selected.value); error.value=''; message.value='' }
watch(()=>props.models, list=>{ if(!list.some(model=>model.id===selectedModelId.value))selectedModelId.value=list[0]?.id??''; syncModel() },{deep:true,immediate:true})
watch(selectedModelId,syncModel)
async function save() {
  if(!selected.value||(!props.runtime&&!props.providerId))return
  saving.value=true;error.value='';message.value=''
  try {
    const path=props.runtime?`/api/admin/model-deployments/${selected.value.id}`:`/api/admin/external-providers/${props.providerId}/models/${selected.value.id}`
    const saved=await adminFetch<Model>(path,props.auth,{method:'PATCH',body:JSON.stringify({featureSupportJson:encode()})})
    emit('saved',saved);message.value='모델 기능 지원 정보를 저장했습니다.'
  } catch(value) { error.value=value instanceof Error?value.message:'모델 기능 정보를 저장하지 못했습니다.' }
  finally { saving.value=false }
}
</script>

<template>
  <article v-if="models.length" class="surface-card feature-support-manager">
    <header class="card-header"><div><span class="card-kicker">MODEL FEATURE SUPPORT</span><h2>모델 기능 지원 정보</h2><p>Provider가 기능 메타데이터를 제공하지 않으면 실제 문서나 호출 검증으로 확인한 항목만 지정하세요.</p></div></header>
    <div class="feature-support-body">
      <label class="field">모델<select v-model="selectedModelId"><option v-for="model in models" :key="model.id" :value="model.id">{{ model.displayName }} · {{ model.providerModelId }}</option></select></label>
      <ModelFeatureBadges v-if="selected" :capabilities="selected.capabilitiesJson" :feature-support-json="encode()" :reasoning-effort="selected.reasoningEffort" :service-tier="selected.serviceTier" />
      <p class="feature-support-note">‘확인 안 됨’은 미지원이 아니라 근거가 아직 등록되지 않았다는 뜻입니다. FAST tier 기본값·단가와 추론 기본값은 모델 지원 여부를 증명하지 않습니다.</p>
      <div class="form-grid">
        <label class="field">Vision / 이미지 입력<select v-model="support.vision"><option value="UNKNOWN">확인 안 됨</option><option value="SUPPORTED">지원 확인</option><option value="UNSUPPORTED">미지원 확인</option></select></label>
        <label class="field">Thinking / 추론 기능<select v-model="support.thinking"><option value="UNKNOWN">확인 안 됨</option><option value="SUPPORTED">지원 확인</option><option value="UNSUPPORTED">미지원 확인</option></select></label>
        <label class="field">Fast mode<select v-model="support.fast"><option value="UNKNOWN">확인 안 됨</option><option value="SUPPORTED">지원 확인</option><option value="UNSUPPORTED">미지원 확인</option></select></label>
        <label class="field">추론 수준 구분<select v-model="support.reasoningLevels"><option value="UNKNOWN">확인 안 됨</option><option value="SUPPORTED">지원 확인</option><option value="UNSUPPORTED">미지원 확인</option></select></label>
      </div>
      <fieldset v-if="support.reasoningLevels==='SUPPORTED'" class="effort-picker"><legend>확인된 추론 수준</legend><label v-for="effort in efforts" :key="effort"><input v-model="support.reasoningEfforts" type="checkbox" :value="effort" />{{ effort }}</label></fieldset>
      <div class="feature-support-footer"><small>이 정보는 라우팅 기본 설정이나 실제 요청 파라미터를 바꾸지 않습니다.</small><button class="primary-button" :disabled="saving||!selected" @click="save">{{ saving?'저장 중…':'지원 정보 저장' }}</button></div>
      <p v-if="error" class="feature-support-error">{{ error }}</p><p v-if="message" class="feature-support-success">{{ message }}</p>
    </div>
  </article>
</template>

<style scoped>
.feature-support-manager{overflow:hidden}.feature-support-manager .card-header p{margin:5px 0 0;color:var(--muted);font-size:10px;line-height:1.5}.feature-support-body{padding:15px;display:grid;gap:11px}.feature-support-note{margin:0;padding:9px 11px;border:1px solid var(--border);border-radius:9px;background:var(--surface-2);color:var(--muted);font-size:9px;line-height:1.5}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.effort-picker{margin:0;padding:10px 12px;display:flex;gap:13px;flex-wrap:wrap;border:1px solid var(--border);border-radius:9px}.effort-picker legend{padding:0 5px;color:var(--text-soft);font-size:10px;font-weight:700}.effort-picker label{display:flex;align-items:center;gap:5px;color:var(--text-soft);font-size:9px}.effort-picker input{accent-color:var(--accent-strong)}.feature-support-footer{display:flex;justify-content:space-between;align-items:center;gap:10px}.feature-support-footer small{color:var(--muted);font-size:9px}.feature-support-footer .primary-button{min-height:34px;font-size:10px}.feature-support-error,.feature-support-success{margin:0;font-size:10px}.feature-support-error{color:var(--danger)}.feature-support-success{color:var(--accent-strong)}@media(max-width:650px){.form-grid{grid-template-columns:1fr}.feature-support-footer{align-items:flex-start;flex-direction:column}}
</style>
