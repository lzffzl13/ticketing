param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^W4P[A-Z0-9_-]{0,12}$')][string]$Namespace = 'W4P',
    [DateTimeOffset]$BaseTime = [DateTimeOffset]::UtcNow,
    [switch]$PauseAfterChannelSuccess,
    [switch]$RecoverBeforeDelivery
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$BaseUrl = $BaseUrl.TrimEnd('/')
if ($PauseAfterChannelSuccess -and $RecoverBeforeDelivery) {
    throw 'PauseAfterChannelSuccess and RecoverBeforeDelivery describe separate runs; choose one per invocation.'
}

function Assert-PaymentDemo([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "$Message Existing payments, orders and seats are preserved. Resume this namespace after fixing the cause, or use a new W4P -Namespace."
    }
}

function Invoke-PaymentDemoRequest {
    param([string]$Method, [string]$Path, $Login, $Payload, [string]$Key, [int[]]$ExpectedStatus = @(200))
    $taskArguments = @{
        Uri = "$BaseUrl$Path"; Method = $Method; ErrorAction = 'Stop'; UseBasicParsing = $true
        Headers = @{ Authorization = "Bearer $($Login.token)" }
    }
    if ($Key) { $taskArguments.Headers['Idempotency-Key'] = $Key }
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
    Assert-PaymentDemo ($taskStatus -in $ExpectedStatus) "Unexpected HTTP $taskStatus at $Path."
    return [pscustomobject]@{ status = $taskStatus; envelope = $taskEnvelope; data = $taskEnvelope.data }
}

function Save-PaymentDemoState {
    $temporaryFile = "$stateFile.tmp"
    [IO.File]::WriteAllText($temporaryFile, ($state | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryFile -Destination $stateFile -Force
}

function Get-PaymentDemoOrder([string]$Slot, [string]$Key) {
    $request = $state.$Slot
    if ($null -eq $request) {
        $recommendation = Invoke-Week2Api $BaseUrl GET "$sessionPath/recommendations?quantity=2&limit=5"
        Assert-PaymentDemo (-not $recommendation.reservationMade -and @($recommendation.candidates).Count -gt 0) 'No two-seat recommendation is available.'
        $seatIds = @(@($recommendation.candidates)[0].seats | ForEach-Object { [long]$_.id } | Sort-Object)
        Assert-PaymentDemo ($seatIds.Count -eq 2) 'Recommendation must contain two seats.'
        $request = [pscustomobject]@{
            orderKey = $Key; seatIds = $seatIds; orderId = $null; paymentExpiresAt = $null
            paymentKey = "payment-$Key"; paymentNumber = $null; ticketNumbers = @()
        }
        $state.$Slot = $request
        # Save the intent before POST: a lost response is retried with the original request.
        Save-PaymentDemoState
    }
    Assert-PaymentDemo ($request.orderKey -eq $Key -and @($request.seatIds).Count -eq 2) 'Saved purchase intent does not match this demo.'
    $payload = @{ sessionId = [long]$session.id; selectionMode = 'RECOMMENDED'; seatIds = @($request.seatIds | ForEach-Object { [long]$_ }) }
    $response = Invoke-PaymentDemoRequest POST '/api/orders' $consumer $payload $request.orderKey @(200, 201)
    $order = $response.data
    Assert-PaymentDemo ($order.customerId -eq $consumer.account.id -and $order.sessionId -eq $session.id -and
        @($order.items).Count -eq 2 -and $order.paymentExpiresAt -eq $order.hold.expiresAt) 'Order ownership, items or original deadline differ.'
    $returnedSeats = @($order.items | ForEach-Object { [long]$_.sessionSeatId } | Sort-Object)
    Assert-PaymentDemo (($returnedSeats -join ',') -eq (@($request.seatIds | Sort-Object) -join ',')) 'Order items differ from the saved seats.'
    if ($null -ne $request.orderId) {
        Assert-PaymentDemo ($request.orderId -eq $order.id -and $request.paymentExpiresAt -eq $order.paymentExpiresAt -and
            $response.status -eq 200) 'Order replay changed identity or deadline.'
    }
    $request.orderId = $order.id
    $request.paymentExpiresAt = $order.paymentExpiresAt
    Save-PaymentDemoState
    return $order
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDirectory = Join-Path $projectRoot '.local/demo-payments'
$stateFile = Join-Path $stateDirectory "$Namespace.json"
$null = New-Item -ItemType Directory -Path $stateDirectory -Force
$state = $null
if (Test-Path -LiteralPath $stateFile) { $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json }

# Immutable rule seed remains DRAFT; only this independent SESSION-PAYMENT namespace is traded.
$seed = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace -BaseTime $BaseTime
$sessionCode = "SESSION-PAYMENT-$Namespace"
$session = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$sessionCode" -AllowMissing
if ($null -eq $session) {
    Assert-PaymentDemo ($null -eq $state) 'Saved demo state references a missing session.'
    $createdSession = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seed $sessionCode)
    $session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($createdSession.id)"
}
Assert-PaymentDemo ($session.venueId -eq $seed.venueId -and $session.eventId -eq $seed.eventId -and
    $session.forbidNewOrphan -and $session.salesStatus -in @('DRAFT', 'ON_SALE')) 'Payment session configuration changed.'
Assert-PaymentDemo (([DateTimeOffset]$session.startsAt) -eq ([DateTimeOffset]$seed.baseTime).AddDays(30)) 'Payment session time changed.'
$sessionPath = "/api/sessions/$($session.id)"
$layout = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
$tiers = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/price-tiers")
Assert-PaymentDemo ($layout.Count -eq 56 -and $tiers.Count -eq 2 -and @($tiers | Where-Object { -not $_.enabled }).Count -eq 0) 'Fixture seats or price tiers changed.'
if ($null -eq $state) {
    Assert-PaymentDemo (@($layout | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 54) 'Fixture already contains trades without a saved intent.'
    $state = [pscustomobject]@{
        version = 1; namespace = $Namespace; baseUrl = $BaseUrl; sessionCode = $sessionCode
        sessionId = [long]$session.id; normal = $null; cancellation = $null
    }
    Save-PaymentDemoState
}
Assert-PaymentDemo ($state.version -eq 1 -and $state.namespace -eq $Namespace -and $state.baseUrl -eq $BaseUrl -and
    $state.sessionId -eq $session.id -and $state.sessionCode -eq $sessionCode) 'Saved demo state belongs to a different fixture or application URL.'
if ($session.salesStatus -eq 'DRAFT') {
    Assert-PaymentDemo ($null -eq $state.normal -and $null -eq $state.cancellation) 'Saved orders reference an unopened session.'
    $session = Invoke-Week2Api $BaseUrl PUT "$sessionPath/sales-status" @{ salesStatus = 'ON_SALE' }
}

$consumer = Get-Week2Login $BaseUrl 'consumer1'
$otherConsumer = Get-Week2Login $BaseUrl 'consumer2'
$operator = Get-Week2Login $BaseUrl 'operator1'
Assert-PaymentDemo ($consumer.account.role -eq 'CONSUMER' -and $operator.account.role -eq 'OPERATOR') 'Demo account roles are invalid.'

$order = Get-PaymentDemoOrder 'normal' "buy-$Namespace"
Assert-PaymentDemo ($order.orderStatus -in @('PENDING_PAYMENT', 'PAID')) 'The normal order is no longer payable or paid.'
Assert-PaymentDemo (-not $PauseAfterChannelSuccess -or $order.orderStatus -eq 'PENDING_PAYMENT') 'PauseAfterChannelSuccess requires a pending order. An already PAID order cannot be rolled back to the pause point; use a new namespace.'
$paymentPath = "/api/orders/$($order.id)/payments"
$started = Invoke-PaymentDemoRequest POST $paymentPath $consumer -Key $state.normal.paymentKey -ExpectedStatus @(200, 201)
$payment = $started.data
Assert-PaymentDemo ($payment.paymentStatus -in @('PROCESSING', 'SUCCEEDED') -and
    -not [string]::IsNullOrWhiteSpace($payment.paymentNumber)) 'Payment must be processing or already succeeded.'
if ($null -ne $state.normal.paymentNumber) {
    Assert-PaymentDemo ($state.normal.paymentNumber -eq $payment.paymentNumber) 'Payment replay changed the channel number.'
}
$state.normal.paymentNumber = $payment.paymentNumber
Save-PaymentDemoState
$paymentNumber = [Uri]::EscapeDataString($payment.paymentNumber)
$replayed = Invoke-PaymentDemoRequest POST $paymentPath $consumer -Key $state.normal.paymentKey
Assert-PaymentDemo ($replayed.data.paymentNumber -eq $payment.paymentNumber) 'Payment replay created another attempt.'
$attempts = @( (Invoke-PaymentDemoRequest GET $paymentPath $consumer).data )
Assert-PaymentDemo ($attempts.Count -eq 1 -and $attempts[0].paymentNumber -eq $payment.paymentNumber) 'Normal order must have exactly one payment attempt.'
Write-Host "PASS payment start/replay: order=$($order.id); payment=$($payment.paymentNumber); status=$($payment.paymentStatus)."

$beforeSuccess = (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)" $consumer).data
$channelResult = (Invoke-PaymentDemoRequest POST "/api/operations/payments/$paymentNumber/succeed" $operator).data
Assert-PaymentDemo ($channelResult.paymentStatus -eq 'SUCCEEDED' -and $channelResult.paymentNumber -eq $payment.paymentNumber) 'The independently saved channel result is not the original successful payment.'
$afterChannel = (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)" $consumer).data
Assert-PaymentDemo ($afterChannel.orderStatus -eq $beforeSuccess.orderStatus) 'Saving the channel result unexpectedly changed the local order before delivery.'
$notices = @( (Invoke-PaymentDemoRequest GET "/api/operations/payments/$paymentNumber/notifications" $operator).data )
$successNotice = $notices | Where-Object observedStatus -eq 'SUCCEEDED' | Select-Object -First 1
$processingNotice = $notices | Where-Object observedStatus -eq 'PROCESSING' | Select-Object -First 1
Assert-PaymentDemo ($null -ne $successNotice -and $null -ne $processingNotice) 'Both the accepted and succeeded notification snapshots must exist.'
$successNumber = [Uri]::EscapeDataString($successNotice.notificationNumber)
if ($PauseAfterChannelSuccess) {
    $pendingTickets = @( (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)/tickets" $consumer).data )
    $pendingLayout = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
    $pendingSeats = @($pendingLayout | Where-Object { $_.id -in $state.normal.seatIds })
    Assert-PaymentDemo ($afterChannel.orderStatus -eq 'PENDING_PAYMENT' -and $afterChannel.hold.status -eq 'ACTIVE' -and
        $pendingTickets.Count -eq 0 -and $pendingSeats.Count -eq 2 -and
        @($pendingSeats | Where-Object seatStatus -ne 'HELD').Count -eq 0 -and
        $null -eq $successNotice.deliveredAt) 'Pause point requires channel SUCCEEDED, local PENDING_PAYMENT/ACTIVE/HELD, zero tickets and an undelivered success notification.'
    Write-Host "PASS pause point: namespace=$Namespace; session=$($session.id); order=$($order.id); payment=$($payment.paymentNumber); channel=SUCCEEDED; local=PENDING_PAYMENT; tickets=0; HELD=2."
    Write-Host "No notification was delivered and no cancellation order was created. Restart the same application, then resume with -Namespace $Namespace -RecoverBeforeDelivery."
    [pscustomobject]@{
        namespace = $Namespace; sessionId = $session.id; orderId = $order.id
        paymentNumber = $payment.paymentNumber; successNotificationNumber = $successNotice.notificationNumber
        stage = 'CHANNEL_SUCCEEDED_AWAITING_LOCAL_RESULT'; requestFile = $stateFile
    }
    return
}
if ($RecoverBeforeDelivery) {
    $recovery = (Invoke-PaymentDemoRequest POST "/api/operations/orders/$($order.id)/recover" $operator).data
    $recoveredOrder = (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)" $consumer).data
    $recoveredTickets = @( (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)/tickets" $consumer).data )
    Assert-PaymentDemo ($recovery.orderStatus -eq 'PAID' -and $recoveredOrder.orderStatus -eq 'PAID' -and
        $recoveredOrder.hold.status -eq 'CONSUMED' -and $recoveredTickets.Count -eq 2 -and
        @($recoveredTickets | Where-Object ticketStatus -ne 'VALID').Count -eq 0) 'Manual recovery did not restore the original payment to one paid order and two tickets before delivery.'
    $recoveredNumbers = @($recoveredTickets | ForEach-Object ticketNumber | Sort-Object)
    if (@($state.normal.ticketNumbers).Count -gt 0) {
        Assert-PaymentDemo (($recoveredNumbers -join ',') -eq (@($state.normal.ticketNumbers | Sort-Object) -join ',')) 'Manual recovery changed saved ticket identities.'
    }
    $state.normal.ticketNumbers = $recoveredNumbers
    Save-PaymentDemoState
    Write-Host "PASS manual recovery before delivery: original payment=$($payment.paymentNumber); PAID + CONSUMED + VALID=2; tickets=$($recoveredNumbers -join ',')."
}
$null = Invoke-PaymentDemoRequest POST "/api/operations/notifications/$successNumber/deliver" $operator
$paid = (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)" $consumer).data
$tickets = @( (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)/tickets" $consumer).data )
Assert-PaymentDemo ($paid.orderStatus -eq 'PAID' -and $paid.hold.status -eq 'CONSUMED' -and
    $tickets.Count -eq 2 -and @($tickets | Where-Object ticketStatus -ne 'VALID').Count -eq 0) 'Success did not produce one paid order and two valid tickets.'
$ticketNumbers = @($tickets | ForEach-Object { $_.ticketNumber } | Sort-Object)
Assert-PaymentDemo (@($ticketNumbers | Select-Object -Unique).Count -eq 2) 'Ticket identities must be distinct.'
if (@($state.normal.ticketNumbers).Count -gt 0) {
    Assert-PaymentDemo (($ticketNumbers -join ',') -eq (@($state.normal.ticketNumbers | Sort-Object) -join ',')) 'Repeating the demo changed original ticket identities.'
}
$state.normal.ticketNumbers = $ticketNumbers
Save-PaymentDemoState
$soldSeats = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats" | Where-Object { $_.id -in $state.normal.seatIds })
Assert-PaymentDemo ($soldSeats.Count -eq 2 -and @($soldSeats | Where-Object seatStatus -ne 'SOLD').Count -eq 0) 'Paid seats are not both SOLD.'
Write-Host "PASS result transaction: PAID + CONSUMED + SOLD=2 + VALID=2; tickets=$($ticketNumbers -join ',')."

$null = Invoke-PaymentDemoRequest POST "/api/operations/notifications/$successNumber/deliver" $operator
$oldNumber = [Uri]::EscapeDataString($processingNotice.notificationNumber)
$null = Invoke-PaymentDemoRequest POST "/api/operations/notifications/$oldNumber/deliver" $operator
$unchangedOrder = (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)" $consumer).data
$unchangedTickets = @( (Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)/tickets" $consumer).data )
Assert-PaymentDemo ($unchangedOrder.orderStatus -eq 'PAID' -and $unchangedOrder.hold.status -eq 'CONSUMED' -and
    $unchangedTickets.Count -eq 2 -and
    ((@($unchangedTickets | ForEach-Object ticketNumber | Sort-Object) -join ',') -eq ($ticketNumbers -join ','))) 'Duplicate or old PROCESSING notification changed the terminal order or tickets.'
Write-Host 'PASS notifications: repeated success and late PROCESSING preserve the original two tickets and PAID state.'

$foreign = Invoke-PaymentDemoRequest GET "/api/orders/$($order.id)/tickets" $otherConsumer -ExpectedStatus @(404)
Assert-PaymentDemo ($foreign.envelope.code -eq 'NOT_FOUND') 'Another consumer could read the tickets.'
$forbidden = Invoke-PaymentDemoRequest POST "/api/operations/payments/$paymentNumber/succeed" $consumer -ExpectedStatus @(403)
Assert-PaymentDemo ($forbidden.status -eq 403) 'A consumer could control the simulated result.'
Write-Host 'PASS access: foreign tickets return 404; consumer simulator controls return 403.'

$cancelOrder = Get-PaymentDemoOrder 'cancellation' "cancel-$Namespace"
Assert-PaymentDemo ($cancelOrder.orderStatus -in @('PENDING_PAYMENT', 'CANCELLED')) 'Cancellation fixture has an unexpected state.'
$cancelPaymentPath = "/api/orders/$($cancelOrder.id)/payments"
if ($null -eq $state.cancellation.paymentNumber) {
    Assert-PaymentDemo ($cancelOrder.orderStatus -eq 'PENDING_PAYMENT') 'The cancelled fixture has no saved payment intent.'
    $cancelStarted = Invoke-PaymentDemoRequest POST $cancelPaymentPath $consumer -Key $state.cancellation.paymentKey -ExpectedStatus @(200, 201)
    Assert-PaymentDemo ($cancelStarted.data.paymentStatus -eq 'PROCESSING') 'Cancellation fixture must first have one processing payment.'
    $state.cancellation.paymentNumber = $cancelStarted.data.paymentNumber
    Save-PaymentDemoState
}
$null = Invoke-PaymentDemoRequest POST "/api/orders/$($cancelOrder.id)/cancel" $consumer
$null = Invoke-PaymentDemoRequest POST "/api/orders/$($cancelOrder.id)/cancel" $consumer
$cancelled = (Invoke-PaymentDemoRequest GET "/api/orders/$($cancelOrder.id)" $consumer).data
$cancelTickets = @( (Invoke-PaymentDemoRequest GET "/api/orders/$($cancelOrder.id)/tickets" $consumer).data )
$cancelPayments = @( (Invoke-PaymentDemoRequest GET $cancelPaymentPath $consumer).data )
Assert-PaymentDemo ($cancelled.orderStatus -eq 'CANCELLED' -and $cancelled.hold.status -eq 'RELEASED' -and
    $cancelTickets.Count -eq 0 -and $cancelPayments.Count -eq 1 -and
    $cancelPayments[0].paymentNumber -eq $state.cancellation.paymentNumber -and
    $cancelPayments[0].paymentStatus -eq 'CLOSED') 'A processing payment was not safely closed before atomic cancellation.'
$cancelNumber = [Uri]::EscapeDataString($state.cancellation.paymentNumber)
$cancelNotices = @( (Invoke-PaymentDemoRequest GET "/api/operations/payments/$cancelNumber/notifications" $operator).data )
Assert-PaymentDemo (@($cancelNotices | Where-Object observedStatus -eq 'CLOSED').Count -eq 1) 'Cancelled payment has no durable channel close proof.'
$finalLayout = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
$releasedSeats = @($finalLayout | Where-Object { $_.id -in $state.cancellation.seatIds })
Assert-PaymentDemo ($releasedSeats.Count -eq 2 -and @($releasedSeats | Where-Object seatStatus -ne 'AVAILABLE').Count -eq 0 -and
    @($finalLayout | Where-Object seatStatus -eq 'SOLD').Count -eq 2 -and
    @($finalLayout | Where-Object seatStatus -eq 'HELD').Count -eq 0 -and
    @($finalLayout | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 52) 'Cancellation did not release only its own seats.'
Write-Host "PASS cancellation replay: order=$($cancelOrder.id); channel CLOSED + CANCELLED + RELEASED + no tickets; SOLD=2, AVAILABLE=52, HELD=0."
Write-Host "Payment demo complete. Saved non-sensitive intents: $stateFile. Orders, payment history and notifications remain in MySQL."
[pscustomobject]@{
    namespace = $Namespace; sessionId = $session.id; paidOrderId = $order.id
    paymentNumber = $payment.paymentNumber; ticketNumbers = $ticketNumbers
    cancelledOrderId = $cancelOrder.id; requestFile = $stateFile
}
