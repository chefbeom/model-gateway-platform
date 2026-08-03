#!/usr/bin/env bash
set -euo pipefail

private_cidr="${AICONNECT_PRIVATE_CIDR:-}"
http_port="${AICONNECT_PRIVATE_HTTP_PORT:-80}"
mode="${1:---apply}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script with sudo." >&2
  exit 1
fi

if [[ -z "${private_cidr}" ]]; then
  echo "AICONNECT_PRIVATE_CIDR is required (example: 10.0.0.0/16)." >&2
  exit 1
fi

apply_rules() {
  iptables -N DOCKER-USER 2>/dev/null || true
  iptables -C DOCKER-USER -p tcp --dport "${http_port}" -s 127.0.0.0/8 -m comment --comment aiconnect-private-loopback -j ACCEPT 2>/dev/null \
    || iptables -I DOCKER-USER 1 -p tcp --dport "${http_port}" -s 127.0.0.0/8 -m comment --comment aiconnect-private-loopback -j ACCEPT
  iptables -C DOCKER-USER -i tailscale0 -p tcp --dport "${http_port}" -m comment --comment aiconnect-private-tailnet -j ACCEPT 2>/dev/null \
    || iptables -I DOCKER-USER 2 -i tailscale0 -p tcp --dport "${http_port}" -m comment --comment aiconnect-private-tailnet -j ACCEPT
  iptables -C DOCKER-USER -p tcp --dport "${http_port}" -s "${private_cidr}" -m comment --comment aiconnect-private-vcn -j ACCEPT 2>/dev/null \
    || iptables -I DOCKER-USER 3 -p tcp --dport "${http_port}" -s "${private_cidr}" -m comment --comment aiconnect-private-vcn -j ACCEPT
  iptables -C DOCKER-USER -p tcp --dport "${http_port}" -m comment --comment aiconnect-private-drop-public -j DROP 2>/dev/null \
    || iptables -A DOCKER-USER -p tcp --dport "${http_port}" -m comment --comment aiconnect-private-drop-public -j DROP
}

if [[ "${mode}" == "--install" ]]; then
  install -m 0755 "$0" /usr/local/sbin/aiconnect-private-firewall
  cat >/etc/systemd/system/aiconnect-private-firewall.service <<EOF
[Unit]
Description=AICONNECT private-only Docker ingress policy
After=docker.service network-online.target
Wants=docker.service network-online.target

[Service]
Type=oneshot
Environment=AICONNECT_PRIVATE_CIDR=${private_cidr}
Environment=AICONNECT_PRIVATE_HTTP_PORT=${http_port}
ExecStart=/usr/local/sbin/aiconnect-private-firewall --apply
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable --now aiconnect-private-firewall.service
else
  apply_rules
fi
