[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('BASELINE', 'FAILOVER', 'RECOVERY')][string]$Phase,
    [string]$BaseUrl = 'http://localhost',
    [Parameter(Mandatory)][Guid]$OrganizationId,
    [Parameter(Mandatory)][string]$LogicalService,
    [Parameter(Mandatory)][Guid]$PrimaryDeploymentId,
    [Parameter(Mandatory)][Guid]$SecondaryDeploymentId,
    [Guid]$PrimaryEndpointId,
    [string]$AdminEmail,
    [Security.SecureString]$AdminPassword,
    [Security.SecureString]$ProjectApiKey,
    [switch]$RequireSameRequestFailover,
    [switch]$RequireIncident,
    [string]$Prompt = 'Reply with a short failover verification response.'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

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
    if ($null -ne $Body) { $arguments.Body = $Body | ConvertTo-Json -Depth 30 -Compress }
    Invoke-RestMethod @arguments
}

function Assert-True {
    param([object]$Condition, [string]$Message)
    if (-not [bool]$Condition) { throw $Message }
}

if ([string]::IsNullOrWhiteSpace($AdminEmail)) {
    $AdminEmail = Read-Host 'AIConnect administrator email'
}
if ($null -eq $AdminPassword) {
    $AdminPassword = Read-Host 'AIConnect administrator password' -AsSecureString
}
if ($null -eq $ProjectApiKey) {
    $ProjectApiKey = Read-Host 'AIConnect project API key' -AsSecureString
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

$plainProjectKey = ConvertFrom-ProtectedString $ProjectApiKey
try {
    $projectHeaders = @{ Authorization = "Bearer $plainProjectKey" }
    $requestBody = @{
        model = $LogicalService
        messages = @(@{ role = 'user'; content = $Prompt })
        stream = $false
        max_tokens = 32
    }
    $response = Invoke-WebRequest -UseBasicParsing -Method POST -Uri "$BaseUrl/v1/chat/completions" `
        -Headers $projectHeaders -ContentType 'application/json' `
        -Body ($requestBody | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 300
} finally {
    $plainProjectKey = $null
    $projectHeaders = $null
}

Assert-True ($response.StatusCode -eq 200) "$Phase completion did not return HTTP 200."
$chat = $response.Content | ConvertFrom-Json
Assert-True ($chat.model -eq $LogicalService) 'The physical model ID leaked from the completion response.'
$requestId = [string]$response.Headers['X-Request-Id']
Assert-True (-not [string]::IsNullOrWhiteSpace($requestId)) 'The Gateway response omitted X-Request-Id.'

$observed = $null
$deadline = (Get-Date).AddSeconds(20)
do {
    $page = Invoke-Json -Method GET -Path "/api/admin/organizations/$OrganizationId/requests?size=100" -Headers $adminHeaders
    $observed = @($page.items | Where-Object { $_.requestId -eq $requestId }) | Select-Object -First 1
    if ($null -ne $observed -and $observed.status -ne 'IN_PROGRESS') { break }
    Start-Sleep -Milliseconds 400
} while ((Get-Date) -lt $deadline)

Assert-True ($null -ne $observed) 'The administrator Request Explorer did not return the completion.'
Assert-True ($observed.status -eq 'SUCCEEDED') "The logical request status was $($observed.status)."

$expectedDeployment = if ($Phase -eq 'FAILOVER') { $SecondaryDeploymentId } else { $PrimaryDeploymentId }
Assert-True ($observed.finalDeploymentId -eq $expectedDeployment.ToString()) "$Phase used deployment $($observed.finalDeploymentId), expected $expectedDeployment."

$attempts = @($observed.attempts)
Assert-True ($attempts.Count -ge 1) 'No physical runtime attempt was recorded.'
Assert-True (@($attempts.deploymentId) -contains $expectedDeployment.ToString()) 'The expected deployment is absent from request attempts.'

if ($Phase -eq 'FAILOVER' -and $RequireSameRequestFailover) {
    Assert-True ($observed.failoverCount -ge 1) 'The request routed to Secondary but did not record a same-request failover.'
    Assert-True (@($attempts.deploymentId) -contains $PrimaryDeploymentId.ToString()) 'The failed Primary attempt is absent.'
    Assert-True (@($attempts.deploymentId) -contains $SecondaryDeploymentId.ToString()) 'The successful Secondary attempt is absent.'
    $primaryAttempt = @($attempts | Where-Object { $_.deploymentId -eq $PrimaryDeploymentId.ToString() }) | Select-Object -First 1
    $secondaryAttempt = @($attempts | Where-Object { $_.deploymentId -eq $SecondaryDeploymentId.ToString() }) | Select-Object -Last 1
    Assert-True ($primaryAttempt.status -ne 'SUCCEEDED') 'The Primary attempt unexpectedly succeeded.'
    Assert-True ($secondaryAttempt.status -eq 'SUCCEEDED') 'The Secondary attempt did not succeed.'
}

$primaryHealth = $null
if ($null -ne $PrimaryEndpointId -and $PrimaryEndpointId -ne [Guid]::Empty) {
    $endpoints = Invoke-Json -Method GET -Path '/api/admin/runtime-endpoints' -Headers $adminHeaders
    $primary = $endpoints | Where-Object { $_.id -eq $PrimaryEndpointId.ToString() } | Select-Object -First 1
    Assert-True ($null -ne $primary) 'The Primary endpoint is not visible to the administrator.'
    $primaryHealth = [string]$primary.healthStatus
    if ($Phase -eq 'RECOVERY') {
        Assert-True ($primaryHealth -eq 'HEALTHY') "Recovered Primary endpoint status was $primaryHealth."
    }
}

$incidentStatus = $null
if ($Phase -eq 'FAILOVER' -and $RequireIncident) {
    Assert-True ($null -ne $PrimaryEndpointId -and $PrimaryEndpointId -ne [Guid]::Empty) '-RequireIncident needs -PrimaryEndpointId.'
    $incidentDeadline = (Get-Date).AddSeconds(90)
    do {
        $incidents = Invoke-Json -Method GET -Path "/api/admin/organizations/$OrganizationId/incidents?status=OPEN" -Headers $adminHeaders
        $incident = $incidents | Where-Object { $_.runtimeEndpointId -eq $PrimaryEndpointId.ToString() } | Select-Object -First 1
        if ($null -ne $incident) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $incidentDeadline)
    Assert-True ($null -ne $incident) 'No OPEN incident was observed for the Primary endpoint.'
    $incidentStatus = $incident.status
}

[ordered]@{
    status = 'passed'
    phase = $Phase
    requestId = $requestId
    logicalService = $LogicalService
    finalDeploymentId = $observed.finalDeploymentId
    failoverCount = $observed.failoverCount
    attemptCount = $attempts.Count
    primaryHealth = $primaryHealth
    incidentStatus = $incidentStatus
} | ConvertTo-Json -Depth 5
