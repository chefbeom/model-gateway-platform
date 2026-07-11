# TLS Operations

AIConnect supplies `docker-compose.tls.yml` for TLS termination at Nginx. API consumers and browser administrators should use HTTPS; the refresh cookie is deliberately `Secure` and cannot be restored over plain HTTP.

## Certificate files

Place the certificate chain and unencrypted private key on the Gateway host outside source control. Set their host paths in the uncommitted `.env`:

```text
TLS_CERT_FILE=./secrets/tls/fullchain.pem
TLS_KEY_FILE=./secrets/tls/privkey.pem
```

Restrict private-key permissions to the deployment operator. Do not bake the key into the Nginx image or commit it to the repository.

## Start

TLS without the Tailscale sidecar:

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.tls.yml `
  --env-file .env `
  up -d --build --wait
```

TLS with Tailnet GPU access:

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.tailscale.yml `
  -f docker-compose.tls.yml `
  --env-file .env `
  up -d --build --wait
```

The TLS override:

- publishes TCP `443` while retaining TCP `80` for redirects and health checks;
- replaces the default Nginx virtual host with `infra/nginx/nginx-tls.conf`;
- supports TLS 1.2 and TLS 1.3;
- redirects public HTTP requests to HTTPS with status `308`;
- keeps `/healthz` available on loopback HTTP for the container health check;
- disables proxy buffering and cache for `/v1/*` SSE requests;
- forwards `X-Forwarded-Proto: https` to the API and frontend.

## Certificate renewal

Renew certificate files atomically on the host, then validate and reload Nginx:

```powershell
docker compose -f docker-compose.yml -f docker-compose.tls.yml exec nginx nginx -t
docker compose -f docker-compose.yml -f docker-compose.tls.yml exec nginx nginx -s reload
```

If the certificate path itself changes, update `.env` and recreate Nginx with `docker compose up -d --wait nginx` using the same Compose file set.

## Executed validation

The TLS topology was exercised with a one-day localhost self-signed certificate and the production images:

```text
HTTP / redirect: 308 to https://localhost/
HTTPS frontend: 200
administrator login: 200
refresh cookie: Secure, HttpOnly, SameSite=Strict
refresh rotation: 200 and token changed
old refresh token reuse: 401
logout: 204
refresh after logout: 401
HTTPS SSE: 200, logical model preserved, [DONE] observed
physical model ID in SSE: absent
```

The self-signed certificate and temporary cookie files were test-only artifacts and are removed after the rehearsal. Production acceptance still requires a trusted certificate for the final hostname and browser verification without certificate bypass.
