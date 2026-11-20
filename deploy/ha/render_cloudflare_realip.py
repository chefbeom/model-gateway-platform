#!/usr/bin/env python3
"""Render nginx trusted-proxy directives; never writes or reloads a firewall."""
import ipaddress
import json
import sys
import urllib.request

URL = "https://api.cloudflare.com/client/v4/ips"


def validated_ranges(document):
    if document.get("success") is not True:
        raise ValueError("Cloudflare IP API did not report success")
    result = document.get("result")
    if not isinstance(result, dict):
        raise ValueError("Missing Cloudflare IP result")
    groups = []
    for name, version in (("ipv4_cidrs", 4), ("ipv6_cidrs", 6)):
        values = result.get(name)
        if not isinstance(values, list) or not 1 <= len(values) <= 100:
            raise ValueError("Missing or excessive " + name)
        group = []
        for value in values:
            network = ipaddress.ip_network(value, strict=True)
            if network.version != version or not network.is_global or network.prefixlen < (8 if version == 4 else 16):
                raise ValueError("Unsafe Cloudflare IP range")
            group.append(network)
        if len(set(group)) != len(group):
            raise ValueError("Duplicate Cloudflare IP range")
        groups.extend(group)
    return groups


def render(document):
    return "# Generated from Cloudflare's official IP API.\n" + "".join(
        f"set_real_ip_from {network};\n" for network in validated_ranges(document)
    )


def fetch_document():
    request = urllib.request.Request(URL, headers={"User-Agent": "AIConnect-ops/1.0", "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        if response.geturl() != URL:
            raise ValueError("Unexpected Cloudflare API redirect")
        data = response.read(65537)
        if len(data) > 65536:
            raise ValueError("Cloudflare IP response is too large")
    return json.loads(data)


def main():
    sys.stdout.write(render(fetch_document()))


if __name__ == "__main__":
    main()
