import type { DocPage } from './types'

export const operationsGuides: DocPage[] = [
  {
    id: 'runtime-routing',
    group: '인프라·정책 운영',
    title: 'Runtime · 외부 AI · 라우팅 · 대기열',
    shortTitle: 'Runtime·라우팅·대기열',
    description: '로컬 Runtime 종류별 관리 범위, 외부 Provider 승인·단가, 논리 서비스 대기열과 모델 테스트 콘솔을 정리합니다.',
    audience: '관리자',
    minutes: 8,
    icon: '⇄',
    keywords: [
      'LM Studio',
      'Ollama',
      'llama.cpp',
      'OpenAI-compatible',
      '외부 AI',
      'FAST 가격',
      '대기열',
      'IMMEDIATE',
      'LOCAL_FIRST_WAIT',
      'LOCAL_ONLY_QUEUE',
      'Failover',
      '모델 테스트',
      '시스템 구조'
    ],
    sections: [
      {
        id: 'runtime-matrix',
        title: 'Runtime 종류별 지원과 로드 상태 원칙',
        description: '현재 로드된 모델과 과거 등록 모델을 분리해 표시하며, 장애 시에도 기존 Target 참조를 임의로 삭제하지 않습니다.',
        blocks: [
          {
            type: 'table',
            columns: ['Runtime', '모델 조회 방식', 'AICONNECT 관리 범위'],
            rows: [
              ['LM Studio', 'native /api/v1/models (필요 시 /v1/models)', '지원되는 native 로드·언로드·다운로드 API 호출'],
              ['Ollama', '/api/tags 다운로드 + /api/ps 메모리 상태', '현재 코드에서 로드·언로드 관리 API 미지원 (Ollama에서 직접 관리)'],
              ['llama.cpp', 'router /models + /v1/models, 제공 시 /props', 'router 모드에서 로드·언로드 지원 (단일 프로세스 서버는 직접 관리)'],
              ['OpenAI-compatible', '/v1/models', '호환 Chat API 중계 (파일 목록·내부 로드 설정 조회 불가)']
            ]
          },
          {
            type: 'callout',
            tone: 'warning',
            title: '프로필 저장과 실제 실행값은 다릅니다',
            text: 'LM Studio native REST 적용 항목은 Context Length, Evaluation Batch Size, Flash Attention, expert 수, KV Cache GPU offload 등으로 제한됩니다. Q4/Q8 소스 변경이나 미지원 옵션은 LM Studio에서 직접 설정한 뒤 모델 동기화를 실행하세요.'
          },
          {
            type: 'links',
            items: [
              { label: 'LM Studio Developer Docs', href: 'https://lmstudio.ai/docs/developer', description: 'List · Load · Download native REST 공식 명세' }
            ]
          }
        ]
      },
      {
        id: 'external-ai',
        title: '외부 OpenAI Provider 승인 · 추론 · Fast 가격',
        description: '외부 Provider API 키는 관리자가 암호화 보관하며 프로젝트별 명시적 승인이 있어야만 호출됩니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '외부 AI › 프로젝트 외부 접근 승인',
            roleTag: '시스템/조직 관리자 화면',
            fields: [
              { label: 'Provider · Base URL', value: 'OPEN-AI-API (https://api.openai.com/v1)' },
              { label: '대상 프로젝트', value: 'cs-copilot' },
              { label: '수동 사용 허용 (manualAllowed)', value: 'ON', badge: '수동 서비스 호출 가능', badgeTone: 'accent' },
              { label: '자동 Failover 허용', value: 'OFF (기본값)', badge: '예상치 못한 비용 방지', badgeTone: 'warning' },
              { label: '월 비용 상한 / 만료', value: 'USD 50 / 2026-12-31' }
            ],
            actions: [
              { label: '반려' },
              { label: '승인 저장', primary: true }
            ],
            navigate: { label: '외부 AI 열기', destination: 'external' }
          },
          {
            type: 'table',
            columns: ['설정 항목', '동작 규칙', '주의사항'],
            rows: [
              ['추론 수준 · 서비스 티어', '모델에 명시하면 호출자·서비스 설정을 덮어씀', '지원 표시와 실제 Provider 적용·청구는 요청 상세에서 확인'],
              ['표준 · 캐시 단가', '100만 토큰 단가와 통화(KRW/USD) 별도 저장', '등록 가격 기준 추정치이며 실제 공급자 청구서가 아님'],
              ['FAST 가격', 'Fast 입력·캐시·출력 단가를 별도로 등록', 'FAST 가격이 없으면 일반 단가로 임의 계산하지 않고 미산정 처리']
            ]
          }
        ]
      },
      {
        id: 'request-scheduling',
        title: '로컬 우선 배분과 대기열',
        description: '로컬 GPU가 바쁠 때 바로 외부로 넘기지 않고 잠시 기다렸다가 로컬 슬롯을 배정할 수 있습니다.',
        blocks: [
          {
            type: 'paragraph',
            text: 'LLM 서비스의 요청 배분·대기열에서 IMMEDIATE(기존 즉시 전환), LOCAL_FIRST_WAIT(로컬 대기 후 승인 외부 허용), LOCAL_ONLY_QUEUE(로컬 전용 대기)를 선택합니다. 기존 서비스 기본값은 IMMEDIATE입니다. 기다리는 요청은 아직 모델을 점유하지 않고 슬롯이 비면 배정되며, 이미 실행한 요청을 중간에 이동하지 않습니다.'
          },
          {
            type: 'table',
            columns: ['설정 위치', '내용 및 기본값'],
            rows: [
              ['인프라 → Runtime 전체 동시 처리 한도', '같은 Runtime의 모델·논리 서비스·Chat 테스트가 공유 (로컬 기본 1)'],
              ['LLM 서비스 → 요청 배분 · 대기열', '최대 대기 60초, 서비스 대기 요청 수·본문 크기·키별 대기 수 한도'],
              ['Target 옵션 (Priority / Weight / Degraded)', '낮은 Priority 우선, 같은 Priority 내 상대 Weight 분배, Degraded는 최후 후보'],
              ['요청 상세 → 대기와 실행 상태', '큐 상태·대기 시간·실행 시간·외부 배정 이유·한도/만료 오류 확인']
            ]
          },
          {
            type: 'callout',
            tone: 'warning',
            title: '보안 정책과 호환성이 대기열보다 우선합니다',
            text: '외부 전송 금지(INTERNAL_ONLY) 프로젝트는 대기 시간이 끝나도 외부로 전송하지 않습니다. 키·권한·보안 정책은 실행 직전 다시 검사하며, 오프라인·미로드·필수 기능 미지원 모델만 있으면 기다리지 않고 오류를 반환합니다. 직접 모델 테스트는 모델이 바쁘면 MODEL_AT_CAPACITY를 표시합니다.'
          },
          {
            type: 'callout',
            tone: 'info',
            title: 'Redis를 쓰는 짧은 HTTP 대기열입니다',
            text: '다중 API 서버는 Redis로 순서와 슬롯을 공유합니다. Redis에는 본문·이미지·API 비밀을 저장하지 않으며 대기 본문은 수신 서버 메모리에만 남습니다(서버 재시작 시 자동 재실행 안 함).'
          }
        ]
      },
      {
        id: 'model-playground-and-system',
        title: '모델 테스트 콘솔과 시스템 구조 연결도',
        blocks: [
          {
            type: 'table',
            columns: ['콘솔 화면', '대상과 목적', '핵심 제한 및 특징'],
            rows: [
              ['모델 테스트 콘솔', '관리자가 선택한 Runtime / Provider 모델 직접 테스트', '이미지(JPEG/PNG/WebP/GIF), PDF 최대 100페이지 텍스트 추출, 파일 4MB/전체 8MB(5개/20개), 암호화 대화 저장(PLAYGROUND 통계 분리)'],
              ['API 테스트', '프로젝트 API 키 → Gateway → 논리 서비스 경로 검증', '프로젝트 권한·한도·데이터 보호·Failover 전체 경로 확인'],
              ['시스템 구조', '프로젝트 → Gateway → 논리 서비스 → Target 연결 맵', '초록(정상/활성) · 노랑(미로드/복구/Degraded) · 빨강(장애/권한) · 회색(비활성), GPU 인벤토리 중복 합산 안 함']
            ]
          }
        ]
      }
    ]
  },
  {
    id: 'data-protection-quotas',
    group: '인프라·정책 운영',
    title: '데이터 보호 · 요금 · 총량제',
    shortTitle: '데이터 보호·요금',
    description: '사내 GPU 전용 데이터 경계, 민감정보 탐지(DLP) 모드, KRW·USD 단가와 조직·팀·프로젝트·키 예산 한도를 설정합니다.',
    audience: '관리자',
    audiences: ['관리자', '프로젝트 총괄자'],
    minutes: 6,
    icon: '◆',
    keywords: ['데이터 보호', 'DLP', 'INTERNAL_ONLY', 'APPROVED_EXTERNAL_NO_RETENTION', 'EXTERNAL_ALLOWED', 'REDACT', 'BLOCK', 'LOCAL_ONLY', '요금', '쿼터', '총량제', 'KRW', 'USD'],
    sections: [
      {
        id: 'data-boundary',
        title: '3단계 데이터 경계와 상한 원칙',
        description: '상위 경계가 하위 경계를 제한합니다. 프로젝트 총괄자와 키 발급자는 관리자 상한보다 느슨한 정책을 고를 수 없습니다.',
        blocks: [
          {
            type: 'table',
            columns: ['데이터 경계 정책', '허용 대상', '외부 호출 차단 동작'],
            rows: [
              ['사내 GPU 전용 (INTERNAL_ONLY)', '사내 로컬 Runtime만 허용', '민감정보 탐지 여부와 무관하게 외부 GPU·상용 API 전면 제외. 로컬 없으면 503 LOCAL_MODEL_UNAVAILABLE'],
              ['승인된 외부 비보존 GPU 허용 (APPROVED_EXTERNAL_NO_RETENTION)', '사내 GPU + 승인된 외부 비보존 GPU', '상용 외부 API는 차단'],
              ['외부 API 허용 (EXTERNAL_ALLOWED)', '사내 + 승인된 외부 GPU/상용 API', '프로젝트 외부 AI 승인과 DLP 정책 조건은 별도 평가']
            ]
          },
          {
            type: 'callout',
            tone: 'info',
            title: '정책 합성 순서: 플랫폼 → 조직 → 프로젝트 → 서비스·API 키',
            text: '항상 더 엄격한 제한이 우선합니다. 프로젝트에 사내 GPU 전용을 켜 두었다면 하위 API 키에서 외부 허용을 선택하더라도 외부로 전송되지 않습니다.'
          }
        ]
      },
      {
        id: 'dlp-policy',
        title: '민감정보 탐지(DLP) 설정 화면 예시',
        description: '프롬프트 내 API 키·개인정보·금융정보·기밀 키워드·사용자 정의 정규식을 탐지해 차단하거나 로컬 전용으로 우회합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '데이터 보호 › 프로젝트 DLP 정책 편집',
            roleTag: '관리자 · 프로젝트 총괄자 화면',
            fields: [
              { label: '적용 범위', value: '프로젝트 · cs-copilot', badge: '상위 상한 상속', badgeTone: 'info' },
              { label: '모드 (Mode)', value: 'ENFORCE', badge: '탐지 시 즉시 조치', badgeTone: 'accent' },
              { label: '조치 (Action)', value: 'LOCAL_ONLY', hint: '민감정보 발견 시 외부 Target 후보 제외' },
              { label: '기본 탐지 항목', value: 'Secret / API 키 · 개인정보 · 금융정보 · 미디어 입력' },
              { label: '사용자 정규식 (JSON 배열)', value: '["ACME-[0-9]{6}"]', mono: true, hint: '탐지 단계 수 제한(ReDoS 방어) 자동 적용' }
            ],
            actions: [
              { label: '정책 초기화' },
              { label: 'DLP 정책 저장', primary: true }
            ],
            navigate: { label: '데이터 보호 열기', destination: 'data-protection' }
          },
          {
            type: 'table',
            columns: ['설정값', '실제 동작', '운영 유의사항'],
            rows: [
              ['OFF / MONITOR / ENFORCE', '해제 / 탐지 기록만 / 탐지 시 조치 실행', '상위 범위에서 ENFORCE가 켜지면 하위 OFF로 풀리지 않음'],
              ['ALLOW / LOCAL_ONLY / BLOCK', '허용 / 외부 제외(로컬만) / 즉시 차단', 'BLOCK 시 요청 거부 및 실패 진단에 보호 결정 기록'],
              ['REDACT', '현재 구현에서는 안전하게 로컬 전용으로 처리', '외부 API로 완전히 마스킹된 본문을 보낸다고 가정하지 않기']
            ]
          }
        ]
      },
      {
        id: 'pricing-quotas',
        title: '원화·USD 단가 및 예산·총량제 설정',
        description: '조직·팀·프로젝트·API 키 단위로 일간·월간·전체 예산 한도와 RPM/월 토큰 한도를 적용합니다.',
        blocks: [
          {
            type: 'mockup',
            screen: '요금·한도 › 프로젝트 예산 · 총량제',
            roleTag: '관리자 · 프로젝트 총괄자 화면',
            fields: [
              { label: '적용 범위', value: 'PROJECT · cs-copilot' },
              { label: '집계 기간', value: 'MONTHLY (월간)' },
              { label: '통화 / 예산 한도', value: 'KRW · ₩200,000', badge: '관리자 상한 이하', badgeTone: 'accent' },
              { label: '요청·토큰 한도', value: '60 RPM / 5,000,000 Tokens' }
            ],
            actions: [
              { label: '초기화' },
              { label: '총량제 저장', primary: true }
            ],
            result: {
              tone: 'warning',
              title: '통화 및 한도 초과 처리 원칙',
              items: [
                'KRW와 USD는 임의 환산·합산하지 않고 각 통화별로 독립 비교·표시합니다.',
                '활성 한도 도달 시 Gateway가 429 상태로 차단하며 사용량·요청 상세에 사유를 남깁니다.'
              ]
            },
            navigate: { label: '요금·한도 열기', destination: 'quotas' }
          }
        ]
      }
    ]
  },
  {
    id: 'diagnostics',
    group: '진단·운영 참조',
    title: '요청 실패 진단 · Runtime 문제 해결',
    shortTitle: '오류 진단·해결',
    description: 'X-Request-Id로 개별 Attempt와 Failover 경로를 추적하고, HTTP 오류 코드와 Runtime 상태 이상을 빠르게 분리해 해결합니다.',
    audience: '공통',
    minutes: 6,
    icon: '!',
    keywords: [
      '오류',
      'X-Request-Id',
      'Attempt',
      'MODEL_UNAVAILABLE',
      'LOCAL_MODEL_UNAVAILABLE',
      'CONTEXT_LENGTH_EXCEEDED',
      'REQUEST_FORMAT_UNSUPPORTED',
      'ORIGIN_MISMATCH',
      'STANDBY',
      'UNHEALTHY',
      '401',
      '403',
      '429',
      '503',
      '504'
    ],
    sections: [
      {
        id: 'trace-request',
        title: '한 요청의 실행 시도(Attempt) 끝까지 추적하기',
        description: '최종 결과가 성공(200)이어도 첫 Target 실패 후 Failover된 요청일 수 있습니다. 관측성 화면에서 Request ID로 확인하세요.',
        blocks: [
          {
            type: 'mockup',
            screen: '관측성 › 요청 상세 및 Attempt 진단',
            roleTag: '요청 추적 예시',
            fields: [
              { label: 'X-Request-Id', value: 'req_9f2a4c1b', mono: true, badge: '최종 200 OK (Failover)', badgeTone: 'accent' },
              { label: '프로젝트 · 서비스 · 키', value: 'cs-copilot / text-pro / cs-copilot-prod' },
              { label: 'Attempt #1 (Primary)', value: 'seoul-gpu-01 → 503 MODEL_UNAVAILABLE (12ms)', badge: 'FAIL', badgeTone: 'danger' },
              { label: 'Attempt #2 (Secondary)', value: 'backup-gpu-02 → 200 OK (1.8s · 142 tok)', badge: 'SUCCESS', badgeTone: 'accent' },
              { label: '데이터 보호 결정', value: 'EXTERNAL_ALLOWED (민감정보 미탐지)' }
            ],
            actions: [
              { label: '원인 · 권장 조치 보기' }
            ],
            navigate: { label: '관측성 열기', destination: 'observability' }
          },
          {
            type: 'callout',
            tone: 'info',
            title: 'METADATA 보관 정책에서는 원문을 보여주지 않습니다',
            text: '요청 본문 저장이 꺼져 있어도 상태 코드·지연시간·추정/실제 토큰·보호 결정·권장 조치 진단은 항상 확인할 수 있습니다.'
          }
        ]
      },
      {
        id: 'error-codes',
        title: '자주 만나는 HTTP / 오류 코드 해결표',
        blocks: [
          {
            type: 'table',
            columns: ['HTTP / 오류 코드', '원인', '권장 조치'],
            rows: [
              ['401 / AUTH_REQUIRED', 'API 키 오류·폐기·만료 또는 세션 만료', 'Authorization Bearer 헤더, 키 상태, 재로그인 확인'],
              ['403 / ORIGIN_MISMATCH', '허용되지 않은 서비스·중지된 조직 또는 교차 출처 refresh/logout', '프로젝트 서비스 권한(/v1/models), 조직 상태, 동일 Origin 호출 확인'],
              ['400 / CONTEXT_LENGTH_EXCEEDED', '입력 프롬프트 + 출력 한도가 모델 컨텍스트 초과', '대화·첨부 크기 또는 max_completion_tokens 축소, 큰 컨텍스트 모델 연결'],
              ['400 / REQUEST_FORMAT_UNSUPPORTED', '모델이 받지 않는 temperature·토큰 필드·옵션 전송', '미지원 옵션 제거 및 서비스/모델 기본값에서 필드 생략 선택'],
              ['400 (LM Studio 언로드)', 'model key 대신 로드 인스턴스 ID 필요', '인프라에서 모델 동기화 후 Drain → 정확한 instance_id로 언로드'],
              ['429', 'RPM·월 토큰·예산·대기열 한도 초과', '요금·한도 정책 및 Retry-After 확인 후 재시도'],
              ['503 MODEL_UNAVAILABLE / LOCAL_MODEL_UNAVAILABLE', '조건(상태·Capability·사내 GPU 전용)에 맞는 후보 없음', 'Endpoint/모델 로드·Target 활성 상태 확인(로컬 전용 시 외부 우회 안 함)'],
              ['504', 'Runtime 응답 제한 시간 또는 프록시/앱 Timeout 초과', 'Gateway(360s)·프록시·앱 중 가장 짧은 제한 시간과 GPU 상태 확인']
            ]
          }
        ]
      },
      {
        id: 'runtime-states',
        title: 'Runtime 상태 구분과 장애 점검 체크리스트',
        blocks: [
          {
            type: 'table',
            columns: ['상태', '의미', '조치'],
            rows: [
              ['HEALTHY', 'API 정상 + 로드된 모델 1개 이상 확인', '정상 라우팅 대상'],
              ['STANDBY', 'API 정상이나 현재 메모리에 로드된 모델 없음 (장애 아님)', 'LM Studio/Ollama에서 모델 로드 후 동기화'],
              ['LOADING / RECOVERING / DRAINING', '모델 적재 중 / 복구 검증 중 / 신규 요청 유입 중지', '작업 완료 대기 또는 Drain 해제'],
              ['CONNECTED', 'API 응답은 있으나 로드 모델 목록 미확인', 'Runtime 버전과 native 모델 API 확인'],
              ['SUSPECT / UNHEALTHY / DISABLED', '최근/연속 연결 실패 또는 관리자 사용 중지', 'Tailscale 경로·서버 포트·인증 토큰·인프라 연결 확인']
            ]
          },
          {
            type: 'callout',
            tone: 'warning',
            title: '호스트에서는 되는데 API 컨테이너에서만 실패한다면',
            text: 'Docker 컨테이너가 호스트의 Tailnet 경로를 보지 못하는 상태일 수 있습니다. 컨테이너에서 Runtime 주소 연결과 Tailscale 오버레이 구성을 확인하세요.'
          },
          {
            type: 'checklist',
            items: [
              '문제 보고 시 포함: 발생 시각, X-Request-Id, error.code, 논리 모델명, Attempt 목록 (예: req_9f2a4c1b)',
              '절대 첨부 금지: API 키 원문, 로그인 비밀번호, Runtime 토큰, 2FA 복구 코드, Webhook URL'
            ]
          }
        ]
      }
    ]
  },
  {
    id: 'operations-deploy',
    group: '진단·운영 참조',
    title: '관측 · 감사 · 알림 · 배포 · 보안',
    shortTitle: '관측·배포·보안',
    description: '역할별 사용량 집계, 변경 불가 감사 로그, Discord/Telegram 경보, Timeout·배포 프로필·보안 체크리스트를 한눈에 봅니다.',
    audience: '관리자',
    minutes: 7,
    icon: '◉',
    keywords: [
      '사용량',
      '감사 로그',
      'Discord',
      'Telegram',
      'Prometheus',
      'Grafana',
      'Timeout',
      '360000',
      '보관 정책',
      'Standalone',
      'HA',
      'Kubernetes',
      'TOTP',
      '보안'
    ],
    sections: [
      {
        id: 'observe-audit-alerts',
        title: '사용량 · 감사 로그 · 알림 · 외부 모니터링',
        blocks: [
          {
            type: 'table',
            columns: ['역할 / 기능', '조회·동작 범위', '핵심 규칙'],
            rows: [
              ['팀원 (GENERAL_USER)', '본인이 발급한 API 키 요청·토큰·비용', '사용량 화면에 API 키 원문을 입력하지 않음'],
              ['프로젝트 총괄자 (PROJECT_DIRECTOR)', '담당 프로젝트 전체 키의 사용량·요청 상세', '타 프로젝트 통계 조회 불가'],
              ['조직 / 시스템 관리자', '조직 전체 프로젝트·서비스·실제 배포·Failover 통계', '통화는 KRW/USD 분리, Playground 통계 별도'],
              ['감사 로그 (#audit)', '계정·초대·정책·원문 열람·삭제 이력 영구 보존', '비밀값 원문 미기록, Break-glass 토큰 사용 시 별도 귀속 기록'],
              ['알림 채널 (#notifications)', 'Discord Webhook(운영 https:// 필수) · Telegram 경보', '재전송 간격으로 폭주 방지, 오류 원문 비밀값/프롬프트 미포함'],
              ['Prometheus · Grafana', '관리 포트 :18081 / :18082 의 /actuator/prometheus', '인터넷 공개 금지(사설망/Tailnet ACL로 Prometheus 서버만 허용)']
            ]
          }
        ]
      },
      {
        id: 'security-timeout',
        title: '비밀값 경계 · 보관 정책 · Gateway Timeout',
        blocks: [
          {
            type: 'table',
            columns: ['항목', '기준값 / 정책', '설명'],
            rows: [
              ['로그인 · 2FA', '12~72바이트 비밀번호 + Google Authenticator TOTP', 'refresh/logout 동일 Origin 검사, 비상 토큰은 Break-glass 전용'],
              ['프롬프트 보관', 'METADATA (기본) / FULL_ENCRYPTED (선택)', 'DATA_POLICY_BLOCKED 차단 요청 본문은 저장하지 않음'],
              ['RUNTIME_CONNECT_TIMEOUT_MS', '3,000 ms', 'Runtime 서버 TCP 연결 시작 대기'],
              ['RUNTIME_RESPONSE_TIMEOUT_MS', '360,000 ms (6분)', '추론 완료 대기(호출 앱·프록시·Gateway 중 최소값이 실제 제한)'],
              ['HEALTH_CHECK_INITIAL_DELAY_MS / DELAY_MS', '30,000 ms / 30,000 ms', '기동 직후 오탐 방지를 위한 초기 지연 및 검사 주기']
            ]
          }
        ]
      },
      {
        id: 'deploy-profiles',
        title: '배포 프로필 · 운영 보안 체크리스트',
        blocks: [
          {
            type: 'table',
            columns: ['프로필', '대상', 'Gateway / 공유 상태'],
            rows: [
              ['Standalone', '개인·소규모 단일 VM', 'Gateway 1개 · JVM 로컬 상태'],
              ['HA', 'Kubernetes 없는 이중화 구성', 'Gateway 2개 이상 · Redis 공유 · Nginx LB'],
              ['Kubernetes', '대규모 클러스터 운영', 'Pod 2개 이상 · Redis HA · MariaDB HA · Helm']
            ]
          },
          {
            type: 'code',
            language: 'bash',
            title: 'Standalone / HA 빠른 시작 예시 (예시 사설망 IP 사용)',
            code: `# Standalone + 사설망 Runtime 점검
AICONNECT_LM_STUDIO_URL=http://100.64.0.20:1234 ./quickstart_standalone.sh

# 사내 LAN 직접 접속 모드 (신뢰된 사내 대역 전용)
AICONNECT_LAN_IP=192.0.2.10 ./quickstart_standalone.sh --lan

# Helm 차트 검증
helm lint deploy/kubernetes/helm/aiconnect`
          },
          {
            type: 'checklist',
            items: [
              '운영 시크릿(ADMIN_API_TOKEN, API_KEY_PEPPER, GATEWAY_ENCRYPTION_KEY, AUTH_SIGNING_KEY, AUTH_REFRESH_PEPPER)은 32자 이상 난수 사용 및 .env 파일 권한 0600 유지',
              'API_KEY_PEPPER·GATEWAY_ENCRYPTION_KEY는 MariaDB 백업과 함께 별도 암호화 보관(분실 시 기존 키 검증·복호화 불가)',
              'API 컨테이너 비루트(aiconnect) 실행, 외부 HTTPS Origin TLS, Cloudflare-only/사설망 방화벽, /actuator·직접 사용자 생성 차단 상태 유지'
            ]
          }
        ]
      }
    ]
  }
]
