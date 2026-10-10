param(
    [ValidateRange(20, 10000)][int[]]$SeatCounts = @(100, 500),
    [ValidateRange(1, 32)][int[]]$Concurrency = @(1, 4, 8),
    [ValidateRange(1, 100)][int]$Batches = 4,
    [ValidateRange(0, 20)][int]$WarmupBatches = 1,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
foreach ($seatCount in $SeatCounts) {
    if ($seatCount % 20 -ne 0) { throw 'Every SeatCount must be a multiple of 20 (one area, 20 seats per row).' }
    foreach ($workers in $Concurrency) {
        if (2L * ($Batches + $WarmupBatches) * $workers -gt $seatCount) {
            throw 'Each case requires a distinct two-seat pair for every warmup and measured request. Increase SeatCounts or decrease Batches/Concurrency.'
        }
    }
}
& (Join-Path $PSScriptRoot 'use-java21.ps1') -JdkHome $JdkHome
$settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
$timestamp = [DateTimeOffset]::UtcNow
$namespace = "HB$($timestamp.ToString('yyyyMMddHHmmssfff'))-$([Guid]::NewGuid().ToString('N').Substring(0,6).ToUpperInvariant())"
$outputDirectory = Join-Path $projectRoot '.local'
$null = New-Item -ItemType Directory -Force -Path $outputDirectory
$prefix = Join-Path $outputDirectory "order-holds-$($timestamp.ToString('yyyyMMdd-HHmmss-fff'))"
$metadataPath = "$prefix.environment.json"
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$maven = Join-Path $projectRoot 'mvnw.cmd'
$javaVersion = (& $java -version 2>&1 | Out-String).Trim()
$mavenVersion = (& $maven -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Maven version check failed.' }
$os = Get-CimInstance Win32_OperatingSystem
$cpus = @(Get-CimInstance Win32_Processor)
$metadata = [ordered]@{
    status = 'STARTED'; recordedAtUtc = $timestamp.ToString('o'); namespace = $namespace
    baseline = 'Current complete MySQL-backed OrderService.create; concurrency=1 is the serial control for each seat count.'
    database = [ordered]@{ name = 'ticketing_test'; host = '127.0.0.1'; port = [int]$settings.MYSQL_TEST_PORT; username = 'ticketing_test' }
    machine = [ordered]@{
        operatingSystem = $os.Caption; version = $os.Version; architecture = $os.OSArchitecture
        totalMemoryBytes = [long]$os.TotalVisibleMemorySize * 1024
        processors = @($cpus | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
    }
    javaHome = $env:JAVA_HOME; javaVersion = $javaVersion; mavenVersion = $mavenVersion
    parameters = [ordered]@{
        seatCounts = $SeatCounts; concurrency = $Concurrency; measuredBatchesPerCase = $Batches
        warmupBatchesPerCase = $WarmupBatches; seatsPerOrder = 2; areaCount = 1; tierCount = 1
        seatsPerRow = 20; aisleSegmentsPerRow = 1; unavailableRatio = 0; forbidNewOrphan = $true
    }
    timing = 'System.nanoTime around each full OrderService.create; includes connection/row-lock waits and commit/result loading; excludes fixture generation, HTTP/auth, warmup, assertions and I/O.'
    concurrencyModel = 'Fixed worker count; every finite wave starts from a CountDownLatch barrier; distinct aligned two-seat pairs on the same session, same customer, unique keys.'
    throughputDefinition = 'Successful measured orders divided by the sum of measured wave durations; setup and gaps between waves excluded.'
    limitations = 'Finite service-level observations, no HTTP/auth or payment/cancellation load, no overlapping-seat load, no production capacity or optimization gain claim. Hikari maximum pool size is recorded by the runner.'
    preservation = 'No cleanup, no Flyway clean, no FK bypass; unique fixture namespace and all non-sensitive request keys remain recorded in requests.csv.'
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
    '-Dit.test=OrderHoldBenchmarkExperiment', '-DrunOrderHoldBenchmark=true',
    "-Dbench.namespace=$namespace", "-Dbench.outputPrefix=$prefix",
    "-Dbench.seatCounts=$($SeatCounts -join ',')", "-Dbench.concurrency=$($Concurrency -join ',')",
    "-Dbench.batches=$Batches", "-Dbench.warmupBatches=$WarmupBatches",
    'test-compile', 'failsafe:integration-test', 'failsafe:verify'
)
Write-Host "Benchmark database=ticketing_test:$($settings.MYSQL_TEST_PORT); namespace=$namespace; no existing data is deleted."
Write-Host "SeatCounts=$($SeatCounts -join ','); concurrency=$($Concurrency -join ','); measured batches/case=$Batches; warmup batches/case=$WarmupBatches."
Write-Host "Baseline=current complete order service; concurrency=1 serial control; output=$prefix"
Push-Location $projectRoot
try {
    & $maven @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Order-hold experiment failed; inspect its output and retained .local artifacts.' }
    $runnerEnvironment = Get-Content -LiteralPath "$prefix.database.json" -Raw | ConvertFrom-Json
    if ($runnerEnvironment.status -ne 'COMPLETED' -or $runnerEnvironment.database -ne 'ticketing_test' -or
        -not (Test-Path -LiteralPath "$prefix.summary.csv") -or -not (Test-Path -LiteralPath "$prefix.samples.csv")) {
        throw 'The experiment did not produce a completed test-database run and raw samples.'
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
Write-Host "Environment=$metadataPath; database/runtime=$prefix.database.json"
[pscustomobject]@{
    namespace = $namespace; environment = $metadataPath; databaseEnvironment = "$prefix.database.json"
    summary = "$prefix.summary.csv"; samples = "$prefix.samples.csv"; requests = "$prefix.requests.csv"
}
