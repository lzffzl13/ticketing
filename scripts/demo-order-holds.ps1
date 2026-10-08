param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^W4[A-Z0-9_-]{0,14}$')][string]$Namespace = 'W4',
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl

function Assert-OrderDemo([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "$Message Existing orders and seats are preserved; use a new W4 -Namespace for an independent fixture."
    }
}

function Invoke-OrderDemoRequest {
    param([string]$Method, [string]$Path, $Login, $Payload, [string]$IdempotencyKey, [int[]]$ExpectedStatus = @(200))
    $taskArguments = @{
        Uri = "$($BaseUrl.TrimEnd('/'))$Path"; Method = $Method; ErrorAction = 'Stop'; UseBasicParsing = $true
        Headers = @{ Authorization = "Bearer $($Login.token)" }
    }
    if ($IdempotencyKey) { $taskArguments.Headers['Idempotency-Key'] = $IdempotencyKey }
    if ($null -ne $Payload) {
        $taskArguments.ContentType = 'application/json; charset=utf-8'
        $taskArguments.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 20 -Compress))
    }
    try {
        $taskResponse = Invoke-WebRequest @taskArguments
        $taskStatus = [int]$taskResponse.StatusCode
        $taskEnvelope = $taskResponse.Content | ConvertFrom-Json
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $taskStatus = [int]$_.Exception.Response.StatusCode
        # Authentication or unexpected server failures remain real failures.
        if ($taskStatus -notin $ExpectedStatus) { throw }
        $taskErrorJson = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($taskErrorJson)) {
            if ($null -ne $_.Exception.Response.Content) {
                $taskErrorJson = $_.Exception.Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            } else {
                $taskReader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
                try { $taskErrorJson = $taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
            }
        }
        $taskEnvelope = $taskErrorJson | ConvertFrom-Json
    }
    Assert-OrderDemo ($taskStatus -in $ExpectedStatus) "Unexpected HTTP status $taskStatus at $Path."
    return [pscustomobject]@{ status = $taskStatus; envelope = $taskEnvelope; data = $taskEnvelope.data }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDirectory = Join-Path $projectRoot '.local/demo-order-holds'
$stateFile = Join-Path $stateDirectory "$Namespace.json"
$requestState = $null
if (Test-Path -LiteralPath $stateFile) {
    $requestState = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
}

# A separate immutable DRAFT seed is reused; trades occur only in SESSION-ORDER-W4*.
$seed = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace -BaseTime $BaseTime
$sessionCode = "SESSION-ORDER-$Namespace"
$session = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$sessionCode" -AllowMissing
if ($null -eq $session) {
    Assert-OrderDemo ($null -eq $requestState) 'The saved request references a missing session.'
    $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seed $sessionCode)
    $session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
}
Assert-OrderDemo ($session.venueId -eq $seed.venueId -and $session.eventId -eq $seed.eventId -and $session.forbidNewOrphan -and
    $session.salesStatus -in @('DRAFT', 'ON_SALE')) 'Order fixture configuration has changed.'
Assert-OrderDemo (([DateTimeOffset]$session.startsAt) -eq ([DateTimeOffset]$seed.baseTime).AddDays(30)) 'Order fixture time has changed.'
$sessionPath = "/api/sessions/$($session.id)"
$layout = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
$tiers = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/price-tiers")
Assert-OrderDemo ($layout.Count -eq 56 -and $tiers.Count -eq 2 -and @($tiers | Where-Object { -not $_.enabled }).Count -eq 0) 'Order fixture seats or tiers have changed.'
if ($session.salesStatus -eq 'DRAFT') {
    Assert-OrderDemo ($null -eq $requestState) 'The saved request references an unopened session.'
    $session = Invoke-Week2Api $BaseUrl PUT "$sessionPath/sales-status" @{ salesStatus = 'ON_SALE' }
}

$consumer1 = Get-Week2Login $BaseUrl 'consumer1'
$consumer2 = Get-Week2Login $BaseUrl 'consumer2'
Assert-OrderDemo ($consumer1.account.role -eq 'CONSUMER' -and $consumer2.account.role -eq 'CONSUMER' -and
    $consumer1.account.id -ne $consumer2.account.id) 'The consumer accounts are invalid.'

$key = "order-$Namespace"
if ($null -eq $requestState) {
    Assert-OrderDemo (@($layout | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 54) 'A fixture without a saved request already contains occupied seats.'
    $beforeRecommendation = $layout | ConvertTo-Json -Depth 20 -Compress
    $recommendations = Invoke-Week2Api $BaseUrl GET "$sessionPath/recommendations?quantity=2&limit=5"
    Assert-OrderDemo (-not $recommendations.reservationMade -and @($recommendations.candidates).Count -gt 0) 'No read-only recommendation is available.'
    $candidate = @($recommendations.candidates)[0]
    $seatIds = @($candidate.seats | ForEach-Object { [long]$_.id } | Sort-Object)
    Assert-OrderDemo ($seatIds.Count -eq 2) 'The recommendation has an unexpected seat count.'
    $afterRecommendation = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats") | ConvertTo-Json -Depth 20 -Compress
    Assert-OrderDemo ($beforeRecommendation -eq $afterRecommendation) 'The recommendation changed inventory.'
    $requestState = [pscustomobject]@{
        namespace = $Namespace; sessionCode = $sessionCode; sessionId = [long]$session.id
        idempotencyKey = $key; selectionMode = 'RECOMMENDED'; seatIds = $seatIds
        orderId = $null; paymentExpiresAt = $null; holdExpiresAt = $null
    }
    $null = New-Item -ItemType Directory -Path $stateDirectory -Force
    # Save the purchase intent before POST, so a lost response can be retried with the same request.
    [IO.File]::WriteAllText($stateFile, ($requestState | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
    Write-Host "PASS recommendation: namespace=$Namespace; seats=$($seatIds -join ','); inventory unchanged."
} else {
    Assert-OrderDemo ($requestState.namespace -eq $Namespace -and $requestState.sessionCode -eq $sessionCode -and
        $requestState.sessionId -eq $session.id -and $requestState.idempotencyKey -eq $key -and
        $requestState.selectionMode -eq 'RECOMMENDED' -and @($requestState.seatIds).Count -eq 2) 'Saved request does not match this fixture.'
    Write-Host "Reusing saved purchase intent: namespace=$Namespace; session=$($session.id); key=$key."
}

$payload = @{ sessionId = [long]$session.id; selectionMode = 'RECOMMENDED'; seatIds = @($requestState.seatIds | ForEach-Object { [long]$_ }) }
$createdOrder = Invoke-OrderDemoRequest POST '/api/orders' $consumer1 $payload $key @(201, 200)
$order = $createdOrder.data
Assert-OrderDemo ($order.customerId -eq $consumer1.account.id -and $order.sessionId -eq $session.id -and
    $order.selectionMode -eq 'RECOMMENDED' -and $order.orderStatus -eq 'PENDING_PAYMENT' -and
    $order.hold.status -eq 'ACTIVE' -and @($order.items).Count -eq 2 -and
    $order.paymentExpiresAt -eq $order.hold.expiresAt) 'The order, items and active hold do not agree.'
$returnedIds = @($order.items | ForEach-Object { [long]$_.sessionSeatId } | Sort-Object)
Assert-OrderDemo (($returnedIds -join ',') -eq (@($payload.seatIds | Sort-Object) -join ',')) 'Order items differ from the requested seats.'
if ($null -ne $requestState.orderId) {
    Assert-OrderDemo ($order.id -eq $requestState.orderId -and $order.paymentExpiresAt -eq $requestState.paymentExpiresAt -and
        $order.hold.expiresAt -eq $requestState.holdExpiresAt -and $createdOrder.status -eq 200) 'Repeated demo changed the original order or deadlines.'
}
$requestState.orderId = $order.id
$requestState.paymentExpiresAt = $order.paymentExpiresAt
$requestState.holdExpiresAt = $order.hold.expiresAt
[IO.File]::WriteAllText($stateFile, ($requestState | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
Write-Host "PASS order: HTTP $($createdOrder.status); id=$($order.id); status=$($order.orderStatus); hold=$($order.hold.status); total=$($order.totalAmount)."

$replay = Invoke-OrderDemoRequest POST '/api/orders' $consumer1 $payload $key
Assert-OrderDemo ($replay.data.id -eq $order.id -and $replay.data.paymentExpiresAt -eq $order.paymentExpiresAt -and
    $replay.data.hold.id -eq $order.hold.id -and $replay.data.hold.expiresAt -eq $order.hold.expiresAt) 'Idempotent replay created another order or renewed its hold.'
Write-Host "PASS replay: HTTP 200; same order and hold; paymentExpiresAt (UTC)=$($order.paymentExpiresAt)."

$ownQuery = Invoke-OrderDemoRequest GET "/api/orders/$($order.id)" $consumer1
Assert-OrderDemo ($ownQuery.data.id -eq $order.id -and $ownQuery.data.customerId -eq $consumer1.account.id) 'The owner could not read the order.'
$competition = Invoke-OrderDemoRequest POST '/api/orders' $consumer2 $payload "compete-$Namespace" @(409)
Assert-OrderDemo ($competition.envelope.code -eq 'SEAT_NOT_AVAILABLE') 'The overlapping purchase failed for an unexpected reason.'
Write-Host 'PASS overlapping purchase: consumer2 receives HTTP 409 SEAT_NOT_AVAILABLE; original hold is retained.'
$foreignQuery = Invoke-OrderDemoRequest GET "/api/orders/$($order.id)" $consumer2 -ExpectedStatus @(404)
Assert-OrderDemo ($foreignQuery.envelope.code -eq 'NOT_FOUND') 'A foreign order was not hidden as NOT_FOUND.'
Write-Host 'PASS ownership: consumer1 reads the order; consumer2 receives HTTP 404.'

$heldLayout = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
$heldSeats = @($heldLayout | Where-Object { $_.id -in $payload.seatIds })
Assert-OrderDemo ($heldSeats.Count -eq 2 -and @($heldSeats | Where-Object seatStatus -ne 'HELD').Count -eq 0 -and
    @($heldLayout | Where-Object seatStatus -eq 'HELD').Count -eq 2 -and
    @($heldLayout | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 52) 'The final inventory does not contain exactly one two-seat hold.'
Write-Host "PASS inventory: HELD=2; AVAILABLE=52; UNAVAILABLE=2; session=$sessionCode."
Write-Host 'Demo complete. No payment, cancellation or automatic expiry is implemented in this first loop; retained trades are not deleted.'
[pscustomobject]@{
    namespace = $Namespace; sessionId = $session.id; orderId = $order.id; holdId = $order.hold.id
    paymentExpiresAt = $order.paymentExpiresAt; requestFile = $stateFile
}
