[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [ValidateSet('aiconnect')]
    [string]$ConfirmDatabaseName,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

if (-not $Force) {
    throw 'Restore changes database state. Re-run with -Force after verifying the backup and target.'
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$resolvedInput = (Resolve-Path -LiteralPath $InputPath).Path

if ([System.IO.Path]::GetExtension($resolvedInput) -ne '.sql') {
    throw 'Only .sql backups are accepted.'
}

$restoreCommand = 'exec mariadb --default-character-set=utf8mb4 -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"'

Push-Location $repositoryRoot
try {
    Write-Warning "Restoring '$resolvedInput' into database '$ConfirmDatabaseName'. Existing rows can be overwritten."

    Get-Content -LiteralPath $resolvedInput -Encoding utf8 |
        & docker compose exec -T mariadb sh -c $restoreCommand

    if ($LASTEXITCODE -ne 0) {
        throw "MariaDB restore failed with exit code $LASTEXITCODE."
    }

    Write-Output "Restore completed for database '$ConfirmDatabaseName'."
}
finally {
    Pop-Location
}
