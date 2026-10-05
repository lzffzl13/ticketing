param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Z0-9][A-Z0-9_-]{0,15}$')][string]$Namespace = 'DEMO',
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
. (Join-Path $PSScriptRoot 'galaxy-seed-data.ps1')
Assert-Week2LocalUrl $BaseUrl
$venueCode = "VENUE-GALAXY-$Namespace"
$eventCode = "EVENT-STARLIGHT-$Namespace"
$sessionSpecs = @(Get-GalaxySessionSpecifications)
$existingSessions = @{}
foreach ($spec in $sessionSpecs) {
    $code = "$($spec.code)-$Namespace"
    $existing = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$code" -AllowMissing
    if ($null -ne $existing) { $existingSessions[$code] = $existing }
}
# Match DATETIME(6) precision once; relative times always use this single UTC base.
$utcTicks = $BaseTime.UtcDateTime.Ticks
$base = [DateTimeOffset]::new($utcTicks - ($utcTicks % 10), [TimeSpan]::Zero)
foreach ($spec in $sessionSpecs) {
    $existing = $existingSessions["$($spec.code)-$Namespace"]
    if ($null -ne $existing) {
        $base = ([DateTimeOffset]$existing.startsAt).AddMinutes(-$spec.offsetMinutes).ToUniversalTime()
        break
    }
}
$venue = Invoke-Week2Api $BaseUrl GET "/api/venues/by-code/$venueCode" -AllowMissing
$event = Invoke-Week2Api $BaseUrl GET "/api/events/by-code/$eventCode" -AllowMissing
$areas = @()
$templates = @()

# Preflight all existing data before creating or opening anything. No silent repairs.
if ($existingSessions.Count -gt 0) {
    Assert-GalaxySeedCondition ($null -ne $venue -and $null -ne $event) 'Existing galaxy sessions have missing seed parents.'
}
if ($null -ne $venue) {
    Assert-GalaxySeedCondition ($venue.name -eq '星河艺术中心' -and $venue.address -eq '自行构造的虚拟地址' -and $venue.enabled) 'Galaxy venue has changed.'
    $areas = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venue.id)/areas")
    $templates = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venue.id)/seat-templates")
    Assert-GalaxyTemplates $areas $templates ($existingSessions.Count -gt 0)
}
if ($null -ne $event) {
    Assert-GalaxySeedCondition ($event.name -eq '星河交响夜' -and $event.description -eq '自行构造的虚拟演出' -and $event.enabled) 'Galaxy event has changed.'
}
foreach ($spec in $sessionSpecs) {
    $existing = $existingSessions["$($spec.code)-$Namespace"]
    if ($null -ne $existing) { Assert-GalaxySession $existing $spec $venue $event $base $templates $BaseUrl }
}

# Each API operation owns its database transaction. A failed run can resume from completed operations.
if ($null -eq $venue) {
    $venue = Invoke-Week2Api $BaseUrl POST '/api/venues' @{
        code = $venueCode; name = '星河艺术中心'; address = '自行构造的虚拟地址'; enabled = $true
    }
}
foreach ($spec in Get-GalaxyAreaSpecifications) {
    $area = $areas | Where-Object code -eq $spec.code | Select-Object -First 1
    if ($null -eq $area) {
        $area = Invoke-Week2Api $BaseUrl POST "/api/venues/$($venue.id)/areas" @{
            code = $spec.code; name = $spec.name; displayOrder = $spec.displayOrder
        }
        $areas += $area
    }
    if (@($templates | Where-Object areaId -eq $area.id).Count -eq 0) {
        $null = Invoke-Week2Api $BaseUrl POST "/api/areas/$($area.id)/seat-templates" @(New-GalaxySeatSpecifications $spec)
    }
}
$templates = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venue.id)/seat-templates")
Assert-GalaxyTemplates $areas $templates $true
if ($null -eq $event) {
    $event = Invoke-Week2Api $BaseUrl POST '/api/events' @{
        code = $eventCode; name = '星河交响夜'; description = '自行构造的虚拟演出'; enabled = $true
    }
}
$overrides = @($templates | Where-Object enabled | ForEach-Object {
    @{ seatTemplateId = $_.id; priceTierCode = Get-GalaxyTierCode $_.areaCode $_.rowLabel }
})
$sessions = @()
foreach ($spec in $sessionSpecs) {
    $code = "$($spec.code)-$Namespace"
    $session = $existingSessions[$code]
    if ($null -eq $session) {
        $start = $base.AddMinutes($spec.offsetMinutes)
        $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' @{
            code = $code; eventId = $event.id; venueId = $venue.id
            startsAt = $start.ToString('o'); admissionOpenAt = $start.AddHours(-1).ToString('o')
            admissionCloseAt = $start.AddMinutes(-5).ToString('o'); forbidNewOrphan = $true
            priceTiers = @(Get-GalaxyPriceSpecifications); areaPrices = @(); seatOverrides = $overrides
        }
        $session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
    }
    Assert-GalaxySession $session $spec $venue $event $base $templates $BaseUrl
    if ($spec.salesStatus -eq 'ON_SALE' -and $session.salesStatus -eq 'DRAFT') {
        $session = Invoke-Week2Api $BaseUrl PUT "/api/sessions/$($session.id)/sales-status" @{ salesStatus = 'ON_SALE' }
    }
    Assert-GalaxySeedCondition ($session.salesStatus -eq $spec.salesStatus) 'Galaxy seed did not reach its expected sales state.'
    $sessions += $session
    Write-Host "Session ready: $code; state=$($session.salesStatus); seats=246; available=242; startsAt (UTC)=$(([DateTimeOffset]$session.startsAt).UtcDateTime.ToString('o'))"
}
Write-Host "Galaxy seed ready: venues=1; areas=2; rows=14; templates=246; sessions=3; tiers=9; snapshots=738; accounts=0; reusedSessions=$($existingSessions.Count)"
Write-Host "Seed base time (UTC): $($base.UtcDateTime.ToString('o'))"
[pscustomobject]@{
    venueId = $venue.id; eventId = $event.id; baseTime = $base; templateCount = $templates.Count
    sessionCount = $sessions.Count; sessionSeatCount = 738; accountCount = 0; sessions = $sessions
}
