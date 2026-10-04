param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Z0-9][A-Z0-9_-]{0,15}$')][string]$Namespace = 'W2',
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$venueCode = "VENUE-RULES-$Namespace"
$eventCode = "EVENT-STARLIGHT-$Namespace"
$sessionCode = "SESSION-SNAPSHOT-$Namespace"

function Assert-SeedCondition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "$Message Existing data is preserved. Use a new -Namespace to create an independent fixture."
    }
}

$venueData = Invoke-Week2Api $BaseUrl GET "/api/venues/by-code/$venueCode" -AllowMissing
if ($null -eq $venueData) {
    $venueData = Invoke-Week2Api $BaseUrl POST '/api/venues' @{
        code = $venueCode; name = '规则测试厅'; address = '自行构造的虚拟地址'; enabled = $true
    }
}
Assert-SeedCondition ($venueData.name -eq '规则测试厅' -and $venueData.enabled) 'Seed venue has changed.'
$existingSession = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$sessionCode" -AllowMissing
$areaSpecs = @(
    @{ code = 'FLOOR-A'; name = '池座'; displayOrder = 0; labels = 'ABC'; seatCount = 12; aisle = 6; disabledRow = 'C'; disabledNumber = 1 },
    @{ code = 'BALCONY-B'; name = '楼座'; displayOrder = 1; labels = 'DE'; seatCount = 10; aisle = 5; disabledRow = 'E'; disabledNumber = 10 }
)
$areas = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venueData.id)/areas")
Assert-SeedCondition (@($areas | Where-Object { $_.code -notin @('FLOOR-A', 'BALCONY-B') }).Count -eq 0) 'Unexpected seed area.'

foreach ($spec in $areaSpecs) {
    $areaData = $areas | Where-Object code -eq $spec.code | Select-Object -First 1
    if ($null -eq $areaData) {
        Assert-SeedCondition ($null -eq $existingSession) 'A completed seed is missing an area.'
        $areaData = Invoke-Week2Api $BaseUrl POST "/api/venues/$($venueData.id)/areas" @{
            code = $spec.code; name = $spec.name; displayOrder = $spec.displayOrder
        }
    }
    Assert-SeedCondition ($areaData.name -eq $spec.name -and $areaData.displayOrder -eq $spec.displayOrder) 'Seed area has changed.'
    $expected = @()
    for ($row = 0; $row -lt $spec.labels.Length; $row++) {
        $label = [string]$spec.labels[$row]
        for ($number = 1; $number -le $spec.seatCount; $number++) {
            $expected += @{
                rowLabel = $label; rowOrder = $row; seatNumber = $number
                coordinateX = $number; coordinateY = $row; aisleSegment = $(if ($number -le $spec.aisle) { 'L' } else { 'R' })
                enabled = -not ($label -eq $spec.disabledRow -and $number -eq $spec.disabledNumber)
            }
        }
    }
    $existing = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venueData.id)/seat-templates" | Where-Object areaId -eq $areaData.id)
    if ($existing.Count -eq 0) {
        Assert-SeedCondition ($null -eq $existingSession) 'A completed seed is missing its templates.'
        $null = Invoke-Week2Api $BaseUrl POST "/api/areas/$($areaData.id)/seat-templates" $expected
        $existing = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venueData.id)/seat-templates" | Where-Object areaId -eq $areaData.id)
    }
    Assert-SeedCondition ($existing.Count -eq $expected.Count) 'Seed template count has changed.'
    foreach ($seatSpec in $expected) {
        $actual = $existing | Where-Object { $_.rowLabel -eq $seatSpec.rowLabel -and $_.seatNumber -eq $seatSpec.seatNumber } | Select-Object -First 1
        Assert-SeedCondition ($null -ne $actual -and $actual.rowOrder -eq $seatSpec.rowOrder -and
            [decimal]$actual.coordinateX -eq [decimal]$seatSpec.coordinateX -and [decimal]$actual.coordinateY -eq [decimal]$seatSpec.coordinateY -and
            $actual.aisleSegment -eq $seatSpec.aisleSegment -and $actual.enabled -eq $seatSpec.enabled) 'Seed template values have changed.'
    }
}

$eventData = Invoke-Week2Api $BaseUrl GET "/api/events/by-code/$eventCode" -AllowMissing
if ($null -eq $eventData) {
    $eventData = Invoke-Week2Api $BaseUrl POST '/api/events' @{
        code = $eventCode; name = '星河交响夜'; description = '自行构造的虚拟演出'; enabled = $true
    }
}
Assert-SeedCondition ($eventData.name -eq '星河交响夜' -and $eventData.enabled) 'Seed event has changed.'
$seedData = [pscustomobject]@{ venueId = $venueData.id; eventId = $eventData.id; sessionId = $null; sessionCode = $sessionCode; baseTime = $BaseTime }
$sessionData = $existingSession
$reused = $null -ne $sessionData
if (-not $reused) {
    $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seedData $sessionCode)
    $sessionData = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
}
Assert-SeedCondition ($sessionData.venueId -eq $venueData.id -and $sessionData.eventId -eq $eventData.id -and
    $sessionData.salesStatus -eq 'DRAFT') 'Seed session configuration has changed.'
$seedData.sessionId = $sessionData.id
$snapshots = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($sessionData.id)/seats")
Assert-SeedCondition ($snapshots.Count -eq 56 -and @($snapshots | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 54) 'Seed snapshot count/state has changed.'
$templates = @(Invoke-Week2Api $BaseUrl GET "/api/venues/$($venueData.id)/seat-templates")
foreach ($snapshot in $snapshots) {
    $source = $templates | Where-Object id -eq $snapshot.sourceSeatTemplateId | Select-Object -First 1
    Assert-SeedCondition ($null -ne $source -and $snapshot.areaCodeSnapshot -eq $source.areaCode -and
        $snapshot.areaNameSnapshot -eq $source.areaName -and $snapshot.areaDisplayOrderSnapshot -eq $source.areaDisplayOrder -and
        $snapshot.rowLabelSnapshot -eq $source.rowLabel -and $snapshot.rowOrderSnapshot -eq $source.rowOrder -and
        $snapshot.seatNumberSnapshot -eq $source.seatNumber -and [decimal]$snapshot.coordinateXSnapshot -eq [decimal]$source.coordinateX -and
        [decimal]$snapshot.coordinateYSnapshot -eq [decimal]$source.coordinateY -and $snapshot.aisleSegmentSnapshot -eq $source.aisleSegment) 'Seed snapshot layout has changed.'
    if ($source.enabled) {
        $price = $(if ($source.areaCode -eq 'FLOOR-A') { 380 } else { 180 })
        $tierName = $(if ($source.areaCode -eq 'FLOOR-A') { 'A 档' } else { 'B 档' })
        Assert-SeedCondition ($snapshot.seatStatus -eq 'AVAILABLE' -and [decimal]$snapshot.priceSnapshot -eq $price -and
            $snapshot.priceTierNameSnapshot -eq $tierName -and $null -ne $snapshot.priceTierId) 'Seed snapshot price/state has changed.'
    } else {
        Assert-SeedCondition ($snapshot.seatStatus -eq 'UNAVAILABLE' -and $null -eq $snapshot.priceTierId -and
            $null -eq $snapshot.priceTierNameSnapshot -and $null -eq $snapshot.priceSnapshot) 'Disabled seed snapshot has changed.'
    }
}
Write-Host "Seed ready: $venueCode; 2 areas; 56 templates; 54 available; session=$sessionCode; reused=$reused"
$seedStartTime = [DateTimeOffset]$sessionData.startsAt
$seedBaseTime = $seedStartTime.AddDays(-30)
$seedData.baseTime = $seedBaseTime
Write-Host "Seed base time (UTC): $($seedBaseTime.UtcDateTime.ToString('o')); startsAt (UTC)=$($seedStartTime.UtcDateTime.ToString('o'))"
$seedData
