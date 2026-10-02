param([switch]$IncludeTest)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
$composeFile = Join-Path $projectRoot 'compose.yaml'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker CLI was not found. Install and start Docker Desktop first.'
}

if (-not (Test-Path -LiteralPath $envFile)) {
    $envContent = Get-Content -LiteralPath (Join-Path $projectRoot '.env.example') -Raw
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        foreach ($key in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'MYSQL_TEST_PASSWORD', 'MYSQL_TEST_ROOT_PASSWORD')) {
            $passwordBytes = New-Object byte[] 24
            $random.GetBytes($passwordBytes)
            $password = ([BitConverter]::ToString($passwordBytes)).Replace('-', '').ToLowerInvariant()
            $envContent = $envContent.Replace("$key=GENERATE_ME", "$key=$password")
        }
    } finally {
        $random.Dispose()
    }
    [IO.File]::WriteAllText($envFile, $envContent, (New-Object Text.UTF8Encoding $false))
    Write-Output 'Created ignored .env with local passwords.'
}

$composeArgs = @('compose', '--progress', 'quiet', '--project-directory', $projectRoot, '--file', $composeFile, '--env-file', $envFile)
if ($IncludeTest) { $composeArgs += @('--profile', 'test') }
$composeArgs += @('up', '-d', '--wait', '--wait-timeout', '180', 'mysql')
if ($IncludeTest) { $composeArgs += 'mysql-test' }

Write-Output 'Starting project MySQL services and waiting for their health checks.'
& docker @composeArgs
if ($LASTEXITCODE -ne 0) {
    throw 'MySQL startup failed. Inspect docker compose --profile test ps and logs before retrying.'
}
Write-Output 'MySQL is healthy. Credentials are in the local .env file.'
