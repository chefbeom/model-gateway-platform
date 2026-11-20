# 초대 베타 ingress — 기본 비활성

`docker-compose.beta.yml`은 명시적으로 추가할 때만 동작하며 기본 바인딩은 loopback입니다. 외부 공개 및 TLS는 별도 설정이며 운영 적용 전 원본 API 8080, 기존 관리 프록시, DB와 Redis가 공개되지 않는지 검증해야 합니다. 공개 ingress는 API 서버와만 별도 네트워크를 공유합니다. 방화벽과 외부 프록시에서 공개 ingress만 도달 가능하게 해야 합니다.

베타 overlay는 기존 관리 load-balancer의 바인딩도 기본적으로 `127.0.0.1:80`으로 덮어씁니다. 기존에 Tailnet IP로 관리 페이지에 접근하던 경우 이 설정을 적용하면 접속이 끊길 수 있습니다. 필요한 경우 `AICONNECT_BETA_ADMIN_BIND_ADDRESS`에 검증된 Tailnet IP를 명시하고 관리 방화벽을 확인하세요. Compose `-f` 순서상 베타 overlay를 맨 마지막에 지정해야 다른 설정이 이 제한을 다시 덮어쓰지 않습니다. Compose 결과와 `docker port`를 적용 전후로 확인해야 합니다. 이 파일을 만드는 것만으로 운영 포트는 변경되지 않습니다. 베타 Chat 요청 본문은 최대 256KiB, 초대 수락은 16KiB입니다.

`AICONNECT_BETA_INGRESS_TOKEN`은 무작위 32자 이상의 별도 비밀값이며 공개 프록시와 두 API 인스턴스에 동일하게 전달합니다. 환경변수 또는 비밀 관리 시스템으로만 전달하고 저장소, 로그, 클라이언트에 노출하지 마세요. 토큰은 Nginx 설정에 사용되므로 Nginx 설정 생성·열람 권한을 신뢰된 운영자로 제한합니다. 공백, 따옴표, 제어 문자 없이 URL-safe 무작위 값만 사용합니다. 토큰 회전은 API 양쪽과 공개 프록시를 조율해야 하며 불일치 시 베타는 차단됩니다.

공개 경로는 GET `/v1/models`, POST `/v1/chat/completions`, POST `/api/beta/invitations/redeem`뿐입니다. 최초 초대 코드는 한 번만 표시되고 POST JSON으로 제출합니다. 초대 이메일 소유권은 자동 검증하지 않으므로 운영자가 신뢰된 별도 경로로 전달해야 합니다. 초대 토큰 및 API 키를 URL·로그에 넣지 마세요. SSE 버퍼링과 POST 재시도를 끕니다.

운영 전 별도 테스트 환경에서 등록된 베타 키로 JSON/SSE 호출, 기존 내부 키의 공개 요청 거부, 사용자 중지 후 대기 요청 차단, 관리 API/임의 경로 거부, 원본 API 직접 접근 차단을 검증하세요. 초대 서비스는 로컬 전용이며 외부 AI 호출·외부 과금 한도는 지원하지 않습니다. 공개 ingress 중지 후 서비스 롤백을 진행합니다.

## Cloudflare Tunnel(`cloudflared`) HTTPS 연계

외부에 베타 공개 API(`/v1/models`, `/v1/chat/completions`, `/api/beta/invitations/redeem`)를 HTTPS로 노출할 때는 원본 포트(`80`, `8080`, `3306`, `6379`)를 직접 개방하지 않고, 호스트 루프백 또는 신뢰된 전용 프록시 대역에 바인딩된 `beta-ingress`(`127.0.0.1:18080`) 앞단에 Cloudflare Tunnel(`cloudflared`)을 연결합니다.

- **터널 대상 지정**: `cloudflared` ingress 규칙에서 `service: http://127.0.0.1:18080`만 연결하고 관리용 `load-balancer`와 내부 포트는 터널에 등록하지 않습니다.
- **신뢰 프록시 기반 실제 클라이언트 IP 복원**:
  - `AICONNECT_BETA_TRUSTED_PROXY_CIDR`(기본 `127.0.0.1/32`)와 `127.0.0.1/32`만 `set_real_ip_from`에 등록하고 `real_ip_header CF-Connecting-IP;`, `real_ip_recursive on;`을 적용합니다.
  - 신뢰 프록시 CIDR을 거치지 않은 직접 요청이 `CF-Connecting-IP` 헤더를 위조해 보내더라도 Nginx가 무시하며, 신뢰 프록시를 경유한 요청에 한해서만 실제 클라이언트 IP가 `$remote_addr` 및 `$binary_remote_addr`에 복원되어 `/api/beta/invitations/redeem`의 분당 5회 레이트 리밋(`limit_req zone=beta_redeem`)이 사용자 IP별로 격리됩니다.
  - `cloudflared`를 호스트 루프백이 아닌 별도 컨테이너/브리지 대역에서 실행하는 경우 `AICONNECT_BETA_TRUSTED_PROXY_CIDR`에 해당 프록시 IP 또는 좁은 CIDR만 명시하세요(`0.0.0.0/0` 금지).
  - 백엔드에는 정제된 단일 `X-Real-IP`, `X-Forwarded-For`, 그리고 Cloudflare가 전달한 `X-Forwarded-Proto: https`(미전달 시 `$scheme`)가 전달됩니다.
- **Cloudflare 100초 제한(HTTP 524) 및 SSE 버퍼링 방지**:
  - `/v1/chat/completions`는 `AICONNECT_BETA_UPSTREAM_TIMEOUT`(기본 `95s`)을 `proxy_read_timeout` 및 `proxy_send_timeout`에 적용해 Cloudflare 프록시 100초 제한 도달 전 정상 종료/제어되도록 구성합니다.
  - `proxy_buffering off;`, `proxy_cache off;`, `proxy_next_upstream off;`, `add_header X-Accel-Buffering no always;`로 SSE 스트리밍 청크가 버퍼링 없이 즉시 전달됩니다. 긴 응답이 예상되는 베타 호출은 `stream: true` 사용을 권장합니다.
- **적용 전 검증 체크리스트**:
  - `docker compose -f deploy/ha/docker-compose.yml -f deploy/ha/docker-compose.beta.yml config -q` 및 `docker port`로 `beta-ingress`가 `127.0.0.1:18080`에만 바인딩되는지 확인합니다.
  - 신뢰 프록시 경로와 비신뢰 직접 호출 경로에서 `CF-Connecting-IP` 위조 시 레이트 리밋 우회가 불가능한지 검증합니다.
  - SSE 스트리밍 응답 헤더에 `X-Accel-Buffering: no`가 포함되고 첫 토큰 청크가 버퍼링 지연 없이 수신되는지 확인합니다.
