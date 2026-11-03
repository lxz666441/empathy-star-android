param(
    [Parameter(Mandatory = $true)][string]$ApkPath,
    [Parameter(Mandatory = $true)][string]$AaptPath,
    [Parameter(Mandatory = $true)][ValidateSet('Debug', 'Release')][string]$Variant
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw 'APK not found' }
if (-not (Test-Path -LiteralPath $AaptPath -PathType Leaf)) { throw 'aapt2 not found' }
$manifest = (& $AaptPath dump xmltree $ApkPath --file AndroidManifest.xml 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Manifest inspection failed' }
foreach ($attribute in @('allowBackup', 'usesCleartextTraffic')) {
    if ($manifest -notmatch (':'+$attribute+'\([^)]*\)=false')) {
        throw "Required false manifest attribute missing: $attribute"
    }
}
$permissions = (& $AaptPath dump permissions $ApkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Permission inspection failed' }
$allowed = @('android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
    'com.lxz.empathystar.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION')
foreach ($match in [regex]::Matches($permissions, "uses-permission: name='([^']+)'")) {
    if ($match.Groups[1].Value -notin $allowed) { throw 'Unexpected requested permission; review APK manually' }
}
$compat = 'com.google.android.accessibility.selecttospeak.SelectToSpeakService'
$expectsDebug = $Variant -eq 'Debug'
if ($manifest.Contains($compat) -ne $expectsDebug) { throw 'Compatibility component boundary failed' }
$debuggable = $manifest -match ':debuggable\([^)]*\)=true'
if ($debuggable -ne $expectsDebug) { throw 'Debuggable boundary failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
$hasCompatCode = $false
$hasSocketListener = $false
$dexCount = 0
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
        $dexCount++
        $stream = $entry.Open()
        $memory = New-Object System.IO.MemoryStream
        try {
            $stream.CopyTo($memory)
            # DEX type descriptors are ASCII; only booleans leave this process.
            $types = [System.Text.Encoding]::ASCII.GetString($memory.ToArray())
            if ($types.Contains('Lcom/google/android/accessibility/selecttospeak/SelectToSpeakService;')) { $hasCompatCode = $true }
            if ($types.Contains('Ljava/net/ServerSocket;')) { $hasSocketListener = $true }
        } finally { $stream.Dispose(); $memory.Dispose() }
    }
} finally { $archive.Dispose() }
if ($dexCount -eq 0) { throw 'No DEX inspected' }
if ($hasCompatCode -ne $expectsDebug) { throw 'Compatibility code boundary failed' }
if (-not $expectsDebug -and $hasSocketListener) { throw 'Socket listener code present in Release; review required' }
Write-Output "PASS: variant=$Variant; backup=false; cleartext=false; permissions=allowlisted; debuggable=$debuggable; compatCode=$hasCompatCode; socketListener=$hasSocketListener"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash)"
Write-Output 'Scope: static package boundary only; not signing, runtime privacy, telemetry or device acceptance.'
