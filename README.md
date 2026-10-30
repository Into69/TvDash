# TvDash

An ad-free home screen for Android TV / Google TV: a clock, the weather, your apps, and shortcuts for inputs and settings. Nothing else.

## Build

```powershell
.\tools\setup-sdk.ps1          # one time: portable JDK 17 + Android SDK in %LOCALAPPDATA%
.\gradlew.bat assembleDebug    # -> app\build\outputs\apk\debug\app-debug.apk
```

The APK contains no keys. Optional settings go in `local.properties` (not checked in), and
`tv.ps1 install` sends them to each TV:

```properties
# Default Weather Underground station and PWS API key
wu.station=KONTORON123
wu.apiKey=...
# UniFi Protect console for camera tiles: address, integration API key, and the
# SHA-256 of its self-signed certificate
unifi.host=192.168.1.1
unifi.apiKey=...
unifi.certSha256=AB:CD:...
```

## Updates

Every push to `main` builds a release (`build-<n>`, where n is the commit count) with GitHub Actions.
On a TV, open **Updates > TvDash** to install the newest one. The first update after an adb install asks
for confirmation; after that, Android 12+ TVs update without asking.

Releases are signed with the debug keystore from `%USERPROFILE%\.android\debug.keystore`, stored as the
repository secret `DEBUG_KEYSTORE_BASE64`. TVs only accept updates signed with the key their current
build has, so keep a backup of that file.

## Try it on the PC first

A Google TV emulator (Android 14) is set up as `GoogleTV`. Use the PC keyboard's arrow keys, Enter and Esc as the remote,
or the D-pad in the emulator's side panel. The first boot shows a black screen for 2–4 minutes; that's normal.

```powershell
.\tools\emulator.ps1                     # boots it and waits until it's ready
.\tools\tv.ps1 install emulator-5554
.\tools\tv.ps1 debloat emulator-5554
```

## Put it on a TV

On the TV: **Settings > System > About**, select **Build** 7 times, then turn on
**Developer options > USB debugging** (and **Network debugging** if it's there). Find the TV's IP under
**Settings > Network**.

```powershell
.\tools\tv.ps1 install 192.168.1.50 192.168.1.51   # accept "Allow debugging?" on each TV the first time
.\tools\tv.ps1 debloat 192.168.1.50 192.168.1.51   # disable the stock launcher + recommendations
```

Undo with `.\tools\tv.ps1 restore <ip>`. Use `.\tools\tv.ps1 packages <ip>` to look for vendor bloat, then
disable it with `.\tools\tv.ps1 debloat <ip> -Extra com.vendor.thing`.

A system update can turn the stock launcher back on. If ads come back, run `debloat` again.

## Using it

- **Long-press OK** (or **Menu**) on an app to add it to Favorites, reorder favorites, hide it, or open App info.
- **Home screen** tile: set the weather city, switch °C/°F, and unhide apps.
- **Updates** tile: update TvDash, the TV's software, or apps from the Play Store.
- **Logs** tile: why the TV last restarted (including kernel crashes and freezes) and recent app crashes.
- Press **Up** from the top row to reach the weather and change the city.
- Weather comes from [Open-Meteo](https://open-meteo.com): no account, no API key.
