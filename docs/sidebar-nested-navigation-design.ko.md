# 콘솔 사이드바 중첩형 내비게이션 설계서 · 작업 지시서

> 상태: 설계 확정 · 기준 커밋 `a3c4149` (브랜치 `claude/sidebar-nested-nav`) · 작성 2026-11-26
> 이 문서는 설계와 작업 지시서만 담는다. 문서 작성 시점에 제품 코드는 변경하지 않았다.
> 단, §7의 모든 코드는 저장소 밖 임시 복사본에서 그대로 적용해 `vue-tsc --noEmit`, CI 프론트엔드 테스트 16종 전체, `npm run build`, 브라우저 렌더링(다크/라이트, 115%/135%, 모바일)까지 통과시킨 결과물이다(부록 A).
> 표기: 설명은 한국어, 식별자·경로·코드는 영어. `파일:줄`은 기준 커밋 위치다. 경로는 저장소 루트 기준.

---

## 0. 요약

- **문제**: 관리자 사이드바는 2줄짜리(이름+설명) 48px 행이 19~21개 한꺼번에 펼쳐진다. 플랫폼 관리자 기준 메뉴 영역 높이가 약 1,150 CSS px라 1080p·115%에서 화면 높이의 약 3배를 스크롤해야 한다. 그룹 5개 중 `인프라·시스템`은 매핑되지 않은 페이지를 모두 받는 잡동사니 그룹(7~9개)이다.
- **IA**: `대시보드`는 카테고리 없이 맨 위에 고정하고, 나머지는 업무 기준 카테고리 5개로 나눈다. `모델·라우팅` / `조직·보안` / `관측·비용` / `개발자 도구` / `플랫폼 운영`(조건부). 페이지마다 카테고리는 **역할과 상관없이 하나**로 정한다. 역할은 보이는 페이지만 거른다.
- **중첩**: 카테고리 헤더는 펼침/접힘 버튼이다. **관리자는 아코디언**(한 번에 하나만 열림), **프로젝트 총괄·팀원은 모두 펼침**(10개 이하라 접으면 클릭만 늘어난다). 펼침 상태는 **저장하지 않는다**. 현재 페이지(라우트)에서 매번 계산한다.
- **밀도**: 하위 항목은 한 줄 라벨만 보여 준다. 설명은 `title` 툴팁과 기능 검색(⌘K) 결과로 옮긴다. 아이콘은 대시보드와 카테고리 헤더에만 둔다. 플랫폼 관리자 기준 보이는 행이 21개 → 최대 11개, 메뉴 높이는 약 1,150 → 약 310 CSS px(-73%)로 줄어든다.
- **구조**: 메뉴 정의와 그룹·펼침 로직은 순수 TS 모듈 `frontend/src/consoleNavigation.ts`로 옮기고, 렌더링은 새 컴포넌트 `frontend/src/SideNav.vue`가 맡는다. `ConsoleShell.vue`는 import 2줄, computed 4줄, 템플릿 1줄만 바꾼다. 라우팅(`navigate`, `isAllowedPage`, 해시)은 그대로 둔다.
- **부가**: 기능 검색 결과와 상단 브레드크럼에 카테고리명을 표시한다(`관측·비용 · 토큰·비용·API 호출 이력`, `AiConnect / 관측·비용 / 사용량`). 검색은 카테고리명으로도 찾는다.

---

## 1. 현재 상태 분석

### 1.1 렌더링 경로 (기준 커밋)

| 항목 | 위치 | 내용 |
|---|---|---|
| 메뉴 정의 | `frontend/src/ConsoleShell.vue:54-95` | `adminNavItems`(18), `directorNavItems`(9), `platformNavItem`, `developerNavItems`(7), `docsItem`. 각 항목은 `id/label/description/keywords/icon/group` |
| 역할 선택 | `ConsoleShell.vue:151-157` | `isOrgOrPlatformAdmin` → admin 목록(+`platformNavItem` if `platformAdmin`), `isProjectDirector` → director, 그 외 developer. `platformAdmin \|\| betaOperator`이면 `beta`(초대 서비스 운영)를 인라인 객체로 덧붙임 |
| **그룹 재매핑** | `ConsoleShell.vue:158-161` | `.map(item => ({ ...item, group: ... }))`로 **항목의 `group` 값을 버리고** id로 다시 분류: `dashboard`→`홈`, `relationship/projects/teams/portal/data-protection`→`워크스페이스`, `playground/model-playground`→`개발자 도구`, `observability/usage/quotas`→`관측·비용`, **나머지 전부→`인프라·시스템`** |
| 그룹 순서 | `ConsoleShell.vue:162` | `['홈','워크스페이스','개발자 도구','관측·비용','인프라·시스템']` 중 항목이 있는 것만 |
| 사이드바 마크업 | `ConsoleShell.vue:393` | 그룹마다 `<section class="nav-group"><p>그룹</p><button>…<strong>라벨</strong><small>설명</small>…</button></section>`. 모든 항목·설명이 항상 보임. 버튼에 `type`, `aria-current` 없음 |
| Dev-Docs | `ConsoleShell.vue:394-401` | `.sidebar-support` 고정 영역(“도움말”), `navigate('docs')` |
| 기능 검색(⌘K) | `ConsoleShell.vue:179-182, 361-364, 460` | `allNavigation = [...navItems, docsItem]`을 `label+description+keywords` 부분 문자열로 거름. 사이드바 상태와 무관 |
| 접근 제어 | `ConsoleShell.vue:187, 194-203, 353-360` | `isAllowedPage(target)` = `docs` 또는 `navItems`에 존재. `navigate`/`onHashChange`/`ensureAllowedPage`가 허용되지 않은 페이지를 `dashboard`로 돌림. **사이드바 구조와 독립** |
| 스타일 | `frontend/src/style.css:161-185`, `frontend/src/accessibility.css:19-46` | `.side-nav`(flex:1, overflow-y:auto), `.nav-group …`(행 48px, 3열 그리드), `.nav-icon`(29px, 팔레트·Dev-Docs도 사용). `accessibility.css`가 `.nav-group` 글자 크기를 10/12/10px로 덮어씀 |
| 배율 | `accessibility.css:1-15, 220-221` | `.console-shell { zoom: var(--console-zoom) }`(100/115/125/135%, 기본 115%). 760px 이하에서는 zoom 1 |
| 모바일 | `style.css:520-525` | 1020px 이하: 사이드바가 off-canvas 서랍(`transform`), `navigate()`가 `sidebarOpen=false`로 닫음 |

**정리**: 항목의 `group` 필드에는 서로 다른 값이 10종(`운영`, `워크스페이스`, `Operations`, `워크스페이스 관리`, `관측`, `시스템`, `개발자 도구`, `홈`, `관측·비용`, `내 작업`) 들어 있지만 **화면에는 하나도 쓰이지 않는 죽은 데이터**다. 실제 분류는 158-161줄의 id 목록이 정하고, 목록에 없는 id는 모두 `인프라·시스템`으로 떨어진다. 새 페이지를 추가하면 조용히 `인프라·시스템`으로 들어가는 구조다.

### 1.2 역할별 현재 렌더 결과

| 역할 | 그룹(항목 수) | 메뉴 행 | 비고 |
|---|---|---|---|
| 플랫폼 관리자 | 홈(1) · 워크스페이스(5: 내 API, 팀과 부서, 프로젝트, 조직 관계도, 데이터 보호) · 개발자 도구(2) · 관측·비용(3) · 인프라·시스템(9: 인프라스트럭처, 시스템 구조, 외부 AI, LLM 서비스, 워크플로우 디자이너, 알림 채널, 감사 로그, Platform Admin, 초대 서비스 운영) | 20 + Dev-Docs | 그룹 라벨 5개 |
| 조직 관리자 | 위와 같음. 인프라·시스템 7개(+베타 운영자면 8개) | 18(+1) + Dev-Docs | |
| 프로젝트 총괄 | 홈(1) · 워크스페이스(4: 프로젝트, 조직 관계도, 데이터 보호, 내 API) · 개발자 도구(1) · 관측·비용(3) | 9(+1) + Dev-Docs | 베타 운영자면 `인프라·시스템`에 초대 서비스 운영 1개 |
| 팀원 | 홈(1) · 워크스페이스(2: 내 API, 조직 관계도) · 개발자 도구(1) · 관측·비용(3) | 7(+1) + Dev-Docs | |

측정값(브라우저, 플랫폼 관리자, 기존 CSS 그대로): 메뉴 영역 콘텐츠 높이 **1,323px @115% ≈ 1,150 CSS px**. 1440×900·115%에서 메뉴에 쓸 수 있는 높이는 약 410px라 약 3배를 스크롤한다. 1366×768·135%에서는 메뉴 영역이 약 190px까지 줄어든다.

### 1.3 문제점

| # | 문제 | 영향 |
|---|---|---|
| P1 | 행 19~21개가 모두 펼쳐지고 행마다 설명 줄이 붙음(텍스트 2배) | 훑어보기 부담, 스크롤 3배 |
| P2 | `인프라·시스템`이 잡동사니 그룹: 라우팅 구성, 토폴로지, 알림, 감사, 플랫폼 관리가 한데 섞임 | 원하는 메뉴의 위치를 추측하기 어려움 |
| P3 | `워크스페이스`에 소비자 기능(`내 API`)과 거버넌스(`팀과 부서`, `데이터 보호`)가 섞임 | 업무 단위가 아님 |
| P4 | `group` 필드 10종이 전부 죽은 데이터, 실제 분류는 다른 곳(158-161줄)에 있음 | 유지보수 함정. 새 페이지가 자동으로 잡동사니 그룹에 들어감 |
| P5 | 같은 그룹 안 아이콘 중복(`⌘` 3개, `◇` 4개) | 아이콘이 구분 수단이 되지 못함 |
| P6 | 버튼에 `type="button"`, `aria-current`가 없음 | 보조기기가 현재 위치를 알 수 없음 |
| P7 | 기능 검색 결과 순서가 사이드바와 다르고 소속 그룹 정보가 없음 | 검색 → 사이드바 위치를 연결하기 어려움 |

### 1.4 기존 테스트가 고정한 계약 (수정 대상)

| 테스트 | 고정 내용 | 이번 변경 후 |
|---|---|---|
| `frontend/tests/projectGovernance.test.mjs:261` | `ConsoleShell.vue`를 컴파일해 메모리 DOM에 렌더. 첫 `<nav>`의 텍스트로 역할별 허용/차단 라벨 검사. **설명 텍스트**(`프로젝트·멤버·권한·API 키`)로 버튼을 찾아 클릭 | `./consoleNavigation`·`./SideNav.vue` import 해석을 추가하고, 버튼 찾기를 `title` 속성 기준으로 변경(WP-3) |
| `frontend/tests/flowDesignerAdapter.test.mjs:345-361, 573-581` | `ConsoleShell.vue` 소스에서 `const adminNavItems: NavItem[]` 블록과 `id: 'flow-designer'` 줄(라벨·설명·키워드) 검사 | 같은 검사를 `consoleNavigation.ts` 소스에 적용(WP-3) |
| `frontend/tests/betaOperator.test.mjs:343-349` | `ConsoleShell.vue`의 인라인 beta 객체 정규식, `isAllowedPage` 본문 정규식 | beta 정규식만 `consoleNavigation.ts`/`navItemsFor` 호출 기준으로 교체. `isAllowedPage` 정규식은 **그대로 통과해야 함**(본문 변경 금지)(WP-3) |
| `frontend/tests/dateTime.test.mjs:196-199`, `totpAndHierarchy.test.mjs` | `<DateTimePreferences />`, `title="환경 설정"`, `accountTierLabel` 등 | 영향 없음(해당 부분을 건드리지 않음) |

메모리 DOM(`projectGovernance.test.mjs`의 `createMemoryDom`)은 `style`, `classList`, `querySelector`가 없다. 따라서 **`v-show`와 `<Transition>`을 쓰면 테스트가 깨진다**(Vue가 `el.style`/`classList`를 만짐). 접힘은 `hidden` 속성으로 구현한다.

---

## 2. 정보 구조(IA) 제안

### 2.1 원칙

1. **업무 기준 분류**: “무엇을 하려는가”로 묶는다. 구성(연결·라우팅) → 접근 관리(누가·어떤 정책으로) → 운영 관측(무슨 일이·얼마나) → 직접 써 보기(개발자 도구) → 플랫폼 전체 관리 순서다. 관리자 가이드(Dev-Docs)의 흐름(런타임 연결 → 서비스 구성 → 프로젝트·키 → 관측)과 같다.
2. **페이지당 카테고리 하나, 역할 무관**: 같은 페이지는 어느 역할에서든 같은 카테고리에 있다. 역할은 보이는 페이지만 거르고, 빈 카테고리는 숨긴다. 문서·교육·스크린샷의 위치 설명이 역할마다 달라지지 않는다.
3. **최대 2단계**: 카테고리 → 페이지. 카테고리당 최대 5개라 하위 그룹(3단계)은 클릭만 늘리고 이득이 없어 두지 않는다.
4. **홈은 고정**: `대시보드`는 모든 역할의 시작점이라 카테고리 밖 맨 위에 고정(pinned)한다. 펼침 상태와 상관없이 항상 1클릭이다.
5. **분류는 한 곳에만**: `PAGE_CATEGORY: Record<NavPageId, NavCategoryId | null>` 하나가 분류의 유일한 원천이다. `Record` 타입이라 새 `NavPageId`를 추가하면서 카테고리를 빠뜨리면 **컴파일 오류**가 난다(P4 해결).

### 2.2 카테고리 정의

| 순서 | id | 라벨 | 헤더 아이콘 | 의도(사용자 업무) |
|---|---|---|---|---|
| – | (pinned) | 대시보드 | `◔` | 시작점·요약 |
| 1 | `models` | 모델·라우팅 | `▣` | 모델 공급원(사내 Runtime, 외부 AI)을 연결하고 논리 모델·라우팅을 구성하고 연결 구조를 확인한다 |
| 2 | `access` | 조직·보안 | `◈` | 누가 무엇을 쓰는지(프로젝트·팀·관계도), 어떤 데이터 정책으로(데이터 보호), 누가 무엇을 바꿨는지(감사 로그) |
| 3 | `observe` | 관측·비용 | `◉` | 요청·장애 추적, 사용량·비용, 예산·한도, 장애 알림 전송 |
| 4 | `devtools` | 개발자 도구 | `▷` | 직접 써 보기: 내 API(키·Base URL), API 테스트, 모델 테스트 콘솔 |
| 5 | `platform` | 플랫폼 운영 | `⚡` | 플랫폼 전역 관리(플랫폼 관리자), 초대 서비스 운영(플랫폼 관리자·베타 운영자). 해당자에게만 보임 |
| – | (고정 영역) | 도움말 › Dev-Docs | `</>` | 기존 `.sidebar-support` 그대로 |

`관측·비용`, `개발자 도구`는 기존 라벨을 유지해 익숙함을 살렸다. `인프라·시스템`, `워크스페이스`는 없앤다.

### 2.3 페이지 배치표

| 페이지 id | 라벨 | 카테고리 | 관리자 | 총괄 | 팀원 | 배치 근거 |
|---|---|---|---|---|---|---|
| `dashboard` | 대시보드 | (pinned) | ● | ● | ● | 시작점 |
| `infrastructure` | 인프라스트럭처 | 모델·라우팅 | ● | | | 사내 Runtime·노드·배포 모델 연결 = 모델 공급원 |
| `external` | 외부 AI | 모델·라우팅 | ● | | | 클라우드 모델 공급원·Failover |
| `services` | LLM 서비스 | 모델·라우팅 | ● | | | 논리 모델과 라우팅 정책 |
| `flow-designer` | 워크플로우 디자이너 | 모델·라우팅 | ● | | | 라우팅 워크플로우 구성(실험) |
| `system` | 시스템 구조 | 모델·라우팅 | ● | | | 구성된 요청 경로·Target·연결 상태의 지도. 구성 결과를 확인하는 화면이라 구성 옆에 둔다 |
| `projects` | 프로젝트 | 조직·보안 | ● | ● | | 멤버·권한·API 키 = 접근 관리의 중심 |
| `teams` | 팀과 부서 | 조직·보안 | ● | | | 부서·역할 |
| `relationship` | 조직 관계도 | 조직·보안 | ● | ● | ● | 조직 계층 |
| `data-protection` | 데이터 보호 | 조직·보안 | ● | ● | | 데이터 경계(DLP) 정책 = 접근 정책 |
| `audit` | 감사 로그 | 조직·보안 | ● | | | 변경·열람 증적 |
| `observability` | 관측성 | 관측·비용 | ● | ● | ● | 요청·장애·Failover 추적 |
| `usage` | 사용량 / API 사용량 | 관측·비용 | ● | ● | ● | 토큰·비용 |
| `quotas` | 요금·한도 | 관측·비용 | ● | ● | ● | 사용량을 보고 바로 예산·한도를 정하는 흐름 |
| `notifications` | 알림 채널 | 관측·비용 | ● | | | Endpoint 장애·복구 이벤트를 Discord/Telegram으로 전송(`NotificationsPage` 설명) = 관측의 push 측면 |
| `portal` | 내 API | 개발자 도구 | ● | ● | ● | 키·Base URL·모델명. API 테스트와 짝 |
| `playground` | API 테스트 | 개발자 도구 | ● | ● | ● | |
| `model-playground` | 모델 테스트 콘솔 | 개발자 도구 | ● | | | |
| `platform` | Platform Admin | 플랫폼 운영 | 플랫폼 관리자만 | | | 기존 규칙 유지 |
| `beta` | 초대 서비스 운영 | 플랫폼 운영 | 플랫폼 관리자 또는 베타 운영자 | 베타 운영자 | 베타 운영자 | 기존 규칙 유지(`platformAdmin \|\| betaOperator`) |
| `docs` | Dev-Docs | (고정 영역 “도움말”) | ● | ● | ● | 변경 없음 |

역할별 노출 집합은 **기존과 완전히 같다**(테스트 `role lists keep the pre-redesign access boundaries`가 고정). 바뀌는 것은 분류·순서·표시 방식뿐이다.

### 2.4 역할별 트리

```
플랫폼 관리자 (20 + Dev-Docs)                 조직 관리자 (18, 베타 운영자면 +초대 서비스 운영)
◔ 대시보드                                    ◔ 대시보드
▣ 모델·라우팅 (5)                             ▣ 모델·라우팅 (5)
   인프라스트럭처 · 외부 AI · LLM 서비스          ◈ 조직·보안 (5)
   워크플로우 디자이너 · 시스템 구조             ◉ 관측·비용 (4)
◈ 조직·보안 (5)                               ▷ 개발자 도구 (3)
   프로젝트 · 팀과 부서 · 조직 관계도            (⚡ 플랫폼 운영 (1) — 베타 운영자일 때만)
   데이터 보호 · 감사 로그
◉ 관측·비용 (4)
   관측성 · 사용량 · 요금·한도 · 알림 채널
▷ 개발자 도구 (3)
   내 API · API 테스트 · 모델 테스트 콘솔
⚡ 플랫폼 운영 (2)
   Platform Admin · 초대 서비스 운영

프로젝트 총괄 (9)                             팀원 (7)
◔ 대시보드                                    ◔ 대시보드
◈ 조직·보안 (3)                               ◈ 조직·보안 (1)
   프로젝트 · 조직 관계도 · 데이터 보호            조직 관계도
◉ 관측·비용 (3)                               ◉ 관측·비용 (3)
   관측성 · API 사용량 · 요금·한도                 관측성 · API 사용량 · 요금·한도
▷ 개발자 도구 (2)                             ▷ 개발자 도구 (2)
   내 API · API 테스트                            내 API · API 테스트
(⚡ 플랫폼 운영 (1) — 베타 운영자일 때만)        (⚡ 플랫폼 운영 (1) — 베타 운영자일 때만)
```

### 2.5 명명 근거와 대안

- **모델·라우팅**: 인프라 페이지 제목이 “인프라와 모델 운영”이고 외부 AI·LLM 서비스·워크플로우가 모두 “어떤 모델로 어떻게 보낼지”를 다룬다. 대안: `AI 연결·라우팅`, `모델·인프라`.
- **조직·보안**: 프로젝트·팀·관계도(누가)와 데이터 보호·감사(정책·증적)를 함께 묶는 짧은 라벨. 팀원에게는 `조직 관계도` 1개만 보이지만 위치 일관성을 우선했다. 대안: `조직·접근 관리`, `조직·거버넌스`. **라벨에 `프로젝트`를 넣지 않는다**: 거버넌스 테스트가 버튼 텍스트 `startsWith('프로젝트')`로 팀원 화면의 프로젝트 메뉴 부재를 검사하고, 검색에서 카테고리명이 페이지명과 겹치지 않아야 하기 때문이다(테스트 `category labels never collide with page labels`).
- **페이지 라벨은 바꾸지 않는다**(`Platform Admin` 영문, 관리자 `사용량` vs 총괄·팀원 `API 사용량` 등). 테스트와 Dev-Docs 본문(`frontend/src/dev-docs/roleGuides.ts`)이 라벨을 참조하므로 별도 작업으로 다룬다(§8).
- **아이콘**: 하위 항목은 아이콘을 표시하지 않으므로 중복 문제(P5)가 사이드바에서는 사라진다. 기능 검색에는 계속 표시되므로 같은 카테고리 안 중복 1건(`teams`/`projects` 모두 `⌘`)만 `teams` → `◫`로 바꾼다.

---

## 3. 상호작용 설계

### 3.1 펼침/접힘 규칙

| 모드 | 적용 역할 | 처음 상태 | 헤더 클릭 | 다른 카테고리 페이지로 이동 |
|---|---|---|---|---|
| `single` (아코디언) | 관리자(조직·플랫폼) | 현재 페이지의 카테고리만 열림. 대시보드·Dev-Docs면 모두 닫힘 | 닫혀 있으면 그것만 열고 나머지를 닫음. 열려 있으면 닫음(모두 닫힌 상태 허용) | 그 카테고리를 열고 나머지를 닫음 |
| `multi` | 프로젝트 총괄, 팀원 | 모든 카테고리 열림 | 그 카테고리만 토글 | 닫혀 있으면 열고, 다른 것은 건드리지 않음 |

**근거**
- 관리자는 18~20개라 “한 번에 많이 보이는 부담”이 실제로 생기는 유일한 역할이다. 아코디언이면 언제나 `고정 1 + 헤더 4~5 + 열린 카테고리 ≤5 = 최대 11행`이 보장된다. 여러 개를 열 수 있게 하면 사용하면서 결국 다 열린 상태로 돌아간다.
- 카테고리 간 이동 비용은 아코디언이든 아니든 “헤더 1클릭 + 항목 1클릭”으로 같다. 자주 쓰는 다른 카테고리 페이지는 기능 검색(⌘K) 한 번으로 간다.
- 총괄(9개)·팀원(7개)은 모두 펼쳐도 1080p에서 스크롤 없이 들어간다. 아코디언을 적용하면 주력 화면(`내 API`)까지 매번 2클릭이 되어 오히려 나빠진다. 카테고리 헤더는 시각적 묶음 역할만 하고, 원하면 접을 수 있다.
- 모드는 `navExpansionMode(role)` 한 함수에서 정한다. 관리자도 다중 펼침으로 바꾸려면 이 함수 한 줄만 고치면 된다(테스트 1건 수정).

### 3.2 기본값과 영속성

- **저장하지 않는다**(localStorage·sessionStorage 미사용). 아코디언에서는 펼침 상태가 사실상 “현재 페이지의 카테고리”이므로 라우트에서 매번 계산할 수 있다. `multi`는 기본이 “모두 열림”이라 저장할 정보가 거의 없다.
- 저장을 안 하면 생기지 않는 문제: 시크릿 모드·저장소 차단 예외, 역할 전환 시 다른 역할의 상태가 섞임, IA 변경 후 남은 옛 카테고리 id, 비공개 브라우저 캡처 중 빈 상태.
- 세션 정보를 비동기로 불러오는 동안 역할이 바뀌면(처음에는 팀원 목록으로 그렸다가 `/api/portal/session` 응답 후 관리자 목록으로 바뀜) `mode`와 카테고리 구성으로 만든 키(`layoutKey`)가 바뀌므로 그 역할의 기본 상태로 다시 초기화한다. 베타 운영자 확인 응답으로 `플랫폼 운영`이 나중에 생길 때도 같다.

### 3.3 활성 페이지 동작

- `SideNav`는 `activeId`(=ConsoleShell의 `page`)를 감시한다. **사이드바 클릭, 기능 검색, 대시보드 바로가기, `@navigate` 이벤트, 해시 직접 입력, 새로고침 딥링크(`#model-playground/<id>` 포함), 허용되지 않은 페이지의 대시보드 대체** 모두 결국 `page`를 바꾸므로 한 경로로 처리된다. 바뀐 페이지의 카테고리가 닫혀 있으면 연다(`revealActive`).
- 현재 페이지가 들어 있는 카테고리 헤더에는 `has-active` 클래스를 붙여 헤더 아이콘을 accent 색으로 표시한다. 아코디언에서 다른 카테고리를 둘러보느라 현재 카테고리가 닫혀도 “지금 여기”가 보인다.
- 활성 항목에는 `aria-current="page"`를 단다.
- 활성 항목이 메뉴 스크롤 영역 밖이면(작은 화면, 135%) 메뉴 영역의 `scrollTop`만 조정해 보이게 한다. `scrollIntoView`는 쓰지 않는다. 모바일 서랍은 `transform`으로 화면 밖에 있어 창 자체가 스크롤될 수 있기 때문이다. CSS `zoom` 아래에서도 `offsetTop`/`scrollTop`/`clientHeight`가 같은 좌표계라 브라우저에서 정상 동작함을 확인했다(부록 A).

### 3.4 검색(⌘K 기능 검색)과의 관계

- 검색 대상은 계속 `allNavigation = [...navItems, docsNavItem]`이다. **사이드바 펼침 상태와 완전히 독립**이므로 접힌 카테고리의 페이지도 그대로 찾는다.
- 검색어는 `라벨 + 설명 + 키워드 + 카테고리 라벨`과 비교한다(대소문자 무시). 예: `보안` → 조직·보안의 5개, `discord` → 알림 채널.
- 결과의 보조 줄을 `카테고리 · 설명`으로 바꾼다(대시보드 `홈`, Dev-Docs `도움말`). 결과 순서는 사이드바 순서와 같다(항목 배열을 사이드바 순서로 정의).
- 검색으로 이동하면 §3.3에 따라 해당 카테고리가 자동으로 열린다.
- 팔레트의 키보드 이동(↑↓/Enter)은 현재도 없고 이번 범위가 아니다(§8).

### 3.5 설명(description) 표시

- 사이드바 하위 항목은 **라벨 한 줄만** 보여 준다. 설명은 ① 버튼의 `title` 툴팁(마우스 호버, 보조기기는 accessible description으로 읽음), ② 기능 검색 결과에 남는다.
- 활성 항목에만 설명을 보여 주는 방식은 쓰지 않는다. 이동할 때마다 행 높이가 바뀌어 목록이 흔들리고, 브레드크럼이 이미 위치를 알려 준다.
- 카테고리 헤더 옆 숫자(항목 수)는 펼치기 전에 크기를 가늠하게 한다(`aria-hidden`).

### 3.6 브레드크럼

`AiConnect / {카테고리} / {페이지}`로 바꾼다(대시보드 `홈`, Dev-Docs `도움말`). 아코디언으로 현재 카테고리가 닫혀 있어도 위치를 알 수 있다. 760px 이하에서는 기존 규칙(`.breadcrumb span, .breadcrumb b { display:none }`)에 따라 페이지명만 남는다.

### 3.7 모바일·배율

- 1020px 이하 서랍: 동작은 데스크톱과 같다. 헤더 토글은 서랍을 닫지 않고, 페이지 이동만 닫는다(`navigate()` 기존 동작).
- 터치 대상: `(max-width: 1020px), (pointer: coarse)`에서 헤더·항목 최소 높이 42px.
- 모든 치수는 px라 `.console-shell { zoom }`에 함께 비례한다. JS 측정은 §3.3의 스크롤 보정뿐이며 같은 zoom 좌표계 안에서만 계산한다.

### 3.8 키보드·접근성

| 요소 | 마크업 |
|---|---|
| 메뉴 | `<nav class="side-nav" aria-label="주요 기능">` (이름 유지, 테스트가 첫 `<nav>`를 찾음) |
| 고정 항목 | `<button type="button" class="nav-item nav-pinned" :aria-current :title>` |
| 카테고리 헤더 | `<button type="button" class="nav-category-toggle" id="nav-category-{id}" aria-expanded="true/false" aria-controls="nav-category-items-{id}">` (디스클로저 패턴) |
| 카테고리 본문 | `<div id="nav-category-items-{id}" role="group" aria-labelledby="nav-category-{id}" :hidden>` |
| 하위 항목 | `<button type="button" class="nav-item" :aria-current :title>` |

- 포커스 순서: 문서 순서 그대로(고정 → 헤더 → 열린 하위 항목 → 다음 헤더 …). 닫힌 본문은 `hidden`(display:none)이라 Tab에서 빠진다. 별도 roving tabindex·화살표 키는 두지 않는다(표준 디스클로저 패턴은 Tab/Enter/Space로 충분).
- 헤더 Enter/Space → 토글(네이티브 button). 토글 후에도 포커스는 헤더에 남는다.
- 포커스 링: 전역 `button:focus-visible { outline: 2px … offset 2px }`는 `.side-nav`의 `overflow-y:auto`에 잘리므로 `.side-nav button:focus-visible { outline-offset: -2px }`로 안쪽에 그린다.
- 장식 요소(아이콘, 개수, 꺾쇠, 활성 막대)는 `aria-hidden="true"`.
- 움직임: 꺾쇠 회전·활성 막대만 transition. 기존 `prefers-reduced-motion` 전역 규칙이 자동으로 꺼 준다. 높이 애니메이션 없음.

### 3.9 이번에 하지 않는 것

- 아이콘 전용 접힌 사이드바: 워크스페이스 선택 카드·Dev-Docs 카드까지 재설계가 필요하고 플라이아웃 메뉴가 필요해 비용 대비 이득이 작다.
- 펼침 상태 저장, 사이드바 안 검색 입력(⌘K가 이미 있음), 화살표 키 탐색, 팔레트 키보드 이동, 라벨 문구 정리.

---

## 4. 시각 설계

### 4.1 치수·토큰 (기존 CSS 변수만 사용)

| 요소 | 치수 | 색·상태 |
|---|---|---|
| 고정 항목 `.nav-pinned` | 최소 높이 38px, padding 6px 8px, 그리드 `24px 1fr 3px`, gap 9px, 아래 여백 6px | 글자 `--text-soft` 700 12px |
| 카테고리 헤더 `.nav-category-toggle` | 최소 높이 38px, 그리드 `24px 1fr auto 12px` | 글자 `--text-soft` 700 12px, hover `--surface-2`/`--text`, `has-active`면 글자 `--text` + 아이콘 `--accent-border`/`--accent-dim`/`--accent-strong` |
| 아이콘 상자 `.side-nav .nav-icon` | 24×24, radius 7px, 12px | 기존 `.nav-icon`(29px, 팔레트·Dev-Docs용)은 그대로 |
| 개수 배지 `.nav-category-count` | 높이 18px, 최소 폭 20px, pill | 테두리 `--border`, 글자 `--faint` 800 10px |
| 꺾쇠 `.nav-category-chevron` | `›` 14px | `--faint`, `aria-expanded='true'`면 90° 회전(.16s) |
| 하위 목록 `.nav-category-items` | 왼쪽 여백 20px(헤더 아이콘 중심선) + 안내선 `border-left: 1px solid var(--border)`, padding-left 6px, gap 2px, 아래 여백 8px | `[hidden]`이면 `display:none` (**필수**: `display:grid`가 UA의 `[hidden]`을 덮어씀) |
| 하위 항목 `.nav-category-items .nav-item` | 최소 높이 32px, padding `6px 8px 6px 14px`, 그리드 `1fr 3px` | 글자 `--muted` 600 12px, hover `--surface-2`/`--text`, active `--accent-dim` 배경 + `--text` + 오른쪽 막대 18px `--accent-strong` 발광 |
| 터치 | `(max-width:1020px), (pointer:coarse)`에서 위 세 요소 최소 높이 42px | |

라벨의 텍스트 시작점이 헤더 라벨과 하위 항목에서 같은 x(41px)에 맞도록 여백을 계산했다: 헤더 8 + 아이콘 24 + gap 9 = 41, 하위 = 여백 20 + 선 1 + padding 6 + 항목 padding 14 = 41.

### 4.2 ASCII 목업

관리자(플랫폼 관리자, 현재 페이지 `사용량`, 다크):

```
┌ sidebar 270px ─────────────────────────┐
│ [logo] AiConnect                        │
│        LLM CONTROL PLANE                │
│ ┌ WORKSPACE ─────────────────────────┐  │
│ │ [ Acme · ACTIVE              ⌄ ]   │  │
│ └────────────────────────────────────┘  │
│  ◔  대시보드                             │
│  ▣  모델·라우팅                  (5)  › │
│  ◈  조직·보안                    (5)  › │
│ [◉] 관측·비용                    (4)  ⌄ │ ← has-active: 아이콘 accent
│   │  관측성                             │
│   │ ░사용량░░░░░░░░░░░░░░░░░░░░░░░░░░▐ │ ← active: accent-dim + 오른쪽 막대
│   │  요금·한도                          │
│   │  알림 채널                          │
│  ▷  개발자 도구                  (3)  › │
│  ⚡ 플랫폼 운영                  (2)  › │
├─────────────────────────────────────────┤
│ 도움말                                   │
│ </> Dev-Docs  제품 가이드 · API 참조     │
├─────────────────────────────────────────┤
│ ● Gateway online                        │
└─────────────────────────────────────────┘
topbar:  AiConnect / 관측·비용 / 사용량
```

팀원(현재 페이지 `내 API`, 모두 펼침):

```
 ◔  대시보드
 ◈  조직·보안          (1) ⌄
   │  조직 관계도
 ◉  관측·비용          (3) ⌄
   │  관측성
   │  API 사용량
   │  요금·한도
[▷] 개발자 도구        (2) ⌄
   │ ░내 API░░░░░░░░░░░░░▐
   │  API 테스트
```

### 4.3 이번에 바꾸지 않는 시각 요소

브랜드, 워크스페이스 카드, `.sidebar-support`(Dev-Docs 카드, 설명 포함), `.sidebar-foot`, 사이드바 폭 270px, 테마 토큰.

---

## 5. 데이터 모델·모듈 설계

### 5.1 `frontend/src/consoleNavigation.ts` (순수 모듈: Vue·DOM·저장소 접근 없음)

```ts
export type NavPageId = 'beta' | 'dashboard' | … | 'model-playground'   // ConsoleShell PageKey에서 'docs'를 뺀 것과 같은 집합
export type NavRole = 'admin' | 'director' | 'developer'
export type NavCategoryId = 'models' | 'access' | 'observe' | 'devtools' | 'platform'
export type NavExpansionMode = 'single' | 'multi'
export type NavItem = { id: NavPageId; label: string; description: string; keywords: string; icon: string }   // group 필드 삭제
export type NavCategory = { id: NavCategoryId; label: string; icon: string; items: NavItem[] }
export type NavSections = { pinned: NavItem[]; categories: NavCategory[] }
export type NavRoleInput = { role: NavRole; platformAdmin: boolean; betaOperator: boolean }
export type SearchableNavItem = { id: string; label: string; description: string; keywords: string }

export const NAV_CATEGORIES: ReadonlyArray<{ id: NavCategoryId; label: string; icon: string }>   // 표시 순서
export const PAGE_CATEGORY: Readonly<Record<NavPageId, NavCategoryId | null>>                    // 분류의 유일한 원천, null = pinned
export const adminNavItems, directorNavItems, developerNavItems: NavItem[]                        // 사이드바 순서로 정의
export const platformNavItem, betaNavItem: NavItem
export const docsNavItem: { id: 'docs'; label; description; keywords }

export function navItemsFor(input: NavRoleInput): NavItem[]            // 역할 필터. 기존 규칙과 동일
export function categoryOf(id: string): NavCategoryId | null           // 알 수 없는 id·docs·dashboard → null (hasOwnProperty로 'constructor' 등 차단)
export function navCategoryLabel(id: string): string                   // dashboard→'홈', docs→'도움말', 그 외 카테고리 라벨 또는 ''
export function buildNavSections(items: readonly NavItem[]): NavSections   // 빈 카테고리 제거, 입력 순서 유지
export function navExpansionMode(role: NavRole): NavExpansionMode      // admin→'single', 그 외 'multi'
export function initialExpanded(sections: NavSections, activeId: string, mode: NavExpansionMode): NavCategoryId[]
export function toggleExpanded(expanded: NavCategoryId[], id: NavCategoryId, mode: NavExpansionMode): NavCategoryId[]   // 입력 불변
export function revealActive(expanded: NavCategoryId[], activeId: string, mode: NavExpansionMode): NavCategoryId[]      // 변화 없으면 같은 배열 반환
export function searchNavItems<T extends SearchableNavItem>(items: readonly T[], query: string): T[]
```

전체 코드는 WP-1에 있다. 펼침 상태는 “열린 카테고리 id 배열” 하나로 표현하고, 모든 변경은 새 배열을 돌려주는 순수 함수로 처리한다. 변화가 없으면 같은 참조를 돌려주어 Vue ref가 불필요하게 갱신되지 않게 한다.

### 5.2 `frontend/src/SideNav.vue`

| 구분 | 정의 |
|---|---|
| props | `sections: NavSections`, `activeId: string`(PageKey 문자열, `docs` 포함 가능), `mode: NavExpansionMode` |
| emits | `navigate: [id: NavPageId]` |
| 상태 | `expanded = ref<NavCategoryId[]>([])` |
| watch 1 | `layoutKey = mode + 카테고리 id 목록` 변경 시(immediate) → `initialExpanded` |
| watch 2 | `activeId` 변경 시 → `revealActive` |
| watch 3 | `[activeId, layoutKey]` 변경 후(`flush:'post'`)와 `onMounted` → 활성 행 스크롤 보정(`scrollTop`만) |
| 금지 | `v-show`, `<Transition>`, `v-html`, `localStorage`/`sessionStorage`, `scrollIntoView` |

### 5.3 `ConsoleShell.vue` 연동

```ts
const navRole = computed<NavRole>(() => isOrgOrPlatformAdmin.value ? 'admin' : isProjectDirector.value ? 'director' : 'developer')
const navItems = computed(() => navItemsFor({ role: navRole.value, platformAdmin: platformAdmin.value, betaOperator: betaOperator.value }))
const navSections = computed(() => buildNavSections(navItems.value))
const navMode = computed(() => navExpansionMode(navRole.value))
```

`navItems`의 이름과 의미(허용 페이지 목록)는 유지되므로 `isAllowedPage`, `ensureAllowedPage`, `navigate`, `openModelPlayground`, `currentNav`는 **수정하지 않는다**. `PageKey`, `knownPage`, 페이지 렌더 조건(`v-else-if`)도 그대로다. `navigate(target: PageKey)`는 `NavPageId`(PageKey의 부분집합)를 받으므로 `@navigate="navigate"`로 바로 연결된다.

---

## 6. 엣지 케이스

| # | 상황 | 기대 동작 | 보장 수단 |
|---|---|---|---|
| E1 | 세션 정보 로딩 전(팀원 목록) → 로딩 후 관리자 목록 | 관리자 기본 상태(현재 페이지 카테고리만 열림)로 재초기화 | `layoutKey` watch, 테스트 `SideNav resets to the role default…` |
| E2 | 베타 운영자 확인이 늦게 도착해 `플랫폼 운영` 추가 | 카테고리 구성이 바뀌어 재초기화. `multi`면 새 카테고리도 열림 | 같음 |
| E3 | `#teams`로 딥링크했지만 총괄이라 허용 안 됨 | 기존대로 `#dashboard`로 대체. 사이드바는 대시보드 활성, 카테고리 변화 없음 | `ensureAllowedPage` 무변경, 거버넌스 테스트 |
| E4 | 기능 검색으로 닫힌 카테고리의 페이지로 이동 | 그 카테고리가 열리고(관리자는 나머지 닫힘) 활성 행이 보이게 스크롤 | `revealActive` + 스크롤 보정 |
| E5 | 관리자가 현재 카테고리를 직접 닫음 | 닫힌 채 유지. 헤더 아이콘 accent로 위치 표시. 같은 페이지 재클릭(검색)은 `page`가 바뀌지 않아 열리지 않음(의도) | `has-active` |
| E6 | 현재 페이지가 `dashboard`/`docs` | 관리자는 모두 닫힘, 다른 역할은 모두 열림. 이동해도 펼침 상태 유지 | `categoryOf`가 null → `revealActive` 무변경 |
| E7 | `#model-playground/<id>` 딥링크 | `page='model-playground'` → `개발자 도구` 열림. playground target 처리는 기존 코드 그대로 | `onHashChange` 무변경 |
| E8 | 조직 전환(`selectOrganization`) | 역할 목록이 바뀌면 E1과 같고, 아니면 상태 유지 | |
| E9 | 모바일 서랍이 닫힌 상태에서 페이지 이동 | 서랍 안 메뉴만 스크롤 보정, 문서 스크롤 없음 | `scrollTop`만 사용 |
| E10 | 135% + 768px 높이 | 메뉴 영역이 약 190px. 활성 행은 스크롤 보정으로 보임. 전체가 들어가지는 않음(§8 R4) | |
| E11 | 비상 플랫폼 토큰 세션(`platformTokenSession`) | `platformAdmin`이 true → 관리자 + Platform Admin + 초대 서비스 운영 | `navItemsFor` 테스트 |
| E12 | 새 페이지 id를 `PageKey`에 추가 | 메뉴에 넣으려면 `NavPageId`와 `PAGE_CATEGORY`에 함께 추가해야 함. 빠뜨리면 `Record` 타입 오류 | 타입 + 테스트 `every navigable page maps…` |
| E13 | 터치 기기 | 툴팁 없음 → 설명은 기능 검색에서 확인 | §8 R3 |
| E14 | 같은 id가 두 번 들어간 목록 | 허용하지 않음 | 테스트 `no duplicates` |
| E15 | 라이트/다크 전환 | 토큰만 사용하므로 즉시 반영 | 브라우저 확인 |

---

## 7. 작업 지시서 (Sonnet 구현용)

### 7.0 공통 규칙

1. 작업 위치는 git worktree `C:\project\aiconnect-sidebar`(브랜치 `claude/sidebar-nested-nav`)뿐이다. `C:\project\aiconnect` 등 다른 worktree는 건드리지 않는다. 명령은 `C:\project\aiconnect-sidebar\frontend`에서 실행한다.
2. **이 문서의 코드를 그대로 옮긴다.** 이름·문구·클래스·정규식을 바꾸지 않는다. 바꿔야 할 것 같으면 멈추고 보고한다.
3. 새 파일은 전체 내용을 그대로 쓴다. 기존 파일은 “찾을 문자열 → 바꿀 문자열”로 수정한다. 찾을 문자열은 파일 안에서 **정확히 1번** 나와야 한다(아니면 멈추고 보고).
4. 새 npm 의존성 금지. 백엔드 변경 금지. 새 파일 줄바꿈은 LF(`.gitattributes`가 `*.vue/*.ts/*.css/*.json/*.yml eol=lf`). 기존 `frontend/tests/*.mjs`는 작업 트리에서 CRLF다(`text=auto`). Edit 도구로 고치면 그대로 두어도 되고, 스크립트로 치환한다면 CRLF를 고려한다.
5. 다음은 **수정 금지**: `PageKey` 타입, `knownPage`, `isAllowedPage`, `fallbackPage`, `resolveHash`, `ensureAllowedPage`, `navigate`, `openModelPlayground`, `onHashChange`, `onGlobalKeydown`, 페이지 렌더 `v-if/v-else-if` 줄들, `.sidebar-support`(Dev-Docs) 마크업, 기존 `.nav-icon` 규칙.
6. 각 WP가 끝날 때마다 해당 WP의 “확인” 명령을 실행하고 통과해야 다음으로 넘어간다. 커밋은 부모 에이전트/사용자 지시에 따른다.
7. `node_modules`가 없으면 `npm ci`를 먼저 실행한다.

### WP-0 · 기준선 확인

```bash
cd /c/project/aiconnect-sidebar/frontend
git status --short          # 이 문서 외 변경이 없어야 함
npx vue-tsc --noEmit
npm run test:project-governance && npm run test:flow-designer && npm run test:beta-operator
```

### WP-1 · 순수 모듈 `frontend/src/consoleNavigation.ts` 새로 만들기

아래 내용 그대로 새 파일을 만든다. 메뉴 항목 문구(라벨·설명·키워드·아이콘)는 기존 `ConsoleShell.vue`와 같고, 다른 점은 ① `group` 필드 삭제, ② 배열 순서를 사이드바 순서로 정렬, ③ `teams` 아이콘 `⌘` → `◫`, ④ 인라인이던 beta 항목을 `betaNavItem` 상수로 분리한 것뿐이다.

파일: `frontend/src/consoleNavigation.ts`

```ts
// Console sidebar information architecture. Pure data and functions only:
// no Vue, DOM or storage access, so node --test can import it directly.

export type NavPageId =
  | 'beta' | 'dashboard' | 'relationship' | 'infrastructure' | 'system' | 'external' | 'services'
  | 'flow-designer' | 'teams' | 'projects' | 'observability' | 'usage' | 'quotas' | 'data-protection'
  | 'notifications' | 'audit' | 'platform' | 'portal' | 'playground' | 'model-playground'
export type NavRole = 'admin' | 'director' | 'developer'
export type NavCategoryId = 'models' | 'access' | 'observe' | 'devtools' | 'platform'
export type NavExpansionMode = 'single' | 'multi'
export type NavItem = { id: NavPageId; label: string; description: string; keywords: string; icon: string }
export type NavCategory = { id: NavCategoryId; label: string; icon: string; items: NavItem[] }
export type NavSections = { pinned: NavItem[]; categories: NavCategory[] }
export type NavRoleInput = { role: NavRole; platformAdmin: boolean; betaOperator: boolean }
export type SearchableNavItem = { id: string; label: string; description: string; keywords: string }

/** Sidebar category order. Empty categories are omitted per role. */
export const NAV_CATEGORIES: ReadonlyArray<{ id: NavCategoryId; label: string; icon: string }> = [
  { id: 'models', label: '모델·라우팅', icon: '▣' },
  { id: 'access', label: '조직·보안', icon: '◈' },
  { id: 'observe', label: '관측·비용', icon: '◉' },
  { id: 'devtools', label: '개발자 도구', icon: '▷' },
  { id: 'platform', label: '플랫폼 운영', icon: '⚡' }
]

/** One category per page for every role. null = pinned above the categories. */
export const PAGE_CATEGORY: Readonly<Record<NavPageId, NavCategoryId | null>> = {
  dashboard: null,
  infrastructure: 'models',
  external: 'models',
  services: 'models',
  'flow-designer': 'models',
  system: 'models',
  projects: 'access',
  teams: 'access',
  relationship: 'access',
  'data-protection': 'access',
  audit: 'access',
  observability: 'observe',
  usage: 'observe',
  quotas: 'observe',
  notifications: 'observe',
  portal: 'devtools',
  playground: 'devtools',
  'model-playground': 'devtools',
  platform: 'platform',
  beta: 'platform'
}

// Item arrays are written in sidebar order (category order, then item order),
// so the command palette lists results in the same order as the sidebar.
export const adminNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '요청·장애·런타임 핵심 지표', keywords: 'overview health dashboard', icon: '◔' },
  { id: 'infrastructure', label: '인프라스트럭처', description: '노드·Runtime·배포 모델', keywords: 'server gpu lm studio endpoint model', icon: '◇' },
  { id: 'external', label: '외부 AI', description: 'OpenAI·승인·수동·자동 Failover', keywords: 'openai external provider cloud approval failover', icon: '◎' },
  { id: 'services', label: 'LLM 서비스', description: '논리 모델과 라우팅 정책', keywords: 'service model failover routing', icon: '▣' },
  { id: 'flow-designer', label: '워크플로우 디자이너', description: '노드와 간선으로 서비스 워크플로우 구성 · 실험', keywords: 'workflow designer flow canvas node edge graph pipeline routing 워크플로우 노드 간선 플로우 디자이너 배치 캔버스 실험 experimental', icon: '⌬' },
  { id: 'system', label: '시스템 구조', description: '요청 경로 · Target · 서버 연결 상태', keywords: '시스템 구조 연결도 상태 architecture structure topology infrastructure provider llm team project api key', icon: '~' },
  { id: 'projects', label: '프로젝트', description: '프로젝트·권한·API 키', keywords: 'project key quota credential', icon: '⌘' },
  { id: 'teams', label: '팀과 부서', description: '부서·역할·프로젝트 소유', keywords: 'team department role audit', icon: '◫' },
  { id: 'relationship', label: '조직 관계도', description: '최고 관리자·조직·프로젝트·구성원 계층도', keywords: 'organization relationship hierarchy chart tree member manager 조직 관계도 계층도', icon: '◈' },
  { id: 'data-protection', label: '데이터 보호', description: '민감정보 탐지·외부 전송 정책', keywords: 'security privacy dlp pii secret local only external block', icon: '◇' },
  { id: 'audit', label: '감사 로그', description: '관리자 변경·열람 증적', keywords: 'audit log admin change security', icon: '◎' },
  { id: 'observability', label: '관측성', description: '요청·장애·Failover 추적', keywords: 'request incident failover monitor', icon: '◉' },
  { id: 'usage', label: '사용량', description: '토큰·비용·API 호출 이력', keywords: 'usage token cost billing', icon: '◴' },
  { id: 'quotas', label: '요금·한도', description: '범위별 예산과 초과 차단', keywords: 'budget quota limit spend cost', icon: '₩' },
  { id: 'notifications', label: '알림 채널', description: 'Discord와 Telegram 연동', keywords: 'notification discord telegram alert', icon: '◇' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷' },
  { id: 'model-playground', label: '모델 테스트 콘솔', description: '등록된 Runtime·Provider 모델 직접 대화 테스트', keywords: 'model playground chat runtime provider streaming file test', icon: '◈' }
]
export const directorNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '접근 범위 요약', keywords: 'dashboard overview', icon: '◔' },
  { id: 'projects', label: '프로젝트', description: '프로젝트·멤버·권한·API 키', keywords: 'project key member delegation policy', icon: '⌘' },
  { id: 'relationship', label: '조직 관계도', description: '최고 관리자·조직·프로젝트·구성원 계층도', keywords: 'organization relationship hierarchy chart tree member manager 조직 관계도 계층도', icon: '◈' },
  { id: 'data-protection', label: '데이터 보호', description: '프로젝트·API 키 민감정보 정책', keywords: 'security privacy dlp pii secret local only', icon: '◇' },
  { id: 'observability', label: '관측성', description: '접근 범위 요청 이력', keywords: 'request monitor', icon: '◉' },
  { id: 'usage', label: 'API 사용량', description: '프로젝트·API 키 토큰·비용 이력', keywords: 'usage token cost request', icon: '◴' },
  { id: 'quotas', label: '요금·한도', description: '프로젝트 예산과 관리자 상한', keywords: 'budget quota limit spend cost ceiling', icon: '₩' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷' }
]
export const developerNavItems: NavItem[] = [
  { id: 'dashboard', label: '대시보드', description: '접근 범위 요약', keywords: 'dashboard overview', icon: '◔' },
  { id: 'relationship', label: '조직 관계도', description: '최고 관리자·조직·프로젝트·구성원 계층도', keywords: 'organization relationship hierarchy chart tree member manager 조직 관계도 계층도', icon: '◈' },
  { id: 'observability', label: '관측성', description: '내 API 키 요청 이력', keywords: 'request monitor', icon: '◉' },
  { id: 'usage', label: 'API 사용량', description: '내 API 키의 토큰·비용·요청', keywords: 'usage token cost request', icon: '◴' },
  { id: 'quotas', label: '요금·한도', description: '내 API 키 추정 비용', keywords: 'budget quota cost', icon: '₩' },
  { id: 'portal', label: '내 API', description: '프로젝트·사용 모델·API 키', keywords: 'my api project model key', icon: '⌘' },
  { id: 'playground', label: 'API 테스트', description: '모델 연결·Chat Completions 테스트', keywords: 'playground api test chat completion models', icon: '▷' }
]
export const platformNavItem: NavItem = { id: 'platform', label: 'Platform Admin', description: '조직·사용자·키·데이터 정리', keywords: 'platform admin organization user api key cleanup purge', icon: '⚡' }
export const betaNavItem: NavItem = { id: 'beta', label: '초대 서비스 운영', description: '초대·접근·운영 상태', keywords: 'beta invitation invite', icon: '◇' }
export const docsNavItem = { id: 'docs' as const, label: 'Dev-Docs', description: '제품 가이드 · API 참조', keywords: 'docs manual guide api lm studio' }

/** Role filtering. Keeps the pre-redesign access rules exactly. */
export function navItemsFor(input: NavRoleInput): NavItem[] {
  const base = input.role === 'admin'
    ? (input.platformAdmin ? [...adminNavItems, platformNavItem] : adminNavItems)
    : input.role === 'director' ? directorNavItems : developerNavItems
  return [...base, ...(input.platformAdmin || input.betaOperator ? [betaNavItem] : [])]
}

export function categoryOf(id: string): NavCategoryId | null {
  return Object.prototype.hasOwnProperty.call(PAGE_CATEGORY, id) ? PAGE_CATEGORY[id as NavPageId] : null
}

/** 'dashboard' → '홈', 'docs' → '도움말', otherwise the category label ('' when unknown). */
export function navCategoryLabel(id: string): string {
  if (id === 'docs') return '도움말'
  if (id === 'dashboard') return '홈'
  const category = categoryOf(id)
  return NAV_CATEGORIES.find(entry => entry.id === category)?.label ?? ''
}

export function buildNavSections(items: readonly NavItem[]): NavSections {
  return {
    pinned: items.filter(item => categoryOf(item.id) === null),
    categories: NAV_CATEGORIES
      .map(category => ({ ...category, items: items.filter(item => categoryOf(item.id) === category.id) }))
      .filter(category => category.items.length > 0)
  }
}

/** Admins see ~20 pages → accordion. Directors/developers see ≤10 → everything open. */
export function navExpansionMode(role: NavRole): NavExpansionMode {
  return role === 'admin' ? 'single' : 'multi'
}

export function initialExpanded(sections: NavSections, activeId: string, mode: NavExpansionMode): NavCategoryId[] {
  if (mode === 'multi') return sections.categories.map(category => category.id)
  const active = categoryOf(activeId)
  return active && sections.categories.some(category => category.id === active) ? [active] : []
}

export function toggleExpanded(expanded: NavCategoryId[], id: NavCategoryId, mode: NavExpansionMode): NavCategoryId[] {
  if (expanded.includes(id)) return expanded.filter(entry => entry !== id)
  return mode === 'single' ? [id] : [...expanded, id]
}

/** Opens the active page's category (sidebar click, palette, hash/deep link). Never closes on its own in multi mode. */
export function revealActive(expanded: NavCategoryId[], activeId: string, mode: NavExpansionMode): NavCategoryId[] {
  const active = categoryOf(activeId)
  if (!active || expanded.includes(active)) return expanded
  return mode === 'single' ? [active] : [...expanded, active]
}

/** Command palette search. Independent of sidebar collapse state; also matches the category label. */
export function searchNavItems<T extends SearchableNavItem>(items: readonly T[], query: string): T[] {
  const needle = query.trim().toLowerCase()
  if (!needle) return [...items]
  return items.filter(item => `${item.label} ${item.description} ${item.keywords} ${navCategoryLabel(item.id)}`.toLowerCase().includes(needle))
}
```

확인: `npx vue-tsc --noEmit` (아직 아무도 import하지 않아도 통과해야 함)

### WP-2 · 컴포넌트 `frontend/src/SideNav.vue` 새로 만들기

아래 내용 그대로 새 파일을 만든다. `<style>` 블록은 두지 않는다(스타일은 WP-4에서 전역 `style.css`에 둔다. 기존 사이드바 스타일이 전역이므로 같은 곳에 모은다).

파일: `frontend/src/SideNav.vue`

```vue
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  initialExpanded,
  revealActive,
  toggleExpanded,
  type NavCategory,
  type NavCategoryId,
  type NavExpansionMode,
  type NavPageId,
  type NavSections
} from './consoleNavigation'

const props = defineProps<{ sections: NavSections; activeId: string; mode: NavExpansionMode }>()
const emit = defineEmits<{ navigate: [id: NavPageId] }>()

const expanded = ref<NavCategoryId[]>([])
// Reset to the role default whenever the role (mode) or the visible category set changes,
// e.g. after the session context finishes loading and a developer list becomes an admin list.
const layoutKey = computed(() => `${props.mode}|${props.sections.categories.map(category => category.id).join(',')}`)

watch(layoutKey, () => { expanded.value = initialExpanded(props.sections, props.activeId, props.mode) }, { immediate: true })
watch(() => props.activeId, id => { expanded.value = revealActive(expanded.value, id, props.mode) })

// Keep the active row inside the nav's own scroll area (small screens / 135% font scale).
// Only nav.scrollTop changes, never the window, so the off-canvas mobile drawer cannot shift the page.
const navEl = ref<HTMLElement | null>(null)
function keepActiveVisible() {
  const container = navEl.value
  if (!container || typeof container.querySelector !== 'function') return
  const active = container.querySelector<HTMLElement>('.nav-item.active')
  if (!active) return
  const top = active.offsetTop - container.offsetTop
  const bottom = top + active.offsetHeight
  if (top < container.scrollTop) container.scrollTop = top
  else if (bottom > container.scrollTop + container.clientHeight) container.scrollTop = bottom - container.clientHeight
}
onMounted(keepActiveVisible)
watch([() => props.activeId, layoutKey], keepActiveVisible, { flush: 'post' })

function isExpanded(id: NavCategoryId) { return expanded.value.includes(id) }
function toggle(id: NavCategoryId) { expanded.value = toggleExpanded(expanded.value, id, props.mode) }
function containsActive(category: NavCategory) { return category.items.some(item => item.id === props.activeId) }
</script>

<template>
  <nav ref="navEl" class="side-nav" aria-label="주요 기능">
    <button
      v-for="item in sections.pinned"
      :key="item.id"
      type="button"
      class="nav-item nav-pinned"
      :class="{ active: activeId === item.id }"
      :title="item.description"
      :aria-current="activeId === item.id ? 'page' : undefined"
      @click="emit('navigate', item.id)"
    ><span class="nav-icon" aria-hidden="true">{{ item.icon }}</span><strong>{{ item.label }}</strong><i aria-hidden="true"></i></button>
    <section v-for="category in sections.categories" :key="category.id" class="nav-category">
      <button
        :id="`nav-category-${category.id}`"
        type="button"
        class="nav-category-toggle"
        :class="{ 'has-active': containsActive(category) }"
        :aria-expanded="isExpanded(category.id) ? 'true' : 'false'"
        :aria-controls="`nav-category-items-${category.id}`"
        @click="toggle(category.id)"
      ><span class="nav-icon" aria-hidden="true">{{ category.icon }}</span><strong>{{ category.label }}</strong><span class="nav-category-count" aria-hidden="true">{{ category.items.length }}</span><span class="nav-category-chevron" aria-hidden="true">›</span></button>
      <div
        :id="`nav-category-items-${category.id}`"
        class="nav-category-items"
        role="group"
        :aria-labelledby="`nav-category-${category.id}`"
        :hidden="!isExpanded(category.id)"
      >
        <button
          v-for="item in category.items"
          :key="item.id"
          type="button"
          class="nav-item"
          :class="{ active: activeId === item.id }"
          :title="item.description"
          :aria-current="activeId === item.id ? 'page' : undefined"
          @click="emit('navigate', item.id)"
        ><strong>{{ item.label }}</strong><i aria-hidden="true"></i></button>
      </div>
    </section>
  </nav>
</template>
```

주의(이유 포함):
- `v-show`, `<Transition>` 금지: 테스트용 메모리 DOM에 `style`/`classList`가 없어 렌더가 깨진다. 접힘은 `:hidden`으로 한다.
- 버튼 텍스트 노드 사이에 공백이 생기지 않도록 `>` 바로 뒤에 자식을 붙인 형태를 유지한다(테스트가 텍스트를 정확히 비교함).
- `keepActiveVisible`의 `typeof container.querySelector !== 'function'` 검사는 메모리 DOM 대비용이니 지우지 않는다.

확인: `npx vue-tsc --noEmit`

### WP-3 · `ConsoleShell.vue` 연결 + 기존 테스트 3종 수정 (한 WP로 함께 끝낸다)

#### 3-A. `frontend/src/ConsoleShell.vue`

**3-A-1. SideNav import 추가** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```ts
import ScopedOverviewPage from './ScopedOverviewPage.vue'
```

바꿀 문자열:

```ts
import ScopedOverviewPage from './ScopedOverviewPage.vue'
import SideNav from './SideNav.vue'
```

**3-A-2. consoleNavigation import 추가** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```ts
import { copyText } from './clipboard'
```

바꿀 문자열:

```ts
import { copyText } from './clipboard'
import { buildNavSections, docsNavItem, navCategoryLabel, navExpansionMode, navItemsFor, searchNavItems, type NavRole } from './consoleNavigation'
```

**3-A-3. 로컬 NavItem 타입 삭제** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```ts
type SessionContext = { platformAdmin: boolean; accountTier?: AccountTier; totpEnabled?: boolean; mfaState?: MfaState; memberships: Membership[] }
type NavItem = { id: Exclude<PageKey, 'docs'>; label: string; description: string; keywords: string; icon: string; group: string }
```

바꿀 문자열:

```ts
type SessionContext = { platformAdmin: boolean; accountTier?: AccountTier; totpEnabled?: boolean; mfaState?: MfaState; memberships: Membership[] }
```

**3-A-4. 메뉴 정의 블록 삭제**: `const adminNavItems: NavItem[] = [`로 시작하는 줄(기준 54번 줄)부터 `const docsItem = { id: 'docs' as const, …` 줄(기준 95번 줄)까지 **42줄을 통째로 삭제**한다. 바로 다음 줄 `const fontScaleOptions: …`와 그 바로 위 `const auth = computed<AdminAuth>(…)` 줄은 남긴다. 삭제한 이름(`adminNavItems`, `docsItem` 등)을 쓰던 곳은 3-A-5에서 함께 정리되므로, 3-A-5 전까지 생기는 타입 오류는 무시한다.

**3-A-5. navItems·groups 계산을 모듈 호출로 교체** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```ts
const navItems = computed(() => [
  ...(isOrgOrPlatformAdmin.value
    ? (platformAdmin.value ? [...adminNavItems, platformNavItem] : adminNavItems)
    : isProjectDirector.value
      ? directorNavItems
      : developerNavItems),
  ...(platformAdmin.value || betaOperator.value ? [{id:'beta' as const,label:'초대 서비스 운영',description:'초대·접근·운영 상태',keywords:'beta invitation invite',icon:'◇',group:'운영'}] : [])
].map(item => ({ ...item, group: item.id === 'dashboard' ? '홈'
  : ['relationship', 'projects', 'teams', 'portal', 'data-protection'].includes(item.id) ? '워크스페이스'
  : ['playground', 'model-playground'].includes(item.id) ? '개발자 도구'
  : ['observability', 'usage', 'quotas'].includes(item.id) ? '관측·비용' : '인프라·시스템' })))
const groups = computed(() => ['홈', '워크스페이스', '개발자 도구', '관측·비용', '인프라·시스템'].filter(group => navItems.value.some(item => item.group === group)))
const allNavigation = computed(() => [...navItems.value, docsItem])
const currentNav = computed(() => allNavigation.value.find(item => item.id === page.value) ?? navItems.value[0] ?? docsItem)
```

바꿀 문자열:

```ts
const navRole = computed<NavRole>(() => isOrgOrPlatformAdmin.value ? 'admin' : isProjectDirector.value ? 'director' : 'developer')
const navItems = computed(() => navItemsFor({ role: navRole.value, platformAdmin: platformAdmin.value, betaOperator: betaOperator.value }))
const navSections = computed(() => buildNavSections(navItems.value))
const navMode = computed(() => navExpansionMode(navRole.value))
const allNavigation = computed(() => [...navItems.value, docsNavItem])
const currentNav = computed(() => allNavigation.value.find(item => item.id === page.value) ?? navItems.value[0] ?? docsNavItem)
```

**3-A-6. 기능 검색 필터를 모듈 함수로 교체** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```ts
const searchResults = computed(() => {
  const query = search.value.trim().toLowerCase()
  return query ? allNavigation.value.filter(item => `${item.label} ${item.description} ${item.keywords}`.toLowerCase().includes(query)) : allNavigation.value
})
```

바꿀 문자열:

```ts
const searchResults = computed(() => searchNavItems(allNavigation.value, search.value))
```

**3-A-7. 사이드바 마크업 교체**: `      <nav class="side-nav" aria-label="주요 기능">`로 시작하고 `</nav>`로 끝나는 **한 줄 전체**(기준 393번 줄)를 아래 한 줄로 바꾼다(앞 공백 6칸 유지).

```vue
      <SideNav :sections="navSections" :active-id="page" :mode="navMode" @navigate="navigate" />
```

**3-A-8. 기능 검색 결과에 카테고리 표시 (긴 한 줄의 일부만 바꾼다)** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```vue
<small>{{ item.description }}</small></span><kbd>↵</kbd>
```

바꿀 문자열:

```vue
<small>{{ navCategoryLabel(item.id) }} · {{ item.description }}</small></span><kbd>↵</kbd>
```

**3-A-9. 브레드크럼에 카테고리 추가 (긴 한 줄의 일부만 바꾼다)** — `frontend/src/ConsoleShell.vue`

찾을 문자열:

```vue
<b>/</b><strong>{{ currentNav.label }}</strong>
```

바꿀 문자열:

```vue
<b>/</b><template v-if="navCategoryLabel(currentNav.id)"><span class="breadcrumb-category">{{ navCategoryLabel(currentNav.id) }}</span><b>/</b></template><strong>{{ currentNav.label }}</strong>
```

3-A 후 아래 명령 결과가 없어야 한다(새 이름 `docsNavItem`은 걸리지 않는 패턴이다).

```bash
grep -nE "\bgroups\b|nav-group|\bdocsItem\b|\b(admin|director|developer)NavItems\b|\bplatformNavItem\b|type NavItem" src/ConsoleShell.vue
```

#### 3-B. `frontend/tests/projectGovernance.test.mjs`

ConsoleShell이 `./consoleNavigation`과 `./SideNav.vue`를 import하게 되므로, 테스트 컴파일러가 이 둘을 해석하게 한다. `SideNav.vue`는 스텁이 아니라 **실제 컴포넌트를 컴파일**해서 넣는다(사이드바 텍스트·클릭 검사가 실제 마크업을 대상으로 해야 함).

**3-B-1. 컴파일 함수가 data URL 문자열을 돌려주도록 이름 변경** — `frontend/tests/projectGovernance.test.mjs`

찾을 문자열:

```js
function compileVueComponent(relativePath, id, extraReplacements = {}) {
  const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8')
```

바꿀 문자열:

```js
function compileVueComponentUrl(relativePath, id, extraReplacements = {}) {
  const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8')
```

**3-B-2. ./consoleNavigation import 해석 추가** — `frontend/tests/projectGovernance.test.mjs`

찾을 문자열:

```js
    .replace(/from ['"]\.\/externalCapabilities['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/externalCapabilities.ts')))
```

바꿀 문자열:

```js
    .replace(/from ['"]\.\/externalCapabilities['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/externalCapabilities.ts')))
    .replace(/from ['"]\.\/consoleNavigation['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/consoleNavigation.ts')))
```

**3-B-3. URL을 반환하고, 기존 이름 compileVueComponent는 import 래퍼로 유지** — `frontend/tests/projectGovernance.test.mjs`

찾을 문자열:

```js
  return import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))
}

function stubModuleUrl(exportCode) {
```

바꿀 문자열:

```js
  return 'data:text/javascript;base64,' + Buffer.from(script).toString('base64')
}

function compileVueComponent(relativePath, id, extraReplacements = {}) {
  return import(compileVueComponentUrl(relativePath, id, extraReplacements))
}

function stubModuleUrl(exportCode) {
```

**3-B-4. ConsoleShell 테스트에 실제 SideNav.vue 연결** — `frontend/tests/projectGovernance.test.mjs`

찾을 문자열:

```js
    '\\./WorkspaceRelationship\\.vue': emptyComponentStub('WorkspaceRelationship')
  }
  const { default: ConsoleShell } = await compileVueComponent('../src/ConsoleShell.vue', 'shell-gov-test', stubs)
```

바꿀 문자열:

```js
    '\\./WorkspaceRelationship\\.vue': emptyComponentStub('WorkspaceRelationship'),
    '\\./SideNav\\.vue': compileVueComponentUrl('../src/SideNav.vue', 'side-nav-gov-test')
  }
  const { default: ConsoleShell } = await compileVueComponent('../src/ConsoleShell.vue', 'shell-gov-test', stubs)
```

**3-B-5. 설명 텍스트 대신 title 속성으로 프로젝트 메뉴 찾기 (설명은 이제 툴팁)** — `frontend/tests/projectGovernance.test.mjs`

찾을 문자열:

```js
      walk(sideNav, el => el.tag === 'button' && textContent(el).includes('프로젝트·멤버·권한·API 키'))[0].props.onClick()
```

바꿀 문자열:

```js
      walk(sideNav, el => el.tag === 'button' && el.props.title === '프로젝트·멤버·권한·API 키')[0].props.onClick()
```

#### 3-C. `frontend/tests/flowDesignerAdapter.test.mjs`

**3-C-1. 메뉴 정의 검사를 consoleNavigation.ts 대상으로 변경** — `frontend/tests/flowDesignerAdapter.test.mjs`

찾을 문자열:

```js
const shell = readFileSync(new URL('../src/ConsoleShell.vue', import.meta.url), 'utf8')

test('ConsoleShell wires the flow designer page for organization and platform admins only', () => {
  assert.match(shell, /defineAsyncComponent\(\(\) => import\('\.\/flow-designer\/FlowDesignerPage\.vue'\)\)/)
  assert.match(shell, /type PageKey = [^\n]*'flow-designer'/)
  assert.match(shell, /function knownPage\(value: string\): value is PageKey \{\s*return \[[^\]]*'flow-designer'/)
  assert.match(shell, /<FlowDesignerPage v-else-if="isOrgOrPlatformAdmin && page === 'flow-designer'"/)
  const block = name => {
    const start = shell.indexOf(`const ${name}`)
    assert.ok(start >= 0, `${name} exists`)
    return shell.slice(start, shell.indexOf('\n]', start))
  }
  assert.match(block('adminNavItems: NavItem[]'), /id: 'flow-designer'/)
  assert.doesNotMatch(block('directorNavItems: NavItem[]'), /flow-designer/)
  assert.doesNotMatch(block('developerNavItems: NavItem[]'), /flow-designer/)
  assert.equal(shell.match(/id: 'flow-designer'/g).length, 1)
})
```

바꿀 문자열:

```js
const shell = readFileSync(new URL('../src/ConsoleShell.vue', import.meta.url), 'utf8')
const consoleNav = readFileSync(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8')

test('ConsoleShell wires the flow designer page for organization and platform admins only', () => {
  assert.match(shell, /defineAsyncComponent\(\(\) => import\('\.\/flow-designer\/FlowDesignerPage\.vue'\)\)/)
  assert.match(shell, /type PageKey = [^\n]*'flow-designer'/)
  assert.match(shell, /function knownPage\(value: string\): value is PageKey \{\s*return \[[^\]]*'flow-designer'/)
  assert.match(shell, /<FlowDesignerPage v-else-if="isOrgOrPlatformAdmin && page === 'flow-designer'"/)
  const block = name => {
    const start = consoleNav.indexOf(`const ${name}`)
    assert.ok(start >= 0, `${name} exists`)
    return consoleNav.slice(start, consoleNav.indexOf('\n]', start))
  }
  assert.match(block('adminNavItems: NavItem[]'), /id: 'flow-designer'/)
  assert.doesNotMatch(block('directorNavItems: NavItem[]'), /flow-designer/)
  assert.doesNotMatch(block('developerNavItems: NavItem[]'), /flow-designer/)
  assert.equal(consoleNav.match(/id: 'flow-designer'/g).length, 1)
  assert.doesNotMatch(shell, /id: 'flow-designer'/, 'nav items live only in consoleNavigation.ts')
})
```

**3-C-2. 워크플로우 용어 검사 대상 변경** — `frontend/tests/flowDesignerAdapter.test.mjs`

찾을 문자열:

```js
  const line = shell.split('\n').find(row => row.includes("id: 'flow-designer'"))
```

바꿀 문자열:

```js
  const line = consoleNav.split('\n').find(row => row.includes("id: 'flow-designer'"))
```

#### 3-D. `frontend/tests/betaOperator.test.mjs`

**3-D-1. 인라인 beta 객체 정규식을 모듈 기준 검사로 교체** — `frontend/tests/betaOperator.test.mjs`

찾을 문자열:

```js
  assert.match(shell, /platformAdmin\.value \|\| betaOperator\.value \? \[\{id:'beta' as const,label:'초대 서비스 운영'/)
```

바꿀 문자열:

```js
  const consoleNav = readFileSync(new URL('../src/consoleNavigation.ts', import.meta.url), 'utf8')
  assert.match(consoleNav, /export const betaNavItem: NavItem = \{ id: 'beta', label: '초대 서비스 운영'/)
  assert.match(consoleNav, /input\.platformAdmin \|\| input\.betaOperator \? \[betaNavItem\] : \[\]/)
  assert.match(shell, /navItemsFor\(\{ role: navRole\.value, platformAdmin: platformAdmin\.value, betaOperator: betaOperator\.value \}\)/)
```

`isAllowedPage` 정규식(바로 다음 줄)은 그대로 둔다. ConsoleShell의 `isAllowedPage` 본문이 바뀌지 않았으므로 계속 통과해야 한다.

확인:

```bash
npx vue-tsc --noEmit
npm run test:project-governance && npm run test:flow-designer && npm run test:beta-operator && npm run test:date-time && npm run test:totp-hierarchy
```

(이 시점에는 CSS가 아직 옛 규칙이라 화면이 어색해도 정상이다.)

### WP-4 · 스타일

#### 4-A. `frontend/src/style.css`

**4-A-1. 옛 `.nav-group` 규칙 10줄 삭제**: 아래 10줄을 삭제한다. 사이에 있는 `.nav-icon { width: 29px; … }` 줄은 **남긴다**(기능 검색·Dev-Docs가 사용).

```css
.nav-group { margin-bottom: 18px; }
.nav-group > p { margin: 0 10px 6px; color: var(--faint); font-size: 9px; font-weight: 800; letter-spacing: .18em; text-transform: uppercase; }
.nav-group button { position: relative; width: 100%; min-height: 48px; padding: 8px 10px; display: grid; grid-template-columns: 30px 1fr 3px; align-items: center; gap: 8px; border: 0; border-radius: 11px; background: transparent; color: var(--muted); text-align: left; }
.nav-group button:hover { background: var(--surface-2); color: var(--text-soft); }
.nav-group button.active { background: var(--accent-dim); color: var(--text); }
.nav-group button.active > i { height: 22px; border-radius: 3px; background: var(--accent-strong); box-shadow: 0 0 14px var(--accent-strong); }
.nav-group button.active .nav-icon { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.nav-group button > span:nth-child(2) { min-width: 0; display: grid; gap: 3px; }
.nav-group button strong { font-size: 11px; }
.nav-group button small { overflow: hidden; font-size: 8px; font-weight: 500; white-space: nowrap; text-overflow: ellipsis; }
```

**4-A-2. 새 사이드바 규칙을 `.nav-icon` 줄 바로 아래에 추가** — `frontend/src/style.css`

찾을 문자열:

```css
.nav-icon { width: 29px; height: 29px; display: inline-grid; place-content: center; border: 1px solid var(--border); border-radius: 9px; background: var(--surface); color: var(--text-soft); font-size: 14px; }
```

바꿀 문자열:

```css
.nav-icon { width: 29px; height: 29px; display: inline-grid; place-content: center; border: 1px solid var(--border); border-radius: 9px; background: var(--surface); color: var(--text-soft); font-size: 14px; }
/* Side navigation: pinned item + collapsible categories (SideNav.vue) */
.side-nav button:focus-visible { outline-offset: -2px; }
.nav-item, .nav-category-toggle { position: relative; width: 100%; display: grid; align-items: center; gap: 9px; border: 0; border-radius: 10px; background: transparent; text-align: left; }
.nav-item strong, .nav-category-toggle strong { min-width: 0; overflow: hidden; font-size: 12px; white-space: nowrap; text-overflow: ellipsis; }
.nav-pinned, .nav-category-toggle { min-height: 38px; padding: 6px 8px; color: var(--text-soft); }
.nav-pinned { margin-bottom: 6px; grid-template-columns: 24px 1fr 3px; }
.nav-category-toggle { grid-template-columns: 24px 1fr auto 12px; }
.nav-pinned strong, .nav-category-toggle strong { font-weight: 700; }
.nav-category-items .nav-item { min-height: 32px; padding: 6px 8px 6px 14px; grid-template-columns: 1fr 3px; color: var(--muted); }
.nav-category-items .nav-item strong { font-weight: 600; }
.side-nav .nav-icon { width: 24px; height: 24px; border-radius: 7px; font-size: 12px; }
.nav-item:hover, .nav-category-toggle:hover { background: var(--surface-2); color: var(--text); }
.nav-item.active { background: var(--accent-dim); color: var(--text); }
.nav-item > i { height: 0; border-radius: 3px; background: var(--accent-strong); transition: height .18s; }
.nav-item.active > i { height: 18px; box-shadow: 0 0 12px var(--accent-strong); }
.nav-item.active .nav-icon, .nav-category-toggle.has-active .nav-icon { border-color: var(--accent-border); background: var(--accent-dim); color: var(--accent-strong); }
.nav-category-toggle.has-active { color: var(--text); }
.nav-category { margin-bottom: 2px; }
.nav-category-count { min-width: 20px; height: 18px; padding: 0 6px; display: inline-grid; place-content: center; border: 1px solid var(--border); border-radius: 999px; color: var(--faint); font-size: 10px; font-weight: 800; }
.nav-category-chevron { color: var(--faint); font-size: 14px; line-height: 1; text-align: center; transition: transform .16s ease; }
.nav-category-toggle[aria-expanded='true'] .nav-category-chevron { transform: rotate(90deg); }
.nav-category-items { margin: 2px 0 8px 20px; padding-left: 6px; display: grid; gap: 2px; border-left: 1px solid var(--border); }
.nav-category-items[hidden] { display: none; }
@media (max-width: 1020px), (pointer: coarse) {
  .side-nav .nav-pinned, .side-nav .nav-category-toggle, .nav-category-items .nav-item { min-height: 42px; }
}
```

**4-A-3. 브레드크럼 카테고리 글자 간격** — `frontend/src/style.css`

찾을 문자열:

```css
.breadcrumb b { color: var(--faint); }
```

바꿀 문자열:

```css
.breadcrumb b { color: var(--faint); }
.breadcrumb .breadcrumb-category { letter-spacing: 0; }
```

#### 4-B. `frontend/src/accessibility.css`

옛 클래스용 선택자 3줄을 지운다(쉼표 목록의 첫 줄이라 남은 목록 문법은 그대로 유효하다).

**4-B-1** — `frontend/src/accessibility.css`

찾을 문자열:

```css
.nav-group > p,
.scope-card > span,
```

바꿀 문자열:

```css
.scope-card > span,
```

**4-B-2** — `frontend/src/accessibility.css`

찾을 문자열:

```css
.nav-group button strong,
.scope-card select,
```

바꿀 문자열:

```css
.scope-card select,
```

**4-B-3** — `frontend/src/accessibility.css`

찾을 문자열:

```css
.nav-group button small,
.scope-card small,
```

바꿀 문자열:

```css
.scope-card small,
```

확인: `npx vue-tsc --noEmit` 그리고 `grep -rn "nav-group" src` 결과 없음.

### WP-5 · 새 테스트, npm 스크립트, CI

#### 5-A. `frontend/tests/sideNavigation.test.mjs` 새로 만들기

테스트 구성: ① IA(분류 완전성, 역할 경계, beta/platform 규칙, 역할별 섹션, 라벨 충돌), ② 펼침 규칙(모드, 초기값, 토글, 활성 노출), ③ 검색, ④ `SideNav.vue` 렌더(ARIA 연결, 아코디언, 활성 노출, 역할 전환 재초기화, 금지 패턴·CSS 계약).

파일: `frontend/tests/sideNavigation.test.mjs`

```js
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createRenderer, h, nextTick, ref } from 'vue'
import ts from 'typescript'
import { loadTypeScript, typeScriptModuleUrl } from './loadTypeScript.mjs'

const nav = await loadTypeScript('../src/consoleNavigation.ts')
const ids = items => items.map(item => item.id)
const sectionIds = sections => ({
  pinned: ids(sections.pinned),
  categories: sections.categories.map(category => [category.id, ids(category.items)])
})
const ORG_ONLY = ['infrastructure', 'system', 'external', 'services', 'flow-designer', 'teams', 'notifications', 'audit', 'model-playground']

/* ---------------- 1. information architecture ---------------- */

test('every navigable page maps to exactly one known category; only dashboard is pinned', () => {
  const categoryIds = nav.NAV_CATEGORIES.map(category => category.id)
  assert.deepEqual(categoryIds, ['models', 'access', 'observe', 'devtools', 'platform'])
  const all = [...nav.adminNavItems, ...nav.directorNavItems, ...nav.developerNavItems, nav.platformNavItem, nav.betaNavItem]
  for (const item of all) {
    const category = nav.categoryOf(item.id)
    if (item.id === 'dashboard') assert.equal(category, null)
    else assert.ok(categoryIds.includes(category), `${item.id} has a category`)
  }
  assert.deepEqual(Object.keys(nav.PAGE_CATEGORY).sort(), [...new Set(ids(all))].sort())
  assert.equal(nav.categoryOf('docs'), null)
  assert.equal(nav.categoryOf('unknown-page'), null)
  assert.equal(nav.categoryOf('constructor'), null, 'prototype keys are not categories')
})

test('role lists keep the pre-redesign access boundaries and have no duplicates', () => {
  assert.deepEqual(ids(nav.adminNavItems).sort(), ['audit', 'dashboard', 'data-protection', 'external', 'flow-designer', 'infrastructure', 'model-playground', 'notifications', 'observability', 'playground', 'portal', 'projects', 'quotas', 'relationship', 'services', 'system', 'teams', 'usage'])
  assert.deepEqual(ids(nav.directorNavItems).sort(), ['dashboard', 'data-protection', 'observability', 'playground', 'portal', 'projects', 'quotas', 'relationship', 'usage'])
  assert.deepEqual(ids(nav.developerNavItems).sort(), ['dashboard', 'observability', 'playground', 'portal', 'quotas', 'relationship', 'usage'])
  for (const list of [nav.adminNavItems, nav.directorNavItems, nav.developerNavItems]) {
    assert.equal(new Set(ids(list)).size, list.length)
    assert.equal(list[0].id, 'dashboard', 'dashboard stays first (currentNav fallback)')
  }
  for (const id of ORG_ONLY) {
    assert.equal(ids(nav.directorNavItems).includes(id), false, `director must not see ${id}`)
    assert.equal(ids(nav.developerNavItems).includes(id), false, `developer must not see ${id}`)
  }
  assert.equal(ids(nav.developerNavItems).includes('projects'), false)
  assert.equal(ids(nav.developerNavItems).includes('data-protection'), false)
})

test('navItemsFor adds Platform Admin only for platform admins and beta for platform admins or beta operators', () => {
  const run = (role, platformAdmin, betaOperator) => ids(nav.navItemsFor({ role, platformAdmin, betaOperator }))
  assert.deepEqual(run('admin', true, false), [...ids(nav.adminNavItems), 'platform', 'beta'])
  assert.deepEqual(run('admin', false, false), ids(nav.adminNavItems))
  assert.deepEqual(run('admin', false, true), [...ids(nav.adminNavItems), 'beta'])
  assert.deepEqual(run('director', false, false), ids(nav.directorNavItems))
  assert.deepEqual(run('director', false, true), [...ids(nav.directorNavItems), 'beta'])
  assert.deepEqual(run('developer', false, false), ids(nav.developerNavItems))
  assert.deepEqual(run('developer', false, true), [...ids(nav.developerNavItems), 'beta'])
  assert.equal(run('director', true, false).includes('platform'), false, 'platform item is admin-only')
})

test('buildNavSections: platform admin gets pinned dashboard + 5 ordered categories', () => {
  const sections = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }))
  assert.deepEqual(sectionIds(sections), {
    pinned: ['dashboard'],
    categories: [
      ['models', ['infrastructure', 'external', 'services', 'flow-designer', 'system']],
      ['access', ['projects', 'teams', 'relationship', 'data-protection', 'audit']],
      ['observe', ['observability', 'usage', 'quotas', 'notifications']],
      ['devtools', ['portal', 'playground', 'model-playground']],
      ['platform', ['platform', 'beta']]
    ]
  })
  assert.deepEqual(sections.categories.map(category => category.label), ['모델·라우팅', '조직·보안', '관측·비용', '개발자 도구', '플랫폼 운영'])
  const orgAdmin = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: false, betaOperator: false }))
  assert.deepEqual(orgAdmin.categories.map(category => category.id), ['models', 'access', 'observe', 'devtools'])
})

test('buildNavSections: director and developer omit empty categories', () => {
  assert.deepEqual(sectionIds(nav.buildNavSections(nav.navItemsFor({ role: 'director', platformAdmin: false, betaOperator: false }))), {
    pinned: ['dashboard'],
    categories: [
      ['access', ['projects', 'relationship', 'data-protection']],
      ['observe', ['observability', 'usage', 'quotas']],
      ['devtools', ['portal', 'playground']]
    ]
  })
  assert.deepEqual(sectionIds(nav.buildNavSections(nav.navItemsFor({ role: 'developer', platformAdmin: false, betaOperator: true }))), {
    pinned: ['dashboard'],
    categories: [
      ['access', ['relationship']],
      ['observe', ['observability', 'usage', 'quotas']],
      ['devtools', ['portal', 'playground']],
      ['platform', ['beta']]
    ]
  })
})

test('category labels never collide with page labels (labels stay unique search targets)', () => {
  const pageLabels = [...nav.adminNavItems, ...nav.directorNavItems, ...nav.developerNavItems, nav.platformNavItem, nav.betaNavItem].map(item => item.label)
  for (const category of nav.NAV_CATEGORIES) {
    for (const label of pageLabels) {
      assert.equal(category.label.includes(label), false, `${category.label} contains ${label}`)
    }
  }
})

/* ---------------- 2. expansion rules ---------------- */

test('navExpansionMode: admin accordion, director/developer multi-open', () => {
  assert.equal(nav.navExpansionMode('admin'), 'single')
  assert.equal(nav.navExpansionMode('director'), 'multi')
  assert.equal(nav.navExpansionMode('developer'), 'multi')
})

test('initialExpanded: single opens only the active category, multi opens all', () => {
  const admin = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }))
  assert.deepEqual(nav.initialExpanded(admin, 'usage', 'single'), ['observe'])
  assert.deepEqual(nav.initialExpanded(admin, 'dashboard', 'single'), [])
  assert.deepEqual(nav.initialExpanded(admin, 'docs', 'single'), [])
  const developer = nav.buildNavSections(nav.developerNavItems)
  assert.deepEqual(nav.initialExpanded(developer, 'dashboard', 'multi'), ['access', 'observe', 'devtools'])
  assert.deepEqual(nav.initialExpanded(developer, 'services', 'single'), [], 'a category the role cannot see is never opened')
})

test('toggleExpanded: single keeps at most one open, multi toggles independently', () => {
  assert.deepEqual(nav.toggleExpanded(['models'], 'observe', 'single'), ['observe'])
  assert.deepEqual(nav.toggleExpanded(['observe'], 'observe', 'single'), [])
  assert.deepEqual(nav.toggleExpanded([], 'access', 'single'), ['access'])
  assert.deepEqual(nav.toggleExpanded(['access'], 'observe', 'multi'), ['access', 'observe'])
  assert.deepEqual(nav.toggleExpanded(['access', 'observe'], 'access', 'multi'), ['observe'])
  const input = ['models']
  nav.toggleExpanded(input, 'observe', 'multi')
  assert.deepEqual(input, ['models'], 'input is not mutated')
})

test('revealActive opens the active page category after palette, hash or deep-link navigation', () => {
  assert.deepEqual(nav.revealActive([], 'services', 'single'), ['models'])
  assert.deepEqual(nav.revealActive(['access'], 'services', 'single'), ['models'])
  assert.deepEqual(nav.revealActive(['access'], 'services', 'multi'), ['access', 'models'])
  const open = ['models']
  assert.equal(nav.revealActive(open, 'external', 'single'), open, 'already open → same array')
  const state = ['observe']
  assert.equal(nav.revealActive(state, 'dashboard', 'single'), state, 'pinned page keeps state')
  assert.equal(nav.revealActive(state, 'docs', 'single'), state, 'docs keeps state')
  assert.equal(nav.revealActive(state, 'nope', 'multi'), state, 'unknown id keeps state')
})

/* ---------------- 3. search ---------------- */

test('searchNavItems finds every page regardless of sidebar collapse state', () => {
  const all = [...nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }), nav.docsNavItem]
  assert.equal(nav.searchNavItems(all, '').length, all.length)
  assert.equal(nav.searchNavItems(all, '   ').length, all.length)
  for (const item of all) {
    assert.ok(ids(nav.searchNavItems(all, item.label)).includes(item.id), `label finds ${item.id}`)
  }
  assert.deepEqual(ids(nav.searchNavItems(all, 'DISCORD')), ['notifications'], 'case-insensitive keyword match')
  assert.deepEqual(ids(nav.searchNavItems(all, '조직·보안')), ['projects', 'teams', 'relationship', 'data-protection', 'audit'], 'category label match')
  assert.ok(ids(nav.searchNavItems(all, '플로우')).includes('flow-designer'))
  assert.deepEqual(nav.searchNavItems(all, 'zz-no-match'), [])
})

test('navCategoryLabel labels palette results', () => {
  assert.equal(nav.navCategoryLabel('dashboard'), '홈')
  assert.equal(nav.navCategoryLabel('docs'), '도움말')
  assert.equal(nav.navCategoryLabel('quotas'), '관측·비용')
  assert.equal(nav.navCategoryLabel('beta'), '플랫폼 운영')
  assert.equal(nav.navCategoryLabel('unknown'), '')
})

/* ---------------- 4. SideNav.vue ---------------- */

function compileSideNav() {
  const source = readFileSync(new URL('../src/SideNav.vue', import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  let script = ts.transpileModule(
    compileScript(descriptor, { id: 'side-nav-test', inlineTemplate: true }).content,
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }
  ).outputText
  script = script
    .replace(/from ['"]vue['"]/g, 'from ' + JSON.stringify(import.meta.resolve('vue')))
    .replace(/from ['"]\.\/consoleNavigation['"]/g, 'from ' + JSON.stringify(typeScriptModuleUrl('../src/consoleNavigation.ts')))
  return import('data:text/javascript;base64,' + Buffer.from(script).toString('base64'))
}

function createMemoryRenderer() {
  const node = tag => ({ tag, props: {}, children: [], text: '', parent: null })
  const renderer = createRenderer({
    createElement: node,
    createText: text => ({ ...node('#text'), text }),
    createComment: text => ({ ...node('#comment'), text }),
    patchProp: (element, key, _old, value) => { element.props[key] = value },
    insert(element, parent, anchor = null) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, element)
      element.parent = parent
    },
    remove(element) { element.parent?.children.splice(element.parent.children.indexOf(element), 1) },
    setElementText(element, text) { element.text = text; element.children = [] },
    setText(element, text) { element.text = text },
    parentNode: element => element.parent,
    nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] ?? null
  })
  const walk = (element, match) => [...(match(element) ? [element] : []), ...(element.children ?? []).flatMap(child => walk(child, match))]
  const text = element => element.tag === '#comment' ? '' : (element.text || '') + (element.children ?? []).map(text).join('')
  return { renderer, root: node('root'), walk, text }
}

test('SideNav renders accessible disclosure buttons, accordion toggling and active reveal', async () => {
  const { default: SideNav } = await compileSideNav()
  const { renderer, root, walk, text } = createMemoryRenderer()
  const activeId = ref('usage')
  const navigated = []
  const sections = nav.buildNavSections(nav.navItemsFor({ role: 'admin', platformAdmin: true, betaOperator: false }))
  const app = renderer.createApp({
    render: () => h(SideNav, { sections, activeId: activeId.value, mode: 'single', onNavigate: id => navigated.push(id) })
  })
  app.mount(root)
  try {
    const navEl = walk(root, el => el.tag === 'nav')[0]
    assert.equal(navEl.props['aria-label'], '주요 기능')
    const toggles = () => walk(root, el => el.tag === 'button' && String(el.props.class ?? '').split(' ').includes('nav-category-toggle'))
    const toggle = id => walk(root, el => el.props.id === `nav-category-${id}`)[0]
    const panel = id => walk(root, el => el.props.id === `nav-category-items-${id}`)[0]
    assert.equal(toggles().length, 5)
    for (const button of toggles()) {
      const controlled = walk(root, el => el.props.id === button.props['aria-controls'])[0]
      assert.ok(controlled, `${button.props.id} controls an existing element`)
      assert.equal(controlled.props.role, 'group')
      assert.equal(controlled.props['aria-labelledby'], button.props.id)
      assert.equal(controlled.props.hidden, button.props['aria-expanded'] !== 'true')
    }
    assert.equal(toggle('observe').props['aria-expanded'], 'true', 'active page category starts open')
    assert.equal(toggle('models').props['aria-expanded'], 'false')
    assert.match(String(toggle('observe').props.class), /has-active/)
    const usage = walk(root, el => el.tag === 'button' && el.props['aria-current'] === 'page')
    assert.equal(usage.length, 1)
    assert.equal(text(usage[0]), '사용량')
    assert.equal(usage[0].props.title, '토큰·비용·API 호출 이력', 'description moves to the tooltip')
    assert.ok(text(panel('models')).includes('LLM 서비스'), 'collapsed items stay in the DOM (hidden attribute)')

    toggle('models').props.onClick()
    await nextTick()
    assert.equal(toggle('models').props['aria-expanded'], 'true')
    assert.equal(toggle('observe').props['aria-expanded'], 'false', 'accordion closes the previous category')
    assert.match(String(toggle('observe').props.class), /has-active/, 'collapsed category still marks the active page')

    walk(panel('models'), el => el.tag === 'button' && text(el) === 'LLM 서비스')[0].props.onClick()
    assert.deepEqual(navigated, ['services'])

    activeId.value = 'audit'
    await nextTick()
    assert.equal(toggle('access').props['aria-expanded'], 'true', 'palette/hash navigation reveals the category')
    assert.equal(toggle('models').props['aria-expanded'], 'false')

    toggle('access').props.onClick()
    await nextTick()
    assert.equal(toggles().every(button => button.props['aria-expanded'] === 'false'), true, 'the open category can be closed')

    const pinned = walk(root, el => el.tag === 'button' && String(el.props.class ?? '').split(' ').includes('nav-pinned'))
    assert.equal(pinned.length, 1)
    assert.ok(text(pinned[0]).includes('대시보드'))
    pinned[0].props.onClick()
    assert.deepEqual(navigated, ['services', 'dashboard'])
  } finally {
    app.unmount()
  }
})

test('SideNav resets to the role default when the role changes (multi-open for directors)', async () => {
  const { default: SideNav } = await compileSideNav()
  const { renderer, root, walk } = createMemoryRenderer()
  const role = ref('developer')
  const app = renderer.createApp({
    render: () => h(SideNav, {
      sections: nav.buildNavSections(nav.navItemsFor({ role: role.value, platformAdmin: false, betaOperator: false })),
      activeId: 'dashboard',
      mode: nav.navExpansionMode(role.value)
    })
  })
  app.mount(root)
  try {
    const expandedIds = () => walk(root, el => el.tag === 'button' && el.props['aria-expanded'] === 'true').map(el => el.props.id)
    assert.deepEqual(expandedIds(), ['nav-category-access', 'nav-category-observe', 'nav-category-devtools'])
    role.value = 'admin'
    await nextTick()
    assert.deepEqual(expandedIds(), [], 'admin on dashboard starts fully collapsed')
  } finally {
    app.unmount()
  }
})

test('SideNav source keeps memory-DOM-safe, storage-free markup', () => {
  const source = readFileSync(new URL('../src/SideNav.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(source, /v-show/, 'use the hidden attribute, not v-show')
  assert.doesNotMatch(source, /<Transition/)
  assert.doesNotMatch(source, /localStorage|sessionStorage/)
  assert.doesNotMatch(source, /v-html/)
  const css = readFileSync(new URL('../src/style.css', import.meta.url), 'utf8')
  assert.match(css, /\.nav-category-items\[hidden\]\s*\{\s*display:\s*none;\s*\}/, 'hidden must beat display:grid')
  assert.doesNotMatch(css, /\.nav-group/)
  assert.doesNotMatch(readFileSync(new URL('../src/accessibility.css', import.meta.url), 'utf8'), /\.nav-group/)
})
```

#### 5-B. `frontend/package.json`

**5-B-1. test:side-nav 스크립트 추가** — `frontend/package.json`

찾을 문자열:

```json
    "test:registration-modal": "node --test tests/registrationModalErrors.test.mjs"
```

바꿀 문자열:

```json
    "test:registration-modal": "node --test tests/registrationModalErrors.test.mjs",
    "test:side-nav": "node --test tests/sideNavigation.test.mjs"
```

#### 5-C. `.github/workflows/quality-gate.yml`

`frontend` job의 “Frontend behavior tests” 단계 `run:` 줄 끝에 새 스크립트를 이어 붙인다(다른 `test:*`와 같은 방식).

**5-C-1. CI 테스트 체인 끝에 추가 (긴 한 줄의 끝부분만 바꾼다)** — `.github/workflows/quality-gate.yml`

찾을 문자열:

```yaml
&& npm run test:registration-modal
```

바꿀 문자열:

```yaml
&& npm run test:registration-modal && npm run test:side-nav
```

확인: `npm run test:side-nav` (15개 통과)

### WP-6 · 전체 검증

```bash
cd /c/project/aiconnect-sidebar/frontend
npx vue-tsc --noEmit
npm run test:docs && npm run test:quota && npm run test:topology && npm run test:project-security && npm run test:runtime-status && npm run test:date-time && npm run test:playground-response && npm run test:external-model-deletion && npm run test:external-capabilities && npm run test:beta-operator && npm run test:totp-hierarchy && npm run test:project-governance && npm run test:flow-designer && npm run test:services-page && npm run test:registration-modal && npm run test:side-nav
npm run build
git status --short   # 변경 파일이 아래 목록과 정확히 같아야 함
```

변경 파일 목록(이 외에는 없어야 함):

```
M  .github/workflows/quality-gate.yml
M  frontend/package.json
M  frontend/src/ConsoleShell.vue
M  frontend/src/accessibility.css
M  frontend/src/style.css
M  frontend/tests/betaOperator.test.mjs
M  frontend/tests/flowDesignerAdapter.test.mjs
M  frontend/tests/projectGovernance.test.mjs
A  frontend/src/SideNav.vue
A  frontend/src/consoleNavigation.ts
A  frontend/tests/sideNavigation.test.mjs
(+ 이 문서 docs/sidebar-nested-navigation-design.ko.md)
```

**수동 QA 체크리스트**(로컬 백엔드를 띄울 수 있으면 `npm run dev`로 확인. 못 띄우면 생략하고 보고에 명시):

1. 플랫폼 관리자 로그인 → 대시보드: 대시보드 + 카테고리 5개가 모두 닫혀 있다. 헤더 숫자 5/5/4/3/2.
2. `관측·비용` 클릭 → 열림, 다시 클릭 → 닫힘. `모델·라우팅`을 열면 `관측·비용`이 닫힌다.
3. `LLM 서비스` 이동 → 주소 `#services`, 브레드크럼 `AiConnect / 모델·라우팅 / LLM 서비스`.
4. ⌘K(Ctrl+K) → `감사` 입력 → 결과 보조 줄 `조직·보안 · 관리자 변경·열람 증적` → Enter/클릭 → `조직·보안`이 자동으로 열리고 `모델·라우팅`은 닫힌다.
5. 주소창에 `#notifications` 입력 → `관측·비용`이 열리고 `알림 채널`이 활성이다. 새로고침해도 같다.
6. 프로젝트 총괄·팀원 계정: 카테고리가 모두 열려 있다. `#teams` 입력 → 대시보드로 돌아간다.
7. Tab으로 헤더에 포커스 → 포커스 링이 잘리지 않음 → Enter로 토글, `aria-expanded` 변화 확인(개발자 도구).
8. 환경 설정에서 글자 크기 135% → 활성 항목이 메뉴 영역 안에 보인다. 라이트/다크 전환 확인.
9. 창 폭 1000px 이하 → ☰ 서랍: 헤더 토글로는 서랍이 닫히지 않고, 항목을 누르면 닫힌다. 행 높이 42px.
10. 항목에 마우스를 올리면 설명 툴팁이 보인다.

### 완료 정의 (DoD)

- [ ] WP-6 명령 전부 통과(16개 test 스크립트, `vue-tsc`, `build`)
- [ ] 변경 파일 목록이 WP-6과 일치
- [ ] `ConsoleShell.vue`에서 `isAllowedPage`·`navigate`·`PageKey`·`knownPage`가 바이트 단위로 그대로
- [ ] `grep -rn "nav-group\|v-show\|localStorage" frontend/src/SideNav.vue frontend/src/style.css frontend/src/accessibility.css` 결과 없음(`style.css`의 기존 다른 규칙에 `localStorage`는 없음)
- [ ] 수동 QA 결과(또는 생략 사유)를 보고

---

## 8. 미결 사항·리스크 (사용자 확인 필요)

| # | 항목 | 현재 결정 | 확인 요청 |
|---|---|---|---|
| Q1 | 카테고리 라벨 | `모델·라우팅` / `조직·보안` / `관측·비용` / `개발자 도구` / `플랫폼 운영` | 다른 이름 선호 시 `NAV_CATEGORIES` 라벨과 테스트 기대값만 바꾸면 됨. 단 `프로젝트`·페이지명을 포함하는 라벨은 피해야 함(§2.5) |
| Q2 | 관리자 아코디언 | 한 번에 하나만 열림 | 여러 개를 열어 두고 싶다면 `navExpansionMode`를 `'multi'`로(이때 저장 여부도 다시 결정) |
| Q3 | 펼침 상태 저장 안 함 | 라우트로 매번 계산 | 저장이 필요하면 역할별 localStorage 키 + try/catch를 추가하는 후속 작업 |
| Q4 | `시스템 구조`, `알림 채널` 위치 | 각각 `모델·라우팅`, `관측·비용` | 운영 관점에서 `관측·비용`(시스템 구조) / `조직·보안`(알림) 선호 여부 |
| R1 | 설명이 툴팁으로 이동 | 터치 기기에서는 설명이 안 보임(검색에서는 보임) | 수용 가능 여부 |
| R2 | 라벨 불일치 유지 | `Platform Admin`(영문), 관리자 `사용량` vs 다른 역할 `API 사용량` | 라벨 정리는 Dev-Docs(`roleGuides.ts`)·테스트와 함께 별도 작업 권장 |
| R3 | `has-active` 표시는 시각 전용 | 보조기기는 닫힌 카테고리 안의 현재 페이지를 알 수 없음(브레드크럼·`aria-current`로 보완) | 필요 시 헤더에 시각적으로 숨긴 “현재 페이지 포함” 텍스트 추가 |
| R4 | 세로 공간 | 1366×768·135%에서 브랜드·워크스페이스 카드·Dev-Docs·`Gateway online`(정적 문구) 때문에 메뉴 영역이 약 190px | 낮은 화면에서 `.sidebar-foot` 숨김·워크스페이스 카드 축소를 후속으로 할지 |
| R5 | 기존 사용자 학습 비용 | 그룹 이름과 위치가 바뀜 | 기능 검색이 카테고리명으로도 찾고, 결과에 카테고리를 표시해 완화 |
| R6 | 문서 | `docs/flow-designer-design.ko.md:1393`에 옛 그룹명 `인프라·시스템` 언급(과거 설계 기록), `docs/USER_GUIDE_KO.md:69`는 이미 옛 메뉴명 | 이번 범위에서 수정하지 않음 |

---

## 부록 A. 프로토타입 검증 기록

저장소 밖 임시 복사본(기준 커밋의 `frontend/` + worktree `node_modules` 연결)에 §7을 그대로 적용해 확인했다.

| 항목 | 결과 |
|---|---|
| `npx vue-tsc --noEmit` | 통과 |
| CI 프론트엔드 테스트 체인(기존 15개 스크립트 + `test:side-nav`) | 16개 스크립트 모두 `fail 0`. `test:side-nav` 15개, `test:project-governance` 17개, `test:flow-designer` 96개, `test:beta-operator` 4개 |
| 비CI 테스트 `uiLayoutDisclosure`, `gatewayConnectionGuide` | 통과(영향 없음 확인) |
| `npm run build` | 통과 |
| 브라우저(Chromium, 하네스 페이지에 실제 `style.css`·`accessibility.css`·`SideNav.vue` 렌더) | 다크/라이트, 115%/135%, 1440×900·1366×768·375×812에서 레이아웃 확인. Enter 토글·`aria-expanded`·`hidden`→`display:none`·포커스 링 확인. 135%·768px에서 `#audit` 활성 행이 메뉴 영역 하단에 정확히 맞춰짐 |
| 높이 측정 | 플랫폼 관리자 옛 메뉴 콘텐츠 1,323px@115%(≈1,150 CSS px) → 새 메뉴(5개짜리 카테고리 열림) 420px@135%(≈311 CSS px) |
