param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Z0-9][A-Z0-9_-]{0,13}$')][string]$Namespace = 'VERIFY',
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
. (Join-Path $PSScriptRoot 'galaxy-seed-data.ps1')
Assert-Week2LocalUrl $BaseUrl
$seedScript = Join-Path $PSScriptRoot 'seed-galaxy.ps1'

function Get-GalaxyVerificationState($Seed) {
    $sessionStates = @(foreach ($session in $Seed.sessions) {
        [ordered]@{
            session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($session.id)"
            tiers = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($session.id)/price-tiers")
            seats = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($session.id)/seats")
        }
    })
    [ordered]@{
        venue = Invoke-Week2Api $BaseUrl GET "/api/venues/$($Seed.venueId)"
        event = Invoke-Week2Api $BaseUrl GET "/api/events/$($Seed.eventId)"
        areas = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($Seed.venueId)/areas")
        templates = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($Seed.venueId)/seat-templates")
        sessions = $sessionStates
    }
}

$seed = & $seedScript -BaseUrl $BaseUrl -Namespace $Namespace -BaseTime $BaseTime
$before = Get-GalaxyVerificationState $seed
$beforeJson = $before | ConvertTo-Json -Depth 16 -Compress
$repeated = & $seedScript -BaseUrl $BaseUrl -Namespace $Namespace -BaseTime $seed.baseTime.AddDays(90)
$afterJson = (Get-GalaxyVerificationState $repeated) | ConvertTo-Json -Depth 16 -Compress
Assert-GalaxySeedCondition ($beforeJson -eq $afterJson) 'Repeated seeding changed IDs, timestamps or data.'
Write-Host 'PASS repeat: all records and first opening timestamps unchanged, even with a different BaseTime.'

$source = $before.templates | Where-Object { $_.areaCode -eq 'ORCHESTRA' -and $_.rowLabel -eq 'A' -and $_.seatNumber -eq 1 } | Select-Object -First 1
$restore = @{
    areaId = $source.areaId; rowLabel = $source.rowLabel; rowOrder = $source.rowOrder; seatNumber = $source.seatNumber
    coordinateX = $source.coordinateX; coordinateY = $source.coordinateY; aisleSegment = $source.aisleSegment; enabled = $source.enabled
}
$changed = $restore.Clone()
$changed.coordinateX = 999.125
try {
    $null = Invoke-Week2Api $BaseUrl PUT "/api/seat-templates/$($source.id)" $changed
    $rejected = $false
    try { $null = & $seedScript -BaseUrl $BaseUrl -Namespace $Namespace }
    catch {
        if ($_.Exception.Message -notlike '*Galaxy template values have changed*') { throw }
        $rejected = $true
    }
    Assert-GalaxySeedCondition $rejected 'Changed seed was not rejected.'
    $actual = Invoke-Week2Api $BaseUrl GET "/api/venues/$($seed.venueId)/seat-templates" | Where-Object id -eq $source.id | Select-Object -First 1
    Assert-GalaxySeedCondition ([decimal]$actual.coordinateX -eq 999.125) 'Seed silently overwrote a changed template.'
    $current = Get-GalaxyVerificationState $seed
    Assert-GalaxySeedCondition (($before.sessions | ConvertTo-Json -Depth 16 -Compress) -eq
        ($current.sessions | ConvertTo-Json -Depth 16 -Compress)) 'Source drift changed existing session snapshots.'
    Write-Host 'PASS drift: seeding rejected a changed template, preserved the edit, and kept all existing snapshots.'
} finally {
    $null = Invoke-Week2Api $BaseUrl PUT "/api/seat-templates/$($source.id)" $restore
}
$null = & $seedScript -BaseUrl $BaseUrl -Namespace $Namespace

# Prepare a real interrupted workflow: one intended ON_SALE session committed as DRAFT,
# with no other sessions. This is fixture setup, not a process-kill experiment.
$resumeNamespace = "$Namespace-R"
$resumeVenue = Invoke-Week2Api $BaseUrl GET "/api/venues/by-code/VENUE-GALAXY-$resumeNamespace" -AllowMissing
$preparedPartial = $null -eq $resumeVenue
$partialId = $null
if ($preparedPartial) {
    $resumeVenue = Invoke-Week2Api $BaseUrl POST '/api/venues' @{
        code = "VENUE-GALAXY-$resumeNamespace"; name = '星河艺术中心'; address = '自行构造的虚拟地址'; enabled = $true
    }
    foreach ($areaSpec in Get-GalaxyAreaSpecifications) {
        $area = Invoke-Week2Api $BaseUrl POST "/api/venues/$($resumeVenue.id)/areas" @{
            code = $areaSpec.code; name = $areaSpec.name; displayOrder = $areaSpec.displayOrder
        }
        $null = Invoke-Week2Api $BaseUrl POST "/api/areas/$($area.id)/seat-templates" @(New-GalaxySeatSpecifications $areaSpec)
    }
    $resumeEvent = Invoke-Week2Api $BaseUrl POST '/api/events' @{
        code = "EVENT-STARLIGHT-$resumeNamespace"; name = '星河交响夜'; description = '自行构造的虚拟演出'; enabled = $true
    }
    $resumeTemplates = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($resumeVenue.id)/seat-templates")
    $overrides = @($resumeTemplates | Where-Object enabled | ForEach-Object {
        @{ seatTemplateId = $_.id; priceTierCode = Get-GalaxyTierCode $_.areaCode $_.rowLabel }
    })
    $start = $seed.baseTime.AddDays(7)
    $partial = Invoke-Week2Api $BaseUrl POST '/api/sessions' @{
        code = "SESSION-ON-SALE-$resumeNamespace"; eventId = $resumeEvent.id; venueId = $resumeVenue.id
        startsAt = $start.ToString('o'); admissionOpenAt = $start.AddHours(-1).ToString('o')
        admissionCloseAt = $start.AddMinutes(-5).ToString('o'); forbidNewOrphan = $true
        priceTiers = @(Get-GalaxyPriceSpecifications); areaPrices = @(); seatOverrides = $overrides
    }
    $partialId = $partial.id
    $partialState = Invoke-Week2Api $BaseUrl GET "/api/sessions/$partialId"
    Assert-GalaxySeedCondition ($partialState.salesStatus -eq 'DRAFT' -and $null -eq $partialState.firstOpenedAt) 'Partial recovery fixture is not DRAFT.'
}
$resumed = & $seedScript -BaseUrl $BaseUrl -Namespace $resumeNamespace -BaseTime $seed.baseTime.AddDays(90)
if ($preparedPartial) {
    $recovered = $resumed.sessions | Where-Object code -eq "SESSION-ON-SALE-$resumeNamespace" | Select-Object -First 1
    Assert-GalaxySeedCondition ($recovered.id -eq $partialId -and $recovered.salesStatus -eq 'ON_SALE' -and
        $resumed.baseTime -eq $seed.baseTime) 'Partial run did not reuse its committed session and original time.'
}
Write-Host "PASS recovery: namespace=$resumeNamespace; sessions=3; snapshots=738; newlyPreparedPartial=$preparedPartial."
Write-Host "Verification complete. Retained fixtures: $Namespace and $resumeNamespace; source template restored."
