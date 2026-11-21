<script setup lang="ts">
import { ref } from 'vue'
import { copyText } from './clipboard'

type Endpoint = { scope: 'INTERNAL' | 'EXTERNAL'; label: string; url: string }
type Model = { serviceKey: string; displayName: string }
defineProps<{ endpoints: Endpoint[]; models: Model[]; connectionError?: boolean; hideModels?: boolean }>()
const notice = ref('')

function hostBase(url: string) { return url.replace(/\/+$/, '').replace(/\/v1$/, '') }
async function copy(value: string) {
  try { await copyText(value); notice.value = '복사했습니다.' }
  catch { notice.value = '복사하지 못했습니다. 값을 직접 선택해 복사해 주세요.' }
}
</script>

<template>
  <section class="guide" aria-label="API 연결 정보">
    <h3>서비스 연결 정보</h3>
    <template v-if="!hideModels"><p>프로젝트에 연결된 논리 모델명을 사용하세요. 실제 GPU 또는 외부 모델은 라우팅 정책에 따라 선택됩니다.</p>
    <div v-if="models.length" class="items"><div v-for="model in models" :key="model.serviceKey" class="item"><span>서버 모델명 · {{ model.displayName }}</span><code>{{ model.serviceKey }}</code><button type="button" class="secondary-button" @click="copy(model.serviceKey)">복사</button></div></div>
    <p v-else class="warning">사용 가능한 연결 모델이 없거나 조회하지 못했습니다. 프로젝트의 서비스 권한을 확인하세요.</p>
    </template>
    <p v-if="connectionError" class="warning" role="status">연결 주소 조회에 실패했습니다. 관리자에게 확인하거나 다시 시도하세요.</p>
    <template v-else>
      <div v-for="scope in (['INTERNAL', 'EXTERNAL'] as const)" :key="scope" class="endpoint">
        <strong>{{ scope === 'INTERNAL' ? '내부 Gateway' : '외부 Gateway · HTTPS' }}</strong>
        <template v-for="endpoint in endpoints.filter(item => item.scope === scope)" :key="endpoint.scope">
        <div class="item"><span>OpenAI SDK Base URL</span><code>{{ endpoint.url }}</code><button type="button" class="secondary-button" :aria-label="`${scope === 'INTERNAL' ? '내부' : '외부'} SDK Base URL 복사`" @click="copy(endpoint.url)">복사</button></div>
        <div class="item"><span>Calen OpenAI API 주소 (경로 별도 입력)</span><code>{{ hostBase(endpoint.url) }}</code><button type="button" class="secondary-button" :aria-label="`${scope === 'INTERNAL' ? '내부' : '외부'} Calen 주소 복사`" @click="copy(hostBase(endpoint.url))">복사</button></div>
        </template>
        <p v-if="!endpoints.some(item => item.scope === scope)" class="warning">설정되지 않았습니다. 관리자에게 문의하세요.</p>
      </div>
      <div class="item"><span>Chat Completions path</span><code>/v1/chat/completions</code><button type="button" class="secondary-button" @click="copy('/v1/chat/completions')">복사</button></div>
      <div class="item"><span>Models path</span><code>/v1/models</code><button type="button" class="secondary-button" @click="copy('/v1/models')">복사</button></div>
      <p>Calen에는 위 주소와 경로를 각각 입력합니다. SDK Base URL에 경로의 /v1을 다시 붙이지 마세요. 외부 주소는 네트워크 접근 허용 상태도 확인해야 합니다.</p>
    </template>
    <small aria-live="polite">{{ notice }}</small>
  </section>
</template>

<style scoped>
.guide { display:grid; gap:10px; margin-top:14px; padding:16px; border:1px solid var(--border); border-radius:12px; background:var(--surface-2); }
.guide h3,.guide p { margin:0; }.guide p,.guide small { color:var(--muted); font-size:12px; line-height:1.6; }.guide .warning { color:var(--warning); }
.endpoint,.items { display:grid; gap:8px; }.endpoint { padding-top:8px; border-top:1px solid var(--border); }.endpoint strong { font-size:12px; }
.item { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:6px; }.item span { grid-column:1/-1; color:var(--muted); font-size:11px; }.item code { min-width:0; overflow-wrap:anywhere; padding:9px; border-radius:7px; background:var(--surface); color:var(--accent-strong); }.item button { white-space:nowrap; }
</style>
