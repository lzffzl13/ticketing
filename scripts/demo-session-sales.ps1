param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Z0-9][A-Z0-9_-]{0,15}$')][string]$Namespace = 'W2-SALES'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
# Keep the original DRAFT seed unchanged; all management steps use a separate session.
$seedData = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace
$code = "SESSION-MANAGED-$Namespace"
$managed = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$code" -AllowMissing
if ($null -eq $managed) {
    $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seedData $code)
    $managed = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
}
$path = "/api/sessions/$($managed.id)"
$tiers = @(Invoke-Week2Api $BaseUrl GET "$path/price-tiers")
$tierA = $tiers | Where-Object code -eq 'TIER-A' | Select-Object -First 1
$seats = @(Invoke-Week2Api $BaseUrl GET "$path/seats")
$disabled = $seats | Where-Object { $_.areaCodeSnapshot -eq 'FLOOR-A' -and $_.rowLabelSnapshot -eq 'C' -and $_.seatNumberSnapshot -eq 1 } | Select-Object -First 1
if ($null -eq $tierA -or $null -eq $disabled) { throw 'Management fixture is incomplete; existing data is preserved.' }
$configuration = @{
    startsAt = $managed.startsAt; admissionOpenAt = $managed.admissionOpenAt; admissionCloseAt = $managed.admissionCloseAt
    forbidNewOrphan = $true
    priceTiers = @(@{ id = $tierA.id; name = '调整后 A 档'; price = 420.00 })
    seats = @(@{ id = $disabled.id; priceTierId = $tierA.id; available = $true })
}
if ($managed.salesStatus -eq 'DRAFT') {
    $managed = Invoke-Week2Api $BaseUrl PUT "$path/configuration" $configuration
}
$seats = @(Invoke-Week2Api $BaseUrl GET "$path/seats")
if ($seats.Count -ne 56 -or @($seats | Where-Object seatStatus -eq 'AVAILABLE').Count -ne 55) {
    throw 'Configured fixture count/state has changed; use a new -Namespace.'
}
foreach ($seat in $seats | Where-Object priceTierId -eq $tierA.id) {
    if ([decimal]$seat.priceSnapshot -ne 420 -or $seat.priceTierNameSnapshot -ne '调整后 A 档') {
        throw 'Configured price snapshots do not match the demonstration; existing data is preserved.'
    }
}
$beforeJson = $seats | ConvertTo-Json -Depth 12 -Compress
$null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tierA.id)/enabled" @{ enabled = $true }
$opened = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = 'ON_SALE' }
$firstOpened = $opened.firstOpenedAt
if ($null -eq $firstOpened) { throw 'First opening timestamp is missing.' }
$repeated = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = 'ON_SALE' }
if ($repeated.firstOpenedAt -ne $firstOpened -or $repeated.updatedAt -ne $opened.updatedAt) {
    throw 'Repeated target state changed an opening timestamp.'
}
$null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tierA.id)/enabled" @{ enabled = $false }
$null = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = 'STOPPED' }
$frozen = $false
try { $null = Invoke-Week2Api $BaseUrl PUT "$path/configuration" $configuration }
catch {
    if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 409) { $frozen = $true }
    else { throw }
}
if (-not $frozen) { throw 'A paused session allowed editing previously opened configuration.' }
$null = Invoke-Week2Api $BaseUrl PUT "$path/price-tiers/$($tierA.id)/enabled" @{ enabled = $true }
$resumed = Invoke-Week2Api $BaseUrl PUT "$path/sales-status" @{ salesStatus = 'ON_SALE' }
$afterJson = @(Invoke-Week2Api $BaseUrl GET "$path/seats") | ConvertTo-Json -Depth 12 -Compress
if ($resumed.firstOpenedAt -ne $firstOpened -or $beforeJson -ne $afterJson) {
    throw 'Sales/tier switches changed the first opening or seat snapshots.'
}
Write-Host "PASS: configured A tier=420; 55 available; opened/paused/resumed; paused edits returned 409; switches kept snapshots."
$firstOpenedUtc = ([DateTimeOffset]$firstOpened).UtcDateTime.ToString('o')
Write-Host "Session=$($managed.id) ($code); firstOpenedAt (UTC)=$firstOpenedUtc; final state=ON_SALE."
