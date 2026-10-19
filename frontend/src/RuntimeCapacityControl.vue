<script setup lang="ts">
import { ref, watch } from 'vue'
import { adminFetch,type AdminAuth } from './api'
const props=defineProps<{endpointId:string;auth:AdminAuth}>()
const value=ref(1),busy=ref(false),message=ref(''),ready=ref(false)
let generation=0
watch(()=>props.endpointId,async id=>{const seq=++generation;ready.value=false;message.value=''
  try{const data=await adminFetch<{maxConcurrentRequests:number}>(`/api/admin/runtime-endpoints/${id}/capacity`,props.auth);if(seq===generation){value.value=data.maxConcurrentRequests;ready.value=true}}
  catch(e){if(seq===generation)message.value=e instanceof Error?e.message:'처리 한도 조회 실패'}
},{immediate:true})
async function save(){const id=props.endpointId;busy.value=true
  try{await adminFetch(`/api/admin/runtime-endpoints/${id}/capacity`,props.auth,{method:'PATCH',body:JSON.stringify({maxConcurrentRequests:value.value})});if(id===props.endpointId)message.value='Runtime 전체 동시 처리 한도를 저장했습니다.'}
  catch(e){if(id===props.endpointId)message.value=e instanceof Error?e.message:'저장 실패'}finally{busy.value=false}}
</script>
<template><section class="runtime-capacity"><div><strong>Runtime 전체 동시 처리 한도</strong><p>같은 Runtime의 모든 모델·논리 서비스·Chat 테스트가 함께 사용합니다. 실제 서버의 병렬 처리 능력에 맞추세요. 로컬 기본값은 1입니다.</p><small role="status">{{message}}</small></div><label class="field">최대 동시 요청<input v-model.number="value" type="number" min="1" max="64" :disabled="!ready||busy" /></label><button class="secondary-button" :disabled="!ready||busy" @click="save">저장</button></section></template>
<style scoped>.runtime-capacity{display:flex;align-items:center;gap:18px;padding:22px 24px;border-bottom:1px solid var(--border);min-width:0}.runtime-capacity>div{flex:1}.runtime-capacity p{font-size:12px;line-height:1.65;color:var(--muted);margin:7px 0}.runtime-capacity .field{width:145px;flex-shrink:0}.runtime-capacity small{color:var(--muted)}@media(max-width:700px){.runtime-capacity{flex-wrap:wrap;padding:18px}.runtime-capacity>div{flex-basis:100%}}</style>
