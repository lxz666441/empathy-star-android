function Get-WeChatWindowProtection {
    param([string]$WindowDump, [string]$ForegroundComponent)

    function Expand-Component([string]$Component) {
        $parts = $Component -split '/', 2
        if ($parts.Count -ne 2) { return $Component }
        if ($parts[1].StartsWith('.')) { return $parts[0] + '/' + $parts[0] + $parts[1] }
        return $Component
    }
    $foreground = Expand-Component $ForegroundComponent
    $anyFound = $false
    $anySecure = $false
    $foregroundFound = $false
    $foregroundSecure = $false
    $currentComponent = ''
    foreach ($line in ($WindowDump -split "`n")) {
        if ($line -match '^\s*Window #') {
            $currentComponent = ''
            if ($line -match '\b(com\.tencent\.mm/[A-Za-z0-9_.$]+)') {
                $currentComponent = Expand-Component $Matches[1]
                $anyFound = $true
                if ($currentComponent -eq $foreground) { $foregroundFound = $true }
            }
        }
        if ($currentComponent -and $line -match '^\s*fl=' -and $line -match '\bSECURE\b') {
            $anySecure = $true
            if ($currentComponent -eq $foreground) { $foregroundSecure = $true }
        }
    }
    [pscustomobject]@{
        AnyFound = $anyFound
        AnySecure = $anySecure
        ForegroundFound = $foregroundFound
        ForegroundSecure = $(if ($foregroundFound) { $foregroundSecure } else { $null })
    }
}
