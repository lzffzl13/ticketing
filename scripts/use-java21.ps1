param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not $JdkHome) { $JdkHome = $env:JDK21_HOME }
if (-not $JdkHome -and (Test-Path -LiteralPath (Join-Path $projectRoot '.local/jdk21-home.txt'))) {
    $JdkHome = (Get-Content -LiteralPath (Join-Path $projectRoot '.local/jdk21-home.txt') -Raw).Trim()
}
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) { throw 'Set JDK21_HOME or run scripts/setup-java21.ps1 first.' }

$javaExe = Join-Path $JdkHome 'bin/java.exe'
$javacExe = Join-Path $JdkHome 'bin/javac.exe'
if (-not (Test-Path -LiteralPath $javaExe) -or -not (Test-Path -LiteralPath $javacExe)) {
    throw 'Set JDK21_HOME or pass -JdkHome pointing to a Java 21 JDK.'
}
$versionInfo = (& $javaExe -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $versionInfo -notmatch 'version "21(?:\.|\")') {
    throw 'The selected JDK is not Java 21.'
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkHome).Path
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
Write-Output "Java 21 selected for this PowerShell session: $env:JAVA_HOME"
& $javacExe -version
$maven = Join-Path $projectRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven = (Get-Command mvn).Source }
& $maven -version
if ($LASTEXITCODE -ne 0) { throw 'Maven environment check failed.' }
