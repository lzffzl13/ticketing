param(
    [ValidateSet('Init', 'Status', 'Rotate', 'Revoke', 'Retire')][string]$Action = 'Status',
    [string]$Kid,
    [string]$NewKid,
    [string]$KeyFile,
    [int[]]$StoppedProcessId = @(),
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.local'))
if (-not $KeyFile) { $KeyFile = Join-Path $localRoot 'ticket-keys.properties' }
if (-not [IO.Path]::IsPathRooted($KeyFile)) { $KeyFile = Join-Path $projectRoot $KeyFile }
$KeyFile = [IO.Path]::GetFullPath($KeyFile)
if (-not $KeyFile.StartsWith($localRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The key file must be inside this project .local directory.'
}
$keyDirectory = Split-Path -Parent $KeyFile
$metadataFile = Join-Path $keyDirectory (([IO.Path]::GetFileNameWithoutExtension($KeyFile)) + '.metadata.json')
$backupDirectory = Join-Path $keyDirectory (([IO.Path]::GetFileNameWithoutExtension($KeyFile)) + '.backups')
# Refuse reparse points so a local junction/link cannot redirect secret writes outside .local.
$candidatePaths = @($localRoot, $KeyFile, $metadataFile, $backupDirectory)
$directoryToCheck = $keyDirectory
while ($directoryToCheck.Length -gt $localRoot.Length) {
    $candidatePaths += $directoryToCheck
    $directoryToCheck = Split-Path -Parent $directoryToCheck
}
foreach ($candidatePath in $candidatePaths) {
    if ((Test-Path -LiteralPath $candidatePath) -and
        ((Get-Item -LiteralPath $candidatePath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Ticket key paths must not contain symbolic links or directory junctions.'
    }
}
$utf8 = [Text.UTF8Encoding]::new($false)
$invariant = [Globalization.CultureInfo]::InvariantCulture
$dateFormat = "yyyy-MM-dd'T'HH:mm:ss.ffffff'Z'"

function Format-Utc([DateTimeOffset]$Value) { return $Value.ToUniversalTime().ToString($dateFormat, $invariant) }
function Parse-Utc($Value) {
    # Recent PowerShell versions convert JSON ISO timestamps to DateTime automatically.
    # Preserve all six fractional digits rather than converting through a locale string.
    if ($Value -is [DateTimeOffset]) { return $Value.ToUniversalTime() }
    if ($Value -is [DateTime] -and $Value.Kind -eq [DateTimeKind]::Utc) { return [DateTimeOffset]::new($Value) }
    return [DateTimeOffset]::ParseExact([string]$Value, $dateFormat, $invariant,
        ([Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal))
}
function Assert-Kid([string]$Value) {
    if ($Value -cnotmatch '^[A-Za-z0-9_-]{1,32}$') { throw 'A key identifier must be 1-32 ASCII letters, numbers, underscores or hyphens.' }
}
function Protect-LocalPath([string]$Path, [bool]$Directory) {
    # Disable inherited grants; this local demo allows only the current Windows user.
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $item = Get-Item -LiteralPath $Path -Force
    # Read/write DACL only. Set-Acl can request SACL privileges unavailable to a normal user.
    $security = if ($PSVersionTable.PSEdition -eq 'Core') {
        [IO.FileSystemAclExtensions]::GetAccessControl($item, [Security.AccessControl.AccessControlSections]::Access)
    } else { $item.GetAccessControl([Security.AccessControl.AccessControlSections]::Access) }
    $security.SetAccessRuleProtection($true, $false)
    foreach ($existingRule in $security.Access) { $null = $security.RemoveAccessRuleSpecific($existingRule) }
    if ($Directory) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    } else {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
    }
    $security.AddAccessRule($rule)
    if ($PSVersionTable.PSEdition -eq 'Core') { [IO.FileSystemAclExtensions]::SetAccessControl($item, $security) }
    else { $item.SetAccessControl($security) }
}
function Write-ProtectedNewFile([string]$Path, [string]$Content) {
    # CreateNew refuses to overwrite an existing secret. Apply ACL before writing bytes.
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $stream.Dispose()
    Protect-LocalPath $Path $false
    [IO.File]::WriteAllText($Path, $Content, $utf8)
}
function Get-KeyFingerprint([string]$Secret) {
    $bytes = [Convert]::FromBase64String($Secret)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
    finally { $random.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}
function Read-KeyRing {
    if (-not (Test-Path -LiteralPath $KeyFile -PathType Leaf)) { throw 'Ticket key file is missing. Run scripts/ticket-keys.ps1 -Action Init first; startup never creates temporary keys.' }
    $properties = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($line in [IO.File]::ReadAllLines($KeyFile, $utf8)) {
        if ($line -match '^\s*(?:#.*|!.*)?$') { continue }
        if ($line -cnotmatch '^(activeKid|key\.[A-Za-z0-9_-]{1,32})=(\S+)$') { throw 'Ticket key file has an unsupported property or invalid syntax.' }
        if ($properties.ContainsKey($Matches[1])) { throw 'Ticket key file has a duplicate property.' }
        $properties.Add($Matches[1], $Matches[2])
    }
    if (-not $properties.ContainsKey('activeKid')) { throw 'Ticket key file has no activeKid.' }
    Assert-Kid $properties['activeKid']
    $keys = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    foreach ($entry in $properties.GetEnumerator()) {
        if ($entry.Key -eq 'activeKid') { continue }
        try { $bytes = [Convert]::FromBase64String($entry.Value) } catch { throw 'Ticket key must use canonical standard Base64.' }
        if ($bytes.Length -ne 32 -or [Convert]::ToBase64String($bytes) -cne $entry.Value) { throw 'Ticket key must be exactly 32 bytes in canonical standard Base64.' }
        $keys.Add($entry.Key.Substring(4), $entry.Value)
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
    if (-not $keys.ContainsKey($properties['activeKid'])) { throw 'activeKid does not identify a configured ticket key.' }
    return [pscustomobject]@{ activeKid = $properties['activeKid']; keys = $keys }
}
function Read-Metadata($Ring) {
    if (-not (Test-Path -LiteralPath $metadataFile -PathType Leaf)) { throw 'Ticket key metadata is missing. Preserve all keys; do not guess retention dates or reinitialize.' }
    try { $data = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json } catch { throw 'Ticket key metadata cannot be read; preserve all keys and inspect the controlled backup.' }
    if ($data.version -ne 1 -or $data.activeKid -cne $Ring.activeKid -or -not $data.records) { throw 'Ticket key metadata does not match the key ring. Do not start or retire keys until recovered.' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($record in @($data.records)) {
        Assert-Kid $record.kid
        if (-not $seen.Add($record.kid) -or $record.status -notin @('active', 'verification', 'retired', 'revoked')) { throw 'Ticket key metadata has duplicate identifiers or invalid states.' }
        foreach ($field in @('createdAt', 'stoppedAt', 'retainUntil', 'retiredAt', 'revokedAt')) {
            if ($record.$field) {
                try { $record.$field = Format-Utc (Parse-Utc $record.$field) }
                catch { throw 'Ticket key metadata has an invalid UTC timestamp. Preserve all keys until recovered.' }
            }
        }
        if ($Ring.keys.ContainsKey($record.kid)) {
            if ($record.status -notin @('active', 'verification') -or
                $record.fingerprint -cne (Get-KeyFingerprint $Ring.keys[$record.kid])) { throw 'Ticket key metadata rejects a removed key or reassigned secret.' }
            if (($record.kid -ceq $Ring.activeKid) -ne ($record.status -eq 'active')) { throw 'Ticket key metadata has an invalid active key.' }
        } elseif ($record.status -in @('active', 'verification')) { throw 'Ticket key metadata references a missing trusted key; preserve the remaining files.' }
    }
    foreach ($name in $Ring.keys.Keys) { if (-not $seen.Contains($name)) { throw 'Ticket key has no lifecycle record; preserve it and recover metadata.' } }
    return $data
}
function Assert-SignersStopped {
    $processFilter = "Name='java.exe' OR Name='javaw.exe'"
    foreach ($processId in $StoppedProcessId) { $processFilter += " OR ProcessId=$processId" }
    try { $processes = @(Get-CimInstance Win32_Process -Filter $processFilter -ErrorAction Stop) }
    catch { throw 'Cannot inspect machine-wide Java processes. Retry where process inspection is allowed; no keys were changed.' }
    foreach ($processId in $StoppedProcessId) {
        if (@($processes | Where-Object ProcessId -eq $processId).Count -gt 0) { throw "Expected stopped process $processId is still alive. This script never stops processes." }
    }
    $projectToken = $projectRoot.Replace('\', '/').ToLowerInvariant()
    foreach ($process in $processes) {
        if ($process.Name -notin @('java.exe', 'javaw.exe')) { continue }
        $command = ([string]$process.CommandLine).Replace('\', '/').ToLowerInvariant()
        if (-not $command -or $command.Contains($projectToken) -or $command.Contains('com.ticketing.') -or $command -match 'ticketing[^\s"]*\.jar') {
            throw "Java process $($process.ProcessId) may still sign project tickets. Stop and verify it before retrying; no process was stopped by this script."
        }
    }
    return Format-Utc ([DateTimeOffset]::UtcNow)
}
function Get-RetentionBound {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required to read the retention bound. All existing keys are preserved.' }
    # Keep the same .env / Docker application-account convention as check-infra.ps1.
    $settings = & (Join-Path $PSScriptRoot 'local-settings.ps1')
    $composeArgs = @('compose', '--project-directory', $projectRoot, '--file', (Join-Path $projectRoot 'compose.yaml'), '--env-file', (Join-Path $projectRoot '.env'))
    $query = @'
SET time_zone='+00:00';
START TRANSACTION READ ONLY;
SELECT COUNT(*), COALESCE(DATE_FORMAT(MAX(s.admission_close_at), '%Y-%m-%dT%H:%i:%s.%fZ'), 'NONE')
FROM ticket t JOIN session s ON s.id = t.session_id;
COMMIT;
'@
    $queryResult = @($query | & docker @composeArgs exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names' 2>$null)
    if ($LASTEXITCODE -ne 0 -or $queryResult.Count -ne 1 -or $queryResult[0] -notmatch '^(\d+)\t(.+)$') { throw 'Retention query failed or was ambiguous. All existing keys are preserved; restore DB access and retry.' }
    $ticketCount = [long]$Matches[1]
    $maxClose = $Matches[2]
    if ($ticketCount -eq 0 -and $maxClose -eq 'NONE') { return [pscustomobject]@{ ticketCount = 0; maxAdmissionCloseAt = $null } }
    if ($ticketCount -le 0 -or $maxClose -eq 'NONE') { throw 'Retention query returned inconsistent facts. All existing keys are preserved.' }
    try { $null = Parse-Utc $maxClose } catch { throw 'Retention query returned an invalid UTC timestamp. All existing keys are preserved.' }
    return [pscustomobject]@{ ticketCount = $ticketCount; maxAdmissionCloseAt = $maxClose }
}
function Save-KeyState($Ring, $Metadata, [bool]$Initial) {
    $null = New-Item -ItemType Directory -Path $keyDirectory -Force
    $null = New-Item -ItemType Directory -Path $backupDirectory -Force
    Protect-LocalPath $backupDirectory $true
    $operationId = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssffffffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $operationDirectory = Join-Path $backupDirectory $operationId
    $null = New-Item -ItemType Directory -Path $operationDirectory
    Protect-LocalPath $operationDirectory $true
    if (-not $Initial) {
        Write-ProtectedNewFile (Join-Path $operationDirectory 'before.properties') ([IO.File]::ReadAllText($KeyFile, $utf8))
        Write-ProtectedNewFile (Join-Path $operationDirectory 'before.metadata.json') ([IO.File]::ReadAllText($metadataFile, $utf8))
    }
    $lines = @('activeKid=' + $Ring.activeKid)
    foreach ($name in @($Ring.keys.Keys | Sort-Object -CaseSensitive)) { $lines += 'key.' + $name + '=' + $Ring.keys[$name] }
    $nextKey = Join-Path $operationDirectory 'next.properties'
    $nextMetadata = Join-Path $operationDirectory 'next.metadata.json'
    Write-ProtectedNewFile $nextKey (($lines -join "`n") + "`n")
    Write-ProtectedNewFile $nextMetadata (($Metadata | ConvertTo-Json -Depth 12) + "`n")
    Write-ProtectedNewFile (Join-Path $operationDirectory 'after.properties') ([IO.File]::ReadAllText($nextKey, $utf8))
    Write-ProtectedNewFile (Join-Path $operationDirectory 'after.metadata.json') ([IO.File]::ReadAllText($nextMetadata, $utf8))
    Write-ProtectedNewFile (Join-Path $operationDirectory 'operation.json') (([pscustomobject]@{
        action = $Action; at = Format-Utc ([DateTimeOffset]::UtcNow); activeKid = $Ring.activeKid; targetKid = $Kid
    } | ConvertTo-Json) + "`n")
    # Metadata first: interruption fails closed at Status/startup, including a revoked-key tombstone.
    # All paths were resolved within .local; neither move is recursive.
    Move-Item -LiteralPath $nextMetadata -Destination $metadataFile -Force
    Move-Item -LiteralPath $nextKey -Destination $KeyFile -Force
    Protect-LocalPath $metadataFile $false
    Protect-LocalPath $KeyFile $false
}

$null = New-Item -ItemType Directory -Path $keyDirectory -Force
$lockFile = $KeyFile + '.lock'
if ((Test-Path -LiteralPath $lockFile) -and
    ((Get-Item -LiteralPath $lockFile -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'Ticket key lock file must not be a symbolic link.'
}
try { $operationLock = [IO.File]::Open($lockFile, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
catch { throw 'Another key operation is running, or the key operation lock cannot be acquired. No keys were changed.' }
try {
if ($Action -eq 'Init') {
    if ((Test-Path -LiteralPath $KeyFile) -or (Test-Path -LiteralPath $metadataFile) -or (Test-Path -LiteralPath $backupDirectory)) { throw 'Existing ticket key material or history was found. Init never overwrites or discards it.' }
    if (-not $NewKid) { $NewKid = 'K' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8) }
    Assert-Kid $NewKid
    $keys = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
    $keys.Add($NewKid, (New-Secret))
    $ring = [pscustomobject]@{ activeKid = $NewKid; keys = $keys }
    $metadata = [pscustomobject]@{ version = 1; activeKid = $NewKid; records = @([pscustomobject]@{
        kid = $NewKid; status = 'active'; fingerprint = Get-KeyFingerprint $keys[$NewKid]; createdAt = Format-Utc ([DateTimeOffset]::UtcNow)
        stoppedAt = $null; retainUntil = $null; ticketCountAtStop = $null; retiredAt = $null; revokedAt = $null; stoppedProcessIds = @()
    }) }
    Save-KeyState $ring $metadata $true
} else {
    $ring = Read-KeyRing
    $metadata = Read-Metadata $ring
    if ($Action -ne 'Status') {
        $stoppedAt = Assert-SignersStopped
        if ($Action -in @('Revoke', 'Retire')) {
            Assert-Kid $Kid
            if (-not $ring.keys.ContainsKey($Kid)) { throw 'The target key is not in the current trusted ring. Historical identifiers cannot be reused.' }
        }
        if ($Action -eq 'Retire') {
            if ($Kid -ceq $ring.activeKid) { throw 'The active signing key cannot be retired; rotate it first.' }
            $record = @($metadata.records | Where-Object { $_.kid -ceq $Kid })[0]
            if ($record.status -ne 'verification' -or -not $record.stoppedAt -or -not $record.retainUntil) { throw 'Retention metadata is incomplete. Preserve this key; never guess a retirement date.' }
            try { $retainUntil = Parse-Utc $record.retainUntil } catch { throw 'Retention timestamp is invalid. Preserve this key.' }
            if ([DateTimeOffset]::UtcNow -lt $retainUntil) { throw "Key must remain trusted until $($record.retainUntil) UTC. No keys were changed." }
            $null = $ring.keys.Remove($Kid)
            $record.status = 'retired'
            $record.retiredAt = $stoppedAt
        } else {
            if (-not $NewKid) { $NewKid = 'K' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddHHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8) }
            Assert-Kid $NewKid
            if (@($metadata.records | Where-Object { $_.kid -ceq $NewKid }).Count -gt 0) { throw 'This key identifier has already been used. Choose a new identifier; removed and revoked identifiers are permanent history.' }
            $oldActive = @($metadata.records | Where-Object { $_.kid -ceq $ring.activeKid })[0]
            # A prior active key needs a bound even when revoking a different, older key.
            if ($Action -eq 'Rotate' -or $Kid -cne $ring.activeKid) {
                $bound = $null
                if ($Action -eq 'Rotate') { $bound = Get-RetentionBound }
                else {
                    try { $bound = Get-RetentionBound }
                    catch { Write-Warning 'Emergency revocation continues without a retention bound for the previously active, unaffected key. Preserve that key indefinitely until the bound is recovered.' }
                }
                $oldActive.status = 'verification'
                $oldActive.stoppedAt = $stoppedAt
                $oldActive.retainUntil = if ($null -eq $bound) { $null } elseif ($null -eq $bound.maxAdmissionCloseAt) { $stoppedAt } else { $bound.maxAdmissionCloseAt }
                $oldActive.ticketCountAtStop = if ($null -eq $bound) { $null } else { $bound.ticketCount }
                $oldActive.stoppedProcessIds = @($StoppedProcessId)
            }
            if ($Action -eq 'Revoke') {
                $record = @($metadata.records | Where-Object { $_.kid -ceq $Kid })[0]
                $record.status = 'revoked'
                $record.revokedAt = $stoppedAt
                $record.stoppedAt = $stoppedAt
                $record.stoppedProcessIds = @($StoppedProcessId)
                $null = $ring.keys.Remove($Kid)
            }
            $ring.keys.Add($NewKid, (New-Secret))
            $ring.activeKid = $NewKid
            $metadata.activeKid = $NewKid
            $metadata.records = @($metadata.records) + [pscustomobject]@{
                kid = $NewKid; status = 'active'; fingerprint = Get-KeyFingerprint $ring.keys[$NewKid]; createdAt = Format-Utc ([DateTimeOffset]::UtcNow)
                stoppedAt = $null; retainUntil = $null; ticketCountAtStop = $null; retiredAt = $null; revokedAt = $null; stoppedProcessIds = @()
            }
        }
        Save-KeyState $ring $metadata $false
    }
}

if (-not $Quiet) {
    Write-Output "Ticket keys: action=$Action; activeKid=$($ring.activeKid); trusted=$(@($ring.keys.Keys | Sort-Object -CaseSensitive) -join ','); no secrets displayed."
    foreach ($record in @($metadata.records | Where-Object status -ne 'active')) {
        Write-Output "kid=$($record.kid); status=$($record.status); stoppedAt=$($record.stoppedAt); retainUntil=$($record.retainUntil); revokedAt=$($record.revokedAt)"
    }
    if ($Action -ne 'Status') { Write-Output 'Start the application using the updated file. Never restore a revoked key from an old backup.' }
}
} finally {
    $operationLock.Dispose()
}
