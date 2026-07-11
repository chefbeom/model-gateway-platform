# Tailscale Network Runbook

API consumers connect only to the public AIConnect Gateway. Tailscale is used exclusively between the Gateway host and LM Studio GPU nodes.

```text
API client -- HTTPS 443 --> Gateway -- Tailscale TCP 1234 --> LM Studio
```

Do not expose LM Studio port `1234` through the GPU host's public firewall or router.


## Docker Gateway egress

A Tailscale client installed on the Docker host does not guarantee that bridge-network containers inherit the host's `100.64.0.0/10` routes. This is especially visible with Docker Desktop. AIConnect therefore provides `docker-compose.tailscale.yml`, which runs the official Tailscale image in userspace mode and exposes its outbound HTTP proxy only on the internal Compose network.

Create a pre-authorized, reusable auth key owned by `tag:llm-gateway`, place it in the operator's uncommitted `.env`, and start the production topology with both Compose files:

```powershell
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --env-file .env up -d --build --wait
```

The override sets `RUNTIME_HTTP_PROXY_URL=http://tailscale:1055` only on the API container. LM Studio model discovery, non-streaming completions, and SSE completions use that proxy. Discord and Telegram delivery use a separate direct HTTP client and are not sent through the Tailnet proxy.

The Tailscale container uses:

- `TS_USERSPACE=true`, avoiding a `/dev/net/tun` and elevated Linux capabilities.
- `TS_OUTBOUND_HTTP_PROXY_LISTEN=:1055` for HTTP and HTTPS runtime requests.
- `TS_ACCEPT_DNS=true` so the proxy can resolve MagicDNS names.
- `TS_AUTH_ONCE=true` and the persistent `tailscale-state` volume so restarts retain the node identity.
- `TS_ENABLE_HEALTH_CHECK=true`; the API waits for `/healthz` before starting.

Never commit `TS_AUTHKEY`. Protect the `tailscale-state` volume because it contains the Gateway node identity. Do not run `docker compose down -v` in production unless intentionally removing that identity.

## Device tags

Assign `tag:llm-gateway` to every Gateway instance and `tag:gpu-node` to every approved inference host. Tag ownership should remain with tailnet administrators.

Example tailnet policy fragment:

```json
{
  "tagOwners": {
    "tag:llm-gateway": ["autogroup:admin"],
    "tag:gpu-node": ["autogroup:admin"]
  },
  "grants": [
    {
      "src": ["tag:llm-gateway"],
      "dst": ["tag:gpu-node"],
      "ip": ["tcp:1234"]
    }
  ]
}
```

Tailscale Grants are additive and deny access that is not granted by any policy. Review the complete tailnet policy for older broad rules such as `src: ["*"]`; a narrower rule does not override a broader grant.

## Endpoint address

Enable MagicDNS and register the stable device name when possible:

```text
http://gpu-node-01:1234
```

The node's `100.x.y.z` Tailscale address is also valid but is less readable in operations. AIConnect strips trailing slashes from configured Base URLs.

LM Studio must listen on an interface reachable through Tailscale, use API-token authentication, and have its operating-system firewall allow the Tailscale interface or address range on the configured port.

## Verification from the Gateway host

```powershell
tailscale status
tailscale ping gpu-node-01
Test-NetConnection gpu-node-01 -Port 1234
```

Then verify authentication without writing the token into shell history. The preferred check is AIConnect's Endpoint `Probe` action, which uses the encrypted token already stored by the Gateway.

Successful probing should return models from LM Studio's native `GET /api/v1/models` endpoint. A current LM Studio server reports model metadata and loaded instances; older servers returning `404` are retried through `GET /v1/models` with reduced metadata.

## Security checklist

- Only Gateway devices can initiate TCP `1234` connections to GPU nodes.
- GPU nodes do not receive access to MariaDB, Grafana, or administrator APIs.
- API consumers are not invited into the tailnet.
- Every LM Studio node uses a different API token.
- Tokens are entered through the administrator UI and never committed to `.env` or documentation.
- Remove or rotate a node token when a host is reassigned.
- Verify that no public NAT/port-forward rule exposes LM Studio.

References:

- [Tailscale Grants syntax](https://tailscale.com/docs/reference/syntax/grants)
- [Tailscale MagicDNS](https://tailscale.com/docs/features/magicdns)
- [Tailscale userspace networking](https://tailscale.com/docs/concepts/userspace-networking)
- [Tailscale Docker parameters](https://tailscale.com/docs/features/containers/docker/docker-params)
