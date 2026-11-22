#!/usr/bin/env bash
# Update the dedicated Cloudflare guard atomically. Never flush other rules.
set -euo pipefail
umask 077
root="${AICONNECT_PUBLIC_RULE_DIR:-/etc/aiconnect/public-ingress}"
lib="${AICONNECT_PUBLIC_RULE_LIB:-/usr/local/lib/aiconnect-public}"
staging="${AICONNECT_PUBLIC_NGINX_DIR:-/opt/aiconnect-public-staging}"
max_age_minutes="${AICONNECT_CF_MAX_AGE_MINUTES:-10080}"
[[ "${EUID}" -eq 0 ]] || { echo 'Must run as root' >&2; exit 1; }
[[ "$max_age_minutes" =~ ^[0-9]+$ ]] && (( max_age_minutes > 0 )) || { echo 'Invalid maximum age' >&2; exit 1; }
install -d -m 700 "$root"
exec 9>"$root/update.lock"
flock -n 9 || { echo 'Another firewall refresh is running' >&2; exit 1; }
tmp=$(mktemp -d /run/aiconnect-cf.XXXXXXXX)
trap 'rm -rf -- "$tmp"' EXIT

# Render both outputs from the same response. Never replace a working guard
# with unvalidated data. An offline boot may use a recent last-good snapshot.
if curl -fsS --max-time 15 --proto '=https' --max-redirs 0 -H 'Accept: application/json' \
    -H 'User-Agent: AIConnect-ops/1.0' https://api.cloudflare.com/client/v4/ips > "$tmp/ips.json" && \
    PYTHONPATH="$lib" python3 -c 'import json,sys;from render_public_nft import render;sys.stdout.write(render(json.load(sys.stdin)))' < "$tmp/ips.json" > "$tmp/guard.nft" && \
    PYTHONPATH="$lib" python3 -c 'import json,sys;from render_cloudflare_realip import render;sys.stdout.write(render(json.load(sys.stdin)))' < "$tmp/ips.json" > "$tmp/realip.conf"; then
  refreshed=1
elif [[ -f "$root/guard.nft" && -f "$root/realip.conf" ]] && \
     [[ -n "$(find "$root/guard.nft" -maxdepth 0 -mmin "-$max_age_minutes" -print)" ]]; then
  cp "$root/guard.nft" "$tmp/guard.nft"
  cp "$root/realip.conf" "$tmp/realip.conf"
  refreshed=0
else
  echo 'Cloudflare IP list unavailable and no recent snapshot; refusing to start' >&2
  exit 1
fi

if nft list table inet aiconnect_public_guard >/dev/null 2>&1; then
  printf '%s\n' 'delete table inet aiconnect_public_guard' > "$tmp/batch.nft"
else
  : > "$tmp/batch.nft"
fi
cat "$tmp/guard.nft" >> "$tmp/batch.nft"
nft -c -f "$tmp/batch.nft"
nft -f "$tmp/batch.nft"
if (( refreshed )); then
  install -m 600 "$tmp/guard.nft" "$root/guard.nft.new"
  install -m 600 "$tmp/realip.conf" "$root/realip.conf.new"
  mv -f "$root/guard.nft.new" "$root/guard.nft"
  mv -f "$root/realip.conf.new" "$root/realip.conf"
fi

# This path is optional at early boot. Overwrite in place so Docker bind mounts
# see the new contents. A failed validation restores the prior file.
if [[ -f "$staging/cf-realip.conf" ]]; then
  cp "$staging/cf-realip.conf" "$tmp/realip.old"
  cp "$tmp/realip.conf" "$staging/cf-realip.conf"
  chmod 644 "$staging/cf-realip.conf"
  for proxy in aiconnect-public-staging aiconnect-public-limited aiconnect-public; do
    if [[ "$(docker inspect -f '{{.State.Running}}' "$proxy" 2>/dev/null || true)" == true ]]; then
      if ! docker exec "$proxy" nginx -t >/dev/null 2>&1 || ! docker exec "$proxy" nginx -s reload >/dev/null 2>&1; then
        cp "$tmp/realip.old" "$staging/cf-realip.conf"
        docker exec "$proxy" nginx -s reload >/dev/null 2>&1 || true
        echo "Proxy $proxy reload failed; previous trusted IP file restored" >&2
        exit 1
      fi
    fi
  done
fi
echo "Cloudflare guard updated (fresh=$refreshed)"
