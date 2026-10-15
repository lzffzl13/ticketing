param([int]$Port = 8080)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$keyFile = $env:TICKET_CREDENTIAL_KEY_FILE
if ([string]::IsNullOrWhiteSpace($keyFile)) { $keyFile = Join-Path $projectRoot '.local/ticket-keys.properties' }
if (-not [IO.Path]::IsPathRooted($keyFile)) { $keyFile = Join-Path $projectRoot $keyFile }
$env:TICKET_CREDENTIAL_KEY_FILE = [IO.Path]::GetFullPath($keyFile)
& (Join-Path $PSScriptRoot 'ticket-keys.ps1') -Action Status -KeyFile $env:TICKET_CREDENTIAL_KEY_FILE -Quiet
& (Join-Path $PSScriptRoot 'use-java21.ps1')
$settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
$env:DB_URL = "jdbc:mysql://127.0.0.1:$($settings.MYSQL_PORT)/ticketing?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USER = 'ticketing'
$env:DB_PASSWORD = $settings.MYSQL_PASSWORD
$env:DEMO_PASSWORD = $settings.DEMO_PASSWORD
$env:SERVER_PORT = $Port.ToString()

$maven = Join-Path $projectRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn).Source }
Push-Location $projectRoot
try {
    & $maven -B -ntp "-Dmaven.repo.local=$(Join-Path $projectRoot '.local/m2')" spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Application startup failed; inspect the preceding log.' }
} finally {
    Pop-Location
}
