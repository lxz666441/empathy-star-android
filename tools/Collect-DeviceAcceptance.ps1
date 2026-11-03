param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [string]$AdbPath,
    [ValidateSet('WeChat', 'QQ')][string]$ChatApp = 'WeChat'
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.lxz.empathystar'
$scanPrefix = if ($ChatApp -eq 'QQ') { 'qq' } else { 'wechat' }
$targetPackage = if ($ChatApp -eq 'QQ') { 'com.tencent.mobileqq' } else { 'com.tencent.mm' }
$serviceName = 'com.lxz.empathystar/.WeChatService'
$compatServiceName = 'com.lxz.empathystar/com.google.android.accessibility.selecttospeak.SelectToSpeakService'

if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        $AdbPath = $adbCommand.Source
    } elseif ($env:ANDROID_SDK_ROOT) {
        $AdbPath = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
    } elseif ($env:ANDROID_HOME) {
        $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else {
        throw '找不到 adb；请通过 -AdbPath 指定 adb.exe，或设置 ANDROID_SDK_ROOT。'
    }
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb.exe 不存在：$AdbPath"
}

function Invoke-DeviceRead([string[]]$Arguments) {
    $result = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "设备只读查询失败：$($Arguments -join ' ')"
    }
    return ($result | Out-String).Trim()
}

$state = Invoke-DeviceRead @('get-state')
if ($state -ne 'device') { throw "设备未就绪：$state" }

$model = Invoke-DeviceRead @('shell', 'getprop', 'ro.product.model')
$androidVersion = Invoke-DeviceRead @('shell', 'getprop', 'ro.build.version.release')
$packageDump = Invoke-DeviceRead @('shell', 'dumpsys', 'package', $packageName)
$versionCodeMatch = [regex]::Match($packageDump, 'versionCode=(\d+).*?targetSdk(?:Version)?=(\d+)')
$versionNameMatch = [regex]::Match($packageDump, 'versionName=([^\s]+)')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw '设备上未找到共情星，或包版本信息无法读取。'
}

$enabledServices = Invoke-DeviceRead @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$serviceDump = Invoke-DeviceRead @('shell', 'dumpsys', 'activity', 'services', $packageName)
$serviceEnabled = $enabledServices.Contains($serviceName) -or $enabledServices.Contains($compatServiceName)
if ($enabledServices.Contains($compatServiceName)) { $serviceName = $compatServiceName }
$serviceRecordPattern = '(?s)\* ServiceRecord\{(?=[^}]*' + [regex]::Escape($serviceName) + ')[^}]*\}.*?isBindService:true'
$serviceBound = [regex]::IsMatch(
    $serviceDump,
    $serviceRecordPattern
)

$activityDump = Invoke-DeviceRead @('shell', 'dumpsys', 'activity', 'activities')
$activityMatch = [regex]::Match($activityDump, 'topResumedActivity=.*?\bu\d+\s+(?<component>[^\s]+)')
$foreground = 'unknown'
if ($activityMatch.Success) {
    $foreground = $activityMatch.Groups['component'].Value
}

Write-Output "Device: $model / Android $androidVersion"
Write-Output "App: $packageName / $($versionNameMatch.Groups[1].Value) (versionCode $($versionCodeMatch.Groups[1].Value), target $($versionCodeMatch.Groups[2].Value))"
Write-Output "Accessibility service: enabled=$serviceEnabled; bound=$serviceBound"
Write-Output "Foreground component: $foreground"

# Read layout flags only. Never print window titles, node text or full dumps.
# SECURE explains screenshot refusal; it does not alone prove why nodes are empty.
try {
    $windowDump = Invoke-DeviceRead @('shell', 'dumpsys', 'window', 'windows')
    . (Join-Path $PSScriptRoot 'WindowProtection.ps1')
    $protection = Get-WeChatWindowProtection -WindowDump $windowDump -ForegroundComponent $foreground
    $foregroundSecure = if ($protection.ForegroundFound) { $protection.ForegroundSecure } else { 'unknown' }
    Write-Output "WeChat window protection: foregroundFound=$($protection.ForegroundFound); foregroundSecure=$foregroundSecure; anyWindowSecure=$($protection.AnySecure)"
} catch {
    Write-Output 'WeChat window protection: unavailable'
}

# Read only the app's non-content diagnostic keys. Whitelist diagnostic formats
# before printing so an unexpected value is suppressed rather than exposed.
$hasCurrentMarkerScan = $false
$scanAgeSeconds = $null
try {
    $scanEntry = Invoke-DeviceRead @('shell', 'run-as', $packageName, 'grep', ($scanPrefix + '_scan_result'), 'shared_prefs/settings.xml')
    $scanMatch = [regex]::Match($scanEntry, '<string name="' + $scanPrefix + '_scan_result">([^<]*)</string>')
    if ($scanMatch.Success) {
        $scanResult = [System.Net.WebUtility]::HtmlDecode($scanMatch.Groups[1].Value)
        $safePatterns = @(
            '^群聊已识别：可见文字=\d+；仅提供总结入口，未上传$',
            '^读取状态=(?:PAUSED|NOT_AUTHORIZED|NO_COMPOSER|NO_WINDOW|WINDOW_UNRESOLVED|UNSUPPORTED|READ_ERROR)；未读取消息、未截屏、未调用模型$',
            '^收到微信事件，但扫描时未找到微信窗口$',
            '^扫描时未找到可读取的微信窗口$',
            '^聊天页诊断：节点=\d+，非空文本叶=\d+，消息文本ID=\d+，消息行祖先=\d+，消息列表祖先=\d+，完整消息结构=\d+，底部编辑框=\d+，按钮=\d+，导航标签=\d+，窗口总数=\d+，微信窗口数=\d+，聚焦微信窗口数=\d+，活动根微信=[01]，活动根子节点=-?\d+，微信根最大子节点=-?\d+；未显示星标$',
            '^聊天页诊断：节点=\d+，非空文本叶=\d+，消息文本ID=\d+，消息行祖先=\d+，消息列表祖先=\d+，完整消息结构=\d+，底部编辑框=\d+，按钮=\d+，导航标签=\d+，窗口总数=\d+，微信窗口数=\d+，聚焦微信窗口数=\d+，活动根微信=[01]，活动根子节点=-?\d+，微信根最大子节点=-?\d+；无障碍树暂空，上一帧星标短暂保留并等待重扫$',
            '^聊天页诊断：节点=\d+，非空文本叶=\d+，消息文本ID=\d+，消息行祖先=\d+，消息列表祖先=\d+，完整消息结构=\d+，底部编辑框=\d+，按钮=\d+，导航标签=\d+，窗口总数=\d+，微信窗口数=\d+，聚焦微信窗口数=\d+，活动根微信=[01]，活动根子节点=-?\d+，微信根最大子节点=-?\d+；无障碍树恢复超时，已清除上一帧星标$',
            '^微信节点 \d+ 个，文字节点 \d+ 个，编辑框 \d+ 个.*$',
            '^聊天输入栏已识别，但未读到文字节点；点击“识别”可在本机 OCR$',
            '^已找到聊天输入栏，但当前系统不支持手动 OCR$',
            '^已找到聊天输入栏，但没有受支持的文字消息；未截屏、未调用模型$',
            '^可见文字疑似敏感密钥，已跳过星标$',
            '^已找到消息，但系统拒绝显示星标$',
            '^已识别 \d+ 条可见文字消息，显示 \d+ 个分析星标，跳过 \d+ 条(?:（按微信底部控件栏定位输入区域）)?$',
            '^方向识别：对方=\d+，自己=\d+，不确定=\d+；对方消息星标=\d+，因位置跳过=\d+(?:（按微信底部控件栏定位输入区域）)?$',
            '^聊天页已识别，但系统拒绝显示手动 OCR 入口$',
            '^用户主动 OCR 完成，未找到安全候选文字$',
            '^用户主动 OCR 完成，找到 \d+ 个候选；等待用户选择$',
            '^OCR 已完成，但系统拒绝显示候选列表$'
        )
        $normalizedScan = if ($ChatApp -eq 'QQ') { $scanResult.Replace('QQ', '微信') } else { $scanResult }
        if ($safePatterns | Where-Object { $normalizedScan -match $_ }) {
            if ($serviceBound) { Write-Output "$ChatApp latest scan: $scanResult" }
            else { Write-Output "$ChatApp stored scan (service not bound; not current): $scanResult" }
            if ($serviceBound -and $scanResult -match '^(?:已识别 \d+ 条可见文字消息，显示 [1-9]\d* 个分析星标，跳过 \d+ 条|方向识别：对方=\d+，自己=\d+，不确定=\d+；对方消息星标=[1-9]\d*，因位置跳过=\d+)') {
                $scanAtEntry = Invoke-DeviceRead @('shell', 'run-as', $packageName, 'grep', ($scanPrefix + '_scan_at'), 'shared_prefs/settings.xml')
                $scanAtMatch = [regex]::Match($scanAtEntry, '<long name="' + $scanPrefix + '_scan_at" value="(\d+)"\s*/>')
                if ($scanAtMatch.Success -and $foreground.StartsWith($targetPackage + '/')) {
                    # The scan timestamp belongs to the phone's clock. Host clock
                    # skew must not turn a fresh phone result into a stale one.
                    $deviceSeconds = Invoke-DeviceRead @('shell', 'date', '+%s')
                    if ($deviceSeconds -notmatch '^\d{10,}$') { throw '设备时钟不可读，无法验证扫描时效' }
                    $scanAgeSeconds = [math]::Max(0, [math]::Round(([long]$deviceSeconds * 1000 - [long]$scanAtMatch.Groups[1].Value) / 1000.0, 1))
                    $hasCurrentMarkerScan = $scanAgeSeconds -le 6
                    Write-Output "Scan age: ${scanAgeSeconds}s"
                }
            }
        } else {
            Write-Output 'Latest scan: unknown format; suppressed for privacy'
        }
    } else {
        Write-Output 'Latest scan: unavailable'
    }
} catch {
    Write-Output 'Latest scan: unavailable (requires a debuggable build)'
}

try {
    $recoveryEntry = Invoke-DeviceRead @('shell', 'run-as', $packageName, 'grep', 'root_recovery_result', 'shared_prefs/settings.xml')
    $recoveryMatch = [regex]::Match($recoveryEntry, '<string name="root_recovery_result">([^<]*)</string>')
    if ($recoveryMatch.Success -and $recoveryMatch.Groups[1].Value -match '^before=-?\d+;refreshed=[01];afterRefresh=-?\d+;cacheCleared=[01];afterQuery=-?\d+$') {
        Write-Output "Root recovery: $($recoveryMatch.Groups[1].Value)"
    } else {
        Write-Output 'Root recovery: unavailable'
    }
} catch {
    Write-Output 'Root recovery: unavailable'
}

try {
    $geometryEntry = Invoke-DeviceRead @('shell', 'run-as', $packageName, 'grep', 'latest_overlay_geometry', 'shared_prefs/layout_diagnostics.xml')
    $geometryMatch = [regex]::Match($geometryEntry, '<string name="latest_overlay_geometry">([^<]*)</string>')
    $anchorListPattern = '-?\d+,-?\d+(?:\|-?\d+,-?\d+)*'
    $geometryPattern = '^count=\d+;metrics=\d+x\d+;composerTop=\d+;editorTop=\d+;first=-?\d+,-?\d+;last=-?\d+,-?\d+;anchors=' + $anchorListPattern + ';targets=' + $anchorListPattern + ';windowOffset=-?\d+,-?\d+$'
    if ($hasCurrentMarkerScan -and $geometryMatch.Success -and $geometryMatch.Groups[1].Value -match $geometryPattern) {
        Write-Output "Latest overlay geometry: $($geometryMatch.Groups[1].Value)"
    } else {
        Write-Output 'Latest overlay geometry: unavailable or not current'
    }
} catch {
    Write-Output 'Latest overlay geometry: unavailable'
}
