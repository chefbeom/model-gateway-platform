# Runtime Validation Evidence

Validation date: 2026-07-11 (Asia/Seoul)

This document records executable evidence gathered from the local Docker Desktop environment. It distinguishes completed runtime checks from checks that still require an operator-provided Tailnet LM Studio node or external notification/TLS credentials.

## Environment

- Docker Server: 29.5.3
- MariaDB container: 11.4.12
- Flyway: 11.20.1
- Tailscale host client: 1.98.8
- Tailscale Docker image: `tailscale/tailscale:v1.98.8`
- Spring Boot: 3.5.11 on Java 17

## Compose startup

Both the base Compose file and the Tailscale override merge passed `docker compose config -q`. The production images were built from clean contexts; the frontend context fell from 64.51 MB to about 43 KB after adding `frontend/.dockerignore`.

`docker compose up -d --build --wait` reported all six base services healthy:

```text
api          healthy
frontend     healthy
grafana      healthy
mariadb      healthy
nginx        healthy
prometheus   healthy
```

The API readiness endpoint is reachable only inside the Compose network. Public `/actuator/health` returned 404 through Nginx, and an unauthenticated administrator request returned 401. Prometheus reported the `api:8080/actuator/prometheus` target as `up`.

Flyway connected to the real MariaDB container, validated V1 through V9, and reported the schema up to date. After upgrading to Flyway 11.20.1, startup emitted neither the MariaDB support warning nor Spring Security's generated-password warning.

## Gateway data-path smoke

The deterministic `scripts/mock-lm-studio.py` server ran on the internal Docker network. `scripts/verify-compose-smoke.ps1` completed the complete control/data-plane path:

```text
status: passed
native loaded model: mock/gemma:loaded
logical service: smoke-chat-<timestamp>
non-streaming HTTP status: 200
SSE terminator: [DONE]
request count: 2
input tokens: 8
output tokens: 4
estimated cost: 0.016000
```

The checks proved:

- bootstrap authentication and administrator access;
- organization, project, node, endpoint, logical service, target, entitlement, and one-time API-key creation;
- LM Studio native `/api/v1/models` discovery and loaded-instance synchronization;
- replacement of the external logical model with the physical provider model on the upstream request;
- replacement of the physical model with the logical service in non-streaming and SSE responses;
- no physical model ID in downstream SSE chunks;
- token, cost, actual deployment, request history, and administrator attempt persistence.

After rebuilding the production images and restoring the database, non-streaming and SSE requests were executed again and returned HTTP 200. Runtime Base URL normalization was also verified against the real API: a trailing-slash duplicate returned 409 and a `file://` URL returned 400.

## Backup and restore rehearsal

The live MariaDB baseline contained one organization, two LLM requests, and nine Flyway history rows. A dump was produced at:

```text
backups/aiconnect-20260711T010905Z.sql
bytes: 38500
sha256: 9278b91b3b4df64b10222a69d6c20f82d6ad73b03515e0c3ac925824e5b4c376
```

A sentinel organization was added after the dump. The API was stopped, the dump restored with the guarded restore script, and the API restarted. Post-restore evidence was:

```text
api health: UP
organization/request/Flyway/sentinel counts: 1,2,9,0
administrator login after restore: passed
```

## Tailnet findings

The Windows host is logged into a Tailnet with MagicDNS enabled. It reached the online Linux peer with `tailscale ping` in 4 ms, while the Compose bridge container could not reach the same `100.x` peer. This proves that relying only on a Docker host's Tailscale client is insufficient in this environment.

The repository now supplies `docker-compose.tailscale.yml`, using the official Tailscale v1.98.8 image in userspace mode with an internal outbound HTTP proxy. `RuntimeHttpProxyIntegrationTest` proves both model discovery and SSE reach an otherwise unresolvable Tailnet hostname through that proxy. `NotificationDirectClientWithRuntimeProxyIntegrationTest` proves Discord/Telegram delivery remains on a separate direct client.

The Tailscale override, image, healthcheck binary, and merged Compose contract are verified. A live sidecar login and real LM Studio call remain unexecuted because no `TS_AUTHKEY` was supplied and neither current Tailnet peer exposes TCP 1234.

## Remaining operator-environment checks

- Supply a pre-authorized `tag:llm-gateway` Tailscale auth key and start the Tailscale Compose override.
- Bring a GPU node online with authenticated LM Studio listening on its Tailnet interface.
- Run normal, structured-output, vision, SSE, failover, and recovery scenarios against that real runtime.
- Configure real Discord/Telegram credentials and verify delivery.
- Install production TLS in front of Nginx and repeat the browser refresh-cookie flow over HTTPS.
