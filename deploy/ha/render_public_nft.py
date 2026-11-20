#!/usr/bin/env python3
"""Render a scoped nftables guard. Does not modify firewall state."""
import sys

from render_cloudflare_realip import fetch_document, validated_ranges


def render(document):
    networks = validated_ranges(document)
    v4 = ", ".join(str(n) for n in networks if n.version == 4)
    v6 = ", ".join(str(n) for n in networks if n.version == 6)
    return f'''table inet aiconnect_public_guard {{
    set cloudflare4 {{ type ipv4_addr; flags interval; elements = {{ {v4} }} }}
    set cloudflare6 {{ type ipv6_addr; flags interval; elements = {{ {v6} }} }}
    chain input_guard {{
        type filter hook input priority -10; policy accept;
        iifname != {{ "lo", "tailscale0" }} tcp dport 443 ip saddr != @cloudflare4 drop
        iifname != {{ "lo", "tailscale0" }} tcp dport 443 ip6 saddr != @cloudflare6 drop
    }}
    chain forward_guard {{
        type filter hook forward priority -10; policy accept;
        # Docker-published incoming 443 is DNATed. Do not block unrelated
        # forwarded HTTPS from application containers to external providers.
        iifname != {{ "lo", "tailscale0" }} ct status dnat ct original proto-dst 443 tcp dport 443 ip saddr != @cloudflare4 drop
        iifname != {{ "lo", "tailscale0" }} ct status dnat ct original proto-dst 443 tcp dport 443 ip6 saddr != @cloudflare6 drop
    }}
}}
'''


if __name__ == "__main__":
    if len(sys.argv) != 1:
        raise SystemExit("Usage: render_public_nft.py")
    sys.stdout.write(render(fetch_document()))
