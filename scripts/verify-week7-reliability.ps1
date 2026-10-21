param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'use-java21.ps1') -JdkHome $JdkHome
$settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
$startedAt = [DateTimeOffset]::UtcNow
$outputDirectory = Join-Path $projectRoot '.local'
$null = New-Item -ItemType Directory -Force -Path $outputDirectory
$prefix = Join-Path $outputDirectory "week7-reliability-$($startedAt.ToString('yyyyMMdd-HHmmss-fff'))"
$classes = @('Week7InventoryReliabilityIT', 'Week7PaymentReliabilityIT')
$os = Get-CimInstance Win32_OperatingSystem
$metadata = [ordered]@{
    status = 'STARTED'; startedAtUtc = $startedAt.ToString('o')
    gitHead = (& git -C $projectRoot rev-parse HEAD | Out-String).Trim()
    gitBranch = (& git -C $projectRoot branch --show-current | Out-String).Trim()
    trackedChanges = @(& git -C $projectRoot diff HEAD --name-only)
    untrackedSources = @(& git -C $projectRoot ls-files --others --exclude-standard -- src scripts)
    database = [ordered]@{ name = 'ticketing_test'; host = '127.0.0.1'; port = [int]$settings.MYSQL_TEST_PORT }
    machine = [ordered]@{
        operatingSystem = $os.Caption; version = $os.Version; architecture = $os.OSArchitecture
        totalMemoryBytes = [long]$os.TotalVisibleMemorySize * 1024
        processors = @(Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
    }
    javaVersion = (& (Join-Path $env:JAVA_HOME 'bin/java.exe') -version 2>&1 | Out-String).Trim()
    sourceHashes = @('scripts/verify-week7-reliability.ps1',
        'src/test/java/com/ticketing/trade/Week7InventoryReliabilityIT.java',
        'src/test/java/com/ticketing/trade/Week7PaymentReliabilityIT.java') | ForEach-Object {
            $sourcePath = Join-Path $projectRoot $_
            [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash }
        }
    testClasses = $classes
    scope = 'Real MySQL service-level correctness experiments; controlled test Clock; no HTTP throughput measurement.'
    isolation = 'Dedicated ticketing_test only. Existing integration fixtures reset this test database. Run serially with all other integration tests and benchmarks.'
    faultModel = 'Test-only latches, mapper exceptions and simulator acknowledgement failure; these do not constitute an OS process-kill experiment.'
    reports = @()
}
$metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$prefix.summary.json" -Encoding utf8
$previousEnvironment = @{}
foreach ($name in @('TEST_DB_URL', 'TEST_DB_USER', 'TEST_DB_PASSWORD')) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$env:TEST_DB_URL = "jdbc:mysql://127.0.0.1:$($settings.MYSQL_TEST_PORT)/ticketing_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:TEST_DB_USER = 'ticketing_test'
$env:TEST_DB_PASSWORD = $settings.MYSQL_TEST_PASSWORD
$maven = Join-Path $projectRoot 'mvnw.cmd'
$arguments = @(
    '-B', '-ntp', "-Dmaven.repo.local=$(Join-Path $projectRoot '.local/m2')",
    '-Pintegration', '-DskipTests=false', '-DskipITs=false', "-Dit.test=$($classes -join ',')",
    'test-compile', 'failsafe:integration-test', 'failsafe:verify'
)
Write-Host "Week 7 correctness experiments: ticketing_test:$($settings.MYSQL_TEST_PORT); output=$prefix"
Write-Host 'The dedicated test database is reset by the test fixtures. Do not run another test or benchmark concurrently.'
Push-Location $projectRoot
try {
    # Exact generated files only: a successful run must create fresh reports, even on an immediate retry.
    foreach ($artifact in @('target/failsafe-reports/TEST-com.ticketing.trade.Week7InventoryReliabilityIT.xml',
        'target/failsafe-reports/TEST-com.ticketing.trade.Week7PaymentReliabilityIT.xml',
        'target/week7-inventory-reliability.json', 'target/week7-payment-reliability.json')) {
        $oldReport = Join-Path $projectRoot $artifact
        if (Test-Path -LiteralPath $oldReport) { Remove-Item -LiteralPath $oldReport }
    }
    & $maven @arguments 2>&1 | Tee-Object -FilePath "$prefix.log"
    if ($LASTEXITCODE -ne 0) { throw 'Week 7 experiments failed; inspect the retained log and Maven reports.' }
    foreach ($class in $classes) {
        $reportPath = Join-Path $projectRoot "target/failsafe-reports/TEST-com.ticketing.trade.$class.xml"
        $reportFile = Get-Item -LiteralPath $reportPath
        if ($reportFile.LastWriteTimeUtc -lt $startedAt.UtcDateTime.AddSeconds(-2)) {
            throw "Stale test report: $class"
        }
        [xml]$report = Get-Content -LiteralPath $reportPath -Raw
        $suite = $report.testsuite
        if ([int]$suite.tests -lt 1 -or [int]$suite.failures -ne 0 -or
            [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Incomplete experiment report: $class"
        }
        $metadata.reports += [ordered]@{
            class = $class; tests = [int]$suite.tests; failures = [int]$suite.failures
            errors = [int]$suite.errors; skipped = [int]$suite.skipped; elapsedSeconds = [double]$suite.time
            cases = @($suite.testcase | ForEach-Object { $_.name })
        }
    }
    foreach ($artifact in @('week7-inventory-reliability.json', 'week7-payment-reliability.json')) {
        $source = Get-Item -LiteralPath (Join-Path $projectRoot "target/$artifact")
        if ($source.LastWriteTimeUtc -lt $startedAt.UtcDateTime.AddSeconds(-2)) {
            throw "Stale experiment evidence: $artifact"
        }
        Copy-Item -LiteralPath $source.FullName -Destination "$prefix.$artifact"
    }
    $metadata.status = 'COMPLETED'
} catch {
    $metadata.status = 'FAILED'
    $metadata.failure = $_.Exception.Message
    throw
} finally {
    Pop-Location
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
    $metadata.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $metadata | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$prefix.summary.json" -Encoding utf8
}
Write-Host "Completed. Evidence: $prefix.summary.json; per-experiment JSON and log share this prefix."
