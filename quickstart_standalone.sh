#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ENV_FILE="${AICONNECT_ENV_FILE:-.env}"
WAIT_TIMEOUT="${AICONNECT_WAIT_TIMEOUT_SECONDS:-600}"
NON_INTERACTIVE=0
SKIP_BOOTSTRAP=0
DEFER_TAILSCALE_AUTH=0
LAN_MODE=0

log() { printf '\n[AICONNECT] %s\n' "$*"; }
warn() { printf '\n[AICONNECT][WARN] %s\n' "$*" >&2; }
die() { printf '\n[AICONNECT][ERROR] %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
AICONNECT Standalone quickstart for Linux

Usage:
  ./quickstart_standalone.sh [options]

Options:
  --non-interactive  Do not prompt. Admin bootstrap runs only when
                     AICONNECT_ADMIN_EMAIL and AICONNECT_ADMIN_PASSWORD are set.
  --skip-bootstrap       Start without creating the first administrator.
  --defer-tailscale-auth Install and start locally before `sudo tailscale up`.
                         Rerun without this option after Tailscale authentication.
  --lan                  Expose AICONNECT on a trusted LAN over HTTP.
                         Sets AUTH_COOKIE_SECURE=false; never use on the internet.
  -h, --help             Show this help.

Optional environment variables:
  AICONNECT_ENV_FILE=.env
  AICONNECT_WAIT_TIMEOUT_SECONDS=600
  AICONNECT_ADMIN_EMAIL=admin@example.com
  AICONNECT_ADMIN_PASSWORD='at-least-12-characters'
  AICONNECT_EXTERNAL_BASE_URL=https://api.example.com/v1
  AICONNECT_LM_STUDIO_URL=http://100.x.y.z:1234
  AICONNECT_LAN_IP=192.168.35.101
USAGE
}

while (($# > 0)); do
  case "$1" in
    --non-interactive) NON_INTERACTIVE=1 ;;
    --skip-bootstrap) SKIP_BOOTSTRAP=1 ;;
    --defer-tailscale-auth) DEFER_TAILSCALE_AUTH=1 ;;
    --lan) LAN_MODE=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "알 수 없는 옵션입니다: $1" ;;
  esac
  shift
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "$1 명령이 없습니다. 먼저 설치한 뒤 다시 실행하세요."
}

secret() {
  openssl rand -hex 32
}

read_env_value() {
  local name="$1"
  awk -F= -v key="$name" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$ENV_FILE"
}

set_env_value() {
  local name="$1"
  local value="$2"
  local temporary
  temporary="$(mktemp)"
  awk -F= -v key="$name" -v replacement="${name}=${value}" '
    BEGIN { found = 0 }
    $1 == key { print replacement; found = 1; next }
    { print }
    END { if (!found) print replacement }
  ' "$ENV_FILE" >"$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$ENV_FILE"
}

json_escape() {
  local value="$1"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  printf '%s' "$value"
}

[[ "$(uname -s)" == "Linux" ]] || die "이 파일은 Linux VM 전용입니다. Windows에서는 실행할 수 없습니다."
[[ -f docker-compose.yml && -f Dockerfile && -d frontend ]] || die "저장소 루트에서 실행해야 합니다."

for command_name in docker tailscale curl openssl awk grep sed; do
  require_command "$command_name"
done

docker compose version >/dev/null 2>&1 || die "Docker Compose v2 플러그인이 필요합니다: docker compose version"
docker info >/dev/null 2>&1 || die "현재 사용자로 Docker에 접근할 수 없습니다. Docker 서비스를 시작하고 사용자를 docker 그룹에 추가한 뒤 다시 로그인하세요."

if ((LAN_MODE == 0 && DEFER_TAILSCALE_AUTH == 0)); then
  tailscale status >/dev/null 2>&1 || die "Tailscale이 Tailnet에 연결되지 않았습니다. 먼저 sudo tailscale up을 완료하세요."
  mapfile -t tailscale_ips < <(tailscale ip -4 2>/dev/null)
  ((${#tailscale_ips[@]} > 0)) || die "Tailscale IPv4 주소를 찾지 못했습니다."
elif ((LAN_MODE == 0)); then
  warn "Tailscale 인증을 유예했습니다. 서비스는 loopback에만 배포되며 Tailnet 사용자는 아직 접속할 수 없습니다."
fi

if [[ ! -f "$ENV_FILE" ]]; then
  project_name="${COMPOSE_PROJECT_NAME:-$(basename "$SCRIPT_DIR" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9_-]+//g')}"
  existing_volume="${project_name}_mariadb-data"
  if docker volume inspect "$existing_volume" >/dev/null 2>&1; then
    die "$existing_volume 볼륨이 있지만 $ENV_FILE 파일이 없습니다. 기존 API 키와 암호화 데이터를 보호하려면 원래 환경 파일을 복원하세요. 새 비밀값을 만들면 안 됩니다."
  fi
fi

serve_output_file="$(mktemp)"
bootstrap_response_file="$(mktemp)"
cleanup() { rm -f "$serve_output_file" "$bootstrap_response_file"; }
trap cleanup EXIT

if ((LAN_MODE == 1)); then
  lan_ip="${AICONNECT_LAN_IP:-$(hostname -I | awk '{print $1}')}"
  [[ "$lan_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "AICONNECT_LAN_IP 또는 자동 감지 LAN IPv4 주소가 올바르지 않습니다: $lan_ip"
  serve_url="http://${lan_ip}"
  internal_base_url="${serve_url}/v1"
  http_bind_address="$lan_ip"
  cookie_secure="false"
elif ((DEFER_TAILSCALE_AUTH == 1)); then
  serve_url="http://127.0.0.1"
  internal_base_url="http://127.0.0.1/v1"
  http_bind_address="127.0.0.1"
  cookie_secure="true"
else
  log "Tailscale Serve로 Tailnet 전용 HTTPS 주소를 준비합니다."
  if ! tailscale serve --bg --yes 80 >"$serve_output_file" 2>&1; then
    cat "$serve_output_file" >&2
    die "Tailscale Serve 설정에 실패했습니다. 출력에 표시된 URL에서 Tailnet HTTPS를 허용한 뒤 다시 실행하세요."
  fi
  serve_status="$(tailscale serve status 2>&1 || true)"
  serve_url="$(printf '%s\n' "$serve_status" | grep -Eo 'https://[^ /]+' | sed -n '1p' || true)"
  [[ -n "$serve_url" ]] || die "Tailscale Serve HTTPS 주소를 확인하지 못했습니다. tailscale serve status를 확인하세요."
  serve_url="${serve_url%/}"
  internal_base_url="${serve_url}/v1"
  http_bind_address="127.0.0.1"
  cookie_secure="true"
fi
external_base_url="${AICONNECT_EXTERNAL_BASE_URL:-}"

if [[ -n "$external_base_url" ]]; then
  [[ "$external_base_url" =~ ^https://[^[:space:]]+$ ]] || die "AICONNECT_EXTERNAL_BASE_URL은 공백 없는 https:// 주소여야 합니다."
  external_base_url="${external_base_url%/}"
  [[ "$external_base_url" == */v1 ]] || external_base_url="${external_base_url}/v1"
fi

new_env=0
if [[ -f "$ENV_FILE" ]]; then
  log "기존 $ENV_FILE 파일을 유지합니다. 비밀값은 다시 만들지 않습니다."
  profile="$(read_env_value AICONNECT_DEPLOYMENT_PROFILE || true)"
  provider="$(read_env_value AICONNECT_SHARED_STATE_PROVIDER || true)"
  [[ -z "$profile" || "$profile" == "STANDALONE" ]] || die "$ENV_FILE은 Standalone 프로필이 아닙니다: $profile"
  [[ -z "$provider" || "$provider" == "LOCAL" ]] || die "$ENV_FILE은 LOCAL 공유 상태가 아닙니다: $provider"
  if ((LAN_MODE == 1 || DEFER_TAILSCALE_AUTH == 0)); then
    set_env_value GATEWAY_INTERNAL_BASE_URL "$internal_base_url"
    set_env_value AICONNECT_HTTP_BIND_ADDRESS "$http_bind_address"
    set_env_value AUTH_COOKIE_SECURE "$cookie_secure"
    if [[ -n "$external_base_url" ]]; then
      set_env_value GATEWAY_EXTERNAL_BASE_URL "$external_base_url"
    fi
  fi
else
  log "Standalone 운영 비밀값과 환경 파일을 생성합니다."
  umask 077
  {
    printf '%s\n' "# Generated by quickstart_standalone.sh. Never commit this file."
    printf 'DB_PASSWORD=%s\n' "$(secret)"
    printf 'MARIADB_ROOT_PASSWORD=%s\n' "$(secret)"
    printf 'ADMIN_API_TOKEN=%s\n' "$(secret)"
    printf 'API_KEY_PEPPER=%s\n' "$(secret)"
    printf 'GATEWAY_ENCRYPTION_KEY=%s\n' "$(secret)"
    printf 'AUTH_SIGNING_KEY=%s\n' "$(secret)"
    printf 'AUTH_REFRESH_PEPPER=%s\n' "$(secret)"
    printf 'GRAFANA_ADMIN_USER=admin\n'
    printf 'GRAFANA_ADMIN_PASSWORD=%s\n' "$(secret)"
    printf '\nAICONNECT_DEPLOYMENT_PROFILE=STANDALONE\n'
    printf 'AICONNECT_SHARED_STATE_PROVIDER=LOCAL\n'
    printf 'AICONNECT_INSTANCE_ID=standalone-1\n'
    printf 'AICONNECT_HTTP_BIND_ADDRESS=%s\n' "$http_bind_address"
    printf 'AUTH_COOKIE_SECURE=%s\n' "$cookie_secure"
    printf '\nHEALTH_CHECK_DELAY_MS=30000\n'
    printf 'HEALTH_CHECK_INITIAL_DELAY_MS=30000\n'
    printf 'RUNTIME_CONNECT_TIMEOUT_MS=3000\n'
    printf 'RUNTIME_RESPONSE_TIMEOUT_MS=360000\n'
    printf '\nGATEWAY_INTERNAL_BASE_URL=%s\n' "$internal_base_url"
    printf 'GATEWAY_EXTERNAL_BASE_URL=%s\n' "$external_base_url"
  } >"$ENV_FILE"
  chmod 600 "$ENV_FILE"
  new_env=1
fi

bootstrap_requested=0
admin_email="${AICONNECT_ADMIN_EMAIL:-}"
admin_password="${AICONNECT_ADMIN_PASSWORD:-}"
if ((SKIP_BOOTSTRAP == 0)); then
  if ((NON_INTERACTIVE == 1)); then
    if [[ -n "$admin_email" && -n "$admin_password" ]]; then
      bootstrap_requested=1
    else
      warn "비대화형 실행에 관리자 이메일 또는 비밀번호가 없어 최초 관리자 생성을 건너뜁니다."
    fi
  else
    read -r -p "최초 관리자 생성을 시도할까요? [Y/n] " bootstrap_answer
    bootstrap_answer="${bootstrap_answer:-Y}"
    if [[ "$bootstrap_answer" =~ ^[Yy]$ ]]; then
      bootstrap_requested=1
      if [[ -z "$admin_email" ]]; then
        read -r -p "관리자 이메일 [admin@aiconnect.local]: " admin_email
        admin_email="${admin_email:-admin@aiconnect.local}"
      fi
      if [[ -z "$admin_password" ]]; then
        read -r -s -p "관리자 비밀번호(12~128자): " admin_password
        printf '\n'
        read -r -s -p "관리자 비밀번호 확인: " admin_password_confirm
        printf '\n'
        [[ "$admin_password" == "$admin_password_confirm" ]] || die "비밀번호 확인이 일치하지 않습니다."
      fi
    fi
  fi
fi

if ((bootstrap_requested == 1)); then
  [[ "$admin_email" == *@* && ${#admin_email} -le 320 ]] || die "유효한 관리자 이메일을 입력하세요."
  ((${#admin_password} >= 12 && ${#admin_password} <= 128)) || die "관리자 비밀번호는 12~128자여야 합니다."
fi

compose=(docker compose --env-file "$ENV_FILE")
"${compose[@]}" config --quiet
if ! docker compose up --help | grep -q -- '--wait'; then
  die "docker compose up --wait를 지원하는 최신 Docker Compose v2가 필요합니다. Docker를 업그레이드하세요."
fi

log "MariaDB, Gateway, Frontend, Nginx, Prometheus, Grafana를 빌드하고 시작합니다."
if ! "${compose[@]}" up -d --build --wait --wait-timeout "$WAIT_TIMEOUT"; then
  "${compose[@]}" ps >&2 || true
  "${compose[@]}" logs --tail 120 >&2 || true
  die "컨테이너 시작에 실패했습니다. 위 로그를 확인하세요."
fi

"${compose[@]}" exec -T api curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null
curl -fsS "$serve_url" >/dev/null

if ((bootstrap_requested == 1)); then
  payload="$(printf '{\"email\":\"%s\",\"password\":\"%s\"}' "$(json_escape "$admin_email")" "$(json_escape "$admin_password")")"
  if ! bootstrap_code="$(curl -sS -o "$bootstrap_response_file" -w '%{http_code}' -H 'Content-Type: application/json' --data-binary "$payload" http://127.0.0.1/api/auth/bootstrap)"; then
    die "최초 관리자 생성 요청을 전송하지 못했습니다."
  fi
  case "$bootstrap_code" in
    200) log "최초 관리자 계정을 생성했습니다: $admin_email" ;;
    409) warn "최초 관리자가 이미 존재하므로 관리자 생성을 건너뜁니다." ;;
    *)
      cat "$bootstrap_response_file" >&2
      die "최초 관리자 생성에 실패했습니다. HTTP $bootstrap_code"
      ;;
  esac
fi

if [[ -n "${AICONNECT_LM_STUDIO_URL:-}" ]]; then
  lmstudio_url="${AICONNECT_LM_STUDIO_URL%/}"
  [[ "$lmstudio_url" =~ ^https?://[^[:space:]]+$ ]] || die "AICONNECT_LM_STUDIO_URL 형식이 올바르지 않습니다."
  log "Gateway 컨테이너에서 LM Studio 연결을 확인합니다: $lmstudio_url"
  if lmstudio_code="$("${compose[@]}" exec -T -e "CHECK_URL=${lmstudio_url}/api/v1/models" api sh -c 'curl -sS -o /dev/null -w "%{http_code}" --connect-timeout 5 "$CHECK_URL"')"; then
    [[ "$lmstudio_code" != "000" ]] || die "LM Studio에 연결하지 못했습니다."
    log "LM Studio가 HTTP $lmstudio_code로 응답했습니다. 401도 네트워크 연결 성공을 의미하며 UI에서 Token을 등록하면 됩니다."
  else
    die "Gateway 컨테이너에서 LM Studio에 연결하지 못했습니다. Tailscale ACL, LM Studio Serve on Local Network, 포트 1234를 확인하세요."
  fi
fi

configured_internal_url="$(read_env_value GATEWAY_INTERNAL_BASE_URL || true)"
configured_external_url="$(read_env_value GATEWAY_EXTERNAL_BASE_URL || true)"

printf '\n============================================================\n'
printf 'AICONNECT Standalone 준비 완료\n'
if ((LAN_MODE == 1)); then
  printf '내부망 관리 화면: %s\n' "$serve_url"
  printf '내부망 OpenAI Base URL: %s\n' "${configured_internal_url:-$internal_base_url}"
elif ((DEFER_TAILSCALE_AUTH == 1)); then
  printf '로컬 관리 화면: http://127.0.0.1 (VM 내부에서만 접근 가능)\n'
  printf '다음 단계: sudo tailscale up 실행 후 ./quickstart_standalone.sh 재실행\n'
else
  printf '관리 화면: %s\n' "$serve_url"
  printf 'Tailnet OpenAI Base URL: %s\n' "${configured_internal_url:-$internal_base_url}"
fi
if [[ -n "$configured_external_url" ]]; then
  printf '외부 OpenAI Base URL: %s\n' "$configured_external_url"
fi
printf '상태 확인: docker compose --env-file %s ps\n' "$ENV_FILE"
printf '로그 확인: docker compose --env-file %s logs -f api\n' "$ENV_FILE"
printf '중지: docker compose --env-file %s down\n' "$ENV_FILE"
printf 'Grafana: VM에서 http://127.0.0.1:3000 또는 SSH 터널 사용\n'
printf '비밀값: %s 파일을 VM 밖의 암호화 저장소에 백업하세요.\n' "$ENV_FILE"
printf '============================================================\n'

if ((LAN_MODE == 1)); then
  warn "신뢰된 내부망 HTTP 모드입니다. AUTH_COOKIE_SECURE=false가 적용됐으므로 이 포트를 인터넷에 공개하지 마세요. GPU Tailnet 연결에는 별도로 sudo tailscale up이 필요합니다."
elif ((DEFER_TAILSCALE_AUTH == 1)); then
  warn "Tailscale 인증 전 로컬 배포만 완료했습니다. sudo tailscale up 후 일반 Quickstart를 재실행해야 설치가 끝납니다."
elif ((new_env == 1)); then
  warn "이 설치는 Tailnet 내부 HTTPS용입니다. 인터넷 공개가 필요하면 공인 DNS/TLS 리버스 프록시를 구성하고 AICONNECT_EXTERNAL_BASE_URL을 설정하세요."
fi
