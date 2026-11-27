# Installing Rozan

Rozan is installed from a computer with adb. This page covers the devices it
runs on, installing and updating it, letting it update itself, making the
Home button open it, the
standalone [Rozan AirPlay](#rozan-airplay) app, and getting back to the stock
home screen. The device-specific steps have not yet been tried on TV
hardware, and menu paths can differ by build.

## Which devices can run it

Rozan needs Android 9 or later. On Fire TV that means Fire OS 7 or later.

| Device | Runs Rozan | What the Home button can do |
| --- | --- | --- |
| Fire TV on Fire OS 7, 8 or 14 | Yes | Redirect only |
| Fire TV on Fire OS 6 or earlier | No | |
| Google TV | Yes | Redirect only |
| NVIDIA Shield TV, every model, on Shield Experience 8 or 9 | Yes | Redirect, or Rozan as the default home |
| Other devices with the classic Android TV home (Mi Box S, Sony and Philips Android TV) | Yes | Redirect, or Rozan as the default home |
| Fire TV sticks on Vega OS (4K Select, HD 2nd gen, 4K 3rd gen) | No | |
| LG webOS, Samsung Tizen, Roku | No | |

The redirect is [step 5](#5-turn-on-the-home-button-redirect); Rozan as the
default home is [step 6](#6-rozan-as-the-real-default-home).

Roku OS is not Android, so neither Rozan nor Rozan AirPlay runs on it. Most
Roku TVs and players have AirPlay built in, under Settings > Apple AirPlay
and HomeKit. To use Rozan with a Roku TV, plug a Fire TV or Android TV device
into one of its HDMI ports and set the TV's Settings > System > Power >
Power on to that input.

## 1. Turn on adb

1. On the TV, turn on developer options and debugging:
   - Fire TV: in Settings > My Fire TV > About, select the device name seven
     times. Then turn on Settings > My Fire TV > Developer options > ADB
     debugging.
   - Google TV: in Settings > System > About, select Android TV OS build
     seven times. Then turn on USB debugging or network debugging under
     Settings > System > Developer options.
   - NVIDIA Shield: in Settings > Device Preferences > About, select Build
     seven times. Then turn on Network debugging under Settings > Device
     Preferences > Developer options.
   - Other Android TV: the path differs by model. It is usually Build under
     About, selected seven times, then debugging under Developer options.
2. On a computer on the same network, with adb installed (Android SDK
   Platform-Tools), connect to the TV. Its IP address is in the TV's network
   settings.

   ```sh
   adb connect <device-ip>:5555
   ```

   If the TV offers Wireless debugging with a pairing code instead, run
   `adb pair <address>` with the pairing address and code it shows, then
   `adb connect` to the address and port on the Wireless debugging screen.
3. The TV asks whether to allow debugging from this computer. Allow it.

## 2. Install the APK

1. From the latest Rozan release on GitHub, download
   `rozan-launcher-X.Y.Z.apk` and `SHA256SUMS` into one folder.
2. Check the download. In that folder, run:

   ```sh
   shasum -a 256 -c --ignore-missing SHA256SUMS
   ```

   It must print `./rozan-launcher-X.Y.Z.apk: OK`. On Linux, use `sha256sum`
   in place of `shasum -a 256`. On Windows,
   `certutil -hashfile rozan-launcher-X.Y.Z.apk SHA256` prints the hash to
   compare with its line in `SHA256SUMS`. If the check fails, do not install
   the file; download it again.
3. Install it:

   ```sh
   adb install -r rozan-launcher-X.Y.Z.apk
   ```

Rozan then appears in the stock home screen's app list. Opening it from
there always works, whether or not the Home button opens it. Rozan's own
settings open from the gear at the top right of its home screen.

### Updating

Download and check the new release as above, then run the same
`adb install -r` command. Rozan keeps its settings. Afterwards, check the
"Home button" and "Show over other apps" rows in Rozan's settings, and redo
step 3 or step 5 if either has changed. Rozan can also install new releases
itself ([step 4](#4-let-rozan-update-itself)).

An update installs only over a copy signed with the same key. If adb reports
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall Rozan first with
`adb uninstall ai.aneesiqbal.rozan.launcher`, which deletes its settings, then
do steps 2 to 5 again.

## 3. Let AirPlay show over other apps

When an Apple device connects, Rozan shows a code on the TV, and after
pairing it shows the mirrored screen. Without the "draw over other apps"
permission, both appear only while Rozan is open. TVs often have no settings
screen for this permission, so allow it with adb:

```sh
adb shell appops set ai.aneesiqbal.rozan.launcher SYSTEM_ALERT_WINDOW allow
adb shell appops get ai.aneesiqbal.rozan.launcher SYSTEM_ALERT_WINDOW   # should print allow
```

In Rozan's settings, under AirPlay, "Show over other apps" then reads
"Allowed". While it reads "Not allowed", the row shows the command above. To
take the permission back, run the first command with `default` in place of
`allow`. [AIRPLAY.md](AIRPLAY.md) describes using the receiver.

## 4. Let Rozan update itself

With "Update automatically" on, under Updates in Rozan's settings, Rozan
checks GitHub once a day for a new release and installs it
([LAUNCHER.md](LAUNCHER.md#updates)). The switch is off by default. To
install the updates it downloads, Rozan needs the "install unknown apps"
permission. It is needed only for automatic updates. Allow it with adb:

```sh
adb shell appops set ai.aneesiqbal.rozan.launcher REQUEST_INSTALL_PACKAGES allow
adb shell appops get ai.aneesiqbal.rozan.launcher REQUEST_INSTALL_PACKAGES   # should print allow
```

In Rozan's settings, under Updates, "Install unknown apps" then reads
"Allowed". While it reads "Not allowed", the row shows the command above. To
take the permission back, run the first command with `default` in place of
`allow`. Android closes Rozan when the permission is taken back.

- On Android 12 and later, Rozan installs an update without asking, at a
  time when no Rozan screen is showing and AirPlay is not in use.
- On Android 11 and earlier, which includes Fire OS 7 and 8, Android asks to
  confirm every update. Rozan downloads and checks the update, then shows
  "Install update X.Y.Z" under Updates; selecting it opens Android's
  confirmation.

Installing an update restarts Rozan's processes, as an update with adb does,
so afterwards check the rows named under [Updating](#updating).

## 5. Turn on the Home button redirect

Rozan's accessibility service, "Rozan home button", opens Rozan whenever Home
brings up the stock home screen. It can be used on every device that runs
Rozan, and it is the only way on Fire TV and Google TV, where Rozan cannot
or should not be the default home
([step 6](#6-rozan-as-the-real-default-home)). It reads only which app's
screen is in front, and it does not read or block keys. This page calls it
the redirect.

### On the TV

| Device | Accessibility settings |
| --- | --- |
| Google TV | Settings > System > Accessibility |
| Android TV | Settings > Device Preferences > Accessibility. Some versions list Rozan home button under Services. |
| Fire TV | Fire OS 8.1.5.5 and later do not list Rozan home button; use [adb](#with-adb). Older builds may list it under Settings > Accessibility. |

1. Open the accessibility settings. On Google TV and Android TV, selecting
   the "Home button" row in Rozan's settings opens them.
2. Select "Rozan home button", turn it on and accept the system's warning.

On Android 13 and later the switch can be greyed out as a "restricted
setting" because Rozan was sideloaded. Allow restricted settings on Rozan's
App info page first, or use adb.

### With adb

These commands add Rozan's service to the list of enabled accessibility
services and keep every service already in it, such as TalkBack or a remote
mapper. Do not write a list that contains only Rozan's service: that turns
the others off.

```sh
SVC=ai.aneesiqbal.rozan.launcher/.redirect.HomeRedirectService
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in
  null|"") new=$SVC ;;
  *"$SVC"*) new=$cur ;;
  *) new="$cur:$SVC" ;;
esac
adb shell settings put secure enabled_accessibility_services "$new"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services   # must include Rozan's service
```

### Check it

In Rozan's settings, the "Home button" row reads one of:

- "On": the redirect is running.
- "Turned on, not running": the service is turned on, but the system has not
  started it. See the next section.
- "Off": the service is turned off, or Home opens a screen that Rozan does
  not replace, such as setup or the system's app chooser. The text under the
  row says which.
- "Opens Rozan": Rozan is the default home ([step 6](#6-rozan-as-the-real-default-home)),
  so the redirect is not needed.

Under the row, Rozan names the stock home screen it replaces, such as
`com.google.android.tvlauncher/.MainActivity`. While the redirect is off or
not running, the section also shows the adb commands that turn it on.

### If it does not turn on or stay on

- The last command under [With adb](#with-adb) does not show Rozan's
  service: write the list through the settings provider instead, in the same
  terminal:

  ```sh
  adb shell content insert --uri content://settings/secure --bind name:s:enabled_accessibility_services --bind "value:s:$new"
  ```

- "Turned on, not running": switch the service off and on again in the
  accessibility settings. With adb, run:

  ```sh
  adb shell settings put secure accessibility_enabled 0
  adb shell settings put secure accessibility_enabled 1
  ```

  If it still does not run, remove Rozan's service from the list
  ([commands](#turn-the-redirect-off-with-adb)) and add it again
  ([commands](#with-adb)). On Fire TV, turn parental controls off while you
  do this.
- Not running after every restart, as happens on TCL and some onn Google TV
  builds: let Rozan start on its own and exempt it from idle.

  ```sh
  adb shell appops set ai.aneesiqbal.rozan.launcher AUTO_START allow
  adb shell dumpsys deviceidle whitelist +ai.aneesiqbal.rozan.launcher
  ```

  Some builds name the first operation `APP_AUTO_START`; if `AUTO_START` is
  refused, use that name. To undo, run the first command with `default` in
  place of `allow`, and the second with `-` in place of `+`.
- "Off" after an update of Rozan, a reset of the system settings or a force
  stop: these can remove the service from the list. Turn it on again.
- Profiles: on Google TV each profile has its own accessibility settings,
  and Fire TV profiles may too. Turn the redirect on in each profile, or run
  the adb commands with `settings --user <id>` in place of `settings`.
  `adb shell pm list users` lists the ids. Do not turn it on for an Amazon
  Kids profile.

### What to expect after pressing Home

- The stock home screen shows briefly before Rozan opens, because the
  redirect can only act once the stock home screen is on screen. On the
  Android TV emulator (API 36) that is about 50 ms; on Fire TV it is up to 5
  seconds on Fire OS 7 and about 250 ms on Fire OS 8. Where Rozan can be the
  real default home ([step 6](#6-rozan-as-the-real-default-home)), the
  system opens Rozan directly and the stock home screen does not show.
- On Fire TV, the Apps button and the long-press Home panel pass through the
  stock home screen on their way to their own screen, and Rozan leaves them
  alone. A Home press made from them can leave you on the stock home screen.
  Open any app and press Home again.
- Rozan opens in place of some screens that the stock launcher draws inside
  its home screen: the input list that the remote's Input key opens on Fire
  TV Edition and Omni TVs, and the Settings tab that Back returns to from a
  Settings page on Fire OS 7. On Google TV, the profile button can open Rozan
  over the profile chooser. On Fire TV Edition and Omni TVs, Rozan's own
  Inputs button opens the input list ([LAUNCHER.md](LAUNCHER.md)).

## 6. Rozan as the real default home

- Fire TV: not possible. The stock launcher always takes priority.
  `cmd package set-home-activity` and `cmd role add-role-holder` print
  Success and change nothing. Never run `pm disable-user` on
  `com.amazon.tv.launcher`, `com.amazon.firehomestarter`,
  `com.amazon.tv.settings.v2` or any other Amazon system package: Fire OS
  Settings lives in the launcher.
- Google TV: do not. It means disabling the Google TV launcher and its setup
  app, which breaks profiles, Assistant, casting and remote buttons, and makes
  a Google TV Streamer restart over and over. Use the redirect.
- NVIDIA Shield and other classic Android TV devices: possible, if you do
  not need the stock launcher. Home then opens Rozan without the redirect,
  but the stock app list behind long-press Home, the notifications panel and
  the inputs panel are gone. Keep ADB debugging on afterwards: if Rozan stops
  working, adb is the way back ([step 7](#undo-rozan-as-the-default-home-classic-android-tv)).

On a classic Android TV device, first look at the stock home that the "Home
button" row in Rozan's settings names. The commands below, and the ones that
undo them, are for `com.google.android.tvlauncher/.MainActivity`; if the row
names a different one, use its package and component in their place. Then
run:

```sh
adb shell pm disable-user --user 0 com.google.android.tvlauncher
adb shell cmd package set-home-activity ai.aneesiqbal.rozan.launcher/ai.aneesiqbal.rozan.launcher.HomeActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME   # must name ai.aneesiqbal.rozan.launcher
```

The "Home button" row in Rozan's settings then reads "Opens Rozan".

- On a Shield, if Home opens the stock home screen again after a restart or
  a system update, run the commands again or use the redirect.
- On Xiaomi devices, use the redirect. On a Mi Box S the firmware turns its
  PatchWall home app back on about 3 minutes after it is disabled, and
  China-market Xiaomi firmware refuses third-party home apps.

## 7. Recovery

Rozan never disables, hides or uninstalls the stock launcher itself, so the
stock home screen is always there to fall back to.

### Back to the stock home screen

- In Rozan's settings, "Open the stock home screen" opens the stock launcher
  and stays there until you open another app. The row is there while another
  launcher is the default home.
- If Rozan crashes, the redirect stops sending Home to Rozan, so Home shows
  the stock home screen and its Settings work. The redirect starts again once
  you open Rozan from the stock home screen's app list.
- With adb, [turn the redirect off](#turn-the-redirect-off-with-adb), then
  run `adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME`.
  While the redirect is on, this opens the stock home and the redirect sends
  you on to Rozan.

### Turn the redirect off without a computer

Force stopping Rozan turns the redirect off until you turn it on again. On
Fire TV, Fire OS Settings opens from Rozan's Settings tile or from the
long-press Home panel.

| Device | Turn the redirect off | Remove Rozan |
| --- | --- | --- |
| Fire TV | Settings > Applications > Manage Installed Applications > Rozan > Force stop; or Settings > Accessibility on Fire OS 7 | Same screen, Uninstall |
| Google TV | Settings > Apps > Rozan > Force stop; or Settings > System > Accessibility > Rozan home button > off | Settings > Apps > Rozan > Uninstall |
| Android TV | Settings > Apps > Rozan > Force stop; or Settings > Device Preferences > Accessibility > Rozan home button > off | Settings > Apps > Rozan > Uninstall |

### Turn the redirect off with adb

```sh
adb shell am force-stop ai.aneesiqbal.rozan.launcher
adb shell settings get secure enabled_accessibility_services   # Rozan's service should be gone
```

If Rozan's service is still listed, write the list back without it:

```sh
SVC=ai.aneesiqbal.rozan.launcher/.redirect.HomeRedirectService
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
rest=$(printf '%s\n' "$cur" | tr ':' '\n' | grep -vxF -e "$SVC" -e null | paste -sd: -)
if [ -n "$rest" ]; then
  adb shell settings put secure enabled_accessibility_services "$rest"
else
  adb shell settings delete secure enabled_accessibility_services
fi
```

Uninstalling Rozan with `adb uninstall ai.aneesiqbal.rozan.launcher` also
removes its service from the list.

### Undo Rozan as the default home (classic Android TV)

```sh
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

If Rozan crashes while it is the default home and the stock launcher is
disabled, Home can show a blank screen. These two commands bring the stock
home back.

### If someone disabled the Google TV launcher

Turn the launcher back on before the setup app, then point Home back at the
launcher:

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

### Last resort

Factory reset. On a Fire TV remote, hold Back and Right together for 10
seconds, or use Settings > My Fire TV > Reset to Factory Defaults.

## Rozan AirPlay

Rozan AirPlay (`ai.aneesiqbal.rozan.airplay`) is the same AirPlay receiver
without the home screen, for a TV that keeps its own home screen. It is in
the same release as `rozan-airplay-X.Y.Z.apk`. Turn on adb (step 1), check
the download against `SHA256SUMS` (step 2), then run:

```sh
adb install -r rozan-airplay-X.Y.Z.apk
adb shell appops set ai.aneesiqbal.rozan.airplay SYSTEM_ALERT_WINDOW allow
```

It appears in the system's app list. Its screen shows "Show over other apps"
as "Allowed" or "Not allowed", with the command above while it is not
allowed. Updating works as for the launcher, with its own file name.

The launcher contains the same receiver, and only one receiver can use port
7000 at a time. If both apps are installed, turn AirPlay off in the one you
do not use: "Enable AirPlay" in Rozan's settings, or "AirPlay receiver" in
Rozan AirPlay. While both are installed, Rozan's AirPlay settings start with
the warning "A separate Rozan AirPlay app is installed", which says that it
is not needed because Rozan's built-in AirPlay works without it. Selecting
the warning opens Android's uninstall confirmation for Rozan AirPlay.

To remove it, run `adb uninstall ai.aneesiqbal.rozan.airplay`, or uninstall
it under Settings > Apps.
