[CmdletBinding()]
param(
    [string]$EnvFile = '.env',
    [switch]$RequireTailscale,
    [switch]$RequireTls
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "배포 환경 파일 '$EnvFile'이 없습니다. 새 설치라면 scripts/new-deployment-env.ps1을 실행하고, 기존 설치라면 백업된 비밀값을 복원하세요."
}

$values = @{}
Get-Content -LiteralPath $EnvFile -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith('#')) { return }
    $separator = $line.IndexOf('=')
    if ($separator -lt 1) { return }
    $values[$line.Substring(0, $separator).Trim()] = $line.Substring($separator + 1).Trim()
}

$secretNames = @('DB_PASSWORD', 'MARIADB_ROOT_PASSWORD', 'ADMIN_API_TOKEN', 'API_KEY_PEPPER',
    'GATEWAY_ENCRYPTION_KEY', 'AUTH_SIGNING_KEY', 'AUTH_REFRESH_PEPPER', 'GRAFANA_ADMIN_PASSWORD')
$required = @($secretNames)
if ($RequireTailscale) { $required += 'TS_AUTHKEY'; $secretNames += 'TS_AUTHKEY' }
if ($RequireTls) { $required += @('TLS_CERT_FILE', 'TLS_KEY_FILE', 'GATEWAY_EXTERNAL_BASE_URL') }

$missing = @()
$unsafe = @()
foreach ($name in $required) {
    $value = $values[$name]
    if ([string]::IsNullOrWhiteSpace($value)) { $missing += $name; continue }
    if ($value -match 'replace-with|change-me|development-only|placeholder|example\.com' -or
        ($secretNames -contains $name -and $value.Length -lt 24)) {
        $unsafe += $name
    }
}

if ($missing.Count -gt 0) { throw "필수 환경 변수가 누락되었습니다: $($missing -join ', ')" }
if ($unsafe.Count -gt 0) { throw "안전하지 않은 예시값 또는 너무 짧은 값이 있습니다: $($unsafe -join ', ')" }

$duplicateSecrets = $secretNames | Where-Object { $values[$_] } | Group-Object { $values[$_] } | Where-Object Count -gt 1
if ($duplicateSecrets) {
    $duplicateSecretNames = $duplicateSecrets | ForEach-Object { $_.Group -join ' + ' }
    throw "서로 다른 용도의 비밀값을 재사용했습니다: $($duplicateSecretNames -join ', ')"
}

$connectTimeout = 0L
$responseTimeout = 0L
if (-not [long]::TryParse($values['RUNTIME_CONNECT_TIMEOUT_MS'], [ref]$connectTimeout) -or $connectTimeout -lt 100 -or $connectTimeout -gt 60000) {
    throw 'RUNTIME_CONNECT_TIMEOUT_MS는 100~60000ms 범위의 정수여야 합니다.'
}
if (-not [long]::TryParse($values['RUNTIME_RESPONSE_TIMEOUT_MS'], [ref]$responseTimeout) -or $responseTimeout -le $connectTimeout -or $responseTimeout -gt 3600000) {
    throw 'RUNTIME_RESPONSE_TIMEOUT_MS는 연결 제한보다 크고 3600000ms 이하인 정수여야 합니다.'
}

$warnings = @()
if ($values['GATEWAY_INTERNAL_BASE_URL'] -match '^https?://(localhost|127\.0\.0\.1)(/|$)') {
    $warnings += 'GATEWAY_INTERNAL_BASE_URL이 localhost입니다. Gateway 컴퓨터 외부 호출자에게는 실제 사내 DNS·LAN·Tailscale 주소를 안내해야 합니다.'
}
if ($RequireTls) {
    if ($values['GATEWAY_EXTERNAL_BASE_URL'] -notmatch '^https://') {
        throw 'TLS 배포에서는 GATEWAY_EXTERNAL_BASE_URL을 https:// 주소로 설정해야 합니다.'
    }
    foreach ($name in @('TLS_CERT_FILE', 'TLS_KEY_FILE')) {
        if (-not (Test-Path -LiteralPath $values[$name] -PathType Leaf)) {
            throw "$name 파일을 찾을 수 없습니다: $($values[$name])"
        }
    }
}

Write-Host "배포 환경 파일을 확인했습니다: $EnvFile"
Write-Host "필수 비밀값 $($required.Count)개가 설정되어 있으며 원문은 표시하지 않습니다."
Write-Host "Runtime timeout: connect=${connectTimeout}ms, response=${responseTimeout}ms"
if ($warnings.Count -eq 0) { Write-Host '경고 없음' }
else { $warnings | ForEach-Object { Write-Warning $_ } }
