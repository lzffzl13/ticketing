param(
    [string]$JarPath = 'target/ticketing-0.0.1-SNAPSHOT.jar',
    [string]$JdkHome,
    [string]$KeyFile = $env:TICKET_CREDENTIAL_KEY_FILE,
    [ValidateRange(1024, 65535)][int]$Port = 18091,
    [ValidateRange(1024, 65535)][int]$DatabasePort = 3309,
    # Existing payment/refund scripts allow at most 15 characters, including the P/R suffix.
    [ValidatePattern('^W4P[A-Z0-9_-]{0,11}$')][string]$Namespace = ('W4PW7' + [DateTimeOffset]::UtcNow.ToString('MMddHHmm')),
    [ValidateRange(10, 300)][int]$StartupWaitSeconds = 90,
    [ValidateRange(10, 600)][int]$RecoveryWaitSeconds = 120,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$baseUrl = "http://127.0.0.1:$Port"
$databaseUrl = "jdbc:mysql://127.0.0.1:$DatabasePort/ticketing_week7?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$directory = Join-Path $projectRoot ".local/demo-week7-restart/$Namespace"
$evidenceFile = Join-Path $directory 'evidence.json'
$snapshotJar = Join-Path $directory 'application.jar'
$invocationId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$ownedApplication = $null
$state = $null
$invocation = $null
$stateLock = $null
. (Join-Path $PSScriptRoot 'week2-api.ps1')

function Assert-W7Restart([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "$Message No history is deleted. Retain $evidenceFile and the original demo request files." }
}

function Save-W7Evidence {
    $temporary = "$evidenceFile.tmp"
    [IO.File]::WriteAllText($temporary, ($state | ConvertTo-Json -Depth 45), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $evidenceFile -Force
}

function Get-W7Listeners {
    return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Assert-W7Artifacts {
    Assert-W7Restart ((Get-FileHash -LiteralPath $snapshotJar -Algorithm SHA256).Hash -ceq $state.jarSha256) 'The isolated application jar changed.'
    Assert-W7Restart ((Get-FileHash -LiteralPath $KeyFile -Algorithm SHA256).Hash -ceq $state.keyFileSha256) 'The controlled key file changed; do not guess or restore old key material.'
    & (Join-Path $PSScriptRoot 'ticket-keys.ps1') -Action Status -KeyFile $KeyFile -Quiet
}

function Get-W7OwnedIdentity {
    Assert-W7Restart ($null -ne $script:ownedApplication) 'There is no application created by this invocation.'
    $owned = $script:ownedApplication
    $owned.process.Refresh()
    if ($owned.process.HasExited) { return $null }
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($owned.process.Id)" -ErrorAction Stop
    Assert-W7Restart ($null -ne $identity) 'The created application has no inspectable process identity.'
    $creation = ([DateTimeOffset]$identity.CreationDate).ToUniversalTime().ToString('o')
    $jarPattern = '(?i)(?:^|\s)-jar\s+"' + [regex]::Escape($snapshotJar) + '"(?=\s|$)'
    Assert-W7Restart ($owned.invocationId -ceq $invocationId -and
        [string]::Equals([string]$identity.ExecutablePath, $javaExecutable, [StringComparison]::OrdinalIgnoreCase) -and
        $creation -ceq $owned.creationDate -and $identity.CommandLine -match $jarPattern -and
        $identity.CommandLine -match ('(?:^|\s)--server.port=' + $Port + '(?=\s|$)')) 'Process identity changed; refusing to operate on a reused PID or another application.'
    return $identity
}

function Stop-W7OwnedApplication([string]$Purpose) {
    if ($null -eq $script:ownedApplication) { return }
    $identity = Get-W7OwnedIdentity
    if ($null -eq $identity) {
        $script:ownedApplication = $null
        return
    }
    $record = [pscustomobject]@{
        purpose = $Purpose; processId = [int]$identity.ProcessId
        creationDateUtc = ([DateTimeOffset]$identity.CreationDate).ToUniversalTime().ToString('o')
        jarPath = $snapshotJar; executablePath = $javaExecutable
        identityCheckedAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); stoppedAtUtc = $null
        forced = $true
    }
    # The process handle was returned by Start-Process in this invocation; no PID is loaded from evidence.
    Stop-Process -InputObject $script:ownedApplication.process -Force -ErrorAction Stop
    Assert-W7Restart ($script:ownedApplication.process.WaitForExit(10000)) 'The owned application did not exit within ten seconds.'
    $record.stoppedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $invocation.stops = @($invocation.stops) + @($record)
    $script:ownedApplication = $null
    Save-W7Evidence
}

function Start-W7Application([bool]$EnableRecovery, [string]$Phase) {
    Assert-W7Restart ($null -eq $script:ownedApplication) 'An application from this invocation is still running.'
    Assert-W7Restart (@(Get-W7Listeners).Count -eq 0) "Port $Port is already occupied; existing processes are never stopped."
    Assert-W7Artifacts
    $enabled = $EnableRecovery.ToString().ToLowerInvariant()
    $env:PAYMENT_RECOVERY_ENABLED = $enabled
    $env:REFUND_RECOVERY_ENABLED = $enabled
    $env:CONSISTENCY_ENABLED = $enabled
    $stdout = Join-Path $directory "$invocationId-$Phase.out.log"
    $stderr = Join-Path $directory "$invocationId-$Phase.err.log"
    # Start-Process joins native arguments. The exact quoted -jar path is verified again before termination.
    $arguments = @('-jar', ('"{0}"' -f $snapshotJar), "--server.port=$Port", '--server.address=127.0.0.1',
        "--spring.datasource.url=$databaseUrl", '--spring.datasource.username=ticketing_week7',
        '--spring.profiles.active=default', "--ticketing.payment-recovery.enabled=$enabled",
        "--ticketing.refund-recovery.enabled=$enabled", "--ticketing.consistency.enabled=$enabled",
        '--ticketing.payment-recovery.fixed-delay-ms=1000', '--ticketing.payment-recovery.batch-size=50',
        '--ticketing.refund-recovery.fixed-delay-ms=1000', '--ticketing.refund-recovery.batch-size=50',
        '--ticketing.consistency.fixed-delay-ms=60000', '--ticketing.consistency.batch-size=10')
    $process = Start-Process -FilePath $javaExecutable -ArgumentList $arguments -WorkingDirectory $projectRoot `
        -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    # Record ownership immediately, even when the first identity or health check subsequently fails.
    $script:ownedApplication = [pscustomobject]@{ process = $process; creationDate = $null; invocationId = $invocationId }
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction Stop
    Assert-W7Restart ($null -ne $identity) 'Started application has no inspectable process identity.'
    $script:ownedApplication.creationDate = ([DateTimeOffset]$identity.CreationDate).ToUniversalTime().ToString('o')
    Assert-W7Restart ([int]$identity.ParentProcessId -eq $PID) 'Started application could not be bound to this PowerShell process.'
    $null = Get-W7OwnedIdentity
    $invocation.starts = @($invocation.starts) + @([pscustomobject]@{
        phase = $Phase; processId = $process.Id; creationDateUtc = $script:ownedApplication.creationDate
        jarPath = $snapshotJar; jarSha256 = $state.jarSha256; executablePath = $javaExecutable
        recoveryEnabled = $EnableRecovery; stdout = $stdout; stderr = $stderr
    })
    Save-W7Evidence
    $readyBy = [DateTimeOffset]::UtcNow.AddSeconds($StartupWaitSeconds)
    do {
        Assert-W7Restart ($null -ne (Get-W7OwnedIdentity)) "Application exited during startup; inspect $stdout and $stderr."
        $healthy = $false
        try { $healthy = (Invoke-RestMethod -Uri "$baseUrl/actuator/health" -TimeoutSec 3).status -eq 'UP' } catch { }
        if ($healthy) {
            $listeners = @(Get-W7Listeners)
            Assert-W7Restart ($listeners.Count -gt 0 -and @($listeners | Where-Object OwningProcess -ne $process.Id).Count -eq 0) 'The healthy HTTP listener is not owned by the application just started.'
            Write-Host "Ready: phase=$Phase; owned process=$($process.Id); port=$Port; recovery=$enabled."
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $readyBy)
    throw "Application did not become healthy within $StartupWaitSeconds seconds; inspect $stdout and $stderr."
}

function Get-W7Api([string]$Path, [string]$Login = 'consumer1') {
    $auth = Get-Week2Login $baseUrl $Login
    return Invoke-Week2Api $baseUrl GET $Path -Headers @{ Authorization = "Bearer $($auth.token)" }
}

function Get-W7PaymentObservation {
    $intent = $state.payment
    $order = Get-W7Api "/api/orders/$($intent.orderId)"
    $payments = @(Get-W7Api "/api/orders/$($intent.orderId)/payments")
    $tickets = @(Get-W7Api "/api/orders/$($intent.orderId)/tickets" | Sort-Object id)
    $seats = @(Get-W7Api "/api/sessions/$($intent.sessionId)/seats" | Where-Object { $_.id -in $intent.seatIds } | Sort-Object id)
    $notices = @(Get-W7Api "/api/operations/payments/$($intent.paymentNumber)/notifications" 'operator1')
    return [pscustomobject]@{
        orderId = $order.id; orderStatus = $order.orderStatus; holdStatus = $order.hold.status
        paymentExpiresAt = $order.paymentExpiresAt; payments = $payments; tickets = $tickets
        seats = @($seats | Select-Object id, seatStatus)
        successNotice = $notices | Where-Object notificationNumber -eq $intent.successNotificationNumber | Select-Object -First 1
        recovery = Get-W7Api "/api/operations/orders/$($intent.orderId)/payment-state" 'operator1'
    }
}

function Get-W7RefundObservation {
    $intent = $state.refund
    $order = Get-W7Api "/api/orders/$($intent.orderId)"
    $tickets = @(Get-W7Api "/api/orders/$($intent.orderId)/tickets" | Sort-Object id)
    $seat = @(Get-W7Api "/api/sessions/$($intent.sessionId)/seats" | Where-Object id -eq $intent.sessionSeatId)
    $notices = @(Get-W7Api "/api/operations/refunds/$($intent.refundNumber)/notifications" 'operator1')
    return [pscustomobject]@{
        orderId = $order.id; orderStatus = $order.orderStatus; holdStatus = $order.hold.status
        paymentExpiresAt = $order.paymentExpiresAt
        payments = @(Get-W7Api "/api/orders/$($intent.orderId)/payments")
        refund = Get-W7Api "/api/orders/$($intent.orderId)/refunds/$($intent.refundId)"
        tickets = $tickets; seats = @($seat | Select-Object id, seatStatus)
        successNotice = $notices | Where-Object notificationNumber -eq $intent.successNotificationNumber | Select-Object -First 1
    }
}

function Assert-W7Pause($Payment, $Refund) {
    Assert-W7Restart ($Payment.orderId -eq $state.payment.orderId -and $Payment.paymentExpiresAt -eq $state.payment.paymentExpiresAt -and
        $Payment.orderStatus -eq 'PENDING_PAYMENT' -and $Payment.holdStatus -eq 'ACTIVE' -and
        @($Payment.payments).Count -eq 1 -and $Payment.payments[0].paymentStatus -eq 'PROCESSING' -and
        $Payment.payments[0].paymentNumber -ceq $state.payment.paymentNumber -and @($Payment.tickets).Count -eq 0 -and
        @($Payment.seats).Count -eq 2 -and @($Payment.seats | Where-Object seatStatus -ne 'HELD').Count -eq 0 -and
        $null -ne $Payment.successNotice -and $Payment.successNotice.observedStatus -eq 'SUCCEEDED' -and
        $null -eq $Payment.successNotice.deliveredAt) 'The payment pause is not channel SUCCEEDED plus local PENDING_PAYMENT/ACTIVE/HELD with zero tickets.'
    $target = @($Refund.tickets | Where-Object id -eq $state.refund.ticketId)
    $other = @($Refund.tickets | Where-Object id -eq $state.refund.otherTicketId)
    Assert-W7Restart ($Refund.orderStatus -eq 'PAID' -and $Refund.holdStatus -eq 'CONSUMED' -and
        $Refund.refund.refundStatus -in @('REQUESTED', 'PROCESSING') -and $target.Count -eq 1 -and
        $target[0].ticketStatus -eq 'REFUNDING' -and $other.Count -eq 1 -and $other[0].ticketStatus -eq 'VALID' -and
        @($Refund.seats).Count -eq 1 -and $Refund.seats[0].seatStatus -eq 'SOLD' -and
        $null -ne $Refund.successNotice -and $Refund.successNotice.refund.refundStatus -eq 'SUCCEEDED' -and
        $null -eq $Refund.successNotice.deliveredAt) 'The refund pause is not channel SUCCEEDED plus local pending refund, REFUNDING ticket and SOLD seat.'
}

function Assert-W7Recovered($Payment, $Refund) {
    Assert-W7Restart ($Payment.orderId -eq $state.payment.orderId -and $Payment.orderStatus -eq 'PAID' -and
        $Payment.holdStatus -eq 'CONSUMED' -and $Payment.paymentExpiresAt -eq $state.payment.paymentExpiresAt -and
        @($Payment.payments).Count -eq 1 -and $Payment.payments[0].paymentNumber -ceq $state.payment.paymentNumber -and
        $Payment.payments[0].paymentStatus -eq 'SUCCEEDED' -and @($Payment.tickets).Count -eq 2 -and
        @($Payment.tickets | Where-Object ticketStatus -ne 'VALID').Count -eq 0 -and
        @($Payment.seats).Count -eq 2 -and @($Payment.seats | Where-Object seatStatus -ne 'SOLD').Count -eq 0 -and
        $null -ne $Payment.successNotice -and $null -eq $Payment.successNotice.deliveredAt -and
        $Payment.recovery.consecutiveFailures -eq 0 -and -not $Payment.recovery.manualRequired -and
        $null -eq $Payment.recovery.nextRecoveryAt -and $null -eq $Payment.recovery.recoveryClaimUntil) 'Automatic payment recovery changed an identity/deadline, duplicated tickets, or did not settle cleanly before any notice delivery.'
    $target = @($Refund.tickets | Where-Object id -eq $state.refund.ticketId)
    $other = @($Refund.tickets | Where-Object id -eq $state.refund.otherTicketId)
    Assert-W7Restart ($Refund.orderId -eq $state.refund.orderId -and $Refund.orderStatus -eq 'PARTIALLY_REFUNDED' -and
        $Refund.holdStatus -eq 'CONSUMED' -and $Refund.paymentExpiresAt -eq $state.refund.paymentExpiresAt -and
        $Refund.refund.id -eq $state.refund.refundId -and $Refund.refund.refundNumber -ceq $state.refund.refundNumber -and
        $Refund.refund.paymentNumber -ceq $state.refund.paymentNumber -and [decimal]$Refund.refund.amount -eq [decimal]$state.refund.amount -and
        $Refund.refund.refundStatus -eq 'SUCCEEDED' -and @($Refund.tickets).Count -eq 2 -and
        $target.Count -eq 1 -and $target[0].ticketStatus -eq 'REFUNDED' -and $target[0].ticketNumber -ceq $state.refund.ticketNumber -and
        $other.Count -eq 1 -and $other[0].ticketStatus -eq 'VALID' -and $other[0].ticketNumber -ceq $state.refund.otherTicketNumber -and
        @($Refund.seats).Count -eq 1 -and $Refund.seats[0].seatStatus -eq 'AVAILABLE' -and
        $null -ne $Refund.successNotice.deliveredAt -and $Refund.refund.consecutiveFailures -eq 0 -and
        -not $Refund.refund.manualRequired -and $null -eq $Refund.refund.nextRecoveryAt -and
        $null -eq $Refund.refund.recoveryClaimUntil) 'Automatic refund recovery changed an identity or did not atomically invalidate the original ticket and return its seat.'
    Assert-W7Restart (($Refund.payments | ConvertTo-Json -Depth 20 -Compress) -ceq $state.refund.originalPaymentsJson) 'Refund recovery changed the original successful collection.'
    if (@($state.payment.ticketIds).Count -gt 0) {
        Assert-W7Restart ((@($Payment.tickets | ForEach-Object id) -join ',') -ceq (@($state.payment.ticketIds) -join ',') -and
            (@($Payment.tickets | ForEach-Object ticketNumber) -join ',') -ceq (@($state.payment.ticketNumbers) -join ',')) 'Read-only verification found replacement payment tickets.'
    }
}

$environmentNames = @('JAVA_HOME', 'PATH', 'DB_URL', 'DB_USER', 'DB_PASSWORD', 'DEMO_PASSWORD', 'SERVER_PORT',
    'TICKET_CREDENTIAL_KEY_FILE', 'SPRING_PROFILES_ACTIVE', 'PAYMENT_RECOVERY_ENABLED', 'REFUND_RECOVERY_ENABLED', 'CONSISTENCY_ENABLED')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    Assert-W7Restart ($env:OS -eq 'Windows_NT') 'This process-identity orchestration requires Windows.'
    # A distinct port is not database isolation. This script deliberately binds to the prepared week-seven schema/account.
    Assert-W7Restart ($DatabasePort -notin @(3307, 3308)) 'Use the isolated week-seven MySQL instance, not the development or test instance.'
    Assert-W7Restart ($Port -notin @(8080, 18090)) 'Use a dedicated application port; existing applications must remain running.'
    if ([string]::IsNullOrWhiteSpace($KeyFile)) { $KeyFile = Join-Path $projectRoot '.local/ticket-keys.properties' }
    if (-not [IO.Path]::IsPathRooted($KeyFile)) { $KeyFile = Join-Path $projectRoot $KeyFile }
    $KeyFile = [IO.Path]::GetFullPath($KeyFile)
    & (Join-Path $PSScriptRoot 'ticket-keys.ps1') -Action Status -KeyFile $KeyFile -Quiet
    & (Join-Path $PSScriptRoot 'use-java21.ps1') -JdkHome $JdkHome
    $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
    Assert-W7Restart (-not [string]::IsNullOrWhiteSpace($settings.MYSQL_TEST_PASSWORD)) 'The local isolated-database password is unavailable.'
    if (-not [IO.Path]::IsPathRooted($JarPath)) { $JarPath = Join-Path $projectRoot $JarPath }
    $JarPath = (Resolve-Path -LiteralPath $JarPath -ErrorAction Stop).Path
    Assert-W7Restart ([IO.Path]::GetExtension($JarPath) -eq '.jar') 'JarPath must identify an already packaged application jar.'
    $sourceHash = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash
    $null = New-Item -ItemType Directory -Path $directory -Force
    $stateLock = [IO.File]::Open((Join-Path $directory 'orchestration.lock'), [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    if (Test-Path -LiteralPath $evidenceFile) {
        $state = Get-Content -LiteralPath $evidenceFile -Raw | ConvertFrom-Json
        Assert-W7Restart ($state.version -eq 1 -and $state.namespace -ceq $Namespace -and $state.baseUrl -ceq $baseUrl -and
            $state.database.port -eq $DatabasePort -and $state.database.name -eq 'ticketing_week7' -and
            $state.jarPath -ceq $snapshotJar -and $state.jarSha256 -ceq $sourceHash -and $state.keyFile -ceq $KeyFile) 'Saved evidence belongs to a different artifact, URL, key file or isolated database.'
        Assert-W7Artifacts
    } else {
        Assert-W7Restart (-not $VerifyOnly) 'No completed evidence exists for read-only verification.'
        if (Test-Path -LiteralPath $snapshotJar) {
            Assert-W7Restart ((Get-FileHash -LiteralPath $snapshotJar -Algorithm SHA256).Hash -ceq $sourceHash) 'An interrupted copy differs from the current application jar.'
        } else { Copy-Item -LiteralPath $JarPath -Destination $snapshotJar }
        $state = [pscustomobject]@{
            version = 1; namespace = $Namespace; baseUrl = $baseUrl; status = 'CREATED'
            startedAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); completedAtUtc = $null
            database = [pscustomobject]@{ host = '127.0.0.1'; port = $DatabasePort; name = 'ticketing_week7'; user = 'ticketing_week7' }
            sourceJar = $JarPath; jarPath = $snapshotJar; jarSha256 = $sourceHash
            keyFile = $KeyFile; keyFileSha256 = (Get-FileHash -LiteralPath $KeyFile -Algorithm SHA256).Hash
            payment = $null; refund = $null; pause = $null; recovered = $null; consistency = @()
            invocations = @(); lastFailure = $null
            scope = 'Real forced termination after independently committed channel success, before local result application; not an in-transaction process kill or performance test.'
        }
    }
    if ($state.status -eq 'COMPLETED') { $VerifyOnly = $true }
    Assert-W7Restart (-not $VerifyOnly -or $state.status -eq 'COMPLETED') 'Read-only verification requires a previously completed restart experiment.'
    $invocation = [pscustomobject]@{
        id = $invocationId; startedAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); finishedAtUtc = $null
        verifyOnly = [bool]$VerifyOnly; status = 'STARTED'; starts = @(); stops = @(); observation = $null
    }
    $state.invocations = @($state.invocations) + @($invocation)
    Save-W7Evidence
    $env:DB_URL = $databaseUrl
    $env:DB_USER = 'ticketing_week7'
    $env:DB_PASSWORD = $settings.MYSQL_TEST_PASSWORD
    $env:DEMO_PASSWORD = $settings.DEMO_PASSWORD
    $env:SERVER_PORT = $Port.ToString()
    $env:TICKET_CREDENTIAL_KEY_FILE = $KeyFile
    $env:SPRING_PROFILES_ACTIVE = 'default'

    if (-not $VerifyOnly -and $state.status -in @('CREATED', 'CHANNEL_RESULTS_DURABLE')) {
        Start-W7Application $false 'scans-disabled'
        # Both existing demos use independent session namespaces and persist their own original request identities.
        $paymentPause = & (Join-Path $PSScriptRoot 'demo-payments.ps1') -BaseUrl $baseUrl -Namespace "${Namespace}P" -PauseAfterChannelSuccess
        Assert-W7Restart ($paymentPause.stage -eq 'CHANNEL_SUCCEEDED_AWAITING_LOCAL_RESULT') 'The payment demo did not stop at the required durable pause.'
        $savedPayment = Get-Content -LiteralPath $paymentPause.requestFile -Raw | ConvertFrom-Json
        $state.payment = [pscustomobject]@{
            namespace = "${Namespace}P"; sessionId = $paymentPause.sessionId; orderId = $paymentPause.orderId
            paymentNumber = $paymentPause.paymentNumber; paymentExpiresAt = $savedPayment.normal.paymentExpiresAt
            successNotificationNumber = $paymentPause.successNotificationNumber; seatIds = @($savedPayment.normal.seatIds)
            ticketIds = @(); ticketNumbers = @(); requestFile = $paymentPause.requestFile
        }
        Save-W7Evidence
        $refundPause = & (Join-Path $PSScriptRoot 'demo-refunds.ps1') -BaseUrl $baseUrl -Namespace "${Namespace}R" -PauseAfterChannelSuccess
        Assert-W7Restart ($refundPause.stage -eq 'CHANNEL_SUCCEEDED_AWAITING_LOCAL_REFUND') 'The refund demo did not stop at the required durable pause.'
        $savedRefund = Get-Content -LiteralPath $refundPause.requestFile -Raw | ConvertFrom-Json
        $refundOrder = Get-W7Api "/api/orders/$($savedRefund.orderId)"
        # Pick only public identities. The existing refund request file also contains a QR payload and is never copied or printed.
        $state.refund = [pscustomobject]@{
            namespace = "${Namespace}R"; sessionId = $savedRefund.sessionId; orderId = $savedRefund.orderId
            paymentNumber = $savedRefund.paymentNumber; paymentExpiresAt = $refundOrder.paymentExpiresAt
            ticketId = $savedRefund.ticketId; ticketNumber = $savedRefund.ticketNumber
            otherTicketId = $savedRefund.otherTicketId; otherTicketNumber = $savedRefund.otherTicketNumber
            sessionSeatId = $savedRefund.sessionSeatId; refundId = $savedRefund.refundId; refundNumber = $savedRefund.refundNumber
            amount = $savedRefund.amount; successNotificationNumber = $savedRefund.successNotificationNumber
            originalPaymentsJson = $savedRefund.originalPaymentsJson; requestFile = $refundPause.requestFile
        }
        $paymentBefore = Get-W7PaymentObservation
        $refundBefore = Get-W7RefundObservation
        Assert-W7Pause $paymentBefore $refundBefore
        $state.pause = [pscustomobject]@{ observedAtUtc = [DateTimeOffset]::UtcNow.ToString('o'); payment = $paymentBefore; refund = $refundBefore }
        $state.status = 'CHANNEL_RESULTS_DURABLE'
        Save-W7Evidence
        Stop-W7OwnedApplication 'EXPERIMENT_FORCE_TERMINATION_AFTER_DURABLE_CHANNEL_SUCCESS'
        $state.status = 'PROCESS_TERMINATED'
        Save-W7Evidence
    }

    Assert-W7Restart ($VerifyOnly -or $state.status -in @('PROCESS_TERMINATED', 'RECOVERY_OBSERVED')) 'Saved progress cannot be safely continued by this script.'
    $forcedStops = @($state.invocations | ForEach-Object { $_.stops } | Where-Object purpose -eq 'EXPERIMENT_FORCE_TERMINATION_AFTER_DURABLE_CHANNEL_SUCCESS')
    Assert-W7Restart ($forcedStops.Count -gt 0) 'No verified experimental process termination is recorded; this is not completed restart evidence.'
    Start-W7Application (-not $VerifyOnly) $(if ($VerifyOnly) { 'read-only-verification' } else { 'restarted-scans-enabled' })
    # No explicit recovery endpoint, result control or notification delivery is called after the real restart.
    $recoverBy = [DateTimeOffset]::UtcNow.AddSeconds($RecoveryWaitSeconds)
    do {
        Assert-W7Restart ($null -ne (Get-W7OwnedIdentity)) 'The restarted application exited before observation completed.'
        $paymentAfter = Get-W7PaymentObservation
        $refundAfter = Get-W7RefundObservation
        $settled = $paymentAfter.orderStatus -eq 'PAID' -and $refundAfter.orderStatus -eq 'PARTIALLY_REFUNDED' -and
            $refundAfter.refund.refundStatus -eq 'SUCCEEDED' -and
            $null -eq $paymentAfter.recovery.recoveryClaimUntil -and $null -eq $refundAfter.refund.recoveryClaimUntil -and
            $null -eq $refundAfter.refund.nextRecoveryAt -and $null -ne $refundAfter.successNotice.deliveredAt
        if ($settled) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTimeOffset]::UtcNow -lt $recoverBy)
    Assert-W7Restart $settled 'Automatic recovery was not observed within the window; preserve the original identities and inspect leases, recovery state and isolated database health.'
    Assert-W7Recovered $paymentAfter $refundAfter
    $reports = @(
        (Get-W7Api "/api/operations/sessions/$($state.payment.sessionId)/consistency" 'operator1'),
        (Get-W7Api "/api/operations/sessions/$($state.refund.sessionId)/consistency" 'operator1')
    )
    foreach ($report in $reports) {
        Assert-W7Restart ($report.consistent -and @($report.differences).Count -eq 0 -and
            $report.recovery.pendingOrders -eq 0 -and $report.recovery.unresolvedPayments -eq 0 -and
            $report.recovery.manualOrders -eq 0 -and $report.recovery.unresolvedRefunds -eq 0 -and
            $report.recovery.manualRefunds -eq 0) 'The recovered session has a consistency difference or unfinished/manual recovery.'
    }
    $observation = [pscustomobject]@{
        observedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
        payment = $paymentAfter; refund = $refundAfter; consistency = $reports
    }
    if ($VerifyOnly) {
        # Keep the first automatic recovery snapshot intact; repeat observations belong to this invocation.
        $invocation.observation = $observation
    } else {
        $state.payment.ticketIds = @($paymentAfter.tickets | ForEach-Object id)
        $state.payment.ticketNumbers = @($paymentAfter.tickets | ForEach-Object ticketNumber)
        $state.recovered = $observation
        $state.consistency = $reports
        $state.status = 'RECOVERY_OBSERVED'
    }
    Assert-W7Artifacts
    $state.status = 'COMPLETED'
    if ($null -eq $state.completedAtUtc) { $state.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o') }
    $state.lastFailure = $null
    $invocation.status = 'COMPLETED'
    Save-W7Evidence
    Write-Host "PASS restart recovery: original payment order=$($state.payment.orderId), two VALID tickets; refund=$($state.refund.refundNumber), original ticket REFUNDED and seat AVAILABLE; both reports consistent."
} catch {
    if ($null -ne $state -and $null -ne $invocation) {
        $invocation.status = 'FAILED'
        $state.lastFailure = [pscustomobject]@{
            atUtc = [DateTimeOffset]::UtcNow.ToString('o'); stage = $state.status
            exceptionType = $_.Exception.GetType().FullName
            recovery = 'Keep the evidence, isolated database and original demo state. Fix the cause and rerun the same parameters. A surviving process from an earlier invocation is never stopped automatically.'
        }
        Save-W7Evidence
    }
    throw
} finally {
    try {
        try {
            if ($null -ne $ownedApplication) { Stop-W7OwnedApplication 'OWNED_PROCESS_CLEANUP' }
        } catch {
            if ($null -ne $invocation) { $invocation.status = 'CLEANUP_FAILED' }
            throw
        } finally {
            if ($null -ne $invocation) {
                $invocation.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
                Save-W7Evidence
            }
        }
    } finally {
        if ($null -ne $stateLock) { $stateLock.Dispose() }
        foreach ($name in $previousEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
        }
    }
}
[pscustomobject]@{ status = 'COMPLETED'; namespace = $Namespace; verifyOnly = [bool]$VerifyOnly; evidenceFile = $evidenceFile }
