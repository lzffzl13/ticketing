param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^W6[A-Z0-9_-]{0,14}$')][string]$Namespace = 'W6',
    [switch]$PrepareOnly,
    [switch]$VerifyOnly,
    [ValidateRange(0, 3600)][int]$MaxAdmissionWaitSeconds = 180
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$BaseUrl = $BaseUrl.TrimEnd('/')
if ($PrepareOnly -and $VerifyOnly) { throw 'Choose only PrepareOnly or VerifyOnly per invocation.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$stateDirectory = Join-Path $projectRoot '.local/demo-checkins'
$stateFile = Join-Path $stateDirectory "$Namespace.json"
$null = New-Item -ItemType Directory -Path $stateDirectory -Force
$state = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } else { $null }

function Assert-CheckinDemo([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw "$Message Existing history and saved requests are preserved. Resume the same namespace after fixing the cause; an expired unfinished fixture needs a new W6 namespace."
    }
}

function Invoke-CheckinDemoRequest {
    param([string]$Method, [string]$Path, $Login, $Payload, [string]$Key, [int[]]$ExpectedStatus = @(200))
    $taskArguments = @{
        Uri = "$BaseUrl$Path"; Method = $Method; ErrorAction = 'Stop'; UseBasicParsing = $true; TimeoutSec = 20
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
        # Do not print a captured request, credential payload, authentication header or raw error body.
        if ($null -eq $_.Exception.Response) { throw "HTTP transport failed at $Method $Path. Inspect the application log and resume the saved namespace." }
        $taskStatus = [int]$_.Exception.Response.StatusCode
        if ($taskStatus -notin $ExpectedStatus) { throw "Unexpected HTTP $taskStatus at $Method $Path. Inspect the application log and resume the saved namespace." }
        $taskErrorJson = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($taskErrorJson)) {
            if ($null -ne $_.Exception.Response.Content) {
                $taskErrorJson = $_.Exception.Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            } else {
                $taskReader = [IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
                try { $taskErrorJson = $taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
            }
        }
        try { $taskEnvelope = $taskErrorJson | ConvertFrom-Json } catch { throw "Invalid error response at $Method $Path; response content was withheld." }
    }
    Assert-CheckinDemo ($taskStatus -in $ExpectedStatus) "Unexpected HTTP $taskStatus at $Method $Path."
    return [pscustomobject]@{ status = $taskStatus; data = $taskEnvelope.data; envelope = $taskEnvelope }
}

function Save-CheckinDemoState {
    $temporaryFile = "$stateFile.tmp"
    [IO.File]::WriteAllText($temporaryFile, ($state | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryFile -Destination $stateFile -Force
}

function Get-CheckinDemoTickets($Intent, $Login) {
    return @((Invoke-CheckinDemoRequest GET "/api/orders/$($Intent.orderId)/tickets" $Login).data)
}

function Complete-CheckinDemoPayment($Intent, $Login) {
    $paymentPath = "/api/orders/$($Intent.orderId)/payments"
    $payment = (Invoke-CheckinDemoRequest POST $paymentPath $Login -Key $Intent.paymentKey -ExpectedStatus @(200, 201)).data
    Assert-CheckinDemo ($payment.paymentStatus -in @('PROCESSING', 'SUCCEEDED')) 'A saved purchase no longer has a successful or processing payment.'
    if ($null -ne $Intent.paymentNumber) {
        Assert-CheckinDemo ($Intent.paymentNumber -ceq $payment.paymentNumber) 'Payment replay changed the original channel identity.'
    }
    $Intent.paymentNumber = $payment.paymentNumber
    Save-CheckinDemoState
    $paymentNumber = [Uri]::EscapeDataString($payment.paymentNumber)
    $channel = (Invoke-CheckinDemoRequest POST "/api/operations/payments/$paymentNumber/succeed" $operator).data
    Assert-CheckinDemo ($channel.paymentStatus -eq 'SUCCEEDED') 'The independent payment channel did not save success.'
    $notices = @((Invoke-CheckinDemoRequest GET "/api/operations/payments/$paymentNumber/notifications" $operator).data)
    $success = @($notices | Where-Object observedStatus -eq 'SUCCEEDED')
    Assert-CheckinDemo ($success.Count -eq 1) 'The original payment must have exactly one durable success snapshot.'
    $successNumber = [Uri]::EscapeDataString($success[0].notificationNumber)
    $null = Invoke-CheckinDemoRequest POST "/api/operations/notifications/$successNumber/deliver" $operator
    $payments = @((Invoke-CheckinDemoRequest GET $paymentPath $Login).data)
    Assert-CheckinDemo ($payments.Count -eq 1 -and $payments[0].paymentNumber -ceq $Intent.paymentNumber -and
        $payments[0].paymentStatus -eq 'SUCCEEDED') 'The purchase has an unexpected payment history.'
    return $payments
}

function Save-CheckinDemoOrder($Intent, $Login, [string]$Mode) {
    $payload = @{ sessionId = [long]$state.sessionId; selectionMode = $Mode; seatIds = @($Intent.seatIds | ForEach-Object { [long]$_ }) }
    $created = Invoke-CheckinDemoRequest POST '/api/orders' $Login $payload $Intent.orderKey @(200, 201)
    $order = $created.data
    Assert-CheckinDemo ($order.customerId -eq $Login.account.id -and $order.sessionId -eq $state.sessionId -and
        $order.selectionMode -eq $Mode -and $order.orderStatus -in @('PENDING_PAYMENT', 'PAID', 'PARTIALLY_REFUNDED', 'COMPLETED') -and
        $order.paymentExpiresAt -eq $order.hold.expiresAt -and
        ([DateTimeOffset]$order.hold.expiresAt - [DateTimeOffset]$order.hold.createdAt).TotalMinutes -eq 15 -and
        ((@($order.items | ForEach-Object sessionSeatId | Sort-Object) -join ',') -eq (@($Intent.seatIds | Sort-Object) -join ','))) 'The saved order no longer matches its consumer, seats or original payment deadline.'
    if ($null -ne $Intent.orderId) {
        Assert-CheckinDemo ($order.id -eq $Intent.orderId -and $order.paymentExpiresAt -eq $Intent.paymentExpiresAt) 'Order replay changed the original order or deadline.'
    }
    $Intent.orderId = [long]$order.id
    $Intent.paymentExpiresAt = $order.paymentExpiresAt
    Save-CheckinDemoState
    return $order
}

function Get-CheckinDemoAudit([long]$TicketId) {
    $records = @()
    $afterId = 0L
    do {
        $page = (Invoke-CheckinDemoRequest GET "/api/checkins?sessionId=$($state.sessionId)&ticketId=$TicketId&afterId=$afterId&limit=100" $checker).data
        $records += @($page.records)
        if ($null -eq $page.nextAfterId) { break }
        Assert-CheckinDemo ([long]$page.nextAfterId -gt $afterId) 'Audit pagination did not advance.'
        $afterId = [long]$page.nextAfterId
    } while ($true)
    Assert-CheckinDemo (@($records | Where-Object { $_.ticketId -ne $TicketId -or $_.sessionId -ne $state.sessionId }).Count -eq 0) 'Audit filters returned another ticket or session.'
    return $records
}

function Submit-CheckinDemoScan([long]$TicketId, [string]$Credential, [string]$ExpectedResult) {
    Assert-CheckinDemo (-not [string]::IsNullOrWhiteSpace($Credential)) 'A required saved credential is missing.'
    $attempt = (Invoke-CheckinDemoRequest POST '/api/checkins' $checker @{ sessionId = [long]$state.sessionId; credential = $Credential }).data
    Assert-CheckinDemo ($attempt.ticketId -eq $TicketId -and $attempt.result -eq $ExpectedResult -and
        $attempt.checkerId -eq $checker.account.id -and $attempt.sessionId -eq $state.sessionId -and
        $attempt.id -gt 0 -and -not [string]::IsNullOrWhiteSpace($attempt.requestId)) "Expected $ExpectedResult for ticket $TicketId."
    return $attempt
}

function Complete-CheckinDemoTicket($Intent, $Login, [long]$TicketId, [string]$Credential) {
    $ticket = @(Get-CheckinDemoTickets $Intent $Login | Where-Object id -eq $TicketId)
    Assert-CheckinDemo ($ticket.Count -eq 1 -and $ticket[0].ticketStatus -in @('VALID', 'CHECKED_IN')) 'A ticket expected for admission is no longer valid or checked in.'
    if ($ticket[0].ticketStatus -eq 'VALID') {
        $null = Submit-CheckinDemoScan $TicketId $Credential 'SUCCESS'
    }
    # A response may have been lost after commit. The persistent audit determines whether admission succeeded.
    $records = @(Get-CheckinDemoAudit $TicketId)
    Assert-CheckinDemo (@($records | Where-Object result -eq 'SUCCESS').Count -eq 1) 'The checked-in ticket must have exactly one committed success audit.'
    return @($records | Where-Object result -eq 'SUCCESS')[0]
}

function Get-CheckinDemoReport {
    $report = (Invoke-CheckinDemoRequest GET "/api/operations/sessions/$($state.sessionId)/consistency" $operator).data
    # Save only the safe operational report. The checker state, including credentials, remains a separate local file.
    $reportFile = Join-Path $stateDirectory "$Namespace-consistency.json"
    [IO.File]::WriteAllText($reportFile, ($report | ConvertTo-Json -Depth 30), [Text.UTF8Encoding]::new($false))
    Assert-CheckinDemo ($report.sessionId -eq $state.sessionId -and $report.consistent -eq $true -and @($report.differences).Count -eq 0) 'The operational consistency report found differences; inspect the saved report before taking any repair action.'
    return $report
}

function Assert-CheckinDemoFinalState {
    $original = (Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)" $consumer).data
    $resale = (Invoke-CheckinDemoRequest GET "/api/orders/$($state.resale.orderId)" $otherConsumer).data
    $originalTickets = @(Get-CheckinDemoTickets $state.purchase $consumer)
    $newTickets = @(Get-CheckinDemoTickets $state.resale $otherConsumer)
    $refunds = (Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)/refunds" $consumer).data
    $payments = @((Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)/payments" $consumer).data)
    $occupied = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($state.sessionId)/seats" | Where-Object { $_.id -in $state.purchase.seatIds })
    $old = @($originalTickets | Where-Object id -eq $state.refund.ticketId)
    $retained = @($originalTickets | Where-Object id -eq $state.purchase.retainedTicketId)
    Assert-CheckinDemo ($original.orderStatus -eq 'COMPLETED' -and $resale.orderStatus -eq 'COMPLETED' -and
        $original.hold.status -eq 'CONSUMED' -and $resale.hold.status -eq 'CONSUMED' -and
        $originalTickets.Count -eq 2 -and $old.Count -eq 1 -and $old[0].ticketStatus -eq 'REFUNDED' -and
        $retained.Count -eq 1 -and $retained[0].ticketStatus -eq 'CHECKED_IN' -and
        $newTickets.Count -eq 1 -and $newTickets[0].id -eq $state.resale.ticketId -and $newTickets[0].ticketStatus -eq 'CHECKED_IN' -and
        $newTickets[0].sessionSeatId -eq $state.refund.sessionSeatId -and $newTickets[0].ticketNumber -cne $state.refund.ticketNumber -and
        $occupied.Count -eq 2 -and @($occupied | Where-Object seatStatus -ne 'SOLD').Count -eq 0 -and
        @($refunds.refunds).Count -eq 1 -and @($refunds.refunds)[0].id -eq $state.refund.id -and
        @($refunds.refunds)[0].refundStatus -eq 'SUCCEEDED' -and [decimal]$refunds.refundedAmount -eq [decimal]$state.refund.amount -and
        (($payments | ConvertTo-Json -Depth 20 -Compress) -ceq $state.purchase.originalPaymentsJson)) 'Final order, ticket, refund, payment or SOLD inventory facts differ from the saved flow.'
    $newAudit = @(Get-CheckinDemoAudit $state.resale.ticketId)
    $oldAudit = @(Get-CheckinDemoAudit $state.refund.ticketId)
    $retainedAudit = @(Get-CheckinDemoAudit $state.purchase.retainedTicketId)
    Assert-CheckinDemo (@($newAudit | Where-Object result -eq 'SUCCESS').Count -eq 1 -and
        @($newAudit | Where-Object result -eq 'ALREADY_CHECKED_IN').Count -ge 1 -and
        @($retainedAudit | Where-Object result -eq 'SUCCESS').Count -eq 1 -and
        @($oldAudit | Where-Object result -eq 'SUCCESS').Count -eq 0 -and
        @($oldAudit | Where-Object result -eq 'REFUNDED').Count -ge 1) 'Persistent audit does not prove unique admission, duplicate rejection and old-code rejection.'
    $null = Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)/tickets/$($state.refund.ticketId)/credential" $consumer -ExpectedStatus @(409)
    $report = Get-CheckinDemoReport
    Assert-CheckinDemo ($report.counts.orders -eq 2 -and $report.counts.successfulCheckins -eq 2 -and $report.counts.tickets -eq 3 -and $report.counts.refunds -eq 1 -and
        $report.recovery.pendingOrders -eq 0 -and $report.recovery.unresolvedPayments -eq 0 -and $report.recovery.manualOrders -eq 0 -and
        $report.recovery.unresolvedRefunds -eq 0 -and $report.recovery.manualRefunds -eq 0) 'The dedicated session report has unexpected counts or unresolved recovery work.'
    Write-Host "PASS final facts: original order=$($state.purchase.orderId), resale order=$($state.resale.orderId), both COMPLETED; REFUNDED=1, CHECKED_IN=2, SOLD=2."
    Write-Host 'PASS audit: new ticket SUCCESS exactly once, repeat ALREADY_CHECKED_IN, old ticket REFUNDED, retained ticket SUCCESS exactly once.'
    Write-Host "PASS consistency: session=$($state.sessionId), differences=0, unresolved recovery=0; safe report=$Namespace-consistency.json."
    return $report
}

function Write-CheckinDemoSummary([string]$Stage) {
    [pscustomobject]@{
        namespace = $Namespace; sessionId = $state.sessionId; originalOrderId = $state.purchase.orderId
        refundedTicketId = $state.refund.ticketId; refundId = $state.refund.id
        resaleOrderId = $state.resale.orderId; newTicketId = $state.resale.ticketId; retainedTicketId = $state.purchase.retainedTicketId
        admissionOpenAt = $state.admissionOpenAt; admissionCloseAt = $state.admissionCloseAt
        stage = $Stage; requestFile = $stateFile
        consistencyReportFile = (Join-Path $stateDirectory "$Namespace-consistency.json")
    }
}

$consumer = Get-Week2Login $BaseUrl 'consumer1'
$otherConsumer = Get-Week2Login $BaseUrl 'consumer2'
$operator = Get-Week2Login $BaseUrl 'operator1'
$checker = Get-Week2Login $BaseUrl 'checker1'
Assert-CheckinDemo ($consumer.account.role -eq 'CONSUMER' -and $otherConsumer.account.role -eq 'CONSUMER' -and
    $consumer.account.id -ne $otherConsumer.account.id -and $operator.account.role -eq 'OPERATOR' -and
    $checker.account.role -eq 'CHECKER') 'The fixed demo account roles are invalid.'
if ($null -ne $state) {
    Assert-CheckinDemo ($state.version -eq 1 -and $state.namespace -ceq $Namespace -and $state.baseUrl -ceq $BaseUrl) 'Saved state belongs to a different fixture or application URL.'
}
if ($VerifyOnly -or ($null -ne $state -and $null -ne $state.finishedAt)) {
    Assert-CheckinDemo ($null -ne $state -and $null -ne $state.resale.ticketId) 'No prepared saved fixture exists to verify.'
    $null = Assert-CheckinDemoFinalState
    Write-CheckinDemoSummary 'VERIFIED'
    return
}

# The immutable rule seed stays DRAFT. All business activity uses a separate W6 session.
$seed = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace
$sessionCode = "SESSION-CHECKIN-$Namespace"
if ($null -eq $state) {
    Assert-CheckinDemo ($null -eq (Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$sessionCode" -AllowMissing)) 'A check-in session exists without saved original requests.'
    # Take the real clock only after the seed is ready, leaving about two minutes for buying and refunding.
    $baseTime = [DateTimeOffset]::UtcNow
    $baseTime = $baseTime.AddTicks(-($baseTime.Ticks % 10)) # MySQL DATETIME(6) precision.
    $startsAt = $baseTime.AddMinutes(62)
    $state = [pscustomobject]@{
        version = 1; namespace = $Namespace; baseUrl = $BaseUrl; sessionCode = $sessionCode; sessionId = $null
        venueId = [long]$seed.venueId; eventId = [long]$seed.eventId; baseTime = $baseTime.ToString('o')
        startsAt = $startsAt.ToString('o'); admissionOpenAt = $startsAt.AddMinutes(-60).ToString('o')
        admissionCloseAt = $startsAt.AddMinutes(-5).ToString('o'); preparedAt = $null; finishedAt = $null
        purchase = [pscustomobject]@{
            orderKey = "buy-$Namespace"; paymentKey = "payment-$Namespace"; seatIds = @(); orderId = $null; paymentExpiresAt = $null
            paymentNumber = $null; originalPaymentsJson = $null; retainedTicketId = $null; retainedTicketNumber = $null; retainedCredential = $null
        }
        refund = [pscustomobject]@{
            requestKey = "refund-$Namespace"; ticketId = $null; ticketNumber = $null; sessionSeatId = $null; amount = $null
            originalCredential = $null; id = $null; number = $null
        }
        resale = [pscustomobject]@{
            orderKey = "rebuy-$Namespace"; paymentKey = "rebuy-payment-$Namespace"; seatIds = @(); orderId = $null; paymentExpiresAt = $null
            paymentNumber = $null; ticketId = $null; ticketNumber = $null; credential = $null
        }
    }
    Save-CheckinDemoState
}
Assert-CheckinDemo ($state.venueId -eq $seed.venueId -and $state.eventId -eq $seed.eventId -and
    $state.sessionCode -ceq $sessionCode) 'The saved fixture no longer matches the fixed seed.'
$session = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$sessionCode" -AllowMissing
if ($null -eq $session) {
    Assert-CheckinDemo ($null -eq $state.sessionId -and [DateTimeOffset]::UtcNow -lt [DateTimeOffset]$state.admissionOpenAt) 'The saved uncreated session missed its purchase/refund stage.'
    $payload = New-Week2SessionPayload $BaseUrl $seed $sessionCode
    $payload.startsAt = $state.startsAt
    $payload.admissionOpenAt = $state.admissionOpenAt
    $payload.admissionCloseAt = $state.admissionCloseAt
    $created = Invoke-Week2Api $BaseUrl POST '/api/sessions' $payload
    $session = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($created.id)"
}
Assert-CheckinDemo ($session.venueId -eq $state.venueId -and $session.eventId -eq $state.eventId -and $session.forbidNewOrphan -and
    $session.salesStatus -in @('DRAFT', 'ON_SALE') -and
    [DateTimeOffset]$session.startsAt -eq [DateTimeOffset]$state.startsAt -and
    [DateTimeOffset]$session.admissionOpenAt -eq [DateTimeOffset]$state.admissionOpenAt -and
    [DateTimeOffset]$session.admissionCloseAt -eq [DateTimeOffset]$state.admissionCloseAt -and
    ($null -eq $state.sessionId -or $session.id -eq $state.sessionId)) 'The saved session configuration or immutable admission window changed.'
$state.sessionId = [long]$session.id
Save-CheckinDemoState
$sessionPath = "/api/sessions/$($state.sessionId)"
if ($session.salesStatus -eq 'DRAFT') {
    Assert-CheckinDemo ($null -eq $state.purchase.orderId) 'Saved business history refers to an unopened session.'
    $session = Invoke-Week2Api $BaseUrl PUT "$sessionPath/sales-status" @{ salesStatus = 'ON_SALE' }
}
Write-Host "Fixture ${Namespace}: base UTC=$($state.baseTime); admission opens UTC=$($state.admissionOpenAt); closes UTC=$($state.admissionCloseAt)."

if (@($state.purchase.seatIds).Count -eq 0) {
    $layoutBefore = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
    Assert-CheckinDemo ($layoutBefore.Count -eq 56 -and @($layoutBefore | Where-Object seatStatus -eq 'AVAILABLE').Count -eq 54) 'The independent fixture already contains business state without a saved buying intent.'
    $recommendation = Invoke-Week2Api $BaseUrl GET "$sessionPath/recommendations?quantity=2&limit=5"
    Assert-CheckinDemo (-not $recommendation.reservationMade -and @($recommendation.candidates).Count -gt 0) 'The fixed seed returned no two-seat recommendation.'
    $state.purchase.seatIds = @(@($recommendation.candidates)[0].seats | ForEach-Object { [long]$_.id } | Sort-Object)
    $layoutAfter = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats")
    Assert-CheckinDemo (@($state.purchase.seatIds).Count -eq 2 -and
        ($layoutBefore | ConvertTo-Json -Depth 20 -Compress) -ceq ($layoutAfter | ConvertTo-Json -Depth 20 -Compress)) 'Recommendation changed inventory or did not contain two seats.'
    Save-CheckinDemoState # Original buying intent precedes the order POST.
}
$originalOrder = Save-CheckinDemoOrder $state.purchase $consumer 'RECOMMENDED'
$originalPayments = @(Complete-CheckinDemoPayment $state.purchase $consumer)
$paymentJson = $originalPayments | ConvertTo-Json -Depth 20 -Compress
if ($null -ne $state.purchase.originalPaymentsJson) {
    Assert-CheckinDemo ($paymentJson -ceq $state.purchase.originalPaymentsJson) 'The original successful collection changed during a replay.'
} else {
    $state.purchase.originalPaymentsJson = $paymentJson
    Save-CheckinDemoState
}
$originalTickets = @(Get-CheckinDemoTickets $state.purchase $consumer | Sort-Object id)
Assert-CheckinDemo ($originalTickets.Count -eq 2) 'The original purchase did not issue exactly two tickets.'
if ($null -eq $state.refund.ticketId) {
    Assert-CheckinDemo (@($originalTickets | Where-Object ticketStatus -ne 'VALID').Count -eq 0) 'Both original tickets must be valid before saving the refund intent.'
    $target = $originalTickets[0]
    $retained = $originalTickets[1]
    $item = @($originalOrder.items | Where-Object sessionSeatId -eq $target.sessionSeatId)
    Assert-CheckinDemo ($item.Count -eq 1) 'The refund ticket does not identify one original price snapshot.'
    $state.refund.ticketId = [long]$target.id
    $state.refund.ticketNumber = $target.ticketNumber
    $state.refund.sessionSeatId = [long]$target.sessionSeatId
    $state.refund.amount = [decimal]$item[0].transactionPrice
    $state.refund.originalCredential = (Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)/tickets/$($target.id)/credential" $consumer).data.credential
    $state.purchase.retainedTicketId = [long]$retained.id
    $state.purchase.retainedTicketNumber = $retained.ticketNumber
    $state.purchase.retainedCredential = (Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)/tickets/$($retained.id)/credential" $consumer).data.credential
    $state.resale.seatIds = @([long]$target.sessionSeatId)
    Save-CheckinDemoState # Save the old authentic code before the refund makes code querying unavailable.
}
Assert-CheckinDemo (@($originalTickets | Where-Object { $_.id -eq $state.refund.ticketId -and $_.ticketNumber -ceq $state.refund.ticketNumber }).Count -eq 1 -and
    @($originalTickets | Where-Object { $_.id -eq $state.purchase.retainedTicketId -and $_.ticketNumber -ceq $state.purchase.retainedTicketNumber }).Count -eq 1) 'Original ticket identities changed.'
Write-Host "PASS purchase: consumer1 order=$($state.purchase.orderId), two original tickets; original 15-minute payment deadline=$($state.purchase.paymentExpiresAt)."

$refundPath = "/api/orders/$($state.purchase.orderId)/tickets/$($state.refund.ticketId)/refunds"
$refund = (Invoke-CheckinDemoRequest POST $refundPath $consumer @{ requestKey = $state.refund.requestKey } -ExpectedStatus @(200, 201)).data
Assert-CheckinDemo ($refund.ticketId -eq $state.refund.ticketId -and [decimal]$refund.amount -eq [decimal]$state.refund.amount -and
    $refund.refundStatus -in @('REQUESTED', 'PROCESSING', 'SUCCEEDED') -and
    ($null -eq $state.refund.id -or ($refund.id -eq $state.refund.id -and $refund.refundNumber -ceq $state.refund.number))) 'The refund changed its original identity, amount or target.'
$state.refund.id = [long]$refund.id
$state.refund.number = $refund.refundNumber
Save-CheckinDemoState
$refundNumber = [Uri]::EscapeDataString($state.refund.number)
$null = Invoke-CheckinDemoRequest POST "/api/operations/refunds/$refundNumber/succeed" $operator
$refundNotices = @((Invoke-CheckinDemoRequest GET "/api/operations/refunds/$refundNumber/notifications" $operator).data)
$refundSuccess = @($refundNotices | Where-Object { $_.refund.refundStatus -eq 'SUCCEEDED' })
Assert-CheckinDemo ($refundSuccess.Count -eq 1) 'The original refund has no unique durable success notification.'
foreach ($notice in @($refundSuccess) + @($refundNotices | Where-Object { $_.refund.refundStatus -eq 'PROCESSING' })) {
    $noticeNumber = [Uri]::EscapeDataString($notice.notificationNumber)
    $null = Invoke-CheckinDemoRequest POST "/api/operations/refund-notifications/$noticeNumber/deliver" $operator
}
$afterRefund = @(Get-CheckinDemoTickets $state.purchase $consumer)
$refunded = @($afterRefund | Where-Object id -eq $state.refund.ticketId)
Assert-CheckinDemo ($refunded.Count -eq 1 -and $refunded[0].ticketStatus -eq 'REFUNDED' -and
    @($afterRefund | Where-Object { $_.id -eq $state.purchase.retainedTicketId -and $_.ticketStatus -in @('VALID', 'CHECKED_IN') }).Count -eq 1) 'Single-ticket refund changed the retained ticket or did not invalidate the old ticket.'
$null = Invoke-CheckinDemoRequest GET "/api/orders/$($state.purchase.orderId)/tickets/$($state.refund.ticketId)/credential" $consumer -ExpectedStatus @(409)
Write-Host "PASS single-ticket refund: refund=$($state.refund.id), old ticket=$($state.refund.ticketId) REFUNDED, retained ticket unchanged."

$resaleOrder = Save-CheckinDemoOrder $state.resale $otherConsumer 'MANUAL'
$null = Complete-CheckinDemoPayment $state.resale $otherConsumer
$newTickets = @(Get-CheckinDemoTickets $state.resale $otherConsumer)
Assert-CheckinDemo ($newTickets.Count -eq 1 -and $newTickets[0].sessionSeatId -eq $state.refund.sessionSeatId -and
    $newTickets[0].id -ne $state.refund.ticketId -and $newTickets[0].ticketNumber -cne $state.refund.ticketNumber -and
    $newTickets[0].ticketStatus -in @('VALID', 'CHECKED_IN') -and
    ($null -eq $state.resale.ticketId -or ($newTickets[0].id -eq $state.resale.ticketId -and $newTickets[0].ticketNumber -ceq $state.resale.ticketNumber))) 'Returned-seat resale did not issue one distinct new ticket for consumer2.'
$state.resale.ticketId = [long]$newTickets[0].id
$state.resale.ticketNumber = $newTickets[0].ticketNumber
if ($null -eq $state.resale.credential) {
    Assert-CheckinDemo ($newTickets[0].ticketStatus -eq 'VALID') 'The new credential was not saved before its first admission.'
    $state.resale.credential = (Invoke-CheckinDemoRequest GET "/api/orders/$($state.resale.orderId)/tickets/$($state.resale.ticketId)/credential" $otherConsumer).data.credential
}
if ($null -eq $state.preparedAt) { $state.preparedAt = [DateTimeOffset]::UtcNow.ToString('o') }
Save-CheckinDemoState
# Replaying the old refund after resale must preserve the new occupancy.
$oldSuccessNumber = [Uri]::EscapeDataString($refundSuccess[0].notificationNumber)
$null = Invoke-CheckinDemoRequest POST "/api/operations/refund-notifications/$oldSuccessNumber/deliver" $operator
$resoldSeat = @(Invoke-Week2Api $BaseUrl GET "$sessionPath/seats" | Where-Object id -eq $state.refund.sessionSeatId)
Assert-CheckinDemo ($resoldSeat.Count -eq 1 -and $resoldSeat[0].seatStatus -eq 'SOLD') 'Old refund replay released the resold seat.'
Write-Host "PASS resale: consumer2 order=$($state.resale.orderId), returned seat=$($state.refund.sessionSeatId), distinct new ticket=$($state.resale.ticketId); old refund replay preserves SOLD."
if ($PrepareOnly) {
    $null = Get-CheckinDemoReport
    Write-Host "Prepared. Resume without -PrepareOnly using the same -BaseUrl and -Namespace $Namespace; original windows and requests will be reused."
    Write-CheckinDemoSummary 'PREPARED_AWAITING_ADMISSION'
    return
}

$retainedCurrent = @($afterRefund | Where-Object id -eq $state.purchase.retainedTicketId)[0]
$requiresAdmission = $newTickets[0].ticketStatus -eq 'VALID' -or $retainedCurrent.ticketStatus -eq 'VALID'
if ($requiresAdmission) {
    $admissionOpen = ([DateTimeOffset]$state.admissionOpenAt).AddSeconds(1)
    $admissionClose = [DateTimeOffset]$state.admissionCloseAt
    $remaining = [Math]::Max(0, ($admissionOpen - [DateTimeOffset]::UtcNow).TotalSeconds)
    if ($remaining -gt $MaxAdmissionWaitSeconds) {
        Write-Host "Prepared; admission opens in about $([Math]::Ceiling($remaining)) seconds, beyond this invocation's wait limit. Resume the same namespace later."
        Write-CheckinDemoSummary 'PREPARED_AWAITING_ADMISSION'
        return
    }
    while ([DateTimeOffset]::UtcNow -lt $admissionOpen) {
        $remaining = ($admissionOpen - [DateTimeOffset]::UtcNow).TotalSeconds
        $waitSeconds = [Math]::Min(15, [Math]::Max(1, [Math]::Ceiling($remaining)))
        Write-Host "Waiting for the real admission window: about $([Math]::Ceiling($remaining)) seconds left; next check in $waitSeconds seconds."
        Start-Sleep -Seconds $waitSeconds
    }
    Assert-CheckinDemo ([DateTimeOffset]::UtcNow -lt $admissionClose) 'The original admission window has closed before this fixture completed.'
}
$null = Complete-CheckinDemoTicket $state.resale $otherConsumer $state.resale.ticketId $state.resale.credential
$null = Complete-CheckinDemoTicket $state.purchase $consumer $state.purchase.retainedTicketId $state.purchase.retainedCredential
$newAudit = @(Get-CheckinDemoAudit $state.resale.ticketId)
if (@($newAudit | Where-Object result -eq 'ALREADY_CHECKED_IN').Count -eq 0) {
    $null = Submit-CheckinDemoScan $state.resale.ticketId $state.resale.credential 'ALREADY_CHECKED_IN'
}
$oldAudit = @(Get-CheckinDemoAudit $state.refund.ticketId)
if (@($oldAudit | Where-Object result -eq 'REFUNDED').Count -eq 0) {
    $null = Submit-CheckinDemoScan $state.refund.ticketId $state.refund.originalCredential 'REFUNDED'
}
$null = Assert-CheckinDemoFinalState
$state.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
Save-CheckinDemoState
Write-Host "End-to-end check-in demo complete. Sensitive credentials remain only in ignored local state: $stateFile."
Write-CheckinDemoSummary 'COMPLETED'
