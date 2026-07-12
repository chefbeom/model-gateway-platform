# Kubernetes 배포

동일한 AICONNECT Backend/Frontend 이미지를 Kubernetes에 배포합니다. Gateway Pod는 두 개 이상이며 Redis 공유 상태가 필수입니다.

## 전제 조건

- Kubernetes Gateway API CRD와 호환 Controller
- 운영 MariaDB와 Redis HA
- `aiconnect-secrets` Secret
- GPU LM Studio까지 연결되는 사설망 또는 Tailscale Operator Egress

```bash
kubectl create secret generic aiconnect-secrets \
  --from-literal=DB_PASSWORD='...' \
  --from-literal=REDIS_PASSWORD='...' \
  --from-literal=ADMIN_API_TOKEN='...' \
  --from-literal=API_KEY_PEPPER='...' \
  --from-literal=GATEWAY_ENCRYPTION_KEY='...' \
  --from-literal=AUTH_SIGNING_KEY='...' \
  --from-literal=AUTH_REFRESH_PEPPER='...'
```

운영 환경은 명령 기록 대신 Secret Manager를 사용합니다.

```bash
helm lint deploy/kubernetes/helm/aiconnect
helm template aiconnect deploy/kubernetes/helm/aiconnect -f values-production.yaml
helm upgrade --install aiconnect deploy/kubernetes/helm/aiconnect \
  --namespace aiconnect --create-namespace -f values-production.yaml
```

이미지 태그, DB·Redis 주소, GatewayClass, 호스트명과 TLS Secret을 운영 values에 고정합니다.

## Tailnet Runtime

Tailscale Kubernetes Operator Egress를 GPU Endpoint별로 생성하고 해당 Kubernetes Service DNS를 Runtime Endpoint로 등록합니다. HA Egress는 ProxyGroup을 사용합니다. GPU 서버를 Kubernetes Worker로 편입할 필요는 없습니다.

## 가용성 경계

- Backend Pod를 서로 다른 Worker Node에 배치합니다.
- PDB는 계획된 축출 중 최소 한 개의 Pod를 유지합니다.
- Redis와 MariaDB가 단일 인스턴스라면 전체 시스템은 완전한 HA가 아닙니다.
- 진행 중 SSE는 Pod 장애 시 이어받지 못하고 다음 요청부터 다른 Pod가 처리합니다.
- 모든 Pod가 동일한 암호화·서명 Secret을 사용해야 합니다.
