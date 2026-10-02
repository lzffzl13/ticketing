$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'Local .env was not found. Run scripts/start-infra.ps1 first.'
}
$settings = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z_]+)=(.*)$') {
        $settings[$Matches[1]] = $Matches[2]
    }
}
return $settings
