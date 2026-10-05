param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Z0-9][A-Z0-9_-]{0,15}$')][string]$Namespace = 'W3',
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl

function Assert-SeatDemo([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "$Message Existing data is preserved; use a new -Namespace for an independent fixture." }
}

function Get-SeatDemoSingletons($Seats, $ExcludedIds) {
    $singletons = [Collections.Generic.HashSet[long]]::new()
    $available = @($Seats | Where-Object { $_.seatStatus -eq 'AVAILABLE' -and -not $ExcludedIds.Contains([long]$_.id) })
    foreach ($group in $available | Group-Object areaCodeSnapshot, rowLabelSnapshot, priceTierId, aisleSegmentSnapshot) {
        $ordered = @($group.Group | Sort-Object seatNumberSnapshot)
        $run = @()
        foreach ($seat in $ordered) {
            if ($run.Count -gt 0 -and [int]$seat.seatNumberSnapshot -ne [int]$run[-1].seatNumberSnapshot + 1) {
                if ($run.Count -eq 1) { $null = $singletons.Add([long]$run[0].id) }
                $run = @()
            }
            $run += $seat
        }
        if ($run.Count -eq 1) { $null = $singletons.Add([long]$run[0].id) }
    }
    # A set must remain one object even when it contains zero or one identity.
    return ,$singletons
}

function Assert-SeatDemoCandidates($Result, $Layout, $EnabledTierIds, [int]$Quantity, [string]$AreaCode, $TierId) {
    Assert-SeatDemo ($Result.reservationMade -eq $false -and $null -ne $Result.queriedAt) 'Recommendation query metadata is missing.'
    $null = [DateTimeOffset]::Parse($Result.queriedAt)
    Assert-SeatDemo ([int]$Result.quantity -eq $Quantity) 'Recommendation quantity differs from the request.'
    $noSelection = [Collections.Generic.HashSet[long]]::new()
    $oldSingletons = Get-SeatDemoSingletons $Layout $noSelection
    foreach ($candidate in @($Result.candidates)) {
        $selected = @($candidate.seats | Sort-Object seatNumberSnapshot)
        Assert-SeatDemo ($selected.Count -eq $Quantity -and -not [string]::IsNullOrWhiteSpace($candidate.reason)) 'Candidate size or reason is invalid.'
        $selectedIds = [Collections.Generic.HashSet[long]]::new()
        $first = $selected[0]
        $total = [decimal]0
        for ($index = 0; $index -lt $selected.Count; $index++) {
            $seat = $selected[$index]
            $actual = $Layout | Where-Object id -eq $seat.id | Select-Object -First 1
            Assert-SeatDemo ($null -ne $actual -and $selectedIds.Add([long]$seat.id)) 'Candidate contains unknown or duplicate seats.'
            Assert-SeatDemo ($actual.seatStatus -eq 'AVAILABLE' -and $EnabledTierIds.Contains([long]$actual.priceTierId)) 'Candidate contains an unavailable seat or disabled tier.'
            Assert-SeatDemo ($seat.areaCodeSnapshot -eq $first.areaCodeSnapshot -and $seat.rowLabelSnapshot -eq $first.rowLabelSnapshot -and
                $seat.priceTierId -eq $first.priceTierId -and $seat.aisleSegmentSnapshot -eq $first.aisleSegmentSnapshot -and
                [int]$seat.seatNumberSnapshot -eq [int]$first.seatNumberSnapshot + $index) 'Candidate crosses a continuity boundary.'
            if ($AreaCode) { Assert-SeatDemo ($seat.areaCodeSnapshot -eq $AreaCode) 'Candidate ignores the area filter.' }
            if ($null -ne $TierId) { Assert-SeatDemo ($seat.priceTierId -eq $TierId) 'Candidate ignores the tier filter.' }
            $total += [decimal]$seat.priceSnapshot
        }
        Assert-SeatDemo ($total -eq [decimal]$candidate.totalPrice -and [decimal]$candidate.centerDistanceSquared -ge 0) 'Candidate price or center distance is invalid.'
        $newSingletons = Get-SeatDemoSingletons $Layout $selectedIds
        foreach ($identity in $newSingletons) {
            Assert-SeatDemo ($oldSingletons.Contains($identity)) 'Candidate manufactures a new singleton.'
        }
    }
}

function Get-SeatDemoReadState([string]$Path) {
    # These persisted fields omit only the Clock response marker on the two new GET endpoints.
    return ([ordered]@{
        session = Invoke-Week2Api $BaseUrl GET $Path
        tiers = @(Invoke-Week2Api $BaseUrl GET "$Path/price-tiers")
        seats = @(Invoke-Week2Api $BaseUrl GET "$Path/seats")
    } | ConvertTo-Json -Depth 20 -Compress)
}

# The original rule seed stays DRAFT; recommendation demonstrations have their own session.
$seed = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace -BaseTime $BaseTime
$code = "SESSION-RECOMMEND-$Namespace"
$session = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$code" -AllowMissing
if ($null -eq $session) {
    $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seed $code)
    $session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
}
$path = "/api/sessions/$($session.id)"
$layout = @(Invoke-Week2Api $BaseUrl GET "$path/seats")
$seedLayout = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($seed.sessionId)/seats")
$tiers = @(Invoke-Week2Api $BaseUrl GET "$path/price-tiers")
$tierA = $tiers | Where-Object code -eq 'TIER-A' | Select-Object -First 1
$tierB = $tiers | Where-Object code -eq 'TIER-B' | Select-Object -First 1
Assert-SeatDemo ($session.venueId -eq $seed.venueId -and $session.eventId -eq $seed.eventId -and $session.forbidNewOrphan -and
    $session.salesStatus -in @('DRAFT', 'ON_SALE', 'STOPPED')) 'Recommendation fixture configuration has changed.'
Assert-SeatDemo ($layout.Count -eq 56 -and @($layout | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 54 -and
    $tiers.Count -eq 2 -and $null -ne $tierA -and $null -ne $tierB -and
    $tierA.name -eq 'A 档' -and [decimal]$tierA.price -eq 380 -and $tierB.name -eq 'B 档' -and [decimal]$tierB.price -eq 180) 'Recommendation fixture counts or prices have changed.'
foreach ($seat in $layout) {
    $source = $seedLayout | Where-Object sourceSeatTemplateId -eq $seat.sourceSeatTemplateId | Select-Object -First 1
    Assert-SeatDemo ($null -ne $source -and $seat.areaCodeSnapshot -eq $source.areaCodeSnapshot -and
        $seat.areaNameSnapshot -eq $source.areaNameSnapshot -and $seat.areaDisplayOrderSnapshot -eq $source.areaDisplayOrderSnapshot -and
        $seat.rowLabelSnapshot -eq $source.rowLabelSnapshot -and $seat.rowOrderSnapshot -eq $source.rowOrderSnapshot -and
        $seat.seatNumberSnapshot -eq $source.seatNumberSnapshot -and $seat.aisleSegmentSnapshot -eq $source.aisleSegmentSnapshot -and
        [decimal]$seat.coordinateXSnapshot -eq [decimal]$source.coordinateXSnapshot -and
        [decimal]$seat.coordinateYSnapshot -eq [decimal]$source.coordinateYSnapshot -and
        $seat.seatStatus -eq $source.seatStatus -and $seat.priceTierNameSnapshot -eq $source.priceTierNameSnapshot -and
        $seat.priceSnapshot -eq $source.priceSnapshot) 'Recommendation fixture snapshots have changed.'
}
$beforeSeatJson = $layout | ConvertTo-Json -Depth 12 -Compress
$restoreSales = $(if ($session.salesStatus -eq 'DRAFT') { 'ON_SALE' } else { $session.salesStatus })
$firstOpenedAt = $session.firstOpenedAt
$completed = $false
try {
    foreach ($tier in $tiers) {
        $null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tier.id)/enabled" @{ enabled = $true }
    }
    $opened = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = 'ON_SALE' }
    $firstOpenedAt = $opened.firstOpenedAt
    $beforeReads = Get-SeatDemoReadState $path
    $map = Invoke-Week2Api $BaseUrl GET "$path/seat-map"
    $mapSeats = @($map.areas | ForEach-Object { $_.rows | ForEach-Object { $_.seats } })
    Assert-SeatDemo ($map.sessionId -eq $session.id -and $map.salesStatus -eq 'ON_SALE' -and $map.forbidNewOrphan -and
        $map.reservationMade -eq $false -and $null -ne $map.queriedAt -and @($map.areas).Count -eq 2 -and
        @($map.areas | ForEach-Object { $_.rows }).Count -eq 5 -and $mapSeats.Count -eq 56 -and
        @($mapSeats | Where-Object selectable).Count -eq 54) 'Seat map is incomplete or has incorrect selectability.'
    $null = [DateTimeOffset]::Parse($map.queriedAt)
    $mapIds = [Collections.Generic.HashSet[long]]::new()
    foreach ($mappedSeat in $mapSeats) {
        $storedSeat = $layout | Where-Object id -eq $mappedSeat.id | Select-Object -First 1
        Assert-SeatDemo ($null -ne $storedSeat -and $mapIds.Add([long]$mappedSeat.id) -and
            $mappedSeat.seatNumber -eq $storedSeat.seatNumberSnapshot -and
            $mappedSeat.aisleSegment -eq $storedSeat.aisleSegmentSnapshot -and
            $mappedSeat.priceTierId -eq $storedSeat.priceTierId -and
            $mappedSeat.priceTierName -eq $storedSeat.priceTierNameSnapshot -and $mappedSeat.price -eq $storedSeat.priceSnapshot -and
            [decimal]$mappedSeat.coordinateX -eq [decimal]$storedSeat.coordinateXSnapshot -and
            [decimal]$mappedSeat.coordinateY -eq [decimal]$storedSeat.coordinateYSnapshot -and
            $mappedSeat.seatStatus -eq $storedSeat.seatStatus -and
            $mappedSeat.priceTierEnabled -eq ($null -ne $storedSeat.priceTierId) -and
            $mappedSeat.selectable -eq ($storedSeat.seatStatus -eq 'AVAILABLE')) 'Seat map contains duplicate identities or differs from persisted snapshots.'
    }
    $enabledIds = [Collections.Generic.HashSet[long]]::new()
    foreach ($tier in $tiers) { $null = $enabledIds.Add([long]$tier.id) }
    $result = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=2&limit=5"
    Assert-SeatDemo (@($result.candidates).Count -eq 5 -and [int]$result.diagnostics.availableSeatCount -eq 54 -and
        [int]$result.diagnostics.contiguousRunCount -eq 10 -and [int]$result.diagnostics.validCandidateCount -eq 25) 'Default recommendations do not match the fixed rule fixture.'
    Assert-SeatDemoCandidates $result $layout $enabledIds 2 '' $null
    $repeated = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=2&limit=5"
    Assert-SeatDemo (($result.candidates | ConvertTo-Json -Depth 20 -Compress) -eq
        ($repeated.candidates | ConvertTo-Json -Depth 20 -Compress)) 'Repeated recommendation order changed.'
    $repeatedMap = Invoke-Week2Api $BaseUrl GET "$path/seat-map"
    Assert-SeatDemo (($map.areas | ConvertTo-Json -Depth 20 -Compress) -eq
        ($repeatedMap.areas | ConvertTo-Json -Depth 20 -Compress)) 'Repeated seat map layout changed.'
    $filtered = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=2&areaCode=FLOOR-A&priceTierId=$($tierA.id)&limit=20"
    Assert-SeatDemo (@($filtered.candidates).Count -gt 0) 'Filtered recommendations are unexpectedly empty.'
    Assert-SeatDemoCandidates $filtered $layout $enabledIds 2 'FLOOR-A' $tierA.id
    $empty = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=7&limit=5"
    Assert-SeatDemo (@($empty.candidates).Count -eq 0 -and [int]$empty.diagnostics.tooShortRunCount -eq 10) 'Insufficient contiguous inventory did not return an explained empty result.'
    Assert-SeatDemo ((Get-SeatDemoReadState $path) -eq $beforeReads) 'Read-only queries changed persisted session, tier, or seat data.'

    $null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tierA.id)/enabled" @{ enabled = $false }
    $disabledMap = Invoke-Week2Api $BaseUrl GET "$path/seat-map"
    $floorSeats = @($disabledMap.areas | Where-Object code -eq 'FLOOR-A' | ForEach-Object { $_.rows | ForEach-Object { $_.seats } })
    Assert-SeatDemo (@($floorSeats | Where-Object selectable).Count -eq 0 -and
        @($floorSeats | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 35) 'Tier switch incorrectly changed inventory or left disabled seats selectable.'
    $disabledResult = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=2&priceTierId=$($tierA.id)&limit=5"
    Assert-SeatDemo (@($disabledResult.candidates).Count -eq 0) 'Disabled tier still has candidates.'
    $otherTierResult = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=2&limit=20"
    $null = $enabledIds.Remove([long]$tierA.id)
    Assert-SeatDemoCandidates $otherTierResult $layout $enabledIds 2 '' $null
    Assert-SeatDemo (@($otherTierResult.candidates).Count -eq 8) 'Enabled balcony tier candidates disappeared.'

    $null = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = 'STOPPED' }
    $pausedMap = Invoke-Week2Api $BaseUrl GET "$path/seat-map"
    $pausedSeats = @($pausedMap.areas | ForEach-Object { $_.rows | ForEach-Object { $_.seats } })
    Assert-SeatDemo ($pausedMap.salesStatus -eq 'STOPPED' -and @($pausedSeats | Where-Object selectable).Count -eq 0 -and $pausedSeats.Count -eq 56) 'Paused seat map lost its layout or purchase gate.'
    $conflict = $false
    try { $null = Invoke-Week2Api $BaseUrl GET "$path/recommendations?quantity=2" }
    catch {
        if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 409) { $conflict = $true }
        else { throw }
    }
    Assert-SeatDemo $conflict 'Paused session allowed recommendations.'
    $completed = $true
} finally {
    # Resume while tiers are enabled if needed, then restore each independently configured tier.
    foreach ($tier in $tiers) {
        $null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tier.id)/enabled" @{ enabled = $true }
    }
    $restored = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = $restoreSales }
    foreach ($tier in $tiers) {
        $null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tier.id)/enabled" @{ enabled = [bool]$tier.enabled }
    }
    $afterSeatJson = @(Invoke-Week2Api $BaseUrl GET "$path/seats") | ConvertTo-Json -Depth 12 -Compress
    Assert-SeatDemo ($afterSeatJson -eq $beforeSeatJson -and ($null -eq $firstOpenedAt -or $restored.firstOpenedAt -eq $firstOpenedAt)) 'Demonstration changed seat snapshots or first-opening time.'
}
if ($completed) {
    Write-Host 'PASS: complete map; 25 valid two-seat combinations; stable read-only queries; disabled tier excluded; STOPPED recommendations returned 409.'
    Write-Host "Session=$($session.id) ($code); snapshots=56; inventory AVAILABLE=54; final sales=$restoreSales; reservationMade=False."
    Write-Host 'Candidates remain unreserved; week 4 must re-read and atomically validate the complete selection during order creation.'
    [pscustomobject]@{ sessionId = $session.id; sessionCode = $code; namespace = $Namespace; seatCount = 56; validCandidateCount = 25; reservationMade = $false }
}
