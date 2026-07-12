<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, onUpdated, ref, watch } from 'vue'

const props = withDefaults(defineProps<{ open: boolean; title: string; description?: string; size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLElement | null>(null)

const helpByLabel: Record<string, string> = {
  '표시 이름': '콘솔과 모델 목록에 표시할 사람이 읽기 쉬운 이름입니다. API 요청의 model 값으로는 사용되지 않습니다.',
  '이메일': '새 사용자가 로그인할 회사 이메일 주소입니다. 초대와 계정 식별에 사용됩니다.',
  '임시 비밀번호': '신규 사용자가 처음 로그인할 때 사용할 비밀번호입니다. 최소 12자 이상으로 설정하고 안전한 경로로 전달하세요.',
  '조직 역할': '조직 전체에서 사용할 기본 권한입니다. Organization Admin은 조직 설정을 관리하고 Developer는 배정된 프로젝트를 사용합니다.',
  '팀': '사용자를 특정 부서 또는 팀에 연결합니다. 팀을 지정하지 않으면 조직 역할만 적용됩니다.',
  '팀 역할': '선택한 팀 안에서의 세부 권한입니다. 프로젝트 생성·관리·조회 범위에 영향을 줍니다.',
  '키 이름': '키의 사용처를 구분하는 설명 이름입니다. 예: production-backend, staging-test. 비밀값 자체를 입력하지 마세요.',
  '만료일 (선택)': '지정한 시각 이후 이 키는 자동으로 사용할 수 없게 됩니다. 비워 두면 만료되지 않습니다.',
  '프로젝트 API 키': '이 프로젝트의 사용량을 조회할 API 키입니다. 브라우저 탭에만 보관되며 서버에는 저장되지 않습니다.',
  '조직 이름': '회사의 독립 운영 단위 이름입니다. 같은 조직 안에서 사용자·팀·프로젝트·Runtime을 관리합니다.',
  '프로젝트 이름': 'API 키와 사용량을 구분할 작업 단위 이름입니다. 서비스별로 개발·운영 프로젝트를 나누는 것을 권장합니다.',
  '소유 팀': '프로젝트를 관리할 팀입니다. 조직 공용으로 두면 조직 관리자 정책을 따릅니다.',
  '논리 모델': 'API 사용자가 model 값으로 호출할 수 있도록 이 프로젝트에 허용할 논리 서비스입니다.',
  '분당 요청 수': '프로젝트 전체가 1분 동안 보낼 수 있는 최대 API 요청 수(RPM)입니다.',
  '월 토큰 한도': '한 달 동안 사용할 수 있는 입력·출력 토큰의 합계 한도입니다. 비워 두면 제한하지 않습니다.',
  'RPM 경보': '분당 요청 수가 이 값을 넘으면 관리자에게 사용량 급증 알림을 보냅니다.',
  '오류율 경보 (%)': '요청 실패 비율이 이 값 이상이면 알림을 보냅니다. 예: 5는 실패율 5%입니다.',
  '월 토큰 경보 (%)': '월 토큰 한도의 사용률이 이 퍼센트 이상이면 알림을 보냅니다.',
  '알림 재전송 간격 (초)': '같은 종류의 경보를 다시 보낼 때까지 기다리는 시간입니다. 알림 폭주를 막습니다.',
  '메타데이터만': '프롬프트와 응답 원문은 저장하지 않고 토큰·비용·상태·지연시간만 기록합니다. 기본 권장 정책입니다.',
  '암호화된 원문': '프롬프트와 응답을 AES-GCM으로 암호화해 저장합니다. 민감한 내용이 포함될 수 있으므로 보관 일수와 열람 권한을 신중히 설정하세요.',
  '원문 보관 일수': '암호화된 프롬프트와 응답을 보관할 최대 일수입니다. 기간이 지나면 자동 삭제됩니다.',
  '팀 이름': '프로젝트와 구성원을 묶어 권한을 나눌 부서 또는 작업 그룹 이름입니다.',
  '채널 유형': '장애·복구 알림을 보낼 서비스입니다. Discord Webhook 또는 Telegram Bot을 선택합니다.',
  'Webhook URL': 'Discord 채널 설정에서 발급한 Webhook 주소입니다. 외부에 노출되지 않도록 주의하세요.',
  'Chat ID': 'Telegram 알림을 받을 개인·그룹·채널의 Chat ID입니다.',
  'Bot Token': 'Telegram BotFather에서 발급한 봇 인증 토큰입니다. 입력값은 암호화해 보관합니다.',
  '노드 이름': 'GPU 서버 또는 워크스테이션을 관리 화면에서 식별할 이름입니다. GPU 종류를 고정 값으로 입력할 필요는 없습니다.',
  'Endpoint URL': 'Gateway가 Tailscale 또는 사내망을 통해 연결할 LM Studio 주소입니다. 예: http://100.x.x.x:1234',
  '설명': '서버 위치, 담당 팀, 운영 목적처럼 관리자가 참고할 메모입니다. API 호출에는 사용되지 않습니다.',
  'LM Studio API Token': 'LM Studio에서 API 인증을 켠 경우에만 입력하는 내부용 토큰입니다. 사용자 API 키와는 다릅니다.',
  'Endpoint 활성화': '켜면 신규 API 요청의 라우팅 후보가 됩니다. 끄면 기존 요청은 마무리하고 새 요청은 받지 않습니다.',
  '새 LM Studio API Token (선택)': '새 토큰을 입력한 경우에만 기존 저장 토큰을 교체합니다. 비워 두면 기존 값을 유지합니다.',
  '저장된 API Token 제거': 'LM Studio API 인증을 더 이상 사용하지 않을 때만 선택하세요. 인증이 켜져 있으면 Gateway 연결이 실패합니다.',
  '제조사': '가속기 제조사 정보입니다. 예: NVIDIA, AMD. 라우팅의 필수 조건은 아니며 모니터링 참고용입니다.',
  '제품명': 'GPU 또는 가속기 제품명입니다. 예: RTX 5090, H100. 자유 텍스트로 입력할 수 있습니다.',
  '장치 번호': '해당 서버에서의 GPU 인덱스입니다. 첫 번째 GPU는 일반적으로 0입니다.',
  'VRAM (MB)': '장치의 전체 비디오 메모리 용량을 MB 단위로 입력합니다. 선택 정보이며 추정·모니터링에 활용합니다.',
  '드라이버 버전': 'GPU 드라이버 버전입니다. 문제 분석을 위한 선택 메타데이터입니다.',
  'Device UUID': 'GPU 도구가 제공하는 고유 장치 식별자입니다. 동일 제품 GPU 여러 장을 구분할 때 유용합니다.',
  '호환 키': '동일 모델·양자화처럼 서로 대체 가능한 Deployment를 묶는 내부 키입니다. STRICT Failover 정책에서 사용합니다.',
  '최대 동시 요청': '이 Deployment가 동시에 처리할 수 있도록 허용하는 최대 요청 수입니다. GPU 메모리와 모델 성능에 맞춰 낮게 시작하세요.',
  '관리자 검증 Capability JSON': 'Runtime이 자동 보고하지 못한 지원 기능을 관리자가 보완하는 JSON 배열입니다. 예: ["STRUCTURED_OUTPUT"].',
  '라우팅 활성화': '켜면 이 Deployment가 서비스 Target을 통해 신규 요청을 처리할 수 있습니다.',
  '모델': 'LM Studio Endpoint에서 동기화한 모델 중 로드할 대상을 선택합니다.',
  '컨텍스트 길이': '한 요청에서 모델이 기억할 수 있는 최대 토큰 수입니다. 크게 설정할수록 메모리 사용량이 증가합니다.',
  '프로필 이름': '현재 모델 로드 설정을 다시 사용하기 위한 이름입니다. 예: gemma-e4b-32k.',
  'Flash Attention': '지원되는 모델에서 메모리 사용량과 생성 속도를 개선할 수 있는 LM Studio 옵션입니다. 문제가 생기면 해제해 비교하세요.',
  '모델 식별자': 'LM Studio Hub에서 내려받을 모델 ID입니다. 예: publisher/model-name.',
  '양자화 (선택)': '다운로드할 모델 파일의 양자화 형식입니다. 비워 두면 LM Studio 또는 모델의 기본 선택을 사용합니다.',
  'Service Key': '외부 API 요청의 model 값으로 사용하는 고유 식별자입니다. 예: text-pro. 생성 후에는 변경할 수 없습니다.',
  'Failover 정책': 'Primary가 실패했을 때 어떤 Deployment까지 대체 대상으로 허용할지 정합니다. STRICT가 가장 보수적입니다.',
  'Retry 정책': '같은 요청을 다른 Target으로 재시도하는 범위입니다. SAFE는 서버에 전달되지 않은 연결 실패만 재시도합니다.',
  '필수 Capability': '이 서비스가 처리하려면 Deployment가 반드시 지원해야 하는 기능의 JSON 배열입니다. 예: ["STRUCTURED_OUTPUT"].',
  '입력 단가 / 1M': '사용자 비용 계산에 사용할 입력 토큰 100만 개당 단가입니다.',
  '출력 단가 / 1M': '사용자 비용 계산에 사용할 출력 토큰 100만 개당 단가입니다.',
  'Degraded 허용': '정상 Target이 모두 없을 때 성능이 낮은 대체 모델까지 사용하는 것을 허용합니다.',
  '서비스 활성화': '켜면 이 논리 서비스가 권한이 있는 사용자에게 모델 목록으로 노출되고 호출할 수 있습니다.',
  'Deployment': '논리 서비스 요청을 실제로 처리할 Runtime Endpoint의 모델 배포본을 선택합니다.',
  'Priority': '낮은 숫자부터 먼저 선택합니다. 일반적으로 주 서버는 1, 대체 서버는 2 이상으로 설정합니다.',
  'Weight': '같은 Priority의 Target 여러 개 사이에서 트래픽을 나누는 비중입니다. 일반적인 기본값은 100입니다.',
  '동시성 재정의': '이 서비스에서만 적용할 Target별 최대 동시 요청 수입니다. 비워 두면 Deployment 기본값을 사용합니다.',
  'Degraded Target': '이 Target을 마지막 성능 저하 대체 대상으로 표시합니다. 서비스에서 Degraded 허용도 켜야 선택됩니다.',
  'Target 활성화': '켜면 이 Target을 실제 라우팅 후보로 사용합니다. 끄면 설정은 보존되지만 요청을 받지 않습니다.',
  'ADMIN_API_TOKEN': '일반 로그인이 불가능할 때만 사용하는 비상 관리자 토큰입니다. 평상시에는 사용하거나 공유하지 마세요.'
}

function normalized(value: string) {
  return value.replace(/\s+/g, ' ').trim()
}

function fieldName(label: HTMLLabelElement) {
  const directText = Array.from(label.childNodes)
    .find(node => node.nodeType === Node.TEXT_NODE && normalized(node.textContent ?? ''))?.textContent
  if (directText) return normalized(directText)
  const strong = label.querySelector('b')?.textContent
  if (strong) return normalized(strong)
  const span = label.querySelector(':scope > span')?.textContent
  return normalized(span ?? '')
}

function fallbackHelp(label: string, control: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement) {
  if (control instanceof HTMLInputElement && control.type === 'password') return `${label} 값은 민감 정보일 수 있습니다. 필요한 경우에만 입력하고 다른 사람과 공유하지 마세요.`
  if (control instanceof HTMLInputElement && control.type === 'checkbox') return `${label} 옵션을 켜거나 끌 수 있습니다. 변경 시 라우팅·보안·보관 정책에 영향을 줄 수 있습니다.`
  if (control instanceof HTMLInputElement && control.type === 'number') return `${label}에 사용할 숫자 값을 입력합니다. 허용 범위와 단위를 확인하세요.`
  if (control instanceof HTMLSelectElement) return `${label}에 사용할 항목을 목록에서 선택합니다. 선택한 값은 저장 후 운영 정책에 적용됩니다.`
  return `${label}에 필요한 값을 입력하세요. 입력 예시와 설명을 확인한 뒤 저장하면 됩니다.`
}

function installFieldHelp(root: HTMLElement) {
  root.querySelectorAll<HTMLLabelElement>('.modal-body label.field, .modal-body label.toggle-field, .modal-body .retention-options > label')
    .forEach(label => {
      if (label.dataset.helpReady === 'true') return
      const control = label.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input, select, textarea')
      if (!control) return
      const name = fieldName(label) || '입력 항목'
      const text = helpByLabel[name] ?? fallbackHelp(name, control)
      label.dataset.helpReady = 'true'
      label.classList.add('has-modal-help')

      const id = `modal-help-${Math.random().toString(36).slice(2)}`
      const trigger = document.createElement('button')
      trigger.type = 'button'
      trigger.className = 'modal-help-trigger'
      trigger.textContent = '?'
      trigger.setAttribute('aria-label', `${name} 도움말`)
      trigger.setAttribute('aria-controls', id)
      trigger.setAttribute('aria-expanded', 'false')

      const tooltip = document.createElement('span')
      tooltip.id = id
      tooltip.className = 'modal-help-tooltip'
      tooltip.setAttribute('role', 'tooltip')
      tooltip.textContent = text

      let pinned = false
      const setOpen = (open: boolean) => {
        tooltip.classList.toggle('is-open', open)
        trigger.setAttribute('aria-expanded', String(open))
      }
      trigger.addEventListener('click', event => {
        event.preventDefault()
        event.stopPropagation()
        pinned = !pinned
        setOpen(pinned)
      })
      trigger.addEventListener('mouseenter', () => setOpen(true))
      trigger.addEventListener('mouseleave', () => { if (!pinned) setOpen(false) })
      trigger.addEventListener('focus', () => setOpen(true))
      trigger.addEventListener('blur', () => { if (!pinned) setOpen(false) })
      trigger.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
          pinned = false
          setOpen(false)
          trigger.blur()
        }
      })
      label.append(trigger, tooltip)
    })
}

function installAfterRender() {
  void nextTick(() => { if (props.open && panel.value) installFieldHelp(panel.value) })
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) emit('close')
}
function lockBody(open: boolean) { document.body.classList.toggle('modal-open', open) }

watch(() => props.open, open => { lockBody(open); if (open) installAfterRender() })
onMounted(() => { window.addEventListener('keydown', onKeydown); lockBody(props.open); installAfterRender() })
onUpdated(installAfterRender)
onUnmounted(() => { window.removeEventListener('keydown', onKeydown); lockBody(false) })
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="modal-backdrop" @mousedown.self="emit('close')">
        <section ref="panel" class="modal-panel" :class="`modal-${size}`" role="dialog" aria-modal="true" :aria-label="title">
          <header class="modal-header">
            <div><span class="modal-kicker">AICONNECT CONTROL</span><h2>{{ title }}</h2><p v-if="description">{{ description }}</p></div>
            <button class="icon-button" type="button" aria-label="닫기" @click="emit('close')">×</button>
          </header>
          <div class="modal-body"><slot /></div>
          <footer v-if="$slots.footer" class="modal-footer"><slot name="footer" /></footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
:deep(.modal-body label.field), :deep(.modal-body label.toggle-field), :deep(.modal-body .retention-options > label) { position: relative; }
:deep(.modal-help-trigger) { position: absolute; z-index: 3; top: -3px; right: 0; width: 18px; height: 18px; padding: 0; display: grid; place-items: center; border: 1px solid var(--accent-border); border-radius: 50%; background: var(--surface); color: var(--accent-strong); font-size: 11px; font-weight: 900; line-height: 1; cursor: help; }
:deep(.modal-help-trigger:hover), :deep(.modal-help-trigger:focus-visible), :deep(.modal-help-trigger[aria-expanded="true"]) { outline: none; border-color: var(--accent-strong); background: var(--accent); color: var(--accent-ink); box-shadow: 0 0 0 3px var(--accent-dim); }
:deep(.modal-help-tooltip) { position: absolute; z-index: 10; top: 22px; right: 0; width: min(270px, calc(100vw - 72px)); padding: 10px 11px; display: none; border: 1px solid var(--accent-border); border-radius: 10px; background: var(--text); color: var(--surface); box-shadow: 0 12px 28px color-mix(in srgb, #07100c 28%, transparent); font-size: 11px; font-weight: 500; line-height: 1.55; text-align: left; }
:deep(.modal-help-tooltip.is-open) { display: block; }
:deep(.modal-body .toggle-field .modal-help-trigger) { top: 50%; right: 43px; transform: translateY(-50%); }
:deep(.modal-body .toggle-field .modal-help-tooltip) { top: calc(50% + 18px); right: 38px; }
:deep(.modal-body .retention-options > label .modal-help-trigger) { top: 10px; right: 10px; }
:deep(.modal-body .retention-options > label .modal-help-tooltip) { top: 34px; right: 10px; }
@media (max-width: 620px) { :deep(.modal-help-tooltip) { width: min(240px, calc(100vw - 58px)); } }
</style>
