param([switch]$Integration)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'use-java21.ps1')
$mavenArgs = @('-B', '-ntp', "-Dmaven.repo.local=$(Join-Path $projectRoot '.local/m2')")
if ($Integration) {
    $settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
    $env:TEST_DB_URL = "jdbc:mysql://127.0.0.1:$($settings.MYSQL_TEST_PORT)/ticketing_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    $env:TEST_DB_USER = 'ticketing_test'
    $env:TEST_DB_PASSWORD = $settings.MYSQL_TEST_PASSWORD
    $mavenArgs += @('-Pintegration', 'verify')
} else {
    $mavenArgs += 'test'
}

$maven = Join-Path $projectRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn).Source }
Push-Location $projectRoot
try {
    & $maven @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed; inspect target/surefire-reports and target/failsafe-reports.' }
} finally {
    Pop-Location
}
