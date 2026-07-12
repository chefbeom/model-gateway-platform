# HA 배포

Kubernetes 없이 두 Gateway와 Redis 공유 상태를 사용하는 배포입니다.

```powershell
.\scripts\new-deployment-env.ps1 -Profile HA -OutputPath deploy\ha\.env
docker compose --env-file deploy/ha/.env -f deploy/ha/docker-compose.yml config
docker compose --env-file deploy/ha/.env -f deploy/ha/docker-compose.yml up -d --build
```

두 Gateway가 Tailscale userspace 프록시를 각각 사용하려면 선택 레이어를 함께 적용합니다.

```powershell
docker compose --env-file deploy/ha/.env `
  -f deploy/ha/docker-compose.yml -f deploy/ha/docker-compose.tailscale.yml `
  up -d --build
```

참조 Compose는 한 호스트의 프로세스 장애 검증용입니다. 물리 호스트 장애까지 견디려면 LB, Gateway A/B, Redis HA와 MariaDB Primary/Replica를 서로 다른 장애 영역에 배치합니다.

두 Gateway에는 `API_KEY_PEPPER`, `GATEWAY_ENCRYPTION_KEY`, `AUTH_SIGNING_KEY`, `AUTH_REFRESH_PEPPER`가 반드시 같아야 하며 `AICONNECT_INSTANCE_ID`는 달라야 합니다. HA 프로필은 Redis가 아니면 시작하지 않습니다.

## 검증

1. 두 Gateway readiness를 확인합니다.
2. `/api/admin/deployment-profile`이 `HA`, `REDIS`를 반환하는지 확인합니다.
3. 두 Gateway 요청 합계로 전역 RPM이 제한되는지 확인합니다.
4. Gateway 하나를 중지하고 LB를 통한 신규 요청이 5초 내 성공하는지 확인합니다.
5. 진행 중 SSE는 이어받지 못하며 다음 요청부터 복구됨을 확인합니다.

Standalone에서 전환할 때는 기존 MariaDB와 Secret을 유지합니다. API 키와 논리 모델명은 변경하지 않습니다.
