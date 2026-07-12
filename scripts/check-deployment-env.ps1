[CmdletBinding()]
param(
    [string]$EnvFile = '.env',
    [switch]$RequireTailscale,
    [switch]$RequireTls
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    throw "배포 환경 파일 '$EnvFile'이 없습니다. .env.example을 복사한 뒤 기존 비밀값을 복원하세요. 새 비밀값으로 교체하면 기존 API 키와 암호화된 Runtime Token을 사용할 수 없을 수 있습니다."
}

$values = @{}
Get-Content -LiteralPath $EnvFile | ForEach-Object {
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
if ($RequireTls) { $required += @('TLS_CERT_FILE', 'TLS_KEY_FILE') }

$missing = @()
$placeholder = @()
foreach ($name in $required) {
    $value = $values[$name]
    if ([string]::IsNullOrWhiteSpace($value)) { $missing += $name; continue }
    if ($value -match 'replace-with|change-me|development-only|placeholder' -or ($secretNames -contains $name -and $value.Length -lt 24)) {
        $placeholder += $name
    }
}

if ($missing.Count -gt 0) { throw "필수 환경 변수가 누락되었습니다: $($missing -join ', ')" }
if ($placeholder.Count -gt 0) { throw "안전하지 않은 예시값 또는 너무 짧은 값이 있습니다: $($placeholder -join ', ')" }

$warnings = @()
if ($values['GATEWAY_INTERNAL_BASE_URL'] -match '^https?://(localhost|127\.0\.0\.1)(/|$)') {
    $warnings += 'GATEWAY_INTERNAL_BASE_URL이 localhost입니다. Gateway 컴퓨터 외부의 호출자에게는 실제 사내 DNS·LAN·Tailscale 주소를 안내해야 합니다.'
}
if ($RequireTls -and $values['GATEWAY_EXTERNAL_BASE_URL'] -notmatch '^https://') {
    $warnings += 'TLS 배포에서는 GATEWAY_EXTERNAL_BASE_URL을 https:// 주소로 설정하세요.'
}

Write-Host "배포 환경 파일을 확인했습니다: $EnvFile"
Write-Host "필수 비밀값 $($required.Count)개가 설정되어 있습니다. 원문은 표시하지 않습니다."
if ($warnings.Count -eq 0) { Write-Host '경고 없음' }
else { $warnings | ForEach-Object { Write-Warning $_ } }
