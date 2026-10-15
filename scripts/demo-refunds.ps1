param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^W4P[A-Z0-9_-]{0,12}$')][string]$Namespace = 'W4PRF',
    [switch]$PauseAfterChannelSuccess,
    [switch]$RecoverBeforeDelivery
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$BaseUrl = $BaseUrl.TrimEnd('/')
if ($PauseAfterChannelSuccess -and $RecoverBeforeDelivery) {
    throw 'PauseAfterChannelSuccess and RecoverBeforeDelivery are separate stages. Choose one per invocation.'
}
$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDirectory = Join-Path $projectRoot '.local/demo-refunds'
$stateFile = Join-Path $stateDirectory "$Namespace.json"
$null = New-Item -ItemType Directory -Path $stateDirectory -Force

function Assert-RefundDemo([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "$Message Existing orders, refunds, tickets and saved requests are preserved. Resume the original namespace after fixing the cause."
    }
}

function Invoke-RefundDemoRequest {
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
    Assert-RefundDemo ($taskStatus -in $ExpectedStatus) "Unexpected HTTP $taskStatus at $Path."
    return [pscustomobject]@{ status = $taskStatus; envelope = $taskEnvelope; data = $taskEnvelope.data }
}

function Save-RefundDemoState {
    $temporaryFile = "$stateFile.tmp"
    [IO.File]::WriteAllText($temporaryFile, ($state | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryFile -Destination $stateFile -Force
}

function Get-RefundDemoTickets {
    return @((Invoke-RefundDemoRequest GET "/api/orders/$($state.orderId)/tickets" $consumer).data)
}

function Assert-OriginalPayment {
    $current = @((Invoke-RefundDemoRequest GET "/api/orders/$($state.orderId)/payments" $consumer).data)
    Assert-RefundDemo (($current | ConvertTo-Json -Depth 20 -Compress) -ceq $state.originalPaymentsJson) 'The original successful collection was changed by refund processing.'
}

$consumer = Get-Week2Login $BaseUrl 'consumer1'
$otherConsumer = Get-Week2Login $BaseUrl 'consumer2'
$operator = Get-Week2Login $BaseUrl 'operator1'
$state = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } else { $null }
if ($null -eq $state) {
    # This purchasing demo is called only before a refund exists. Its replay expects a PAID original order.
    $purchase = & (Join-Path $PSScriptRoot 'demo-payments.ps1') -BaseUrl $BaseUrl -Namespace $Namespace
    $orderPath = "/api/orders/$($purchase.paidOrderId)"
    $original = (Invoke-RefundDemoRequest GET $orderPath $consumer).data
    $tickets = @((Invoke-RefundDemoRequest GET "$orderPath/tickets" $consumer).data | Sort-Object id)
    Assert-RefundDemo ($original.orderStatus -eq 'PAID' -and $tickets.Count -eq 2 -and
        @($tickets | Where-Object ticketStatus -ne 'VALID').Count -eq 0) 'The original purchase must have two VALID tickets.'
    $target = $tickets[0]
    $item = @($original.items | Where-Object sessionSeatId -eq $target.sessionSeatId)
    Assert-RefundDemo ($item.Count -eq 1) 'The original ticket must match exactly one original price snapshot.'
    $code = (Invoke-RefundDemoRequest GET "$orderPath/tickets/$($target.id)/credential" $consumer).data
    $originalPayments = @((Invoke-RefundDemoRequest GET "$orderPath/payments" $consumer).data)
    $state = [pscustomobject]@{
        version = 1; namespace = $Namespace; baseUrl = $BaseUrl; sessionId = [long]$original.sessionId
        orderId = [long]$original.id; paymentNumber = $purchase.paymentNumber
        ticketId = [long]$target.id; ticketNumber = $target.ticketNumber; sessionSeatId = [long]$target.sessionSeatId
        otherTicketId = [long]$tickets[1].id; otherTicketNumber = $tickets[1].ticketNumber
        amount = [decimal]$item[0].transactionPrice; refundKey = "refund-$Namespace"
        refundId = $null; refundNumber = $null; successNotificationNumber = $null; processingNotificationNumber = $null
        originalCredential = $code.credential
        originalPaymentsJson = ($originalPayments | ConvertTo-Json -Depth 20 -Compress)
        resale = [pscustomobject]@{
            orderKey = "rebuy-$Namespace"; paymentKey = "rebuy-payment-$Namespace"
            orderId = $null; paymentExpiresAt = $null; paymentNumber = $null; ticketId = $null; ticketNumber = $null
        }
    }
    # Includes the saved original QR payload; keep only in ignored local storage and never print it.
    Save-RefundDemoState
}
Assert-RefundDemo ($state.version -eq 1 -and $state.namespace -ceq $Namespace -and $state.baseUrl -ceq $BaseUrl) 'Saved refund state belongs to another application or fixture.'
$orderPath = "/api/orders/$($state.orderId)"
$sessionPath = "/api/sessions/$($state.sessionId)"
$refundPath = "$orderPath/tickets/$($state.ticketId)/refunds"
Assert-OriginalPayment

$request = Invoke-RefundDemoRequest POST $refundPath $consumer @{ requestKey = $state.refundKey } -ExpectedStatus @(200, 201)
$refund = $request.data
Assert-RefundDemo ($refund.ticketId -eq $state.ticketId -and [decimal]$refund.amount -eq [decimal]$state.amount -and
    $refund.refundStatus -in @('REQUESTED', 'PROCESSING', 'SUCCEEDED')) 'The refund must use the original ticket and original item price.'
if ($null -ne $state.refundNumber) {
    Assert-RefundDemo ($refund.id -eq $state.refundId -and $refund.refundNumber -ceq $state.refundNumber) 'Replaying the refund changed its original identity.'
}
$state.refundId = [long]$refund.id
$state.refundNumber = $refund.refundNumber
Save-RefundDemoState
$repeat = (Invoke-RefundDemoRequest POST $refundPath $consumer @{ requestKey = $state.refundKey }).data
Assert-RefundDemo ($repeat.id -eq $refund.id -and $repeat.refundNumber -ceq $refund.refundNumber) 'Original request replay created a second refund.'
$foreign = Invoke-RefundDemoRequest GET "$orderPath/refunds" $otherConsumer -ExpectedStatus @(404)
Assert-RefundDemo ($foreign.envelope.code -eq 'NOT_FOUND') 'A different consumer could inspect the original refund.'
$null = Invoke-RefundDemoRequest GET "$orderPath/tickets/$($state.ticketId)/credential" $consumer -ExpectedStatus @(409)
Write-Host "PASS refund request/replay: original order=$($state.orderId), payment=$($state.paymentNumber), ticket=$($state.ticketId), refund=$($state.refundNumber), amount=$($state.amount)."

$refundNumber = [Uri]::EscapeDataString($state.refundNumber)
$channel = (Invoke-RefundDemoRequest POST "/api/operations/refunds/$refundNumber/succeed" $operator).data
Assert-RefundDemo ($channel.refundNumber -ceq $state.refundNumber -and $channel.refundStatus -eq 'SUCCEEDED') 'The original independent channel refund did not succeed.'
$notices = @((Invoke-RefundDemoRequest GET "/api/operations/refunds/$refundNumber/notifications" $operator).data)
$successNotice = $notices | Where-Object { $_.refund.refundStatus -eq 'SUCCEEDED' } | Select-Object -First 1
$processingNotice = $notices | Where-Object { $_.refund.refundStatus -eq 'PROCESSING' } | Select-Object -First 1
Assert-RefundDemo ($null -ne $successNotice -and $null -ne $processingNotice -and $notices.Count -eq 2) 'Original acceptance and success snapshots must be durable and unique.'
$state.successNotificationNumber = $successNotice.notificationNumber
$state.processingNotificationNumber = $processingNotice.notificationNumber
Save-RefundDemoState
if ($PauseAfterChannelSuccess) {
    $summary = (Invoke-RefundDemoRequest GET "$orderPath/refunds" $consumer).data
    $local = @($summary.refunds | Where-Object id -eq $state.refundId)
    $tickets = @(Get-RefundDemoTickets)
    $target = $tickets | Where-Object id -eq $state.ticketId
    $seat = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats" | Where-Object id -eq $state.sessionSeatId)
    Assert-RefundDemo ($local.Count -eq 1 -and $local[0].refundStatus -in @('REQUESTED', 'PROCESSING') -and
        $target.ticketStatus -eq 'REFUNDING' -and $seat.Count -eq 1 -and $seat[0].seatStatus -eq 'SOLD' -and
        $null -eq $successNotice.deliveredAt) 'Pause requires the refund scanner disabled: channel SUCCEEDED, local not settled, REFUNDING/SOLD and undelivered success.'
    Write-Host "PASS durable pause: refund=$($state.refundNumber); channel=SUCCEEDED; original ticket=REFUNDING; original seat=SOLD; original success notification undelivered."
    Write-Host "Restart the same application with refund recovery enabled. Resume this original namespace; do not remove saved state."
    [pscustomobject]@{
        namespace = $Namespace; orderId = $state.orderId; refundId = $state.refundId; refundNumber = $state.refundNumber
        stage = 'CHANNEL_SUCCEEDED_AWAITING_LOCAL_REFUND'; requestFile = $stateFile
    }
    return
}
if ($RecoverBeforeDelivery) {
    $null = Invoke-RefundDemoRequest POST "/api/operations/refunds/$($state.refundId)/recover" $operator
}
$successNumber = [Uri]::EscapeDataString($state.successNotificationNumber)
$processingNumber = [Uri]::EscapeDataString($state.processingNotificationNumber)
$null = Invoke-RefundDemoRequest POST "/api/operations/refund-notifications/$successNumber/deliver" $operator
$null = Invoke-RefundDemoRequest POST "/api/operations/refund-notifications/$processingNumber/deliver" $operator
$summary = (Invoke-RefundDemoRequest GET "$orderPath/refunds" $consumer).data
$tickets = @(Get-RefundDemoTickets)
$target = $tickets | Where-Object id -eq $state.ticketId
$other = $tickets | Where-Object id -eq $state.otherTicketId
$original = (Invoke-RefundDemoRequest GET $orderPath $consumer).data
Assert-RefundDemo ($summary.orderStatus -eq 'PARTIALLY_REFUNDED' -and [decimal]$summary.refundedAmount -eq [decimal]$state.amount -and
    @($summary.refunds).Count -eq 1 -and @($summary.refunds)[0].refundStatus -eq 'SUCCEEDED' -and
    $original.orderStatus -eq 'PARTIALLY_REFUNDED' -and $original.hold.status -eq 'CONSUMED' -and
    $tickets.Count -eq 2 -and $target.ticketStatus -eq 'REFUNDED' -and $target.ticketNumber -ceq $state.ticketNumber -and
    $other.ticketStatus -eq 'VALID' -and $other.ticketNumber -ceq $state.otherTicketNumber) 'Single-ticket settlement changed another ticket, history or refund summary.'
Assert-OriginalPayment
$null = Invoke-RefundDemoRequest GET "$orderPath/tickets/$($state.ticketId)/credential" $consumer -ExpectedStatus @(409)
Write-Host 'PASS atomic refund: original ticket REFUNDED, other ticket VALID, order PARTIALLY_REFUNDED, original collection and CONSUMED hold history retained.'

# Reuse one saved buying intent even after an interrupted response or a completed resale.
$resalePayload = @{ sessionId = [long]$state.sessionId; selectionMode = 'MANUAL'; seatIds = @([long]$state.sessionSeatId) }
$created = Invoke-RefundDemoRequest POST '/api/orders' $otherConsumer $resalePayload $state.resale.orderKey @(200, 201)
$resale = $created.data
Assert-RefundDemo ($resale.customerId -eq $otherConsumer.account.id -and $resale.sessionId -eq $state.sessionId -and
    @($resale.items).Count -eq 1 -and @($resale.items)[0].sessionSeatId -eq $state.sessionSeatId -and
    $resale.orderStatus -in @('PENDING_PAYMENT', 'PAID')) 'The returned seat was not bought by the second consumer through the normal order API.'
if ($null -ne $state.resale.orderId) {
    Assert-RefundDemo ($resale.id -eq $state.resale.orderId -and $resale.paymentExpiresAt -eq $state.resale.paymentExpiresAt) 'Resale replay changed the original order or deadline.'
}
$state.resale.orderId = [long]$resale.id
$state.resale.paymentExpiresAt = $resale.paymentExpiresAt
Save-RefundDemoState

# Replaying the old refund while the new order owns the seat must not release its HELD occupancy.
$null = Invoke-RefundDemoRequest POST "/api/operations/refund-notifications/$successNumber/deliver" $operator
$newSeat = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats" | Where-Object id -eq $state.sessionSeatId)
$expectedSeatStatus = if ($resale.orderStatus -eq 'PAID') { 'SOLD' } else { 'HELD' }
Assert-RefundDemo ($newSeat.Count -eq 1 -and $newSeat[0].seatStatus -eq $expectedSeatStatus) 'Old refund replay released the new order occupancy.'

$resalePath = "/api/orders/$($state.resale.orderId)"
$started = Invoke-RefundDemoRequest POST "$resalePath/payments" $otherConsumer -Key $state.resale.paymentKey -ExpectedStatus @(200, 201)
$resalePayment = $started.data
if ($null -ne $state.resale.paymentNumber) {
    Assert-RefundDemo ($resalePayment.paymentNumber -ceq $state.resale.paymentNumber) 'Resale payment replay created another channel transaction.'
}
$state.resale.paymentNumber = $resalePayment.paymentNumber
Save-RefundDemoState
$resalePaymentNumber = [Uri]::EscapeDataString($state.resale.paymentNumber)
$null = Invoke-RefundDemoRequest POST "/api/operations/payments/$resalePaymentNumber/succeed" $operator
$resaleNotices = @((Invoke-RefundDemoRequest GET "/api/operations/payments/$resalePaymentNumber/notifications" $operator).data)
$resaleNotice = $resaleNotices | Where-Object observedStatus -eq 'SUCCEEDED' | Select-Object -First 1
Assert-RefundDemo ($null -ne $resaleNotice) 'The resale payment has no durable success notification.'
$resaleNoticeNumber = [Uri]::EscapeDataString($resaleNotice.notificationNumber)
$null = Invoke-RefundDemoRequest POST "/api/operations/notifications/$resaleNoticeNumber/deliver" $operator
$newTickets = @((Invoke-RefundDemoRequest GET "$resalePath/tickets" $otherConsumer).data)
Assert-RefundDemo ($newTickets.Count -eq 1 -and $newTickets[0].ticketStatus -eq 'VALID' -and
    $newTickets[0].sessionSeatId -eq $state.sessionSeatId -and $newTickets[0].id -ne $state.ticketId -and
    $newTickets[0].ticketNumber -cne $state.ticketNumber) 'Resale must issue a distinct new VALID ticket for the returned seat.'
if ($null -ne $state.resale.ticketId) {
    Assert-RefundDemo ($newTickets[0].id -eq $state.resale.ticketId -and $newTickets[0].ticketNumber -ceq $state.resale.ticketNumber) 'Resale replay changed the already issued new ticket.'
}
$state.resale.ticketId = [long]$newTickets[0].id
$state.resale.ticketNumber = $newTickets[0].ticketNumber
Save-RefundDemoState
$newCode = (Invoke-RefundDemoRequest GET "$resalePath/tickets/$($newTickets[0].id)/credential" $otherConsumer).data
$oldParts = $state.originalCredential.Split('.')
$newParts = $newCode.credential.Split('.')
Assert-RefundDemo ($oldParts.Count -eq 4 -and $oldParts[2] -ceq $state.ticketNumber -and
    $newParts.Count -eq 4 -and $newParts[2] -ceq $newTickets[0].ticketNumber -and
    $oldParts[2] -cne $newParts[2]) 'The saved old code was rebound to the new ticket.'

# Neither the original refund nor the original payment success may mutate the new sale or original refund summary.
$null = Invoke-RefundDemoRequest POST "/api/operations/refund-notifications/$successNumber/deliver" $operator
$null = Invoke-RefundDemoRequest POST "/api/operations/refund-notifications/$processingNumber/deliver" $operator
$originalPaymentNumber = [Uri]::EscapeDataString($state.paymentNumber)
$originalNotices = @((Invoke-RefundDemoRequest GET "/api/operations/payments/$originalPaymentNumber/notifications" $operator).data)
$originalSuccess = $originalNotices | Where-Object observedStatus -eq 'SUCCEEDED' | Select-Object -First 1
$originalSuccessNumber = [Uri]::EscapeDataString($originalSuccess.notificationNumber)
$null = Invoke-RefundDemoRequest POST "/api/operations/notifications/$originalSuccessNumber/deliver" $operator
$finalSeat = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats" | Where-Object id -eq $state.sessionSeatId)
$finalOldTickets = @(Get-RefundDemoTickets)
$finalNewTickets = @((Invoke-RefundDemoRequest GET "$resalePath/tickets" $otherConsumer).data)
$finalSummary = (Invoke-RefundDemoRequest GET "$orderPath/refunds" $consumer).data
Assert-RefundDemo ($finalSeat.Count -eq 1 -and $finalSeat[0].seatStatus -eq 'SOLD' -and $finalNewTickets.Count -eq 1 -and
    $finalNewTickets[0].id -eq $state.resale.ticketId -and $finalNewTickets[0].ticketStatus -eq 'VALID' -and
    $finalOldTickets.Count -eq 2 -and @($finalOldTickets | Where-Object id -eq $state.ticketId)[0].ticketStatus -eq 'REFUNDED' -and
    $finalSummary.orderStatus -eq 'PARTIALLY_REFUNDED' -and [decimal]$finalSummary.refundedAmount -eq [decimal]$state.amount) 'Old result replay changed the original refund, new ticket or new SOLD occupancy.'
Assert-OriginalPayment
$null = Invoke-RefundDemoRequest GET "$orderPath/tickets/$($state.ticketId)/credential" $consumer -ExpectedStatus @(409)
Write-Host "PASS resale: consumer2 order=$($state.resale.orderId), payment=$($state.resale.paymentNumber), new ticket=$($state.resale.ticketId); old refund/payment replays preserve the new SOLD seat and original REFUNDED ticket."
Write-Host 'PASS code binding: saved old payload still identifies the old ticket; old ticket code query is rejected; new ticket code query succeeds. Formal check-in remains a separate sixth-week feature.'
Write-Host "Refund demo complete. Sensitive saved code and original replay intents remain only in ignored local storage: $stateFile."
[pscustomobject]@{
    namespace = $Namespace; sessionId = $state.sessionId; originalOrderId = $state.orderId
    originalPaymentNumber = $state.paymentNumber; originalTicketId = $state.ticketId
    refundId = $state.refundId; refundNumber = $state.refundNumber
    resaleOrderId = $state.resale.orderId; newTicketId = $state.resale.ticketId; requestFile = $stateFile
}
