import type { DocPage } from './types'

const curlExample = `curl "$AICONNECT_BASE_URL/chat/completions" \\
  -H "Authorization: Bearer $AICONNECT_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "text-pro",
    "messages": [
      {"role": "user", "content": "회의 내용을 세 문장으로 요약해 주세요."}
    ],
    "stream": false
  }'`

const pythonExample = `from openai import OpenAI
import os

client = OpenAI(
    api_key=os.environ["AICONNECT_API_KEY"],
    base_url=os.environ["AICONNECT_BASE_URL"],
)

response = client.chat.completions.create(
    model="text-pro",
    messages=[
        {"role": "user", "content": "회의 내용을 세 문장으로 요약해 주세요."}
    ],
)

print(response.choices[0].message.content)`

const typescriptExample = `import OpenAI from 'openai'

const client = new OpenAI({
  apiKey: process.env.AICONNECT_API_KEY,
  baseURL: process.env.AICONNECT_BASE_URL
})

const response = await client.chat.completions.create({
  model: 'text-pro',
  messages: [
    { role: 'user', content: '회의 내용을 세 문장으로 요약해 주세요.' }
  ]
})

console.log(response.choices[0].message.content)`

export const roleGuides: DocPage[] = [
  {
    id: 'guide-admin',
    group: '역할별 실전 가이드',
    title: '시스템 관리자 실전 가이드',
    shortTitle: '관리자 가이드',
    description: '조직·팀·프로젝트 구성부터 프로젝트 총괄자 초대, 사내 GPU Runtime 연결, 논리 서비스·상한 설정까지 실제 화면 입력 예시로 진행합니다.',
    audience: '관리자',
    minutes: 8,
    icon: '◫',
    keywords: ['시스템 관리자', '조직 관리자', '최초 구축', '초대', 'Runtime', 'LLM 서비스', '프로젝트', '상한', 'SYSTEM_ADMIN'],
    sections: [
      {
        id: 'setup-flow',
        title: '처음 세팅하는 전체 흐름',
        description: '아래 4단계 순서대로 설정하면 빈 AICONNECT에서도 바로 API를 호출할 수 있습니다.',
        blocks: [
          {
            type: 'flow',
            items: [
              { label: 'STEP 1', title: '조직·팀·총괄자 초대', text: 'Acme-AI-Lab 확인 → Platform-AI 팀과 cs-copilot 프로젝트 생성 → 총괄자(lead.park@acme.example) 초대' },
              { label: 'STEP 2', title: 'GPU Runtime·외부 AI 등록', text: '사내 GPU 서버(seoul-gpu-01)를 등록하고 연결 확인 후 모델 동기화' },
              { label: 'STEP 3', title: '논리 서비스·Target 구성', text: '호출 모델 키(text-pro)를 만들고 사내/외부 배포 Target과 우선순위 연결' },
              { label: 'STEP 4', title: '권한·데이터 경계·예산 상한', text: '프로젝트에 text-pro 허용, 데이터 경계 상한과 RPM/월 토큰 상한 확정' }
            ]
          },
          {
            type: 'callout',
            tone: 'info',
            title: '계정 생성은 초대 기반만 지원합니다',
            text: '보안 정책상 직접 계정 생성(POST .../users)은 비활성화(410)되어 있습니다. 모든 하위 계정은 1회용 초대 코드·링크로 가입하고 Google Authenticator 2차 인증(TOTP)을 등록해야 합니다.'
          }
        ]
      },
      {
        id: 'invite-director',
        title: 'STEP 1 · 팀·프로젝트 만들고 총괄자 초대하기',
        description: '팀과 프로젝트를 먼저 만든 뒤 상단 ⚙ 설정 › 계정 추가 모달에서 총괄자를 초대합니다.',
        blocks: [
          {
            type: 'steps',
            items: [
              { title: '팀과 부서 생성', text: '팀과 부서 화면에서 부서 이름 `Platform-AI`를 생성합니다.', action: { label: '팀과 부서 열기', destination: 'teams' } },
              { title: '프로젝트 생성', text: '프로젝트 화면에서 소유 팀 `Platform-AI`, 이름 `cs-copilot`으로 프로젝트를 만듭니다.', action: { label: '프로젝트 열기', destination: 'projects' } }
            ]
          },
          {
            type: 'mockup',
            screen: '설정 (⚙) › 계정 추가 · 초대 코드 발급',
            roleTag: '시스템 관리자 화면',
            stepLabel: 'STEP 1',
            caption: '팀과 프로젝트 준비가 끝나면 프로젝트 총괄자(PROJECT_DIRECTOR) 계정을 초대합니다.',
            fields: [
              { label: '부여 계층', value: '프로젝트 총괄 (PROJECT_DIRECTOR)', badge: '관리자 지정', badgeTone: 'accent' },
              { label: '대상 조직 (Workspace)', value: 'Acme-AI-Lab' },
              { label: '대상 프로젝트', value: 'cs-copilot (Platform-AI)' },
              { label: '지정 이메일 (선택)', value: 'lead.park@acme.example', hint: '입력한 이메일과 가입 이메일이 일치해야 수락 가능' },
              { label: '만료 시간', value: '72시간' }
            ],
            actions: [
              { label: '초대 이력·사용자 관리' },
              { label: '닫기' },
              { label: '초대 코드/링크 발급', primary: true }
            ],
            result: {
              tone: 'success',
              title: '1회용 초대 코드·가입 링크 발급 예시 (생성 직후 1회만 표시)',
              items: [
                '초대 코드: INV-EXAMPLE-7K9Q',
                '가입 링크: https://www.aiconnectgateway.com/#register?code=INV-EXAMPLE-7K9Q'
              ]
            },
            navigate: { label: '팀과 부서 열기', destination: 'teams' }
          }
        ]
      },
      {
        id: 'register-runtime',
        title: 'STEP 2 · 사내 GPU Runtime 연결 및 모델 동기화',
        description: '사설망/Tailscale 내부 추론 서버를 인프라스트럭처에 등록합니다. 주소가 바뀌면 인증 토큰도 새로 저장해야 합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '인프라스트럭처 › Runtime Endpoint 등록',
            roleTag: '시스템 관리자 화면',
            stepLabel: 'STEP 2',
            caption: 'Workspace로 Acme-AI-Lab이 선택되어 있어야 등록할 수 있습니다.',
            fields: [
              { label: 'Runtime 종류', value: 'LM Studio', badge: 'native API 지원', badgeTone: 'info' },
              { label: 'Endpoint 이름', value: 'seoul-gpu-01' },
              { label: 'Base URL (사설망)', value: 'http://100.64.0.20:1234', mono: true, hint: '외부 사용자에게는 절대 직접 노출하지 않음' },
              { label: '인증 토큰', value: '설정됨 (AES 암호화 저장)', hint: 'baseUrl 변경 시 보안상 새 토큰 입력 필수' }
            ],
            actions: [
              { label: '연결 확인' },
              { label: '모델 동기화' },
              { label: '저장', primary: true }
            ],
            result: {
              tone: 'success',
              title: '연결 확인 및 모델 동기화 완료 상태 예시',
              items: [
                'Endpoint 상태: HEALTHY · 동시 처리 기본 1',
                '발견 및 로드 모델: google/gemma-4-e4b (LOADED · READY)'
              ]
            },
            navigate: { label: '인프라스트럭처 열기', destination: 'infrastructure' }
          }
        ]
      },
      {
        id: 'service-and-project',
        title: 'STEP 3–4 · 논리 서비스 구성 및 프로젝트 권한·상한 부여',
        description: '사용자가 호출할 논리 모델명(`text-pro`)을 만들고, `cs-copilot` 프로젝트에 허용 범위와 상한을 지정합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: 'LLM 서비스 › 논리 서비스 · Target 연결',
            roleTag: '시스템 관리자 화면',
            stepLabel: 'STEP 3',
            fields: [
              { label: 'Service Key (호출 model)', value: 'text-pro', mono: true, badge: '사용자 공개 키', badgeTone: 'accent' },
              { label: '요청 배분 · 대기열', value: 'LOCAL_FIRST_WAIT (최대 60초)', hint: '사내 GPU 우선 대기 후 승인 프로젝트만 외부 전환' },
              { label: 'Target P1 (주 모델)', value: 'seoul-gpu-01 · google/gemma-4-e4b', badge: 'Priority 1 · Weight 100', badgeTone: 'accent' },
              { label: 'Target P2 (예비 모델)', value: 'OPEN-AI-API · gpt-analysis', badge: 'Priority 2 · Degraded', badgeTone: 'warning' }
            ],
            actions: [
              { label: 'Target 추가' },
              { label: '서비스 저장', primary: true }
            ],
            navigate: { label: 'LLM 서비스 열기', destination: 'services' }
          },
          {
            type: 'mockup',
            screen: '프로젝트 › 서비스 권한 · 데이터 경계 · 쿼터 상한 설정',
            roleTag: '시스템 관리자 화면',
            stepLabel: 'STEP 4',
            fields: [
              { label: '선택 프로젝트', value: 'cs-copilot (Platform-AI)', badge: 'ACTIVE', badgeTone: 'accent' },
              { label: '허용 논리 모델 (서비스 권한)', value: 'text-pro', hint: '비어 있으면 /v1/models가 비어 호출 불가' },
              { label: '데이터 경계 상한 (Max Boundary)', value: '사내 GPU 전용 (INTERNAL_ONLY)', badge: '총괄자는 이하만 선택 가능', badgeTone: 'warning' },
              { label: '관리자 상한 (RPM / 월 토큰)', value: '120 RPM / 10,000,000 Tokens' }
            ],
            actions: [
              { label: '프로젝트 중지/제어' },
              { label: '권한·상한 저장', primary: true }
            ],
            navigate: { label: '프로젝트 열기', destination: 'projects' }
          },
          {
            type: 'table',
            columns: ['프로젝트 관리 작업', '실제 동작', '권한 제한'],
            rows: [
              ['팀 변경 · 영구 삭제', '소유 팀 변경 또는 요청 이력 없는 프로젝트 정리', '조직/시스템 관리자 전용 (총괄자 불가)'],
              ['프로젝트 중지 / 재개', 'ACTIVE ↔ SUSPENDED 전환(중지 시 즉시 차단)', '중지는 총괄자 가능, ACTIVE 재활성화는 관리자 전용'],
              ['서비스 권한 부여·해제', '프로젝트에서 쓸 논리 모델 추가/제거', '조직/시스템 관리자 전용'],
              ['조직 멤버십 변경', '사용자 조직 권한·계층 조정', '시스템 관리자 전용']
            ]
          }
        ]
      }
    ]
  },
  {
    id: 'guide-director',
    group: '역할별 실전 가이드',
    title: '프로젝트 총괄자 실전 가이드',
    shortTitle: '프로젝트 총괄자 가이드',
    description: '담당 프로젝트 안에서 팀원을 초대하고 API 키 권한을 위임하며, 관리자 상한 이내에서 데이터 보호와 예산을 운영하는 방법입니다.',
    audience: '프로젝트 총괄자',
    minutes: 6,
    icon: '⌘',
    keywords: ['프로젝트 총괄자', 'PROJECT_DIRECTOR', '팀원 초대', '키 위임', '예산', '상한', '멤버 회수', '관계도'],
    sections: [
      {
        id: 'director-scope',
        title: '총괄자가 할 수 있는 일 vs 관리자 전용 작업',
        description: '프로젝트 총괄자(PROJECT_DIRECTOR)는 담당 프로젝트의 일상 운영·팀원 관리를 전담하며, 조직 인프라와 상한은 관리자가 보호합니다.',
        blocks: [
          {
            type: 'cards',
            items: [
              { label: 'INVITE', title: '팀원 초대', text: '담당 프로젝트의 기본 계정(GENERAL_USER) 초대 코드·링크를 발급합니다.' },
              { label: 'DELEGATE', title: '키 발급 위임·회수', text: '팀원별 API 키 발급 권한을 켜거나 끄고 멤버십을 회수합니다.' },
              { label: 'POLICY', title: '상한 내 보호·예산', text: '관리자 상한 범위 안에서 데이터 경계·DLP·프로젝트 예산을 조정합니다.' }
            ]
          },
          {
            type: 'table',
            columns: ['작업', '프로젝트 총괄자 (PROJECT_DIRECTOR)', '조직/시스템 관리자'],
            rows: [
              ['팀원 초대 (기본 계정)', '담당 프로젝트에 한해 발급 가능', '전체 조직/프로젝트 가능'],
              ['총괄자 계정 초대 · 팀 변경', '불가 (상위 권한 필요)', '가능 (조직/시스템 관리자)'],
              ['API 키 발급·폐기 · 멤버 위임', '담당 프로젝트 내 모든 멤버/키 관리', '전체 프로젝트 관리'],
              ['데이터 경계 · 프로젝트 예산', '관리자 상한 이하로 강화·설정 가능', '관리자 상한 자체 설정·변경'],
              ['논리 모델 허용 · 프로젝트 삭제/재개', '조회·요청만 가능 (직접 부여·삭제 불가)', '가능']
            ]
          }
        ]
      },
      {
        id: 'invite-member',
        title: 'STEP 1 · 담당 프로젝트에 팀원 초대하기',
        description: '프로젝트 화면의 초대 버튼 또는 상단 ⚙ 설정 › 계정 추가에서 팀원을 초대합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '프로젝트 › 팀원 초대 코드 발급',
            roleTag: '프로젝트 총괄자 화면',
            stepLabel: 'STEP 1',
            caption: '총괄자는 기본 계정(GENERAL_USER) 계층으로만 초대할 수 있습니다.',
            fields: [
              { label: '부여 계층', value: '기본 계정 (GENERAL_USER · 고정)', badge: '총괄자 초대 범위', badgeTone: 'info' },
              { label: '대상 프로젝트', value: 'cs-copilot (담당 프로젝트)', badge: '잠금', badgeTone: 'neutral' },
              { label: 'API 키 발급 권한 위임', value: '체크 (허용)', badge: 'ON', badgeTone: 'accent', hint: '체크하면 가입 직후 본인 키 직접 발급 가능' },
              { label: '지정 이메일 (선택)', value: 'dev.kim@acme.example' },
              { label: '만료 시간', value: '72시간' }
            ],
            actions: [
              { label: '초대 이력·사용자 관리' },
              { label: '닫기' },
              { label: '초대 코드/링크 발급', primary: true }
            ],
            result: {
              tone: 'success',
              title: '발급 후 팀원에게 전달할 정보',
              items: [
                '가입 링크: https://www.aiconnectgateway.com/#register?code=INV-EXAMPLE-7K9Q',
                '안내: 가입 시 비밀번호 설정 후 Google Authenticator 2차 인증을 필수로 등록해야 콘솔에 입장합니다.'
              ]
            },
            navigate: { label: '프로젝트 열기', destination: 'projects' }
          }
        ]
      },
      {
        id: 'delegate-and-revoke',
        title: 'STEP 2 · 팀원 키 발급 위임 전환 및 권한 회수',
        description: '합류한 팀원이 직접 API 키를 발급하도록 허용하거나, 퇴사·이동 시 즉시 프로젝트 권한과 활성 키를 회수합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '프로젝트 › 프로젝트 멤버 · 권한 관리',
            roleTag: '프로젝트 총괄자 화면',
            stepLabel: 'STEP 2',
            fields: [
              { label: 'lead.park@acme.example', value: 'MANAGER · 활성 키 1개', badge: '프로젝트 총괄자 · 키 발급 고정 허용', badgeTone: 'accent' },
              { label: 'dev.kim@acme.example', value: 'MEMBER · 활성 키 1개', badge: '키 발급 위임 ON', badgeTone: 'accent', hint: '클릭 1번으로 위임 회수 또는 멤버 회수 가능' }
            ],
            actions: [
              { label: '발급 위임 전환' },
              { label: '멤버 권한 회수' }
            ],
            result: {
              tone: 'warning',
              title: '회수 시 즉시 적용되는 보안 효과',
              items: [
                '발급 위임 OFF: 기존 키 유지, 새 키 발급만 즉시 중단',
                '멤버 권한 회수: 해당 프로젝트의 멤버 대기 초대 및 사용자가 발급한 활성 API 키 즉시 전부 폐기'
              ]
            },
            navigate: { label: '프로젝트 열기', destination: 'projects' }
          }
        ]
      },
      {
        id: 'policy-and-budget',
        title: 'STEP 3 · 상한 안에서 데이터 보호·예산 설정 및 관계도/사용량 확인',
        description: '관리자가 허용한 상한 이하로 프로젝트 데이터 경계를 조이거나, 월간 예산과 RPM을 설정합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '프로젝트 · 요금·한도 › 상한 내 정책·예산 조정',
            roleTag: '프로젝트 총괄자 화면',
            stepLabel: 'STEP 3',
            fields: [
              { label: '관리자 데이터 경계 상한', value: '승인된 외부 비보존 GPU 허용', badge: '수정 불가 (관리자 상한)', badgeTone: 'neutral' },
              { label: '프로젝트 적용 경계', value: '사내 GPU 전용 (INTERNAL_ONLY)', badge: '강화 선택 가능', badgeTone: 'accent', hint: '상한보다 느슨한 외부 API 허용은 선택 불가' },
              { label: '관리자 쿼터 상한', value: '120 RPM / 10,000,000 Tokens' },
              { label: '프로젝트 예산 · 한도 설정', value: '₩200,000 (월간) · 60 RPM / 5,000,000 Tokens', badge: '상한 이내 적용', badgeTone: 'info' }
            ],
            actions: [
              { label: '정책 저장', primary: true },
              { label: '한도 저장', primary: true }
            ],
            navigate: { label: '요금·한도 열기', destination: 'quotas' }
          },
          {
            type: 'steps',
            items: [
              { title: '조직 관계도에서 구성 한눈에 점검', text: '내 프로젝트에 어떤 총괄자와 팀원이 속해 있고 누가 API 키 발급 권한을 가졌는지 트리 구조로 확인합니다.', action: { label: '조직 관계도 열기', destination: 'relationship' } },
              { title: 'API 사용량·관측성으로 프로젝트 전체 모니터링', text: '총괄자는 담당 프로젝트의 모든 팀원 키 사용량과 요청·실패 이력을 API 키 원문 입력 없이 조회합니다.', action: { label: 'API 사용량 열기', destination: 'usage' } }
            ]
          }
        ]
      }
    ]
  },
  {
    id: 'guide-member',
    group: '역할별 실전 가이드',
    title: '팀원 실전 가이드 · 첫 API 호출',
    shortTitle: '팀원 · API 연결',
    description: '배정된 프로젝트에서 API 키를 발급하고 API 테스트 화면으로 검증한 뒤, cURL·Python·TypeScript 코드에 바로 연결합니다.',
    audience: '팀원',
    minutes: 6,
    icon: '</>',
    keywords: ['팀원', '개발자', '내 API', 'API 키', 'Base URL', 'API 테스트', 'curl', 'python', 'typescript', 'chat completions', 'streaming'],
    sections: [
      {
        id: 'member-start',
        title: '팀원이 사용하는 콘솔 메뉴 한눈에 보기',
        description: '팀원(GENERAL_USER)은 인프라 관리 메뉴 없이 내 프로젝트와 API 사용에 필요한 화면만 깔끔하게 봅니다.',
        blocks: [
          {
            type: 'checklist',
            items: [
              '대시보드 · 조직 관계도: 배정된 조직(Acme-AI-Lab)과 내 프로젝트(cs-copilot) 소속 구성 확인',
              '내 API: 허용된 논리 모델 확인, API 키 발급/폐기, Base URL 복사, 외부 AI 요청 상태 확인',
              'API 테스트: 발급한 키와 논리 모델로 JSON / SSE 스트리밍 즉시 검증',
              'API 사용량 · 관측성 · 요금·한도: 내가 발급한 키의 요청 수, 토큰, 비용과 X-Request-Id 추적'
            ]
          },
          {
            type: 'callout',
            tone: 'warning',
            title: 'API 키 발급 버튼이 보이지 않는다면?',
            text: '프로젝트에서 API 키 발급 권한 위임(canIssueApiKeys)이 꺼져 있는 상태입니다. 프로젝트 총괄자(lead.park@acme.example)에게 cs-copilot 프로젝트의 키 발급 권한 위임을 요청하세요.'
          }
        ]
      },
      {
        id: 'issue-api-key',
        title: 'STEP 1 · 내 API에서 API 키 발급 및 Base URL 확인',
        description: '프로젝트를 선택한 뒤 호출 용도가 드러나는 이름으로 키를 발급하고 원문·Base URL·모델명을 저장합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '내 API › API 키 발급',
            roleTag: '팀원 화면',
            stepLabel: 'STEP 1',
            fields: [
              { label: '선택 프로젝트', value: 'cs-copilot', badge: '키 발급 가능', badgeTone: 'accent' },
              { label: '허용 논리 모델 (/v1/models)', value: 'text-pro', mono: true },
              { label: '키 이름 (호출 용도)', value: 'cs-copilot-prod', hint: '사람 이름 대신 앱/환경 이름 권장' },
              { label: '만료일 (선택)', value: '2026-12-31' },
              { label: '키 데이터 경계', value: '사내 GPU 전용 (INTERNAL_ONLY)', hint: '프로젝트 상한보다 느슨한 값은 선택 불가' }
            ],
            actions: [
              { label: '취소' },
              { label: 'API 키 발급', primary: true }
            ],
            result: {
              tone: 'danger',
              title: '발급 직후 한 번만 표시되는 연결 정보 (모달을 닫으면 원문 초기화)',
              items: [
                'API 키 원문: sk_llmg_example_9f8a... (비밀 저장소·환경 변수에 즉시 저장)',
                '외부 HTTPS Base URL: https://api.aiconnectgateway.com/v1',
                '내부망 Base URL: http://100.64.0.10/v1',
                '호출 model: text-pro'
              ]
            },
            navigate: { label: '내 API 열기', destination: 'portal' }
          },
          {
            type: 'callout',
            tone: 'danger',
            title: 'GPU 서버 주소(예: :1234)를 넣지 마세요 · 유출 시 즉시 폐기',
            text: '앱 코드에는 반드시 Gateway의 /v1 주소를 넣어야 인증·라우팅·사용량·데이터 보호가 동작합니다. 키 원문이 코드·채팅·문서에 노출되면 내 API에서 즉시 폐기 후 새 키로 교체하세요.'
          }
        ]
      },
      {
        id: 'playground-verify',
        title: 'STEP 2 · API 테스트 화면에서 첫 응답 확인하기',
        description: '코드에 붙이기 전에 콘솔 내 API 테스트 화면으로 실제 연결·토큰·Request ID를 먼저 검증합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: 'API 테스트 › Gateway 연결 검증',
            roleTag: '팀원 화면',
            stepLabel: 'STEP 2',
            caption: 'Gateway 모드에서는 Base URL이 안전하게 고정되며, 입력한 API 키는 브라우저 sessionStorage에 저장되지 않습니다.',
            fields: [
              { label: '대상 모드', value: 'Gateway (기본)', badge: 'Base URL 읽기 전용', badgeTone: 'info' },
              { label: 'Base URL', value: 'https://api.aiconnectgateway.com/v1', mono: true },
              { label: 'API 키', value: 'sk_llmg_example_9f8a...', mono: true, hint: '방금 발급한 키 붙여넣기 (새로고침 시 메모리에서 제거)' },
              { label: '모델', value: 'text-pro', mono: true },
              { label: '스트리밍 (stream)', value: 'OFF (JSON 비스트리밍 먼저 검증)' },
              { label: '테스트 메시지', value: '회의 내용을 세 문장으로 요약해 주세요.' }
            ],
            actions: [
              { label: '모델 조회 (/v1/models)' },
              { label: '요청 전송', primary: true }
            ],
            result: {
              tone: 'success',
              title: '성공 응답 예시',
              items: [
                '상태: HTTP 200 OK · 응답 본문(choices[0].message.content) 정상 출력',
                '사용 토큰: 128 tokens · 응답 헤더 X-Request-Id: req_9f2a4c1b',
                '확인: API 사용량 및 관측성 화면에 내 키 기준으로 자동 기록됨'
              ]
            },
            navigate: { label: 'API 테스트 열기', destination: 'playground' }
          }
        ]
      },
      {
        id: 'code-examples',
        title: 'STEP 3 · OpenAI 호환 SDK · cURL 연동 예제',
        description: '기존 OpenAI SDK에서 base_url(또는 baseURL)과 api_key만 바꾸면 그대로 연동됩니다.',
        blocks: [
          {
            type: 'table',
            columns: ['Endpoint', '지원', '설명'],
            rows: [
              ['GET /v1/models', '지원', '프로젝트에 허용된 논리 모델 목록 조회 (data[].id 사용)'],
              ['POST /v1/chat/completions', '지원', 'JSON 응답 및 stream: true SSE 스트리밍 중계'],
              ['POST /v1/responses', '미지원', '현재 Gateway 공개 API에 없음'],
              ['POST /v1/embeddings', '미지원', '현재 Gateway 공개 API에 없음']
            ]
          },
          {
            type: 'code',
            language: 'bash',
            title: '환경 변수 및 허용 모델 조회 (GET /v1/models)',
            code: `export AICONNECT_BASE_URL="https://api.aiconnectgateway.com/v1"
export AICONNECT_API_KEY="sk_llmg_example_9f8a..."

curl "$AICONNECT_BASE_URL/models" \\
  -H "Authorization: Bearer $AICONNECT_API_KEY"`
          },
          { type: 'code', language: 'bash', title: 'cURL', code: curlExample },
          { type: 'code', language: 'python', title: 'Python', code: pythonExample },
          { type: 'code', language: 'typescript', title: 'TypeScript', code: typescriptExample },
          {
            type: 'callout',
            tone: 'warning',
            title: '스트리밍 실패 전환 한계 · X-Request-Id 보관',
            text: '최소 예제에서는 모델 호환성을 위해 temperature를 생략합니다. stream: true 호출 시 첫 토큰 전달 전에는 대체 모델로 전환되지만, 일부 토큰이 전달된 뒤 오류는 스트림을 종료합니다. 문제 추적 시 응답 헤더의 X-Request-Id를 함께 기록하세요.'
          }
        ]
      }
    ]
  }
]
