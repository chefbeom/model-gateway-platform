[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost',
    [Parameter(Mandatory)][string]$RuntimeBaseUrl,
    [string]$AdminEmail,
    [Security.SecureString]$AdminPassword,
    [Security.SecureString]$RuntimeApiToken,
    [Guid]$OrganizationId,
    [string]$Prompt = 'Reply with a short confirmation that the Tailnet runtime is reachable.'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$RuntimeBaseUrl = $RuntimeBaseUrl.TrimEnd('/')

function ConvertFrom-ProtectedString {
    param([Security.SecureString]$Value)
    if ($null -eq $Value) { return $null }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Invoke-Json {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body,
        [hashtable]$Headers = @{},
        [int]$TimeoutSec = 60
    )
    $arguments = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        ContentType = 'application/json'
        TimeoutSec = $TimeoutSec
    }
    if ($null -ne $Body) { $arguments.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
    Invoke-RestMethod @arguments
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    $AdminEmail = Read-Host 'AIConnect platform administrator email'
}
if ($null -eq $AdminPassword) {
    $AdminPassword = Read-Host 'AIConnect platform administrator password' -AsSecureString
}

$plainAdminPassword = ConvertFrom-ProtectedString $AdminPassword
try {
    $session = Invoke-Json -Method POST -Path '/api/auth/login' -Body @{
        email = $AdminEmail
        password = $plainAdminPassword
    }
} finally {
    $plainAdminPassword = $null
}
Assert-True (-not [string]::IsNullOrWhiteSpace($session.accessToken)) 'Administrator login did not return an access token.'
$adminHeaders = @{ Authorization = "Bearer $($session.accessToken)" }

$organizations = @(Invoke-Json -Method GET -Path '/api/admin/organizations' -Headers $adminHeaders)
if ($null -eq $OrganizationId -or $OrganizationId -eq [Guid]::Empty) {
    Assert-True ($organizations.Count -gt 0) 'No organization exists. Create one before running the Tailnet verification.'
    $OrganizationId = [Guid]$organizations[0].id
} else {
    Assert-True (@($organizations.id) -contains $OrganizationId.ToString()) 'The selected organization is not visible to this administrator.'
}

$runtimeToken = ConvertFrom-ProtectedString $RuntimeApiToken
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
try {
    $project = Invoke-Json -Method POST -Path '/api/admin/projects' -Headers $adminHeaders -Body @{
        organizationId = $OrganizationId
        name = "Tailnet Verification $suffix"
    }
    $node = Invoke-Json -Method POST -Path '/api/admin/nodes' -Headers $adminHeaders -Body @{
        organizationId = $OrganizationId
        name = "tailnet-runtime-$suffix"
        description = 'Created by verify-tailnet-runtime.ps1'
        connectionMode = 'DIRECT'
        labelsJson = '{"verification":"tailnet"}'
    }
    $endpoint = Invoke-Json -Method POST -Path '/api/admin/runtime-endpoints' -Headers $adminHeaders -Body @{
        nodeId = $node.id
        runtimeType = 'LM_STUDIO'
        baseUrl = $RuntimeBaseUrl
        apiToken = $runtimeToken
    }
} finally {
    $runtimeToken = $null
}

$probe = Invoke-Json -Method POST -Path "/api/admin/runtime-endpoints/$($endpoint.id)/probe" -Headers $adminHeaders -TimeoutSec 120
Assert-True ($probe.reachable -eq $true) 'The Gateway could not reach LM Studio through the Tailnet runtime proxy.'
Assert-True (@($probe.modelIds).Count -gt 0) 'LM Studio was reachable but exposed no models.'

$deployments = @(Invoke-Json -Method POST -Path "/api/admin/runtime-endpoints/$($endpoint.id)/sync-models" -Headers $adminHeaders -TimeoutSec 120)
$deployment = $deployments | Where-Object { $_.loaded -eq $true -and $_.enabled -eq $true } | Select-Object -First 1
Assert-True ($null -ne $deployment) 'No loaded, enabled LM Studio deployment was discovered.'

$serviceKey = "tailnet-check-$suffix"
$service = Invoke-Json -Method POST -Path '/api/admin/services' -Headers $adminHeaders -Body @{
    organizationId = $OrganizationId
    serviceKey = $serviceKey
    displayName = 'Tailnet Runtime Verification'
    failoverPolicy = 'STRICT'
    retryPolicy = 'SAFE'
    allowDegraded = $false
    requiredCapabilitiesJson = '[]'
    inputPricePerMillion = 0
    outputPricePerMillion = 0
}
Invoke-Json -Method POST -Path "/api/admin/services/$($service.id)/targets" -Headers $adminHeaders -Body @{
    deploymentId = $deployment.id
    priority = 1
    weight = 100
    degraded = $false
    maxConcurrencyOverride = 1
} | Out-Null
Invoke-Json -Method POST -Path "/api/admin/projects/$($project.id)/service-access" -Headers $adminHeaders -Body @{
    serviceId = $service.id
} | Out-Null
$issuedKey = Invoke-Json -Method POST -Path "/api/admin/projects/$($project.id)/api-keys" -Headers $adminHeaders -Body @{
    name = 'tailnet-verification'
    expiresAt = $null
}
Assert-True (-not [string]::IsNullOrWhiteSpace($issuedKey.secret)) 'API-key issuance did not return its one-time secret.'
$projectHeaders = @{ Authorization = "Bearer $($issuedKey.secret)" }

$models = Invoke-Json -Method GET -Path '/v1/models' -Headers $projectHeaders
Assert-True (@($models.data.id) -contains $serviceKey) 'The temporary logical service is absent from /v1/models.'

$chatRequest = @{
    model = $serviceKey
    messages = @(@{ role = 'user'; content = $Prompt })
    stream = $false
}
$chatResponse = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$BaseUrl/v1/chat/completions" -Headers $projectHeaders `
    -ContentType 'application/json' -Body ($chatRequest | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 300
$chat = $chatResponse.Content | ConvertFrom-Json
Assert-True ($chatResponse.StatusCode -eq 200) 'Non-streaming completion did not return HTTP 200.'
Assert-True ($chat.model -eq $serviceKey) 'The physical model ID leaked from the non-streaming response.'
Assert-True (@($chat.choices).Count -gt 0) 'The non-streaming completion contained no choices.'

$streamRequest = @{
    model = $serviceKey
    messages = @(@{ role = 'user'; content = $Prompt })
    stream = $true
    stream_options = @{ include_usage = $true }
}
$streamResponse = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$BaseUrl/v1/chat/completions" -Headers $projectHeaders `
    -ContentType 'application/json' -Body ($streamRequest | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 300
Assert-True ($streamResponse.StatusCode -eq 200) 'Streaming completion did not return HTTP 200.'
Assert-True ($streamResponse.Content.Contains('data: [DONE]')) 'Streaming completion did not terminate with [DONE].'
Assert-True ($streamResponse.Content.Contains("`"model`":`"$serviceKey`"")) 'The logical service ID is absent from SSE chunks.'
Assert-True (-not $streamResponse.Content.Contains($deployment.providerModelId)) 'The physical model ID leaked from the SSE stream.'

$usage = $null
$deadline = (Get-Date).AddSeconds(20)
do {
    $usage = Invoke-Json -Method GET -Path '/api/me/usage' -Headers $projectHeaders
    if ($usage.requestCount -ge 2) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $deadline)
Assert-True ($usage.requestCount -ge 2) 'Usage aggregation did not observe both real-runtime requests.'

[ordered]@{
    status = 'passed'
    organizationId = $OrganizationId
    projectId = $project.id
    endpointId = $endpoint.id
    deploymentId = $deployment.id
    physicalModel = $deployment.providerModelId
    logicalService = $serviceKey
    nonStreamingRequestId = $chatResponse.Headers['X-Request-Id']
    requestCount = $usage.requestCount
    inputTokens = $usage.inputTokens
    outputTokens = $usage.outputTokens
    streamingDone = $true
} | ConvertTo-Json -Depth 5
