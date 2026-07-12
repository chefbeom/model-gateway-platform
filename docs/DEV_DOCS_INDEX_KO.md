# AICONNECT Dev-Docs 인덱스

이 문서는 사람과 AI 작업자가 AICONNECT의 최신 사용법을 찾기 위한 시작점이다. 웹 콘솔의 좌측 하단 **Dev-Docs**는 아래 문서를 제품 화면에 맞게 요약·구조화하여 제공한다.

## 독자별 시작 위치

| 독자 | 먼저 읽을 문서 | 목적 |
|---|---|---|
| 처음 사용하는 개발자 | [사용자 가이드](USER_GUIDE_KO.md) | 로그인, 프로젝트, API 키, Base URL, OpenAI 호환 호출 |
| 플랫폼·조직 관리자 | [관리자 가이드](ADMIN_GUIDE_KO.md) | 조직, 팀, Runtime, 모델, 서비스, 프로젝트, 관측과 장애 대응 |
| 네트워크 운영자 | [Tailscale 네트워크](tailscale-network.md) | Gateway와 GPU 서버 사이의 사설 연결 |
| Runtime 운영자 | [모델 발견](model-discovery.md), [Failover 운영](failover-operations.md) | LM Studio 모델 동기화와 장애 전환 |
| 배포 운영자 | [Linux VM Standalone](../deploy/standalone/README.md), [배포 프로필과 마이그레이션](deployment-profile-migration.md) | wget 빠른 설치, Standalone·HA·Kubernetes 선택·전환·롤백 |
| API 구현자 | [OpenAPI](openapi.yaml) | 공개 API 및 관리자 API 계약 |

## 웹 Dev-Docs 정보 구조

웹 문서는 누적 공지 형식이 아니라 다음 분류로 관리한다.

1. **시작하기**
   - AICONNECT 개요와 요청 경로
   - 5분 빠른 시작
   - 핵심 개념과 권한
2. **사용자 가이드**
   - 프로젝트와 API 키
   - OpenAI 호환 API 연결
3. **관리자 가이드**
   - 관리자 최초 구축
   - LM Studio Runtime과 모델
   - 논리 서비스와 라우팅
   - 사용량·관측·알림
4. **운영과 참조**
   - 배포 프로필 선택과 설치·마이그레이션
   - 보안·보관·Timeout
   - 운영 전 자동 품질 게이트와 승인 체크리스트
   - 문제 해결

## 프론트엔드 파일 구조

```text
frontend/src/
├─ DevDocsPage.vue                 # 검색, 좌측 탐색, 우측 페이지 목차
└─ dev-docs/
   ├─ types.ts                     # 문서 페이지·섹션·블록 타입
   ├─ catalog.ts                   # 최신 문서 목차와 본문 데이터
   └─ DevDocsArticle.vue           # 단계·표·코드·흐름도 렌더러
```

## 새 기능 문서화 규칙

새 기능을 구현할 때 다음 규칙을 함께 적용한다.

1. 기존 주제와 관련된 기능은 `frontend/src/dev-docs/catalog.ts`의 해당 페이지·섹션을 수정한다.
2. 날짜순으로 “새 기능 안내” 카드를 페이지 위에 계속 추가하지 않는다.
3. 사용 절차, 권한, 입력값 의미, 실패 조건, 삭제·보존 영향을 함께 기록한다.
4. 사용자 화면과 관리자 화면의 절차를 구분한다.
5. LM Studio 관련 동작은 [LM Studio Developer 공식 문서](https://lmstudio.ai/docs/developer)를 확인하고 실제 구현 범위만 설명한다.
6. API 계약이 바뀌면 `docs/openapi.yaml`도 갱신한다.
7. 운영 세부사항이 길어지면 `docs/`의 주제 문서를 갱신하고 Dev-Docs에서 연결한다.
8. API 키, 비밀번호, LM Studio Token, Tailscale Auth Key 같은 실제 비밀값은 예시나 검증 결과에 기록하지 않는다.
9. 배포·보안·복구 기준을 바꾸면 `docs/production-readiness.md`와 웹 Dev-Docs의 운영 전 체크리스트를 함께 갱신한다.

## 문서 검증 체크리스트

- [ ] 일반 개발자가 관리자 메뉴 없이 절차를 완료할 수 있는가?
- [ ] Base URL과 LM Studio Endpoint를 혼동하지 않게 설명했는가?
- [ ] 화면의 현재 버튼 이름과 문서가 일치하는가?
- [ ] 권한 부족, 연결 실패, 삭제 차단 이유를 설명했는가?
- [ ] 코드 예제의 model 값이 실제 모델 ID가 아닌 논리 서비스 키인가?
- [ ] 현재 기본 Timeout과 환경 변수명이 코드와 일치하는가?
- [ ] 다크·라이트 모드와 작은 화면에서 문서 레이아웃을 확인했는가?


## 외부 OpenAI Provider

관리자 등록, 프로젝트 요청·승인, 수동 사용과 명시적 자동 Failover 설정은 [외부 OpenAI Provider 운영 가이드](EXTERNAL_PROVIDER_GUIDE_KO.md)를 참고하세요.
