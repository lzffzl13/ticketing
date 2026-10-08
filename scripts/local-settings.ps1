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
# Upgrade an existing local environment without rotating database or account passwords.
if ([string]::IsNullOrWhiteSpace($settings.DEMO_PASSWORD) -or $settings.DEMO_PASSWORD -eq 'GENERATE_ME') {
    $passwordBytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($passwordBytes) } finally { $random.Dispose() }
    $settings.DEMO_PASSWORD = ([BitConverter]::ToString($passwordBytes)).Replace('-', '').ToLowerInvariant()
    $envContent = Get-Content -LiteralPath $envFile -Raw
    if ($envContent -match '(?m)^DEMO_PASSWORD=.*$') {
        $envContent = [regex]::Replace($envContent, '(?m)^DEMO_PASSWORD=.*$', "DEMO_PASSWORD=$($settings.DEMO_PASSWORD)")
    } else {
        $envContent = $envContent.TrimEnd("`r", "`n") + "`r`nDEMO_PASSWORD=$($settings.DEMO_PASSWORD)`r`n"
    }
    [IO.File]::WriteAllText($envFile, $envContent, (New-Object Text.UTF8Encoding $false))
}
return $settings
