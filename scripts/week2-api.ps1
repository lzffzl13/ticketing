# Shared by local seed/demo scripts. No database credentials are read or printed.
function Assert-Week2LocalUrl([string]$BaseUrl) {
    $taskUri = [Uri]$BaseUrl
    if ($taskUri.Scheme -ne 'http' -or $taskUri.Host -notin @('127.0.0.1', 'localhost')) {
        throw 'The week-2 scripts target a local HTTP application only.'
    }
}

function Invoke-Week2Api {
    param([string]$BaseUrl, [string]$Method, [string]$Path, $Payload, [switch]$AllowMissing)
    $taskArguments = @{ Uri = "$($BaseUrl.TrimEnd('/'))$Path"; Method = $Method; ErrorAction = 'Stop' }
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
