<#
.SYNOPSIS
    Install TvDash on Android/Google TVs over the network and strip the stock home screen.

.EXAMPLE
    .\tools\tv.ps1 install 192.168.1.50 192.168.1.51   # install/update TvDash and make it the home screen
    .\tools\tv.ps1 debloat 192.168.1.50                # disable stock launcher, recommendations and ads
    .\tools\tv.ps1 restore 192.168.1.50                # undo debloat (re-enable everything in the list)
    .\tools\tv.ps1 packages 192.168.1.50               # list installed packages, to find more bloat
    .\tools\tv.ps1 install emulator-5554               # same commands work against the Google TV emulator
    .\tools\tv.ps1 wunderground 192.168.1.50 -Station KONTORON123   # weather from your PWS (built-in key)
    .\tools\tv.ps1 wunderground 192.168.1.50 -Off                                 # back to Open-Meteo
    .\tools\tv.ps1 export 192.168.1.50                 # save this TV's layout to layouts\layout.json (master copy)
    .\tools\tv.ps1 import 192.168.1.51                 # apply it on another TV; it offers to install missing apps

.NOTES
    On each TV first enable: Settings > System > About > tap "Build" 7 times, then
    Settings > System > Developer options > USB debugging (and "Network debugging" if listed).
    The first connection shows an "Allow debugging?" prompt on the TV - tick "Always allow".
#>
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('install', 'debloat', 'restore', 'packages', 'wunderground', 'export', 'import')]
    [string]$Command,

    [Parameter(Mandatory, Position = 1, ValueFromRemainingArguments)]
    [string[]]$Tv,

    # Extra packages to disable on top of the built-in list (e.g. vendor bloat found with 'packages').
    [string[]]$Extra = @(),

    # Weather Underground station ID, and optionally a PWS API key to use instead of the
    # built-in one (wu.apiKey in local.properties).
    [string]$Station,
    [string]$ApiKey,
    [switch]$Off,

    # Layout file for export/import (favorites, order, placeholders, cameras, inputs, weather).
    [string]$File = (Join-Path $PSScriptRoot '..\layouts\layout.json')
)

if ($Command -eq 'wunderground' -and -not $Off -and -not $Station) {
    throw "wunderground needs -Station (or -Off)."
}

$ErrorActionPreference = 'Stop'
$Launcher = 'dev.tvdash.launcher'
$Apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'

# Stock home screens and the services that feed them ads/"recommendations".
# Everything here is disabled for user 0 only (no root) and comes back with 'restore' or a factory reset.
$Bloat = @(
    'com.google.android.apps.tv.launcherx'          # Google TV home screen (the ads)
    'com.google.android.tungsten.setupwraith'       # Google TV setup shell; re-asserts launcherx as home
    'com.google.android.tvlauncher'                 # Android TV home screen
    'com.google.android.leanbacklauncher'           # older Android TV home screen
    'com.google.android.tvrecommendations'          # Android TV recommendation feed
    'com.google.android.leanbacklauncher.recommendations'
    'com.sony.dtv.promos'                           # Sony BRAVIA promotions/ads
    'com.google.android.apps.tv.dreamx'             # Google TV Ambient mode screensaver (TvDash has its own)
    'com.google.android.backdrop'                   # Android TV Backdrop screensaver (e.g. NVIDIA SHIELD)
) + $Extra

# Keys and addresses TvDash needs but the published APK doesn't contain, from local.properties
# (property name -> DeviceConfigReceiver extra).
$DeviceConfig = [ordered]@{
    'wu.station'       = 'wu_default_station'
    'wu.apiKey'        = 'wu_default_key'
    'unifi.host'       = 'unifi_host'
    'unifi.apiKey'     = 'unifi_key'
    'unifi.certSha256' = 'unifi_cert_sha256'
}

function Read-LocalProperties {
    $path = Join-Path $PSScriptRoot '..\local.properties'
    $props = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
        }
    }
    $props
}

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }

function Invoke-Adb([string]$Serial, [string[]]$Arguments) {
    $out = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb $($Arguments -join ' ') failed on ${Serial}: $out" }
    $out
}

function Connect-Tv([string]$Address) {
    # Already attached under this serial: an emulator, or a TV adb found by itself over
    # Wireless debugging (adb-<id>._adb-tls-connect._tcp; see 'adb devices'), whose port changes on restart.
    $attached = & $adb -s $Address get-state 2>$null
    if ($Address -like 'emulator-*' -or $Address -like 'adb-*' -or $attached -eq 'device') {
        $serial = $Address
    } else {
        $serial = if ($Address -match ':\d+$') { $Address } else { "${Address}:5555" }
        $out = & $adb connect $serial 2>&1
        if ($out -notmatch 'connected') { throw "Could not connect to ${serial}: $out" }
    }
    $state = & $adb -s $serial get-state 2>&1
    if ($state -ne 'device') {
        throw "$serial is '$state'. Accept the 'Allow debugging' prompt on the TV, then run again."
    }
    $serial
}

function Test-Installed([string]$Serial, [string]$Package) {
    $list = Invoke-Adb $Serial @('shell', 'pm', 'list', 'packages', $Package)
    [bool]($list | Where-Object { $_ -eq "package:$Package" })
}

# Some builds protect individual packages from the shell; skip those instead of stopping the whole TV.
function Set-PackageState([string]$Serial, [string]$Package, [string[]]$PmArgs, [string]$Done) {
    try {
        Invoke-Adb $Serial (@('shell', 'pm') + $PmArgs + $Package) | Out-Null
        Write-Host "  $Done $Package"
    } catch {
        Write-Host "  skipped $Package (protected on this device)" -ForegroundColor Yellow
    }
}

foreach ($address in $Tv) {
    try {
        $serial = Connect-Tv $address
        $model = (Invoke-Adb $serial @('shell', 'getprop', 'ro.product.model')) -join ''
        Write-Host "== $serial ($model)" -ForegroundColor Cyan

        switch ($Command) {
            'install' {
                if (-not (Test-Path $Apk)) { throw "APK not found at $Apk - run .\gradlew.bat assembleDebug first." }
                # -d: a local build can have a lower build number than a release the TV updated to.
                Invoke-Adb $serial @('install', '-r', '-d', $Apk) | Out-Null
                # Keys and addresses from local.properties (the APK has none; it's published).
                $props = Read-LocalProperties
                $extras = foreach ($name in $DeviceConfig.Keys) {
                    if ($props[$name]) { '--es', $DeviceConfig[$name], "'$($props[$name] -replace "'", '')'" }
                }
                if ($extras) {
                    Invoke-Adb $serial (@('shell', 'am', 'broadcast', '-n', "$Launcher/.DeviceConfigReceiver") + $extras) | Out-Null
                }
                # Self-update from GitHub releases (Updates tile).
                Invoke-Adb $serial @('shell', 'appops', 'set', $Launcher, 'REQUEST_INSTALL_PACKAGES', 'allow') | Out-Null
                Invoke-Adb $serial @('shell', 'cmd', 'package', 'set-home-activity', "$Launcher/.MainActivity") | Out-Null
                # The Logs tile reads the system DropBox (restart reasons, crashes); only adb can allow that.
                Invoke-Adb $serial @('shell', 'pm', 'grant', $Launcher, 'android.permission.READ_LOGS') | Out-Null
                Invoke-Adb $serial @('shell', 'appops', 'set', $Launcher, 'GET_USAGE_STATS', 'allow') | Out-Null
                # Now playing: reading other apps' media sessions needs an enabled notification listener.
                Invoke-Adb $serial @('shell', 'cmd', 'notification', 'allow_listener', "$Launcher/$Launcher.MediaListener") | Out-Null
                # Screensaver: TvDash's, in place of Google TV's Ambient mode.
                Invoke-Adb $serial @('shell', 'settings', 'put', 'secure', 'screensaver_components', "$Launcher/.TvDashDream") | Out-Null
                Invoke-Adb $serial @('shell', 'settings', 'put', 'secure', 'screensaver_enabled', '1') | Out-Null
                # Lets the Home screen settings change when the screensaver starts, or turn it off.
                Invoke-Adb $serial @('shell', 'pm', 'grant', $Launcher, 'android.permission.WRITE_SECURE_SETTINGS') | Out-Null
                Invoke-Adb $serial @('shell', 'appops', 'set', $Launcher, 'WRITE_SETTINGS', 'allow') | Out-Null
                Write-Host "  installed and set as home. Run 'debloat' to remove the stock home screen."
            }
            'debloat' {
                # Never leave a TV without a home screen.
                if (-not (Test-Installed $serial $Launcher)) { throw "TvDash is not installed here - run 'install' first." }
                foreach ($pkg in $Bloat) {
                    if (Test-Installed $serial $pkg) {
                        Set-PackageState $serial $pkg @('disable-user', '--user', '0') 'disabled'
                    }
                }
                Invoke-Adb $serial @('shell', 'cmd', 'package', 'set-home-activity', "$Launcher/.MainActivity") | Out-Null
                Invoke-Adb $serial @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
                Write-Host "  done. Re-run after system updates, which can re-enable the stock launcher."
            }
            'restore' {
                foreach ($pkg in $Bloat) {
                    if (Test-Installed $serial $pkg) {
                        Set-PackageState $serial $pkg @('enable') 'enabled'
                    }
                }
                # Hand the screensaver back to Google's: Ambient mode on Google TV, Backdrop on Android TV.
                if (Test-Installed $serial 'com.google.android.apps.tv.dreamx') {
                    Invoke-Adb $serial @('shell', 'settings', 'put', 'secure', 'screensaver_components', 'com.google.android.apps.tv.dreamx/.service.Backdrop') | Out-Null
                    Write-Host "  screensaver: Google Ambient mode"
                } elseif (Test-Installed $serial 'com.google.android.backdrop') {
                    Invoke-Adb $serial @('shell', 'settings', 'put', 'secure', 'screensaver_components', 'com.google.android.backdrop/.Backdrop') | Out-Null
                    Write-Host "  screensaver: Backdrop"
                }
            }
            'wunderground' {
                if (-not (Test-Installed $serial $Launcher)) { throw "TvDash is not installed here - run 'install' first." }
                $extras = if ($Off) { @('--ez', 'wu_clear', 'true') }
                          elseif ($ApiKey) { @('--es', 'wu_station', $Station, '--es', 'wu_key', $ApiKey) }
                          else { @('--es', 'wu_station', $Station) }
                Invoke-Adb $serial (@('shell', 'am', 'start', '-n', "$Launcher/.MainActivity") + $extras) | Out-Null
                if ($Off) { Write-Host '  weather source: Open-Meteo' } else { Write-Host "  weather source: Weather Underground station $Station" }
            }
            'export' {
                if (-not (Test-Installed $serial $Launcher)) { throw "TvDash is not installed here." }
                # The app writes the layout in the background (no UI), then run-as reads it back.
                & $adb -s $serial shell run-as $Launcher rm -f files/layout.json 2>$null | Out-Null
                Invoke-Adb $serial @('shell', 'am', 'broadcast', '-n', "$Launcher/.LayoutExportReceiver") | Out-Null
                $json = $null
                foreach ($i in 1..20) {
                    Start-Sleep -Milliseconds 500
                    $out = & $adb -s $serial shell run-as $Launcher cat files/layout.json 2>$null
                    if ($LASTEXITCODE -eq 0 -and $out) { $json = $out -join "`n"; break }
                }
                if (-not $json) { throw "TvDash didn't write a layout (is this the current build? run 'install' first)." }
                $layout = $json | ConvertFrom-Json
                # Keep hidden apps the previous layout collected from other TVs (Sony/Realtek extras this
                # TV doesn't have), so re-exporting the master doesn't drop them. Apps this TV does have
                # follow its own hidden list.
                if ((Test-Path $File) -and $layout.settings.hidden) {
                    $old = Get-Content $File -Raw | ConvertFrom-Json
                    $here = @($layout.labels.PSObject.Properties.Name)
                    $carried = @($old.settings.hidden.value) | Where-Object { $_ -and $_ -notin $here -and $_ -notin @($layout.settings.hidden.value) }
                    if ($carried) {
                        $layout.settings.hidden.value = @(@($layout.settings.hidden.value) + $carried)
                        foreach ($p in $carried) {
                            if ($old.labels.$p -and -not $layout.labels.PSObject.Properties[$p]) { $layout.labels | Add-Member -NotePropertyName $p -NotePropertyValue $old.labels.$p }
                        }
                        $json = $layout | ConvertTo-Json -Depth 10
                        Write-Host "  kept $(@($carried).Count) hidden app(s) from other TVs"
                    }
                }
                New-Item -ItemType Directory -Force (Split-Path $File) | Out-Null
                Set-Content -Path $File -Value $json -Encoding utf8
                $apps = @(($layout.settings.app_order.value -split ',') | Where-Object { $_ -and $_ -notmatch '^(placeholder|camera):' })
                Write-Host "  saved layout from $($layout.model) to $File ($($apps.Count) apps in order)"
            }
            'import' {
                if (-not (Test-Installed $serial $Launcher)) { throw "TvDash is not installed here - run 'install' first." }
                if (-not (Test-Path $File)) { throw "No layout at $File - run 'export' on the master TV first." }
                $json = Get-Content $File -Raw
                $layout = $json | ConvertFrom-Json
                $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
                Invoke-Adb $serial @('shell', 'am', 'start', '-n', "$Launcher/.MainActivity", '--es', 'layout_import', $encoded) | Out-Null
                # Same check the TV does, so the PC shows what's missing too.
                $installed = Invoke-Adb $serial @('shell', 'pm', 'list', 'packages') | ForEach-Object { $_ -replace '^package:' }
                $wanted = @($layout.settings.favorites.value -split ',') + @($layout.settings.app_order.value -split ',') |
                    Where-Object { $_ -and $_ -notmatch '^(placeholder|camera):' } | Select-Object -Unique
                Write-Host "  imported layout from $($layout.model)."
                # Device editions (e.g. ...livingroom.nvidia) stand in for the master's app, as on the TV.
                $missing = @()
                foreach ($pkg in @($wanted | Where-Object { $_ -notin $installed })) {
                    $edition = @($installed | Where-Object { $_.StartsWith("$pkg.") })
                    if ($edition.Count -eq 1) { Write-Host "  using $($edition[0]) for $($layout.labels.$pkg ?? $pkg)" } else { $missing += $pkg }
                }
                if ($missing.Count) {
                    Write-Host "  $($missing.Count) app(s) not installed here - the TV is asking whether to install or skip each:"
                    $missing | ForEach-Object { Write-Host "    $($layout.labels.$_ ?? $_)  ($_)" }
                }
            }
            'packages' {
                $disabled = Invoke-Adb $serial @('shell', 'pm', 'list', 'packages', '-d')
                Invoke-Adb $serial @('shell', 'pm', 'list', 'packages') |
                    ForEach-Object { $_ -replace '^package:' } | Sort-Object |
                    ForEach-Object { if ($disabled -contains "package:$_") { "  $_  (disabled)" } else { "  $_" } }
            }
        }
    } catch {
        Write-Host "  $($_.Exception.Message)" -ForegroundColor Red
    }
}
