import type { DocPage } from './types'

export const currentGuides: DocPage[] = [
  {
    id: 'model-playground', group: '관리자 가이드', title: '모델 테스트와 대화 기록', shortTitle: '모델 테스트·대화',
    description: '등록된 모델을 직접 호출하고 이미지·파일·스트리밍·모델별 대화 기록을 확인합니다.',
    audience: '관리자', minutes: 8, icon: '◈',
    keywords: ['chat', 'playground', '모델 테스트', '대화 기록', 'history', '이미지', 'PDF', 'vision', 'thinking', 'fast', 'reasoning'],
    sections: [
      { id: 'test-paths', title: 'API 테스트와 모델 테스트는 다릅니다', blocks: [
        { type: 'table', columns: ['화면', '호출 대상', '확인하는 내용'], rows: [
          ['API 테스트', '프로젝트 키 → Gateway → 논리 서비스', '프로젝트 권한·한도·데이터 보호·Target 선택·Failover'],
          ['모델 테스트 콘솔', '관리자 → 선택한 Runtime / Provider 모델', '해당 모델의 실제 추론·멀티모달·SSE 동작']
        ] },
        { type: 'callout', tone: 'warning', title: '직접 테스트 성공이 서비스 성공을 보장하지 않습니다', text: '모델 테스트는 선택한 실제 모델의 검증용입니다. 프로젝트 정책과 논리 서비스의 전체 경로는 API 테스트에서 별도로 확인하세요. 테스트를 성공시키려고 외부 반출이 금지된 데이터를 보내지 마세요.' },
        { type: 'steps', items: [
          { title: '현재 로드 모델 선택', text: '인프라에서 현재 로드된 모델의 Chat 테스트를 열거나 모델 테스트 콘솔에서 대상을 선택합니다. 로컬 미로드 모델은 먼저 해당 Runtime에서 준비합니다.', action: { label: '모델 테스트 콘솔 열기', destination: 'model-playground' } },
          { title: '짧은 메시지 전송', text: '등록된 Endpoint와 인증 정보를 그대로 사용합니다. Enter는 전송, Shift+Enter는 줄바꿈입니다. 한글 입력 조합 중 Enter는 전송하지 않습니다.' },
          { title: '응답과 요청 상태 확인', text: 'HTTP 상태, 응답시간, 입력·출력 토큰, 요청 ID와 실패 사유를 함께 확인합니다. 모델을 바꾸면 해당 모델의 대화로 전환됩니다.' }
        ] }
      ] },
      { id: 'files-and-streaming', title: '이미지·파일·스트리밍 검증', blocks: [
        { type: 'table', columns: ['입력 / 옵션', '처리 방식', '제한과 확인 사항'], rows: [
          ['이미지', 'VISION 모델에 image_url 입력으로 전달', 'JPEG / PNG / WebP / GIF. 실제 Runtime의 비전 실행 구성도 필요'],
          ['PDF', '서버에서 텍스트를 추출해 프롬프트로 전달', '최대 100페이지. 스캔 PDF의 그림·레이아웃을 그대로 이해하는 기능은 아님'],
          ['TXT / JSON / CSV / MD / XML / LOG', '파일 내용을 텍스트 입력으로 전달', '바이너리 파일과 지원하지 않는 확장자는 차단'],
          ['첨부 용량', '파일별 4MB, 대화 전체 8MB', '한 번에 최대 5개, 대화 전체 최대 20개 참조'],
          ['스트리밍 (SSE)', '생성된 응답 조각을 도착하는 대로 표시', '생성 속도 자체가 빨라지는 옵션이 아님. 서버·프록시 SSE 지원 필요']
        ] },
        { type: 'callout', tone: 'info', title: '일반 응답부터 확인하세요', text: '일반 응답(JSON) 성공 후 스트리밍을 켜서 비교하세요. SSE 요청에 JSON이 돌아오면 화면에 그 차이가 표시됩니다. 이미지 처리 중 모델 프로세스가 종료되면 Runtime 로그·메모리·이미지 인코더 구성도 확인해야 합니다.' }
      ] },
      { id: 'saved-chats', title: '모델별 대화 다시 찾기', blocks: [
        { type: 'steps', items: [
          { title: '저장된 모델 대화 검색', text: '모델 테스트 콘솔의 저장된 모델 대화에서 제목·질문·응답으로 검색하고 대화를 다시 엽니다.' },
          { title: '대상과 첨부 재확인', text: '저장된 대화의 모델·Runtime과 현재 선택 대상을 확인합니다. 파일 원본은 저장하지 않으므로 복원 후 파일 내용이 다시 필요하면 재첨부합니다.' },
          { title: '필요 없는 대화 삭제', text: '대화 삭제는 영구 삭제입니다. 새 대화는 현재 화면을 새 세션으로 시작하며 기존 저장 기록을 지우는 동작과 다릅니다.' }
        ] },
        { type: 'callout', tone: 'warning', title: '대화 본문은 암호화 저장됩니다', text: '파일 원본은 저장하지 않지만 모델이 답변에 인용한 내용은 답변 본문에 남습니다. 대화는 직접 삭제하기 전까지 보관되므로 민감정보 테스트의 보관 기준을 먼저 정하세요. PLAYGROUND 요청 메타데이터는 일반 Gateway 요청·비용 집계와 분리됩니다.' }
      ] },
      { id: 'feature-support', title: '기능 지원 배지 해석', blocks: [
        { type: 'paragraph', text: '모델의 Vision, Thinking, Fast, 추론 수준 지원 표시는 발견된 정보 또는 관리자가 등록한 지원 정보를 기준으로 합니다. 미확인은 미지원과 다릅니다. 지원 정보에서 실제 성공한 최소 요청을 근거로 값을 관리하세요.' },
        { type: 'callout', tone: 'warning', title: '지원 표시와 요청 설정은 별개입니다', text: 'Fast 또는 높은 추론 수준을 선택할 수 있다고 모든 모델이 그 값을 받는 것은 아닙니다. 모델마다 허용 값이 다르므로 실제 테스트와 Provider 응답을 확인하세요. 로컬 추론 기능을 외부 API의 reasoning_effort / service_tier와 같은 방식이라고 가정하지 마세요.' }
      ] }
    ]
  },
  {
    id: 'data-protection', group: '관리자 가이드', title: '데이터 보호 정책', shortTitle: '데이터 보호',
    description: '프로젝트 로컬 전용 경계와 민감정보 탐지 정책을 구분해 외부 전송을 제한합니다.',
    audience: '관리자', minutes: 8, icon: '◆',
    keywords: ['DLP', '데이터 보호', '보안', '민감정보', '개인정보', 'secret', '외부 전송', 'local only', 'redact'],
    sections: [
      { id: 'project-local-only', title: '프로젝트 전체의 외부 AI 전송 금지', blocks: [
        { type: 'paragraph', text: '프로젝트 & API 키에서 생성·수정할 때 외부 AI 전송 금지를 켜면 모든 프로젝트 API 키의 Gateway 요청이 로컬 전용이 됩니다. 민감정보 탐지 여부와 관계없이 외부 Provider를 호출 전에 제외하며, 키·서비스의 보호 모드를 OFF로 바꿔도 제한을 풀 수 없습니다.' },
        { type: 'callout', tone: 'warning', title: '로컬 모델이 없으면 외부로 우회하지 않습니다', text: '요청한 논리 서비스에 정상·로드된 로컬 Target이 필요합니다. Vision 등 요청 기능을 지원하는 로컬 Target이 없으면 503 LOCAL_MODEL_UNAVAILABLE을 반환합니다. 로컬 사이의 전환은 기존 Retry/Failover 정책을 따르며, 외부 GPT가 정상이어도 호출하지 않습니다. 프로젝트 API 키를 사용하지 않는 관리자 직접 모델 테스트는 별도 경로입니다.' },
        { type: 'steps', items: [
          { title: '프로젝트 외부 전송 금지 설정', text: '프로젝트 생성·수정에서 체크하고 저장합니다. 체크 해제는 기존 민감정보 정책·Provider 승인까지 해제하는 동작이 아닙니다.', action: { label: '프로젝트 설정 열기', destination: 'projects' } },
          { title: '로컬 Target과 기능 준비', text: '해당 논리 서비스에 신뢰할 수 있는 자체 Runtime의 로컬 모델을 연결하고 로드 상태와 Capability를 확인합니다.', action: { label: '서비스 Target 확인', destination: 'services' } },
          { title: '프로젝트 키로 검증', text: '일반·스트리밍 요청을 테스트하고 요청 상세의 LOCAL_ONLY 처리 및 외부 Target 제외 사유를 확인합니다.', action: { label: 'API 테스트 열기', destination: 'playground' } }
        ] }
      ] },
      { id: 'policy-scopes', title: '적용 범위와 정책 합성', blocks: [
        { type: 'paragraph', text: '플랫폼 → 조직 → 프로젝트 → 논리 서비스·API 키의 정책을 합쳐 Gateway 요청에 적용합니다. 더 엄격한 제한이 우선하며 하위 범위에서 상위 보호 기준을 완화할 수 없습니다. 프로젝트 전체 보호와 특정 기능·키만 보호하는 구성을 나눌 수 있습니다.' },
        { type: 'steps', items: [
          { title: '설정 범위 선택', text: '데이터 보호에서 조직 기본, 프로젝트, AI 기능(서비스), API 키를 선택합니다. 플랫폼 전역은 플랫폼 관리자 권한이 필요합니다.', action: { label: '데이터 보호 열기', destination: 'data-protection' } },
          { title: '모드·감지 항목·조치 저장', text: '프리셋의 모드와 탐지기를 검토한 뒤 저장합니다. 상위 정책이 켜져 있으면 현재 범위의 OFF만으로 전체 보호가 해제되지는 않습니다.' },
          { title: '프로젝트 키로 실제 경로 검증', text: 'API 테스트에서 비민감·민감 예제 요청을 비교하고 관측성의 보호 결정과 선택된 Target을 확인합니다.', action: { label: 'API 테스트 열기', destination: 'playground' } }
        ] }
      ] },
      { id: 'modes-and-actions', title: '모드와 감지 후 처리', blocks: [
        { type: 'table', columns: ['설정', '동작', '주의'], rows: [
          ['OFF', '해당 범위의 보호 기능 해제', '상위 범위의 제한은 유지'],
          ['MONITOR', '탐지 결과를 기록하는 관찰 모드', '차단을 기대하지 말고 외부 전송 가능성을 검토'],
          ['ENFORCE', '민감정보 감지 시 설정된 보호 조치 적용', '프로젝트 외부 전송 금지는 감지 여부와 무관하게 적용'],
          ['ALLOW', '탐지 시에도 허용', '기존 프로젝트·Provider 승인 조건은 별도'],
          ['LOCAL_ONLY', '민감정보 감지 시 외부 후보를 제외', '모든 요청을 로컬로 제한하려면 프로젝트 외부 AI 전송 금지 사용'],
          ['BLOCK', '요청 차단', '실패 진단에서 보호 결정 확인'],
          ['REDACT', '현재 구현에서는 안전하게 로컬 전용으로 처리', '외부로 완전히 마스킹된 본문을 보내는 기능으로 해석하면 안 됨']
        ] },
        { type: 'paragraph', text: 'RELAXED는 제한 없이 시작하는 프리셋, BALANCED는 탐지·기록 중심, STRICT는 로컬 전용 중심입니다. CUSTOM은 직접 조정한 정책입니다. 프리셋 이름보다 실제 모드·조치·탐지 항목과 합성된 정책을 확인하세요.' }
      ] },
      { id: 'detection-and-limits', title: '탐지 범위와 보안 한계', blocks: [
        { type: 'checklist', items: [
          'Secret / API 키, 개인정보, 금융정보, 기밀 키워드, 미디어 입력 탐지 항목을 목적에 맞게 선택',
          '사용자 정의 정규식은 JSON 배열로 입력하고 검증 오류 확인',
          '민감정보 감지 시 외부 자동 Failover 허용 여부와 로컬 후보를 함께 확인',
          '탐지 원문이 아닌 분류·보호 결정 메타데이터로 진단',
          'Provider의 데이터 보관·학습 정책은 계약과 공급자 설정에서 별도 확인'
        ] },
        { type: 'callout', tone: 'warning', title: '완전한 유출 방지를 보장하는 기능은 아닙니다', text: '패턴 탐지는 모든 기밀이나 문맥을 알아낼 수 없습니다. 직접 모델 테스트와 관리자가 수행하는 별도 외부 호출까지 프로젝트 Gateway 정책으로 보호된다고 가정하지 마세요. 원문 보관 정책과 외부 반출 승인도 함께 운영해야 합니다.' }
      ] }
    ]
  },
  {
    id: 'request-diagnostics', group: '운영과 참조', title: '요청 실패 원인과 권장 조치', shortTitle: '요청 실패 진단',
    description: '최종 결과와 개별 Attempt를 구분하고 요청 조건·설정·실패 원인·권장 조치를 확인합니다.',
    audience: '공통', minutes: 7, icon: '!',
    keywords: ['오류', 'error', 'temperature', '컨텍스트', 'CONTEXT_LENGTH_EXCEEDED', 'REQUEST_FORMAT_UNSUPPORTED', 'request id', 'failover', '키', 'reasoning', 'fast'],
    sections: [
      { id: 'trace-a-request', title: '한 요청을 끝까지 추적하기', blocks: [
        { type: 'steps', items: [
          { title: '요청 ID 확보', text: '응답의 X-Request-Id 또는 테스트 결과의 요청 ID를 기록합니다. 비밀키와 프롬프트 원문을 함께 공유하지 마세요.' },
          { title: '요청 상세 열기', text: '관측성 또는 사용량에서 요청을 열어 최종 상태, 논리 서비스, 실제 배포, Provider / Runtime을 확인합니다.', action: { label: '관측성 열기', destination: 'observability' } },
          { title: '실행 시도 이력 읽기', text: '각 Attempt의 순서, HTTP 상태, 소요 시간, 오류 코드와 사유를 확인합니다. 첫 모델이 실패해도 다음 모델이 성공하면 요청의 최종 결과는 성공일 수 있습니다.' },
          { title: '원인과 권장 조치 비교', text: '추정 입력 토큰·요청 출력 한도·필요 Capability·선택 후보·보호 정책과 추천 설정을 함께 확인합니다. 진단은 설정 변경 제안이며 자동으로 전부 고치는 기능은 아닙니다.' }
        ] }
      ] },
      { id: 'common-errors', title: '자주 만나는 실패와 확인 위치', blocks: [
        { type: 'table', columns: ['실패', '확인할 정보', '권장 조치'], rows: [
          ['키·프로젝트 권한', '키 상태, 조직·프로젝트, 허용 서비스', '키 만료·폐기·프로젝트 중지·서비스 권한 점검'],
          ['MODEL_UNAVAILABLE', 'Endpoint / 모델 상태, 활성 Target, 기능·동시성 조건', 'Runtime 연결·모델 로드·Target과 Capability 확인'],
          ['CONTEXT_LENGTH_EXCEEDED', '추정 입력, 출력 한도, 대상 Context Length', '대화·파일·출력 한도를 줄이거나 더 큰 컨텍스트 모델 연결'],
          ['입력 / 출력 제한', '모델이 허용하는 한도와 실제 Provider 오류', 'max_completion_tokens 등 요청 한도와 모델 메타데이터 정정'],
          ['REQUEST_FORMAT_UNSUPPORTED', 'temperature, 토큰 필드, response_format 등 Provider 원문 오류', '지원하지 않는 옵션을 제거하거나 서비스·모델 기본값 조정'],
          ['Timeout / Runtime 장애', 'Attempt 지연, HTTP 상태, 응답 시작 여부', '클라이언트·프록시·Gateway 제한과 Runtime 메모리·서버 로그 점검'],
          ['데이터 보호 / 외부 승인', '보호 결정, 로컬 후보, 프로젝트 Provider 승인', '정책 의도를 유지하면서 승인·로컬 Target을 올바르게 구성']
        ] },
        { type: 'callout', tone: 'info', title: '추정 토큰과 실제 토큰은 다릅니다', text: '사전 점검의 입력 토큰은 추정값입니다. 최종 사용량은 모델이 반환한 usage를 기준으로 보며, 값이 없으면 0으로 단정하지 않습니다.' }
      ] },
      { id: 'fallback-and-modes', title: 'Failover와 실제 적용 옵션 확인', blocks: [
        { type: 'paragraph', text: '다음 Target으로 전환할지는 오류 유형, Retry / Failover 정책, 호환성, 필요한 기능, 외부 승인·데이터 보호 조건에 따라 결정됩니다. 모든 실패를 무조건 다른 모델로 보내지 않습니다. 스트리밍 응답이 이미 전달된 뒤에는 안전한 중간 전환이 제한됩니다.' },
        { type: 'paragraph', text: '외부 모델 요청에서는 요청된 추론 수준·서비스 티어와 적용·응답 정보, 캐시 입력·추론 토큰 및 비용 산정 근거를 확인하세요. Fast 설정만 켰다고 실제 Priority 처리나 해당 요금이 확인된 것은 아닙니다. 누락된 Fast 단가를 일반 단가로 임의 표시하지 않습니다.' },
        { type: 'callout', tone: 'warning', title: '오류 진단과 원문 열람을 구분하세요', text: '메타데이터 진단은 원문 보관을 켜지 않아도 사용할 수 있습니다. METADATA 정책에서는 요청·응답 원문을 보여주지 않습니다. 모델 테스트의 저장된 대화는 별도의 암호화 대화 기록이며 일반 API 보관 정책과 구분합니다.' }
      ] }
    ]
  }
]
