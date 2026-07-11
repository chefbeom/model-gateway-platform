# Implementation Completion Audit

This audit maps the original platform requirements to current repository evidence. "Verified" means executable local evidence exists. It does not claim access to the operator's external Tailnet, physical LM Studio hosts, notification-provider accounts, or trusted production certificate.

| Requirement | Current evidence | Verification |
|---|---|---|
| Hardware-independent registration | `InferenceNode`, `RuntimeEndpoint`, dynamic `ModelDeployment`, free-form `AcceleratorDevice`; no GPU enum; Infrastructure UI registers arbitrary accelerator metadata | `AcceleratorInventoryIntegrationTest` registers `FutureGPU X1000`; live accelerator inventory read passed |
| Tailscale data path | Docker userspace sidecar exposes an internal runtime-only HTTP proxy; host routes are not assumed; MagicDNS and least-privilege Grants documented | `RuntimeHttpProxyIntegrationTest`, `NotificationDirectClientWithRuntimeProxyIntegrationTest`; merged Tailscale Compose contract; live sidecar requires operator auth key |
| Native LM Studio discovery | Native `/api/v1/models`, `/v1/models` fallback, loaded instances, quantization, context, parallelism, capabilities and metadata | `LmStudioModelDiscoveryTest`, `ModelSynchronizationIntegrationTest`; physical Tailnet LM Studio returned six models with one loaded |
| Administrator-verified model capabilities | Persistent capability overrides and compatibility keys survive model sync | `DeploymentCapabilityOverrideIntegrationTest` |
| Gateway-mediated AI data path | OpenAI-compatible Gateway rewrites logical/physical model IDs and calls LM Studio only from the server | Physical Tailnet Gemma 4 E4B passed non-streaming, schema-constrained output and SSE with provider-ID privacy; Compose/MariaDB smoke also passed; `tailnet-runtime-verification.md` |
| Project API keys | One-time raw issuance, HMAC-only persistence, revoke and expiry | `ApiKeyServiceTest` |
| Browser administrator credentials | Short-lived access token, Secure/HttpOnly/SameSite refresh cookie, reload restoration, single-flight 401 refresh, server-side locked rotation and reuse rejection | Actual HTTPS/MariaDB login, rotation, old-token 401, logout and post-logout 401; `RefreshTokenRotationIntegrationTest`; Vue production build |
| Multi-tenant authorization | Platform admin, organization admin, developer roles; membership-scoped organization discovery and resource filters | `OrganizationAuthorizationIntegrationTest`, `OrganizationDiscoveryIntegrationTest`, `ScopedRuntimeInventoryIntegrationTest` |
| Logical services and routing | Prioritized/weighted targets, capability and vision filters, degraded targets, concurrency limits | `RoutingFailoverPolicyTest`, `VisionRoutingIntegrationTest`, `ActiveRequestRegistryTest` |
| Mutable routing without key changes | Service policy and targets can be listed, patched, disabled, reprioritized or removed while `serviceKey` remains immutable | `RoutingPolicyManagementIntegrationTest`; Vue Routing Policy panel |
| STRICT/COMPATIBLE/DEGRADED | `STRICT` uses the primary compatibility group; compatible and degraded policies broaden only as configured | `RoutingFailoverPolicyTest` |
| SAFE/AGGRESSIVE replay | SAFE retries connection refusal and explicit connect timeout, not HTTP rejection or read timeout; AGGRESSIVE permits configured ambiguous failures | Actual Compose phases: Primary timeout → Secondary success with attempts 2; `GatewaySafeConnectionFailoverIntegrationTest`, `FailoverRetryDeciderTest`, `GatewaySafeRetryIntegrationTest` |
| Non-streaming failover | Failed Primary attempt and successful Secondary attempt are persisted under one logical request | Actual Compose Request Explorer: Secondary/SUCCEEDED/failoverCount 1/attempts 2; integration tests |
| Streaming start failover | First upstream byte is prefetched before committing success; empty 2xx stream can fail over under AGGRESSIVE | `StreamingResponsePrefetcherTest`, `GatewayStreamingStartFailureIntegrationTest` |
| Streaming interruption boundary | No replay after response start; read failure records `STREAM_INTERRUPTED` and `responseStarted=true` | `GatewayStreamingInterruptionIntegrationTest` |
| Logical model privacy in SSE | Physical model IDs in chunks are rewritten to the logical service key | Actual HTTP and HTTPS SSE smoke; `GatewayStreamingFailoverIntegrationTest` |
| Health state machine | HEALTHY → SUSPECT → UNHEALTHY after three consecutive failures; RECOVERING requires warm-up | `RuntimeEndpointStateTest`; actual Compose failure/recovery rehearsal; Flyway V9 |
| Planned maintenance | DRAINING rejects new work; resume triggers probe and one-token warm-up | `RuntimeEndpointStateTest`; actual recovery phase; `failover-operations.md` |
| Quotas | Per-key rolling RPM and project monthly token limits use project-scoped DB aggregation; project policy modal reads and updates both limits | `RateWindowTest`, `ProjectTokenQuotaIntegrationTest`, `StreamingQuotaIntegrationTest`; live MariaDB policy round-trip passed |
| Cost calculation | Input/output price snapshot is stored on request creation and cost is calculated from reported usage | Gateway failover usage assertions; actual Compose usage smoke |
| Consumer visibility | Project-scoped date-range/current-month totals and latest requests with logical service, actual deployment and stream mode; Vue usage panel | `UsageHistoryIntegrationTest`; frontend build |
| Administrator visibility | Organization-scoped requests, rates, p95, active work, tokens, cost, failover, endpoints and incidents plus request-attempt exploration | Actual phase verification through Request Explorer; overview/explorer integration tests; Vue panels |
| Health incidents and alerts | Threshold-based open/recovery, encrypted Discord/Telegram configuration, channel enable control, failure isolation, and queryable delivery outcomes | Actual Compose rehearsal produced OPEN/SENT/[CRITICAL], warm-up HEALTHY, RECOVERED/SENT/[RECOVERED]; provider-specific delivery still needs real credentials |
| Prompt privacy | Default metadata-only; explicit `FULL_ENCRYPTED`; streaming response is never buffered for retention; project policy modal explains and controls both modes | `RequestContentRetentionIntegrationTest`; live policy round-trip passed |
| Audit trail | Successful admin mutations, identity, alert, retention, hardware and endpoint actions are audited | Schema and integration test gate |
| Monitoring | Micrometer/Prometheus metrics, internal scrape, Grafana datasource, readiness healthchecks on all six services | Live Prometheus target `up`; `docker compose up --wait` reported all services healthy |
| Operations UI | Login-first session restoration, grouped sidebar navigation, feature search, light/dark themes, organization/project discovery, runtime and accelerator inventory, logical routing, API keys, quota/retention policies, requests, incidents, notifications and usage | Vue TypeScript production build (32 modules); rebuilt frontend image served through Nginx with HTTP 200 |
| Deployment | Base, optional Tailscale, and optional TLS Compose layers; required secrets, health dependencies, timeout settings, certificate mounts and health checks | All two- and three-layer Compose contracts valid; production images built; base six-service stack live and healthy |
| TLS | TLS 1.2/1.3 Nginx termination, HTTP 308 redirect, Secure browser session and unbuffered HTTPS SSE | Actual self-signed rehearsal: frontend/login/refresh/SSE passed; temporary certificate removed; `tls-operations.md` |
| API contract | OpenAPI 3.1 data/control/usage/incident/operations contract including compatibility, replay, discovery, overview and endpoint conflicts | All `OpenApi*ContractTest` classes |
| Backup and restore | Guarded dump/restore scripts, SHA-256 verification and rehearsal procedure | Live MariaDB dump/restore passed: sentinel removed, counts restored, API health and login recovered |
| Operator acceptance tooling | Secure interactive real-runtime and phase-based Failover verification plus deterministic LM Studio/notification sinks | `verify-tailnet-runtime.ps1`, `verify-tailnet-failover.ps1`, local phase rehearsal and documented runbooks |

## Final local gates

```text
clean test bootJar --offline --no-daemon: passed (61 tests, 0 failures)
npm run build: passed (32 modules)
base, Tailscale, TLS, and combined docker compose config: passed
Docker production image build and six-service `up --wait`: passed
PowerShell backup/restore/runtime/failover verification and Python mock syntax: passed
actual MariaDB refresh rotation/reuse rejection: passed
actual Primary → Secondary → Primary phase verification: passed
actual incident open/recovery and Webhook delivery rehearsal: passed
actual HTTPS browser-session and SSE rehearsal: passed
```

Flyway 11.20.1 validated and applied migrations V1 through V9 on a real MariaDB 11.4.12 container. Exact runtime, failover, smoke, health, restore, authentication, alert, and TLS evidence is recorded in the corresponding documents under `docs/`.

## External-state checks still requiring the operator environment

- Enable LM Studio API-token authentication on the enrolled physical runtime and prefer its MagicDNS name over the current test IP.
- Bring a second physical GPU node online and execute same-model Primary → Secondary → Primary failover against physical runtimes.
- Execute the supplied vision-payload acceptance case on a model with a verified vision projector.
- Confirm provider-specific Discord/Telegram delivery using real credentials.
- Install a trusted certificate for the final hostname and verify without certificate bypass.
- Confirm final Tailscale Grants and GPU-host firewall policy.

Docker Desktop and the complete base stack are currently running. A physical Tailnet LM Studio peer was enrolled and passed native discovery, Gateway non-streaming inference, JSON Schema output and SSE logical-model privacy. Remaining checks are production hardening and multi-physical-node acceptance.
