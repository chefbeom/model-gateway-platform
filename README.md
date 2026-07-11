# AIConnect — Hardware-Independent LLM Service Platform

AIConnect provides a single OpenAI-compatible API in front of privately operated LLM runtimes. It authenticates clients, applies organization-scoped permissions and quotas, routes each request to a healthy deployment, records usage and cost, and monitors failures.

```text
API consumer ── HTTPS ──> AIConnect Gateway ── Tailscale ──> LM Studio / GPU node
                                  │
                              MariaDB + Prometheus
```

GPU product names are not part of the routing model. An organization registers an inference node, its runtime endpoint, and discovered model deployments. This works equally for a single consumer GPU, multi-GPU host, or future hardware without source changes.

## Included platform capabilities

- Multi-tenant organizations, projects, API keys, logical services, deployments, and routing targets.
- Bootstrap/login/refresh session flow with signed short-lived access tokens and HttpOnly refresh cookies.
- Platform administrator, organization administrator, and developer roles. Organization administrators are constrained to their own organization.
- OpenAI-compatible `/v1/models` and `/v1/chat/completions`, including non-buffered SSE streaming.
- LM Studio runtime client, encrypted runtime credentials, priority failover, concurrency limits, health probes, incidents, and safe streaming failure behavior.
- API-key RPM limiting, optional monthly token quota, usage history, price snapshots, request attempts, latency, and estimated cost.
- Discord Webhook and Telegram Bot incident open/recovery alerts with encrypted channel secrets and persisted delivery outcomes.
- Audit records for successful control-plane mutations without retaining prompts, responses, API keys, or runtime tokens.
- Vue 3 operations console with organization discovery and 24-hour operations cards, plus Nginx, MariaDB, Prometheus, and Grafana Compose services with readiness health checks.
- Optional Tailscale v1.98.8 userspace sidecar that proxies only LM Studio runtime traffic from the API container.

## Start the stack

1. Copy `.env.example` to the ignored `.env` file and replace every placeholder with a unique secret.
2. Install Tailscale on every GPU host and bind LM Studio to a Tailnet-reachable interface. Permit only `tag:llm-gateway` to connect to `tag:gpu-node` on the configured runtime port.
3. For a local network or mock-runtime deployment, start the base stack:

```powershell
docker compose --env-file .env up -d --build --wait
```

A host Tailscale installation is not automatically inherited by Docker bridge containers, particularly on Docker Desktop. For a containerized Gateway that must reach Tailnet LM Studio nodes, create a pre-authorized auth key for `tag:llm-gateway`, set `TS_AUTHKEY` in `.env`, and use the userspace proxy override:

```powershell
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --env-file .env up -d --build --wait
```

Nginx is exposed on port `80` and Grafana on `3000`. MariaDB, Prometheus, the runtime proxy, and application ports remain on the internal network. Put TLS termination in front of Nginx before production use; the browser refresh cookie is deliberately `Secure`. See [Tailscale network runbook](docs/tailscale-network.md).

## First setup

1. In the console, choose **첫 관리자 생성**, or call `POST /api/auth/bootstrap`. This can only succeed once on an empty installation.
2. Login to receive an access token. Use `Authorization: Bearer <token>` for the control plane.
3. Create an organization, project, node, and `LM_STUDIO` runtime endpoint (using its Tailnet URL and private token).
4. Probe and synchronize models. Create a logical service and attach prioritized deployment targets.
5. Grant project service access, issue a project API key, and call the public `/v1` API.
6. Add an optional Discord or Telegram channel under `/api/admin/organizations/{id}/notification-channels`.

`X-Admin-Token` is retained as a break-glass platform credential, not the normal administrator login flow.

## Consumer request

```http
POST /v1/chat/completions
Authorization: Bearer sk_llmg_...
Content-Type: application/json

{
  "model": "image-prompt-builder",
  "messages": [{"role":"user", "content":"Create a winter castle prompt"}],
  "stream": true
}
```

When a deployment fails before the response starts, the gateway tries the next eligible target. Once stream bytes have been sent, the gateway ends the interrupted stream rather than duplicating or corrupting the completion; subsequent requests route to a healthy target.

## Verify

```powershell
gradle clean test bootJar --offline --no-daemon
npm --prefix frontend run build
docker compose --env-file .env config -q
docker compose -f docker-compose.yml -f docker-compose.tailscale.yml --env-file .env config -q
```

On a fresh local test database, start `scripts/mock-lm-studio.py` in the internal Compose network and run:

```powershell
.\scripts\verify-compose-smoke.ps1
```

The smoke verifier creates organization/project/node/service/API-key resources and proves native model discovery, physical-to-logical model rewriting, non-streaming and SSE responses, token/cost persistence, consumer history, and administrator request visibility. It is intended for an empty disposable smoke database because runtime Base URLs are unique.

The test suite additionally covers signed-token tamper rejection, organization scope enforcement, failover boundaries, quota/concurrency controls, Tailscale HTTP-proxy delegation, notification isolation, request privacy, and OpenAPI contracts. See [runtime validation evidence](docs/runtime-validation.md) and [authentication and operations](docs/auth-and-operations.md).
