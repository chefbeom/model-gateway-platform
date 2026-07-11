# Authentication and Operations

## Bootstrap and organization access

1. Once per empty installation, call `POST /api/auth/bootstrap` with an email and a password of at least 12 characters. It creates the first platform administrator and returns an access token while setting the HttpOnly refresh cookie.
2. Use `Authorization: Bearer <access-token>` for administrator requests. The short-lived access token is kept in the current tab's `sessionStorage`; the refresh cookie is `HttpOnly`, `Secure`, and `SameSite=Strict`.
3. A platform administrator can create users at `POST /api/admin/users`, then grant organization roles with `PUT /api/admin/organizations/{organizationId}/members/{userId}`.

Roles:

- `platformAdmin`: can manage all organizations, users, and platform-wide settings.
- `ORGANIZATION_ADMIN`: can manage only nodes, runtimes, deployments, services, keys, quotas, members, and notification channels inside their organization.
- `DEVELOPER`: uses project API keys and the `/api/me/*` usage endpoints; it has no control-plane write access.

`X-Admin-Token` remains supported as a break-glass platform credential. Store it only in a secret manager; normal browser use should use the session flow.

The console keeps a manually entered break-glass credential only in `sessionStorage`; closing the tab or selecting **Disconnect** removes it. It is never written to `localStorage`. The credential must still be rotated and sourced from an operator secret manager.

## Session restoration and rotation

The console reloads runtime inventory when a tab is refreshed. When an access token expires, administrator API calls perform one refresh-cookie exchange and retry once with the replacement access token. Concurrent 401 responses share one browser refresh request, preventing several components from rotating the same cookie simultaneously.

The server also enforces rotation:

- `POST /api/auth/refresh` locks the matching refresh-token row before checking and revoking it.
- A successful exchange revokes the presented token and issues a new access/refresh pair.
- Reuse of the previous token returns `401`.
- `POST /api/auth/logout` revokes the current token and expires the browser cookie.
- Only an HMAC-SHA256 digest is stored in MariaDB; the raw refresh token exists only in the HttpOnly cookie.

`RefreshTokenRotationIntegrationTest` covers cookie flags, rotation, old-token rejection, logout, and replacement-token rejection after logout. The production Compose stack was also exercised against MariaDB: refresh returned `200`, the token changed, and reuse of the original returned `401`.

Terminate TLS in front of Nginx before production traffic. Because the refresh cookie is deliberately `Secure`, a plain-HTTP browser cannot restore or rotate it. Local HTTP smoke tests may authenticate again, but this flag must not be weakened for production convenience.

## Notification channels

Create a notification channel within an organization:

```http
POST /api/admin/organizations/{organizationId}/notification-channels
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "type": "DISCORD_WEBHOOK",
  "target": "https://discord.com/api/webhooks/..."
}
```

For Telegram, set `type` to `TELEGRAM_BOT`, use the chat ID as `target`, and supply the bot token as `secret`.

The target and secret are AES-GCM encrypted using `GATEWAY_ENCRYPTION_KEY`. Delivery attempts are stored in `notification_delivery`; opening and recovery alerts are generated only on incident state transitions, preventing repeated alerts on each health check.

## Required deployment secrets

Set these values in `.env`; Compose refuses to start without them:

```text
ADMIN_API_TOKEN
API_KEY_PEPPER
GATEWAY_ENCRYPTION_KEY
AUTH_SIGNING_KEY
AUTH_REFRESH_PEPPER
```
