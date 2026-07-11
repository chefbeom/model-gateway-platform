# Routing Policy Management

Routing policy changes are independent from project API keys and logical model names. A client can continue using the same key and `model` value while administrators update eligible deployments.

## List logical services

```http
GET /api/admin/organizations/{organizationId}/services
Authorization: Bearer <organization-admin-token>
```

## Change service policy

```http
PATCH /api/admin/services/{serviceId}
Authorization: Bearer <organization-admin-token>
Content-Type: application/json

{
  "failoverPolicy": "COMPATIBLE",
  "retryPolicy": "SAFE",
  "allowDegraded": false,
  "requiredCapabilitiesJson": "[\"STRUCTURED_OUTPUT\"]",
  "inputPricePerMillion": 500,
  "outputPricePerMillion": 1000,
  "enabled": true
}
```

Omitted properties are unchanged. `serviceKey` is deliberately immutable so policy changes cannot silently break client code.

## Reorder or disable a target

```http
PATCH /api/admin/services/{serviceId}/targets/{targetId}
Authorization: Bearer <organization-admin-token>
Content-Type: application/json

{
  "priority": 2,
  "weight": 100,
  "degraded": false,
  "enabled": true,
  "maxConcurrencyOverride": 1
}
```

Targets can be listed with `GET /api/admin/services/{serviceId}/targets` and removed with `DELETE` on the target URL. Disabling is preferable when historical configuration or a quick rollback is useful.
