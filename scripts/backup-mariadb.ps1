[CmdletBinding()]
param(
    [string]$OutputDirectory = 'backups'
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $outputDirectoryPath = [System.IO.Path]::GetFullPath($OutputDirectory)
}
else {
    $outputDirectoryPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputDirectory))
}

New-Item -ItemType Directory -Path $outputDirectoryPath -Force | Out-Null

$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$outputPath = Join-Path $outputDirectoryPath "aiconnect-$timestamp.sql"
$temporaryPath = "$outputPath.partial"

$dumpCommand = 'exec mariadb-dump --single-transaction --routines --triggers --events --hex-blob --default-character-set=utf8mb4 -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE"'

Push-Location $repositoryRoot
try {
    & docker compose exec -T mariadb sh -c $dumpCommand |
        Out-File -LiteralPath $temporaryPath -Encoding utf8

    if ($LASTEXITCODE -ne 0) {
        throw "mariadb-dump failed with exit code $LASTEXITCODE."
    }

    if ((Get-Item -LiteralPath $temporaryPath).Length -eq 0) {
        throw 'mariadb-dump produced an empty backup.'
    }

    Move-Item -LiteralPath $temporaryPath -Destination $outputPath
    $hash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()

    [pscustomobject]@{
        BackupPath = $outputPath
        Sha256 = $hash
        CreatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    }
}
finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
    Pop-Location
}
