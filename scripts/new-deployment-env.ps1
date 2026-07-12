[CmdletBinding()]
param(
    [string]$OutputPath = '.env',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

if ((Test-Path -LiteralPath $OutputPath) -and -not $Force) {
    throw "'$OutputPath' 파일이 이미 있습니다. 기존 비밀값을 잃으면 저장된 API 키와 암호화 데이터가 무효화될 수 있습니다. 새 설치에서만 -Force를 사용하세요."
}

function New-Secret([int]$Bytes = 32) {
    $buffer = New-Object byte[] $Bytes
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) }
    finally { $rng.Dispose() }
    [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$content = @"
# 이 파일은 Git에 커밋하지 마세요. DB 백업과 별도로 암호화해 보관하세요.
DB_PASSWORD=$(New-Secret)
MARIADB_ROOT_PASSWORD=$(New-Secret)
ADMIN_API_TOKEN=$(New-Secret)
API_KEY_PEPPER=$(New-Secret)
GATEWAY_ENCRYPTION_KEY=$(New-Secret)
AUTH_SIGNING_KEY=$(New-Secret)
AUTH_REFRESH_PEPPER=$(New-Secret)
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=$(New-Secret)

HEALTH_CHECK_DELAY_MS=30000
RUNTIME_CONNECT_TIMEOUT_MS=3000
RUNTIME_RESPONSE_TIMEOUT_MS=360000

# 실제 배포 환경에 맞게 반드시 수정하세요. localhost는 Gateway 컴퓨터에서만 유효합니다.
GATEWAY_INTERNAL_BASE_URL=http://aiconnect-gateway.company.internal/v1
GATEWAY_EXTERNAL_BASE_URL=https://api.example.com/v1

# Tailscale 및 TLS Override를 사용할 때만 설정합니다.
TS_AUTHKEY=replace-with-a-preauthorized-tagged-tailscale-auth-key
TS_HOSTNAME=aiconnect-gateway
TLS_CERT_FILE=./secrets/tls/fullchain.pem
TLS_KEY_FILE=./secrets/tls/privkey.pem
"@

$utf8NoBom = New-Object Text.UTF8Encoding($false)
$resolved = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputPath))
[IO.File]::WriteAllText($resolved, $content, $utf8NoBom)
Write-Host "운영 비밀값이 포함된 '$OutputPath' 파일을 생성했습니다."
Write-Warning '이 파일과 GATEWAY_ENCRYPTION_KEY, API_KEY_PEPPER를 암호화된 별도 저장소에 백업하세요. 원문은 다시 출력하지 않습니다.'
