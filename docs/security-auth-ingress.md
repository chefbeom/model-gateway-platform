# HA authentication ingress limits

The HA Nginx configuration limits `/api/auth/login` and `/api/auth/bootstrap` to five requests per minute per observed source address, with an immediately accepted burst of five. Excess requests receive HTTP 429. Chat, SSE and other routes are unaffected. This is an edge control, not an account-level lockout or distributed limiter.

When a proxy such as Tailscale Serve connects over loopback, Nginx may see all clients as the same source and share their budget. Do not trust forwarded IP headers without configuring and restricting trusted proxies. API ports reachable outside this proxy also bypass this limit. Standalone deployments do not use this HA Nginx configuration and are not covered. Apply equivalent controls at any other ingress and keep the API private.

## Session upgrade

New access tokens are bound to the server-side refresh session. Revoking or rotating that session invalidates its previous access token; disabled accounts are rejected and removed platform privileges are no longer accepted. Access tokens issued by earlier versions have no session binding and are rejected by the new backend. A valid refresh cookie can obtain a new token; otherwise the user signs in again. During an HA upgrade, old backends may still issue unbound tokens, so avoid prolonged mixed-version operation and verify sign-in and refresh after both backends are updated. Gateway API keys are unaffected by the browser token format change.

This change does not enable HTTPS or turn on production data-protection policies. Verify the HTTPS origin and existing clients before changing ingress or enabling secure cookies; see [Private-only deployment](private-only-deployment.md).
