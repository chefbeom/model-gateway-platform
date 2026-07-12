# Runtime 연결 장애 진단

이 문서는 AICONNECT Gateway가 LM Studio Runtime에 연결하지 못해 `MODEL_UNAVAILABLE` 또는 `UNHEALTHY`가 발생할 때 사용하는 운영 절차다. 웹 Dev-Docs의 **Runtime 연결 장애 진단** 문서와 같은 기준을 사용한다.

## 판단 순서

1. GPU 서버의 Tailscale 피어가 온라인인지 확인한다.
2. GPU 서버에서 LM Studio Developer Server가 실행 중이고 해당 모델이 로드됐는지 확인한다.
3. AICONNECT 호스트에서 `Runtime URL:1234` 및 `/v1/models`에 연결할 수 있는지 확인한다.
4. 인프라 화면에서 **연결 확인**을 실행한다.
5. 성공한 뒤에만 모델 동기화, Deployment 상태, 논리 서비스 Target 순으로 확인한다.
6. Gateway Base URL로 짧은 Chat Completion을 호출하고 관측성·사용량에 기록됐는지 확인한다.

## 상태별 조치

| 상태 | 의미 | 조치 |
|---|---|---|
| Tailscale peer `offline` | 원격 GPU 컴퓨터가 Tailnet에 없음 | GPU 컴퓨터 전원·Tailscale 로그인·네트워크부터 복구 |
| 호스트도 TCP 1234 실패 | Gateway 이전의 네트워크 또는 LM Studio 문제 | LM Studio 서버 실행·Listen 주소·Tailnet ACL 확인 |
| 호스트는 성공, API 컨테이너만 실패 | Docker와 Tailnet 경로가 분리됨 | `docker-compose.tailscale.yml` 오버레이 또는 컨테이너용 Tailnet 경로 구성 |
| Endpoint `UNHEALTHY` | Health Check가 모델 목록을 읽지 못함 | 연결 확인에서 표시되는 HTTP 상태·오류 원인을 먼저 해결 |
| Deployment `UNHEALTHY` 또는 `loaded=false` | Endpoint는 보이지만 대상 모델이 준비되지 않음 | LM Studio에서 모델 로드 후 모델 동기화 |
| 503 `MODEL_UNAVAILABLE` | 후보 Target이 하나도 없음 | Endpoint·Deployment·Target·프로젝트 서비스 권한을 순서대로 확인 |

## Docker와 Tailscale

Windows Docker Desktop에서는 호스트의 Tailscale 연결이 컨테이너에 자동으로 전달되지 않을 수 있다. 호스트에서 Runtime URL이 열리는데 API 컨테이너가 열지 못한다면, Tailnet에 참여하는 Compose 오버레이를 사용한다.

```powershell
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml up -d
```

실행 전 `.env`에 실제 `TS_AUTHKEY`와 기존 비밀값을 넣어야 한다. 이미 실행 중인 데이터베이스에서 비밀값을 새로 만들면 API 키 검증, 암호화된 Runtime Token 복호화, 로그인 세션에 영향을 줄 수 있으므로 기존 비밀값을 보존한다.

## 성공 기준

- `/v1/models`에 프로젝트의 **논리 모델명**이 표시된다.
- `/v1/chat/completions`가 Gateway를 통해 `200`으로 완료된다.
- 관측성 요청 탐색기에 Request ID와 Attempt가 생긴다.
- 사용량의 프로젝트·서비스·인프라 집계가 증가한다.
- 사용자 애플리케이션은 LM Studio `100.x.x.x:1234`가 아닌 AICONNECT Gateway의 `/v1` URL을 사용한다.

실제 API 키, LM Studio Token, Tailscale Auth Key, 비밀번호는 진단 결과나 문서에 기록하지 않는다.
