param([ValidateRange(1024, 65535)][int]$Port = 3309)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Run scripts/start-infra.ps1 first to create the ignored local environment.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker CLI was not found.' }
$settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
if ($Port -eq [int]$settings.MYSQL_PORT -or $Port -eq [int]$settings.MYSQL_TEST_PORT) {
    throw 'The restart experiment requires its own MySQL port, separate from development and integration tests.'
}
$previousPort = [Environment]::GetEnvironmentVariable('WEEK7_MYSQL_PORT', 'Process')
$env:WEEK7_MYSQL_PORT = $Port.ToString()
$composeArgs = @('compose', '--project-name', 'ticketing-week7-reliability', '--project-directory', $projectRoot,
    '--file', (Join-Path $projectRoot 'compose.week7.yaml'), '--env-file', $envFile)
try {
    & docker @composeArgs up -d --wait --wait-timeout 180 mysql-week7
    if ($LASTEXITCODE -ne 0) { throw 'Isolated week-seven MySQL startup failed.' }
    $query = 'SELECT VERSION(), DATABASE(), @@character_set_server; SELECT 1;'
    $query | & docker @composeArgs exec -T mysql-week7 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) { throw 'Isolated week-seven application account check failed.' }
    Write-Output "Week-seven restart database is ready: ticketing_week7 at 127.0.0.1:$Port. Existing experiment data is preserved."
} finally {
    [Environment]::SetEnvironmentVariable('WEEK7_MYSQL_PORT', $previousPort, 'Process')
}
