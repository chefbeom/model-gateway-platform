# Tailnet Runtime Verification

Use `scripts/verify-tailnet-runtime.ps1` after the Tailscale sidecar and a real LM Studio GPU node are online. The script exercises the same public and administrator APIs used in production; it does not connect to LM Studio directly from the operator shell.

## Prerequisites

- Start AIConnect with `docker-compose.yml` and `docker-compose.tailscale.yml`.
- Confirm the `tailscale` service is healthy.
- Make LM Studio listen on a Tailnet-reachable interface with at least one loaded model.
- Permit only `tag:llm-gateway` to reach the GPU node's LM Studio port.
- Use a platform administrator account. The script can select the first visible organization or accept `-OrganizationId`.

## Run

Read secrets interactively so they are not stored in shell history:

```powershell
$adminPassword = Read-Host 'AIConnect administrator password' -AsSecureString
$runtimeToken = Read-Host 'LM Studio API token' -AsSecureString

.\scripts\verify-tailnet-runtime.ps1 `
  -BaseUrl 'https://api.example.com' `
  -RuntimeBaseUrl 'http://gpu-node-01:1234' `
  -AdminEmail 'platform-admin@example.com' `
  -AdminPassword $adminPassword `
  -RuntimeApiToken $runtimeToken
```

Omit `-RuntimeApiToken` only when the runtime is intentionally unauthenticated in an isolated test environment. Use the MagicDNS device name instead of a `100.x` address when possible.

The script verifies:

- administrator authentication and organization visibility;
- Gateway-to-LM Studio reachability through the runtime-only Tailscale proxy;
- native model discovery and synchronization;
- creation of a logical service, target, project entitlement, and one-time API key;
- `/v1/models` visibility;
- non-streaming and SSE chat completions;
- replacement of the physical provider model ID in downstream responses;
- request and token usage aggregation.

A successful run emits JSON with `status: passed`, the created resource identifiers, request count, token counts, and `streamingDone: true`. It never prints the administrator password, LM Studio token, or issued API-key secret.


## Live physical-runtime evidence (2026-07-11)

A private Tailnet LM Studio peer exposed TCP 1234 and was enrolled through the running AIConnect administrator API.

Verified evidence:

- Tailscale peer reachability and TCP 1234 connectivity passed.
- LM Studio native `/api/v1/models` and OpenAI-compatible `/v1/models` both returned HTTP 200.
- Six models were discovered; `google/gemma-4-e4b` Q4_K_M was loaded.
- AIConnect Endpoint Probe returned reachable and synchronization persisted six deployments with one loaded deployment.
- Logical service `gemma4-e4b-tailnet` was connected to the physical deployment.
- A Gateway-mediated non-streaming completion succeeded and recorded 23 input tokens and 2 output tokens.
- Administrator-verified `STRUCTURED_OUTPUT` capability produced schema-valid `{"status":"ok"}`.
- Physical SSE produced four data chunks and `[DONE]`; the logical service key was present and the physical provider model ID was absent.
- Every temporary acceptance API key was revoked immediately after its verification request.

The runtime was intentionally unauthenticated during this isolated Tailnet test. Enable an LM Studio API token and prefer its MagicDNS name before production use.

## Scope and cleanup

This is a new-enrollment acceptance test. It creates a project, node, endpoint, deployment, logical service, target, entitlement, and API key. Run it once per new runtime and retain the resulting identifiers as evidence. If the Base URL is already registered, the platform correctly returns `409`; use the existing Endpoint `Probe` and `Sync models` actions for recurring health checks.

The current control-plane API intentionally has no bulk destructive cleanup endpoint. Revoke the temporary API key and disable or remove acceptance-test resources through the administrator workflow according to the organization's retention policy.

For network setup and Grants, see [Tailscale Network Runbook](tailscale-network.md). For completed local evidence, see [Runtime Validation Evidence](runtime-validation.md).
