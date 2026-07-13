#!/usr/bin/env bash
set -Eeuo pipefail

log() { printf '\n[AICONNECT-FULL] %s\n' "$*"; }
warn() { printf '\n[AICONNECT-FULL][WARN] %s\n' "$*" >&2; }
die() { printf '\n[AICONNECT-FULL][ERROR] %s\n' "$*" >&2; exit 1; }

APT_LOCK_TIMEOUT_SECONDS="${AICONNECT_APT_LOCK_TIMEOUT_SECONDS:-600}"
apt_get() {
  local attempt
  for attempt in 1 2 3; do
    if apt-get -o "DPkg::Lock::Timeout=${APT_LOCK_TIMEOUT_SECONDS}" "$@"; then
      return 0
    fi
    if ((attempt < 3)); then
      warn "apt failed due to a package lock or transient network error. Retrying in 10 seconds. (${attempt}/3)"
      sleep 10
    fi
  done
  return 1
}

if ((EUID != 0)); then
  exec sudo -E bash "$0" "$@"
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
QUICKSTART="$ROOT_DIR/quickstart_standalone.sh"
TARGET_USER="${SUDO_USER:-${AICONNECT_TARGET_USER:-}}"
LAN_REQUESTED=0
for argument in "$@"; do
  [[ "$argument" == "--lan" ]] && LAN_REQUESTED=1
done

[[ -n "$TARGET_USER" && "$TARGET_USER" != "root" ]] || die "일반 사용자로 sudo bash $0 형태로 실행하거나 AICONNECT_TARGET_USER를 지정하세요."
id "$TARGET_USER" >/dev/null 2>&1 || die "대상 사용자가 없습니다: $TARGET_USER"
[[ -f "$QUICKSTART" && -f "$ROOT_DIR/docker-compose.yml" ]] || die "AICONNECT 저장소의 deploy 디렉터리에서 실행해야 합니다."

. /etc/os-release
[[ "${ID:-}" == "ubuntu" ]] || die "현재 자동 설치는 Ubuntu Server만 지원합니다. 감지된 OS: ${PRETTY_NAME:-unknown}"
ubuntu_codename="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
[[ -n "$ubuntu_codename" ]] || die "Ubuntu codename을 확인하지 못했습니다."
architecture="$(dpkg --print-architecture)"

export DEBIAN_FRONTEND=noninteractive
log "기본 패키지를 설치합니다."
apt_get update
apt_get install -y ca-certificates curl wget git openssl gnupg tar

install_docker() {
  log "Docker 공식 APT 저장소에서 Engine, Buildx, Compose v2를 설치합니다."
  local conflicts=()
  local package
  for package in docker.io docker-compose docker-compose-v2 docker-doc podman-docker containerd runc; do
    if dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
      conflicts+=("$package")
    fi
  done
  if ((${#conflicts[@]} > 0)); then
    apt_get remove -y "${conflicts[@]}"
  fi

  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  printf '%s\n' \
    'Types: deb' \
    'URIs: https://download.docker.com/linux/ubuntu' \
    "Suites: $ubuntu_codename" \
    'Components: stable' \
    "Architectures: $architecture" \
    'Signed-By: /etc/apt/keyrings/docker.asc' \
    >/etc/apt/sources.list.d/docker.sources

  apt_get update
  apt_get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
}

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  install_docker
else
  log "기존 Docker Engine과 Compose v2를 사용합니다."
fi

systemctl enable --now docker
getent group docker >/dev/null 2>&1 || groupadd docker
usermod -aG docker "$TARGET_USER"

docker info >/dev/null
docker compose version

install_tailscale() {
  log "Tailscale 공식 stable APT 저장소에서 클라이언트를 설치합니다."
  curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${ubuntu_codename}.noarmor.gpg" \
    -o /usr/share/keyrings/tailscale-archive-keyring.gpg
  curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${ubuntu_codename}.tailscale-keyring.list" \
    -o /etc/apt/sources.list.d/tailscale.list
  apt_get update
  apt_get install -y tailscale
}

if ! command -v tailscale >/dev/null 2>&1; then
  install_tailscale
else
  log "기존 Tailscale 클라이언트를 사용합니다."
fi

systemctl enable --now tailscaled
tailscale version

available_kib="$(df --output=avail / | tail -n 1 | tr -d ' ')"
memory_kib="$(awk '/MemTotal/ { print $2 }' /proc/meminfo)"
if ((available_kib < 12 * 1024 * 1024)); then
  warn "루트 디스크 여유가 12GiB 미만입니다. 이미지 빌드와 로그를 위해 디스크 확장을 권장합니다."
fi
if ((memory_kib < 4 * 1024 * 1024)); then
  warn "RAM이 4GiB 미만입니다. 빌드 중 메모리 부족 가능성이 있어 RAM 또는 Swap 확장을 권장합니다."
fi

if ((LAN_REQUESTED == 1)); then
  log "신뢰된 내부망 HTTP용 Standalone 배포를 시작합니다."
else
  log "Tailscale 인증 전 로컬 전용 Standalone 배포를 시작합니다."
fi
forward_env=()
for name in AICONNECT_ENV_FILE AICONNECT_WAIT_TIMEOUT_SECONDS AICONNECT_ADMIN_EMAIL AICONNECT_ADMIN_PASSWORD AICONNECT_EXTERNAL_BASE_URL AICONNECT_LM_STUDIO_URL; do
  if [[ -n "${!name:-}" ]]; then
    forward_env+=("${name}=${!name}")
  fi
done

runuser -u "$TARGET_USER" -- env "${forward_env[@]}" \
  bash "$QUICKSTART" --defer-tailscale-auth "$@"

if ((LAN_REQUESTED == 1)); then
  cat <<EOF

============================================================
서버 기본 설치와 내부망 Standalone 배포가 완료되었습니다.
내부망 접속 주소는 위 Quickstart 결과에 표시됩니다.

GPU 서버와 Tailnet으로 연결하려면 남은 수동 인증을 수행하세요:
  sudo tailscale up

LAN 웹/API 사용에는 재배포가 필요하지 않습니다. 인증 후 인프라 화면에서
GPU 서버의 Tailscale LM Studio 주소를 등록하세요.
============================================================
EOF
else
  cat <<EOF

============================================================
서버 기본 설치와 로컬 Standalone 배포가 완료되었습니다.

남은 수동 단계(Tailscale 인증):
  sudo tailscale up

인증 완료 후 $TARGET_USER 사용자로 다음을 실행하세요:
  cd $ROOT_DIR
  ./quickstart_standalone.sh

두 번째 실행이 Tailnet 전용 HTTPS 주소를 만들고 .env의 Base URL을
자동으로 갱신합니다. 인증 전에는 외부 PC에서 웹 화면을 사용하지 마세요.
============================================================
EOF
fi
