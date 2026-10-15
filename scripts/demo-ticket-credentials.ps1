param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^W4P[A-Z0-9_-]{0,12}$')][string]$Namespace = 'W4PQR',
    [ValidatePattern('^[A-Za-z0-9_-]{1,32}$')][string]$ExpectedKid
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$BaseUrl = $BaseUrl.TrimEnd('/')
$projectRoot = Split-Path -Parent $PSScriptRoot

# The existing purchase demo preserves original requests, payments and ticket numbers on replay.
$purchase = & (Join-Path $PSScriptRoot 'demo-payments.ps1') -BaseUrl $BaseUrl -Namespace $Namespace
$login = Get-Week2Login $BaseUrl 'consumer1'
$headers = @{ Authorization = "Bearer $($login.token)" }
$orderPath = "/api/orders/$($purchase.paidOrderId)"
$before = Invoke-Week2Api $BaseUrl GET $orderPath -Headers $headers
$paymentsBefore = @(Invoke-Week2Api $BaseUrl GET "$orderPath/payments" -Headers $headers)
$tickets = @(Invoke-Week2Api $BaseUrl GET "$orderPath/tickets" -Headers $headers)
if ($tickets.Count -ne 2) { throw 'Expected the original two paid tickets.' }
$directory = Join-Path $projectRoot '.local/ticket-credentials'
$null = New-Item -ItemType Directory -Path $directory -Force

$results = @()
foreach ($ticket in $tickets) {
    $path = "$orderPath/tickets/$($ticket.id)"
    $response = Invoke-WebRequest -Uri "$BaseUrl$path/credential" -Headers $headers -UseBasicParsing
    $view = ($response.Content | ConvertFrom-Json).data
    $repeat = Invoke-Week2Api $BaseUrl GET "$path/credential" -Headers $headers
    if ($view.ticketId -ne $ticket.id -or $view.ticketNumber -cne $ticket.ticketNumber -or
        $view.ticketStatus -cne 'VALID' -or $view.credential -cne $repeat.credential -or
        [string]$response.Headers['Cache-Control'] -notmatch 'no-store') {
        throw 'Credential identity, repeat behavior or no-store headers differ.'
    }
    $parts = $view.credential.Split('.')
    if ($parts.Count -ne 4 -or $parts[0] -cne 'TKT1' -or $parts[2] -cne $ticket.ticketNumber -or
        ($ExpectedKid -and $parts[1] -cne $ExpectedKid)) { throw 'Credential version, original ticket or active kid differs.' }

    $pngPath = Join-Path $directory "$Namespace-$($ticket.id).png"
    $jsonPath = Join-Path $directory "$Namespace-$($ticket.id).json"
    $previous = if (Test-Path -LiteralPath $jsonPath) { Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json } else { $null }
    if ($null -ne $previous -and ($previous.orderId -ne $before.id -or $previous.ticketNumber -cne $ticket.ticketNumber)) {
        throw 'Saved original identity differs. Preserve it and use a new namespace.'
    }
    if ($null -ne $previous -and $previous.latestKid -ceq $parts[1] -and $previous.latestCredential -cne $view.credential) {
        throw 'Restart or repeat with the same kid changed the credential.'
    }
    $pngResponse = Invoke-WebRequest -Uri "$BaseUrl$path/qrcode" -Headers $headers -OutFile $pngPath -PassThru -UseBasicParsing
    $imageBytes = [IO.File]::ReadAllBytes($pngPath)
    if ($imageBytes.Length -lt 8 -or [BitConverter]::ToString($imageBytes[0..7]) -cne '89-50-4E-47-0D-0A-1A-0A' -or
        [string]$pngResponse.Headers['Cache-Control'] -notmatch 'no-store' -or
        [string]$pngResponse.Headers['Content-Type'] -notmatch '^image/png') { throw 'Expected a no-store PNG response.' }
    $artifact = [ordered]@{
        orderId = $before.id; paymentNumber = $purchase.paymentNumber; ticketId = $ticket.id
        ticketNumber = $ticket.ticketNumber
        firstCredential = $(if ($null -ne $previous) { $previous.firstCredential } else { $view.credential })
        latestKid = $parts[1]; latestCredential = $view.credential; image = $pngPath
    }
    # The artifact contains a bearer-like admission secret; keep it only in ignored local storage.
    [IO.File]::WriteAllText($jsonPath, ($artifact | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $results += [pscustomobject]@{ ticketId = $ticket.id; ticketNumber = $ticket.ticketNumber; kid = $parts[1]; image = $pngPath }
}
$after = Invoke-Week2Api $BaseUrl GET $orderPath -Headers $headers
$paymentsAfter = @(Invoke-Week2Api $BaseUrl GET "$orderPath/payments" -Headers $headers)
if (($before | ConvertTo-Json -Depth 20 -Compress) -cne ($after | ConvertTo-Json -Depth 20 -Compress) -or
    ($paymentsBefore | ConvertTo-Json -Depth 20 -Compress) -cne ($paymentsAfter | ConvertTo-Json -Depth 20 -Compress)) {
    throw 'Credential reads changed the original order or payment facts.'
}
Write-Host "PASS original order=$($before.id), original payment=$($purchase.paymentNumber), tickets=$($tickets.Count); repeat + PNG + no-store + read-only."
Write-Host "Sensitive code artifacts remain in ignored local storage: $directory. The full credential is not printed."
[pscustomobject]@{ namespace = $Namespace; orderId = $before.id; paymentNumber = $purchase.paymentNumber; tickets = $results }
