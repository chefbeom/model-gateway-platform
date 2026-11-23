# 서비스 플로우 디자이너(실험) 설계서

> 상태: v1 설계 확정 · 기준 커밋 `8e4fcd5` · 작성 2026-11-23
> 이 문서는 설계와 작업 지시서만 담는다. 문서 작성 시점에 소스 코드는 변경하지 않았다.
> 표기: 설명은 한국어, 식별자·경로·코드는 영어. `파일:줄` 참조는 기준 커밋에서 직접 확인한 위치다.

---

## 1. 개요 · 목표 · 비목표

### 1.1 한 줄 요약

ComfyUI/n8n처럼 노드를 캔버스에 놓고 와이어로 연결하는 **"플로우 디자이너"**를 추가한다. 대상은 **논리 서비스 1개의 요청 파이프라인**(프로젝트 접근 → 가드레일/DLP → 프롬프트·파라미터 → 응답 캐시 → 라우터(Failover·Retry·스케줄링) → Target → Deployment → Runtime/Provider)이다. 캔버스 그래프는 기존 관리 API 호출 목록(Plan)으로 **컴파일**되고, 관리자가 변경 목록을 검토한 뒤 **적용(Apply)**해야만 서버에 쓴다.

### 1.2 목표

| # | 목표 | 판정 기준 |
|---|---|---|
| G1 | 배치·연결이 실제 설정이 된다 | 와이어/속성 변경이 `PATCH/POST/DELETE` 작업으로 컴파일되어 적용된다 |
| G2 | 기존 기능은 무변경이다(순수 추가) | 기존 페이지·API·백엔드 서비스 로직을 수정하지 않는다. 기존 파일 수정은 §11의 통합 지점으로 한정한다 |
| G3 | 서버가 유일한 진실 원천이다 | 그래프는 항상 서버 스냅샷에서 다시 만들 수 있다. 레이아웃은 좌표만 저장한다 |
| G4 | Plan → 검토 → Apply | 확인 전에는 쓰기 요청이 0건이다. 적용 직전에 서버를 다시 읽어 Plan이 그대로인지 확인한다 |
| G5 | 동시 편집과 부분 실패에 안전하다 | 3-way diff와 충돌 시 적용 차단, 작업별 결과 보고, 실패 후 자동 재동기화(rebase) |
| G6 | 의존성을 추가하지 않고 메인 번들도 커지지 않는다 | `vue` 외 런타임 의존성 0개, `defineAsyncComponent` 지연 로딩 |

### 1.3 비목표 (v1에서 하지 않음)

- 서비스 삭제, Node/Runtime/Deployment/Provider 생성·수정·삭제, 모델 동기화·프로브 (InfrastructurePage, ExternalProvidersPage 유지)
- 데이터 보호 세부 정책(탐지 항목, 사용자 정의 패턴) 편집 (DataProtectionPage 유지, `PUT /services/{id}/data-protection` 미사용)
- 프로젝트·API 키 정책, 외부 AI 승인(external-access), Quota
- 조직 전체를 한 캔버스에 담는 다중 서비스 캔버스, 복사/붙여넣기, 템플릿, 미니맵, 실시간 공동 편집, 터치 핀치 줌
- 서버 측 트랜잭션 일괄 적용 API
- 가드레일 시뮬레이터(`pipeline-preview`) 연동. POST 요청이라 감사 로그가 남으므로 v2에서 다룬다

---

## 2. 현행 분석 (직접 확인한 API·필드·제약)

### 2.1 프런트엔드 구조

| 항목 | 확인 내용 | 위치 |
|---|---|---|
| 페이지 등록 | `defineAsyncComponent`로 페이지 지연 로딩 | `frontend/src/ConsoleShell.vue:8-25` |
| PageKey | 문자열 유니온 | `ConsoleShell.vue:41` |
| 역할별 메뉴 | `adminNavItems`(조직/플랫폼 관리자), `directorNavItems`, `developerNavItems` | `ConsoleShell.vue:53-92` |
| 메뉴 그룹 | id 목록 기반 재분류이며, 목록에 없는 id는 `'인프라·시스템'` | `ConsoleShell.vue:149-160` |
| 허용 페이지 | 하드코딩된 `knownPage` 배열, `isAllowedPage` | `ConsoleShell.vue:182-186` |
| 해시 라우팅 | `#page/segment`에서 `split('/')[0]`만 페이지로 해석 | `ConsoleShell.vue:187-191` |
| 페이지 렌더 | `v-else-if` 체인, ServicesPage는 `isOrgOrPlatformAdmin` 조건 | `ConsoleShell.vue:433-453` (ServicesPage `:438`) |
| 관리자 판정 | `isOrgOrPlatformAdmin`: platformAdmin 또는 (DIRECTOR/GENERAL이 아니고 ORGANIZATION_ADMIN) | `ConsoleShell.vue:141-146` |
| HTTP | `adminFetch<T>(path, auth, init)`이 실패 시 `ApiError{message,code,status}`를 throw하고, 204는 `undefined`를 반환 | `frontend/src/api.ts:540-546` |
| 경로 재작성 | 비플랫폼 사용자의 GET `/api/admin/runtime-endpoints`만 조직 경로로 바꾼다. 플랫폼 관리자는 전 조직 Runtime을 받는다 | `api.ts:283-294`, `ControlPlaneService.java:345` |
| 마스커 목록 | `listMaskerDeployments(orgId)` | `api.ts:876-878` |
| 서비스 화면 | 서비스/Target 타입, 파이프라인 저장 시 `guardrailAction`을 **항상** 전송, TTL 하한을 5로 clamp(서버는 10) | `ServicesPage.vue:19-20, 295-337, 322, 326` |
| 스케줄링 | `GET/PATCH /services/{id}/scheduling`, 5초 폴링 | `ServiceSchedulingPanel.vue` |
| RoutingPolicyPanel | 어디에서도 import하지 않는 고아 컴포넌트 | grep 결과 |
| 기존 SVG 맵 | HTML 노드와 SVG 엣지를 섞은 자체 구현, 확대 0.65–1.4, 읽기 전용 | `SystemTopologyMap.vue`, `systemTopology.ts` |
| 부분 실패 로딩 | `read(path, section, fallback)` + `unavailablePaths` 패턴 | `SystemStructurePage.vue:68-120` |
| 테마 토큰 | `--surface(-2,-3)`, `--border(-strong)`, `--accent(-strong,-dim,-border)`, `--danger`, `--warning`, `--info`, `--muted`, `--grid-line`, `:root[data-theme]` | `frontend/src/style.css:3-59` |
| 모달 | `BaseModal` props `{open,title,description?,size?:'sm'|'md'|'lg'}`, slot `default`/`footer`, emit `close` | `frontend/src/BaseModal.vue:4-5` |
| Capability 입력 | `CapabilityPicker` v-model(JSON 문자열) | `frontend/src/CapabilityPicker.vue:4-5` |
| 테스트 로더 | `.ts`를 transpile한 뒤 상대 import에 `.ts`를 붙여 data URL로 로딩한다. `.vue`는 대상이 아니다 | `frontend/tests/loadTypeScript.mjs` |
| ConsoleShell 회귀 테스트 | 정규식 소스 검사, SFC 컴파일 후 렌더 검사. 동적 import는 스텁하지 않으면 실행되지 않는 한 무해 | `tests/betaOperator.test.mjs:343-348`, `tests/dateTime.test.mjs:196-198`, `tests/projectGovernance.test.mjs:9-28, 260-410` |
| CI | 프런트 테스트를 `npm run test:* && ...` 체인으로 실행한 뒤 `npm run build` | `.github/workflows/quality-gate.yml:77-79` |

### 2.2 실제 요청 처리 순서 (`ChatCompletionGateway.java:88-233`)

1. API 키 인증 → `model`로 활성 서비스 조회 → `project_service_access` 확인 (`:90-99`)
2. 데이터 보호 inspect(플랫폼·조직·프로젝트·키·서비스 정책 병합), 차단 시 403 (`:103, :114-120`)
3. 라우팅 평가(Target·Capability·데이터 경계·Failover) (`:107-110`), 스케줄링 대기열(설정 시) (`:129`)
4. 시스템 프롬프트 적용 (`:168`) → 응답 캐시 조회 (`:169-186`). 캐시 키에 `promptVersion`이 포함된다 (`ResponseCacheService.java:33-40`)
5. Target별 시도: temperature 정책(`:227`) → 필요 시 마스킹(`:228-232`, 외부 Target이거나 masker가 지정된 경우) → 외부 Target이면 reasoning/fast 적용

→ 캔버스는 이 고정 순서를 **"서비스 → 가드레일 → 프롬프트·파라미터 → 캐시 → 라우터"라는 논리 단계**로 표현한다. 서버 순서가 하드코딩되어 있으므로 단계 노드는 **재배열할 수 없는 고정 노드**로 둔다(§3 Q1).

### 2.3 사용할 API (모두 기존 API이며, 레이아웃 API만 신규 §9.2)

| 목적 | 메서드·경로 | 요청/응답 핵심 필드 | 위치 |
|---|---|---|---|
| 서비스 목록 | `GET /api/admin/organizations/{org}/services` | `ServicePolicyView{id, organizationId, serviceKey, displayName, failoverPolicy, retryPolicy, allowDegraded, requiredCapabilitiesJson, input/outputPricePerMillion, currency, enabled, temperaturePolicy, temperatureValue, reasoningEffort, openAiFastMode, dataBoundaryPolicy, maskerDeploymentId, maskPersonNames, guardrailAction, systemPromptTemplate, promptVersion, responseCacheEnabled, responseCacheTtlSeconds}` | `RoutingPolicyController.java:43-48, 161-199` |
| 서비스 생성 | `POST /api/admin/services` | `CreateService{organizationId, serviceKey(^[a-z0-9][a-z0-9-]{0,119}$), displayName, failoverPolicy, retryPolicy, allowDegraded, requiredCapabilitiesJson, input/outputPricePerMillion, currency, temperaturePolicy, temperatureValue, reasoningEffort, openAiFastMode, dataBoundaryPolicy}` → `ServiceView{id,...}`. **enabled·guardrail·prompt·cache 필드는 없다** | `AdminController.java:53`, `AdminDtos.java:49-78`, `ControlPlaneService.java:132-146` |
| 서비스 정책 | `PATCH /api/admin/services/{id}` | `UpdateService`(전 필드 선택). **null이면 변경하지 않는다**. `clearMaskerDeployment`, `clearSystemPromptTemplate`, `responseCacheTtlSeconds @Min(10)`, `systemPromptTemplate @Size(max=16000)` | `RoutingPolicyController.java:55-59, 85-108`, `RoutingPolicyService.java:112-161` |
| Target 목록 | `GET /api/admin/services/{id}/targets` | `TargetPolicyView{id, serviceId, deploymentId, priority, weight, degraded, enabled, maxConcurrencyOverride, followModelChanges}` | `RoutingPolicyController.java:67-70, 201-209` |
| Target 생성 | `POST /api/admin/services/{id}/targets` | `CreateTarget{deploymentId, priority≥1, weight≥1, degraded, maxConcurrencyOverride≥1, followModelChanges}`, **enabled 필드 없음**, 응답 `TargetView{id,...}` | `AdminController.java:60`, `AdminDtos.java:80-87`, `ControlPlaneService.java:165-187` |
| Target 수정 | `PATCH /api/admin/services/{sid}/targets/{tid}` | `UpdateTarget{priority, weight, degraded, enabled, maxConcurrencyOverride, followModelChanges}`, null이면 변경 없음, **deploymentId는 변경 불가** | `RoutingPolicyController.java:72-76, 147-154` |
| Target 삭제 | `DELETE` 같은 경로 → 204, 없으면 404 `TARGET_NOT_FOUND` | | `RoutingPolicyController.java:78-83`, `RoutingPolicyService.java:274-293` |
| 스케줄링 | `GET/PATCH /api/admin/services/{id}/scheduling` | `{settings:{mode, localWaitMs, maxWaitMs, maxQueuedRequests, maxQueuedBytes, maxQueuedPerKey}, queue}`. PATCH 본문은 **전체 객체**이고 `@NotNull mode` | `SchedulingPolicyController.java:16-17`, `SchedulingSettings.java` |
| 서비스 DLP 조회 | `GET /api/admin/services/{id}/data-protection` | `PolicyView{mode, level, externalAction, allowExternalFailover, detect*}` (v1은 읽기 전용) | `DataProtectionPolicyController.java:106-110` |
| 마스커 후보 | `GET /api/admin/organizations/{org}/masker-deployments` | `MaskerDeploymentOption{deploymentId, displayName, providerModelId, sourceType, sourceName, eligible, trustReason}` | `RoutingPolicyController.java:50-53`, `RoutingPolicyService.java:77-110` |
| Runtime | `GET /api/admin/organizations/{org}/runtime-endpoints` | `EndpointView{id,nodeId,displayName,runtimeType,baseUrl,enabled,healthStatus,...}` (apiToken 미포함) | `OrganizationRuntimeInventoryController.java:22-26` |
| 로컬 Deployment | `GET /api/admin/runtime-endpoints/{id}/deployments` | `DeploymentView{id, runtimeEndpointId, externalProviderId, providerModelId, displayName, loaded, enabled, healthStatus, maxConcurrency, contextLength,...}` (archived 플래그 없음) | `AdminController.java:98, 127` |
| Provider | `GET /api/admin/organizations/{org}/external-providers` | `ProviderView{id, providerType, displayName, baseUrl, enabled, healthStatus, apiKeyConfigured, zeroRetentionVerified,...}` (키 원문 없음) | `ExternalProviderAdministrationController.java:23-24`, `ExternalProviderAdministrationService.java:480-486` |
| 외부 모델 | `GET /api/admin/external-providers/{id}/models` | `ProviderModelView{id, externalProviderId, providerModelId, displayName, contextLength, enabled, healthStatus, maxConcurrency,...}` (`loaded` 없음) | `ExternalProviderAdministrationController.java:48-49` |
| 프로젝트 | `GET /api/admin/organizations/{org}/projects` | `{id, name, status, teamId, ...}` | `OrganizationQueryController.java:50` |
| 프로젝트 접근 조회 | `GET /api/admin/projects/{pid}/service-access` | `ServiceAccessView{id(serviceId), serviceKey, displayName, enabled}[]` | `ProjectServiceAccessManagementController.java:35-38` |
| 접근 부여 | `POST /api/admin/projects/{pid}/service-access` `{serviceId}` | 같은 조직인지 검사. 복합 PK라 중복 호출해도 멱등 | `AdminController.java:55-59`, `ControlPlaneService.java:155-163` |
| 접근 해제 | `DELETE /api/admin/projects/{pid}/service-access/{sid}` → 204, 없으면 404 `PROJECT_SERVICE_ACCESS_NOT_FOUND` | | `ProjectServiceAccessManagementController.java:40-45`, `ProjectServiceAccessManagementService.java:46-53` |

### 2.4 설계에 직접 반영해야 하는 서버 의미론

| # | 사실 | 설계 반영 |
|---|---|---|
| N1 | PATCH 서비스/Target은 **null = 변경 없음**이다 (`LlmService.java:71-84`, `ServiceTarget.java:35-43`) | 변경된 필드만 보내는 부분 PATCH로 컴파일한다 |
| N2 | 마스커와 프롬프트 해제는 `clearMaskerDeployment:true`, `clearSystemPromptTemplate:true` 플래그로 한다 (`LlmService.java:148-173`) | 값이 null로 바뀌면 clear 플래그로 변환한다 |
| N3 | 프롬프트가 실제로 바뀌면(서버가 trim 후 비교) `promptVersion+1`, 캐시 키가 바뀐다 (`LlmService.java:159-173`, `ResponseCacheService.java:38`) | 비교 전에 trim하고 ''는 null로 정규화한다. Plan에 버전 증가와 캐시 무효화를 안내한다 |
| N4 | `guardrailAction`을 보내면 **서비스 범위 DLP 정책을 다시 저장**한다. mode/level/외부 failover를 재설정하고 비밀정보·개인정보·금융 탐지를 **true로 강제**한다. 기밀·미디어·사용자 패턴은 유지 (`RoutingPolicyService.java:133-158`) | **값이 바뀐 경우에만** 전송하고, Plan에 부수효과 경고를 표시한다 |
| N5 | `maxConcurrencyOverride`는 PATCH로 **해제(null) 불가**다 (`ServiceTarget.java:41`) | 기존 값을 비우면 검증 오류 `TARGET_CONCURRENCY_CLEAR_UNSUPPORTED`를 낸다 |
| N6 | Target의 `deploymentId`는 API로 바꿀 수 없다(`rebindTo`는 모델 추적 로직 전용, `ServiceTarget.java:44`) | 재연결은 **새 Target 생성 + 기존 Target 삭제(교체)**로 컴파일한다 |
| N7 | `UNIQUE(service_id, deployment_id)`: 서비스당 Deployment 1개에 Target 1개 (`V1__initial_control_plane.sql:127`) | 중복 바인딩은 검증 **오류**다. 교체·스왑 시 "선행 삭제" 단계를 둔다(§7.6) |
| N8 | `CreateTarget`에 `enabled`가 없어 항상 활성으로 생성된다 | 비활성 신규 Target은 생성 직후 `PATCH {enabled:false}`를 붙인다 |
| N9 | 외부 Deployment Target의 `followModelChanges`는 생성 시 서버가 false로 강제한다 (`ControlPlaneService.java:186`) | 외부 Deployment에 바인딩하면 그래프에서도 false로 맞춘다 |
| N10 | `UNIQUE(organization_id, service_key)`가 tombstone 서비스까지 포함한다 (`V1:102`, `LlmService.deletedAt`) | 목록에 없는 key도 생성이 실패할 수 있다. 서버 오류를 그대로 보고한다(§12) |
| N11 | Jackson `default-property-inclusion: non_null` (`application.yml:19`): null 필드는 **응답에서 빠진다** | 정규화 단계에서 누락 필드를 null/기본값으로 처리한다 |
| N12 | `/api/admin/**`의 성공한 변경 요청은 모두 감사 로그 `ADMIN_CONFIGURATION_CHANGED`로 기록된다 (`AdminTokenFilter.java:42-48`) | 레이아웃은 자동 저장하지 않는다(명시적 저장 + 적용 직후 1회만) |
| N13 | 모델 추적(V29)으로 서버가 Target의 deploymentId를 **자발적으로** 바꿀 수 있다 | deploymentId도 3-way 비교 대상 필드다 |
| N14 | 플랫폼 관리자의 `GET /api/admin/runtime-endpoints`는 전 조직을 반환한다 | 디자이너는 **조직 경로만** 사용한다 |
| N15 | ORGANIZATION_ADMIN을 부여하면 비플랫폼 사용자는 PROJECT_DIRECTOR로 승격되고(`IdentityService.java:91-98`), DIRECTOR는 조직 관리자가 아니다(`TeamAccessService.java:51-57, 117-119`) | 실사용 편집자는 주로 시스템 관리자다. 프런트 노출 조건은 기존 `isOrgOrPlatformAdmin`을 따른다 |
| N16 | 운영 DB는 `ddl-auto: validate`, 통합 테스트는 H2 `create-drop`이고 Flyway는 비활성이다 (`application.yml:11`, `src/test/resources/application-integration.yml`) | 신규 엔티티 매핑이 마이그레이션과 정확히 일치해야 한다 |
| N17 | 조직 영구 삭제가 `llm_service` 행을 하드 삭제한다 (`PlatformAdministrationService.java:152`) | 레이아웃 FK는 `ON DELETE CASCADE`로 둔다(`V40` 스케줄링 테이블과 같은 방식). 삭제 순서 코드는 수정하지 않는다 |

### 2.5 권한과 조직 스코프

- `OrganizationAuthorizationFilter`(`web/OrganizationAuthorizationFilter.java:58-200`)가 `/api/admin/**` 경로에서 조직을 해석한다. `organizations/{org}`는 경로, `services/{id}`·`projects/{id}`·`runtime-endpoints/{id}`·`external-providers/{id}`는 엔티티로 해석한다. 비플랫폼 사용자는 조직 멤버여야 하고, 조직 관리자가 아니면 discovery GET과 프로젝트 관리 경로 외에는 403(`ORGANIZATION_ADMIN_REQUIRED`)이다.
- 따라서 `/api/admin/services/{serviceId}/...` 아래 신규 엔드포인트는 **필터가 서비스 소유 조직의 관리자만 통과시킨다**. 최근 RBAC/IDOR 수정 방식(`DataProtectionPolicyController.java:131-137`의 `isOrganizationOrPlatformAdmin`)을 따라 컨트롤러에서도 한 번 더 검사한다(defense in depth).
- 서버 쪽 Target 생성은 Deployment의 조직 일치를 검사하고(`ControlPlaneService.java:171-183`), 마스커는 `assertTrustedMaskerDeployment`로 검사한다. 프런트는 집행 지점이 아니다.

### 2.6 테스트·빌드 관례

- 순수 TS는 `node --test tests/<x>.test.mjs`로 검증한다(`loadTypeScript`). 확장자 없는 상대 import를 쓰고, `.ts` 확장자 import는 vue-tsc가 거부한다(`allowImportingTsExtensions` 미설정).
- `.vue`는 단위 테스트하지 않는다(일부 테스트는 `@vue/compiler-sfc`로 렌더 검사). 빌드 검증은 `npm run build`(vue-tsc + vite)로 한다.
- 백엔드 통합 테스트는 `@SpringBootTest @ActiveProfiles("integration")`와 개별 H2 URL을 쓴다. 플랫폼 토큰 헤더는 `X-Admin-Token: integration-admin-token`이다. 사용자 토큰은 `login → totp/setup → totp/enroll` 흐름으로 얻는다(`ScopedRuntimeInventoryIntegrationTest.java:42-68`).

---

## 3. 핵심 설계 결정 (10문항)

### Q1. v1 범위: **조직 안의 "서비스 1개 = 캔버스 1개" (Service Flow)**

- **결정**: 상단 서비스 선택기로 서비스 하나를 고른다. 캔버스에는 그 서비스의 파이프라인과 연결된 자원만 그린다. "새 서비스 초안"도 같은 캔버스에서 만들 수 있다.
- **이유**: 도메인 집합(서비스 + Target + 정책 + 접근)과 1:1이어서 diff와 Plan이 단순하다. 그래프가 작고(실사용 20–80노드, 상한 300) 레이아웃 키도 `serviceId` 하나면 된다.
- **기각**: 조직 전체 캔버스. 서비스 간 Deployment 공유 표현과 대규모 Plan으로 v1 위험이 커진다(후속 과제).

| 구분 | v1 편집 | v1 읽기 전용 | 연기 |
|---|---|---|---|
| 서비스 | 표시 이름, 활성, 데이터 경계, 필수 Capability, 단가·통화, 신규 시 serviceKey | promptVersion | 삭제(ServicesPage 유지) |
| 가드레일 | guardrailAction, 성명 가명화, 마스킹 워커(노드) | 서비스 DLP 탐지 항목·모드 | DLP 세부 편집, 시뮬레이터 |
| 프롬프트·파라미터 | 시스템 프롬프트, temperature, reasoningEffort, fast mode | | 프롬프트 이력 |
| 캐시 | 사용 여부, TTL | | |
| 라우터 | failover, retry, allowDegraded, 스케줄링 6필드 | 대기열 현황 | |
| Target | 생성, 삭제, 우선순위, 가중치, 활성, degraded, 동시성 재정의, 모델 추적, Deployment 재연결(교체) | | |
| 프로젝트 접근 | 부여, 해제 | 프로젝트 상태·경계 | API 키, 외부 승인 |
| Deployment/Runtime/Provider | 캔버스 배치만 가능(참조 노드) | 상태·모델·주소 라벨 | 생성·수정·동기화 |

### Q2. 노드 타입 카탈로그: **11종**(§5)

고정 단계 노드 5종(service, guardrail, prompt, cache, router)은 싱글턴이고 삭제할 수 없다. 사용자가 추가할 수 있는 노드는 4종(project, target, deployment, masker), 자동으로 관리되는 노드는 2종(endpoint, provider)이다.

### Q3. 엣지 의미: **엣지는 데이터를 갖지 않고 Target은 노드다**

- **결정**: Router→(route)→**Target 노드**→(binding)→Deployment. 우선순위·가중치 같은 Target 속성은 Target 노드에 둔다.
- **이유**: ComfyUI/n8n 관례대로 설정은 노드에, 연결은 순수 관계로 둔다. 인스펙터는 노드 하나만 다루면 된다. 미연결이나 비활성 같은 Target 상태를 시각화하기 쉽다. 같은 노드를 다른 Deployment에 재연결하면 교체(N6)로 자연스럽게 매핑된다.
- **편의**: Router의 `targets` 출력 포트에서 Deployment로 와이어를 그으면 Target 노드가 **자동 삽입**된다.
- 엣지 6종의 의미는 §6.1, 연결 규칙·순환 방지·검증은 §6을 본다.

### Q4. 그래프 ⇄ 도메인: **decompile / compile / 3-way plan / execute / rebase**

- 모두 순수 TS다(execute만 transport를 주입받는다). 적용 흐름은 `Plan(재조회 R1) → 검토 모달 → Apply 클릭 → 재조회 R2 → 재계산한 Plan의 fingerprint가 같을 때만 실행`이다.
- 실행은 **순차 실행, 첫 실패에서 중단, 자동 롤백 없음**이다. 롤백도 위험한 쓰기이고, 프롬프트 버전 증가처럼 되돌릴 수 없는 효과가 있기 때문이다. 결과는 작업별로 보고한다. 실행 후에는 항상 **재조회 + rebase**로 캔버스를 서버와 맞추고, 남은 변경은 그대로 대기 상태로 둔다. 상세는 §7.

### Q5. 레이아웃 저장: **서버 측(Flyway V53 `service_flow_layout`)**

- **결정**: `GET/PUT /api/admin/services/{serviceId}/flow-layout`. 좌표, 접힘 상태, 뷰포트만 저장하고, 서버가 검증한 뒤 재직렬화한다(§9.2).
- **이유**: (1) 배치는 사용자가 "만든 것"이므로 조직 관리자 사이에서 공유되어야 한다. (2) 최근 보안 커밋이 브라우저 저장소를 축소했다(`Root.vue` clearSession 키 목록). 디자이너는 `localStorage`/`sessionStorage`에 쓰지 않는다. (3) 별도 테이블이고 기존 테이블을 변경하지 않으며 FK는 CASCADE라 기존 동작 영향이 0이다.
- **보호장치**: 레이아웃은 절대 설정의 원천이 아니다. decompile은 레이아웃을 좌표 매핑에만 쓰고, 모르는 id는 무시한다. 감사 로그 폭증을 막기 위해 자동 저장하지 않는다(N12). API가 없거나 실패하면 자동 정렬로 대체하고 저장 버튼을 숨긴다.
- **기각**: localStorage. 기기와 관리자 간 공유가 안 되고 저장소 정책에 역행한다.

### Q6. 캔버스 엔진: **Vue 기반 자체 구현(HTML 노드 + SVG 엣지), 의존성 추가 없음**

- **이유**: 저장소의 "vue 단일 런타임 의존" 원칙, Windows와 오프라인 빌드 안정성, 테마 토큰·접근성·결정적 포트 좌표에 대한 완전한 통제가 필요하다. 200노드는 DOM과 SVG로 충분하다. `SystemTopologyMap.vue`라는 선례도 있다.
- **기각**: `@vue/flow`(@vue-flow/core). 번들이 커지고 d3 계열 전이 의존성이 생긴다. 테마와 포트 규칙을 커스터마이즈하는 비용이 자체 구현과 비슷하다.
- 엔진 명세는 §8에 있다. 팬/줌(휠 줌, 범위 0.2–2), 16px 스냅 드래그, 결정적 포트 좌표 기반 히트 테스트, 베지어 와이어, 클릭/Shift 다중/마키 선택, Delete, 스냅샷 방식 Undo/Redo(100단계), 화면 맞춤, 첫 로드 시 열 기반 자동 정렬, 키보드 조작, 테마 토큰, 200노드 60fps 드래그를 포함한다. 미니맵과 복사/붙여넣기는 v2로 미룬다.

### Q7. 화면 구성: **좌 팔레트 / 중앙 캔버스 / 우 인스펙터 / 상단 툴바 / 하단 상태바 / Plan 모달(BaseModal lg)**

상세는 §8.7이다. 실험 배지는 `EXPERIMENTAL`을 쓴다. 1020px 미만에서는 팔레트와 인스펙터가 드로어가 되고, 760px 미만에서는 **읽기 전용**이 된다.

### Q8. 통합: **PageKey `flow-designer`, `adminNavItems`에만 추가, 지연 로딩**

- 조직 관리자와 플랫폼 관리자(`isOrgOrPlatformAdmin`)만 보고 편집한다. Director와 일반 사용자에게는 메뉴가 없고, 직접 해시로 접근하면 기존 `ensureAllowedPage`가 대시보드로 돌려보낸다.
- 딥링크는 `#flow-designer/<serviceId>`이다(ConsoleShell은 첫 세그먼트만 해석하므로 추가 수정이 필요 없다).
- **ServicesPage의 "캔버스에서 열기" 링크는 v1에서 넣지 않는다.** 기존 화면 무수정 원칙 때문이다. 딥링크가 있으므로 나중에 한 줄로 추가할 수 있다.

### Q9. 보안

프런트는 기존 인가 API만 호출하고, 서버가 유일한 집행 지점이다. 신규 레이아웃 API는 필터와 컨트롤러에서 이중으로 조직 관리자를 검사하고, tombstone 서비스는 404로 처리한다. 그래프 JSON과 레이아웃에 비밀값(API 키, Provider 키, Runtime 토큰, baseUrl 원문)을 넣지 않는다. 노드 300개, 엣지 600개, Plan 작업 100개, 레이아웃 60KB를 상한으로 둔다. 상세는 §9.

### Q10. 테스트

- WP-A: `tests/flowDesignerModel.test.mjs`(모델 전 영역, §10.1).
- WP-C: `tests/flowDesignerAdapter.test.mjs`(정규화, 실행기, 초안, ConsoleShell 소스 검사, §10.2).
- npm 스크립트 `test:flow-designer`를 추가하고 CI 체인에 넣는다.
- 백엔드: `ServiceFlowLayoutIntegrationTest`.
- 수동 브라우저 체크리스트는 §10.5.

---

## 4. 데이터 모델 & 타입 정의

### 4.1 디렉터리와 파일 소유 (선례: `frontend/src/dev-docs/`)

```
frontend/src/flow-designer/
  types.ts          (WP-A) 공유 타입 — §4.2 전문을 그대로 사용
  catalog.ts        (WP-A) 노드 타입·포트·필드·연결 규칙·기본값·라벨·describeNode
  layout.ts         (WP-A) 기하·좌표·자동 정렬·레이아웃 직렬화/검증
  graphOps.ts       (WP-A) 순수 그래프 명령(applyCommand), canConnect, 호스트 동기화
  decompile.ts      (WP-A) snapshot(+layout) → graph, syncGraphToConfig
  compile.ts        (WP-A) graph → FlowConfigState, 정규화 유틸
  validate.ts       (WP-A) 검증 이슈
  diff.ts           (WP-A) 3-way planChanges, mergeConfigs, rebase, computeNodeChanges
  history.ts        (WP-A) Undo/Redo
  FlowCanvas.vue    (WP-B)
  FlowNodeCard.vue  (WP-B)
  FlowPalette.vue   (WP-B)
  FlowInspector.vue (WP-B)
  FlowToolbar.vue   (WP-B)
  flowSnapshot.ts   (WP-C) 원시 API JSON → ServiceFlowSnapshot (순수)
  flowApi.ts        (WP-C) 조회·transport·레이아웃 API 클라이언트
  execute.ts        (WP-C) Plan 실행기 (순수 + 주입 transport)
  draftStore.ts     (WP-C) 메모리 전용 초안 보관 (순수)
  FlowPlanModal.vue (WP-C)
  FlowDesignerPage.vue (WP-C)
  useFlowEditor.ts  (WP-C, 선택) 페이지 상태 composable
frontend/tests/flowDesignerModel.test.mjs   (WP-A)
frontend/tests/flowDesignerAdapter.test.mjs (WP-C)
```

import 규칙: 확장자 없는 상대 경로(`./types`)를 쓰고, 타입은 반드시 `import type`으로 가져온다. WP-A 모듈은 `vue`, `../api`, DOM 전역(window/document/sessionStorage)을 import하거나 참조하지 않는다. 모듈 간 순환 import를 금지한다(허용 방향: types ← catalog ← layout ← graphOps ← decompile ← compile ← validate ← diff. 단 compile과 validate는 decompile 없이 catalog만 써도 된다).

### 4.2 `frontend/src/flow-designer/types.ts` 전문 (WP-A가 이대로 작성하고 B/C는 이 계약에 맞춰 코딩)

```ts
// Shared contracts for the experimental Service Flow Designer.
// Pure type declarations only: no runtime values, no imports.

/* ---------------- Domain enums (mirror backend enums) ---------------- */
export type FailoverPolicy = 'STRICT' | 'COMPATIBLE' | 'DEGRADED'
export type RetryPolicy = 'SAFE' | 'AGGRESSIVE'
export type DataBoundaryPolicy = 'INTERNAL_ONLY' | 'APPROVED_EXTERNAL_NO_RETENTION' | 'EXTERNAL_ALLOWED'
export type Currency = 'KRW' | 'USD'
export type TemperaturePolicy = 'REQUEST' | 'FIXED' | 'OMIT'
export type ReasoningEffort = 'REQUEST' | 'NONE' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX'
export type GuardrailAction = 'ALLOW' | 'REDACT_RESTORE' | 'REDACT' | 'LOCAL_ONLY' | 'BLOCK'
export type SchedulingMode = 'IMMEDIATE' | 'LOCAL_FIRST_WAIT' | 'LOCAL_ONLY_QUEUE'

/* ---------------- Editable domain state (server is the source of truth) ---------------- */
export interface ServiceState {
  serviceKey: string
  displayName: string
  enabled: boolean
  dataBoundaryPolicy: DataBoundaryPolicy
  requiredCapabilitiesJson: string
  currency: Currency
  inputPricePerMillion: number
  outputPricePerMillion: number
  guardrailAction: GuardrailAction
  maskerDeploymentId: string | null
  maskPersonNames: boolean
  systemPromptTemplate: string | null
  temperaturePolicy: TemperaturePolicy
  temperatureValue: number | null
  reasoningEffort: ReasoningEffort
  openAiFastMode: boolean
  responseCacheEnabled: boolean
  responseCacheTtlSeconds: number
  failoverPolicy: FailoverPolicy
  retryPolicy: RetryPolicy
  allowDegraded: boolean
}
export type ServiceField = keyof ServiceState

export interface SchedulingState {
  mode: SchedulingMode
  localWaitMs: number
  maxWaitMs: number
  maxQueuedRequests: number
  maxQueuedBytes: number
  maxQueuedPerKey: number
}
export type SchedulingField = keyof SchedulingState

export interface TargetState {
  /** FlowNode id of the target node: 'target:<uuid>' or 'target:new-<n>'. */
  key: string
  /** Server id; null until created. */
  id: string | null
  deploymentId: string
  priority: number
  weight: number
  degraded: boolean
  enabled: boolean
  maxConcurrencyOverride: number | null
  followModelChanges: boolean
}
export type TargetField = 'deploymentId' | 'priority' | 'weight' | 'degraded' | 'enabled' | 'maxConcurrencyOverride' | 'followModelChanges'

export interface FlowConfigState {
  service: ServiceState
  scheduling: SchedulingState
  /** Sorted by key. */
  targets: TargetState[]
  /** Sorted ascending, unique. */
  accessProjectIds: string[]
}

/* ---------------- Read-only catalog ---------------- */
export type HealthTone = 'healthy' | 'warning' | 'error' | 'muted' | 'unknown'
export interface ServiceSummary { id: string; serviceKey: string; displayName: string; enabled: boolean }
export interface DeploymentInfo {
  id: string
  displayName: string
  providerModelId: string
  runtimeEndpointId: string | null
  externalProviderId: string | null
  external: boolean
  enabled: boolean
  /** Always true for external deployments (load state does not apply). */
  loaded: boolean
  healthStatus: string
  maxConcurrency: number
  contextLength: number | null
}
export interface EndpointInfo { id: string; displayName: string; runtimeType: string; enabled: boolean; healthStatus: string; addressLabel: string }
export interface ProviderInfo { id: string; displayName: string; providerType: string; enabled: boolean; healthStatus: string; apiKeyConfigured: boolean; zeroRetentionVerified: boolean }
export interface ProjectInfo { id: string; name: string; status: string; dataBoundaryPolicy: DataBoundaryPolicy | null }
export interface MaskerInfo { deploymentId: string; displayName: string; providerModelId: string; sourceType: string; sourceName: string; eligible: boolean; trustReason: string }
export interface ServiceDataProtectionInfo {
  mode: string
  level: string
  externalAction: string
  allowExternalFailover: boolean
  detectSecrets: boolean
  detectPii: boolean
  detectFinancial: boolean
  detectConfidential: boolean
  detectMedia: boolean
}
export interface FlowCatalog {
  services: ServiceSummary[]
  deployments: DeploymentInfo[]
  endpoints: EndpointInfo[]
  providers: ProviderInfo[]
  projects: ProjectInfo[]
  maskers: MaskerInfo[]
}
export interface ServiceFlowSnapshot {
  organizationId: string
  /** null = unsaved new-service draft. */
  serviceId: string | null
  /** Read-only; 1 for drafts. */
  promptVersion: number
  config: FlowConfigState
  /** false => project access could not be fully read; access edits are disabled. */
  accessComplete: boolean
  catalog: FlowCatalog
  dataProtection: ServiceDataProtectionInfo | null
  /** ISO-8601 timestamp. */
  loadedAt: string
  /** API paths whose (non-fatal) read failed. */
  unavailable: string[]
}

/* ---------------- Graph ---------------- */
export type NodeType = 'project' | 'service' | 'masker' | 'guardrail' | 'prompt' | 'cache' | 'router' | 'target' | 'deployment' | 'endpoint' | 'provider'
export type NodeCategory = 'entry' | 'pipeline' | 'routing' | 'infra'
export type PortType = 'access' | 'request' | 'masker' | 'route' | 'binding' | 'host'
export type PortDirection = 'in' | 'out'
export type EdgeKind = 'access' | 'pipeline' | 'masker' | 'route' | 'binding' | 'host'
export type FlowPropValue = string | number | boolean | null

export interface FlowNode {
  id: string
  type: NodeType
  /** Referenced entity id (project/target/deployment/endpoint/provider); null otherwise or for new targets. */
  refId: string | null
  /** World coordinates of the top-left corner. */
  x: number
  y: number
  collapsed: boolean
  props: Record<string, FlowPropValue>
}
export interface PortRef { nodeId: string; portId: string }
export interface FlowEdge { id: string; kind: EdgeKind; from: PortRef; to: PortRef; locked: boolean }
export interface FlowGraph {
  schemaVersion: 1
  serviceId: string | null
  nodes: FlowNode[]
  edges: FlowEdge[]
  /** Sequence for 'target:new-<n>' ids; starts at 1. */
  nextSeq: number
}

export interface PortSpec { id: string; direction: PortDirection; type: PortType; label: string; multiple: boolean }
export type FieldKind = 'text' | 'textarea' | 'number' | 'integer' | 'boolean' | 'select' | 'capabilities' | 'masker-select' | 'deployment-binding' | 'access-toggle'
export interface FieldOption { value: string; label: string }
export interface FieldSpec {
  /** Prop key, or a virtual key starting with '$' ('$binding', '$access'). */
  key: string
  label: string
  kind: FieldKind
  options?: FieldOption[]
  min?: number
  max?: number
  step?: number
  maxLength?: number
  /** For number/integer: an empty input means null. */
  nullable?: boolean
  placeholder?: string
  help?: string
  visibleWhen?: { key: string; oneOf: FlowPropValue[] }
  /** Editable only while graph.serviceId === null. */
  newServiceOnly?: boolean
}
export interface NodeTypeSpec {
  type: NodeType
  label: string
  category: NodeCategory
  icon: string
  column: number
  singleton: boolean
  deletable: boolean
  paletteAddable: boolean
  ports: PortSpec[]
  fields: FieldSpec[]
}
export interface ConnectionRule { fromType: NodeType; fromPort: string; toType: NodeType; toPort: string; kind: EdgeKind; userEditable: boolean }
export interface NodeView {
  title: string
  subtitle: string
  typeLabel: string
  category: NodeCategory
  icon: string
  tone: HealthTone
  badges: string[]
  /** At most 2 lines. */
  summary: string[]
  locked: boolean
}
export interface FactRow { label: string; value: string }

/* ---------------- Geometry ---------------- */
export interface Point { x: number; y: number }
export interface Size { width: number; height: number }
export interface Rect { x: number; y: number; width: number; height: number }
export interface Viewport { x: number; y: number; zoom: number }

/* ---------------- Layout persistence ---------------- */
export interface FlowLayoutNode { x: number; y: number; collapsed?: boolean }
export interface FlowLayout { version: 1; viewport: Viewport; nodes: Record<string, FlowLayoutNode> }

/* ---------------- Editing ---------------- */
export interface FlowSelection { nodeIds: string[]; edgeIds: string[] }
export type AddableNodeType = 'project' | 'deployment' | 'target' | 'masker'
export type FlowCommand =
  | { type: 'moveNodes'; moves: Array<{ nodeId: string; x: number; y: number }> }
  | { type: 'addNode'; nodeType: AddableNodeType; refId?: string | null; position?: Point | null }
  | { type: 'removeItems'; nodeIds: string[]; edgeIds: string[] }
  | { type: 'connect'; from: PortRef; to: PortRef }
  | { type: 'updateProps'; nodeId: string; patch: Record<string, FlowPropValue> }
  | { type: 'bindTarget'; targetNodeId: string; deploymentId: string }
  | { type: 'setAccess'; projectNodeId: string; granted: boolean }
  | { type: 'toggleCollapse'; nodeIds: string[]; collapsed: boolean }
  | { type: 'autoLayout' }
export interface CommandResult { graph: FlowGraph; changed: boolean; error: string | null; select: FlowSelection | null }
export type ConnectCheck = { ok: true; replaces: string[] } | { ok: false; reason: string }
export type NodeChangeKind = 'added' | 'modified'
export type FlowToolbarAction = 'undo' | 'redo' | 'fit' | 'auto-layout' | 'validate' | 'plan' | 'revert' | 'reload' | 'save-layout' | 'toggle-palette' | 'toggle-inspector'

/* ---------------- Validation ---------------- */
export type IssueSeverity = 'error' | 'warning' | 'info'
export interface ValidationIssue { code: string; severity: IssueSeverity; message: string; nodeId: string | null; edgeId: string | null; field: string | null }

/* ---------------- Plan ---------------- */
export type ServiceRef = { kind: 'existing'; id: string } | { kind: 'created' }
export type TargetRef = { kind: 'existing'; id: string } | { kind: 'created'; key: string }
export type OperationRisk = 'safe' | 'caution' | 'danger'
export interface CreateServiceBody {
  organizationId: string
  serviceKey: string
  displayName: string
  failoverPolicy: FailoverPolicy
  retryPolicy: RetryPolicy
  allowDegraded: boolean
  requiredCapabilitiesJson: string
  inputPricePerMillion: number
  outputPricePerMillion: number
  currency: Currency
  temperaturePolicy: TemperaturePolicy
  temperatureValue: number | null
  reasoningEffort: ReasoningEffort
  openAiFastMode: boolean
  dataBoundaryPolicy: DataBoundaryPolicy
}
export interface PatchServiceBody {
  displayName?: string
  enabled?: boolean
  dataBoundaryPolicy?: DataBoundaryPolicy
  requiredCapabilitiesJson?: string
  currency?: Currency
  inputPricePerMillion?: number
  outputPricePerMillion?: number
  guardrailAction?: GuardrailAction
  maskerDeploymentId?: string
  clearMaskerDeployment?: true
  maskPersonNames?: boolean
  systemPromptTemplate?: string
  clearSystemPromptTemplate?: true
  temperaturePolicy?: TemperaturePolicy
  temperatureValue?: number | null
  reasoningEffort?: ReasoningEffort
  openAiFastMode?: boolean
  responseCacheEnabled?: boolean
  responseCacheTtlSeconds?: number
  failoverPolicy?: FailoverPolicy
  retryPolicy?: RetryPolicy
  allowDegraded?: boolean
}
export interface CreateTargetBody { deploymentId: string; priority: number; weight: number; degraded: boolean; followModelChanges: boolean; maxConcurrencyOverride?: number }
export interface PatchTargetBody { priority?: number; weight?: number; degraded?: boolean; enabled?: boolean; maxConcurrencyOverride?: number; followModelChanges?: boolean }
export interface OperationChange { field: string; label: string; before: string; after: string }
export interface OperationCommon { opId: string; risk: OperationRisk; summary: string; changes: OperationChange[] }
export type FlowOperation =
  | (OperationCommon & { kind: 'createService'; body: CreateServiceBody })
  | (OperationCommon & { kind: 'patchService'; service: ServiceRef; body: PatchServiceBody })
  | (OperationCommon & { kind: 'updateScheduling'; service: ServiceRef; body: SchedulingState })
  | (OperationCommon & { kind: 'createTarget'; service: ServiceRef; key: string; body: CreateTargetBody })
  | (OperationCommon & { kind: 'patchTarget'; service: ServiceRef; target: TargetRef; body: PatchTargetBody })
  | (OperationCommon & { kind: 'deleteTarget'; service: ServiceRef; targetId: string })
  | (OperationCommon & { kind: 'grantAccess'; service: ServiceRef; projectId: string })
  | (OperationCommon & { kind: 'revokeAccess'; service: ServiceRef; projectId: string })
export type FlowOperationKind = FlowOperation['kind']
export type ConflictCode = 'FIELD_CONFLICT' | 'TARGET_REMOVED_REMOTELY' | 'TARGET_CHANGED_REMOTELY' | 'DEPLOYMENT_TARGETED_REMOTELY'
export interface FlowConflict {
  code: ConflictCode
  entity: 'service' | 'scheduling' | 'target'
  /** Field name for service/scheduling, target key for targets. */
  key: string
  field: string | null
  base: string
  local: string
  remote: string
  message: string
}
export interface PlanNote { level: 'info' | 'warning'; message: string }
export interface FlowPlan {
  operations: FlowOperation[]
  conflicts: FlowConflict[]
  blockingIssues: ValidationIssue[]
  notes: PlanNote[]
  remoteDrift: boolean
  fingerprint: string
}
export interface PlanInput { base: ServiceFlowSnapshot; desired: FlowConfigState; remote: ServiceFlowSnapshot; issues: ValidationIssue[] }
export interface RebaseInput { graph: FlowGraph; base: ServiceFlowSnapshot; remote: ServiceFlowSnapshot; createdTargetIds?: Record<string, string> }
export interface RebaseResult { graph: FlowGraph; conflicts: FlowConflict[] }

/* ---------------- Execution (implemented by WP-C execute.ts) ---------------- */
export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
export interface FlowTransport { request<T>(method: HttpMethod, path: string, body?: unknown): Promise<T> }
export interface TransportError { message: string; code?: string; status?: number }
export type OperationStatus = 'pending' | 'running' | 'succeeded' | 'already-applied' | 'failed' | 'skipped'
export interface OperationResult { opId: string; status: OperationStatus; message: string | null; code: string | null; httpStatus: number | null }
export interface ExecutionReport {
  results: OperationResult[]
  /** Service id after execution (the created id for new services). */
  serviceId: string | null
  /** Target key (FlowNode id) -> created server id. */
  createdTargetIds: Record<string, string>
  completed: boolean
  failedOpId: string | null
}
```

### 4.3 모듈별 공개 API 시그니처 (B·C가 의존하는 계약)

**catalog.ts (WP-A)**
```ts
export const GRID_SIZE = 16
export const LIMITS: { maxNodes: 300; maxEdges: 600; maxOperations: 100; promptMaxLength: 16000; displayNameMaxLength: 200; capabilitiesMaxLength: 2000; maxProjectsForAccess: 200 }
export const NODE_TYPES: Record<NodeType, NodeTypeSpec>          // §5
export const CONNECTION_RULES: ConnectionRule[]                    // §6.1
export const SINGLETON_IDS: { service: 'service'; guardrail: 'guardrail'; prompt: 'prompt'; cache: 'cache'; router: 'router'; masker: 'masker' }
export const DEFAULT_SERVICE_STATE: ServiceState                   // §7.11
export const DEFAULT_SCHEDULING: SchedulingState                   // {IMMEDIATE,5000,30000,100,33554432,10}
export const DEFAULT_CONFIG: FlowConfigState                       // {service: DEFAULT_SERVICE_STATE, scheduling: DEFAULT_SCHEDULING, targets: [], accessProjectIds: []}
export const FIELD_LABELS: Record<string, string>                  // service/scheduling/target 필드 → 한국어 라벨
export const GUARDRAIL_STRICTNESS: Record<GuardrailAction, number> // ALLOW 0, REDACT_RESTORE 1, REDACT 2, LOCAL_ONLY 3, BLOCK 4
export const BOUNDARY_RANK: Record<DataBoundaryPolicy, number>     // INTERNAL_ONLY 0, APPROVED_EXTERNAL_NO_RETENTION 1, EXTERNAL_ALLOWED 2
export const SERVICE_KEY_PATTERN: RegExp                           // /^[a-z0-9][a-z0-9-]{0,119}$/
export function nodeSpec(type: NodeType): NodeTypeSpec
export function portSpec(type: NodeType, portId: string): PortSpec | undefined
export function targetNodeId(targetId: string): string             // 'target:<id>'
export function projectNodeId(projectId: string): string           // 'project:<id>'
export function deploymentNodeId(deploymentId: string): string     // 'deployment:<id>'
export function hostNodeIdFor(deployment: DeploymentInfo): string | null // 'endpoint:<id>' | 'provider:<id>' | null
export function edgeId(from: PortRef, to: PortRef): string         // `${from.nodeId}/${from.portId}->${to.nodeId}/${to.portId}`
export function toneOf(enabled: boolean, healthStatus: string): HealthTone
export function describeNode(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): NodeView   // §5.4
export function nodeFacts(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): FactRow[]
export function describeEdge(edge: FlowEdge): { label: string; meaning: string }                         // §6.1
```

**layout.ts (WP-A)**
```ts
export const NODE_WIDTH = 232, HEADER_HEIGHT = 40, PORT_ROW_HEIGHT = 24, SUMMARY_HEIGHT = 56, NODE_PADDING = 8, COLLAPSED_HEIGHT = 40
export const COLUMN_WIDTH = 300, ROW_GAP = 24, MIN_ZOOM = 0.2, MAX_ZOOM = 2
export const LAYOUT_NODE_ID_PATTERN: RegExp   // /^(service|guardrail|prompt|cache|router|masker|(project|target|deployment|endpoint|provider):[A-Za-z0-9-]{1,64})$/
export function nodeSize(node: FlowNode): Size
export function nodeRect(node: FlowNode): Rect
export function portAnchor(node: FlowNode, portId: string): Point
export function bezierPath(from: Point, to: Point): string
export function graphBounds(graph: FlowGraph): Rect
export function fitViewport(bounds: Rect, size: Size, padding?: number): Viewport   // padding 기본 48, zoom ≤ 1
export function zoomAt(viewport: Viewport, screenPoint: Point, factor: number): Viewport
export function screenToWorld(point: Point, viewport: Viewport): Point
export function worldToScreen(point: Point, viewport: Viewport): Point
export function clampZoom(zoom: number): number
export function snapToGrid(value: number, grid?: number): number
export function nodesInRect(graph: FlowGraph, rect: Rect): string[]
export function autoLayout(graph: FlowGraph, snapshot?: ServiceFlowSnapshot): FlowGraph
export function placeNewNode(graph: FlowGraph, nodeId: string): FlowGraph
export function applyLayout(graph: FlowGraph, layout: FlowLayout | null, snapshot?: ServiceFlowSnapshot): FlowGraph
export function extractLayout(graph: FlowGraph, viewport: Viewport): FlowLayout
export function sanitizeLayout(raw: unknown): FlowLayout | null
```

**graphOps.ts (WP-A)**
```ts
export function createSkeletonGraph(serviceId: string | null): FlowGraph
export function findNode(graph: FlowGraph, nodeId: string): FlowNode | undefined
export function edgesOf(graph: FlowGraph, nodeId: string): FlowEdge[]
export function canConnect(graph: FlowGraph, from: PortRef, to: PortRef, snapshot: ServiceFlowSnapshot): ConnectCheck
export function nearestCompatiblePort(graph: FlowGraph, from: PortRef, point: Point, radius: number, snapshot: ServiceFlowSnapshot): PortRef | null
export function applyCommand(graph: FlowGraph, command: FlowCommand, snapshot: ServiceFlowSnapshot): CommandResult
export function syncHostNodes(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowGraph
export function renameTargetNodes(graph: FlowGraph, createdTargetIds: Record<string, string>): FlowGraph
export function sortGraph(graph: FlowGraph): FlowGraph   // nodes by (column, id), edges by id
```

**decompile.ts / compile.ts / validate.ts / diff.ts / history.ts (WP-A)**
```ts
// decompile.ts
export function syncGraphToConfig(graph: FlowGraph, config: FlowConfigState, snapshot: ServiceFlowSnapshot): FlowGraph
export function decompile(snapshot: ServiceFlowSnapshot, layout: FlowLayout | null): FlowGraph
// compile.ts
export function compile(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowConfigState
export function normalizePrompt(value: string | null | undefined): string | null
export function canonicalCapabilities(json: string): string
export function sameValue(a: unknown, b: unknown): boolean
// validate.ts
export function validateGraph(graph: FlowGraph, base: ServiceFlowSnapshot): ValidationIssue[]
export function issueCountsByNode(issues: ValidationIssue[]): Record<string, { error: number; warning: number; info: number }>
// diff.ts
export function planChanges(input: PlanInput): FlowPlan
export function mergeConfigs(base: FlowConfigState, local: FlowConfigState, remote: FlowConfigState): { merged: FlowConfigState; conflicts: FlowConflict[]; adopted: Record<string, string> }
export function rebase(input: RebaseInput): RebaseResult
export function computeNodeChanges(graph: FlowGraph, base: ServiceFlowSnapshot): Record<string, NodeChangeKind>
export function configsEqual(a: FlowConfigState, b: FlowConfigState): boolean
export function stableStringify(value: unknown): string
// history.ts
export interface HistoryState<T> { past: T[]; future: T[]; limit: number }
export function createHistory<T>(limit?: number): HistoryState<T>                     // limit 기본 100
export function recordHistory<T>(history: HistoryState<T>, previous: T): HistoryState<T>  // future 비움
export function undoHistory<T>(history: HistoryState<T>, current: T): { history: HistoryState<T>; state: T } | null
export function redoHistory<T>(history: HistoryState<T>, current: T): { history: HistoryState<T>; state: T } | null
```

**flowSnapshot.ts / flowApi.ts / execute.ts / draftStore.ts (WP-C)**
```ts
// flowSnapshot.ts (pure)
export interface RawFlowData {
  organizationId: string; serviceId: string | null; loadedAt: string; unavailable: string[]
  services: unknown[]; targets: unknown[] | null; scheduling: unknown | null; dataProtection: unknown | null
  endpoints: unknown[]; endpointDeployments: Record<string, unknown[]>
  providers: unknown[]; providerModels: Record<string, unknown[]>
  projects: unknown[] | null; projectAccess: Record<string, unknown[] | null>; maskers: unknown[]
}
export class FlowLoadError extends Error { readonly code: 'SERVICE_NOT_FOUND' | 'REQUIRED_READ_FAILED' }
export function buildSnapshot(raw: RawFlowData): ServiceFlowSnapshot
export function safeAddressLabel(url: string): string   // 'http://user:pw@h:1/v1?x' → 'http://h:1/v1', 실패 시 '주소 확인 필요'
// flowApi.ts
export function createAdminTransport(auth: AdminAuth): FlowTransport
export function loadFlowSnapshot(organizationId: string, serviceId: string | null, auth: AdminAuth): Promise<ServiceFlowSnapshot>
export function loadFlowLayout(serviceId: string, auth: AdminAuth): Promise<{ available: boolean; layout: FlowLayout | null; revision: number }>
export function saveFlowLayout(serviceId: string, layout: FlowLayout, auth: AdminAuth): Promise<{ revision: number }>
export function currentUserId(): string   // sessionStorage 'aiconnect.user'.id, 플랫폼 토큰 세션이면 'platform-token' (읽기 전용 접근)
// execute.ts (pure + injected transport)
export function operationRequest(op: FlowOperation, ctx: { serviceId: string | null; createdTargetIds: Record<string, string> }): { method: HttpMethod; path: string; body?: unknown }
export function executePlan(plan: FlowPlan, transport: FlowTransport, options: { serviceId: string | null; onProgress?: (results: OperationResult[]) => void }): Promise<ExecutionReport>
// draftStore.ts (pure, module memory only)
export interface FlowDraft { userId: string; organizationId: string; serviceId: string | null; base: ServiceFlowSnapshot; graph: FlowGraph; viewport: Viewport; savedAt: number }
export const MAX_DRAFTS = 10
export function draftKey(userId: string, organizationId: string, serviceId: string | null): string
export function stashDraft(draft: FlowDraft): void
export function takeDraft(userId: string, organizationId: string, serviceId: string | null): FlowDraft | null
export function hasDraft(userId: string, organizationId: string, serviceId: string | null): boolean
export function clearDrafts(): void
export function installDraftAutoClear(target: { addEventListener(type: string, listener: (event: Event) => void): void; removeEventListener(type: string, listener: (event: Event) => void): void }): () => void
```

### 4.4 노드·엣지 ID 규칙 (결정적이며 레이아웃 키로 사용)

| 노드 | id | refId |
|---|---|---|
| service, guardrail, prompt, cache, router, masker | 타입명 그대로(`'service'` 등) | null |
| project | `project:<projectId>` | projectId |
| target(기존) | `target:<targetId>` | targetId |
| target(신규) | `target:new-<nextSeq>` | null |
| deployment | `deployment:<deploymentId>` | deploymentId |
| endpoint / provider | `endpoint:<id>` / `provider:<id>` | id |

엣지 id는 `${from.nodeId}/${from.portId}->${to.nodeId}/${to.portId}`이다. 그래프 배열은 `sortGraph`로 정렬한다(노드는 (column, id) 순, 엣지는 id 순). 이렇게 하면 decompile 결과가 결정적이 되고 DOM 탭 순서도 열 순서를 따른다.

---

## 5. 노드 카탈로그

### 5.1 노드 타입 표

| type | 라벨 | category(색 토큰) | icon | column | 싱글턴 | 삭제 | 팔레트 | 읽기 API | 쓰기 API | v1 편집 |
|---|---|---|---|---|---|---|---|---|---|---|
| project | 프로젝트 | entry(`--info`) | ⌘ | 0 | ✗ | ✓(접근 해제) | ✓ | organizations/{org}/projects, projects/{pid}/service-access | service-access POST/DELETE | 접근만 |
| service | 논리 서비스 | entry(`--info`) | ▣ | 1 | ✓ | ✗ | ✗ | organizations/{org}/services | services POST / PATCH | ✓ |
| masker | 마스킹 워커 | pipeline(`--accent-strong`) | ◍ | 1(서비스 아래) | ✓(최대 1) | ✓(해제) | ✓ | masker-deployments | PATCH maskerDeploymentId/clear | ✓ |
| guardrail | 가드레일·DLP | pipeline | ⛨ | 2 | ✓ | ✗ | ✗ | services(guardrailAction), services/{id}/data-protection(읽기) | PATCH guardrailAction, maskPersonNames | ✓ |
| prompt | 프롬프트·파라미터 | pipeline | ✎ | 3 | ✓ | ✗ | ✗ | services | PATCH prompt/temperature/reasoning/fast | ✓ |
| cache | 응답 캐시 | pipeline | ⟲ | 4 | ✓ | ✗ | ✗ | services | PATCH responseCache* | ✓ |
| router | 라우터 | routing(`--warning`) | ⑂ | 5 | ✓ | ✗ | ✗ | services, services/{id}/scheduling | PATCH failover/retry/degraded, PATCH scheduling | ✓ |
| target | Target | routing | ↝ | 6 | ✗ | ✓ | ✓ | services/{id}/targets | targets POST/PATCH/DELETE | ✓ |
| deployment | 모델 Deployment | infra(`--muted`) | ◇ | 7 | ✗ | ✓(바인딩 없을 때만, 캔버스에서만 제거) | ✓ | runtime-endpoints/{id}/deployments, external-providers/{id}/models | — | ✗ |
| endpoint | Local Runtime | infra | ▤ | 8 | ✗ | ✗(자동) | ✗ | organizations/{org}/runtime-endpoints | — | ✗ |
| provider | External Provider | infra | ☁ | 8 | ✗ | ✗(자동) | ✗ | organizations/{org}/external-providers | — | ✗ |

### 5.2 포트 표 (in은 왼쪽, out은 오른쪽, 행 순서대로)

| type | in 포트 (id:type, multiple) | out 포트 (id:type, multiple) |
|---|---|---|
| project | — | `access:access`(✓) |
| service | `access:access`(✓) | `request:request`(✗) |
| masker | — | `masker:masker`(✗) |
| guardrail | `request:request`(✗), `masker:masker`(✗) | `request:request`(✗) |
| prompt | `request:request`(✗) | `request:request`(✗) |
| cache | `request:request`(✗) | `request:request`(✗) |
| router | `request:request`(✗) | `targets:route`(✓) |
| target | `route:route`(✗) | `binding:binding`(✗) |
| deployment | `binding:binding`(✓) | `host:host`(✗) |
| endpoint | `host:host`(✓) | — |
| provider | `host:host`(✓) | — |

포트 라벨(한국어): access "접근", request "요청", masker "마스커", targets "Target", route "라우팅", binding "Deployment", host "실행 위치".

### 5.3 속성(필드) 스펙 — `NODE_TYPES[type].fields`

| 노드 | key | 라벨 | kind | 제약·옵션 | 도메인 매핑 |
|---|---|---|---|---|---|
| service | serviceKey | Service Key (model 값) | text | `newServiceOnly`, maxLength 120, 패턴 §6.5 | service.serviceKey(생성 전용) |
| service | displayName | 표시 이름 | text | 필수, maxLength 200 | displayName |
| service | enabled | 서비스 활성화 | boolean | | enabled |
| service | dataBoundaryPolicy | 데이터 경계 | select | INTERNAL_ONLY "사내 GPU 전용", APPROVED_EXTERNAL_NO_RETENTION "외부 비보존 GPU 허용", EXTERNAL_ALLOWED "외부 AI 허용" | dataBoundaryPolicy |
| service | requiredCapabilitiesJson | 필수 Capability | capabilities | `CapabilityPicker` 재사용 | requiredCapabilitiesJson |
| service | currency | 통화 | select | KRW, USD | currency |
| service | inputPricePerMillion | 입력 단가 / 1M | number | min 0, step 0.000001 | inputPricePerMillion |
| service | outputPricePerMillion | 출력 단가 / 1M | number | min 0, step 0.000001 | outputPricePerMillion |
| guardrail | guardrailAction | 처리 정책 | select | ALLOW "개인정보 허가 · 외부 전송 허용", REDACT_RESTORE "마스킹 전송 + 응답 원본 복원", REDACT "마스킹 전송 · 가명화 유지", LOCAL_ONLY "개인정보 검사 · 외부 금지", BLOCK "민감정보 차단" | guardrailAction |
| guardrail | maskPersonNames | 한국어 성명 가명화 | boolean | | maskPersonNames |
| masker | deploymentId | 마스킹 워커 Deployment | masker-select | catalog.maskers, 부적격은 disabled + trustReason | maskerDeploymentId(마스커 엣지가 연결된 경우) |
| prompt | systemPromptTemplate | 시스템 프롬프트 (변경 시 버전 증가) | textarea | maxLength 16000, ''=없음 | systemPromptTemplate |
| prompt | temperaturePolicy | Temperature 처리 | select | REQUEST "요청값 기준", FIXED "고정값", OMIT "요청에서 제거" | temperaturePolicy |
| prompt | temperatureValue | 고정 Temperature (0–2) | number | min 0, max 2, step 0.1, nullable, visibleWhen FIXED | temperatureValue |
| prompt | reasoningEffort | 추론 수준 · 외부 API | select | REQUEST, NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX | reasoningEffort |
| prompt | openAiFastMode | Fast mode · 외부 API | boolean | | openAiFastMode |
| cache | responseCacheEnabled | 동일 질문 캐싱 | boolean | | responseCacheEnabled |
| cache | responseCacheTtlSeconds | TTL(초) | integer | min 10, max 86400, visibleWhen enabled=true | responseCacheTtlSeconds |
| router | failoverPolicy | Failover 정책 | select | STRICT, COMPATIBLE, DEGRADED | failoverPolicy |
| router | retryPolicy | Retry 정책 | select | SAFE, AGGRESSIVE | retryPolicy |
| router | allowDegraded | Degraded 허용 | boolean | | allowDegraded |
| router | schedulingMode | 배분 방식 | select | IMMEDIATE "즉시 다음 Target", LOCAL_FIRST_WAIT "로컬 우선 대기", LOCAL_ONLY_QUEUE "로컬 전용 대기" | scheduling.mode |
| router | localWaitMs | 외부 전환 전 로컬 대기(ms) | integer | 0–60000 step 100, visibleWhen LOCAL_FIRST_WAIT | scheduling.localWaitMs |
| router | maxWaitMs | 전체 최대 대기(ms) | integer | 100–60000 step 100, visibleWhen ≠IMMEDIATE | scheduling.maxWaitMs |
| router | maxQueuedRequests | 서비스 최대 대기 요청 | integer | 1–500, visibleWhen ≠IMMEDIATE | scheduling.maxQueuedRequests |
| router | maxQueuedPerKey | API 키별 최대 대기 | integer | 1–500, visibleWhen ≠IMMEDIATE | scheduling.maxQueuedPerKey |
| router | maxQueuedBytes | 대기 본문 합계 한도(bytes) | integer | 1024–134217728, visibleWhen ≠IMMEDIATE | scheduling.maxQueuedBytes |
| target | `$binding` | Deployment | deployment-binding | catalog.deployments, `bindTarget` 명령 발행 | targets[].deploymentId |
| target | priority | 우선순위(작을수록 먼저) | integer | min 1 | priority |
| target | weight | 가중치 | integer | min 1 | weight |
| target | enabled | Target 활성화 | boolean | | enabled |
| target | degraded | Degraded Target | boolean | | degraded |
| target | maxConcurrencyOverride | 동시성 재정의 | integer | min 1, nullable | maxConcurrencyOverride |
| target | followModelChanges | 모델 변경 자동 추적 | boolean | | followModelChanges |
| project | `$access` | 이 서비스 호출 허용 | access-toggle | `setAccess` 명령, `accessComplete=false`면 disabled | accessProjectIds |

`visibleWhen.oneOf` 예: router `localWaitMs` → `{key:'schedulingMode', oneOf:['LOCAL_FIRST_WAIT']}`, `maxWaitMs` 등 → `oneOf:['LOCAL_FIRST_WAIT','LOCAL_ONLY_QUEUE']`. 신규 노드 기본 props: target `{priority: max(기존 priority)+1(없으면 1), weight:100, enabled:true, degraded:false, maxConcurrencyOverride:null, followModelChanges: 외부 Deployment면 false 아니면 true}`, masker `{deploymentId:null}`. project/deployment/endpoint/provider는 props가 `{}`이다.

### 5.4 `describeNode` 규칙 (title / subtitle / badges / summary / tone)

| type | title | subtitle | badges | summary(≤2) | tone |
|---|---|---|---|---|---|
| project | 카탈로그 name(없으면 '알 수 없는 프로젝트') | status | 비ACTIVE면 '중지됨' | 접근 엣지 유무에 따라 '접근 허용됨'/'미연결', 데이터 경계 | ACTIVE면 healthy, 아니면 muted, 카탈로그에 없으면 error |
| service | displayName 또는 '(이름 없음)' | `model: ${serviceKey \|\| '새 서비스'}` | 비활성 '비활성', 초안 '초안' | 데이터 경계 짧은 라벨, `Prompt v${promptVersion}` | 활성이면 healthy, 아니면 muted |
| guardrail | '가드레일 · DLP' | 정책 라벨 | 성명 가명화 '성명 가명화' | 마스커 이름 또는 '내장 정규식 엔진', DLP 모드(읽기 정보) | ALLOW면 muted, 아니면 healthy |
| masker | 마스커 displayName 또는 '워커 미선택' | sourceName | 부적격 '사용 불가' | trustReason | 적격이면 healthy, 미선택이면 warning, 부적격이면 error |
| prompt | '프롬프트 · 파라미터' | 프롬프트가 있으면 `${len}자`, 없으면 '시스템 프롬프트 없음' | fast면 'Fast' | `temperature ${policy}`, `reasoning ${effort}` | healthy |
| cache | '응답 캐시' | 사용 시 `TTL ${n}초`, 아니면 '사용 안 함' | | | 사용이면 healthy, 아니면 muted |
| router | '라우터' | `${failover} · ${retry}` | Degraded 허용 'Degraded 허용' | `배분 ${mode}`, `활성 Target ${n}개` | 활성 Target이 0개면 warning, 아니면 healthy |
| target | 바인딩된 Deployment displayName 또는 '미연결 Target' | `P${p} · W${w} (${share}%)`(같은 priority의 활성 Target 가중치 합 대비) | 비활성 '중지', degraded 'Degraded', 모델 고정 '모델 고정', 신규 '신규' | providerModelId | 미연결이면 error, 비활성이면 muted, 아니면 Deployment 톤 |
| deployment | displayName(카탈로그에 없으면 '알 수 없는 Deployment') | providerModelId | 외부 '외부', 로컬 미로드 '미로드', 비활성 '비활성' | 바인딩 Target 수, 컨텍스트 길이 | `toneOf(enabled, healthStatus)`, 로컬 미로드는 warning, 없으면 error |
| endpoint | displayName | `${runtimeType} · ${addressLabel}` | 비활성 '비활성' | | toneOf |
| provider | displayName | providerType | '무보존 확인' / '키 미설정' | | 키 미설정이면 error, 아니면 toneOf |

`toneOf`: 비활성이면 muted. HEALTHY/READY/ACTIVE/LOADED는 healthy, UNHEALTHY/FAILED/ERROR는 error, DRAINING/DEGRADED/RECOVERING/LOADING/STANDBY/SUSPECT는 warning, 나머지는 unknown이다.

---

## 6. 연결 규칙 & 검증

### 6.1 연결 규칙 (`CONNECTION_RULES`)과 엣지 의미

| from (type.port) | to (type.port) | kind | 사용자 편집 | 도메인 의미 (`describeEdge`) |
|---|---|---|---|---|
| project.access | service.access | access | ✓ | `project_service_access` 행: 이 프로젝트의 API 키가 이 서비스의 `model`을 호출할 수 있음 |
| service.request | guardrail.request | pipeline | ✗(고정) | 요청 처리 순서(데이터 없음) |
| guardrail.request | prompt.request | pipeline | ✗ | 〃 |
| prompt.request | cache.request | pipeline | ✗ | 〃 |
| cache.request | router.request | pipeline | ✗ | 〃 |
| masker.masker | guardrail.masker | masker | ✓ | `llm_service.masker_deployment_id` |
| router.targets | target.route | route | ✗(자동) | `service_target.service_id`(이 서비스의 Target) |
| target.binding | deployment.binding | binding | ✓ | `service_target.deployment_id` |
| deployment.host | endpoint.host | host | ✗(자동) | `model_deployment.runtime_endpoint_id`(읽기 전용) |
| deployment.host | provider.host | host | ✗(자동) | `model_deployment.external_provider_id`(읽기 전용) |

**특수 규칙(편의)**: `router.targets → deployment.binding` 연결 요청은 엣지를 만들지 않는다. 대신 "Target 노드 삽입"(신규 target + route 엣지 + binding 엣지)으로 해석한다. 같은 Deployment에 바인딩된 Target이 이미 있으면 거부한다.

### 6.2 `canConnect` 판정 순서와 사유 메시지

1. from/to 노드가 없으면 `'연결할 노드를 찾을 수 없습니다.'`
2. 같은 노드끼리는 `'같은 노드끼리 연결할 수 없습니다.'`
3. from이 out 포트, to가 in 포트가 아니면 `'출력 포트에서 입력 포트로만 연결할 수 있습니다.'`
4. 특수 규칙(router→deployment)에 해당하면 중복 바인딩 여부를 검사해 ok 또는 `'이 Deployment에는 이미 Target이 있습니다(서비스당 Deployment 1개).'`를 반환한다.
5. 규칙 표에 없는 조합은 `'이 포트 조합은 연결할 수 없습니다.'`
6. `userEditable=false` 규칙은 `'자동으로 관리되는 연결입니다.'`
7. 같은 엣지가 이미 있으면 `'이미 연결되어 있습니다.'`
8. 통과하면 `{ok:true, replaces}`를 반환한다. `replaces`는 단일 포트(`multiple=false`)에 이미 있는 엣지 id이며, 연결 시 교체된다(target.binding의 out 쪽, guardrail.masker의 in 쪽).

**순환 방지**: 포트 타입이 열 순서대로만 이어지고 모든 규칙이 왼쪽 열에서 오른쪽 열로 향하므로(masker는 같은 열 1에서 열 2로), 구조상 순환이 불가능하다. 그래도 손상 상태를 막기 위해 `validateGraph`가 DFS로 `CYCLE_DETECTED`를 검사한다.

### 6.3 명령 의미 (`applyCommand`) — 항상 새 그래프 객체를 반환하고 `sortGraph`를 적용한다

| 명령 | 동작 | 실패(`error`, 그래프 불변) |
|---|---|---|
| moveNodes | x/y를 설정한다(스냅은 캔버스가 이미 적용한 값) | 없는 id는 무시 |
| addNode project | refId가 catalog.projects에 있어야 한다. 이미 있으면 변경 없이 select만 반환한다. position이 없으면 `placeNewNode` | '알 수 없는 프로젝트입니다.' |
| addNode deployment | refId가 catalog.deployments에 있어야 한다. 이미 있으면 select. 추가 후 `syncHostNodes` | '알 수 없는 Deployment입니다.' |
| addNode target | `target:new-${nextSeq}`, nextSeq+1. 기본 props(§5.3)와 router→target route 엣지. refId(=deploymentId)가 있으면 deployment 노드를 보장하고 binding까지 연결 | 중복 바인딩이면 거부 |
| addNode masker | 마스커가 없을 때만 추가한다. guardrail.masker가 비어 있으면 자동 연결 | '마스킹 워커는 하나만 둘 수 있습니다.' |
| removeItems | locked/삭제 불가 노드와 엣지는 건너뛴다(하나라도 건너뛰면 error 메시지, 나머지는 수행). 노드를 지우면 연결 엣지도 함께 지운다. 바인딩이 있는 deployment 노드는 지우지 않는다. 이후 `syncHostNodes` | '고정 노드는 삭제할 수 없습니다.' / 'Target이 연결된 Deployment는 제거할 수 없습니다.' |
| connect | `canConnect`. 특수 규칙이면 target 삽입. 아니면 replaces 제거 후 엣지 추가. 신규 target이 외부 Deployment에 바인딩되면 followModelChanges=false | canConnect 사유 |
| updateProps | `NODE_TYPES[type].fields`에 있는 키만 반영한다. 타입은 강제 변환한다(number/integer는 `Number`, 빈 문자열이고 nullable이면 null. boolean, string). `newServiceOnly` 필드는 serviceId가 있으면 무시 | 모르는 노드 |
| bindTarget | deployment 노드를 보장하고 기존 binding을 교체한다. 신규 target이고 외부 Deployment면 followModelChanges=false | 알 수 없는 Deployment |
| setAccess | granted면 project→service access 엣지를 보장하고, 아니면 제거한다 | — |
| toggleCollapse | collapsed 설정 | — |
| autoLayout | `autoLayout(graph, snapshot)` | — |

선택 반환: addNode는 새 노드를, connect 특수 규칙은 새 target을 선택한다. removeItems는 선택을 비운다.

### 6.4 `syncHostNodes`

각 deployment 노드에 대해 catalog의 호스트(`hostNodeIdFor`)가 있으면 호스트 노드와 locked host 엣지를 보장한다. 연결된 deployment가 없는 endpoint/provider 노드는 제거한다. 카탈로그에 없는 Deployment는 호스트 없이 둔다.

### 6.5 검증 코드 (`validateGraph(graph, base)`) — `message`는 아래 한국어 그대로

| code | 심각도 | 조건 | 메시지 |
|---|---|---|---|
| GRAPH_TOO_LARGE | error | 노드 > 300 또는 엣지 > 600 | 캔버스는 노드 300개, 연결 600개까지 지원합니다. |
| STRUCTURE_BROKEN | error | 싱글턴 5종 또는 pipeline 엣지 4개 누락 | 고정 파이프라인 구조가 손상되었습니다. 되돌리기 또는 새로고침하세요. |
| CYCLE_DETECTED | error | 방향 그래프에 순환 존재 | 순환 연결은 허용되지 않습니다. |
| SERVICE_KEY_INVALID | error | 신규이고 `SERVICE_KEY_PATTERN` 불일치 | Service Key는 소문자·숫자·하이픈만 쓸 수 있고 영숫자로 시작해야 합니다(최대 120자). |
| SERVICE_KEY_TAKEN | error | 신규이고 catalog.services에 같은 key | 이 조직에 이미 있는 Service Key입니다. |
| DISPLAY_NAME_REQUIRED | error | trim 후 빈 값 | 표시 이름을 입력하세요. |
| DISPLAY_NAME_TOO_LONG | error | > 200자 | 표시 이름은 200자 이하여야 합니다. |
| CAPABILITIES_INVALID | error | JSON 문자열 배열이 아니거나 > 2000자 | 필수 Capability 형식이 올바르지 않습니다. |
| PRICE_INVALID | error | 유한수가 아니거나 < 0 | 단가는 0 이상의 숫자여야 합니다. |
| TEMPERATURE_VALUE_REQUIRED | error | FIXED이고 null | 고정 Temperature 값을 입력하세요. |
| TEMPERATURE_VALUE_RANGE | error | FIXED이고 0–2 범위 밖 | Temperature는 0에서 2 사이여야 합니다. |
| PROMPT_TOO_LONG | error | > 16000자 | 시스템 프롬프트는 16,000자 이하여야 합니다. |
| CACHE_TTL_RANGE | error | 정수가 아니거나 10–86400 범위 밖 | 캐시 TTL은 10–86,400초 사이 정수여야 합니다. |
| SCHEDULING_RANGE | error | §5.3 범위 위반(필드별 issue) | 배분·대기열 값이 허용 범위를 벗어났습니다. |
| SCHEDULING_LOCAL_WAIT_EXCEEDS_MAX | error | LOCAL_FIRST_WAIT이고 localWaitMs > maxWaitMs | 로컬 대기 시간은 전체 최대 대기 시간을 넘을 수 없습니다. |
| SCHEDULING_PER_KEY_EXCEEDS_TOTAL | error | maxQueuedPerKey > maxQueuedRequests | API 키별 대기 한도는 서비스 전체 한도를 넘을 수 없습니다. |
| TARGET_UNBOUND | error | binding 엣지 없는 target | Deployment에 연결되지 않은 Target이 있습니다. |
| TARGET_NUMBER_INVALID | error | priority/weight가 1 이상 정수가 아님, maxConcurrencyOverride가 null이 아니면서 1 이상 정수가 아님 | Target 우선순위·가중치·동시성은 1 이상의 정수여야 합니다. |
| TARGET_CONCURRENCY_CLEAR_UNSUPPORTED | error | base에서 override가 non-null인데 desired가 null | 서버가 동시성 재정의 해제를 지원하지 않습니다. Target을 삭제 후 다시 추가하세요. |
| DUPLICATE_DEPLOYMENT_TARGET | error | 같은 Deployment에 target 2개 이상 | 한 서비스에서 같은 Deployment에는 Target을 하나만 둘 수 있습니다. |
| UNKNOWN_DEPLOYMENT | error(신규·재연결) / warning(기존 무변경) | 카탈로그에 없는 Deployment에 바인딩 | 등록 정보를 찾을 수 없는 Deployment입니다. |
| MASKER_NOT_SELECTED | error | 연결된 마스커의 deploymentId가 비어 있음 | 마스킹 워커 Deployment를 선택하세요. |
| MASKER_NOT_ELIGIBLE | error | deploymentId가 catalog.maskers에서 eligible이 아님(마스커 목록 조회 실패 시 warning `MASKER_UNVERIFIED`) | 신뢰 마스킹 워커로 사용할 수 없는 Deployment입니다. |
| MASKER_DISCONNECTED | warning | 마스커 노드가 가드레일에 미연결 | 연결되지 않은 마스킹 워커는 적용되지 않습니다. |
| UNKNOWN_PROJECT | error | 접근 집합을 바꾸는 project 노드가 카탈로그에 없음 | 알 수 없는 프로젝트입니다. |
| ACCESS_EDIT_UNAVAILABLE | error | 접근 집합 변경 && `!base.accessComplete` | 프로젝트 접근 정보를 모두 불러오지 못해 접근 변경을 적용할 수 없습니다. |
| NO_ENABLED_TARGETS | warning | 서비스 활성 && 활성·바인딩 target 0개 | 활성 Target이 없어 이 서비스 요청은 모두 실패합니다. |
| DEPLOYMENT_UNAVAILABLE | warning | 바인딩 Deployment 비활성/UNHEALTHY/(로컬) 미로드 | 현재 사용할 수 없는 Deployment입니다(Target은 유지됩니다). |
| EXTERNAL_TARGET_BLOCKED_BY_BOUNDARY | warning | 서비스 경계 ≠ EXTERNAL_ALLOWED && 외부 Deployment 바인딩 | 서비스 데이터 경계 정책 때문에 이 외부 Target은 실행되지 않습니다. |
| GUARDRAIL_LOCAL_ONLY_WITHOUT_LOCAL_TARGET | warning | LOCAL_ONLY && 활성 로컬 target 0개 | 민감정보 요청을 처리할 사내 Local Target이 없습니다. |
| FOLLOW_MODEL_ON_EXTERNAL | info | 외부 Deployment target의 followModelChanges=true | 외부 Deployment에는 모델 자동 추적이 적용되지 않습니다. |
| SERVICE_DISABLED | info | 서비스 비활성 | 서비스가 비활성 상태라 모든 요청이 거부됩니다. |
| PROJECT_INACTIVE | info | 접근 연결된 프로젝트의 status ≠ ACTIVE | 비활성 프로젝트입니다. |
| CATALOG_PARTIAL | info | `base.unavailable` 비어 있지 않음 | 일부 자원 정보를 불러오지 못했습니다. |

`nodeId`, `edgeId`, `field`를 채워 인스펙터와 노드 배지가 위치를 표시할 수 있게 한다. **error가 하나라도 있으면 Plan의 `blockingIssues`가 되어 적용할 수 없다.**

---

## 7. Compile / Diff / Apply 알고리즘

### 7.1 전체 상태 흐름

```
load:   B = loadFlowSnapshot(); layout = loadFlowLayout(); graph = decompile(B, layout)
edit:   graph' = applyCommand(graph, cmd, B)  → history.record(graph)
local:  issues = validateGraph(graph, B); preview = planChanges({base:B, desired:compile(graph,B), remote:B, issues})
        → 상태바 "변경 N건"(preview.operations.length), 노드 배지(computeNodeChanges)
plan:   R1 = loadFlowSnapshot(); P1 = planChanges({base:B, desired, remote:R1, issues}) → 검토 모달
apply:  R2 = loadFlowSnapshot(); P2 = planChanges({... remote:R2})
        P2.fingerprint ≠ P1.fingerprint 또는 충돌/차단 이슈 → 실행하지 않고 P2를 다시 보여 줌
        같으면 report = executePlan(P2) (순차, 첫 실패에서 중단)
sync:   R3 = loadFlowSnapshot(report.serviceId ?? B.serviceId)
        {graph, conflicts} = rebase({graph, base:B, remote:R3, createdTargetIds: report.createdTargetIds})
        B = R3; history 초기화; 성공 작업이 1건 이상이고 레이아웃 API를 쓸 수 있으면 saveFlowLayout 1회
```

### 7.2 decompile / syncGraphToConfig

`decompile(snapshot, layout)`:
1. `g = createSkeletonGraph(snapshot.serviceId)`로 싱글턴 5종과 locked pipeline 엣지 4개를 만든다.
2. `g = syncGraphToConfig(g, snapshot.config, snapshot)`
3. layout이 null이면 `autoLayout(g, snapshot)`, 있으면 `applyLayout(g, layout, snapshot)`를 적용한다. layout에 있는 id만 좌표와 접힘 상태를 덮어쓰고, 나머지 노드는 `placeNewNode`로 배치한다. 모르는 id는 무시한다.

`syncGraphToConfig(graph, config, snapshot)`는 그래프의 컴파일 결과가 `config`와 같아지게 만들면서 **추가 노드(미바인딩 target, 미연결 project, 바인딩 없는 deployment)와 좌표는 보존**한다.
1. 싱글턴과 pipeline 엣지가 없으면 보장한다.
2. 싱글턴 props를 설정한다. 프롬프트의 null은 `''`로 넣는다. router의 scheduling 필드 매핑은 §5.3을 따른다.
3. 마스커: `maskerDeploymentId`가 있으면 `masker` 노드(props.deploymentId)와 masker 엣지를 보장하고, null이면 masker 노드와 엣지를 제거한다.
4. Target: config의 각 t에 대해 노드 `t.key`를 보장한다(refId=t.id, props). router→target route 엣지, `deployment:<t.deploymentId>` 노드, binding 엣지를 보장하고 기존 binding은 교체한다. **binding이 있는데 config에 key가 없는 target 노드는 제거한다**(미바인딩 target 노드는 유지).
5. 접근: `accessProjectIds`마다 project 노드와 access 엣지를 보장하고, 목록에 없는 access 엣지를 제거한다(project 노드는 유지).
6. `syncHostNodes`를 적용하고, 새로 만든 노드는 생성 순서대로 `placeNewNode`로 배치한 뒤 `graph.serviceId = snapshot.serviceId`, `sortGraph`.

### 7.3 compile

- service: 싱글턴 props에서 읽는다. `serviceKey`는 serviceId가 있으면 `snapshot.config.service.serviceKey`(불변), 초안이면 props를 trim한 값이다. 숫자는 `Number(v)`로 변환하며 NaN도 그대로 통과시킨다(검증에서 차단).
- `systemPromptTemplate = normalizePrompt(props)`: trim 후 빈 문자열은 null.
- `temperatureValue`: FIXED일 때만 숫자(빈 값은 null)이고, 그 외에는 null.
- `requiredCapabilitiesJson = canonicalCapabilities(raw)`: 유효하면 정렬·중복 제거된 `JSON.stringify(string[])`, 아니면 원문.
- `maskerDeploymentId`: masker 노드가 있고, masker 엣지가 guardrail에 연결되어 있고, deploymentId가 비어 있지 않을 때만 값이다.
- targets: binding 엣지가 있는 target 노드만 포함한다. `{key: node.id, id: node.refId, deploymentId: 바인딩 노드 refId, ...props}`, key 순으로 정렬.
- `accessProjectIds`: service로 가는 access 엣지가 있는 project refId를 정렬·중복 제거한 목록.
- `sameValue(a,b)`: 숫자는 |a−b| < 1e-9, null과 undefined는 동일, 그 외는 `===`. 단 `requiredCapabilitiesJson`은 `canonicalCapabilities` 결과로, 프롬프트는 `normalizePrompt` 결과로 비교한다.

### 7.4 3-way 규칙 (B=base, L=local desired, R=remote)

필드 단위 판정 `resolve(b, l, r)`:
- `l ≡ b`이면 **변경 없음**(작업 없음, 원격 값 유지)
- `l ≡ r`이면 **수렴**(작업 없음, 충돌 아님)
- `r ≡ b`이면 **적용**(L 값을 전송)
- 그 외는 **충돌**(`FIELD_CONFLICT`, 적용 차단)

| 대상 | 규칙 |
|---|---|
| service 필드(serviceKey 제외) | 필드별 resolve |
| scheduling 필드 | 필드별 resolve. 적용 필드가 있으면 `body = {...R.scheduling, ...적용필드}`(전체 객체) |
| target: L에 id 있음, B에 있음 | 변경 필드가 없으면 skip. **R에 없으면** `TARGET_REMOVED_REMOTELY`. 아니면 필드별 resolve. `deploymentId`가 적용 대상이면 **교체**(createTarget(key 유지, 전체 L 속성) + deleteTarget(old id)), 아니면 patchTarget(적용 필드만) |
| target: B에 있고 L에 없음(로컬 삭제) | R에 없으면 skip(이미 삭제됨). R이 B와 다르면 `TARGET_CHANGED_REMOTELY`. 아니면 deleteTarget |
| target: L에서 id=null(신규) | R에 (B에 없던) 같은 deploymentId Target이 있으면 `DEPLOYMENT_TARGETED_REMOTELY`. 아니면 createTarget. `enabled=false`면 바로 뒤에 created 참조 patch `{enabled:false}` |
| target: R에만 있음(원격 추가) | 작업 없음(rebase가 캔버스에 반영) |
| 접근 | L≠B이고 `!base.accessComplete \|\| !remote.accessComplete`이면 blocking `ACCESS_EDIT_UNAVAILABLE`. 아니면 grant = (L∖B) 중 R에 없는 것, revoke = (B∖L) 중 R에 있는 것. 집합 연산이라 충돌이 없음 |
| 신규 서비스 | B=R=`DEFAULT_CONFIG` 기반 초안. 서비스 key가 `remote.catalog.services`에 있으면 blocking `SERVICE_KEY_TAKEN` |

`remoteDrift = !configsEqual(B.config, R.config)`이면 note "불러온 이후 다른 곳에서 변경된 항목이 있습니다. 충돌하지 않는 원격 변경은 유지됩니다."를 추가한다.

### 7.5 작업(Operation) 생성 규칙과 정확한 요청

| kind | opId | 요청 (WP-C `operationRequest`) | 본문 규칙 |
|---|---|---|---|
| createService | `create-service` | `POST /api/admin/services` | `CreateServiceBody`: organizationId + L.service의 생성 지원 필드 전체. temperatureValue는 FIXED일 때만 값, 아니면 null |
| patchService | `patch-service` | `PATCH /api/admin/services/{sid}` | 적용 필드만. maskerDeploymentId: 값이면 `{maskerDeploymentId}`, null이면 `{clearMaskerDeployment:true}`. systemPromptTemplate: 값이면 `{systemPromptTemplate}`, null이면 `{clearSystemPromptTemplate:true}`. temperaturePolicy 또는 temperatureValue가 적용되면 **둘 다** `{temperaturePolicy, temperatureValue: FIXED?v:null}`. guardrailAction은 적용될 때만. 나머지는 같은 이름으로 |
| updateScheduling | `update-scheduling` | `PATCH /api/admin/services/{sid}/scheduling` | 전체 `SchedulingState` |
| createTarget | `create-target:<key>` | `POST /api/admin/services/{sid}/targets` | `{deploymentId, priority, weight, degraded, followModelChanges, maxConcurrencyOverride?}`(null이면 키 생략) |
| patchTarget(신규 비활성화) | `disable-created-target:<key>` | `PATCH /api/admin/services/{sid}/targets/{createdId}` | `{enabled:false}` |
| patchTarget | `patch-target:<id>` | `PATCH /api/admin/services/{sid}/targets/{tid}` | 적용 필드만(maxConcurrencyOverride는 non-null만 가능) |
| deleteTarget | `delete-target:<id>` | `DELETE /api/admin/services/{sid}/targets/{tid}` | — |
| grantAccess | `grant-access:<projectId>` | `POST /api/admin/projects/{pid}/service-access` | `{serviceId: sid}` |
| revokeAccess | `revoke-access:<projectId>` | `DELETE /api/admin/projects/{pid}/service-access/{sid}` | — |

`sid`는 `ServiceRef`를 해석한 값이다(existing이면 id, created이면 create-service 응답의 `id`). `TargetRef{kind:'created',key}`는 createTarget 응답의 `id`로 해석한다. 모든 경로 세그먼트에 `encodeURIComponent`를 적용한다.

신규 서비스의 경우 `CREATE_COVERED_FIELDS = [serviceKey, displayName, failoverPolicy, retryPolicy, allowDegraded, requiredCapabilitiesJson, inputPricePerMillion, outputPricePerMillion, currency, temperaturePolicy, temperatureValue, reasoningEffort, openAiFastMode, dataBoundaryPolicy]`이다. patchService에는 적용 필드 중 이 목록에 **없는** 것(enabled, guardrailAction, maskerDeploymentId, maskPersonNames, systemPromptTemplate, responseCacheEnabled, responseCacheTtlSeconds)만 담는다.

`summary`와 `changes`(한국어):
- createService `새 논리 서비스 생성: ${serviceKey}`
- patchService `서비스 정책 변경 (${n}개 항목)`
- updateScheduling `요청 배분·대기열 정책 변경`
- createTarget `Target 추가: ${deployment 이름} (P${p} · W${w})`
- 신규 비활성화 `새 Target 비활성화: ${이름}`
- patchTarget `Target 변경: ${이름}`
- deleteTarget `Target 삭제: ${이름}`
- grantAccess `프로젝트 접근 허용: ${프로젝트명}`
- revokeAccess `프로젝트 접근 해제: ${프로젝트명}`

`changes`는 `{field, label: FIELD_LABELS[field], before, after}`이고 표시용 문자열로 포맷한다. boolean은 '사용'/'사용 안 함', null은 '없음', 프롬프트는 `${len}자`(본문은 노출하지 않음), 마스커는 Deployment 이름으로 쓴다. 이름은 remote 카탈로그에서 찾고, 없으면 id를 쓴다.

### 7.6 실행 순서 (phase, phase 안에서는 결정적 정렬)

| phase | 작업 | 정렬 |
|---|---|---|
| 0 | createService | — |
| 1 | patchService | — |
| 2 | updateScheduling | — |
| 3 | **선행 삭제**: deleteTarget 중 대상의 remote deploymentId를 이 Plan의 createTarget이 쓰는 것(교체·스왑, N7) | target id |
| 4 | createTarget, 각 작업 직후 해당 `disable-created-target` | (priority, key) |
| 5 | patchTarget | (priority, id) |
| 6 | 나머지 deleteTarget | id |
| 7 | grantAccess | projectId |
| 8 | revokeAccess | projectId |

순서의 근거: 데이터 보호 강화(서비스 정책)가 라우팅 확대보다 먼저 온다. 용량 추가가 삭제보다 먼저 온다. 신규 서비스는 접근 부여 전까지 호출될 수 없다. 사용자에게 영향이 큰 접근 해제는 마지막에 둔다. 작업이 100개를 넘으면 blocking `PLAN_TOO_LARGE`('한 번에 적용할 수 있는 작업은 100개까지입니다. 나누어 적용하세요.')를 낸다.

`fingerprint = stableStringify(operations.map(({kind, opId, body, service, target, targetId, projectId, key}) => ...))`이며, summary와 changes 텍스트는 제외한다. `stableStringify`는 객체 키를 정렬해 직렬화한다.

### 7.7 위험도와 노트

| 작업 | risk |
|---|---|
| createService | caution |
| patchService | **danger**: enabled true→false, 경계 완화(`BOUNDARY_RANK` 증가), 가드레일 완화(`GUARDRAIL_STRICTNESS` 감소), 마스커 해제 중 하나라도 해당할 때. 그 외 caution |
| updateScheduling | caution |
| createTarget | 외부 Deployment면 caution, 아니면 safe |
| disable-created-target | safe |
| patchTarget | enabled true→false면 **danger**, 아니면 safe |
| deleteTarget, revokeAccess | **danger** |
| grantAccess | caution |

노트(`PlanNote`)는 해당할 때만 추가하고 문구는 그대로 쓴다.
- guardrailAction 변경(warning): "가드레일 처리 정책을 바꾸면 서버가 서비스 범위 데이터 보호 정책을 다시 저장합니다. 모드·수준·외부 Failover 허용이 새 정책에 맞게 재설정되고 비밀정보·개인정보·금융 탐지가 켜집니다. 기밀·미디어 탐지와 사용자 정의 패턴은 유지됩니다."
- 프롬프트 변경(info): "시스템 프롬프트가 바뀌면 프롬프트 버전이 v{n}에서 v{n+1}로 올라가고 기존 응답 캐시는 더 이상 쓰이지 않습니다."
- 교체(info): "Deployment를 바꾼 Target은 새 Target을 만든 뒤 기존 Target을 삭제합니다. Target ID가 바뀝니다."
- 선행 삭제(warning): "같은 Deployment를 다시 쓰려고 기존 Target을 먼저 삭제합니다. 적용하는 동안 잠시 라우팅 대상이 줄어듭니다."
- 결과적으로 활성 Target이 0개이고 서비스가 활성이면(warning): "적용 후 활성 Target이 없어 이 서비스 요청은 실패합니다."
- 신규 서비스(info): "새 서비스는 프로젝트 접근을 허용하기 전까지 호출되지 않습니다."
- remoteDrift(info): §7.4

### 7.8 실행기 (`executePlan`)와 부분 실패

- 작업을 순차 실행하고, 시작할 때 모든 결과를 `pending`으로 둔다. 실행 중인 작업은 `running`이며, 상태가 바뀔 때마다 `onProgress(결과 배열 사본)`를 호출한다.
- createService가 성공하면 `serviceId = response.id`. createTarget이 성공하면 `createdTargetIds[op.key] = response.id`.
- `deleteTarget`/`revokeAccess`가 HTTP 404를 받으면 `already-applied`(성공으로 취급)로 기록한다.
- 그 밖의 오류에서는 `failed`(message, code, httpStatus 기록)로 기록하고 **즉시 중단**한다. 나머지는 `skipped`다. 자동 롤백은 하지 않는다.
- created 참조를 해석할 수 없으면(앞선 생성이 실패한 경우) 해당 작업을 failed('선행 생성 작업 결과가 없습니다.')로 처리하고 중단한다.
- 반환값은 `{results, serviceId, createdTargetIds, completed: 모두 succeeded/already-applied, failedOpId}`.
- **멱등성**: patch와 scheduling은 절대값을 보내므로 재실행해도 안전하다. grant는 복합 PK라 멱등이다. delete와 revoke는 404를 성공으로 처리한다. createTarget과 createService는 멱등이 아니다. 응답이 유실된 경우에는 rebase의 **채택(adoption)** 규칙(§7.9)이 같은 Deployment의 원격 신규 Target을 흡수해 중복 생성을 막는다.

### 7.9 rebase (재동기화)

```
rebase({graph, base, remote, createdTargetIds}):
  g1 = createdTargetIds ? renameTargetNodes(graph, createdTargetIds) : graph   // 'target:new-3' → 'target:<realId>', refId 설정, 엣지 id·좌표 유지
  L  = compile(g1, base)
  {merged, conflicts, adopted} = mergeConfigs(base.config, L, remote.config)
  g2 = adopted가 비어 있지 않으면 renameTargetNodes(g1, adopted) 아니면 g1
  return { graph: syncGraphToConfig(g2, merged, remote), conflicts }
```

`mergeConfigs` 규칙(충돌이 나면 **원격 우선**, 충돌 목록을 반환):
- service/scheduling 필드: resolve 결과 "적용"이면 L, "충돌"이면 R, 그 외는 R(=수렴 값).
- target id가 B∩L∩R: 필드별로 같은 방식. B에 있고 L에 없음: R에 있고 R==B면 제외(로컬 삭제 유지), R≠B면 R 포함(충돌), R에 없으면 제외. B∩L이고 R에 없음: 제외(원격 삭제 수용, L이 바뀌었으면 충돌 기록). R에만 있음: L에 같은 id가 있으면(이름이 바뀐 생성 결과) R, 없으면 **채택 검사**로 넘어간다. L의 신규(id=null) 중 같은 deploymentId가 있으면 그 신규를 `{...L신규, id: R.id}`로 채택하고 `adopted[L신규.key] = R.id`. 없으면 R을 그대로 추가한다.
- L 신규(채택 안 됨): 그대로 유지한다.
- 접근: `merged = (R ∪ (L∖B)) ∖ (B∖L)`, 정렬.

### 7.10 동시성 정책

- 모든 Plan은 **새로 읽은 R**에 대해 계산하고, Apply는 **한 번 더 읽은 R2**로 fingerprint가 같은지 확인한다. 다르면 "검토 이후 서버 구성이 바뀌어 변경 목록을 다시 계산했습니다. 다시 확인하세요."를 표시하고 새 Plan을 보여 준다.
- 충돌이 있으면 Apply를 막는다. 모달의 "최신 서버 상태와 병합" 버튼은 `rebase(base=B, remote=R)` 후 `B=R`로 바꾸며, 충돌 필드는 원격 값이 된다.
- 툴바의 "새로고침"은 변경이 있으면 rebase, 없으면 `decompile(R, extractLayout(graph, viewport))`를 수행한다.
- 서버에 낙관적 잠금 버전이 없으므로, 확인 시점과 실행 시점 사이(수백 ms)의 경합은 남은 위험이다(§12).

### 7.11 신규 서비스 기본값 (`DEFAULT_SERVICE_STATE`, 서버 생성 기본값과 일치)

`{serviceKey:'', displayName:'', enabled:true, dataBoundaryPolicy:'EXTERNAL_ALLOWED', requiredCapabilitiesJson:'[]', currency:'KRW', inputPricePerMillion:0, outputPricePerMillion:0, guardrailAction:'ALLOW', maskerDeploymentId:null, maskPersonNames:true, systemPromptTemplate:null, temperaturePolicy:'REQUEST', temperatureValue:null, reasoningEffort:'REQUEST', openAiFastMode:false, responseCacheEnabled:false, responseCacheTtlSeconds:300, failoverPolicy:'STRICT', retryPolicy:'SAFE', allowDegraded:false}`

초안 스냅샷: `serviceId:null, promptVersion:1, config:DEFAULT_CONFIG, accessComplete:true`. 카탈로그는 정상 로드하되 서비스별 읽기(targets, scheduling, DLP, 프로젝트 접근)는 생략한다. 생성이 성공하면 페이지는 `#flow-designer/<newId>`로 바꾸고(`history.replaceState`) 서비스 선택기를 갱신한다.

---

## 8. 캔버스 엔진 & UX

### 8.1 렌더 구조 (WP-B)

```
<div class="fd-canvas" tabindex="0" role="application" aria-label="서비스 플로우 캔버스">   ← 포인터·휠·키 이벤트, 배경 격자
  <div class="fd-world" style="transform: translate(x,y) scale(zoom); transform-origin:0 0">
    <svg class="fd-edges" width="1" height="1" style="overflow:visible">  ← 엣지(hit path 14px 투명 + 표시 path), 와이어 미리보기
    <FlowNodeCard v-for="node" :style="{left,top,width,height}" />          ← 절대 위치 HTML 노드
  </div>
  <div class="fd-marquee" />  <div class="sr-only" aria-live="polite" />
</div>
```

- 좌표: 노드 x/y는 월드 좌표의 좌상단이다. `screen = world*zoom + (vx,vy)`. 변환 함수는 layout.ts에 있다.
- 노드 치수는 **결정적**이다. 너비 232, 높이 = 40(헤더) + max(in, out, 1)×24(포트 행) + 56(요약) + 8, 접힘 시 40. `FlowNodeCard`는 CSS 고정 높이와 `overflow:hidden`, 요약 2줄 `line-clamp`로 이 치수를 **정확히** 지킨다. 그래야 `portAnchor`가 DOM 측정 없이 맞는다.
- 포트 앵커: in은 `(x, y+40+24i+12)`, out은 `(x+232, y+40+24j+12)`. 접힘 상태에서는 모든 포트가 `y+20`.
- 와이어: `bezierPath(a,b) = M a C (a.x+dx) a.y, (b.x−dx) b.y, b.x b.y`, `dx = max(40, |b.x−a.x|·0.5)`, 좌표는 소수 1자리로 반올림.
- 격자 배경: `background-image`로 `--grid-line`을 쓰고 `background-size = 16·zoom px`, `background-position = vx vy`.

### 8.2 인터랙션 상태 기계 (`pointer*` 이벤트로 마우스·터치·펜을 통합하고, 드래그 중에는 `setPointerCapture`)

| 상태 | 시작 | 이동 | 종료 |
|---|---|---|---|
| panning | 배경 좌클릭(Shift 없음), 가운데 버튼, Space 누른 채 드래그 | vx,vy 갱신(rAF 스로틀) | `update:viewport` 1회 emit |
| marquee | 배경 Shift+드래그 | 사각형 표시 | `nodesInRect`로 선택(Shift면 기존 선택에 추가) |
| dragging | 노드 본문 pointerdown(포트·입력 요소 제외). 선택되지 않은 노드면 단독 선택(Shift면 추가) | 선택된 노드 모두에 **임시 오프셋**(그래프는 불변) | 3px 미만 이동은 클릭으로 간주. 그 이상이면 `snapToGrid`(Alt를 누르면 스냅 해제) 후 `moveNodes` 1회 emit |
| wiring | out 포트 pointerdown | 미리보기 경로. `nearestCompatiblePort(반경 24/zoom)`를 하이라이트하고 호환 포트는 강조 | 대상이 있으면 `connect` emit, 없으면 취소 |
| 휠 | — | `zoomAt(viewport, cursor, exp(−deltaY·0.0015))`, 범위 0.2–2, `preventDefault` | 150ms 디바운스 후 `update:viewport` |
| 엣지 클릭 | hit path 클릭 | — | 엣지 선택(Shift면 토글) |

팔레트에서 끌어 놓기: 팔레트는 HTML5 DnD로 MIME `application/x-aiconnect-flow-node`에 JSON `{nodeType, refId}`를 싣는다. 캔버스는 `dragover`에서 MIME를 확인해 `preventDefault`하고, `drop`에서 JSON을 검증한다(nodeType ∈ AddableNodeType, refId는 80자 이하 문자열 또는 null). 그다음 `snapToGrid(screenToWorld(dropPoint))` 위치로 `addNode`를 emit한다.

### 8.3 키보드와 접근성 (캔버스 루트에 포커스가 있을 때만, 이벤트 대상이 input/textarea/select면 무시)

| 키 | 동작 |
|---|---|
| Ctrl/⌘+Z, Ctrl/⌘+Shift+Z, Ctrl+Y | `action` emit: undo / redo |
| Delete, Backspace | 선택 항목으로 `removeItems` emit |
| 화살표 | 선택 노드를 16px 이동(Shift면 64px) → `moveNodes` |
| F | 화면 맞춤. `+`/`-`는 중심 기준 줌, `0`은 100% |
| Ctrl/⌘+A | 모든 노드 선택 |
| Escape | 와이어·마키 취소, 다시 누르면 선택 해제 |
| Tab / Shift+Tab | 노드 카드 순회(DOM 순서 = 열, id) |
| Enter(노드 포커스) | 그 노드를 선택하고 인스펙터를 엶 |

- 노드 카드는 `role="group"`, `tabindex="0"`, `aria-label="${typeLabel} ${title}, ${tone 라벨}"`, `aria-selected`를 갖는다. 포트는 `tabindex="-1"`(포인터 전용)이다. **모든 연결 편집은 인스펙터로도 할 수 있다**(Target의 Deployment 선택, 프로젝트 접근 토글, 마스커 선택, 팔레트 클릭 추가). 따라서 포인터 없이 전 기능을 쓸 수 있다.
- 결과는 `aria-live` 영역으로 알린다(예: "Target을 추가했습니다").
- `prefers-reduced-motion`이면 애니메이션과 전환을 끈다.

### 8.4 테마와 시각 언어 (기존 토큰만 사용, 하드코딩 색 금지)

- 카테고리: `[data-category=entry]`는 `--info`, pipeline은 `--accent-strong`, routing은 `--warning`, infra는 `--muted`. 헤더 왼쪽 4px 스트립과 아이콘 색으로 쓴다.
- 톤 점: healthy `--accent-strong`, warning `--warning`, error `--danger`, muted/unknown `--muted`.
- 엣지: access는 `--info` 점선, pipeline은 `--accent-strong` 2px, masker는 `--warning` 점선, route는 `--warning`, binding은 `--text-soft`(대상 Target이 비활성이면 opacity .4), host는 `--faint` 점선이다. locked 엣지는 선택해도 삭제 버튼이 없다. 선택된 엣지는 3px.
- 노드 상태 배지: 변경됨은 `--accent` 점, 신규는 '신규' 칩, 이슈는 오류 수(`--danger`)와 경고 수(`--warning`), 잠금은 🔒 대신 텍스트 '고정'이다.
- 카드 배경 `--surface`, 테두리 `--border`(선택 시 `--accent-border` + `--shadow-soft`). 캔버스 배경은 `--bg-soft`.

### 8.5 성능 목표와 기법

- 목표: 노드 200개, 엣지 300개에서 드래그 60fps, 첫 렌더 100ms 미만, 명령 1회 처리(검증 + compile + 로컬 diff) 5ms 미만.
- 기법:
  - 페이지는 `shallowRef<FlowGraph>`를 쓰고 명령마다 객체를 교체한다.
  - 드래그 오프셋은 캔버스 로컬 `reactive`이고 대상 노드만 영향을 받는다.
  - `FlowNodeCard`에 `v-memo="[node, view, selected, change, counts, offsetKey]"`를 쓴다.
  - pointermove는 rAF로 스로틀한다.
  - 엣지 경로는 `computed`로 한 번에 계산한다(300개는 저렴).
  - 노드 조회용 `Map`을 `computed`로 둔다.

### 8.6 자동 정렬 (`autoLayout`)

- x = column × 300. pipeline 싱글턴은 y=0에 둔다.
- masker는 열 1에서 `service 높이 + 48`.
- project(열 0)는 이름순(스냅샷이 없으면 refId 순)으로 쌓는다.
- target(열 6)은 (priority 오름차순, weight 내림차순, key) 순으로 쌓는다.
- deployment(열 7)는 자신을 참조하는 가장 앞 target의 순서를 따르고, 미바인딩 deployment는 뒤에 refId 순으로 둔다.
- endpoint/provider(열 8)는 가장 앞 deployment 순서를 따른다.
- 같은 열 안에서는 ROW_GAP 24로 세로로 쌓고 16 격자에 스냅한다. 결정적이어야 하고 같은 열의 노드가 겹치면 안 된다.
- `placeNewNode`는 해당 열의 최하단 + 24에 놓는다(열이 비어 있으면 y=0, masker는 위 규칙).

### 8.7 화면 구성 (WP-C 페이지 + WP-B 컴포넌트)

```
┌ page-hero: "서비스 플로우 디자이너" [EXPERIMENTAL] · 서비스 선택기(+새 서비스 초안, 초안 있음 표시) ─────────┐
│ 설명: 기존 화면과 같은 API를 사용합니다. 변경은 '변경 검토 → 적용' 후에만 저장됩니다.                      │
├ FlowToolbar: ↶ ↷ │ 화면 맞춤 · 자동 정렬 │ 검증(E n · W n) │ 되돌리기 · 새로고침 · 배치 저장• │ [변경 검토 (n)] ┤
├──────────┬───────────────────────────────────────────────────────┬───────────────┤
│ Palette  │                    FlowCanvas                          │ FlowInspector │
│ 260px    │                                                       │ 320px         │
├──────────┴───────────────────────────────────────────────────────┴───────────────┤
│ 상태바: 변경 n건 · 오류 n · 경고 n · 불러온 시각 · 확대 n% · 배치 미저장                           │
└───────────────────────────────────────────────────────────────────────────────────┘
```

- 높이는 `calc(100vh − 260px)`, 최소 520px.
- 메시지는 기존 `.inline-alert`(`role="status"`)로 표시하고, 버튼은 `primary-button`/`secondary-button`/`ghost-button`, 칩은 `status-chip`을 재사용한다.
- 인스펙터:
  - 선택이 없으면 **검증 결과 목록**을 보여 준다(심각도별, 클릭 시 해당 노드 선택과 `centerOn`).
  - 노드를 선택하면 헤더, 읽기 정보(`nodeFacts`), 필드 폼, 노드 이슈, [접기] [화면 중앙] [삭제]를 보여 준다.
  - 엣지를 선택하면 의미, 양 끝 노드, 삭제를 보여 준다.
  - 다중 선택이면 개수와 [선택 삭제]만 있다.
  - text/textarea/number는 `change`(blur/Enter)에서, checkbox/select는 즉시 `updateProps`를 emit한다. 키 입력마다 history 항목이 생기지 않게 하기 위해서다.
- Plan 모달(FlowPlanModal, BaseModal `size="lg"`) 구성:
  1. 요약(작업 수, 위험 수)
  2. **충돌**(있으면 Apply 비활성, [최신 서버 상태와 병합] 버튼)
  3. **차단 이슈**
  4. 순서가 매겨진 작업 목록(번호, 위험 배지, summary, changes 표, `METHOD path` 작게 monospace)
  5. 노트
  6. danger 작업이 있으면 "위험 작업 N건을 확인했습니다" 체크박스를 눌러야 [적용 (N개 작업)]이 활성화된다.
  - 적용 중에는 작업별 상태 아이콘(대기·진행·성공·이미 적용·실패·건너뜀)을 보여 준다.
  - 완료 후에는 결과 요약을 보여 주고, 실패 시 "남은 변경은 캔버스에 그대로 유지됩니다."를 덧붙인다.

### 8.8 좁은 화면

- 1020px 미만: 팔레트와 인스펙터는 오버레이 드로어가 된다(툴바의 `toggle-palette`/`toggle-inspector`로 연다).
- 760px 미만: `readOnly`다. 상단 배너 "편집은 화면 폭 760px 이상에서 지원합니다. 지금은 보기 전용입니다."를 띄운다. 팬·줌·선택·인스펙터 보기만 허용하고 모든 편집 명령과 툴바 편집 버튼을 막는다. 판단은 `matchMedia('(max-width: 759px)')` 리스너로 한다.

### 8.9 초안 보존과 이탈

- ConsoleShell은 화면 이동을 막을 수 없다(hashchange 리스너 순서상 먼저 페이지를 바꾼다). 그래서 **메모리 전용 `draftStore`**에 `(userId, orgId, serviceId)` 키로 base·graph·viewport를 보관한다.
- 보관 시점: 서비스 전환, 조직 전환, `onBeforeUnmount` 시 변경(설정 또는 배치)이 있으면 보관한다. 돌아오면 복원하고 "저장되지 않은 초안을 복원했습니다(불러온 시각 …). 새로고침하면 서버 상태와 병합합니다." 배너를 띄운다.
- 초안 상한은 10개(LRU)다. `aiconnect:session-expired` 이벤트나 다른 사용자 세션 이벤트(`aiconnect:session`의 user.id 변경)가 오면 전부 비운다. 적용에 성공하면 해당 키를 삭제한다.
- 브라우저 저장소에는 **쓰지 않는다**. 새로고침이나 탭을 닫을 때는 변경이 있으면 `beforeunload`로 브라우저 확인 창을 띄운다.

---

## 9. 보안 · 권한 · 레이아웃 API

### 9.1 원칙

1. **서버가 집행 지점**이다. 프런트는 §2.3의 기존 API와 §9.2의 레이아웃 API만 호출한다. 메뉴 숨김은 UX일 뿐이며 권한 판단을 대신하지 않는다.
2. **비밀값 비보관**:
   - API 키는 조회하지 않는다(프로젝트 키 목록 미호출).
   - Provider는 `apiKeyConfigured` boolean만 사용한다. Runtime 토큰은 응답에 없다.
   - Endpoint `baseUrl`은 `safeAddressLabel`로 자격 증명·쿼리·프래그먼트를 제거한 뒤 표시용으로만 쓰고, **그래프 props와 레이아웃에는 넣지 않는다**.
   - 레이아웃에는 노드 id, 좌표, 접힘 상태, 뷰포트만 들어간다.
   - 시스템 프롬프트는 그래프 props(메모리)에만 있고 레이아웃에는 들어가지 않으며, Plan 표시에서도 길이만 보여 준다.
3. **참조 무결성**: 작업에 들어가는 deploymentId, projectId, maskerDeploymentId는 그래프 노드의 refId에서 나오고, 노드는 카탈로그에 있는 id로만 만든다(`addNode` 검증). 서버는 조직 일치와 신뢰 마스커를 다시 검사한다.
4. **XSS**: 모든 이름은 텍스트 보간으로 출력하고 `v-html`을 금지한다. DnD 페이로드는 스키마로 검증한다.
5. **한도**: 노드 300, 엣지 600, 작업 100, 프롬프트 16000, 표시 이름 200, Capability 2000, 접근 조회 프로젝트 200(초과하면 `accessComplete=false`), 레이아웃 60,000자, 레이아웃 노드 500. 조회 병렬도는 6이다.
6. **감사**: 모든 적용 작업과 레이아웃 저장은 기존 `AdminTokenFilter`가 자동으로 감사 기록한다. 레이아웃은 자동 저장하지 않는다.

### 9.2 레이아웃 영속화 API (WP-D)

**마이그레이션** `src/main/resources/db/migration/V53__service_flow_layout.sql`
```sql
CREATE TABLE service_flow_layout (
    service_id CHAR(36) NOT NULL PRIMARY KEY,
    organization_id CHAR(36) NOT NULL,
    layout_json TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_by CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_service_flow_layout_service FOREIGN KEY (service_id) REFERENCES llm_service(id) ON DELETE CASCADE
);
CREATE INDEX idx_service_flow_layout_org ON service_flow_layout(organization_id);
```
(MariaDB/H2 공통 문법이다. 통합 테스트는 JPA로 스키마를 생성하고, 운영은 `ddl-auto: validate`이므로 엔티티 매핑이 정확히 일치해야 한다. CASCADE이므로 조직 영구 삭제 순서 코드(N17)는 수정하지 않는다.)

**패키지** `com.aiconnect.llmgateway.flowdesigner`(신규, 실험 기능을 격리해 제거하기 쉽게 한다)
- `ServiceFlowLayout`(엔티티): `@Id @Column(columnDefinition="char(36)") UUID serviceId`, `@Column(nullable=false, columnDefinition="char(36)") UUID organizationId`, `@Column(nullable=false, columnDefinition="text") String layoutJson`, `@Column(nullable=false) long revision`, `@Column(columnDefinition="char(36)") UUID updatedBy`, `@Column(nullable=false) Instant createdAt`, `@Column(nullable=false) Instant updatedAt`. 생성자와 `replace(String json, UUID actor)`(revision+1, updatedAt=now)를 둔다.
- `ServiceFlowLayoutRepository extends JpaRepository<ServiceFlowLayout, UUID>`
- `ServiceFlowLayoutDtos`: `record FlowLayoutPayload(Integer version, ViewportDto viewport, Map<String, NodePositionDto> nodes)`, `record ViewportDto(Double x, Double y, Double zoom)`, `record NodePositionDto(Double x, Double y, Boolean collapsed)`, `record LayoutView(UUID serviceId, long revision, Instant updatedAt, FlowLayoutPayload layout)`
- `ServiceFlowLayoutService`: `get(UUID serviceId, HttpServletRequest)`, `save(UUID serviceId, FlowLayoutPayload, HttpServletRequest)`, `static FlowLayoutPayload validateAndNormalize(FlowLayoutPayload)`
- `ServiceFlowLayoutController`: `@RequestMapping("/api/admin/services/{serviceId}/flow-layout")`, `@GetMapping`, `@PutMapping`

**엔드포인트 계약**

| 메서드 | 응답 | 오류 |
|---|---|---|
| `GET /api/admin/services/{serviceId}/flow-layout` | 200 `LayoutView`. 저장본이 없으면 `{serviceId, revision:0}`(non_null로 layout과 updatedAt 생략) | 404 `SERVICE_NOT_FOUND`(없거나 tombstone), 403 |
| `PUT /api/admin/services/{serviceId}/flow-layout` 본문 `FlowLayoutPayload` | 200 `LayoutView`(revision 증가, 처음 저장이면 1) | 400 `FLOW_LAYOUT_INVALID`, 400 `FLOW_LAYOUT_TOO_LARGE`, 404, 403 |

**검증·정규화** (`validateAndNormalize`, 위반 시 `ApiException(HttpStatus.BAD_REQUEST, code, message)`)
- `version == 1`, `viewport`와 `nodes`는 non-null이어야 한다.
- viewport x,y는 유한수이고 |v| ≤ 1,000,000, zoom은 0.1 ≤ zoom ≤ 4.
- nodes.size() ≤ 500. 각 키는 `^(service|guardrail|prompt|cache|router|masker|(project|target|deployment|endpoint|provider):[A-Za-z0-9-]{1,64})$`, 값 x,y는 non-null 유한수이고 |v| ≤ 1,000,000.
- 정규화: `collapsed`는 true일 때만 유지하고 그 외는 null(응답에서 생략). 키 순서는 `TreeMap`으로 정렬한다. **모르는 필드는 record 역직렬화에서 버려지고 재직렬화되므로 저장되지 않는다.**
- 정규화한 JSON(`ObjectMapper`)의 길이가 60,000자를 넘으면 `FLOW_LAYOUT_TOO_LARGE`.

**권한** (필터 + 컨트롤러 이중 검사)
- 필터: `/api/admin/services/{id}`를 서비스 조직으로 해석하고, 조직 관리자가 아니면 403이다(§2.5).
- 서비스: `LlmServiceRepository.findById`가 없거나 `isDeleted()`면 404 `SERVICE_NOT_FOUND`. 이어서 `http.getAttribute("aiconnect.platform-admin")==TRUE`이거나 `CurrentActor.principal()`이 platformAdmin이거나 `TeamAccessService.isOrganizationAdmin(actor, service.getOrganizationId())`인 경우가 아니면 403 `ORGANIZATION_ADMIN_REQUIRED`(`DataProtectionPolicyController.java:131-137` 패턴, `TeamAccessService`는 `@Autowired(required=false)`).
- 저장 시 `organizationId = service.getOrganizationId()`. 조회 시 저장된 organizationId가 서비스 조직과 다르면 저장본을 무시하고 revision 0을 반환한다(방어).
- `updatedBy = CurrentActor.userIdOrNull()`.

### 9.3 프런트 레이아웃 클라이언트 동작 (WP-C)

- `loadFlowLayout`는 GET을 보내고 응답의 `layout`을 `sanitizeLayout`에 통과시킨다. **어떤 오류든**(404 미배포, 403, 5xx) `{available:false, layout:null, revision:0}`을 반환하고, 이때 페이지는 자동 정렬로 그리고 [배치 저장]을 숨긴다.
- `saveFlowLayout`은 `PUT`에 `extractLayout(graph, viewport)`를 보낸다. `target:new-*` id는 제외한다.
- 배치 변경 감지(`layoutDirty`)는 viewport를 제외한 `stableStringify(extractLayout(...).nodes)`가 마지막 저장본과 다른지로 판단한다.

---

## 10. 테스트 계획

### 10.1 WP-A — `frontend/tests/flowDesignerModel.test.mjs` (필수 케이스)

픽스처: 서비스 1개, 로컬 Runtime 1개와 Deployment 2개(a: 로드됨, b: 미로드), 외부 Provider 1개와 모델 1개(x), 프로젝트 2개(p1 접근 있음, p2 없음), Target 2개(t1→a P1 W80, t2→x P2 W100, t2 maxConcurrencyOverride=2), 마스커 후보(a 적격, x 부적격).

1. **카탈로그 무결성**: CONNECTION_RULES가 참조하는 포트가 모두 존재한다. 싱글턴 5종은 deletable=false다. 필드 key는 모두 compile에서 소비한다(목록 비교).
2. **decompile 결정성**: 두 번 실행한 결과가 deep-equal이다. 노드 id 규칙(§4.4), locked pipeline·host 엣지, masker 노드 존재, p1만 access 엣지를 가진다.
3. **왕복 불변식**: `compile(decompile(s,null), s)`가 `s.config`와 deep-equal이다(**핵심**).
4. 변경 없음이면 `planChanges` 작업 0개, 충돌 0개이고, fingerprint가 실행마다 같다.
5. t1 weight만 수정하면 patchTarget 1건, body `{weight}`만 담기고 risk safe다.
6. guardrailAction BLOCK→ALLOW면 body `{guardrailAction:'ALLOW'}`만 담기고 risk danger, DLP 부수효과 노트가 생긴다. 변경이 없으면 guardrailAction 키 자체가 없다.
7. 마스커 노드 삭제 → `{clearMaskerDeployment:true}`. 부적격 x 선택 → `MASKER_NOT_ELIGIBLE`로 차단.
8. 프롬프트를 '  ' 로 바꾸면 `{clearSystemPromptTemplate:true}`. 앞뒤 공백만 추가하면 작업 0개. 변경하면 버전 노트.
9. FIXED + null → `TEMPERATURE_VALUE_REQUIRED`로 차단. FIXED + 0.7 → body에 `{temperaturePolicy:'FIXED', temperatureValue:0.7}`.
10. router.targets→deployment b를 connect하면 `target:new-1`, route·binding 엣지, priority 3(max+1), weight 100이 생기고 createTarget 1건. 외부 x에 연결하면 followModelChanges=false.
11. 신규 Target을 enabled=false로 두면 createTarget 바로 뒤에 `disable-created-target:<key>`, TargetRef created.
12. t1을 b로 재연결하면 createTarget(key `target:<t1>`) + deleteTarget(t1). **스왑**(t1→x, t2→a)이면 phase 3 선행 삭제가 create보다 먼저 온다.
13. 두 Target을 같은 Deployment에 연결하면 `DUPLICATE_DEPLOYMENT_TARGET` error.
14. t2 노드를 삭제하면 deleteTarget(danger). service 노드 삭제는 error를 반환하고 그래프는 불변이다. 바인딩이 있는 deployment 노드 삭제도 거부된다.
15. p2에 setAccess true → grantAccess, p1 access 해제 → revokeAccess(danger). `accessComplete=false`면 `ACCESS_EDIT_UNAVAILABLE`이고 접근 작업은 0건.
16. **3-way**:
    - 원격만 t1 weight 변경 → 작업 0개.
    - 로컬 priority, 원격 weight → priority만 patch.
    - 양쪽이 weight를 다른 값으로 → `FIELD_CONFLICT`.
    - 양쪽이 같은 값 → 작업 0개, 충돌 0개.
    - 원격이 deploymentId를 자동 추적으로 변경하고 로컬은 weight만 변경 → weight만 patch.
17. 원격에서 삭제된 t1을 로컬이 수정 → `TARGET_REMOVED_REMOTELY`. 로컬이 삭제한 t2를 원격이 수정 → `TARGET_CHANGED_REMOTELY`.
18. 원격에 b Target이 새로 생기고 로컬도 b 신규 → `DEPLOYMENT_TARGETED_REMOTELY`. `rebase` 후 채택되어 노드 id가 `target:<remoteId>`가 되고 재계획 결과 충돌이 0개다.
19. **부분 적용 rebase**: createdTargetIds `{ 'target:new-1': 'real-9' }`와 원격(생성 반영)으로 rebase하면 노드 이름이 바뀌고 좌표가 보존되며, 재계획에는 미적용 작업만 남는다.
20. **신규 서비스**: key 'text-new', guardrail LOCAL_ONLY, 신규 Target 1개, p1 접근 → 순서가 `create-service, patch-service({guardrailAction} 포함, displayName 미포함), create-target(service created), grant-access`. key 'Text_New' → `SERVICE_KEY_INVALID`, 기존 key → `SERVICE_KEY_TAKEN`.
21. 스케줄링: LOCAL_FIRST_WAIT에서 local 40000 > max 30000 → error. perKey > total → error. 정상 변경이면 body가 원격 값과 병합된 전체 객체다.
22. TTL 5 → `CACHE_TTL_RANGE`. t2 override를 null로 → `TARGET_CONCURRENCY_CLEAR_UNSUPPORTED`.
23. 작업 101개 → `PLAN_TOO_LARGE`. phase 순서와 phase 안 정렬이 결정적이다.
24. **layout**:
    - `portAnchor`가 `nodeSize`와 일관된다(in 첫 행 y = y+52).
    - `bezierPath` 문자열 형식이 맞다.
    - `fitViewport`의 zoom은 1 이하이고 MIN 이상으로 clamp된다.
    - `zoomAt`은 커서 아래 월드 좌표를 유지한다.
    - `snapToGrid`, `nodesInRect`가 맞게 동작한다.
    - `autoLayout` 결과는 결정적이고 같은 열에서 겹치지 않는다.
    - `sanitizeLayout`은 잘못된 id, NaN, 범위 밖 값을 제거하고 zoom을 clamp하며 version이 1이 아니면 null을 반환한다.
    - `extractLayout`은 `target:new-*`를 제외하고 이름·주소·프롬프트를 포함하지 않는다(JSON 문자열 검사).
25. `nearestCompatiblePort`는 반경과 `canConnect`를 따른다. 잘못된 조합의 사유 메시지(§6.2)가 맞다. 손수 만든 순환 그래프 → `CYCLE_DETECTED`.
26. **history**: record/undo/redo가 동작하고, limit 100을 넘으면 오래된 것부터 버리며, 새 record가 future를 비운다.
27. **화이트리스트**: 생성된 모든 body의 키는 §4.2 Body 타입에 정의된 키뿐이다.

### 10.2 WP-C — `frontend/tests/flowDesignerAdapter.test.mjs`

1. `buildSnapshot`:
   - non_null로 누락된 필드(maskerDeploymentId, temperatureValue, systemPromptTemplate, maxConcurrencyOverride)는 null, guardrailAction은 'ALLOW', followModelChanges는 true, promptVersion은 1.
   - 외부 모델은 `external:true, loaded:true, externalProviderId`를 가진다.
   - targets와 접근 목록이 정렬된다.
   - 프로젝트 접근 조회가 1건이라도 실패하거나 프로젝트가 200개를 넘으면 `accessComplete:false`.
   - serviceId를 지정했는데 목록에 없으면 `FlowLoadError('SERVICE_NOT_FOUND')`. targets나 scheduling이 null이면 `REQUIRED_READ_FAILED`.
2. `safeAddressLabel`: `http://user:pw@10.0.0.1:1234/v1?token=x#a` → `http://10.0.0.1:1234/v1`, 잘못된 URL은 '주소 확인 필요'.
3. `operationRequest`: kind별 method, path, body가 §7.5와 정확히 같다. created 참조를 해석하고, 경로에 `encodeURIComponent`를 적용한다.
4. `executePlan`(가짜 transport):
   - 호출 순서가 맞다.
   - create-service id가 이후 경로에 반영되고, createdTargetIds를 수집한다.
   - deleteTarget 404는 already-applied로 처리한다.
   - 3번째 작업이 500이면 failed이고 나머지는 skipped, `completed:false`, `failedOpId`가 설정된다.
   - onProgress가 상태 변화마다 호출된다.
5. `draftStore`: 사용자·조직·서비스 키별로 격리되고, 다른 userId로는 takeDraft가 null이다. LRU 10개, `installDraftAutoClear`는 `aiconnect:session-expired` 이벤트로 비우며 해제 함수가 동작한다.
6. **ConsoleShell 소스 검사**(정규식):
   - `defineAsyncComponent(() => import('./flow-designer/FlowDesignerPage.vue'))`가 있다.
   - PageKey와 `knownPage`에 `'flow-designer'`가 있다.
   - nav 항목 `id: 'flow-designer'`가 `adminNavItems` 블록 안에만 있다(`directorNavItems`와 `developerNavItems`에는 없다).
   - `<FlowDesignerPage v-else-if="isOrgOrPlatformAdmin && page === 'flow-designer'"`가 있다.
7. **정적 안전 검사**:
   - `frontend/src/flow-designer/` 전 파일에 `localStorage`, `sessionStorage.setItem`, `v-html`, `innerHTML`이 없다.
   - `ServicesPage.vue`, `RoutingPolicyPanel.vue`, `InfrastructurePage.vue`, `ProjectsPage.vue`, `DataProtectionPage.vue`에 `flow-designer` 문자열이 없다(무변경 보증).

### 10.3 백엔드 — `src/test/java/com/aiconnect/llmgateway/ServiceFlowLayoutIntegrationTest.java`

`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("integration")`, H2 URL은 `jdbc:h2:mem:aiconnect_flow_layout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1`. 조직 A·B와 각 조직의 서비스를 리포지토리로 만든다(`RoutingPolicyManagementIntegrationTest` 패턴).

1. 플랫폼 토큰(`X-Admin-Token: integration-admin-token`)으로 GET → 200, `revision`=0, `$.layout`이 존재하지 않는다.
2. PUT 정상 → 200, revision 1. GET이 같은 nodes를 반환한다. 다시 PUT → revision 2. collapsed=false는 응답에서 생략된다.
3. 잘못된 키(`evil`, `target:../x`), 범위 밖 좌표(1e9), zoom 10, version 2, nodes 501개 → 400 `FLOW_LAYOUT_INVALID`. 60,000자 초과 → 400 `FLOW_LAYOUT_TOO_LARGE`. (`$.code`로 확인)
4. 모르는 필드(최상위와 노드 안의 `"secret":"x"`)를 보내도 200이고, 이후 GET 본문 문자열에 `secret`이 없다.
5. tombstone 서비스(`markDeleted`) → GET 404. 없는 UUID → 403 또는 404(필터가 조직을 해석하지 못해 403을 반환하는 것이 기존 동작이다. **둘 중 하나**로 단언).
6. **RBAC/IDOR**:
   - 조직 A의 ORGANIZATION_ADMIN 멤버(`identity.createUser` + `grantMembership`, DIRECTOR로 승격됨)의 토큰으로 A 서비스 GET/PUT → 403.
   - 조직 A의 DEVELOPER 토큰으로 B 서비스 → 403.
   - 토큰 획득은 `ScopedRuntimeInventoryIntegrationTest`의 login→totp 흐름을 쓴다.
7. 서비스 엔티티를 하드 삭제해도 예외가 없다(H2는 JPA 스키마라 FK가 없다. MariaDB CASCADE는 10.4의 CI 단계에서 검증).

추가로 `FlywayUpgradeIntegrationTest`(MariaDB CI 전용)에 테이블 존재 단언 1줄을 넣는다: `service_flow_layout`.

### 10.4 CI·스크립트

- `frontend/package.json`: `"test:flow-designer": "node --test tests/flowDesignerModel.test.mjs tests/flowDesignerAdapter.test.mjs"`
- `.github/workflows/quality-gate.yml` 77–78행 체인 끝에 ` && npm run test:flow-designer`를 붙인다. 백엔드 job은 `gradle clean test`가 신규 테스트를 자동으로 포함한다.

### 10.5 수동 브라우저 체크리스트 (`npm run dev` + 백엔드 8080)

1. 플랫폼 관리자로 로그인하면 사이드바 '인프라·시스템'에 "플로우 디자이너"가 보인다. Director와 일반 사용자에게는 메뉴가 없고, `#flow-designer`를 직접 입력하면 대시보드로 이동한다. ⌘K 검색에서 "배치", "플로우"로 찾을 수 있다.
2. 서비스를 선택하면 자동 정렬된 캔버스가 화면에 맞춰지고, 다크/라이트 전환 시 색이 토큰을 따른다.
3. 팬, 휠 줌(0.2–2), 16px 스냅 드래그, Shift 마키, 화살표 이동, Undo/Redo, Delete, F가 동작한다.
4. Target 가중치를 바꾸면 상태바에 "변경 1건"이 뜨고, Plan에는 PATCH와 `{weight}`만 보인다. 적용 후 LLM 서비스 화면에서 반영을 확인하고, 감사 로그에 기록이 남는다.
5. 라우터→Deployment 와이어로 Target이 생성되고, 적용 후 서비스 화면 Target 목록에 보인다. 다른 Deployment로 재연결하면 교체 작업 2건이 나온다.
6. 가드레일 정책을 바꾸면 Plan에 경고 노트가 나오고, 적용 후 데이터 보호 화면의 서비스 정책에 반영된다.
7. 마스킹 워커: 부적격 Deployment를 고르면 오류로 차단되고, 적격이면 적용 후 서비스 화면 마스커 선택에 반영된다.
8. 프롬프트를 수정하면 버전 노트가 나오고, 적용 후 Prompt v가 증가한다.
9. 프로젝트 접근을 연결하면 grant가 되고 프로젝트 화면에 표시된다. 해제하면 danger 체크박스가 필요하다.
10. **충돌**: 다른 탭의 서비스 화면에서 같은 Target 가중치를 바꾸고 캔버스에서도 다르게 바꾸면 Plan에 충돌이 나오고 적용이 차단된다. [병합]하면 원격 값이 반영된다.
11. **stale plan**: Plan 모달을 연 뒤 다른 탭에서 변경하고 [적용]을 누르면 "다시 계산했습니다" 메시지가 나오고 새 목록이 보인다.
12. **부분 실패**: 예를 들어 archived Deployment로 Target을 만들게 하면 실패 작업이 표시되고, 나머지는 건너뛰며 캔버스에 남는다.
13. 새 서비스 초안부터 생성, Target, 접근까지 한 번에 적용하면 URL이 `#flow-designer/<id>`가 된다.
14. [배치 저장] 후 새로고침해도 좌표가 유지되고, 다른 관리자 계정에서도 같은 배치가 보인다. 레이아웃 PUT 본문에는 id와 좌표만 있다(Network 탭).
15. 폭이 1020px 미만이면 드로어, 760px 미만이면 읽기 전용 배너가 뜬다.
16. 저장하지 않은 상태로 다른 메뉴에 갔다가 돌아오면 초안 복원 배너가 뜬다. 로그아웃 후 재로그인하면 초안이 없다.
17. 기존 화면(LLM 서비스, 프로젝트, 인프라, 외부 AI, 데이터 보호, 시스템 구조)이 이전과 같이 동작하는지 스모크 테스트한다.
18. `npm run build` 출력에서 메인 `index-*.js` 크기 변화가 ±1KB 안이고, 별도의 `FlowDesignerPage-*.js` 청크가 생긴다.

---

## 11. 작업 지시서

### 11.0 공통 규칙 (모든 WP 필독)

- 이 문서의 §4.2(types.ts 전문)와 §4.3(시그니처)이 **계약**이다. 이름, 필드, 시그니처를 바꾸지 말 것. 꼭 바꿔야 하면 구현을 멈추고 보고한다.
- **파일 소유가 엄격하다.** 자기 WP 표에 없는 파일은 만들거나 수정하지 않는다. 기존 페이지·컴포넌트(ServicesPage, RoutingPolicyPanel, InfrastructurePage, ProjectsPage, DataProtectionPage, api.ts, style.css, BaseModal 등)와 기존 백엔드 서비스·컨트롤러는 **수정하지 않는다**.
- npm 의존성 추가 금지. 백엔드 Gradle 의존성 추가 금지.
- UI 문자열은 한국어, 식별자는 영어로 쓴다. `flow-designer/` 안에서는 `localStorage` 사용, `sessionStorage` 쓰기, `v-html`/`innerHTML`을 금지한다. 상대 import는 확장자 없이 쓰고 타입은 `import type`으로 가져온다.
- 커밋, 푸시, PR은 사용자가 요청할 때만 한다.
- 검증 환경: 프런트는 `cd frontend` 후 `npm run build`, `node --test tests/<file>.test.mjs`. 백엔드는 Git Bash에서 다음을 실행한다.
  ```bash
  cd /c/project/aiconnect && JAVA_HOME="C:/Users/kjs99/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1" \
    "C:/Users/kjs99/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle" \
    test --tests '<pattern>' --no-daemon
  ```

### 11.1 의존성과 병렬성

| 단계 | 병렬 실행 | 선행 조건 | 비고 |
|---|---|---|---|
| Phase 1 | **WP-A ∥ WP-D** | 없음 | 서로 독립(프런트 코어 / 백엔드) |
| Phase 2 | **WP-B ∥ WP-C** | WP-A 병합 | B와 C는 §4 계약과 §11.3 B↔C 인터페이스에 맞춰 코딩. C의 `npm run build` 최종 게이트는 B 병합 후 |
| Phase 3 | 통합 검증(WP-C 담당자) | A·B·C·D 병합 | 전체 프런트 테스트 체인, 빌드, §10.5 체크리스트, 백엔드 대상 테스트 |

B와 C는 A가 끝나기 전에 착수할 수 있지만(계약 전문이 문서에 있다) 검증은 A 병합 후에 한다. C의 레이아웃 클라이언트는 D가 없어도 `available:false`로 동작해야 한다.

---

### WP-A · 순수 TS 코어 모델 (types · catalog · layout · graphOps · decompile · compile · validate · diff · history) + 테스트

**목표**: UI와 네트워크에 의존하지 않는 결정적 모델 계층을 만든다. 그래프 ⇄ 도메인 변환, 검증, 3-way Plan, rebase, 기하 계산, Undo/Redo를 포함한다.

**소유 파일(모두 신규)**:
- `frontend/src/flow-designer/types.ts` (§4.2 **문자 그대로**)
- `frontend/src/flow-designer/catalog.ts`
- `frontend/src/flow-designer/layout.ts`
- `frontend/src/flow-designer/graphOps.ts`
- `frontend/src/flow-designer/decompile.ts`
- `frontend/src/flow-designer/compile.ts`
- `frontend/src/flow-designer/validate.ts`
- `frontend/src/flow-designer/diff.ts`
- `frontend/src/flow-designer/history.ts`
- `frontend/tests/flowDesignerModel.test.mjs`

**금지**: `vue`, `../api`, DOM 전역 참조. 순환 import(§4.1 방향을 따름). 무작위 값(`Math.random`, `crypto.randomUUID`)과 현재 시각 사용. 결정성이 필요하므로 신규 id는 `nextSeq`로 만든다.

**구현 명세**:
1. `catalog.ts`: §4.3 상수와 함수를 구현한다. `NODE_TYPES`는 §5.1–5.3을 그대로 옮긴다(라벨, icon, column, singleton, deletable, paletteAddable, ports, fields). `CONNECTION_RULES`는 §6.1. `describeNode`, `nodeFacts`, `describeEdge`는 §5.4, §6.1.
   - `nodeFacts` 행 예: service는 Service Key, Prompt 버전, 불러온 시각. guardrail은 DLP 모드, 수준, 탐지 항목(dataProtection 없으면 '정보 없음'). deployment는 모델 ID, 소스, 상태, 최대 동시성, 컨텍스트. endpoint는 Runtime 유형, 주소 라벨. provider는 유형, 키 설정, 무보존 확인. project는 상태, 데이터 경계. target은 서버 ID 또는 '생성 전'.
   - `FIELD_LABELS`는 §5.3의 라벨을 서비스, 스케줄링, Target 필드명 키로 둔다.
2. `layout.ts`: §4.3과 §8.1, §8.6. `sanitizeLayout`과 `extractLayout`은 §9.2, §9.3의 규칙과 같다(id 정규식, 범위, 500개 상한, collapsed는 true만).
3. `graphOps.ts`: `createSkeletonGraph`, `canConnect`(§6.2), `applyCommand`(§6.3), `syncHostNodes`(§6.4), `nearestCompatiblePort`, `renameTargetNodes`(노드 id, refId, 엣지 from/to/id를 갱신하고 좌표를 유지), `sortGraph`. 모든 함수는 입력을 변경하지 않는다.
4. `decompile.ts`: §7.2. `compile.ts`: §7.3. `validate.ts`: §6.5 전 코드(메시지 문자열 그대로)와 `issueCountsByNode`.
5. `diff.ts`: `planChanges`(§7.4–7.7), `mergeConfigs`·`rebase`(§7.9), `computeNodeChanges`, `configsEqual`, `stableStringify`.
   - `computeNodeChanges`: service, guardrail, prompt, cache, router는 담당 필드가 base와 다르면 'modified'. masker는 base에 없으면 'added', 다르면 'modified'. target은 refId가 null이면 'added', base와 다르면 'modified'. project는 base에 없던 접근이면 'added'.
   - `planChanges`는 `input.issues` 중 error를 `blockingIssues`로 옮긴다. 신규 서비스의 serviceKey 중복은 `remote.catalog.services` 기준으로 추가하고, `PLAN_TOO_LARGE`도 추가한다.
6. `history.ts`: §4.3. 불변이며 limit 기본 100.
7. 테스트: §10.1의 27개 케이스를 전부 구현한다. `loadTypeScript('../src/flow-designer/<module>.ts')`를 사용하고 픽스처는 파일 안에 둔다.

**수용 기준**:
- §10.1 전 케이스 통과, 특히 왕복 불변식 `compile(decompile(s)) ≡ s.config`.
- `planChanges`가 같은 입력에 같은 출력과 fingerprint를 낸다.
- 생성된 body는 §4.2 Body 타입 키만 포함한다.
- `types.ts`가 §4.2와 바이트 단위로 같다(공백 차이는 허용).
- `npx vue-tsc --noEmit` 통과(이 시점에 B/C 파일이 없어도 A 파일만으로 타입 오류 0).

**검증 명령**:
```bash
cd /c/project/aiconnect/frontend
node --test tests/flowDesignerModel.test.mjs
npx vue-tsc --noEmit
```

---

### WP-B · 캔버스·편집기 Vue 컴포넌트

**목표**: §8의 자체 구현 캔버스 엔진과 팔레트, 인스펙터, 툴바를 만든다. 상태는 소유하지 않는다. 모든 변경은 `command`/`action` 이벤트로 올린다.

**소유 파일(모두 신규)**:
- `frontend/src/flow-designer/FlowCanvas.vue`
- `frontend/src/flow-designer/FlowNodeCard.vue`
- `frontend/src/flow-designer/FlowPalette.vue`
- `frontend/src/flow-designer/FlowInspector.vue`
- `frontend/src/flow-designer/FlowToolbar.vue`

**사용 가능한 외부 모듈**: `vue`, WP-A 모듈(읽기 전용 import), `../CapabilityPicker.vue`(수정 금지, v-model 문자열). `../api` import는 금지다(네트워크 없음).

**컴포넌트 계약 (C가 이대로 사용)**:
```ts
// FlowCanvas.vue
defineProps<{ graph: FlowGraph; snapshot: ServiceFlowSnapshot; viewport: Viewport; selection: FlowSelection;
              issues: ValidationIssue[]; nodeChanges: Record<string, NodeChangeKind>; readOnly: boolean }>()
defineEmits<{ 'update:viewport': [Viewport]; 'update:selection': [FlowSelection]; command: [FlowCommand]; action: ['undo' | 'redo'] }>()
defineExpose({ fit(): void, centerOn(nodeId: string): void, viewportCenterWorld(): Point })
// FlowPalette.vue
defineProps<{ snapshot: ServiceFlowSnapshot; graph: FlowGraph; readOnly: boolean }>()
defineEmits<{ command: [FlowCommand]; 'select-node': [string] }>()
// FlowInspector.vue
defineProps<{ graph: FlowGraph; snapshot: ServiceFlowSnapshot; selection: FlowSelection; issues: ValidationIssue[]; readOnly: boolean }>()
defineEmits<{ command: [FlowCommand]; 'update:selection': [FlowSelection]; 'focus-node': [string] }>()
// FlowToolbar.vue
defineProps<{ canUndo: boolean; canRedo: boolean; changeCount: number; errorCount: number; warningCount: number;
              layoutDirty: boolean; layoutAvailable: boolean; busy: boolean; readOnly: boolean }>()
defineEmits<{ action: [FlowToolbarAction] }>()
// FlowNodeCard.vue (B 내부 전용)
defineProps<{ node: FlowNode; view: NodeView; ports: PortSpec[]; selected: boolean; change: NodeChangeKind | null;
              counts: { error: number; warning: number; info: number }; highlightPortIds: string[]; readOnly: boolean }>()
```

**동작 명세**:
1. FlowCanvas: §8.1–8.5를 구현한다.
   - 그래프는 props만 읽고 직접 변경하지 않는다.
   - 드래그 중에는 로컬 오프셋으로 그리고, 끝날 때 `moveNodes` 1회.
   - 와이어 드래그 중 `canConnect`로 호환 포트를 강조하고 `nearestCompatiblePort`로 스냅한다.
   - Delete 키는 현재 selection으로 `removeItems`. undo/redo 키는 `action` emit.
   - `fit()`는 `fitViewport(graphBounds(graph), 캔버스 크기)` 후 `update:viewport`. `centerOn`은 노드 중심을 화면 중앙에 둔다(zoom 유지).
   - `ResizeObserver`는 정리(unmount)해야 한다. `readOnly`이면 pan, zoom, 선택만 허용한다.
   - 드롭 처리는 §8.2.
2. FlowNodeCard: §8.1의 고정 치수를 정확히 지킨다(`NODE_WIDTH`, `HEADER_HEIGHT`, `PORT_ROW_HEIGHT`, `SUMMARY_HEIGHT`, `NODE_PADDING`, `COLLAPSED_HEIGHT` import). 포트 원은 `data-node-id`, `data-port-id`, `data-port-dir` 속성을 갖고 앵커 위치에 그린다. 시각 요소와 a11y는 §8.3–8.4.
3. FlowPalette:
   - 검색 입력 1개로 전 섹션을 필터링한다. 섹션은 '라우팅'([Target 추가], [마스킹 워커 추가], 마스커가 있으면 비활성), 'Deployment'(로컬 먼저, 이름, 모델 ID, 소스, 상태 칩, '캔버스에 있음' 칩), '프로젝트'(이름, 상태, '접근 허용됨' 칩).
   - 클릭: 캔버스에 있으면 `select-node`, 없으면 `addNode`(position null). 드래그는 §8.2 MIME. `readOnly`면 비활성.
4. FlowInspector: §8.7 인스펙터 규칙을 따른다. 필드 kind별 컨트롤:
   - text, textarea(maxLength), number/integer(min, max, step, nullable), boolean(`.toggle-field`), select, capabilities(CapabilityPicker), masker-select(부적격 disabled + trustReason).
   - deployment-binding → `bindTarget`, access-toggle → `setAccess`(`!snapshot.accessComplete`면 disabled + 사유 표시).
   - `visibleWhen`과 `newServiceOnly`(serviceId가 있으면 disabled)를 반영한다. 필드 옆에 해당 `field`의 이슈를 표시한다.
   - 가드레일 노드에는 "세부 탐지 정책은 데이터 보호 화면에서 관리합니다" `<a href="#data-protection">` 링크를 둔다.
   - 읽기 정보는 `nodeFacts`.
5. FlowToolbar: §8.7 버튼 구성. 각 버튼에 aria-label과 단축키 title을 둔다. `layoutAvailable=false`면 [배치 저장]을 숨긴다. `changeCount=0` 또는 busy면 [변경 검토]를 비활성화한다. `readOnly`면 편집 버튼을 비활성화한다. 1020px 미만에서는 palette/inspector 토글 버튼을 표시한다.
6. 스타일은 scoped와 `fd-` 접두사를 쓰고 기존 토큰만 사용한다. 전역 클래스(`primary-button` 등)는 재사용 가능하다.

**수용 기준**:
- `npm run build` 통과(vue-tsc 오류 0).
- §10.5의 3, 5, 15 항목을 개발 서버에서 확인한다(C 병합 전이라면 C 담당자가 Phase 3에서 확인).
- 노드 200개 그래프에서 드래그가 끊기지 않는다(개발자 도구 Performance로 대략 확인).
- 키보드만으로 노드 선택, 인스펙터 편집, Target 바인딩, 접근 토글, 삭제가 가능하다.
- 하드코딩 색상 0개(`#`, `rgb(` 검색. `transparent` 허용).

**검증 명령**:
```bash
cd /c/project/aiconnect/frontend
npm run build
grep -nE "#[0-9a-fA-F]{3,8}\b|rgb\(" src/flow-designer/*.vue || echo "no hardcoded colors"
```

---

### WP-C · 페이지 통합 · API 어댑터 · Plan/Apply UI · 초안 · 내비 · CI

**목표**: 데이터 로딩·정규화, Plan/Apply 실행, 페이지 상태 관리, ConsoleShell 연결, 테스트·CI 등록을 맡는다. **공유 통합 파일(ConsoleShell.vue, package.json, quality-gate.yml)의 유일한 소유자**다.

**소유 파일**:
- 신규:
  - `frontend/src/flow-designer/flowSnapshot.ts`
  - `frontend/src/flow-designer/flowApi.ts`
  - `frontend/src/flow-designer/execute.ts`
  - `frontend/src/flow-designer/draftStore.ts`
  - `frontend/src/flow-designer/FlowPlanModal.vue`
  - `frontend/src/flow-designer/FlowDesignerPage.vue`
  - (선택) `frontend/src/flow-designer/useFlowEditor.ts`
  - `frontend/tests/flowDesignerAdapter.test.mjs`
- 수정:
  - `frontend/src/ConsoleShell.vue`
  - `frontend/package.json`
  - `.github/workflows/quality-gate.yml`

**구현 명세**:
1. `flowSnapshot.ts`(순수, `../api` import 금지): §4.3 시그니처와 §10.2-1·2 규칙을 따른다.
   - 기본값은 WP-A의 `DEFAULT_SERVICE_STATE`/`DEFAULT_SCHEDULING`이고, 정규화에는 `normalizePrompt`, `canonicalCapabilities`를 쓴다.
   - 매핑: ServicePolicyView → ServiceState, TargetPolicyView → TargetState(key `target:<id>`), scheduling `.settings` → SchedulingState, DeploymentView/ProviderModelView → DeploymentInfo, EndpointView → EndpointInfo(`addressLabel = safeAddressLabel(baseUrl)`), ProviderView → ProviderInfo, 프로젝트 → ProjectInfo, MaskerDeploymentOption → MaskerInfo, DLP PolicyView → ServiceDataProtectionInfo.
   - `accessProjectIds`는 `projectAccess[pid]`에 serviceId가 들어 있는 프로젝트다.
2. `flowApi.ts`:
   - `createAdminTransport(auth)`는 `adminFetch(path, auth, { method, body: body===undefined ? undefined : JSON.stringify(body) })`로 구현한다.
   - `loadFlowSnapshot`은 §2.3의 읽기 API만, 아래 순서로 호출한다(병렬도 6, `SystemStructurePage`의 `read()` 패턴).
     1. 서비스 목록 — **필수**
     2. 서비스 지정 시 targets, scheduling(**필수**)과 data-protection(선택)
     3. 조직 runtime-endpoints → 각 deployments
     4. 조직 external-providers → 각 models
     5. 조직 projects → 서비스가 지정되어 있고 프로젝트 수가 200 이하일 때만 각 service-access
     6. masker-deployments
   - 실패한 선택 읽기는 `unavailable`에 경로를 넣는다. 반드시 **조직 경로**를 쓴다(`/api/admin/runtime-endpoints` 사용 금지).
   - `loadFlowLayout`, `saveFlowLayout`은 §9.3. `currentUserId()`.
3. `execute.ts`(순수): `operationRequest`(§7.5), `executePlan`(§7.8). transport 오류를 `TransportError`로 해석한다(`status`, `code`, `message`).
4. `draftStore.ts`(순수, 모듈 메모리): §4.3과 §8.9.
5. `FlowPlanModal.vue`:
   - props `{ open: boolean; plan: FlowPlan | null; phase: 'review' | 'applying' | 'done'; results: OperationResult[]; serviceLabel: string; busy: boolean }`
   - emits `close`, `apply`, `rebase`. `BaseModal size="lg"`. 구성은 §8.7 Plan 모달.
   - applying 중에는 닫기를 막는다(`close` 무시).
6. `FlowDesignerPage.vue`:
   - props `{ organizationId: string; auth: AdminAuth; platformAdmin: boolean }`.
   - 상태: `base`, `graph`(shallowRef), `viewport`, `selection`, `history`, `layout`(available, savedNodesFingerprint), `planState`, `readOnly`(§8.8 matchMedia, 로딩·적용 중 포함).
   - 파생 값: `issues = validateGraph(graph, base)`, `preview = planChanges({base, desired: compile(graph, base), remote: base, issues})`, `nodeChanges = computeNodeChanges(graph, base)`, `changeCount = preview.operations.length`.
   - 동작은 §7.1, §7.10, §8.7–8.9의 흐름 그대로다.
     - 초기 서비스: 해시 `#flow-designer/<id>` 2번째 세그먼트를 `decodeURIComponent`한 값, 없으면 목록의 첫 서비스, 목록이 비면 새 서비스 초안.
     - 서비스·조직 전환 시 초안을 보관한다.
     - 명령은 `applyCommand` → `recordHistory`. `error`는 메시지로, `select`는 selection으로 반영한다.
     - 툴바 action 매핑: undo/redo, fit은 `canvas.fit()`, auto-layout은 `{type:'autoLayout'}` 명령, validate는 선택 해제와 인스펙터 열기, plan, revert(confirm 후 `decompile(base, extractLayout(graph, viewport))`, history 기록), reload, save-layout.
     - palette `select-node`와 inspector `focus-node`는 선택 후 `canvas.centerOn`.
     - 위치 없는 `addNode`는 `canvas.viewportCenterWorld()`를 position으로 채워 다시 적용한다.
   - 제목 "서비스 플로우 디자이너", `EXPERIMENTAL` 배지, 설명 문구(§8.7).
   - 로딩 실패 시 오류 카드와 [다시 시도]. `SERVICE_NOT_FOUND`면 "서비스가 삭제되었거나 접근할 수 없습니다".
7. `ConsoleShell.vue`: **아래 5곳만** 추가한다. 다른 줄은 한 글자도 바꾸지 않는다. 기존 테스트 정규식(§2.1)을 보존한다.
   - (1) 25행 다음: `const FlowDesignerPage = defineAsyncComponent(() => import('./flow-designer/FlowDesignerPage.vue'))`
   - (2) `PageKey` 유니온에 `| 'flow-designer'`
   - (3) `adminNavItems`의 `services` 항목 바로 다음: `{ id: 'flow-designer', label: '플로우 디자이너', description: '노드 캔버스로 서비스 파이프라인 구성 · 실험', keywords: 'flow designer canvas node graph pipeline routing 플로우 디자이너 배치 노드 캔버스 실험 experimental', icon: '⌬', group: '운영' }`
   - (4) `knownPage` 배열에 `'flow-designer'`
   - (5) ServicesPage 렌더 줄 바로 다음: `<FlowDesignerPage v-else-if="isOrgOrPlatformAdmin && page === 'flow-designer'" :organization-id="organizationId" :auth="auth" :platform-admin="platformAdmin" />`
8. `package.json`: `"test:flow-designer": "node --test tests/flowDesignerModel.test.mjs tests/flowDesignerAdapter.test.mjs"`(scripts 끝에 추가).
9. `quality-gate.yml`: "Frontend behavior tests" 체인 끝에 ` && npm run test:flow-designer`.
10. 테스트: §10.2의 7개 그룹.

**수용 기준**:
- `npm run test:flow-designer` 통과.
- 기존 테스트 회귀 없음: `test:beta-operator`, `test:project-governance`, `test:date-time`, `test:topology`, `test:docs`, `test:totp-hierarchy` 등 CI 체인 전체.
- `npm run build` 통과(B 병합 후). 메인 청크 크기가 그대로이고 별도 청크가 생긴다.
- §10.5 체크리스트 전 항목(Phase 3, D 병합 후 14번 포함).
- D가 없는 백엔드에서도 [배치 저장]이 숨겨진 채 나머지 기능이 정상 동작한다.

**검증 명령**:
```bash
cd /c/project/aiconnect/frontend
node --test tests/flowDesignerAdapter.test.mjs
npm run test:flow-designer
npm run test:docs && npm run test:quota && npm run test:topology && npm run test:project-security && npm run test:runtime-status && npm run test:date-time && npm run test:playground-response && npm run test:external-model-deletion && npm run test:external-capabilities && npm run test:beta-operator && npm run test:totp-hierarchy && npm run test:project-governance
npm run build
```

---

### WP-D · 백엔드 레이아웃 영속화 (Flyway V53 + 조직 스코프 API)

**목표**: §9.2의 `GET/PUT /api/admin/services/{serviceId}/flow-layout`을 기존 동작 변경 없이 추가한다.

**소유 파일**:
- 신규:
  - `src/main/resources/db/migration/V53__service_flow_layout.sql`
  - `src/main/java/com/aiconnect/llmgateway/flowdesigner/ServiceFlowLayout.java`
  - `src/main/java/com/aiconnect/llmgateway/flowdesigner/ServiceFlowLayoutRepository.java`
  - `src/main/java/com/aiconnect/llmgateway/flowdesigner/ServiceFlowLayoutDtos.java`
  - `src/main/java/com/aiconnect/llmgateway/flowdesigner/ServiceFlowLayoutService.java`
  - `src/main/java/com/aiconnect/llmgateway/flowdesigner/ServiceFlowLayoutController.java`
  - `src/test/java/com/aiconnect/llmgateway/ServiceFlowLayoutIntegrationTest.java`
- 수정:
  - `docs/openapi.yaml`: `/api/admin/services/{serviceId}/flow-layout` 경로(get, put)와 스키마 `ServiceFlowLayout`, `ServiceFlowLayoutView`를 추가한다. 기존 항목은 수정하지 않는다. 233행 근처의 서비스 경로 블록 뒤에 둔다.
  - `src/test/java/com/aiconnect/llmgateway/FlywayUpgradeIntegrationTest.java`: 테이블 존재 단언 1줄을 추가한다(기존 `service_request_scheduling` 단언과 같은 형식).

**금지**: `OrganizationAuthorizationFilter`, `PlatformAdministrationService`, 기존 엔티티·리포지토리·컨트롤러 수정. FK는 CASCADE로 처리한다. 새 의존성 추가 금지.

**구현 명세**:
1. 마이그레이션 SQL은 §9.2 그대로. 엔티티 컬럼 정의는 `ddl-auto: validate`와 일치해야 한다: UUID는 `columnDefinition="char(36)"`, layoutJson은 `"text"`, revision은 `long`, 시각은 `Instant`.
2. 서비스 로직:
   - `requireEditableService(serviceId, http)`: 404 `SERVICE_NOT_FOUND`(없거나 deleted) → 권한 검사(§9.2, `DataProtectionPolicyController.isOrganizationOrPlatformAdmin`과 같은 방식, `TeamAccessService`는 `@Autowired(required=false)`) → 403 `ORGANIZATION_ADMIN_REQUIRED`.
   - `get`: 저장본이 없거나 저장된 organizationId가 서비스 조직과 다르면 `new LayoutView(serviceId, 0, null, null)`. 있으면 `objectMapper.readValue(json, FlowLayoutPayload.class)`.
   - `save`: `validateAndNormalize` → `objectMapper.writeValueAsString` → 길이 > 60000이면 `FLOW_LAYOUT_TOO_LARGE`. upsert(없으면 revision 1로 생성, 있으면 `replace`). `@Transactional`.
3. 검증 규칙은 §9.2 그대로이며 오류 코드는 `FLOW_LAYOUT_INVALID`(메시지에 실패 이유 요약, 사용자 입력 원문은 넣지 않음).
4. 컨트롤러는 얇게 만든다. `@PutMapping`은 `@RequestBody FlowLayoutPayload`. 응답은 `LayoutView`.
5. 통합 테스트는 §10.3의 7개 시나리오. `RoutingPolicyManagementIntegrationTest`(엔티티 직접 생성)와 `ScopedRuntimeInventoryIntegrationTest`(사용자 토큰 획득) 패턴을 쓴다.

**수용 기준**:
- 신규 테스트가 통과한다.
- `DatabaseSchemaIntegrationTest`(JPA 매핑), `MigrationVersionOrderingTest`(V53이 순서 증가), `OpenApiContractTest`(yaml 파싱), `PlatformAdministrationIntegrationTest`(조직 삭제 경로 회귀 없음)가 통과한다.
- 응답에 비밀값이나 모르는 필드가 없다.
- 기존 파일 diff는 openapi.yaml 추가 블록과 FlywayUpgrade 1줄뿐이다.

**검증 명령** (Git Bash):
```bash
cd /c/project/aiconnect
G="C:/Users/kjs99/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle"
export JAVA_HOME="C:/Users/kjs99/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1"
"$G" test --tests 'com.aiconnect.llmgateway.ServiceFlowLayoutIntegrationTest' --no-daemon
"$G" test --tests 'com.aiconnect.llmgateway.DatabaseSchemaIntegrationTest' --tests 'com.aiconnect.llmgateway.MigrationVersionOrderingTest' --tests 'com.aiconnect.llmgateway.OpenApiContractTest' --tests 'com.aiconnect.llmgateway.PlatformAdministrationIntegrationTest' --no-daemon
```
(`FlywayUpgradeIntegrationTest`는 `TEST_MARIADB_URL`이 있을 때만 실행된다. 로컬에서는 건너뛰고 CI에서 검증된다.)

---

## 12. 리스크 & 후속 과제

### 12.1 리스크

| 리스크 | 영향 | 완화 |
|---|---|---|
| 일괄 적용이 트랜잭션이 아님 | 부분 적용 상태 | 순서 설계(§7.6), 첫 실패에서 중단, 작업별 보고, 자동 rebase로 남은 변경 유지 |
| 서버에 낙관적 잠금 버전이 없음 | 확인과 실행 사이의 경합 | 적용 직전 재조회와 fingerprint 비교. v2에서 서버 `updatedAt`/ETag 도입 |
| guardrailAction의 DLP 부수효과(N4) | 탐지 항목 강제 활성화 | 변경 시에만 전송하고 경고 노트 표시 |
| 동시성 재정의 해제 불가(N5) | 사용자 혼란 | 명시적 검증 오류와 대안 안내 |
| tombstone 서비스 key 재사용 불가(N10) | 생성 실패, 오류 메시지가 불친절할 수 있음(ApiExceptionHandler에 DataIntegrityViolation 매핑이 없음, **응답 코드 미검증**) | 실패 작업 메시지를 그대로 표시. 후속으로 서버 매핑 개선 |
| DeploymentView에 archived 플래그 없음 | 카탈로그에 보관(archived) Deployment가 보이고 생성 시 `DEPLOYMENT_NOT_FOUND` | 실패 보고. 후속으로 서버 뷰에 플래그 추가 |
| 프로젝트 접근 조회가 N+1(최대 200) | 대형 조직에서 로딩 지연·제한 | 병렬도 6, 200 초과 시 `accessComplete=false`로 접근 편집 비활성 |
| 감사 로그 증가 | 적용 작업마다 기록 | 정상(감사가 목적). 레이아웃은 수동 저장만 |
| 자체 캔버스 엔진 복잡도 | 버그와 접근성 공백 | 순수 기하 함수를 단위 테스트(§10.1-24·25), 키보드 대체 경로 |
| 실제 조직 관리자 범위(N15) | 비플랫폼 ORGANIZATION_ADMIN이 DIRECTOR로 승격되어 사실상 시스템 관리자 전용 | ServicesPage와 같은 노출 정책. 별도 조치 없음 |
| ConsoleShell 해시 해석 의존 | `#flow-designer/<id>` 동작이 `split('/')[0]` 규칙에 의존 | C 테스트에서 소스 검사. ConsoleShell 수정 최소화 |

### 12.2 이 설계에서 발견했지만 범위 밖으로 둔 기존 이슈 (수정하지 않음)

- `ServicesPage.vue:326` 캐시 TTL을 5까지 허용하지만 서버는 `@Min(10)`(`RoutingPolicyController.java:107`) → 5–9를 입력하면 400.
- `ServicesPage.vue:104`는 플랫폼 관리자에게 전 조직 Runtime의 Deployment를 노출한다(타 조직을 선택하면 서버가 `ORGANIZATION_MISMATCH`).
- `ServicesPage.savePipeline`은 guardrailAction을 항상 전송하므로 저장할 때마다 서비스 DLP 탐지 항목이 재설정된다(`ServicesPage.vue:322`, N4).
- `RoutingPolicyPanel.vue`는 어디에서도 사용되지 않는다.

### 12.3 후속 과제 (v2 이후)

1. 서버 측 원자적 적용 API(`POST /api/admin/services/{id}/flow-apply`, 트랜잭션과 버전 검사)
2. 서비스·Target 응답에 `updatedAt`/버전을 넣어 낙관적 잠금
3. 가드레일 노드에서 `pipeline-preview` 시뮬레이터, 서비스 DLP 세부 정책 노드(`PUT /services/{id}/data-protection`)
4. 프로젝트 외부 AI 승인(external-access) 노드, API 키 노드
5. 조직 전체 다중 서비스 캔버스, 미니맵, 복사/붙여넣기, 템플릿
6. 터치 핀치 줌, 실시간 공동 편집 표시
7. ServicesPage "캔버스에서 열기" 링크(딥링크 `#flow-designer/<serviceId>` 재사용)
8. 실험 종료 기준: 4주 사용 지표(적용 성공률, 충돌·부분 실패 빈도)에 따라 정식화하거나 제거한다. 제거는 `flow-designer/` 디렉터리 삭제, ConsoleShell 5곳 원복, `flowdesigner` 패키지 삭제, 테이블 drop 마이그레이션 순서로 한다.
