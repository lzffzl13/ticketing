# Shared by local seed/demo scripts. Credentials and login tokens are never printed.
function Assert-Week2LocalUrl([string]$BaseUrl) {
    $taskUri = [Uri]$BaseUrl
    if ($taskUri.Scheme -ne 'http' -or $taskUri.Host -notin @('127.0.0.1', 'localhost')) {
        throw 'The week-2 scripts target a local HTTP application only.'
    }
}

function Get-Week2Login {
    param([string]$BaseUrl, [string]$LoginName = 'operator1')
    Assert-Week2LocalUrl $BaseUrl
    if ($null -eq (Get-Variable -Name Week2LoginCache -Scope Script -ErrorAction SilentlyContinue)) {
        $script:Week2LoginCache = @{}
    }
    $taskLoginKey = "$($BaseUrl.TrimEnd('/'))|$LoginName"
    $taskLogin = $script:Week2LoginCache[$taskLoginKey]
    if ($null -ne $taskLogin -and [DateTimeOffset]$taskLogin.expiresAt -gt [DateTimeOffset]::UtcNow.AddSeconds(30)) {
        return $taskLogin
    }
    $taskSettings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
    $taskLoginBody = @{ loginName = $LoginName; password = $taskSettings.DEMO_PASSWORD } | ConvertTo-Json -Compress
    # Call the login endpoint directly: Invoke-Week2Api authenticates management writes.
    $taskLogin = (Invoke-RestMethod -Uri "$($BaseUrl.TrimEnd('/'))/api/auth/login" -Method POST `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($taskLoginBody)) -ErrorAction Stop).data
    if ([string]::IsNullOrWhiteSpace($taskLogin.token) -or [string]::IsNullOrWhiteSpace($taskLogin.expiresAt)) {
        throw 'Login returned no valid token or expiry. Inspect the application log; credentials were not printed.'
    }
    $script:Week2LoginCache[$taskLoginKey] = $taskLogin
    return $taskLogin
}

function Invoke-Week2Api {
    param([string]$BaseUrl, [string]$Method, [string]$Path, $Payload, [switch]$AllowMissing, [hashtable]$Headers)
    Assert-Week2LocalUrl $BaseUrl
    $taskArguments = @{ Uri = "$($BaseUrl.TrimEnd('/'))$Path"; Method = $Method; ErrorAction = 'Stop' }
    $taskHeaders = @{}
    if ($null -ne $Headers) {
        foreach ($taskHeader in $Headers.Keys) { $taskHeaders[$taskHeader] = $Headers[$taskHeader] }
    }
    if ($Method -notin @('GET', 'HEAD', 'OPTIONS') -and $Path -ne '/api/auth/login' -and -not $taskHeaders.ContainsKey('Authorization')) {
        $taskLogin = Get-Week2Login $BaseUrl
        $taskHeaders.Authorization = "Bearer $($taskLogin.token)"
    }
    if ($taskHeaders.Count -gt 0) { $taskArguments.Headers = $taskHeaders }
    if ($null -ne $Payload) {
        $taskArguments.ContentType = 'application/json; charset=utf-8'
        $taskArguments.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 20 -Compress))
    }
    try { return (Invoke-RestMethod @taskArguments).data }
    catch {
        if ($AllowMissing -and $null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) {
            return $null
        }
        throw
    }
}

function New-Week2SessionPayload {
    param([string]$BaseUrl, $Seed, [string]$Code)
    $taskTemplates = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($Seed.venueId)/seat-templates")
    $taskStart = ([DateTimeOffset]$Seed.baseTime).AddDays(30)
    $taskOverrides = @($taskTemplates | Where-Object enabled | ForEach-Object {
        @{ seatTemplateId = $_.id; priceTierCode = $(if ($_.areaCode -eq 'FLOOR-A') { 'TIER-A' } else { 'TIER-B' }) }
    })
    return @{
        code = $Code; eventId = $Seed.eventId; venueId = $Seed.venueId
        startsAt = $taskStart.ToString('o'); admissionOpenAt = $taskStart.AddHours(-1).ToString('o')
        admissionCloseAt = $taskStart.AddMinutes(-5).ToString('o'); forbidNewOrphan = $true
        priceTiers = @(@{ code = 'TIER-A'; name = 'A 档'; price = 380.00 }, @{ code = 'TIER-B'; name = 'B 档'; price = 180.00 })
        areaPrices = @(); seatOverrides = $taskOverrides
    }
}
