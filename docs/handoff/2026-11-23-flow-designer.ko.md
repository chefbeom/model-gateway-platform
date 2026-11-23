# 작업 인계: 서비스 플로우 디자이너(실험)

> 작성: Claude Code (Opus 5.5) · 2026-11-23
> 대상: 이 저장소를 이어서 작업하는 에이전트(Antigravity, Codex 등)
> 기준: 브랜치 `claude/flow-designer`, 커밋 `fd0290f` (부모 `8e4fcd5`, `main`과 같음)

## 1. 무엇을 했나

ComfyUI/n8n처럼 노드를 캔버스에 배치하고 와이어로 연결해 설정하는 GUI **"서비스 플로우 디자이너"**를 추가했다.

- 관리 콘솔 메뉴 `운영 → 플로우 디자이너`, 페이지 키 `flow-designer`, 딥링크 `#flow-designer/<serviceId>`
- 논리 서비스 하나의 요청 파이프라인을 왼쪽에서 오른쪽으로 그린다.
  `서비스 → 가드레일·DLP → 프롬프트·파라미터 → 응답 캐시 → 라우터 → Target → Deployment → Runtime/Provider`
- 캔버스에서 바꾼 내용은 **기존 관리 API 호출 목록(Plan)으로 컴파일**된다. "변경 검토" 모달에서 확인한 뒤 "적용"을 눌러야만 서버에 쓴다.
- **사용자 요구사항: 기존 기능을 대체하지 않는 추가 GUI다.** 기존 페이지(ServicesPage 등), 기존 API와 백엔드 서비스 로직은 변경하지 않았다.

## 2. 어떻게 했나

| 단계 | 담당 | 결과 |
|---|---|---|
| 설계 분석·작업 지시서 | Claude Opus 5.5 (xhigh) | [`docs/flow-designer-design.ko.md`](../flow-designer-design.ko.md), 약 1,700줄. 현행 API·필드를 `파일:줄` 근거로 정리(§2), 핵심 결정 10개(§3), 타입 전문(§4.2), 작업 지시서 WP-A~D(§11) |
| WP-A 순수 TS 코어 | Claude Sonnet 5.5 (medium) | 타입, 노드 카탈로그, 그래프 명령, decompile/compile, 검증, 3-way diff/rebase, Undo |
| WP-D 백엔드 레이아웃 저장 | Claude Sonnet 5.5 (medium) | Flyway V53, `flowdesigner` 패키지, 통합 테스트 |
| WP-B 캔버스 컴포넌트 | Claude Sonnet 5.5 (medium) | 캔버스 엔진, 노드 카드, 팔레트, 인스펙터, 툴바 |
| WP-C 페이지 통합 | Claude Sonnet 5.5 (medium) | 데이터 로딩, Plan/Apply 실행기, 초안 보관, ConsoleShell 연결, CI |
| 통합·브라우저 검증 | Claude Opus 5.5 | 로컬 백엔드와 실제 브라우저로 검증, 버그 7건을 수정 작업으로 재지시 |

A와 D, B와 C를 각각 병렬로 진행했다. 패키지마다 파일 소유를 엄격히 나눠 충돌이 없었다.

## 3. 핵심 설계 결정 (상세는 설계서 §3)

- **서비스 1개 = 캔버스 1개.** 단계 노드 5개(service, guardrail, prompt, cache, router)는 서버 처리 순서가 고정이라 재배열·삭제가 불가능하다.
- **엣지는 데이터를 갖지 않는다.** 우선순위·가중치는 Target 노드에 둔다. 라우터에서 Deployment로 와이어를 그으면 Target 노드가 자동 삽입된다.
- **서버가 유일한 진실 원천이다.** 적용 흐름은 `Plan → 검토 → Apply`이고, 적용 직전에 서버를 다시 읽어 3-way 비교를 한다. 충돌이 있으면 적용을 차단하고, 실행은 순차로 하며 첫 실패에서 멈춘다(자동 롤백 없음). 실행 후에는 자동 rebase한다.
- **레이아웃만 서버에 저장한다.** 테이블 `service_flow_layout`(V53, FK `ON DELETE CASCADE`), API `GET/PUT /api/admin/services/{id}/flow-layout`. 좌표, 접힘, 뷰포트만 저장한다. 자동 저장은 하지 않는다(감사 로그 폭증 방지). 저장 시점은 [배치 저장]을 누를 때와 적용 직후 1회다.
- **캔버스 엔진은 자체 구현이다**(HTML 노드 + SVG 와이어). npm 의존성은 추가하지 않았다. 페이지는 지연 로딩되는 별도 청크로 분리되어 메인 번들 크기는 그대로다.
- **노출 범위:** `adminNavItems`에만 추가했다(`isOrgOrPlatformAdmin`, ServicesPage와 같은 노출 정책).

## 4. 변경 파일

- 신규 프런트: `frontend/src/flow-designer/` (21개 파일), `frontend/tests/flowDesignerModel.test.mjs`, `frontend/tests/flowDesignerAdapter.test.mjs`
- 신규 백엔드: `src/main/java/com/aiconnect/llmgateway/flowdesigner/` (5개), `V53__service_flow_layout.sql`, `ServiceFlowLayoutIntegrationTest.java`
- 기존 파일 수정(추가만 함):
  - `ConsoleShell.vue`: 정확히 5곳(import, PageKey, nav 항목, knownPage, 렌더 줄)
  - `frontend/package.json`: `test:flow-designer` 추가
  - `.github/workflows/quality-gate.yml`: 테스트 체인에 `test:flow-designer` 추가
  - `docs/openapi.yaml`: flow-layout 경로와 스키마 추가
  - `FlywayUpgradeIntegrationTest.java`: 테이블 존재 단언 1줄

## 5. 검증 결과

- 프런트: `npm run test:flow-designer` **58/58** (`flowDesignerAdapter.test.mjs`에 프로젝트 접근 노드 추가 → 접근 부여/해제 Plan 컴파일 → `executePlan` → `rebase` 시나리오 추가), 기존 포함 전체 프런트엔드 테스트 **153/153** 통과, `npm run build` (`vue-tsc --noEmit && vite build`) 성공
- 백엔드(대상 테스트): `ServiceFlowLayoutIntegrationTest` **7/7** (실제 TOTP 인증 `platformAdmin` JWT 세션의 프로젝트 목록 조회·접근 부여/해제·`flow-layout` 저장 및 `updatedBy` 기록 검증 포함), `DatabaseSchemaIntegrationTest`, `MigrationVersionOrderingTest`, `OpenApiContractTest`, `PlatformAdministrationIntegrationTest` 통과 (선행 커밋 `fd0290f`에서 `RoutingPolicyManagement` 통과)
- CI (`AICONNECT quality gate`, PR `#9`, run `45153027120`): `Frontend typecheck and build`, `Compose and image contract`, `Backend test and package`(MariaDB 11.4 · Redis 7.4 서비스 포함 `gradle clean test bootJar`) 3개 잡 모두 `success`
- 실제 브라우저(로컬 백엔드 + 시드 데이터, 폰트 배율 115%)에서 아래 항목을 서버 반영까지 확인했다.
  - Target 우선순위 변경, 응답 캐시 켜기
  - 와이어로 Target 추가(임시 노드 id가 실제 id로 교체됨), Delete 키로 Target 삭제
  - 동시 편집 충돌: "기준 100 · 내 변경 50 · 서버 70"을 표시하고 적용 버튼을 비활성화
  - 노드 드래그와 그리드 스냅, 배치 저장(서버 revision 증가), Undo/Redo
  - 해시 딥링크로 서비스 전환
  - 새 서비스 초안 생성: 서비스 생성 + Target 생성 후 URL이 새 id로 바뀜
- 검증 중 발견해 고친 버그
  - 콘솔의 CSS `zoom`(폰트 배율) 때문에 마우스 좌표가 어긋남(와이어 연결 실패)
  - 좁은 화면에서 캔버스 폭이 0px로 줄어듦
  - 크기가 0일 때 화면 맞춤이 실행됨
  - 해시 변경에 반응하지 않음
  - 팔레트로 추가한 노드가 기존 노드와 겹침
  - 토글에 접근성 이름이 없음
  - 노드 드래그가 반영되지 않음(`moved` 플래그를 리셋한 뒤에 읽던 버그)

## 6. 현재 상태와 남은 일

- **브랜치 `claude/flow-designer`에 푸시했고(PR `#9` OPEN), 아직 `main`에 병합·운영 배포하지 않았다.** 병합과 배포 여부는 사용자가 결정한다.
- 추가 확인(후속 검증)
  - 실제 TOTP 로그인 `platformAdmin` JWT 세션을 사용한 `GET /organizations/{id}/projects`, `POST/DELETE /projects/{id}/service-access`, `PUT /services/{id}/flow-layout` 및 프런트 캔버스 접근 부여·해제 `Plan → Execute → Rebase` 자동화 검증을 완료했다. (비상용 `X-Admin-Token`에서는 `canViewProject` 필터에 의해 프로젝트 목록이 빈 배열로 반환되는 기존 동작 유지)
  - MariaDB 11.4 대상 `gradle clean test bootJar`는 PR `#9` GitHub Actions CI(`45153027120`)에서 통과했다. (실제 운영 브라우저 화면에서의 로그인 계정 기반 프로젝트 접근 클릭 수동 확인만 선택 과제로 남음)
- 범위 밖으로 남긴 기존 버그(설계서 §12.2, 수정하지 않음)
  - `ServicesPage.vue`: 캐시 TTL 하한이 5지만 서버는 `@Min(10)`이라 5~9를 입력하면 400
  - `ServicesPage.savePipeline`: `guardrailAction`을 항상 전송해서 저장할 때마다 서비스 DLP 탐지 항목이 재설정됨
  - `ServicesPage.vue`: 플랫폼 관리자에게 다른 조직의 Deployment가 노출됨
  - `RoutingPolicyPanel.vue`: 어디에서도 쓰지 않는 컴포넌트
- 후속 과제 후보(설계서 §12.3): 서버 측 원자적 적용 API와 낙관적 잠금, 가드레일 시뮬레이터 노드, 조직 전체 캔버스, 미니맵, ServicesPage의 "캔버스에서 열기" 링크

## 7. 이어서 작업할 때 주의할 점

- **추가 원칙을 지킬 것.** 플로우 디자이너 때문에 기존 페이지나 API의 동작을 바꾸지 않는다. ConsoleShell 수정은 5곳뿐이며 기존 테스트(`betaOperator`, `projectGovernance` 등)가 ConsoleShell 소스를 정규식으로 검사한다.
- **캔버스 좌표:** `.console-shell`에 `zoom: var(--console-zoom)`(기본 1.15)이 걸려 있다. 포인터 좌표는 `layout.ts`의 `clientToLocal`/`clientScale`로 변환해야 한다.
- `frontend/src/flow-designer/`의 WP-A 모듈(`types`~`history`)은 순수 TS다. `vue`, DOM, 무작위 값, 현재 시각을 쓰지 않는다. `node --test`로 테스트한다.
- 일부 파일은 CRLF 줄바꿈이다(`.gitattributes`가 정규화한다).
- 로컬 실행 방법(Docker·MariaDB 없이)
  - JDK 경로: `C:/Users/kjs99/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1`
  - Gradle 경로: `C:/Users/kjs99/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle`
  - 실행: `gradle bootTestRun --args='--spring.profiles.active=integration --server.port=8080'`. H2 메모리 DB를 쓰며 데이터가 비어 있으니 `/api/admin` POST로 시드한다. 관리 토큰은 테스트 프로필 값 `integration-admin-token`이다.
  - 프런트: `cd frontend && npm run dev`(5173 포트, `/api`는 8080으로 프록시)
- 커밋하지 않고 남겨둔 파일(이번 작업과 무관하거나 로컬 전용): 루트의 PDF 3개, `docs/portfolio/`, `.claude/launch.json`(브라우저 검증용 dev 서버 설정)
