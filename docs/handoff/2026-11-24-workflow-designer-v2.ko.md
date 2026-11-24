# 워크플로우 디자이너 v2 — WP-C(페이지 통합·어댑터) 인계

> 설계: `docs/workflow-designer-v2-design.ko.md`. 이 문서는 WP-C가 바꾼 범위와 확인할 점만 적는다. 브랜치 `claude/workflow-redesign`, 커밋하지 않은 상태.

## 바뀐 파일

| 파일 | 내용 |
|---|---|
| `frontend/src/flow-designer/flowSnapshot.ts` | Target `systemPromptTemplate` 매핑, 프로젝트 `externalAiBlocked`·`maxDataBoundaryPolicy`·`dlp`(PROJECT 범위 행의 `mode`/`externalAction`만), 조직 `effective`의 두 필드, `projectPoliciesComplete`, `projectsHidden`(→ `accessComplete=false`), `features.targetPrompt`, FIXED가 아니면 `temperatureValue=null` |
| `frontend/src/flow-designer/flowApi.ts` | 1단계 읽기에 `GET /api/admin/organizations/{org}/data-protection` 추가(실패는 비치명), `projectsHidden = currentUserId() === 'platform-token'`, 레이아웃 저장이 400 `FLOW_LAYOUT_INVALID`면 v1 키만으로 1회 재시도 |
| `frontend/src/flow-designer/useFlowEditor.ts` | 모드(`lastMode` 모듈 변수), `canNormalize`, 빠른 시작(`showQuickStart`/`dismissQuickStart`), v1 형태 레이아웃 안내와 미저장 표시, `schemaVersion !== 2` 초안 폐기, 적용 후 Target 프롬프트 미지원 감지, 자동 배선 알림, 툴바 `mode-*`/`normalize-order`, 용어 교체 |
| `frontend/src/flow-designer/FlowDesignerPage.vue` | 용어, `:mode`/`:can-normalize` 전달, `FlowQuickStart` 오버레이, 상태바 모드. `.fd-stage` grid와 `100dvh / zoom` 높이는 그대로 |
| `frontend/src/flow-designer/FlowPlanModal.vue` | 문구 2곳 |
| `frontend/src/ConsoleShell.vue` | 61행 nav 항목만 |
| `frontend/tests/flowDesignerAdapter.test.mjs` | v2 어댑터 테스트 9건 추가(기존 기대값 무변경) |
| `docs/flow-designer-design.ko.md` | 머리에 v2 포인터 1줄 |

`execute.ts`, `draftStore.ts`는 변경하지 않았다(body 그대로 전달, 초안은 호출자가 `schemaVersion`을 검사).

## 해석·주의

- 알림(`message`): `CommandResult`에 메시지가 없으므로 명령 전후 노드 집합을 비교한다. 라우터가 새로 생기고 명령이 라우터 추가가 아니면 'LLM이 두 개가 되어 라우터를 자동으로 추가했습니다.', 간선 삽입은 '노드를 간선 사이에 넣었습니다.', 트렁크 노드 자동 배선은 `{라벨} 노드를 처리 순서에 맞는 위치에 넣었습니다.`. 명령 오류가 있으면 오류가 우선한다.
- 위치 제안(`suggestNodePosition`)은 `autoWire === false`이고 `spliceEdgeId`·`position`이 없는 `addNode`에만 넣는다.
- 레이아웃 세대 1이면 `savedFingerprint = ''`로 두어 '배치 미저장'이 켜지고 안내 메시지를 띄운다. 저장된 뷰포트는 쓰지 않고 `fitSoon()`한다.
- 조직 DLP 응답에서는 `effective.{mode, externalAction}`과 PROJECT 범위 행의 `{mode, externalAction}`만 읽는다. 사용자 지정 패턴·탐지 설정은 스냅샷에 들어가지 않는다(테스트로 고정).
- 모델별 프롬프트 미지원 감지(§8.6): 성공한 createTarget/patchTarget이 프롬프트를 보냈는데 재조회 Target의 값이 null이면 `features.targetPrompt=false`로 두고 메시지를 띄운다. 재조회한 행에 키 자체가 없을 때는 `buildSnapshot`이 이미 false로 만든다.
