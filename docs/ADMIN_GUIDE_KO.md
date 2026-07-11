---
document_id: aiconnect-admin-guide-ko
title: AICONNECT 관리자 운영 가이드
audience:
  - Platform Administrator
  - Organization Administrator
  - AI 운영 보조자
language: ko-KR
source_of_truth:
  - docker-compose.yml
  - src/main/java/com/aiconnect/llmgateway/admin
  - src/main/java/com/aiconnect/llmgateway/modelops
  - docs/openapi.yaml
lm_studio_reference: https://lmstudio.ai/docs/developer
last_reviewed: 2026-07-11
---

# AICONNECT 관리자 운영 가이드

## 1. 목적과 운영 경계

AICONNECT는 LM Studio 서버를 설치하거나 GPU 드라이버를 관리하는 제품이 아니다. 이미 준비된 LM Studio Runtime을 등록하고, 조직·사용자·프로젝트·API 키·라우팅·사용량·장애 대응을 중앙에서 관리하는 Control Plane 및 API Gateway다.

```text
외부 API 사용자
  → HTTPS
  → AICONNECT Gateway
  → Tailscale 사설망
  → GPU 서버의 LM Studio
```

API 사용자 요청과 응답은 Gateway를 통과한다. GPU Runtime은 인터넷에 직접 공개하지 않고, Gateway만 Tailscale을 통해 접근하도록 구성한다.

## 2. 관리자 역할

| 역할 | 책임 |
|---|---|
| Platform Administrator | 전체 조직, 플랫폼 보안, 전역 장애·감사, 초기 관리자 구조 |
| Organization Administrator | 조직 사용자·팀·프로젝트·Runtime·논리 서비스·알림 관리 |
| Team Admin | 팀 구성원, 팀 프로젝트, 팀 사용량 관리 |
| Project Owner | API 키, 프로젝트 한도·알림·사용량 관리 |

가장 작은 권한으로 운영한다. 일상적인 프로젝트 관리에 Platform Administrator 권한을 사용하지 않는다.

## 3. 설치 전 점검

### 3.1 AICONNECT 서버

- Docker Engine 또는 Docker Desktop과 Docker Compose
- 공개 HTTPS 주소 또는 내부용 접근 주소
- MariaDB 데이터 저장소
- 충분한 디스크 공간과 백업 정책
- `.env`의 모든 비밀값 설정

필수 비밀값 예시:

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

`.env`, API 키 원문, LM Studio API Token, Tailscale Auth Key를 저장소에 커밋하지 않는다.

### 3.2 GPU 서버

각 GPU 서버에서 다음이 준비되어야 한다.

1. Tailscale 로그인 및 Tailnet 연결
2. LM Studio 설치
3. 필요한 모델 다운로드
4. LM Studio Developer Server 시작
5. Gateway에서 접근 가능한 Tailnet IP 또는 MagicDNS 이름 확인
6. 가능하면 LM Studio API Token 인증 활성화

GPU의 제품명은 플랫폼 로직의 조건이 아니다. RTX 5080, RTX 5090, H100, RTX 6000, AMD GPU 등은 모두 선택적인 인벤토리 메타데이터다. Runtime의 정상 상태, 모델 기능, 동시 요청 여유, 라우팅 우선순위가 실제 선택 기준이다.

### 3.3 Tailscale 접근 정책

허용해야 할 최소 경로:

```text
tag:llm-gateway → tag:gpu-node → TCP 1234
```

권장 원칙:

- GPU 서버의 LM Studio 포트를 공용 인터넷에 열지 않는다.
- GPU 노드에서 MariaDB, AICONNECT 관리자 API로의 접근 권한은 부여하지 않는다.
- 각 Runtime에 서로 다른 LM Studio API Token을 사용한다.
- DNS 이름보다 Tailscale MagicDNS를 사용할 경우 이름 변경 절차를 문서화한다.

상세 구성은 [Tailscale 네트워크 가이드](tailscale-network.md)를 따른다.

## 4. 최초 기동

### 4.1 컨테이너 실행

```powershell
Copy-Item .env.example .env
# .env의 placeholder를 안전한 값으로 교체
docker compose --env-file .env up -d --build --wait
```

Tailscale sidecar 구성 사용 시:

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.tailscale.yml `
  --env-file .env `
  up -d --build --wait
```

### 4.2 첫 관리자 생성

초기 설치에서만 로그인 화면의 관리자 초기화 기능을 사용한다. 이후에는 `/api/auth/login`으로 로그인하며, 최초 관리자 초기화는 반복 실행하지 않는다.

초기 관리자 생성 조건:

- 유효한 이메일
- 12자 이상의 비밀번호
- 초기화가 가능한 빈 설치 상태

`X-Admin-Token`은 일반 운영 로그인이 아니라 비상(Break-glass) 절차에만 사용한다.

## 5. 권장 초기 구성 순서

```text
조직 생성
→ 팀 생성
→ 조직 사용자 생성
→ 팀 구성원과 역할 부여
→ GPU Runtime 등록·Probe·모델 동기화
→ 논리 LLM 서비스 생성
→ 서비스 Target/Failover 정책 구성
→ 프로젝트 생성·서비스 접근 권한 부여
→ API 키 발급
→ 최소 호출과 사용량 기록 검증
→ 알림·보관·백업 정책 설정
```

이 순서를 지키면 실제 GPU나 모델이 바뀌어도 API 사용자 키와 코드가 변하지 않는다.

## 6. 조직, 팀, 사용자, 프로젝트 관리

### 6.1 조직과 팀

- 조직은 서버·프로젝트·사용량을 격리하는 최상위 단위다.
- 팀은 부서·서비스·업무 단위의 권한과 사용량 분리 단위다.
- 하나의 프로젝트는 필요에 따라 한 팀에 연결한다.

권장 예시:

```text
Organization: Example Corporation
  Team: Platform Engineering
    Project: internal-assistant-prod
  Team: Marketing
    Project: campaign-prompt-builder
```

### 6.2 사용자 생성 원칙

- 개인 계정을 생성한다. 공유 계정을 만들지 않는다.
- 운영 API 키는 개인 키가 아닌 프로젝트 키로 발급한다.
- 퇴사·이동 시 팀 권한을 먼저 회수하고, 관련 키를 점검한다.
- 감사가 필요한 사용자는 `AUDITOR` 역할을 우선 고려한다.

### 6.3 프로젝트 원칙

프로젝트는 최소한 환경별로 분리한다.

```text
service-a-dev
service-a-staging
service-a-prod
```

운영 프로젝트에 실험용 키나 높은 한도를 섞지 않는다.

## 7. LM Studio Runtime 등록

### 7.1 등록 입력값

**인프라** 화면에서 Runtime을 추가한다.

| 입력 | 설명 | 예시 |
|---|---|---|
| 노드 이름 | 사람이 식별할 이름 | `seoul-gpu-5090-01` |
| Endpoint URL | Gateway가 Tailscale로 접근하는 주소 | `http://100.x.x.x:1234` |
| 설명 | 위치·담당 팀·용도 | `서울 3층, 생산 텍스트 모델` |
| LM Studio API Token | Runtime 내부 인증 토큰 | 비밀값, 화면 재표시 금지 |

등록 후 반드시 다음 순서로 진행한다.

1. **연결 확인(Probe)**: Runtime 접근과 기본 모델 정보를 확인한다.
2. **모델 동기화**: LM Studio의 모델 목록을 Model Deployment로 반영한다.
3. 필요한 경우 Accelerator 인벤토리에 GPU 메타데이터를 추가한다.
4. 동기화된 모델의 `loaded`, context, capabilities를 검토한다.

Probe가 실패하면 우선 Tailscale ACL, 주소·포트, LM Studio Server 상태, API Token 순으로 점검한다.

### 7.2 모델 동기화의 의미

동기화는 모델 파일을 다운로드하거나 자동으로 로드하지 않는다. LM Studio가 알려주는 다운로드/로드 상태와 설정을 AICONNECT DB에 반영하는 작업이다.

관리자는 Deployment의 다음 운영값을 설정할 수 있다.

- 호환 키(Compatibility Key)
- 라우팅 사용 여부
- 최대 동시 요청 수
- 관리자 검증 Capability

## 8. 모델 로드와 설정: 현재 Agent 없는 정책

현재 운영 정책은 **Node Agent 없음**이다. 따라서 AICONNECT는 LM Studio Developer native REST에서 실제 적용을 보장하는 설정만 노출한다.

### 8.1 플랫폼에서 설정하는 항목

| 항목 | 동작 |
|---|---|
| Context Length | 로드 요청에 적용. 모델 최대값을 넘으면 차단 |
| Flash Attention | 로드 요청에 적용. 모델/엔진이 지원할 때만 효과 |

LM Studio native REST의 모델 로드 API는 `context_length`, `flash_attention`, `eval_batch_size`, `num_experts`, `offload_kv_cache_to_gpu` 등을 문서화한다. AICONNECT 화면은 현재 운영자가 선택한 핵심 항목만 노출하고, 나머지는 LM Studio 기본값에 맡긴다. [LM Studio Developer: Load a model](https://lmstudio.ai/docs/developer/rest/load)

### 8.2 기본값에 맡기는 항목

다음은 현재 AICONNECT 화면에서 변경하지 않는다.

- GPU Offload
- CPU Thread Pool Size
- Unified KV Cache
- 모델 메모리 유지/TTL
- K Cache Quantization Type
- V Cache Quantization Type
- 평가 배치와 병렬 처리 수

이 값은 각 GPU 서버의 LM Studio에서 설정한 기본값을 사용한다. 화면에 제어 항목을 표시하더라도 실제 적용되지 않는 설정을 제공하지 않는 것이 원칙이다.

GPU Offload와 TTL은 LM Studio CLI로 원격 제어할 수 있지만, 현재 AICONNECT에는 CLI 명령 실행 기능을 연결하지 않는다. 향후 별도 승인된 Node Agent 또는 관리 작업 채널이 도입되기 전까지는 LM Studio에서 직접 관리한다. [LM Studio Developer CLI: `lms load`](https://lmstudio.ai/docs/cli/local-models/load)

### 8.3 안전한 모델 작업 절차

```text
모델 선택
→ 사전 점검(모델 존재·컨텍스트·예상 메모리)
→ 프로필 저장(선택)
→ Endpoint DRAINING
→ 진행 중 요청 수 확인
→ LM Studio 로드/언로드
→ 모델 동기화
→ 짧은 Warm-up 요청
→ HEALTHY 또는 UNHEALTHY 상태 확정
```

운영자가 확인해야 할 사항:

- 다른 요청이 진행 중이면 작업은 `WAITING_FOR_DRAIN` 상태가 된다. 요청 완료 뒤 다시 실행한다.
- 마지막 로드 모델을 언로드하면 해당 Endpoint는 정상 요청을 처리할 수 없으므로 `UNHEALTHY` 상태가 될 수 있다.
- 로드 성공만으로 복구 처리하지 않는다. Warm-up 성공까지 확인한다.
- 작업 이력은 감사용으로 보관한다.

### 8.4 모델 다운로드

인프라 화면의 다운로드 요청은 LM Studio의 모델 다운로드 API를 호출한다. 다운로드 완료 후 다음을 수행한다.

```text
다운로드 완료
→ 모델 동기화
→ 사전 점검
→ 로드
→ Deployment 설정
→ 논리 서비스 Target 연결
```

다운로드 요청에는 카탈로그 모델 식별자 또는 허용된 Hugging Face 링크와 양자화를 사용할 수 있다. [LM Studio Developer: Download a model](https://lmstudio.ai/docs/developer/rest/download)

## 9. 논리 서비스와 Failover 구성

### 9.1 논리 서비스

사용자에게 GPU나 LM Studio 모델 ID를 노출하지 말고 논리 서비스를 제공한다.

```text
사용자 호출: document-analysis

Target 1: 5090 Runtime / model-A / priority 1
Target 2: 5080 Runtime / model-A / priority 2
Target 3: 5060 Runtime / model-B / priority 3 / degraded
```

### 9.2 Failover 정책

| 정책 | 사용 시점 |
|---|---|
| `STRICT` | 같은 모델/호환 키만 허용해야 할 때 |
| `COMPATIBLE` | 검증된 대체 모델을 사용할 수 있을 때 |
| `DEGRADED` | 품질 저하를 허용하고 서비스 지속성을 우선할 때 |

일반적으로 Primary와 Secondary는 같은 호환 키를 사용하고, 마지막 대체 대상만 `degraded=true`로 둔다.

### 9.3 재시도 정책

- `SAFE`: 서버에 요청이 도달하지 않았다고 판단할 수 있는 경우만 재시도한다.
- `AGGRESSIVE`: 첫 토큰 전 시간 초과/5xx에도 재시도할 수 있으나 중복 추론 가능성이 있다.

기본값은 `SAFE`를 권장한다.

## 10. 프로젝트 접근 권한과 API 키

1. 프로젝트에 필요한 논리 서비스 접근 권한을 부여한다.
2. 프로젝트 API 키를 발급한다.
3. 키는 생성 순간 한 번만 표시한다.
4. 애플리케이션에서는 AICONNECT `/v1` 주소와 논리 모델명을 사용한다.
5. 키 노출 시 폐기 후 교체한다.

관리자도 자신의 프로젝트를 만들어 API 키를 발급받아 일반 사용자와 동일한 Gateway 경로를 검증할 수 있다. 다만 운영자 개인 키를 공용 서비스에 사용하지 않는다.

## 11. 사용량, 알림, 보관 정책

### 11.1 사용량과 비용

MariaDB의 요청·토큰·비용 정보는 사용량의 기준 데이터다. Prometheus/Grafana는 시스템 상태와 시계열 모니터링용이며 과금 원본으로 사용하지 않는다.

관리 화면에서 확인할 항목:

- 프로젝트·팀·API 키별 요청 수
- 논리 서비스와 실제 Deployment
- 입력·출력 토큰과 예상 비용
- 응답 시간, 첫 토큰 시간, 실패율
- Failover 및 Runtime Attempt

### 11.2 알림 정책

프로젝트별 알림 기준:

- 분당 요청 수(RPM)
- 오류율(%)
- 월간 토큰 사용률(%)
- 중복 알림 방지 Cooldown

알림은 조직의 Discord 또는 Telegram 채널에 전달한다. 채널 비밀값(Webhook URL, Bot Token)은 기록·로그·화면에 재노출하지 않는다.

### 11.3 프롬프트/응답 보관

| 정책 | 운영 기준 |
|---|---|
| `NONE` | 민감도가 높거나 원문이 불필요한 서비스 |
| `METADATA_ONLY` | 기본 권장값 |
| `MASKED` | 지정 규칙으로 민감정보를 제거할 수 있을 때 |
| `FULL_ENCRYPTED` | 명시적 승인과 접근 감사가 있을 때만 |

`FULL_ENCRYPTED` 원문 열람은 최소 권한 관리자에게만 주고, 보관 기간을 짧게 설정한다.

## 12. 장애 대응 Runbook

### 12.1 Runtime이 UNHEALTHY일 때

1. 인프라 화면에서 Endpoint 상태와 마지막 Probe를 확인한다.
2. 해당 Runtime의 Incident와 최근 request attempt를 확인한다.
3. Tailscale 연결, LM Studio Server 실행, 모델 로드 상태를 GPU 서버에서 확인한다.
4. Primary가 실패해도 Secondary가 처리하는지 Gateway 요청으로 확인한다.
5. Runtime 복구 후 Probe → 모델 동기화 → Warm-up을 수행한다.
6. HEALTHY가 된 뒤 신규 요청만 Primary로 다시 유입되는지 확인한다.

진행 중인 스트리밍 응답은 중간 장애 뒤 자동으로 이어 붙이지 않는다. 이는 데이터 무결성을 위한 정상 동작이다.

### 12.2 모델 설정 변경이 실패할 때

1. 작업 이력의 상태와 메시지를 확인한다.
2. `WAITING_FOR_DRAIN`이면 진행 중 요청이 끝난 뒤 다시 실행한다.
3. 사전 점검에서 컨텍스트 최대값을 넘지 않았는지 확인한다.
4. GPU 메모리 부족이면 Context Length를 낮추거나 LM Studio에서 모델 설정을 조정한다.
5. Warm-up 실패 시 Endpoint가 UNHEALTHY로 유지되는지 확인하고 직접 `resume` 하지 않는다. 원인을 해결한 뒤 재검증한다.

### 12.3 API 키 노출 대응

```text
키 폐기
→ 새 키 발급
→ 애플리케이션 Secret 교체
→ 배포 재시작
→ 해당 키 Prefix의 요청 내역 점검
→ 필요 시 프로젝트 한도·알림 강화
```

## 13. 관리자 API 요약

정확한 계약은 [OpenAPI 명세](openapi.yaml)를 기준으로 한다. 대표 경로는 다음과 같다.

```text
POST /api/auth/bootstrap
POST /api/auth/login
POST /api/auth/logout

POST /api/admin/organizations
POST /api/admin/organizations/{organizationId}/teams
POST /api/admin/organizations/{organizationId}/users

POST /api/admin/projects
POST /api/admin/projects/{projectId}/api-keys
DELETE /api/admin/api-keys/{apiKeyId}

POST /api/admin/runtime-endpoints
POST /api/admin/runtime-endpoints/{endpointId}/probe
POST /api/admin/runtime-endpoints/{endpointId}/sync-models
POST /api/admin/runtime-endpoints/{endpointId}/model-operations/preflight
POST /api/admin/runtime-endpoints/{endpointId}/model-operations/load
POST /api/admin/runtime-endpoints/{endpointId}/model-operations/unload

POST /api/admin/services
POST /api/admin/services/{serviceId}/targets
```

관리 API는 일반 사용자 API와 다르다. 외부 애플리케이션은 `/api/admin/*`가 아니라 OpenAI 호환 `/v1/*`만 사용해야 한다.

## 14. AI 운영 보조자용 실행 계약

### 14.1 작업 전 필수 확인

```text
1. 요청자가 조직 관리자 또는 Platform Administrator인지 확인
2. 대상 조직, 프로젝트, Runtime, 모델을 식별
3. 현재 상태를 읽기 전용 조회로 확인
4. 영향 범위와 롤백 방법을 설명
5. 변경 작업은 필요한 범위만 실행
```

### 14.2 AI가 안전하게 수행할 수 있는 작업

- Runtime Probe 및 모델 동기화
- 사용량·오류·Incident 요약
- 사전 점검 결과 해석
- 팀·프로젝트·서비스 구성 초안 생성
- Failover 후보와 호환성 검토
- 알림 정책 초안 작성

### 14.3 반드시 사용자 확인이 필요한 작업

- API 키 폐기 또는 사용자 제거
- 마지막 로드 모델 언로드
- 논리 서비스의 Primary/Failover 정책 변경
- `FULL_ENCRYPTED` 원문 열람 또는 보관 정책 완화
- 프로젝트 삭제, 조직 삭제, 대량 사용자 권한 변경
- 실제 GPU 서버의 LM Studio 고급 설정 변경

### 14.4 절대 금지

- API 키·비밀번호·LM Studio Token·Tailscale Auth Key를 출력하거나 저장
- GPU Runtime을 공용 인터넷에 노출
- 권한을 우회해 다른 조직의 데이터를 조회
- 스트리밍 중단 응답을 다른 Runtime 결과로 임의 연결
- Agent가 없는 상태에서 GPU Offload, CPU Thread Pool, Unified KV Cache, K/V cache 양자화가 적용된 것처럼 보고

## 15. 운영 완료 점검표

- [ ] `.env`와 비밀값이 저장소에서 제외되어 있다.
- [ ] GPU Runtime은 Tailnet에서만 접근 가능하다.
- [ ] 모든 Runtime의 Probe와 모델 동기화가 성공했다.
- [ ] 논리 서비스에 Primary와 Secondary Target이 있다.
- [ ] 각 운영 프로젝트의 서비스 접근 권한과 API 키가 분리되어 있다.
- [ ] 정상 호출·권한 거부·한도 초과·Primary 장애·복구 시나리오를 검증했다.
- [ ] Discord/Telegram 알림 채널과 Cooldown을 확인했다.
- [ ] 보관 정책과 원문 열람 권한을 확인했다.
- [ ] 백업·복구 절차를 점검했다.

## 16. 관련 문서

- [사용자 사용 가이드](USER_GUIDE_KO.md)
- [LM Studio Developer 공식 문서](https://lmstudio.ai/docs/developer)
- [모델 발견](model-discovery.md)
- [장애 전환 운영](failover-operations.md)
- [알림과 Incident](incident-and-alerts.md)
- [백업과 복구](backup-and-restore.md)
