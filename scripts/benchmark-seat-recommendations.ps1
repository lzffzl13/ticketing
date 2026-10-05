param(
    [ValidateRange(1, 1000000)][int[]]$SeatCounts = @(1000, 5000, 10000),
    [ValidateRange(1, 100)][int]$AreaCount = 2,
    [ValidateRange(1, 1000)][int]$SeatsPerRow = 20,
    [ValidateRange(1, 1000)][int]$SegmentsPerRow = 2,
    [ValidateRange(1, 100)][int]$TierCount = 3,
    [ValidateRange(0, 0.99)][double]$UnavailableRatio = 0.05,
    [long]$Seed = 20260928,
    [ValidateRange(1, 1000000)][int]$Quantity = 2,
    [ValidateRange(1, 20)][int]$Limit = 5,
    [ValidateRange(1, 10000)][int]$Warmup = 50,
    [ValidateRange(1, 10000)][int]$Samples = 100,
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($SegmentsPerRow -gt $SeatsPerRow -or $SeatsPerRow % $SegmentsPerRow -ne 0) {
    throw 'SegmentsPerRow must divide SeatsPerRow evenly.'
}
foreach ($count in $SeatCounts) {
    if ($count % ($AreaCount * $SeatsPerRow) -ne 0) { throw 'Each SeatCount must divide evenly into AreaCount * SeatsPerRow.' }
}
& (Join-Path $PSScriptRoot 'use-java21.ps1') -JdkHome $JdkHome
$classes = Join-Path $projectRoot 'target/classes'
if (-not (Test-Path -LiteralPath (Join-Path $classes 'com/ticketing/inventory/SeatRecommendationEngine.class'))) {
    throw 'Compile the current implementation first with scripts/test.ps1 or Maven compile.'
}
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$maven = Join-Path $projectRoot 'mvnw.cmd'
$javaVersion = (& $java -version 2>&1 | Out-String).Trim()
$mavenVersion = (& $maven -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Maven version check failed.' }
$os = Get-CimInstance Win32_OperatingSystem
$cpus = @(Get-CimInstance Win32_Processor)
$timestamp = [DateTimeOffset]::UtcNow
$outputDirectory = Join-Path $projectRoot '.local'
$null = New-Item -ItemType Directory -Force -Path $outputDirectory
$prefix = Join-Path $outputDirectory "seat-recommendations-$($timestamp.ToString('yyyyMMdd-HHmmss-fff'))"
$parameters = [ordered]@{
    seatCounts = $SeatCounts; areaCount = $AreaCount; seatsPerRow = $SeatsPerRow
    rowsPerArea = @($SeatCounts | ForEach-Object { $_ / ($AreaCount * $SeatsPerRow) })
    segmentsPerRow = $SegmentsPerRow; tierCount = $TierCount; unavailableRatio = $UnavailableRatio
    seed = $Seed; effectiveSeeds = @($SeatCounts | ForEach-Object { $Seed + $_ })
    quantity = $Quantity; limit = $Limit; forbidNewOrphan = $true
    threads = 1; warmupPerSize = $Warmup; samplesPerSize = $Samples
}
$metadata = [ordered]@{
    recordedAtUtc = $timestamp.ToString('o'); status = 'STARTED'
    baseline = 'Current complete realtime sort/partition/window recommendation, including orphan filtering, center scoring, result sorting and limiting.'
    timeDefinition = 'System.nanoTime around recommend only; generation, warmup, result consumption, source compilation and I/O excluded; nanoseconds retained, summary microseconds.'
    percentileDefinition = 'Nearest rank: sorted[ceil(sampleCount * quantile) - 1].'
    inputGeneration = 'Java Random(seed + seatCount), row-based tier cycling, equal aisle segments, independent unavailable Bernoulli draws; fixed shuffle seed=(seed+seatCount) XOR 0x5DEECE66D.'
    machine = [ordered]@{
        operatingSystem = $os.Caption; version = $os.Version; architecture = $os.OSArchitecture
        totalMemoryBytes = [long]$os.TotalVisibleMemorySize * 1024
        processors = @($cpus | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors)
    }
    javaHome = $env:JAVA_HOME; javaVersion = $javaVersion; mavenVersion = $mavenVersion
    parameters = $parameters
    gcPolicy = 'Default JVM policy; no forced collections; runtime pauses are included in measured elapsed time.'
    limitations = 'Single-process, single-thread pure algorithm observations; no HTTP, database, lock contention, throughput or production capacity claim.'
}
$metadataPath = "$prefix.environment.json"
$metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $metadataPath -Encoding utf8
Write-Host "OS=$($os.Caption) $($os.Version) $($os.OSArchitecture); CPU=$($cpus.Name -join ', ')"
Write-Host "SeatCounts=$($SeatCounts -join ','); areas=$AreaCount; seatsPerRow=$SeatsPerRow; segments=$SegmentsPerRow; tiers=$TierCount; unavailableRatio=$UnavailableRatio; seed=$Seed; threads=1"
Write-Host "Baseline=current complete realtime algorithm; warmup/size=$Warmup; samples/size=$Samples; output=$prefix"
$runnerArguments = @(
    '-cp', $classes, (Join-Path $PSScriptRoot 'SeatRecommendationBenchmark.java'),
    '--sizes', ($SeatCounts -join ','), '--areas', [string]$AreaCount,
    '--seats-per-row', [string]$SeatsPerRow, '--segments', [string]$SegmentsPerRow,
    '--tiers', [string]$TierCount, '--unavailable-ratio', $UnavailableRatio.ToString([Globalization.CultureInfo]::InvariantCulture),
    '--seed', [string]$Seed, '--quantity', [string]$Quantity, '--limit', [string]$Limit,
    '--warmup', [string]$Warmup, '--samples', [string]$Samples, '--output-prefix', $prefix
)
try {
    & $java @runnerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Algorithm benchmark failed; inspect its terminal output.' }
    $metadata.status = 'COMPLETED'
    $metadata.completedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
} catch {
    $metadata.status = 'FAILED'
    throw
} finally {
    $metadata | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $metadataPath -Encoding utf8
}
Write-Host "Environment=$metadataPath"
[pscustomobject]@{ environment = $metadataPath; summary = "$prefix.summary.csv"; samples = "$prefix.samples.csv" }
