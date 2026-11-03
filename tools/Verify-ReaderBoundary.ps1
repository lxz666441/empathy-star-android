param()
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '..\app\src\main\java\com\lxz\empathystar'
$service = Get-Content -LiteralPath (Join-Path $source 'WeChatService.java') -Raw
if ($service -match 'AccessibilityNodeInfo|AccessibilityWindowInfo|ChatAppAdapter|getRootInActiveWindow\s*\(|getWindows\s*\(') {
    throw 'UI service reaches through the reader boundary'
}
foreach ($name in @('ScreenReader.java', 'ScreenReadResult.java')) {
    $text = Get-Content -LiteralPath (Join-Path $source $name) -Raw
    if ($text -match 'import\s+android\.|import\s+java\.net\.|HttpURLConnection|SharedPreferences') {
        throw "Reader contract has a platform, settings or network dependency: $name"
    }
}
$backend = Get-Content -LiteralPath (Join-Path $source 'AccessibilityChatReader.java') -Raw
if ($backend -match 'ACTION_CLICK|ACTION_PASTE|getRootInActiveWindow|takeScreenshot|ChatModelClient') {
    throw 'Reader backend includes sending, clipboard, screenshots or model functionality'
}
$scanner = Get-Content -LiteralPath (Join-Path $source 'NodeMessageScanner.java') -Raw
if ($scanner -match 'getWindows\s*\(|\.getRoot\s*\(|WindowManager|HttpURLConnection|ChatModelClient') {
    throw 'Message parser reaches outside structure parsing'
}
Write-Output 'PASS: UI -> pure reader contract -> Android backend -> app adapter / node parser'
Write-Output 'Scope: static dependency boundary only, not Android runtime or chat acceptance.'
