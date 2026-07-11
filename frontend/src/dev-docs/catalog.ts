import type { DocPage } from './types'

const curlExample = `curl "https://ai.company.example/v1/chat/completions" \\
  -H "Authorization: Bearer $AICONNECT_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "text-pro",
    "messages": [
      {"role": "user", "content": "회의 내용을 세 문장으로 요약해 주세요."}
    ],
    "temperature": 0.2,
    "stream": false
  }'`

const pythonExample = `from openai import OpenAI
import os

client = OpenAI(
    api_key=os.environ["AICONNECT_API_KEY"],
    base_url="https://ai.company.example/v1",
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
  baseURL: 'https://ai.company.example/v1'
})

const response = await client.chat.completions.create({
  model: 'text-pro',
  messages: [
    { role: 'user', content: '회의 내용을 세 문장으로 요약해 주세요.' }
  ]
})

console.log(response.choices[0].message.content)`

export const devDocs: DocPage[] = [
  {
    id: 'overview', group: '시작하기', title: 'AICONNECT 시작하기', shortTitle: '개요',
    description: '여러 LM Studio 서버를 하나의 안전한 OpenAI 호환 API로 제공하는 방식을 이해합니다.',
    audience: '공통', minutes: 4, icon: '◎', keywords: ['개요', 'gateway', 'runtime', 'tailscale', '구조'],
    sections: [
      {
        id: 'what-is-aiconnect', title: 'AICONNECT가 하는 일',
        blocks: [
          { type: 'paragraph', text: 'AICONNECT는 GPU나 모델을 직접 실행하는 프로그램이 아닙니다. 이미 준비된 LM Studio Runtime을 등록하고, 인증·권한·논리 모델·라우팅·사용량·장애 대응을 중앙에서 관리하는 Control Plane 겸 API Gateway입니다.' },
          { type: 'cards', items: [
            { label: 'CONTROL', title: '관리', text: '조직, 팀, 사용자, 프로젝트, API 키, Runtime과 논리 서비스를 구성합니다.' },
            { label: 'GATEWAY', title: '중계', text: '사용자 요청과 모델 응답을 중계하고 실제 모델 ID를 외부에서 숨깁니다.' },
            { label: 'OBSERVE', title: '관측', text: '요청량, 토큰, 비용, 오류, 실제 배포와 Failover 이력을 기록합니다.' }
          ] }
        ]
      },
      {
        id: 'request-path', title: '요청 데이터가 이동하는 경로',
        description: '사용자는 GPU 서버가 아니라 Gateway에만 접속합니다.',
        blocks: [
          { type: 'flow', items: [
            { label: '1', title: '사용자 애플리케이션', text: 'Gateway Base URL, API 키, 논리 모델명으로 요청' },
            { label: '2', title: 'AICONNECT Gateway', text: '인증·권한·한도 확인 후 사용할 배포 선택' },
            { label: '3', title: 'Tailscale 사설망', text: 'Gateway와 GPU 서버 사이의 암호화된 연결' },
            { label: '4', title: 'LM Studio Runtime', text: '실제 모델 추론 후 결과를 Gateway로 반환' }
          ] },
          { type: 'callout', tone: 'info', title: '프롬프트가 Gateway를 통과한다는 의미', text: '프롬프트와 결과는 중계를 위해 Gateway 메모리를 통과합니다. 원문을 DB에 저장하는지는 프로젝트 보관 정책(METADATA 또는 암호화 원문)에 따라 별도로 결정됩니다.' }
        ]
      },
      {
        id: 'terms', title: '먼저 알아둘 네 가지 용어',
        blocks: [
          { type: 'table', columns: ['용어', '의미', '예시'], rows: [
            ['Runtime Endpoint', 'Gateway가 접근하는 LM Studio API 주소', 'http://gpu-node.tailnet:1234'],
            ['Model Deployment', '특정 Runtime에서 제공되는 실제 모델', 'google/gemma-4-e4b'],
            ['논리 서비스', '사용자에게 공개하는 안정적인 model 이름', 'text-pro'],
            ['프로젝트 API 키', '프로젝트 권한과 사용량을 식별하는 비밀 키', 'sk_llmg_…']
          ] }
        ]
      }
    ]
  },
  {
    id: 'quickstart', group: '시작하기', title: '5분 빠른 시작', shortTitle: '빠른 시작',
    description: '일반 사용자가 로그인부터 첫 Chat Completion 요청까지 진행합니다.',
    audience: '사용자', minutes: 5, icon: '→', keywords: ['처음', '로그인', '프로젝트', 'api key', '첫 요청'],
    sections: [
      {
        id: 'before-start', title: '시작 전에 받을 정보',
        blocks: [
          { type: 'checklist', items: ['AICONNECT 로그인 계정', '사용할 프로젝트 또는 프로젝트 생성 권한', '허용된 논리 모델명', '호출 환경에 맞는 Gateway Base URL'] },
          { type: 'callout', tone: 'warning', title: 'GPU 서버 주소를 사용하지 마세요', text: '100.x.x.x:1234 같은 LM Studio 주소는 관리자가 Runtime으로 등록하는 내부 주소입니다. 사용자 애플리케이션은 AICONNECT Gateway 주소의 /v1 경로를 사용합니다.' }
        ]
      },
      {
        id: 'five-steps', title: '첫 요청까지 다섯 단계',
        blocks: [
          { type: 'steps', items: [
            { title: '로그인하고 Workspace 확인', text: '전달받은 계정으로 로그인합니다. 현재 선택된 조직과 자신의 역할을 확인합니다.' },
            { title: '내 API에서 프로젝트 선택', text: '기존 프로젝트를 선택하거나 권한이 있으면 목적별 프로젝트를 만듭니다.', action: { label: '내 API 열기', destination: 'portal' } },
            { title: 'API 키 발급', text: '키 이름과 만료일을 정합니다. 키 원문은 생성 직후 한 번만 표시되므로 비밀 저장소에 즉시 보관합니다.' },
            { title: 'Base URL과 논리 모델 복사', text: '발급 완료 화면의 내부/외부 Base URL 중 호출 위치에 맞는 주소와 model 값을 복사합니다.' },
            { title: '비스트리밍 요청으로 확인', text: '짧은 메시지와 stream: false로 먼저 호출한 뒤 API 사용량에서 성공 기록과 토큰 수를 확인합니다.', action: { label: 'API 사용량 열기', destination: 'usage' } }
          ] }
        ]
      },
      {
        id: 'minimum-request', title: '가장 작은 연결 테스트',
        blocks: [
          { type: 'code', language: 'bash', title: 'cURL', code: curlExample },
          { type: 'callout', tone: 'success', title: '성공 기준', text: 'HTTP 200 응답, choices[0].message.content, usage 토큰 정보가 반환되고 사용량 화면에 요청이 기록되면 연결이 완료된 것입니다.' }
        ]
      }
    ]
  },
  {
    id: 'concepts', group: '시작하기', title: '핵심 개념과 권한', shortTitle: '핵심 개념',
    description: '조직부터 실제 모델까지의 관계와 역할별 책임 범위를 정리합니다.',
    audience: '공통', minutes: 6, icon: '◇', keywords: ['권한', 'organization', 'team', 'project owner', 'developer', 'logical service'],
    sections: [
      {
        id: 'resource-tree', title: '리소스 관계',
        blocks: [
          { type: 'flow', items: [
            { label: 'ORG', title: '조직', text: '회사 또는 개인 운영 경계' },
            { label: 'TEAM', title: '팀·부서', text: '구성원과 프로젝트 소유 범위' },
            { label: 'PROJECT', title: '프로젝트', text: 'API 키·권한·정책·사용량 단위' },
            { label: 'SERVICE', title: '논리 서비스', text: '사용자 model 값과 실제 배포 연결' }
          ] }
        ]
      },
      {
        id: 'role-model', title: '권한 계층',
        blocks: [
          { type: 'table', columns: ['역할', '할 수 있는 일', '제한'], rows: [
            ['플랫폼 관리자', '전체 조직·팀·사용자·인프라·서비스·프로젝트 관리', '감사 이력이 있는 리소스는 정책에 따라 영구 삭제 제한'],
            ['조직 관리자', '자신의 조직 안에서 팀·사용자·Runtime·서비스·프로젝트 운영', '다른 조직 데이터 접근 불가'],
            ['팀 관리자 / 프로젝트 소유자', '담당 팀과 프로젝트, API 키와 서비스 권한 관리', '조직 전체 인프라 정책 변경 불가'],
            ['일반 개발자', '할당된 프로젝트에서 API 키 사용과 본인 사용량 확인', 'Runtime·라우팅·조직 관리 불가']
          ] },
          { type: 'callout', tone: 'info', title: '관리자도 API를 사용할 수 있습니다', text: '관리자는 운영 권한과 별개로 프로젝트를 만들고 API 키를 발급받아 일반 사용자와 동일한 /v1 API를 호출할 수 있습니다.' }
        ]
      },
      {
        id: 'logical-model', title: '논리 모델을 쓰는 이유',
        blocks: [
          { type: 'paragraph', text: '사용자는 text-pro 같은 논리 모델만 호출합니다. 관리자는 이 논리 서비스에 여러 Model Deployment를 우선순위로 연결합니다. GPU 교체, 모델 파일 변경, 장애 전환이 있어도 사용자 Base URL·API 키·model 값은 유지됩니다.' },
          { type: 'callout', tone: 'warning', title: '논리 서비스와 프로젝트는 다른 리소스입니다', text: '프로젝트가 서비스를 사용할 수 있는 관계를 서비스 권한이라고 합니다. 서비스를 그만 사용할 때 프로젝트를 삭제할 필요 없이 프로젝트의 서비스 권한만 해제합니다.' }
        ]
      }
    ]
  },
  {
    id: 'user-portal', group: '사용자 가이드', title: '프로젝트와 API 키', shortTitle: '프로젝트·API 키',
    description: '프로젝트를 선택하고 키를 발급·폐기·기록 삭제하는 전체 절차입니다.',
    audience: '사용자', minutes: 8, icon: '⌘', keywords: ['내 API', '키 발급', '키 폐기', '기록 삭제', 'base url', 'model'],
    sections: [
      {
        id: 'project-unit', title: '프로젝트는 사용량과 권한의 단위',
        blocks: [
          { type: 'cards', items: [
            { label: '권한', title: '사용 가능한 모델', text: '프로젝트에 부여된 논리 서비스만 /v1/models와 요청에서 사용할 수 있습니다.' },
            { label: '정책', title: '요청·토큰 한도', text: 'RPM, 월 토큰, 오류율 경보와 원문 보관 정책을 프로젝트별로 설정합니다.' },
            { label: '추적', title: 'API 키와 사용량', text: '개발·운영·서비스별 키를 나누면 어떤 시스템의 요청인지 빠르게 찾을 수 있습니다.' }
          ] }
        ]
      },
      {
        id: 'issue-key', title: 'API 키 발급과 보관',
        blocks: [
          { type: 'steps', items: [
            { title: '프로젝트 선택', text: '내 API 화면 상단에서 작업할 프로젝트를 고릅니다.', action: { label: '내 API 열기', destination: 'portal' } },
            { title: '사용 가능한 모델 확인', text: '모델 카드가 비어 있다면 관리자 또는 프로젝트 소유자에게 서비스 권한을 요청합니다.' },
            { title: 'API 키 발급 선택', text: '사람 이름보다 production-backend, local-test처럼 호출 주체와 용도가 드러나는 이름을 사용합니다.' },
            { title: '원문과 연결 정보 저장', text: 'API 키 원문, 호출 위치에 맞는 Base URL, 논리 모델명을 함께 저장합니다.' }
          ] },
          { type: 'callout', tone: 'danger', title: '키 원문은 다시 볼 수 없습니다', text: '키 원문은 생성 직후 한 번만 표시됩니다. Git, 프론트엔드 번들, 문서, 메신저에 넣지 말고 서버 환경 변수나 전용 Secret Manager에 보관하세요.' }
        ]
      },
      {
        id: 'revoke-delete', title: '유출 또는 미사용 키 정리',
        blocks: [
          { type: 'table', columns: ['작업', '언제 사용', '결과'], rows: [
            ['폐기', '키가 유출됐거나 더 이상 호출하지 않을 때', '즉시 인증이 차단되며 복구할 수 없음'],
            ['폐기 기록 영구 삭제', '목록에서 폐기된 키 레코드까지 정리할 때', '키 메타데이터 연결 제거. 요청·감사 이력은 보존'],
            ['새 키 발급', '폐기한 서비스를 다시 연결할 때', '새 원문을 환경 변수에 교체하고 애플리케이션 재시작']
          ] },
          { type: 'callout', tone: 'warning', title: '먼저 폐기하고 교체하세요', text: '키 삭제는 보안 사고의 첫 조치가 아닙니다. 활성 키를 즉시 폐기하고 새 키로 교체한 뒤, 필요할 때 폐기 기록을 정리합니다.' }
        ]
      }
    ]
  },
  {
    id: 'api', group: '사용자 가이드', title: 'OpenAI 호환 API 연결', shortTitle: 'API 연결',
    description: 'Base URL 선택, 모델 조회, Chat Completions와 스트리밍 호출 예시를 제공합니다.',
    audience: '사용자', minutes: 10, icon: '</>', keywords: ['openai', 'python', 'typescript', 'curl', 'chat completions', 'streaming', 'baseurl'],
    sections: [
      {
        id: 'base-url', title: 'Base URL은 호출 위치에 따라 선택',
        blocks: [
          { type: 'table', columns: ['호출 위치', '사용할 주소', '예시'], rows: [
            ['Gateway와 같은 컴퓨터', '로컬 Base URL', 'http://localhost/v1'],
            ['회사 내부망·VPN·Tailnet', '내부 Gateway Base URL', 'http://100.x.x.x/v1 또는 사내 DNS'],
            ['인터넷의 운영 서버', '외부 HTTPS Base URL', 'https://ai.company.example/v1']
          ] },
          { type: 'callout', tone: 'danger', title: 'LM Studio 주소와 혼동하지 마세요', text: 'http://100.x.x.x:1234는 GPU 서버의 Runtime Endpoint일 수 있습니다. 사용자 애플리케이션에는 AICONNECT Gateway 주소를 넣어야 인증·라우팅·사용량·Failover가 동작합니다.' }
        ]
      },
      {
        id: 'models', title: '사용 가능한 모델 조회',
        blocks: [
          { type: 'code', language: 'bash', title: 'GET /v1/models', code: `curl "https://ai.company.example/v1/models" \\
  -H "Authorization: Bearer $AICONNECT_API_KEY"` },
          { type: 'paragraph', text: '응답의 data[].id 값만 chat/completions의 model로 사용합니다. 여기에는 실제 LM Studio 모델 ID가 아니라 프로젝트에 허용된 논리 서비스 키가 표시됩니다.' }
        ]
      },
      {
        id: 'examples', title: 'Chat Completions 예제',
        blocks: [
          { type: 'code', language: 'bash', title: 'cURL', code: curlExample },
          { type: 'code', language: 'python', title: 'Python', code: pythonExample },
          { type: 'code', language: 'typescript', title: 'TypeScript', code: typescriptExample }
        ]
      },
      {
        id: 'streaming', title: '스트리밍 요청',
        blocks: [
          { type: 'paragraph', text: 'stream: true를 사용하면 Gateway가 LM Studio의 SSE 조각을 버퍼링하지 않고 전달합니다. 클라이언트가 연결을 끊으면 Runtime 요청도 취소됩니다.' },
          { type: 'callout', tone: 'warning', title: '스트리밍 중 장애 전환의 한계', text: '첫 토큰을 사용자에게 전달하기 전에는 다른 배포로 전환할 수 있습니다. 일부 토큰이 이미 전달된 뒤에는 중복·깨진 JSON을 막기 위해 현재 스트림을 오류로 종료하고 다음 요청부터 대체 배포를 사용합니다.' }
        ]
      },
      {
        id: 'status-codes', title: '주요 오류 코드',
        blocks: [
          { type: 'table', columns: ['HTTP', '의미', '확인할 것'], rows: [
            ['401', 'API 키가 잘못됐거나 폐기·만료됨', 'Authorization Bearer 값과 키 상태'],
            ['403', '프로젝트에 모델 권한이 없음', '서비스 권한과 /v1/models 결과'],
            ['429', '요청·토큰·동시성 한도 초과', '프로젝트 정책과 Retry-After'],
            ['503', '사용 가능한 Target이 없음', 'Runtime, 모델 로드, Target 활성 상태'],
            ['504', 'Runtime 응답 제한 시간 초과', '출력 길이, 모델 성능, Gateway timeout']
          ] }
        ]
      }
    ]
  },

  {
    id: 'admin-first-run', group: '관리자 가이드', title: '관리자 최초 구축', shortTitle: '최초 구축',
    description: '빈 AICONNECT에서 조직·팀·사용자·Runtime·서비스·프로젝트를 순서대로 구성합니다.',
    audience: '관리자', minutes: 12, icon: '◫', keywords: ['관리자', '조직', '팀', '사용자', '초기 설정'],
    sections: [
      {
        id: 'build-order', title: '권장 구축 순서',
        blocks: [
          { type: 'steps', items: [
            { title: '조직과 팀·부서 생성', text: '회사를 조직으로, 비용과 책임을 나눌 부서를 팀으로 만듭니다.', action: { label: '팀과 부서 열기', destination: 'teams' } },
            { title: '사용자와 역할 배정', text: '조직 관리자, 팀 관리자, 프로젝트 소유자, 개발자를 최소 권한으로 배정합니다.' },
            { title: 'LM Studio Runtime 등록', text: 'Tailscale에서 접근 가능한 Endpoint를 등록하고 모델을 동기화합니다.', action: { label: '인프라 열기', destination: 'infrastructure' } },
            { title: '논리 서비스와 Target 구성', text: '외부에 공개할 model 키와 실제 배포의 우선순위를 연결합니다.', action: { label: 'LLM 서비스 열기', destination: 'services' } },
            { title: '프로젝트와 서비스 권한 설정', text: '팀 소유 프로젝트를 만들고 필요한 논리 서비스만 허용합니다.', action: { label: '프로젝트 열기', destination: 'projects' } },
            { title: '테스트 계정으로 검증', text: '일반 사용자 화면에서 키 발급, /v1/models, 짧은 Chat Completion, 사용량 기록을 확인합니다.' }
          ] }
        ]
      },
      {
        id: 'roles', title: '계정과 역할 운영',
        blocks: [
          { type: 'table', columns: ['역할', '주요 책임', '권장 원칙'], rows: [
            ['플랫폼·조직 관리자', '조직 전체 인프라와 권한 관리', '개인별 계정 사용, 공용 관리자 금지'],
            ['팀 관리자·프로젝트 소유자', '담당 팀과 프로젝트 운영', '필요한 서비스만 권한 부여'],
            ['개발자', 'API 키 사용과 자신의 사용량 확인', 'Runtime·라우팅 권한 부여 금지']
          ] },
          { type: 'callout', tone: 'danger', title: '사용자·팀 제거 전 영향 확인', text: '확인 모달에 표시되는 API 키 이름·식별자·등록 프로젝트를 검토하세요. 요청·사용량·감사 이력은 보존될 수 있습니다.' }
        ]
      },
      {
        id: 'project-lifecycle', title: '프로젝트 수명주기',
        blocks: [
          { type: 'table', columns: ['작업', '영향', '사용 시점'], rows: [
            ['수정', '이름·소유 팀 변경, 기존 키와 이력 유지', '조직 개편'],
            ['중지', '새 요청을 PROJECT_SUSPENDED로 즉시 차단', '오용·비용 급증·보안 사고'],
            ['재개', '새 요청 허용. 폐기된 키는 복구되지 않음', '원인 해결 후'],
            ['영구 삭제', '연결 설정 정리', '요청 이력이 없는 프로젝트만']
          ] }
        ]
      }
    ]
  },
  {
    id: 'runtime', group: '관리자 가이드', title: 'LM Studio Runtime과 모델', shortTitle: 'Runtime·모델',
    description: 'Tailscale Endpoint 등록, 모델 동기화와 로드·언로드·다운로드를 운영합니다.',
    audience: '관리자', minutes: 14, icon: '◈', keywords: ['lm studio', 'tailscale', 'endpoint', 'load', 'unload', 'download'],
    sections: [
      {
        id: 'prepare', title: 'GPU 서버 준비',
        blocks: [
          { type: 'checklist', items: ['GPU 서버와 AICONNECT 서버를 같은 Tailnet에 연결', 'LM Studio Developer Server 실행', 'Tailscale 인터페이스에서 접근 가능한 Listen 설정', '가능하면 LM Studio API Token 인증 활성화', 'Tailnet ACL에서 Gateway만 TCP 1234 접근 허용', '공개 NAT·포트 포워딩 사용 금지'] },
          { type: 'callout', tone: 'info', title: 'GPU는 선택적 메타데이터입니다', text: '라우팅은 RTX 5090, H100 같은 제품명이 아니라 Endpoint 상태, 모델 Capability, 우선순위와 동시성으로 결정됩니다.' }
        ]
      },
      {
        id: 'register', title: 'Endpoint 등록과 동기화',
        blocks: [
          { type: 'steps', items: [
            { title: 'Runtime 연결', text: '노드 이름, LM Studio Base URL과 선택적 API Token을 입력합니다.', action: { label: '인프라 열기', destination: 'infrastructure' } },
            { title: '연결 확인', text: 'Gateway가 LM Studio native /api/v1/models에 접근할 수 있는지 검사합니다.' },
            { title: '모델 동기화', text: '모델과 로드 인스턴스를 Model Deployment로 반영합니다. 동기화 자체는 다운로드나 로드를 실행하지 않습니다.' },
            { title: 'HEALTHY·LOADED 확인', text: 'Endpoint와 사용할 모델 상태를 확인한 뒤 논리 서비스 Target으로 연결합니다.' }
          ] }
        ]
      },
      {
        id: 'operations', title: '모델 작업과 로드 설정',
        blocks: [
          { type: 'table', columns: ['작업·설정', '의미', '주의'], rows: [
            ['다운로드', '모델 식별자와 양자화를 LM Studio에 요청', '완료 후 모델 동기화'],
            ['Context Length', '요청 문맥 크기. 클수록 메모리 사용 증가', '업무에 필요한 최소값부터'],
            ['Flash Attention', '지원 모델의 Attention 최적화', '사전 점검 후 활성화'],
            ['Evaluation Batch Size', '프롬프트 평가 배치', '비우면 LM Studio 기본값'],
            ['Offload KV Cache to GPU', 'KV Cache를 GPU에 배치', 'VRAM 여유 확인'],
            ['언로드', '로드된 instance_id를 제거', 'model key만 보내면 HTTP 400 가능']
          ] },
          { type: 'callout', tone: 'warning', title: 'Node Agent 없는 현재 범위', text: 'GPU Offload layer, CPU Thread Pool, Unified KV Cache, K/V Cache 양자화처럼 native REST에서 적용을 보장하기 어려운 값은 GPU 서버의 LM Studio에서 직접 관리합니다.' }
        ]
      },
      {
        id: 'official', title: 'LM Studio 공식 문서',
        blocks: [
          { type: 'links', items: [
            { label: 'Developer Docs', href: 'https://lmstudio.ai/docs/developer', description: 'LM Studio 개발자 문서 전체' },
            { label: 'List models', href: 'https://lmstudio.ai/docs/developer/rest/list', description: '모델과 로드 인스턴스 조회' },
            { label: 'Load a model', href: 'https://lmstudio.ai/docs/developer/rest/load', description: '모델 로드 요청과 설정' },
            { label: 'Download a model', href: 'https://lmstudio.ai/docs/developer/rest/download', description: '모델 다운로드 요청' }
          ] }
        ]
      }
    ]
  },
  {
    id: 'routing', group: '관리자 가이드', title: '논리 서비스와 라우팅', shortTitle: '서비스·라우팅',
    description: '사용자 model과 실제 Deployment를 분리하고 우선순위·Failover를 구성합니다.',
    audience: '관리자', minutes: 12, icon: '⇄', keywords: ['service', 'target', 'priority', 'weight', 'failover', 'retry'],
    sections: [
      {
        id: 'create', title: '서비스 구성 순서',
        blocks: [
          { type: 'steps', items: [
            { title: 'Service Key 결정', text: 'text-pro처럼 사용자 API의 model에 넣을 안정적인 목적 중심 키를 정합니다.' },
            { title: 'Capability와 가격 설정', text: '필수 기능, Failover·Retry 정책, 입력·출력 100만 토큰 단가를 설정합니다.' },
            { title: 'Target 추가', text: '동기화된 실제 배포를 Priority 순서로 연결합니다.', action: { label: 'LLM 서비스 열기', destination: 'services' } },
            { title: '프로젝트 권한 부여', text: '사용할 프로젝트에 논리 서비스 접근 권한을 부여합니다.', action: { label: '프로젝트 열기', destination: 'projects' } }
          ] }
        ]
      },
      {
        id: 'fields', title: 'Target 필드',
        blocks: [
          { type: 'table', columns: ['필드', '의미', '예시'], rows: [
            ['Priority', '낮은 숫자를 먼저 선택', 'Primary 1, Secondary 2'],
            ['Weight', '같은 Priority 후보의 상대 분배 비율', '70 / 30'],
            ['동시성 재정의', '서비스별 최대 동시 요청', '고비용 서비스 1'],
            ['Degraded', '품질 저하를 허용할 때만 마지막 후보', '고성능 → 경량 모델'],
            ['활성화', '꺼진 Target은 즉시 후보 제외', '점검 전 비활성화']
          ] },
          { type: 'callout', tone: 'warning', title: 'Weight는 속도 설정이 아닙니다', text: '100과 100은 동일 분배입니다. 매우 큰 숫자를 넣어도 처리 속도나 Timeout은 늘어나지 않습니다.' }
        ]
      },
      {
        id: 'failover', title: 'Failover와 삭제 원칙',
        blocks: [
          { type: 'flow', items: [
            { label: 'P1', title: 'Primary 검사', text: 'HEALTHY·READY·기능·동시성 확인' },
            { label: 'FAIL', title: 'Attempt 기록', text: '오류와 응답 시작 여부 저장' },
            { label: 'P2', title: 'Secondary 호출', text: '정책에 맞는 다음 Target 선택' },
            { label: 'OK', title: '최종 기록', text: '실제 배포·토큰·Failover 저장' }
          ] },
          { type: 'table', columns: ['삭제 차단 원인', '해결'], rows: [
            ['프로젝트 서비스 권한', '프로젝트 & API 키 → 서비스 권한에서 관계만 해제'],
            ['Service Target', '서비스에서 Target 제거'],
            ['요청 이력', '감사 보존 때문에 삭제 불가. 서비스 비활성화'],
            ['연결·이력 없음', '삭제 검사 후 영구 삭제 가능']
          ] },
          { type: 'callout', tone: 'info', title: '프로젝트를 삭제하지 마세요', text: '서비스를 정리하려면 프로젝트 자체가 아니라 프로젝트의 서비스 권한 관계를 해제합니다.' }
        ]
      }
    ]
  },
  {
    id: 'operations', group: '관리자 가이드', title: '사용량·관측·알림', shortTitle: '관측·알림',
    description: '조직 전체 통계에서 이상을 찾고 요청·장애·Failover를 추적합니다.',
    audience: '관리자', minutes: 10, icon: '◉', keywords: ['usage', 'observability', 'incident', 'discord', 'telegram', 'alert'],
    sections: [
      {
        id: 'usage', title: '관리자 통계와 요청 추적',
        blocks: [
          { type: 'paragraph', text: '사용량 화면은 API 키 원문을 입력받지 않습니다. 서버가 로그인 계정의 권한을 확인해 개발자는 직접 발급한 키, 프로젝트 소유자·팀 관리자는 소유 프로젝트의 모든 키, 조직·플랫폼 관리자는 관리 조직의 모든 요청을 자동으로 집계합니다.' },
          { type: 'cards', items: [
            { label: 'TOTAL', title: '전체', text: '요청·토큰·비용·성공률을 확인합니다.' },
            { label: 'PROJECT', title: '팀·프로젝트', text: '어느 조직 단위에서 사용량이 증가했는지 비교합니다.' },
            { label: 'RUNTIME', title: '실제 인프라', text: '처리 배포, 지연, 오류와 Failover를 확인합니다.' }
          ] },
          { type: 'steps', items: [
            { title: '사용량에서 이상 구간 확인', text: '요청 급증, 토큰 비용, 실패율을 프로젝트별로 좁힙니다.', action: { label: '사용량 열기', destination: 'usage' } },
            { title: '관측성에서 Request·Attempt 확인', text: 'Request ID, 실제 배포, 오류, Failover 경로를 확인합니다.', action: { label: '관측성 열기', destination: 'observability' } },
            { title: '필요하면 프로젝트 중지', text: '오용이 계속되면 새 요청을 차단하고 활성 키를 폐기합니다.', action: { label: '프로젝트 열기', destination: 'projects' } }
          ] }
        ]
      },
      {
        id: 'alerts', title: 'Discord·Telegram 알림',
        blocks: [
          { type: 'checklist', items: ['운영·개발 채널 분리', 'Webhook URL·Bot Token 재노출 금지', '알림 재전송 간격으로 반복 폭주 방지', '장애와 복구 메시지를 모두 시험', '프롬프트 원문을 알림 본문에 포함하지 않기'] },
          { type: 'steps', items: [
            { title: '채널 등록', text: 'Discord Webhook 또는 Telegram 정보를 암호화 저장합니다.', action: { label: '알림 채널 열기', destination: 'notifications' } },
            { title: '프로젝트 경보 설정', text: 'RPM, 오류율, 월 토큰 비율과 재전송 간격을 정합니다.' },
            { title: '장애·복구 시험', text: '테스트 Runtime을 Drain해 알림의 전체 수명주기를 검증합니다.' }
          ] }
        ]
      }
    ]
  },
  {
    id: 'security-timeout', group: '운영과 참조', title: '보안·보관·Timeout', shortTitle: '보안·Timeout',
    description: '비밀값 경계, 프롬프트 보관, Gateway Runtime Timeout을 정리합니다.',
    audience: '공통', minutes: 10, icon: '◆', keywords: ['security', 'retention', 'secret', 'timeout', '360000', 'backup'],
    sections: [
      {
        id: 'secrets', title: '비밀값을 구분하세요',
        blocks: [
          { type: 'table', columns: ['비밀값', '사용 주체', '원칙'], rows: [
            ['로그인 비밀번호', '사람이 웹 로그인', '계정별 사용, 해시 저장'],
            ['프로젝트 API 키', '애플리케이션이 Gateway 호출', '원문 1회 표시, Secret Manager 보관'],
            ['LM Studio Token', 'Gateway가 Runtime 호출', '암호화 저장, 사용자 공개 금지']
          ] }
        ]
      },
      {
        id: 'retention', title: '프롬프트 보관',
        blocks: [
          { type: 'table', columns: ['정책', '저장', '용도'], rows: [
            ['METADATA', '모델·토큰·상태·지연·오류', '기본 권장'],
            ['FULL_ENCRYPTED', '메타데이터와 암호화 원문', '명확한 감사 목적과 보관 기간이 있을 때']
          ] },
          { type: 'callout', tone: 'warning', title: '요청 이력은 감사 데이터입니다', text: '키나 프로젝트 레코드를 정리해도 기존 요청·사용량·장애·감사 이력은 보존될 수 있습니다.' }
        ]
      },
      {
        id: 'timeouts', title: 'Gateway Runtime Timeout',
        blocks: [
          { type: 'table', columns: ['환경 변수', '기본값', '의미'], rows: [
            ['RUNTIME_CONNECT_TIMEOUT_MS', '3,000ms', 'LM Studio 연결 시작 대기'],
            ['RUNTIME_RESPONSE_TIMEOUT_MS', '360,000ms', '연결 후 추론 완료 대기']
          ] },
          { type: 'callout', tone: 'info', title: '현재는 Gateway 공통 설정입니다', text: 'docker-compose/.env의 환경 변수로 모든 Runtime에 공통 적용됩니다. Endpoint별 Timeout UI는 아직 제공하지 않습니다.' },
          { type: 'callout', tone: 'warning', title: '가장 짧은 제한이 실제 제한입니다', text: 'Gateway가 360초를 기다려도 호출 앱이나 프록시가 120초에 연결을 끊으면 결과를 받을 수 없습니다. 클라이언트·Nginx·Gateway 제한을 함께 확인하세요.' }
        ]
      },
      {
        id: 'production', title: '운영 전 체크리스트',
        blocks: [
          { type: 'checklist', items: ['외부 Gateway HTTPS 적용', '기본 비밀번호와 운영 Secret 변경', 'LM Studio 공개 포트 차단', 'Tailnet ACL 최소 허용', '.env·API 키·Token Git 커밋 금지', 'MariaDB와 암호화 키를 함께 백업하고 복구 시험'] }
        ]
      }
    ]
  },
  {
    id: 'troubleshooting', group: '운영과 참조', title: '문제 해결', shortTitle: '문제 해결',
    description: '연결 실패, 401·403·503·504, 언로드 HTTP 400을 증상별로 해결합니다.',
    audience: '공통', minutes: 12, icon: '!', keywords: ['error', 'cannot connect', '400', '401', '403', '503', '504', 'unload'],
    sections: [
      {
        id: 'connection', title: 'Cannot connect to OpenAI API',
        blocks: [
          { type: 'steps', items: [
            { title: 'Base URL 확인', text: '호출 앱에는 LM Studio 주소가 아니라 AICONNECT Gateway 주소를 넣고 /v1 중복 여부를 확인합니다.' },
            { title: '호출 위치 확인', text: 'localhost는 같은 컴퓨터에서만 유효합니다. 다른 컴퓨터·컨테이너는 내부 IP·DNS 또는 외부 HTTPS를 사용합니다.' },
            { title: '키와 모델 확인', text: '같은 키로 /v1/models를 호출하고 응답의 논리 모델명을 그대로 사용합니다.' },
            { title: 'Timeout 확인', text: 'LM Studio가 결과를 만들었는데 앱이 실패하면 호출 앱의 제한 시간이 더 짧은지 확인합니다.' },
            { title: 'Request·Attempt 확인', text: 'Gateway 도착 여부와 실제 배포의 HTTP 상태를 확인합니다.', action: { label: '관측성 열기', destination: 'observability' } }
          ] }
        ]
      },
      {
        id: 'statuses', title: '상태 코드별 점검',
        blocks: [
          { type: 'table', columns: ['상태', '원인', '조치'], rows: [
            ['401', '키 오류·폐기·만료', 'Bearer 값과 키 상태 확인'],
            ['403', '서비스 권한 없음', '프로젝트 권한과 /v1/models 확인'],
            ['429', 'RPM·토큰·동시성 한도', '정책 확인 후 지수 백오프'],
            ['503', '사용 가능한 Target 없음', 'Endpoint·모델 로드·Target·Circuit 확인'],
            ['504', '추론 응답 제한 초과', 'max_tokens·모델 성능·전체 Timeout 확인']
          ] }
        ]
      },
      {
        id: 'unload', title: '모델 언로드 HTTP 400',
        blocks: [
          { type: 'paragraph', text: 'LM Studio native unload API는 model key가 아니라 현재 로드된 인스턴스의 instance_id를 요구합니다.' },
          { type: 'steps', items: [
            { title: '모델 동기화', text: '현재 로드 인스턴스 정보를 새로 가져옵니다.' },
            { title: 'Drain', text: '새 요청 유입을 막고 진행 중 요청을 완료합니다.' },
            { title: 'instance_id 확인', text: '동일 모델의 여러 인스턴스가 있으면 정확한 대상을 선택합니다.' },
            { title: '언로드 재실행', text: '최근 모델 작업에서 LM Studio 응답 상세를 확인합니다.' }
          ] }
        ]
      },
      {
        id: 'report', title: '문제 보고에 포함할 정보',
        blocks: [
          { type: 'checklist', items: ['발생 시각과 시간대', 'Request ID와 error.code', '논리 모델명', 'Endpoint와 실제 Deployment 상태', '스트리밍·첫 토큰 여부', 'Attempt 목록', '비밀값을 제거한 클라이언트 설정'] },
          { type: 'callout', tone: 'danger', title: '비밀값 첨부 금지', text: 'API 키 원문, 비밀번호, LM Studio Token, Tailscale Auth Key를 스크린샷이나 로그에 포함하지 마세요.' }
        ]
      }
    ]
  }
]

export const docGroups = ['시작하기', '사용자 가이드', '관리자 가이드', '운영과 참조'] as const

export function findDoc(id: string | null | undefined) {
  return devDocs.find(doc => doc.id === id) ?? devDocs[0]
}

export function searchDocs(query: string) {
  const normalized = query.trim().toLocaleLowerCase('ko-KR')
  if (!normalized) return []
  return devDocs.filter(doc => {
    const sectionText = doc.sections.map(section => section.title + ' ' + (section.description ?? '')).join(' ')
    return (doc.title + ' ' + doc.description + ' ' + doc.keywords.join(' ') + ' ' + sectionText).toLocaleLowerCase('ko-KR').includes(normalized)
  })
}
