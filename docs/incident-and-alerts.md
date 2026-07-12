# Incident and Alert Operations

Runtime health transitions are managed per endpoint. A failed probe first changes a healthy endpoint to `SUSPECT`; after the configured consecutive-failure threshold it becomes `UNHEALTHY`, opens one `OPEN` incident, and sends one alert through every enabled organization channel. A successful probe enters `RECOVERING`, performs a one-token warm-up, returns the endpoint to `HEALTHY`, closes the incident as `RECOVERED`, and sends recovery alerts.

## Configure channels

```http
POST /api/admin/organizations/{organizationId}/notification-channels
Authorization: Bearer <access-token>
Content-Type: application/json
```

Discord uses the webhook URL as `target` and does not need `secret`:

```json
{
  "type": "DISCORD_WEBHOOK",
  "target": "https://discord.com/api/webhooks/...",
  "secret": null
}
```

Telegram uses the chat ID as `target` and the bot token as `secret`:

```json
{
  "type": "TELEGRAM_BOT",
  "target": "-1001234567890",
  "secret": "123456:bot-token"
}
```

Targets and secrets are AES-GCM encrypted before persistence and are never returned by list APIs.

Pause or resume a channel without deleting its encrypted configuration:

```http
PATCH /api/admin/organizations/{organizationId}/notification-channels/{channelId}
Content-Type: application/json

{"enabled": false}
```

Disabled channels receive neither incident nor recovery messages and do not create delivery-attempt rows. The update verifies that the channel belongs to the organization in the URL and records an audit event.

## Inspect incidents and deliveries

```http
GET /api/admin/organizations/{organizationId}/incidents
GET /api/admin/organizations/{organizationId}/incidents?status=OPEN
GET /api/admin/organizations/{organizationId}/incidents?status=RECOVERED
```

Each incident includes its runtime endpoint, reason, open/recovery timestamps, and every notification attempt. Delivery status is `SENT` or `FAILED`; a failed channel does not stop the remaining channels. `errorMessage` contains the operational failure without exposing the encrypted credential.

The Vue Incident & Alerts panel presents the same organization-scoped data and supports channel registration.

## Health-check configuration

Compose passes the following optional settings to the API container:

```text
HEALTH_CHECK_DELAY_MS=30000
HEALTH_CHECK_INITIAL_DELAY_MS=30000
RUNTIME_CONNECT_TIMEOUT_MS=3000
RUNTIME_RESPONSE_TIMEOUT_MS=360000
```

The first health check waits `HEALTH_CHECK_INITIAL_DELAY_MS` (30 seconds by default), then repeats at `HEALTH_CHECK_DELAY_MS`. This startup grace prevents a Runtime from being marked unhealthy while the Gateway and model server are still becoming ready. Lower values can be supplied for an isolated rehearsal, but overly aggressive production checks may create noise during model loading or temporary network congestion.

## Executed Compose rehearsal

The complete incident lifecycle was exercised against the running production images, real MariaDB, a dedicated LM Studio-compatible container, and `scripts/mock-notification-sink.py` on the internal Compose network.

Procedure and observed evidence:

1. Register a new healthy runtime endpoint and synchronize its loaded model.
2. Register an enabled `DISCORD_WEBHOOK` channel pointing to the internal deterministic sink.
3. Stop the runtime container.
4. Observe three failed probes, endpoint `UNHEALTHY`, Incident `OPEN`, delivery `SENT`, and one payload containing `[CRITICAL]`.
5. Start the same runtime container.
6. Observe successful probe, `RECOVERING`, one-token warm-up, endpoint `HEALTHY`, Incident `RECOVERED`, delivery `SENT`, and a second payload containing `[RECOVERED]`.

Result:

```text
incident open: passed
critical delivery: SENT
recovery warm-up: passed
endpoint after recovery: HEALTHY
incident after recovery: RECOVERED
recovery delivery: SENT
webhook event count: 2
```

After the rehearsal, its channel was disabled, its endpoint placed in `DRAINING`, temporary containers removed, and the normal 30-second interval restored. Incident and delivery records remain in MariaDB as operational evidence.

This proves AIConnect's runtime failure detection, state transitions, persistence, encryption/decryption path, channel dispatch, delivery recording, and recovery notification against the actual Compose stack. A final provider-specific check still requires valid external Discord and Telegram credentials plus outbound network access from the deployment environment.
