# AICONNECT / Dev-Docs

문서 기준: **2026. 08. 13.**

AICONNECT의 실제 화면과 현재 main 브랜치 구현을 기준으로, 사용·관리·운영 문서를 한 곳에서 탐색할 수 있도록 정리한 개발 문서입니다.
앱의 상위 화면은 `#docs`로 열고, 문서 선택 상태는 `#docs/{문서 ID}`로 공유합니다. 문서 ID는 `frontend/src/dev-docs/catalog.ts`와 `runtimeDiagnostics.ts`를 기준으로 합니다.


## 문서 구조

| 영역 | 대상 | 핵심 내용 |
| --- | --- | --- |
| 시작하기 | 모든 사용자 | AICONNECT 개요, 권한 구조, 첫 API 요청 |
| 사용자 가이드 | 개발자·서비스 사용자 | 프로젝트, API 키, Base URL, OpenAI 호환 API |
| 관리자 가이드 | 조직 관리자 | Runtime·Provider·모델·논리 서비스·요금·총량제 |
| 운영과 참조 | 운영자·개발자 | API 테스트, 시스템 구조, 장애 진단, 배포·보안·모니터링 |

## 최근 구현 기준

- 입력·출력 단가에 **KRW(원화) 또는 USD(달러)**를 선택해 저장합니다.
- 요금 총량제를 **조직·팀·프로젝트·API 키** 단위로 설정하고, 일간·월간·전체 기간 한도를 적용합니다.
- 한도 초과 요청은 Gateway에서 차단하고, 사용량 화면에서 일별 그래프·월간 캘린더·프로젝트·팀·API 키별 집계를 확인합니다.
- API 테스트 화면에서 모델 조회, JSON 요청, SSE 스트리밍, 실제 응답·토큰·Request ID를 확인할 수 있습니다.
- 시스템 구조 화면에서는 Gateway → 논리 서비스 → Runtime/Provider → 팀·프로젝트·API 키 연결을 확인합니다. GPU/Inference Node는 실행 자원 수에 중복 합산하지 않습니다.

## 어디서 확인할까요?

| 확인 목적 | AICONNECT 화면 | Dev-Docs 문서 |
| --- | --- | --- |
| 첫 연결과 API 호출 | 내 API / API 테스트 | 빠른 시작, API 연결, API 테스트·구조 |
| Runtime·외부 AI 설정 | 인프라 / 외부 AI | Runtime·모델, 외부 AI |
| 단가·통화·예산 | 요금·쿼터 / LLM 서비스 | 요금·통화·총량제 |
| 요청·비용·Failover 추적 | 사용량 / 관측성 | 사용량·관측·감사·알림, Runtime 장애 진단 |
| 전체 연결 관계 | 시스템 구조 | API 테스트와 시스템 구조 |
| 배포·보안·복구 | 배포 프로필 / 운영 문서 | 배포 프로필, 보안·보관·Timeout |

## 화면과 소스 구조

    frontend/src/
    ├─ DevDocsPage.vue                 # breadcrumb, 검색·대상 필터, 문서 영역 탐색
    └─ dev-docs/
       ├─ types.ts                     # 문서 페이지·섹션·블록·화면 이동 타입
       ├─ catalog.ts                   # 화면에 표시되는 문서 카탈로그
       ├─ runtimeDiagnostics.ts        # Runtime 장애 진단 문서
       └─ DevDocsArticle.vue            # 본문 블록 렌더러

## 문서 작성 규칙

1. 문서는 실제 화면의 현재 라벨·메뉴·API 계약을 기준으로 작성합니다.
2. 설정 위치와 확인 위치를 함께 적고, 가능하면 AICONNECT 화면으로 이동하는 링크를 제공합니다.
3. 가격은 통화와 입력·출력 단가를 함께 설명하고, KRW와 USD를 임의 환산하지 않습니다.
4. 요청 이력·사용량·감사 로그는 보존 대상일 수 있으므로 삭제 동작과 비활성화 동작을 구분합니다.
5. API 예제의 model은 실제 논리 서비스 키를 사용하고, Runtime 내부 주소를 사용자 호출 예제로 노출하지 않습니다.
6. 기능 변경 시 catalog.ts, 이 인덱스, 관련 docs/*.md의 기준일과 링크를 함께 갱신합니다.
7. 비밀값(API 키 원문, LM Studio Token, Tailscale Auth Key)은 문서·로그·스크린샷에 기록하지 않습니다.

## 검증 체크리스트

- [ ] 일반 사용자가 관리자 메뉴 없이 첫 요청 절차를 따라갈 수 있는가?
- [ ] Base URL과 Runtime Endpoint의 역할이 구분되어 있는가?
- [ ] 화면의 현재 버튼·메뉴명과 문서 링크가 일치하는가?
- [ ] 단가의 통화와 사용량의 통화별 표시가 일치하는가?
- [ ] 총량제 초과 시 요청 차단과 요청 이력 확인 방법이 설명되어 있는가?
- [ ] API 테스트 결과의 응답·토큰·Request ID 확인 방법이 있는가?
- [ ] GPU/Inference Node와 실행 가능한 Endpoint/Provider가 중복 집계되지 않는가?
- [ ] 배포·보안·보관 정책 변경이 관련 운영 문서에도 반영됐는가?

## 외부 참고 문서

- [LM Studio Developer Docs](https://lmstudio.ai/docs/developer)
- [OpenAPI 계약](openapi.yaml)
- [외부 Provider 운영 가이드](EXTERNAL_PROVIDER_GUIDE_KO.md)
- [배포 준비 체크리스트](production-readiness.md)