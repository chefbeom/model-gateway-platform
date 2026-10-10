# 프로젝트 외부 AI 전송 금지

프로젝트 생성·수정에서 **외부 AI 전송 금지**를 켜면 해당 프로젝트의 모든 API 키로 들어오는 Gateway Chat Completions 요청이 로컬 전용으로 처리됩니다. 일반 응답과 스트리밍에 동일하게 적용됩니다.

## 동작

- 민감정보 탐지 여부, 보호 모드 OFF/MONITOR, API 키별 완화 설정과 무관하게 외부 Provider Target을 호출 전에 제외합니다.
- 요청한 논리 서비스에 연결된 활성·정상·로드된 로컬 Target만 사용합니다. 요청 Capability, STRICT 호환성, 동시성 제한은 유지됩니다.
- 외부 Target은 로컬 호환성 기준으로도 사용하지 않습니다.
- 로컬 Target 사이의 전환은 기존 Retry/Failover 정책을 따릅니다. 로컬 장애가 발생해도 외부로 우회하지 않습니다.
- 사용 가능한 로컬 Target이 없으면 HTTP 503 `LOCAL_MODEL_UNAVAILABLE`과 요청 ID를 반환합니다. Vision 등 요청 기능 미지원도 대상별 제외 사유에 표시됩니다.
- 기존 민감정보 BLOCK 정책은 그대로 유지되며 HTTP 403 `DATA_POLICY_BLOCKED`를 반환할 수 있습니다.
- 요청 기록에는 실제 보호 모드 ENFORCE, 처리 동작 LOCAL_ONLY, 외부 전송 불허가 남습니다. 실패 진단의 외부 Target에는 `PROJECT_EXTERNAL_AI_BLOCKED`가 표시됩니다.

## 설정 API

프로젝트 생성 `POST /api/admin/projects` 및 수정 `PATCH /api/admin/projects/{id}`의 `externalAiBlocked` boolean 필드를 사용합니다. 기존 클라이언트가 수정 시 필드를 생략하면 현재 값을 보존합니다. 기존 프로젝트는 DB 마이그레이션 V39에서 false로 유지되어 기존 동작이 바뀌지 않습니다.

## 예시

`text-pro`에 외부 GPT, 로컬 Gemma, 로컬 Qwen Target이 연결되어 있다면 보호 프로젝트는 GPT를 제외하고 Gemma/Qwen만 선택합니다. 두 로컬 모델이 모두 다운되면 외부 GPT가 정상이어도 실패합니다. 정상적인 로컬 Vision 모델이 없으면 이미지 요청 역시 거절합니다.

## 경계

로컬은 Runtime Endpoint로 등록된 모델을 뜻합니다. 신뢰할 수 있는 자체 서버만 Runtime으로 등록하세요. 이 설정은 해당 프로젝트의 Gateway 요청에 대한 정책이며, 프로젝트 API 키를 사용하지 않는 관리자 직접 모델 테스트 콘솔이나 외부 Provider의 저장·학습 정책을 통제하지 않습니다. 이미 외부로 전송 중인 요청을 소급 취소하지는 않습니다.
