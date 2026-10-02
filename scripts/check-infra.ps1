param([switch]$IncludeTest)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$composeArgs = @('compose', '--project-directory', $projectRoot, '--file', (Join-Path $projectRoot 'compose.yaml'),
    '--env-file', (Join-Path $projectRoot '.env'))
if ($IncludeTest) { $composeArgs += @('--profile', 'test') }

$services = @('mysql')
if ($IncludeTest) { $services += 'mysql-test' }
foreach ($service in $services) {
    Write-Output "Checking $service using its application account:"
    $query = 'SELECT VERSION(), DATABASE(), @@character_set_server; SELECT 1;'
    $query | & docker @composeArgs exec -T $service sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) { throw "MySQL authentication/query check failed: $service" }
}
& docker @composeArgs ps
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose status check failed.' }
