# Platform administrator 운영 기능

Platform administrator는 특정 조직의 관리자와 달리 전체 Control Plane을 관리하는 최상위 역할입니다. 이 권한은 조직·사용자·API 키·외부 Provider를 정리할 수 있으므로 운영 계정에만 부여하고, 모든 파괴적 작업은 감사 로그와 확인 문자열을 사용합니다.

## 외부 AI Provider 관리

외부 AI 화면에서 Provider를 선택하면 다음 작업을 할 수 있습니다.

- **설정**: 표시 이름, Base URL, API 키를 수정합니다. API 키를 비워 두면 기존 암호화 키를 유지합니다.
- **연결 확인**: Provider의 `/models`를 호출하고 HTTP 상태·지연 시간·인증 방식·모델 ID·소유자·컨텍스트·동시성·기능을 표시합니다. 기능은 Provider 메타데이터를 우선 사용하고, 등록된 AICONNECT 정책이 있으면 그 정책을 함께 표시합니다.
- **모델 등록**: 외부 Provider가 반환한 모델을 AICONNECT의 Model Deployment로 등록합니다. 외부 모델은 자동으로 사용자에게 공개되지 않으며, Logical Service Target에 연결해야 실제 요청에 사용됩니다.
- **삭제**: 먼저 모델·Service Target·프로젝트 권한·요청 이력의 참조 수를 보여 줍니다. 일반 조직 관리자는 참조가 남아 있으면 삭제할 수 없습니다. Platform administrator는 `강제 정리`를 명시하고, 요청 이력까지 지울 때 `요청 이력 영구 삭제`를 추가로 선택해야 합니다.

Provider 삭제 API:

```http
GET    /api/admin/external-providers/{providerId}/deletion-preview
DELETE /api/admin/external-providers/{providerId}?force=false&purgeHistory=false
```

강제 삭제는 Platform administrator 토큰 또는 Platform administrator 세션에서만 허용됩니다. `purgeHistory=true`이면 요청 본문·시도 이력까지 영구 삭제되므로 운영 정책에 따라 사용합니다.

## 전체 운영 API

모든 경로는 Platform administrator 전용입니다.

```http
GET    /api/admin/platform/overview
GET    /api/admin/platform/organizations
GET    /api/admin/platform/organizations/{organizationId}/cleanup-preview
POST   /api/admin/platform/organizations/{organizationId}/suspend
DELETE /api/admin/platform/organizations/{organizationId}?confirmation={조직명}&purgeHistory=true

GET    /api/admin/platform/users
PATCH  /api/admin/platform/users/{userId}       {"enabled": false}
DELETE /api/admin/platform/users/{userId}?confirmation={이메일}
DELETE /api/admin/platform/api-keys/{apiKeyId}
```

### 조직 중지와 삭제

중지는 신규 요청을 막고 감사·사용량 데이터를 보존하는 운영 조치입니다. 복구 가능성이 있는 경우 먼저 중지를 사용합니다.

영구 삭제는 다음 순서로 진행합니다.

1. `cleanup-preview`로 프로젝트, Provider, Runtime, 멤버, 요청 이력 수를 확인합니다.
2. 조직 이름을 확인 문자열로 다시 입력합니다.
3. `purgeHistory=true`를 명시합니다.
4. 요청·콘텐츠·시도 이력, 프로젝트 정책·권한·키, Service Target·Logical Service, 모델·Runtime·Provider, 팀·멤버십을 정리합니다.
5. 사용자 계정 자체는 보존합니다. 사용자를 삭제하려면 별도의 사용자 삭제 API를 사용합니다.

### 사용자와 API 키

- 사용자 비활성화는 로그인만 차단하고 이력은 보존합니다.
- 사용자 영구 삭제는 이메일 확인이 필요하며 현재 로그인한 Platform administrator 자기 자신은 삭제할 수 없습니다.
- 발급자 참조와 감사 로그의 행위자 참조를 먼저 null 처리한 뒤 사용자 계정을 제거합니다.
- API 키 영구 삭제는 키 원문이 아니라 식별자와 prefix만 감사 로그에 남깁니다.

## 권한 경계

| 작업 | 조직 관리자 | Platform administrator |
| --- | --- | --- |
| 자기 조직 Provider 수정 | 가능 | 가능 |
| 참조 없는 Provider 삭제 | 가능 | 가능 |
| 참조가 있는 Provider 강제 삭제 | 불가 | 가능(명시적 force) |
| 다른 조직 Provider 조회/삭제 | 불가 | 가능 |
| 조직 중지/영구 삭제 | 불가 | 가능 |
| 전체 사용자/API 키 정리 | 불가 | 가능 |

삭제 작업은 되돌릴 수 없으므로 운영 DB 백업, Preview 확인, 감사 로그 확인을 순서대로 수행합니다.
