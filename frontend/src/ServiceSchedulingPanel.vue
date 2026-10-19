<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { adminFetch, type AdminAuth } from './api'
const props=defineProps<{serviceId:string;auth:AdminAuth}>()
type Settings={mode:'IMMEDIATE'|'LOCAL_FIRST_WAIT'|'LOCAL_ONLY_QUEUE';localWaitMs:number;maxWaitMs:number;maxQueuedRequests:number;maxQueuedBytes:number;maxQueuedPerKey:number}
type View={settings:Settings;queue:{queuedRequests:number;queuedBytes:number;oldestWaitMs:number}}
const state=ref<View|null>(null),draft=ref<Settings|null>(null),busy=ref(false),message=ref('')
let generation=0
async function load(reset=false) {
  const id=props.serviceId,seq=++generation
  try { const value=await adminFetch<View>(`/api/admin/services/${id}/scheduling`,props.auth)
    if(seq!==generation||id!==props.serviceId)return
    state.value=value;if(reset||!draft.value)draft.value={...value.settings}
  } catch(e) {if(seq===generation)message.value=e instanceof Error?e.message:'대기 정책을 불러오지 못했습니다.'}
}
async function save() {
  if(!draft.value)return
  const id=props.serviceId;busy.value=true;message.value=''
  try {const value=await adminFetch<View>(`/api/admin/services/${id}/scheduling`,props.auth,{method:'PATCH',body:JSON.stringify(draft.value)})
    if(id===props.serviceId){state.value=value;draft.value={...value.settings};message.value='대기 정책을 저장했습니다.'}
  }catch(e){if(id===props.serviceId)message.value=e instanceof Error?e.message:'저장 실패'}finally{busy.value=false}
}
watch(()=>props.serviceId,()=>{state.value=null;draft.value=null;message.value='';void load(true)},{immediate:true})
const timer=setInterval(()=>{if(!busy.value)void load()},5000)
onUnmounted(()=>{generation++;clearInterval(timer)})
</script>
<template>
  <section class="scheduling-panel" aria-label="요청 배분과 대기열">
    <header><div><span class="card-kicker">REQUEST SCHEDULING</span><h3>요청 배분 · 대기열</h3></div><span v-if="state" class="count-badge">대기 {{state.queue.queuedRequests}}</span></header>
    <p>빈 로컬 슬롯에 요청을 배정합니다. 실행 중인 요청은 이동하지 않으며, 보안 정책이 외부 전송을 금지하면 외부로 전환하지 않습니다.</p>
    <template v-if="draft">
      <label class="field">배분 방식<select v-model="draft.mode"><option value="IMMEDIATE">기존 방식 · 바쁘면 즉시 다음 Target</option><option value="LOCAL_FIRST_WAIT">로컬 우선 대기 · 시간 초과 후 승인된 외부 Target 허용</option><option value="LOCAL_ONLY_QUEUE">로컬 전용 대기 · 외부 전송 안 함</option></select></label>
      <div v-if="draft.mode!=='IMMEDIATE'" class="scheduling-fields">
        <label v-if="draft.mode==='LOCAL_FIRST_WAIT'" class="field">외부 전환 전 로컬 대기 (ms)<input v-model.number="draft.localWaitMs" type="number" min="0" :max="draft.maxWaitMs" step="100" /></label>
        <label class="field">전체 최대 대기 (ms)<input v-model.number="draft.maxWaitMs" type="number" min="100" max="60000" step="100" /></label>
        <label class="field">서비스 최대 대기 요청<input v-model.number="draft.maxQueuedRequests" type="number" min="1" max="500" /></label>
        <label class="field">API 키별 최대 대기 요청<input v-model.number="draft.maxQueuedPerKey" type="number" min="1" :max="draft.maxQueuedRequests" /></label>
        <label class="field">서비스 대기 본문 합계 한도 (bytes)<input v-model.number="draft.maxQueuedBytes" type="number" min="1024" max="134217728" /></label>
      </div>
      <p v-if="state" class="queue-status">대기 본문 {{(state.queue.queuedBytes/1024).toFixed(1)}} KB · 가장 오래 기다린 요청 {{(state.queue.oldestWaitMs/1000).toFixed(1)}}초</p>
      <small>대기는 최대 60초입니다. API 서버 재시작 시 대기 본문은 복구되지 않습니다. 호환 모델이 오프라인·미로드 상태이면 기다리지 않고 오류를 반환합니다.</small>
      <footer><p role="status">{{message}}</p><button type="button" class="secondary-button" :disabled="busy" @click="save">{{busy?'저장 중…':'배분 정책 저장'}}</button></footer>
    </template><p v-else role="status">{{message||'대기 정책을 불러오는 중…'}}</p>
  </section>
</template>
<style scoped>
.scheduling-panel{margin:20px 24px;padding:20px;border:1px solid var(--border);border-radius:12px;background:var(--surface-2);min-width:0}
header,footer{display:flex;justify-content:space-between;align-items:center;gap:16px}h3{margin:5px 0}p,small{color:var(--muted);line-height:1.65}.scheduling-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-top:14px}.queue-status{font-variant-numeric:tabular-nums}footer{margin-top:16px}footer p{margin:0}small{display:block}select{width:100%}@media(max-width:700px){.scheduling-panel{margin:16px;padding:16px}.scheduling-fields{grid-template-columns:1fr}footer{align-items:stretch;flex-direction:column}}
</style>
