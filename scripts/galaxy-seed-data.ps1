# Fixed virtual layout. Coordinates are logical units, not physical measurements.
function Get-GalaxyAreaSpecifications {
    @(
        @{ code = 'ORCHESTRA'; name = '一层池座'; displayOrder = 0; labels = 'ABCDEFGH'; counts = @(14,16,18,18,20,20,22,22) },
        @{ code = 'BALCONY'; name = '二层楼座'; displayOrder = 1; labels = 'ABCDEF'; counts = @(16,16,16,16,16,16) }
    )
}

function Get-GalaxyPriceSpecifications {
    @(
        @{ code = 'PREMIUM'; name = '优选票'; price = 580.00 },
        @{ code = 'STANDARD'; name = '标准票'; price = 380.00 },
        @{ code = 'ECONOMY'; name = '楼座票'; price = 280.00 }
    )
}

function Get-GalaxySessionSpecifications {
    @(
        @{ code = 'SESSION-DRAFT'; offsetMinutes = 43200; salesStatus = 'DRAFT' },
        @{ code = 'SESSION-ON-SALE'; offsetMinutes = 10080; salesStatus = 'ON_SALE' },
        @{ code = 'SESSION-CHECKIN'; offsetMinutes = 30; salesStatus = 'ON_SALE' }
    )
}

function New-GalaxySeatSpecifications($Area) {
    for ($row = 0; $row -lt $Area.labels.Length; $row++) {
        $count = $Area.counts[$row]
        for ($number = 1; $number -le $count; $number++) {
            $left = $number -le $count / 2
            # Center each row at x=0; moving each half 0.75 adds a 1.5-unit aisle gap.
            $x = [decimal]$number - ([decimal]$count + 1) / 2 + $(if ($left) { -0.75 } else { 0.75 })
            @{
                rowLabel = [string]$Area.labels[$row]; rowOrder = $row; seatNumber = $number
                coordinateX = $x; coordinateY = $row; aisleSegment = $(if ($left) { 'L' } else { 'R' })
                enabled = -not ($row -eq $Area.labels.Length - 1 -and ($number -eq 1 -or $number -eq $count))
            }
        }
    }
}

function Get-GalaxyTierCode([string]$AreaCode, [string]$RowLabel) {
    if ($AreaCode -eq 'BALCONY') { return 'ECONOMY' }
    if ($AreaCode -eq 'ORCHESTRA') {
        if ($RowLabel -in @('A','B','C')) { return 'PREMIUM' }
        return 'STANDARD'
    }
    throw "Unknown galaxy area: $AreaCode"
}

function Assert-GalaxySeedCondition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "$Message Existing data is preserved. Use a new -Namespace for an independent fixture."
    }
}

function Assert-GalaxyTemplates($Areas, $Templates, [bool]$LayoutMustBeComplete) {
    $specs = @(Get-GalaxyAreaSpecifications)
    Assert-GalaxySeedCondition (@($Areas | Where-Object { $_.code -notin @('ORCHESTRA','BALCONY') }).Count -eq 0) 'Unexpected galaxy area.'
    foreach ($spec in $specs) {
        $area = $Areas | Where-Object code -eq $spec.code | Select-Object -First 1
        if ($null -eq $area) {
            Assert-GalaxySeedCondition (-not $LayoutMustBeComplete) 'An existing session is missing its source area.'
            continue
        }
        Assert-GalaxySeedCondition ($area.name -eq $spec.name -and $area.displayOrder -eq $spec.displayOrder) 'Galaxy area has changed.'
        $actualSeats = @($Templates | Where-Object areaId -eq $area.id)
        $expectedSeats = @(New-GalaxySeatSpecifications $spec)
        if ($actualSeats.Count -eq 0 -and -not $LayoutMustBeComplete) { continue }
        Assert-GalaxySeedCondition ($actualSeats.Count -eq $expectedSeats.Count) 'Galaxy template count has changed.'
        foreach ($expected in $expectedSeats) {
            $actual = $actualSeats | Where-Object { $_.rowLabel -eq $expected.rowLabel -and $_.seatNumber -eq $expected.seatNumber } | Select-Object -First 1
            Assert-GalaxySeedCondition ($null -ne $actual -and $actual.rowOrder -eq $expected.rowOrder -and
                [decimal]$actual.coordinateX -eq [decimal]$expected.coordinateX -and [decimal]$actual.coordinateY -eq [decimal]$expected.coordinateY -and
                $actual.aisleSegment -eq $expected.aisleSegment -and $actual.enabled -eq $expected.enabled) 'Galaxy template values have changed.'
        }
    }
}

function Assert-GalaxySession($Session, $Spec, $Venue, $Event, [DateTimeOffset]$BaseTime, $Templates, [string]$BaseUrl) {
    $start = $BaseTime.AddMinutes($Spec.offsetMinutes)
    $pendingOpening = $Spec.salesStatus -eq 'ON_SALE' -and $Session.salesStatus -eq 'DRAFT' -and $null -eq $Session.firstOpenedAt
    Assert-GalaxySeedCondition ($Session.venueId -eq $Venue.id -and $Session.eventId -eq $Event.id -and
        $Session.venueNameSnapshot -eq $Venue.name -and $Session.eventNameSnapshot -eq $Event.name -and $Session.forbidNewOrphan -and
        ([DateTimeOffset]$Session.startsAt) -eq $start -and ([DateTimeOffset]$Session.admissionOpenAt) -eq $start.AddHours(-1) -and
        ([DateTimeOffset]$Session.admissionCloseAt) -eq $start.AddMinutes(-5) -and
        ($Session.salesStatus -eq $Spec.salesStatus -or $pendingOpening)) 'Galaxy session configuration has changed.'
    $tiers = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($Session.id)/price-tiers")
    Assert-GalaxySeedCondition ($tiers.Count -eq 3) 'Galaxy tier count has changed.'
    $tiersByCode = @{}
    foreach ($expected in Get-GalaxyPriceSpecifications) {
        $actual = $tiers | Where-Object code -eq $expected.code | Select-Object -First 1
        Assert-GalaxySeedCondition ($null -ne $actual -and $actual.sessionId -eq $Session.id -and $actual.name -eq $expected.name -and
            [decimal]$actual.price -eq [decimal]$expected.price -and $actual.enabled) 'Galaxy tier price/name/switch has changed.'
        $tiersByCode[$expected.code] = $actual
    }
    $snapshots = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($Session.id)/seats")
    Assert-GalaxySeedCondition ($snapshots.Count -eq 246 -and @($snapshots | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 242) 'Galaxy snapshot count/state has changed.'
    $templatesById = @{}
    foreach ($template in $Templates) { $templatesById[$template.id] = $template }
    foreach ($seat in $snapshots) {
        $source = $templatesById[$seat.sourceSeatTemplateId]
        Assert-GalaxySeedCondition ($null -ne $source -and $seat.sessionId -eq $Session.id -and $seat.sourceAreaId -eq $source.areaId -and
            $seat.areaCodeSnapshot -eq $source.areaCode -and $seat.areaNameSnapshot -eq $source.areaName -and
            $seat.areaDisplayOrderSnapshot -eq $source.areaDisplayOrder -and $seat.rowLabelSnapshot -eq $source.rowLabel -and
            $seat.rowOrderSnapshot -eq $source.rowOrder -and $seat.seatNumberSnapshot -eq $source.seatNumber -and
            [decimal]$seat.coordinateXSnapshot -eq [decimal]$source.coordinateX -and [decimal]$seat.coordinateYSnapshot -eq [decimal]$source.coordinateY -and
            $seat.aisleSegmentSnapshot -eq $source.aisleSegment) 'Galaxy snapshot layout has changed.'
        if ($source.enabled) {
            $tier = $tiersByCode[(Get-GalaxyTierCode $source.areaCode $source.rowLabel)]
            Assert-GalaxySeedCondition ($seat.seatStatus -eq 'AVAILABLE' -and $seat.priceTierId -eq $tier.id -and
                $seat.priceTierNameSnapshot -eq $tier.name -and [decimal]$seat.priceSnapshot -eq [decimal]$tier.price) 'Galaxy snapshot price has changed.'
        } else {
            Assert-GalaxySeedCondition ($seat.seatStatus -eq 'UNAVAILABLE' -and $null -eq $seat.priceTierId -and
                $null -eq $seat.priceTierNameSnapshot -and $null -eq $seat.priceSnapshot) 'Disabled galaxy snapshot has changed.'
        }
    }
}
