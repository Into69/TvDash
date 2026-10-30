# Starts the Google TV emulator (created by the steps in README.md) and waits until it has booted.
$ErrorActionPreference = 'Stop'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'

Start-Process (Join-Path $sdk 'emulator\emulator.exe') -ArgumentList '-avd', 'GoogleTV', '-no-snapshot-save', '-no-boot-anim'
& $adb wait-for-device
while ((& $adb shell getprop sys.boot_completed 2>$null) -ne '1') { Start-Sleep -Seconds 2 }
Write-Host 'GoogleTV emulator is ready as emulator-5554.'
