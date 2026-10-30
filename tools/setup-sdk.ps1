# Installs a portable JDK 17 and the Android SDK into %LOCALAPPDATA% (no admin needed).
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$base = $env:LOCALAPPDATA
$tmp = Join-Path $env:TEMP 'tvdash-setup'
New-Item -ItemType Directory -Force $tmp | Out-Null

$jdkRoot = Join-Path $base 'Programs\jdk17'
if (-not (Test-Path "$jdkRoot\bin\java.exe")) {
    Write-Host 'Downloading JDK 17...'
    Invoke-WebRequest 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' -OutFile "$tmp\jdk.zip"
    Expand-Archive "$tmp\jdk.zip" "$tmp\jdk" -Force
    $inner = Get-ChildItem "$tmp\jdk" -Directory | Select-Object -First 1
    New-Item -ItemType Directory -Force (Split-Path $jdkRoot) | Out-Null
    Move-Item $inner.FullName $jdkRoot
}
$env:JAVA_HOME = $jdkRoot

$sdk = Join-Path $base 'Android\Sdk'
$cmdline = Join-Path $sdk 'cmdline-tools\latest'
if (-not (Test-Path "$cmdline\bin\sdkmanager.bat")) {
    Write-Host 'Downloading Android command-line tools...'
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile "$tmp\cmdline.zip"
    Expand-Archive "$tmp\cmdline.zip" "$tmp\cmdline" -Force
    New-Item -ItemType Directory -Force (Split-Path $cmdline) | Out-Null
    Move-Item "$tmp\cmdline\cmdline-tools" $cmdline
}

Write-Host 'Installing SDK packages...'
$yes = ('y' + "`n") * 20
$yes | & "$cmdline\bin\sdkmanager.bat" --sdk_root="$sdk" --licenses | Out-Null
& "$cmdline\bin\sdkmanager.bat" --sdk_root="$sdk" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'

[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkRoot, 'User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdk, 'User')
Write-Host "Done. JAVA_HOME=$jdkRoot ANDROID_HOME=$sdk"
