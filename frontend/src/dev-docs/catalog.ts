import type { DocGroup, DocPage } from './types'
import { resolveDocumentId, searchDocuments } from './navigation'
import { roleGuides } from './roleGuides'
import { operationsGuides } from './operationsGuides'

export const DOCS_REVIEWED_AT = '2026. 11. 22.'
export const DOCS_REVIEWED_ISO = '2026-11-22'

const overviewDoc: DocPage = {
  id: 'overview',
  group: '시작하기',
  title: 'AICONNECT 한눈에 보기',
  shortTitle: '개요·권한',
  description: 'AICONNECT의 요청 처리 흐름과 시스템 관리자 · 프로젝트 총괄자 · 팀원 3단계 역할 구조를 실제 예시 데이터로 이해합니다.',
  audience: '공통',
  minutes: 3,
  icon: '◎',
  keywords: ['개요', '권한', '역할', '시스템 관리자', '프로젝트 총괄자', '팀원', '조직 관계도', '용어', 'Acme-AI-Lab', 'cs-copilot'],
  sections: [
    {
      id: 'request-path',
      title: 'AICONNECT가 요청을 처리하는 4단계',
      description: '개발자와 서비스는 GPU 서버에 직접 접속하지 않고 단일 Gateway(/v1)만 호출합니다.',
      blocks: [
        {
          type: 'flow',
          items: [
            { label: '1', title: '애플리케이션 · SDK', text: '프로젝트 API 키(sk_llmg_example_...)와 논리 모델(text-pro)로 Gateway 호출' },
            { label: '2', title: 'AICONNECT Gateway', text: '키 인증, 데이터 경계·DLP, RPM·예산 한도 평가' },
            { label: '3', title: '논리 서비스 · Target', text: 'Priority·대기열 정책에 따라 최적의 실제 배포 선택' },
            { label: '4', title: '사내 GPU / 승인 외부 AI', text: 'seoul-gpu-01(LM Studio) 또는 승인된 외부 Provider에서 추론' }
          ]
        }
      ]
    },
    {
      id: 'role-hierarchy',
      title: '역할별 권한 비교 (시스템 관리자 · 총괄자 · 팀원)',
      description: '예시 조직 Acme-AI-Lab · 프로젝트 cs-copilot을 기준으로 세 역할의 책임과 제한이 나뉩니다.',
      blocks: [
        {
          type: 'table',
          columns: ['역할 (Account Tier)', '예시 계정', '할 수 있는 일', '제한 사항'],
          rows: [
            [
              '시스템 관리자 (SYSTEM_ADMIN / 조직 관리자)',
              'admin@acme.example',
              '조직·팀·프로젝트 생성, 총괄자·팀원 초대, GPU Runtime·외부 AI·논리 서비스 구성, 상한 설정',
              '모든 계정은 초대 가입+TOTP 필수, 직접 사용자 생성 비활성화'
            ],
            [
              '프로젝트 총괄자 (PROJECT_DIRECTOR)',
              'lead.park@acme.example',
              '담당 프로젝트(cs-copilot) 팀원 초대, API 키 위임·회수, 상한 내 데이터 보호·예산 관리, 전체 키 사용량 조회',
              '팀 변경·프로젝트 삭제·재활성화·서비스 권한 부여·인프라 변경 불가'
            ],
            [
              '팀원 / 기본 계정 (GENERAL_USER)',
              'dev.kim@acme.example',
              '위임 시 본인 API 키 발급·폐기, API 테스트 검증, 본인 키 사용량·Request ID 조회',
              '타인 키 관리·초대 발급·예산/정책/인프라 변경 불가'
            ]
          ]
        },
        {
          type: 'mockup',
          screen: '조직 관계도 (#relationship)',
          roleTag: '공통 화면 · 트리 계층',
          caption: '내 계정이 속한 조직부터 관리자, 프로젝트, 프로젝트 총괄자, 팀원의 키 발급 권한까지 한 화면에서 확인합니다.',
          fields: [
            { label: '1단계 · 최고 관리자', value: 'admin@acme.example', badge: '시스템 관리자 · 전체 관리', badgeTone: 'accent' },
            { label: '2단계 · 선택 조직', value: 'Acme-AI-Lab (ACTIVE)', badge: '팀 Platform-AI', badgeTone: 'info' },
            { label: '3단계 · 프로젝트', value: 'cs-copilot', badge: '활성 · text-pro 연결', badgeTone: 'accent' },
            { label: '4단계 · 프로젝트 총괄자', value: 'lead.park@acme.example', badge: 'API 키 발급 가능', badgeTone: 'accent' },
            { label: '5단계 · 팀원', value: 'dev.kim@acme.example', badge: 'API 키 발급 가능 (위임됨)', badgeTone: 'info' }
          ],
          navigate: { label: '조직 관계도 열기', destination: 'relationship' }
        }
      ]
    },
    {
      id: 'core-terms',
      title: '꼭 알아둘 6가지 핵심 용어',
      blocks: [
        {
          type: 'table',
          columns: ['용어', '의미', '문서 예시'],
          rows: [
            ['조직 (Workspace)', '인프라·비용·권한이 완전히 격리되는 운영 경계', 'Acme-AI-Lab'],
            ['프로젝트', 'API 키, 데이터 경계, 예산 총량제, 사용량 집계 단위', 'cs-copilot'],
            ['논리 서비스', '앱이 model 필드에 넣는 안정적인 서비스 키', 'text-pro'],
            ['Target', '논리 서비스에 연결된 실제 모델 실행 후보와 우선순위', 'P1 seoul-gpu-01'],
            ['Runtime Endpoint', 'Gateway가 연결하는 사설망 추론 서버', 'http://100.64.0.20:1234'],
            ['프로젝트 API 키', '프로젝트 권한·한도 식별용 비밀 키(1회 표시)', 'sk_llmg_example_9f8a...']
          ]
        }
      ]
    }
  ]
}

const onboardingAuthDoc: DocPage = {
  id: 'onboarding-auth',
  group: '시작하기',
  title: '초대 가입과 2차 인증(TOTP)',
  shortTitle: '가입·2차 인증',
  description: '1회용 초대 링크로 계정을 만들고 Google Authenticator 2차 인증을 등록해 콘솔에 입장하는 전체 과정입니다.',
  audience: '공통',
  minutes: 4,
  icon: '→',
  keywords: ['초대', '가입', 'INV', 'register', 'TOTP', 'Google Authenticator', '2차 인증', '복구 코드', '로그인', '세션'],
  sections: [
    {
      id: 'invite-policy',
      title: '가입 원칙: 초대 코드 + TOTP 필수',
      blocks: [
        {
          type: 'callout',
          tone: 'info',
          title: '자유 회원가입 및 직접 계정 생성은 제공하지 않습니다',
          text: '상위 관리자 또는 프로젝트 총괄자가 발급한 1회용 초대 코드·가입 링크가 있어야 가입할 수 있습니다. 로그인 시 비밀번호와 함께 Google Authenticator 2차 인증을 필수로 완료해야 콘솔 메뉴가 열립니다.'
        }
      ]
    },
    {
      id: 'invite-register-screen',
      title: 'STEP 1 · 초대 링크로 계정 생성하기',
      description: '전달받은 가입 링크를 열면 코드가 자동으로 검증되며, 주소창의 초대 코드는 보안을 위해 즉시 지워집니다.',
      blocks: [
        {
          type: 'mockup',
          screen: '초대 코드 회원가입 (#register)',
          roleTag: '신규 가입자 화면',
          stepLabel: 'STEP 1',
          fields: [
            { label: '전달받은 가입 링크', value: 'https://www.aiconnectgateway.com/#register?code=INV-EXAMPLE-7K9Q', mono: true, hint: '화면 로드 즉시 주소창에서 ?code= 파라미터 자동 제거' },
            { label: '초대 검증 결과', value: 'Acme-AI-Lab · cs-copilot · 기본 계정 (GENERAL_USER)', badge: '유효한 초대', badgeTone: 'accent' },
            { label: '이메일', value: 'dev.kim@acme.example', hint: '지정 이메일 초대인 경우 동일 이메일만 허용' },
            { label: '비밀번호', value: '•••••••••••• (12자 이상 ~ 72바이트 이하)' }
          ],
          actions: [
            { label: '로그인으로 돌아가기' },
            { label: '계정 생성', primary: true }
          ],
          result: {
            tone: 'success',
            title: '계정 생성 직후 다음 단계',
            items: [
              '자동으로 2차 인증(Google Authenticator) 등록 화면으로 이동합니다.'
            ]
          }
        }
      ]
    },
    {
      id: 'totp-enroll-screen',
      title: 'STEP 2 · Google Authenticator 2차 인증 및 복구 코드 보관',
      description: 'QR 코드를 스캔한 뒤 6자리 코드를 입력하고, 발급된 복구 코드 8개를 안전한 곳에 저장합니다.',
      blocks: [
        {
          type: 'mockup',
          screen: 'Google Authenticator 2차 인증 등록',
          roleTag: '최초 로그인 필수',
          stepLabel: 'STEP 2',
          fields: [
            { label: '등록 계정', value: 'dev.kim@acme.example' },
            { label: 'QR 코드 / 수동 시크릿 키', value: 'Google Authenticator 앱으로 스캔 또는 수동 입력', badge: '1회 표시', badgeTone: 'info' },
            { label: '앱 6자리 인증 코드', value: '482 910', mono: true },
            { label: '복구 코드 8개 보관 확인', value: '안전한 비밀번호 관리자에 저장 완료 (체크 필수)', badge: '필수 확인', badgeTone: 'warning' }
          ],
          actions: [
            { label: '복구 코드 복사' },
            { label: '2차 인증 완료 후 콘솔 입장', primary: true }
          ]
        },
        {
          type: 'callout',
          tone: 'warning',
          title: '세션 · 로그아웃 보안 안내',
          text: '토큰 갱신(/api/auth/refresh)과 로그아웃(/api/auth/logout)은 동일 Origin 검증이 적용되며, 로그아웃 시 서버 세션이 즉시 폐기됩니다. 비상 플랫폼 토큰(X-Admin-Token)은 긴급 복구 전용으로만 사용하세요.'
        }
      ]
    }
  ]
}

export const devDocs: DocPage[] = [
  overviewDoc,
  onboardingAuthDoc,
  ...roleGuides,
  ...operationsGuides
]

export const docGroups: readonly DocGroup[] = ['시작하기', '역할별 실전 가이드', '인프라·정책 운영', '진단·운영 참조'] as const

export function findDoc(id: string | null | undefined): DocPage {
  const resolved = id ? resolveDocumentId(id) : ''
  return devDocs.find(doc => doc.id === resolved) ?? devDocs[0]!
}

export function searchDocs(query: string) {
  return searchDocuments(devDocs, query)
}
