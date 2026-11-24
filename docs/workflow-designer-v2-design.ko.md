# 워크플로우 디자이너 v2 설계서 (서비스 플로우 디자이너 개편)

> 상태: v2 설계 확정(구현 전) · 브랜치 `claude/workflow-redesign` · 기준 커밋 `7be2814`(main `c3aa3a3` + 전체 화면·툴바 줌 베이스라인) · 작성 2026-11-24
> 이 문서는 설계와 작업 지시서만 담는다. 작성 시점에 소스 코드는 바꾸지 않았다.
> 선행 문서: v1 설계서 `docs/flow-designer-design.ko.md`(이하 "v1 §n"), 인계 `docs/handoff/2026-11-23-flow-designer.ko.md`. v1에서 바뀌지 않는 규칙(3-way diff, 실행기, 초안 보관, 레이아웃 API 권한·검증, 동시성 정책)은 다시 쓰지 않고 "v1 §n 유지"로 참조한다.
> 표기: 설명은 한국어, 식별자·경로·코드는 영어. `파일:줄`은 기준 커밋에서 직접 확인한 위치다.

---

## 1. 개요 · 요구사항 해석

### 1.1 한 줄 요약

v1의 "고정 단계 노드 5개(서비스·가드레일·프롬프트·캐시·라우터)" 캔버스를 **시작 → 프로젝트 → LLM** 세 노드만으로 동작하는 자유 조합형 **워크플로우**로 바꾼다. 가드레일·시스템 프롬프트·응답 캐시·생성 파라미터·라우터는 필요할 때 **간선 위에 끼워 넣는 선택 노드**다. 화면 배치는 **노드의 존재 여부와 고정 ID**만으로 서버 설정에 컴파일되므로(compile-by-presence) 서버의 고정 처리 순서와 충돌하지 않는다. 외부 노출 방지 정책이 켜져 있으면 해당 가드레일은 **보호(잠금) 노드** 또는 **상속 정책 노드**로 반드시 워크플로우에 남는다. LLM 바로 앞에 둔 시스템 프롬프트가 그 모델에만 적용되도록 백엔드에 Target 단위 프롬프트(Flyway V54)를 추가한다.

### 1.2 요구사항 → 해석 → 결정

| # | 사용자 요구(요지) | 해석 | 결정(절) |
|---|---|---|---|
| R1 | 가드레일 강제 대신 노드·설정 자유 조합 | 가드레일·프롬프트·캐시·파라미터·라우터를 선택 노드로 바꾼다. 노드가 있으면 그 설정, 없으면 서버 기본값이다. 노드 삭제는 "설정 해제"로 컴파일된다 | D2, §5.5, §7.3 |
| R1a | 서비스·프로젝트·모델에 외부 노출 방지가 켜져 있으면 해당 가드레일 필수 | 보호의 **소유자**에 따라 강제 방식을 나눈다. (a) 이 서비스가 가진 보호(데이터 경계 ≠ 외부 허용, 민감정보 처리 ≠ ALLOW)는 가드레일 노드가 항상 존재하고 **보호 잠금**된다(명시적 '보호 해제' 없이 삭제 불가). (b) 프로젝트가 가진 보호(외부 AI 전송 금지·데이터 경계·프로젝트 DLP)는 그 프로젝트 경로에 **상속 정책 노드**가 자동으로 들어가고 삭제·편집할 수 없다. 보호가 의미를 잃는 구성(로컬 LLM 없음 등)은 경고와 빠른 수정으로 알린다 | D4, §6.9 |
| R1b | 마스킹 워커 같은 가드레일 하위 노드도 자유 | 마스커는 가드레일의 종속 노드다. 자유롭게 추가·삭제한다(삭제는 v1처럼 위험 작업) | §5.6 |
| R1c | 기본 워크플로우는 시작 → 프로젝트 → LLM | 새 워크플로우는 시작 노드 하나로 시작한다. 프로젝트와 LLM만 추가하면 적용할 수 있다 | D3, §10.2 |
| R1d | 종속 노드 동반 이동 | "그 노드 때문에만 존재하는 노드"를 정의해(§5.6) 드래그·화살표 이동 때 함께 옮긴다. Ctrl/⌘를 누르면 단독 이동한다 | §6.8 |
| R1e | 기존 간선에 노드를 넣으면 끼워 넣기 | 팔레트 항목을 간선 위에 놓기, 간선 중앙 ＋ 버튼, 인스펙터의 '이 간선에 노드 넣기' 세 경로를 지원한다 | §6.6 |
| R1f | LLM 앞 시스템 프롬프트는 그 LLM에 적용 | 공통 프롬프트(모든 LLM, 서비스 단위)와 모델별 프롬프트(특정 LLM 바로 앞, Target 단위)를 둔다. 모델별은 백엔드 확장이다 | D6, §8 |
| R2 | 용어: 워크플로우/노드/간선 | 사용자에게 보이는 문자열만 바꾼다. 페이지 키·라우트·API·CSS 접두사는 유지한다 | D10, §4 |
| R3 | 팔레트 정리 | 역할별 접이식 그룹으로 나눈다. LLM은 로컬/외부로 나누고 활성·로드된 모델을 먼저 보인다. 중지·미로드 모델은 기본으로 숨기고 토글로 보인다 | D9, §9 |
| 목표 | 초보자는 최소 단계, 전문가는 상세 설계 | 기본/고급 모드, 빈 워크플로우 빠른 시작 템플릿, 정해진 위치로 자동 배선, 간선 ＋ 버튼 | D8, §10 |

### 1.3 사용자 표현과 다르게 해석한 부분 (이탈 사항)

1. **"시작 = 사용하는 앱(서비스)·API 키"** → 시작 노드는 앱이나 키 자체가 아니라 **호출 진입점**(`POST /v1/chat/completions`, `model=<serviceKey>`)이다. 서비스 단위로 저장되는 "앱" 엔터티가 없고, 서비스 접근 권한도 키가 아니라 **프로젝트** 단위다(`ChatCompletionGateway.java:97`, `project_service_access`). 그래서 앱(키)의 소속은 프로젝트 노드가 대표하고, 시작 노드는 서비스 정체성(호출 이름·표시 이름·사용 여부·Capability·단가)을 담는다. 키 목록은 v1처럼 조회하지 않는다(비밀값 최소화, v1 §9.1).
2. **"서비스·프로젝트·모델에 외부 노출 방지가 켜지면 가드레일 필수"** → 모델(Deployment)에는 외부 노출 방지 설정이 없다(`ModelDeployment.java:10-39` 필드 확인, 외부 여부만 있음). 이 저장소에서 "모델"은 **논리 모델 = 서비스**를 뜻하므로(`MODEL_NOT_FOUND "The requested logical model does not exist."`, `ChatCompletionGateway.java:96`) 서비스로 해석한다. 외부 LLM 노드가 있는지는 "보호가 실제로 의미 있는 조건"으로 쓴다. 또 파이프라인은 서비스 단위로 공유되므로, 프로젝트 정책 때문에 **서비스** 가드레일을 강제로 넣으면 다른 프로젝트의 동작까지 바뀐다. 그래서 프로젝트 정책은 그 프로젝트 경로에만 놓이는 **읽기 전용 상속 노드**로 표현하고, 서비스 가드레일이 없다는 이유로 적용을 일괄 차단하지 않는다.
3. **조직·플랫폼·API 키 단위 DLP/데이터 경계**는 사용자 목록에 없고 서버가 항상 먼저 집행한다. 노드로 만들지 않고 시작 노드의 배지·읽기 정보와 정보 수준 검증으로만 보여 준다.
4. **완전한 자유 배치**는 서버 순서가 고정이라 그대로 실현할 수 없다. 공통 경로(트렁크) 위의 순서는 자유롭게 두되, 서버 처리 순서와 다르면 경고와 [순서 정리]를 제공한다. 서버에 대응 개념이 없는 배치(라우터 뒤 가드레일, 분기마다 다른 캐시 등)는 오류로 막는다.
5. **LLM이 두 개 이상**이면 라우터 노드가 필요하다(Failover·Retry·스케줄링 정책의 자리). 두 번째 LLM을 붙이면 라우터를 자동으로 넣는다.
6. **프롬프트와 파라미터 분리**: v1의 '프롬프트·파라미터' 노드를 '시스템 프롬프트'와 '생성 파라미터'(고급)로 나눈다. 생성 파라미터(temperature·reasoning·fast)는 서비스 단위만 지원한다. Target 단위는 후속 과제다.
7. **내부 식별자 유지**: 페이지 키 `flow-designer`, 해시 `#flow-designer/<id>`, API `/flow-layout`, CSS 접두사 `fd-`, 파일명 `Flow*`, 타입명 `Flow*`는 바꾸지 않는다(§4.3).

### 1.4 목표 · 비목표

| # | 목표 | 판정 기준 |
|---|---|---|
| G1 | 기본 워크플로우 3노드로 적용 가능 | 새 초안 + 프로젝트 1 + LLM 1 → Plan `create-service, create-target, grant-access`(필요 시 `patch-service`) |
| G2 | 자유 조합의 결정적 컴파일 | 같은 노드 집합이면 좌표·트렁크 순서와 무관하게 같은 `FlowConfigState`가 나온다 |
| G3 | 정책 기반 가드레일 강제 | §6.9의 보호 잠금·상속 노드 규칙과 테스트 |
| G4 | v1 원칙 유지 | Plan → 검토 → Apply, 재조회 + 3-way, 충돌 차단, 순차 실행, rebase, 서버가 진실 원천, npm 의존성 0 |
| G5 | 순수 추가 | 다른 페이지·기존 API 동작 무변경. 백엔드는 nullable 컬럼·선택 필드·정규식 상위 호환 확장만 한다 |
| G6 | 왕복 불변식 | 모든 서버 상태 s에서 `compile(decompile(s, null), s) ≡ s.config` |
| G7 | 초보자·전문가 모두 지원 | 기본/고급 모드, 빈 워크플로우 템플릿, 자동 배선, 간선 ＋ 삽입 |

**비목표(v2에서 하지 않음)**: 프로젝트별 처리 경로(서버가 서비스 단위 공유 파이프라인만 지원), 캔버스에서 프로젝트 정책(외부 AI 전송 금지·경계) 편집, API 키 노드, Target 단위 temperature·reasoning, 조직 전체 워크플로우, 복사·붙여넣기, 미니맵, 서버 원자 적용 API, 가드레일 시뮬레이터(`pipeline-preview`), 기존 화면(ServicesPage 등)에 모델별 프롬프트 표시.

---

## 2. 현행 분석

### 2.1 v1 그래프 구조와 한계

| 항목 | 현행 | 위치 | v2 처리 |
|---|---|---|---|
| 고정 싱글턴 5개와 잠긴 pipeline 간선 4개 | `PIPELINE_EDGES`, `createSkeletonGraph` | `frontend/src/flow-designer/graphOps.ts:73-86` | 제거. 스켈레톤은 시작 노드 하나 |
| 단계 노드 삭제 불가 | `deletable: false`, `removeItems`의 '고정 노드' 거부 | `catalog.ts:195-261`, `graphOps.ts:406-407` | 선택 노드화. 보호·파생·시작만 잠금 |
| 구조 손상 검증 | `STRUCTURE_BROKEN`(싱글턴·pipeline 간선 누락) | `validate.ts:7-13, 72-76` | 제거. `WF_*` 구조 검증(§6.12) |
| Target·Deployment 이중 노드 | 라우터→Deployment 연결 시 Target 자동 삽입 | `catalog.ts:262-281`, `graphOps.ts:105-108, 439-448` | LLM 노드 하나로 합침(D5) |
| 노드 ID = 레이아웃 키 | 프런트·백엔드가 같은 정규식 | `layout.ts:26`, `ServiceFlowLayoutService.java:31-32` | 상위 호환 확장(§7.8) |
| compile이 싱글턴 ID의 props를 읽음 | 노드가 없으면 base 값 | `compile.ts:72-155` | 존재 기반으로 확장(부재 = 서버 기본값) |
| 역컴파일·동기화 | `syncGraphToConfig`, `decompile` | `decompile.ts:12-100` | 재작성(§7.4–7.5) |
| diff/plan은 도메인 상태만 다룸 | `configsEqual`, `planChanges`가 `FlowConfigState`만 사용 | `diff.ts:69-83, 340-628` | **재사용**. Target 프롬프트 필드만 추가(§7.6) |
| 그래프 의존 diff | `rebase`(Target 노드 이름 변경·분리), `computeNodeChanges` | `diff.ts:309-337, 630-665` | v2 노드 규칙으로 재작성 |
| 팔레트 | 평면 3섹션(라우팅, Deployment 전체, 프로젝트), 숨김·그룹 없음 | `FlowPalette.vue:31-59, 90-171` | 그룹·숨김·정렬을 순수 TS로(§9) |
| 캔버스 DnD 허용 타입·간선 삭제 버튼 | `ADDABLE`, `[data-edge-delete]` | `FlowCanvas.vue:56, 312, 674-690, 772-780` | 새 타입, 간선 ＋ 버튼, 간선 위 놓기 |
| 인스펙터 Target 바인딩 | `bindTarget`, `deployment-binding` | `FlowInspector.vue:203-233, 407` | '모델 교체'(`replaceModel`) |
| 사용자 노출 용어 | "서비스 플로우 디자이너", "연결", "파이프라인" | `FlowDesignerPage.vue:33-35, 48`, `FlowToolbar.vue:28`, `FlowCanvas.vue:727-728, 749-750, 777`, `FlowInspector.vue:447-465`, `validate.ts:68, 73, 75`, `graphOps.ts:32-38`, `ConsoleShell.vue:61` | §4 표대로 교체 |
| 베이스라인 `7be2814` | 전체 화면 작업 영역, 툴바 −/100%/＋ | `style.css:682-707`, `FlowDesignerPage.vue:113-135`, `FlowToolbar.vue:41-45`, `flowDesignerAdapter.test.mjs:458-480` | 그대로 보존(정규식 테스트가 고정) |

### 2.2 서버 요청 처리 순서(변경 불가)와 v2 노드 대응

| 순서 | 서버 처리 | 위치 | v2 노드 |
|---|---|---|---|
| 1 | API 키 인증 → `model`로 활성 서비스 조회 → 프로젝트 접근 확인 | `ChatCompletionGateway.java:90, 95-99` | 시작, 프로젝트, 시작→프로젝트 간선 |
| 2 | 데이터 보호 inspect(플랫폼·조직·프로젝트·키·서비스 정책 병합), 차단 시 403 | `:103, :114-120` | 가드레일(민감정보 처리), 상속 정책 |
| 3 | 라우팅 평가(데이터 경계·프로젝트 외부 차단·Capability·외부 승인). **외부 Target은 priority와 무관하게 로컬 뒤** | `:107-110`, `RoutingService.java:198-213` | 가드레일(데이터 경계), LLM(우선순위·가중치) |
| 4 | 스케줄링 대기열(설정 시) | `:129` | 라우터 |
| 5 | 공통 시스템 프롬프트 적용 → 응답 캐시 키 계산·조회 | `:168`, `:169-186`, `ResponseCacheService.java:33-46` | 시스템 프롬프트(공통), 응답 캐시 |
| 6 | Target별 시도: 요청 복사 → (v2: 모델별 프롬프트) → temperature → 마스킹(외부·외부 경계 Target이거나 마스커 지정 시) → 외부면 reasoning·fast | `:194, :225, :227, :228-232, :233-235` | 모델별 프롬프트, 생성 파라미터, 마스킹 워커, LLM |

스트리밍(`StreamingChatCompletionGateway.java:186-228`)은 시도마다 공통 프롬프트를 적용하고(`:218`) 이후 순서는 같다.

→ **결론**: 화면의 노드 순서는 서버 처리 순서를 바꿀 수 없다. v2는 "어떤 노드가 있는가"와 "그 노드가 모든 LLM의 공통 경로에 있는가, 특정 LLM 바로 앞에 있는가"만 서버 설정으로 옮긴다. 순서는 위 고정 순서로 정규화한다(§7.2).

### 2.3 외부 노출 방지 신호의 실제 출처

| 신호 | 저장 위치·API | 서버 집행 | v1 프런트 조회 |
|---|---|---|---|
| 서비스 데이터 경계 | `llm_service.data_boundary_policy`, `PATCH /api/admin/services/{id}`(`RoutingPolicyController.java:55-59`) | 라우팅 | ✓ (서비스 목록) |
| 서비스 민감정보 처리(guardrailAction) | 서비스 범위 DLP 정책 재저장(v1 N4, `RoutingPolicyService.java:133-158`) | inspect | ✓ |
| 마스킹 워커 | `llm_service.masker_deployment_id` | Target별 마스킹 | ✓ |
| 프로젝트 외부 AI 전송 금지 | `project.external_ai_blocked`, `ProjectView.externalAiBlocked`(`AdminController.java:124`), 문서 `docs/project-local-only.md` | 외부 Target 제외, 로컬이 없으면 503 `LOCAL_MODEL_UNAVAILABLE` | ✗ (`flowSnapshot.ts:205-213`이 `dataBoundaryPolicy`만 매핑) |
| 프로젝트 데이터 경계 | `dataBoundaryPolicy`, `maxDataBoundaryPolicy`. 실효값 = strictest(max, data), 외부 금지면 getter가 INTERNAL_ONLY 반환(`Project.java:77-93`) | 라우팅 | 일부(`dataBoundaryPolicy`만) |
| 프로젝트 DLP | PROJECT 범위 정책. `GET /api/admin/organizations/{org}/data-protection`의 `policies[]`(`DataProtectionPolicyController.java:50-54, 196-197`). DataProtectionPage도 같은 API를 사용(`DataProtectionPage.vue:92`) | inspect | ✗ |
| 조직·플랫폼 DLP | 같은 응답의 `effective` | inspect | ✗ |
| API 키 경계·DLP | 키 단위 | inspect·라우팅 | ✗ (키 목록 미조회 유지) |
| 모델(Deployment) | 외부 노출 방지 설정 없음. 외부 여부만 있음(`DeploymentInfo.external`) | — | ✓ |
| 비상 플랫폼 토큰 세션 | `canViewProject` 필터로 프로젝트 목록이 빈 배열(`OrganizationQueryController.java:50-57`, 인계 §6) | — | 구분하지 않음 |

### 2.4 시스템 프롬프트·캐시 현행

- 프롬프트는 서비스 단위만 있다: `LlmService.java:30-31`(`system_prompt_template`, `prompt_version`). `configureSystemPromptTemplate`은 trim 후 값이 바뀌면 version+1(`:159-173`), `applySystemPromptTemplate`은 첫 system 메시지 앞에 붙이거나 새로 삽입한다(`:184-197`).
- 적용 지점: 비스트리밍 `ChatCompletionGateway.java:168`(캐시 키 계산 전 1회), 스트리밍 `StreamingChatCompletionGateway.java:218`(시도마다).
- 캐시 키는 `org | service | v{promptVersion} | action | canonical(preparedRequest)`이다(`ResponseCacheService.java:33-46`). Target 선택 전에 조회하므로 Target 정보가 들어 있지 않다.
- Target(`ServiceTarget.java:9-56`)에는 프롬프트가 없다. 생성은 `ControlPlaneService.java:165-187`(`AdminDtos.CreateTarget`, `AdminDtos.java:80-87`), 수정은 `RoutingPolicyService.java:265-272`(`UpdateTarget`, `RoutingPolicyController.java:147-158`), 삭제는 `:274-278`, 조회 뷰는 `TargetPolicyView`(`RoutingPolicyController.java:201-209`)다. `new ServiceTarget(` 생성 지점은 `ControlPlaneService.java:184` 한 곳, 삭제 지점은 `RoutingPolicyService.java:277` 한 곳이다(grep 확인).
- 최신 마이그레이션은 `V53__service_flow_layout.sql`, 프롬프트 컬럼은 `V52__guardrail_masker_and_service_pipeline.sql`에서 추가됐다.

### 2.5 v1에서 그대로 유지하는 계약·제약

- v1 §2.4 N1–N17은 모두 유효하다(PATCH null = 무변경, clear 플래그, guardrailAction 부수효과, `maxConcurrencyOverride` 해제 불가, `deploymentId` 불변, `UNIQUE(service_id, deployment_id)`, `CreateTarget`에 enabled 없음, 외부 Target `followModelChanges=false`, `non_null` 생략, 감사 로그, 모델 추적에 의한 원격 재바인딩 등).
- 테스트 로더는 `.ts`를 transpile만 한다(`frontend/tests/loadTypeScript.mjs`). 타입 전용 import는 `import type`, 상대 import는 확장자 없이 쓴다.
- `frontend/tsconfig.json`에 `strictTemplates`가 없다. 부모가 아직 선언되지 않은 prop·이벤트를 넘겨도 `vue-tsc`가 통과한다(Phase 2 병렬 근거).
- ConsoleShell 정규식 테스트: `flowDesignerAdapter.test.mjs:347-361`(id 1회, `adminNavItems` 안), `betaOperator.test.mjs:346`.
- 베이스라인을 고정하는 테스트: `flowDesignerAdapter.test.mjs:392-405`(`onPointerUp`의 `moved`), `:458-466`(`will-change`·`contain`·`isZooming`), `:468-480`(`.fd-stage` grid, `100dvh / zoom`, 줌 버튼 `fire('zoom-*')`).
- CI 체인(`.github/workflows/quality-gate.yml:78`)은 `npm run test:flow-designer`를 부른다. 새 테스트 파일은 이 스크립트에만 추가하면 되고 CI yml은 수정하지 않는다.
- Jackson은 알 수 없는 JSON 필드를 무시한다(별도 설정 없음). 구버전 서버는 새 필드를 조용히 버리므로 §8.6에서 감지한다.

---

## 3. 핵심 설계 결정

| # | 결정 | 근거 | 기각안(1줄) |
|---|---|---|---|
| D1 | **워크플로우 1개 = 논리 서비스 1개**(v1 Q1 유지) | 도메인 집합과 1:1이라 3-way diff·Plan을 그대로 쓸 수 있다 | 프로젝트별 경로: 서버가 서비스 단위 공유 파이프라인만 지원 |
| D2 | **존재 기반 컴파일**: 선택 노드는 고정 ID를 갖고, 있으면 그 props, 없으면 서버 기본값으로 컴파일한다. 좌표·트렁크 순서는 컴파일에 쓰지 않는다 | 서버 순서가 고정이므로 순서에 의미를 주면 거짓이 된다. 결정성과 왕복 불변식이 쉬워진다 | 간선 순서 해석: 서버가 순서를 바꿀 수 없어 표현과 실제가 어긋남 |
| D3 | **그래프 모양** = 진입층(시작 → 프로젝트 → [상속 정책]) + 트렁크(공통 경로, 단계 노드 직선) + 분기([라우터] → [모델별 프롬프트 0–1개] → LLM). LLM이 1개면 라우터 없이 트렁크 끝에 LLM | 서버 개념과 1:1이다(공통 처리 + Target별 시도) | 임의 DAG: 중첩 분기·합류에 대응하는 서버 개념이 없음 |
| D4 | **정책 기반 가드레일 강제** = 서비스 소유 보호는 보호 잠금, 프로젝트 소유 보호는 상속 정책 노드, 효과 없는 구성은 경고와 빠른 수정 | 서버 집행과 화면 표시가 항상 일치하고, 다른 프로젝트의 동작을 강제로 바꾸지 않는다 | 서비스 가드레일 누락을 일괄 오류로 차단: 프로젝트 하나의 정책 때문에 서비스 전체 정책 변경을 강요 |
| D5 | **LLM 노드 = Target + Deployment**. ID는 `deployment:<deploymentId>`, Target 서버 ID는 `FlowNode.targetId`. 모델 교체 = 새 노드 + 기존 노드 삭제 | 서비스당 Deployment에는 Target이 하나뿐(N7)이라 ID가 결정적이고 v1 레이아웃 키를 재사용한다. 신규 Target 임시 ID(`new-<n>`)가 필요 없다 | v1 Target/Deployment 이중 노드: 초보자에게 불필요한 개념 |
| D6 | **시스템 프롬프트 두 범위**: 공통(`prompt`, 서비스) + 모델별(`prompt:<deploymentId>`, Target, V54). Target 프롬프트가 바뀌면 서비스 `promptVersion`을 올려 캐시를 무효화한다 | "LLM 앞 프롬프트" 요구를 그대로 충족한다. 캐시 키 코드를 바꾸지 않는다 | 서비스 단위만: 다중 LLM에서 특정 LLM 앞 프롬프트가 모든 LLM에 적용되는 거짓 표현 |
| D7 | **시작→프로젝트 간선 = 접근 허용**(`project_service_access`). 프로젝트(또는 상속 정책)에서 첫 노드로 가는 간선은 자동이며 잠긴다 | 처리 경로가 하나라 모든 프로젝트는 같은 첫 노드로 들어간다. 첫 노드가 바뀌어도(끼워 넣기) 접근 집합은 그대로다. LLM이 없는 빈 워크플로우에서도 접근을 표현할 수 있다 | 프로젝트를 트렁크 아무 곳에 연결: 서버에 대응 개념이 없음 |
| D8 | **기본/고급 모드**는 표시와 추가 가능성만 바꾸고 컴파일과 무관하다. 기본 모드는 고급 노드(마스킹 워커·생성 파라미터) 추가, 고급 필드, 인프라 노드(Runtime·Provider)를 숨긴다 | 점진적 공개. 모드 전환으로 설정이 바뀌지 않는다 | 모드별 다른 그래프: 동기화가 복잡하고 혼란스러움 |
| D9 | **팔레트 = 순수 TS `buildPalette()` 결과를 그리는 컴포넌트** | 그룹·정렬·숨김 규칙을 단위 테스트로 고정할 수 있다 | v1처럼 컴포넌트 안 로직: 테스트 불가 |
| D10 | **용어는 사용자 노출 문자열만 교체**하고 내부 식별자는 유지한다 | 딥링크·정규식 테스트·문서 이력이 보존된다 | 라우트·파일명 변경: 이득 없이 딥링크·테스트가 깨짐 |
| D11 | **레이아웃은 v1 API·테이블 그대로**, 노드 키 정규식만 상위 호환으로 확장한다(백엔드 1줄). v1 형태 저장본을 만나면 v2 자동 정렬로 그리고 저장을 권한다 | DB 마이그레이션이 없고 구 저장본도 유효하다 | 저장본 좌표 변환: 구조가 달라 좌표 의미가 맞지 않음. `version: 2`: 백엔드 검증 변경 범위 증가 |
| D12 | **Phase 1 독립 빌드**: WP-A는 v1 Vue 코드가 쓰는 타입 일부를 `@deprecated` 호환 멤버로 남기고 Phase 3에서 제거한다 | "각 WP가 빌드를 녹색으로 유지" 요구를 엄격한 파일 소유와 함께 만족한다 | `v2/` 경로에 병행 모듈: 중복과 이후 이동 비용. Phase 단위 게이트: 요구 위반 |

---

## 4. 용어 (워크플로우 / 노드 / 간선) 적용 범위

### 4.1 원칙

- 전체 그림은 **워크플로우**, 역할을 가진 단위는 **노드**, 노드를 잇는 선은 **간선**이다.
- 동사 '연결하다'는 그대로 쓴다(예: "LLM에 연결되지 않은 노드"). 명사로서의 선만 '간선'으로 바꾼다.
- '캔버스'는 그리는 화면 자체를 말할 때만 쓴다(예: "캔버스에 추가", "캔버스에 있음"). 전체 구성을 말하면 '워크플로우'다.
- 'Target'은 서버 객체 이름이 필요한 곳(Plan의 요청 경로, 인스펙터 읽기 정보)에서만 괄호로 덧붙인다. 노드 이름은 'LLM'이다.

### 4.2 교체 표 (사용자에게 보이는 문자열)

| 위치 | v1 | v2 | 담당 |
|---|---|---|---|
| `ConsoleShell.vue:61` label | 플로우 디자이너 | 워크플로우 디자이너 | C |
| 〃 description | 노드 캔버스로 서비스 파이프라인 구성 · 실험 | 노드와 간선으로 서비스 워크플로우 구성 · 실험 | C |
| 〃 keywords | `flow designer canvas node graph pipeline routing 플로우 디자이너 배치 노드 캔버스 실험 experimental` | `workflow designer flow canvas node edge graph pipeline routing 워크플로우 노드 간선 플로우 디자이너 배치 캔버스 실험 experimental` (구 검색어 유지) | C |
| `FlowDesignerPage.vue` eyebrow | SERVICE FLOW | WORKFLOW | C |
| 〃 h1 | 서비스 플로우 디자이너 | 워크플로우 디자이너 | C |
| 〃 설명 | 기존 화면과 같은 API를 사용합니다. … | 노드를 놓고 간선으로 이어 서비스 워크플로우를 만듭니다. 변경은 '변경 검토 → 적용' 후에만 저장됩니다. | C |
| 〃 조직 미선택 | 서비스 파이프라인을 캔버스로 구성할 수 있습니다. | 서비스 워크플로우를 구성할 수 있습니다. | C |
| 〃 선택기 라벨 / 새 초안 | 서비스 / ＋ 새 서비스 초안 | 워크플로우 / ＋ 새 워크플로우 초안 | C |
| 〃 로딩·오류 | 서비스 구성을 불러오고 있습니다… / 서비스 구성을 불러오지 못했습니다. | 워크플로우를 불러오고 있습니다… / 워크플로우를 불러오지 못했습니다. | C |
| `useFlowEditor.ts` 되돌리기 확인 | 캔버스의 모든 변경을 버리고 … | 워크플로우의 모든 변경을 버리고 마지막으로 불러온 서버 상태로 되돌릴까요? | C |
| 〃 `serviceLabel` 초안 | 새 서비스 초안 | 새 워크플로우 초안 | C |
| `FlowPlanModal.vue` 실패 안내 | 남은 변경은 캔버스에 그대로 유지됩니다. | 남은 변경은 워크플로우에 그대로 유지됩니다. | C |
| `FlowToolbar.vue` aria | 플로우 디자이너 도구 모음 | 워크플로우 디자이너 도구 모음 | B |
| 〃 자동 정렬 title | 노드를 열 기준으로 자동 정렬합니다. | 노드를 처리 순서대로 자동 정렬합니다. | B |
| `FlowCanvas.vue` aria-label / roledescription | 서비스 플로우 캔버스 / 노드 캔버스 | 워크플로우 캔버스 / 워크플로우 캔버스 | B |
| 〃 알림 | 연결을 추가했습니다. / 연결을 제거했습니다. | 간선을 추가했습니다. / 간선을 제거했습니다. | B |
| 〃 간선 삭제 버튼 aria | 선택한 연결 삭제 | 선택한 간선 삭제 | B |
| `FlowInspector.vue` 간선 패널 | 연결 / 연결 삭제 / 자동으로 관리되는 연결이라 … | 간선 / 간선 삭제 / 자동으로 관리되는 간선이라 삭제할 수 없습니다. | B |
| 〃 다중 선택 | 노드 n개 · 연결 n개 | 노드 n개 · 간선 n개 | B |
| `graphOps.ts` 연결 사유 | 연결할 노드를… / 자동으로 관리되는 연결입니다. / 이미 연결되어 있습니다. | §6.4 메시지 표 | A |
| `validate.ts` | 캔버스는 노드 300개, 연결 600개 / 고정 파이프라인 구조가 손상 / 순환 연결은 | 워크플로우는 노드 300개, 간선 600개까지 지원합니다. / (삭제) / 순환 간선은 만들 수 없습니다. | A |
| `diff.ts` Plan 요약 | Target 추가/변경/삭제, 서비스 정책 변경 | §7.6 표 | A |
| 문서 | v1 설계서 | 머리에 "v2: `docs/workflow-designer-v2-design.ko.md`" 한 줄 | C |

### 4.3 바꾸지 않는 내부 식별자와 이유

| 식별자 | 이유 |
|---|---|
| PageKey `flow-designer`, 해시 `#flow-designer/<serviceId>` | 기존 딥링크·북마크, ConsoleShell 정규식 테스트(`flowDesignerAdapter.test.mjs:347-361`) |
| API `/api/admin/services/{id}/flow-layout`, 테이블 `service_flow_layout` | 기존 저장본 재사용(D11) |
| CSS 접두사 `fd-`, 파일명 `Flow*.vue`, 타입 `FlowGraph`·`FlowNode`·`FlowEdge` | 베이스라인 정규식 테스트(`.fd-stage`, `.fd-world` 등), 변경 이득 없음 |
| 코드 안 `edge`·`connect` 같은 영어 이름 | 내부 이름. 사용자에게 보이지 않음 |
| DnD MIME `application/x-aiconnect-flow-node` | 내부 계약 |

---

## 5. 노드 카탈로그 v2

### 5.1 노드 타입 표

| type | 라벨 | category | ID | 개수 | 삭제 | 팔레트(모드) | 서버 매핑 |
|---|---|---|---|---|---|---|---|
| `start` | 시작 · 앱 호출 | entry | `start` | 항상 1 | ✗(잠금) | ✗ | 서비스 정체성: `serviceKey`(신규만), `displayName`, `enabled`, `requiredCapabilitiesJson`, `currency`, `input/outputPricePerMillion` |
| `project` | 프로젝트 | entry | `project:<projectId>` | 0..n | ✓ | ✓ 기본 | `project_service_access` (시작→프로젝트 간선이 있으면 허용) |
| `policy` | 보호 정책(상속) | guard | `policy:<projectId>` | 허용된 보호 프로젝트당 1, 자동 | ✗(파생) | ✗ | 없음. 읽기 전용(프로젝트 설정이 서버에서 집행) |
| `guardrail` | 외부 노출 방지 가드레일 | guard | `guardrail` | 0..1 | ✓(보호 중이면 '보호 해제'로만) | ✓ 기본 | `dataBoundaryPolicy`, `guardrailAction`, `maskPersonNames` |
| `masker` | 마스킹 워커 | guard | `masker` | 0..1(가드레일 필요) | ✓ | ✓ 고급 | `maskerDeploymentId` |
| `prompt`(공통) | 시스템 프롬프트 | prompt | `prompt` | 0..1 | ✓ | ✓ 기본 | `service.systemPromptTemplate` |
| `prompt`(모델별) | 시스템 프롬프트 | prompt | `prompt:<deploymentId>` | LLM당 0..1 | ✓ | 간선 삽입 또는 LLM 앞 간선에 놓기 | `target.systemPromptTemplate`(V54) |
| `params` | 생성 파라미터 | prompt | `params` | 0..1 | ✓ | ✓ 고급 | `temperaturePolicy`, `temperatureValue`, `reasoningEffort`, `openAiFastMode` |
| `cache` | 응답 캐시 | cache | `cache` | 0..1 | ✓ | ✓ 기본 | `responseCacheEnabled=true`, `responseCacheTtlSeconds` |
| `router` | 라우터 · Failover | routing | `router` | 0..1(LLM ≥ 2면 필수) | ✓(LLM ≤ 1일 때만) | ✓ 기본 | `failoverPolicy`, `retryPolicy`, `allowDegraded`, scheduling 6필드 |
| `llm` | LLM | llm | `deployment:<deploymentId>` | 0..n | ✓ | ✓ 기본(모델 목록) | `service_target` 1행(`priority`, `weight`, `enabled`, `degraded`, `maxConcurrencyOverride`, `followModelChanges`) |
| `endpoint` | 로컬 Runtime | infra | `endpoint:<id>` | 자동 | ✗(파생) | ✗ (고급 모드에서만 표시) | 없음 |
| `provider` | 외부 Provider | infra | `provider:<id>` | 자동 | ✗(파생) | ✗ (고급 모드에서만 표시) | 없음 |
| `target` | (v1 호환 전용) | routing | — | 만들지 않음 | — | ✗ | 없음. Phase 3에서 제거(D12) |

- 카테고리 색(기존 토큰만): entry `--info`, guard `--warning`, prompt `--accent-strong`, cache `--accent-strong`, routing `--warning`, llm `--accent`, infra `--muted`. 헤더 왼쪽 스트립과 아이콘 색으로 쓴다(v1 §8.4와 같은 방식). guard와 routing이 같은 색이므로 guard 노드는 아이콘 `⛨`와 배지로 구분한다.
- 아이콘: start `▶`, project `⌘`, policy `⛨`, guardrail `⛨`, masker `◍`, prompt `✎`, params `≋`, cache `⟲`, router `⑂`, llm `◆`, endpoint `▤`, provider `☁`.
- `rank`(서버 처리 순서, §6.3): start 0, project 10, policy 15, guardrail 20, masker 21, prompt(공통) 30, cache 40, params 50, router 60, prompt(모델별) 70(`PROMPT_BRANCH_RANK`), llm 80, endpoint·provider 90, target 99.

### 5.2 ID와 참조 필드 규칙

| 노드 | `id` | `refId` | `targetId` |
|---|---|---|---|
| start, guardrail, masker, prompt(공통), params, cache, router | 타입명 그대로 | null | null |
| project | `project:<projectId>` | projectId | null |
| policy | `policy:<projectId>` | projectId | null |
| prompt(모델별) | `prompt:<deploymentId>` | null | null |
| llm | `deployment:<deploymentId>` | deploymentId | 서버 Target id, 생성 전이면 null |
| endpoint / provider | `endpoint:<id>` / `provider:<id>` | id | null |

- 간선 id는 v1과 같다: `${from.nodeId}/${from.portId}->${to.nodeId}/${to.portId}`.
- LLM 노드의 도메인 키(`TargetState.key`)는 `targetKeyOf(node)` = `targetId ? 'target:<targetId>' : 'target:new-<deploymentId>'`이다. 이 키가 Plan의 `create-target:<key>`, 실행 결과 `createdTargetIds`의 키가 된다.
- 모든 ID는 결정적이다. 같은 서버 상태에서 역컴파일하면 같은 ID가 나온다. 그래서 `FlowGraph.nextSeq`는 없앤다.

### 5.3 포트 표 (in은 왼쪽, out은 오른쪽, 행 순서대로)

| type | in 포트 (`id:type`, multiple) | out 포트 (`id:type`, multiple) |
|---|---|---|
| start | — | `out:flow`(✓) "앱 요청" |
| project | `in:flow`(✗) | `out:flow`(✗) "요청" — 자동, 끌기 시작 불가 |
| policy | `in:flow`(✗) | `out:flow`(✗) — 자동, 끌기 시작 불가 |
| guardrail | `in:flow`(✓), `masker:masker`(✗) | `out:flow`(✗) |
| masker | — | `masker:masker`(✗) |
| prompt, params, cache | `in:flow`(✓) | `out:flow`(✗) |
| router | `in:flow`(✓) | `out:flow`(✓) |
| llm | `in:flow`(✓) | `host:host`(✗) |
| endpoint, provider | `in:host`(✓) | — |
| target(호환) | — | — |

- 포트 라벨: `in` "요청", `out` "요청"(start는 "앱 요청"), `masker` "마스커", `host` "실행 위치".
- 흐름 노드의 `in`이 multiple인 이유: 첫 노드는 프로젝트마다 하나씩 자동 `access` 간선을 받는다. 다른 흐름 노드에서 들어오는 `flow`/`route` 간선은 최대 1개이며 `canConnect`가 강제한다(§6.4).
- 노드 치수 계산(`nodeSize`)은 v1과 같다(행 수 = max(in, out, 1)). 가드레일만 2행이다.

### 5.4 필드 스펙 (`NODE_TYPES[type].fields`)

`advanced: true`는 고급 모드에서만, `basicOnly: true`는 기본 모드에서만 보인다. 둘 다 없으면 항상 보인다.

| 노드 | key | 라벨 | kind | 제약·옵션 | 모드 |
|---|---|---|---|---|---|
| start | `serviceKey` | 호출 이름 (model 값) | text | `newServiceOnly`, maxLength 120, `SERVICE_KEY_PATTERN`, placeholder `text-pro` | 항상 |
| start | `displayName` | 표시 이름 | text | 필수, maxLength 200 | 항상 |
| start | `enabled` | 워크플로우 사용 | boolean | | 항상 |
| start | `requiredCapabilitiesJson` | 필수 Capability | capabilities | `CapabilityPicker` 재사용, maxLength 2000 | advanced |
| start | `currency` | 통화 | select | KRW, USD | advanced |
| start | `inputPricePerMillion` / `outputPricePerMillion` | 입력 / 출력 단가 · 1M | number | min 0, step 0.000001 | advanced |
| project | `$access` | 이 워크플로우 호출 허용 | access-toggle | `setAccess` 명령, `accessComplete=false`면 비활성 | 항상 |
| guardrail | `$preset` | 보호 수준 | guardrail-preset | `GUARDRAIL_PRESETS`(§5.8). 선택 시 `dataBoundaryPolicy`·`guardrailAction`을 함께 `updateProps` | basicOnly |
| guardrail | `dataBoundaryPolicy` | 외부 전송 범위 | select | INTERNAL_ONLY "사내 LLM만", APPROVED_EXTERNAL_NO_RETENTION "외부는 무보존만", EXTERNAL_ALLOWED "외부 LLM 허용" | advanced |
| guardrail | `guardrailAction` | 민감정보 처리 | select | v1 라벨 그대로(ALLOW "개인정보 허가 · 외부 전송 허용" … BLOCK "민감정보 차단") | advanced |
| guardrail | `maskPersonNames` | 한국어 성명 가명화 | boolean | | advanced |
| masker | `deploymentId` | 마스킹 워커 모델 | masker-select | v1과 같음(부적격은 disabled + trustReason) | 항상 |
| prompt | `systemPromptTemplate` | 시스템 프롬프트 | textarea | maxLength 16000, '' = 없음. 공통이면 help "모든 LLM에 적용됩니다. 바꾸면 프롬프트 버전이 올라갑니다.", 모델별이면 "이 LLM에만 적용됩니다(공통 프롬프트 뒤에 붙음)." | 항상 |
| params | `temperaturePolicy`, `temperatureValue`, `reasoningEffort`, `openAiFastMode` | v1 §5.3과 같음 | 〃 | `temperatureValue`는 visibleWhen FIXED | 항상(노드 자체가 고급) |
| cache | `responseCacheTtlSeconds` | 캐시 유지 시간(초) | integer | 10–86400 | 항상 |
| router | `failoverPolicy` | Failover 정책 | select | STRICT, COMPATIBLE, DEGRADED | 항상 |
| router | `schedulingMode` | 배분 방식 | select | v1 라벨 | 항상 |
| router | `retryPolicy`, `allowDegraded` | v1 라벨 | select/boolean | | advanced |
| router | `localWaitMs`, `maxWaitMs`, `maxQueuedRequests`, `maxQueuedPerKey`, `maxQueuedBytes` | v1 라벨 | integer | v1 범위·visibleWhen | advanced |
| llm | `$model` | 모델 교체 | model-replace | 카탈로그 Deployment 중 아직 워크플로우에 없는 것. `replaceModel` 명령 | 항상 |
| llm | `enabled` | 사용 | boolean | | 항상 |
| llm | `priority` | 우선순위(작을수록 먼저) | integer | min 1 | 항상 |
| llm | `weight`, `degraded`, `maxConcurrencyOverride`, `followModelChanges` | v1 라벨(가중치, Degraded, 동시성 재정의, 모델 변경 자동 추적) | integer/boolean | v1 제약 | advanced |

**새 노드의 기본 props** (`defaultNodeProps(type, graph, snapshot, refId)`):

| type | props |
|---|---|
| guardrail | `{dataBoundaryPolicy: 'EXTERNAL_ALLOWED', guardrailAction: 'REDACT_RESTORE', maskPersonNames: true}` (프리셋 `mask-restore`. 아무 효과 없는 기본값을 피한다) |
| masker | `{deploymentId: refId ?? null}` |
| prompt | `{systemPromptTemplate: ''}` |
| params | `{temperaturePolicy: 'REQUEST', temperatureValue: null, reasoningEffort: 'REQUEST', openAiFastMode: false}` |
| cache | `{responseCacheTtlSeconds: max(10, base.config.service.responseCacheTtlSeconds)}` |
| router | `nodePropsFromConfig(base.config).router`(현재 서버 값 = 대개 기본값) |
| llm | `{priority: (그래프 LLM priority 최댓값, 없으면 0) + 1, weight: 100, enabled: true, degraded: false, maxConcurrencyOverride: null, followModelChanges: !external}` |
| project, policy, endpoint, provider | `{}` |

### 5.5 존재 술어와 부재 값 (compile/decompile의 핵심 계약)

| 노드 | 역컴파일 시 존재 조건 (`presenceOf(config)`) | 노드가 없을 때 컴파일 값 |
|---|---|---|
| guardrail | `dataBoundaryPolicy ≠ EXTERNAL_ALLOWED` 또는 `guardrailAction ≠ ALLOW` 또는 `maskerDeploymentId ≠ null` | `dataBoundaryPolicy = EXTERNAL_ALLOWED`, `guardrailAction = ALLOW`, `maskPersonNames = base 값`, `maskerDeploymentId = null` |
| masker | `maskerDeploymentId ≠ null` (그러면 guardrail도 존재) | `maskerDeploymentId = null` |
| prompt(공통) | `systemPromptTemplate ≠ null` | `systemPromptTemplate = null` |
| params | `temperaturePolicy ≠ REQUEST` 또는 `reasoningEffort ≠ REQUEST` 또는 `openAiFastMode` | `REQUEST`, `null`, `REQUEST`, `false` |
| cache | `responseCacheEnabled` | `responseCacheEnabled = false`, `responseCacheTtlSeconds = base 값` |
| router | `targets.length ≥ 2` 또는 `failoverPolicy ≠ STRICT` 또는 `retryPolicy ≠ SAFE` 또는 `allowDegraded` 또는 `scheduling.mode ≠ IMMEDIATE` | `STRICT`, `SAFE`, `false`, scheduling = `{...base.scheduling, mode: 'IMMEDIATE'}` |
| prompt(모델별) `prompt:D` | Target(D)의 `systemPromptTemplate ≠ null` | 그 Target의 `systemPromptTemplate = null` |
| llm `deployment:D` | Target(D)이 있음 | 그 Target 없음(삭제) |
| project 노드 + 시작→프로젝트 간선 | `accessProjectIds`에 포함 | 접근 없음(노드만 있고 간선이 없으면 미허용) |

- "base 값"은 `snapshot.config`(불러온 서버 상태)의 값이다. 부재 노드가 건드리지 않는 필드(`maskPersonNames`, TTL, 스케줄링 수치)를 base로 두어 불필요한 변경이 생기지 않게 한다.
- 이 표 덕분에 왕복 불변식(G6)이 성립한다. 노드가 없다는 것은 그 필드가 서버 기본값이라는 뜻이고, 역컴파일은 기본값이 아닐 때만 노드를 만든다.
- **존재하지만 효과가 없는 노드**(가드레일이 ALLOW + EXTERNAL_ALLOWED + 마스커 없음, 빈 프롬프트, 기본값 파라미터, LLM ≤ 1인 기본값 라우터)는 컴파일 결과가 부재와 같다. 정보 `WF_NODE_NO_EFFECT`로 "다시 불러오면 표시되지 않습니다"를 알린다.

### 5.6 종속(의존) 노드 정의

"종속 노드 = 소유 노드 때문에만 존재하는 노드"다.

| 소유 노드 | 종속 노드 | 조건 |
|---|---|---|
| `guardrail` | `masker` | 마스커가 있음 |
| `deployment:D`(llm) | `prompt:D`(모델별 프롬프트) | 있음 |
| `deployment:D`(llm) | `endpoint:E` / `provider:P` | 그 호스트를 쓰는 LLM 노드가 D 하나뿐(공유 호스트는 종속이 아님) |
| `project:P` | `policy:P` | 있음 |

효과:
- **이동**: 소유 노드를 드래그하거나 화살표로 옮기면 종속 노드도 같은 오프셋만큼 옮긴다(재귀). 종속 노드만 옮길 때는 혼자 움직인다. Ctrl/⌘를 누르고 드래그하면 종속 노드 없이 선택한 노드만 옮긴다.
- **삭제**: 소유 노드를 지우면 종속 노드도 지운다(호스트·상속 정책은 동기화가 지움).
- 시작·프로젝트·라우터는 다른 노드를 소유하지 않는다(LLM은 라우터와 독립적으로 존재).

### 5.7 `describeNode` 규칙 (title / subtitle / badges / summary(≤2) / tone / 잠금)

| type | title | subtitle | badges | summary | tone | locked / protected / lockReason |
|---|---|---|---|---|---|---|
| start | displayName 또는 '(이름 없음)' | `model: ${serviceKey \|\| '새 워크플로우'}` | 비활성 '사용 안 함', 초안 '초안', `projectsHidden` '프로젝트 숨김', 조직 정책 집행 중 '상위 정책' | `허용 프로젝트 ${n}개`, `Prompt v${promptVersion}` | 활성 healthy, 아니면 muted | locked, '시작 노드는 항상 있습니다.' |
| project | v1과 같음 | status | v1 + 보호 신호가 있으면 '보호 정책' | 허용이면 '호출 허용', 아니면 '미연결'; 실효 경계 짧은 라벨 | v1 | — |
| policy | `보호 정책 · ${프로젝트명}` | 첫 신호 라벨 | '상속' | 신호 라벨(최대 2) | warning | locked, '프로젝트 설정에서 관리하는 정책입니다. 프로젝트 화면에서 바꾸세요.' |
| guardrail | '외부 노출 방지 가드레일' | 일치하는 프리셋 라벨 또는 '사용자 지정' | 경계 짧은 라벨('사내 전용'/'외부 비보존'/'외부 허용'), 성명 가명화 '성명 가명화' | 마스커 이름 또는 '내장 정규식 엔진', `민감정보: ${action 라벨}` | 효과 없음이면 muted, 아니면 healthy | protected = `isProtectedGuardrail(snapshot)`, '서버에 외부 노출 방지 정책이 적용되어 있습니다. 삭제하려면 [보호 해제 후 삭제]를 사용하세요.' |
| masker | v1 | v1 | v1 | v1 | v1 | — |
| prompt | '시스템 프롬프트' | 공통 '공통 · 모든 LLM', 모델별 `모델별 · ${LLM 표시 이름}` | | 길이 > 0이면 `${len}자`, 아니면 '내용 없음' | 내용 있으면 healthy, 없으면 muted | `promptScope` 'common' / 'model' |
| params | '생성 파라미터' | `temperature ${policy}` | Fast면 'Fast' | `reasoning ${effort}` | healthy | — |
| cache | '응답 캐시' | `TTL ${n}초` | | | healthy | — |
| router | '라우터 · Failover' | `${failover} · ${retry}` | Degraded 허용 'Degraded 허용' | `배분 ${mode}`, `사용 중 LLM ${n}개` | 사용 중 LLM 0이면 warning | — |
| llm | Deployment 표시 이름, 카탈로그에 없으면 '알 수 없는 모델' | `${로컬\|외부} · ${Runtime/Provider 이름}` | 신규 '신규'(targetId null), 사용 중지 '중지', 'Degraded', 로컬 미로드 '미로드', 비활성 '비활성', 모델 고정 '모델 고정'(로컬만) | `P${p} · W${w} (${share}%)`(v1 share 계산), providerModelId | v1 Deployment tone, 사용 중지면 muted | — |
| endpoint / provider | v1 | v1 | v1 | | v1 | locked, '실행 위치는 모델에 따라 자동으로 표시됩니다.' |
| target(호환) | 'Target(구버전)' | '' | | | unknown | locked |

`nodeFacts`: start는 Service Key, Prompt 버전, 불러온 시각, 조직 데이터 보호(모드·외부 처리, 없으면 '정보 없음'). guardrail은 v1의 서비스 DLP 읽기 정보 + 보호 여부. policy는 신호별 행(라벨, 외부 LLM 제외 여부). llm은 v1 deployment 사실 + 'Target ID'(없으면 '생성 전'). prompt는 범위, 길이, Prompt 버전. 나머지는 v1과 같다.

### 5.8 가드레일 프리셋 (`GUARDRAIL_PRESETS`, 기본 모드 '보호 수준')

| id | 라벨 | 설명 | dataBoundaryPolicy | guardrailAction |
|---|---|---|---|---|
| `mask-restore` | 민감정보 마스킹 후 외부 전송 | 외부 LLM에는 가린 문장을 보내고 응답에서 원래 값으로 되돌립니다 | EXTERNAL_ALLOWED | REDACT_RESTORE |
| `local-sensitive` | 민감정보는 사내 LLM만 | 민감정보가 있는 요청은 로컬 LLM으로만 보냅니다 | EXTERNAL_ALLOWED | LOCAL_ONLY |
| `no-retention` | 무보존 외부만 + 마스킹 | 외부는 무보존 확인된 곳만 쓰고 민감정보는 가립니다 | APPROVED_EXTERNAL_NO_RETENTION | REDACT_RESTORE |
| `internal-only` | 사내 LLM 전용 | 모든 요청을 사내 LLM에서만 처리합니다 | INTERNAL_ONLY | LOCAL_ONLY |
| `block` | 민감정보 요청 차단 | 민감정보가 있는 요청을 거부합니다 | EXTERNAL_ALLOWED | BLOCK |

`presetOf(props)`는 두 값이 정확히 일치하는 프리셋 id를, 없으면 null('사용자 지정')을 돌려준다. 프리셋을 고르면 인스펙터가 `updateProps {dataBoundaryPolicy, guardrailAction}` 한 번을 보낸다.

---

## 6. 간선 규칙 · 삽입 · 동반 이동 · 검증

### 6.1 간선 종류(`EdgeKind`)와 의미

| kind | from → to | 사용자 편집 | 잠김 | 도메인 의미(`describeEdge`) |
|---|---|---|---|---|
| `entry` | start.out → project.in | ✓(그리기 = 허용, 지우기 = 해제) | ✗ | 접근 허용: `project_service_access` 행. 이 프로젝트의 API 키로 이 워크플로우(model)를 호출할 수 있음 |
| `policy` | project.out → policy.in | ✗ | ✓ | 보호 정책 적용: 프로젝트 설정(외부 AI 전송 금지·데이터 경계·DLP)이 이 프로젝트 요청에 적용됨(읽기 전용) |
| `access` | project.out 또는 policy.out → 첫 흐름 노드.in | ✗(자동) | ✓ | 처리 시작: 허용된 프로젝트의 요청이 워크플로우의 첫 노드로 들어감 |
| `flow` | 트렁크 노드.out → 트렁크 노드·라우터·LLM·모델별 프롬프트.in | ✓ | ✗ | 요청 흐름: 공통 경로의 처리 단계(실제 순서는 서버 고정 순서) |
| `route` | router.out → llm.in 또는 prompt:D.in / prompt:D.out → deployment:D.in | router 쪽 ✓ / 모델별 프롬프트 출력 ✗ | 모델별 프롬프트 출력만 ✓ | LLM 분기: `service_target`. 라우터가 이 LLM으로 보낼 수 있음 |
| `masker` | masker.masker → guardrail.masker | ✓ | ✗ | v1과 같음(`llm_service.masker_deployment_id`) |
| `host` | llm.host → endpoint.in / provider.in | ✗ | ✓ | v1과 같음(읽기 전용) |
| `binding` | (v1 호환 전용, 만들지 않음) | | | v1 문구 |

- 모델별 프롬프트 `prompt:D`의 출력 간선은 언제나 `deployment:D`로 가는 잠긴 `route` 간선이다(프롬프트는 자기 LLM에 묶임).
- 단일 LLM 워크플로우에서 트렁크 마지막 노드 → LLM은 `flow`다. 라우터가 생기면 라우터 → LLM은 `route`다.

### 6.2 `CONNECTION_RULES` (from.port → to.port : kind, userEditable)

```
start.out      → project.in                                   : entry,  true
project.out    → policy.in                                    : policy, false
project.out    → {guardrail,prompt,params,cache,router,llm}.in : access, false
policy.out     → {guardrail,prompt,params,cache,router,llm}.in : access, false
{guardrail,prompt,params,cache}.out → {guardrail,prompt,params,cache,router,llm}.in : flow, true
router.out     → {prompt,llm}.in                              : route,  true
masker.masker  → guardrail.masker                             : masker, true
llm.host       → endpoint.in                                  : host,   false
llm.host       → provider.in                                  : host,   false
```
- `prompt.out → llm.in : flow, true` 규칙은 공통 프롬프트(id `prompt`)가 단일 LLM 바로 앞에 있을 때만 쓴다. 모델별 프롬프트(`prompt:D`)의 출력은 명령이 잠긴 `route`로 만들고, 사용자는 그릴 수 없다(§6.4 단계 6).
- 규칙 목록은 위 표기를 펼친 평면 배열이다(`fromType`, `fromPort`, `toType`, `toPort`, `kind`, `userEditable`).

### 6.3 워크플로우 분석 (`analyzeWorkflow(graph, snapshot): WorkflowAnalysis`)

모든 구조 판단(첫 노드, 트렁크, 분기, 분리 노드)은 이 함수 하나로 한다. 레이아웃·자동 배선·검증·`syncDerived`가 공유한다.

**정의**
- 흐름 노드 F = 타입이 `guardrail, prompt, params, cache, router, llm`인 노드(공통·모델별 프롬프트 포함).
- 흐름 간선 = kind가 `flow` 또는 `route`이고 양 끝이 F인 간선. (`access`·`entry`·`policy`·`masker`·`host`는 제외)
- `rankOf(node)`: §5.1의 rank. 단 `prompt:D`는 70.

**알고리즘**
1. `roots` = F 중 들어오는 흐름 간선이 없는 노드.
2. `head` = roots가 비어 있으면 null. 아니면 (도달 가능한 LLM 수 내림차순, `rankOf` 오름차순, id 오름차순)으로 첫 번째. 나머지 root는 `WF_MULTIPLE_ROOTS` 이슈 대상이다.
3. `reachable` = head에서 흐름 간선으로 도달 가능한 노드 집합(방문 표시로 순환에 안전). `detached` = F ∖ reachable(정렬).
4. **트렁크**: `cur = head`에서 시작한다. `cur`가 LLM이거나 모델별 프롬프트면 멈춘다. 아니면 trunk에 넣는다. `cur`가 router면 멈춘다. 나가는 흐름 간선이 정확히 1개면 그 대상으로 진행하고, 0개면 멈춘다. 2개 이상이면 `WF_ROUTER_REQUIRED`(nodeId = cur)를 기록하고 멈춘다. `tail` = trunk의 마지막 노드(없으면 null). `router` = trunk에 router가 있으면 그 id.
5. **분기**:
   - router가 있으면 router의 나가는 흐름 간선마다: 대상이 llm이면 `{llmNodeId, promptNodeId: null}`. 대상이 `prompt:D`이고 그 출력이 `deployment:D`면 `{llmNodeId: 'deployment:D', promptNodeId: 'prompt:D'}`. 대상이 그 밖의 타입이면 `WF_STAGE_AFTER_ROUTER`.
   - router가 없으면: head가 llm이면 그 하나. head가 `prompt:D`면 `{deployment:D, prompt:D}`. tail이 있으면 tail의 나가는 흐름 간선 대상(1개)으로 같은 판정을 한다.
6. **추가 검사**: router에서 도달 가능한 노드 중 llm·모델별 프롬프트가 아닌 노드 → `WF_STAGE_AFTER_ROUTER`. 공통 `prompt`가 reachable인데 trunk에 없음 → `WF_STAGE_AFTER_ROUTER`. `prompt:D`의 출력이 `deployment:D`가 아니거나, 입력이 router·tail(라우터 없을 때)이 아니고 head도 아님 → `WF_BRANCH_PROMPT_MISPLACED`. reachable한 LLM이 2개 이상인데 router가 없음 → `WF_ROUTER_REQUIRED`(빠른 수정: 라우터 추가). reachable LLM 중 branches에 없는 것 → `WF_LLM_DETACHED`.
7. **순서 검사**: trunk에서 router를 뺀 노드의 rank가 오름차순이 아니면 `WF_ORDER_NONCANONICAL`(경고, 빠른 수정 `normalizeOrder`). 같은 rank(없음)는 순서 무관.
8. **분기 정렬**(서버 디스패치 순서, `RoutingService.java:209-213`): (외부 여부 0/1, priority 오름차순, weight 내림차순, llm id). 외부 여부는 `snapshot.catalog.deployments`의 `external`(카탈로그에 없으면 로컬로 간주).
9. detached의 llm은 `WF_LLM_DETACHED`, 그 밖의 흐름 노드는 `WF_NODE_DETACHED`(모두 error).

```ts
export interface WorkflowBranch { llmNodeId: string; promptNodeId: string | null }
export interface WorkflowAnalysis {
  head: string | null; trunk: string[]; router: string | null; tail: string | null
  branches: WorkflowBranch[]; detached: string[]; issues: ValidationIssue[]
}
```

### 6.4 `canConnect(graph, from, to, snapshot)` 판정 순서와 메시지

| 단계 | 조건 | 결과 메시지 |
|---|---|---|
| 1 | from/to 노드 없음 | '간선을 이을 노드를 찾을 수 없습니다.' |
| 2 | 같은 노드 | '같은 노드끼리 연결할 수 없습니다.' |
| 3 | from이 out, to가 in 포트가 아님 | '출력 포트에서 입력 포트로만 연결할 수 있습니다.' |
| 4 | from이 start인데 to가 project.in이 아님 | '시작 노드는 프로젝트에만 연결합니다.' |
| 5 | from이 project·policy | '자동으로 관리되는 간선입니다. 시작 노드에서 프로젝트로 간선을 그리면 접근이 허용됩니다.' |
| 6 | from이 `prompt:D`(모델별) | '모델별 시스템 프롬프트는 자기 LLM에만 연결됩니다.' |
| 7 | 규칙 표에 없음 | '이 포트 조합은 연결할 수 없습니다.' |
| 8 | `userEditable=false` | '자동으로 관리되는 간선입니다.' |
| 9 | from이 router인데 to가 llm도 모델별 프롬프트도 아님 | '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.' |
| 10 | 같은 id의 간선이 이미 있음 | '이미 간선이 있습니다.' |
| 11 | to에서 from으로 흐름 간선 경로가 있음(순환) | '순환 간선은 만들 수 없습니다.' |
| 12 | 통과 | `{ok: true, replaces}`. `replaces` = from 포트가 single이면 그 포트의 기존 간선 + to.in에 이미 있는 `flow`/`route` 간선(`access` 간선은 제외) |

`nearestCompatiblePort`는 v1 시그니처·의미 그대로다(반경 안의 in 포트 중 `canConnect` ok인 가장 가까운 것).

### 6.5 자동 배선 (`addNode`의 `autoWire`, 기본 true)

노드를 간선 지정 없이 추가하면 **서버 처리 순서에 맞는 자리**에 자동으로 잇는다. 팔레트 클릭, 빈 곳에 놓기, 템플릿이 모두 이 규칙을 쓴다. 고급 사용자는 Alt를 누른 채 놓아 `autoWire: false`로 연결 없이 둘 수 있다(이후 연결 전까지 `WF_NODE_DETACHED` 오류).

| 추가 노드 | 규칙 |
|---|---|
| project | 노드가 없으면 만들고 `start → project` `entry` 간선(허용)을 만든다. 이미 있으면 허용만 보장하고 선택한다. 자동 `access` 간선은 `syncDerived`가 만든다 |
| 트렁크 노드(guardrail, prompt(공통), cache, params) | **정규 위치 삽입**: trunk에서 rank가 새 노드보다 큰 첫 노드 앞에 넣는다. 없으면 tail 뒤(tail이 원래 가리키던 대상 앞)에 넣는다. 흐름 노드가 하나도 없으면 새 노드가 head가 된다. 같은 타입이 이미 있으면 실패: '{라벨}은(는) 워크플로우에 하나만 둘 수 있습니다.' (공통 프롬프트는 '공통 시스템 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선의 ＋를 누르세요.') |
| router | tail 뒤에 넣고 tail에서 나가던 간선(LLM·모델별 프롬프트로 가는 것)을 모두 router.out으로 옮긴다(kind `route`). 흐름 노드가 LLM 하나뿐이면 router가 head가 되고 그 LLM이 분기가 된다 |
| llm(refId) | 같은 `deployment:<refId>`가 있으면 선택만 한다. router가 있으면 `router → llm`. LLM이 하나도 없으면 tail(없으면 진입층) 뒤에 단일 LLM으로 잇는다. **LLM이 이미 1개이고 router가 없으면 router를 자동으로 넣고** 두 LLM을 분기로 만든다(알림 'LLM이 두 개가 되어 라우터를 자동으로 추가했습니다.') |
| masker | guardrail이 없으면 실패: '마스킹 워커는 가드레일에 연결됩니다. 가드레일을 먼저 추가하세요.' 마스커가 이미 있으면 실패(v1 문구). 아니면 만들고 `masker → guardrail.masker`를 잇는다 |
| prompt(모델별) | 간선 지정 없이 만들 수 없다(§6.6). 공통 프롬프트가 있으면 위 실패 메시지 |
| 레거시 `deployment` | `llm`과 같다. 레거시 `target` → 실패 'v2에서는 팔레트의 LLM 모델을 바로 추가합니다.' |

### 6.6 간선 위 삽입 (splice, `addNode`의 `spliceEdgeId`)

| 대상 간선 | 넣을 수 있는 노드(`insertableTypesForEdge`) | 결과 |
|---|---|---|
| `access`(진입층 → 첫 노드 H) | 아직 없는 트렁크 노드(guardrail, prompt, cache, params). H가 LLM 또는 모델별 프롬프트면 router(없을 때)도. H가 LLM이면 prompt는 그 LLM의 모델별 프롬프트로 해석(이미 있으면 제외) | 새 노드 N이 새 head가 된다: `N → H`. 모든 프로젝트의 `access` 간선이 N으로 옮겨진다(처리 경로는 하나) |
| `flow`(A → B) | 아직 없는 트렁크 노드. B가 LLM 또는 모델별 프롬프트면 router(없을 때)도(라우터는 항상 트렁크 끝). B가 LLM이면 prompt는 그 LLM의 모델별 프롬프트로 해석(이미 있으면 제외) | `A → N → B` (N이 router면 `A → router → B`, kind `route`) |
| `route`(router → LLM) | prompt(모델별, 그 LLM에 아직 없을 때) | `router → prompt:D → deployment:D`(출력 간선 잠김) |
| `route`(router → prompt:D) / `prompt:D → deployment:D` | 없음 | ＋ 버튼을 보이지 않는다 |
| `entry`, `policy`, `masker`, `host` | 없음 | ＋ 버튼을 보이지 않는다 |

- **프롬프트 범위 규칙**: 시스템 프롬프트를 **LLM으로 들어가는 간선**(대상이 llm) 위에 놓으면 그 LLM의 모델별 프롬프트 `prompt:<D>`가 된다. 그 밖의 간선이나 빈 곳에 놓으면 공통 프롬프트(`prompt`)다. `promptScopeForSplice(graph, edgeId)`가 'model'/'common'을 돌려주고 ＋ 메뉴 라벨('모델별 시스템 프롬프트'/'공통 시스템 프롬프트')에 쓴다. LLM이 하나뿐인 워크플로우에서 공통 프롬프트를 원하면 팔레트 항목을 클릭한다(간선 지정 없이 §6.5 정규 위치에 공통 프롬프트로 들어간다).
- `snapshot.features?.targetPrompt === false`이면 모델별 프롬프트를 넣을 수 없다(메뉴에서 빠지고 명령은 '서버가 모델별 시스템 프롬프트를 지원하지 않습니다.'로 실패).
- 삽입 위치가 정규 순서와 달라도(예: 캐시 뒤에 가드레일) 허용한다. 결과는 `WF_ORDER_NONCANONICAL` 경고와 [순서 정리]다.
- 좌표: §7.9의 `placeSpliced`(자리 만들기).

### 6.7 삭제와 자동 치유 (`removeItems`)

1. 대상 확장: 요청한 노드 + 종속 노드(§5.6). 
2. 거부(나머지는 계속 수행, 첫 사유를 `error`로 반환):
   - start: '시작 노드는 삭제할 수 없습니다.'
   - policy·endpoint·provider(파생): '자동으로 표시되는 노드라 삭제할 수 없습니다.'
   - **보호 중인 guardrail이고 `force !== true`**: '외부 노출 방지 정책이 적용된 가드레일입니다. 인스펙터의 [보호 해제 후 삭제]를 사용하세요.'
   - LLM 분기가 2개 이상 남는 router: '여러 LLM이 연결된 라우터는 삭제할 수 없습니다. LLM을 하나만 남기세요.'
3. **치유**: 지운 흐름 노드 N에 들어오는 흐름 간선이 정확히 1개(P)이고 나가는 흐름 간선이 1개 이상(S…)이면, 지운 뒤 `P → S`를 다시 잇는다(P가 router면 `route`, 아니면 `flow`. `canConnect`가 거부하면 잇지 않음). N이 head였다면(들어오는 흐름 간선 없음) 다음 노드가 자동으로 head가 되고 `access` 간선은 `syncDerived`가 옮긴다. router(LLM 1개)를 지우면 `tail → LLM`이 된다.
4. 간선 삭제: `entry` 지우기 = 접근 해제(프로젝트 노드는 남김). `access`·`policy`·`host`와 잠긴 `route`는 거부('자동으로 관리되는 간선입니다.'). `flow`·`route`·`masker`는 지울 수 있고, 그 결과 분리된 노드는 오류로 표시된다(재배선 중 상태 허용).
5. 끝으로 `syncDerived`.

### 6.8 종속 노드 동반 이동

- 순수 함수 `dependencyClosure(graph, nodeIds): string[]` = 입력 + §5.6 종속 노드(재귀), id 정렬.
- 캔버스(WP-B): 노드 드래그 시작 시 `dragIds = (Ctrl/⌘ 누름) ? 선택 : dependencyClosure(graph, 선택)`. 화살표 이동도 같다. `moveNodes`는 받은 좌표만 쓰는 단순 명령으로 둔다(확장은 캔버스 책임).
- 공유 호스트(여러 LLM이 쓰는 Runtime/Provider)는 따라오지 않는다.

### 6.9 정책 기반 가드레일 강제 (핵심 규칙)

**신호 정의** (`projectSignals(project)`, `isProtectedGuardrail(base)`)

| 신호 | 출처 | 소유 | 외부 LLM 전면 제외(`blocksExternal`) |
|---|---|---|---|
| 서비스 보호 | base `dataBoundaryPolicy ≠ EXTERNAL_ALLOWED` 또는 base `guardrailAction ≠ ALLOW` (기존 서비스만) | 이 워크플로우 | 경계가 INTERNAL_ONLY일 때 |
| `PROJECT_LOCAL_ONLY` | `ProjectInfo.externalAiBlocked === true` | 프로젝트 | ✓ |
| `PROJECT_BOUNDARY` | strictest(`dataBoundaryPolicy`, `maxDataBoundaryPolicy`) ≠ EXTERNAL_ALLOWED. 단 `externalAiBlocked`면 생략(LOCAL_ONLY가 포함) | 프로젝트 | INTERNAL_ONLY일 때 ✓, 무보존이면 ✗ |
| `PROJECT_DLP` | `dlp.mode === 'ENFORCE'` 이고 `dlp.externalAction ≠ ALLOW` | 프로젝트 | ✗(내용에 따라 다름) |

신호 라벨: LOCAL_ONLY '외부 AI 전송 금지(로컬 전용)', BOUNDARY '데이터 경계: 사내 GPU 전용' 또는 '데이터 경계: 외부는 무보존만', DLP '민감정보: 사내 LLM만'(LOCAL_ONLY) / '민감정보: 차단'(BLOCK) / '민감정보: 마스킹 후 전송'(REDACT, REDACT_RESTORE).

**강제 규칙**

| 규칙 | 내용 | 수단 |
|---|---|---|
| E1 서비스 보호 = 보호 잠금 | 서비스 보호가 켜진 기존 서비스는 역컴파일 시 가드레일 노드가 반드시 생기고(§5.5) `protected`다. Delete 키·다중 삭제·간선 정리로는 지워지지 않는다. 인스펙터의 **[보호 해제 후 삭제]**(확인 창)만 `removeItems {force: true}`를 보낸다. 값 완화(프리셋·필드 변경)는 허용하되 경고 `GUARDRAIL_RELAXED`를 내고, 삭제했으면 경고 `GUARDRAIL_REMOVED`를 낸다. Plan은 v1 위험도 규칙(경계 완화·처리 정책 완화·마스커 해제 = danger)과 노트 `NOTE_PROTECTION_RELAXED`로 위험 작업 확인 체크박스를 요구한다 | locked node + 경고 + danger 확인 |
| E2 프로젝트 보호 = 상속 정책 노드 | 접근이 허용된 프로젝트에 프로젝트 신호가 하나라도 있으면 `syncDerived`가 `policy:<projectId>`를 **자동으로 넣는다**: `project → policy`(잠김) + `policy → 첫 노드`(`access`, 잠김). 삭제·편집 불가(파생). 접근을 해제하거나 프로젝트 화면에서 설정을 끄면 다음 동기화에서 사라진다 | auto-insert + locked |
| E3 보호가 효과 없는 구성 | 허용된 보호 프로젝트 중 `blocksExternal` 신호가 있는데 사용 중인 로컬 LLM이 없으면 경고 `POLICY_NO_LOCAL_LLM`(그 프로젝트 요청은 모두 503). 외부 LLM이 있으면 정보 `POLICY_SKIPS_EXTERNAL`(건너뛰는 모델 이름 최대 3개). 가드레일 LOCAL_ONLY인데 로컬 LLM이 없으면 v1 경고 `GUARDRAIL_LOCAL_ONLY_WITHOUT_LOCAL_TARGET`. 경계가 외부 허용이 아닌데 외부 LLM이 있으면 v1 경고 `EXTERNAL_TARGET_BLOCKED_BY_BOUNDARY` | 경고/정보 |
| E4 보호 제안 | 외부 LLM 노드가 있는데 가드레일이 없으면 각 외부 LLM에 정보 `EXTERNAL_LLM_WITHOUT_GUARDRAIL`과 빠른 수정 [가드레일 추가](`addNode guardrail`)를 붙인다 | auto-insert 제안 |
| E5 상위 정책 표시 | 조직 실효 정책이 ENFORCE이고 외부 처리가 ALLOW가 아니며 외부 LLM이 있으면 시작 노드에 정보 `ORG_POLICY_APPLIES`와 배지 '상위 정책' | 정보 |
| E6 불가능한 위치 | 가드레일은 공통 경로에만 둘 수 있다(라우터 뒤면 `WF_STAGE_AFTER_ROUTER` 오류). 연결되지 않은 가드레일은 `WF_NODE_DETACHED` 오류라 적용할 수 없다(실수로 끊어서 보호가 풀리는 것을 막음). 마스커는 가드레일 없이 추가할 수 없다 | 오류 |
| E7 정보 부족 | 프로젝트 정책을 다 읽지 못하면 정보 `POLICY_UNVERIFIED`. 비상 토큰 세션이면 정보 `PROJECTS_HIDDEN`(접근 편집 불가) | 정보 |

`isProtectedGuardrail(base)` = `base.serviceId !== null && (base.config.service.dataBoundaryPolicy !== 'EXTERNAL_ALLOWED' || base.config.service.guardrailAction !== 'ALLOW')`. 초안(새 워크플로우)에는 보호가 없다.

### 6.10 명령 (`applyCommand`) — 항상 새 그래프를 돌려주고 끝에 `syncDerived` + `sortGraph`

| 명령 | 동작 | 실패(`error`, 그래프 불변) |
|---|---|---|
| `moveNodes` | v1과 같음(좌표만 설정, 없는 id 무시) | — |
| `addNode {nodeType, refId?, position?, spliceEdgeId?, autoWire?}` | `spliceEdgeId`가 있으면 §6.6, 없으면 `autoWire !== false`일 때 §6.5, false면 연결 없이 추가. `position`이 있으면 그 좌표(16 격자 스냅은 호출자 책임), 없으면 §7.9 배치. 새 노드를 선택 | §6.5·§6.6 메시지, '알 수 없는 프로젝트입니다.', '알 수 없는 모델입니다.' |
| `removeItems {nodeIds, edgeIds, force?}` | §6.7 | §6.7 메시지 |
| `connect {from, to}` | `canConnect` 후 `replaces` 제거·간선 추가. **보조 규칙**: from이 router가 아닌 흐름 노드이고 to가 llm이며 from이 이미 다른 LLM(또는 모델별 프롬프트)으로 나가고 router가 없으면, 교체 대신 router를 자동으로 넣어 두 LLM을 분기로 만든다. `start → project`는 접근 허용. 외부 LLM으로 새로 이어진 신규 LLM의 `followModelChanges`는 false | `canConnect` 사유 |
| `updateProps {nodeId, patch}` | v1과 같음(필드 kind로 형변환, `$`로 시작하는 가상 키는 무시, `newServiceOnly`는 기존 서비스에서 무시) | '알 수 없는 노드입니다.' |
| `replaceModel {nodeId, deploymentId}` | `deployment:<new>`를 같은 좌표에 만들고(targetId null) props를 복사(외부면 `followModelChanges=false`). 들어오던 흐름 간선을 새 노드로 옮기고, `prompt:<old>`가 있으면 `prompt:<new>`로 이름을 바꿔 잇는다. 기존 노드를 지운다. 새 노드를 선택 | '알 수 없는 모델입니다.', '이미 워크플로우에 있는 모델입니다.' |
| `setAccess {projectNodeId, granted}` | `start → project` `entry` 간선을 보장하거나 지운다(v1과 같은 이름, adapter 테스트 호환) | '알 수 없는 노드입니다.' |
| `normalizeOrder` | trunk(router 제외)를 rank 순으로 다시 잇는다: 기존 trunk 노드 사이 흐름 간선과 tail의 출력 간선을 떼고 `head → … → (router 또는 원래 tail의 대상들)`로 다시 연결. 각 노드의 x는 기존 trunk x 좌표들을 오름차순으로 다시 배정(y 유지) | 순서가 이미 맞으면 `changed: false` |
| `applyTemplate {template, projectIds, deploymentIds}` | 흐름 노드가 하나도 없을 때만. §10.4 규칙으로 노드를 추가한 뒤 `autoLayout`. 시작 노드를 선택 | '빈 워크플로우에서만 템플릿을 적용할 수 있습니다.', 템플릿별 메시지(§10.4) |
| `toggleCollapse`, `autoLayout` | v1과 같음(`autoLayout`은 §7.9) | — |
| `bindTarget`(레거시) | 항상 실패 | '더 이상 지원하지 않는 명령입니다. LLM 노드의 [모델 교체]를 사용하세요.' |

선택 반환: `addNode`·`replaceModel`은 새 노드, `applyTemplate`은 start, `removeItems`는 빈 선택, 그 밖은 null.

### 6.11 `syncDerived(graph, snapshot)` (모든 명령 끝, 역컴파일, rebase에서 호출)

1. **호스트**: v1 `syncHostNodes`를 llm 노드 기준으로 수행한다(`llm.host → endpoint/provider.in`, 잠김. 쓰이지 않는 호스트 노드 제거).
2. **진입층**: `head = analyzeWorkflow(graph, snapshot).head`. 각 project 노드 p에 대해:
   - 허용(`entry` 간선 있음) + 신호 있음 → `policy:p` 보장(refId = projectId), `p.out → policy:p.in`(`policy`, 잠김), head가 있으면 `policy:p.out → head.in`(`access`, 잠김). p에서 나가는 `access` 간선 제거.
   - 허용 + 신호 없음 → `policy:p` 제거, head가 있으면 `p.out → head.in`(`access`, 잠김).
   - 미허용 → p와 `policy:p`의 `access`·`policy` 간선과 `policy:p` 노드 제거.
   - head가 아닌 노드로 가는 남은 `access` 간선 제거.
3. 프로젝트 노드가 사라진 `policy:*` 제거.
4. 새로 만든 파생 노드만 배치한다(policy: `x = project.x + COLUMN_WIDTH, y = project.y`, 겹치면 아래로 밀기. 호스트: v1 규칙).

### 6.12 검증 코드 (`validateGraph(graph, base)`) — 메시지는 아래 한국어 그대로

**구조·정책 (신규 v2)**

| code | 심각도 | 조건 | 메시지 | nodeId / fix |
|---|---|---|---|---|
| GRAPH_TOO_LARGE | error | 노드 > 300 또는 간선 > 600 | 워크플로우는 노드 300개, 간선 600개까지 지원합니다. | — |
| WF_START_MISSING | error | start 노드 없음 | 시작 노드가 없습니다. 되돌리기 또는 새로고침하세요. | — (이 오류면 이후 검사 생략) |
| CYCLE_DETECTED | error | 순환 | 순환 간선은 만들 수 없습니다. | 순환 노드 |
| WF_MULTIPLE_ROOTS | error | head 외 root | 처리 경로가 여러 갈래로 끊겨 있습니다. 하나의 경로로 연결하세요. | 각 추가 root |
| WF_NODE_DETACHED | error | head에서 도달할 수 없는 흐름 노드(LLM 제외) | 요청 경로에 연결되지 않은 노드가 있습니다. 연결하거나 삭제하세요. | 그 노드 |
| WF_LLM_DETACHED | error | 도달할 수 없거나 분기에 없는 LLM | 요청 경로에 연결되지 않은 LLM이 있습니다. 연결하거나 삭제하세요. | 그 LLM |
| WF_ROUTER_REQUIRED | error | LLM ≥ 2인데 router 없음, 또는 router가 아닌 노드의 분기 | LLM을 두 개 이상 쓰려면 라우터 노드가 필요합니다. | 분기 노드 / fix `{label: '라우터 추가', command: {type: 'addNode', nodeType: 'router'}}` |
| WF_STAGE_AFTER_ROUTER | error | 라우터 뒤의 트렁크 전용 노드 | 가드레일·공통 시스템 프롬프트·응답 캐시·생성 파라미터는 라우터 앞(모든 LLM의 공통 경로)에만 둘 수 있습니다. | 그 노드 |
| WF_BRANCH_PROMPT_MISPLACED | error | `prompt:D` 위치 오류 | 모델별 시스템 프롬프트는 해당 LLM 바로 앞에만 둘 수 있습니다. | 그 노드 |
| TARGET_PROMPT_UNSUPPORTED | error | `features.targetPrompt === false`이고 내용 있는 `prompt:D` | 서버가 모델별 시스템 프롬프트를 지원하지 않습니다(백엔드 업데이트 필요). 공통 시스템 프롬프트를 사용하세요. | 그 노드 |
| WF_ORDER_NONCANONICAL | warning | trunk rank 역전 | 화면의 순서와 서버 처리 순서(가드레일 → 공통 시스템 프롬프트 → 응답 캐시 → 생성 파라미터 → 라우터)가 다릅니다. 서버는 항상 이 순서로 처리합니다. | 첫 역전 노드 / fix `{label: '순서 정리', command: {type: 'normalizeOrder'}}` |
| WF_NO_LLM | warning | LLM 노드 0 | LLM 노드가 없어 이 워크플로우의 요청은 모두 실패합니다. | start |
| WF_NO_PROJECT | warning | 서비스 사용 중, 허용 프로젝트 0, `accessComplete`, `!projectsHidden` | 호출을 허용한 프로젝트가 없어 어떤 앱도 이 워크플로우를 쓸 수 없습니다. | start |
| GUARDRAIL_RELAXED | warning | 보호 중이고 가드레일 값이 base보다 약함(경계 rank 증가 또는 처리 정책 strictness 감소) | 서버에 적용된 외부 노출 방지 정책을 완화합니다. 적용할 때 위험 작업 확인이 필요합니다. | guardrail |
| GUARDRAIL_REMOVED | warning | 보호 중인데 guardrail 노드 없음 | 외부 노출 방지 가드레일을 제거했습니다. 적용하면 데이터 경계는 '외부 LLM 허용', 민감정보 처리는 '허용'으로 바뀝니다. | start |
| POLICY_NO_LOCAL_LLM | warning | §6.9 E3 | {프로젝트명}의 보호 정책 때문에 외부 LLM을 쓸 수 없는데 사용 중인 로컬 LLM이 없습니다. 이 프로젝트의 요청은 모두 실패합니다. | policy:P |
| POLICY_SKIPS_EXTERNAL | info | §6.9 E3 | {프로젝트명}의 요청은 외부 LLM({이름 목록})을 건너뜁니다. | policy:P |
| EXTERNAL_LLM_WITHOUT_GUARDRAIL | info | §6.9 E4 | 외부 LLM으로 나가는 요청을 보호할 가드레일이 없습니다. | 외부 llm / fix `{label: '가드레일 추가', command: {type: 'addNode', nodeType: 'guardrail'}}` |
| ORG_POLICY_APPLIES | info | §6.9 E5 | 조직 데이터 보호 정책(민감정보: {처리 라벨})이 이 워크플로우보다 먼저 적용됩니다. | start |
| POLICY_UNVERIFIED | info | `projectPoliciesComplete === false` | 프로젝트 데이터 보호 정책을 모두 불러오지 못해 일부 보호 정책 노드가 빠졌을 수 있습니다. | — |
| PROJECTS_HIDDEN | info | `projectsHidden === true` | 비상 토큰 세션에서는 프로젝트가 보이지 않아 프로젝트 노드와 접근 변경을 쓸 수 없습니다. 사용자 계정으로 로그인하세요. | start |
| WF_NODE_NO_EFFECT | info | §5.5 효과 없는 노드 | 기본값만 설정되어 적용해도 서버 설정이 바뀌지 않는 노드입니다. 다시 불러오면 표시되지 않습니다. | 그 노드 |

**v1 코드 유지(nodeId 재매핑)**: `SERVICE_KEY_INVALID`, `SERVICE_KEY_TAKEN`, `DISPLAY_NAME_REQUIRED`, `DISPLAY_NAME_TOO_LONG`, `CAPABILITIES_INVALID`, `PRICE_INVALID`, `SERVICE_DISABLED` → `start` / `TEMPERATURE_VALUE_REQUIRED`, `TEMPERATURE_VALUE_RANGE` → `params` / `PROMPT_TOO_LONG` → `prompt` 또는 `prompt:D` / `CACHE_TTL_RANGE` → `cache` / `SCHEDULING_*` → `router` / `TARGET_NUMBER_INVALID`, `TARGET_CONCURRENCY_CLEAR_UNSUPPORTED`, `UNKNOWN_DEPLOYMENT`(신규 = error, 기존 무변경 = warning), `DEPLOYMENT_UNAVAILABLE`, `EXTERNAL_TARGET_BLOCKED_BY_BOUNDARY`, `FOLLOW_MODEL_ON_EXTERNAL` → llm 노드 / `MASKER_NOT_SELECTED`, `MASKER_NOT_ELIGIBLE`, `MASKER_UNVERIFIED`, `MASKER_DISCONNECTED` → `masker` / `GUARDRAIL_LOCAL_ONLY_WITHOUT_LOCAL_TARGET` → `guardrail` / `UNKNOWN_PROJECT`, `PROJECT_INACTIVE` → `project:P` / `ACCESS_EDIT_UNAVAILABLE`, `CATALOG_PARTIAL` → null / `NO_ENABLED_TARGETS` → `router`(없으면 `start`, LLM ≥ 1일 때만). 메시지는 v1 §6.5 그대로이되 'Target'은 'LLM'으로 바꾼다(예: `NO_ENABLED_TARGETS` '사용 중인 LLM이 없어 이 워크플로우의 요청은 모두 실패합니다.').

**삭제되는 v1 코드**: `STRUCTURE_BROKEN`, `TARGET_UNBOUND`, `DUPLICATE_DEPLOYMENT_TARGET`(ID 구조상 발생 불가).

검사 순서: GRAPH_TOO_LARGE → WF_START_MISSING(있으면 반환) → CYCLE_DETECTED → `analyzeWorkflow` 이슈 → 필드 검사(`compile` 결과 L 기준, v1 순서) → 정책 검사(§6.9) → 서비스 수준 경고·정보. `issueCountsByNode`는 v1과 같다. `ValidationIssue.fix`는 선택 필드이며 `planChanges`의 `blockingIssues`에 그대로 실려도 지문(fingerprint)에는 영향이 없다.

---

## 7. 그래프 ⇄ 도메인 컴파일 / 역컴파일 v2

### 7.1 상태 흐름 (v1 §7.1 유지)

`load → decompile → edit(applyCommand) → validate + 로컬 preview plan → Plan(재조회 R1) → 검토 → Apply(재조회 R2, fingerprint 비교) → 순차 실행 → 재조회 R3 → rebase → (성공 작업이 있으면) 레이아웃 1회 저장`. 흐름과 동시성 정책(v1 §7.1, §7.10)은 그대로다. 바뀌는 것은 그래프 계층(`decompile`, `compile`, `syncGraphToConfig`, `rebase`의 노드 이름 처리, `computeNodeChanges`)과 Target 프롬프트 필드뿐이다.

### 7.2 자유 배치를 고정 서버 순서로 정규화하는 원칙

1. **컴파일은 노드의 존재와 ID만 본다**(D2). 트렁크 순서·좌표·접힘은 서버 설정에 들어가지 않는다. 같은 노드 집합 = 같은 `FlowConfigState`.
2. **범위는 ID로 정해진다**: 공통 경로 노드(`guardrail`, `prompt`, `cache`, `params`, `router`)는 서비스 필드, `prompt:<D>`·`deployment:<D>`는 Target(D) 필드, 시작→프로젝트 간선은 접근 집합이다. 위상(topology)은 검증에만 쓴다.
3. **표현할 수 없는 위상은 오류**다(§6.12의 `WF_*` error). 오류가 있으면 Plan의 `blockingIssues`가 되어 적용할 수 없다(v1과 같음). 그래서 "화면은 A인데 서버는 B"인 상태가 적용되는 일은 없다.
4. **표현은 가능하지만 서버 순서와 다른 트렁크 순서**는 경고(`WF_ORDER_NONCANONICAL`)와 [순서 정리]로만 다룬다. 서버는 언제나 §2.2 순서로 처리한다.
5. 허용되는 트렁크 순서: rank가 같거나 커지는 순서(`guardrail(20) → prompt(30) → cache(40) → params(50) → router(60)`)는 경고 없음. 그 밖의 순열은 경고. 라우터는 항상 트렁크 마지막이며(라우터 뒤는 분기), 분기에는 모델별 프롬프트 0–1개와 LLM 1개만 올 수 있다.

### 7.3 `compile(graph, snapshot): FlowConfigState`

```
base = snapshot.config; existing = (graph.serviceId ?? snapshot.serviceId) !== null
S = props('start'); G = node('guardrail'); M = node('masker'); P = node('prompt')
Q = node('params'); C = node('cache'); R = node('router')
maskerLinked = 간선 kind 'masker', from 'masker', to 'guardrail' 존재

service:
  serviceKey            = existing ? base.service.serviceKey : trim(S.serviceKey ?? '')
  displayName, enabled, requiredCapabilitiesJson(canonical), currency, input/outputPricePerMillion
                        = S의 값(없으면 base) — v1 compile 변환 규칙 그대로
  dataBoundaryPolicy    = G ? G.props.dataBoundaryPolicy : 'EXTERNAL_ALLOWED'
  guardrailAction       = G ? G.props.guardrailAction    : 'ALLOW'
  maskPersonNames       = G ? bool(G.props.maskPersonNames) : base.service.maskPersonNames
  maskerDeploymentId    = G && M && maskerLinked && M.props.deploymentId가 비어 있지 않은 문자열 ? 그 값 : null
  systemPromptTemplate  = P ? normalizePrompt(P.props.systemPromptTemplate) : null
  temperaturePolicy     = Q ? Q.props.temperaturePolicy : 'REQUEST'
  temperatureValue      = Q && policy === 'FIXED' ? nullableNumber(Q.props.temperatureValue) : null
  reasoningEffort       = Q ? Q.props.reasoningEffort : 'REQUEST'
  openAiFastMode        = Q ? bool(Q.props.openAiFastMode) : false
  responseCacheEnabled  = C !== undefined
  responseCacheTtlSeconds = C ? numeric(C.props.responseCacheTtlSeconds) : base.service.responseCacheTtlSeconds
  failoverPolicy / retryPolicy / allowDegraded = R ? R.props.* : 'STRICT' / 'SAFE' / false
scheduling = R ? {mode: R.props.schedulingMode, 수치 5개: R.props(없으면 base)} : {...base.scheduling, mode: 'IMMEDIATE'}
targets = type 'llm' 노드마다(키 순 정렬):
  D = node.refId ?? id.slice('deployment:'.length); BP = node('prompt:' + D)
  { key: targetKeyOf(node), id: node.targetId, deploymentId: D,
    priority, weight, degraded, enabled, maxConcurrencyOverride, followModelChanges ← node.props (v1 변환 규칙),
    systemPromptTemplate: BP ? normalizePrompt(BP.props.systemPromptTemplate) : null }
accessProjectIds = 'start'에서 kind 'entry' 간선이 들어오는 project 노드의 refId(정렬·중복 제거)
```
- 숫자 변환·NaN 통과·capabilities 정규화·`sameValue`/`sameFieldValue`는 v1 §7.3 그대로다. `sameFieldValue`는 필드명이 `systemPromptTemplate`이면 서비스·Target 모두 `normalizePrompt`로 비교한다(이미 그렇게 동작).
- 연결되지 않은 노드도 존재하면 컴파일한다. 분리 상태는 오류로 적용을 막으므로 재배선 중 Plan 미리보기가 요동치지 않는다.

### 7.4 `decompile(snapshot, layout): FlowGraph` (정규형 생성)

1. `start`(props = `nodePropsFromConfig(config).start`).
2. `config.accessProjectIds`마다 `project:<id>` + `start → project` `entry` 간선. 카탈로그에 없는 프로젝트도 노드를 만든다(`UNKNOWN_PROJECT`는 신규 허용에만 오류).
3. `presenceOf(config)`가 참인 트렁크 노드를 **정규 순서**(guardrail, prompt, cache, params, router)로 만들고 `flow` 간선으로 직선 연결한다. 마스커가 있으면 `masker` + `masker` 간선.
4. Target마다 `deployment:<deploymentId>`(targetId = id, props = `llmPropsFromTarget(t)`). `t.systemPromptTemplate`이 있으면 `prompt:<deploymentId>`와 잠긴 `route` 출력 간선.
5. 분기 연결: router가 있으면 `router → (prompt:D 또는 deployment:D)`(`route`). 없으면(Target ≤ 1) 트렁크 끝(트렁크가 비었으면 그 노드가 head) → 분기 첫 노드(`flow`).
6. `syncDerived`(호스트, 상속 정책, `access` 간선).
7. 좌표: `layout`이 있고 `layoutGeneration(layout) === 2`면 `applyLayout`, 아니면 `autoLayout`(§7.8).
8. `schemaVersion: 2`, `serviceId = snapshot.serviceId`, `sortGraph`(rank, id 순).

### 7.5 `syncGraphToConfig(graph, config, snapshot)` (rebase·병합용)

컴파일 결과가 `config`와 같아지게 그래프를 고치되 **좌표, 사용자가 만든 트렁크 순서, 연결되지 않은 프로젝트 노드는 보존**한다.

1. `start` props를 config로 맞춘다(없으면 만든다).
2. 트렁크 타입 X(guardrail, prompt, cache, params, router)마다 `want = presenceOf(config)[X]`:
   - want이고 있음 → props만 config 값으로.
   - want이고 없음 → 만들고 §6.5 정규 위치에 삽입.
   - want이 아니고 있음 → §6.7 치유 규칙으로 제거(guardrail이면 masker도).
3. 마스커: config 값이 있으면 노드·간선 보장 + `deploymentId`, 없으면 제거.
4. LLM: config Target t마다 `n = (t.id로 targetId가 같은 노드) ?? ('deployment:' + t.deploymentId 노드)`.
   - n이 있고 id가 `deployment:<t.deploymentId>`와 다르면(원격 모델 추적에 의한 재바인딩, v1 N13) 노드 id·refId를 새 값으로 **이름 변경**한다(좌표·간선 유지, `prompt:<old>`도 `prompt:<new>`로).
   - n이 없으면 만들고 §6.5 LLM 규칙으로 잇는다.
   - `n.targetId = t.id`, props = `llmPropsFromTarget(t)`.
   - config에 대응하는 Target이 없는 llm 노드는 제거한다(`prompt:D` 포함, 치유).
5. 모델별 프롬프트: t마다 프롬프트가 있으면 `prompt:D`를 보장(없으면 `deployment:D` 앞에 삽입) + props, 없으면 제거(치유).
6. 접근: `accessProjectIds`의 프로젝트마다 노드(없으면 생성) + `entry` 간선. 목록에 없는 프로젝트의 `entry` 간선은 지운다(노드 유지).
7. `syncDerived`, 새로 만든 노드 배치, `serviceId = snapshot.serviceId`, `sortGraph`.

### 7.6 diff / plan 변경점 (diff.ts는 `FlowConfigState` 기반이라 대부분 재사용)

| 항목 | 변경 |
|---|---|
| `TARGET_FIELDS` | 끝에 `'systemPromptTemplate'` 추가 → `analyze`/`mergeConfigs`/3-way가 자동으로 포함 |
| createTarget body | `t.systemPromptTemplate`이 null이 아니면 `systemPromptTemplate` 키 추가(null이면 키 생략). changes에 `{field: 'systemPromptTemplate', before: '없음', after: '${len}자'}` 추가 |
| patchTarget body | 적용 필드에 프롬프트가 있으면 값이면 `{systemPromptTemplate}`, null이면 `{clearSystemPromptTemplate: true}`. risk는 최소 `caution`(사용 중지면 v1대로 danger) |
| 교체(resolvedProps) | 프롬프트도 함께 복사(기존 코드가 필드 목록 기반이라 자동) |
| 노트 `NOTE_TARGET_PROMPT`(info) | 프롬프트가 있는 Target 생성·프롬프트 변경·프롬프트가 있던 Target 삭제가 하나라도 있으면: "모델별 시스템 프롬프트가 바뀌어 프롬프트 버전(현재 v{n})이 올라가고 기존 응답 캐시는 더 이상 쓰이지 않습니다." |
| 노트 `NOTE_PROTECTION_RELAXED`(warning) | patchService가 경계 완화 또는 처리 정책 완화로 danger일 때: "외부 노출 방지 정책이 완화됩니다(데이터 경계 또는 민감정보 처리). 영향을 받는 프로젝트와 모델을 확인한 뒤 적용하세요." |
| 요약 문구 | createService `새 워크플로우(논리 서비스) 생성: ${key}` / patchService `워크플로우 설정 변경 (${n}개 항목)` / updateScheduling `라우터 배분·대기열 정책 변경` / createTarget `LLM 연결 추가: ${이름} (P${p} · W${w})` / disable-created `새 LLM 연결 사용 중지: ${이름}` / patchTarget `LLM 설정 변경: ${이름}` / deleteTarget `LLM 연결 삭제: ${이름}` / grant·revoke `프로젝트 접근 허용/해제: ${이름}` |
| 기존 노트 문구 | NOTE_REPLACE 'LLM 노드의 모델이 바뀌어 새 연결(Target)을 만든 뒤 기존 연결을 삭제합니다. 서버 ID가 바뀝니다.' / NOTE_PREDELETE '같은 모델을 다시 쓰려고 기존 연결을 먼저 삭제합니다. 적용하는 동안 잠시 라우팅 대상이 줄어듭니다.' / NOTE_NO_TARGET '적용 후 사용 중인 LLM이 없어 이 워크플로우의 요청은 실패합니다.' / NOTE_NEW_SERVICE '새 워크플로우는 시작 노드에 프로젝트를 연결(접근 허용)하기 전까지 호출되지 않습니다.' / 충돌 메시지의 'Target' → 'LLM 연결' |
| `FIELD_LABELS` | `'target.enabled': 'LLM 사용'`, `systemPromptTemplate: '시스템 프롬프트'`, `'target.systemPromptTemplate': '모델별 시스템 프롬프트'`, `enabled: '워크플로우 사용'`, `serviceKey: '호출 이름 (model 값)'`, `dataBoundaryPolicy: '외부 전송 범위'`, `guardrailAction: '민감정보 처리'` |
| 실행 순서·위험도·fingerprint·멱등성 | v1 §7.5–7.8 그대로 |

`execute.ts`는 body를 그대로 보내므로 바꿀 필요가 없다(`createTarget`의 null `maxConcurrencyOverride` 제거 로직만 있음, `execute.ts:46-50`).

### 7.7 rebase 변경점

`rebase`(diff.ts:309-337)의 흐름은 그대로다. 그래프 함수의 의미만 바뀐다.
- `renameTargetNodes(graph, createdTargetIds)`: `targetKeyOf(node)`가 맵의 키인 llm 노드의 `targetId`를 맵 값으로 설정한다(노드 id·좌표 불변).
- `detachTargetNodes(graph, keys)`: `targetKeyOf(node)`가 keys에 있는 llm 노드의 `targetId`를 null로 바꾼다(삭제된 서버 행을 다시 만들 대상으로 돌림).
- 채택(adoption): `mergeConfigs`의 `adopted[localKey] = remoteId`를 `renameTargetNodes`로 반영한다. 신규 LLM의 키는 `target:new-<D>`이고 원격 Target도 같은 Deployment이므로 같은 노드가 채택된다.

### 7.8 v1 호환 · 마이그레이션

| 대상 | 처리 |
|---|---|
| 서버 데이터 | 변경 없음. v2 역컴파일은 모든 v1 서버 상태를 표현한다(예: guardrailAction ALLOW + 마스커 → 가드레일 노드 존재). DB 마이그레이션은 V54(§8)뿐 |
| 저장된 레이아웃(DB) | 키 정규식을 **상위 호환**으로 넓힌다(백엔드 `ServiceFlowLayoutService.java:31-32`, 프런트 `LAYOUT_NODE_ID_PATTERN`): `^(start\|service\|guardrail\|prompt\|params\|cache\|router\|masker\|(project\|policy\|prompt\|target\|deployment\|endpoint\|provider):[A-Za-z0-9-]{1,64})$`. 기존 저장본은 모두 그대로 유효하다. `version`은 1 유지 |
| v1 형태 저장본 읽기 | `layoutGeneration(layout)`: `nodes`에 `start` 키가 있으면 2, 키가 하나도 없으면 2, 그 밖은 1. 1이면 좌표를 쓰지 않고 `autoLayout`으로 그린다(구조가 달라 좌표 의미가 맞지 않음). 페이지는 "이전 버전 배치를 새 워크플로우 배치로 정리했습니다. [배치 저장]을 누르면 저장됩니다." 메시지를 띄우고 배치를 미저장 상태로 표시한 뒤 화면 맞춤을 한다 |
| 레이아웃 저장 | `extractLayout`은 v2 정규식에 맞는 모든 노드를 담는다(신규 LLM도 ID가 결정적이라 포함). 서버가 400 `FLOW_LAYOUT_INVALID`를 돌려주면(구버전 백엔드) `LAYOUT_NODE_ID_PATTERN_V1`에 맞는 키만 남겨 **한 번 재시도**한다 |
| 메모리 초안(draftStore) | 페이지 새로고침으로 사라진다. 같은 세션에서 `graph.schemaVersion !== 2`인 초안을 꺼내면 버리고 서버에서 다시 읽는다 |
| v1 Vue 컴포넌트 | Phase 1 동안 `@deprecated` 타입 멤버로 컴파일만 유지(D12). 동작은 보장하지 않으며 Phase 2에서 교체 |

### 7.9 자동 정렬과 새 노드 배치 (`layout.ts`)

**`autoLayout(graph, snapshot)`** — 레인(lane) 기반, 결정적, 16 격자 스냅, 같은 레인 안에서 겹치지 않음. `COLUMN_WIDTH = 300`, `ROW_GAP = 24`.
1. 레인 0: start(y = 0).
2. 레인 1: project. 허용된 프로젝트(이름순) 다음 미허용 프로젝트(이름순)를 세로로 쌓는다.
3. 레인 2(상속 정책 노드가 하나라도 있을 때만): `policy:P`를 자기 프로젝트와 같은 y에 둔다.
4. 다음 레인부터 트렁크 노드를 화면 순서대로 한 레인에 하나씩(y = 0). masker는 guardrail과 같은 레인, y = guardrail 높이 + 48.
5. 모델별 프롬프트가 하나라도 있으면 그다음 한 레인을 비워 `prompt:D`를 자기 LLM과 같은 y에 둔다.
6. 다음 레인: LLM을 `analyzeWorkflow().branches` 순서(로컬 먼저, priority·weight)로 쌓는다.
7. 다음 레인: endpoint/provider를 처음 쓰는 LLM의 y에 맞추되 겹치면 아래로 민다.
8. 분리된 흐름 노드는 전체 아래쪽(최대 y + 96)에 rank 순으로 가로로 늘어놓는다.

**새 노드 배치** (`position`이 없을 때):
- 끼워 넣기(A → N → B): `placeSpliced(graph, N, A, B)`. N을 (A.x + 300, A.y)에 두고, B.x < N.x + 300이면 **B와 B에서 도달 가능한 모든 노드(+ 종속 노드)**를 오른쪽으로 (N.x + 300 − B.x)만큼 민다(자리 만들기). A가 진입층이면 A = 진입층에서 가장 오른쪽 노드.
- 라우터 분기에 새 LLM: 기존 LLM 레인 x, 맨 아래 LLM 아래(+ ROW_GAP). LLM이 없으면 tail.x + 300.
- 모델별 프롬프트: (라우터 또는 tail).x + 300, 자기 LLM y. LLM 레인이 그보다 왼쪽이거나 같으면 모든 LLM(+ 종속)을 300 민다.
- 프로젝트: 프로젝트 레인(없으면 x = 300)의 맨 아래.
- 그 밖(연결 없이 추가 등): v1 `suggestNodePosition` 규칙(보이는 영역 중앙, 겹치면 아래로).
- 모든 경우 마지막에 겹침 검사 후 아래로 `ROW_GAP` 단위 이동(최대 200회).

**간선 히트 테스트** `nearestEdge(graph, point, radius, kinds)`: 각 간선의 베지어(`bezierPath`와 같은 제어점)를 24점으로 샘플링해 선분 거리 최솟값이 radius 이하인 간선 중 가장 가까운 것(동률이면 id 순). 팔레트에서 끌어 놓을 때 쓰며, 캔버스는 `radius = 16 / zoom`을 넘긴다.

---

## 8. 시스템 프롬프트 노드 (범위 · 백엔드 · 캐시)

### 8.1 결정: 두 범위 모두 지원 (백엔드 확장 필요)

| 범위 | 노드 | 위치 | 저장 | 적용 지점 |
|---|---|---|---|---|
| 공통 | `prompt` | 트렁크(라우터 앞) | `llm_service.system_prompt_template`(기존) | 비스트리밍 `ChatCompletionGateway.java:168`, 스트리밍 `:218`(기존) |
| 모델별 | `prompt:<deploymentId>` | 해당 LLM 바로 앞(라우터 뒤 또는 단일 LLM 앞) | **`service_target.system_prompt_template`(신규 V54)** | Target별 시도 안(신규, §8.3) |

이유: 사용자의 "LLM 앞에 둔 프롬프트는 그 LLM에 적용"을 그대로 실현한다. 서비스 단위만 두면 다중 LLM 워크플로우에서 특정 LLM 앞 프롬프트가 모든 LLM에 적용되는 거짓 표현이 생긴다. 모델마다 다른 지시(작은 로컬 모델용 짧은 지시, 외부 모델용 형식 지시 등)는 실제 운영 요구이기도 하다.

### 8.2 백엔드 변경 (모두 추가만, WP-D)

**마이그레이션** `src/main/resources/db/migration/V54__service_target_system_prompt.sql`
```sql
ALTER TABLE service_target ADD COLUMN system_prompt_template TEXT NULL;
```
(MariaDB·H2 공통 문법. 통합 테스트는 JPA로 스키마를 만들고 운영은 `ddl-auto: validate`이므로 엔티티 매핑이 정확히 일치해야 한다. 기존 행은 NULL = 기존 동작.)

**엔티티** `domain/ServiceTarget.java`
```java
@Column(name = "system_prompt_template", columnDefinition = "text") private String systemPromptTemplate;
public String getSystemPromptTemplate() { return systemPromptTemplate; }
/** Returns true when the stored value changed. Blank means none; values are trimmed. */
public boolean configureSystemPromptTemplate(String template, boolean clear) {
    String next;
    if (clear) next = null;
    else if (template == null) return false;
    else next = template.isBlank() ? null : template.trim();
    if (java.util.Objects.equals(this.systemPromptTemplate, next)) return false;
    this.systemPromptTemplate = next;
    return true;
}
```

**엔티티** `domain/LlmService.java`
```java
public void bumpPromptVersion() { this.promptVersion = Math.max(1, this.promptVersion + 1); }
public void applySystemPromptTemplate(ObjectNode request) { applySystemPromptTemplate(request, null); }
/** Service prompt first, then the target prompt, then the client's own first system message. */
public void applySystemPromptTemplate(ObjectNode request, String targetTemplate) { ... }
```
- `targetTemplate`이 null 또는 공백이면 **기존 메서드와 바이트 단위로 같은 결과**여야 한다(기존 본문을 그대로 쓰는 분기).
- 아니면 `combined` = (서비스 프롬프트가 공백이 아니면 `서비스 + "\n\n"`) + `targetTemplate.trim()`. 이후 삽입 규칙은 기존과 같다(첫 메시지가 텍스트 system이면 `combined + "\n\n" + 기존 내용`, 아니면 `{role: system, content: combined}`를 맨 앞에 삽입).

**DTO / API** (null = 변경 없음, 기존 생성자 유지)
- `RoutingPolicyController.UpdateTarget`: 컴포넌트 끝에 `@Size(max = 16000) String systemPromptTemplate, Boolean clearSystemPromptTemplate` 추가. 기존 6인자·5인자 생성자는 두 값을 null로 위임하는 명시 생성자로 유지한다.
- `RoutingPolicyController.TargetPolicyView`: 끝에 `@JsonInclude(JsonInclude.Include.ALWAYS) String systemPromptTemplate` 추가, `from()`에서 채운다. **항상 직렬화**(값이 null이어도 키가 있음)해 프런트가 서버 지원 여부를 알 수 있게 한다(§8.6). 전역 `non_null` 설정보다 속성 수준 주석이 우선한다.
- `AdminDtos.CreateTarget`: 끝에 `@Size(max = 16000) String systemPromptTemplate` 추가. 기존 6인자(`followModelChanges` 포함)·5인자 생성자 유지.
- `AdminController.TargetView`(POST 응답)는 바꾸지 않는다(프런트는 `id`만 씀).

**서비스 로직**
- `RoutingPolicyService.configureTarget`(`:265-272`): 기존 configure 뒤에 `boolean changed = target.configureSystemPromptTemplate(request.systemPromptTemplate(), Boolean.TRUE.equals(request.clearSystemPromptTemplate()))`. changed면 `requireService`가 돌려준 서비스에 `bumpPromptVersion()` 후 저장.
- `RoutingPolicyService.deleteTarget`(`:274-278`): 지우기 전 프롬프트가 null이 아니었으면 서비스 `bumpPromptVersion()` 후 저장.
- `ControlPlaneService.addTarget`(`:165-187`): 생성한 Target에 `configureSystemPromptTemplate(request.systemPromptTemplate(), false)`가 true면 서비스 `bumpPromptVersion()` 후 저장.
- 감사 로그는 기존 `AdminTokenFilter`가 자동 기록한다.

**게이트웨이**
- `ChatCompletionGateway.java:225`:
  ```java
  String targetPrompt = candidate.target() == null ? null : candidate.target().getSystemPromptTemplate();
  ObjectNode proxiedRequest;
  if (targetPrompt == null || targetPrompt.isBlank()) {
      proxiedRequest = preparedRequest.deepCopy();          // 기존 동작 그대로
  } else {
      proxiedRequest = request.deepCopy();                  // 원본에서 다시 조립
      service.applySystemPromptTemplate(proxiedRequest, targetPrompt);
  }
  ```
  `request`는 `:167` 이후 변경되지 않음을 구현자가 확인한다(바뀐다면 `:167` 직전에 원본 사본을 보관해 그것을 쓴다).
- `StreamingChatCompletionGateway.java:218`: `service.applySystemPromptTemplate(proxied, candidate.target() == null ? null : candidate.target().getSystemPromptTemplate())`.

**레이아웃 정규식** `ServiceFlowLayoutService.java:31-32`: §7.8의 v2 정규식으로 교체(상위 호환).

**OpenAPI** `docs/openapi.yaml`: `CreateServiceTarget`에 `systemPromptTemplate: { type: [string, 'null'], maxLength: 16000, description: 'Model-specific system prompt appended after the service prompt for this target only. Changing it increments the service promptVersion.' }`. `ServiceFlowLayout.nodes.description`에 v2 키(`start`, `params`, `policy:<id>`, `prompt:<id>`)를 적는다.

### 8.3 합성 순서

최종 system 메시지 = `[공통 프롬프트]` + `\n\n` + `[모델별 프롬프트]` + `\n\n` + `[클라이언트 system 메시지]`(있는 것만). 화면의 흐름(공통 → 라우터 → 모델별 → LLM)과 같은 순서다. 마스킹은 그 뒤에 일어나므로 두 프롬프트 모두 요청 본문과 같은 마스킹 규칙을 받는다(기존 서비스 프롬프트와 같은 동작). 비용 예약(`providerCap.reserveForAttempt`)은 합성된 요청으로 계산되므로 자동 반영된다.

### 8.4 응답 캐시 영향

- 캐시는 Target 선택 **전에** 서비스 단위 키로 조회한다(`ChatCompletionGateway.java:169-186`). 모델별 프롬프트를 키에 넣으려면 조회 시점에 Target을 알아야 해서 넣을 수 없다.
- 대신 **Target 프롬프트가 바뀌면(생성·수정·삭제) 서비스 `promptVersion`을 올린다**(§8.2). 캐시 키에 `v{promptVersion}`이 있으므로(`ResponseCacheService.java:38`) 기존 캐시가 자동으로 무효화된다. `ResponseCacheService`는 바꾸지 않는다.
- 캐시된 응답은 원래부터 "이 서비스의 어떤 Target이 만든 응답"이었으므로(서비스 단위 캐시), 프롬프트 구성이 그대로인 동안 다른 Target 선택 시에도 재사용되는 의미는 v1과 같다.
- 부수효과: ServicesPage의 'Prompt v' 표시가 Target 프롬프트 변경에도 올라간다. Plan 노트(`NOTE_TARGET_PROMPT`)로 안내한다.

### 8.5 프런트 매핑

- `flowSnapshot.mapTarget`: `systemPromptTemplate: normalizePrompt(typeof raw.systemPromptTemplate === 'string' ? raw.systemPromptTemplate : null)`.
- 컴파일·역컴파일·diff는 §7.3–7.6.
- 인스펙터: 모델별 프롬프트 노드에 "이 LLM에만 적용됩니다(공통 프롬프트 뒤에 붙음)." 도움말과 16,000자 제한. 같은 LLM 노드의 인스펙터에 [모델별 프롬프트 추가] 버튼(들어오는 간선에 `addNode prompt` splice)을 둔다.

### 8.6 버전 불일치 감지 (프런트가 먼저 배포된 경우)

- WP-D 이후 `GET /services/{id}/targets` 응답의 각 행에는 `systemPromptTemplate` 키가 항상 있다(`@JsonInclude(ALWAYS)`). `buildSnapshot`은 `features.targetPrompt = (Target 행이 0개) || (어떤 행이든 키가 있음)`으로 정한다.
- false면 모델별 프롬프트 삽입이 막히고(§6.6), 이미 있으면 `TARGET_PROMPT_UNSUPPORTED` 오류로 적용을 막는다.
- 보조 감지: 적용 후 재조회에서, 이번 실행이 성공시킨 createTarget/patchTarget body에 `systemPromptTemplate`이 있었는데 서버 Target 값이 null이면 `features.targetPrompt = false`로 바꾸고 "서버가 모델별 시스템 프롬프트를 저장하지 않았습니다(백엔드 업데이트 필요)." 메시지를 띄운다.

---

## 9. 팔레트 v2

### 9.1 구성 (`buildPalette(snapshot, graph, options): PaletteGroup[]`, 순수 TS)

| 순서 | group id | 라벨 | 내용 |
|---|---|---|---|
| 1 | `entry` | 시작 · 프로젝트 | 카탈로그 프로젝트(`PaletteProjectItem`). '시작' 노드는 항상 있으므로 목록에 없다. `projectsHidden`이면 빈 목록 + 안내 |
| 2 | `guard` | 가드레일 · 데이터 보호 | 외부 노출 방지 가드레일, 마스킹 워커(고급) |
| 3 | `prompt` | 프롬프트 | 시스템 프롬프트, 생성 파라미터(고급) |
| 4 | `cache` | 캐시 | 응답 캐시 |
| 5 | `routing` | 라우팅 | 라우터 · Failover |
| 6 | `llm-local` | 로컬 LLM | 로컬 Deployment(`PaletteModelItem`) |
| 7 | `llm-external` | 외부 LLM | 외부 Provider 모델 |

인프라(Runtime·Provider)는 추가할 수 없으므로 팔레트에 없다.

### 9.2 노드 항목 상태 (`PaletteNodeItem`)

| 항목 | `clickDisabledReason`(null이면 클릭 가능) | `draggable` |
|---|---|---|
| 가드레일 | 이미 있으면 '이미 워크플로우에 있습니다. 클릭하면 선택합니다.'(클릭 = 선택) | 없을 때만 |
| 마스킹 워커 | 가드레일이 없으면 '가드레일을 먼저 추가하세요.', 이미 있으면 '이미 있습니다.' | 둘 다 아닐 때 |
| 시스템 프롬프트 | 공통이 이미 있으면 '공통 프롬프트가 이미 있습니다. 특정 LLM에만 쓰려면 그 LLM 앞 간선에 끌어 놓거나 ＋를 누르세요.' | 항상 true(간선 위 놓기로 모델별 생성). 단 공통이 있고 `features.targetPrompt === false`면 false |
| 생성 파라미터·응답 캐시·라우터 | 이미 있으면 '이미 워크플로우에 있습니다.' (라우터는 LLM이 없어도 추가 가능) | 없을 때만 |

`onCanvas`는 해당 노드가 이미 있는지. 이미 있는 항목을 클릭하면 페이지가 그 노드를 선택·가운데 정렬한다(`select-node` 이벤트).

### 9.3 모델 항목 (`PaletteModelItem`)과 숨김·정렬

`modelAvailability(d, catalog)`:
| 결과 | 조건(위에서부터 첫 일치) | reason |
|---|---|---|
| inactive | `!d.enabled` | '비활성 모델' |
| inactive | 로컬이고 `!d.loaded` | '미로드' |
| inactive | 로컬이고 Runtime이 카탈로그에 있으며 `!enabled` | 'Runtime 비활성' |
| inactive | 외부이고 Provider가 `!enabled` | 'Provider 비활성' |
| inactive | 외부이고 Provider `!apiKeyConfigured` | 'API 키 미설정' |
| degraded | `toneOf(d.enabled, d.healthStatus)`가 warning·error, 또는 로컬 Runtime tone이 warning·error | '상태 확인 필요' |
| active | 그 밖(UNKNOWN 포함) | null |

- **기본 숨김**: `inactive`는 `options.showInactive`가 false면 숨긴다. 단 이미 워크플로우에 있는 모델(`onCanvas`)은 언제나 보인다. 그룹마다 `hiddenCount`를 돌려주고 컴포넌트가 "중지·미로드 {n}개 숨김 · 표시" 링크를 그린다.
- **정렬**: availability(active → degraded → inactive) → `displayName.localeCompare(…, 'ko')` → id.
- `sourceName`: 로컬은 Runtime 표시 이름, 외부는 Provider 표시 이름(없으면 '로컬 Runtime'/'외부 Provider').
- 표시: 이름, providerModelId, `${sourceName}`, 상태 칩(tone 라벨, inactive면 reason), '워크플로우에 있음' 칩.

### 9.4 검색·모드·접기

- `options.query`(trim, 소문자)는 그룹 라벨, 노드 라벨·설명·`keywords`, 프로젝트 이름·상태, 모델 이름·providerModelId·sourceName, '로컬'/'외부'에 부분 일치로 거른다. 결과가 없는 그룹은 빼고 돌려준다. 검색 중에는 숨김(inactive)도 일치하면 보인다(`hiddenCount`는 0).
- `options.mode === 'basic'`이면 `NodeTypeSpec.advanced === true`인 노드 항목(마스킹 워커, 생성 파라미터)을 뺀다.
- 그룹 접기 상태는 컴포넌트의 메모리 상태다(브라우저 저장소 금지). 기본: 모든 그룹 펼침. 검색 중에는 강제로 펼친다.
- DnD 페이로드와 클릭 명령: 프로젝트 `{nodeType: 'project', refId}`, 모델 `{nodeType: 'llm', refId: deploymentId}`, 노드 `{nodeType, refId: null}`.

---

## 10. UX

### 10.1 화면 구성 (베이스라인 `7be2814`의 전체 화면 작업 영역 유지)

```
┌ page-hero: WORKFLOW · "워크플로우 디자이너" [EXPERIMENTAL] · 워크플로우 선택기(+ 새 워크플로우 초안) ┐
├ FlowToolbar: ↶ ↷ │ 화면 맞춤 · 자동 정렬 │ − 100% ＋ │ [기본|고급] │ 검증 E n W n │ (순서 정리) │ 되돌리기 · 새로고침 · 배치 저장 │ [변경 검토 (n)] ┤
├────────────┬──────────────────────────────────────────────────────────┬────────────────┤
│ Palette     │ FlowCanvas (+ 빈 워크플로우면 FlowQuickStart 오버레이)       │ FlowInspector  │
├────────────┴──────────────────────────────────────────────────────────┴────────────────┤
│ 상태바: 변경 n건 · 오류 n · 경고 n · 불러온 시각 · 확대 n% · 모드 기본/고급 · 배치 미저장          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```
`.fd-stage` grid(`FlowDesignerPage.vue:124`), `.console-stage:has(.fd-page)` 규칙(`style.css:682-707`), 줌 버튼은 그대로다. 좁은 화면 규칙(1180px 미만 드로어, 640px 미만 읽기 전용, `useFlowEditor.ts:35-36`)도 그대로다.

### 10.2 기본 모드: 최소 단계

1. 새 워크플로우 초안을 열면 캔버스에 **시작** 노드와 **빠른 시작** 카드가 보인다.
2. 카드에서 '기본(시작 → 프로젝트 → LLM)'을 고르고 프로젝트(복수 선택)와 LLM 1개(활성 모델만 표시), 호출 이름·표시 이름을 입력한 뒤 [만들기]를 누른다.
3. [변경 검토] → [적용]. Plan은 `create-service → create-target → grant-access`다.

카드 없이도 팔레트에서 프로젝트 클릭 → 모델 클릭만으로 같은 결과가 된다(자동 배선). 이후 확장: 간선의 ＋ → '외부 노출 방지 가드레일' → 인스펙터에서 '보호 수준' 프리셋 하나 선택.

### 10.3 고급 모드

- 팔레트에 마스킹 워커·생성 파라미터가 보인다. 인스펙터에 고급 필드(가드레일 원시 필드, 라우터 대기열, LLM 가중치·동시성·모델 추적, 시작 노드 Capability·단가)가 보인다.
- 캔버스에 Runtime/Provider 노드와 `host` 간선이 보인다(기본 모드에서는 숨기되 그래프에는 남아 있음).
- Alt를 누른 채 놓으면 자동 배선 없이 추가, Ctrl/⌘ 드래그는 종속 노드 없이 단독 이동.
- 모드는 페이지 모듈 메모리에 기억한다(새로고침하면 기본 모드). 브라우저 저장소에 쓰지 않는다.

### 10.4 빈 워크플로우 빠른 시작 (`FlowQuickStart.vue`, `WORKFLOW_TEMPLATES`)

표시 조건: `isEmptyWorkflow(graph)`(흐름 노드가 하나도 없음) && 닫지 않음 && 편집 가능. 기존 서비스라도 Target·단계 설정이 하나도 없으면 보인다.

| id | 라벨 · 설명 | 입력 | `applyTemplate` 결과 | 실패 메시지 |
|---|---|---|---|---|
| `basic` | 기본 · 시작 → 프로젝트 → LLM | 프로젝트 0..n, LLM 1개 이상 | 프로젝트 허용, LLM 추가(2개 이상이면 라우터 자동) | LLM 0개: 'LLM을 하나 이상 고르세요.' |
| `local-first` | 로컬 우선 + 외부 Failover · 민감정보는 가려서 외부로 | 프로젝트, 로컬 ≥ 1 + 외부 ≥ 1 | 가드레일(`mask-restore`), 로컬 LLM priority 1..n, 외부 LLM 그 뒤, 라우터 | '로컬 LLM과 외부 LLM을 하나 이상씩 고르세요.' |
| `internal-only` | 사내 전용 · 외부 전송 없음 | 프로젝트, 로컬 ≥ 1 | 가드레일(`internal-only`), 로컬 LLM(2개 이상이면 라우터) | 외부 포함: '사내 전용 템플릿에는 로컬 LLM만 쓸 수 있습니다.' |

- 초안(`serviceId === null`)이면 카드에 '호출 이름(model 값)'과 '표시 이름' 입력을 두고, [만들기] 시 `applyTemplate` 다음에 `updateProps(start, {serviceKey, displayName})`를 보낸다(되돌리기 2단계).
- [직접 구성]을 누르면 카드를 닫는다(`dismiss`). 워크플로우를 다시 불러오면 조건에 따라 다시 보인다.

### 10.5 자동 배선·삽입 피드백

- 결과는 `aria-live`로 알린다: '가드레일을 처리 순서에 맞는 위치에 넣었습니다.', 'LLM이 두 개가 되어 라우터를 자동으로 추가했습니다.', '노드를 간선 사이에 넣었습니다.'
- 캔버스 상단 메시지(`message`)에는 명령의 `error`를 그대로 보인다(v1과 같음).

### 10.6 간선 ＋ 버튼과 삽입 메뉴

- `access`·`flow`·`route` 간선에 포인터를 올리거나 간선을 선택하면 중앙에 ＋ 원(SVG, `data-edge-insert`)을 그린다. `insertableTypesForEdge`가 비면 그리지 않는다. 선택된 편집 가능 간선의 × 버튼(`data-edge-delete`, v1)은 그대로이며 ＋와 겹치지 않게 옆에 둔다.
- ＋를 누르면 작은 메뉴(캔버스 위 HTML, `role="menu"`)에 넣을 수 있는 노드를 보인다(라벨은 `NODE_TYPES` 라벨, 프롬프트는 `promptScopeForSplice` 결과로 '공통 시스템 프롬프트'/'모델별 시스템 프롬프트'). 고르면 `addNode {nodeType, spliceEdgeId}`. Esc나 바깥 클릭으로 닫는다.
- 팔레트 항목을 간선 위(반경 16/zoom)에 놓으면 그 간선에 끼워 넣는다(그 간선에 넣을 수 없는 타입이면 일반 추가).
- 포인터 없이: 인스펙터의 간선 패널에 '이 간선에 노드 넣기' 버튼 목록을 둔다. ＋ 원은 간선이 선택되면 `tabindex="0"`이고 Enter로 메뉴를 연다.

### 10.7 인스펙터 변경

- 모드에 따라 `advanced`/`basicOnly` 필드를 거른다.
- 가드레일: 기본 모드는 '보호 수준' 프리셋 select(일치하는 프리셋이 없으면 '사용자 지정 — 고급 모드에서 편집' 항목 선택 상태). 보호 중이면 상단에 lockReason 안내와 **[보호 해제 후 삭제]**(위험 스타일, `window.confirm('외부 노출 방지 정책을 해제하고 가드레일을 삭제할까요? 적용하면 데이터 경계와 민감정보 처리가 기본값으로 바뀝니다.')` 후 `removeItems {nodeIds: ['guardrail'], edgeIds: [], force: true}`). 일반 [삭제] 버튼은 보호 중이면 비활성.
- 상속 정책 노드: 신호 목록(사실 행), lockReason, `<a href="#projects">프로젝트 화면에서 변경</a>`.
- LLM: '모델 교체' select(로컬/외부 `optgroup`, 이미 있는 모델 제외, inactive는 '(중지)' 접미사), [모델별 프롬프트 추가] 버튼.
- 이슈: `issue.fix`가 있으면 이슈 옆에 [`fix.label`] 버튼을 두고 누르면 `command` 이벤트로 `fix.command`를 보낸다.
- 간선 패널: '간선' 제목, `describeEdge` 의미, 양 끝 노드, 삽입 버튼 목록, (잠기지 않았으면) [간선 삭제].

---

## 11. 보안 · 권한

1. **서버가 유일한 집행 지점**이다(v1 §9.1 유지). 새 프런트 쓰기 경로는 없다. 모델별 프롬프트는 기존 Target PATCH/POST 경로(조직 관리자 필터 + 기존 검증)를 쓰고 길이는 서버가 `@Size(max = 16000)`로 막는다.
2. **새 읽기**: `GET /api/admin/organizations/{org}/data-protection`(조직 경로, 필터가 조직 관리자 확인, DataProtectionPage가 이미 사용). 응답의 `customPatternsJson`·탐지 설정은 **스냅샷에 복사하지 않는다**. `flowSnapshot`은 PROJECT 범위 행의 `{mode, externalAction}`과 `effective`의 `{mode, externalAction}`만 뽑는다(테스트로 고정). 읽기 실패는 치명적이지 않다(`projectPoliciesComplete=false`).
3. **비밀값 비보관**: v1 규칙 유지. 프롬프트 본문은 그래프 props(메모리)에만 있고 레이아웃·Plan 표시에는 길이만 나온다. 상속 정책 노드는 프로젝트 id와 신호 라벨만 가진다. 레이아웃 키에는 id만 들어간다(정규식 `[A-Za-z0-9-]{1,64}`).
4. **비상 플랫폼 토큰 세션**: `currentUserId() === 'platform-token'`이면 `projectsHidden=true`, `accessComplete=false`다. 프로젝트 노드를 만들 수 없고 접근 변경은 `ACCESS_EDIT_UNAVAILABLE`로 막힌다. 서버 상태를 잘못 "접근 없음"으로 덮어쓰는 일이 없다(접근 작업 0건).
5. **프로젝트 접근은 프로젝트 단위 작업**(`/projects/{pid}/service-access`)이다. 다른 프로젝트의 정책·키는 건드리지 않는다. 프로젝트 정책은 읽기 전용으로만 표시한다.
6. **보호 해제**는 이중 확인이다: 인스펙터 확인 창 + Plan의 danger 체크박스.
7. **한도**: v1과 같다(노드 300, 간선 600, 작업 100, 프롬프트 16,000, 레이아웃 60KB/500노드). 모델별 프롬프트는 LLM 수만큼 늘 수 있으나 각각 16,000자다.
8. **XSS**: `v-html`·`innerHTML` 금지(정적 테스트 유지). DnD 페이로드는 `nodeType`이 허용 목록에 있고 `refId`가 80자 이하 문자열일 때만 받는다.
9. **감사**: 모든 적용 작업과 레이아웃 저장은 기존 필터가 기록한다. 레이아웃 자동 저장은 하지 않는다(v1 N12).

---

## 12. 테스트 계획

### 12.1 WP-A — `frontend/tests/flowDesignerModel.test.mjs` (재작성)

픽스처: 기존 서비스 svc1(guardrailAction BLOCK, 마스커 a, 공통 프롬프트, 캐시 사용, failover COMPATIBLE), 로컬 Runtime ep1(Deployment a 로드됨, b 미로드), 외부 Provider pv1(모델 x, 키 설정됨), Provider pv2(모델 y, 키 미설정), 프로젝트 p1(일반, 허용), p2(일반, 미허용), p3(`externalAiBlocked`, 허용), p4(`dlp: {mode: 'ENFORCE', externalAction: 'LOCAL_ONLY'}`), Target t1(a, P1 W80, 모델별 프롬프트 'Be brief'), t2(x, P2 W100, override 2), 마스커 후보(a 적격, x 부적격), `features.targetPrompt: true`.

| # | 케이스 |
|---|---|
| 1 | 카탈로그 무결성: 규칙이 참조하는 포트가 모두 있다. start는 삭제 불가, policy·endpoint·provider는 `derived` + 팔레트 불가. 모든 비가상 필드 key를 compile이 읽는다. 레거시 `target` 스펙은 포트·필드가 없다 |
| 2 | decompile 결정성과 ID: 두 번 결과가 같다. 노드 id 집합(start, project:p1, project:p3, policy:p3, guardrail, masker, prompt, cache, router, deployment:a, deployment:x, prompt:a, endpoint:ep1, provider:pv1), 간선 kind, 트렁크 정규 순서, `schemaVersion: 2` |
| 3 | **왕복 불변식**: 픽스처, 기본값 서비스(Target 0), Target 1개(라우터 없음), 파라미터만 비기본, ALLOW + 마스커, maskPersonNames false + 가드레일 없음, LOCAL_FIRST_WAIT + Target 1, 모델별 프롬프트만, 초안 — 모두 `compile(decompile(s)) ≡ s.config` |
| 4 | 존재 기반 컴파일: 좌표 이동·트렁크 순서 뒤집기(캐시 → 가드레일)는 compile 결과를 바꾸지 않는다. 캐시 삭제 → `responseCacheEnabled=false`, TTL = base. 파라미터 삭제 → 기본값 |
| 5 | 기본 워크플로우: 초안 + addNode project p1 + addNode llm a → 접근 [p1], Target 1(key `target:new-a`, P1, W100), 라우터 없음. serviceKey 입력 전 `SERVICE_KEY_INVALID`, 입력 후 오류 0. Plan opId 순서 `create-service, create-target:target:new-a, grant-access:p1` |
| 6 | 두 번째 LLM: addNode llm x → 라우터 자동, `router→a`, `router→x`, x `followModelChanges=false`, priority 2. `connect`로 트렁크 끝 → 두 번째 LLM도 같은 결과 |
| 7 | splice: access 간선에 cache → cache가 head, 모든 프로젝트 access가 cache로. flow 간선(guardrail→cache)에 params → `guardrail → params → cache`. route 간선(router→x)에 prompt → `prompt:x`, 출력 간선 잠김. `insertableTypesForEdge`·`promptScopeForSplice` 결과 |
| 8 | 자동 배선 정규 위치: 빈 트렁크에 cache, guardrail, prompt 순으로 추가 → `guardrail → prompt → cache`. 같은 타입 재추가 실패 메시지 |
| 9 | 비정규 순서: 수동 연결로 `cache → guardrail` → `WF_ORDER_NONCANONICAL`(fix normalizeOrder) → `normalizeOrder` 후 정규 순서·경고 없음. 전 과정 compile 동일 |
| 10 | 불가능한 배치: `canConnect(router.out → cache.in)` 거부 메시지. 손으로 만든 그래프에서 라우터 뒤 캐시 `WF_STAGE_AFTER_ROUTER`, 비라우터 분기 `WF_ROUTER_REQUIRED`(fix), 분리 LLM `WF_LLM_DETACHED`, root 2개 `WF_MULTIPLE_ROOTS`, 순환 `CYCLE_DETECTED`, `prompt:a`가 x를 가리키면 `WF_BRANCH_PROMPT_MISPLACED` |
| 11 | 삭제·치유: 가운데 cache 삭제 → 앞뒤 연결. LLM 2개 라우터 삭제 거부. LLM 1개 라우터 삭제 → tail→LLM. guardrail 삭제(force) → masker도 삭제. LLM a 삭제 → `prompt:a`도 삭제. start/policy/host 삭제 거부 메시지. entry 간선 삭제 = 접근 해제(노드 유지). access 간선 삭제 거부 |
| 12 | **보호 잠금**: BLOCK 서비스의 guardrail은 `protected`. force 없는 삭제는 오류·그래프 불변. force 삭제 → compile ALLOW/EXTERNAL_ALLOWED/마스커 null, patch-service risk danger + `NOTE_PROTECTION_RELAXED`, `GUARDRAIL_REMOVED` 경고. 프리셋으로 완화 → `GUARDRAIL_RELAXED`. 초안은 보호 없음 |
| 13 | **상속 정책 노드**: p3 허용 → `policy:p3`, `project:p3→policy:p3`(policy, 잠김), `policy:p3→head`(access, 잠김). 삭제 거부. `setAccess p3 false` → 정책 노드 사라짐. 로컬 LLM이 모두 사용 중지면 `POLICY_NO_LOCAL_LLM`, 외부 LLM이 있으면 `POLICY_SKIPS_EXTERNAL`. p4 허용 → 신호 `PROJECT_DLP`, `blocksExternal=false`. `maxDataBoundaryPolicy: INTERNAL_ONLY` → `PROJECT_BOUNDARY`(blocksExternal). `projectSignals(undefined)` = [] |
| 14 | 의존 이동: `dependencyClosure(['guardrail'])` ⊇ masker. `['deployment:a']` ⊇ `prompt:a`, ep1을 b도 쓰는 그래프에서는 ep1 미포함, a만 쓰면 포함. `['project:p3']` ⊇ `policy:p3`. masker의 closure = [masker] |
| 15 | 모델별 프롬프트 Plan: x에 프롬프트 추가 → patch-target body `{systemPromptTemplate}` risk caution + `NOTE_TARGET_PROMPT`. a 프롬프트를 '  '로 → `{clearSystemPromptTemplate: true}`. 신규 LLM + 프롬프트 → create body에 `systemPromptTemplate`. `features.targetPrompt=false` → `TARGET_PROMPT_UNSUPPORTED` 오류, splice 실패 |
| 16 | 공통 프롬프트: v1 케이스 8(공백 정규화, 앞뒤 공백만 바꾸면 작업 0, 변경 시 버전 노트) 이식. 공통이 있을 때 간선 없는 prompt 추가 실패 메시지 |
| 17 | `replaceModel(a → b)`: `deployment:b`(targetId null) 생성, props 복사, `prompt:a → prompt:b`, a 삭제. Plan: `create-target:target:new-b` + `delete-target:t1`. 이미 있는 모델로 교체 거부 |
| 18 | 접근: `connect(start.out → project:p2.in)` = 허용, `project.out` 끌기 거부 메시지, `accessComplete=false`면 `ACCESS_EDIT_UNAVAILABLE`, `projectsHidden` → `PROJECTS_HIDDEN` 정보, `WF_NO_PROJECT` 조건 |
| 19 | v1 도메인 회귀 이식: weight만 patch, guardrailAction 변경 노트·danger, 마스커 해제·부적격 차단, FIXED temperature(파라미터 노드), 신규 비활성 LLM의 disable-created, 3-way 5종, 원격 삭제/변경 충돌, 원격 동일 Deployment 생성 → 채택 후 rebase(targetId 설정), 부분 적용 rebase(좌표 보존·남은 작업만), 신규 서비스 순서·키 검증, 스케줄링 검증과 병합 body(라우터), TTL 범위·override 해제, 작업 101개 `PLAN_TOO_LARGE`와 결정성, 모든 body 키 화이트리스트(Target 프롬프트 키 포함) |
| 20 | `syncGraphToConfig`: 원격 캐시 추가 → 정규 위치 삽입, 원격 가드레일 제거 → 치유, 원격 재바인딩(t1: a → b) → `deployment:a`가 `deployment:b`로 이름 변경(좌표 유지), 원격 신규 Target → 라우터 분기 추가 |
| 21 | 레이아웃: v2 정규식 허용(start, params, `policy:x`, `prompt:x`, `deployment:x`)·거부(`evil`, `prompt:../x`), V1 정규식은 start 거부. `layoutGeneration`. v1 저장본 `applyLayout` → autoLayout과 같음. autoLayout 결정성·같은 레인 비겹침·레인 x 순서(start < project < trunk < llm < host)·분기 순서(로컬 먼저). `extractLayout`에 이름·주소·프롬프트 없음(JSON 문자열 검사). `nearestEdge`. `placeSpliced`가 하류를 밀어 겹침 없음. v1 기하 케이스 24 유지 |
| 22 | 팔레트: 그룹 순서·라벨, 로컬/외부 분리, b(미로드)·y(키 미설정) 기본 숨김 + `hiddenCount`, `showInactive`로 보임, 정렬(active → degraded → inactive), 워크플로우에 있는 inactive 모델은 항상 보임, 검색 거르기·빈 그룹 제외, 기본 모드에서 masker·params 항목 제외, 항목 비활성 사유 문구 |
| 23 | `applyTemplate` 3종 결과, 비어 있지 않은 워크플로우 거부, internal-only에 외부 포함 거부, local-first 조건 |
| 24 | `computeNodeChanges` v2: 가드레일 added/modified, `prompt:D` added, LLM added(targetId null)/modified, 프로젝트 added/modified |
| 25 | `describeNode`/`nodeFacts`/`describeEdge`가 모든 타입(레거시 target 포함)에서 예외 없이 동작. 보호·잠금·promptScope 값 |
| 26 | `canConnect` 사유 전체(§6.4)와 `nearestCompatiblePort` |
| 27 | history(v1 26), `clientToLocal`(v1), `suggestNodePosition`(v2 타입) |
| 28 | 레거시 호환: `bindTarget` 실패 메시지, `addNode 'deployment'` = llm, `addNode 'target'` 실패 |

### 12.2 WP-B — `frontend/tests/flowDesignerUi.test.mjs` (신규, 소스 정적 검사)

1. `FlowCanvas.vue`: `ADDABLE`에 `'llm'`, `'guardrail'`, `'prompt'`, `'params'`, `'cache'`, `'router'`, `'masker'`, `'project'`가 있고 `'target'`·`'binding'` 문자열이 없다. `dependencyClosure`를 import한다. `data-edge-insert`가 있고 포인터다운에서 제외된다. '선택한 간선 삭제'가 있고 '선택한 연결 삭제'가 없다. `mode` prop 기본값.
2. `FlowInspector.vue`: `bindTarget`·`deployment-binding` 문자열이 없다. `replaceModel`, `force: true`, `guardrail-preset`, `model-replace`가 있다. '간선 삭제'가 있다.
3. `FlowPalette.vue`: `buildPalette`를 import하고 직접 정렬 로직(`localeCompare`)이 없다. 숨김 토글 라벨 '중지·미로드'.
4. `FlowToolbar.vue`: '워크플로우 디자이너 도구 모음', `fire('mode-basic')`, `fire('mode-advanced')`, `fire('normalize-order')`, 기존 `fire('zoom-*')` 유지.
5. `FlowQuickStart.vue`: `applyTemplate` 명령을 보내고 `WORKFLOW_TEMPLATES`를 import한다.
6. 모든 B 파일에 `localStorage`, `sessionStorage`, `v-html`, `innerHTML`이 없다.

### 12.3 WP-C — `frontend/tests/flowDesignerAdapter.test.mjs` (기존 유지 + 추가)

1. `buildSnapshot`: Target `systemPromptTemplate` 매핑·정규화. `features.targetPrompt`(키 있음 → true, 행은 있는데 키 없음 → false, 행 0 → true). 프로젝트 `externalAiBlocked`·`maxDataBoundaryPolicy`·`dlp`(PROJECT 범위만, 다른 범위 무시). `orgDataProtection = effective`의 두 필드. `projectPoliciesComplete`(조직 DLP null → false). `projectsHidden: true` → `accessComplete: false`. FIXED가 아니면 `temperatureValue = null`. `JSON.stringify(snapshot)`에 `customPatternsJson`·`detectSecrets` 같은 조직 정책 세부가 없다.
2. `operationRequest`: createTarget body의 `systemPromptTemplate` 통과, patchTarget `clearSystemPromptTemplate`.
3. 기존 프로젝트 접근 시나리오(`:406-443`)는 기대값을 바꾸지 않고 통과해야 한다.
4. ConsoleShell: label `'워크플로우 디자이너'`, description에 '워크플로우', keywords에 'workflow'와 '플로우' 모두 포함. 기존 id 규칙 유지.
5. 페이지 용어: `FlowDesignerPage.vue`에 '워크플로우 디자이너'가 있고 '서비스 플로우 디자이너'가 없다. `FlowPlanModal.vue`에 '워크플로우에 그대로'.
6. 기존 정적 안전 검사, 베이스라인 검사(`:458-480`) 유지.

### 12.4 WP-D — 백엔드

| 테스트 | 내용 |
|---|---|
| `src/test/java/com/aiconnect/llmgateway/ServiceTargetSystemPromptIntegrationTest.java`(신규, `RoutingPolicyManagementIntegrationTest` 패턴, H2 `jdbc:h2:mem:aiconnect_target_prompt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1`) | (1) PATCH `{systemPromptTemplate: '  Be brief  '}` → GET targets에 'Be brief', 서비스 promptVersion +1. 같은 값 재전송 → 증가 없음. `clearSystemPromptTemplate` → null, +1. 공백 문자열 → null. 16,001자 → 400. (2) POST target에 프롬프트 → 저장·+1, 없으면 증가 없음. (3) 프롬프트 있는 Target DELETE → +1. (4) GET targets 응답 JSON에 프롬프트 없는 Target도 `"systemPromptTemplate":null` 키가 있다. (5) 게이트웨이(`GatewayFailoverIntegrationTest`의 `server(..., received)` 패턴): 서비스 'S' + Target 'T' + 클라이언트 system 'C' → 업스트림 `messages[0].content == "S\n\nT\n\nC"`. Target 프롬프트 없음 → "S\n\nC"(기존과 동일). 서비스 프롬프트 없음 → "T\n\nC". 스트리밍 요청 1건도 같은 합성 |
| `LlmServicePromptCompositionTest`(신규 단위) | `applySystemPromptTemplate(req, null)` 결과가 기존 1인자와 같음(공백 포함 레거시 값 포함), 합성 순서, system 메시지 없을 때 삽입 |
| `ServiceFlowLayoutIntegrationTest`(추가 1건) | v2 키(`start`, `params`, `policy:<uuid>`, `prompt:<uuid>`, `deployment:<uuid>`) PUT 200, `prompt:../x`·`policy:` 400 |
| `FlywayUpgradeIntegrationTest`(MariaDB CI 전용, 1줄) | `information_schema.columns`에 `service_target.system_prompt_template` 존재 |
| 회귀 | `RoutingPolicyManagementIntegrationTest`, `GatewayFailoverIntegrationTest`, `GatewayStreaming*`, `OpenApiContractTest`, `DatabaseSchemaIntegrationTest`, `MigrationVersionOrderingTest`, 그 뒤 전체 `gradle test` |

### 12.5 수동 브라우저 체크리스트 (Phase 3, `npm run dev` + 백엔드 8080, 인계 §7 실행법)

1. 사이드바 '워크플로우 디자이너'가 보이고 ⌘K에서 '워크플로우', '플로우'로 찾을 수 있다. 딥링크 `#flow-designer/<id>`가 동작한다.
2. 새 초안 → 빠른 시작 '기본' → 프로젝트·LLM 선택 → 적용 → ServicesPage에서 서비스·Target·프로젝트 접근 확인.
3. 기본 모드 팔레트에 마스킹 워커·생성 파라미터가 없고, 고급 모드에 있다. 미로드 모델이 기본으로 숨고 토글하면 보인다. 검색이 그룹을 거른다.
4. 간선 ＋로 가드레일을 넣고 프리셋을 고르면 Plan에 DLP 부수효과 노트가 나온다.
5. LLM 두 번째 추가 시 라우터 자동 삽입, 라우터 뒤 간선 ＋로 모델별 프롬프트 추가 → 적용 → 실제 호출에서 그 모델에만 프롬프트가 붙는다(Request 진단 또는 테스트 Runtime 로그).
6. 보호 중인 서비스에서 가드레일 Delete 키는 거부, [보호 해제 후 삭제]는 확인 창 → Plan에 위험 체크박스.
7. 외부 AI 전송 금지 프로젝트를 허용하면 상속 정책 노드가 들어가고 삭제할 수 없다. 로컬 LLM이 없으면 경고.
8. 가드레일을 드래그하면 마스킹 워커가, LLM을 드래그하면 모델별 프롬프트와 전용 Runtime이 함께 움직인다. Ctrl 드래그는 단독.
9. 트렁크 순서를 뒤집으면 경고와 [순서 정리] 버튼이 나오고, 누르면 정렬된다. Plan은 0건이다.
10. v1에서 저장한 배치가 있는 서비스를 열면 자동 정렬 안내가 뜨고 [배치 저장] 후 새로고침해도 유지된다.
11. 비상 토큰 세션에서 프로젝트 숨김 안내가 보이고 접근 변경 작업이 0건이다.
12. 기존 화면(LLM 서비스, 프로젝트, 인프라, 외부 AI, 데이터 보호, 시스템 구조) 스모크 테스트. `npm run build`에서 메인 번들 크기 변화 ±1KB 안.

---

## 13. 작업 지시서

### 13.0 공통 규칙 (모든 WP 필독)

- 이 문서의 §13.2(`types.ts` 전문)와 §13.3(시그니처), §13.4(B↔C 인터페이스)가 **계약**이다. 이름·필드·시그니처를 바꾸지 말 것. 꼭 바꿔야 하면 멈추고 보고한다. 설계와 코드가 모순이면 이 문서를 고치지 말고 보고한다.
- **파일 소유가 엄격하다**(§13.1 표). 자기 WP 표에 없는 파일은 만들거나 수정하지 않는다. 특히 `frontend/src/style.css`, `.github/workflows/quality-gate.yml`, 다른 페이지(`ServicesPage.vue` 등), `api.ts`, `BaseModal.vue`는 아무도 수정하지 않는다.
- npm·Gradle 의존성 추가 금지. 사용자 노출 문자열은 한국어, 식별자는 영어. `flow-designer/` 안에서 `localStorage`, `sessionStorage` 쓰기, `v-html`, `innerHTML` 금지. 상대 import는 확장자 없이, 타입은 `import type`.
- 순수 TS 모듈(WP-A 전부)은 `vue`, `../api`, DOM 전역, `Math.random`, `crypto.randomUUID`, 현재 시각을 쓰지 않는다. import 방향(순환 금지): `types ← catalog ← workflow ← layout ← graphOps ← compile ← decompile ← validate ← diff`, `palette`는 `catalog`·`workflow`만 import.
- 베이스라인 `7be2814` 보존: `.fd-stage` grid·`100dvh / zoom`(`FlowDesignerPage.vue`), `.fd-world`·`will-change`·`contain`·`isZooming`(`FlowCanvas.vue`), `fire('zoom-*')`(`FlowToolbar.vue`), `onPointerUp`이 `resetInteraction()` 뒤에 `moved`를 읽지 않는 구조. `flowDesignerAdapter.test.mjs:392-405, 458-480`이 이를 검사한다.
- 커밋·푸시·PR은 사용자가 요청할 때만 한다. 일부 파일은 CRLF다(`.gitattributes`가 정규화).
- **각 WP 완료 조건**: 아래 프런트 테스트 체인 전체 + `npm run build`가 녹색(WP-D는 백엔드 대상 테스트). 프런트 명령은 Git Bash에서 실행한다(PowerShell 5.1은 `&&` 미지원).
  ```bash
  cd /c/project/aiconnect/frontend
  npm run build
  npm run test:docs && npm run test:quota && npm run test:topology && npm run test:project-security && npm run test:runtime-status && npm run test:date-time && npm run test:playground-response && npm run test:external-model-deletion && npm run test:external-capabilities && npm run test:beta-operator && npm run test:totp-hierarchy && npm run test:project-governance && npm run test:flow-designer && npm run test:services-page && npm run test:registration-modal
  ```
  백엔드:
  ```bash
  cd /c/project/aiconnect && JAVA_HOME="C:/Users/kjs99/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1" \
    "C:/Users/kjs99/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle" \
    test --tests '<pattern>' --no-daemon
  ```

### 13.1 단계 · 병렬성 · 파일 소유

| 단계 | 실행 | 선행 | 비고 |
|---|---|---|---|
| Phase 1 | **WP-A ∥ WP-D** | 없음 | 서로 독립. WP-A는 레거시 타입 멤버로 v1 Vue가 계속 컴파일되게 유지(D12) |
| Phase 2 | **WP-B ∥ WP-C** | WP-A 병합 | B의 새 prop은 모두 선택(기본값 있음), C는 B가 병합되기 전에도 새 prop을 넘길 수 있다(strictTemplates 없음). WP-D가 없어도 C는 동작해야 한다(§8.6 감지, §7.8 재시도) |
| Phase 3 | 통합 검증(WP-C 담당) + 레거시 정리(WP-A 담당) | A·B·C·D 병합 | §12.5 체크리스트, 전체 프런트 체인·빌드, 백엔드 전체 테스트. WP-A는 B·C가 더 이상 쓰지 않는 레거시 멤버(`NodeType 'target'`, `EdgeKind 'binding'`, `FieldKind 'deployment-binding'`, `AddableNodeType 'deployment'·'target'`, `bindTarget`, `NODE_TYPES.target`)를 지우고 빌드·테스트를 다시 돌린다(남은 사용처가 있으면 지우지 말고 보고) |

| 파일 | 소유 | 비고 |
|---|---|---|
| `frontend/src/flow-designer/types.ts` | A | §13.2 전문 |
| `frontend/src/flow-designer/catalog.ts`, `layout.ts`, `graphOps.ts`, `compile.ts`, `decompile.ts`, `validate.ts`, `diff.ts`, `history.ts`(변경 없음) | A | |
| `frontend/src/flow-designer/workflow.ts`, `palette.ts` | A | 신규 |
| `frontend/tests/flowDesignerModel.test.mjs` | A | 재작성(§12.1) |
| `frontend/src/flow-designer/FlowCanvas.vue`, `FlowNodeCard.vue`, `FlowPalette.vue`, `FlowInspector.vue`, `FlowToolbar.vue` | B | |
| `frontend/src/flow-designer/FlowQuickStart.vue` | B | 신규 |
| `frontend/tests/flowDesignerUi.test.mjs` | B | 신규 |
| `frontend/package.json` | B | `test:flow-designer` 스크립트 끝에 ` tests/flowDesignerUi.test.mjs` 추가 1곳만 |
| `frontend/src/flow-designer/FlowDesignerPage.vue`, `FlowPlanModal.vue`, `useFlowEditor.ts`, `flowApi.ts`, `flowSnapshot.ts`, `execute.ts`, `draftStore.ts` | C | `execute.ts`·`draftStore.ts`는 필요할 때만 |
| `frontend/tests/flowDesignerAdapter.test.mjs` | C | |
| `frontend/src/ConsoleShell.vue` | C | 61행(nav 항목 label·description·keywords)만 |
| `docs/flow-designer-design.ko.md`(머리말 1줄), `docs/handoff/2026-11-2x-workflow-designer-v2.ko.md`(선택, 신규) | C | |
| `src/main/resources/db/migration/V54__service_target_system_prompt.sql` | D | 신규 |
| `src/main/java/com/aiconnect/llmgateway/domain/ServiceTarget.java`, `domain/LlmService.java` | D | |
| `.../admin/RoutingPolicyController.java`, `admin/RoutingPolicyService.java`, `admin/AdminDtos.java`, `admin/ControlPlaneService.java` | D | |
| `.../gateway/ChatCompletionGateway.java`, `gateway/StreamingChatCompletionGateway.java` | D | 지정 줄만 |
| `.../flowdesigner/ServiceFlowLayoutService.java` | D | 정규식 1곳 |
| `docs/openapi.yaml` | D | |
| `src/test/java/com/aiconnect/llmgateway/ServiceTargetSystemPromptIntegrationTest.java`, `LlmServicePromptCompositionTest.java` | D | 신규 |
| `src/test/java/com/aiconnect/llmgateway/ServiceFlowLayoutIntegrationTest.java`, `FlywayUpgradeIntegrationTest.java` | D | 추가만 |
| `frontend/src/style.css`, `.github/workflows/quality-gate.yml`, 이 문서 | 없음 | 수정 금지 |

### 13.2 `frontend/src/flow-designer/types.ts` 전문 (WP-A가 이대로 작성)

```ts
// Shared contracts for the experimental Workflow Designer (v2; page key 'flow-designer').
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
  /** 'target:<targetId>' for persisted targets, 'target:new-<deploymentId>' for pending ones. */
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
  /** v2 (backend V54): model-specific system prompt, trimmed; null = none. Readers treat undefined as null. */
  systemPromptTemplate?: string | null
}
export type TargetField = 'deploymentId' | 'priority' | 'weight' | 'degraded' | 'enabled' | 'maxConcurrencyOverride' | 'followModelChanges' | 'systemPromptTemplate'

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
/** Stored PROJECT-scope data-protection policy: only what the designer needs (never custom patterns). */
export interface ProjectDlpInfo { mode: string; externalAction: GuardrailAction }
export interface ProjectInfo {
  id: string
  name: string
  status: string
  /** Server getter value (already INTERNAL_ONLY when externalAiBlocked). */
  dataBoundaryPolicy: DataBoundaryPolicy | null
  /** v2. Readers treat undefined as null. */
  maxDataBoundaryPolicy?: DataBoundaryPolicy | null
  /** v2: project "외부 AI 전송 금지". Readers treat undefined as false. */
  externalAiBlocked?: boolean
  /** v2: stored PROJECT-scope DLP policy; null/undefined = none stored or unknown. */
  dlp?: ProjectDlpInfo | null
}
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
/** Effective organization (platform + organization) data-protection policy. */
export interface OrgDataProtectionInfo { mode: string; externalAction: string }
export interface FlowCatalog {
  services: ServiceSummary[]
  deployments: DeploymentInfo[]
  endpoints: EndpointInfo[]
  providers: ProviderInfo[]
  projects: ProjectInfo[]
  maskers: MaskerInfo[]
}
export interface SnapshotFeatures {
  /** false when the server does not persist model-specific system prompts (pre-V54 backend). */
  targetPrompt: boolean
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
  /** v2. Readers treat undefined as null. */
  orgDataProtection?: OrgDataProtectionInfo | null
  /** v2: false when project-scope DLP policies could not be read. Readers treat undefined as true. */
  projectPoliciesComplete?: boolean
  /** v2: break-glass platform-token session (projects invisible). Readers treat undefined as false. */
  projectsHidden?: boolean
  /** v2. Readers treat undefined as { targetPrompt: true }. */
  features?: SnapshotFeatures
}

/* ---------------- Graph ---------------- */
/** 'target' is a v1 legacy member kept only so v1 components compile during migration (never instantiated; removed in Phase 3). */
export type NodeType = 'start' | 'project' | 'policy' | 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router' | 'llm' | 'endpoint' | 'provider' | 'target'
export type NodeCategory = 'entry' | 'guard' | 'prompt' | 'cache' | 'routing' | 'llm' | 'infra'
export type PortType = 'flow' | 'masker' | 'host'
export type PortDirection = 'in' | 'out'
/** 'binding' is a v1 legacy member (never produced; removed in Phase 3). */
export type EdgeKind = 'entry' | 'policy' | 'access' | 'flow' | 'route' | 'masker' | 'host' | 'binding'
export type FlowPropValue = string | number | boolean | null
export type PromptScope = 'common' | 'model'

export interface FlowNode {
  id: string
  type: NodeType
  /** Primary entity: projectId (project, policy), deploymentId (llm), endpoint/provider id (hosts); null otherwise. */
  refId: string | null
  /** llm only: server ServiceTarget id, null while pending creation. Always null for other types. */
  targetId: string | null
  /** World coordinates of the top-left corner. */
  x: number
  y: number
  collapsed: boolean
  props: Record<string, FlowPropValue>
}
export interface PortRef { nodeId: string; portId: string }
export interface FlowEdge { id: string; kind: EdgeKind; from: PortRef; to: PortRef; locked: boolean }
export interface FlowGraph {
  schemaVersion: 2
  serviceId: string | null
  nodes: FlowNode[]
  edges: FlowEdge[]
}

export interface PortSpec { id: string; direction: PortDirection; type: PortType; label: string; multiple: boolean }
/** 'deployment-binding' is a v1 legacy member (no v2 field uses it; removed in Phase 3). */
export type FieldKind = 'text' | 'textarea' | 'number' | 'integer' | 'boolean' | 'select' | 'capabilities' | 'masker-select' | 'access-toggle' | 'guardrail-preset' | 'model-replace' | 'deployment-binding'
export interface FieldOption { value: string; label: string }
export interface FieldSpec {
  /** Prop key, or a virtual key starting with '$' ('$access', '$preset', '$model'). */
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
  /** v2: shown only in advanced mode. */
  advanced?: boolean
  /** v2: shown only in basic mode. */
  basicOnly?: boolean
}
export interface NodeTypeSpec {
  type: NodeType
  label: string
  /** v2: one-line palette description. */
  description: string
  /** v2: extra lowercase search words (Korean and English, space separated). */
  keywords: string
  category: NodeCategory
  icon: string
  /** v2: canonical server-order rank (§6.3); model-specific prompts use PROMPT_BRANCH_RANK instead. */
  rank: number
  /** At most one node of this type (prompt is false: one common + one per LLM). */
  singleton: boolean
  deletable: boolean
  paletteAddable: boolean
  /** v2: hidden from the palette in basic mode. */
  advanced: boolean
  /** v2: created and removed only by synchronisation (policy, endpoint, provider). */
  derived: boolean
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
  /** Never deletable (start, derived nodes). */
  locked: boolean
  /** v2: deletable only through the explicit "보호 해제 후 삭제" action. */
  protected: boolean
  /** v2: reason shown for locked/protected nodes; null otherwise. */
  lockReason: string | null
  /** v2: prompt nodes only; null for other types. */
  promptScope: PromptScope | null
}
export interface FactRow { label: string; value: string }

/* ---------------- Workflow analysis & policy (v2) ---------------- */
export interface WorkflowBranch { llmNodeId: string; promptNodeId: string | null }
export interface WorkflowAnalysis {
  /** First flow node after the entry layer; null when the workflow has no flow node. */
  head: string | null
  /** Trunk node ids in visual order (head first; ends with the router when present; excludes LLMs and model prompts). */
  trunk: string[]
  router: string | null
  /** Last trunk node; null when the trunk is empty. */
  tail: string | null
  /** Reachable branches in server dispatch order (local first, then priority asc, weight desc, id). */
  branches: WorkflowBranch[]
  /** Flow nodes not reachable from head, sorted by id. */
  detached: string[]
  /** Structural issues (WF_* codes, CYCLE_DETECTED is reported by validate). */
  issues: ValidationIssue[]
}
export type PolicySignalCode = 'PROJECT_LOCAL_ONLY' | 'PROJECT_BOUNDARY' | 'PROJECT_DLP'
export interface PolicySignal { code: PolicySignalCode; label: string; /** External LLMs are skipped entirely for this project. */ blocksExternal: boolean }
export type GuardrailPresetId = 'mask-restore' | 'local-sensitive' | 'no-retention' | 'internal-only' | 'block'
export interface GuardrailPreset { id: GuardrailPresetId; label: string; description: string; dataBoundaryPolicy: DataBoundaryPolicy; guardrailAction: GuardrailAction }
export type WorkflowTemplateId = 'basic' | 'local-first' | 'internal-only'
export interface WorkflowTemplate { id: WorkflowTemplateId; label: string; description: string }

/* ---------------- Palette (v2) ---------------- */
export type DesignerMode = 'basic' | 'advanced'
export type ModelAvailability = 'active' | 'degraded' | 'inactive'
export type PaletteGroupId = 'entry' | 'guard' | 'prompt' | 'cache' | 'routing' | 'llm-local' | 'llm-external'
export interface PaletteNodeItem { nodeType: AddableNodeType; label: string; description: string; icon: string; clickDisabledReason: string | null; draggable: boolean; onCanvas: boolean }
export interface PaletteProjectItem { projectId: string; name: string; status: string; onCanvas: boolean; granted: boolean; guarded: boolean }
export interface PaletteModelItem { deploymentId: string; displayName: string; providerModelId: string; sourceName: string; external: boolean; availability: ModelAvailability; tone: HealthTone; reason: string | null; onCanvas: boolean }
export interface PaletteGroup { id: PaletteGroupId; label: string; nodes: PaletteNodeItem[]; projects: PaletteProjectItem[]; models: PaletteModelItem[]; hiddenCount: number }
export interface PaletteOptions { query: string; showInactive: boolean; mode: DesignerMode }

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
/** 'deployment' (alias of 'llm') and 'target' are v1 legacy members kept for v1 components (removed in Phase 3). */
export type AddableNodeType = 'project' | 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router' | 'llm' | 'deployment' | 'target'
export type FlowCommand =
  | { type: 'moveNodes'; moves: Array<{ nodeId: string; x: number; y: number }> }
  | { type: 'addNode'; nodeType: AddableNodeType; refId?: string | null; position?: Point | null; spliceEdgeId?: string | null; autoWire?: boolean }
  | { type: 'removeItems'; nodeIds: string[]; edgeIds: string[]; force?: boolean }
  | { type: 'connect'; from: PortRef; to: PortRef }
  | { type: 'updateProps'; nodeId: string; patch: Record<string, FlowPropValue> }
  | { type: 'replaceModel'; nodeId: string; deploymentId: string }
  | { type: 'setAccess'; projectNodeId: string; granted: boolean }
  | { type: 'normalizeOrder' }
  | { type: 'applyTemplate'; template: WorkflowTemplateId; projectIds: string[]; deploymentIds: string[] }
  | { type: 'toggleCollapse'; nodeIds: string[]; collapsed: boolean }
  | { type: 'autoLayout' }
  /** v1 legacy: always fails in v2 (removed in Phase 3). */
  | { type: 'bindTarget'; targetNodeId: string; deploymentId: string }
export interface CommandResult { graph: FlowGraph; changed: boolean; error: string | null; select: FlowSelection | null }
export type ConnectCheck = { ok: true; replaces: string[] } | { ok: false; reason: string }
export type NodeChangeKind = 'added' | 'modified'
export type FlowToolbarAction = 'undo' | 'redo' | 'fit' | 'auto-layout' | 'zoom-in' | 'zoom-out' | 'zoom-reset' | 'validate' | 'plan' | 'revert' | 'reload' | 'save-layout' | 'toggle-palette' | 'toggle-inspector' | 'mode-basic' | 'mode-advanced' | 'normalize-order'

/* ---------------- Validation ---------------- */
export type IssueSeverity = 'error' | 'warning' | 'info'
/** v2: a one-click fix offered next to the issue. */
export interface IssueFix { label: string; command: FlowCommand }
export interface ValidationIssue { code: string; severity: IssueSeverity; message: string; nodeId: string | null; edgeId: string | null; field: string | null; fix?: IssueFix }

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
export interface CreateTargetBody { deploymentId: string; priority: number; weight: number; degraded: boolean; followModelChanges: boolean; maxConcurrencyOverride?: number; systemPromptTemplate?: string }
export interface PatchTargetBody { priority?: number; weight?: number; degraded?: boolean; enabled?: boolean; maxConcurrencyOverride?: number; followModelChanges?: boolean; systemPromptTemplate?: string; clearSystemPromptTemplate?: true }
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
export interface RebaseInput { graph: FlowGraph; base: ServiceFlowSnapshot; remote: ServiceFlowSnapshot; createdTargetIds?: Record<string, string>; deletedTargetKeys?: string[] }
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
  /** Target key -> created server id. */
  createdTargetIds: Record<string, string>
  /** Target keys whose server row this run deleted (succeeded or already gone). */
  deletedTargetKeys?: string[]
  completed: boolean
  failedOpId: string | null
}
```

### 13.3 WP-A 모듈 공개 시그니처 (B·C가 의존)

```ts
// catalog.ts — 아래 목록이 전체 공개 API다(v1 이름 deploymentNodeId·targetNodeId·projectNodeId·toneOf 등은 유지)
export const GRID_SIZE = 16
export const LIMITS: { maxNodes: 300; maxEdges: 600; maxOperations: 100; promptMaxLength: 16000; displayNameMaxLength: 200; capabilitiesMaxLength: 2000; maxProjectsForAccess: 200 }
export const SERVICE_KEY_PATTERN: RegExp
export const DEFAULT_SERVICE_STATE: ServiceState; export const DEFAULT_SCHEDULING: SchedulingState; export const DEFAULT_CONFIG: FlowConfigState
export const GUARDRAIL_STRICTNESS: Record<GuardrailAction, number>; export const BOUNDARY_RANK: Record<DataBoundaryPolicy, number>
export const SERVICE_FIELDS: ServiceField[]; export const SCHEDULING_FIELDS: SchedulingField[]
export const TARGET_FIELDS: TargetField[]                       // 끝에 'systemPromptTemplate'
export const FIELD_LABELS: Record<string, string>; export function fieldLabel(entity: 'service' | 'scheduling' | 'target', field: string): string
export const SINGLETON_IDS: { start: 'start'; guardrail: 'guardrail'; masker: 'masker'; prompt: 'prompt'; params: 'params'; cache: 'cache'; router: 'router' }
export const PROMPT_BRANCH_RANK = 70
export const NODE_TYPES: Record<NodeType, NodeTypeSpec>        // §5.1–5.4 (+ 레거시 target)
export const CONNECTION_RULES: ConnectionRule[]                 // §6.2
export const GUARDRAIL_PRESETS: GuardrailPreset[]               // §5.8 순서
export const WORKFLOW_TEMPLATES: WorkflowTemplate[]             // §10.4 순서
export function nodeSpec(type: NodeType): NodeTypeSpec
export function portSpec(type: NodeType, portId: string, direction?: PortDirection): PortSpec | undefined
export function projectNodeId(projectId: string): string        // 'project:<id>'
export function policyNodeId(projectId: string): string         // 'policy:<id>'
export function llmNodeId(deploymentId: string): string         // 'deployment:<id>'
export function deploymentNodeId(deploymentId: string): string  // = llmNodeId (v1 이름 유지)
export function branchPromptNodeId(deploymentId: string): string        // 'prompt:<id>'
export function branchPromptDeploymentId(nodeId: string): string | null // 'prompt:<id>' → id, 'prompt' → null
export function targetNodeId(targetId: string): string          // 'target:<id>' (TargetState.key, v1 이름 유지)
export function targetKeyOf(node: FlowNode): string             // §5.2
export function hostNodeIdFor(deployment: DeploymentInfo): string | null
export function edgeId(from: PortRef, to: PortRef): string
export function toneOf(enabled: boolean, healthStatus: string): HealthTone
export function modelAvailability(deployment: DeploymentInfo, catalog: FlowCatalog): { availability: ModelAvailability; reason: string | null } // §9.3
export function projectSignals(project: ProjectInfo | undefined): PolicySignal[]   // §6.9
export function isProtectedGuardrail(base: ServiceFlowSnapshot): boolean           // §6.9
export function presetOf(props: Record<string, FlowPropValue>): GuardrailPresetId | null
export function presenceOf(config: FlowConfigState): { guardrail: boolean; masker: boolean; prompt: boolean; params: boolean; cache: boolean; router: boolean } // §5.5
export function nodePropsFromConfig(config: FlowConfigState): Record<'start' | 'guardrail' | 'masker' | 'prompt' | 'params' | 'cache' | 'router', Record<string, FlowPropValue>>
export function llmPropsFromTarget(target: TargetState): Record<string, FlowPropValue>
export function defaultNodeProps(type: AddableNodeType, graph: FlowGraph, snapshot: ServiceFlowSnapshot, refId: string | null): Record<string, FlowPropValue> // §5.4
export function describeNode(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): NodeView   // §5.7
export function nodeFacts(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): FactRow[]
export function describeEdge(edge: FlowEdge): { label: string; meaning: string }                         // §6.1

// workflow.ts (신규) — catalog·types만 import
export const FLOW_NODE_TYPES: ReadonlySet<NodeType>             // guardrail, prompt, params, cache, router, llm
export function rankOf(node: FlowNode): number
export function isBranchPrompt(node: FlowNode): boolean         // type 'prompt' && id !== 'prompt'
export function analyzeWorkflow(graph: FlowGraph, snapshot: ServiceFlowSnapshot): WorkflowAnalysis      // §6.3
export function isEmptyWorkflow(graph: FlowGraph): boolean      // FLOW_NODE_TYPES 노드가 0개
export function dependentsOf(graph: FlowGraph, nodeId: string): string[]                                 // §5.6 직속
export function dependencyClosure(graph: FlowGraph, nodeIds: string[]): string[]                         // 입력 + 재귀 종속, 정렬
export function insertableTypesForEdge(graph: FlowGraph, edgeId: string, snapshot: ServiceFlowSnapshot): AddableNodeType[] // §6.6, 순서: guardrail, prompt, cache, params, router
export function promptScopeForSplice(graph: FlowGraph, edgeId: string): PromptScope
export function findCycle(graph: FlowGraph): string | null

// layout.ts — v1 export 유지(NODE_WIDTH … MAX_ZOOM, nodeSize, nodeRect, portAnchor, bezierPath, graphBounds, fitViewport,
//             screenToWorld, worldToScreen, zoomAt, clampZoom, snapToGrid, nodesInRect, clientScale, clientToLocal,
//             extractLayout, sanitizeLayout) + 변경·추가:
export const LAYOUT_NODE_ID_PATTERN: RegExp                     // v2 (§7.8)
export const LAYOUT_NODE_ID_PATTERN_V1: RegExp                  // v1 원문
export function layoutGeneration(layout: FlowLayout | null): 1 | 2
export function autoLayout(graph: FlowGraph, snapshot?: ServiceFlowSnapshot): FlowGraph                  // §7.9
export function applyLayout(graph: FlowGraph, layout: FlowLayout | null, snapshot?: ServiceFlowSnapshot): FlowGraph // generation 1 → autoLayout
export function placeNewNode(graph: FlowGraph, nodeId: string, snapshot?: ServiceFlowSnapshot): FlowGraph
export function placeSpliced(graph: FlowGraph, nodeId: string, fromNodeId: string | null, toNodeId: string | null): FlowGraph
export function suggestNodePosition(graph: FlowGraph, nodeType: NodeType | AddableNodeType, center: Point, visible?: Rect | null): Point
export function nearestEdge(graph: FlowGraph, point: Point, radius: number, kinds: EdgeKind[]): string | null

// graphOps.ts
export function sortGraph(graph: FlowGraph): FlowGraph          // nodes: (rankOf, id), edges: id
export function findNode(graph: FlowGraph, nodeId: string): FlowNode | undefined
export function edgesOf(graph: FlowGraph, nodeId: string): FlowEdge[]
export function makeNode(type: NodeType, id: string, refId: string | null, props?: Record<string, FlowPropValue>, targetId?: string | null): FlowNode
export function makeEdge(kind: EdgeKind, from: PortRef, to: PortRef, locked: boolean): FlowEdge
export function createSkeletonGraph(serviceId: string | null): FlowGraph   // start 하나(props = DEFAULT_CONFIG 기준)
export function canConnect(graph: FlowGraph, from: PortRef, to: PortRef, snapshot: ServiceFlowSnapshot): ConnectCheck // §6.4
export function nearestCompatiblePort(graph: FlowGraph, from: PortRef, point: Point, radius: number, snapshot: ServiceFlowSnapshot): PortRef | null
export function syncHostNodes(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowGraph
export function syncDerived(graph: FlowGraph, snapshot: ServiceFlowSnapshot): FlowGraph   // §6.11
export function renameTargetNodes(graph: FlowGraph, createdTargetIds: Record<string, string>): FlowGraph // §7.7
export function detachTargetNodes(graph: FlowGraph, keys: string[]): FlowGraph            // §7.7
export function applyCommand(graph: FlowGraph, command: FlowCommand, snapshot: ServiceFlowSnapshot): CommandResult // §6.10

// compile.ts — 시그니처 v1 그대로: compile, normalizePrompt, canonicalCapabilities, sameValue, sameFieldValue (§7.3)
// decompile.ts — decompile(snapshot, layout), syncGraphToConfig(graph, config, snapshot) (§7.4–7.5)
// validate.ts — validateGraph(graph, base), issueCountsByNode(issues) (§6.12)
// diff.ts — 시그니처 v1 그대로: planChanges, mergeConfigs, rebase, computeNodeChanges, configsEqual, isConfigDirty, stableStringify (§7.6–7.7)
// history.ts — 변경 없음
// palette.ts (신규) — catalog·workflow·types만 import
export function buildPalette(snapshot: ServiceFlowSnapshot, graph: FlowGraph, options: PaletteOptions): PaletteGroup[] // §9
```

### 13.4 B ↔ C 인터페이스 (컴포넌트 props / emits)

| 컴포넌트(소유 B) | props (v1에 더해) | emits | 비고 |
|---|---|---|---|
| `FlowCanvas.vue` | `mode?: DesignerMode`(기본 `'advanced'`) | v1 그대로(`update:viewport`, `update:selection`, `command`, `action`, `open-inspector`) | `defineExpose` v1 그대로. 기본 모드면 endpoint/provider 노드와 host 간선을 그리지 않는다 |
| `FlowPalette.vue` | `mode?: DesignerMode`(기본 `'advanced'`) | `command`, `select-node`(v1) | `buildPalette` 사용 |
| `FlowInspector.vue` | `mode?: DesignerMode`(기본 `'advanced'`) | `command`, `update:selection`, `focus-node`(v1) | 이슈 빠른 수정도 `command`로 |
| `FlowToolbar.vue` | `mode?: DesignerMode`(기본 `'advanced'`), `canNormalize?: boolean`(기본 false) | `action`(새 값 `mode-basic`, `mode-advanced`, `normalize-order` 포함) | |
| `FlowQuickStart.vue`(신규) | `snapshot: ServiceFlowSnapshot`, `graph: FlowGraph`, `readOnly: boolean` | `command: [FlowCommand]`, `dismiss: []` | 페이지가 겹쳐 띄움(`.fd-stage` 안 절대 위치, 캔버스 위) |

페이지(C)는 모든 B 컴포넌트에 `:mode="mode"`를, 툴바에 `:can-normalize="canNormalize"`를 넘긴다. DnD MIME과 페이로드(`{nodeType, refId}`)는 v1 그대로다.

---

### WP-A · 순수 TS 모델 v2 (types · catalog · workflow · layout · graphOps · compile · decompile · validate · diff · palette) + 테스트

**목표**: UI·네트워크와 무관한 결정적 v2 모델 계층. 존재 기반 compile/decompile, 워크플로우 분석, 자동 배선·splice·치유·종속 이동, 정책 기반 가드레일, 팔레트 모델, Target 프롬프트 diff.

**소유 파일**: §13.1 표의 A 행.

**구현 순서와 명세**
1. `types.ts`: §13.2 **문자 그대로**.
2. `catalog.ts`: §13.3 catalog 시그니처. `NODE_TYPES`는 §5.1–5.4(라벨·설명·keywords·카테고리·아이콘·rank·singleton·deletable·paletteAddable·advanced·derived·포트·필드). 레거시 `target` 스펙(포트·필드 없음, paletteAddable false, deletable false, derived true, rank 99). `CONNECTION_RULES`는 §6.2를 펼친 배열. `GUARDRAIL_PRESETS`(§5.8), `WORKFLOW_TEMPLATES`(§10.4 라벨·설명), `modelAvailability`(§9.3), `projectSignals`·`isProtectedGuardrail`(§6.9), `presenceOf`(§5.5), `nodePropsFromConfig`·`llmPropsFromTarget`·`defaultNodeProps`(§5.4), `describeNode`·`nodeFacts`·`describeEdge`(§5.7, §6.1). v1의 `singletonPropsFromConfig`는 `nodePropsFromConfig`로 대체한다(다른 소유 파일이 쓰지 않음을 grep으로 확인).
3. `workflow.ts`: §6.3 알고리즘, §5.6 종속, §6.6 `insertableTypesForEdge`·`promptScopeForSplice`. `analyzeWorkflow`의 이슈 메시지·nodeId·fix는 §6.12 표 그대로. `findCycle`은 v1 `validate.ts:29-59`를 옮긴다.
4. `layout.ts`: §7.8 정규식 2개, `layoutGeneration`, §7.9 `autoLayout`·`placeSpliced`·`placeNewNode`·`nearestEdge`. `extractLayout`은 v2 정규식으로 거르고 `target:new-` 예외는 지운다. `sanitizeLayout`은 v2 정규식을 쓴다. 나머지 v1 기하 함수는 그대로 둔다.
5. `graphOps.ts`: §6.4 `canConnect`, §6.5–6.7 삽입·삭제 내부 도우미, §6.10 `applyCommand`, §6.11 `syncDerived`, §7.7 `renameTargetNodes`·`detachTargetNodes`. 모든 함수는 입력을 바꾸지 않는다. 명령 결과는 `syncDerived` → `sortGraph`를 거친다.
6. `compile.ts`: §7.3. `decompile.ts`: §7.4–7.5. `validate.ts`: §6.12(메시지 그대로). `diff.ts`: §7.6–7.7(분석·병합·실행 순서 로직은 손대지 않고 필드·body·노트·문구·그래프 함수만 바꾼다). `computeNodeChanges`는 §12.1 #24.
7. `palette.ts`: §9.
8. **v1 컴파일 호환(D12)**: 끝나면 `npm run build`를 돌린다. 소유하지 않은 파일(`*.vue`, `useFlowEditor.ts`, `flowSnapshot.ts`, `flowApi.ts`)에서 타입 오류가 나면 **그 파일을 고치지 말고** `types.ts`/`catalog.ts`/`layout.ts`에 가장 좁은 호환 수단(`@deprecated` 멤버, 넓힌 매개변수 타입)을 추가해 해결하고 보고서에 적는다(§13.2의 레거시 멤버가 기본 세트). 설계 시 §13.2 전문으로 v1 소스를 타입 검사해 본 결과, B·C 소유 파일의 실제 오류는 `useFlowEditor.ts:257`의 `suggestNodePosition(…, command.nodeType, …)` 하나였다(§13.3의 `nodeType: NodeType | AddableNodeType` 시그니처가 해결). 같은 실험에서 v1 `catalog.ts`·`graphOps.ts`·`decompile.ts`·`layout.ts`의 오류는 이 WP가 재작성하므로 문제없다.
9. 테스트: §12.1의 28개 케이스를 `flowDesignerModel.test.mjs`에 구현한다(`loadTypeScript('../src/flow-designer/<module>.ts')`, 픽스처는 파일 안).

**수용 기준**
- §12.1 전 케이스 통과, 특히 #3 왕복 불변식과 #4 존재 기반 결정성.
- `flowDesignerAdapter.test.mjs`(C 소유, 수정 금지)가 **그대로** 통과한다. 특히 프로젝트 접근 시나리오(`:406-443`): `addNode project`(자동 허용) → `setAccess` → opId `['grant-access:p2', 'revoke-access:p1']` → rebase 후 `access` 간선이 `project:p2`에서 나가고 `project:p1`에서는 나가지 않는다.
- 생성되는 모든 body 키는 §13.2 Body 타입 키뿐이다. `planChanges`는 같은 입력에 같은 fingerprint.
- 프런트 테스트 체인 전체와 `npm run build` 녹색.

**검증**
```bash
cd /c/project/aiconnect/frontend && node --test tests/flowDesignerModel.test.mjs && node --test tests/flowDesignerAdapter.test.mjs && npm run build
# 그리고 §13.0 전체 체인
```

---

### WP-B · 캔버스 · 노드 카드 · 팔레트 · 인스펙터 · 툴바 · 빠른 시작 (Vue)

**목표**: v2 모델을 쓰는 편집 UI. 간선 ＋ 삽입, 간선 위 놓기, 종속 동반 이동, 그룹 팔레트, 모드별 표시, 보호 해제, 빠른 시작, 용어 교체.

**소유 파일**: §13.1 표의 B 행. **선행**: WP-A 병합.

**명세**
1. `FlowCanvas.vue`
   - prop `mode?: DesignerMode = 'advanced'`. `mode === 'basic'`이면 endpoint/provider 노드와 `host` 간선을 렌더에서 뺀다(그래프·선택·마키 계산에서도 제외, 데이터는 유지).
   - 드래그·화살표 이동 대상: `event.ctrlKey || event.metaKey`면 선택 노드만, 아니면 `dependencyClosure(graph, 선택)`. 마키 선택은 그대로.
   - DnD: `ADDABLE = ['project', 'guardrail', 'masker', 'prompt', 'params', 'cache', 'router', 'llm']`. 놓을 때 `world` 좌표에서 `nearestEdge(graph, world, 16 / zoom, ['access', 'flow', 'route'])`를 찾고, 그 간선에 대해 `insertableTypesForEdge`가 nodeType을 포함하면 `addNode {nodeType, refId, spliceEdgeId}`, 아니면 `addNode {nodeType, refId, position: 스냅 좌표, autoWire: !event.altKey}`.
   - 간선 ＋ 버튼·삽입 메뉴: §10.6. 포인터를 올린 간선 id(로컬 상태)와 선택된 간선 1개에 대해 `data-edge-insert` 그룹을 그린다. `onPointerDown`의 제외 목록(`[data-edge-delete]`, `FlowCanvas.vue:312`)에 `[data-edge-insert]`와 메뉴 요소를 추가한다. 메뉴 항목은 `insertableTypesForEdge` 순서, 프롬프트 라벨은 `promptScopeForSplice`.
   - 간선 스타일(토큰만): entry `--info` 1.5px, policy `--warning` 점선, access `--info` 점선, flow `--accent-strong` 2px, route `--warning` 2px(대상 LLM 사용 중지면 opacity .4 — v1 `binding` dim 규칙을 `route`로 옮김), masker `--warning` 점선, host `--faint` 점선. 잠긴 간선은 × 버튼 없음(v1).
   - 용어: §4.2의 B 행. 알림에 §10.5 문구 추가(addNode splice·라우터 자동 추가 감지는 명령 전후 노드 집합 비교로 충분).
   - 보존: v1 상호작용 상태 기계, `clientToLocal`(CSS zoom), `isZooming`·`will-change`·`contain` 규칙, `onPointerUp`의 `didMove` 스냅샷 패턴.
2. `FlowNodeCard.vue`: `view.protected`면 '보호' 칩, `view.locked`면 '고정'(start)·'정책'(policy)·'자동'(호스트) 칩, `view.promptScope`면 '공통'/'모델별' 칩. `data-category`가 v2 값(§5.1 색). 치수는 `layout.ts` 상수로 v1과 같이 고정.
3. `FlowPalette.vue`: §9. `buildPalette(snapshot, graph, {query, showInactive, mode})` 결과만 그린다(정렬·숨김 로직을 컴포넌트에 두지 않음). 그룹 헤더는 `<button aria-expanded>`로 접기(메모리 상태), 검색 중 강제 펼침. 그룹마다 "중지·미로드 {n}개 숨김 · 표시" 버튼(전역 `showInactive` 토글). 항목 클릭: `onCanvas`면 `select-node`(프로젝트·모델은 노드 id, 노드 항목은 싱글턴 id), 아니면 `clickDisabledReason`이 null일 때 `command addNode`. `draggable` 항목만 `dragstart`.
4. `FlowInspector.vue`: §10.7. 필드 필터(`advanced`/`basicOnly` × mode), `guardrail-preset`(GUARDRAIL_PRESETS, `presetOf`), `model-replace`(→ `replaceModel`), `access-toggle`(→ `setAccess`), 보호 해제 버튼(확인 후 `removeItems {force: true}`), 일반 삭제는 `spec.deletable && !view.locked && !view.protected`, 상속 정책 안내·링크, 이슈 `fix` 버튼, 간선 패널(삽입 버튼 목록 = `insertableTypesForEdge`, 삭제), LLM [모델별 프롬프트 추가](들어오는 흐름 간선 id로 `addNode prompt splice`). `bindTarget`·`deployment-binding` 처리 제거.
5. `FlowToolbar.vue`: prop `mode`, `canNormalize`. [기본 | 고급] 분할 버튼(`aria-pressed`) → `mode-basic`/`mode-advanced`. `canNormalize`면 [순서 정리] → `normalize-order`. aria-label·title 용어(§4.2). 줌 버튼·`fire('zoom-*')`는 그대로.
6. `FlowQuickStart.vue`(신규): §10.4. 템플릿 카드 3개 + [직접 구성]. 선택 후 단계: 프로젝트 체크박스(`snapshot.catalog.projects`, `projectsHidden`이면 안내만), LLM 선택(`buildPalette(...)`의 llm 그룹 중 `availability !== 'inactive'`, 템플릿 조건 안내), 초안이면 호출 이름·표시 이름 입력(`SERVICE_KEY_PATTERN`로 즉시 안내). [만들기] → `command applyTemplate`(+ 초안이면 `command updateProps start`). `readOnly`면 버튼 비활성.
7. 테스트: §12.2를 `frontend/tests/flowDesignerUi.test.mjs`에 쓰고 `package.json`의 `test:flow-designer`에 파일을 추가한다.

**수용 기준**
- §12.2 통과, 기존 adapter 테스트의 베이스라인 검사(`:392-405`, `:458-480`) 통과.
- C가 아직 병합되지 않은 상태(v1 페이지가 새 prop을 넘기지 않음)에서도 `npm run build` 녹색(모든 새 prop 선택).
- 기본 모드에서 호스트 노드가 숨고, 고급 모드에서 보인다. 드래그로 종속 노드가 함께 움직이고 Ctrl/⌘면 단독이다. 간선 ＋로 가드레일·캐시·모델별 프롬프트를 넣을 수 있다.
- 프런트 테스트 체인 전체와 `npm run build` 녹색.

**검증**
```bash
cd /c/project/aiconnect/frontend && npm run test:flow-designer && npm run build
# 그리고 §13.0 전체 체인. 가능하면 npm run dev로 §12.5의 3·4·8번을 눈으로 확인
```

---

### WP-C · 페이지 통합 · 데이터 어댑터 · 용어 · 내비게이션 · Plan 모달 · 문서

**목표**: v2 스냅샷 데이터(프로젝트 정책, 조직 DLP, Target 프롬프트, 기능 감지), 모드·빠른 시작·순서 정리 연결, 레이아웃 호환, 용어 교체.

**소유 파일**: §13.1 표의 C 행. **선행**: WP-A 병합(B와 병렬).

**명세**
1. `flowSnapshot.ts`
   - `RawFlowData`에 `orgDataProtection: unknown | null`(조직 DLP 응답 원문), `projectsHidden?: boolean` 추가.
   - `mapTarget`: `systemPromptTemplate`(§8.5).
   - 프로젝트: `externalAiBlocked: bool(row.externalAiBlocked, false)`, `maxDataBoundaryPolicy`(BOUNDARY 값이면), `dlp`: `orgDataProtection.policies` 중 `scopeType === 'PROJECT' && scopeId === project.id`인 행의 `{mode: str(mode), externalAction: oneOf(externalAction, GUARDRAIL, 'ALLOW')}`(없으면 null). **다른 필드(customPatternsJson, detect*)는 복사하지 않는다.**
   - 스냅샷: `orgDataProtection = effective ? {mode, externalAction} : null`, `projectPoliciesComplete = raw.orgDataProtection !== null`, `projectsHidden = raw.projectsHidden === true`(true면 `accessComplete = false`), `features = {targetPrompt: Target 행이 0개이거나 어떤 행이든 'systemPromptTemplate' 키를 가짐}`(초안은 true).
   - `config.service.temperatureValue`는 정책이 FIXED가 아니면 null로 정규화.
2. `flowApi.ts`
   - `loadFlowSnapshot`의 1단계 읽기에 `read('/api/admin/organizations/${org}/data-protection')` 추가(실패는 `unavailable`에 기록, 치명적 아님).
   - `projectsHidden = currentUserId() === 'platform-token'`을 `RawFlowData`에 넣는다.
   - `saveFlowLayout`: 400 + code `FLOW_LAYOUT_INVALID`면 `LAYOUT_NODE_ID_PATTERN_V1`에 맞는 키만 남겨 한 번 재시도(§7.8). `target:new-` 필터는 지워도 된다(v2 ID에는 없음).
3. `useFlowEditor.ts`
   - 모드: 모듈 변수 `let lastMode: DesignerMode = 'basic'`로 초기화하는 `mode` ref. 툴바 `mode-basic`/`mode-advanced`에서 바꾸고 `lastMode`도 갱신. 반환값에 `mode` 추가.
   - `canNormalize = computed(() => issues.value.some(i => i.code === 'WF_ORDER_NONCANONICAL'))`, 툴바 `normalize-order` → `handleCommand({type: 'normalizeOrder'})`.
   - 빠른 시작: `quickStartDismissed` ref(서비스 전환 시 false로), `showQuickStart = computed(() => !!graph.value && isEmptyWorkflow(graph.value) && !quickStartDismissed.value && !readOnly.value)`, `dismissQuickStart()`.
   - 레이아웃 세대: 로드 시 `layoutGeneration(layoutResult.layout) === 1`이면 `message`에 §7.8 안내, `savedFingerprint = ''`(배치 미저장 표시), `fitSoon()`.
   - 초안 복원: `draft.graph.schemaVersion !== 2`면 초안을 버리고 서버에서 읽는다.
   - 적용 후 Target 프롬프트 미지원 감지(§8.6): 성공한 createTarget/patchTarget 중 body에 `systemPromptTemplate`이 있었던 작업의 Target(created면 `report.createdTargetIds[op.key]`)이 재조회 결과에서 프롬프트가 null이면 `base.value = {...synced, features: {targetPrompt: false}}`로 두고 메시지 '서버가 모델별 시스템 프롬프트를 저장하지 않았습니다(백엔드 업데이트 필요).'.
   - 툴바 `addNode`의 위치 제안은 v1처럼 `suggestNodePosition`을 쓰되 **`spliceEdgeId`가 있거나 `autoWire !== false`인 명령에는 위치를 넣지 않는다**(모델이 배치). 연결 없는 추가(`autoWire: false`)만 제안 위치를 넣는다.
   - 용어(§4.2의 C 행).
4. `FlowDesignerPage.vue`: 용어(§4.2), 선택기 라벨, 상태바에 '모드 기본/고급', 모든 B 컴포넌트에 `:mode`, 툴바에 `:can-normalize`, `.fd-stage` 안에 `<FlowQuickStart v-if="showQuickStart" … @command="editor.handleCommand" @dismiss="editor.dismissQuickStart" />`(절대 위치 오버레이, 캔버스 위 가운데). `.fd-stage` grid와 높이 규칙은 그대로 둔다.
5. `FlowPlanModal.vue`: 문구 '남은 변경은 워크플로우에 그대로 유지됩니다.' 등 §4.2. 위험 체크박스 설명에 '외부 노출 방지 정책 완화'를 포함('서비스 중단, 외부 노출 방지 정책 완화, 삭제 또는 접근 해제가 포함되어 있습니다.').
6. `ConsoleShell.vue`: 61행 label·description·keywords만 §4.2대로. 다른 줄은 건드리지 않는다.
7. `execute.ts`: 변경 불필요(확인만). `draftStore.ts`: 변경 불필요.
8. 문서: `docs/flow-designer-design.ko.md` 맨 위 상태 줄 아래에 `> v2 개편: [docs/workflow-designer-v2-design.ko.md](workflow-designer-v2-design.ko.md) — 이 문서의 노드 카탈로그·연결 규칙·compile 규칙은 v2로 대체되었다.` 한 줄. (선택) Phase 3 완료 후 인계 문서 신규 작성.
9. 테스트: §12.3을 `flowDesignerAdapter.test.mjs`에 추가(기존 테스트 유지, 기대값을 바꾸지 말 것).
10. **Phase 3 통합 검증(C 담당)**: A·B·D 병합 후 §13.0 전체 체인, `npm run build`, 백엔드 전체 `gradle test`, §12.5 브라우저 체크리스트. 발견한 버그는 소유 WP에 되돌려 보낸다.

**수용 기준**
- §12.3 통과. 프로젝트 접근 시나리오(`:406-443`) 기대값 무변경 통과.
- WP-D 없이도: 조직 DLP 읽기가 실패해도 로드되고(`POLICY_UNVERIFIED`), Target 행에 프롬프트 키가 없으면 모델별 프롬프트가 막히며, 레이아웃 저장이 v1 키로 재시도된다.
- ConsoleShell 정규식 테스트(`flowDesignerAdapter.test.mjs:347-361`, `betaOperator.test.mjs:346`) 통과.
- 프런트 테스트 체인 전체와 `npm run build` 녹색.

**검증**
```bash
cd /c/project/aiconnect/frontend && node --test tests/flowDesignerAdapter.test.mjs && npm run test:beta-operator && npm run test:project-governance && npm run build
# 그리고 §13.0 전체 체인
```

---

### WP-D · 백엔드: Target 단위 시스템 프롬프트(V54) + 레이아웃 키 정규식 + 테스트

**목표**: 모델별 시스템 프롬프트 저장·적용·캐시 무효화와 v2 레이아웃 키 허용. 모두 추가만.

**소유 파일**: §13.1 표의 D 행. **선행**: 없음(WP-A와 병렬).

**명세**: §8.2 전부.
1. `V54__service_target_system_prompt.sql`(§8.2 DDL 한 줄).
2. `ServiceTarget`: 필드·getter·`configureSystemPromptTemplate`(§8.2 코드).
3. `LlmService`: `bumpPromptVersion()`, `applySystemPromptTemplate(ObjectNode, String)`. 1인자 버전은 2인자에 null을 넘기되 **null·공백 target이면 기존 본문과 동일한 결과**(레거시 공백 포함 값도 그대로).
4. `UpdateTarget`, `TargetPolicyView`(`@JsonInclude(JsonInclude.Include.ALWAYS)`), `CreateTarget`: §8.2. 기존 생성자(5·6인자) 유지 — `ExternalModelDeletionIntegrationTest`, `ExternalProviderScenarioIntegrationTest`, `QuotaAndProviderCapHaIntegrationTest` 등이 5인자 `CreateTarget`을 쓴다.
5. `RoutingPolicyService.configureTarget`/`deleteTarget`, `ControlPlaneService.addTarget`: 변경 시 서비스 `bumpPromptVersion()` + 저장(같은 트랜잭션).
6. 게이트웨이 2곳(§8.2 코드). `ChatCompletionGateway`에서 `request`가 `:167` 이후 변경되지 않는지 확인하고, 변경된다면 `:167` 직전에 원본 사본을 보관한다.
7. `ServiceFlowLayoutService` 정규식(§7.8).
8. `docs/openapi.yaml`(§8.2).
9. 테스트: §12.4.

**수용 기준**
- §12.4 신규 테스트 통과. 기존 `RoutingPolicyManagementIntegrationTest`, `GatewayFailoverIntegrationTest`, `GatewayStreaming*IntegrationTest`, `ServiceFlowLayoutIntegrationTest`, `OpenApiContractTest`, `DatabaseSchemaIntegrationTest`, `MigrationVersionOrderingTest`, `DataProtectionRedactorTest`(프롬프트 버전·캐시 키 테스트 `:223-240`) 통과. 마지막에 전체 `gradle test` 통과(실패 0).
- Target 프롬프트가 없는 서비스의 업스트림 요청 본문은 V54 이전과 바이트 단위로 같다(테스트로 확인).
- GET targets 응답에 `systemPromptTemplate` 키가 항상 있다.

**검증**
```bash
cd /c/project/aiconnect && JAVA_HOME="C:/Users/kjs99/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.20.1+1" \
  "C:/Users/kjs99/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2/bin/gradle" \
  test --tests 'com.aiconnect.llmgateway.ServiceTargetSystemPromptIntegrationTest' \
       --tests 'com.aiconnect.llmgateway.LlmServicePromptCompositionTest' \
       --tests 'com.aiconnect.llmgateway.ServiceFlowLayoutIntegrationTest' \
       --tests 'com.aiconnect.llmgateway.RoutingPolicyManagementIntegrationTest' \
       --tests 'com.aiconnect.llmgateway.GatewayFailoverIntegrationTest' \
       --tests 'com.aiconnect.llmgateway.GatewayStreaming*' \
       --tests 'com.aiconnect.llmgateway.OpenApiContractTest' \
       --tests 'com.aiconnect.llmgateway.DatabaseSchemaIntegrationTest' \
       --tests 'com.aiconnect.llmgateway.MigrationVersionOrderingTest' \
       --tests 'com.aiconnect.llmgateway.dataprotection.DataProtectionRedactorTest' --no-daemon
# 이어서 같은 JAVA_HOME/gradle 경로로: test --no-daemon (전체)
```

---

## 14. 리스크 · 후속 과제 · 미검증 사항

### 14.1 리스크와 대응

| 리스크 | 영향 | 대응 |
|---|---|---|
| 자동 배선이 사용자의 의도와 다른 위치에 노드를 넣음 | 혼란 | 정규 위치(서버 순서)만 쓰고 알림으로 설명. Alt 놓기로 끄기, ＋ 삽입으로 정확한 위치 지정, 되돌리기 |
| 존재 기반 compile 때문에 노드 삭제가 곧 설정 해제 | 의도치 않은 완화 | 보호 잠금(E1), 분리 노드 오류(E6), Plan의 danger 확인. 효과 없는 노드는 정보로 안내 |
| v1 레이아웃을 한 번 자동 정렬로 대체 | 수동 배치 유실(1회) | 안내 메시지 + 배치 저장 유도. 구조가 바뀌어 좌표 재사용이 의미 없음(D11) |
| Target 프롬프트 변경이 서비스 `promptVersion`을 올림 | ServicesPage 'Prompt v' 의미 확장 | Plan 노트로 안내. 캐시 무효화 정확성이 더 중요 |
| 프런트·백엔드 버전 불일치 | 모델별 프롬프트 유실 | `features.targetPrompt` 감지 + 적용 후 보조 감지(§8.6), 레이아웃 v1 키 재시도(§7.8) |
| 큰 조직(모델 수백 개) 팔레트 | 스크롤·렌더 비용 | 기본 숨김·검색·접기. 가상 스크롤은 후속 |
| Phase 1 동안 v1 UI의 실제 동작 | 개발 브랜치에서만 깨짐 | 빌드만 보장(D12). Phase 2 병합 전에는 배포하지 않음 |
| Plan 확인과 실행 사이 경합 | v1과 같음 | v1 §7.10, 낙관적 잠금은 후속 |

### 14.2 후속 과제

캔버스에서 프로젝트 보호 정책(외부 AI 전송 금지·경계) 편집, API 키 노드(읽기 전용), Target 단위 temperature·reasoning, ServicesPage에 모델별 프롬프트 표시, 서버 원자 적용 API와 낙관적 잠금, 가드레일 시뮬레이터 노드(`pipeline-preview`), 조직 전체 워크플로우 보기, 공통 ↔ 모델별 프롬프트 전환 명령, 팔레트 가상 스크롤, 복사·붙여넣기, 미니맵.

### 14.3 이 문서 작성 시 직접 확인하지 못한 사항 (구현자가 확인)

1. `TargetPolicyView` 레코드 컴포넌트의 `@JsonInclude(ALWAYS)`가 전역 `non_null`을 이기는지 — WP-D 테스트 §12.4 (4)로 확인한다. 안 되면 getter 수준 주석이나 `@JsonProperty` + 커스텀 직렬화로 같은 결과를 낸다.
2. `ChatCompletionGateway.completePrepared`의 `request`가 `:167` 이후 변경되지 않는지(§8.2).
3. 조직 관리자 세션에서 `GET /api/admin/organizations/{org}/data-protection`이 허용되는지 — DataProtectionPage가 같은 호출을 하므로 허용될 것으로 보지만 실패해도 비치명(§11-2).
4. `APPROVED_EXTERNAL_NO_RETENTION`에서 외부 Provider의 `zeroRetentionVerified`가 라우팅에 어떻게 쓰이는지 — v2는 v1 경고 규칙(경계 ≠ 외부 허용이면 외부 LLM 경고)을 그대로 쓰므로 정확한 판정은 후속.
5. `localeCompare(…, 'ko')`의 정렬 결과가 Node 테스트 환경과 브라우저에서 같은지 — 테스트 픽스처 이름을 ASCII·한글 혼합으로 두되 기대값은 Node 결과로 고정.
6. 스트리밍 경로가 응답 캐시를 쓰지 않는지(`StreamingChatCompletionGateway`에 `responseCache` 사용 흔적 없음) — 캐시 영향 분석은 비스트리밍 기준.

