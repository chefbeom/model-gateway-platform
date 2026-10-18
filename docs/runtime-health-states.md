# Runtime 연결·모델 준비 상태

Runtime 상태는 프로그램 창의 실행 여부가 아니라 AIConnect에서 접근한 API 응답과 최신 모델 인벤토리로 판단합니다. 프로그램이 켜져 있어도 API 서버가 꺼져 있거나 인증·네트워크에 문제가 있으면 연결 실패입니다.

| 상태 | 의미 | 신규 추론 |
| --- | --- | --- |
| HEALTHY | API 정상, 로드된 모델 확인. 장애 복구 중이라면 Chat 모델의 검증도 완료 | 요청별 모델·기능·동시성 조건을 만족할 때 허용 |
| STANDBY | API 정상, 현재 로드된 모델 없음 | 제외. 모델 로드는 가능 |
| LOADING | API가 모델 로드·다운로드·언로드 진행을 보고함 | 준비가 끝날 때까지 제외 |
| CONNECTED | API 정상이나 실제 모델 로드 여부를 확인할 수 없음 | 로드된 것으로 추정하지 않음 |
| SUSPECT | 연결 확인 실패, 연속 실패 임계값 미만 | 제외 |
| UNHEALTHY | 연속 API 실패 또는 로드된 모델 복구 검증 실패 | 제외 |
| RECOVERING | 연결 복구 후 로드된 모델 검증 중 | 검증 완료 전 제외 |
| DRAINING | 관리자 설정에 따라 신규 요청 중지 | 제외. 인벤토리 확인으로 자동 해제하지 않음 |
| UNKNOWN | 아직 연결 확인 기록 없음 | 제외 |

비활성화된 Endpoint는 화면에서 `DISABLED`가 저장된 상태보다 우선합니다.

## 프로그램별 확인

- LM Studio: `/api/v1/models`의 `loaded_instances`를 사용합니다. 구버전은 `/api/v0/models`의 명시적인 state를 확인합니다. 최후의 `/v1/models` 목록은 JIT 때문에 다운로드 목록일 수 있어 로드 여부가 없으면 CONNECTED로 남깁니다.
- Ollama: `/api/tags`는 다운로드 목록, `/api/ps`는 메모리 로드 여부의 기준입니다. `/api/ps`를 확인하지 못했으면 로드 상태를 추정하지 않습니다.
- llama.cpp: router의 명시적 상태 및 standalone `/v1/models`를 사용합니다. HTTP 503 중 정확한 `Loading model` 응답만 LOADING으로 취급하며 일반 503·인증 실패는 정상 연결로 바꾸지 않습니다.

자동 검사 기본 주기는 30초이며 인프라 화면은 저장된 검사 결과를 15초마다 조회합니다. ‘연결 확인’은 즉시 검사합니다. 로드된 모델이 없는 정상 Runtime에는 복구용 추론을 보내거나 장애 알림을 만들지 않습니다. 과거 모델 등록·서비스 Target·요금·요청 기록은 삭제하지 않습니다.

공식 형식: [LM Studio 모델 목록](https://lmstudio.ai/docs/developer/rest/list), [LM Studio OpenAI 목록과 JIT](https://lmstudio.ai/docs/developer/openai-compat/models), [Ollama 실행 모델](https://docs.ollama.com/api/ps), [llama.cpp 서버](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).

## 배포 호환성

상태 컬럼은 기존 VARCHAR(24)를 사용하므로 DB 마이그레이션은 추가하지 않습니다. 다만 새 enum 값을 모르는 구버전 API는 새 상태가 저장된 행을 읽지 못할 수 있습니다. 공유 DB를 사용하는 여러 API는 함께 교체하거나, 먼저 새 값을 읽을 수 있는 호환 버전을 전체에 배포한 후 상태 기록을 활성화해야 합니다. 구버전으로 롤백할 때도 새 상태 값의 호환성을 확인해야 합니다.
