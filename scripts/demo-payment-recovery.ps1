param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^W5R[A-Z0-9_-]{0,12}$')][string]$Namespace = 'W5R',
    [ValidateSet('AutoPayment', 'Timeout')][string]$Scenario = 'AutoPayment',
    [ValidateRange(1, 60)][int]$MaxWaitSeconds = 30,
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$BaseUrl = $BaseUrl.TrimEnd('/')

function Assert-RecoveryDemo([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "$Message Data is preserved; inspect the original order or use a new W5R namespace." }
}
function Save-RecoveryDemo {
    $temporaryFile = "$stateFile.tmp"
    [IO.File]::WriteAllText($temporaryFile, ($state | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryFile -Destination $stateFile -Force
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDirectory = Join-Path $projectRoot '.local/demo-payment-recovery'
$null = New-Item -ItemType Directory -Path $stateDirectory -Force
$stateFile = Join-Path $stateDirectory "$Namespace-$Scenario.json"
$state = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } else { $null }
$seed = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace -BaseTime $BaseTime
$sessionCode = "SESSION-RECOVERY-$Namespace-$($Scenario.ToUpperInvariant())"
$session = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$sessionCode" -AllowMissing
if ($null -eq $session) {
    Assert-RecoveryDemo ($null -eq $state) 'Saved intent references a missing session.'
    $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seed $sessionCode)
    $session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
}
Assert-RecoveryDemo ($session.venueId -eq $seed.venueId -and $session.eventId -eq $seed.eventId -and
    $session.salesStatus -in @('DRAFT', 'ON_SALE') -and $session.forbidNewOrphan -and
    ([DateTimeOffset]$session.startsAt) -eq ([DateTimeOffset]$seed.baseTime).AddDays(30)) 'Session configuration changed.'
if ($session.salesStatus -eq 'DRAFT') {
    $null = Invoke-Week2Api $BaseUrl PUT "/api/sessions/$($session.id)/sales-status" @{ salesStatus = 'ON_SALE' }
}
$consumer = Get-Week2Login $BaseUrl 'consumer1'
$consumerHeaders = @{ Authorization = "Bearer $($consumer.token)" }
if ($null -eq $state) {
    $recommendation = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($session.id)/recommendations?quantity=2&limit=1"
    Assert-RecoveryDemo (@($recommendation.candidates).Count -gt 0) 'No two-seat candidate is available.'
    $state = [pscustomobject]@{
        version = 1; baseUrl = $BaseUrl; sessionId = [long]$session.id; scenario = $Scenario
        orderKey = "buy-$Namespace-$Scenario"; paymentKey = "pay-$Namespace-$Scenario"
        seatIds = @(@($recommendation.candidates)[0].seats | ForEach-Object { [long]$_.id } | Sort-Object)
        orderId = $null; paymentExpiresAt = $null; paymentNumber = $null; ticketNumbers = @()
    }
    Save-RecoveryDemo # Preserve the purchase intent before POST, including a lost-response retry.
}
Assert-RecoveryDemo ($state.version -eq 1 -and $state.baseUrl -eq $BaseUrl -and
    $state.sessionId -eq $session.id -and $state.scenario -eq $Scenario -and @($state.seatIds).Count -eq 2) 'Saved intent has a different binding.'
$orderHeaders = @{ Authorization = $consumerHeaders.Authorization; 'Idempotency-Key' = $state.orderKey }
$order = Invoke-Week2Api $BaseUrl POST '/api/orders' @{
    sessionId = [long]$session.id; selectionMode = 'RECOMMENDED'; seatIds = @($state.seatIds)
} -Headers $orderHeaders
if ($null -ne $state.orderId) {
    Assert-RecoveryDemo ($state.orderId -eq $order.id -and $state.paymentExpiresAt -eq $order.paymentExpiresAt) 'Replay changed the original order or deadline.'
}
Assert-RecoveryDemo (($state.seatIds -join ',') -eq ((@($order.items | ForEach-Object { [long]$_.sessionSeatId } | Sort-Object)) -join ',')) 'Order seats changed.'
$state.orderId = $order.id
$state.paymentExpiresAt = $order.paymentExpiresAt
Save-RecoveryDemo

if ($Scenario -eq 'Timeout') {
    $attempts = @(Invoke-Week2Api $BaseUrl GET "/api/orders/$($order.id)/payments" -Headers $consumerHeaders)
    Assert-RecoveryDemo ($attempts.Count -eq 0) 'Timeout fixture must never have a payment attempt.'
    if ([DateTimeOffset]::UtcNow -lt [DateTimeOffset]$order.paymentExpiresAt) {
        Assert-RecoveryDemo ($order.orderStatus -eq 'PENDING_PAYMENT' -and $order.hold.status -eq 'ACTIVE') 'Order changed before its deadline.'
        $displayDeadline = ([DateTimeOffset]$order.paymentExpiresAt).ToOffset([TimeSpan]::FromHours(8)).ToString('yyyy-MM-dd HH:mm:ss.ffffff zzz')
        Write-Host "READY timeout fixture: order=$($order.id); original deadline=$displayDeadline (Asia/Shanghai)."
        Write-Host "Run the same command after that deadline to verify automatic cancellation. No deadline was shortened."
        return [pscustomobject]@{ stage = 'WAITING_ORIGINAL_DEADLINE'; orderId = $order.id; paymentExpiresAt = $order.paymentExpiresAt; requestFile = $stateFile }
    }
    $expected = 'CANCELLED'
} else {
    Assert-RecoveryDemo ($order.orderStatus -in @('PENDING_PAYMENT', 'PAID')) 'Automatic payment fixture is no longer pending or paid.'
    $paymentHeaders = @{ Authorization = $consumerHeaders.Authorization; 'Idempotency-Key' = $state.paymentKey }
    $payment = Invoke-Week2Api $BaseUrl POST "/api/orders/$($order.id)/payments" -Headers $paymentHeaders
    Assert-RecoveryDemo ($payment.paymentStatus -in @('PROCESSING', 'SUCCEEDED')) 'Original payment was not accepted.'
    if ($null -ne $state.paymentNumber) { Assert-RecoveryDemo ($state.paymentNumber -eq $payment.paymentNumber) 'Payment identity changed.' }
    $state.paymentNumber = $payment.paymentNumber
    Save-RecoveryDemo
    $encodedNumber = [Uri]::EscapeDataString($payment.paymentNumber)
    $channel = Invoke-Week2Api $BaseUrl POST "/api/operations/payments/$encodedNumber/succeed"
    Assert-RecoveryDemo ($channel.paymentStatus -eq 'SUCCEEDED') 'Channel success was not saved.'
    # Deliberately do not deliver a notification or call the operator recovery endpoint.
    $expected = 'PAID'
}

$waitUntil = [DateTimeOffset]::UtcNow.AddSeconds($MaxWaitSeconds)
do {
    $current = Invoke-Week2Api $BaseUrl GET "/api/orders/$($order.id)" -Headers $consumerHeaders
    if ($current.orderStatus -eq $expected) { break }
    Assert-RecoveryDemo ($current.orderStatus -eq 'PENDING_PAYMENT') 'Unexpected terminal order state.'
    Start-Sleep -Milliseconds 250
} while ([DateTimeOffset]::UtcNow -lt $waitUntil)
Assert-RecoveryDemo ($current.orderStatus -eq $expected -and $current.paymentExpiresAt -eq $state.paymentExpiresAt) 'Automatic task did not finish within the observation window; check configuration, leases and recovery state.'
$tickets = @(Invoke-Week2Api $BaseUrl GET "/api/orders/$($order.id)/tickets" -Headers $consumerHeaders)
$selected = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($session.id)/seats" | Where-Object { $_.id -in $state.seatIds })
Assert-RecoveryDemo ($selected.Count -eq 2) 'Fixture seats are missing.'
if ($Scenario -eq 'Timeout') {
    Assert-RecoveryDemo ($current.hold.status -eq 'EXPIRED' -and $tickets.Count -eq 0 -and
        @($selected | Where-Object seatStatus -ne 'AVAILABLE').Count -eq 0) 'Timeout did not atomically release both seats without tickets.'
    Write-Host "PASS automatic timeout: order=$($order.id); CANCELLED + EXPIRED + AVAILABLE=2 + tickets=0."
} else {
    $notices = @(Invoke-Week2Api $BaseUrl GET "/api/operations/payments/$encodedNumber/notifications" -Headers @{ Authorization = "Bearer $((Get-Week2Login $BaseUrl 'operator1').token)" })
    $successNotice = $notices | Where-Object observedStatus -eq 'SUCCEEDED' | Select-Object -First 1
    Assert-RecoveryDemo ($null -ne $successNotice -and $null -eq $successNotice.deliveredAt -and
        $current.hold.status -eq 'CONSUMED' -and $tickets.Count -eq 2 -and
        @($tickets | Where-Object ticketStatus -ne 'VALID').Count -eq 0 -and
        @($selected | Where-Object seatStatus -ne 'SOLD').Count -eq 0) 'Automatic recovery did not issue exactly two tickets before notification delivery.'
    $numbers = @($tickets | ForEach-Object ticketNumber | Sort-Object)
    if (@($state.ticketNumbers).Count -gt 0) { Assert-RecoveryDemo (($numbers -join ',') -eq ($state.ticketNumbers -join ',')) 'Replay replaced the original tickets.' }
    $state.ticketNumbers = $numbers
    Save-RecoveryDemo
    Write-Host "PASS automatic recovery: order=$($order.id); PAID + CONSUMED + SOLD=2 + VALID=2; success notice remains undelivered."
}
return [pscustomobject]@{ stage = $expected; orderId = $order.id; paymentNumber = $state.paymentNumber; ticketNumbers = @($state.ticketNumbers); requestFile = $stateFile }
