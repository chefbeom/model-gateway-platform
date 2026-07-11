# Failover Operations

## Routing order

1. Load enabled targets in ascending numerical priority.
2. Apply the logical service's model compatibility policy.
3. Remove disabled, unhealthy, suspect, draining, unloaded, capability-incompatible, and concurrency-saturated deployments.
4. Prefer the lowest remaining priority.
5. Within a priority, prefer the lowest normalized active-request load.
6. When load is equal, distribute selections according to target weight.

GPU vendor or product name is never part of candidate selection.

## Model compatibility policy

Every deployment has a `compatibilityKey`. Automatic LM Studio discovery uses the model key. An administrator may assign the same key when separately hosted deployments are validated as equivalent.

| Policy | Eligible fallback models |
|---|---|
| `STRICT` | Only deployments sharing the primary configured target's compatibility key |
| `COMPATIBLE` | Any non-degraded target explicitly attached to the logical service and satisfying required capabilities |
| `DEGRADED` | Compatible targets plus targets marked as degraded |

`allowDegraded=true` also permits degraded targets under `STRICT` or `COMPATIBLE`; `STRICT` still requires the same compatibility key.

## Request replay policy

Model compatibility and request replay are separate decisions.

| Policy | Automatic retry |
|---|---|
| `SAFE` | Failures known to occur before a network connection, such as connection refusal, DNS failure, or connect timeout |
| `AGGRESSIVE` | SAFE failures plus ambiguous transport failures and HTTP 408, 429, or 5xx responses |

`SAFE` is the default because a timeout or HTTP response can mean the runtime already started inference. Replaying such a request can consume tokens twice. Choose `AGGRESSIVE` only where availability is more important than possible duplicate inference.

## Health threshold and incidents

Endpoint state changes use a consecutive-failure threshold of three:

```text
HEALTHY -> SUSPECT (failure 1) -> SUSPECT (failure 2) -> UNHEALTHY (failure 3)
```

`SUSPECT` endpoints are excluded from new routing immediately but do not open an Incident yet. A successful probe before the threshold restores `HEALTHY`. Reaching `UNHEALTHY` opens one Incident and sends configured notifications without duplicating the incident on every probe.

A later successful probe moves an unhealthy endpoint to `RECOVERING`. It becomes `HEALTHY` only after the one-token warm-up succeeds. Warm-up failure returns it directly to `UNHEALTHY`, preserving the open Incident.

## Streaming boundary

The Gateway reads and retains the first upstream response byte before committing the successful SSE response to the API client. If the runtime returns successful headers and closes before that byte, the attempt is recorded and `AGGRESSIVE` policy may try the next eligible deployment.

After any response bytes have been delivered to the client, the request is never replayed. A later disconnect is recorded as `STREAM_INTERRUPTED`; the next request uses the updated endpoint health state.

## Planned maintenance

Drain an endpoint before stopping LM Studio or servicing its host:

```http
POST /api/admin/runtime-endpoints/{endpointId}/drain
Authorization: Bearer <organization-admin-token>
```

`DRAINING` immediately removes the endpoint from new routing decisions. Existing requests keep their acquired concurrency slot and may finish normally.

After maintenance, request recovery:

```http
POST /api/admin/runtime-endpoints/{endpointId}/resume
Authorization: Bearer <organization-admin-token>
```

The endpoint enters `RECOVERING`. A model-list probe must succeed, followed by a one-token warm-up inference on a loaded deployment. Only then does the endpoint become `HEALTHY` and receive new traffic.
