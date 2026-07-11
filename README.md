# AIConnect — 하드웨어 독립형 LLM 서비스 플랫폼

AIConnect는 여러 대의 개인·회사 소유 LLM 서버를 하나의 **OpenAI 호환 API**로 제공하는 API Gateway이자 관리·모니터링 플랫폼입니다.

사용자는 GPU 서버나 LM Studio 주소를 직접 알 필요 없이 논리 모델명과 프로젝트 API 키만 사용합니다. AIConnect는 인증, 권한, 요청 제한, 배포 선택, 장애 전환, 사용량 및 예상 비용 기록을 담당합니다.

```text
API 사용자 ── HTTPS ──> AIConnect Gateway ── Tailscale ──> LM Studio / GPU 서버
                              │
                              ├─ MariaDB
                              ├─ Prometheus
                              └─ Grafana
```

GPU 제품명은 라우팅 기준으로 하드코딩하지 않습니다. RTX, H100, AMD GPU, Apple Silicon 또는 아직 출시되지 않은 장비도 코드와 DB 스키마 변경 없이 자유 메타데이터로 등록할 수 있습니다.

## 주요 기능

### API Gateway

- OpenAI 호환 `GET /v1/models`
- OpenAI 호환 `POST /v1/chat/completions`
- 일반 JSON 응답과 SSE 스트리밍
- 외부 논리 모델명을 내부 LM Studio 모델 ID로 변환
- 사용자 응답에서 물리 서버·모델 식별자 비공개
- 입력·출력·Reasoning 토큰과 응답시간 기록

### 조직과 접근 제어

- 조직, 프로젝트, API 키, 논리 서비스 단위의 멀티테넌시
- Platform Administrator, Organization Administrator, Developer 역할
- 짧은 만료 Access Token과 HttpOnly Refresh Cookie
- API 키 원문은 발급 직후 한 번만 표시
- API 키는 HMAC 해시만 저장하며 폐기·만료 지원

### 라우팅과 장애 전환

- 논리 서비스와 실제 Model Deployment 분리
- 우선순위와 가중치 기반 Target 선택
- `STRICT`, `COMPATIBLE`, `DEGRADED` Failover 정책
- `SAFE`, `AGGRESSIVE` 재시도 정책
- Deployment별 최대 동시 요청 제한
- Circuit 상태, Endpoint Health, DRAINING 지원
- 복구 서버 Probe와 워밍업 후 자동 재투입
- 스트리밍 응답 시작 후에는 중복 생성을 방지하기 위해 재시도하지 않음

### LM Studio와 하드웨어 관리

- LM Studio 네이티브 `/api/v1/models` 자동 발견
- `/v1/models` 호환 API Fallback
- 모델 로드 상태, 양자화, 컨텍스트 길이, 병렬 처리 및 Capability 동기화
- 관리자 검증 Capability Override
- GPU 제조사·제품명·VRAM·드라이버·추가 JSON 메타데이터 관리
- GPU 정보가 없어도 Endpoint와 Gateway 기능 정상 작동

### 사용량과 운영

- 프로젝트별 RPM 제한과 월간 토큰 한도
- 입력·출력 토큰 단가 스냅샷과 예상 비용
- 요청별 실제 Deployment와 Failover Attempt 기록
- Discord Webhook과 Telegram Bot 장애·복구 알림
- 알림 채널 자격증명 암호화 및 전달 결과 저장
- 기본 `METADATA_ONLY` 요청 보관 정책
- 선택적 `FULL_ENCRYPTED` 프롬프트·응답 보관
- 관리자 변경 Audit Log

### 웹 관리 콘솔

- 로그인 우선 진입과 세션 자동 복구
- 기능별 페이지와 왼쪽 내비게이션
- `Ctrl/⌘ + K` 기능 검색
- 연녹색 기반 라이트·다크 모드
- 대시보드, 인프라, LLM 서비스, 프로젝트/API 키, 관측성, 사용량, 알림 화면
- 노드 Accelerator 인벤토리와 프로젝트 한도·보관 정책 관리
- 데스크톱·태블릿·모바일 반응형 UI

## 기술 구성

| 영역 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3, Spring MVC/WebClient |
| Database | MariaDB 11.4, Flyway |
| Frontend | Vue 3, TypeScript, Vite |
| Runtime | LM Studio |
| Private Network | Tailscale |
| Proxy | Nginx |
| Monitoring | Micrometer, Prometheus, Grafana |
| Deployment | Docker Compose |

## 사전 준비

- Docker Desktop 또는 Docker Engine + Compose
- GPU 서버에 설치된 Tailscale
- GPU 서버에서 실행 중인 LM Studio API Server
- 로컬 개발 시 Java 17, Gradle 8.10.x, Node.js 22

운영 환경에서는 LM Studio API Token 인증을 활성화하고, LM Studio 포트를 공개 인터넷에 노출하지 마세요.

## 환경변수 설정

예제 파일을 복사해 Git에서 제외되는 `.env`를 생성합니다.

```powershell
Copy-Item .env.example .env
```

`.env`의 모든 placeholder를 서로 다른 충분히 긴 비밀값으로 변경합니다.

주요 항목:

```text
DB_PASSWORD
MARIADB_ROOT_PASSWORD
ADMIN_API_TOKEN
API_KEY_PEPPER
GATEWAY_ENCRYPTION_KEY
AUTH_SIGNING_KEY
AUTH_REFRESH_PEPPER
GRAFANA_ADMIN_PASSWORD
```

`.env`와 실제 API 키, LM Studio Token, Tailscale Auth Key는 저장소에 커밋하지 않습니다.

## 기본 Docker 실행

로컬 네트워크 또는 Mock Runtime 환경에서는 기본 구성을 실행합니다.

```powershell
docker compose --env-file .env up -d --build --wait
```

기본 접속 주소:

- AIConnect: [http://localhost](http://localhost)
- Grafana: [http://localhost:3000](http://localhost:3000)

MariaDB, Prometheus, Backend, Frontend 컨테이너는 외부에 직접 노출되지 않습니다.

## Tailscale을 통한 GPU 서버 연결

Docker Bridge 컨테이너는 호스트의 Tailscale 경로를 자동으로 공유하지 않을 수 있습니다. 특히 Docker Desktop에서는 userspace sidecar 구성을 권장합니다.

1. Tailscale에서 `tag:llm-gateway`용 Pre-authorized Auth Key를 발급합니다.
2. `.env`의 `TS_AUTHKEY`에 설정합니다.
3. Tailscale Compose Override를 함께 실행합니다.

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.tailscale.yml `
  --env-file .env `
  up -d --build --wait
```

권장 접근 정책:

```text
tag:llm-gateway → tag:gpu-node → TCP 1234
```

GPU 서버에서는 LM Studio를 Tailnet 인터페이스에서 접근 가능하게 실행하고, 가능하면 `100.x` IP 대신 MagicDNS 이름을 등록합니다.

자세한 내용은 [Tailscale 네트워크 운영 문서](docs/tailscale-network.md)를 참고하세요.

## 첫 설정

1. 웹 콘솔에서 **첫 관리자 생성**을 선택합니다.
2. 조직과 프로젝트를 생성합니다.
3. `인프라스트럭처`에서 LM Studio Runtime Endpoint를 등록합니다.
4. Endpoint Probe와 모델 동기화를 실행합니다.
5. `LLM 서비스`에서 논리 서비스와 Service Target을 구성합니다.
6. 프로젝트에 서비스 사용 권한을 부여합니다.
7. 프로젝트 API 키를 발급합니다.
8. 사용자 애플리케이션에서 AIConnect의 `/v1` API를 호출합니다.

첫 관리자 생성은 비어 있는 설치에서 한 번만 성공합니다. `X-Admin-Token`은 일반 로그인 수단이 아닌 비상용 Break-glass 인증으로만 사용합니다.

## API 호출 예제

```http
POST /v1/chat/completions
Authorization: Bearer sk_llmg_...
Content-Type: application/json

{
  "model": "text-pro",
  "messages": [
    {
      "role": "user",
      "content": "다음 문서를 요약해 주세요."
    }
  ],
  "stream": false
}
```

스트리밍 요청:

```json
{
  "model": "text-pro",
  "messages": [{ "role": "user", "content": "안녕하세요" }],
  "stream": true,
  "stream_options": {
    "include_usage": true
  }
}
```

Primary가 첫 응답 전에 실패하면 다음 Target으로 자동 전환합니다. 일부 SSE 데이터가 이미 전송된 후 장애가 발생하면 현재 스트림을 오류로 종료하고, 다음 요청부터 정상 Target으로 라우팅합니다.

## 빌드와 테스트

Backend 전체 테스트와 실행 JAR 생성:

```powershell
gradle clean test bootJar --offline --no-daemon
```

Frontend 프로덕션 빌드:

```powershell
npm --prefix frontend run build
```

Compose 계약 확인:

```powershell
docker compose --env-file .env config -q
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --env-file .env config -q
docker compose -f docker-compose.yml -f docker-compose.tls.yml --env-file .env config -q
```

로컬 Smoke 검증:

```powershell
$adminPassword = Read-Host 'Smoke 관리자 비밀번호'

.\scripts\verify-compose-smoke.ps1 `
  -AdminEmail 'admin@example.com' `
  -AdminPassword $adminPassword
```

Smoke 검증은 다음 흐름을 확인합니다.

- 조직·프로젝트·노드·Endpoint 생성
- LM Studio 모델 발견과 동기화
- 논리 서비스·Target·API 키 구성
- 일반 응답과 SSE 스트리밍
- 물리 모델 ID의 논리 모델명 변환
- 토큰·비용·요청 Attempt 저장
- 사용자 사용량과 관리자 요청 탐색

현재 테스트 스위트는 인증, 조직 격리, 모델 발견, 라우팅, Failover, 스트리밍 경계, Quota, 요청 보관, 알림 격리, Tailscale Proxy 및 OpenAPI 계약을 검증합니다.

## 프로젝트 구조

```text
.
├─ src/                         Spring Boot Backend
│  ├─ main/java/                Gateway 및 Control Plane
│  ├─ main/resources/           설정 및 Flyway Migration
│  └─ test/                     Unit/Integration Test
├─ frontend/                    Vue 3 관리 콘솔
├─ infra/                       Nginx, Prometheus, Grafana 설정
├─ scripts/                     Smoke, Failover, Backup/Restore 검증 도구
├─ docs/                        설계·보안·운영 문서
├─ docker-compose.yml           기본 스택
├─ docker-compose.tailscale.yml Tailscale Override
└─ docker-compose.tls.yml       TLS Override
```

## 운영 문서

- [인증과 운영](docs/auth-and-operations.md)
- [모델 발견](docs/model-discovery.md)
- [라우팅 정책 관리](docs/routing-policy-management.md)
- [Failover 운영](docs/failover-operations.md)
- [장애와 알림](docs/incident-and-alerts.md)
- [요청 보관 정책](docs/request-retention.md)
- [백업과 복구](docs/backup-and-restore.md)
- [TLS 운영](docs/tls-operations.md)
- [Tailnet Runtime 검증](docs/tailnet-runtime-verification.md)
- [Tailnet Failover 검증](docs/tailnet-failover-verification.md)
- [전체 구현 완료 감사](docs/completion-audit.md)

## 보안 원칙

- 모든 외부 API는 운영 환경에서 HTTPS로 제공합니다.
- API 키 원문과 LM Studio Token을 로그에 기록하지 않습니다.
- LM Studio는 Gateway가 접근하는 Tailnet 내부에만 배치합니다.
- Tailscale Grants에서 Gateway → GPU Node Runtime 포트만 허용합니다.
- 프롬프트와 응답 원문은 기본적으로 저장하지 않습니다.
- `FULL_ENCRYPTED`는 사용자 고지와 보관 정책이 준비된 프로젝트에서만 사용합니다.
- Prometheus Label에 요청 ID, 사용자 입력, API 키와 같은 고유·민감값을 넣지 않습니다.

## 라이선스

라이선스 정책을 확정한 뒤 저장소 루트에 `LICENSE` 파일을 추가하세요.
