<script setup lang="ts">
import { ref } from 'vue'

type Section = 'start' | 'quickstart' | 'architecture' | 'api' | 'admin' | 'runtime' | 'troubleshoot'
type Destination = 'projects' | 'infrastructure' | 'services' | 'teams' | 'usage' | 'notifications'
type CodeTab = 'curl' | 'typescript' | 'python'

const emit = defineEmits<{ navigate: [target: Destination] }>()
const active = ref<Section>('start')
const tab = ref<CodeTab>('curl')
const copied = ref('')

const sections: Array<{ id: Section; number: string; title: string; subtitle: string }> = [
  { id: 'start', number: '01', title: '처음 시작', subtitle: 'AICONNECT 소개' },
  { id: 'quickstart', number: '02', title: '5분 빠른 시작', subtitle: '프로젝트와 API 키' },
  { id: 'architecture', number: '03', title: '작동 구조', subtitle: 'Gateway와 Runtime' },
  { id: 'api', number: '04', title: '사용자 API', subtitle: 'OpenAI 호환 호출' },
  { id: 'admin', number: '05', title: '관리자 운영', subtitle: '팀·서비스·알림' },
  { id: 'runtime', number: '06', title: 'LM Studio Runtime', subtitle: 'Tailscale과 모델' },
  { id: 'troubleshoot', number: '07', title: '문제 해결', subtitle: '오류와 점검' }
]

const examples = {
  curl: `curl "https://api.example.com/v1/chat/completions" \\
  -H "Authorization: Bearer $AICONNECT_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "text-pro",
    "messages": [{"role":"user","content":"회의록을 세 문장으로 요약해 주세요."}],
    "temperature": 0.2
  }'`,
  typescript: `import OpenAI from 'openai'

const client = new OpenAI({
  apiKey: process.env.AICONNECT_API_KEY,
  baseURL: 'https://api.example.com/v1'
})

const result = await client.chat.completions.create({
  model: 'text-pro',
  messages: [{ role: 'user', content: '회의록을 요약해 주세요.' }]
})`,
  python: `from openai import OpenAI
import os

client = OpenAI(
    api_key=os.environ['AICONNECT_API_KEY'],
    base_url='https://api.example.com/v1',
)

result = client.chat.completions.create(
    model='text-pro',
    messages=[{'role': 'user', 'content': '회의록을 요약해 주세요.'}],
)`
}

async function copyExample() {
  try {
    await navigator.clipboard.writeText(examples[tab.value])
    copied.value = '복사됨'
    window.setTimeout(() => { copied.value = '' }, 1800)
  } catch { copied.value = '복사 실패' }
}
</script>

<template>
  <section class="dev-docs">
    <aside class="docs-nav surface-card">
      <header><span class="card-kicker">AICONNECT DOCS</span><h1>Dev-Docs</h1><p>처음 사용하는 사람과 AI 작업 보조자를 위한 운영 문서</p></header>
      <button v-for="item in sections" :key="item.id" :class="{ active: active === item.id }" @click="active = item.id"><span>{{ item.number }}</span><div><strong>{{ item.title }}</strong><small>{{ item.subtitle }}</small></div></button>
      <a href="https://lmstudio.ai/docs/developer" target="_blank" rel="noreferrer"><span>↗</span><div><strong>LM Studio Developer</strong><small>공식 문서 열기</small></div></a>
    </aside>

    <main class="docs-body">
      <section v-if="active === 'start'" class="docs-hero">
        <p class="eyebrow"><i class="live-dot"></i> AICONNECT DEVELOPER DOCUMENTATION</p>
        <h2>내 GPU 서버를<br><em>안전한 AI API</em>로 사용하세요.</h2>
        <p class="lead">AICONNECT는 여러 LM Studio 서버를 하나의 OpenAI 호환 API로 연결합니다. 사용자는 프로젝트와 API 키만 사용하고, GPU 선택·장애 전환·사용량 기록은 Gateway가 담당합니다.</p>
        <div class="hero-actions"><button class="primary-button" @click="active = 'quickstart'">첫 API 호출 시작하기 ↓</button><button class="secondary-button" @click="active = 'architecture'">작동 구조 보기</button></div>
        <div class="signal-grid"><article><span>01</span><strong>프로젝트</strong><small>API 키와 사용량의 분리 단위</small></article><article><span>02</span><strong>논리 모델</strong><small>GPU 대신 `text-pro` 사용</small></article><article><span>03</span><strong>자동 전환</strong><small>장애 Runtime은 자동 제외</small></article></div>
      </section>

      <section v-else-if="active === 'quickstart'" class="docs-article">
        <p class="section-label">02 / FIVE-MINUTE START</p><h2>첫 요청까지 5단계</h2><p>외부 애플리케이션은 GPU 주소와 LM Studio 내부 모델을 알 필요가 없습니다. 다음 다섯 단계만 완료하면 됩니다.</p>
        <ol class="step-list"><li><b>로그인과 조직 선택</b><span>전달받은 계정으로 로그인하고 상단 Workspace에서 작업할 조직을 선택합니다.</span></li><li><b>프로젝트 생성</b><span>개발·운영 환경은 서로 다른 프로젝트로 만듭니다.</span><button class="text-button" @click="emit('navigate', 'projects')">프로젝트 & API 키 열기 →</button></li><li><b>API 키 발급</b><span>키 원문은 생성 직후 한 번만 표시됩니다. 환경 변수 또는 비밀 저장소에 저장합니다.</span></li><li><b>논리 모델 확인</b><span><code>GET /v1/models</code>에서 프로젝트에 허용된 모델만 사용합니다.</span></li><li><b>최소 요청으로 연결 확인</b><span>먼저 비스트리밍 요청을 보내고, 응답 뒤 사용량 기록을 확인합니다.</span></li></ol>
        <div class="callout"><i>!</i><div><strong>키 보안</strong><p>API 키를 Git, 브라우저 코드, 채팅, 캡처에 넣지 마세요. 노출이 의심되면 즉시 폐기하고 새 키를 발급합니다.</p></div></div>
      </section>

      <section v-else-if="active === 'architecture'" class="docs-article">
        <p class="section-label">03 / REQUEST PATH</p><h2>프롬프트와 결과는 어디를 지나가나요?</h2><p>사용자 요청과 모델 응답은 AICONNECT Gateway를 통과합니다. Tailscale은 Gateway와 GPU 서버 간의 사설 연결에만 사용합니다.</p>
        <div class="architecture" role="img" aria-label="사용자 앱에서 AICONNECT Gateway를 거쳐 Tailscale의 LM Studio Runtime으로 요청이 전달되는 흐름"><article><span>⌁</span><strong>사용자 앱</strong><small>OpenAI SDK · HTTPS</small></article><div><i></i><small>API 키 · 논리 모델</small></div><article class="gateway"><span>◈</span><strong>AICONNECT Gateway</strong><small>인증 · 권한 · 라우팅 · 기록</small></article><div><i></i><small>Tailscale 사설망</small></div><article><span>▣</span><strong>LM Studio Runtime</strong><small>GPU · 실제 모델 추론</small></article></div>
        <div class="compare-grid"><article><h3>사용자가 아는 것</h3><ul><li>AICONNECT API 주소</li><li>프로젝트 API 키</li><li>논리 모델명</li></ul></article><article><h3>사용자가 알 필요 없는 것</h3><ul><li>GPU 종류와 IP 주소</li><li>LM Studio 내부 토큰</li><li>실제 모델 파일과 대체 서버</li></ul></article></div>
      </section>

      <section v-else-if="active === 'api'" class="docs-article">
        <p class="section-label">04 / OPENAI-COMPATIBLE API</p><h2>기존 OpenAI 코드에서 Base URL만 바꾸세요.</h2><p>사용자 애플리케이션은 관리자 API가 아닌 <code>/v1</code> API만 호출합니다. 모델 값에는 실제 GPU나 LM Studio 파일명이 아닌 논리 모델명을 넣습니다.</p>
        <div class="code-card"><header><div class="tabs"><button v-for="name in (['curl', 'typescript', 'python'] as CodeTab[])" :key="name" :class="{ active: tab === name }" @click="tab = name">{{ name === 'curl' ? 'cURL' : name === 'typescript' ? 'TypeScript' : 'Python' }}</button></div><button class="text-button" @click="copyExample">{{ copied || '코드 복사' }}</button></header><pre><code>{{ examples[tab] }}</code></pre></div>
        <div class="two-cards"><article><h3>스트리밍</h3><p><code>stream: true</code>로 SSE 스트림을 사용할 수 있습니다. 일부 토큰 전송 뒤 Runtime 장애가 나면 중복·깨진 응답을 방지하기 위해 다른 모델이 이어 쓰지 않습니다.</p></article><article><h3>사용량</h3><p>요청 수, 입력/출력 토큰, 예상 비용, 오류는 사용량 화면에서 프로젝트별로 확인합니다.</p><button class="text-button" @click="emit('navigate', 'usage')">사용량 화면 열기 →</button></article></div>
      </section>

      <section v-else-if="active === 'admin'" class="docs-article">
        <p class="section-label">05 / ADMINISTRATION</p><h2>관리자는 연결을 만들고, 사용자는 API를 사용합니다.</h2><p>관리자는 GPU 서버를 직접 공개하지 않고 조직·팀·Runtime·논리 서비스·프로젝트 순서로 구성합니다.</p>
        <div class="admin-flow"><article><span>1</span><strong>조직 · 팀 · 사용자</strong><small>부서와 역할로 접근 범위를 분리합니다.</small></article><i>→</i><article><span>2</span><strong>Runtime 등록</strong><small>Tailscale의 LM Studio Endpoint를 연결합니다.</small></article><i>→</i><article><span>3</span><strong>서비스 라우팅</strong><small>논리 모델과 Primary/Secondary를 연결합니다.</small></article><i>→</i><article><span>4</span><strong>프로젝트 · API 키</strong><small>사용량과 권한을 분리합니다.</small></article></div>
        <div class="quick-grid"><button @click="emit('navigate', 'teams')"><span>◫</span><b>팀과 구성원</b><small>부서별 권한 분리</small></button><button @click="emit('navigate', 'infrastructure')"><span>◈</span><b>Runtime 연결</b><small>LM Studio와 모델 관리</small></button><button @click="emit('navigate', 'services')"><span>⇄</span><b>LLM 서비스</b><small>라우팅과 Failover</small></button><button @click="emit('navigate', 'notifications')"><span>◌</span><b>알림 채널</b><small>Discord·Telegram</small></button></div>
      </section>

      <section v-else-if="active === 'runtime'" class="docs-article">
        <p class="section-label">06 / LM STUDIO RUNTIME</p><h2>GPU가 아니라 Runtime을 등록합니다.</h2><p>AICONNECT는 RTX 5090, H100 같은 GPU 이름으로 라우팅하지 않습니다. Tailscale에서 접근 가능한 LM Studio Endpoint와 그 안의 모델 상태를 기준으로 동작합니다.</p>
        <div class="runtime-visual"><div class="runtime-window"><header><i></i><span>http://100.x.x.x:1234</span><b>LM Studio</b></header><article><em>READY</em><strong>google/gemma-4-e4b</strong><small>Loaded · Context 131,072</small></article><div class="fake-lines"><i></i><i></i><i></i></div></div><div><h3>등록 전 GPU 서버 점검</h3><ol><li>Gateway와 같은 Tailnet에 연결</li><li>LM Studio Developer Server 실행</li><li>사용할 모델 다운로드</li><li>필요하면 LM Studio API Token 활성화</li></ol></div></div>
        <div class="settings-table"><header><strong>현재 정책: Node Agent 없음</strong><small>실제로 적용 가능한 설정만 노출합니다.</small></header><div><span>Context Length</span><b>플랫폼에서 설정</b><small>최대값 초과 시 사전 점검에서 차단</small></div><div><span>Flash Attention</span><b>플랫폼에서 설정</b><small>Runtime 지원 시 적용</small></div><div><span>GPU Offload · CPU Thread Pool · Unified KV Cache · K/V Cache 양자화</span><b>LM Studio 기본값</b><small>현재 화면에서 변경하지 않음</small></div><div><span>모델 메모리 유지 · TTL</span><b>LM Studio 정책</b><small>명시적 로드/언로드와 Runtime 기본값 사용</small></div></div>
        <p class="reference">LM Studio 설정은 <a href="https://lmstudio.ai/docs/developer" target="_blank" rel="noreferrer">공식 Developer 문서 ↗</a>를 기준으로 확인합니다.</p>
      </section>

      <section v-else class="docs-article">
        <p class="section-label">07 / TROUBLESHOOTING</p><h2>문제가 생기면 이 순서로 점검하세요.</h2>
        <div class="trouble-grid"><article><em>401</em><h3>API 키 거부</h3><p>올바른 프로젝트 키인지, 만료·폐기되지 않았는지 확인합니다. 키 원문은 공유하지 않습니다.</p></article><article><em>403</em><h3>모델 권한 없음</h3><p>프로젝트에 논리 모델 접근 권한이 없습니다. 관리자에게 권한을 요청합니다.</p></article><article><em>429</em><h3>한도 초과</h3><p>지수 백오프로 재시도하고, 사용량 화면에서 API 키별 트래픽을 확인합니다.</p></article><article><em>503</em><h3>사용 가능한 모델 없음</h3><p>관리자는 Runtime 상태, 모델 로드, Service Target, Failover 후보를 확인합니다.</p></article></div>
        <div class="callout warning"><i>!</i><div><strong>운영 안전 원칙</strong><p>마지막 로드 모델 언로드, API 키 폐기, 라우팅 변경, 원문 열람은 영향 범위를 확인한 뒤 실행합니다. 스트리밍 중단 응답을 다른 Runtime 결과로 이어 붙이지 않습니다.</p></div></div>
      </section>
    </main>
  </section>
</template>

<style scoped>
:global(.sidebar-doc-link) { width: calc(100% - 10px); min-height: 56px; margin: 0 5px 9px; padding: 9px 10px; display: grid; grid-template-columns: 29px 1fr 12px; align-items: center; gap: 9px; border: 1px solid var(--accent-border); border-radius: 11px; background: var(--accent-dim); color: var(--text); text-align: left; }
:global(.sidebar-doc-link:hover), :global(.sidebar-doc-link.active) { border-color: var(--accent-strong); background: color-mix(in srgb, var(--accent-dim) 72%, var(--surface)); }
:global(.sidebar-doc-link > span:first-child) { width: 29px; height: 29px; display: grid; place-content: center; border-radius: 9px; background: var(--accent); color: var(--accent-ink); font-weight: 900; }
:global(.sidebar-doc-link > span:nth-child(2)) { display: grid; gap: 3px; min-width: 0; }:global(.sidebar-doc-link strong) { font-size: 11px; }:global(.sidebar-doc-link small) { overflow: hidden; color: var(--muted); font-size: 8px; white-space: nowrap; text-overflow: ellipsis; }:global(.sidebar-doc-link b) { color: var(--accent-strong); }
.dev-docs { display: grid; grid-template-columns: 245px minmax(0, 1fr); gap: 25px; align-items: start; }.docs-nav { position: sticky; top: 92px; overflow: hidden; }.docs-nav header { padding: 21px 18px 16px; border-bottom: 1px solid var(--border); }.docs-nav h1 { margin: 5px 0; font-size: 20px; }.docs-nav header p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.5; }.docs-nav button, .docs-nav a { width: 100%; min-height: 51px; padding: 9px 13px; display: grid; grid-template-columns: 25px 1fr; gap: 8px; align-items: center; border: 0; border-bottom: 1px solid var(--border); background: transparent; color: var(--muted); text-align: left; text-decoration: none; }.docs-nav button:hover, .docs-nav button.active { background: var(--accent-dim); color: var(--text); }.docs-nav button > span, .docs-nav a > span { color: var(--accent-strong); font: 700 9px 'Space Grotesk'; }.docs-nav div { display: grid; gap: 2px; }.docs-nav strong { font-size: 10px; }.docs-nav small { color: var(--faint); font-size: 8px; }.docs-nav a { min-height: 65px; border-bottom: 0; background: var(--surface-2); }
.docs-body { min-width: 0; padding: 10px 0 48px; }.docs-hero { position: relative; overflow: hidden; padding: clamp(29px, 5vw, 61px); border: 1px solid var(--accent-border); border-radius: 24px; background: radial-gradient(circle at 85% 5%, var(--accent-dim), transparent 30%), linear-gradient(135deg, var(--surface), var(--surface-2)); }.docs-hero::after { content: ''; position: absolute; right: -145px; bottom: -185px; width: 330px; height: 330px; border: 1px solid var(--accent-border); border-radius: 50%; box-shadow: 0 0 0 42px var(--accent-dim); }.eyebrow, .section-label { color: var(--accent-strong); font-size: 10px; font-weight: 800; letter-spacing: .16em; }.eyebrow { display: flex; align-items: center; gap: 8px; }.docs-hero h2 { position: relative; z-index: 1; margin: 18px 0; font-size: clamp(39px, 5vw, 68px); line-height: .98; letter-spacing: -.06em; }.docs-hero h2 em { color: var(--accent); font-style: normal; }.lead { position: relative; z-index: 1; max-width: 700px; color: var(--text-soft); font-size: 14px; line-height: 1.8; }.hero-actions { position: relative; z-index: 1; display: flex; flex-wrap: wrap; gap: 9px; margin-top: 25px; }.signal-grid { position: relative; z-index: 1; max-width: 770px; margin-top: 40px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }.signal-grid article { min-height: 104px; padding: 15px; border: 1px solid var(--border); border-radius: 14px; background: color-mix(in srgb, var(--surface) 86%, transparent); }.signal-grid span { color: var(--accent-strong); font: 700 11px 'Space Grotesk'; }.signal-grid strong { display: block; margin-top: 12px; font-size: 12px; }.signal-grid small { display: block; margin-top: 4px; color: var(--muted); font-size: 9px; line-height: 1.5; }
.docs-article { animation: page-in .25s ease both; }.docs-article > h2 { margin: 10px 0; font-size: clamp(27px, 3vw, 37px); letter-spacing: -.045em; }.docs-article > p { max-width: 780px; color: var(--text-soft); font-size: 13px; line-height: 1.75; }.step-list { margin: 25px 0 0; padding: 0; display: grid; gap: 9px; list-style: none; counter-reset: doc-step; }.step-list li { counter-increment: doc-step; padding: 17px 18px 17px 59px; display: grid; gap: 5px; position: relative; border: 1px solid var(--border); border-radius: 15px; background: var(--surface); }.step-list li::before { content: counter(doc-step); position: absolute; top: 17px; left: 17px; width: 31px; height: 31px; display: grid; place-content: center; border: 1px solid var(--accent-border); border-radius: 9px; background: var(--accent-dim); color: var(--accent-strong); font-weight: 900; }.step-list b { font-size: 13px; }.step-list span { color: var(--muted); font-size: 10px; line-height: 1.65; }
.callout { margin-top: 14px; padding: 16px; display: grid; grid-template-columns: 28px 1fr; gap: 11px; border: 1px solid var(--accent-border); border-radius: 14px; background: var(--accent-dim); }.callout i { width: 27px; height: 27px; display: grid; place-content: center; border-radius: 8px; background: var(--accent); color: var(--accent-ink); font-style: normal; font-weight: 900; }.callout strong { font-size: 11px; }.callout p { margin: 5px 0 0; color: var(--text-soft); font-size: 10px; line-height: 1.6; }.callout.warning { border-color: color-mix(in srgb, var(--warning) 38%, transparent); background: var(--warning-dim); }.callout.warning i { background: var(--warning); }
.architecture { margin-top: 23px; padding: clamp(17px, 3vw, 30px); display: grid; grid-template-columns: 1fr auto 1.2fr auto 1fr; gap: 10px; align-items: center; border: 1px solid var(--border); border-radius: 20px; background: var(--surface); }.architecture article { min-height: 128px; padding: 15px; display: grid; place-content: center; justify-items: center; text-align: center; border: 1px solid var(--border); border-radius: 15px; background: var(--surface-2); }.architecture article.gateway { border-color: var(--accent-border); background: var(--accent-dim); }.architecture article > span { width: 36px; height: 36px; display: grid; place-content: center; border-radius: 11px; background: var(--surface); color: var(--accent-strong); font-size: 16px; }.architecture article.gateway > span { background: var(--accent); color: var(--accent-ink); }.architecture strong { margin-top: 10px; font-size: 11px; }.architecture small { margin-top: 4px; color: var(--muted); font-size: 8px; }.architecture > div { width: 82px; display: grid; gap: 8px; justify-items: center; }.architecture > div i { position: relative; width: 100%; height: 1px; background: var(--accent-border); }.architecture > div i::after { content: ''; position: absolute; top: -3px; right: 0; width: 7px; height: 7px; border-top: 1px solid var(--accent-strong); border-right: 1px solid var(--accent-strong); transform: rotate(45deg); }.architecture > div small { color: var(--muted); font-size: 8px; text-align: center; }.compare-grid, .two-cards { margin-top: 13px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.compare-grid article, .two-cards article { padding: 17px; border: 1px solid var(--border); border-radius: 14px; background: var(--surface-2); }.compare-grid h3, .two-cards h3 { margin: 0; font-size: 12px; }.compare-grid ul { margin: 10px 0 0; padding-left: 17px; color: var(--muted); font-size: 10px; line-height: 1.9; }.two-cards p { color: var(--muted); font-size: 10px; line-height: 1.65; }
.code-card { margin-top: 23px; overflow: hidden; border: 1px solid var(--border); border-radius: 17px; }.code-card header { min-height: 53px; padding: 0 14px; display: flex; justify-content: space-between; align-items: center; background: var(--surface-2); }.tabs { display: flex; gap: 3px; }.tabs button { min-height: 30px; padding: 0 10px; border: 0; border-radius: 7px; background: transparent; color: var(--muted); font-size: 10px; font-weight: 700; }.tabs button.active { background: var(--accent-dim); color: var(--accent-strong); }.code-card pre { margin: 0; padding: 22px; overflow: auto; background: #07100c; color: #b8f5c3; font-size: 11px; line-height: 1.7; }
.admin-flow { margin-top: 24px; display: grid; grid-template-columns: 1fr auto 1fr auto 1fr auto 1fr; gap: 8px; align-items: center; }.admin-flow article { min-height: 132px; padding: 14px; display: grid; align-content: start; gap: 8px; border: 1px solid var(--border); border-radius: 14px; background: var(--surface); }.admin-flow span { width: 25px; height: 25px; display: grid; place-content: center; border-radius: 7px; background: var(--accent-dim); color: var(--accent-strong); font-size: 9px; font-weight: 900; }.admin-flow strong { font-size: 11px; }.admin-flow small { color: var(--muted); font-size: 9px; line-height: 1.5; }.admin-flow > i { color: var(--accent-strong); font-style: normal; }
.runtime-visual { margin-top: 22px; display: grid; grid-template-columns: 1.1fr .9fr; gap: 14px; }.runtime-window, .runtime-visual > div:last-child { overflow: hidden; border: 1px solid var(--border); border-radius: 16px; background: var(--surface); }.runtime-window header { min-height: 40px; padding: 0 12px; display: flex; gap: 8px; align-items: center; border-bottom: 1px solid var(--border); background: var(--surface-2); color: var(--muted); font-size: 9px; }.runtime-window header i { width: 7px; height: 7px; border-radius: 50%; background: var(--accent-strong); box-shadow: 0 0 9px var(--accent-strong); }.runtime-window header b { margin-left: auto; color: var(--text-soft); }.runtime-window article { margin: 15px; padding: 14px; display: grid; gap: 6px; border: 1px solid var(--accent-border); border-radius: 12px; background: var(--accent-dim); }.runtime-window em { width: fit-content; padding: 3px 6px; border-radius: 5px; background: var(--accent); color: var(--accent-ink); font-size: 7px; font-style: normal; font-weight: 900; }.runtime-window strong { font-size: 12px; }.runtime-window small { color: var(--muted); font-size: 9px; }.fake-lines { padding: 0 15px 18px; display: grid; gap: 7px; }.fake-lines i { height: 8px; border-radius: 4px; background: linear-gradient(90deg, var(--accent-dim), var(--surface-2)); }.fake-lines i:nth-child(2) { width: 78%; }.fake-lines i:nth-child(3) { width: 61%; }.runtime-visual > div:last-child { padding: 18px; background: var(--surface-2); }.runtime-visual h3 { margin: 0; font-size: 13px; }.runtime-visual ol { margin: 12px 0 0; padding-left: 18px; color: var(--muted); font-size: 10px; line-height: 1.9; }
.settings-table { margin-top: 14px; overflow: hidden; border: 1px solid var(--border); border-radius: 16px; }.settings-table header { padding: 15px; display: grid; gap: 4px; border-bottom: 1px solid var(--border); background: var(--surface-2); }.settings-table header strong { font-size: 12px; }.settings-table header small { color: var(--muted); font-size: 9px; }.settings-table > div { padding: 13px 15px; display: grid; grid-template-columns: 1.2fr auto 1fr; gap: 12px; align-items: center; border-bottom: 1px solid var(--border); font-size: 10px; }.settings-table > div:last-child { border-bottom: 0; }.settings-table b { padding: 4px 7px; border-radius: 6px; background: var(--accent-dim); color: var(--accent-strong); font-size: 8px; }.settings-table small { color: var(--faint); font-size: 8px; }.reference { margin-top: 12px !important; color: var(--muted) !important; font-size: 10px !important; }.reference a { color: var(--accent-strong); font-weight: 700; }
.trouble-grid { margin-top: 23px; display: grid; grid-template-columns: 1fr 1fr; gap: 11px; }.trouble-grid article { padding: 17px; border: 1px solid var(--border); border-radius: 15px; background: var(--surface); }.trouble-grid em { padding: 4px 7px; border: 1px solid color-mix(in srgb, var(--danger) 32%, transparent); border-radius: 6px; background: var(--danger-dim); color: var(--danger); font: 700 10px 'Space Grotesk'; }.trouble-grid h3 { margin: 13px 0 6px; font-size: 12px; }.trouble-grid p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.65; }
@media (max-width: 1160px) { .dev-docs { grid-template-columns: 205px minmax(0, 1fr); }.admin-flow { grid-template-columns: 1fr 1fr; }.admin-flow > i { display: none; } }.quick-grid { grid-template-columns: 1fr 1fr; }
@media (max-width: 900px) { .dev-docs { grid-template-columns: 1fr; }.docs-nav { position: static; display: grid; grid-template-columns: 1fr 1fr; }.docs-nav header { grid-column: 1 / -1; }.architecture { grid-template-columns: 1fr; }.architecture > div { width: 100%; grid-template-columns: 1fr auto; align-items: center; }.architecture > div small { grid-column: 2; grid-row: 1; }.runtime-visual { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .docs-nav, .signal-grid, .compare-grid, .two-cards, .trouble-grid, .quick-grid { grid-template-columns: 1fr; }.docs-hero { padding: 28px 21px; }.settings-table > div { grid-template-columns: 1fr; gap: 6px; }.code-card pre { padding: 15px; font-size: 9px; } }
</style>
