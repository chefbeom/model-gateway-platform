# 배포 프로필 선택과 마이그레이션

AIConnect는 동일 애플리케이션 이미지와 Flyway DB 스키마를 `STANDALONE`, `HA`, `KUBERNETES` 프로필에서 사용합니다.

## 불변 계약

프로필을 변경해도 다음은 유지합니다.

- 공개 Base URL의 호스트 계약
- 프로젝트 API 키와 논리 서비스의 `model` 값
- 사용자·팀·프로젝트·권한
- Runtime Endpoint와 모델 Deployment
- 요청·사용량·비용·감사 이력

MariaDB와 다음 비밀값을 그대로 보존해야 합니다.

- `API_KEY_PEPPER`
- `GATEWAY_ENCRYPTION_KEY`
- `AUTH_SIGNING_KEY`
- `AUTH_REFRESH_PEPPER`

## Standalone에서 HA로

1. MariaDB와 `.env`를 암호화 백업합니다.
2. Redis HA 또는 운영 Redis를 준비합니다.
3. 기존 DB와 동일 Secret을 사용하는 `gateway-1`을 HA 프로필로 실행합니다.
4. `gateway-2`를 서로 다른 instance ID로 실행합니다.
5. 두 Gateway에서 readiness와 Tailnet LM Studio 접근을 확인합니다.
6. Load Balancer에서 SSE 버퍼링을 끄고 두 Gateway를 등록합니다.
7. 전역 RPM, 활성 요청 수와 Gateway 장애 전환을 검증합니다.
8. 공개 DNS 또는 기존 Nginx upstream을 새 LB로 전환합니다.
9. 관측 후 기존 Standalone 프로세스를 종료합니다.

```dotenv
AICONNECT_DEPLOYMENT_PROFILE=HA
AICONNECT_SHARED_STATE_PROVIDER=REDIS
AICONNECT_INSTANCE_ID=gateway-1
REDIS_HOST=redis.internal
REDIS_PASSWORD=...
REDIS_HEALTH_ENABLED=true
```

## HA에서 Kubernetes로

1. 기존 DB·Redis·Secret 백업을 확인합니다.
2. 동일 이미지 태그를 Helm values에 고정합니다.
3. 동일 Secret을 Kubernetes Secret Manager에 등록합니다.
4. Backend Pod 두 개 이상을 배포하고 readiness를 확인합니다.
5. Tailscale Egress 또는 사설망으로 모든 GPU Endpoint를 Probe합니다.
6. 임시 호스트로 비스트리밍과 SSE를 검증합니다.
7. 공개 DNS를 Kubernetes Gateway로 전환합니다.
8. 기존 HA Gateway를 Drain하고 종료합니다.

## 롤백

DB 마이그레이션이 없는 배포 방식 전환은 DNS/LB를 이전 Gateway로 되돌려 롤백합니다. Flyway가 적용된 버전 변경은 별도 DB 복원 계획을 사용합니다.

진행 중인 SSE는 Gateway나 Pod가 종료되면 다른 인스턴스가 중간부터 이어받을 수 없습니다. 신규 요청을 Drain하고 기존 연결의 최대 응답 제한만큼 기다린 뒤 종료합니다.

## 승인 기준

- 두 Gateway가 동일 API 키를 검증함
- 두 Gateway 요청 합계로 RPM이 제한됨
- 전체 Gateway 합계로 Deployment 동시 요청이 제한됨
- 한 Gateway 중단 후 신규 요청이 5초 내 성공함
- Health Check·사용량 알림·보관 삭제가 중복 실행되지 않음
- SSE가 버퍼링 없이 전달됨
- DB·Redis 장애 정책과 복구 절차가 기록됨
- 사용자 API 키와 논리 model 변경이 없음
