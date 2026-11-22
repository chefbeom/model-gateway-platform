# AICONNECT / Dev-Docs

구현 검토 기준: **2026. 11. 22.**

AICONNECT의 현재 운영 구현을 기준으로 **관리자 · 프로젝트 총괄자 · 팀원** 역할에 맞춘 간결한 실전 가이드와 실제 화면 입력 예시(`mockup`)를 제공합니다.
웹 콘솔에서는 `#docs`로 열고, 문서별 선택 상태는 `#docs/{문서 ID}`로 공유합니다. 구버전 문서 해시(`#docs/quickstart`, `#docs/request-diagnostics` 등)는 `navigation.ts`의 별칭 테이블을 통해 최신 문서로 자동 연결됩니다.

## 문서 구성 (4개 그룹 · 9개 가이드)

| 그룹 | 문서 ID | 대상 역할 | 핵심 내용 |
| --- | --- | --- | --- |
| 시작하기 | `overview` | 공통 | Gateway 요청 4단계, 3단계 역할 비교, 조직 관계도 예시, 핵심 용어 |
| 시작하기 | `onboarding-auth` | 공통 | 1회용 초대 링크 가입(`#register`), Google Authenticator TOTP, 세션 보안 |
| 역할별 실전 가이드 | `guide-admin` | 관리자 | 팀·프로젝트·총괄자 초대, 사내 GPU 등록, 논리 서비스·상한 설정 화면 예시 |
| 역할별 실전 가이드 | `guide-director` | 프로젝트 총괄자 | 팀원 초대, API 키 위임·회수, 상한 내 정책·예산·관계도/사용량 모니터링 |
| 역할별 실전 가이드 | `guide-member` | 팀원 | `내 API` 키 발급, `API 테스트` 검증, cURL·Python·TypeScript 연동 예제 |
| 인프라·정책 운영 | `runtime-routing` | 관리자 | Runtime 4종 비교, 외부 Provider 승인·단가, 로컬 우선 대기열, 모델 테스트·시스템 구조 |
| 인프라·정책 운영 | `data-protection-quotas` | 관리자 · 프로젝트 총괄자 | 3단계 데이터 경계(`INTERNAL_ONLY` 등), DLP 정책, KRW/USD 단가·총량제 |
| 진단·운영 참조 | `diagnostics` | 공통 | `X-Request-Id`·Attempt 추적 예시, HTTP/오류 코드표, Runtime 상태 점검 |
| 진단·운영 참조 | `operations-deploy` | 관리자 | 사용량 집계 범위, 감사 로그, Discord/Telegram·Prometheus, Timeout·배포·보안 체크리스트 |

## 화면 예시 기준 데이터 (가상 예시)

문서 내 모든 화면 카드와 코드 예제는 일관된 가상 예시 데이터를 사용하며, 실제 개인 계정이나 내부 서버 IP·비밀값을 포함하지 않습니다.

- 조직 / 팀 / 프로젝트: `Acme-AI-Lab` / `Platform-AI` / `cs-copilot`
- 계정: `admin@acme.example`(시스템 관리자), `lead.park@acme.example`(프로젝트 총괄자), `dev.kim@acme.example`(팀원)
- 주소: `https://www.aiconnectgateway.com`, `https://api.aiconnectgateway.com/v1`, `http://100.64.0.10/v1`, `http://100.64.0.20:1234` (`seoul-gpu-01`)
- 논리 모델 / 키 / 초대 코드: `text-pro`, `gpt-analysis`, `sk_llmg_example_9f8a...`, `INV-EXAMPLE-7K9Q`

## 화면과 소스 구조

    frontend/src/
    ├─ DevDocsPage.vue                 # 역할 필터, 3역할 바로가기 카드, 검색·목차·페이지네이션
    └─ dev-docs/
       ├─ types.ts                     # DocAudience(3역할+공통), DocGroup, MockupBlock 타입
       ├─ navigation.ts                # 역할별 필터링, mockup 포함 전문 검색, 구버전 해시 별칭
       ├─ roleGuides.ts                # 시스템 관리자 · 프로젝트 총괄자 · 팀원 실전 가이드
       ├─ operationsGuides.ts          # Runtime·외부 AI·라우팅, 데이터 보호·요금, 진단, 관측·배포·보안
       ├─ catalog.ts                   # 시작하기 2종 + 카탈로그 결합(9개) 및 DOCS_REVIEWED_AT
       └─ DevDocsArticle.vue           # 본문 및 내장 화면 예시(mockup) 카드 렌더러

## 검증

```bash
cd frontend
npm run test:docs
node --test tests/*.test.mjs
npm run build
```

## 관련 운영·참조 문서

- [외부 Provider 운영 가이드](EXTERNAL_PROVIDER_GUIDE_KO.md)
- [Runtime · 모델 사용량 통계](runtime-usage-statistics.md)
- [요청 배분과 공유 대기열](request-scheduling.md)
- [시스템 연결도](system-topology.md)
- [OpenAPI 계약](openapi.yaml)
- [배포 준비 체크리스트](production-readiness.md)
