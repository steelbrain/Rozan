# NVIDIA Shield TV

Research for running the Rozan launcher, its AirPlay receiver and Rozan
AirPlay on an NVIDIA Shield TV: what the Shield does differently from the
Android TV emulator, what Rozan does about it, and what still needs a Shield
to answer. Research date: 2026-11-26.

How to read the evidence:

- No Rozan code has run on a Shield. Behaviour marked "tested" was seen on
  the Android TV emulator (API 31 image, which has the same stock launcher as
  the Shield) or on API 28 and API 30 Google APIs emulator images run with
  a 1080p TV device profile (the Shield's two Android versions; there is no
  arm64 Android TV image for them). Those two are phone builds: they lack
  the leanback feature and the TV system apps.
- Android internals were read in AOSP source; links name the branch.
- Shield behaviour comes from NVIDIA's pages where they exist, then from
  reports by other projects and users, cited per claim. **UNCONFIRMED** marks
  claims that rest on one secondary report, a search summary, or inference.
- The checks a Shield owner can run are in
  [INTEROP.md](../INTEROP.md#nvidia-shield-tv), and the device questions
  behind them in section 10.

## 1. Summary

| Area | On a Shield | What Rozan does |
| --- | --- | --- |
| Software | Android 11 (API 30) on Shield Experience 9.x, the current release on every model; Android 9 (API 28) on units still on 8.2.x (2) | Runs on both; minSdk is 28 |
| Home button | Opens `com.google.android.tvlauncher/.MainActivity`, the classic Android TV home (3.1) | The redirect works there (3.3); Rozan as the real default home works after disabling tvlauncher (3.4) |
| Remote | Back, D-pad and Select reach apps and Home goes to the system; the 2019 remote's ≡ key opens the Shield's own menu, and Netflix opens Netflix (4.3) | Hold Select for an app's panel; no change needed |
| Controller | A, B, X, Y as gamepad buttons; Back and Home as Back and Home; stick and D-pad as axes (4.2) | Works through Android's fallback keys; tested with injected keys and a virtual USB gamepad (4.4) |
| Sleep and wake | Sleep turns the display off and keeps processes running; Wi-Fi goes into power save and Doze cuts the network of background apps; NVIDIA's HDMI-CEC setting turns the TV on when the Shield wakes (5) | The receiver is a foreground service and holds Wi-Fi and multicast locks while wake on connect is on; the code wakes the Shield with a wake lock and a turn-screen-on activity. Unconfirmed on a Shield |
| Video | `OMX.Nvidia.h264.decode`, 4K60; low-latency support reported both ways (6) | Asks for low latency only when listed; offers 1080p30; logs which decoder and whether low latency was asked for |
| Audio | 48 kHz output; Dolby audio processing adds buffering; HDMI audio can drop for seconds after a mode change (7) | Sync follows the output's timestamps; a failed track is reopened on the next packet |
| Display, AI upscaling, screensaver | Refresh rate changes only on request; AI upscaling applies to video up to 30 fps; keep-screen-on blocks the screensaver (8) | Requests no mode change; mirroring keeps the screen on; audio-only does not (open question, 10) |

## 2. Models and software

| Model | Number | SoC | RAM / storage | Came with |
| --- | --- | --- | --- | --- |
| Shield TV (2015) | P2571 | Tegra X1 | 3 GB / 16 GB | Controller (P2570); remote sold separately |
| Shield TV Pro (2015) | P2571 | Tegra X1 | 3 GB / 500 GB | Controller (sources disagree on a remote) |
| Shield TV (2017) | P2897 | Tegra X1 | 3 GB / 16 GB | Remote and controller (P2920); a remote-only version from October 2017 |
| Shield TV (2019, "tube") | P3430 | Tegra X1+ | 2 GB / 8 GB, microSD | 2019 remote (P3700) only |
| Shield TV Pro (2019) | P2897 | Tegra X1+ | 3 GB / 16 GB | 2019 remote (P3700) only |

Sources: [Wikipedia](https://en.wikipedia.org/wiki/Nvidia_Shield_TV),
[TechInfoDepot](https://techinfodepot.shoutwiki.com/wiki/NVIDIA_Shield_(P2897)),
[Android Police on the remote-only model](https://www.androidpolice.com/2017/09/21/nvidia-releases-new-179-99-16gb-shield-tv-remote-sans-gamepad-controller/),
[Android Police on 32-bit apps](https://www.androidpolice.com/2020/01/14/nvidias-shield-tv-dongle-can-only-run-32-bit-apps-but-you-probably-dont-need-to-panic/),
[AFTVnews on the controllers](https://www.aftvnews.com/new-nvidia-shield-controller-passes-through-the-fcc/).
Wikipedia's table lists a remote with the 2015 Pro while its text says the
remote was sold separately.

Software ([NVIDIA software updates](https://www.nvidia.com/en-us/shield/software-update/)):

- Shield Experience 8.x is Android 9 (API 28); 8.2.3 is its last build.
- Shield Experience 9.0 moved to Android 11 (API 30). It started rolling
  out on 2022-01-12 "to all Shield TVs, including the original 2015 models"
  ([Engadget](https://www.engadget.com/nvidia-update-shield-tv-android-11-140040282.html)).
- The current release is 9.2.4 (build 33.2.0.400, 2026-02-25), "for all
  SHIELD and SHIELD Pro units", with January 2026 security patches
  ([NVIDIA forum](https://www.nvidia.com/en-us/geforce/forums/shield-tv/9/582849/shield-experience-upgrade-924-22526/)).
  No later release was found (UNCONFIRMED that none exists).
- Upgrades can be set to "Notify me" (Settings > About > System upgrade);
  users report the upgrade downloading anyway after a restart, and guides
  exist for going back to 8.2.3 (UNCONFIRMED; forum reports). So most
  Shields run API 30 and a few may still run API 28.

adb: pressing Settings > Device Preferences > About > Build seven times turns
on Developer options, and Settings > Device Preferences > Developer options >
Network debugging turns on adb over the network. The first `adb connect`
from a computer shows a prompt on the TV to allow it
([ktz blog](https://blog.ktz.me/nvidia-shield-adb-projectivy-performance-refresh/),
[package list and tests](https://gist.github.com/bharvey88/c211c6ca1d9e5f1379b204bf75067275),
[setup notes](https://gist.github.com/jcassette/c72285d2073bd94c00cdac17c7a01cc0)).

## 3. Home screen and the Home button

### 3.1 Stock home

- Shield Experience 9.x keeps the classic Android TV home, not Google TV:
  Home resolves to `com.google.android.tvlauncher/.MainActivity` at HOME
  priority 2, with `com.android.tv.settings/.system.FallbackHome` at -1000
  (a `dumpsys` from a 2019 Pro on Android 11 in
  [cleanyourtv-app#1631](https://github.com/udhaykumarbala/cleanyourtv-app/issues/1631);
  [TechRadar](https://www.techradar.com/news/nvidia-shield-tv-gets-a-new-os-but-it-isnt-google-tv)).
  No NVIDIA package declares HOME. This is the same launcher as on the API
  31 Android TV emulator, and the class Rozan's redirect already matches
  ([home-button-override.md](home-button-override.md) 4 and 6.2).
- NVIDIA packages that handle buttons: `com.nvidia.shieldtech.hooks`
  (remaps controller Home buttons, and its `NvNetflix` opens Netflix from
  `interceptKeyBeforeDispatching`), `shield.remote.server`,
  `ControllerMapper` and others
  ([package list](https://gist.github.com/bharvey88/c211c6ca1d9e5f1379b204bf75067275),
  [omarchytv#54](https://github.com/piermark/omarchytv/pull/54)).
- The home screen's top row has been Google's "Discover" layout with
  sponsored highlights since June 2021
  ([9to5Google](https://9to5google.com/2021/06/17/shield-tv-discover-homescreen-rolling-out/)).

### 3.2 Home presses

- NVIDIA: press Home to go home, double press for recent apps, press and
  hold for all apps (or broadcasting when NVIDIA Share is on); Back then
  Home quickly puts the Shield to sleep
  ([NVIDIA](https://www.nvidia.com/en-eu/shield/support/shield-tv/know-your-shield-tv/),
  [Shield remote guide](https://support-shield.nvidia.com/shield-tv-user-guide/SHIELD_Remote-atv.htm)).
- Remotes and controllers send `KEYCODE_HOME` (scan code 172) and
  `KEYCODE_BACK` (158) in NVIDIA's key layouts
  ([shield-common keylayout](https://github.com/Lolzen/android_device_nvidia_shield-common/tree/android-8.1/keylayout),
  NVIDIA-copyright files in a community tree).
- AOSP handles Home in `interceptKeyBeforeDispatching`, so apps never get
  it. With a double-tap behaviour configured, a single press waits for the
  double-tap timeout (300 ms) before going home
  ([PhoneWindowManager, android11-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/policy/PhoneWindowManager.java)).
  The AOSP TV overlay leaves double tap off; NVIDIA's double press for
  recents implies the Shield turns it on (UNCONFIRMED inference).
- Long press on the AOSP TV configuration sends `ACTION_ALL_APPS` to the
  system's own home package (`MATCH_SYSTEM_ONLY`)
  ([device/google/atv config](https://android.googlesource.com/device/google/atv/+/refs/heads/android11-release/overlay/frameworks/base/core/res/res/values/config.xml)).

### 3.3 The redirect on a Shield

- A launcher whose accessibility service watches `TYPE_WINDOW_STATE_CHANGED`
  and brings itself back when tvlauncher's MainActivity appears works on a
  2019 Shield TV Pro on Android 11 with the January 2026 patch: it reacted
  82 ms after tvlauncher appeared. Its first Home press after boot lost a
  race because it checked the foreground app with usage stats
  ([aerial-tv#1380](https://github.com/pengchujin/aerial-tv/issues/1380)).
  Rozan reacts to the window event itself and probes the window that is
  already up when its service connects
  ([home-button-override.md](home-button-override.md) 6.7), so it does not
  have that race (UNCONFIRMED on a Shield).
- Projectivy's developer saw no problem on a 2019 Shield; users of 2017 and
  2019 units whose redirect stopped fixed it by updating Android TV Home,
  Android TV Core Services and TV Setup, and the developer notes that
  Android can list a service as enabled while it is not bound after an app
  or system update ([miproja1#143](https://github.com/spocky/miproja1/issues/143)).
  Rozan's settings show that state as "Turned on, not running".
- No Shield firmware was found that blocks third-party accessibility
  services. Android 13's "restricted settings" do not apply to Android 11.
- Tested on the API 31 Android TV image: with the redirect on, Home from a
  gamepad source and from the remote both left system Settings for Rozan.

### 3.4 Rozan as the default home on a Shield

- `cmd package set-home-activity` and the HOME role print success and
  change nothing while tvlauncher is enabled, because a priority-2 match is
  picked before preferred activities
  ([glassos-tv#31](https://github.com/AbhishekKaisar/glassos-tv/issues/31),
  [aerial-tv#1380](https://github.com/pengchujin/aerial-tv/issues/1380);
  [ResolveIntentHelper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ResolveIntentHelper.java)).
- `pm disable-user --user 0 com.google.android.tvlauncher` makes Home open
  the other launcher at once (glassos-tv#31;
  [FLauncher#42](https://gitlab.com/etienn01/flauncher/-/issues/42)).
  Whether it survives a reboot is disputed: a November 2026 report says the
  Shield re-enables tvlauncher on every reboot (glassos-tv#31), others saw
  it stay disabled ([super-shield](https://github.com/daveonkels/super-shield)),
  and Shield updates may re-enable it
  ([LiveTVLauncher](https://github.com/davehess0/LiveTVLauncher)).
- What goes with tvlauncher: its all-apps view, the notifications panel and
  the inputs panel live in that package. Recents (double press) is reported
  to keep working (UNCONFIRMED). The Assistant is handled by the system, not
  tvlauncher (package list above).
- Tested on the API 31 Android TV image with tvlauncher disabled and Rozan
  set as home: Home opens Rozan; a long press sends
  `ACTION_ALL_APPS pkg=com.android.tv.settings`, which nothing handles, and
  Rozan stays on screen. Rozan cannot take that intent, since it is pinned to
  a system package.
- There is no Home app picker in Shield Settings on 9.x (8.0.1 briefly had
  one; [Droid Life](https://www.droid-life.com/2019/10/01/shield-tv-update-8-0-1-brings-a-ton-of-bug-fixes/)).

## 4. Remote and game controller input

### 4.1 What Android does with controller buttons (AOSP)

Rozan has no gamepad code. Controllers work through the framework:

- Fallback keys: when an app leaves a key's initial down unhandled, the
  input dispatcher asks the policy for a fallback from the device's key
  character map and sends it to the same window
  ([InputDispatcher.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android11-release/services/inputflinger/dispatcher/InputDispatcher.cpp),
  `afterKeyEventLockedInterruptible`;
  [PhoneWindowManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/policy/PhoneWindowManager.java),
  `dispatchUnhandledKey`). The decision is made once, on the initial down;
  a view or activity that consumes the down (as Rozan's screensaver does
  with any key) gets no fallback.
- The maps are
  [Generic.kcm](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/data/keyboards/Generic.kcm)
  for real controllers and
  [Virtual.kcm](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/data/keyboards/Virtual.kcm)
  for keys injected with `adb shell input` (also read on the API 28 and 30
  emulators):

  | Button | Real controller (Generic.kcm) | `adb shell input` (Virtual.kcm) |
  | --- | --- | --- |
  | A, START, THUMBL, THUMBR | DPAD_CENTER | DPAD_CENTER |
  | B | BACK | BACK |
  | X | DEL | DPAD_CENTER |
  | Y | SPACE | BACK |
  | SELECT | MENU | MENU |
  | MODE | MENU on Android 9, HOME from Android 10 | MENU up to Android 11, HOME from 12 |
  | L1, R1, L2, R2 | none | none |

- SPACE is a confirm key (with DPAD_CENTER, ENTER and NUMPAD_ENTER) on every
  version from API 28 to 36, so on a real controller Y clicks the focused
  view like A. In a text field X deletes a character and Y types a space.
- Holding A is a long press: the fallback DPAD_CENTER down starts the view's
  long-press timer, and the up does not click once it fired
  ([View.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/core/java/android/view/View.java),
  `onKeyDown`/`onKeyUp`).
- Stick and D-pad: `ViewRootImpl`'s `SyntheticJoystickHandler` turns
  `AXIS_X`/`AXIS_Y` and `AXIS_HAT_X`/`AXIS_HAT_Y` past ±0.5 into D-pad keys,
  repeating while held, unless a view consumes the motion
  ([ViewRootImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/view/ViewRootImpl.java)).
  Triggers produce no keys.
- The fallback BACK from B is an ordinary `KEYCODE_BACK`, so it reaches
  `onBackPressed` (API 28 to 32) and the `OnBackInvokedCallback` (API 33+)
  like the remote's Back.

### 4.2 The Shield controllers

- 2017 controller (USB 0955:7214) in NVIDIA's key layout: A, B, X, Y as
  `BUTTON_A`..`BUTTON_Y`, bumpers L1/R1, stick clicks THUMBL/THUMBR, Back as
  `KEYCODE_BACK`, Home as `KEYCODE_HOME`, Start as `BUTTON_START`, volume
  keys, play/pause, and the NVIDIA button as the vendor `BUTTON_16`. The
  triggers are axes only and the D-pad is a hat (axes)
  ([shield-common keylayout](https://github.com/Lolzen/android_device_nvidia_shield-common/tree/android-8.1/keylayout);
  [LineageOS copy](https://github.com/LineageOS/android_device_lineage_atv/blob/lineage-22.1/keylayout/Vendor_0955_Product_7214.kl)).
  The 2015 controller (0955:7210) has the same layout plus a few vendor
  buttons. Which physical button sends `BUTTON_16` is inferred
  (UNCONFIRMED).
- NVIDIA: A selects, B goes back or cancels, Start plays and pauses, Home
  goes home (double press for recents), the NVIDIA button starts voice
  search ([NVIDIA controller actions](https://support-shield.nvidia.com/controller-user-guide/default-controller-actions.htm),
  [controller guide](https://support-shield.nvidia.com/shield-tv-pro-user-guide/SHIELD_Controller-atv.htm)).
- No NVIDIA `.kcm` was found, so Generic.kcm's fallbacks (4.1) apply
  (UNCONFIRMED on a Shield). The 2019 Shields ship without a controller.

### 4.3 The remotes

- 2019 remote (P3700, Bluetooth 0955:7217): power, ≡, D-pad, Select, Back,
  Home, microphone, rewind, play/pause, fast-forward, volume and Netflix
  ([NVIDIA](https://www.nvidia.com/en-eu/shield/support/shield-tv/know-your-shield-tv/)).
  - ≡ opens the Shield's quick settings (NVIDIA's pages also say Settings),
    and can be set to other actions under Settings > Remotes & Accessories
    ([NVIDIA quick settings](https://www.nvidia.com/en-gb/shield/support/shield-tv/quick-settings/)).
    It is reported to send `KEYCODE_MENU`, which the Shield keeps for its
    own menu ([super-shield](https://github.com/daveonkels/super-shield),
    [quiet-home](https://github.com/NicolasPogorzelski/quiet-home);
    UNCONFIRMED). So on a Shield, Rozan's app panel is opened by holding
    Select.
  - Netflix sends `BUTTON_12`, which NVIDIA's `NvNetflix` turns into
    opening Netflix before apps see it
    ([shield-button-hooks](https://github.com/barrettcoats/shield-button-hooks),
    omarchytv#54). The microphone is `KEYCODE_ASSIST`, handled by the
    system.
  - Volume and power keys go to the TV over HDMI-CEC or the remote's IR
    blaster, per Settings > Device Preferences > Display & Sound
    ([NVIDIA](https://www.nvidia.com/en-gb/shield/support/shield-tv-pro/know-your-shield-tv-pro)).
- 2017 remote (0955:7213) and 2015 remote (0955:7212): voice, D-pad, Select,
  Back, Home and a touch volume strip; search maps to `BUTTON_16`
  ([Shield remote guide](https://support-shield.nvidia.com/shield-tv-user-guide/SHIELD_Remote-atv.htm)).

### 4.4 What Rozan does with them (tested)

With injected gamepad keys (`adb shell input gamepad keyevent`, and the
instrumented tests `GamepadTest` and `SessionUiTest`) on the API 28 and API
30 images and the API 31 Android TV image:

| Screen | A | B | Hold A, or SELECT | Stick |
| --- | --- | --- | --- | --- |
| Home | Opens the tile | Closes the panel, leaves the screensaver, then returns to the first tile | Opens the app panel | Moves focus (API 30, 31) |
| Screensaver | Ends it and does nothing else | Same | | |
| Launcher settings | Toggles or steps the row | Back to home | | Moves focus |
| Rozan AirPlay | Toggles the row; on the name field, opens the keyboard | Hides the keyboard, then leaves | | |
| PIN screen (without the overlay permission) | Nothing | Closes it | | |
| PIN overlay | Goes to the app underneath (the overlay takes no keys) | Same | | |
| Mirroring | Nothing | Ends the session | | |
| Now playing | Nothing | Closes it; audio continues | | |

With a virtual USB gamepad (`scripts/emulator-gamepad.sh`, which registers
a HID device through the shell's `hid` tool, so Generic.kl and Generic.kcm
apply as for a real controller) on the API 28 and API 30 images:

- Y clicks like A: on the Screensaver tile it starts the screensaver.
- X does nothing on the home screen, and ends the screensaver like any key.
- Start opens the focused tile like A.
- Holding A, or pressing Select, opens the app panel; B closes it.
- On the mirroring screen Y, X and A do nothing, and B ends the session.

Still not testable on an emulator: MODE (the Shield controllers have no
such button), the NVIDIA button, and the Shield's own key layouts
(`Vendor_0955_Product_*.kl`), which decide what each physical button sends.
These are hardware checks.

## 5. Sleep, wake and HDMI-CEC

### 5.1 What sleep is

- Sleep is Android's asleep state with the display off; processes keep
  running. On two 2019 Shields on 9.2.4, a sleeping Shield showed
  `mWakefulness=Asleep` and the display `OFF`, and still answered Cast's
  ports and took a Cast session, which woke it and the TV
  ([Cast test](https://gist.github.com/matbanik/7b2945ebefe21435df622752477976ad)).
  The power button and Back-then-Home put it to sleep, and "TV will power
  off if HDMI-CEC or IR power control are enabled"
  ([NVIDIA user guide](https://www.nvidia.com/en-us/shield/support/shield-tv/)).
- Timers: on 8.x, Settings > Device Preferences > Screen saver has "When to
  start" and "Put device to sleep". 9.0 added an Energy saver page
  ([release notes](https://www.nvidia.com/en-gb/shield/software-update)). In
  AOSP's Android 11 TV Settings, that page's "Turn off display" timer writes
  `Settings.Secure.sleep_timeout` (15 minutes to 24 hours, or never), and
  "Allow turning screen off during media playback" sets
  `attentive_timeout`
  ([EnergySaverFragment](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android11-release/Settings/src/com/android/tv/settings/device/display/daydream/EnergySaverFragment.java);
  Shield users describe the same page in
  [jellyfin-androidtv#4007](https://github.com/jellyfin/jellyfin-androidtv/issues/4007)).
- Network while asleep: Ethernet stays up (UNCONFIRMED, forum reports).
  Wi-Fi stays associated but in power save: pings to a sleeping 2019 Shield
  averaged 203 ms with no loss
  ([package list and tests](https://gist.github.com/bharvey88/c211c6ca1d9e5f1379b204bf75067275)).
  Other users report Wi-Fi dropping in sleep (UNCONFIRMED). While "Wake TV
  when someone connects" is on, Rozan holds a high-performance Wi-Fi lock,
  which keeps Wi-Fi out of power save, and a multicast lock, which lets
  mDNS queries in
  ([MulticastLock](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock)).
  Whether they do so on a sleeping Shield is a hardware check.
- Doze happens on a sleeping Shield: an app with a plain started service
  had its network cut (`dumpsys netpolicy` showed `REJECT_ALL` for its UID)
  until it was put on the deviceidle whitelist
  ([omarchytv#143](https://github.com/piermark/omarchytv/issues/143)). AOSP
  9 and 11 do not cut network or wake locks for a process at
  foreground-service state or above
  ([NetworkPolicyManager, android11-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/net/NetworkPolicyManager.java)).
  Rozan's receiver is a foreground service in its own process, so it
  should keep its network; that is UNCONFIRMED on a Shield.
- The Shield has no Wake-on-LAN or network standby setting of its own;
  NVIDIA's Wake-on-LAN page is about the Shield waking a PC
  ([NVIDIA](https://support-shield.nvidia.com/shield-tv-pro-user-guide/Wake_on_LAN___Remote_Login.htm)).

### 5.2 Waking the Shield and the TV

- Rozan wakes the device for a code with a screen wake lock with
  `ACQUIRE_CAUSES_WAKEUP`, and shows the code in an activity with
  `setTurnScreenOn` ([ARCHITECTURE.md](../ARCHITECTURE.md)). Android's
  documentation says "Android TV playback devices attempt to turn on the
  HDMI-connected TV via HDMI-CEC on any wake-up, including wake-ups
  triggered by wake locks"
  ([PowerManager](https://developer.android.com/reference/android/os/PowerManager#ACQUIRE_CAUSES_WAKEUP)).
- Starting that activity from the background needs the "draw over other
  apps" permission on Android 10 and later; a foreground service alone is
  not an exception
  ([background activity starts](https://developer.android.com/guide/components/activities/background-starts)).
  INSTALL.md step 3 grants it.
- No report was found of a third-party app waking a Shield this way. Cast
  does wake it and the TV (Cast test above).
- HDMI-CEC on the Shield is NVIDIA's code. AOSP 9 and 11 do not send One
  Touch Play when the screen turns on: `onAddressAllocated` sends
  <Active Source> only when CEC is being enabled, and AOSP 12 added it on
  screen-on
  ([HdmiCecLocalDevicePlayback, android11-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/hdmi/HdmiCecLocalDevicePlayback.java),
  [android12-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/hdmi/HdmiCecLocalDevicePlayback.java)).
  NVIDIA's setting turns the TV on and switches it to the Shield's input
  "whenever your SHIELD wakes up or you press a button", if the TV has
  HDMI-CEC (One Touch Play) enabled
  ([NVIDIA](https://support-shield.nvidia.com/shield-tv-user-guide/Enable_SHIELD_to_Turn_on_your_TV.htm)).
  From 8.0 the options are under Settings > Device Preferences > Display &
  Sound > Power control (CEC)
  ([Nakamichi](https://www.helpdesk.nakamichi-usa.com/nvidia-shield-v8-hdmi-cec),
  a TV maker's page; UNCONFIRMED wording). Whether a wake by an app's wake
  lock triggers it is UNCONFIRMED; it is the main wake check.
- The 2019 remote can be set to wake the Shield only with its power or
  Netflix button (9.1 release notes); that does not affect a wake by an
  app.

## 6. Video

- Decoders: `OMX.Nvidia.h264.decode` and `OMX.Nvidia.h265.decode` on
  Android 9 and 11 on the 2015, 2017 and 2019 models; no Codec2 `c2.nvidia`
  names were seen
  ([ExoPlayer#8041](https://github.com/google/ExoPlayer/issues/8041),
  [media#3572](https://github.com/androidx/media/issues/3572),
  [stremio-bugs#2930](https://github.com/Stremio/stremio-bugs/issues/2930)).
  NVIDIA specifies 4K at 60 fps for H.264 and H.265
  ([Shield TV](https://www.nvidia.com/en-us/shield/shield-tv/),
  [Shield TV Pro](https://www.nvidia.com/en-gb/shield/shield-tv-pro)).
  Rozan offers senders at most 1920×1080 at 30 fps, well inside that.
- Low latency: one benchmark on a 2019 Pro on Android 11 reports
  `FEATURE_LowLatency` for H.264 and H.265 and about 2 ms per 1080p frame
  ([nightjar#32](https://github.com/RamazanKara/nightjar/issues/32)); the
  codec list in LineageOS's Shield tree declares no such feature
  ([media_codecs.xml](https://github.com/LineageOS/android_device_nvidia_foster/blob/lineage-18.1/media/media_codecs.xml)).
  Rozan asks for low latency only when the decoder lists the feature, and
  retries without it if the decoder refuses
  ([VideoDecoders.kt](../../airplay/android/src/main/kotlin/ai/aneesiqbal/rozan/airplay/android/video/VideoDecoders.kt)),
  so either answer works. The log line "decoding with NAME, low latency
  requested" (or "not requested") shows which one a Shield gives. No NVIDIA
  vendor key for low latency is documented.
- Most of the delay is after decoding: on a 2019 Pro the time from
  releasing a frame to its display was about 40 to 50 ms at 60 Hz and about
  30 ms at 120 Hz, because the decoder gets a free buffer once per vsync
  ([nightjar#7](https://github.com/RamazanKara/nightjar/issues/7); one
  report).
- Quirks reported on Shields, and Rozan's position:
  - Feeding one codec first avcC and then Annex B input froze the picture;
    the fix was a new codec
    ([xbmc#30080](https://github.com/xbmc/xbmc/pull/30080)). Rozan starts a
    new decoder for every new stream format.
  - A flush of the H.264 decoder during 1080i deinterlacing never returned
    ([AIOStreams#1964](https://github.com/Viren070/AIOStreams/pull/1964)).
    Rozan never flushes the video decoder; it stops and releases it.
- The 2019 tube runs a 32-bit userspace (`armeabi-v7a`); the 2015 model
  reports `arm64-v8a` (package list above;
  [Android Police](https://www.androidpolice.com/2020/01/14/nvidias-shield-tv-dongle-can-only-run-32-bit-apps-but-you-probably-dont-need-to-panic/)).
  Rozan ships no native code, so both run the same APK.

## 7. Audio

- The 2019 Pro on Android 11 reports a 48 kHz output with 256-frame
  buffers (nightjar#32). AirPlay audio from Music (ALAC at 44.1 kHz) is
  resampled to 48 kHz on the way out (UNCONFIRMED; forum testers). 9.0 added
  "Match content audio resolution" for HDMI (release notes).
- "Dolby audio processing", reported to be on by default, lengthens the
  output buffer: 192 ms against 128 ms with it off, on a 2019 Pro. Kodi saw
  a constant audio offset with it on, and Kodi contributors have advised
  turning it off for years
  ([xbmc#29371](https://github.com/xbmc/xbmc/issues/29371)). 9.2 "fixed AV
  sync issues when Dolby Processing is enabled" (release notes). Rozan's
  audio clock follows `AudioTrack.getTimestamp`, which should include that
  buffer; whether lips stay in sync with it on is a hardware check.
- After a display mode change, the Shield's HDMI audio output can disappear
  for 0.1 to 5.4 s: writes fail with `ERROR_DEAD_OBJECT` and new tracks fail
  to open (one setup on 9.2.4, through an AV receiver; AIOStreams#1964).
  Rozan's audio sink drops a track whose write fails and tries to open a new
  one with every following packet, so playback resumes once the output is
  back.
- Settings > Device Preferences > Display & Sound has an "Audio video sync"
  slider (audio earlier by up to 1 s, for AV receivers) and the surround
  format choices (NVIDIA user guide). Rozan plays 16-bit PCM, so the
  passthrough settings do not change what it sends.

## 8. Display modes, AI upscaling and the screensaver

- Refresh rate: NVIDIA's "Match frame rate (beta)" is switched on per
  playback from quick settings, and the display returns to its setting when
  playback stops
  ([NVIDIA](https://www.nvidia.com/en-us/shield/support/shield-tv/match-frame-rate/)).
  An app changes the mode only by asking for one with
  `preferredDisplayModeId` (seen on a 2019 Pro in
  [stremio-bugs#2899](https://github.com/Stremio/stremio-bugs/issues/2899);
  [Android frame rate guide](https://developer.android.com/media/optimize/performance/frame-rate)).
  Rozan's mirroring screen asks for none, so mirroring does not switch the
  TV's mode. The receiver offers senders the current refresh rate, capped
  at 30 fps, so a Shield set to 24 Hz gets a 24 fps stream.
- 4K output: the receiver reads the physical mode (3840×2160 on a 4K TV)
  and offers 1920×1080; the Shield scales it up.
- AI upscaling (2019 models): Settings > AI upscaling, with Basic, Enhanced
  (the default) and AI-Enhanced. It is not applied to video under 480p,
  above 30 Hz, or in RGB, which then gets Enhanced; it applies to any app's
  video ([NVIDIA](https://www.nvidia.com/en-us/shield/support/shield-tv/ai-upscaling/)).
  The tube upscales up to 4K30 and the Pro up to 4K60 (product pages).
  Rozan's mirroring is at most 1080p at 30 fps, so with AI-Enhanced chosen
  it is AI-upscaled. NVIDIA says nothing about latency; Steam Link users
  reported about 40 ms less with Basic (UNCONFIRMED). Comparing the modes is
  a hardware check.
- Screensaver and sleep: `FLAG_KEEP_SCREEN_ON` keeps a TV out of the
  screensaver
  ([Android](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on)),
  and in AOSP 11 a held screen wake lock also stops the sleep timer, except
  `attentive_timeout`, which applies "even if the system is holding
  wakelocks"
  ([PowerManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/power/PowerManagerService.java)).
  So with "Allow turning screen off during media playback" on, the Shield
  would sleep after its "Turn off display" time even while mirroring, or on
  Rozan's home with "Keep screen awake" on (inference from AOSP; UNCONFIRMED
  on a Shield).
- Audio-only AirPlay: the now-playing screen does not keep the screen on,
  so the Shield's screensaver starts over it, and its sleep timer later
  turns the display off. If sleep also cuts the HDMI audio, as a forum user
  describes (UNCONFIRMED), the music stops being heard while the session
  goes on. See section 10.

## 9. What changed in Rozan

- Instrumented tests that press controller buttons on the home screen,
  settings, mirroring and now-playing screens (`GamepadTest`,
  `SessionUiTest`), so a future "any key" handler cannot swallow them
  unnoticed. They pass on the API 28, 30 and 31 images.
- `scripts/emulator-gamepad.sh` presses a button on a virtual USB gamepad,
  so a key goes through the same key map as on a real controller.
- The receiver logs the display mode it reads and the stream size it
  offers, and which decoder it uses and whether it asked for low latency,
  for the hardware checks.

Checked and left as they are:

- Home: tvlauncher's `MainActivity` is already in the redirect's list of
  stock homes, and `FallbackHome` is already ignored.
- Input: nothing in Rozan consumes a controller button outside the
  screensaver, which ends on any key by design.
- Video: low latency is asked for per decoder, a new stream format gets a
  new decoder, and no display mode is requested.
- Audio: the sink reopens a track that fails.
- Manifests: no gamepad or Shield-specific entries are needed for a
  sideloaded app; `android.hardware.gamepad` only matters to app stores.

## 10. Open questions for a Shield

The checks for these are in [INTEROP.md](../INTEROP.md#nvidia-shield-tv).

1. Redirect: the time from a Home press to Rozan, and whether the first
   press after boot reaches Rozan (S1).
2. Double press Home with the redirect on opens recents, not Rozan (S2).
3. Controller: X, Y, Start and the NVIDIA button on Rozan's home; Back and
   Home (S3, S4, S11).
4. 2019 remote: whether ≡ ever reaches Rozan; Netflix opens Netflix and
   never the focused tile (S5).
5. Native mode: whether tvlauncher comes back after a reboot or an update
   (S6).
6. Wake on connect from sleep, on Wi-Fi and on Ethernet: the code appears
   and the TV turns on. If not, whether the receiver's network was cut
   (`dumpsys netpolicy`) or the device did not wake (S7 to S9).
7. Video: which decoder, whether low latency is requested, and the
   mirroring delay with AI upscaling on Basic and on AI-Enhanced (S10,
   S12).
8. Audio: lip sync over 10 minutes with Dolby audio processing on and off
   (S13).
9. Audio-only AirPlay through the Shield's sleep timer: whether the music
   stops when the display turns off (S14). If it does, Rozan should keep the
   screen on while audio plays, which also keeps the system screensaver
   away; that is a product decision for the owner.

## 11. Sources

AOSP (android.googlesource.com, branches `pie-release`, `android10-release`,
`android11-release`, `android12-release`, `main`): `Generic.kcm`,
`Virtual.kcm`, `Generic.kl`, `InputDispatcher.cpp`, `PhoneWindowManager`,
`ViewRootImpl`, `View`, `KeyEvent`, `ResolveIntentHelper`,
`ComponentResolver`, `HdmiControlService`, `HdmiCecLocalDevicePlayback`,
`PowerManagerService`, `NetworkPolicyManager`, TvSettings
`EnergySaverFragment`, device/google/atv overlay `config.xml`. Links are
inline.

Android developer documentation:
[PowerManager](https://developer.android.com/reference/android/os/PowerManager#ACQUIRE_CAUSES_WAKEUP),
[background activity starts](https://developer.android.com/guide/components/activities/background-starts),
[MulticastLock](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock),
[frame rate](https://developer.android.com/media/optimize/performance/frame-rate),
[keeping the screen on](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on).

NVIDIA:
[software updates](https://www.nvidia.com/en-us/shield/software-update/),
[9.2.4 release thread](https://www.nvidia.com/en-us/geforce/forums/shield-tv/9/582849/shield-experience-upgrade-924-22526/),
[know your Shield TV](https://www.nvidia.com/en-eu/shield/support/shield-tv/know-your-shield-tv/),
[know your Shield TV Pro](https://www.nvidia.com/en-gb/shield/support/shield-tv-pro/know-your-shield-tv-pro),
[quick settings](https://www.nvidia.com/en-gb/shield/support/shield-tv/quick-settings/),
[remote guide](https://support-shield.nvidia.com/shield-tv-user-guide/SHIELD_Remote-atv.htm),
[controller guide](https://support-shield.nvidia.com/shield-tv-pro-user-guide/SHIELD_Controller-atv.htm),
[controller actions](https://support-shield.nvidia.com/controller-user-guide/default-controller-actions.htm),
[user guide](https://www.nvidia.com/en-us/shield/support/shield-tv/),
[turning the TV on](https://support-shield.nvidia.com/shield-tv-user-guide/Enable_SHIELD_to_Turn_on_your_TV.htm),
[Wake on LAN](https://support-shield.nvidia.com/shield-tv-pro-user-guide/Wake_on_LAN___Remote_Login.htm),
[match frame rate](https://www.nvidia.com/en-us/shield/support/shield-tv/match-frame-rate/),
[AI upscaling](https://www.nvidia.com/en-us/shield/support/shield-tv/ai-upscaling/),
[Shield TV](https://www.nvidia.com/en-us/shield/shield-tv/) and
[Shield TV Pro](https://www.nvidia.com/en-gb/shield/shield-tv-pro) product
pages.

Other projects and reports:
[ktz blog on Shield adb](https://blog.ktz.me/nvidia-shield-adb-projectivy-performance-refresh/),
[Shield adb setup notes](https://gist.github.com/jcassette/c72285d2073bd94c00cdac17c7a01cc0),
[cleanyourtv-app#1631](https://github.com/udhaykumarbala/cleanyourtv-app/issues/1631),
[aerial-tv#1380](https://github.com/pengchujin/aerial-tv/issues/1380),
[glassos-tv#31](https://github.com/AbhishekKaisar/glassos-tv/issues/31),
[miproja1#143](https://github.com/spocky/miproja1/issues/143),
[FLauncher#42](https://gitlab.com/etienn01/flauncher/-/issues/42),
[super-shield](https://github.com/daveonkels/super-shield),
[quiet-home](https://github.com/NicolasPogorzelski/quiet-home),
[LiveTVLauncher](https://github.com/davehess0/LiveTVLauncher),
[omarchytv#54](https://github.com/piermark/omarchytv/pull/54),
[shield-button-hooks](https://github.com/barrettcoats/shield-button-hooks),
[shield-common keylayouts](https://github.com/Lolzen/android_device_nvidia_shield-common/tree/android-8.1/keylayout),
[LineageOS ATV keylayout](https://github.com/LineageOS/android_device_lineage_atv/blob/lineage-22.1/keylayout/Vendor_0955_Product_7214.kl),
[Shield package list and tests](https://gist.github.com/bharvey88/c211c6ca1d9e5f1379b204bf75067275),
[Cast test on a sleeping Shield](https://gist.github.com/matbanik/7b2945ebefe21435df622752477976ad),
[omarchytv#143](https://github.com/piermark/omarchytv/issues/143),
[jellyfin-androidtv#4007](https://github.com/jellyfin/jellyfin-androidtv/issues/4007),
[ExoPlayer#8041](https://github.com/google/ExoPlayer/issues/8041),
[media#3572](https://github.com/androidx/media/issues/3572),
[stremio-bugs#2899](https://github.com/Stremio/stremio-bugs/issues/2899),
[stremio-bugs#2930](https://github.com/Stremio/stremio-bugs/issues/2930),
[xbmc#29371](https://github.com/xbmc/xbmc/issues/29371),
[xbmc#30080](https://github.com/xbmc/xbmc/pull/30080),
[AIOStreams#1964](https://github.com/Viren070/AIOStreams/pull/1964),
[nightjar#7](https://github.com/RamazanKara/nightjar/issues/7),
[nightjar#32](https://github.com/RamazanKara/nightjar/issues/32),
[LineageOS Shield media_codecs.xml](https://github.com/LineageOS/android_device_nvidia_foster/blob/lineage-18.1/media/media_codecs.xml),
[Nakamichi on Shield HDMI-CEC](https://www.helpdesk.nakamichi-usa.com/nvidia-shield-v8-hdmi-cec).

News: [Engadget](https://www.engadget.com/nvidia-update-shield-tv-android-11-140040282.html),
[TechRadar](https://www.techradar.com/news/nvidia-shield-tv-gets-a-new-os-but-it-isnt-google-tv),
[9to5Google](https://9to5google.com/2021/06/17/shield-tv-discover-homescreen-rolling-out/),
[Droid Life](https://www.droid-life.com/2019/10/01/shield-tv-update-8-0-1-brings-a-ton-of-bug-fixes/),
[Android Police on the remote-only model](https://www.androidpolice.com/2017/09/21/nvidia-releases-new-179-99-16gb-shield-tv-remote-sans-gamepad-controller/),
[Android Police on 32-bit apps](https://www.androidpolice.com/2020/01/14/nvidias-shield-tv-dongle-can-only-run-32-bit-apps-but-you-probably-dont-need-to-panic/),
[AFTVnews](https://www.aftvnews.com/new-nvidia-shield-controller-passes-through-the-fcc/),
[Wikipedia](https://en.wikipedia.org/wiki/Nvidia_Shield_TV),
[TechInfoDepot](https://techinfodepot.shoutwiki.com/wiki/NVIDIA_Shield_(P2897)).
