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
# Only accept Home Assistant notifications from these addresses (comma-separated);
# without it, anything on the home network can send them
ha.allowFrom=192.168.1.10
```

To send these settings to a TV that updates itself, without reinstalling, run `.\tools\tv.ps1 grant <ip>`.

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
- **Home Assistant notifications**: see below. Turn them off, or send a test, from the **Home screen** tile.
- Weather comes from [Open-Meteo](https://open-meteo.com): no account, no API key.

## Home Assistant notifications

TvDash listens on port 7676, the port Home Assistant's
[Notifications for Android TV / Fire TV](https://www.home-assistant.io/integrations/nfandroidtv/) integration uses.
In Home Assistant, go to **Settings > Devices & services > Add integration**, pick that integration, and enter
the TV's address. The **Home screen** tile shows the address. Then send to the TV with its notify action:

```yaml
action: notify.living_room_tv
data:
  title: Front door
  message: Someone is at the front door
  data:
    duration: 15                # seconds (default 5)
    position: top-right         # bottom-right (default), bottom-left, top-left, center
    color: red                  # a colour strip: red, green, amber, indigo, cyan, teal, pink, black
    fontsize: large             # small, medium, large, max
    transparency: 25%           # 0% (default), 25%, 50%, 75%, 100%
    image:
      url: http://homeassistant.local:8123/api/camera_proxy/camera.front_door
      # Home Assistant fetches the image and sends it to the TV; add username/password/auth if needed
```

A plain JSON POST works too, e.g. from a `rest_command`:

```powershell
curl -H "Content-Type: application/json" -d '{"title":"Washer","message":"The laundry is done.","duration":10}' http://192.168.1.50:7676/
```

Notifications show over whatever app is open (not over the TV's Settings, which hides all overlays) and
in the TvDash screensaver. Showing over other apps needs a permission only adb can give: run
`.\tools\tv.ps1 grant <ip>` once per TV. Without it, notifications only show while the TvDash home screen
or screensaver is on screen. The integration can't send a password, so TvDash only accepts
notifications from the home network, or only from `ha.allowFrom` if you set it.
