# 워크플로우 디자이너 · 연결 중심 배치 설계서

> 상태: 설계 확정(구현 전) · 워크트리 `C:\project\aiconnect-connection-first` · 브랜치 `claude/connection-first-layout` · 기준 커밋 `12d3730` · 작성 2026-11-26
> 범위: **프런트엔드만**. 백엔드·API·DB·레이아웃 저장 형식은 바꾸지 않는다. 기존 동작은 지우지 않는다(추가 원칙).
> 선행 문서: v2 설계서 `docs/workflow-designer-v2-design.ko.md`(이하 "v2 §n"), v1 `docs/flow-designer-design.ko.md`.
> 표기: 설명은 한국어, 식별자·경로·코드는 영어. `파일:줄`은 기준 커밋에서 직접 확인한 위치다.
> 구현 담당: Sonnet(medium). **§7 작업 지시서를 WP-1부터 순서대로, 코드 블록은 문자 그대로** 옮긴다. 설계와 코드가 모순이면 멈추고 보고한다.
> 사전 검증: 설계 단계에서 이 문서의 코드 블록과 편집 지시를 **저장소 밖 스크래치 사본**에 그대로 적용해 확인했다 — `vue-tsc --noEmit` 무오류, 기존 flow-designer 테스트 76개 + §7.8 신규 19개 통과, `vite build` 성공, 브라우저 시제품에서 렌더·보기 전환·그룹 끌기·모델 추가·연결 삭제·배선 거부/복구 동작 확인. 저장소에는 이 문서만 추가했다.

---

## 0. 결정 요약

| # | 결정 | 근거 |
|---|---|---|
| D1 | **연결 노드 = 기존 파생 host 노드**(`endpoint:<id>` / `provider:<id>`)를 그대로 쓴다. 새 `NodeType`을 만들지 않는다. | host 노드 id가 `ConnectionMeta.id`와 같다(`catalog.ts:385-388` `hostNodeIdFor`, `palette.ts:104-115` `connectionOf`). `syncHostNodes`(`graphOps.ts:245-277`)가 이미 모든 명령 뒤에 만들고 지운다. 레이아웃 키 정규식에 이미 들어 있다(`layout.ts:31`, `ServiceFlowLayoutService.java:31-32`). 기존 테스트가 노드 타입 수 12를 고정한다(`flowDesignerModel.test.mjs:136`). |
| D2 | 연결 중심은 같은 도메인 그래프의 **보기 투영(projection)** 이다. `compile`의 입력(LLM 노드·props·간선 종류)은 보기와 무관하다. | compile 결과가 두 보기에서 같음을 구조적으로 보장(§3.4). Plan·Apply·diff·rebase·초안·실행 취소가 그대로 동작한다. |
| D3 | 모델 없는 연결은 host 노드의 `props.pinned === true`로 그래프에 남긴다. 검증 경고 `CONNECTION_EMPTY`(비차단, 빠른 수정 '연결 삭제'). | 팔레트에서 연결을 먼저 놓고 나중에 모델을 고르는 흐름. 서버 설정에는 아무 영향이 없으므로 오류로 막을 이유가 없다. |
| D4 | 연결 보기의 **연결·모델·모델별 프롬프트 표시 위치는 저장 좌표에서 결정적으로 유도**한다(§5.3). 보기 전환은 그래프를 바꾸지 않는다. 새 레이아웃 키·새 저장 필드 없음. | 레이아웃 API는 키 정규식 밖의 키를 400으로 거부하고 모르는 최상위 필드는 버린다(`ServiceFlowLayoutDtos.java:14`). 그래서 보기 모드별 좌표를 따로 저장할 수 없다. 모델(LLM) 저장 좌표 하나로 두 보기를 모두 그린다. |
| D5 | 연결 보기에서 연결 노드(또는 그 연결의 아무 모델)를 끌면 **그룹 전체**(host + 모델 + 모델별 프롬프트)를 같은 delta로 `moveNodes` 한다. | "연결을 끌면 모델이 따라온다". 모델 위치가 유도값이므로 모델 단독 배치는 의미가 없다. |
| D6 | 간선 투영: 라우터→연결은 **장식선**(선택 불가), 연결→모델은 **실제 route/flow/access 간선을 다시 그린 것**(id 그대로 → 선택·삭제·＋ 삽입 그대로), host(실행 위치) 간선은 숨김, 요청 간선이 없는 모델은 연결→모델 **점선 장식선**. | 상호작용 대상은 언제나 도메인 간선 id다. 가상 간선 ↔ 도메인 간선 매핑이 필요 없다. |
| D7 | 새 명령 3개 `addConnection` · `addConnectionModels` · `connectConnection`, 기존 명령 확장 2개 `removeItems.withConnectionModels` · `autoLayout.view`. 새 명령은 모두 기존 `addNode llm` / `connect` 경로로 환원된다. | LLM props 기본값·자동 배선·라우터 자동 삽입이 모델 중심과 **같은 코드**를 지난다(§3.4). |
| D8 | 연결 삭제 = 그 연결의 모델(+모델별 프롬프트)도 함께 삭제. 확인 창은 띄우지 않는다(실행 취소·변경 검토가 보호). 인스펙터 버튼 라벨과 알림에 개수를 적는다. | 모델은 배포(deployment)가 연결을 고정하므로 연결 밖에 존재할 수 없다(남기면 sync가 host를 다시 만든다). |
| D9 | 모델을 다른 연결로 옮길 수 없다. 연결 출력 → 다른 연결의 모델 간선은 명확한 메시지로 거부, 연결 보기 인스펙터의 '모델 교체'는 같은 연결의 모델만 보인다. | 연결은 모델 배포의 속성이다. |
| D10 | '모델 추가' 목록은 **인스펙터의 연결 패널**(신규 `FlowConnectionModels.vue`)에 둔다. 연결 카드의 [＋ 모델 추가] 버튼은 그 노드를 선택하고 인스펙터를 연다. 캔버스 위 팝오버는 만들지 않는다. | 접근성(키보드)·좁은 화면 서랍과 같은 경로, 캔버스 포인터 상태 기계 변경 최소화. 팝오버는 후속 과제(§9). |
| D11 | 보기 상태 `viewMode: 'connection' \| 'model'`은 **모듈 메모리**에만 둔다(브라우저 저장소 금지, v2 `lastMode`와 같은 방식). 기본값 `'connection'`. 기본/고급 모드와 직교한다. | 저장소 금지 정책(`flowDesignerAdapter.test.mjs` 정적 검사). |
| D12 | 빠른 시작(템플릿)은 바꾸지 않는다. 결과 그래프는 투영으로 자동 그룹 표시된다. 빈 워크플로우에 연결을 놓으면 빠른 시작 오버레이가 닫힌다. | applyTemplate은 보기와 무관한 도메인 명령이다. |

---

## 1. 목표 · 비목표 · 사용자 시나리오

### 1.1 목표

1. **연결 중심 배치(기본 보기)**: `라우터 → [연결: bc250] → 모델 A, 모델 B`, `라우터 → [연결: GOOGLE Gemini] → gemini-…` 처럼 모델을 연결 뒤에 묶어 그린다. 같은 연결의 모델이 흩어지거나 점선이 교차하지 않는다.
2. 팔레트에서 **연결을 먼저 놓고**, 연결 노드에서 그 연결의 모델만 골라 붙인다(중지·미로드는 기본 숨김, 팔레트와 같은 규칙).
3. 연결 노드를 끌면 모델이 함께 움직인다.
4. 연결 상태(온라인·주의·오프라인·비활성)를 연결 노드에 **한 번** 보여 주고, 오프라인·비활성 연결의 모델은 흐리게 그린다.
5. 우선순위·가중치는 모델 노드에 그대로 둔다(서비스 Target = 모델 1개). 연결 간 Failover 순서는 바뀌지 않는다.
6. 툴바 토글 **[연결 중심 | 모델 중심]**, 기본 연결 중심. 모델 중심은 지금 화면 그대로다.
7. compile/Plan/Apply 결과는 두 보기에서 **완전히 같다**(§3.4 증명, C9 골든 테스트).

### 1.2 비목표

- 백엔드·API·DB·Flyway·레이아웃 저장 형식 변경.
- 연결 노드를 접어 모델을 숨기는 그룹 접기, 연결 사이 모델 드래그 재배치, 그룹 안 모델 순서의 수동 지정(순서는 서버 디스패치 순서로 고정, §5.3).
- 캔버스 위 '모델 추가' 팝오버(D10, 후속).
- Plan 모달의 기존 문구 "LLM 연결(Target)" 용어 정리(테스트가 문구를 고정, §9 위험 R6).

### 1.3 사용자 표현과 다르게 해석한 부분

1. **"연결 노드가 모델 추가 목록을 제공"** → 목록은 인스펙터의 연결 패널에 있고, 노드 카드의 [＋ 모델 추가] 버튼이 그 패널을 연다(D10). 넓은 화면에서는 인스펙터가 항상 보이므로 놓자마자 고를 수 있다.
2. **연결 노드는 새 노드가 아니다**: 모델 중심 보기의 점선 '실행 위치' 노드와 **같은 노드**다(D1). 연결 중심에서는 모델 앞(왼쪽)에, 모델 중심에서는 모델 뒤(오른쪽)에 그려진다. 그래서 "둘을 동시에 보이지 않는다"가 자동으로 성립한다.
3. **모델을 하나만 끌 수 없다**(연결 보기): 모델을 끌면 그 연결 그룹 전체가 움직인다(D5). 그룹 안 순서는 우선순위·가중치 순서다.
4. **빈 연결은 저장되지 않는다**: 서버에 대응 개념이 없으므로 새로고침하면 사라진다(변경 검토에 0건). 초안·실행 취소·적용 후 rebase에서는 유지된다.
5. **연결 삭제에 확인 창이 없다**(D8). 실행 취소(Ctrl+Z)와 변경 검토의 '삭제' 위험 표시가 보호한다.

### 1.4 시나리오 — 초보(기본 모드)

1. 새 워크플로우 초안을 열면 빠른 시작이 뜬다. **(a)** 템플릿 '기본'에서 bc250의 모델 2개를 고르고 [만들기] → 캔버스에 `시작 → 프로젝트 → 라우터 → [bc250] → 모델 A, 모델 B`가 그려진다(D12). **(b)** [직접 구성]을 누르고 팔레트 '로컬 LLM'의 연결 머리글 **bc250**을 캔버스로 끌어 놓는다(또는 머리글 옆 [＋]).
2. 연결 노드 bc250이 선택되고 인스펙터에 '모델 추가 · bc250' 체크 목록이 나온다(활성·주의 모델만, 아래 "중지·미로드 N개 표시"). 모델 A·B를 체크하고 [선택한 모델 추가 (2)].
3. 모델이 bc250 뒤에 붙고 라우터가 자동으로 들어간다(알림). 모델 B를 클릭해 우선순위를 3으로 바꾼다.
4. 외부 연결 GOOGLE Gemini도 같은 방법으로 붙인다. [변경 검토 (n)] → [적용].

### 1.5 시나리오 — 고급(고급 모드)

1. 배치가 저장된 기존 서비스를 열면 연결 중심으로 그려진다(저장 좌표에서 유도, 배치 미저장 표시 없음).
2. Gemini 그룹을 끌어 아래로 옮긴다 → 모델 저장 좌표가 함께 바뀌고 [배치 저장]이 켜진다.
3. 연결→모델 실선을 선택해 삭제하면 그 모델은 점선 + 'LLM 미연결' 오류가 된다. bc250의 출력 포트에서 그 모델로 다시 끌면 라우터에서 다시 이어진다. Gemini 출력에서 bc250 모델로 끌면 "이 모델은 'bc250' 연결에 속합니다…"로 거부된다.
4. 연결→모델 실선의 ＋로 모델별 시스템 프롬프트를 넣는다(연결과 모델 사이 칸에 그려진다).
5. [모델 중심]으로 바꾸면 예전 화면(실행 위치 점선 노드)이 그대로 나온다. 다시 [연결 중심].
6. [자동 정렬]은 연결 중심에서는 연결별로 모델을 모아 정렬한다(§5.4).

---

## 2. 데이터 모델

### 2.1 연결 식별 (D1)

| 대상 | 값 | 출처 |
|---|---|---|
| 연결 id(그래프·레이아웃) | `hostNodeIdFor(deployment)` = `endpoint:<runtimeEndpointId>` 또는 `provider:<externalProviderId>`, 둘 다 없으면 `null` | `catalog.ts:385-388` |
| `ConnectionMeta.id`(팔레트) | 카탈로그에 endpoint/provider가 **있으면** 위와 같은 값, 없으면 `unknown-local` / `unknown-external` | `palette.ts:104-115` |
| 규칙 | 그래프는 **항상 `hostNodeIdFor`** 를 쓴다(카탈로그 정보가 없어도 host 노드는 존재). 라벨·상세·톤은 `connectionMetaOf(connectionId)`가 `connectionOf`와 **같은 함수**(`endpointConnectionMeta` / `providerConnectionMeta`, §7 WP-1에서 추출)로 만든다. 연결 id가 null인 모델(엔드포인트·Provider id 둘 다 없음)은 어느 그룹에도 속하지 않고 모델 중심처럼 그려진다. |

모델 ↔ 연결 소속은 그래프의 `host` 간선(`deployment:<id>/host → endpoint:<id>/in`)으로 읽는다. 이 간선은 `syncHostNodes`가 카탈로그에서 매 명령마다 다시 계산하므로 "모델은 정확히 한 연결에 속한다"가 항상 성립한다.

### 2.2 빈 연결(pinned)

- `addConnection`이 만드는 host 노드는 `props: { pinned: true }`를 가진다.
- `syncHostNodes`의 "쓰이지 않는 host 제거" 조건(`graphOps.ts:266-274`)에 `node.props.pinned !== true`를 추가한다. 그 밖의 경로는 그대로다.
- `compile`은 host 노드를 읽지 않는다(`compile.ts:127-143`은 `type === 'llm'`만 순회). 따라서 pinned 노드는 서버 설정에 아무 영향이 없다.
- 일반 `removeItems`는 여전히 파생 노드를 거부한다(모델 중심 동작 불변). `withConnectionModels: true`일 때만 host 노드를 지울 수 있다(§4.5).
- 카탈로그 기준으로 모델이 하나도 없는 연결은 놓을 수 없다('이 연결에는 등록된 모델이 없습니다.').

### 2.3 타입 추가 (`types.ts`, 문자 그대로)

`/* ---------------- Palette (v2) ---------------- */` 블록의 `PaletteConnectionGroup` 한 줄을 다음으로 바꾼다.

```ts
export interface PaletteConnectionGroup extends ConnectionMeta { models: PaletteModelItem[]; activeCount: number; totalCount: number; hiddenCount: number; /** A host node with this id is on the canvas. */ onCanvas: boolean; /** 'endpoint:'/'provider:' ids only (not the unknown-* buckets). */ addable: boolean }
```

`/* ---------------- Geometry ---------------- */` 블록 **바로 위**에 새 블록을 넣는다.

```ts
/* ---------------- Connection-centric view (docs/workflow-connection-first-design.ko.md) ---------------- */
export type FlowViewMode = 'connection' | 'model'
export type ConnectionHealth = 'online' | 'degraded' | 'offline' | 'disabled' | 'unknown'
export interface ConnectionGroupView {
  /** Host node id = connection id ('endpoint:<id>' | 'provider:<id>'). */
  connectionId: string
  kind: 'endpoint' | 'provider'
  /** LLM node ids in server dispatch order (priority asc, weight desc, id). */
  llmNodeIds: string[]
  /** Model-specific prompt node ids of those LLMs. */
  promptNodeIds: string[]
  health: ConnectionHealth
  /** Display rect of the whole group (connection node + models), unpadded. */
  frame: Rect
}
/** An edge as drawn in the connection view. Interactive edges keep the id of the domain edge they redraw. */
export interface ViewEdge extends FlowEdge {
  /** true: a real domain edge (selectable, deletable, splice-able); false: drawn only. */
  interactive: boolean
  /** Dashed membership line of a model that receives no request edge. */
  detached: boolean
}
export interface ConnectionView {
  groups: ConnectionGroupView[]
  /** Display position of every node whose position is derived (hosts, their LLMs and model prompts). */
  positions: Record<string, Point>
  edges: ViewEdge[]
  /** node id -> connection id for every grouped node (host, LLMs, model prompts). */
  groupOf: Record<string, string>
  /** LLM and model-prompt ids behind an offline or disabled connection, sorted. */
  dimmed: string[]
}
export interface ConnectionModelChoices { connection: ConnectionMeta; items: PaletteModelItem[]; hiddenCount: number }
```

`FlowCommand`·`FlowToolbarAction` 확장은 그것을 처리하는 WP에서 넣는다(WP-3, WP-4). 최종 모양:

```ts
export type FlowCommand =
  | { type: 'moveNodes'; moves: Array<{ nodeId: string; x: number; y: number }> }
  | { type: 'addNode'; nodeType: AddableNodeType; refId?: string | null; position?: Point | null; spliceEdgeId?: string | null; autoWire?: boolean }
  | { type: 'removeItems'; nodeIds: string[]; edgeIds: string[]; force?: boolean; /** Connection view: host nodes may be removed and take their models along. */ withConnectionModels?: boolean }
  | { type: 'connect'; from: PortRef; to: PortRef }
  | { type: 'updateProps'; nodeId: string; patch: Record<string, FlowPropValue> }
  | { type: 'replaceModel'; nodeId: string; deploymentId: string }
  | { type: 'setAccess'; projectNodeId: string; granted: boolean }
  | { type: 'normalizeOrder' }
  | { type: 'applyTemplate'; template: WorkflowTemplateId; projectIds: string[]; deploymentIds: string[] }
  | { type: 'toggleCollapse'; nodeIds: string[]; collapsed: boolean }
  | { type: 'autoLayout'; view?: FlowViewMode }
  | { type: 'addConnection'; connectionId: string; position?: Point | null }
  | { type: 'addConnectionModels'; connectionId: string; deploymentIds: string[]; position?: Point | null }
  | { type: 'connectConnection'; from: PortRef; to: PortRef }
export type FlowToolbarAction = 'undo' | 'redo' | 'fit' | 'auto-layout' | 'zoom-in' | 'zoom-out' | 'zoom-reset' | 'validate' | 'plan' | 'revert' | 'reload' | 'save-layout' | 'toggle-palette' | 'toggle-inspector' | 'mode-basic' | 'mode-advanced' | 'normalize-order' | 'view-connection' | 'view-model'
```

### 2.4 보기 상태

- `useFlowEditor.ts`: 모듈 변수 `let lastViewMode: FlowViewMode = 'connection'`(기존 `let lastMode` 바로 아래), 컴포저블 안 `const viewMode = ref<FlowViewMode>(lastViewMode)`. 툴바 액션 `view-connection` / `view-model`이 바꾼다. 그래프·이력·초안·레이아웃은 건드리지 않는다.
- 컴포넌트 prop 이름은 `viewMode?: FlowViewMode`. **기본값을 두지 않는다**(`undefined` = 모델 중심). 기존 테스트가 `withDefaults(..., { mode: 'advanced' })` 문자열을 고정하기 때문이다(`flowDesignerUi.test.mjs` FlowCanvas 검사).

### 2.5 좌표와 레이아웃 저장 (D4)

| 노드 | 저장 좌표(`node.x/y`, 레이아웃 키) | 모델 중심 표시 | 연결 중심 표시 |
|---|---|---|---|
| 시작·프로젝트·정책·트렁크·마스커 | 그대로 | 저장 좌표 | 저장 좌표 |
| LLM(연결 있음) | 모델 중심 위치 (`deployment:<id>`) | 저장 좌표 | **유도**: 그룹 앵커 기준 행 |
| 모델별 프롬프트(연결 있는 LLM) | 모델 중심 위치 (`prompt:<id>`) | 저장 좌표 | **유도**: 연결과 모델 사이 칸 |
| host(모델 있음) | 모델 중심 '실행 위치' 위치 (`endpoint:`/`provider:`) | 저장 좌표 | **유도**: 그룹 앵커 |
| host(빈 연결, pinned) | 놓은 위치 | 저장 좌표 | 저장 좌표 |
| LLM(연결 없음) | 그대로 | 저장 좌표 | 저장 좌표 |

- 그룹 앵커 = (그 연결 LLM들의 저장 x 최솟값 − `laneOffset`, 저장 y 최솟값). 빈 연결은 host 저장 좌표. 자세한 알고리즘은 §5.3.
- **보기 전환은 순수 렌더 전환**이다: `graph`가 바뀌지 않으므로 `layoutDirty`·`dirty`·이력·초안에 영향이 없다. 저장된 배치는 두 보기 공용이다. 새 키·새 필드·백엔드 변경이 없다.
- 연결 보기에서 그룹을 끌면 그룹의 LLM·프롬프트·host **저장 좌표**를 같은 delta로 옮긴다(§4.3). 그래서 연결 보기에서 정리한 배치가 [배치 저장]으로 서버에 남고, 모델 중심에서도 같은 상대 배치가 보인다.
- pinned host 위치도 `endpoint:`/`provider:` 키로 저장되지만(정규식 통과), 다시 불러올 때 그 노드가 없으므로 무시된다(`applyLayout`은 그래프에 있는 노드만 본다).

---

## 3. 그래프 의미

### 3.1 도메인 그래프는 그대로다

| 간선(도메인) | 모델 중심 렌더 | 연결 중심 렌더 |
|---|---|---|
| `router/out → deployment:x/in` (route) | 라우터 → 모델 | **연결/out → 모델/in** (같은 id, 선택·삭제·＋ 가능) + 장식선 `라우터/out → 연결/in` |
| `router/out → prompt:x/in` (route) | 라우터 → 프롬프트 | 연결/out → 프롬프트/in (같은 id) + 장식선 |
| `prompt:x/out → deployment:x/in` (route, locked) | 그대로 | 그대로 |
| `<stage>/out → deployment:x/in` (flow, 라우터 없는 단일 모델) | 그대로 | 연결/out → 모델/in (같은 id) + 장식선 `stage → 연결` |
| `project/out → deployment:x/in` (access, locked, 트렁크 없음) | 그대로 | 연결/out → 모델/in (같은 id, 잠김) + 장식선 `project → 연결` |
| `deployment:x/host → endpoint:y/in` (host) | 점선 | **숨김** |
| (요청 간선 없는 모델) | 없음 | 장식 점선 `연결/out → (프롬프트 또는 모델)/in` |
| entry·policy·masker·나머지 access | 그대로 | 그대로 |

- 장식선 id는 `view:` + `edgeId(from, to)`(예: `view:router/out->endpoint:ep1/in`). 도메인 노드 id는 `view:`로 시작하지 않으므로 충돌하지 않는다.
- 장식선은 히트 영역이 없어 선택·삭제·＋·드롭 대상이 되지 않는다. 선택 상태(`FlowSelection.edgeIds`)에는 항상 **도메인 간선 id만** 들어간다.

### 3.2 연결 보기 포트

| 노드 | 연결 보기 포트(`viewPortsOf`) | 모델 보기 포트 |
|---|---|---|
| host(연결) | in `in`(flow, '요청'), out `out`(flow, '모델') — `CONNECTION_PORTS` | `NODE_TYPES[type].ports` (in `in`, host) |
| llm | in `in`만(`host` 출력 포트 숨김) | `NODE_TYPES.llm.ports` |
| 그 밖 | `NODE_TYPES[type].ports` | 같음 |

`nodeSize`는 계속 `NODE_TYPES` 포트 수로 계산한다. 두 보기 모두 행 수가 1이라 카드 높이가 같다. `portAnchor(host, 'out', 'out')`은 스펙에 없는 포트라 폴백(오른쪽 가장자리, 0번 행)이 되는데, 이것이 연결 보기의 out 포트 위치와 정확히 같다(`layout.ts:60-68`).

### 3.3 compile · decompile · validate · diff · rebase · 이력 · 초안

| 모듈 | 변경 | 설명 |
|---|---|---|
| `compile.ts` | 없음 | host 노드·좌표·보기를 읽지 않는다. |
| `decompile.ts` | 없음 | 정규형 그래프 + `syncDerived`가 host 노드를 만든다. 연결 보기는 투영이 자동으로 그룹을 만든다("decompile이 기존 Target을 연결별로 자동 그룹화"). |
| `graphOps.ts` | `syncHostNodes` pinned 유지, `removeItems.withConnectionModels`, 새 명령 3개, `autoLayout.view`, `canConnectInView`, `nearestPortInView` | §4, §7 WP-3. |
| `validate.ts` | `CONNECTION_EMPTY` 경고 1개 | pinned + host 간선 없음. 그 밖 검증 불변. |
| `diff.ts` | 없음 | `planChanges`/`computeNodeChanges`/`rebase`는 compile 결과만 본다. `rebase`→`syncGraphToConfig`→`syncDerived`가 pinned를 유지한다. |
| `history.ts`·`draftStore.ts` | 없음 | 명령은 모두 `handleCommand`로 이력에 기록된다. 보기 전환은 그래프를 바꾸지 않으므로 기록 대상이 아니다. 초안은 그래프(pinned 포함)를 그대로 보관한다. |
| `workflow.ts` | 없음 | host는 `FLOW_NODE_TYPES`가 아니므로 트렁크·분기 분석에 들어가지 않는다. |

### 3.4 compile 동일성 증명

1. `compile(graph, snapshot)`이 읽는 것은 (a) id가 `start/guardrail/masker/prompt/params/cache/router`인 노드의 props, (b) `type === 'llm'`인 모든 노드의 `refId·targetId·props`, (c) id가 `prompt:<deploymentId>`인 노드의 props, (d) `masker` 간선과 `entry` 간선뿐이다(`compile.ts:72-154`). 좌표·host 노드·host 간선·보기 모드는 입력이 아니다.
2. 새 명령은 LLM 노드를 **오직** `applyCommand(g, { type: 'addNode', nodeType: 'llm', refId })`(→ `addLlm`, `defaultNodeProps('llm', …)`)로 만들고, 간선을 **오직** `applyCommand(g, { type: 'connect', … })`로 잇는다. 따라서 같은 순서로 같은 모델을 추가하면 LLM props(우선순위 = 기존 최대 + 1, 가중치 100, 외부면 `followModelChanges = false`)·라우터 자동 삽입·라우터 props가 모델 중심과 같다. 새 명령이 바꾸는 것은 **좌표**와 pinned host뿐이며 둘 다 (1)에 따라 compile 입력이 아니다.
3. `removeItems.withConnectionModels`는 지울 집합에 그 연결의 LLM id를 더할 뿐, 실제 삭제·치유는 기존 `removeWithHealing`이다. 결과는 같은 LLM들을 직접 선택해 지운 것과 같다(+ host 제거, compile 입력 아님).
4. 그러므로 같은 Target 집합(같은 props)에 대해 `compile` 결과와 `planChanges(...).operations`가 보기와 무관하게 같다. C9 골든 테스트가 이를 고정한다(연결 명령 경로 vs `addNode llm` 경로 → `compile` deepEqual, operation `[opId, kind, body]` deepEqual).

---

## 4. 상호작용

### 4.1 팔레트 (`FlowPalette.vue`, 연결 보기일 때만)

| 요소 | 동작 |
|---|---|
| 연결 머리글(`.fd-conn-head`) | 기존처럼 클릭 = 펼침/접힘. 추가로 `addable && !onCanvas && !readOnly`이면 `draggable`, `dragstart` 페이로드 `{ nodeType: 'connection', refId: conn.id }`(같은 MIME). |
| 머리글 옆 [＋] / [선택] 버튼(`.fd-conn-add`, `addable`일 때만) | `onCanvas`면 `select-node`(연결 id, 캔버스가 중앙 이동). 아니면 `addConnection { connectionId, position: null }`(위치는 `defaultConnectionSpot`). |
| 모델 항목 클릭(캔버스에 없음) | `connectionIdForDeployment`가 있으면 `addConnectionModels { connectionId, deploymentIds: [id], position: null }`, 없으면 기존 `addNode llm`. |
| 모델 항목 드래그 | 페이로드는 그대로(`llm`). 캔버스가 연결 보기면 `addConnectionModels`(놓은 위치를 새 그룹 앵커로)로 바꾼다(§4.6). |
| LLM 그룹 안내 줄 | "연결 머리글을 캔버스로 끌어 놓거나 ＋를 누른 뒤, 연결 노드에서 모델을 고르세요." |

모델 중심에서는 팔레트가 지금과 완전히 같다.

### 4.2 연결 노드의 '모델 추가' (D10)

- 연결 카드(연결 보기, 펼친 상태, `readOnly` 아님)의 포트 행 가운데에 **[＋ 모델 추가]** 버튼. 클릭 = 기존 `activate` 이벤트(선택 + 좁은 화면이면 인스펙터 서랍 열기).
- 인스펙터 연결 패널(연결 보기 + host 노드 단독 선택):
  1. 머리(연결 라벨, `연결 · 로컬 · {detail}` / `연결 · 외부 · {detail}`, 상태 배지), 사실 표(기존 `nodeFacts`).
  2. 상태가 오프라인·비활성이면 경고 노트 "이 연결은 지금 요청을 처리할 수 없어 아래 모델이 흐리게 표시됩니다."
  3. **이 연결의 모델 N개** — 버튼 목록(제목 + `P·W`), 클릭하면 그 모델 선택·중앙 이동.
  4. **모델 추가 · {연결 라벨}**(`FlowConnectionModels.vue`) — `connectionModelChoices(snapshot, graph, connectionId, showInactive)`의 항목을 체크박스로. 이미 캔버스에 있는 모델은 체크·비활성 + '워크플로우에 있음'. 중지·미로드는 기본 숨김("중지·미로드 N개 표시" / "중지·미로드 숨기기"). [선택한 모델 추가 (n)] → `addConnectionModels`. 한 번에 여러 개 = 이력 1단계.
  5. [연결 삭제 (모델 N개 포함)] (D8).
- `addConnectionModels` 결과 선택은 연결 노드다(패널이 열린 채 계속 추가 가능).

### 4.3 그룹 드래그 (D5)

- `connectionDragIds(graph, view, ids, alone)`:
  - 그룹에 속한 id(host·LLM·모델별 프롬프트) → 그 그룹 전체(host + LLM + 프롬프트). `alone`(Ctrl/⌘)이어도 그룹은 쪼개지 않는다.
  - 그룹 밖 id → `alone`이면 자기 자신, 아니면 기존 `dependencyClosure`(가드레일→마스커, 프로젝트→정책)에서 그룹 노드를 뺀 것.
- 끄는 동안 표시: 대상 노드 전부에 **같은 delta**를 더한다. delta는 누른 노드의 표시 위치를 그리드 스냅(Alt면 반올림만)한 값과의 차이(`groupDelta`). 그룹 틀(frame)도 같은 delta로 움직인다.
- 놓을 때: 대상 id마다 `저장 좌표 + delta`로 `moveNodes` 1회. host도 포함되므로 모델 중심의 '실행 위치'가 모델을 따라간다.
- 화살표 키: 같은 대상 집합에 스텝(16 / Shift 64)을 더한 `moveNodes`.
- 모델 중심은 지금 그대로(`dependencyClosure`, 노드별 스냅).
- 포인터 수학은 기존 `flush()`(`scale()`로 나눔), `localPoint()`(=`clientToLocal`), `screenToWorld`만 쓴다. **`getBoundingClientRect` 값을 직접 더하거나 빼지 않는다**(`.console-shell` CSS zoom).

### 4.4 간선 규칙 (`canConnectInView`, 연결 보기)

판정 순서와 메시지(문자 그대로):

**A. 대상이 연결 입력(`host/in`)**
1. `from.portId !== 'out' || to.portId !== 'in'` → `MSG_DIRECTION` '출력 포트에서 입력 포트로만 연결할 수 있습니다.'
2. 출발이 라우터 또는 단계(가드레일·공통 프롬프트·캐시·생성 파라미터)가 아님 → '연결 노드에는 라우터나 처리 단계(가드레일·공통 프롬프트·캐시·생성 파라미터)의 출력에서만 간선을 이을 수 있습니다.'
3. 그 연결에 모델이 없음 → '이 연결에는 아직 모델이 없습니다. 연결 노드에서 모델을 먼저 추가하세요.'
4. 요청 간선 없는 모델(head 기준)이 0개 → '이 연결의 모델은 이미 모두 요청 경로에 연결되어 있습니다.'
5. 출발이 라우터가 아니고 대기 모델이 2개 이상 → '모델이 여러 개인 연결은 라우터에서 이어 주세요.'
6. 대기 head마다 기존 `canConnect(from → head)`가 실패하면 그 사유.
7. 성공: `connectConnection`이 대기 head마다 기존 `connect`를 실행(라우터 자동 삽입 등 기존 규칙 그대로).

**B. 출발이 연결 출력(`host/out`)**
1. 포트 방향 오류 → `MSG_DIRECTION`.
2. 대상이 LLM 또는 모델별 프롬프트가 아님 → '연결 노드의 출력은 그 연결의 모델에만 이을 수 있습니다.'
3. 대상 모델의 연결 ≠ 출발 연결 → `이 모델은 '${라벨}' 연결에 속합니다. 모델의 연결은 바꿀 수 없습니다. 다른 연결의 모델은 그 연결 노드에서 추가하세요.` (D9). 캔버스는 이렇게 거부된 연결/모델 간선을 놓으면 같은 명령을 보내 **페이지 메시지 줄**에 사유를 띄운다(기존 캔버스는 거부 사유를 화면 읽기 영역에만 알렸다).
4. 대상 head에 이미 요청 간선 → '이미 요청 경로에 연결된 모델입니다.'
5. 요청 출발점(`requestSourceFor`): ① 같은 그룹에서 요청 간선(flow/route)을 받는 형제 head의 출발 포트(디스패치 순서 첫째), ② 없으면 분석상의 라우터, ③ 없으면 트렁크 꼬리. 모두 없으면 '이 모델을 이을 요청 경로가 없습니다. 라우터나 처리 단계를 먼저 추가하세요.'
6. 기존 `canConnect(출발점 → head)` 실패 사유.
7. 성공: `connect(출발점 → head)`.

**C. 그 밖**(라우터 → 모델 직접 등) → 기존 `canConnect` 그대로(연결 보기에서도 허용, 결과는 연결을 거쳐 그려짐).

모델 보기에서는 `canConnectInView === canConnect`.

### 4.5 삭제 (D8)

| 경로 | 명령 |
|---|---|
| 캔버스 Delete/Backspace | `removeItems { nodeIds, edgeIds, withConnectionModels: viewKind === 'connection' }` |
| 인스펙터 [연결 삭제 (모델 N개 포함)] | `removeItems { nodeIds: [hostId], edgeIds: [], withConnectionModels: true }` |
| 인스펙터 다중 선택 [선택 삭제] | `withConnectionModels: 연결 보기 여부` |
| `CONNECTION_EMPTY` 빠른 수정 '연결 삭제' | `removeItems { …, withConnectionModels: true }` |

- `withConnectionModels`가 참이면 host 노드를 거부하지 않고, 그 host로 가는 `host` 간선의 LLM id를 지울 집합에 더한다. 이후 기존 `dependencyClosure`(모델별 프롬프트 포함)·라우터 보호 규칙·치유가 그대로 적용된다.
- 모델 하나를 지워도 pinned가 아닌 연결은 마지막 모델과 함께 사라진다(기존 sync). pinned 연결은 남아 경고가 뜬다.
- 알림: `연결 ${h}개와 모델 ${n}개를 워크플로우에서 뺐습니다. 실행 취소(Ctrl+Z)로 되돌릴 수 있습니다.`

### 4.6 캔버스 드롭

| 페이로드 | 연결 보기 | 모델 보기 |
|---|---|---|
| `{ nodeType: 'connection', refId }` | `addConnection { connectionId: refId, position: 스냅 좌표 }` | 무시 |
| `{ nodeType: 'llm', refId }`, 간선 위 아님 | 연결 id가 있으면 `addConnectionModels { connectionId, deploymentIds: [refId], position: 스냅 좌표 }`(그 연결이 이미 있으면 위치는 무시되고 그룹 아래에 붙음), 없으면 기존 `addNode` | 기존 `addNode` |
| 그 밖 | 기존(간선 위 splice는 장식선이 아닌 실제 간선에만) | 기존 |

### 4.7 모델 교체 제한 (D9)

연결 보기에서 LLM 인스펙터의 '모델 교체' select는 `hostNodeIdFor(d) === 현재 모델의 연결` 인 배포만 보인다. 도움말: '연결 중심 보기에서는 같은 연결의 모델로만 바꿀 수 있습니다. 다른 연결의 모델은 그 연결 노드에서 추가하세요.' 모델 보기는 지금 그대로(모든 연결).

### 4.8 보기 전환 (양방향)

- `연결 중심 → 모델 중심`: 렌더만 바뀐다. host 노드가 저장 좌표(실행 위치)로, 모델이 저장 좌표로 돌아가고 host 점선이 다시 보인다. 기본 모드면 host 노드가 다시 숨는다(기존 규칙).
- `모델 중심 → 연결 중심`: 렌더만 바뀐다. host 노드가 그룹 앵커로, 모델·프롬프트가 유도 위치로 간다. 기본 모드에서도 연결 노드는 보인다.
- 선택·뷰포트·이력·초안·`layoutDirty`는 바뀌지 않는다. 자동 '화면 맞춤'은 하지 않는다(사용자 확대 상태 유지, [화면 맞춤]으로 맞춤).

### 4.9 기본/고급 모드 · 빠른 시작

- 기본 모드 + 연결 보기: 연결 노드가 보인다(연결 보기의 주 노드). 인스펙터 필드 필터(가중치 등 고급 필드 숨김)는 그대로.
- 기본 모드 + 모델 보기: 지금처럼 host 노드·host 간선 숨김.
- 빠른 시작: 변경 없음(D12). `showQuickStart`에 "그래프에 연결 노드가 없음" 조건을 더해 빈 워크플로우에 연결을 놓으면 오버레이가 닫힌다.

---

## 5. 렌더링

### 5.1 `FlowNodeCard.vue` 변형

| prop(신규, 선택) | 효과 |
|---|---|
| `dimmed?: boolean` | 루트에 `is-dimmed` → 본문 `opacity: .5`. 연결 보기에서 오프라인·비활성 연결의 모델·프롬프트. |
| `action?: string \| null` | 펼친 카드의 포트 행 가운데 버튼 `.fd-node-action`(`@click.stop` → `emit('activate', node.id)`). 연결 보기의 연결 카드에 '＋ 모델 추가'. |

`ports` prop은 캔버스가 `viewPortsOf(node, viewKind)`로 넘긴다. 연결 카드의 `view`는 `describeConnectionNode`가 만든다:

| 항목 | 값 |
|---|---|
| title | 연결 라벨(`connectionMetaOf(...).label`, 없으면 '알 수 없는 연결') |
| subtitle | `로컬 · {detail}` / `외부 · {detail}` (detail 없으면 '로컬'/'외부'만) |
| typeLabel | '연결' (인스펙터 머리는 `연결 · 로컬 · LLAMA_CPP · …`로 읽힌다) |
| category · icon | 'infra' · `NODE_TYPES[type].icon` |
| tone | health → online 'healthy', degraded 'warning', offline 'error', disabled 'muted', unknown 'unknown' |
| badges | `[상태 라벨]`, 모델이 없으면 `[상태 라벨, '모델 없음']` |
| summary | `모델 N개 · 사용 M개`(없으면 '모델을 추가하세요'), 우선순위가 있으면 `우선순위 P1`/`우선순위 P1–P4` |
| locked · protected · lockReason · promptScope | false · false · null · null (그래서 '자동' 칩이 나오지 않는다) |

### 5.2 상태(health)와 흐림

`connectionHealth(connectionId, snapshot)`:
- provider: 카탈로그에 없음 'unknown' → `!enabled` 'disabled' → `!apiKeyConfigured` 'offline' → `toneOf(enabled, healthStatus)`를 healthy→'online', warning→'degraded', error→'offline', muted→'disabled', unknown→'unknown'.
- endpoint: 없음 'unknown' → `!enabled` 'disabled' → 위 톤 매핑.
- 라벨 `CONNECTION_HEALTH_LABEL`: online '온라인', degraded '주의', offline '오프라인', disabled '비활성', unknown '상태 미확인'.
- 'offline'·'disabled' 그룹의 LLM·프롬프트 → `view.dimmed`(카드 흐림), 그 모델로 들어가는 간선도 `is-dim`. 그룹 틀은 점선.
- 모델 자체 배지('Degraded'·'미로드'·'비활성' 등)는 `describeNode` 그대로.

### 5.3 연결 보기 배치 알고리즘 (`buildConnectionView`, 결정적)

```
입력: graph, snapshot (graph를 바꾸지 않는다)
1. groups = connectionGroups(graph)
   - host 노드(endpoint/provider) 전부, id 오름차순. 빈(pinned) host도 그룹.
   - 그룹의 LLM = host 간선으로 이 host를 가리키는 llm 노드, 디스패치 순서(priority 오름, weight 내림, id).
   - 그룹의 프롬프트 = 그 LLM들의 `prompt:<deploymentId>` 노드(있는 것만).
2. laneOffset = (어느 그룹에든 프롬프트가 있으면) COLUMN_WIDTH(300) 아니면 0   ← 전역(모든 그룹의 모델 칸 정렬)
3. 그룹마다
   anchor = LLM 있으면 (min(LLM.x) − laneOffset, min(LLM.y)), 없으면 (host.x, host.y)
   rows[i] = { llm, prompt?, height = max(nodeSize(llm).h, nodeSize(prompt).h) }
   offsets[0] = 0, offsets[i] = snapUp(offsets[i-1] + rows[i-1].height + ROW_GAP)
   width = LLM 있으면 COLUMN_WIDTH + laneOffset + NODE_WIDTH, 없으면 NODE_WIDTH
   height = max(nodeSize(host).h, offsets[last] + rows[last].height)
4. 그룹을 (anchor.y, anchor.x, connectionId) 오름차순으로 정렬
5. obstacles = 그룹에 속하지 않은 노드들의 nodeRect(저장 좌표)
6. 차례로 배치: y = anchor.y; (최대 500회) 사각형이 이미 놓은 그룹 틀 또는 obstacles와 (간격 ROW_GAP/2 포함) 겹치면
   y = snapUp(겹친 사각형 아래 + ROW_GAP). 틀 = (anchor.x, y, width, height)
7. positions: host → (anchor.x, y); 행 i: prompt → (anchor.x + COLUMN_WIDTH, y + offsets[i]),
   llm → (anchor.x + COLUMN_WIDTH + laneOffset, y + offsets[i])
8. edges = §3.1 투영(장식 출발선 → 점선 → 실제 간선 순, 실제 간선이 위에 그려짐)
9. health/dimmed = §5.2
```

성질: 결정적(같은 입력 → 같은 출력), 그룹끼리 겹치지 않음, 저장 좌표만 쓰므로 새로고침·초안 복원 후에도 같은 화면. 모델 중심 `autoLayout` 결과(라우터 x=600, LLM 칸 x=900)에서 연결은 x=900(라우터 바로 다음 칸), 모델은 x=1200에 놓인다. 모델별 프롬프트를 넣으면 모델 중심의 `insertBranchPrompt`가 모든 LLM을 300 밀기 때문에(`graphOps.ts:400-410`) `anchor = LLM.x − 300`이 그대로라 연결이 움직이지 않는다.

### 5.4 자동 정렬 (`autoLayoutByConnection`, 연결 보기의 [자동 정렬])

```
1. laid = autoLayout(graph, snapshot)       (기존, 모델 중심)
2. branches = analyzeWorkflow(laid).branches (디스패치 순서)
3. 그룹 키 = 그 LLM의 host id(없으면 LLM id), 키 순서 = 첫 등장 순서
4. y0 = min(분기 LLM y). 그룹 순서대로, 그룹 안은 branches 순서대로 LLM(과 그 프롬프트)을 y에 놓고
   y = snapUp(y + max(llm.h, prompt.h) + ROW_GAP). x는 laid 값 유지.
5. host: 그룹 첫 LLM의 y와 바로 위 host 아래(floor) 중 큰 값, x 유지.
6. 그 밖 노드는 laid 그대로.
```

모델 중심의 [자동 정렬]은 기존 `autoLayout` 그대로다(`autoLayout` 명령의 `view`가 없거나 'model').

### 5.5 그룹 틀·간선 스타일 (`FlowCanvas.vue`, CSS 변수만)

```css
.fd-group-frame { fill: var(--surface-2); stroke: var(--border); stroke-width: 1; }
.fd-group-frame.is-degraded { stroke: var(--warning); }
.fd-group-frame.is-offline, .fd-group-frame.is-disabled { stroke-dasharray: 6 5; opacity: .7; }
.fd-edge.is-decor .fd-edge-line { opacity: .85; }
.fd-edge.is-detached .fd-edge-line { stroke: var(--muted); stroke-dasharray: 4 6; }
```

틀은 그룹 frame을 12px 패딩해 SVG 간선보다 먼저(아래) 그린다. 분리 점선은 흐림(`is-dim`)을 겹쳐 쓰지 않는다(시제품에서 `--faint` + opacity .4는 거의 보이지 않았다).

---

## 6. 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 배포에 엔드포인트·Provider id가 둘 다 없음 | host 없음 → 그룹 밖, 저장 좌표 + 도메인 간선 그대로. 팔레트는 'unknown-*'(addable false), 클릭·드롭은 기존 `addNode llm`. |
| E2 | 카탈로그에 없는 배포(UNKNOWN_DEPLOYMENT) | `syncHostNodes`가 host 간선을 지움 → 그룹 밖. |
| E3 | 카탈로그에 없는 endpoint/provider를 가리키는 배포 | host 노드는 존재(`hostNodeIdFor`), 라벨 '알 수 없는 연결', 상태 'unknown'(흐리지 않음). 팔레트에서 놓기 불가(addable false). |
| E4 | 빈 pinned 연결 | 경고 + '연결 삭제' 수정, sync·rebase·초안에서 유지, 새로고침하면 사라짐, Plan 0건. 모델 보기에서는 간선 없는 '실행 위치' 노드로 보인다. |
| E5 | pinned 아닌 연결의 마지막 모델 삭제 | 연결도 사라짐(기존 sync). |
| E6 | 모델 하나가 요청 간선 없음(분리) | 연결→모델 점선 + 'LLM 미연결' 오류 배지. 형제가 라우터에서 받으면 라우터→연결 장식선은 유지. |
| E7 | 모델별 프롬프트가 있으나 엉뚱하게 배선됨(WF_BRANCH_PROMPT_MISPLACED) | 그룹 밖에서 그룹 안(LLM·프롬프트)으로 들어오는 요청 간선은 모두 연결 출력에서 다시 그림. 분리 점선은 LLM·프롬프트 어느 쪽으로도 요청이 없을 때만. |
| E8 | 트렁크 없이 프로젝트가 직접 모델로(access) | 프로젝트마다 장식선 `project → 연결`, 잠긴 access 간선이 연결→모델로 겹쳐 그려짐(＋로 트렁크 삽입 가능). |
| E9 | 그룹이 트렁크·다른 노드와 겹침 | 그룹만 아래로 밀림(그 밖 노드는 움직이지 않음). |
| E10 | 같은 연결 모델들이 모델 보기에서 흩어져 있음 | 앵커 = 최소 x/최소 y, 나머지는 그 아래 행으로 모임(그룹화가 목적). |
| E11 | 첫 모델별 프롬프트를 넣었는데 LLM이 이미 충분히 오른쪽이라 밀리지 않음 | 전역 laneOffset 때문에 연결이 300 왼쪽으로 이동(외관). [자동 정렬]로 정리. 마지막 프롬프트 삭제 시 라우터와 연결 사이에 빈 칸이 생길 수 있음(외관). |
| E12 | 접힌 노드 | 행 높이 = 접힌 높이 40. 접힌 연결 카드에는 [＋ 모델 추가] 버튼 없음. |
| E13 | 원격 모델 추적으로 Target의 배포가 다른 연결로 바뀜(`syncGraphToConfig` 이름 변경) | host 간선이 다시 계산돼 모델이 새 연결 그룹으로 이동. |
| E14 | 연결 보기에서 '모델 교체' | 같은 연결만(D9). 모델 보기에서 다른 연결로 바꾸면 연결 보기에서 그룹이 바뀜. |
| E15 | 읽기 전용(좁은 화면·Plan 중) | 카드 버튼·팔레트 [＋]·드래그·삭제 비활성. 보기 전환은 허용. |
| E16 | 연결이 비활성인데 모델을 추가 | 허용(팔레트 규칙과 같음, '중지·미로드 표시'로 보임). 검증 경고 `DEPLOYMENT_UNAVAILABLE`은 기존대로. |
| E17 | 마키 선택에 연결 노드가 포함된 채 Delete | 연결 보기에서는 그 연결과 모델이 함께 삭제(실행 취소 가능). |
| E18 | 선택된 노드가 보기 전환으로 숨음(기본 모드 + 모델 보기의 host) | 선택 유지, 인스펙터는 계속 표시. |
| E19 | 300 노드 한도 | host 노드는 원래도 노드 수에 포함되었다. pinned만큼 늘 수 있다. |
| E20 | 같은 모델을 두 번 추가 요청 | `addConnectionModels`가 캔버스에 있는 id를 건너뛴다(전부 있으면 `changed: false`). |
| E21 | 다른 연결의 모델을 섞은 `addConnectionModels` | 전체 거부 '선택한 모델 중 이 연결에 속하지 않는 모델이 있습니다.' |
| E22 | 초안 복원 · 적용 후 rebase · [되돌리기] | 초안·rebase는 pinned 유지, [되돌리기]는 서버 상태로 decompile(pinned 사라짐). |
| E23 | CSS zoom 환경에서 끌기·배선·드롭 | 기존 `localPoint`/`screenToWorld`/`scale()` 경로만 사용(§4.3). |

---

## 7. 작업 지시서

### 7.0 공통 규칙 (모든 WP 필독)

- 작업 위치는 **`C:\project\aiconnect-connection-first`만**. `C:\project\aiconnect`(다른 에이전트가 미커밋 수정 중)는 읽지도 고치지도 않는다. 커밋·푸시는 하지 않는다.
- **충돌 회피(필수)**: 다른 에이전트가 같은 파일을 고치고 있다.
  - `useFlowEditor.ts`: 함수 `stashCurrent`, 함수 `loadService` 전체, `let loadTicket = 0` 줄과 그 위아래 3줄을 **건드리지 않는다**. 이 문서가 지정한 위치(모듈 변수, `restoredAt` 바로 아래, `showQuickStart`, `commandNotice`, `onToolbarAction`, `return`)만 고친다.
  - `draftStore.ts`, `flowDesignerAdapter.test.mjs`, `flowDesignerUi.test.mjs`, `flowDesignerModel.test.mjs`는 **수정 금지**(그대로 통과해야 한다). 새 테스트는 신규 파일 `frontend/tests/flowDesignerConnections.test.mjs`에만 쓴다.
- 의존성 추가 금지(런타임은 `vue`만). `flow-designer/` 안에서 `localStorage`·`sessionStorage` 쓰기·`v-html`·`innerHTML` 금지. 색은 CSS 변수만(`#hex`, `rgb(` 금지). 사용자 문자열은 한국어, 식별자는 영어. 상대 import는 확장자 없이, 타입은 `import type`.
- **import 방향(순환 금지)**: `connections.ts`는 `catalog`·`layout`·`palette`·`workflow`만 import한다. `graphOps.ts`가 `connections.ts`를 import한다. `connections.ts`가 `graphOps`·`compile`·`decompile`·`validate`·`diff`를 import하면 **테스트 로더가 무한 재귀**한다(`tests/loadTypeScript.mjs`는 순환 import를 지원하지 않음).
- 기존 테스트가 고정한 문자열 보존:
  - `FlowCanvas.vue`: `withDefaults(..., { mode: 'advanced' })`(viewMode 기본값 추가 금지), `const ADDABLE` 배열, `import { … dependencyClosure … } from './workflow'`, `event.ctrlKey || event.metaKey`, `autoWire: !event.altKey`, `props.mode !== 'basic'`, `e.kind !== 'host'`, `v-for="node in visibleGraph.nodes"`, `.fd-world` 규칙과 `will-change`·`contain`·`const isZooming = ref(false)`, `onPointerUp`의 각 분기가 `resetInteraction()` 뒤에 `moved`를 읽지 않는 구조. 그리고 **`'deployment'`·`'target'`·`'binding'` 따옴표 문자열을 FlowCanvas에 쓰지 않는다**.
  - `FlowPalette.vue`: import 줄 `import { buildPalette, shortModelName } from './palette'` 그대로(다른 이름은 별도 import 줄로), `.sort(`·`localeCompare` 금지, `class="fd-conn-head"` 바로 다음 속성이 `:aria-expanded="connOpen(group.id, conn)"`(새 속성은 그 뒤에), `@dragstart="dragStart($event, 'llm', item.deploymentId)"`·`@click="clickModel(item)"` 유지.
  - `FlowInspector.vue`: `<optgroup v-for="group in modelOptions" :key="group.key"`, `groupModelsByConnection` 사용 유지.
  - `FlowDesignerPage.vue`: `:mode="mode" :can-normalize="canNormalize"` 연속 문자열 유지(새 바인딩은 그 뒤에).
  - `useFlowEditor.ts`: `let lastMode: DesignerMode = 'basic'` 유지.
  - `diff.ts` 문구(`'LLM 연결 추가'` 등)는 손대지 않는다.
- 일부 파일은 CRLF다. 편집 도구가 줄 끝을 보존하게 하고, 테스트는 `\r\n`을 정규화해 읽는다.
- **각 WP 완료 조건**(Git Bash, `frontend` 디렉터리):
  ```bash
  cd /c/project/aiconnect-connection-first/frontend
  npx vue-tsc --noEmit          # package.json에 'typecheck' 스크립트는 없다(build가 vue-tsc를 포함)
  npm run test:flow-designer
  npm run build
  ```
  WP-7에서 프런트 전체 체인(v2 §13.0)을 돌린다.

### 7.1 단계 · 파일

| WP | 내용 | 파일 | 선행 |
|---|---|---|---|
| WP-1 | 타입(명령 제외) · layout export · palette 메타 추출/필드 · 테스트 골격 | `types.ts`, `layout.ts`, `palette.ts`, `tests/flowDesignerConnections.test.mjs`(신규), `package.json` | — |
| WP-2 | 순수 모듈 `connections.ts` + 테스트 | `connections.ts`(신규), 테스트 | WP-1 |
| WP-3 | 명령·간선 규칙·검증 + 테스트 | `types.ts`(FlowCommand), `graphOps.ts`, `validate.ts`, 테스트 | WP-2 |
| WP-4 | 편집기 보기 상태·툴바·페이지(툴바 바인딩) + 테스트 | `types.ts`(FlowToolbarAction), `useFlowEditor.ts`, `FlowToolbar.vue`, `FlowDesignerPage.vue`, 테스트 | WP-3 |
| WP-5 | 노드 카드·캔버스 투영 + 테스트 | `FlowNodeCard.vue`, `FlowCanvas.vue`, `FlowDesignerPage.vue`(캔버스 바인딩), 테스트 | WP-4 |
| WP-6 | 모델 추가 패널·팔레트·인스펙터 + 테스트 | `FlowConnectionModels.vue`(신규), `FlowPalette.vue`, `FlowInspector.vue`, `FlowDesignerPage.vue`(팔레트·인스펙터 바인딩), 테스트 | WP-5 |
| WP-7 | 전체 검증 · 수동 점검 · 보고 | — | WP-6 |

---

### WP-1 · 타입 · layout export · palette 메타 추출

1. `types.ts`: §2.3의 `PaletteConnectionGroup` 교체와 "Connection-centric view" 블록 추가. **`FlowCommand`·`FlowToolbarAction`은 아직 바꾸지 않는다**(처리 코드 없이 바꾸면 `applyCommand` 반환 경로 타입 오류).
2. `layout.ts`: 함수 두 개에 `export`만 붙인다. 본문 변경 없음.
   - `function snapUp(value: number, grid = GRID_SIZE): number` → `export function snapUp(...)`
   - `function freeSpot(others: FlowNode[], node: FlowNode, x: number, y: number): Point` → `export function freeSpot(...)`
3. `palette.ts`:
   - import 타입에 `EndpointInfo`, `ProviderInfo` 추가.
   - `connectionOf` **위에** 두 함수를 추가하고 `connectionOf`가 이를 쓰게 바꾼다(출력은 지금과 같아야 한다 — 기존 테스트 22b가 검사).
     ```ts
     /** ConnectionMeta of a runtime endpoint (shared by the palette and the connection-centric view). */
     export function endpointConnectionMeta(endpoint: EndpointInfo): ConnectionMeta {
       return { id: `endpoint:${endpoint.id}`, kind: 'endpoint', label: endpoint.displayName, detail: [endpoint.runtimeType, endpoint.addressLabel].filter((x) => !!x).join(' · '), tone: toneOf(endpoint.enabled, endpoint.healthStatus), enabled: endpoint.enabled }
     }

     /** ConnectionMeta of an external provider (shared by the palette and the connection-centric view). */
     export function providerConnectionMeta(provider: ProviderInfo): ConnectionMeta {
       return { id: `provider:${provider.id}`, kind: 'provider', label: provider.displayName, detail: provider.providerType, tone: toneOf(provider.enabled, provider.healthStatus), enabled: provider.enabled }
     }

     /** Resolves the connection a deployment belongs to; missing catalog entries land in an "unknown connection". */
     export function connectionOf(d: DeploymentInfo, catalog: FlowCatalog): ConnectionMeta {
       if (d.external) {
         const provider = d.externalProviderId ? catalog.providers.find((p) => p.id === d.externalProviderId) : undefined
         if (provider) return providerConnectionMeta(provider)
         return { id: 'unknown-external', kind: 'provider', label: UNKNOWN_CONNECTION, detail: '', tone: 'unknown', enabled: true }
       }
       const endpoint = d.runtimeEndpointId ? catalog.endpoints.find((e) => e.id === d.runtimeEndpointId) : undefined
       if (endpoint) return endpointConnectionMeta(endpoint)
       return { id: 'unknown-local', kind: 'endpoint', label: UNKNOWN_CONNECTION, detail: '', tone: 'unknown', enabled: true }
     }
     ```
   - `function modelItem(` → `export function modelItem(`(본문 불변).
   - `buildPalette`의 `connections.push({ ...g.connection, models: visible, activeCount: g.activeCount, totalCount: all.length, hiddenCount: hidden })`를 다음으로 바꾼다.
     ```ts
     connections.push({
       ...g.connection,
       models: visible,
       activeCount: g.activeCount,
       totalCount: all.length,
       hiddenCount: hidden,
       onCanvas: graph.nodes.some((n) => n.id === g.connection.id),
       addable: g.connection.id.startsWith('endpoint:') || g.connection.id.startsWith('provider:'),
     })
     ```
4. `package.json`: `"test:flow-designer"` 끝에 ` tests/flowDesignerConnections.test.mjs` 추가.
5. 테스트 파일 신규 — §7.8의 **머리·픽스처·C0**만 넣는다(이후 WP가 섹션을 덧붙인다).

**수용**: 기존 76개 + C0 통과, `vue-tsc` 무오류, build 녹색.

---

### WP-2 · `frontend/src/flow-designer/connections.ts` (신규, 문자 그대로)

```ts
// Connection-centric view ("연결 중심 배치") of the Workflow Designer. Pure TypeScript: no Vue, no DOM, no storage.
// A connection node IS the derived host node ('endpoint:<id>' | 'provider:<id>', the same id as ConnectionMeta.id).
// This module only groups, projects and describes: it never changes anything compile() reads.
// Import direction: catalog, layout, palette, workflow only (graphOps imports this module, never the reverse).
import { NODE_TYPES, branchPromptNodeId, edgeId, hostNodeIdFor, llmNodeId, toneOf } from './catalog'
import { COLUMN_WIDTH, NODE_WIDTH, ROW_GAP, autoLayout, nodeRect, nodeSize, snapUp } from './layout'
import { endpointConnectionMeta, groupModelsByConnection, modelItem, providerConnectionMeta } from './palette'
import { analyzeWorkflow, dependencyClosure, isBranchPrompt } from './workflow'
import type {
  ConnectionGroupView,
  ConnectionHealth,
  ConnectionMeta,
  ConnectionModelChoices,
  ConnectionView,
  FlowGraph,
  FlowNode,
  FlowViewMode,
  HealthTone,
  NodeView,
  Point,
  PortRef,
  PortSpec,
  Rect,
  ServiceFlowSnapshot,
  ViewEdge,
} from './types'

const CONNECTION_ID = /^(endpoint|provider):[A-Za-z0-9-]{1,64}$/
const UNKNOWN_LABEL = '알 수 없는 연결'
const REQUEST_KINDS: ReadonlySet<string> = new Set(['flow', 'route', 'access'])

/** Ports of a connection node in the connection view: requests come in on the left, models hang off the right. */
export const CONNECTION_PORTS: PortSpec[] = [
  { id: 'in', direction: 'in', type: 'flow', label: '요청', multiple: true },
  { id: 'out', direction: 'out', type: 'flow', label: '모델', multiple: true },
]
/** An LLM in the connection view: its connection is drawn in front of it, so the 실행 위치 port is hidden. */
const LLM_PORTS_IN_CONNECTION_VIEW: PortSpec[] = NODE_TYPES.llm.ports.filter((p) => p.id !== 'host')

export const CONNECTION_HEALTH_LABEL: Record<ConnectionHealth, string> = {
  online: '온라인',
  degraded: '주의',
  offline: '오프라인',
  disabled: '비활성',
  unknown: '상태 미확인',
}
const HEALTH_TONE: Record<ConnectionHealth, HealthTone> = { online: 'healthy', degraded: 'warning', offline: 'error', disabled: 'muted', unknown: 'unknown' }
const TONE_HEALTH: Record<HealthTone, ConnectionHealth> = { healthy: 'online', warning: 'degraded', error: 'offline', muted: 'disabled', unknown: 'unknown' }

function cmp(a: string, b: string): number { return a < b ? -1 : a > b ? 1 : 0 }
function num(value: unknown, fallback: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) ? n : fallback
}
function overlaps(a: Rect, b: Rect, gap: number): boolean {
  return a.x < b.x + b.width + gap && a.x + a.width + gap > b.x && a.y < b.y + b.height + gap && a.y + a.height + gap > b.y
}
/** Server dispatch order inside one connection (all its models share local/external): priority asc, weight desc, id. */
function dispatchOrder(a: FlowNode, b: FlowNode): number {
  return num(a.props.priority, Infinity) - num(b.props.priority, Infinity) || num(b.props.weight, 0) - num(a.props.weight, 0) || cmp(a.id, b.id)
}

/* ------------------------------------------------------------------ */
/* Identity                                                            */
/* ------------------------------------------------------------------ */

export function isConnectionId(id: string): boolean {
  return CONNECTION_ID.test(id)
}

export function isConnectionNode(node: FlowNode | null | undefined): boolean {
  return !!node && (node.type === 'endpoint' || node.type === 'provider')
}

export function deploymentIdOfNode(node: FlowNode): string {
  return node.refId ?? (node.id.startsWith('deployment:') ? node.id.slice('deployment:'.length) : node.id)
}

/** The connection (host node id) a catalog deployment belongs to; null when it has neither endpoint nor provider. */
export function connectionIdForDeployment(deploymentId: string, snapshot: ServiceFlowSnapshot): string | null {
  const deployment = snapshot.catalog.deployments.find((d) => d.id === deploymentId)
  return deployment ? hostNodeIdFor(deployment) : null
}

/** Same ConnectionMeta as palette.connectionOf for a known endpoint/provider; an "unknown connection" otherwise. */
export function connectionMetaOf(connectionId: string, snapshot: ServiceFlowSnapshot): ConnectionMeta {
  const refId = connectionId.slice(connectionId.indexOf(':') + 1)
  if (connectionId.startsWith('provider:')) {
    const provider = snapshot.catalog.providers.find((p) => p.id === refId)
    return provider ? providerConnectionMeta(provider) : { id: connectionId, kind: 'provider', label: UNKNOWN_LABEL, detail: '', tone: 'unknown', enabled: true }
  }
  const endpoint = snapshot.catalog.endpoints.find((e) => e.id === refId)
  return endpoint ? endpointConnectionMeta(endpoint) : { id: connectionId, kind: 'endpoint', label: UNKNOWN_LABEL, detail: '', tone: 'unknown', enabled: true }
}

export function connectionHealth(connectionId: string, snapshot: ServiceFlowSnapshot): ConnectionHealth {
  const refId = connectionId.slice(connectionId.indexOf(':') + 1)
  if (connectionId.startsWith('provider:')) {
    const provider = snapshot.catalog.providers.find((p) => p.id === refId)
    if (!provider) return 'unknown'
    if (!provider.enabled) return 'disabled'
    if (!provider.apiKeyConfigured) return 'offline'
    return TONE_HEALTH[toneOf(provider.enabled, provider.healthStatus)]
  }
  const endpoint = snapshot.catalog.endpoints.find((e) => e.id === refId)
  if (!endpoint) return 'unknown'
  if (!endpoint.enabled) return 'disabled'
  return TONE_HEALTH[toneOf(endpoint.enabled, endpoint.healthStatus)]
}

/* ------------------------------------------------------------------ */
/* Grouping                                                            */
/* ------------------------------------------------------------------ */

export interface ConnectionGroupModel {
  connectionId: string
  host: FlowNode
  /** Dispatch order. */
  llms: FlowNode[]
  /** Model-specific prompts of those LLMs (only the ones that exist). */
  prompts: FlowNode[]
}

/** Every host node with the LLMs that point at it through the derived `host` edges. Hosts sorted by id. */
export function connectionGroups(graph: FlowGraph): ConnectionGroupModel[] {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const members = new Map<string, FlowNode[]>()
  for (const e of graph.edges) {
    if (e.kind !== 'host') continue
    const llm = byId.get(e.from.nodeId)
    if (!llm || llm.type !== 'llm' || !isConnectionNode(byId.get(e.to.nodeId))) continue
    const list = members.get(e.to.nodeId) ?? []
    if (!list.includes(llm)) list.push(llm)
    members.set(e.to.nodeId, list)
  }
  return graph.nodes
    .filter((n) => isConnectionNode(n))
    .sort((a, b) => cmp(a.id, b.id))
    .map((host) => {
      const llms = [...(members.get(host.id) ?? [])].sort(dispatchOrder)
      const prompts = llms
        .map((llm) => byId.get(branchPromptNodeId(deploymentIdOfNode(llm))))
        .filter((p): p is FlowNode => p !== undefined)
      return { connectionId: host.id, host, llms, prompts }
    })
}

/** The connection of a host, LLM or model-specific prompt node; null for anything else or an LLM without host. */
export function connectionOfNode(graph: FlowGraph, nodeId: string): string | null {
  const node = graph.nodes.find((n) => n.id === nodeId)
  if (!node) return null
  if (isConnectionNode(node)) return node.id
  const llmId = node.type === 'llm' ? node.id : isBranchPrompt(node) ? llmNodeId(node.id.slice('prompt:'.length)) : null
  if (!llmId) return null
  const e = graph.edges.find((x) => x.kind === 'host' && x.from.nodeId === llmId)
  return e && graph.nodes.some((n) => n.id === e.to.nodeId && isConnectionNode(n)) ? e.to.nodeId : null
}

/** First node of an LLM's branch: its model-specific prompt when it exists, the LLM otherwise. */
export function headOf(graph: FlowGraph, llm: FlowNode): string {
  const prompt = branchPromptNodeId(deploymentIdOfNode(llm))
  return graph.nodes.some((n) => n.id === prompt) ? prompt : llm.id
}

/** A request (flow, route or access) edge enters this node's input. */
export function hasIncomingRequest(graph: FlowGraph, nodeId: string): boolean {
  return graph.edges.some((e) => e.to.nodeId === nodeId && e.to.portId === 'in' && REQUEST_KINDS.has(e.kind))
}

/** Branch heads of the connection that receive no request edge, in dispatch order. */
export function unroutedHeads(graph: FlowGraph, group: ConnectionGroupModel): string[] {
  return group.llms.map((llm) => headOf(graph, llm)).filter((head) => !hasIncomingRequest(graph, head))
}

/** Where a model of this connection should be fed from: a routed sibling's source, else the router, else the trunk tail. */
export function requestSourceFor(graph: FlowGraph, group: ConnectionGroupModel, snapshot?: ServiceFlowSnapshot): PortRef | null {
  for (const llm of group.llms) {
    const head = headOf(graph, llm)
    const e = graph.edges.find((x) => x.to.nodeId === head && x.to.portId === 'in' && (x.kind === 'flow' || x.kind === 'route'))
    if (e) return { nodeId: e.from.nodeId, portId: e.from.portId }
  }
  const analysis = analyzeWorkflow(graph, snapshot)
  if (analysis.router) return { nodeId: analysis.router, portId: 'out' }
  if (analysis.tail) return { nodeId: analysis.tail, portId: 'out' }
  return null
}

/** One extra column (for model-specific prompts) between connections and models once any grouped LLM has a prompt. */
export function connectionLaneOffset(graph: FlowGraph): number {
  return connectionGroups(graph).some((g) => g.prompts.length > 0) ? COLUMN_WIDTH : 0
}

/* ------------------------------------------------------------------ */
/* Projection (§5.3)                                                   */
/* ------------------------------------------------------------------ */

export function buildConnectionView(graph: FlowGraph, snapshot: ServiceFlowSnapshot): ConnectionView {
  const groups = connectionGroups(graph)
  const laneOffset = groups.some((g) => g.prompts.length > 0) ? COLUMN_WIDTH : 0
  const groupOf: Record<string, string> = {}
  for (const g of groups) for (const n of [g.host, ...g.llms, ...g.prompts]) groupOf[n.id] = g.connectionId
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))

  const items = groups.map((group) => {
    const rows = group.llms.map((llm) => {
      const prompt = byId.get(branchPromptNodeId(deploymentIdOfNode(llm)))
      return { llm, prompt, height: Math.max(nodeSize(llm).height, prompt ? nodeSize(prompt).height : 0) }
    })
    const offsets: number[] = []
    let offset = 0
    rows.forEach((row, i) => {
      if (i > 0) offset = snapUp(offset + rows[i - 1].height + ROW_GAP)
      offsets.push(offset)
    })
    const content = rows.length > 0 ? offsets[rows.length - 1] + rows[rows.length - 1].height : 0
    const anchor: Point = group.llms.length > 0
      ? { x: Math.min(...group.llms.map((n) => n.x)) - laneOffset, y: Math.min(...group.llms.map((n) => n.y)) }
      : { x: group.host.x, y: group.host.y }
    return {
      group,
      anchor,
      rows,
      offsets,
      width: group.llms.length > 0 ? COLUMN_WIDTH + laneOffset + NODE_WIDTH : NODE_WIDTH,
      height: Math.max(nodeSize(group.host).height, content),
    }
  })
  items.sort((a, b) => a.anchor.y - b.anchor.y || a.anchor.x - b.anchor.x || cmp(a.group.connectionId, b.group.connectionId))

  const obstacles = graph.nodes.filter((n) => groupOf[n.id] === undefined).map(nodeRect)
  const placed: Rect[] = []
  const positions: Record<string, Point> = {}
  const views: ConnectionGroupView[] = []
  const dimmed: string[] = []
  for (const item of items) {
    let y = item.anchor.y
    for (let i = 0; i < 500; i += 1) {
      const probe: Rect = { x: item.anchor.x, y, width: item.width, height: item.height }
      const hit = placed.find((r) => overlaps(probe, r, ROW_GAP / 2)) ?? obstacles.find((r) => overlaps(probe, r, ROW_GAP / 2))
      if (!hit) break
      y = snapUp(hit.y + hit.height + ROW_GAP)
    }
    const frame: Rect = { x: item.anchor.x, y, width: item.width, height: item.height }
    placed.push(frame)
    const g = item.group
    positions[g.host.id] = { x: item.anchor.x, y }
    item.rows.forEach((row, i) => {
      const rowY = y + item.offsets[i]
      if (row.prompt) positions[row.prompt.id] = { x: item.anchor.x + COLUMN_WIDTH, y: rowY }
      positions[row.llm.id] = { x: item.anchor.x + COLUMN_WIDTH + laneOffset, y: rowY }
    })
    const health = connectionHealth(g.connectionId, snapshot)
    if (health === 'offline' || health === 'disabled') for (const n of [...g.llms, ...g.prompts]) dimmed.push(n.id)
    views.push({
      connectionId: g.connectionId,
      kind: g.host.type === 'provider' ? 'provider' : 'endpoint',
      llmNodeIds: g.llms.map((n) => n.id),
      promptNodeIds: g.prompts.map((n) => n.id),
      health,
      frame,
    })
  }
  return { groups: views, positions, edges: projectEdges(graph, groups, groupOf), groupOf, dimmed: dimmed.sort(cmp) }
}

/** §3.1: host edges hidden, request edges into a group redrawn from the connection, plus drawn-only helper lines. */
function projectEdges(graph: FlowGraph, groups: ConnectionGroupModel[], groupOf: Record<string, string>): ViewEdge[] {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]))
  const llmOf = (id: string): string => {
    const n = byId.get(id)
    return n && isBranchPrompt(n) ? llmNodeId(n.id.slice('prompt:'.length)) : id
  }
  const sources = new Map<string, ViewEdge>()
  const drawn: ViewEdge[] = []
  const fed = new Set<string>()
  for (const e of graph.edges) {
    if (e.kind === 'host') continue
    const target = groupOf[e.to.nodeId]
    const intoModel = target !== undefined && e.to.nodeId !== target && REQUEST_KINDS.has(e.kind) && groupOf[e.from.nodeId] !== target
    if (!intoModel) {
      drawn.push({ ...e, from: { ...e.from }, to: { ...e.to }, interactive: true, detached: false })
      continue
    }
    drawn.push({ ...e, from: { nodeId: target, portId: 'out' }, to: { ...e.to }, interactive: true, detached: false })
    fed.add(llmOf(e.to.nodeId))
    const to: PortRef = { nodeId: target, portId: 'in' }
    const id = `view:${edgeId(e.from, to)}`
    if (!sources.has(id)) sources.set(id, { id, kind: e.kind, from: { ...e.from }, to, locked: true, interactive: false, detached: false })
  }
  const detached: ViewEdge[] = []
  for (const g of groups) {
    for (const llm of g.llms) {
      if (fed.has(llm.id)) continue
      const from: PortRef = { nodeId: g.connectionId, portId: 'out' }
      const to: PortRef = { nodeId: headOf(graph, llm), portId: 'in' }
      detached.push({ id: `view:${edgeId(from, to)}`, kind: 'route', from, to, locked: true, interactive: false, detached: true })
    }
  }
  return [...sources.values(), ...detached, ...drawn]
}

/* ------------------------------------------------------------------ */
/* Interaction helpers                                                 */
/* ------------------------------------------------------------------ */

/** §4.3: grouped ids expand to their whole group (never split); other ids follow the dependency rules. Sorted. */
export function connectionDragIds(graph: FlowGraph, view: ConnectionView, ids: string[], alone: boolean): string[] {
  const out = new Set<string>()
  for (const id of ids) {
    const connectionId = view.groupOf[id]
    if (connectionId !== undefined) {
      const group = view.groups.find((g) => g.connectionId === connectionId)
      out.add(connectionId)
      for (const member of [...(group?.llmNodeIds ?? []), ...(group?.promptNodeIds ?? [])]) out.add(member)
    } else if (alone) {
      out.add(id)
    } else {
      for (const dep of dependencyClosure(graph, [id])) if (view.groupOf[dep] === undefined) out.add(dep)
    }
  }
  return [...out].sort(cmp)
}

export function viewPortsOf(node: FlowNode, view: FlowViewMode): PortSpec[] {
  if (view === 'connection') {
    if (isConnectionNode(node)) return CONNECTION_PORTS
    if (node.type === 'llm') return LLM_PORTS_IN_CONNECTION_VIEW
  }
  return NODE_TYPES[node.type].ports
}

/** §5.1: card of a connection node in the connection view. */
export function describeConnectionNode(node: FlowNode, graph: FlowGraph, snapshot: ServiceFlowSnapshot): NodeView {
  const meta = connectionMetaOf(node.id, snapshot)
  const health = connectionHealth(node.id, snapshot)
  const llms = connectionGroups(graph).find((g) => g.connectionId === node.id)?.llms ?? []
  const enabled = llms.filter((n) => n.props.enabled !== false).length
  const priorities = llms.map((n) => num(n.props.priority, Number.NaN)).filter((p) => Number.isFinite(p))
  const external = node.type === 'provider'
  const summary = [llms.length > 0 ? `모델 ${llms.length}개 · 사용 ${enabled}개` : '모델을 추가하세요']
  if (priorities.length > 0) {
    const min = Math.min(...priorities)
    const max = Math.max(...priorities)
    summary.push(min === max ? `우선순위 P${min}` : `우선순위 P${min}–P${max}`)
  }
  return {
    title: meta.label,
    subtitle: `${external ? '외부' : '로컬'}${meta.detail ? ` · ${meta.detail}` : ''}`,
    typeLabel: '연결',
    category: 'infra',
    icon: NODE_TYPES[node.type].icon,
    tone: HEALTH_TONE[health],
    badges: llms.length === 0 ? [CONNECTION_HEALTH_LABEL[health], '모델 없음'] : [CONNECTION_HEALTH_LABEL[health]],
    summary,
    locked: false,
    protected: false,
    lockReason: null,
    promptScope: null,
  }
}

/** §4.2: the models of one connection for the "모델 추가" list (palette order; inactive hidden unless asked). */
export function connectionModelChoices(snapshot: ServiceFlowSnapshot, graph: FlowGraph, connectionId: string, showInactive: boolean): ConnectionModelChoices {
  const own = snapshot.catalog.deployments.filter((d) => hostNodeIdFor(d) === connectionId)
  const ordered = groupModelsByConnection(own, snapshot.catalog).flatMap((g) => g.items)
  let hiddenCount = 0
  const items = ordered.map((d) => modelItem(d, graph, snapshot)).filter((m) => {
    if (showInactive || m.onCanvas || m.availability !== 'inactive') return true
    hiddenCount += 1
    return false
  })
  return { connection: connectionMetaOf(connectionId, snapshot), items, hiddenCount }
}

/** Where a connection added without a drop point goes: below the lowest group, else next to the router / trunk tail. */
export function defaultConnectionSpot(graph: FlowGraph, snapshot: ServiceFlowSnapshot): Point {
  const view = buildConnectionView(graph, snapshot)
  if (view.groups.length > 0) {
    return {
      x: Math.min(...view.groups.map((g) => g.frame.x)),
      y: snapUp(Math.max(...view.groups.map((g) => g.frame.y + g.frame.height)) + ROW_GAP),
    }
  }
  const analysis = analyzeWorkflow(graph, snapshot)
  const sourceId = analysis.router ?? analysis.tail
  const source = sourceId ? graph.nodes.find((n) => n.id === sourceId) : undefined
  if (source) return { x: source.x + COLUMN_WIDTH, y: source.y }
  let right: FlowNode | undefined
  for (const n of graph.nodes) {
    if (n.type !== 'start' && n.type !== 'project' && n.type !== 'policy') continue
    if (!right || n.x > right.x || (n.x === right.x && n.y < right.y)) right = n
  }
  return right ? { x: right.x + COLUMN_WIDTH, y: 0 } : { x: COLUMN_WIDTH * 3, y: 0 }
}

/** §5.4: the model-centric auto layout with the LLM lane regrouped by connection (first-dispatch order). */
export function autoLayoutByConnection(graph: FlowGraph, snapshot?: ServiceFlowSnapshot): FlowGraph {
  const laid = autoLayout(graph, snapshot)
  const analysis = analyzeWorkflow(laid, snapshot)
  if (analysis.branches.length === 0) return laid
  const byId = new Map(laid.nodes.map((n) => [n.id, n]))
  const hostOf = (llmId: string): string | null => laid.edges.find((e) => e.kind === 'host' && e.from.nodeId === llmId)?.to.nodeId ?? null
  const order: string[] = []
  const members = new Map<string, typeof analysis.branches>()
  for (const branch of analysis.branches) {
    const key = hostOf(branch.llmNodeId) ?? branch.llmNodeId
    const list = members.get(key)
    if (list) list.push(branch)
    else { members.set(key, [branch]); order.push(key) }
  }
  const positions = new Map<string, Point>()
  const firstY = new Map<string, number>()
  let y = Math.min(...analysis.branches.map((b) => (byId.get(b.llmNodeId) as FlowNode).y))
  for (const key of order) {
    for (const branch of members.get(key) ?? []) {
      const llm = byId.get(branch.llmNodeId) as FlowNode
      const prompt = branch.promptNodeId ? byId.get(branch.promptNodeId) : undefined
      if (!firstY.has(key)) firstY.set(key, y)
      positions.set(llm.id, { x: llm.x, y })
      if (prompt) positions.set(prompt.id, { x: prompt.x, y })
      y = snapUp(y + Math.max(nodeSize(llm).height, prompt ? nodeSize(prompt).height : 0) + ROW_GAP)
    }
  }
  let floor = -Infinity
  for (const key of order) {
    const host = byId.get(key)
    if (!host || !isConnectionNode(host)) continue
    const hy = Math.max(firstY.get(key) ?? host.y, floor)
    positions.set(host.id, { x: host.x, y: hy })
    floor = snapUp(hy + nodeSize(host).height + ROW_GAP)
  }
  return { ...laid, nodes: laid.nodes.map((n) => { const p = positions.get(n.id); return p ? { ...n, x: p.x, y: p.y } : n }) }
}
```

테스트: §7.8의 WP-2 섹션(C1–C6, C12, C13, C15, C2b)을 덧붙인다.

**수용**: 새 테스트 통과, 기존 테스트 불변, `vue-tsc`·build 녹색. `connections.ts`가 `graphOps` 등을 import하지 않는다.

---

### WP-3 · 명령 · 간선 규칙 · 검증

#### 3-1. `types.ts`
§2.3의 최종 `FlowCommand`로 교체한다(`FlowToolbarAction`은 아직 그대로).

#### 3-2. `graphOps.ts`

(a) import 확장:
```ts
import { COLUMN_WIDTH, ROW_GAP, autoLayout, freeSpot, nodeSize, placeNewNode, placeSpliced, portAnchor, snapUp } from './layout'
import {
  autoLayoutByConnection,
  connectionGroups,
  connectionLaneOffset,
  connectionMetaOf,
  connectionOfNode,
  defaultConnectionSpot,
  hasIncomingRequest,
  headOf,
  isConnectionId,
  isConnectionNode,
  requestSourceFor,
  unroutedHeads,
  viewPortsOf,
} from './connections'
```
`import type { … }` 목록에 `FlowViewMode`를 추가한다. (`hostNodeIdFor`·`llmNodeId`·`isBranchPrompt`는 이미 import되어 있다.)

(b) 메시지 상수(기존 `MSG_CANNOT_SPLICE` 아래, 문자 그대로):
```ts
const MSG_CONN_UNKNOWN = '알 수 없는 연결입니다.'
const MSG_CONN_NO_CATALOG_MODELS = '이 연결에는 등록된 모델이 없습니다.'
const MSG_CONN_MODEL_MISMATCH = '선택한 모델 중 이 연결에 속하지 않는 모델이 있습니다.'
const MSG_CONN_SOURCE = '연결 노드에는 라우터나 처리 단계(가드레일·공통 프롬프트·캐시·생성 파라미터)의 출력에서만 간선을 이을 수 있습니다.'
const MSG_CONN_NO_MODELS = '이 연결에는 아직 모델이 없습니다. 연결 노드에서 모델을 먼저 추가하세요.'
const MSG_CONN_ALL_ROUTED = '이 연결의 모델은 이미 모두 요청 경로에 연결되어 있습니다.'
const MSG_CONN_NEEDS_ROUTER = '모델이 여러 개인 연결은 라우터에서 이어 주세요.'
const MSG_CONN_TARGET = '연결 노드의 출력은 그 연결의 모델에만 이을 수 있습니다.'
const MSG_CONN_MODEL_ROUTED = '이미 요청 경로에 연결된 모델입니다.'
const MSG_CONN_NO_SOURCE = '이 모델을 이을 요청 경로가 없습니다. 라우터나 처리 단계를 먼저 추가하세요.'
const msgOtherConnection = (label: string): string => `이 모델은 '${label}' 연결에 속합니다. 모델의 연결은 바꿀 수 없습니다. 다른 연결의 모델은 그 연결 노드에서 추가하세요.`
const STAGE_TYPES: ReadonlySet<NodeType> = new Set<NodeType>(['guardrail', 'prompt', 'params', 'cache'])
```

(c) `syncHostNodes`의 제거 조건 한 줄:
```ts
    if (!used && node.props.pinned !== true) {
```
(기존 `if (!used) {`를 바꾼다. 블록 본문은 그대로.)

(d) `nearestCompatiblePort` **아래**에 두 함수 추가(기존 `canConnect`·`nearestCompatiblePort`는 수정 금지):
```ts
/** §4.4: connection-view wiring rules; the model view is exactly canConnect. */
export function canConnectInView(graph: FlowGraph, from: PortRef, to: PortRef, snapshot: ServiceFlowSnapshot, view: FlowViewMode): ConnectCheck {
  if (view !== 'connection') return canConnect(graph, from, to, snapshot)
  const fromNode = findNode(graph, from.nodeId)
  const toNode = findNode(graph, to.nodeId)
  if (!fromNode || !toNode) return { ok: false, reason: MSG_NOT_FOUND }
  if (fromNode.id === toNode.id) return { ok: false, reason: MSG_SAME_NODE }
  if (isConnectionNode(toNode)) {
    if (from.portId !== 'out' || to.portId !== 'in') return { ok: false, reason: MSG_DIRECTION }
    const source = fromNode.type === 'router' || (STAGE_TYPES.has(fromNode.type) && !isBranchPrompt(fromNode))
    if (!source) return { ok: false, reason: MSG_CONN_SOURCE }
    const group = connectionGroups(graph).find((g) => g.connectionId === toNode.id)
    if (!group || group.llms.length === 0) return { ok: false, reason: MSG_CONN_NO_MODELS }
    const pending = unroutedHeads(graph, group)
    if (pending.length === 0) return { ok: false, reason: MSG_CONN_ALL_ROUTED }
    if (fromNode.type !== 'router' && pending.length > 1) return { ok: false, reason: MSG_CONN_NEEDS_ROUTER }
    for (const head of pending) {
      const check = canConnect(graph, from, { nodeId: head, portId: 'in' }, snapshot)
      if (!check.ok) return check
    }
    return { ok: true, replaces: [] }
  }
  if (isConnectionNode(fromNode)) {
    if (from.portId !== 'out' || to.portId !== 'in') return { ok: false, reason: MSG_DIRECTION }
    const llmId = toNode.type === 'llm' ? toNode.id : isBranchPrompt(toNode) ? llmNodeId(toNode.id.slice('prompt:'.length)) : null
    const llm = llmId ? findNode(graph, llmId) : undefined
    if (!llm) return { ok: false, reason: MSG_CONN_TARGET }
    const owner = connectionOfNode(graph, llm.id)
    if (owner !== fromNode.id) return { ok: false, reason: owner ? msgOtherConnection(connectionMetaOf(owner, snapshot).label) : MSG_CONN_TARGET }
    const head = headOf(graph, llm)
    if (hasIncomingRequest(graph, head)) return { ok: false, reason: MSG_CONN_MODEL_ROUTED }
    const group = connectionGroups(graph).find((g) => g.connectionId === fromNode.id)
    const source = group ? requestSourceFor(graph, group, snapshot) : null
    if (!source) return { ok: false, reason: MSG_CONN_NO_SOURCE }
    const check = canConnect(graph, source, { nodeId: head, portId: 'in' }, snapshot)
    return check.ok ? { ok: true, replaces: [] } : check
  }
  return canConnect(graph, from, to, snapshot)
}

/** nearestCompatiblePort for a view: view ports, view rules and display positions (the canvas passes them). */
export function nearestPortInView(
  graph: FlowGraph,
  from: PortRef,
  point: Point,
  radius: number,
  snapshot: ServiceFlowSnapshot,
  view: FlowViewMode,
  positionOf: (node: FlowNode) => Point,
): PortRef | null {
  let best: PortRef | null = null
  let bestDistance = Infinity
  for (const node of graph.nodes) {
    const at = positionOf(node)
    const placed = at.x === node.x && at.y === node.y ? node : { ...node, x: at.x, y: at.y }
    for (const port of viewPortsOf(node, view)) {
      if (port.direction !== 'in') continue
      const ref: PortRef = { nodeId: node.id, portId: port.id }
      const anchor = portAnchor(placed, port.id, 'in')
      const distance = Math.hypot(anchor.x - point.x, anchor.y - point.y)
      if (distance > radius) continue
      if (!canConnectInView(graph, from, ref, snapshot, view).ok) continue
      if (distance < bestDistance || (distance === bestDistance && best && (cmp(ref.nodeId, best.nodeId) || cmp(ref.portId, best.portId)) < 0)) {
        best = ref
        bestDistance = distance
      }
    }
  }
  return best
}
```

(e) `applyCommand`의 `removeItems` 분기 — 노드 루프를 다음으로 바꾼다(이후 코드는 그대로):
```ts
      for (const id of command.nodeIds) {
        const node = w.nodes.get(id)
        if (!node) continue
        if (node.type === 'start') { error ??= '시작 노드는 삭제할 수 없습니다.'; continue }
        const connection = command.withConnectionModels === true && isConnectionNode(node)
        if (NODE_TYPES[node.type].derived && !connection) { error ??= '자동으로 표시되는 노드라 삭제할 수 없습니다.'; continue }
        if (node.type === 'guardrail' && isProtectedGuardrail(snapshot) && command.force !== true) {
          error ??= '외부 노출 방지 정책이 적용된 가드레일입니다. 인스펙터의 [보호 해제 후 삭제]를 사용하세요.'
          continue
        }
        requested.push(id)
        // A model cannot exist outside its connection: deleting the connection takes its models along (§4.5).
        if (connection) {
          for (const e of graph.edges) {
            if (e.kind === 'host' && e.to.nodeId === id && !requested.includes(e.from.nodeId)) requested.push(e.from.nodeId)
          }
        }
      }
```

(f) `applyCommand`의 `autoLayout` 분기와 새 분기:
```ts
    case 'autoLayout':
      return ok(command.view === 'connection' ? autoLayoutByConnection(graph, snapshot) : autoLayout(graph, snapshot), true)

    case 'addConnection':
      return addConnectionCommand(graph, command, snapshot, ok, fail)

    case 'addConnectionModels':
      return addConnectionModelsCommand(graph, command, snapshot, ok, fail)

    case 'connectConnection':
      return connectConnectionCommand(graph, command, snapshot, ok, fail)
```

(g) 파일 끝(`applyTemplateCommand` 아래)에 핸들러 추가:
```ts
/* ------------------------------------------------------------------ */
/* Connection-centric commands (docs/workflow-connection-first-design.ko.md §4)                         */
/* ------------------------------------------------------------------ */

function addConnectionCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'addConnection' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const id = command.connectionId
  if (!isConnectionId(id)) return fail(MSG_CONN_UNKNOWN)
  if (findNode(graph, id)) return ok(graph, false, selectNode(id))
  const refId = id.slice(id.indexOf(':') + 1)
  const isProvider = id.startsWith('provider:')
  const known = isProvider ? snapshot.catalog.providers.some((p) => p.id === refId) : snapshot.catalog.endpoints.some((e) => e.id === refId)
  if (!known) return fail(MSG_CONN_UNKNOWN)
  if (!snapshot.catalog.deployments.some((d) => hostNodeIdFor(d) === id)) return fail(MSG_CONN_NO_CATALOG_MODELS)
  const position = command.position && Number.isFinite(command.position.x) && Number.isFinite(command.position.y) ? command.position : defaultConnectionSpot(graph, snapshot)
  const w = begin(graph)
  const node = makeNode(isProvider ? 'provider' : 'endpoint', id, refId, { pinned: true })
  node.x = position.x
  node.y = position.y
  w.nodes.set(id, node)
  return ok(toGraph(w), true, selectNode(id))
}

function addConnectionModelsCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'addConnectionModels' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const connectionId = command.connectionId
  if (!isConnectionId(connectionId)) return fail(MSG_CONN_UNKNOWN)
  const wanted = [...new Set(command.deploymentIds)]
  for (const id of wanted) {
    const deployment = deploymentOf(snapshot, id)
    if (!deployment) return fail('알 수 없는 모델입니다.')
    if (hostNodeIdFor(deployment) !== connectionId) return fail(MSG_CONN_MODEL_MISMATCH)
  }
  const selectHost = (g: FlowGraph): FlowSelection | null => (g.nodes.some((n) => n.id === connectionId) ? selectNode(connectionId) : null)
  const fresh = wanted.filter((id) => !findNode(graph, llmNodeId(id)))
  if (fresh.length === 0) return ok(graph, false, selectHost(graph))

  // Remember the group as it was: its models (positions are re-read after the adds) or the empty connection's spot.
  const before = connectionGroups(graph).find((g) => g.connectionId === connectionId)
  const memberIds = before ? before.llms.map((n) => n.id) : []
  const hostBefore = findNode(graph, connectionId)
  const pinnedSpot = hostBefore && memberIds.length === 0 ? { x: hostBefore.x, y: hostBefore.y } : null

  // Same path as the model view (identical props, wiring and router insertion: §3.4).
  let g = graph
  for (const id of fresh) {
    const r = applyCommand(g, { type: 'addNode', nodeType: 'llm', refId: id }, snapshot)
    if (r.error) return fail(r.error)
    g = r.graph
  }

  // Stored (model-view) positions: below the siblings, or where the connection already is, or at the drop point.
  const w = begin(g)
  const offset = connectionLaneOffset(g)
  const position = command.position && Number.isFinite(command.position.x) && Number.isFinite(command.position.y) ? command.position : null
  let base: Point | null = null
  if (memberIds.length > 0) {
    const members = memberIds.map((id) => w.nodes.get(id)).filter((n): n is FlowNode => !!n)
    if (members.length > 0) {
      base = { x: Math.min(...members.map((n) => n.x)), y: snapUp(Math.max(...members.map((n) => n.y + nodeSize(n).height)) + ROW_GAP) }
    }
  } else if (pinnedSpot) {
    base = { x: pinnedSpot.x + offset, y: pinnedSpot.y }
  } else if (position) {
    base = { x: position.x + offset, y: position.y }
  }
  if (base) {
    const newIds = fresh.map(llmNodeId)
    let y = base.y
    for (let index = 0; index < newIds.length; index += 1) {
      const node = w.nodes.get(newIds[index])
      if (!node) continue
      const pending = new Set(newIds.slice(index))
      const others = [...w.nodes.values()].filter((n) => !pending.has(n.id) && n.id !== connectionId)
      const spot = freeSpot(others, node, base.x, y)
      node.x = spot.x
      node.y = spot.y
      y = snapUp(spot.y + nodeSize(node).height + ROW_GAP)
    }
  }
  // A connection that had no models gets its model-view spot (right of its first model) now that it has users.
  if (memberIds.length === 0 && w.nodes.has(connectionId)) copyPositions(w, placeNewNode(toGraph(w), connectionId, snapshot))
  const result = toGraph(w)
  return ok(result, true, selectHost(result))
}

function connectConnectionCommand(
  graph: FlowGraph,
  command: Extract<FlowCommand, { type: 'connectConnection' }>,
  snapshot: ServiceFlowSnapshot,
  ok: Ok,
  fail: Fail,
): CommandResult {
  const check = canConnectInView(graph, command.from, command.to, snapshot, 'connection')
  if (!check.ok) return fail(check.reason)
  const fromNode = findNode(graph, command.from.nodeId) as FlowNode
  const toNode = findNode(graph, command.to.nodeId) as FlowNode
  let g = graph
  const connectHead = (from: PortRef, head: string): string | null => {
    const r = applyCommand(g, { type: 'connect', from, to: { nodeId: head, portId: 'in' } }, snapshot)
    if (r.error) return r.error
    g = r.graph
    return null
  }
  if (isConnectionNode(toNode)) {
    const group = connectionGroups(graph).find((x) => x.connectionId === toNode.id)
    for (const head of group ? unroutedHeads(graph, group) : []) {
      const error = connectHead(command.from, head)
      if (error) return fail(error)
    }
    return ok(g, true, selectNode(toNode.id))
  }
  if (isConnectionNode(fromNode)) {
    const group = connectionGroups(graph).find((x) => x.connectionId === fromNode.id)
    const llm = findNode(graph, toNode.type === 'llm' ? toNode.id : llmNodeId(toNode.id.slice('prompt:'.length))) as FlowNode
    const source = group ? requestSourceFor(graph, group, snapshot) : null
    if (!source) return fail(MSG_CONN_NO_SOURCE)
    const error = connectHead(source, headOf(graph, llm))
    if (error) return fail(error)
    return ok(g, true, null)
  }
  return fail(MSG_NO_RULE)
}
```
주의: `copyPositions`·`begin`·`toGraph`·`findNode`·`makeNode`·`deploymentOf`·`selectNode`·`Ok`·`Fail`·`MSG_NOT_FOUND`·`MSG_SAME_NODE`·`MSG_DIRECTION`·`MSG_NO_RULE`·`cmp`는 graphOps에 이미 있다. `FlowSelection`·`Point`·`NodeType` 타입 import는 이미 있다.

#### 3-3. `validate.ts`
`// --- masker ---` 블록이 끝난 직후(`// --- access ---` 앞)에 추가:
```ts
  // --- connections (connection-centric view: a connection placed before its models) ---
  for (const node of graph.nodes) {
    if ((node.type !== 'endpoint' && node.type !== 'provider') || node.props.pinned !== true) continue
    if (graph.edges.some((e) => e.kind === 'host' && e.to.nodeId === node.id)) continue
    add('CONNECTION_EMPTY', 'warning', '모델이 없는 연결입니다. 연결 노드에서 모델을 추가하거나 연결을 삭제하세요. 이 노드만으로는 서버 설정이 바뀌지 않습니다.', {
      nodeId: node.id,
      fix: { label: '연결 삭제', command: { type: 'removeItems', nodeIds: [node.id], edgeIds: [], withConnectionModels: true } },
    })
  }
```

테스트: §7.8의 WP-3 섹션(C7–C11).

**수용**: 새 테스트 통과(특히 C9 골든), 기존 76개 불변(특히 `flowDesignerModel` #11 삭제·#21 레이아웃·#26 canConnect), `vue-tsc`·build 녹색.

---

### WP-4 · 편집기 보기 상태 · 툴바 · 페이지(툴바)

1. `types.ts`: §2.3의 최종 `FlowToolbarAction`으로 교체.
2. `useFlowEditor.ts`(지정 위치만, §7.0 충돌 회피):
   - import: `import { isConnectionNode } from './connections'`를 `./catalog` import 줄 아래에 추가. `import type { … }` 목록에 `FlowViewMode` 추가.
   - 모듈 변수: `let lastMode: DesignerMode = 'basic'` **바로 다음 줄**에
     ```ts
     // Remembered in module memory only (never browser storage): a reload starts in the connection-centric view.
     let lastViewMode: FlowViewMode = 'connection'
     ```
   - `const restoredAt = ref<string | null>(null)` **바로 다음 줄**에 `const viewMode = ref<FlowViewMode>(lastViewMode)`.
   - `showQuickStart`를 다음으로 바꾼다:
     ```ts
     const showQuickStart = computed(() => graph.value !== null && isEmptyWorkflow(graph.value) && !graph.value.nodes.some(node => isConnectionNode(node)) && !quickStartDismissed.value && !readOnly.value)
     ```
   - `commandNotice`의 `return ''` **바로 위**에 추가:
     ```ts
     if ((command.type === 'addConnectionModels' || command.type === 'connectConnection') && routerAdded) return 'LLM이 두 개가 되어 라우터를 자동으로 추가했습니다.'
     if (command.type === 'addConnection' && added.length > 0) return '연결을 놓았습니다. 연결 노드의 [＋ 모델 추가] 또는 인스펙터에서 이 연결의 모델을 고르세요.'
     if (command.type === 'addConnectionModels') {
       const models = added.filter(node => node.type === 'llm').length
       if (models > 0) return `모델 ${models}개를 연결 뒤에 추가했습니다.`
     }
     if (command.type === 'removeItems' && command.withConnectionModels === true) {
       const remaining = new Set(after.nodes.map(node => node.id))
       const connections = before.nodes.filter(node => isConnectionNode(node) && command.nodeIds.includes(node.id) && !remaining.has(node.id)).length
       const models = before.nodes.filter(node => node.type === 'llm' && !remaining.has(node.id)).length
       if (connections > 0) return `연결 ${connections}개와 모델 ${models}개를 워크플로우에서 뺐습니다. 실행 취소(Ctrl+Z)로 되돌릴 수 있습니다.`
     }
     ```
   - `onToolbarAction`: `case 'auto-layout': handleCommand({ type: 'autoLayout' }); break`를 `case 'auto-layout': handleCommand({ type: 'autoLayout', view: viewMode.value }); break`로 바꾸고, `case 'mode-advanced': …` 다음 줄에
     ```ts
     case 'view-connection': viewMode.value = 'connection'; lastViewMode = 'connection'; break
     case 'view-model': viewMode.value = 'model'; lastViewMode = 'model'; break
     ```
   - `return { … }`의 `narrow, compact, paletteOpen, inspectorOpen, mode, canNormalize, showQuickStart,` 줄을 `narrow, compact, paletteOpen, inspectorOpen, mode, viewMode, canNormalize, showQuickStart,`로.
3. `FlowToolbar.vue`:
   - import 타입에 `FlowViewMode` 추가, props에 `viewMode?: FlowViewMode`(withDefaults 객체는 그대로, 기본값 추가 금지).
   - 기존 `<div class="fd-group fd-mode" role="group" aria-label="표시 모드">…</div>` **바로 다음**에:
     ```html
     <div class="fd-group fd-mode" role="group" aria-label="배치 보기">
       <button type="button" class="fd-btn fd-mode-btn" :class="{ 'is-active': viewMode === 'connection' }" :aria-pressed="viewMode === 'connection'" title="로컬 서버·외부 Provider 같은 연결별로 모델을 묶어 보여 줍니다." @click="fire('view-connection')">연결 중심</button>
       <button type="button" class="fd-btn fd-mode-btn" :class="{ 'is-active': viewMode !== 'connection' }" :aria-pressed="viewMode !== 'connection'" title="모델마다 실행 위치를 따로 보여 주는 기존 보기입니다." @click="fire('view-model')">모델 중심</button>
     </div>
     ```
     (보기 전환 버튼은 읽기 전용에서도 활성이다.)
4. `FlowDesignerPage.vue`:
   - 구조 분해에 `viewMode` 추가(`mode, canNormalize` 옆).
   - 툴바: `:mode="mode" :can-normalize="canNormalize"` 뒤에 ` :view-mode="viewMode"`.
   - 상태바 `<span>모드 …</span>` 다음 줄: `<span>보기 {{ viewMode === 'connection' ? '연결 중심' : '모델 중심' }}</span>`.

테스트: §7.8의 WP-4 섹션(C16).

**수용**: 툴바 토글이 상태바 표시를 바꾼다(아직 캔버스는 모델 중심 렌더), 기존 테스트 불변, `vue-tsc`·build 녹색.

---

### WP-5 · `FlowNodeCard.vue` · `FlowCanvas.vue`

#### 5-1. `FlowNodeCard.vue`
- props 타입에 `dimmed?: boolean`, `action?: string | null` 추가(defineProps 그대로, 기본값 없음).
- 루트 `:class`를 `{ 'is-selected': selected, 'is-collapsed': node.collapsed, 'is-readonly': readOnly, 'is-dimmed': dimmed === true }`로.
- `<div class="fd-ports" …>` 안, `v-for` 행들 **뒤**에:
  ```html
  <button v-if="action" type="button" class="fd-node-action" :aria-label="`${view.title}: ${action}`" @click.stop="emit('activate', node.id)">{{ action }}</button>
  ```
- CSS 추가:
  ```css
  .fd-node.is-dimmed .fd-node-body { opacity: .5; }
  .fd-node-action { position: absolute; left: 50%; top: 2px; transform: translateX(-50%); height: 20px; padding: 0 8px; border: 1px solid var(--accent-border); border-radius: 6px; background: var(--accent-dim); color: var(--accent-strong); font-size: 9.5px; font-weight: 800; white-space: nowrap; cursor: pointer; }
  .fd-node-action:hover, .fd-node-action:focus-visible { background: var(--accent-strong); color: var(--surface); outline: none; }
  ```
  (`.fd-ports`는 이미 `position: relative`. 캔버스의 `onPointerDown`은 노드 안 `button`을 무시하므로 끌기·선택이 시작되지 않는다.)

#### 5-2. `FlowCanvas.vue` (C1–C20, 모델 중심의 결과는 지금과 같아야 한다)

**C1 import**
```ts
import { canConnectInView, nearestPortInView } from './graphOps'
import { buildConnectionView, connectionDragIds, connectionIdForDeployment, describeConnectionNode, isConnectionId, isConnectionNode, viewPortsOf } from './connections'
```
기존 `import { canConnect, nearestCompatiblePort } from './graphOps'`는 위 첫 줄로 **교체**한다. `import type` 목록에 `FlowViewMode`, `ViewEdge` 추가.

**C2 props**: `mode?: DesignerMode` 아래에 `viewMode?: FlowViewMode`. `withDefaults(..., { mode: 'advanced' })`는 그대로.

**C3** `const vp = computed(…)` 아래:
```ts
/* ------------------------------ connection-centric view ------------------------------ */
const viewKind = computed<FlowViewMode>(() => (props.viewMode === 'connection' ? 'connection' : 'model'))
const connectionView = computed(() => (viewKind.value === 'connection' ? buildConnectionView(props.graph, props.snapshot) : null))
const dimmedIds = computed(() => new Set(connectionView.value?.dimmed ?? []))
const FRAME_PAD = 12
```

**C4 visibleGraph** — 첫 줄에 연결 보기 분기 추가(나머지 그대로):
```ts
const visibleGraph = computed<FlowGraph>(() => {
  // Connection view: every node is drawn (connections included in basic mode); edges are the projection (§3.1).
  const view = connectionView.value
  if (view) return { ...props.graph, nodes: props.graph.nodes, edges: view.edges }
  if (props.mode !== 'basic') return props.graph
  const nodes = props.graph.nodes.filter((n) => !HIDDEN_IN_BASIC.has(n.type))
  const edges = props.graph.edges.filter((e) => e.kind !== 'host')
  return { ...props.graph, nodes, edges }
})
```

**C5 DragState**: `interface DragState { ids: Set<string>; dx: number; dy: number; snap: boolean; anchorId: string }`

**C6** `dragPosition`은 그대로 두고, `effective`·`offsetKey`를 바꾸고 두 함수를 추가한다:
```ts
/** Display position before any drag: derived in the connection view (§5.3), the stored position otherwise. */
function basePos(node: FlowNode): Point {
  return connectionView.value?.positions[node.id] ?? { x: node.x, y: node.y }
}

/** Connection view: one snapped delta for the whole dragged set, taken from the pressed node (§4.3). */
function groupDelta(drag: DragState): Point {
  const anchor = nodeMap.value.get(drag.anchorId)
  const base = anchor ? basePos(anchor) : { x: 0, y: 0 }
  const x = base.x + drag.dx
  const y = base.y + drag.dy
  const p = drag.snap ? { x: snapToGrid(x), y: snapToGrid(y) } : { x: Math.round(x), y: Math.round(y) }
  return { x: p.x - base.x, y: p.y - base.y }
}

function effective(node: FlowNode): FlowNode {
  const drag = dragState.value
  let p = basePos(node)
  if (drag && drag.ids.has(node.id)) {
    if (connectionView.value) {
      const d = groupDelta(drag)
      p = { x: p.x + d.x, y: p.y + d.y }
    } else {
      p = dragPosition(node, drag)
    }
  }
  return p.x === node.x && p.y === node.y ? node : { ...node, x: p.x, y: p.y }
}

function offsetKey(node: FlowNode): string {
  const p = effective(node)
  return `${p.x},${p.y}`
}
```
`cardStyle`는 그대로(`effective` 사용).

**C7 views** — 루프 안 `describeNode(...)` 줄을:
```ts
    const view = viewKind.value === 'connection' && isConnectionNode(node) ? describeConnectionNode(node, props.graph, props.snapshot) : describeNode(node, props.graph, props.snapshot)
```

**C8 EdgeView / edgeViews**
```ts
interface EdgeView { edge: FlowEdge; d: string; mid: Point; selected: boolean; dim: boolean; drop: boolean; interactive: boolean; detached: boolean }
const edgeViews = computed<EdgeView[]>(() => {
  const result: EdgeView[] = []
  for (const edge of visibleGraph.value.edges as Array<FlowEdge & Partial<Pick<ViewEdge, 'interactive' | 'detached'>>>) {
    const from = nodeMap.value.get(edge.from.nodeId)
    const to = nodeMap.value.get(edge.to.nodeId)
    if (!from || !to) continue
    const a = portAnchor(effective(from), edge.from.portId, 'out')
    const b = portAnchor(effective(to), edge.to.portId, 'in')
    const interactive = edge.interactive !== false
    const detached = edge.detached === true
    result.push({
      edge,
      d: bezierPath(a, b),
      mid: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 },
      selected: interactive && selectedEdges.value.has(edge.id),
      dim: (edge.kind === 'route' && to.type === 'llm' && to.props.enabled === false) || dimmedIds.value.has(edge.to.nodeId),
      drop: dropEdgeId.value === edge.id,
      interactive,
      detached,
    })
  }
  return result
})
```

**C9 wirePath**: `portAnchor(fromNode, …)` → `portAnchor(effective(fromNode), …)`, `portAnchor(targetNode, …)` → `portAnchor(effective(targetNode), …)`.

**C10 groupFrames**(`edgeViews` 아래):
```ts
const groupFrames = computed(() => {
  const view = connectionView.value
  if (!view) return []
  const drag = dragState.value
  return view.groups.map((g) => {
    const d = drag && drag.ids.has(g.connectionId) ? groupDelta(drag) : { x: 0, y: 0 }
    return { id: g.connectionId, health: g.health, x: g.frame.x - FRAME_PAD + d.x, y: g.frame.y - FRAME_PAD + d.y, width: g.frame.width + FRAME_PAD * 2, height: g.frame.height + FRAME_PAD * 2 }
  })
})
```

**C11 표시 기하**(`groupFrames` 아래):
```ts
/** Geometry of what is drawn: visible nodes at their display positions (fit, marquee, drop hit testing). */
const displayGraph = computed<FlowGraph>(() => ({ ...visibleGraph.value, nodes: visibleGraph.value.nodes.map(effective) }))
/** Only real (interactive) edges can take a palette drop. */
const spliceGraph = computed<FlowGraph>(() => ({
  ...displayGraph.value,
  edges: displayGraph.value.edges.filter((e) => (e as Partial<ViewEdge>).interactive !== false),
}))
```
- `fit()`: `graphBounds(visibleGraph.value)` → `graphBounds(displayGraph.value)`.
- `centerOn()`: `const rect = nodeRect(node)` → `const rect = nodeRect(effective(node))`.
- 마키(`onPointerUp`): `nodesInRect(visibleGraph.value, …)` → `nodesInRect(displayGraph.value, …)`.
- `onDragOver`: `nearestEdge(visibleGraph.value, …)` → `nearestEdge(spliceGraph.value, …)`.

**C12 beginNodePress** — `dragIds = …` 한 줄을:
```ts
  // Dependent nodes (masker, model prompt, hosts, policy) follow their owner; Ctrl/Cmd moves the selection alone.
  // Connection view: a connection, its models and their prompts always move as one group (§4.3).
  const alone = event.ctrlKey || event.metaKey
  dragIds = connectionView.value ? connectionDragIds(props.graph, connectionView.value, base, alone) : alone ? base : dependencyClosure(props.graph, base)
```

**C13 beginWire** — 호환 포트 계산 루프를:
```ts
  for (const node of visibleGraph.value.nodes) {
    for (const port of viewPortsOf(node, viewKind.value)) {
      if (port.direction !== 'in') continue
      if (canConnectInView(props.graph, from, { nodeId: node.id, portId: port.id }, props.snapshot, viewKind.value).ok) {
        const list = compat.get(node.id) ?? []
        list.push(`in:${port.id}`)
        compat.set(node.id, list)
      }
    }
  }
```

**C14 flush**
- node 분기: `dragState.value = { ids: new Set(dragIds), dx: dxScreen / zoom, dy: dyScreen / zoom, snap: !lastEvent.altKey, anchorId: pressNodeId ?? '' }`
- wire 분기: `const target = nearestPortInView(props.graph, w.from, world, 24 / vp.value.zoom, props.snapshot, viewKind.value, (n) => effective(n))`

**C15 onPointerUp**
- node 분기의 `if (drag && didMove) { … }` 본문을:
  ```ts
      const delta = connectionView.value ? groupDelta(drag) : null
      const moves: Array<{ nodeId: string; x: number; y: number }> = []
      for (const id of drag.ids) {
        const node = nodeMap.value.get(id)
        if (!node) continue
        const p = delta ? { x: node.x + delta.x, y: node.y + delta.y } : dragPosition(node, drag)
        if (p.x !== node.x || p.y !== node.y) moves.push({ nodeId: id, x: p.x, y: p.y })
      }
      if (moves.length > 0 && !props.readOnly) emit('command', { type: 'moveNodes', moves })
  ```
- wire 분기: `emit('command', { type: 'connect', from, to: target })` → `emit('command', wireCommand(from, target))`. 실패 안내 두 줄
  ```ts
        const check = canConnect(props.graph, from, { nodeId, portId }, props.snapshot)
        if (!check.ok) announce(check.reason)
  ```
  을 다음으로 바꾼다(거부 사유가 화면 읽기 영역에만 가지 않고 페이지 메시지 줄에도 보이게, D9):
  ```ts
        const check = canConnectInView(props.graph, from, { nodeId, portId }, props.snapshot, viewKind.value)
        if (!check.ok) {
          announce(check.reason)
          // Connection view: a refused connection/model wire is also explained in the page message bar (D9).
          if (viewKind.value === 'connection' && (isConnectionId(from.nodeId) || isConnectionId(nodeId))) emit('command', { type: 'connectConnection', from, to: { nodeId, portId } })
        }
  ```
  (실패한 명령은 그래프·이력을 바꾸지 않고 `useFlowEditor`가 `result.error`를 메시지 줄에 띄운다.)
- 새 함수(`onPointerUp` **위**, 다른 함수 사이):
  ```ts
  function wireCommand(from: PortRef, to: PortRef): FlowCommand {
    if (viewKind.value === 'connection' && (isConnectionId(from.nodeId) || isConnectionId(to.nodeId))) return { type: 'connectConnection', from, to }
    return { type: 'connect', from, to }
  }
  ```
  (`moved` 스냅샷 규칙: 새 코드에서 `moved`를 읽지 않는다.)

**C16 화살표 키** — `const ids = …` 한 줄을:
```ts
    const ids = connectionView.value
      ? connectionDragIds(props.graph, connectionView.value, props.selection.nodeIds, mod)
      : mod ? props.selection.nodeIds : dependencyClosure(props.graph, props.selection.nodeIds)
```

**C17 Delete**:
```ts
    emit('command', { type: 'removeItems', nodeIds: [...props.selection.nodeIds], edgeIds: [...props.selection.edgeIds], withConnectionModels: viewKind.value === 'connection' })
```

**C18 onDrop** 전체를:
```ts
function onDrop(event: DragEvent) {
  if (props.readOnly || !hasPaletteData(event)) return
  event.preventDefault()
  dropEdgeId.value = null
  let parsed: unknown
  try { parsed = JSON.parse(event.dataTransfer?.getData(DND_MIME) ?? '') } catch { return }
  if (!parsed || typeof parsed !== 'object') return
  const { nodeType, refId } = parsed as { nodeType?: unknown; refId?: unknown }
  if (refId !== null && refId !== undefined && (typeof refId !== 'string' || refId.length > 80)) return
  const world = screenToWorld(localPoint(event), vp.value)
  const snapped = { x: snapToGrid(world.x), y: snapToGrid(world.y) }
  // Connection view: a palette connection header becomes a (pinned) connection node at the drop point.
  if (nodeType === 'connection') {
    if (viewKind.value === 'connection' && typeof refId === 'string' && isConnectionId(refId)) emit('command', { type: 'addConnection', connectionId: refId, position: snapped })
    return
  }
  if (typeof nodeType !== 'string' || !ADDABLE.includes(nodeType as AddableNodeType)) return
  const type = nodeType as AddableNodeType
  const edgeId = nearestEdge(spliceGraph.value, world, DROP_RADIUS / vp.value.zoom, SPLICE_KINDS)
  if (edgeId && insertableTypesForEdge(props.graph, edgeId, props.snapshot).includes(type)) {
    spliceRequested = true
    emit('command', { type: 'addNode', nodeType: type, refId: typeof refId === 'string' ? refId : null, spliceEdgeId: edgeId })
    return
  }
  // Connection view: a model lands behind its own connection (its group), never as a loose LLM.
  if (viewKind.value === 'connection' && type === 'llm' && typeof refId === 'string') {
    const connectionId = connectionIdForDeployment(refId, props.snapshot)
    if (connectionId) {
      emit('command', { type: 'addConnectionModels', connectionId, deploymentIds: [refId], position: snapped })
      return
    }
  }
  emit('command', {
    type: 'addNode',
    nodeType: type,
    refId: typeof refId === 'string' ? refId : null,
    position: snapped,
    autoWire: !event.altKey,
  })
}
```

**C19 템플릿**
- `<svg class="fd-edges" …>` 바로 안쪽 첫 자식으로:
  ```html
  <g v-if="groupFrames.length > 0" class="fd-group-frames">
    <rect v-for="frame in groupFrames" :key="frame.id" class="fd-group-frame" :class="`is-${frame.health}`" :x="frame.x" :y="frame.y" :width="frame.width" :height="frame.height" rx="16" ry="16" />
  </g>
  ```
- 간선 `<g v-for="item in edgeViews" …>`:
  - `:class`에 `'is-decor': !item.interactive, 'is-detached': item.detached` 추가.
  - `@pointerenter="hoverEdgeId = item.edge.id"` → `@pointerenter="item.interactive && (hoverEdgeId = item.edge.id)"`.
  - `<path class="fd-edge-hit" …>`에 `v-if="item.interactive"`.
- 노드 카드:
  ```html
  <FlowNodeCard
    v-for="node in visibleGraph.nodes"
    :key="node.id"
    v-memo="[node, views.get(node.id), selectedNodes.has(node.id), nodeChanges[node.id] ?? null, counts[node.id], offsetKey(node), highlightKey(node.id), readOnly, viewKind, dimmedIds.has(node.id)]"
    :style="cardStyle(node)"
    :node="node"
    :view="views.get(node.id)!"
    :ports="viewPortsOf(node, viewKind)"
    :selected="selectedNodes.has(node.id)"
    :change="nodeChanges[node.id] ?? null"
    :counts="counts[node.id] ?? ZERO_COUNTS"
    :highlight-port-ids="highlightOf(node.id)"
    :read-only="readOnly"
    :dimmed="dimmedIds.has(node.id)"
    :action="nodeAction(node)"
    @toggle-collapse="onToggleCollapse"
    @activate="onActivate"
  />
  ```
- 스크립트에 함수 추가(노드 카드 이벤트 구역):
  ```ts
  function nodeAction(node: FlowNode): string | null {
    return connectionView.value && isConnectionNode(node) && !props.readOnly && !node.collapsed ? '＋ 모델 추가' : null
  }
  ```

**C20 CSS**: §5.5 블록을 `.fd-edge.is-dim` 규칙 아래에 추가.

#### 5-3. `FlowDesignerPage.vue`
캔버스 태그의 `:mode="mode"` 뒤에 ` :view-mode="viewMode"`.

테스트: §7.8의 WP-5 섹션(C17).

**수용**: 기존 UI·어댑터 소스 검사 전부 통과(특히 FlowCanvas 문자열 규칙, `onPointerUp` `moved` 검사), 새 테스트 통과, `vue-tsc`·build 녹색. 가능하면 `npm run dev`로 §7.7 수동 1–6 확인.

---

### WP-6 · 모델 추가 패널 · 팔레트 · 인스펙터

#### 6-1. `frontend/src/flow-designer/FlowConnectionModels.vue` (신규, 문자 그대로)
```vue
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { connectionModelChoices } from './connections'
import { shortModelName } from './palette'
import type { FlowCommand, FlowGraph, HealthTone, ServiceFlowSnapshot } from './types'

const props = defineProps<{ snapshot: ServiceFlowSnapshot; graph: FlowGraph; connectionId: string; readOnly: boolean }>()
const emit = defineEmits<{ command: [FlowCommand] }>()

const TONE_LABEL: Record<HealthTone, string> = { healthy: '정상', warning: '주의', error: '오류', muted: '비활성', unknown: '미확인' }

// In-memory only (never browser storage).
const showInactive = ref(false)
const picked = ref<string[]>([])
watch(() => props.connectionId, () => { picked.value = []; showInactive.value = false })

const choices = computed(() => connectionModelChoices(props.snapshot, props.graph, props.connectionId, showInactive.value))
const pickable = computed(() => new Set(choices.value.items.filter((item) => !item.onCanvas).map((item) => item.deploymentId)))
const selected = computed(() => picked.value.filter((id) => pickable.value.has(id)))

function onPick(id: string, event: Event) {
  const on = (event.target as HTMLInputElement).checked
  picked.value = on ? (picked.value.includes(id) ? picked.value : [...picked.value, id]) : picked.value.filter((x) => x !== id)
}

function add() {
  if (props.readOnly || selected.value.length === 0) return
  emit('command', { type: 'addConnectionModels', connectionId: props.connectionId, deploymentIds: [...selected.value], position: null })
  picked.value = []
}
</script>

<template>
  <section class="fd-cm" aria-label="이 연결에 모델 추가">
    <h3 class="fd-cm-title">모델 추가 · {{ choices.connection.label }}</h3>
    <p v-if="choices.items.length === 0" class="fd-cm-empty">이 연결에서 추가할 수 있는 모델이 없습니다.</p>
    <label
      v-for="item in choices.items"
      :key="item.deploymentId"
      class="fd-cm-item"
      :class="{ 'is-off': item.availability === 'inactive' }"
      :title="item.displayName"
    >
      <input
        type="checkbox"
        :checked="item.onCanvas || picked.includes(item.deploymentId)"
        :disabled="readOnly || item.onCanvas"
        :aria-label="`모델 ${item.displayName}`"
        @change="onPick(item.deploymentId, $event)"
      />
      <span class="fd-cm-name">{{ shortModelName(item.displayName, item.providerModelId) }}</span>
      <small class="fd-cm-chip">{{ item.onCanvas ? '워크플로우에 있음' : item.availability === 'inactive' ? item.reason : TONE_LABEL[item.tone] }}</small>
    </label>
    <button v-if="choices.hiddenCount > 0" type="button" class="fd-cm-link" @click="showInactive = true">중지·미로드 {{ choices.hiddenCount }}개 표시</button>
    <button v-else-if="showInactive" type="button" class="fd-cm-link" @click="showInactive = false">중지·미로드 숨기기</button>
    <button type="button" class="primary-button fd-cm-add" :disabled="readOnly || selected.length === 0" @click="add">선택한 모델 추가 ({{ selected.length }})</button>
  </section>
</template>

<style scoped>
.fd-cm { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-2); }
.fd-cm-title { margin: 0 0 2px; color: var(--muted); font-size: 10px; font-weight: 800; overflow-wrap: anywhere; }
.fd-cm-empty { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.5; }
.fd-cm-item { display: flex; align-items: center; gap: 8px; min-height: 30px; padding: 0 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); font-size: 11px; cursor: pointer; }
.fd-cm-item:hover { border-color: var(--accent-border); }
.fd-cm-item.is-off { opacity: .6; }
.fd-cm-item input[type='checkbox'] { flex: none; width: 16px; height: 16px; min-height: 16px; padding: 0; }
.fd-cm-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 700; }
.fd-cm-chip { flex: none; color: var(--muted); font-size: 9px; white-space: nowrap; }
.fd-cm-link { justify-self: start; padding: 2px 0; border: 0; background: transparent; color: var(--accent-strong); font-size: 10px; font-weight: 700; cursor: pointer; }
.fd-cm-link:hover { text-decoration: underline; }
.fd-cm-add { min-height: 32px; }
.fd-cm-add:disabled { cursor: not-allowed; opacity: .5; }
</style>
```

#### 6-2. `FlowPalette.vue`
- import(기존 palette import 줄은 그대로 두고 **새 줄**로): `import { connectionIdForDeployment } from './connections'`. 타입 import에 `FlowViewMode` 추가.
- props 타입에 `viewMode?: FlowViewMode`(withDefaults 객체 그대로).
- 스크립트 추가:
  ```ts
  const connectionFirst = computed(() => props.viewMode === 'connection')

  function connectionDraggable(conn: PaletteConnectionGroup): boolean {
    return connectionFirst.value && conn.addable && !conn.onCanvas && !props.readOnly
  }

  function clickConnection(conn: PaletteConnectionGroup) {
    if (conn.onCanvas) { emit('select-node', conn.id); return }
    if (props.readOnly || !conn.addable) return
    emit('command', { type: 'addConnection', connectionId: conn.id, position: null })
  }
  ```
- `clickModel`을 다음으로 바꾼다:
  ```ts
  function clickModel(item: PaletteModelItem) {
    if (item.onCanvas) { emit('select-node', llmNodeId(item.deploymentId)); return }
    if (props.readOnly) return
    const connectionId = connectionFirst.value ? connectionIdForDeployment(item.deploymentId, props.snapshot) : null
    if (connectionId) emit('command', { type: 'addConnectionModels', connectionId, deploymentIds: [item.deploymentId], position: null })
    else emit('command', { type: 'addNode', nodeType: 'llm', refId: item.deploymentId, position: null })
  }
  ```
- `dragStart` 시그니처: `nodeType: AddableNodeType | 'connection'`.
- 템플릿: `<div v-for="conn in group.connections" …>` 안의 머리글 버튼을 행으로 감싸고 속성·버튼을 추가한다(`class="fd-conn-head"` 바로 다음 속성은 `:aria-expanded`):
  ```html
  <div class="fd-conn-row">
    <button
      type="button"
      class="fd-conn-head"
      :aria-expanded="connOpen(group.id, conn)"
      :title="connTitle(conn)"
      :draggable="connectionDraggable(conn)"
      @click="toggleConn(group.id, conn)"
      @dragstart="dragStart($event, 'connection', conn.id)"
    >
      … (안쪽 span들은 그대로) …
    </button>
    <button
      v-if="connectionFirst && conn.addable"
      type="button"
      class="fd-conn-add"
      :disabled="readOnly && !conn.onCanvas"
      :title="conn.onCanvas ? '워크플로우의 연결 노드를 선택합니다.' : '연결을 워크플로우에 놓습니다. 끌어 놓아도 됩니다.'"
      :aria-label="`${conn.label} ${conn.onCanvas ? '연결 선택' : '연결을 워크플로우에 추가'}`"
      @click="clickConnection(conn)"
    >{{ conn.onCanvas ? '선택' : '＋' }}</button>
  </div>
  ```
  (주의: 기존 머리글은 한 줄 태그다. 줄바꿈 형식으로 바꿔도 되지만 `class="fd-conn-head"` 다음 속성은 반드시 `:aria-expanded`이고, 그 사이에 `>` 문자가 없어야 한다.)
- 각 LLM 그룹(`group.id === 'llm-local' || group.id === 'llm-external'`) 본문 맨 위에:
  ```html
  <p v-if="connectionFirst && (group.id === 'llm-local' || group.id === 'llm-external')" class="fd-empty">연결 머리글을 캔버스로 끌어 놓거나 ＋를 누른 뒤, 연결 노드에서 모델을 고르세요.</p>
  ```
- CSS 추가:
  ```css
  .fd-conn-row { display: flex; align-items: stretch; gap: 6px; }
  .fd-conn-row .fd-conn-head { flex: 1; min-width: 0; }
  .fd-conn-head[draggable='true'] { cursor: grab; }
  .fd-conn-add { flex: none; min-width: 34px; padding: 0 8px; border: 1px solid var(--accent-border); border-radius: 8px; background: var(--accent-dim); color: var(--accent-strong); font-size: 10px; font-weight: 800; cursor: pointer; }
  .fd-conn-add:disabled { cursor: not-allowed; opacity: .5; }
  ```

#### 6-3. `FlowInspector.vue`
- import: `import FlowConnectionModels from './FlowConnectionModels.vue'`, `import { connectionGroups, connectionOfNode, describeConnectionNode, isConnectionNode } from './connections'`. `./catalog` import 목록에 `hostNodeIdFor` 추가. 타입 import에 `FlowViewMode` 추가.
- props에 `viewMode?: FlowViewMode`(withDefaults 객체 그대로).
- 스크립트:
  ```ts
  const connectionFirst = computed(() => props.viewMode === 'connection')
  /** Connection view + a single connection (host) node selected. */
  const connectionPanel = computed(() => connectionFirst.value && !!node.value && isConnectionNode(node.value))
  const connectionMembers = computed(() => {
    if (!connectionPanel.value || !node.value) return []
    const group = connectionGroups(props.graph).find((g) => g.connectionId === node.value!.id)
    return (group?.llms ?? []).map((n) => ({
      id: n.id,
      title: describeNode(n, props.graph, props.snapshot).title,
      summary: `P${n.props.priority ?? '-'} · W${n.props.weight ?? '-'}${n.props.enabled === false ? ' · 중지' : ''}`,
    }))
  })
  /** Connection view: the model-replace select only offers models of the same connection (D9). */
  const currentHost = computed(() => (connectionFirst.value && node.value && node.value.type === 'llm' ? connectionOfNode(props.graph, node.value.id) : null))
  const edgeIntoModel = computed(() => {
    const e = edge.value
    if (!connectionFirst.value || !e || (e.kind !== 'route' && e.kind !== 'flow' && e.kind !== 'access')) return false
    return connectionOfNode(props.graph, e.to.nodeId) !== null
  })

  function pickMember(id: string) {
    emit('update:selection', { nodeIds: [id], edgeIds: [] })
    emit('focus-node', id)
  }

  function removeConnection() {
    if (!node.value || props.readOnly) return
    emit('command', { type: 'removeItems', nodeIds: [node.value.id], edgeIds: [], withConnectionModels: true })
  }
  ```
- `view` computed를:
  ```ts
  const view = computed(() => (node.value ? (connectionPanel.value ? describeConnectionNode(node.value, props.graph, props.snapshot) : describeNode(node.value, props.graph, props.snapshot)) : null))
  ```
- `modelOptions`의 `const free = …` 줄을:
  ```ts
  const free = catalog.deployments.filter((d) => !takenDeploymentIds.value.has(d.id) && (currentHost.value === null || hostNodeIdFor(d) === currentHost.value))
  ```
- `removeSelection`의 emit을 `{ type: 'removeItems', nodeIds: [...props.selection.nodeIds], edgeIds: [...props.selection.edgeIds], withConnectionModels: connectionFirst.value }`로.
- 템플릿(단일 노드 패널):
  - 사실 표 `</dl>` 다음에:
    ```html
    <template v-if="connectionPanel">
      <p v-if="view.tone === 'error' || view.tone === 'muted'" class="fd-note fd-note--warning">이 연결은 지금 요청을 처리할 수 없어 아래 모델이 흐리게 표시됩니다.</p>
      <section class="fd-group">
        <h3 class="fd-subheading">이 연결의 모델 {{ connectionMembers.length }}개</h3>
        <p v-if="connectionMembers.length === 0" class="fd-empty">아직 모델이 없습니다. 아래에서 모델을 고르세요.</p>
        <ul v-else class="fd-issue-list">
          <li v-for="member in connectionMembers" :key="member.id">
            <button type="button" class="fd-issue" @click="pickMember(member.id)">
              <span>{{ member.title }}</span>
              <span class="fd-issue-code">{{ member.summary }}</span>
            </button>
          </li>
        </ul>
      </section>
      <FlowConnectionModels :snapshot="snapshot" :graph="graph" :connection-id="node.id" :read-only="readOnly" @command="emit('command', $event)" />
    </template>
    ```
  - `model-replace` 분기의 `</select>` 다음에:
    ```html
    <p v-if="connectionFirst" class="fd-help">연결 중심 보기에서는 같은 연결의 모델로만 바꿀 수 있습니다. 다른 연결의 모델은 그 연결 노드에서 추가하세요.</p>
    ```
  - 마지막 `fd-actions`의 일반 [삭제] 버튼을 `v-if="!connectionPanel"`로 감싸고, 그 앞에:
    ```html
    <button v-if="connectionPanel" type="button" class="secondary-button fd-small-button fd-danger" :disabled="readOnly" @click="removeConnection">연결 삭제{{ connectionMembers.length > 0 ? ` (모델 ${connectionMembers.length}개 포함)` : '' }}</button>
    ```
- 템플릿(간선 패널): `<p class="fd-text">{{ edgeInfo.meaning }}</p>` 다음에
  ```html
  <p v-if="edgeIntoModel" class="fd-note">연결 중심 보기에서는 연결 노드를 거쳐 그려지지만, 실제 간선은 아래의 시작 → 끝입니다.</p>
  ```

#### 6-4. `FlowDesignerPage.vue`
팔레트·인스펙터 태그의 `:mode="mode"` 뒤에 각각 ` :view-mode="viewMode"`. (최종 `:view-mode="viewMode"` 4개: 툴바·팔레트·캔버스·인스펙터.)

테스트: §7.8의 WP-6 섹션(C18).

**수용**: 새·기존 테스트 통과, `vue-tsc`·build 녹색, §7.7 수동 점검 1–12.

---

### WP-7 · 전체 검증 · 보고

```bash
cd /c/project/aiconnect-connection-first/frontend
npx vue-tsc --noEmit
npm run build
npm run test:docs && npm run test:quota && npm run test:topology && npm run test:project-security && npm run test:runtime-status && npm run test:date-time && npm run test:playground-response && npm run test:external-model-deletion && npm run test:external-capabilities && npm run test:beta-operator && npm run test:totp-hierarchy && npm run test:project-governance && npm run test:flow-designer && npm run test:services-page && npm run test:registration-modal
git -C /c/project/aiconnect-connection-first status --short   # 바뀐 파일이 §7.1 표와 일치하는지
```
보고서에는: 바뀐 파일 목록, 테스트 수(기존 76 + 신규), 수동 점검 결과, 설계와 달라진 점(있으면 이유).

### 7.7 수동 점검 (`npm run dev` + 백엔드, v2 §12.5와 같은 실행 환경)

1. 기본 보기가 연결 중심이고 상태바에 '보기 연결 중심'. [모델 중심] ↔ [연결 중심] 전환 시 '배치 미저장'이 켜지지 않는다.
2. 기존 서비스: 라우터 → [bc250] → 모델들, 라우터 → [Gemini] → 모델들. 점선 실행 위치 노드가 없다. 연결 카드에 상태 배지 1개.
3. 팔레트 bc250 머리글을 빈 곳에 끌어 놓기 → 연결 노드 선택 + 인스펙터 '모델 추가 · bc250'. 미로드 모델은 숨김, "중지·미로드 N개 표시"로 보임. 2개 추가 → 연결 뒤에 붙고 라우터 자동 삽입 알림. 연결이 놓은 자리에서 움직이지 않는다.
4. 연결 끌기 → 모델·모델별 프롬프트·틀이 함께 움직인다. 모델을 끌어도 그룹 전체가 움직인다. 화면 배율(콘솔 CSS zoom)을 바꿔도 포인터와 노드가 어긋나지 않는다.
5. 연결→모델 실선 선택·삭제 → 점선 + 오류 배지. 연결 출력 → 그 모델로 끌기 = 복구. Gemini 출력 → bc250 모델 = 페이지 메시지 줄에 거부 사유.
6. 연결→모델 실선의 ＋ → 모델별 시스템 프롬프트가 연결과 모델 사이에 생긴다.
7. 오프라인/비활성 연결(엔드포인트 비활성 또는 API 키 미설정 Provider)의 모델이 흐리고 틀이 점선.
8. 연결 선택 + Delete → 연결과 모델 삭제, 알림의 개수 확인, Ctrl+Z 복구. 인스펙터 [연결 삭제 (모델 N개 포함)]도 같다.
9. 빈 연결만 놓은 상태: 경고 `CONNECTION_EMPTY` + [연결 삭제] 수정 버튼, 변경 검토 0건.
10. [자동 정렬](연결 중심) → 연결별로 모델이 모여 정렬. [배치 저장] 후 새로고침 → 같은 화면.
11. 같은 Target 구성을 모델 중심에서 만든 것과 변경 검토 목록이 같다. 적용 → 서비스 화면에서 Target 확인.
12. 기본 모드에서도 연결 노드가 보이고, 모델 중심 + 기본 모드에서는 지금처럼 실행 위치가 숨는다. 빠른 시작 템플릿 결과가 연결별로 묶여 보인다. 빈 초안에 연결을 놓으면 빠른 시작 오버레이가 닫힌다.

### 7.8 테스트 파일 `frontend/tests/flowDesignerConnections.test.mjs`

WP-1에 **머리·픽스처·C0**, 이후 WP마다 표시된 섹션을 파일 끝에 덧붙인다(WP-2부터 `conn` import 줄도 머리에 추가). 기대 좌표는 기준 커밋의 `decompile`/`autoLayout`으로 확인한 값이다(라우터 x=600, LLM 칸 x=900, host 칸 x=1200, 행 간격 160). 테스트가 실패하면 **기대값을 고치지 말고** 구현을 §5.3·§7 코드와 대조한다.

```js
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { loadTypeScript } from './loadTypeScript.mjs'

// Connection-centric view (docs/workflow-connection-first-design.ko.md).
const catalog = await loadTypeScript('../src/flow-designer/catalog.ts', import.meta.url)
const layout = await loadTypeScript('../src/flow-designer/layout.ts', import.meta.url)
const graphOps = await loadTypeScript('../src/flow-designer/graphOps.ts', import.meta.url)
const palette = await loadTypeScript('../src/flow-designer/palette.ts', import.meta.url)
const { decompile, syncGraphToConfig } = await loadTypeScript('../src/flow-designer/decompile.ts', import.meta.url)
const { compile } = await loadTypeScript('../src/flow-designer/compile.ts', import.meta.url)
const { validateGraph } = await loadTypeScript('../src/flow-designer/validate.ts', import.meta.url)
const diff = await loadTypeScript('../src/flow-designer/diff.ts', import.meta.url)
// WP-2부터 추가: const conn = await loadTypeScript('../src/flow-designer/connections.ts', import.meta.url)

const read = (name) => readFileSync(new URL(`../src/flow-designer/${name}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

/* ---------------- fixtures ---------------- */
const { DEFAULT_CONFIG } = catalog
const dep = (id, extra = {}) => ({ id, displayName: `Model ${id.toUpperCase()}`, providerModelId: `model-${id}`, runtimeEndpointId: 'ep1', externalProviderId: null, external: false, enabled: true, loaded: true, healthStatus: 'HEALTHY', maxConcurrency: 4, contextLength: 8192, ...extra })
const ext = (id, provider, extra = {}) => dep(id, { runtimeEndpointId: null, externalProviderId: provider, external: true, ...extra })
const target = (id, deploymentId, extra = {}) => ({ key: `target:${id}`, id, deploymentId, priority: 1, weight: 100, degraded: false, enabled: true, maxConcurrencyOverride: null, followModelChanges: true, systemPromptTemplate: null, ...extra })
/** bc250: A P1·W100, B P3·W50 / GOOGLE Gemini: g1 P1, g2 P4 (the user's example). */
const TARGETS = () => [
  target('t1', 'a', { priority: 1 }),
  target('t2', 'b', { priority: 3, weight: 50 }),
  target('t3', 'g1', { priority: 1, followModelChanges: false }),
  target('t4', 'g2', { priority: 4, followModelChanges: false }),
]
function snapshot(targets = TARGETS(), mutate = null) {
  const s = {
    organizationId: 'org1', serviceId: 'svc1', promptVersion: 1, accessComplete: true, loadedAt: '2026-01-01T00:00:00.000Z', unavailable: [], dataProtection: null, features: { targetPrompt: true },
    config: { service: { ...DEFAULT_CONFIG.service, serviceKey: 'text-pro', displayName: 'Text Pro', failoverPolicy: 'COMPATIBLE' }, scheduling: { ...DEFAULT_CONFIG.scheduling }, targets, accessProjectIds: ['p1'] },
    catalog: {
      services: [{ id: 'svc1', serviceKey: 'text-pro', displayName: 'Text Pro', enabled: true }],
      deployments: [dep('a'), dep('b'), dep('u', { loaded: false }), dep('c', { runtimeEndpointId: 'ep2' }), ext('g1', 'pv1'), ext('g2', 'pv1'), ext('o1', 'pv2'), dep('n', { runtimeEndpointId: null })],
      endpoints: [
        { id: 'ep1', displayName: 'bc250', runtimeType: 'LLAMA_CPP', enabled: true, healthStatus: 'HEALTHY', addressLabel: 'http://10.0.0.2:4040' },
        { id: 'ep2', displayName: 'GPU Box', runtimeType: 'OLLAMA', enabled: false, healthStatus: 'UNKNOWN', addressLabel: 'http://10.0.0.3:11434' },
      ],
      providers: [
        { id: 'pv1', displayName: 'GOOGLE Gemini API', providerType: 'GEMINI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: true, zeroRetentionVerified: false },
        { id: 'pv2', displayName: 'OPEN-AI-API', providerType: 'OPENAI', enabled: true, healthStatus: 'HEALTHY', apiKeyConfigured: false, zeroRetentionVerified: false },
      ],
      projects: [{ id: 'p1', name: 'Alpha', status: 'ACTIVE', dataBoundaryPolicy: null }],
      maskers: [],
    },
  }
  if (mutate) mutate(s)
  return s
}
const draftSnapshot = () => ({ ...snapshot([]), serviceId: null, config: structuredClone(DEFAULT_CONFIG) })
function run(graph, command, s) {
  const r = graphOps.applyCommand(graph, command, s)
  assert.equal(r.error, null, `command ${command.type} failed: ${r.error}`)
  return r.graph
}
const node = (g, id) => g.nodes.find((n) => n.id === id)
const edge = (g, id) => g.edges.find((e) => e.id === id)
/** New-service draft with identity and project p1 (no LLM yet). */
function draftBase() {
  const s = draftSnapshot()
  let g = decompile(s, null)
  g = run(g, { type: 'updateProps', nodeId: 'start', patch: { serviceKey: 'text-new', displayName: 'New' } }, s)
  g = run(g, { type: 'addNode', nodeType: 'project', refId: 'p1' }, s)
  return { s, g }
}

/* ---------------- WP-1 ---------------- */

test('C0. palette connection groups expose onCanvas and addable; connection meta helpers match connectionOf', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const local = palette.buildPalette(s, g, { query: '', showInactive: true, mode: 'advanced' }).find((x) => x.id === 'llm-local')
  const ep1 = local.connections.find((c) => c.id === 'endpoint:ep1')
  assert.deepEqual([ep1.onCanvas, ep1.addable], [true, true])
  assert.equal(local.connections.find((c) => c.id === 'endpoint:ep2').onCanvas, false)
  assert.equal(local.connections.find((c) => c.id === 'unknown-local').addable, false)
  assert.deepEqual(palette.endpointConnectionMeta(s.catalog.endpoints[0]), palette.connectionOf(s.catalog.deployments.find((d) => d.id === 'a'), s.catalog))
  assert.deepEqual(palette.providerConnectionMeta(s.catalog.providers[0]), palette.connectionOf(s.catalog.deployments.find((d) => d.id === 'g1'), s.catalog))
  assert.equal(typeof palette.modelItem, 'function')
  assert.equal(typeof layout.snapUp, 'function')
  assert.equal(typeof layout.freeSpot, 'function')
})

/* ---------------- WP-2 ---------------- */

test('C1. connection identity: host node id = ConnectionMeta id = connectionIdForDeployment', () => {
  const s = snapshot()
  for (const d of s.catalog.deployments) {
    const id = conn.connectionIdForDeployment(d.id, s)
    assert.equal(id, catalog.hostNodeIdFor(d), d.id)
    const meta = palette.connectionOf(d, s.catalog)
    if (!meta.id.startsWith('unknown-')) assert.equal(id, meta.id, d.id)
  }
  assert.equal(conn.connectionIdForDeployment('n', s), null)
  assert.equal(conn.connectionIdForDeployment('zzz', s), null)
  assert.equal(conn.isConnectionId('endpoint:ep1'), true)
  assert.equal(conn.isConnectionId('provider:pv-1'), true)
  for (const bad of ['endpoint:', 'router', 'deployment:a', 'endpoint:../x', 'provider:a b']) assert.equal(conn.isConnectionId(bad), false, bad)
  assert.deepEqual(conn.connectionMetaOf('endpoint:ep1', s), palette.connectionOf(s.catalog.deployments[0], s.catalog))
  assert.deepEqual(conn.connectionMetaOf('provider:zzz', s), { id: 'provider:zzz', kind: 'provider', label: '알 수 없는 연결', detail: '', tone: 'unknown', enabled: true })
})

test('C2. buildConnectionView groups models behind their connection with derived, deterministic positions', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const before = JSON.stringify(g)
  const view = conn.buildConnectionView(g, s)
  assert.equal(JSON.stringify(g), before, 'the projection never mutates the graph')
  assert.deepEqual(view, conn.buildConnectionView(g, s))
  assert.deepEqual(view.groups.map((x) => [x.connectionId, x.kind, x.llmNodeIds]), [
    ['endpoint:ep1', 'endpoint', ['deployment:a', 'deployment:b']],
    ['provider:pv1', 'provider', ['deployment:g1', 'deployment:g2']],
  ])
  assert.deepEqual(view.positions, {
    'endpoint:ep1': { x: 900, y: 0 }, 'deployment:a': { x: 1200, y: 0 }, 'deployment:b': { x: 1200, y: 160 },
    'provider:pv1': { x: 900, y: 320 }, 'deployment:g1': { x: 1200, y: 320 }, 'deployment:g2': { x: 1200, y: 480 },
  })
  assert.deepEqual(view.groups.map((x) => x.frame), [{ x: 900, y: 0, width: 532, height: 288 }, { x: 900, y: 320, width: 532, height: 288 }])
  assert.equal(view.groupOf['deployment:b'], 'endpoint:ep1')
  assert.equal(view.groupOf.router, undefined)
  assert.deepEqual(view.dimmed, [])
})

test('C2b. connections.ts stays pure and free of cyclic imports', () => {
  const text = read('connections.ts')
  assert.doesNotMatch(text, /from ['"]vue['"]|\bwindow\b|\bdocument\b|localStorage|sessionStorage/)
  assert.doesNotMatch(text, /from '\.\/(graphOps|compile|decompile|validate|diff)'/)
})

test('C3. view edges: router -> connection is drawn only, connection -> model reuses the domain id, host edges are hidden', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const view = conn.buildConnectionView(g, s)
  assert.ok(!view.edges.some((e) => e.kind === 'host'))
  const decor = view.edges.filter((e) => !e.interactive)
  assert.deepEqual(decor.map((e) => e.id).sort(), ['view:router/out->endpoint:ep1/in', 'view:router/out->provider:pv1/in'])
  assert.ok(decor.every((e) => e.kind === 'route' && e.locked && !e.detached))
  const routeA = view.edges.find((e) => e.id === 'router/out->deployment:a/in')
  assert.deepEqual([routeA.interactive, routeA.from, routeA.to, routeA.kind], [true, { nodeId: 'endpoint:ep1', portId: 'out' }, { nodeId: 'deployment:a', portId: 'in' }, 'route'])
  assert.equal(view.edges.find((e) => e.id === 'router/out->deployment:g2/in').from.nodeId, 'provider:pv1')
  assert.deepEqual(
    view.edges.filter((e) => e.interactive).map((e) => e.id).sort(),
    g.edges.filter((e) => e.kind !== 'host').map((e) => e.id).sort(),
    'every non-host domain edge is drawn exactly once, with its own id',
  )
  assert.equal(view.edges.find((e) => e.id === 'project:p1/out->router/in').from.nodeId, 'project:p1')
})

test('C4. a model without a request edge keeps a dashed membership edge to its connection', () => {
  const s = snapshot()
  const g = run(decompile(s, null), { type: 'removeItems', nodeIds: [], edgeIds: ['router/out->deployment:a/in'] }, s)
  const view = conn.buildConnectionView(g, s)
  const dashed = view.edges.find((e) => e.id === 'view:endpoint:ep1/out->deployment:a/in')
  assert.ok(dashed)
  assert.deepEqual([dashed.interactive, dashed.detached], [false, true])
  assert.ok(view.edges.some((e) => e.id === 'view:router/out->endpoint:ep1/in'), 'the routed sibling keeps the router -> connection line')
  assert.deepEqual(view.groups[0].llmNodeIds, ['deployment:a', 'deployment:b'], 'membership does not depend on routing')
})

test('C5. a model-specific prompt sits between the connection and its model', () => {
  const s = snapshot([target('t1', 'a', { priority: 1, systemPromptTemplate: 'Be brief' }), ...TARGETS().slice(1)])
  const g = decompile(s, null)
  assert.equal(conn.connectionLaneOffset(g), layout.COLUMN_WIDTH)
  const view = conn.buildConnectionView(g, s)
  assert.deepEqual(view.positions['endpoint:ep1'], { x: 900, y: 0 })
  assert.deepEqual(view.positions['prompt:a'], { x: 1200, y: 0 })
  assert.deepEqual(view.positions['deployment:a'], { x: 1500, y: 0 })
  assert.deepEqual(view.positions['deployment:b'], { x: 1500, y: 160 })
  assert.deepEqual(view.groups[0].promptNodeIds, ['prompt:a'])
  assert.equal(view.edges.find((e) => e.id === 'router/out->prompt:a/in').from.nodeId, 'endpoint:ep1')
  assert.equal(view.edges.find((e) => e.id === 'prompt:a/out->deployment:a/in').from.nodeId, 'prompt:a')
  assert.ok(!view.edges.some((e) => e.detached))
})

test('C6. connection health is shown once; models behind an offline or disabled connection are dimmed', () => {
  const s = snapshot([...TARGETS(), target('t5', 'c', { priority: 2 }), target('t6', 'o1', { priority: 5, followModelChanges: false })])
  const g = decompile(s, null)
  const view = conn.buildConnectionView(g, s)
  assert.deepEqual(Object.fromEntries(view.groups.map((x) => [x.connectionId, x.health])), {
    'endpoint:ep1': 'online', 'endpoint:ep2': 'disabled', 'provider:pv1': 'online', 'provider:pv2': 'offline',
  })
  assert.deepEqual(view.dimmed, ['deployment:c', 'deployment:o1'])
  const degraded = snapshot(TARGETS(), (x) => { x.catalog.endpoints[0].healthStatus = 'DEGRADED' })
  assert.equal(conn.connectionHealth('endpoint:ep1', degraded), 'degraded')
  assert.equal(conn.connectionHealth('endpoint:zzz', s), 'unknown')
  const card = conn.describeConnectionNode(node(g, 'endpoint:ep1'), g, s)
  assert.deepEqual(
    [card.title, card.subtitle, card.tone, card.badges, card.summary, card.locked],
    ['bc250', '로컬 · LLAMA_CPP · http://10.0.0.2:4040', 'healthy', ['온라인'], ['모델 2개 · 사용 2개', '우선순위 P1–P3'], false],
  )
  assert.equal(conn.describeConnectionNode(node(g, 'provider:pv2'), g, s).badges[0], '오프라인')
  assert.equal(conn.CONNECTION_HEALTH_LABEL.disabled, '비활성')
})

test('C12. dragging a connection or any of its models moves the whole group; other nodes keep their dependency rules', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const view = conn.buildConnectionView(g, s)
  const group = ['deployment:a', 'deployment:b', 'endpoint:ep1']
  assert.deepEqual(conn.connectionDragIds(g, view, ['endpoint:ep1'], false), group)
  assert.deepEqual(conn.connectionDragIds(g, view, ['deployment:b'], true), group, 'Ctrl/Cmd does not split a group')
  assert.deepEqual(conn.connectionDragIds(g, view, ['router'], false), ['router'])
  const moved = run(g, { type: 'moveNodes', moves: group.map((id) => ({ nodeId: id, x: node(g, id).x + 160, y: node(g, id).y })) }, s)
  const after = conn.buildConnectionView(moved, s)
  assert.deepEqual(after.positions['endpoint:ep1'], { x: 1060, y: 0 })
  assert.deepEqual(after.positions['deployment:a'], { x: 1360, y: 0 })
  assert.deepEqual(after.positions['provider:pv1'], { x: 900, y: 320 })
})

test('C13. view ports and the per-connection model choices', () => {
  const s = snapshot()
  const g = decompile(s, null)
  assert.deepEqual(conn.viewPortsOf(node(g, 'endpoint:ep1'), 'connection').map((p) => `${p.direction}:${p.id}`), ['in:in', 'out:out'])
  assert.deepEqual(conn.viewPortsOf(node(g, 'deployment:a'), 'connection').map((p) => p.id), ['in'])
  assert.equal(conn.viewPortsOf(node(g, 'deployment:a'), 'model'), catalog.NODE_TYPES.llm.ports)
  assert.equal(conn.viewPortsOf(node(g, 'router'), 'connection'), catalog.NODE_TYPES.router.ports)
  const hidden = conn.connectionModelChoices(s, g, 'endpoint:ep1', false)
  assert.equal(hidden.connection.label, 'bc250')
  assert.deepEqual(hidden.items.map((m) => [m.deploymentId, m.onCanvas]), [['a', true], ['b', true]])
  assert.equal(hidden.hiddenCount, 1)
  const all = conn.connectionModelChoices(s, g, 'endpoint:ep1', true)
  assert.deepEqual(all.items.map((m) => m.deploymentId), ['a', 'b', 'u'])
  assert.equal(all.hiddenCount, 0)
  assert.deepEqual(conn.connectionModelChoices(s, g, 'provider:pv1', false).items.map((m) => m.deploymentId), ['g1', 'g2'])
})

test('C15. overlapping groups are pushed apart; auto layout in the connection view keeps each connection contiguous', () => {
  const s = snapshot([target('t1', 'a', { priority: 1 }), target('t5', 'c', { priority: 2 }), target('t2', 'b', { priority: 3 })])
  const g = decompile(s, null)
  assert.deepEqual(['deployment:a', 'deployment:c', 'deployment:b'].map((id) => node(g, id).y), [0, 160, 320], 'model view: dispatch order interleaves connections')
  const interleaved = conn.buildConnectionView(g, s)
  assert.deepEqual(interleaved.positions['endpoint:ep2'], { x: 900, y: 320 })
  const [f0, f1] = interleaved.groups.map((x) => x.frame)
  assert.ok(f0.y + f0.height <= f1.y)
  const grouped = conn.autoLayoutByConnection(g, s)
  assert.deepEqual(['deployment:a', 'deployment:b', 'deployment:c'].map((id) => [node(grouped, id).x, node(grouped, id).y]), [[900, 0], [900, 160], [900, 320]])
  assert.deepEqual([node(grouped, 'endpoint:ep1').y, node(grouped, 'endpoint:ep2').y], [0, 320])
  assert.deepEqual(grouped, conn.autoLayoutByConnection(g, s))
  assert.deepEqual(compile(grouped, s), compile(g, s))
})

/* ---------------- WP-3 ---------------- */

test('C7. addConnection places a pinned connection that survives sync and rebase, warns while empty and compiles to nothing', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const r = graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'endpoint:ep2', position: { x: 900, y: 800 } }, s)
  assert.deepEqual([r.error, r.changed, r.select], [null, true, { nodeIds: ['endpoint:ep2'], edgeIds: [] }])
  const host = node(r.graph, 'endpoint:ep2')
  assert.deepEqual([host.type, host.refId, host.props, host.x, host.y], ['endpoint', 'ep2', { pinned: true }, 900, 800])
  assert.deepEqual(compile(r.graph, s), compile(g, s))
  assert.ok(node(graphOps.syncDerived(r.graph, s), 'endpoint:ep2'))
  assert.ok(node(diff.rebase({ graph: r.graph, base: s, remote: s }).graph, 'endpoint:ep2'))
  assert.ok(node(syncGraphToConfig(r.graph, compile(r.graph, s), s), 'endpoint:ep2'))
  assert.deepEqual(conn.buildConnectionView(r.graph, s).positions['endpoint:ep2'], { x: 900, y: 800 })
  const issue = validateGraph(r.graph, s).find((i) => i.code === 'CONNECTION_EMPTY')
  assert.deepEqual([issue.severity, issue.nodeId], ['warning', 'endpoint:ep2'])
  assert.deepEqual(issue.fix.command, { type: 'removeItems', nodeIds: ['endpoint:ep2'], edgeIds: [], withConnectionModels: true })
  assert.equal(node(run(r.graph, issue.fix.command, s), 'endpoint:ep2'), undefined)
  const again = graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'endpoint:ep1' }, s)
  assert.deepEqual([again.error, again.changed, again.select], [null, false, { nodeIds: ['endpoint:ep1'], edgeIds: [] }])
  assert.equal(graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'endpoint:nope' }, s).error, '알 수 없는 연결입니다.')
  assert.equal(graphOps.applyCommand(g, { type: 'addConnection', connectionId: 'router' }, s).error, '알 수 없는 연결입니다.')
  const lonely = snapshot(TARGETS(), (x) => { x.catalog.endpoints.push({ id: 'ep9', displayName: 'Empty Box', runtimeType: 'VLLM', enabled: true, healthStatus: 'HEALTHY', addressLabel: '' }) })
  assert.equal(graphOps.applyCommand(decompile(lonely, null), { type: 'addConnection', connectionId: 'endpoint:ep9' }, lonely).error, '이 연결에는 등록된 모델이 없습니다.')
  const refused = graphOps.applyCommand(r.graph, { type: 'removeItems', nodeIds: ['endpoint:ep2'], edgeIds: [] }, s)
  assert.equal(refused.error, '자동으로 표시되는 노드라 삭제할 수 없습니다.')
  assert.ok(node(refused.graph, 'endpoint:ep2'))
  // the pinned connection keeps its spot when its first model arrives
  const filled = run(r.graph, { type: 'addConnectionModels', connectionId: 'endpoint:ep2', deploymentIds: ['c'] }, s)
  assert.deepEqual(conn.buildConnectionView(filled, s).positions['endpoint:ep2'], { x: 900, y: 800 })
  assert.equal(validateGraph(filled, s).some((i) => i.code === 'CONNECTION_EMPTY'), false)
})

test('C8. addConnectionModels adds only that connection\'s models behind it; the connection does not jump', () => {
  let { s, g } = draftBase()
  g = run(g, { type: 'addConnection', connectionId: 'endpoint:ep1', position: { x: 900, y: 0 } }, s)
  const r = graphOps.applyCommand(g, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['a', 'b'] }, s)
  assert.equal(r.error, null)
  assert.deepEqual(r.select, { nodeIds: ['endpoint:ep1'], edgeIds: [] })
  const out = r.graph
  assert.ok(node(out, 'router'), 'two models add the router automatically')
  assert.ok(edge(out, 'router/out->deployment:a/in') && edge(out, 'router/out->deployment:b/in'))
  assert.deepEqual(['deployment:a', 'deployment:b', 'endpoint:ep1'].map((id) => [node(out, id).x, node(out, id).y]), [[900, 0], [900, 160], [1200, 0]], 'stored (model view) positions')
  const view = conn.buildConnectionView(out, s)
  assert.deepEqual(view.positions['endpoint:ep1'], { x: 900, y: 0 }, 'the connection stays where it was dropped')
  assert.deepEqual([view.positions['deployment:a'], view.positions['deployment:b']], [{ x: 1200, y: 0 }, { x: 1200, y: 160 }])
  assert.equal(graphOps.applyCommand(out, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['g1'] }, s).error, '선택한 모델 중 이 연결에 속하지 않는 모델이 있습니다.')
  assert.equal(graphOps.applyCommand(out, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['zzz'] }, s).error, '알 수 없는 모델입니다.')
  const same = graphOps.applyCommand(out, { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['a'] }, s)
  assert.deepEqual([same.error, same.changed], [null, false])
  const more = run(run(out, { type: 'addConnectionModels', connectionId: 'provider:pv1', deploymentIds: ['g1'] }, s), { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['u'] }, s)
  const spots = more.nodes.filter((n) => n.type === 'llm').map((n) => `${n.x},${n.y}`)
  assert.equal(new Set(spots).size, spots.length, 'no two models share a stored position')
  const v2 = conn.buildConnectionView(more, s)
  assert.deepEqual(v2.groups.find((x) => x.connectionId === 'endpoint:ep1').llmNodeIds, ['deployment:a', 'deployment:b', 'deployment:u'])
  const frames = v2.groups.map((x) => x.frame)
  for (let i = 0; i < frames.length; i += 1) {
    for (let j = i + 1; j < frames.length; j += 1) assert.ok(frames[i].y + frames[i].height <= frames[j].y || frames[j].y + frames[j].height <= frames[i].y)
  }
})

test('C9. golden: the same targets compile to the same configuration and plan in both views', () => {
  const build = (steps) => { let { s, g } = draftBase(); for (const step of steps) g = run(g, step, s); return { s, g } }
  const viaConnections = build([
    { type: 'addConnection', connectionId: 'endpoint:ep1', position: { x: 900, y: 0 } },
    { type: 'addConnectionModels', connectionId: 'endpoint:ep1', deploymentIds: ['a', 'b'] },
    { type: 'addConnectionModels', connectionId: 'provider:pv1', deploymentIds: ['g1'] },
  ])
  const viaModels = build(['a', 'b', 'g1'].map((refId) => ({ type: 'addNode', nodeType: 'llm', refId })))
  const s = viaConnections.s
  assert.deepEqual(compile(viaConnections.g, s), compile(viaModels.g, s))
  const ops = ({ g }) => diff.planChanges({ base: s, desired: compile(g, s), remote: s, issues: validateGraph(g, s) }).operations.map((op) => [op.opId, op.kind, JSON.stringify(op.body ?? null)])
  assert.deepEqual(ops(viaConnections), ops(viaModels))
  assert.ok(ops(viaConnections).length >= 5, 'createService + 3 createTarget + grantAccess')
  for (const key of Object.keys(layout.extractLayout(viaConnections.g, { x: 0, y: 0, zoom: 1 }).nodes)) assert.ok(layout.LAYOUT_NODE_ID_PATTERN.test(key), key)
})

test('C10. wiring in the connection view: same connection re-routes, another connection is refused', () => {
  const s = snapshot()
  const g = decompile(s, null)
  const detached = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['router/out->deployment:a/in'] }, s)
  const epOut = { nodeId: 'endpoint:ep1', portId: 'out' }
  const aIn = { nodeId: 'deployment:a', portId: 'in' }
  const fixed = run(detached, { type: 'connectConnection', from: epOut, to: aIn }, s)
  assert.ok(edge(fixed, 'router/out->deployment:a/in'))
  const wrong = graphOps.applyCommand(detached, { type: 'connectConnection', from: { nodeId: 'provider:pv1', portId: 'out' }, to: aIn }, s)
  assert.match(wrong.error, /'bc250' 연결에 속합니다/)
  assert.match(wrong.error, /연결은 바꿀 수 없습니다/)
  assert.equal(graphOps.canConnectInView(detached, epOut, aIn, s, 'connection').ok, true)
  assert.equal(graphOps.canConnectInView(fixed, epOut, aIn, s, 'connection').reason, '이미 요청 경로에 연결된 모델입니다.')
  const routerToEp1 = { from: { nodeId: 'router', portId: 'out' }, to: { nodeId: 'endpoint:ep1', portId: 'in' } }
  assert.equal(graphOps.applyCommand(g, { type: 'connectConnection', ...routerToEp1 }, s).error, '이 연결의 모델은 이미 모두 요청 경로에 연결되어 있습니다.')
  assert.equal(graphOps.canConnectInView(g, routerToEp1.from, routerToEp1.to, s, 'model').reason, '라우터 뒤에는 LLM 또는 모델별 시스템 프롬프트만 연결할 수 있습니다.')
  const bare = run(g, { type: 'removeItems', nodeIds: [], edgeIds: ['router/out->deployment:a/in', 'router/out->deployment:b/in'] }, s)
  const back = run(bare, { type: 'connectConnection', ...routerToEp1 }, s)
  assert.ok(edge(back, 'router/out->deployment:a/in') && edge(back, 'router/out->deployment:b/in'))
  assert.deepEqual(compile(back, s), compile(g, s))
  const guarded = run(bare, { type: 'addNode', nodeType: 'guardrail' }, s)
  assert.equal(graphOps.applyCommand(guarded, { type: 'connectConnection', from: { nodeId: 'guardrail', portId: 'out' }, to: { nodeId: 'endpoint:ep1', portId: 'in' } }, s).error, '모델이 여러 개인 연결은 라우터에서 이어 주세요.')
  assert.equal(
    graphOps.applyCommand(g, { type: 'connectConnection', from: { nodeId: 'project:p1', portId: 'out' }, to: { nodeId: 'endpoint:ep1', portId: 'in' } }, s).error,
    '연결 노드에는 라우터나 처리 단계(가드레일·공통 프롬프트·캐시·생성 파라미터)의 출력에서만 간선을 이을 수 있습니다.',
  )
  const view = conn.buildConnectionView(bare, s)
  const at = (n) => view.positions[n.id] ?? { x: n.x, y: n.y }
  const p = view.positions['endpoint:ep1']
  assert.deepEqual(graphOps.nearestPortInView(bare, routerToEp1.from, { x: p.x, y: p.y + 52 }, 24, s, 'connection', at), { nodeId: 'endpoint:ep1', portId: 'in' })
})

test('C11. deleting a connection takes its models and prompts; the auto-layout command honours the view', () => {
  const s = snapshot([target('t1', 'a', { priority: 1, systemPromptTemplate: 'Be brief' }), ...TARGETS().slice(1)])
  const g = decompile(s, null)
  const refused = graphOps.applyCommand(g, { type: 'removeItems', nodeIds: ['endpoint:ep1'], edgeIds: [] }, s)
  assert.deepEqual([refused.error, refused.changed], ['자동으로 표시되는 노드라 삭제할 수 없습니다.', false])
  const out = run(g, { type: 'removeItems', nodeIds: ['endpoint:ep1'], edgeIds: [], withConnectionModels: true }, s)
  for (const id of ['endpoint:ep1', 'deployment:a', 'deployment:b', 'prompt:a']) assert.equal(node(out, id), undefined, id)
  for (const id of ['provider:pv1', 'deployment:g1', 'deployment:g2', 'router']) assert.ok(node(out, id), id)
  const plan = diff.planChanges({ base: s, desired: compile(out, s), remote: s, issues: validateGraph(out, s) })
  assert.deepEqual(plan.operations.map((op) => op.opId).sort(), ['delete-target:t1', 'delete-target:t2'])
  const xy = (graph) => graph.nodes.map((n) => [n.id, n.x, n.y])
  assert.deepEqual(xy(run(g, { type: 'autoLayout', view: 'connection' }, s)), xy(graphOps.sortGraph(conn.autoLayoutByConnection(g, s))))
  assert.deepEqual(xy(run(g, { type: 'autoLayout' }, s)), xy(graphOps.sortGraph(layout.autoLayout(g, s))))
})

/* ---------------- WP-4 ---------------- */

test('C16. editor and toolbar wire the view toggle (module memory, default connection view)', () => {
  const editor = read('useFlowEditor.ts')
  assert.match(editor, /let lastViewMode: FlowViewMode = 'connection'/)
  assert.match(editor, /let lastMode: DesignerMode = 'basic'/)
  assert.match(editor, /case 'view-connection':/)
  assert.match(editor, /case 'view-model':/)
  assert.match(editor, /type: 'autoLayout', view: viewMode\.value/)
  assert.match(editor, /mode, viewMode, canNormalize/)
  const toolbar = read('FlowToolbar.vue')
  for (const needle of ["fire('view-connection')", "fire('view-model')", '연결 중심', '모델 중심', 'viewMode?: FlowViewMode']) assert.ok(toolbar.includes(needle), needle)
  assert.match(read('FlowDesignerPage.vue'), /:mode="mode" :can-normalize="canNormalize" :view-mode="viewMode"/)
})

/* ---------------- WP-5 ---------------- */

test('C17. canvas and node card render the connection view', () => {
  const canvas = read('FlowCanvas.vue')
  for (const needle of [
    'buildConnectionView(', 'viewPortsOf(node, viewKind', "nodeType === 'connection'", "type: 'connectConnection'",
    "withConnectionModels: viewKind.value === 'connection'", 'class="fd-group-frame"', 'v-if="item.interactive"',
    'connectionDragIds(', 'nearestPortInView(', 'canConnectInView(', "type: 'addConnectionModels'", 'groupDelta(',
    '(isConnectionId(from.nodeId) || isConnectionId(nodeId))',
  ]) assert.ok(canvas.includes(needle), needle)
  assert.match(canvas, /viewMode\?: FlowViewMode/)
  assert.match(canvas, /\{ mode: 'advanced' \}/)
  const card = read('FlowNodeCard.vue')
  for (const needle of ["'is-dimmed'", 'class="fd-node-action"', 'dimmed?: boolean', 'action?: string | null']) assert.ok(card.includes(needle), needle)
})

/* ---------------- WP-6 ---------------- */

test('C18. palette, inspector and the model picker use the connection commands', () => {
  const pal = read('FlowPalette.vue')
  for (const needle of ["dragStart($event, 'connection', conn.id)", "type: 'addConnection'", "type: 'addConnectionModels'", 'class="fd-conn-add"']) assert.ok(pal.includes(needle), needle)
  const inspector = read('FlowInspector.vue')
  for (const needle of ["import FlowConnectionModels from './FlowConnectionModels.vue'", 'withConnectionModels: true', 'hostNodeIdFor(d) === currentHost.value', '연결 삭제']) assert.ok(inspector.includes(needle), needle)
  const picker = read('FlowConnectionModels.vue')
  assert.match(picker, /type: 'addConnectionModels'/)
  assert.match(picker, /중지·미로드/)
  for (const forbidden of ['localStorage', 'sessionStorage', 'v-html', 'innerHTML']) assert.ok(!picker.includes(forbidden), forbidden)
  assert.doesNotMatch(picker, /#[0-9a-fA-F]{3,8}\b|rgba?\(/)
  const page = read('FlowDesignerPage.vue')
  assert.equal((page.match(/:view-mode="viewMode"/g) ?? []).length, 4)
  assert.match(page, /연결 중심/)
})
```

---

## 8. 파일별 변경 요약

| 파일 | 변경 | WP |
|---|---|---|
| `types.ts` | `PaletteConnectionGroup` 필드 2개, 연결 보기 타입 블록, `FlowCommand` 4개 형태(새 3 + 확장 2), `FlowToolbarAction` 2개 | 1, 3, 4 |
| `layout.ts` | `snapUp`·`freeSpot` export | 1 |
| `palette.ts` | `endpointConnectionMeta`·`providerConnectionMeta` 추출, `modelItem` export, `onCanvas`·`addable` | 1 |
| `connections.ts` | 신규(순수) | 2 |
| `graphOps.ts` | pinned 유지, `removeItems.withConnectionModels`, 새 명령 3, `autoLayout.view`, `canConnectInView`, `nearestPortInView` | 3 |
| `validate.ts` | `CONNECTION_EMPTY` | 3 |
| `useFlowEditor.ts` | `lastViewMode`, `viewMode`, `showQuickStart`, 알림, 툴바 액션, 반환 | 4 |
| `FlowToolbar.vue` | [연결 중심 \| 모델 중심] | 4 |
| `FlowNodeCard.vue` | `dimmed`, `action` | 5 |
| `FlowCanvas.vue` | 투영 렌더·그룹 드래그·보기 배선·드롭·삭제 플래그·그룹 틀 | 5 |
| `FlowConnectionModels.vue` | 신규 | 6 |
| `FlowPalette.vue` | 연결 머리글 드래그·[＋]·모델 클릭 경로 | 6 |
| `FlowInspector.vue` | 연결 패널·모델 교체 제한·삭제 플래그·간선 안내 | 6 |
| `FlowDesignerPage.vue` | `:view-mode` 4곳, 상태바 | 4, 5, 6 |
| `tests/flowDesignerConnections.test.mjs`, `package.json` | 신규 테스트 C0–C18, 스크립트 | 1–6 |
| 변경 없음 | `compile.ts`, `decompile.ts`, `diff.ts`, `workflow.ts`, `history.ts`, `draftStore.ts`, `execute.ts`, `flowApi.ts`, `flowSnapshot.ts`, `FlowQuickStart.vue`, `FlowPlanModal.vue`, 백엔드 전부 | — |

---

## 9. 미해결 위험 · 후속

| # | 위험 | 영향 | 대응 |
|---|---|---|---|
| R1 | 연결 보기 좌표가 모델 저장 좌표에 묶여 있다(D4) | 연결 보기에서 그룹을 옮기면 모델 중심 배치도 같은 delta로 바뀐다. 모델 중심에서 모델을 흩으면 연결 보기는 다시 모아 그린다. | 의도된 단일 좌표계. 두 보기 독립 좌표가 필요해지면 백엔드 레이아웃 형식 확장이 필요하다. |
| R2 | 전역 `laneOffset`(E11) | 첫 모델별 프롬프트·마지막 프롬프트 삭제 시 드물게 연결 칸이 300px 이동하거나 빈 칸이 생긴다(외관). | [자동 정렬]. 필요하면 그룹별 오프셋으로 바꾸는 후속(모델 칸 정렬을 포기). |
| R3 | `FlowCanvas.vue` 변경 폭이 크다(C1–C20) | 모델 중심의 끌기·배선 회귀 가능성. | 모델 중심 경로는 기존 함수(`dragPosition`, `dependencyClosure`, `canConnect` 동치)를 그대로 쓰고, 기존 소스 검사·수동 점검 4·5로 확인. |
| R4 | 빈 연결은 서버에 저장되지 않는다 | 새로고침·[되돌리기]로 사라진다. | 경고 문구에 명시. 의미상 서버 설정이 없는 노드다. |
| R5 | '모델 추가'가 캔버스 팝오버가 아니라 인스펙터(D10) | 사용자가 기대한 "노드에서 바로 고르기"와 다를 수 있다. | 카드 버튼이 패널을 연다. 팝오버는 `FlowConnectionModels.vue`를 재사용하는 후속으로 작게 추가 가능. |
| R6 | 용어 충돌: Plan/충돌 메시지의 "LLM 연결(Target)"(`diff.ts:190, 210, 222, 238, 336-337, 494, 501, 525, 573`) | 캔버스의 '연결'(엔드포인트·Provider)과 Plan의 '연결'(Target)이 다른 뜻. | 기존 테스트가 `'LLM 연결 추가'`를 고정(`flowDesignerModel.test.mjs:285`)해 이번 범위에서 제외. 후속으로 "라우팅 대상(Target)"으로 바꾸고 테스트를 함께 갱신. |
| R7 | 트렁크 없이 여러 프로젝트가 단일 모델로 들어가는 경우(E8) | 잠긴 access 간선이 연결→모델 구간에서 겹쳐 그려진다(외관). | 기능 영향 없음. |
| R8 | 다른 체크아웃의 미커밋 수정(`useFlowEditor.ts`, `draftStore.ts`, `flowDesignerAdapter.test.mjs`) | 병합 시 import 줄·인접 영역 충돌 가능성(낮음). | §7.0 위치 제한. 이 브랜치는 `draftStore.ts`·어댑터 테스트를 건드리지 않는다. |
| R9 | 그룹 충돌 회피는 그룹만 민다(E9) | 큰 그룹이 아래 노드를 지나 길게 밀릴 수 있다. | [자동 정렬]. |
| R10 | 연결 노드의 출력 포트는 하나라 연결→모델 선이 위쪽에서 부채꼴로 퍼진다 | 모델별 프롬프트 칸이 있는 그룹에서 아래 행으로 가는 선이 프롬프트 카드 밑을 지난다(시제품에서 확인, 외관). | 기능 영향 없음. 필요하면 후속으로 행별 출력 앵커. |
| R11 | LLM 카드 제목은 기존 `describeNode`(전체 표시 이름) | 경로형 이름(`/opt/llm/….gguf`)이 카드에서 잘린다. 팔레트·모델 추가 목록은 `shortModelName`을 쓴다. | 범위 밖(기존 동작). 후속으로 카드 제목에 `shortModelName` 적용 검토. |
