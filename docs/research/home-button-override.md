# Home button override on Fire TV, Google TV and Android TV

Research for Rozan's launcher: how each TV platform delivers "go home", what
can and cannot be changed, how existing third-party launchers take over the
Home button, and an event-driven design that never captures Settings or other
system UI. Research date: 2026-11-26.

How to read the evidence:

- Statements about Android internals were read in AOSP source (section A has
  the links). They describe AOSP; vendors change things, and the device
  sections say where they do.
- Statements about Fire OS, Google TV and vendor firmware come from Amazon and
  Google documentation where it exists, and otherwise from logs, `dumpsys`
  output and code published by projects that run on the devices. Those are
  cited per claim. Amazon documents none of its launcher's class names.
- **UNVERIFIED** marks every claim that rests on a single secondary report, a
  search summary, or inference, and every claim that needs real hardware.
  Nothing in this document was tested on a device; no TV hardware was
  available.

## 1. Summary

| Family | OS | Stock home (package / class) | Can the default HOME be changed? | Does Home reach an accessibility key filter? | What Rozan should do |
| --- | --- | --- | --- | --- | --- |
| Fire TV stick, cube, pendant on Fire OS 6 | Android 7.1 (API 25) | `com.amazon.tv.launcher` / `.ui.HomeActivity_vNext` | No | No. On 6.2.8.0 and 6.7.x, third-party accessibility services are not even bound (`A11yAuthorizer`) | Run as an ordinary app opened from the app row. Taking over Home there would need polling, which is out of scope |
| Fire TV stick or cube on Fire OS 7 | Android 9 (API 28) | same | No: `set-home-activity` prints Success and is ignored (stock priority 950); the launcher is a protected package | No (Amazon's key policy manager takes it) | Accessibility redirect on the exact home class. Expect up to 5 s before Rozan appears after a Home press (app-switch hold) |
| Fire TV stick or cube on Fire OS 8 | Android 11 (Amazon lists API 29 to 30) | same | No (same reasons) | No | Accessibility redirect; about 250 ms flash of the stock home |
| Fire TV smart TVs (Toshiba, Insignia, Hisense, TCL, Panasonic, Xiaomi, Amazon Omni) | Fire OS 7, 8, and 14 on 2026 TVs (Android 12 to 14) | same, plus inputs and Live TV packages | No | No for Home; the Input key (178) does reach it on a Fire OS 14 TCL | Same as sticks, plus input-switching hand-overs |
| Google TV (Chromecast with Google TV, Google TV Streamer, onn 4K, Sony, TCL, Hisense, Philips Google TV) | Android 11 to 14 | `com.google.android.apps.tv.launcherx` / `.home.HomeActivity` (priority 2) | Only by disabling launcherx *and* setupwraith, which breaks profiles, Assistant, casting and remote buttons, and reboot-loops a Streamer. Not acceptable as a default | Yes (field reports), but consuming it loses the long-press dashboard | Accessibility redirect on the exact home class, without consuming keys |
| Classic Android TV (Nvidia Shield, Xiaomi Mi Box S, Mi TV Stick, Sony and Philips Android TV) | Android 9 to 12 | `com.google.android.tvlauncher` / `.MainActivity` (priority 2); Xiaomi TVs `com.mitv.tvhome` / `.MainActivityUserMode` | Only by disabling the stock launcher (whole package, over adb). Tolerable on Shield as an owner opt-in | Yes (AOSP path), but some Xiaomi remotes send `KEYCODE_ALL_APPS` instead of Home | Accessibility redirect by default; native home mode if the owner disables the stock launcher |
| Vega OS Fire TV (Fire TV Stick 4K Select 2025, Fire TV Stick HD 2nd gen 2026, Fire TV Stick 4K 3rd gen 2026), Roku OS, LG webOS, Samsung Tizen | Not Android | none | n/a | n/a | Cannot run Rozan |

The design in one paragraph (section 6): ask the package manager which
activity Home opens. If it is Rozan, do nothing special. Otherwise an
accessibility service listens only to `TYPE_WINDOW_STATE_CHANGED` and starts
Rozan when, and only when, the active window is the stock launcher's home
activity, matched by exact package and class. Every other class of that
package (Settings, Apps, Inputs, profiles, quick settings, search) is never
touched. A short list of known trampolines (screens that pass through the
stock home on their way somewhere else) holds the redirect back. One
redirect at a time: further home events are ignored until Rozan's home window
or some other activity appears. A circuit breaker trips when Rozan reports a
crash, so a crashing Rozan leaves the user on the stock home instead of in a
loop. No timers, no polling, no key consumption by default.

## 2. Amazon Fire TV (Fire OS)

### 2.0 Which devices run which Fire OS

Amazon's device table lists the original OS of each model
([device specifications](https://developer.amazon.com/docs/device-specs/device-specifications-comparison-table.html),
[Fire OS overview](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html),
[identify Fire TV devices](https://developer.amazon.com/docs/device-specs/identify-fire-tv-devices.html)).
API levels: Fire OS 6 = 25, 7 = 28, 8 = 29 to 30, 14 = 31 to 34, 16 = 35 to 36.
`PackageManager.hasSystemFeature("amazon.hardware.fire_tv")` identifies a Fire
TV.

- Fire OS 6: Fire TV Stick 4K 1st gen (AFTMM, 2018), Fire TV Cube 1st gen
  (AFTA), Fire TV 3rd gen pendant (AFTN), early Insignia, Toshiba and Onida
  TVs.
- Fire OS 7: Fire TV Stick 3rd gen (AFTSSS), Stick Lite and Stick HD 2024
  (AFTSS), Cube 2nd gen (AFTR), Cube 3rd gen (AFTGAZL, 2022), Stick 4K Max
  1st gen (AFTKA), Omni, 4-Series and 2-Series 2021 to 2023, and many
  Toshiba, Insignia, Hisense, TCL, JVC, Grundig and Xiaomi TVs.
- Fire OS 8: Fire TV Stick 4K 2nd gen (AFTKM), Stick 4K Max 2nd gen (AFTKRT),
  Stick 4K Plus (AFTMA08C15, 2025), 2025 Omni QLED, 2-Series and 4-Series,
  Panasonic 2024 to 2025, TCL 2024, Xiaomi 2026. Some older TVs were updated
  from 7 to 8.
- Fire OS 14: smart TVs only so far (Onida 2026, TCL QM64L 2026, Hisense U7
  2026)
  ([Fire OS 14](https://developer.amazon.com/docs/fire-tv/fire-os-14.html),
  [AFTVnews](https://www.aftvnews.com/amazon-reveals-the-first-fire-tv-running-android-14-youre-going-to-be-disappointed/)).
- Fire OS 16: announced, no device named
  ([Fire OS 16](https://developer.amazon.com/docs/fire-tv/fire-os-16.html));
  apps in the stopped state no longer receive `BOOT_COMPLETED` there.
- Vega OS (not Android): section 5. As of late 2026 the Fire TV Stick 4K Max
  is the last Android-based stick Amazon sells
  ([AFTVnews Vega OS tag](https://www.aftvnews.com/tag/vega-os/)).

### 2.1 The stock launcher and what else lives in it

HOME candidates as printed by `query-activities` on Fire OS 7 (AFTSSS) and 8
(AFTKM 8.1.8.5)
([cleanyourtv-app#9239](https://github.com/udhaykumarbala/cleanyourtv-app/issues/9239),
[cleanyourtv AFTKM notes](https://github.com/Gathin23/cleanyourtv/blob/main/examples/amazon-fire-tv-stick-4k-2023/debloat.txt)):

| Component | Priority | Role |
| --- | --- | --- |
| `com.amazon.tv.launcher/.ui.HomeActivity_vNext` | 950 | the home screen |
| `com.amazon.firehomestarter/.HomeStarterActivity` | 1 | starts the home screen at boot (purpose UNVERIFIED) |
| `com.amazon.tv.settings.v2/.system.FallbackHome` | -1000 | blank screen for about 15 s at boot until the user is unlocked |

The same `HomeActivity_vNext` class is reported on Fire OS 6.7 (AFTMM,
[dumbtv#4](https://github.com/humblemuzzu/dumbtv/pull/4)), on Fire OS 7 and 8
sticks and TVs, and on a Fire OS 14 TCL TV
([home-on-fire#13](https://github.com/toolicious/home-on-fire/issues/13)).

Not home, and never to be redirected (all exact class names come from
published logs and code; the source is given per row):

| Package / class | What it is | Source |
| --- | --- | --- |
| `com.amazon.tv.launcher/.ui.MainSettingsActivity` | main Settings screen (Fire OS 8; present on 7.7.1.3) | [cleanyourtv#407](https://github.com/Gathin23/cleanyourtv/pull/407), [miproja1#338](https://github.com/spocky/miproja1/issues/338) |
| `com.amazon.tv.launcher/.ui.SettingsActivity` | trampoline to MainSettingsActivity on Fire OS 8 | [home-on-fire#12](https://github.com/toolicious/home-on-fire/issues/12) |
| `com.amazon.tv.launcher/.navigation.NavigationActivity` | navigation drawer (includes Inputs) | [home-on-fire HijackService.java](https://github.com/toolicious/home-on-fire) |
| `com.amazon.tv.launcher/.deeplink.activity.RecentDeepLinkActivityDI` | Recents (action `com.amazon.tv.action.NAVIGATE_TO_RECENTS`), a translucent task | [couch-tv#223](https://github.com/Seagey/couch-tv/issues/223) |
| `com.amazon.tv.launcher/.deeplink.activity.DirectiveSearchResultsActivityDI` | Alexa voice search results | [firestick-fixer](https://github.com/Hornet34/firestick-fixer) docs |
| `com.amazon.tv.launcher/.util.QuickSettingsProxyActivity` | Fire OS 14: started with `ACTION_ALL_APPS`, hands over to quick settings | [Fire OS 14](https://developer.amazon.com/docs/fire-tv/fire-os-14.html) |
| `com.amazon.tv.settings.v2/.hud.HudActivity` (action `SHOW_HUD`) | long-press Home panel and the remote's Settings button on Fire OS 6, 7 and 8.1.1.6 or older; on the newer UI, the screen after a profile-switch PIN | [home-on-fire#7](https://github.com/toolicious/home-on-fire/issues/7), [dumbtv#217](https://github.com/humblemuzzu/dumbtv/pull/217) |
| `com.amazon.tv.quicksettings.ui` (any window) | long-press Home panel on Fire OS 8.1.8.0 and later; an overlay window, not an activity | same |
| `com.amazon.tv.settings.v2/.tv.*` (`device.DeviceActivity`, `preferences.PreferencesActivity`, `network.NetworkActivity`, `display_sounds.DisplayAndSoundsActivity`, `controllers_bluetooth_devices.ControllersAndBluetoothActivity`, `accessibility.AccessibilityActivity`, `applications.ApplicationsActivity`) | Settings sub-pages | dumbtv `FireSettings.java`, [Amazon developer forum](https://community.amazondeveloper.com/t/cannot-open-system-accessibility-settings-screen-programmatically-on-firetv/9422) |
| `com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler` | what `android.settings.SETTINGS` resolves to (2.2) | home-on-fire#12 |
| `com.amazon.venezia` `AppsGridLauncherActivity` and other `AppsGrid*` | the remote's Apps button; on Fire OS 8 it then starts `HomeActivity_vNext` itself, 0.2 to 1 s later | home-on-fire `HijackService.java` |
| `com.amazon.venezia/.pdi.AppLaunchActivity` | app shortcut buttons on the remote | firestick-fixer README |
| `com.amazon.tv.inputpreference.service/.StartAmazonInputActivity` (action `SWITCH_AMAZON_INPUT`), `.player.PassthroughPlayerActivity`, `com.amazon.android.amazoninputswitch` | TV input switching; briefly hands focus back to `HomeActivity_vNext` | home-on-fire#13 |
| `com.amazon.tv.livetv` (for example `.guide.GuideActivity`) | Live TV | [dumbtv device notes](https://github.com/humblemuzzu/dumbtv/blob/main/catalog/devices/fireos-insignia-aftdct31.yaml) |
| `com.amazon.tahoe/.ftv.HomeActivity`, `com.amazon.tahoe.ftv.pin.ui.PinActivity` | Amazon Kids home and PIN; kids profiles are separate Android users | home-on-fire#7 |
| `com.amazon.ftv.profilepicker` | profile picker (class UNVERIFIED) | package lists |
| `com.amazon.tv.notificationcenter/.NotificationCenterActivity` | notifications | dumbtv `FireSettings.java`, Leanback on Fire |
| `com.amazon.tv.oobe`, `com.amazon.vizzini`, `com.amazon.tv.turnstile`, `com.amazon.ftv.screensaver`, `com.amazon.tv.keypolicymanager` | setup, Alexa UI, PIN sign-in, screensaver, key policy | package lists (UNVERIFIED roles) |

Two Fire OS facts break a pure class-name rule, and the design accounts for
them (6.4):

- **Fire OS 7 draws its Settings tab inside `HomeActivity_vNext`**, so Back
  from a Settings sub-page can land on `HomeActivity_vNext` showing Settings
  ([Homestead#10](https://github.com/Sarg338/Homestead/pull/10),
  [FireFrame#4](https://github.com/dyascj/FireFrame/issues/4)).
- **Fire OS 8 draws its Apps screen and its Inputs view inside
  `HomeActivity_vNext`** (Compose UI); only the navigation bar's view ids
  (`nav-tab-apps*` against `nav-tab-movies_and_tv*`) tell them apart, readable
  with `flagReportViewIds` (home-on-fire `HijackService.java`). The Inputs
  view is opened with `com.amazon.tv.action.NAVIGATE_TO_INPUTS`.

### 2.2 Settings on Fire OS

- `android.settings.SETTINGS` resolves to
  `com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler`. On Fire OS
  7.7.1.5 and 8.1.8.5 that stub starts `HomeActivity_vNext` "(has extras)"
  from the settings app's uid, and the launcher opens
  `com.amazon.tv.launcher/.ui.MainSettingsActivity` 1 to 2.5 s later. Every
  redirect tool that watches for the home window sees this as a Home press:
  that is the settings loop reported for Projectivy
  ([miproja1#338](https://github.com/spocky/miproja1/issues/338)), Home on Fire
  ([#12](https://github.com/toolicious/home-on-fire/issues/12), open as of
  2026-11-17) and couch-tv ([#75](https://github.com/Seagey/couch-tv/issues/75)).
  On 7.7.1.3 the stub reportedly opens nothing, and one project saw
  `ACTION_SETTINGS` resolve to `com.amazon.tv.launcher/.ui.SettingsActivity`
  instead; the difference is probably per build (UNVERIFIED).
- What works for opening Settings from an app:
  `com.amazon.tv.launcher/.ui.MainSettingsActivity` by explicit component
  ([cleanyourtv#407](https://github.com/Gathin23/cleanyourtv/pull/407), from an
  app on 7.7.1.3; Leanback on Fire's settings tile), optionally with action
  `amazon.intent.action.MAINSETTINGS`
  ([miproja1#338](https://github.com/spocky/miproja1/issues/338)), or
  `ACTION_MAIN` + category `android.intent.category.LEANBACK_SETTINGS` with
  package `com.amazon.tv.launcher` (dumbtv `Home.java`). Rozan targeting API
  30+ needs a `<queries>` entry for `com.amazon.tv.launcher`.
- Sub-pages: the activities in `com.amazon.tv.settings.v2` require
  `com.amazon.tv.permission.LAUNCHER_SETTINGS`, so third-party apps get a
  `SecurityException` for, for example, `.tv.accessibility.AccessibilityActivity`
  ([Amazon developer forum](https://community.amazondeveloper.com/t/cannot-open-system-accessibility-settings-screen-programmatically-on-firetv/9422)).
  On Fire OS 6, dumbtv found a few exported entry points that a third-party
  app may start:

  | Action | Component |
  | --- | --- |
  | `com.amazon.device.settings.action.ADD_NETWORK` | `com.amazon.tv.settings.v2/.tv.network.NetworkActivity` |
  | `com.amazon.device.settings.action.APPLICATIONS` | `com.amazon.tv.settings.v2/.tv.applications.ApplicationsActivity` |
  | `com.amazon.device.settings.action.DEVICE` | `com.amazon.tv.settings.v2/.tv.device.DeviceActivity` |
  | `com.amazon.device.settings.action.CONTROLLERS` | `com.amazon.tv.settings.v2/.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity` |
  | `com.amazon.tv.action.NOTIFICATION_CENTER` | `com.amazon.tv.notificationcenter/.NotificationCenterActivity` |

  Display and sounds, preferences, accessibility, account, profiles, inputs,
  Live TV and Alexa need Amazon signature permissions (dumbtv
  `FireSettings.java`). Whether these entry points work on Fire OS 7 and 8 is
  UNVERIFIED. Rozan should offer one "Settings" entry
  (`MainSettingsActivity`) and resolve-check any deep link before showing it.
- Amazon documents no Fire TV settings intents; its "Supported Android
  Intents" page covers Fire tablets only.
- Inputs on Fire TV smart TVs: `TUNE_INPUT` is a signature permission and the
  input-switch services are blocked on most builds, so a third-party app
  cannot switch inputs reliably
  ([dumbtv#63](https://github.com/humblemuzzu/dumbtv/issues/63),
  [Homestead#45](https://github.com/Sarg338/Homestead/pull/45)). The
  supported routes are Amazon's own inputs view
  (`com.amazon.tv.action.NAVIGATE_TO_INPUTS`, which opens inside the stock
  home and is therefore a hand-over, 6.4) or a public `TvView` with
  `TvContract.buildChannelUriForPassthroughInput(id)` inside Rozan
  ([tv-vibehack-firetv](https://github.com/pulgagr/tv-vibehack-firetv) README).
- The Appstore's launcher entry opens Amazon home on Fire OS 8 and the Cube;
  open `com.amazon.venezia/.ade.ADEHomeActivity` instead (dumbtv `Home.java`).

### 2.3 How Fire OS delivers the Home key

- `KEYCODE_HOME` never reaches an accessibility key filter on Fire OS 6.7, 7
  or 8. Amazon's `FireTVKeyPolicyManager` ("KPM") takes it in
  `system_server`; logcat shows "KPM proceeds the key". Back, OK, D-pad, Menu
  and media keys do reach the filter
  ([firestick-fixer DECISIONS.md D3](https://github.com/Hornet34/firestick-fixer/blob/main/docs/DECISIONS.md),
  home-on-fire `res/xml/accessibility_config.xml`,
  [firetv-tvos](https://github.com/NikhilSiddhu/firetv-tvos) on 7.7.1.7). In
  AOSP terms (A1) the policy keeps Home from being passed to the user, so the
  accessibility filter never sees it.
- `KEYCODE_SETTINGS` (176) is also taken by KPM and routed to quick settings
  ([dumbtv#217](https://github.com/humblemuzzu/dumbtv/pull/217)). App buttons
  on the remote never arrive either.
- `KEYCODE_TV_INPUT` (178) did reach the filter on a Fire OS 14 TCL TV
  ([home-on-fire#13](https://github.com/toolicious/home-on-fire/issues/13)).
- Long-press Home logs `PhoneWindowManager: Undefined home long press
  behavior` and Amazon's `sendLongHomeBroadcasts`; the panel is
  `HudActivity` or `com.amazon.tv.quicksettings.ui` (2.1).
- The broadcasts that would announce a Home press are permission-protected:
  `com.amazon.tv.action.HOME_LONGPRESSED` needs
  `com.amazon.permission.RECEIVE_HOME_LONGPRESSED_ACTION`
  ([home-on-fire#7](https://github.com/toolicious/home-on-fire/issues/7));
  `amazon.intent.action.HOME_PRESSED` needs
  `amazon.intent.permission.HOME_PRESSED` (UNVERIFIED, single blog source).
  On one Fire OS 6.7 TV the AOSP `ACTION_CLOSE_SYSTEM_DIALOGS` "homekey"
  broadcast needed `com.amazon.permission.ENABLE_KEYGUARD_FLAGS`
  ([tv-vibehack-firetv](https://github.com/pulgagr/tv-vibehack-firetv));
  whether Fire OS 7 and 8 deliver it to a third-party receiver is UNVERIFIED.
- Log signal: on Fire OS 8.1.8.5 `ActivityTaskManager` logs exactly one
  `START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] … } from uid 0`
  per Home press, about 10 ms after the press; the Settings trampoline's start
  comes from the settings app's uid
  ([firestick-fixer D6 to D8](https://github.com/Hornet34/firestick-fixer/blob/main/docs/DECISIONS.md),
  [Homestead#10](https://github.com/Sarg338/Homestead/pull/10)). Reading it
  needs `pm grant <pkg> android.permission.READ_LOGS`.
- History of Amazon blocking launchers: Fire OS 6.2.7.2 reworked Home handling
  and broke FTVLaunchX
  ([FTVLaunchX#4](https://github.com/codefaktor/FTVLaunchX/issues/4)); Fire OS
  7.2.7.3 and 6.2.8.7 (February 2022) broke Launcher Manager's launcher swap
  ([AFTVnews](https://www.aftvnews.com/amazon-blocks-custom-home-launchers-with-latest-fire-tv-software-update/));
  Fire OS 7.6.6.9 and 8.1.0.3 (February 2024) blocked local adb from apps
  ([AFTVnews](https://www.aftvnews.com/amazon-blocks-long-running-fire-tv-capability-breaking-popular-apps-with-no-warning-and-giving-developers-the-runaround/)).
  Amazon's `com.amazon.adep` service disables blocklisted package names with
  "… is not compatible with this device"; that happened to
  `com.baronkiko.launcherhijack` and Launcher Manager's `com.wolf.lm`
  ([LauncherHijack#62](https://github.com/BaronKiko/LauncherHijack/issues/62),
  [TROYPOINT](https://troypoint.com/launcher-manager-1-1-8-ban-workaround/)).
  Accessibility-based redirects still work on Fire OS 7 and 8 in reports from
  October and November 2026.

### 2.4 Changing the default HOME on Fire OS: not possible

- There is no home-app picker in Fire TV Settings.
- `cmd package set-home-activity` prints `Success` and changes nothing on
  7.7.1.7, 8.1.8.2 and 8.1.8.5, also after a reboot
  ([firetv-tvos](https://github.com/NikhilSiddhu/firetv-tvos),
  [projectivy-installer](https://github.com/Absolute-Projects-Public/projectivy-installer),
  [cleanyourtv#400](https://github.com/Gathin23/cleanyourtv/pull/400)). This
  is what AOSP predicts (A4): `HomeActivity_vNext` has priority 950, Rozan's
  filter is capped at 0, and the resolver never consults the preferred
  activity when priorities differ. `cmd role add-role-holder
  android.app.role.HOME` is the same call on Android 10+ and has the same
  outcome (reported inert; UNVERIFIED on Fire OS, but A4 predicts it).
- `pm disable-user --user 0 com.amazon.tv.launcher` (and
  `com.amazon.firehomestarter`) fails with "Cannot disable a protected
  package" on Fire OS 7 and 8; component toggles fail with "Shell cannot
  change component state"
  ([cleanyourtv TROUBLESHOOTING](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md),
  [projectivy-installer](https://github.com/Absolute-Projects-Public/projectivy-installer)).
  On Fire tablets the same refusal arrived in 2021
  ([LauncherHijack#64](https://github.com/BaronKiko/LauncherHijack/issues/64)).
  Even where root or a privileged exploit allowed it, disabling the launcher
  removes Settings (it lives in the launcher); users report a white screen.
  Privileged-exploit routes were patched around Fire OS 7 PS7706 and 8 RS8153
  (October 2025).
- `am force-stop` on Amazon home is refused
  ([cleanyourtv#400](https://github.com/Gathin23/cleanyourtv/pull/400)).

### 2.5 Accessibility services on Fire OS

- Fire OS 5.1.1, 6.2.8.0 and 6.7.x: Amazon's `FireOSFwk:A11yAuthorizer`
  refuses to bind third-party services it does not trust; the service shows
  as enabled and is never bound
  ([Fire2MQTT#45](https://github.com/Harrypulvirenti/Fire2MQTT/issues/45),
  [dumbtv#4](https://github.com/humblemuzzu/dumbtv/pull/4)). Which Fire OS 7
  and 8 builds also run it is UNVERIFIED; one AFTTI43 would not bind until
  parental controls were turned off (dumbtv `install.sh`).
- Fire OS 6.2.6.4: `settings put secure enabled_accessibility_services` was
  silently dropped; `content insert --uri content://settings/secure` works
  ([LauncherHijack#52](https://github.com/BaronKiko/LauncherHijack/issues/52),
  dumbtv `install.sh`).
- Fire OS 8.1.5.5 and later: Settings > Accessibility no longer lists
  third-party services, so they must be enabled over adb
  ([miproja1#380](https://github.com/spocky/miproja1/issues/380)).
- Fire OS 8: after an out-of-memory kill the service is not rebound until the
  setting is toggled; Fire OS 7 rebinds by itself (dumbtv
  `docs/TROUBLESHOOTING.md`, #162). App updates, settings resets and
  force-stops can turn it off (A7).
- Any enabled service has side effects: Prime Video's playback controls stop
  auto-hiding ([miproja1#380](https://github.com/spocky/miproja1/issues/380)),
  and `com.amazon.ssmsys`'s network status screen opens without focus
  (dumbtv TROUBLESHOOTING, #130).

### 2.6 What happens after Home, and what other projects do

- Fire OS 7 (Android 9): a start made in reaction to the stock home is held
  for up to 5 s ("often about a second"): the AOSP app-switch hold (A3). Home
  on Fire covers it with a loading screen; FireStick Fixer covers the screen
  on the log line; dumbtv starts its own home screen through an alarm with
  `min_futurity=0`, and the Shelf launcher through an alarm as well
  ([home-on-fire#2](https://github.com/toolicious/home-on-fire/issues/2))
  ([home-on-fire README](https://github.com/toolicious/home-on-fire),
  [LauncherHijack#57](https://github.com/BaronKiko/LauncherHijack/issues/57),
  [miproja1#338](https://github.com/spocky/miproja1/issues/338)).
- Fire OS 8 (Android 11): a direct `startActivity` from the accessibility
  service works and the stock home flashes for about 250 ms. One project's
  alarm route hit `Background activity start … callingUidHasAnyVisibleWindow:
  false` until a cover window had drawn
  ([dumbtv#31](https://github.com/humblemuzzu/dumbtv/pull/31)).

Open-source implementations (source read at the commits listed):

| Project | Events and flags | Home detection | Known problems and fixes |
| --- | --- | --- | --- |
| [LauncherHijack](https://github.com/BaronKiko/LauncherHijack) (f79aee3, deprecated) | `typeWindowStateChanged`, view clicks; `flagRequestFilterKeyEvents`; `notificationTimeout=50` | `onKeyEvent` Home down (which Fire OS builds still delivered it is UNVERIFIED; FTVLaunchX dates the change to 6.2.7.2), `ACTION_CLOSE_SYSTEM_DIALOGS` "homekey", whole-package window match on tablets; 200 ms debounce | Matching all of `com.amazon.tv.launcher` broke Settings because "the FireTV devices have their Settings.apk integrated into the launcher itself" ([PR #20](https://github.com/BaronKiko/LauncherHijack/pull/20)); fix was key-based detection plus Menu+Home to reach the stock long-press menu; package name blocklisted by Amazon |
| [FTVLaunchX](https://github.com/codefaktor/FTVLaunchX) (3b46fed) | `typeWindowsChanged`, key filter | Home down consumed; launch on every screen-on | Broken since Fire OS 6.2.7.2; CEC Home from the TV remote not caught |
| [Home on Fire](https://github.com/toolicious/home-on-fire) (f629042, 1.5.2) | `typeWindowStateChanged`, `typeViewFocused`; key filter, interactive windows, view ids | exact `HomeActivity_vNext` (and Kids home); HUD and quick settings matched by class; Apps grid hand-over; about ten time windows | Settings loop (#12, open); Input switching (#13); double redirect from a late second event (#11); Kids trap if long-press is swallowed (#7) |
| [FireStick Fixer](https://github.com/Hornet34/firestick-fixer) (b68a7f2) | window events plus optional `READ_LOGS` | log line `START … cat=[HOME] … from uid 0`, cover window at about 3 ms, launcher at about 40 ms | Window events repeat for about 1 s per press; trusts the log only per `Build.DISPLAY` (D7); dropped long-press escape because it broke the panel's tiles (D5) |
| [dumbtv](https://github.com/humblemuzzu/dumbtv) (21c57d6) | `typeWindowStateChanged` only, no content, `notificationTimeout=0`, no key filter | exact `HomeActivity_vNext`; alarm trampoline; cover window | Uses several timers; escape to Amazon home pauses the redirect until another app opens (no timer) |
| [Leanback on Fire](https://github.com/tsynik/LeanbackLauncher) | none | none | Settings tile = `MainSettingsActivity`; hides `profilepicker` |
| Projectivy (closed; [tracker](https://github.com/spocky/miproja1)) | accessibility "Override current launcher" | not public | Settings loop (#338); 5 s on Fire OS 7; service must be enabled over adb on 8.1.5.5+ (#380) |

### 2.7 Boot, standby and profiles on Fire OS

- At boot `FallbackHome` shows for about 15 s, then `HomeActivity_vNext`. On
  Fire OS 8.1.8.5 the accessibility service connected about 22 s after power
  on, about 0.2 s before Amazon home started, and `BOOT_COMPLETED` reached
  apps about a minute later
  ([firestick-fixer D13](https://github.com/Hornet34/firestick-fixer/blob/main/docs/DECISIONS.md)).
  So the first home window after boot arrives as an ordinary event.
- With more than one profile, a "Who's watching?" picker shows at boot and on
  wake ([XDA](https://xdaforums.com/t/does-ftv-stick-always-go-to-profile-picker-on-wake.4626661/));
  its class is UNVERIFIED (package `com.amazon.ftv.profilepicker` on Fire OS 6;
  possibly inside the launcher on 8). It is not in `stockHome`, so it is never
  redirected; the home window after the choice is.
- Kids profiles are separate Android users (for example user 5) with the
  launcher and Appstore disabled by policy, and `com.amazon.tahoe/.ftv.HomeActivity`
  as home; the long-press panel is their only way out
  ([home-on-fire#7](https://github.com/toolicious/home-on-fire/issues/7)).
  Whether adult profiles are separate users is UNVERIFIED.
- When going to standby, Fire OS brings Amazon home to the front with the
  screen already off (dumbtv TROUBLESHOOTING, #153); a redirect then happens
  while the screen is off.
- Fire OS 6 with "PIN protect app launches" on: any app started over Amazon
  home triggers the Prime Video PIN prompt
  ([cleanyourtv notes](https://github.com/Gathin23/cleanyourtv/blob/main/examples/amazon-fire-tv-stick-4k-2018/debloat.txt)).

### 2.8 Fire TV smart TVs

Everything above applies, plus:

- Input switching passes through `HomeActivity_vNext` (2.1, 2.2): picking an
  input goes `StartAmazonInputActivity` → `HomeActivity_vNext` briefly →
  `PassthroughPlayerActivity`
  ([home-on-fire#13](https://github.com/toolicious/home-on-fire/issues/13)).
  The Input key opens `HomeActivity_vNext` on its inputs view.
- Going Home from an HDMI input takes about 5 s because Live TV holds the
  display (dumbtv TROUBLESHOOTING, [#31](https://github.com/humblemuzzu/dumbtv/pull/31)).
- Settings > Display & Sounds > Power On offers "Home" or "Last Input"
  ([AFTVnews](https://www.aftvnews.com/toshiba-fire-tv-edition-television-software-update-lets-you-power-on-to-your-last-input/)).
- On a Xiaomi Fire OS 8 TV the long-press panel has only picture and sleep
  toggles, no Settings entry
  ([Fire-Stick-Debloat#22](https://github.com/ScaryWubDubz/Fire-Stick-Debloat/issues/22)).
- Fire OS 14 TVs are Android 12 to 14: the AOSP app-switch hold is gone
  there (A3), so the 5 s problem does not apply; everything else is
  UNVERIFIED on those TVs.

## 3. Google TV

Devices and versions: Chromecast with Google TV 4K launched on Android 10,
then 12, then 14 in 2025
([9to5Google](https://9to5google.com/2025/03/13/chromecast-google-tv-android-14-update/));
Chromecast with Google TV HD is on 14; Google TV Streamer (2024) launched on
Android TV 14
([PhoneArena](https://www.phonearena.com/news/google-tv-streamer-is-their-first-to-bring-the-benefits-of-the-new-android-14-for-tvs_id161265));
onn 4K and 4K Pro went from 12 to 14 in mid-2025
([AFTVnews](https://www.aftvnews.com/android-tv-14-update-arrives-for-the-onn-4k-and-onn-4k-pro/));
Sony (2021+), TCL, Hisense, Philips and Xiaomi Google TV models run 11 to 14.

### 3.1 Launcher and what else lives in it

- Home: `com.google.android.apps.tv.launcherx/.home.HomeActivity`, HOME
  priority 2. Other HOME candidates:
  `com.google.android.tungsten.setupwraith/.RecoveryActivity` (priority 1;
  `.MainActivity` priority 4 during setup on some units) and
  `com.android.tv.settings/.system.FallbackHome` (priority -1000)
  ([arclauncher#52](https://github.com/meddouribadis/arclauncher/issues/52),
  [cleanyourtv TROUBLESHOOTING](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md)).
- Not home, in launcherx:
  `.profile.chooser.ProfileChooserActivity` (profile picker;
  [Plume_Launcher#229](https://github.com/aradhyas8/Plume_Launcher/issues/229),
  [couch#16](https://github.com/samuelloranger/couch/pull/16)),
  `.dashboard.DashboardActivity` and `.dashboard.DashboardHandler` (the side
  panel and long-press Home), `.gemini.chat.ChatActivity` and
  `.entity.EntityActivity` (voice, Gemini and search results on Google TV 14;
  [cleanyourtv#2812](https://github.com/Gathin23/cleanyourtv/issues/2812)), and
  the receivers `.remotecontrol.GlobalKeyListener_Receiver` and
  `.globalbutton.GlobalButtonLaunch_Receiver` that handle the remote's app
  buttons. Kids home classes (`.home.KidsDarkHomeActivity`,
  `.home.KidsLightHomeActivity`) are UNVERIFIED.
- Also never home: setupwraith, FallbackHome, `com.google.android.apps.tv.dreamx`
  (Ambient mode and the Google Home panel), `com.android.tv.settings`.
- Profiles are separate Android users (user type `com.android.tv.profile`,
  user 10 and up), each with its own enabled-package state and its own
  accessibility settings.

### 3.2 Settings

`android.settings.SETTINGS` opens the full `com.android.tv.settings/.MainSettings`
on Google TV (seen on Sony Google TV 14); the gear panel is launcherx's
dashboard, a different thing. The AOSP actions in A8 apply. Device notes:
`APPLICATION_SETTINGS` is declared by two activities in AOSP 14 and can be
ambiguous; `DISPLAY_SETTINGS` is not declared by AOSP TV Settings and is a
stub on Xiaomi (real page
`com.android.tv.settings/com.xiaomi.mitv.settings.picture.PictureActivity`);
some Amlogic builds use `com.droidlogic.tv.settings/.MainSettings`. Inputs:
`com.android.tv.action.VIEW_INPUTS` goes to a vendor activity (Sony
`com.sony.dtv.smartui/.select.SmartSelectActivity`, Realtek
`com.realtek.tv/.SelectInputActivity`, Xiaomi
`com.mitv.livetv/.input.SelectInputActivity`); resolve it per device. The
notification panel opens only a system handler (`MATCH_SYSTEM_ONLY` and
`STATUS_BAR_SERVICE` in
[TvNotificationPanel.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/packages/SystemUI/src/com/android/systemui/statusbar/tv/notifications/TvNotificationPanel.java)),
so Rozan cannot replace it and should not try.

### 3.3 Home key

- An accessibility key filter receives `KEYCODE_HOME` on Google TV:
  cleanyourtv's `HomeKeyService` redirects it
  ([cleanyourtv#2779](https://github.com/Gathin23/cleanyourtv/issues/2779)),
  and tvQuickActions remaps it
  ([PCWorld](https://www.pcworld.com/article/3046242/make-your-google-tv-look-like-apple-tv-with-this-free-app.html)).
  No source names a Google TV version that stopped delivering it. A March 2026
  report that Projectivy's override stopped working on some Google TVs gives
  no mechanism
  ([Android Authority](https://www.androidauthority.com/google-tv-update-blocks-projectivy-override-3649445/)).
- Long press Home on Chromecast with Google TV 4K (Google TV 14) sends
  `TOGGLE_NOTIFICATION_HANDLER_PANEL` to launcherx's `DashboardHandler`.
  Consuming Home loses it, and with launcherx disabled it does nothing.
- Remote buttons are global keys routed to launcherx: YouTube is `BUTTON_3`
  (190), Netflix `BUTTON_4` (191) on the Streamer, the star button `MACRO_1`
  (313), the profile button `KEYCODE_PROFILE_SWITCH` (288) on onn, the gear
  `KEYCODE_NOTIFICATION` (83) or `KEYCODE_SETTINGS` (176). The Chromecast
  remote's Input button is IR to the TV and never reaches the box.
- Field observations about any key-filtering service on these devices: held
  keys stop auto-repeating (volume steps once per press) on TCL, onn,
  Hisense, Xiaomi and Philips firmware, and D-pad lag of 100 to 300 ms was
  seen with Projectivy bound
  ([cleanyourtv TROUBLESHOOTING](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md)).
  Keys injected with `adb shell input keyevent` skip the filter, so test with
  the physical remote. Calling `setServiceInfo()` at runtime dropped
  `flagRequestFilterKeyEvents` that the XML had set
  ([cleanyourtv#2779](https://github.com/Gathin23/cleanyourtv/issues/2779)).

### 3.4 Changing the default HOME on Google TV

- `cmd package set-home-activity` and `cmd role add-role-holder
  android.app.role.HOME` print Success and change nothing while launcherx is
  enabled; a Projectivy user saw the role holder set while
  `resolve-activity` still returned launcherx
  ([miproja1#605](https://github.com/spocky/miproja1/issues/605),
  [arclauncher#52](https://github.com/meddouribadis/arclauncher/issues/52)).
  This is the priority rule in A4.
- The only way to make Rozan the real home is `pm disable-user --user 0` on
  launcherx **and** setupwraith (otherwise `RecoveryActivity` takes Home,
  re-enables launcherx and can loop on a black screen). What then breaks:
  profile chooser and account settings, Assistant and Gemini results, the
  dashboard and long-press Home, the YouTube, star and Free TV buttons, the
  Google Home panel, casting ("Untrusted device"; setupwraith hosts the cast
  info provider), and a Wi-Fi network saved during setup. A Google TV
  Streamer reboots constantly without launcherx (#605); Xiaomi MOSR4 and MOEU0
  units factory-reset or hung at the boot animation; Acer and Panasonic
  firmware re-enable setupwraith on every boot
  ([FLauncher README](https://github.com/svrooij/flauncher),
  [cleanyourtv TROUBLESHOOTING](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md)).
  Rozan must not do this, and should not recommend it.
- There is no Home picker in Google TV Settings (A4, A8).

### 3.5 Redirect specifics on Google TV

- Profile button: launcherx starts `HomeActivity`, then
  `ProfileChooserActivity` about 70 ms later; a redirect on the first event
  can cover the chooser
  ([cleanyourtv#2779](https://github.com/Gathin23/cleanyourtv/issues/2779)).
  Event-driven handling in 6.4.
- Type of Rozan's task (A5): Projectivy 4.73 starts itself with `MAIN` +
  `HOME` so its task is `type=home` and Back from an app no longer flashes
  launcherx ([miproja1#605](https://github.com/spocky/miproja1/issues/605)).
  cleanyourtv found the opposite trade-off: once launcherx is no longer the
  home process it loses its background-start exemption and the YouTube and
  Netflix buttons fail (`BAL_BLOCK`) unless launcherx is given
  `appops set com.google.android.apps.tv.launcherx SYSTEM_ALERT_WINDOW allow`
  (cleanyourtv TROUBLESHOOTING). Needs hardware testing (section 9).
- Boot: launcherx is already on screen when an accessibility service binds,
  so no window event comes
  ([cleanyourtv#1116](https://github.com/Gathin23/cleanyourtv/issues/1116));
  remap apps report that "the standard home screen will always appear when
  your TV first turns on" (PCWorld). cleanyourtv's answer is a probe: start
  an invisible activity that finishes on its first frame, so the window
  underneath regains focus and sends `TYPE_WINDOW_STATE_CHANGED` with its
  class (A6). That is event driven and needs no extra permission (6.7).
- Sideloaded apps on Android 13+ can have their accessibility toggle greyed
  out as a "restricted setting" until the user allows it under App info
  ([Google help](https://support.google.com/android/answer/12623953)); enabling
  over adb avoids that
  ([miproja1#252](https://github.com/spocky/miproja1/issues/252)).
- TCL boot manager unbinds services at boot ("TclAppBoot: forbid bind
  service") unless the `AUTO_START` (or `APP_AUTO_START`) app-op is allowed
  and the app is on the deviceidle whitelist; setting `accessibility_enabled`
  to 1 alone did not rebind, clearing and rewriting the list did
  ([couch-tv#94](https://github.com/Seagey/couch-tv/issues/94),
  [miproja1#489](https://github.com/spocky/miproja1/issues/489),
  [cleanyourtv#1047](https://github.com/Gathin23/cleanyourtv/issues/1047)).

## 4. Classic Android TV

Devices: Nvidia Shield (all models, Android TV 11), Xiaomi Mi Box S (Android
9, with an Android 12 update), Mi TV Stick 1080p (Android 9), Mi TV Stick 4K
(Android TV 11), Sony and Philips Android TV (non-Google-TV) models. Xiaomi's
2nd and 3rd gen boxes and 2025 Stick 4K are Google TV (section 3).

- Home: `com.google.android.tvlauncher/.MainActivity`, HOME priority 2,
  `singleTask`. Not home in the same package: `.appsview.AppsViewActivity`
  (answers `ACTION_ALL_APPS`, which long-press Home sends on the AOSP TV
  config, A2), `.inputs.InputsPanelActivity` (`com.android.tv.action.VIEW_INPUTS`),
  `.notifications.NotificationsSidePanelActivity`,
  `.settings.HomeScreenSettingsActivity`, `.settings.AppChannelPermissionActivity`,
  `.settings.FavoriteLaunchItemsActivity`, `.appsview.AddAppLinkActivity`,
  `.appsview.RemoveAppLinkActivity`
  ([decompiled manifest](https://github.com/AndroidTVDeveloper/com.google.android.tvlauncher/blob/master/src/main/AndroidManifest.xml)).
  Android TV 5 to 7 used `com.google.android.leanbacklauncher/.MainActivity`.
- Xiaomi Mi TV sets with PatchWall: `com.mitv.tvhome/.MainActivityUserMode`;
  on Mi Box S PatchWall is an app (`com.mitv.tvhome.atv`) that the firmware
  re-enables about 3 minutes after it is disabled. China-market firmware
  refuses third-party HOME candidates ("cannot be home on user 0")
  (cleanyourtv notes).
- Settings: AOSP TV Settings (A8).
- Home key: reaches the filter on AOSP code paths (A1). Shield: press goes
  Home, double press opens recent apps, press and hold opens all apps (or
  NVIDIA Share)
  ([NVIDIA](https://www.nvidia.com/en-eu/shield/support/shield-tv/know-your-shield-tv/)).
  Philips Android TV 12 uses `LONG_PRESS_HOME_ALL_APPS`. Some Mi TV Stick
  remotes send `KEYCODE_ALL_APPS` (284) for Home, which stock firmware sends
  only to the system launcher's apps view (cleanyourtv Xiaomi notes,
  UNVERIFIED as a general rule).
- Default HOME: same priority problem as Google TV. `set-home-activity`
  prints Success and is ignored while tvlauncher is enabled (Shield, Android
  11, in cleanyourtv's notes). Disabling tvlauncher with `pm disable-user
  --user 0 com.google.android.tvlauncher` works and is tolerable on Shield
  (setupwraith declares no live HOME after setup there); after that Rozan is
  the highest HOME candidate and becomes the real home (native mode). It also
  removes the app list behind long-press Home and the inputs panel, so it is
  an owner opt-in, never automatic.

## 5. Platforms that cannot run Rozan

- **Vega OS Fire TV devices** (Amazon's Linux-based OS; no Android apps, no
  sideloading): Fire TV Stick 4K Select (AFTCA002, 2025), Fire TV Stick HD 2nd
  gen (AFTCL001, announced 2026-04-15), Fire TV Stick 4K 3rd gen (AFTCR001,
  announced 2026-09-30, Vega OS 2.0). Amazon: "Starting with Fire TV Stick 4K
  Select, all future Fire TV Sticks will run on Vega"
  ([AFTVnews Vega OS tag](https://www.aftvnews.com/tag/vega-os/), Amazon's
  device table). The model numbers come from Amazon's device table and were
  not re-checked; the three models and their dates were checked on AFTVnews.
- **Roku OS, LG webOS, Samsung Tizen**: not Android, cannot install APKs
  ([Wikipedia list of smart TV platforms](https://en.wikipedia.org/wiki/List_of_smart_TV_platforms)).
  Rozan should not be offered for them. Roku's own documentation on the
  Home key, background apps, media formats and its built-in AirPlay is in
  [roku.md](roku.md).

## A. How the platform delivers "go home" (AOSP source)

Everything in this section was read in AOSP source on the release branches
for Android 7 (`nougat-release`), 9 (`pie-release`, Fire OS 7), 10, 11
(`android11-release`, Fire OS 8), 12, 13 and 14. Vendors (Amazon above all) change some of this; where they
do, the device sections say so.

### A1. The Home key's path through the input system

1. `InputDispatcher::notifyKey` first calls the window manager policy's
   `interceptKeyBeforeQueueing`, then, only if an input filter is installed
   (it is when an accessibility service asks to filter keys), hands the event
   to the accessibility filter, then queues it for dispatch
   ([pie InputDispatcher.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/pie-release/services/inputflinger/InputDispatcher.cpp),
   `notifyKey`).
2. Key repeats, and with them `FLAG_LONG_PRESS`, are synthesized later, at
   dispatch time (`dispatchKeyLocked`, `synthesizeKeyRepeatLocked` in
   [android11 InputDispatcher.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android11-release/services/inputflinger/dispatcher/InputDispatcher.cpp)).
   `EventHub` turns off kernel key repeat for keyboard-class devices
   ("Disable kernel key repeat since we handle it ourselves",
   [EventHub.cpp](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android11-release/services/inputflinger/reader/EventHub.cpp)).
   So an accessibility service's `onKeyEvent` sees one `ACTION_DOWN` and one
   `ACTION_UP` per press, always with `repeatCount == 0` and never
   `FLAG_LONG_PRESS`. (A device driver that generates its own repeats, which
   the dispatcher detects as "two identical key downs in a row", would show
   up as several `ACTION_DOWN`s, still with `repeatCount == 0`.) At the
   moment it must answer for `ACTION_DOWN` the service cannot know whether
   the press will be short or long.
3. `AccessibilityInputFilter.onInputEvent` passes straight through any event
   the policy did not mark `FLAG_PASS_TO_USER`
   ([pie AccessibilityInputFilter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/accessibility/java/com/android/server/accessibility/AccessibilityInputFilter.java)).
   A vendor policy that handles Home inside `interceptKeyBeforeQueueing`
   therefore hides Home from every accessibility service. This is what Fire
   OS does (section 2.3).
4. `KeyboardInterceptor` delays only the volume keys; Home is forwarded to the
   services unchanged in Android 9, 11 and 14
   ([pie](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/accessibility/java/com/android/server/accessibility/KeyboardInterceptor.java),
   [android14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/accessibility/java/com/android/server/accessibility/KeyboardInterceptor.java)).
   If the service does not answer within 500 ms the event is passed on
   (`ON_KEY_EVENT_TIMEOUT_MILLIS`,
   [KeyEventDispatcher.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/accessibility/java/com/android/server/accessibility/KeyEventDispatcher.java)).
   `flagRequestFilterKeyEvents` only works if the service's XML also sets
   `android:canRequestFilterKeyEvents="true"`
   ([AccessibilityServiceInfo.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/android/accessibilityservice/AccessibilityServiceInfo.java)).

### A2. What the policy does with Home

From `PhoneWindowManager`
([pie](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/policy/PhoneWindowManager.java),
[android11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/policy/PhoneWindowManager.java),
[android14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/policy/PhoneWindowManager.java)):

- Home is acted on at `ACTION_UP` in `interceptKeyBeforeDispatching`. A
  repeat with `FLAG_LONG_PRESS` calls `handleLongPressOnHome` and marks the
  press consumed, so the later `ACTION_UP` does nothing.
- The AOSP Android TV overlay sets `config_longPressOnHomeBehavior` to `1`
  (`LONG_PRESS_HOME_ALL_APPS`) on 9, 11, 12 and 14
  ([pie overlay](https://android.googlesource.com/device/google/atv/+/refs/heads/pie-release/overlay/frameworks/base/core/res/res/values/config.xml),
  [android14 overlay](https://android.googlesource.com/device/google/atv/+/refs/heads/android14-release/overlay/TvFrameworkOverlay/res/values/config.xml)).
  `launchAllAppsAction` then sends `Intent.ACTION_ALL_APPS` to the package of
  the *system* HOME app (`MATCH_SYSTEM_ONLY`), so a long press opens the stock
  launcher's app list even when another app is the default home. Android 14's
  TV overlay also sets `config_doubleTapOnHomeBehavior` to `2` (picture in
  picture menu), which makes the policy hold a single press for the double-tap
  timeout before going home.
- `handleShortPressOnHome` sends HDMI-CEC One Touch Play (wakes the TV and
  switches its input), and if a screensaver (dream) is running it only stops
  the dream and does not go home. Otherwise `launchHomeFromHotKey` →
  `startDockOrHome`, which:
  1. calls `ActivityManager.stopAppSwitches()` (section A3);
  2. broadcasts `ACTION_CLOSE_SYSTEM_DIALOGS` with extra `reason="homekey"`,
     to registered receivers only (`FLAG_RECEIVER_REGISTERED_ONLY`,
     `closeSystemDialogsLocked` in
     [pie ActivityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/am/ActivityManagerService.java));
  3. on Android 11+, refuses to go home while `USER_SETUP_COMPLETE` or, on TV,
     `TV_USER_SETUP_COMPLETE` is 0 ("Not going home because user setup is in
     progress");
  4. starts the HOME intent with extra `android.intent.extra.FROM_HOME_KEY`
     set to `true`.

Consuming Home in an accessibility service therefore also drops the long
press (stock app list on Android TV, quick settings on Fire TV), the
double-tap (picture in picture menu on Android 14 TV), CEC One Touch Play and
"Home ends the screensaver".

### A3. The app-switch delay after Home (why redirects lag on Fire OS 7)

- `stopAppSwitches()` opens a 5 second window (`APP_SWITCH_DELAY_TIME`). On
  Android 9 and 11, during that window an activity start whose real caller
  uid differs from the uid of the resumed activity, and which holds no
  `STOP_APP_SWITCHES` permission, is not run. It is queued as a
  `PendingActivityLaunch` and the call returns `START_SWITCHES_CANCELED`.
  The queue runs when the window ends, or at the next start that *is*
  allowed. The second allowed start after `stopAppSwitches` reopens switching
  for everyone (`mDidAppSwitch`). Sources: `checkAppSwitchAllowedLocked` in
  [pie ActivityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/am/ActivityManagerService.java)
  and [android11 ActivityTaskManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java),
  and the call sites in
  [pie ActivityStarter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/am/ActivityStarter.java)
  and [android11 ActivityStarter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/ActivityStarter.java).
  The log line is "Activity start request from <uid> stopped".
- An accessibility service reacting to the stock home window is exactly that
  case: the stock launcher is resumed and Rozan's uid is not. Projects that
  redirect on Fire OS 7 all report a hold of up to 5 seconds (section 2.6).
- A `PendingIntent` fired by the system (an alarm) passes the check, because
  the code also tests the IPC caller and the system uid holds
  `STOP_APP_SWITCHES`. A `PendingIntent` that Rozan sends itself does not.
- Android 12 removed the queue (`mAppSwitchesAllowed` in
  [android12 ActivityTaskManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)
  and no `PendingActivityLaunch` in its `ActivityStarter`). On Android 14,
  `BackgroundLaunchProcessController.areBackgroundActivityStartsAllowed`
  allows a process that holds a background-start token before it looks at the
  app-switch state
  ([android14](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/BackgroundLaunchProcessController.java)),
  and the system binds accessibility services with
  `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`
  ([android11 AccessibilityServiceConnection.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java)).
- Fire OS 8 is Android 11 and has the same check in AOSP, yet the projects
  that test on it report a flash of about 250 ms and no 5 second hold. Why is
  UNVERIFIED; the likeliest explanation is that Amazon's own Home handler no
  longer calls `stopAppSwitches()`.

### A4. Which activity "home" is, and who can change it

- Resolution. `chooseBestActivity` returns the first HOME candidate *without
  looking at the preferred activity* when the top two candidates differ in
  intent-filter priority
  ([pie PackageManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/pm/PackageManagerService.java),
  [android11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/pm/PackageManagerService.java)).
  Non-privileged apps have every activity filter priority capped at 0
  (`adjustPriority` in
  [android11 ComponentResolver.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/pm/ComponentResolver.java)).
  So if the stock launcher's HOME filter has priority above 0, no preferred
  activity and no HOME role holder can win against it; only removing it from
  the candidate list (disabling it) changes what Home opens. Check the
  priorities on a device with
  `adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME`
  (each entry prints `priority=`).
- Android 7 to 9: `cmd package set-home-activity <component>` exists since
  Android 7.0
  ([nougat PackageManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/nougat-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)).
  It calls `PackageManagerService.setHomeActivity`, which requires the
  component to be in the HOME candidate list and then replaces the preferred
  activity for the HOME filter.
- Android 10+: the same command calls
  `RoleManager.addRoleHolderAsUser(ROLE_HOME, …)` and also accepts a bare
  package name
  ([android10](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java),
  [android11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)),
  so it is equivalent to
  `cmd role add-role-holder android.app.role.HOME <package>`. `cmd role` on
  Android 10 to 12 has only `add-role-holder`, `remove-role-holder` and
  `clear-role-holders`
  ([android10 RoleManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/role/RoleManagerShellCommand.java));
  `get-role-holders` exists on Android 14
  ([android14 RoleShellCommand.java](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android14-release/service/java/com/android/role/RoleShellCommand.java)).
  To see what Home will actually open, use
  `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`
  (Android 7+).
- The HOME role can be requested by an app (it has request strings and is not
  marked non-requestable), its row in Settings only shows when
  `config_showDefaultHome` is true, and its fallback holder is the
  highest-priority HOME app that is not Settings, or none on a tie
  ([roles.xml](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android12-release/PermissionController/res/xml/roles.xml),
  [HomeRoleBehavior.java](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android12-release/PermissionController/src/com/android/permissioncontroller/role/model/HomeRoleBehavior.java)).
  Whether a TV build shows the role request dialog is UNVERIFIED.
  Observed on the API 36 Android TV emulator (2026-11-26):
  `cmd role add-role-holder android.app.role.HOME ai.aneesiqbal.rozan.launcher`
  succeeds and `get-role-holders` then names Rozan, but `resolve-activity`
  for `MAIN`/`HOME` still returns `com.google.android.tvlauncher/.MainActivity`
  (HOME priority 2; Rozan 0) and the Home key still starts it. The role alone
  does not change what Home opens while the stock launcher is enabled.
- AOSP TV Settings has no home picker: `android.settings.HOME_SETTINGS` and
  `MANAGE_DEFAULT_APPS_SETTINGS` go to `EmptyStubActivity` on 9, 11 and 14
  ([android11 TvSettings manifest](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android11-release/Settings/AndroidManifest.xml)).
- `pm disable-user`: the shell may switch a *whole package* between enabled
  and `DISABLED_USER`; for a single component, or a package in another state,
  it gets "Shell cannot change component state for <pkg>/<cls> to <n>", and a
  protected package gets "Cannot disable a protected package: <pkg>"
  (`setEnabledSetting` in android11 PackageManagerService.java). Vendors add
  their own blocks (section 2.4).
- If a non-system app that is the current home process crashes, Android 11
  clears that package's preferred activities so the user is not trapped
  (`clearPackagePreferredForHomeActivities`, called from
  [android11 AppErrors.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/am/AppErrors.java));
  on Android 10+ the HOME role follows the preferred activity
  (`updateDefaultHomeNotLocked` in PackageManagerService). In android11 the
  call sits at the end of `handleAppCrashLocked`, so it runs on an ordinary
  crash; the "crashed again within `MIN_CRASH_INTERVAL`" branch returns
  before it unless background ANR dialogs are on. In practice one crash of the
  process that hosts Rozan's home activity, while Rozan is the default home,
  hands Home back to the stock launcher. Anything crash-prone that is not the
  home screen (the AirPlay receiver, for example) should run in its own
  process (`android:process`), or its crashes will reset the default home.

### A5. Activity type of Rozan's task

`ActivityRecord.setActivityType` gives a record `ACTIVITY_TYPE_HOME` when the
intent is a home intent (`ACTION_MAIN`, exactly one category which is `HOME`,
no data, no type) and either the component was not specified or the caller
may launch home (system, root, recents, resolver)
([android11 ActivityRecord.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/ActivityRecord.java)).

- Android 9 and 10 set `componentSpecified` from the intent, so an explicit
  `MAIN`+`HOME` intent sent by Rozan gives a normal (`standard`) task
  ([pie ActivityStarter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/am/ActivityStarter.java),
  [android10 ActivityStarter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/ActivityStarter.java)).
- Android 11 and 14 leave `Request.componentSpecified` at `false` for an
  ordinary `startActivity` (it is only set on the heavy-weight-switch and
  `startActivities` paths), so the same explicit intent gives a `home` task
  ([android11 ActivityStarter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/ActivityStarter.java),
  [android14 ActivityStarter.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/wm/ActivityStarter.java)).
  Projectivy's maintainer saw exactly this on a Google TV Streamer: launched
  with `-a MAIN -c HOME -n …` its task is `type=home` and Back no longer falls
  through to the stock launcher
  ([miproja1#605](https://github.com/spocky/miproja1/issues/605)).

### A6. What an accessibility service can see

- `Activity.dispatchPopulateAccessibilityEvent` and
  `Dialog.dispatchPopulateAccessibilityEvent` set the event's class name to
  `getClass().getName()` and its package to the app's package
  ([pie Activity.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/core/java/android/app/Activity.java),
  [pie Dialog.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/core/java/android/app/Dialog.java)).
  For an activity-alias the event carries the real class, not the alias name.
- `ViewRootImpl` sends `TYPE_WINDOW_STATE_CHANGED` from a window's root when
  the window becomes visible (first layout or visibility change) or *regains
  focus* after losing it (toasts excepted), and again for the focused window
  when accessibility becomes enabled for the process
  ([pie ViewRootImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/core/java/android/view/ViewRootImpl.java)).
  So the stock home sends a fresh event every time something on top of it
  closes, not only when someone goes home. Since API 28 views with a pane
  title also send `TYPE_WINDOW_STATE_CHANGED`, with the view's class and a
  `CONTENT_CHANGE_TYPE_PANE_*` bit
  ([AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)).
- The event carries no intent and no caller. `AccessibilityWindowInfo.getTitle()`
  is the window's `accessibilityTitle`, which `PhoneWindow.setTitle` fills
  with the activity's title (a label), not its class
  ([android11 WindowState.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/WindowState.java),
  [PhoneWindow.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/core/java/com/android/internal/policy/PhoneWindow.java)).
- Clients count accessibility as enabled as soon as a service is *binding*
  (`isHandlingAccessibilityEventsLocked` in
  [android11 AccessibilityUserState.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/accessibility/java/com/android/server/accessibility/AccessibilityUserState.java)),
  so the replayed event in the previous point can arrive before
  `onServiceConnected` and be lost. A service must not rely on it.
- `notificationTimeout` holds back events of a type until that long after the
  last one: it is a debounce timer. Use `0`.

### A7. Accessibility service lifecycle facts that matter for recovery

- Force-stopping the app removes its service from
  `enabled_accessibility_services` (`onHandleForceStop` in
  [pie AccessibilityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)
  and [android11](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)).
  The same was observed on Fire OS 8.1.8.5 (FireStick Fixer's `DECISIONS.md`)
  and on the Android TV emulator at API 36, which also lists the service
  under "Services" in Settings > Device Preferences > Accessibility.
  "Force stop" in the system's app settings is therefore a complete off switch
  for the redirect, reachable without adb.
- A service that crashes stays enabled; it is marked crashed and rebound when
  its process comes back or its package is updated
  (`mCrashedServices` in AccessibilityUserState and
  `onPackageUpdateFinished` in android11 AccessibilityManagerService).
- Uninstalling the package removes it from the enabled list
  (`onPackageRemoved`, same file).

### A8. Settings intents in AOSP TV Settings

`com.android.tv.settings` handles these actions on 9, 11 and 14
([pie](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/pie-release/Settings/AndroidManifest.xml),
[android11](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android11-release/Settings/AndroidManifest.xml),
[android14](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/android14-release/Settings/AndroidManifest.xml)):

| Intent action | Activity in `com.android.tv.settings` |
| --- | --- |
| `android.settings.SETTINGS` | `.MainSettings` |
| `android.settings.WIFI_SETTINGS` | `.connectivity.NetworkActivity` |
| `android.settings.APPLICATION_SETTINGS`, `MANAGE_APPLICATIONS_SETTINGS` | `.device.apps.AppsActivity` |
| `android.settings.DEVICE_INFO_SETTINGS` | `.about.AboutActivity` |
| `android.settings.SOUND_SETTINGS` | `.device.sound.SoundActivity` (9), `.device.displaysound.SoundActivity` (11), `.device.displaysound.DisplaySoundActivity` (14) |
| `android.settings.ACCESSIBILITY_SETTINGS` | `.oemlink.AccessibilitySettingsActivity` (14 only; not declared in 9 or 11) |
| `com.google.android.intent.action.CONNECT_INPUT` (pair a remote or gamepad) | `.accessories.AddAccessoryActivity` |
| `android.settings.HOME_SETTINGS`, `MANAGE_DEFAULT_APPS_SETTINGS` | `.EmptyStubActivity` (does nothing) |

There is no `android.settings.DISPLAY_SETTINGS` handler in AOSP TV Settings.
Each vendor and Google TV changes this list, so Rozan must resolve every
settings intent before sending it and fall back to `android.settings.SETTINGS`
(except on Fire OS, section 2.2).

### A9. How a real launcher handles Home intents

Launcher3 (`singleTask`, `clearTaskOnLaunch`, `stateNotNeeded`,
`resumeWhilePausing`, `taskAffinity=""`) treats every `ACTION_MAIN` in
`onNewIntent` as "go home": it closes open menus and returns to the normal
state, and if it was already in front
(`hasWindowFocus() && (flags & FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0`) it also
moves to the default screen
([Launcher.java](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/android11-release/src/com/android/launcher3/Launcher.java),
[AndroidManifest.xml](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/android11-release/AndroidManifest.xml)).

## 6. Recommended design for Rozan

### 6.1 Two modes, chosen from what the system reports

Rozan should not guess the platform from model strings. It asks the package
manager what Home opens:

```kotlin
val home = packageManager.resolveActivity(
    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
    PackageManager.MATCH_DEFAULT_ONLY,
)?.activityInfo
```

(Rozan needs a `<queries>` entry for `MAIN`/`HOME` once it targets API 30+.)

- **Native mode**: `home` is Rozan's `HomeActivity`. The system delivers
  every Home press to Rozan itself; the accessibility service, if enabled,
  does nothing. Because every stock TV launcher found in this research has a
  HOME priority above 0 (A4, sections 2.4, 3.4, 4), native mode only happens
  where the owner has disabled the stock launcher over adb: in practice,
  classic Android TV such as the Shield (section 4). Rozan never does that
  itself.
- **Redirect mode**: `home` is another app and the user enabled Rozan's
  accessibility service. This is the default on every Fire TV, every Google
  TV and every Android TV with its stock launcher in place.
- Neither: Rozan is an ordinary app that the user opens from the stock
  launcher's app row. This is the only mode on Fire OS 6.7 (2.5).
- Re-evaluate on `onServiceConnected`, on `ACTION_PACKAGE_CHANGED`,
  `ACTION_PACKAGE_REPLACED` or `ACTION_PACKAGE_ADDED` for the stock
  launcher's package and for Rozan, and on `ACTION_USER_UNLOCKED`; those are
  the events that can change the answer.

### 6.2 The single redirect rule

Redirect only when **the stock launcher's home activity itself is the active
window**, identified by exact package *and* class:

- The set `stockHome` is built from (a) `home.packageName` plus
  `home.targetActivity ?: home.name` from the query above (the system's own
  answer, alias resolved to the real class), plus (b) every activity of that
  same package that declares `MAIN`/`HOME`, read with
  `queryIntentActivities(Intent(MAIN).addCategory(HOME).setPackage(pkg), MATCH_DISABLED_COMPONENTS)`
  (cleanyourtv's `HomeKeyService` does this), checked against a short
  curated list so an unexpected class shows up in Rozan's diagnostics:
  - Fire OS 6, 7, 8, 14 and Fire TV smart TVs:
    `com.amazon.tv.launcher` / `com.amazon.tv.launcher.ui.HomeActivity_vNext`.
  - Google TV: `com.google.android.apps.tv.launcherx` /
    `com.google.android.apps.tv.launcherx.home.HomeActivity` (seen by
    `uiautomator events` on a Google TV 14 emulator,
    [couch#16](https://github.com/samuelloranger/couch/pull/16), and in
    cleanyourtv's field notes; Kids home classes UNVERIFIED).
  - Android TV: `com.google.android.tvlauncher` /
    `com.google.android.tvlauncher.MainActivity`; older
    `com.google.android.leanbacklauncher` / `.MainActivity`.
- Every other class in the stock launcher's package is "not home", always:
  on Fire OS `MainSettingsActivity`, `SettingsActivity`, `NavigationActivity`,
  the Recents and Alexa results activities; on Google TV the profile chooser,
  dashboard, Gemini and search activities; on Android TV the apps view that
  long-press Home opens, the inputs panel and the notifications panel.
- FallbackHome, setupwraith, `com.amazon.firehomestarter` and every setup
  (OOBE) package are never in `stockHome`, even though they answer HOME.
- Amazon Kids home (`com.amazon.tahoe` / `com.amazon.tahoe.ftv.HomeActivity`)
  is not in the set, and the service does nothing in a user other than the
  one it was enabled for. Kids profiles are separate Android users with their
  own restrictions; Rozan must not offer a way out of them.

### 6.3 Accessibility service configuration

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canRequestFilterKeyEvents="false"
    android:notificationTimeout="0"
    android:description="@string/redirect_service_description"
    android:settingsActivity="ai.aneesiqbal.rozan.launcher.RedirectSettingsActivity" />
```

- `typeWindowStateChanged` is the only event type needed.
- `flagRetrieveInteractiveWindows` plus `canRetrieveWindowContent` let the
  service read `getWindows()` to confirm which window is active when an event
  arrives. It reads only the active window's package name.
- No `packageNames` filter: the service must see other apps come to the front
  to end the states in 6.4.
- `notificationTimeout="0"`: anything else is a debounce timer (A6).
- No key filter by default (6.6). The opt-in key observation in 6.6 needs
  `canRequestFilterKeyEvents="true"` in a second service declaration or a
  build flavour, because the attribute is read from XML; a runtime
  `setServiceInfo()` cannot add the capability and has been seen to drop the
  flag (3.3).
- Declare the service `android:directBootAware="true"` so it can bind before
  the user is unlocked; on one TCL TV a service that was not got "Ignoring
  non-encryption-aware service" after reboot (3.5). Do the real work only
  after `ACTION_USER_UNLOCKED`.
- Run the service in its own process (`android:process=":redirect"`). A crash
  in the UI process then cannot take the service down with it, and the
  service keeps the state it needs to act on that crash (6.4, `C`).

### 6.4 State machine (event driven, no timers)

Inputs, all of them events:

- `W(pkg, cls)`: a `TYPE_WINDOW_STATE_CHANGED` event.
- `H`: evidence that Home was pressed. Sources, where the platform provides
  them: an `ACTION_CLOSE_SYSTEM_DIALOGS` broadcast with `reason=homekey`,
  received by a receiver the service registers at runtime (AOSP sends it on
  every Home press, A2; on Fire OS 7 and 8 delivery to a third-party app is
  UNVERIFIED, and one Fire OS 6.7 TV required an Amazon permission, 2.3); a
  "go home" line in precise mode (below); or, where the opt-in key
  observation of 6.6 is on and the platform delivers it, `KEYCODE_HOME`
  itself (Google TV and Android TV only; never on Fire OS).
- `K(code)`: an observed, never consumed, key (opt-in, 6.6). The ones the
  rules use: `KEYCODE_BACK`, `KEYCODE_TV_INPUT` (178),
  `KEYCODE_PROFILE_SWITCH` (288).
- `S`: Rozan's own "Open the stock home screen" command (a bound call or a
  broadcast from Rozan's UI process to the service).
- `C`: a crash report. Rozan's UI process installs a default
  uncaught-exception handler that makes one binder call to the service
  (which runs in its own process, 6.3) before the process dies.
- `O`: Rozan's Inputs button is about to open the TV's input picker
  (docs/research/tv-inputs.md, 12). One binder call from Rozan's UI process
  before the start, and a second one that takes it back if nothing started.
  Fire OS 8 draws the inputs view that `NAVIGATE_TO_INPUTS` opens inside
  `HomeActivity_vNext` (2.1), so without `O` step 8 would replace it with
  Rozan.

State, in memory: `awaitingRozan` (a redirect was sent and neither Rozan's
home window nor any other activity has appeared since), `tripped` (circuit
breaker), `userStay` (the
user chose the stock home), `handoverHold` (the stock home is in front as a
step towards something else), `lastActivity` and `keysSinceLast` (the keys
observed since `lastActivity` came to the front).

For each `W(pkg, cls)`:

1. Ignore it unless `contentChangeTypes == 0` (a window root, not a pane) and
   `(pkg, cls)` is an activity. "Is an activity" is checked once per pair with
   `packageManager.getActivityInfo(...)` and cached; dialogs, popups,
   `com.android.systemui`, `android`, the IME and Rozan's own windows other
   than its home activity fail the check or are excluded by package.
2. Rozan's `HomeActivity`: `awaitingRozan = false`, `tripped = false`,
   `lastActivity = it`, clear `keysSinceLast`. Stop.
3. Not in `stockHome`: another activity came to the front. The redirect
   attempt, if any, is over: `awaitingRozan = false`, `handoverHold = false`,
   `lastActivity = it`, clear `keysSinceLast`. If `pkg` is not the stock
   launcher's package, also `userStay = false`. Stop.
4. In `stockHome`: confirm with `getWindows()` that the active window's root
   package is the stock launcher's. If not, the event is stale (Home on Fire
   issue #11 saw a second home event about 1 s late). Stop.
5. If `awaitingRozan`, stop: a redirect is already on its way. Fire OS sends
   several home window events per press, for up to about 1 s (FireStick Fixer
   decision D6), and they must not cause a second start, nor be mistaken for a
   failed one. Treating such an event as a failed redirect would trip the
   breaker on ordinary Home presses.
6. If `tripped` or `userStay`, stop. `tripped` clears only in step 2, when the
   user opens Rozan from the stock launcher's app row.
7. Hand-over test. Unless an `H` arrived since `lastActivity` came to the
   front, set `handoverHold = true` and stop when any of these holds:
   - `lastActivity` is a known trampoline into the stock home:
     `com.amazon.venezia` classes whose name contains `AppsGrid` (the Apps
     button on Fire OS 8 starts `HomeActivity_vNext` by itself),
     `com.amazon.tv.settings.v2/.hud.HudActivity` and any
     `com.amazon.tv.quicksettings.ui` window (the long-press panel's tiles),
     `com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler`,
     `com.amazon.tv.inputpreference.service/.StartAmazonInputActivity` and
     `com.amazon.android.amazoninputswitch` (input switching),
     `com.amazon.tv.launcher/.navigation.NavigationActivity` (drawer to
     Inputs) and `com.amazon.tv.launcher/.util.QuickSettingsProxyActivity`
     (Fire OS 14);
   - `keysSinceLast` contains `KEYCODE_TV_INPUT` (the Input key opens the
     stock home's inputs view) or `KEYCODE_PROFILE_SWITCH` (on Google TV the
     profile button shows the home and then the profile chooser);
   - the last key in `keysSinceLast` is `KEYCODE_BACK` and `lastActivity` is
     in `com.amazon.tv.settings.v2` or is a non-home class of the stock
     launcher (Back from a Settings page into the Settings tab that Fire OS 7
     draws inside `HomeActivity_vNext`);
   - an `O` arrived, and was not taken back, since `lastActivity` came to the
     front (the stock home is the input picker that Rozan opened). A picker
     in its own activity, such as the Fire TV Cube's chooser, comes to the
     front first and so ends it.
8. Otherwise redirect (6.5) and set `awaitingRozan = true`.

For each `H`: clear `handoverHold`. Then, if the active window (from
`getWindows()`) is the stock launcher and none of `tripped`, `userStay`,
`awaitingRozan` is set, redirect. This covers a Home press while the stock
home is already in front, which produces no new window event (A6), and an `H`
that arrives after its window event.

For each `K(code)`: append to `keysSinceLast`.

For each `C`: `tripped = true`, `awaitingRozan = false`. Whether Rozan
crashes while starting or a few seconds after showing, the user then stays on
the stock home, where Settings works, until they open Rozan themselves
(step 2). A failure that sends no `C` (a crash before the handler is
installed, a refused start) cannot loop either: each one costs a single
attempt, and the next attempt needs the user to leave the stock home and
press Home again. Install the handler first thing in
`Application.onCreate`.

For `S`: set `userStay = true`, then start the stock launcher. `userStay`
ends when any other activity comes to the front (step 3), as in dumbtv's
`Guard.amazon()`. A Home press on the stock home while `userStay` is set
keeps the user there.

Settings screens are not trampolines: Home pressed in Settings must reach
step 8. Without any `H` source, step 7 errs on the side of not redirecting:
a Home press made inside the long-press panel, the Apps grid or the input
picker leaves the user on the stock home, and pressing Home again there
produces no event. That is recoverable (open any app, then press Home) and
never loops. Without key observation, the Input key and Back into the Fire OS
7 Settings tab are redirected to Rozan: not a loop either, but the user lands
in Rozan instead of the stock screen they asked for.

Why this cannot loop through Settings: a redirect happens only in step 8 or
on an `H`, and both require the active window to be one of the `stockHome`
classes. No Settings screen, panel, picker or dialog is ever in that set, so
opening any of them can never be followed by a redirect while it is in front.
The only path from Settings to a redirect is the user pressing Home, which is
the behaviour asked for. A Rozan crash (`C`) stops all redirects until the
user opens Rozan again, and step 5 allows only one redirect per arrival of
the stock home, so a broken Rozan cannot bounce the user either.

Optional content check, Fire OS 8 only (UNVERIFIED beyond Home on Fire's
code): with `flagReportViewIds`, the selected navigation tab's view id inside
`HomeActivity_vNext` (`nav-tab-apps*`, `nav-tab-movies_and_tv*`, …) tells the
Apps and Inputs views apart from the home tab. That is an identity check on
what is shown and could replace the trampoline list for those two views, at
the price of depending on Amazon's view ids.

Optional precise mode, Fire OS only: with `READ_LOGS` granted over adb, the
service can stream `logcat -b system` and read the `ActivityTaskManager`
(Android 11) or `ActivityManager` (Android 9) line
`START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME] … cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext …} from uid N`
(the `Slog.i(TAG, "START u" …)` statement in `ActivityStarter`, linked in
A3). A line from uid 0 or 1000 without `(has extras)` is an `H`; a line from
any other uid, or with extras, sets `handoverHold` for the window that
follows. This identifies the *intent*, which window events cannot, and a
blocking read of the log stream is an event source, not a poll. The format is
not an API, so trust it only after such a line has been seen on the current
`Build.DISPLAY` (FireStick Fixer decision D7) and fall back otherwise.
FireStick Fixer saw uid 0 for Home on Fire OS 8.1.8.5; the uid on Fire OS 7
is UNVERIFIED. Android 13 added a per-request user approval for log access
([android13 LogcatManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/logcat/LogcatManagerService.java);
not present on android12-release), so this mode is for Fire OS 7 and 8 only.

### 6.5 Starting Rozan

```kotlin
val intent = Intent(Intent.ACTION_MAIN)
    .setComponent(ComponentName(this, HomeActivity::class.java))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
        Intent.FLAG_ACTIVITY_NO_ANIMATION)
    .putExtra(EXTRA_FROM_REDIRECT, true)
if (homeTypeTask) intent.addCategory(Intent.CATEGORY_HOME) // the only category, see A5
startActivity(intent)
```

- `homeTypeTask` (A5): with `CATEGORY_HOME` as the only category, Android 11+
  makes Rozan's task a `home` task, so Back from an app lands on Rozan and
  not on the stock home, and Rozan's process gets the home process's
  treatment. On Android 9 and 10 it changes nothing.
  - Fire OS: on. Home on Fire and dumbtv both start their target this way on
    Fire OS 8 with no reported side effect; the remote's app buttons are
    handled by Amazon's key policy, not by the launcher.
  - Google TV: off until tested. It stops the launcherx flash on Back
    (Projectivy 4.73), but cleanyourtv found the YouTube and Netflix buttons
    blocked once launcherx is no longer the home process (3.5). A flash of
    about 100 to 150 ms is the smaller harm.
- Start it from the service's own context; the system binds accessibility
  services with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` (A3).
- Start it without the open animation (`FLAG_ACTIVITY_NO_ANIMATION`).
  Observed on the API 36 Android TV emulator (2026-11-26), Home pressed from
  the TV Settings panel over Rozan: with the default animation the system
  cross-faded from the stock home to Rozan over about 300 ms, and the stock
  home was on screen for about 400 ms in all; without it, Rozan replaced the
  stock home in one frame and the stock home showed for about 35 ms (about
  50 ms from a full-screen app). When the stock home was already behind the
  Settings panel, the start arrived during the system's own close animation
  and the cross-fade (about 300 ms) still played.
- The flash cannot be removed in redirect mode: the redirect learns of the
  stock home from its window event, which comes once the stock home has been
  drawn. Starting Rozan earlier, on `H`, would race the system's own start
  of the stock home, which the design rules out (no timing dependence). Only
  native mode removes it.
- No `performGlobalAction(GLOBAL_ACTION_HOME)` anywhere: it would go through
  the stock home and back into step 8.
- No self-sent `PendingIntent`: it gains nothing over `startActivity` (A3).
- Fire OS 7 (Android 9) holds this start for up to 5 seconds after a Home
  press (A3, 2.6). Timer-free ways to live with it, in order of preference:
  1. Accept it.
  2. Cover the screen: show a `TYPE_ACCESSIBILITY_OVERLAY` window drawn like
     Rozan's home when step 8 fires, and remove it on the event in step 2, on
     any step 3 event, or on `C`. It hides the stock home but the
     user still waits for the hold to end (Home on Fire does this). Make the
     overlay not focusable and not touchable, so keys keep reaching the
     window underneath.
  3. An alarm trampoline (`AlarmManager.setExact` at "now" with a
     `PendingIntent.getActivity`) passes the check because the system sends
     it, but Android 9 clamps every alarm to at least 5 seconds ahead
     (`MIN_FUTURITY`,
     [pie AlarmManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/AlarmManagerService.java))
     unless the owner runs
     `settings put global alarm_manager_constants min_futurity=0`, which
     changes alarm behaviour for every app. Owner decision; not recommended
     by default.

### 6.6 Keys

- Default: no key filtering on any platform. Reasons: on Fire OS Home never
  reaches the filter (2.3); on Google TV and Android TV consuming Home drops
  the long press, the double tap, CEC One Touch Play and "Home ends the
  screensaver" (A2); and on many Google TV and Android TV firmwares any
  key-filtering service is reported to stop held-key auto-repeat and add
  D-pad lag (3.3).
- The filter cannot tell a long press from a short one at `ACTION_DOWN`
  (A1). Rebuilding long press by comparing `ACTION_UP`'s `eventTime` with
  `downTime` against `ViewConfiguration.getLongPressTimeout()` is possible
  but is a timing rule, moves the panel from "while held" to "on release",
  and needs a way to open the panel that third-party apps mostly lack
  (Amazon's broadcasts are signature-protected, 2.3). Not recommended.
- Opt-in "key observation" (`onKeyEvent` always returns `false`): it feeds
  the `K` and `H` inputs of 6.4 with `KEYCODE_BACK`, `KEYCODE_TV_INPUT`,
  `KEYCODE_PROFILE_SWITCH` and, outside Fire OS, `KEYCODE_HOME`. It makes the
  hand-over rules exact on Fire TV smart TVs (Input key) and Fire OS 7
  (Back into the Settings tab), and lets Google TV and Android TV redirect
  when Home is pressed on the stock home. Costs: every key press passes
  through Rozan's service, the auto-repeat and lag reports above (UNVERIFIED
  on Fire OS), and the 500 ms timeout if the service ever stalls (A1). Keep
  `onKeyEvent` to a few field writes.
- Some Xiaomi remotes send `KEYCODE_ALL_APPS` (284) for Home (section 4).
  Treat that as `H` only as a per-device opt-in.

### 6.7 Boot and service reconnects

- On Fire OS 8.1.8.5 the accessibility service connected about 0.2 s before
  Amazon home started, and `BOOT_COMPLETED` arrived about a minute later
  (FireStick Fixer decision D13). In that order the normal rules handle boot:
  a profile picker is not in `stockHome` and is left alone, and the home
  window that follows the user's choice is redirected (step 8).
- On Google TV the service binds after launcherx is on screen (3.5), and the
  same can happen anywhere after a crash restart, an app update or
  re-enabling. No window event will come for a window that is already
  showing (A6). In `onServiceConnected`, after `ACTION_USER_UNLOCKED`:
  1. Read `getWindows()`. If the active window's package is not the stock
     launcher's, do nothing; the normal rules take over from the next event.
  2. If it is, learn its class without guessing, using one of:
     - a probe: start a transparent, no-animation Rozan activity that calls
       `finish()` in its first `onWindowFocusChanged(true)`; the window
       underneath regains focus and sends `TYPE_WINDOW_STATE_CHANGED` with
       its class (A6), which steps 1 to 8 handle as usual (cleanyourtv's
       `ProbeActivity`); or
     - usage stats: `UsageStatsManager.queryEvents` from boot time to now,
       last `ACTIVITY_RESUMED` (`MOVE_TO_FOREGROUND` before API 29) event,
       which carries the class name; needs `appops set … GET_USAGE_STATS
       allow` over adb; whether Fire OS records these events is UNVERIFIED.
- Do not launch Rozan from `BOOT_COMPLETED`, `ACTION_SCREEN_ON` or
  `onServiceConnected` by themselves. Each of those can fire while a profile
  picker, a PIN prompt, setup or a playing app is in front (FTVLaunchX
  launched on every screen-on; LauncherHijack on every connect).
- Fire OS 16 stops delivering `BOOT_COMPLETED` to apps in the stopped state
  (2.0); another reason not to depend on it.

### 6.8 `HomeActivity`

- Manifest: `launchMode="singleTask"`, `stateNotNeeded="true"`,
  `clearTaskOnLaunch="true"`, `resumeWhilePausing="true"`,
  `excludeFromRecents="true"`, keep the existing `MAIN` + `HOME` + `DEFAULT`
  + `LEANBACK_LAUNCHER` filter (the `LEANBACK_LAUNCHER` tile is how a user
  re-opens Rozan from the stock launcher after the breaker trips).
- `onNewIntent` with `ACTION_MAIN` (a Home press in native mode carries
  `android.intent.extra.FROM_HOME_KEY`; a redirect carries
  `EXTRA_FROM_REDIRECT`): close menus and dialogs and return to the first
  row; if the activity already had focus and the intent lacks
  `FLAG_ACTIVITY_BROUGHT_TO_FRONT`, also move focus to the default item
  (Launcher3's rule, A9).
- Never `finish()` and never start another activity in response to a Home
  intent.

### 6.9 Opening system UI from Rozan

- Resolve every intent before sending it, and fall back to the next entry.
- Fire OS: never send `android.settings.SETTINGS`. It resolves to
  `com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler`, which
  starts `HomeActivity_vNext` with extras, which opens
  `com.amazon.tv.launcher/.ui.MainSettingsActivity` later (Home on Fire issue
  #12, Projectivy issue #338). Start `MainSettingsActivity` directly (2.2).
- Fire OS: open the Appstore at `com.amazon.venezia/.ade.ADEHomeActivity`,
  not its launcher entry, which opens the Amazon home on Fire OS 8 (dumbtv
  `Home.java`).
- Android TV and Google TV: the AOSP actions in A8, then vendor components
  (sections 3 and 4).

### 6.10 Things Rozan must never do

- Match a whole package, or any class not in `stockHome`.
- Redirect on dialogs, panes, popups or the IME.
- Consume keys it does not need, or consume Home on any device by default.
- Call `performGlobalAction(GLOBAL_ACTION_HOME)`.
- Use `notificationTimeout` above 0, `postDelayed`, polling, or a debounce
  window.
- Disable, hide or uninstall the stock launcher in any automated setup
  step.
- Overwrite `enabled_accessibility_services`; always append (section 7).
- Redirect inside an Amazon Kids profile, during setup, or from the long-press
  Home menu.
- Run the redirect service with key filtering on by default, or consume keys
  in the opt-in observation mode.

## 7. adb setup per device family

Placeholders: the service class does not exist yet, so `SVC` below uses a
made-up name. Every command is run from a computer with adb; none of them
needs root.

```sh
PKG=ai.aneesiqbal.rozan.launcher
SVC=$PKG/$PKG.redirect.HomeRedirectService   # placeholder name
adb connect <device-ip>:5555
adb install -r rozan-launcher.apk
```

Enabling the service without removing anyone else's (all families):

```sh
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in
  null|"") new=$SVC ;;
  *"$SVC"*) new=$cur ;;
  *) new="$cur:$SVC" ;;
esac
adb shell settings put secure enabled_accessibility_services "$new"
adb shell settings put secure accessibility_enabled 1
adb shell settings get secure enabled_accessibility_services   # the write must have stuck
adb shell dumpsys accessibility | grep -i -A3 rozan              # listed AND bound
```

If the value did not stick (Fire OS 6.2), write it through the provider:
`adb shell content insert --uri content://settings/secure --bind name:s:enabled_accessibility_services --bind "value:s:$new"`.
If it is listed but not bound, set `accessibility_enabled` to 0 and then to 1
again (one Insignia TV needed that, dumbtv `install.sh`). Profiles are
separate users: add `--user <id>` (`adb shell pm list users`) to set it for
another profile; never do this for an Amazon Kids user.

What Home will open, and the HOME candidates with their priorities:

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME
```

Watching what the service will see while testing (prints accessibility
events with class names) and the activity starts:

```sh
adb shell uiautomator events
adb logcat -b system | grep 'START u0'
```

### 7.1 Fire TV sticks, cubes and smart TVs (Fire OS 7, 8, 14)

- Enable Developer options (Settings > My Fire TV > About, press the device
  name seven times), then ADB debugging, then the commands above.
- On Fire OS 8.1.5.5 and later this is the only way to enable the service;
  on older builds it may also be listed under Settings > Accessibility
  (UNVERIFIED per build).
- Turn parental controls off while enabling if the service does not bind.
- Optional, precise mode (6.4):
  `adb shell pm grant $PKG android.permission.READ_LOGS`.
- Optional, connect-time check through usage stats (6.7):
  `adb shell appops set $PKG GET_USAGE_STATS allow`.
- Do not run `cmd package set-home-activity` or `cmd role add-role-holder`:
  they print Success and do nothing on Fire OS (2.4).
- Never run `pm disable-user` on `com.amazon.tv.launcher`,
  `com.amazon.firehomestarter`, `com.amazon.tv.settings.v2` or any other
  Amazon system package. The launcher refuses today; if a future build
  allowed it, Settings would go with it.
- Fire OS 6.7 (A11yAuthorizer): the service will be listed but not bound; Rozan
  can only be opened from the app row (2.5).

### 7.2 Google TV

- Enable Developer options (Settings > System > About > Android TV OS build,
  press seven times), then USB or network debugging, then the commands above.
  Enabling over adb avoids the Android 13+ "restricted setting" lock that can
  grey out the toggle for sideloaded apps.
- TCL and some onn builds: `adb shell appops set $PKG AUTO_START allow` (some
  builds name the op `APP_AUTO_START`) and
  `adb shell dumpsys deviceidle whitelist +$PKG`, or the service is unbound
  at every boot (3.5).
- Do not disable `com.google.android.apps.tv.launcherx` or
  `com.google.android.tungsten.setupwraith` (3.4).
- Only when testing the home-type task (6.5):
  `adb shell appops set com.google.android.apps.tv.launcherx SYSTEM_ALERT_WINDOW allow`
  (cleanyourtv's workaround for the blocked app buttons; UNVERIFIED).

### 7.3 Classic Android TV

- Redirect mode: same as Google TV.
- Native mode, owner opt-in only, on devices where the stock launcher is not
  load-bearing (Shield). This removes the stock app list behind long-press
  Home and the stock inputs panel. Keep ADB debugging on afterwards; it is the
  recovery path.

  ```sh
  adb shell pm disable-user --user 0 com.google.android.tvlauncher
  adb shell cmd package set-home-activity $PKG/$PKG.HomeActivity   # Android 9 needs the component; 10+ also accepts $PKG
  adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME   # must print Rozan
  ```

## 8. Recovery

Rozan's design keeps the stock home and Settings reachable at all times:

- The stock launcher is never disabled, hidden or uninstalled by Rozan, so
  Home always has a working fallback.
- Rozan never redirects from Settings, panels, pickers or dialogs (6.4), so
  Settings stays usable even if the redirect misbehaves.
- The breaker (6.4, `C`) stops all redirects after a Rozan crash, until the
  user opens Rozan from the stock app row, and a failed start is never
  retried on its own (6.4, step 5).

On the TV, without a computer:

| Platform | Turn the redirect off | Remove Rozan |
| --- | --- | --- |
| Fire TV | Settings > Applications > Manage Installed Applications > Rozan > Force stop (this removes the service from the enabled list, A7); or Settings > Accessibility on Fire OS 7. The long-press Home panel's Settings tile also leads there | same screen, Uninstall |
| Google TV | Settings > Apps > Rozan > Force stop; or Settings > System > Accessibility > Rozan > off | Settings > Apps > Rozan > Uninstall |
| Android TV | Settings > Apps > Rozan > Force stop; or Settings > Device Preferences > Accessibility | Settings > Apps > Rozan > Uninstall |

Rozan's own settings should also offer "Stop replacing the home screen" (turns
redirects off without touching the accessibility setting) and "Open the stock
home screen" (`S` in 6.4). The menu paths above are the commonly documented
ones and may differ by build (UNVERIFIED per device).

With adb:

```sh
adb shell am force-stop $PKG                      # removes the service from enabled_accessibility_services (A7)
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services "<the same list without $SVC>"
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME   # back to the system home
adb uninstall $PKG                                # also removes the service from the list
```

Native mode on Android TV (7.3), if Rozan misbehaves:

```sh
adb shell pm enable com.google.android.tvlauncher
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

On Android 11, a crash of the process that hosts the default home, when that
home is not a system app, clears its preferred-home entry by itself (A4). With
tvlauncher disabled the next candidate is `FallbackHome`, a blank screen, so
native mode depends on ADB debugging staying on.

If someone disabled the Google TV launcher anyway: re-enable launcherx first,
then setupwraith (the other order caused factory resets in field reports),
then point Home back at launcherx:

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

(AOSP has no `clear-package-preferred-activities` shell command on 9, 11 or
14, despite one field note suggesting it.)

Last resort: factory reset. Fire TV remote: hold Back and Right for 10
seconds, or Settings > My Fire TV > Reset to Factory Defaults
([Amazon help](https://www.amazon.com/gp/help/customer/display.html?nodeId=GBVZEZKT6A6LH4UP)).

What never to do, by default or in any script Rozan ships:

- `pm disable-user`, `pm hide` or `pm uninstall --user 0` on any stock
  launcher, setup package or settings package.
- Overwrite `enabled_accessibility_services` with only Rozan's service; that
  silently turns off the user's other services (TalkBack, remote mappers).
- Change `alarm_manager_constants` or other global settings without the
  owner asking.

## 9. Open questions for real hardware

Each needs a device; none could be answered here. Suggested capture for all
of them: `adb shell uiautomator events` and `adb logcat -b system` side by
side while performing the action.

Fire OS:

1. Does `ACTION_CLOSE_SYSTEM_DIALOGS` with `reason=homekey` reach a receiver
   registered by a third-party app on Fire OS 7 and 8? This decides whether
   Fire OS has an `H` signal without `READ_LOGS` (6.4).
2. The exact event sequence, with classes, for: Home from Rozan, from an app,
   from `MainSettingsActivity` and from a settings sub-page; Back from a
   settings sub-page on Fire OS 7; the Apps button on Fire OS 8; each tile of
   the long-press panel (profiles, apps, sleep, mirroring, settings); Recents
   (does a `HomeActivity_vNext` event come before
   `RecentDeepLinkActivityDI`?); Alexa "go home", Alexa search results, Alexa
   "open settings"; the Input key and input picker on a Fire TV smart TV; the
   profile picker at boot and on wake (its class); standby.
3. Fire OS 7: does a start from Rozan's service after Home log "Activity start
   request from <uid> stopped" and wait up to 5 s? Fire OS 8: confirm there is
   no hold, and find out why (A3).
4. Fire OS 8: side effects of Rozan's task being `type=home` (Settings
   trampoline, remote app buttons, Alexa).
5. Profile picker class name; are adult profiles separate Android users?
6. Which Fire OS 7 and 8 builds run `A11yAuthorizer`?
7. Is the service rebound after an out-of-memory kill on Fire OS 8, after a
   Rozan update, after a Fire OS update?
8. Does Settings > Applications > Force stop remove the service from the
   enabled list on Fire OS 7 (AOSP says yes, A7)?
9. Does the probe activity (6.7) work over Amazon home, and does usage stats
   record Amazon launcher activities?
10. Do the Fire OS 6 settings entry points (2.2) work on 7 and 8? Does
    `MainSettingsActivity` exist on Fire OS 7 builds older than 7.7.1.3?
11. Does opt-in key observation degrade held-key repeat or D-pad latency on
    Fire OS (it does on several Google TV and Android TV firmwares)?
12. Fire OS 14 TVs: all of the above; the app-switch hold should be gone
    (Android 12+).

Google TV:

13. Does `KEYCODE_HOME` reach the filter on current Chromecast with Google TV,
    Streamer and onn builds, and does the "homekey" broadcast arrive?
14. Profile button: with a redirect on `HomeActivity`, does the profile
    chooser still appear (standard task vs home-type task)?
15. Home-type task: YouTube, Netflix and star buttons, profile chooser and
    Gemini results with Rozan as a `type=home` task.
16. Boot: does the probe identify launcherx's home at connect? Is there a
    profile picker at boot?
17. Kids profile home class, and accessibility settings per profile user.
18. A Home press during Ambient mode: AOSP only ends the screensaver (A2);
    confirm Google TV does the same.

Android TV:

19. Shield native mode: what long-press Home and the inputs panel do with
    tvlauncher disabled, and whether Settings is reachable without the
    launcher if Rozan fails.
20. Xiaomi remotes that send `KEYCODE_ALL_APPS` for Home.

General:

21. Does a TV build show the HOME role request dialog
    (`RoleManager.createRequestRoleIntent`)? Moot while stock launchers keep
    priority 2 or 950 (A4).
22. Native mode crash fallback (A4) on a real device: does one crash of Rozan
    as default home hand Home back?
23. Risk outside Rozan's control: Amazon's `com.amazon.adep` blocklist has
    disabled launcher-redirect apps by package name before (2.3).

## 10. Sources

AOSP (all on `android.googlesource.com`, branches `pie-release`,
`android10-release`, `android11-release`, `android12-release`,
`android13-release`, `android14-release`): `KeyboardInterceptor`,
`AccessibilityInputFilter`, `KeyEventDispatcher`, `AccessibilityManagerService`,
`AccessibilityServiceConnection`, `AccessibilityUserState`,
`AccessibilityServiceInfo`, `InputDispatcher.cpp`, `EventHub.cpp`,
`PhoneWindowManager`, `ActivityManagerService`, `ActivityTaskManagerService`,
`ActivityStarter`, `ActivityRecord`, `BackgroundLaunchProcessController`,
`PackageManagerService`, `PackageManagerShellCommand`, `ComponentResolver`,
`RoleManagerShellCommand`/`RoleShellCommand`, `roles.xml`,
`HomeRoleBehavior`, `AppErrors`, `WindowProcessController`,
`AlarmManagerService`, `LogcatManagerService`, `Activity`, `Dialog`,
`ViewRootImpl`, `PhoneWindow`, `WindowState`, TvSettings `AndroidManifest.xml`,
`device/google/atv` overlays, Launcher3. Links are inline in section A.

Vendor documentation: Amazon
[device specifications](https://developer.amazon.com/docs/device-specs/device-specifications-comparison-table.html),
[Fire OS overview](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html),
[Fire OS 14](https://developer.amazon.com/docs/fire-tv/fire-os-14.html),
[Fire OS 16](https://developer.amazon.com/docs/fire-tv/fire-os-16.html);
[Android AccessibilityEvent reference](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent);
[Android 12 behavior changes](https://developer.android.com/about/versions/12/behavior-changes-all);
[NVIDIA Shield remote](https://www.nvidia.com/en-eu/shield/support/shield-tv/know-your-shield-tv/).

Open-source projects read at a fixed commit (clones kept outside the repo):
[LauncherHijack](https://github.com/BaronKiko/LauncherHijack),
[FTVLaunchX](https://github.com/codefaktor/FTVLaunchX),
[Home on Fire](https://github.com/toolicious/home-on-fire),
[FireStick Fixer](https://github.com/Hornet34/firestick-fixer),
[dumbtv](https://github.com/humblemuzzu/dumbtv),
[tv-vibehack-firetv](https://github.com/pulgagr/tv-vibehack-firetv),
[LtvLauncher](https://github.com/leanbitlab-org/LtvLauncher),
[FLauncher](https://gitlab.com/flauncher/flauncher),
[Leanback on Fire](https://github.com/tsynik/LeanbackLauncher),
[cleanyourtv](https://github.com/Gathin23/cleanyourtv),
[decompiled tvlauncher manifest](https://github.com/AndroidTVDeveloper/com.google.android.tvlauncher/blob/master/src/main/AndroidManifest.xml).

Issue trackers and reports: Projectivy
([#252](https://github.com/spocky/miproja1/issues/252),
[#338](https://github.com/spocky/miproja1/issues/338),
[#380](https://github.com/spocky/miproja1/issues/380),
[#489](https://github.com/spocky/miproja1/issues/489),
[#605](https://github.com/spocky/miproja1/issues/605)), Home on Fire
([#7](https://github.com/toolicious/home-on-fire/issues/7),
[#11](https://github.com/toolicious/home-on-fire/issues/11),
[#12](https://github.com/toolicious/home-on-fire/issues/12),
[#13](https://github.com/toolicious/home-on-fire/issues/13)), LauncherHijack
([#20](https://github.com/BaronKiko/LauncherHijack/pull/20),
[#52](https://github.com/BaronKiko/LauncherHijack/issues/52),
[#57](https://github.com/BaronKiko/LauncherHijack/issues/57),
[#62](https://github.com/BaronKiko/LauncherHijack/issues/62),
[#64](https://github.com/BaronKiko/LauncherHijack/issues/64)),
[arclauncher#52](https://github.com/meddouribadis/arclauncher/issues/52),
[couch#16](https://github.com/samuelloranger/couch/pull/16),
[couch-tv#75](https://github.com/Seagey/couch-tv/issues/75),
[couch-tv#94](https://github.com/Seagey/couch-tv/issues/94),
[couch-tv#223](https://github.com/Seagey/couch-tv/issues/223),
[Homestead#10](https://github.com/Sarg338/Homestead/pull/10),
[FireFrame#4](https://github.com/dyascj/FireFrame/issues/4),
[Fire2MQTT#45](https://github.com/Harrypulvirenti/Fire2MQTT/issues/45),
[Plume_Launcher#229](https://github.com/aradhyas8/Plume_Launcher/issues/229),
[projectivy-installer](https://github.com/Absolute-Projects-Public/projectivy-installer),
[firetv-tvos](https://github.com/NikhilSiddhu/firetv-tvos),
[Amazon developer forum](https://community.amazondeveloper.com/t/cannot-open-system-accessibility-settings-screen-programmatically-on-firetv/9422).

News: AFTVnews on
[Vega OS](https://www.aftvnews.com/tag/vega-os/),
[the first Fire OS 14 TV](https://www.aftvnews.com/amazon-reveals-the-first-fire-tv-running-android-14-youre-going-to-be-disappointed/),
[the 2022 launcher block](https://www.aftvnews.com/amazon-blocks-custom-home-launchers-with-latest-fire-tv-software-update/),
[the 2024 local adb block](https://www.aftvnews.com/amazon-blocks-long-running-fire-tv-capability-breaking-popular-apps-with-no-warning-and-giving-developers-the-runaround/),
[onn Android 14](https://www.aftvnews.com/android-tv-14-update-arrives-for-the-onn-4k-and-onn-4k-pro/);
[Android Authority on Projectivy and Google TV](https://www.androidauthority.com/google-tv-update-blocks-projectivy-override-3649445/);
[9to5Google on Chromecast Android 14](https://9to5google.com/2025/03/13/chromecast-google-tv-android-14-update/);
[PhoneArena on the Streamer](https://www.phonearena.com/news/google-tv-streamer-is-their-first-to-bring-the-benefits-of-the-new-android-14-for-tvs_id161265);
[PCWorld on tvQuickActions](https://www.pcworld.com/article/3046242/make-your-google-tv-look-like-apple-tv-with-this-free-app.html);
[Wikipedia list of smart TV platforms](https://en.wikipedia.org/wiki/List_of_smart_TV_platforms).
