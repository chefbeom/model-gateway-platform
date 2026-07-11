# Tailnet Failover Verification

Use `scripts/verify-tailnet-failover.ps1` after two physical LM Studio deployments are connected to one logical service. The script verifies each request through the public Gateway and then confirms the actual deployment and attempts through the administrator Request Explorer.

## Required service configuration

- Primary and Secondary endpoints are `HEALTHY`.
- Both deployments are enabled, loaded, and share the compatibility key required by the service policy.
- Primary target priority is `1`; Secondary target priority is `2`.
- The project is entitled to the logical service.
- The project API key remains unchanged during every phase.
- Use `SAFE` to prove connection-stage replay, or `AGGRESSIVE` when the organization explicitly accepts ambiguous retry risk.

Collect these identifiers from the administrator console:

```text
Organization ID
Logical service key
Primary deployment ID
Secondary deployment ID
Primary endpoint ID
```

Read credentials interactively:

```powershell
$adminPassword = Read-Host 'AIConnect administrator password' -AsSecureString
$projectKey = Read-Host 'Project API key' -AsSecureString
```

## Phase 1: baseline

With both runtimes online:

```powershell
.\scripts\verify-tailnet-failover.ps1 `
  -Phase BASELINE `
  -BaseUrl 'https://api.example.com' `
  -OrganizationId '<organization-id>' `
  -LogicalService 'text-pro' `
  -PrimaryDeploymentId '<primary-deployment-id>' `
  -SecondaryDeploymentId '<secondary-deployment-id>' `
  -PrimaryEndpointId '<primary-endpoint-id>' `
  -AdminEmail 'platform-admin@example.com' `
  -AdminPassword $adminPassword `
  -ProjectApiKey $projectKey
```

Expected: HTTP 200, logical model preserved, final deployment is Primary, one successful attempt, and failover count zero.

## Phase 2: failover

Stop LM Studio on the Primary host without changing AIConnect configuration or the project API key. Immediately run:

```powershell
.\scripts\verify-tailnet-failover.ps1 `
  -Phase FAILOVER `
  -BaseUrl 'https://api.example.com' `
  -OrganizationId '<organization-id>' `
  -LogicalService 'text-pro' `
  -PrimaryDeploymentId '<primary-deployment-id>' `
  -SecondaryDeploymentId '<secondary-deployment-id>' `
  -PrimaryEndpointId '<primary-endpoint-id>' `
  -AdminEmail 'platform-admin@example.com' `
  -AdminPassword $adminPassword `
  -ProjectApiKey $projectKey `
  -RequireSameRequestFailover
```

Expected: HTTP 200, failed Primary attempt, successful Secondary attempt, final deployment Secondary, and failover count at least one. Add `-RequireIncident` when the health interval and failure threshold allow waiting for the `OPEN` incident in the same check.

If health probes already excluded Primary before the request, omit `-RequireSameRequestFailover`. The script still proves that the same logical model and API key route to Secondary without client configuration changes.

## Phase 3: recovery

Start LM Studio on Primary. Use the Endpoint `Resume` action, then wait for probe and one-token warm-up to return the endpoint to `HEALTHY`. Run:

```powershell
.\scripts\verify-tailnet-failover.ps1 `
  -Phase RECOVERY `
  -BaseUrl 'https://api.example.com' `
  -OrganizationId '<organization-id>' `
  -LogicalService 'text-pro' `
  -PrimaryDeploymentId '<primary-deployment-id>' `
  -SecondaryDeploymentId '<secondary-deployment-id>' `
  -PrimaryEndpointId '<primary-endpoint-id>' `
  -AdminEmail 'platform-admin@example.com' `
  -AdminPassword $adminPassword `
  -ProjectApiKey $projectKey
```

Expected: Primary endpoint `HEALTHY`, final deployment Primary, one successful attempt, and no API-key change.

## Executed local evidence

The phase verifier was exercised against two Docker LM Studio-compatible runtimes and the production Compose/MariaDB stack:

```text
BASELINE:  Primary, SUCCEEDED, failoverCount 0, attempts 1
FAILOVER:  Secondary, SUCCEEDED, failoverCount 1, attempts 2
RECOVERY:  Primary, SUCCEEDED, failoverCount 0, attempts 1
```

This rehearsal exposed and fixed a real transport-classification gap: Docker connection timeout arrived as `SocketTimeoutException("Connect timed out")`, while SAFE originally recognized only JDK `HttpConnectTimeoutException`. AIConnect now treats only explicit connect-timeout messages as safe; `SocketTimeoutException("Read timed out")` remains ambiguous and is not replayed under SAFE.

`GatewaySafeConnectionFailoverIntegrationTest` protects this behavior, while `FailoverRetryDeciderTest` distinguishes connection timeout from read timeout.
