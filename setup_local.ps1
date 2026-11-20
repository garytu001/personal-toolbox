$ErrorActionPreference = 'Stop'
$candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")
$sdk = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath "$_\platforms") } | Select-Object -First 1
if (-not $sdk) { throw 'Android SDK not found. Install Android Studio or set ANDROID_HOME.' }
$value = ([IO.Path]::GetFullPath($sdk)).Replace('\', '/').Replace(':', '\:')
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Value "sdk.dir=$value" -Encoding ascii
Write-Output "local.properties configured: $sdk"
if (-not (Test-Path -LiteralPath "$sdk\platforms\android-34\android.jar")) {
    Write-Output 'Install Android SDK Platform 34 in Android Studio > SDK Manager before building.'
}
