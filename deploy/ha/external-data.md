# HA with external MariaDB and Redis

This overlay keeps the two-Gateway HA topology and moves only the stateful
services to servers outside the Compose host. The normal `docker-compose.yml`
is intentionally unchanged; omit the overlay to use the bundled MariaDB and
Redis again.

## 1. Fill the external connection values

Keep the existing HA secrets in `deploy/ha/.env`. Create the separate override
file and replace the `CHANGE_ME` values:

```powershell
Copy-Item deploy\ha\.env.external-data.example deploy\ha\.env.external-data
notepad deploy\ha\.env.external-data
```

Enter the external connection details individually:

```env
DB_HOST=외부_MariaDB_호스트
DB_PORT=3306
DB_NAME=사용할_데이터베이스_이름
DB_USERNAME=사용할_MariaDB_사용자
DB_PASSWORD=MariaDB_사용자_비밀번호
MARIADB_ROOT_PASSWORD=별도로_생성한_긴_임의값
REDIS_HOST=외부_Redis_호스트
REDIS_PORT=6379
REDIS_PASSWORD=Redis_비밀번호
```

The Compose overlay builds the JDBC URL from `DB_HOST`, `DB_PORT`, and `DB_NAME`.
`DB_USERNAME` and both external passwords are passed to the Gateway containers. `MARIADB_ROOT_PASSWORD` is only required because the base Compose file still parses the bundled MariaDB definition; it is not used by the external mode. Generate a long random value for it and never reuse an application secret. Use the
external servers' private VCN/LAN or Tailscale addresses as seen from the
Gateway host. Do not enter `localhost`, `127.0.0.1`, or the Gateway's own
`10.0.0.214`/`100.100.9.74` address unless that machine actually hosts the
corresponding external service.

The external MariaDB must already contain the database and user named above.
The external Redis must listen on `REDIS_PORT` and accept `REDIS_PASSWORD`.
Restrict both firewalls to the Gateway source address.

## 2. Validate

Compose v2.24 or newer is required for the `!reset` merge tag. The current
Docker Compose v5 supports it.

```powershell
docker compose -p aiconnect-ha `
  --env-file deploy\ha\.env `
  --env-file deploy\ha\.env.external-data `
  -f deploy\ha\docker-compose.yml `
  -f deploy\ha\docker-compose.external-monitoring.yml `
  -f deploy\ha\docker-compose.external-data.yml `
  config -q
```

This command fails fast when a required value is missing. Do not continue if
`CHANGE_ME` remains in the override file.

## 3. Switch the HA stack

Stop only the bundled stateful containers, keep their volumes, and then start
the stack with the external-data overlay:

```powershell
docker compose -p aiconnect-ha --env-file deploy\ha\.env `
  -f deploy\ha\docker-compose.yml stop mariadb redis

docker compose -p aiconnect-ha `
  --env-file deploy\ha\.env `
  --env-file deploy\ha\.env.external-data `
  -f deploy\ha\docker-compose.yml `
  -f deploy\ha\docker-compose.external-monitoring.yml `
  -f deploy\ha\docker-compose.external-data.yml `
  up -d --build --wait
```

Check both Gateway instances:

```powershell
docker compose -p aiconnect-ha `
  --env-file deploy\ha\.env `
  --env-file deploy\ha\.env.external-data `
  -f deploy\ha\docker-compose.yml `
  -f deploy\ha\docker-compose.external-monitoring.yml `
  -f deploy\ha\docker-compose.external-data.yml `
  ps

Invoke-RestMethod http://127.0.0.1:18081/actuator/health/readiness
Invoke-RestMethod http://127.0.0.1:18082/actuator/health/readiness
```

If the new database is empty, Flyway creates the schema on startup. Since the
data is intentionally not being migrated, create the administrator,
organization, projects, and API keys again. Keep the old Docker volumes until
the new stack has passed an inference and login test; never run `down -v` as
part of the first switch.

## Rollback

Stop the overlay stack and bring the base HA file back up without the external
override. The original local volumes are still present:

```powershell
docker compose -p aiconnect-ha `
  --env-file deploy\ha\.env `
  -f deploy\ha\docker-compose.yml `
  -f deploy\ha\docker-compose.external-monitoring.yml `
  -f deploy\ha\docker-compose.external-data.yml down

docker compose -p aiconnect-ha --env-file deploy\ha\.env `
  -f deploy\ha\docker-compose.yml `
  -f deploy\ha\docker-compose.external-monitoring.yml `
  up -d --build --wait
```
