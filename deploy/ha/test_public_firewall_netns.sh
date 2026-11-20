#!/usr/bin/env bash
# Runs only in disposable namespaces. Requires root, iproute2, nft, curl, Python.
set -euo pipefail
[[ "$EUID" -eq 0 ]] || { echo 'Run with sudo' >&2; exit 1; }
base=$(cd -- "$(dirname -- "$0")" && pwd)
p=$(mktemp -d /tmp/aiconnect-nft-test.XXXXXXXX)
router="aict$$" client="aicc$$" backend="aicb$$" pid1='' pid2=''
cleanup() {
  [[ -z "$pid1" ]] || kill "$pid1" 2>/dev/null || true
  [[ -z "$pid2" ]] || kill "$pid2" 2>/dev/null || true
  ip netns del "$router" 2>/dev/null || true
  ip netns del "$client" 2>/dev/null || true
  ip netns del "$backend" 2>/dev/null || true
  rm -rf -- "$p"
}
trap cleanup EXIT
PYTHONPATH="$base" python3 -c 'from render_public_nft import render; print(render({"success":True,"result":{"ipv4_cidrs":["1.1.1.0/24"],"ipv6_cidrs":["2606:4700::/32"]}}))' > "$p/test.nft"
ip netns add "$router"; ip netns add "$client"; ip netns add "$backend"
ip -n "$router" link add vserver type veth peer name vclient
ip -n "$router" link set vclient netns "$client"
ip -n "$router" link add vback type veth peer name vapp
ip -n "$router" link set vapp netns "$backend"
for ns in "$router" "$client" "$backend"; do ip -n "$ns" link set lo up; done
ip -n "$router" addr add 1.1.1.2/24 dev vserver
ip -n "$router" addr add 203.0.113.2/24 dev vserver
ip -n "$client" addr add 1.1.1.1/24 dev vclient
ip -n "$client" addr add 203.0.113.1/24 dev vclient
ip -n "$router" addr add 192.0.2.1/24 dev vback
ip -n "$backend" addr add 192.0.2.2/24 dev vapp
ip -n "$router" link set vserver up; ip -n "$router" link set vback up
ip -n "$client" link set vclient up; ip -n "$backend" link set vapp up
ip -n "$client" route add 192.0.2.0/24 via 1.1.1.2
ip -n "$backend" route add 1.1.1.0/24 via 192.0.2.1
ip -n "$backend" route add 203.0.113.0/24 via 192.0.2.1
ip netns exec "$router" sysctl -qw net.ipv4.ip_forward=1
ip netns exec "$router" nft -f "$p/test.nft"
ip netns exec "$router" nft add table ip test_nat
ip netns exec "$router" nft 'add chain ip test_nat prerouting { type nat hook prerouting priority dstnat; policy accept; }'
ip netns exec "$router" nft 'add rule ip test_nat prerouting iifname "vserver" tcp dport 443 dnat to 192.0.2.2:443'
ip netns exec "$backend" python3 -m http.server 443 --bind 0.0.0.0 >/dev/null 2>&1 & pid1=$!
ip netns exec "$client" python3 -m http.server 443 --bind 0.0.0.0 >/dev/null 2>&1 & pid2=$!
sleep 1
probe() { ip netns exec "$1" curl -s --noproxy '*' --max-time 2 --interface "$2" -o /dev/null -w '%{http_code}' "http://$3:443/" || true; }
allowed=$(probe "$client" 1.1.1.1 1.1.1.2)
denied=$(probe "$client" 203.0.113.1 203.0.113.2)
egress=$(probe "$backend" 192.0.2.2 203.0.113.1)
printf 'DNAT ingress allowed=%s denied=%s; container egress=%s\n' "$allowed" "$denied" "$egress"
[[ "$allowed" = 200 && "$denied" = 000 && "$egress" = 200 ]]
