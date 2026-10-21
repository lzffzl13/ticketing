param(
    [ValidateRange(10, 500)][int]$OrdersPerCase = 50,
    [ValidateRange(1, 500)][int[]]$BatchSizes = @(1, 10, 50),
    [ValidateRange(0, 50)][int]$WarmupOrders = 5,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$BatchSizes = @($BatchSizes | Select-Object -Unique)
if ($OrdersPerCase % 10 -ne 0) { throw 'OrdersPerCase must be a multiple of 10 (20 seats per row, 2 seats per order).' }
if ($WarmupOrders -gt $OrdersPerCase) { throw 'WarmupOrders must not exceed OrdersPerCase.' }
if ($BatchSizes -notcontains 1 -or $BatchSizes.Count -lt 2) { throw 'Include batch=1 and at least one larger batch for the serial control.' }
& (Join-Path $PSScriptRoot 'use-java21.ps1') -JdkHome $JdkHome
$settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
$timestamp = [DateTimeOffset]::UtcNow
$namespace = "RB$($timestamp.ToString('yyyyMMddHHmmssfff'))-$([Guid]::NewGuid().ToString('N').Substring(0,6).ToUpperInvariant())"
$outputDirectory = Join-Path $projectRoot '.local'
$null = New-Item -ItemType Directory -Force -Path $outputDirectory
$prefix = Join-Path $outputDirectory "payment-recovery-$($timestamp.ToString('yyyyMMdd-HHmmss-fff'))"
$metadataPath = "$prefix.environment.json"
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$maven = Join-Path $projectRoot 'mvnw.cmd'
$javaVersion = (& $java -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Java version check failed.' }
$mavenVersion = (& $maven -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Maven version check failed.' }
$os = Get-CimInstance Win32_OperatingSystem
$cpus = @(Get-CimInstance Win32_Processor)
$metadata = [ordered]@{
    status = 'STARTED'; recordedAtUtc = $timestamp.ToString('o'); namespace = $namespace
    database = [ordered]@{ name = 'ticketing_test'; host = '127.0.0.1'; port = [int]$settings.MYSQL_TEST_PORT; username = 'ticketing_test' }
    machine = [ordered]@{
        operatingSystem = $os.Caption; version = $os.Version; architecture = $os.OSArchitecture
        totalMemoryBytes = [long]$os.TotalVisibleMemorySize * 1024
        processors = @($cpus | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
    }
    javaHome = $env:JAVA_HOME; javaVersion = $javaVersion; mavenVersion = $mavenVersion
    parameters = [ordered]@{
        ordersPerMeasuredCase = $OrdersPerCase; measuredBatchSizes = $BatchSizes; warmupOrdersPerWorkload = $WarmupOrders
        workloadCount = 2; seatsPerOrder = 2; seatsPerRow = 20; areaCount = 1; tierCount = 1
        forbidNewOrphan = $true; originalPaymentWindowSeconds = 900; syntheticBusinessClockUtc = '2000-01-01T00:00:00.123456Z'
    }
    baseline = 'Same real PaymentRecoveryScanner and service/transaction protocol at batch=1 versus larger batches; one scanner thread, finite already-due backlog.'
    timing = 'Real System.nanoTime around scanOnce. Per-order completion is an upper bound at the first scan return observing terminal state, not an exact commit timestamp.'
    drainTiming = 'Uniform release of synthetic overdue backlog through the last scan return, including observation SQL between scans. No intentional 1-second fixed-delay scheduling wait.'
    preservation = 'No cleanup, migration rewrite or FK bypass. Unique namespace retains all orders and channel facts. Abort if the production due predicate finds foreign candidate orders.'
    limitations = 'Year-2000 test Clock advances original 15-minute deadlines without real waiting. Compact service scans cannot establish real automatic-release delay, production SLA or capacity. Warmup is separate; machine/JVM/cache noise is uncontrolled.'
}
$metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $metadataPath -Encoding utf8
$previousEnvironment = @{}
foreach ($name in @('TEST_DB_URL', 'TEST_DB_USER', 'TEST_DB_PASSWORD')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$env:TEST_DB_URL = "jdbc:mysql://127.0.0.1:$($settings.MYSQL_TEST_PORT)/ticketing_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:TEST_DB_USER = 'ticketing_test'
$env:TEST_DB_PASSWORD = $settings.MYSQL_TEST_PASSWORD
$arguments = @(
    '-B', '-ntp', "-Dmaven.repo.local=$(Join-Path $projectRoot '.local/m2')",
    '-Pintegration', '-DskipTests=false', '-DskipITs=false',
    '-Dit.test=PaymentRecoveryBacklogExperiment', '-DrunPaymentRecoveryBenchmark=true',
    "-DrecoveryBench.namespace=$namespace", "-DrecoveryBench.outputPrefix=$prefix",
    "-DrecoveryBench.orders=$OrdersPerCase", "-DrecoveryBench.batchSizes=$($BatchSizes -join ',')",
    "-DrecoveryBench.warmupOrders=$WarmupOrders",
    'test-compile', 'failsafe:integration-test', 'failsafe:verify'
)
Write-Host "Recovery experiment database=ticketing_test:$($settings.MYSQL_TEST_PORT); namespace=$namespace; existing history is retained."
Write-Host "Measured orders/case=$OrdersPerCase; batch sizes=$($BatchSizes -join ','); warmup orders/workload=$WarmupOrders. Run serially with other ITs, benchmarks and application writers."
Write-Host "The year-2000 business Clock is synthetic; original 900-second deadlines remain intact. Results measure compact scans, without the default scheduler delay."
Push-Location $projectRoot
try {
    & $maven @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Payment recovery experiment failed; retained evidence and request identities are under .local.' }
    $runner = Get-Content -LiteralPath "$prefix.database.json" -Raw | ConvertFrom-Json
    $summary = @(Import-Csv -LiteralPath "$prefix.summary.csv")
    $measured = @($summary | Where-Object { $_.warmup -eq 'false' })
    if ($runner.status -ne 'COMPLETED' -or $runner.database -ne 'ticketing_test' -or
        $measured.Count -ne 2 * $BatchSizes.Count -or
        -not (Test-Path -LiteralPath "$prefix.requests.csv") -or
        -not (Test-Path -LiteralPath "$prefix.batches.csv") -or
        -not (Test-Path -LiteralPath "$prefix.completions.csv")) {
        throw 'The runner did not produce all completed measured cases and raw evidence.'
    }
    $metadata.status = 'COMPLETED'
    $metadata.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
} catch {
    $metadata.status = 'FAILED'
    throw
} finally {
    Pop-Location
    foreach ($name in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    $metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $metadataPath -Encoding utf8
}
Write-Host "Environment=$metadataPath; database/consistency=$prefix.database.json"
[pscustomobject]@{
    namespace = $namespace; environment = $metadataPath; databaseEnvironment = "$prefix.database.json"
    summary = "$prefix.summary.csv"; batches = "$prefix.batches.csv"
    requests = "$prefix.requests.csv"; completions = "$prefix.completions.csv"
}
