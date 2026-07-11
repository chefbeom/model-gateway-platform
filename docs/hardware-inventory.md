# Hardware Inventory

Inference routing does not select a server by GPU name. It selects a healthy model deployment attached to a logical service. Accelerator inventory is optional metadata for operators and capacity planning.

Register one device per physical accelerator:

```http
POST /api/admin/nodes/{nodeId}/accelerators
Authorization: Bearer <organization-admin-token>
Content-Type: application/json

{
  "vendor": "NVIDIA",
  "productName": "H100 80GB HBM3",
  "deviceIndex": 0,
  "deviceUuid": "GPU-...",
  "memoryTotalMb": 81920,
  "driverVersion": "...",
  "metadataJson": "{\"optional\":\"custom telemetry\"}"
}
```

All hardware fields except `deviceIndex` are optional free-form metadata. A node with no reported accelerator remains a valid LM Studio runtime endpoint, and a new device name such as `FutureGPU X1000` can be registered without a database or source-code change.
