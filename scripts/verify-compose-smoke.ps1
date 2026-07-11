[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost',
    [Parameter(Mandatory)]
    [string]$AdminEmail,
    [Parameter(Mandatory)]
    [string]$AdminPassword,
    [string]$RuntimeBaseUrl = 'http://aiconnect-mock-lmstudio:1234'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Json {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [object]$Body,
        [hashtable]$Headers = @{},
        [Microsoft.PowerShell.Commands.WebRequestSession]$WebSession
    )
    $arguments = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        ContentType = 'application/json'
        TimeoutSec = 30
    }
    if ($null -ne $Body) { $arguments.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
    if ($null -ne $WebSession) { $arguments.WebSession = $WebSession }
    Invoke-RestMethod @arguments
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$credentials = @{ email = $AdminEmail; password = $AdminPassword }
try {
    $session = Invoke-Json -Method POST -Path '/api/auth/bootstrap' -Body $credentials -WebSession $webSession
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw }
    $session = Invoke-Json -Method POST -Path '/api/auth/login' -Body $credentials -WebSession $webSession
}
Assert-True (-not [string]::IsNullOrWhiteSpace($session.accessToken)) 'Admin authentication did not return an access token.'
$adminHeaders = @{ Authorization = "Bearer $($session.accessToken)" }
$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()

$organization = Invoke-Json -Method POST -Path '/api/admin/organizations' -Headers $adminHeaders -Body @{ name = "Compose Smoke $suffix" }
$project = Invoke-Json -Method POST -Path '/api/admin/projects' -Headers $adminHeaders -Body @{
    organizationId = $organization.id
    name = "Smoke Project $suffix"
}
$node = Invoke-Json -Method POST -Path '/api/admin/nodes' -Headers $adminHeaders -Body @{
    organizationId = $organization.id
    name = "mock-node-$suffix"
    description = 'Docker internal LM Studio-compatible smoke runtime'
    connectionMode = 'DIRECT'
    labelsJson = '{"environment":"smoke"}'
}
$endpoint = Invoke-Json -Method POST -Path '/api/admin/runtime-endpoints' -Headers $adminHeaders -Body @{
    nodeId = $node.id
    runtimeType = 'LM_STUDIO'
    baseUrl = $RuntimeBaseUrl
    apiToken = $null
}
$probe = Invoke-Json -Method POST -Path "/api/admin/runtime-endpoints/$($endpoint.id)/probe" -Headers $adminHeaders
Assert-True ($probe.reachable -eq $true) 'Runtime probe did not report reachable=true.'
Assert-True (@($probe.modelIds) -contains 'mock/gemma:loaded') 'Runtime probe did not discover the loaded mock model.'

$deployments = @(Invoke-Json -Method POST -Path "/api/admin/runtime-endpoints/$($endpoint.id)/sync-models" -Headers $adminHeaders)
Assert-True ($deployments.Count -eq 1) "Expected one synchronized deployment, received $($deployments.Count)."
$deployment = $deployments[0]
Assert-True ($deployment.loaded -eq $true) 'Synchronized deployment is not loaded.'
Assert-True ($deployment.compatibilityKey -eq 'mock/gemma') 'Native model compatibility key was not preserved.'

$serviceKey = "smoke-chat-$suffix"
$service = Invoke-Json -Method POST -Path '/api/admin/services' -Headers $adminHeaders -Body @{
    organizationId = $organization.id
    serviceKey = $serviceKey
    displayName = 'Compose Smoke Chat'
    failoverPolicy = 'STRICT'
    retryPolicy = 'SAFE'
    allowDegraded = $false
    requiredCapabilitiesJson = '["CHAT_COMPLETION"]'
    inputPricePerMillion = 1000
    outputPricePerMillion = 2000
}
Invoke-Json -Method POST -Path "/api/admin/services/$($service.id)/targets" -Headers $adminHeaders -Body @{
    deploymentId = $deployment.id
    priority = 1
    weight = 100
    degraded = $false
    maxConcurrencyOverride = 2
} | Out-Null
Invoke-Json -Method POST -Path "/api/admin/projects/$($project.id)/service-access" -Headers $adminHeaders -Body @{
    serviceId = $service.id
} | Out-Null
$issuedKey = Invoke-Json -Method POST -Path "/api/admin/projects/$($project.id)/api-keys" -Headers $adminHeaders -Body @{
    name = 'compose-smoke-key'
    expiresAt = $null
}
Assert-True (-not [string]::IsNullOrWhiteSpace($issuedKey.secret)) 'API-key issuance did not return the one-time secret.'
$projectHeaders = @{ Authorization = "Bearer $($issuedKey.secret)" }

$models = Invoke-Json -Method GET -Path '/v1/models' -Headers $projectHeaders
Assert-True (@($models.data.id) -contains $serviceKey) 'The entitled logical service is absent from /v1/models.'

$chatRequest = @{
    model = $serviceKey
    messages = @(@{ role = 'user'; content = 'Return the deterministic smoke response.' })
    stream = $false
}
$chatResponse = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$BaseUrl/v1/chat/completions" -Headers $projectHeaders `
    -ContentType 'application/json' -Body ($chatRequest | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 30
$chat = $chatResponse.Content | ConvertFrom-Json
Assert-True ($chatResponse.StatusCode -eq 200) 'Non-streaming completion did not return HTTP 200.'
Assert-True ($chat.model -eq $serviceKey) 'Physical model ID leaked from the non-streaming response.'
Assert-True ($chat.choices[0].message.content -eq 'mock response') 'Unexpected non-streaming completion content.'
Assert-True (-not [string]::IsNullOrWhiteSpace($chatResponse.Headers['X-Request-Id'])) 'Gateway response omitted X-Request-Id.'

$streamRequest = @{
    model = $serviceKey
    messages = @(@{ role = 'user'; content = 'Stream the deterministic smoke response.' })
    stream = $true
    stream_options = @{ include_usage = $true }
}
$streamResponse = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$BaseUrl/v1/chat/completions" -Headers $projectHeaders `
    -ContentType 'application/json' -Body ($streamRequest | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 30
Assert-True ($streamResponse.StatusCode -eq 200) 'Streaming completion did not return HTTP 200.'
Assert-True ($streamResponse.Content.Contains('data: [DONE]')) 'Streaming completion did not terminate with [DONE].'
Assert-True ($streamResponse.Content.Contains("`"model`":`"$serviceKey`"")) 'Logical service ID is absent from the SSE chunks.'
Assert-True (-not $streamResponse.Content.Contains('mock/gemma:loaded')) 'Physical model ID leaked from the SSE stream.'

$usage = $null
$history = @()
$observationDeadline = (Get-Date).AddSeconds(10)
do {
    $usage = Invoke-Json -Method GET -Path '/api/me/usage' -Headers $projectHeaders
    $historyResponse = Invoke-Json -Method GET -Path '/api/me/requests' -Headers $projectHeaders
    $history = @($historyResponse)
    if ($usage.requestCount -ge 2 -and $usage.inputTokens -ge 8 -and $usage.outputTokens -ge 4 -and $history.Count -ge 2) { break }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $observationDeadline)
Assert-True ($usage.requestCount -ge 2) 'Usage summary did not record both completions.'
Assert-True ($usage.inputTokens -ge 8) 'Usage summary did not record input tokens.'
Assert-True ($usage.outputTokens -ge 4) 'Usage summary did not record output tokens.'
Assert-True ($history.Count -ge 2) 'Request history did not return both completions.'
Assert-True (@($history.serviceKey) -contains $serviceKey) 'Request history omitted the logical service key.'
Assert-True (@($history.deploymentDisplayName) -contains 'Mock Gemma') 'Request history omitted the actual deployment display name.'

$adminRequests = Invoke-Json -Method GET -Path "/api/admin/organizations/$($organization.id)/requests?size=10" -Headers $adminHeaders
Assert-True ($adminRequests.totalElements -ge 2) 'Administrator request explorer did not record both completions.'

[ordered]@{
    status = 'passed'
    organizationId = $organization.id
    projectId = $project.id
    endpointId = $endpoint.id
    deploymentId = $deployment.id
    logicalService = $serviceKey
    apiKeyPrefix = $issuedKey.keyPrefix
    nonStreamingRequestId = $chatResponse.Headers['X-Request-Id']
    requestCount = $usage.requestCount
    inputTokens = $usage.inputTokens
    outputTokens = $usage.outputTokens
    estimatedCost = $usage.estimatedCost
    streamingDone = $true
} | ConvertTo-Json -Depth 5
