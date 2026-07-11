# LM Studio Model Discovery

AIConnect probes `GET /api/v1/models`, the LM Studio native v1 model-management endpoint. If a server returns `404`, it falls back to the OpenAI-compatible `GET /v1/models` shape.

For every native v1 model, the synchronizer records:

- model key and loaded instance ID;
- display name and architecture;
- quantization;
- active context length;
- configured parallel inference count;
- loaded or unloaded state;
- chat, streaming, embedding, vision, tool-use, and reasoning capabilities reported by LM Studio;
- the original model metadata JSON for future fields.

The model key becomes the default `compatibilityKey`, while the loaded instance ID becomes `providerModelId`. This lets `STRICT` failover recognize the same downloaded model on different runtime endpoints even when instance IDs differ.

Models without loaded instances remain visible but cannot receive traffic. A deployment that disappears from a later model list is marked unavailable rather than deleted, preserving Service Target and audit history.

LM Studio does not report whether an individual model is reliable for every JSON schema. After validating a model, an administrator can add an explicit capability override:

```http
PATCH /api/admin/model-deployments/{deploymentId}
Authorization: Bearer <organization-admin-token>
Content-Type: application/json

{
  "compatibilityKey": "validated-prompt-builder-v1",
  "maxConcurrency": 2,
  "capabilityOverridesJson": "[\"STRUCTURED_OUTPUT\"]",
  "enabled": true
}
```

Automatic synchronization updates discovered metadata but does not overwrite the administrator capability override or compatibility key.

References:

- [LM Studio native v1 model list](https://lmstudio.ai/docs/developer/rest/list)
- [LM Studio structured output](https://lmstudio.ai/docs/developer/openai-compat/structured-output)
