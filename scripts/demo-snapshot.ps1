param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[A-Z0-9][A-Z0-9_-]{0,15}$')][string]$Namespace = 'W2'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'week2-api.ps1')
Assert-Week2LocalUrl $BaseUrl
$seedData = & (Join-Path $PSScriptRoot 'seed-week2.ps1') -BaseUrl $BaseUrl -Namespace $Namespace
$before = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($seedData.sessionId)/seats")
$beforeJson = $before | ConvertTo-Json -Depth 12 -Compress
$source = Invoke-Week2Api $BaseUrl GET "/api/venues/$($seedData.venueId)/seat-templates" |
    Where-Object { $_.areaCode -eq 'FLOOR-A' -and $_.rowLabel -eq 'A' -and $_.seatNumber -eq 1 } | Select-Object -First 1
$restore = @{
    areaId = $source.areaId; rowLabel = $source.rowLabel; rowOrder = $source.rowOrder; seatNumber = $source.seatNumber
    coordinateX = $source.coordinateX; coordinateY = $source.coordinateY; aisleSegment = $source.aisleSegment; enabled = $source.enabled
}
$changed = $restore.Clone()
$changed.coordinateX = 777.125
$editedCode = "SESSION-EDITED-$Namespace"
try {
    $null = Invoke-Week2Api $BaseUrl PUT "/api/seat-templates/$($source.id)" $changed
    $afterJson = @(Invoke-Week2Api $BaseUrl GET "/api/sessions/$($seedData.sessionId)/seats") | ConvertTo-Json -Depth 12 -Compress
    if ($beforeJson -ne $afterJson) { throw 'Old snapshot changed after editing its template.' }
    $newSession = Invoke-Week2Api $BaseUrl GET "/api/sessions/by-code/$editedCode" -AllowMissing
    if ($null -eq $newSession) {
        $newSession = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seedData $editedCode)
    }
    $newSeat = Invoke-Week2Api $BaseUrl GET "/api/sessions/$($newSession.id)/seats" |
        Where-Object sourceSeatTemplateId -eq $source.id | Select-Object -First 1
    if ([decimal]$newSeat.coordinateXSnapshot -ne 777.125) { throw 'New snapshot did not copy the changed template.' }
    $duplicateRejected = $false
    try { $null = Invoke-Week2Api $BaseUrl POST '/api/sessions' (New-Week2SessionPayload $BaseUrl $seedData $editedCode) }
    catch {
        if ($null -ne $_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 409) { $duplicateRejected = $true }
        else { throw }
    }
    if (-not $duplicateRejected) { throw 'Duplicate session code was not rejected.' }
    Write-Host "PASS: old snapshot unchanged; new snapshot x=777.125; duplicate creation returned 409."
    Write-Host "Old session=$($seedData.sessionId) ($($seedData.sessionCode)); new session=$($newSession.id) ($editedCode)."
} finally {
    $null = Invoke-Week2Api $BaseUrl PUT "/api/seat-templates/$($source.id)" $restore
    Write-Host 'Restored the source template so the same demo can run again.'
}
