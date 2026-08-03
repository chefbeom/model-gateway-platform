# Tailnet / Private-only 배포

이 모드는 AICONNECT 관리 화면과 API를 공개 IP에서 제거하고 다음 두 경로만 제공합니다.

- Tailnet 장치: `http://<tailscale-ip>` 또는 선택적 Tailscale Serve HTTPS
- 같은 OCI VCN의 서버: AIConnect 인스턴스의 Private IP `http://<private-ip>/v1`

GPU 서버의 LM Studio 포트는 계속 Tailnet에서만 접근시키며 인터넷에 공개하지 않습니다.

## 환경 변수

Tailscale Serve가 아직 비활성화된 Tailnet에서는 다음과 같이 설정합니다.

```dotenv
AICONNECT_HTTP_BIND_ADDRESS=127.0.0.1
AICONNECT_TAILNET_BIND_ADDRESS=100.100.9.74
AICONNECT_TAILNET_HTTP_PORT=80
AICONNECT_PRIVATE_BIND_ADDRESS=10.0.0.214
AICONNECT_PRIVATE_HTTP_PORT=80
AICONNECT_LOOPBACK_HTTP_PORT=80
GATEWAY_INTERNAL_BASE_URL=http://100.100.9.74/v1
GATEWAY_EXTERNAL_BASE_URL=
AUTH_COOKIE_SECURE=false
```

HTTP라는 표시는 브라우저와 Gateway 사이의 애플리케이션 프로토콜입니다. Tailnet 구간은 Tailscale이 암호화하지만, 일반 인터넷에 이 포트를 공개하면 안 됩니다.

## 기동 및 공개 접근 차단

```bash
cd /opt/aiconnect/deploy/ha

docker compose -p aiconnect-ha --env-file .env \
  -f docker-compose.yml \
  -f docker-compose.external-monitoring.yml \
  -f docker-compose.private-only.yml \
  up -d mariadb redis api-1 api-2 frontend load-balancer

sudo env AICONNECT_PRIVATE_CIDR=10.0.0.0/16 \
  bash ./private-only-firewall.sh --install
```

Compose override는 Nginx Load Balancer를 Loopback, Tailscale IP와 OCI Private IP에만 바인딩합니다. OCI Public IP는 Private IP로 NAT될 수 있으므로 호스트 필터도 함께 적용합니다. 필터는 `tailscale0`, Loopback과 지정한 VCN CIDR만 TCP 80에 허용하며 systemd로 재부팅 후에도 다시 적용됩니다.

## 호출 주소

```text
Tailnet 클라이언트: http://<aiconnect-tailscale-ip>/v1
같은 OCI VCN:       http://<aiconnect-private-ip>/v1
공개 인터넷:        차단
```

같은 OCI VCN의 다른 서버가 접근하려면 OCI NSG 또는 Security List에서 AIConnect 서버 TCP 80 인바운드를 호출 서버의 Private IP `/32` 또는 필요한 사설 서브넷 CIDR에만 허용합니다. `0.0.0.0/0`은 사용하지 않습니다.

## 선택적 Tailnet HTTPS

Tailnet 관리자가 Serve를 활성화한 후 다음을 실행합니다.

```bash
sudo tailscale serve --bg --yes http://127.0.0.1:80
sudo tailscale serve status
```

이후 `GATEWAY_INTERNAL_BASE_URL=https://<host>.<tailnet>.ts.net/v1`, `AUTH_COOKIE_SECURE=true`로 변경하고 Gateway A/B를 재생성합니다. 관리 화면은 ts.net HTTPS 주소를 사용합니다.

## 검증

```bash
docker compose -p aiconnect-ha --env-file .env \
  -f docker-compose.yml \
  -f docker-compose.external-monitoring.yml \
  -f docker-compose.private-only.yml ps

curl -fsS http://127.0.0.1/
curl -fsS http://<aiconnect-tailscale-ip>/
curl -i http://<aiconnect-private-ip>/v1/models
sudo iptables -S DOCKER-USER | grep aiconnect-private
```

Tailnet 및 Private IP는 응답하고 공개 IP의 TCP 80 연결은 실패해야 합니다. SSH 22번은 별도 관리 경로이므로 Tailscale SSH 또는 Bastion 경로가 검증되기 전에는 차단하지 않습니다.
