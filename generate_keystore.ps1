param(
    [string]$OutputDirectory = $PSScriptRoot,
    [string]$Alias = 'personaltoolbox'
)
$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$keystore = Join-Path $destination 'release.jks'
$properties = Join-Path $destination 'keystore.properties'
if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $properties)) {
    throw 'release.jks or keystore.properties already exists. Keep the existing signing key; nothing was overwritten.'
}
if ($Alias -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Alias must contain letters, digits, underscore or hyphen.' }
$keytool = if ($env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\keytool.exe")) {
    "$env:JAVA_HOME\bin\keytool.exe"
} elseif (Get-Command keytool -ErrorAction SilentlyContinue) {
    (Get-Command keytool).Source
} elseif (Test-Path -LiteralPath 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe') {
    'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
} else { throw 'Install JDK 17+ or set JAVA_HOME before running this script.' }

# Generate an independent strong password without requiring interactive input.
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$random.GetBytes($bytes)
$random.Dispose()
$password = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
$env:TOOLBOX_KEYTOOL_PASSWORD = $password
try {
    & $keytool -genkeypair -noprompt -storetype JKS -keystore $keystore `
        -storepass:env TOOLBOX_KEYTOOL_PASSWORD -keypass:env TOOLBOX_KEYTOOL_PASSWORD `
        -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 `
        -dname 'CN=Personal Toolbox, OU=Personal, O=Personal Toolbox, C=TW'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }
    @("storeFile=release.jks", "storePassword=$password", "keyAlias=$Alias", "keyPassword=$password") |
        Set-Content -LiteralPath $properties -Encoding ascii
    Write-Output "Created $keystore and $properties. Back up both files securely and keep this key for all releases."
    Write-Output ''
    Write-Output 'GitHub Actions secrets (do not publish this output):'
    Write-Output "KEYSTORE_PASSWORD=$password"
    Write-Output "KEY_ALIAS=$Alias"
    Write-Output "KEY_PASSWORD=$password"
    Write-Output 'KEYSTORE_BASE64='
    Write-Output ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)))
} finally {
    Remove-Item Env:TOOLBOX_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
}
