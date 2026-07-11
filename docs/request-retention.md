# Request Content Retention

AIConnect always records request metadata needed for billing and operations: request ID, project, logical service, deployment, token counts, latency, status, and failover attempts. It does not retain prompts or model responses by default.

An organization administrator may enable encrypted content storage for a specific project:

```http
PUT /api/admin/projects/{projectId}/content-policy
Authorization: Bearer <organization-admin-token>
Content-Type: application/json

{ "mode": "FULL_ENCRYPTED" }
```

`FULL_ENCRYPTED` encrypts non-streaming request and response JSON using the deployment `GATEWAY_ENCRYPTION_KEY`. Streaming requests retain the request JSON only; buffering the full SSE response would defeat the low-latency streaming contract.

Read an explicitly retained request with:

```http
GET /api/admin/projects/{projectId}/requests/{requestId}/content
Authorization: Bearer <organization-admin-token>
```

Only use full retention when the API consumer has been informed and data-protection obligations are met. Return the policy to `METADATA_ONLY` for the default privacy-preserving behavior.
