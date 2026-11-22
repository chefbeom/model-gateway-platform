# 공개 HTTPS 입구: 로컬 검증용 초안

이 설정은 **인터넷 공개용 완성본이 아니다**. Compose overlay는 `127.0.0.1`에만 바인딩한다. 기존 Tailscale 관리 입구와 운영 중인 API에는 변경을 가하지 않는다. 실제 공개나 기존 방화벽 변경에는 사용하지 않는다.

사용자 화면은 `www.aiconnectgateway.com`, API는 `api.aiconnectgateway.com`이다. 화면의 API 테스트 콘솔도 같은 출처의 `/v1`을 사용하므로 `www`에서 필요한 `/v1`을 허용한다. 초기 관리자 생성과 actuator를 차단하며 외부의 `X-Admin-Token`, 베타 인증 헤더와 Forwarded 헤더를 제거한다. 인증·역할 판단은 백엔드가 담당한다. 인증 예외와 본문 경로는 반드시 전체 목록 및 회귀 테스트를 거쳐야 한다.

`AICONNECT_CF_REALIP_CONF`는 Cloudflare 공식 IP 범위만 `set_real_ip_from`으로 선언한 별도 파일이다. 소스 검증, 업데이트 및 만료 시 차단 방법을 확정하기 전에는 신뢰 설정과 방화벽을 공개 적용하지 않는다. `CF-Connecting-IP`는 그 범위에서 온 연결만 신뢰한다. Docker의 포트 포워딩에 대한 호스트 방화벽, OCI 보안 목록과 NSG, IPv6도 각각 검증한다.

이 초안은 연결 클라이언트 인증서도 요구한다. `AICONNECT_AOP_CA_CERT`에는 서버용 Origin CA 인증서가 아닌, 신뢰할 AOP 클라이언트 인증 기관을 지정한다. Global AOP는 Cloudflare 공용 인증서를 사용하므로 특정 계정·도메인을 증명하지 않는다. 더 강한 원본 인증을 위해 zone-level 또는 per-hostname 전용 인증서의 가용성과 안전한 키 이전을 검토한다. 외부 공개 전 Cloudflare 경유 성공과 클라이언트 인증서가 없는 원본 직접 접근 실패를 모두 검증한다.

공개 전 필수: 전체 API별 익명·역할·프로젝트 간 접근 및 MFA 시험, 실제 관리자·초대 로그인, Secure 쿠키와 기존 내부 HTTP 접근의 충돌 해결, Cloudflare Full (strict), 원본 접근 제한 및 인증, IP 위조·접속률 제한, API 키 사용과 스트리밍, 장시간 요청, 제한·비용·SSRF·로그 보안, DB·Redis·GPU 비공개, 장애·백업 및 롤백 검증. Cloudflare 시간 제한 때문에 단순히 `proxy_read_timeout`을 늘리는 것만으로 긴 비스트리밍 요청이 보장되지 않는다.

공개 HTTPS 프록시는 `aiconnect_refresh`에 `Secure`, `HttpOnly`, `SameSite=Strict`를 강제한다. 내부 HTTP 쿠키 설정을 그대로 유지해도 공개 응답에서 이 플래그가 붙는지, 갱신·로그아웃까지 실제 브라우저로 시험해야 한다. 내부 HTTP는 Tailnet으로만 제한하며, 프록시의 TLS 포트를 우회해 API 서버에 직접 접속할 수 없도록 확인한다.

`X-Admin-Token` 제거가 원본 직접 접근까지 막아 주지는 않는다. Cloudflare 경유 외에는 원본 공개 입구에 접근할 수 없도록 별도로 강제하고 우회 여부를 시험해야 한다. 개인키는 저장소나 로그에 넣지 않는다.

## 방화벽과 제한 시험 운영

`render_public_nft.py`는 Cloudflare 공식 IP만 허용하는 전용 nftables 테이블을 만든다. INPUT의 HTTPS와 Docker DNAT로 들어오는 HTTPS만 제한하며, 컨테이너에서 외부 AI API로 나가는 HTTPS는 제한하지 않는다. 과거 FORWARD 체인에서 방향 구분 없이 443을 차단해 외부 API에 장애가 발생했으므로, `test_public_firewall_netns.sh`에서 허용·차단뿐 아니라 **컨테이너 출구 요청 성공**을 반드시 확인한다.

`public-firewall-refresh.sh`는 공식 IP API 응답을 검증해 방화벽을 원자적으로 교체하고 프록시의 신뢰 범위를 갱신한다. API 조회 실패 시 최근 7일 이내의 마지막 정상 스냅샷을 사용할 수 있고, 새 목록과 유효한 스냅샷이 모두 없으면 시작을 거부한다. 실행 중 갱신이 실패하면 기존 방화벽이 남지만 목록이 오래될 수 있으므로 알림과 운영자 점검이 필요하다. 재시작 후 실제 적용 및 목록 변경·실패 시 동작은 운영 환경에서 별도로 검증한다.

`aiconnect-public-limited.service`는 **일부 IP만 허용하는 시험용** 컨테이너를 방화벽 서비스 이후에 시작한다. 이 서비스는 정식 공개 구성이나 전체 서버 HA를 의미하지 않는다. 현재 시험용 허용 IP와 컨테이너 생성 절차는 운영 서버에서 수동 관리되고 있어, 정식 공개 전 버전 관리된 구성·검증·복구 절차가 별도로 필요하다.

롤백은 공개 입구를 먼저 중단한 뒤 방화벽 상태를 조정한다. 예: `systemctl stop aiconnect-public-limited.service`. Tailscale 관리 입구와 데이터 서버 연결은 유지하고, 원본 443을 보호하는 규칙을 제거한 상태로 공개 입구를 실행하지 않는다. AOP CA 개인키, 클라이언트 개인키, Origin 서버 키는 root 전용으로 보호하고 오프호스트 암호화 백업·복구 및 인증서 만료 전 교체를 검증해야 한다.

## 정식 공개 전환(Cutover) 및 점검 기준

### 1. 사전 점검 완료 항목 (`100.100.9.74`)
- **Cloudflare Origin CA 인증서**: `/etc/aiconnect/public-tls/origin.crt` (`2026-11-20` ~ `2041-11-16 GMT`)
- **전용 AOP 클라이언트 CA**: `/etc/aiconnect/aop-zone/ca.crt` (`CN=AIConnect AOP Root CA`, `2026-11-20` ~ `2031-11-19 GMT`, `ssl_verify_client on`)
- **방화벽 및 일일 IP 동기화**: `aiconnect-public-firewall.service` (`inet aiconnect_public_guard` nftables 테이블 활성) 및 `aiconnect-public-firewall-refresh.timer` (`daily`, `/opt/aiconnect-public-staging/cf-realip.conf` 원자적 갱신·프록시 리로드)
- **비공개 경계 및 계정 초대 전용 원칙**: API(`8080`), 관리 LB(`100.100.9.74:80`), DB/Redis 포트는 인터넷에 비공개이며, 공개 HTTPS 입구(`public-nginx.conf`)에서 `/api/auth/bootstrap`, `/api/admin/users`, `/actuator/`를 차단해 초대 기반 계정 가입 + TOTP 2FA만 허용한다.
- **보안 응답 헤더**: `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy` 및 `proxy_cookie_flags aiconnect_refresh secure httponly samesite=strict` 적용.

### 2. 정식 공개 전환(IP 허용 목록 해제) 절차
버전 관리된 공개 Compose 오버레이(`deploy/ha/docker-compose.public.yml`)와 systemd 유닛(`deploy/ha/aiconnect-public-ingress.service`)을 사용하여 제한 시험용 컨테이너(`aiconnect-public-limited`)에서 정식 공개 입구(`aiconnect-public`)로 전환한다.

```bash
# 1. 기존 제한 시험용 및 스테이징 입구 중지·비활성화
sudo systemctl disable --now aiconnect-public-limited.service
sudo docker stop aiconnect-public-staging || true

# 2. 정식 공개 컨테이너 생성 (AICONNECT_PUBLIC_BIND_ADDRESS=<OCI_PRIVATE_IP>, 인증서/realip 환경변수 설정 필수)
cd /opt/aiconnect/deploy/ha
docker compose -p aiconnect-ha \
  --env-file .env --env-file .env.external-data --env-file /etc/aiconnect/public-ingress.env \
  -f docker-compose.yml -f docker-compose.external-monitoring.yml -f docker-compose.external-data.yml -f docker-compose.public.yml \
  up -d --no-deps --no-build public-ingress

# 3. systemd 등록 및 활성화
sudo install -m 644 /opt/aiconnect/deploy/ha/aiconnect-public-ingress.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now aiconnect-public-ingress.service
```

### 3. 전환 직후 검증 및 롤백
- **검증**: 외부 비허용 IP에서 `https://www.aiconnectgateway.com/` 및 `https://api.aiconnectgateway.com/v1/models` 접근, `/api/auth/bootstrap` · `/api/admin/users` · `/actuator/health` `404` 차단, Origin `443` 직접 접속(AOP 미첨부) 차단(`400`/`drop`), `Strict-Transport-Security` 헤더 및 `aiconnect_refresh` `Secure; HttpOnly; SameSite=Strict` 쿠키 확인.
- **롤백**: `sudo systemctl disable --now aiconnect-public-ingress.service` 후 필요 시 `sudo systemctl enable --now aiconnect-public-limited.service`로 즉시 원복한다.
