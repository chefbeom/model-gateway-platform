# MariaDB Backup and Restore

The MariaDB volume is persistent, but a Docker volume is not a backup. Store backups on separate encrypted storage and test restoration regularly.

## Create a backup

Run from the repository root while the `mariadb` Compose service is healthy:

```powershell
.\scripts\backup-mariadb.ps1
```

The script creates `backups/aiconnect-<UTC timestamp>.sql` and prints its SHA-256 digest. It uses the database credentials already present inside the container, enables a consistent transactional dump, and writes binary columns as hexadecimal text.

Copy both the SQL file and recorded digest to storage outside this host. Keep `.env`, API-key peppers, access-token signing keys, refresh-token peppers, and the gateway encryption key in the corresponding secrets backup. A database restore without the original encryption key cannot decrypt saved LM Studio tokens, notification credentials, or retained request content.

## Verify a backup before restore

```powershell
Get-FileHash .\backups\aiconnect-20260711T120000Z.sql -Algorithm SHA256
```

Compare the digest with the value recorded when the dump was created. Restore into an isolated staging stack first whenever possible.

## Restore

Stop the API first so it cannot write while the restore is running:

```powershell
docker compose stop api
.\scripts\restore-mariadb.ps1 `
  -InputPath .\backups\aiconnect-20260711T120000Z.sql `
  -ConfirmDatabaseName aiconnect `
  -Force
docker compose start api
```

`-Force` and the exact database-name confirmation are both required. The script does not drop the database; it applies the SQL dump to the configured `aiconnect` database. For a clean disaster-recovery rehearsal, create a fresh Compose volume before restoring.

## Post-restore checks

1. Confirm the API starts and Flyway validation succeeds.
2. Log in with a test identity and list the expected organizations, endpoints, and logical services.
3. Probe a non-production LM Studio endpoint and issue one non-streaming request.
4. Confirm historical usage totals and request attempts are present.
5. Rotate any credential whose backup confidentiality may have been compromised.

## Suggested operating policy

- Daily backup, with additional backups before migrations or upgrades.
- At least one encrypted off-host copy.
- Retention appropriate to the organization's privacy policy.
- Quarterly restore rehearsal with the result recorded in the audit system or operations log.
- Backup files must never be committed; the repository ignores `backups/`.
