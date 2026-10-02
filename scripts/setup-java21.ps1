$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$release = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'java21-release.json') -Raw | ConvertFrom-Json
$toolsDir = Join-Path $projectRoot '.local/tools'
$archivePath = Join-Path $toolsDir $release.archive
$jdkHome = Join-Path $toolsDir $release.release

New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
if (-not (Test-Path -LiteralPath $archivePath)) {
    Write-Output "Downloading Eclipse Temurin $($release.release) from Adoptium."
    $downloadPath = $archivePath + '.download'
    Invoke-WebRequest -Uri $release.url -OutFile $downloadPath -TimeoutSec 300
    if ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $release.sha256) {
        throw 'Downloaded JDK checksum mismatch. The archive was not installed.'
    }
    $toolsPrefix = [IO.Path]::GetFullPath($toolsDir) + [IO.Path]::DirectorySeparatorChar
    foreach ($targetPath in @($downloadPath, $archivePath)) {
        if (-not [IO.Path]::GetFullPath($targetPath).StartsWith($toolsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'The JDK archive path must stay inside the project tools directory.'
        }
    }
    Move-Item -LiteralPath $downloadPath -Destination $archivePath
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $release.sha256) {
    throw 'JDK archive checksum mismatch. Inspect the archive before trying again.'
}
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin/javac.exe'))) {
    Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsDir -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin/javac.exe'))) {
    throw 'The expected JDK directory was not found after extraction.'
}
[IO.File]::WriteAllText((Join-Path $projectRoot '.local/jdk21-home.txt'), $jdkHome, (New-Object Text.UTF8Encoding $false))
Write-Output 'SHA-256 verified. Java 21 is ready inside .local/tools.'
& (Join-Path $PSScriptRoot 'use-java21.ps1') -JdkHome $jdkHome
