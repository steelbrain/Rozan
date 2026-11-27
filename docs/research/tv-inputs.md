# TV inputs

Research for an inputs menu in the Rozan launcher, next to its settings
gear: how a sideloaded app (targetSdk 36, minSdk 28) lists a TV's
passthrough inputs (HDMI 1, HDMI 2, AV), switches to one, and falls back to
the system's own input picker, on Android TV and Google TV TVs, on streaming
sticks and boxes, and on Fire TV. Research date: 2026-11-26.

How to read the evidence:

- No code for this has run on a TV with HDMI inputs. "Tested" marks what
  was seen on the Android TV emulator (the SDK's API 31 and API 36
  `android-tv` arm64 images, `user` builds), which has no inputs of its own
  (10.1).
- Android internals were read in AOSP source (Apache 2.0). Links name the
  branch: mostly `main` as published on android.googlesource.com at the
  research date, with `pie-release` and `android12-release` where older
  releases matter. Line anchors refer to those branches on that date.
- Vendor and Fire OS behaviour comes from vendor documentation where it
  exists, then from issue trackers of other launcher projects and forum
  posts, cited per claim. **UNCONFIRMED** marks claims that rest on one
  secondary report, a search summary, or inference.
- The questions only a real TV can answer are in section 11.

## 1. Summary

| Platform | What a third-party launcher sees | Switching | Fallback |
| --- | --- | --- | --- |
| AOSP reference TV (Live TV app, TvSettings) | `TvInputManager` lists the vendor's hardware inputs (`TYPE_HDMI`, `TYPE_COMPOSITE` and so on) with no permission (3, 4) | `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(id)` resolves to Live TV's `MainActivity`, which tunes the input (6) | `com.android.tv.action.VIEW_INPUTS` opens Live TV's `SelectInputActivity`; `com.android.tv.settings/.system.InputsActivity` opens Settings > Inputs; both exported with no permission (9) |
| Android TV and Google TV TVs (Sony, TCL, Hisense, Philips) | The same API; input IDs come from the chip vendor's input service, such as `com.mediatek.tvinput/...` or `com.tcl.tvinput/...` (8.1) | The same intent, handled by the vendor's TV app; reported to work on Sony, TCL and Philips (**UNCONFIRMED**) | `VIEW_INPUTS` is reported to reach the vendor's picker or `com.google.android.tvlauncher/.inputs.InputsPanelActivity`; Sony's picker is reported as `com.sony.dtv.tvx/.inputselect.InputSelectionActivity` (**UNCONFIRMED**) |
| Sticks and boxes (Chromecast with Google TV, Google TV Streamer, Shield, Fire TV Stick) | No HDMI input hardware, so no passthrough inputs; only `TYPE_TUNER` inputs that installed apps provide (8.2; **UNCONFIRMED** per device) | Nothing to switch to. No public API makes the TV switch to the stick: HDMI-CEC One Touch Play is a system API (8.3) | None in software; the remote's own input button works over IR |
| Fire TV Edition and Omni TVs | Reported to list HDMI and AV inputs from the chip vendor's service (8.4, **UNCONFIRMED**) | Reported to fail on Fire OS 6, 7, 8 and 14: Live TV opens its settings instead. Amazon's own path needs a signature permission. A `TvView` in the launcher's own activity is reported to show the input (**UNCONFIRMED**) | Amazon's inputs screens; no public, documented intent (**UNCONFIRMED**) |
| Fire TV Cube (3rd gen) | One HDMI IN, switched from Fire OS's input menu; whether `TvInputManager` lists it is unknown (8.5) | Unknown | Amazon's input menu |
| Android TV emulator (API 31 and 36, tested) | `TvInputManager` exists and lists no passthrough inputs; a test `TvInputService` is listed as `TYPE_TUNER` (10) | API 36: the intent resolves to Live TV's `MainActivity`, which finishes for an input it cannot tune; API 31: no handler (10.1) | `VIEW_INPUTS`: Live TV's `SelectInputActivity` (API 36) and `com.google.android.tvlauncher/.inputs.InputsPanelActivity` (both) (10.1) |

The rules that follow from this, each explained in its section:

- No permission is needed to list inputs, read their state, register for
  changes or start the switch intent (3, 5, 6).
- `getSystemService(Context.TV_INPUT_SERVICE)` returns null on devices with
  neither `android.software.leanback` nor `android.software.live_tv` (2).
- `loadCustomLabel` and `isHidden` do not throw for a targetSdk 36 app in
  AOSP: both settings keys are `@Readable` (4.4).
- `loadLabel` and `loadIcon` can return null for an app that cannot see the
  input service's package; a `<queries>` entry for
  `android.media.tv.TvInputService` avoids that (4.3).
- Build the switch URI with `TvContract.buildChannelUriForPassthroughInput`,
  never by string concatenation: the input ID contains `/` and must be
  encoded as `%2F` (6.2).

## 2. Is TvInputManager there?

- `SystemServer` starts `TvInputManagerService` only when the device has
  `PackageManager.FEATURE_LIVE_TV` or `FEATURE_LEANBACK`
  ([main](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/java/com/android/server/SystemServer.java#2644),
  [pie-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/java/com/android/server/SystemServer.java#1483);
  the same condition is in `android11-release` through `android16-release`).
  Every Android TV device declares leanback, so the service runs on TVs,
  sticks and boxes alike. The service publishes itself as `tv_input`
  ([TvInputManagerService L222](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#222)),
  so `adb shell dumpsys tv_input` prints the input list.
- `SystemServiceRegistry` creates `TvInputManager` with
  `ServiceManager.getServiceOrThrow`
  ([L1071](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SystemServiceRegistry.java#1071)).
  When the service is missing, the fetcher catches the
  `ServiceNotFoundException`, logs a warning for app processes
  ([L2426](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SystemServiceRegistry.java#2426))
  and `getSystemService` returns null
  ([L1913](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SystemServiceRegistry.java#1913)).
  The AOSP TV app guards for this too: `createTvInputManagerWrapper`
  returns null when `getSystemService` does
  ([TvInputManagerHelper L306](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#306)).
- The class carries `@SystemService(Context.TV_INPUT_SERVICE)` and no
  `@RequiresFeature`
  ([TvInputManager L96](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputManager.java#96)).
  `FEATURE_LIVE_TV` is documented as "The device supports live TV and can
  display contents from TV inputs implemented with the TvInputService API"
  ([PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager#FEATURE_LIVE_TV)).
- Fire TV: Amazon documents third-party `TvInputService` apps for its Live
  TV integration
  ([TV Input Framework on Fire TV](https://developer.amazon.com/docs/fire-tv/tv-input-framework-on-fire-tv.html),
  [Set up TvInputService](https://developer.amazon.com/docs/fire-tv/set-up-tvinputservice.html)),
  which implies the service runs on Fire TV devices (**UNCONFIRMED**,
  inference).

## 3. Listing inputs and following changes

None of these calls needs a permission. In `TvInputManagerService` on
`main`:

| Call | Server side | Check |
| --- | --- | --- |
| `getTvInputList()` | [L1281](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1281): copies every `TvInputState.info` of the calling user | User resolution only |
| `getTvInputInfo(id)` | [L1300](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1300) | User resolution only |
| `getInputState(id)` | Client cache, see below; server [L1350](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1350) | User resolution only |
| `registerCallback(cb, handler)` | One binder callback per `TvInputManager`, registered in its constructor; server [L1518](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1518) | User resolution only |
| `updateTvInputInfo(info)` | [L1315](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1315) | Only the package that owns the input, or a holder of `WRITE_SECURE_SETTINGS` |
| `getHardwareList()` | [L2498](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#2498) | `TV_INPUT_HARDWARE`; `@SystemApi` |

- There is no package-visibility filtering of the list. `getTvInputList`
  returns every input of the user, whatever the caller's targetSdk
  (L1281-1296 above). Package visibility does affect what an app can load
  from a `TvInputInfo`; see 4.3.
- The callbacks `onInputAdded`, `onInputRemoved`, `onInputUpdated`,
  `onInputStateChanged` and `onTvInputInfoUpdated` are broadcast to every
  registered callback with no filter
  ([L1077-1146](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1077),
  [L1200](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1200)).
- `getInputState` does not make a binder call. The `TvInputManager`
  constructor registers its callback and fetches the state of every input
  into a map
  ([L2133](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputManager.java#2133));
  the callbacks keep it current. `onInputAdded` stores `CONNECTED` until a
  state change arrives, `onInputRemoved` deletes the entry
  ([L2077-2113](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputManager.java#2077)).
  For an ID not in the map the client logs "Unrecognized input ID" and
  returns `INPUT_STATE_DISCONNECTED`
  ([L2218](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputManager.java#2218)),
  while the server would have returned `CONNECTED` (L1350 above).
- States
  ([reference](https://developer.android.com/reference/android/media/tv/TvInputManager#INPUT_STATE_CONNECTED)):
  `INPUT_STATE_CONNECTED` (0; "Non-hardware inputs are considered connected
  all the time"), `INPUT_STATE_CONNECTED_STANDBY` (1; "a source device is
  connected to the input port but is in standby or low power mode"),
  `INPUT_STATE_DISCONNECTED` (2).
- The list also holds inputs from installed apps (IPTV and streaming
  services that implement `TvInputService`). They are `TYPE_TUNER` (4.1)
  and a launcher filters them out with `isPassthroughInput()`.

## 4. TvInputInfo for a launcher

### 4.1 Types and what makes an input passthrough

- Public types
  ([TvInputInfo L84-120](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#84)):
  `TYPE_TUNER` 0, `TYPE_OTHER` 1000, `TYPE_COMPOSITE` 1001, `TYPE_SVIDEO`
  1002, `TYPE_SCART` 1003, `TYPE_COMPONENT` 1004, `TYPE_VGA` 1005,
  `TYPE_DVI` 1006, `TYPE_HDMI` 1007, `TYPE_DISPLAY_PORT` 1008.
- `isPassthroughInput()` is `mType != TYPE_TUNER`
  ([L423](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#423)).
- Only a hardware input service can produce another type.
  `TvInputInfo.Builder.build()` sets `TYPE_HDMI` when an `HdmiDeviceInfo`
  was given, maps the type of a `TvInputHardwareInfo` when one was given,
  and otherwise uses `TYPE_TUNER`
  ([L927-958](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#927)).
  Both setters are `@SystemApi`
  ([L834](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#834),
  [L864](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#864)).
  `TvInputManagerService` builds the `TvInputInfo` itself for every service
  whose package lacks `TV_INPUT_HARDWARE`, with neither setter
  ([L405-446](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#405)),
  and accepts `addHardwareInput` and `addHdmiInput` only from callers that
  hold `TV_INPUT_HARDWARE`
  ([L3853](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#3853)).
  That permission is `signature|privileged|vendorPrivileged` (5), so
  passthrough inputs come only from the TV maker's privileged input service.
  source.android.com says the same in prose: HDMI and built-in tuner inputs
  "can be provided only by the manufacturer"
  ([TV Input Framework](https://source.android.com/docs/devices/tv)).
- Input IDs: a hardware port is `<service component>/HW<deviceId>`, an
  HDMI-CEC device is `<service component>/HDMI<physical address as 4 hex
  digits><device id as 2 hex digits>`
  ([L681](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#681),
  [L970-983](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#970)).
  Reported examples are in 8.1. The ID always contains `/`.

### 4.2 Public and hidden members

| Member | API | Note |
| --- | --- | --- |
| `getId()`, `getType()`, `isPassthroughInput()`, `getParentId()`, `loadLabel()`, `loadIcon(Context)`, `getServiceInfo()` | 21 | Public |
| `loadCustomLabel(Context)`, `isHidden(Context)` | 24 | Public; read `Settings.Secure` (4.4) |
| `getHdmiDeviceInfo()`, `isHardwareInput()`, `isConnectedToHdmiSwitch()`, `loadIcon(Context, int state)`, `TvInputSettings` | none | `@SystemApi @hide` ([L407-534](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#407), [L1049](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#1049)); absent from the [public reference](https://developer.android.com/reference/android/media/tv/TvInputInfo) |
| `getHdmiConnectionRelativePosition()` | none | `@hide` |

`getParentId()`: "a HDMI CEC logical device, connected to a HDMI port,
appears as another TV input. In this case, the parent input of this logical
device is the HDMI port. Applications may group inputs by parent input ID"
([L300-319](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#300)).
A launcher can group CEC devices under their port with it, but cannot tell
whether a device sits directly on the port or behind a switch: that is
`isConnectedToHdmiSwitch()`, a system API. The AOSP TV app uses it (7.1).

### 4.3 Labels and icons under package visibility

- `loadLabel` returns the input's label text if the service set one, loads
  a label resource with `PackageManager.getText(packageName, resId, null)`
  if it set a resource, and otherwise returns the service's own label
  ([L476](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#476)).
  `getText` with a null `ApplicationInfo` calls `getApplicationInfo` and
  returns null on `NameNotFoundException`
  ([ApplicationPackageManager L2416](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ApplicationPackageManager.java#2416)).
  For an app targeting API 30 or higher, `getApplicationInfo` throws that
  exception for a package the app cannot see
  ([package visibility](https://developer.android.com/training/package-visibility)).
  So `loadLabel` can return null on API 30+ when the vendor's service set
  its label as a resource (**UNCONFIRMED**, inference from code). Kotlin
  sees the return type as a platform type, so a null here crashes code that
  assumes non-null.
- `loadIcon(Context)` loads the service's `Icon`. A resource icon from
  another package goes through `getApplicationInfo` too and fails with
  "Unable to find pkg" when the package is not visible
  ([Icon L446-478](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/Icon.java#446)),
  and `loadIcon` then returns null without falling back
  ([TvInputInfo L504](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#504))
  (**UNCONFIRMED**, inference).
- The AOSP TV app declares `<queries><intent><action
  android:name="android.media.tv.TvInputService" /></intent></queries>`
  ([AndroidManifest_common.xml L23-28](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/AndroidManifest_common.xml#23)).
  The same entry in the launcher's manifest makes every input service
  package visible, because a `<queries>` intent matches other apps'
  service intent filters
  ([declaring package visibility needs](https://developer.android.com/training/package-visibility/declaring)).

### 4.4 Custom labels and hidden inputs

- `loadCustomLabel` and `isHidden` read `Settings.Secure` through
  `TvInputInfo.TvInputSettings`
  ([L465-494](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#465),
  [L1052-1110](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#1052)).
  `loadCustomLabel` returns null when the user set no custom label.
- Both keys are hidden and annotated `@Readable`, with no `maxTargetSdk`:
  `TV_INPUT_HIDDEN_INPUTS = "tv_input_hidden_inputs"` and
  `TV_INPUT_CUSTOM_LABELS = "tv_input_custom_labels"`
  ([main L10914-10924](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java#10914),
  [android12-release L9005-9015](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android12-release/core/java/android/provider/Settings.java#9005)).
- What happens to a key that is not `@Readable`: for a non-system app
  `SettingsProvider` throws `SecurityException("Settings key: <name> is not
  readable. From S+, settings keys annotated with @hide are restricted to
  system_server and system apps only, unless they are annotated with
  @Readable.")`
  ([SettingsProvider L2258-2340](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java#2258)).
  Neither TV key is in that situation, so in AOSP neither call throws for a
  targetSdk 36 app. API 28 to 30 predate the restriction. A vendor build
  that drops the annotation would throw (**UNCONFIRMED**, nothing found
  either way); a `try`/`catch (SecurityException)` costs nothing.
- Storage format, from `TvInputSettings` (L1052-1180 above) and the KDoc of
  the keys:
  - Hidden inputs: input IDs, each `Uri.encode`d, joined with `:`.
  - Custom labels: pairs `Uri.encode(id) + "," + Uri.encode(label)`, pairs
    joined with `:`. The reader splits on `:` and then `,` and indexes
    `pair[1]` without a length check
    ([L1096-1110](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#1096)),
    so a malformed value written by some other privileged app would throw
    `ArrayIndexOutOfBoundsException` from `loadCustomLabel` (inference;
    TvSettings never writes an empty field).
- Change notifications have gaps. TvSettings writes these keys from
  Settings > Inputs > an input
  ([InputOptionsFragment L163-189](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/main/Settings/src/com/android/tv/settings/system/InputOptionsFragment.java#163)).
  `putHiddenTvInputs` and `putCustomLabels` then call `updateTvInputInfo`,
  which fires `onTvInputInfoUpdated`, but only for the inputs still in the
  hidden set or the label map
  ([L1139](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#1139),
  [L1178](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputInfo.java#1178)).
  Showing a hidden input again, or clearing a custom label, notifies
  nothing for that input. Reading both values when the menu opens, or
  observing `Settings.Secure.getUriFor("tv_input_hidden_inputs")` and
  `getUriFor("tv_input_custom_labels")` with a `ContentObserver`, covers it
  (the observer is inference; not tried).

## 5. Permissions

From `frameworks/base/core/res/AndroidManifest.xml` on `main` unless noted.
`adb shell pm grant` accepts only runtime (`dangerous`) and `development`
permissions; anything else fails with "is not a changeable permission type"
([PermissionManagerServiceImpl L1403-1413](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/permission/PermissionManagerServiceImpl.java#1403)).

| Permission | Protection level | Guards | Sideloaded app |
| --- | --- | --- | --- |
| `TV_INPUT_HARDWARE` ([L2914](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#2914)) | signature, privileged, vendorPrivileged | Hardware inputs, `getHardwareList` | No |
| `HDMI_CEC` ([L2126](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#2126)) | signature, privileged, vendorPrivileged | Every `HdmiControlService` binder call | No |
| `CHANGE_HDMI_CEC_ACTIVE_SOURCE` ([L5859](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5859)) | signature, privileged | `TvView.setMain()` / `setMainSession` | No |
| `CAPTURE_TV_INPUT` ([L2921](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#2921)) | signature, privileged | Frame capture of hardware inputs | No |
| `DVB_DEVICE` ([L2926](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#2926)) | signature, privileged | DVB device access | No |
| `BIND_TV_INPUT` ([L5818](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5818)) | signature, privileged | Held by the system to bind input services | No (an input service requires it of its binder) |
| `ALWAYS_BOUND_TV_INPUT` ([L5828](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5828)) | signature, privileged, vendorPrivileged | Keep an input service bound | No |
| `NOTIFY_TV_INPUTS` ([L5878](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5878)), `MODIFY_PARENTAL_CONTROLS` ([L5865](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5865)), `READ_CONTENT_RATING_SYSTEMS` ([L5871](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5871)), `TV_VIRTUAL_REMOTE_CONTROLLER` ([L5853](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5853)) | signature, privileged | TV app duties | No |
| `TUNER_RESOURCE_ACCESS` ([L5886](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5886)), `ACCESS_TV_TUNER` ([L8088](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#8088)), `ACCESS_TUNED_INFO` ([L8226](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#8226)), `TIS_EXTENSION_INTERFACE` ([L8402](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#8402)) | signature, privileged, vendorPrivileged | Tuner HAL and tuned-channel info | No |
| `INJECT_EVENTS` ([L5374](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5374)) | signature | Keys to other apps' windows: "Without this permission, you can only deliver events to windows in your own process" | No |
| `WRITE_SECURE_SETTINGS` ([L5153](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5153)) | signature, privileged, development, role, installer | Writing the label and hidden keys; `updateTvInputInfo` for others | Only with `adb shell pm grant`; not needed to read |
| `READ_TV_LISTINGS` (TvProvider, [L23](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml#23)) | dangerous | Reading channel and program rows | Runtime grant; not needed for inputs |
| `com.android.providers.tv.permission.READ_EPG_DATA` (TvProvider, [L30](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml#30)) | normal | "@deprecated No longer enforced." | Not needed |
| `com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA` (TvProvider, [L46](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml#46)) | signature, privileged | Every channel and program | No |

None of these is needed for the launcher's menu. What it uses (list, state,
callbacks, `loadCustomLabel`, `isHidden`, `startActivity`) needs no
permission.

## 6. Switching to an input

### 6.1 What the documentation says

- [`TvContract.buildChannelUriForPassthroughInput(String inputId)`](https://developer.android.com/reference/android/media/tv/TvContract#buildChannelUriForPassthroughInput(java.lang.String))
  (API 21): "Build a special channel URI intended to be used with
  pass-through inputs. (e.g. HDMI)". The reference does not mention
  `ACTION_VIEW` or any app that handles the URI.
- source.android.com: "Normally a TV Input may be tuned by the TV App only
  to URIs it has added to the TV Provider database, except for passthrough
  TV Inputs which can be tuned to using
  `TvContract.buildChannelUriForPassthroughInput()`", and "The TIF works
  with the TV App, a system app that can't be replaced by a third-party
  app" ([TV Input Framework](https://source.android.com/docs/devices/tv)).
- No developer.android.com page found documents `startActivity(Intent(
  ACTION_VIEW, passthroughUri))` (**UNCONFIRMED** that none exists). The
  pattern comes from the AOSP TV app, which starts its own `MainActivity`
  with exactly this intent from its input picker
  ([SelectInputActivity L45-63](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/SelectInputActivity.java#45)),
  and from the vendor TV apps that match it (8.1).

### 6.2 How the intent resolves

- The URI is `content://android.media.tv/passthrough/<id>`, built with
  `Uri.Builder.appendPath`, which encodes the `/` in the ID as `%2F`
  ([TvContract L488](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvContract.java#488)).
  `isChannelUriForPassthroughInput` accepts exactly two path segments
  ([L702-725](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvContract.java#702)),
  and TvProvider matches `passthrough/*`
  ([TvProvider L171](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/src/com/android/providers/tv/TvProvider.java#171)),
  so an unencoded ID does not match. Philips owners saw the same thing:
  "you need to keep the %2F to get it working"
  ([Home Assistant forum](https://community.home-assistant.io/t/android-tv-hdmi/234411?page=2)).
- `startActivity` resolves the MIME type of a `content:` URI before
  matching filters
  ([Instrumentation L2008](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/Instrumentation.java#2008)).
  TvProvider returns `Channels.CONTENT_ITEM_TYPE`,
  `"vnd.android.cursor.item/channel"`, for a passthrough URI
  ([TvProvider L1319-1328](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/src/com/android/providers/tv/TvProvider.java#1319),
  [TvContract L1700](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvContract.java#1700)).
  The provider is exported, has no read permission and its package is
  `forceQueryable`
  ([TvProvider manifest L65-72](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml#65)),
  so the type resolves for any caller.
- The handler in AOSP is the Live TV app (`packages/apps/TV`, package
  `com.android.tv`, a privileged system app:
  [Android.bp L39-50](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/Android.bp#39)).
  It builds in two variants with different `MainActivity` filters:
  - `LiveTv`
    ([Android.bp L121](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/Android.bp#121)):
    action `android.intent.action.VIEW`, category `DEFAULT`, and
    `mimeType` only (`vnd.android.cursor.item/program`,
    `vnd.android.cursor.dir/program`, `vnd.android.cursor.item/channel`,
    `vnd.android.cursor.dir/channel`), no scheme, host or path
    ([AndroidManifest_all_inputs.xml L49-57](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/AndroidManifest_all_inputs.xml#49)).
    A passthrough URI matches on its type.
  - `LiveTvNonPassthrough`
    ([Android.bp L130](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/Android.bp#130)):
    the same types with `scheme="content"`, `host="android.media.tv"` and
    `pathPrefix` `/program` or `/channel`
    ([AndroidManifest_non_passthrough.xml L49-70](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/AndroidManifest_non_passthrough.xml#49)).
    A `/passthrough/...` path does not match.
- `MainActivity` skips onboarding for a passthrough `ACTION_VIEW`
  ([L552-565](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/MainActivity.java#552)),
  takes the input ID from the second path segment and finishes if the
  input is gone
  ([L1040-1056](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/MainActivity.java#1040)).
  It holds `CHANGE_HDMI_CEC_ACTIVE_SOURCE`
  ([AndroidManifest_common.xml L37](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/AndroidManifest_common.xml#37)),
  so its `TvView` becomes the main session and the HDMI-CEC routing
  follows (6.4).
- With no handler, `startActivity` throws `ActivityNotFoundException`;
  developer.android.com's advice is to "invoke the intent and handle the
  ActivityNotFoundException exception if no app is available"
  ([package visibility use cases](https://developer.android.com/training/package-visibility/use-cases)).
  `startActivity` needs no `<queries>` entry; `resolveActivity` and
  `queryIntentActivities` do (same page).

### 6.3 Who else can catch the intent

Any app with a VIEW filter for `vnd.android.cursor.item/channel` (or a
broad MIME type) matches too. On an RCA TV, ES File Explorer claimed the
type and "HDMI 1 opened the file manager"
([cleanyourtv TROUBLESHOOTING.md, #805](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md),
**UNCONFIRMED**). A launcher that resolves the handlers itself
(`queryIntentActivities` with a `<queries>` entry for action `VIEW` and
`mimeType="vnd.android.cursor.item/channel"`) can prefer a system app and
set the component explicitly. That is a design option, not something found
in AOSP.

### 6.4 TvView in the launcher's own activity

- `TvView.tune(inputId, channelUri)` is public, and
  `TvInputManagerService.createSession` checks no permission
  ([L1666-1746](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1666)).
  source.android.com allows third-party inputs to show "content from a
  device manufacturer's passthrough TV inputs, like HDMI1"
  ([TV Input Framework](https://source.android.com/docs/devices/tv)).
- `TvView.setMain()` is `@SystemApi` and needs
  `CHANGE_HDMI_CEC_ACTIVE_SOURCE`; it is what makes the input "determine the
  HDMI-CEC active source device"
  ([TvView L227-247](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvView.java#227)).
  `TvView` calls it only when the app holds that permission
  ([L884-893](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvView.java#884),
  [L1411](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvView.java#1411)),
  and the service rejects it otherwise
  ([TvInputManagerService L1781](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/tv/TvInputManagerService.java#1781)).
  So a launcher's `TvView` can show the picture but does not send the CEC
  routing the TV app sends (inference from code).
- Other launchers use a `TvView` where the TV app does not cooperate: on an
  Insignia Fire TV Edition ("HDMI 1-3, AV and the antenna play from the
  launcher's Inputs menu",
  [tv-vibehack-firetv](https://github.com/Ragnarok93/tv-vibehack-firetv)),
  on a Fire OS 7 Hisense
  ([dumbtv PR #33](https://github.com/humblemuzzu/dumbtv/pull/33)) and on a
  TCL Android 9 TV
  ([TCL-Android-TV-HDMI](https://github.com/ian20040409/TCL-Android-TV-HDMI));
  all **UNCONFIRMED**.

## 7. How the system's own screens present inputs

### 7.1 AOSP Live TV input picker

`SelectInputView`, shown by `SelectInputActivity` and inside `MainActivity`
([SelectInputView](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java)):

- Filter
  ([L247-266](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#247)):
  every passthrough input that `isHidden` does not exclude, plus one row for
  all tuner inputs, labelled "TV (antenna/cable)"
  ([strings.xml L121](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/res/values/strings.xml#121)).
  Inputs from non-system packages are dropped unless a setting allows them
  ([TvInputManagerHelper L692](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#692)).
- HDMI-CEC: "Do not show HDMI ports if a CEC device is directly connected
  to the port." For each input with a parent ID that is not behind a switch,
  the parent port is removed, so the CEC device replaces its port
  (L262-266). A port with CEC devices behind a switch stays, next to them.
  A launcher without `isConnectedToHdmiSwitch()` can only approximate this,
  for example by always replacing a port that has children.
- Order (`HardwareInputComparator`,
  [TvInputManagerHelper L737-801](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#737)):
  1. Inputs that are not `DISCONNECTED` first.
  2. Type priority: an OEM order from the partner customization package,
     then the default
     ([L143-161](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#143)):
     bundled tuner, tuner, CEC device, CEC recorder, CEC playback device,
     MHL, HDMI, DVI, component, S-Video, composite, DisplayPort, VGA, SCART,
     other. The CEC kinds come from `getHdmiDeviceInfo()` (a system API;
     [L837-853](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#837)).
  3. Between two tuners, a physical tuner first.
  4. The service's `input_sort_key` meta-data integer, higher first
     ([L594](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#594)).
  5. The parent's label (custom label, else label; the input's own label
     when it has no parent), case-insensitive.
  6. The input's own label, case-insensitive.
- Labels
  ([L284-305](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#284)):
  with no custom label, or one equal to the label, the row shows
  `loadLabel`. Otherwise the custom label is the main line and `loadLabel`
  the second line.
- State
  ([L307](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#307),
  [L335-350](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#335)):
  a `DISCONNECTED` input is listed, greyed and not focusable. `STANDBY` is
  shown and selectable like `CONNECTED`. All four `TvInputCallback` events
  rebuild the list.
- Behaviour: pressing `KEYCODE_TV_INPUT` again moves to the next enabled
  input
  ([L171](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#171)).
  After 4000 ms with no key
  ([integers.xml L68](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/res/values/integers.xml#68)),
  the focused row is selected and tuned
  ([L102-122](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#102),
  [L242](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/ui/SelectInputView.java#242)).
  Opening the picker and waiting therefore switches to whatever is focused.

### 7.2 AOSP TvSettings Inputs screen

`InputsFragment`
([source](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/main/Settings/src/com/android/tv/settings/system/InputsFragment.java)):

- Lists `getTvInputList()` minus `TYPE_TUNER` inputs and minus every input
  with a parent ID, so it shows ports only, never CEC devices
  ([L103-117](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/main/Settings/src/com/android/tv/settings/system/InputsFragment.java#103)).
- Groups them under connected, standby and disconnected headers, in list
  order with no sort (L119-178).
- Title is `loadLabel`. The summary is the hidden marker for a hidden input,
  else the custom label, else `loadLabel`
  ([L210-226](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/main/Settings/src/com/android/tv/settings/system/InputsFragment.java#210)).
  Choosing an input opens `InputOptionsFragment` to rename or hide it
  (4.4).
- The same screen holds the HDMI-CEC switches (HDMI control, device auto
  off, TV auto on)
  ([inputs.xml](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/main/Settings/res/xml/inputs.xml)).

## 8. Per platform

### 8.1 Android TV and Google TV TVs

- Passthrough inputs exist and come from the vendor's privileged input
  service. Reported IDs (all **UNCONFIRMED**, one report each):
  - MediaTek: `com.mediatek.tvinput/.hdmi.HDMIInputService/HW5` to `HW8`
    on Philips 2020 models
    ([Home Assistant forum](https://community.home-assistant.io/t/android-tv-hdmi/234411?page=2)),
    and the CEC-device form `.../HDMIInputService/HDMI100004` on a Sony
    XE9005
    ([unfoldedcircle #128](https://github.com/unfoldedcircle/feature-and-bug-tracker/issues/128)).
  - TCL (Google TV 11, MediaTek): HDMI 1 to 4
    `com.tcl.tvinput/.passthroughinput.TvPassThroughService/HW1413744128`
    and so on, AV `.../HW1413743104`, tuner
    `com.tcl.tvinput/.TDTVInputService/HW1413742848`
    ([cleanyourtv #734](https://github.com/Gathin23/cleanyourtv/issues/734)).
  - Amlogic: `com.droidlogic.tvinput/.services.Hdmi1InputService/HW5`
    (Home Assistant forum above).
  - Realtek (Acer Google TV):
    `com.realtek.tv.passthrough/.hdmiinput.HDMITvInputService/HW151519744`
    ([tv-vibehack](https://github.com/ToomRamcharan/tv-vibehack)).
- Handlers of the passthrough `ACTION_VIEW` (all **UNCONFIRMED**):
  - TCL Google TV 11: "ACTION_VIEW on the passthrough URI resolves to
    com.tcl.tv/.TVActivity and switched to HDMI 1" (cleanyourtv #734
    above).
  - Philips: `org.droidtv.playtv/.PlayTvActivity`; the intent worked with
    the encoded ID (Home Assistant forum above).
  - Sony XE9005 (Android TV 9): the `am start -a android.intent.action.VIEW
    -d content://android.media.tv/passthrough/...` command worked
    (unfoldedcircle #128 above). A Stack Overflow answer mirrored elsewhere
    says the same intent "worked well" on a Sony TV (search summary only).
  - VU (Hisense/MediaTek, Google TV 11): launcher HDMI tiles that use
    `content://android.media.tv/passthrough/...` work
    ([miproja1 #649](https://github.com/spocky/miproja1/issues/649)).
- Google's own Live Channels build is `com.google.android.tv`: the ATV
  reference overlay sends `KEYCODE_TV_INPUT` to
  `com.google.android.tv/.receiver.GlobalKeyReceiver`
  ([device/google/atv global_keys.xml](https://android.googlesource.com/device/google/atv/+/refs/heads/main/overlay/TvFrameworkOverlay/res/xml/global_keys.xml)).
  Which TVs ship it instead of a vendor TV app was not found
  (**UNCONFIRMED**).
- No vendor developer documentation for any of this was found (Sony, TCL,
  Hisense, Philips; **UNCONFIRMED** that none exists).

### 8.2 Streaming sticks and boxes

- They are HDMI sources with no HDMI input: Chromecast with Google TV and
  Google TV Streamer list HDMI output, USB-C and Ethernet
  ([Google support](https://support.google.com/chromecast/answer/3046409),
  [Google TV Streamer specs](https://store.google.com/product/google_tv_streamer_specs));
  Shield TV and Shield TV Pro list HDMI 2.0b output, Ethernet and USB
  ([NVIDIA](https://www.nvidia.com/en-us/shield/shield-tv-pro/)).
- With no input hardware, no vendor hardware input service has anything to
  register, so `getTvInputList()` holds only `TYPE_TUNER` inputs from
  installed apps and the inputs menu would be empty (**UNCONFIRMED**,
  inference; no `dumpsys tv_input` from these devices was found).
- The AOSP emulator configuration, which models a box, sets the device up
  as an HDMI-CEC playback device with one HDMI output port (10).

### 8.3 HDMI-CEC from a stick

- `HdmiControlManager` is `@SystemApi @hide`
  ([L61-66](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/hdmi/HdmiControlManager.java#61)),
  and so is `HdmiPlaybackClient`, which holds `oneTouchPlay`
  ([L30-33](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/hdmi/HdmiPlaybackClient.java#30)).
  The `android.hardware.hdmi` package is not in the public reference
  (developer.android.com returns 404 for
  `reference/android/hardware/hdmi/HdmiControlManager` and the package
  summary on the research date). `FEATURE_HDMI_CEC` itself is `@hide
  @TestApi`
  ([PackageManager L4578-4584](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/PackageManager.java#4578)).
- Every `HdmiControlService` binder call enforces `HDMI_CEC`
  ([L211](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/hdmi/HdmiControlService.java#211),
  [L2315-2321](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/hdmi/HdmiControlService.java#2315)),
  which a sideloaded app cannot hold (5).
- So no public API lets a third-party app make the TV switch inputs, from a
  stick or from a TV. The Google TV remotes switch the TV's input over IR
  after setup in Settings > Remotes & Accessories
  ([Google support](https://support.google.com/chromecast/answer/10094150)),
  which is outside any app's reach.

### 8.4 Fire TV Edition and Omni TVs

Amazon's public documentation covers third-party `TvInputService` apps for
Live TV and HDMI hot-plug events
([TV Input Framework on Fire TV](https://developer.amazon.com/docs/fire-tv/tv-input-framework-on-fire-tv.html),
[Handling HDMI events](https://developer.amazon.com/docs/fire-tv/handling-hdmi-events.html)).
No Amazon page found mentions passthrough inputs, `TvInputManager`,
`TvView` or a deep link to an input. The HDMI events page says "Smart TVs
might choose not to notify the connected Fire TV device that a different
input was selected". Everything below comes from other launcher projects and
is **UNCONFIRMED**:

- Inputs are listed: `com.mediatek.tis/.HdmiInputService/HWn` on Fire OS 8
  TVs
  ([dumbtv #63](https://github.com/humblemuzzu/dumbtv/issues/63)), and
  inputs from `com.mstar.android.tv.inputservice` on an Insignia on Fire OS
  6
  ([tv-vibehack-firetv](https://github.com/Ragnarok93/tv-vibehack-firetv)).
- The passthrough `ACTION_VIEW` reaches Amazon's Live TV
  (`com.amazon.tv.livetv/.TvChannelsPlayerActivityAlias`), which logs
  "Redirecting to LiveTvSettings for CTS compliance" and "No tune target
  found for intent: content://android.media.tv/passthrough/…", and opens
  its settings, "whether or not the input ID in the URI is percent-encoded"
  (dumbtv #63 above, AFTHY7ABBA, Fire OS 8). The same failure is reported on
  Fire OS 14 (TCL 65QM64L,
  [home-on-fire #13](https://github.com/toolicious/home-on-fire/issues/13)),
  and on Fire OS 8.1.8.0 (AFTOLCBC82), Fire OS 7 (AFTDCT31, AFTGRO001,
  AFTHA001) and Fire OS 6.7.1.1 (AFTEAMR311), where "the URI resolve[s] only
  to the Live TV app (which lands in its channel-scan screen)"
  ([dumbtv PR #33](https://github.com/humblemuzzu/dumbtv/pull/33)).
- Fire OS switches inputs itself with `VIEW
  tvinput://tunenow?inputId=<id>` to
  `com.amazon.tv.inputpreference.service/com.amazon.tv.inputpreference.player.PassthroughPlayerActivity`,
  which requires `com.amazon.tv.permission.TUNE_INPUT`, "prot=signature|amazon"
  (dumbtv #63). On Fire OS 14 the path also passes through
  `com.amazon.tv.inputpreference.service/.StartAmazonInputActivity` (action
  `SWITCH_AMAZON_INPUT`), which also gives "Permission Denial" to other
  apps (home-on-fire #13).
- A `TvView` in the launcher's own activity is reported to play HDMI, AV
  and the antenna (tv-vibehack-firetv, Fire OS 6). dumbtv's `TvView` screen
  "asks the TV input service for a session on the passthrough channel and
  hands it the window's surface"; on a Hisense AFTHA001 "Picture and audio
  worked; Back returned to the launcher" (dumbtv PR #33 above).
- The remote's HDMI keys (`KEYCODE_TV_INPUT_HDMI_1` and so on) switch
  inputs, but an app cannot send them (9.4).

### 8.5 Fire TV Cube (3rd gen)

- One HDMI IN, which Amazon describes for cable and satellite boxes. Users
  switch to it from "the input menu in the Fire TV's main navigation bar",
  with "Alexa, switch to HDMI", or by waking a CEC device
  ([AFTVnews, 2022-10-27](https://www.aftvnews.com/everything-there-is-to-know-about-the-hdmi-in-port-on-the-fire-tv-cube-3/)).
- The input is handled by `com.amazon.tv.inputpreference.service`; an adb
  command that opens its chooser is `am start -n
  com.amazon.tv.inputpreference.service/com.amazon.tv.inputpreference.player.InputChooserActivity`
  on the AFTGAZL
  ([HA-Firemote wiki](https://github-wiki-see.page/m/PRProd/HA-Firemote/wiki/Fire-TV-Cube---3rd-Gen-(2022))).
  adb's shell user holds permissions an app does not, so this does not show
  that an app can start it (**UNCONFIRMED**).
- Whether the Cube's HDMI IN appears in `getTvInputList()`, and with which
  type, was not found (**UNCONFIRMED**).

## 9. Opening the system's input picker

### 9.1 The Inputs key in AOSP

- `KEYCODE_TV_INPUT` (178): "On TV remotes, switches the input on a
  television screen"
  ([KeyEvent L548-550](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/KeyEvent.java#548)).
- `PhoneWindowManager` has no case for it; TV devices route it through
  `GlobalKeyManager`
  ([PhoneWindowManager L4006](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/PhoneWindowManager.java#4006)).
  A global key "will NOT go to the foreground application and instead only
  ever be sent via targeted broadcast to the specified component" as
  `android.intent.action.GLOBAL_BUTTON`
  ([framework global_keys.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/xml/global_keys.xml)).
  The framework default maps no keys; the ATV reference overlay maps
  `KEYCODE_TV_INPUT` (with `DVR`, `GUIDE` and `TV`) to
  `com.google.android.tv/.receiver.GlobalKeyReceiver`
  ([device/google/atv](https://android.googlesource.com/device/google/atv/+/refs/heads/main/overlay/TvFrameworkOverlay/res/xml/global_keys.xml)).
- In the TV app the receiver calls `handleTvInputKey`
  ([AbstractGlobalKeyReceiver L96](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/receiver/AbstractGlobalKeyReceiver.java#96)),
  which shows the picker inside `MainActivity` when it is in front and
  otherwise starts `SelectInputActivity`
  ([TvApplication L397-442](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/TvApplication.java#397)).
  A launcher in front never sees the key on such a device.
- Vendors replace this: a Xiaomi Mi TV 4A's firmware handler logs "receive
  ACTION_GLOBAL_BUTTON keyCode = 178" and starts `act=com.android.tv.action.VIEW_INPUTS
  cmp=com.google.android.tvlauncher/.inputs.InputsPanelActivity`
  ([cleanyourtv #2446](https://github.com/Gathin23/cleanyourtv/issues/2446),
  **UNCONFIRMED**).

### 9.2 Intents a launcher can start

| Target | How | Exported, permission | Source |
| --- | --- | --- | --- |
| AOSP Live TV picker, `SelectInputActivity` | Action `com.android.tv.action.VIEW_INPUTS`, category `DEFAULT` | Exported, no permission | [AndroidManifest_common.xml L139-150](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/AndroidManifest_common.xml#139) |
| AOSP Live TV source setup | Action `android.media.tv.action.SETUP_INPUTS` (public, for tuner inputs) | Exported, no permission | [TvInputManager L833-839](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/tv/TvInputManager.java#833), [AndroidManifest_all_inputs.xml L58](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/AndroidManifest_all_inputs.xml#58) |
| AOSP TvSettings Inputs | Explicit `com.android.tv.settings/.system.InputsActivity`; no intent filter | `exported="true"`, no permission | [TvSettings AndroidManifest.xml L732-736](https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/heads/main/Settings/AndroidManifest.xml#732) |
| Classic Android TV home, Inputs panel | `com.google.android.tvlauncher/.inputs.InputsPanelActivity`, answers `VIEW_INPUTS` | **UNCONFIRMED**; found by `query-activities` on an XGIMI projector | [unbloat-android-tv #4](https://github.com/xMazaki/unbloat-android-tv/issues/4) |
| VU (Hisense) Google TV 11 | `com.vt.tv.input/.view.activity.MainActivity`, answers `VIEW_INPUTS` | **UNCONFIRMED** | [miproja1 #649](https://github.com/spocky/miproja1/issues/649) |
| Sony BRAVIA (Android TV 9) | `com.sony.dtv.tvx/.inputselect.InputSelectionActivity`; actions `VIEW_INPUTS` and `com.sony.dtv.tvx.inputselect.intent.action.SHOW` | **UNCONFIRMED**; opened with `am start -n` | [miproja1 #653](https://github.com/spocky/miproja1/issues/653) |
| TCL Google TV 11 | `VIEW_INPUTS` "opens com.tcl.suspension's source overlay" | **UNCONFIRMED** | [cleanyourtv #734](https://github.com/Gathin23/cleanyourtv/issues/734) |
| Fire TV Edition | `com.amazon.tv.action.NAVIGATE_TO_INPUTS` on `com.amazon.tv.launcher` ("I haven't tested this") | **UNCONFIRMED** | [dumbtv #63](https://github.com/humblemuzzu/dumbtv/issues/63) |
| Fire TV Cube | `com.amazon.tv.inputpreference.service/...player.InputChooserActivity` | **UNCONFIRMED**; shown only with adb | 8.5 |

- `com.android.tv.action.VIEW_INPUTS` is not a public SDK constant; it is
  the AOSP TV app's own action, which other vendors' pickers also answer
  (reports above). No developer.android.com page documents it.
- Two handlers bring up the chooser: on the XGIMI, a plain `startActivity`
  with `VIEW_INPUTS` showed `ResolverActivity` while both handlers were
  enabled (unbloat-android-tv #4 above, **UNCONFIRMED**). Resolving with
  `queryIntentActivities` needs a `<queries>` entry for the action (6.2).
- Live TV's picker switches to the focused input after 4 seconds with no
  key (7.1).

### 9.3 Fire TV

No public Amazon intent for the inputs screen was found. See the last two
rows above, and 8.4 for the protected player.

### 9.4 Sending the Inputs key

A third-party app cannot press the key for the user. `INJECT_EVENTS` is
`signature` and "Without this permission, you can only deliver events to
windows in your own process"
([AndroidManifest.xml L5368-5374](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml#5368)).
On Fire OS 14 even keys sent with `adb shell input keyevent` "don't reach
`onKeyEvent`" of an accessibility service
([home-on-fire #13](https://github.com/toolicious/home-on-fire/issues/13),
**UNCONFIRMED**).

## 10. Testing on the emulator

- The AOSP TV emulator images (`sdk_atv_x86`, `aosp_tv_x86`) declare
  `android.software.leanback` and `android.software.live_tv`
  ([tv_core_hardware.xml](https://android.googlesource.com/device/google/atv/+/refs/heads/main/permissions/tv_core_hardware.xml)),
  so `TvInputManager` exists there (2).
- Their vendor partition installs `tv_input.default`
  ([atv_vendor.mk](https://android.googlesource.com/device/google/atv/+/refs/heads/main/products/atv_vendor.mk),
  inherited by
  [atv_emulator_vendor.mk](https://android.googlesource.com/device/google/atv/+/refs/heads/main/products/atv_emulator_vendor.mk)).
  That legacy stub stores the callback in `initialize` and reports no
  devices
  ([tv_input.cpp](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/modules/tv_input/tv_input.cpp)).
  The emulator therefore has no hardware inputs.
- The AIDL default TV input HAL (Android 14 and later) creates three fake
  devices, all available: device 0 `TUNER`, device 1 `HDMI` and device 3
  `DISPLAY_PORT`, each with one stream configuration
  ([tv/input/aidl/default/TvInput.cpp L30-67](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/tv/input/aidl/default/TvInput.cpp#30)).
  The emulator makefiles above do not install it. Even with it, a hardware
  device only becomes a `TvInputInfo` through a privileged input service
  holding `TV_INPUT_HARDWARE` (4.1), and no such service for the emulator
  was found in AOSP (**UNCONFIRMED** that none exists).
- HDMI-CEC: the emulator sets `ro.hdmi.device_type=4` and
  `ro.hdmi.cec_device_types=playback_device`
  ([atv_emulator_vendor.mk](https://android.googlesource.com/device/google/atv/+/refs/heads/main/products/atv_emulator_vendor.mk)).
  The AIDL HDMI connection mock has one port, an `OUTPUT` port with CEC,
  physical address `0xFFFF` until a hot-plug message
  ([HdmiConnectionMock.cpp L185-199](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/tv/hdmi/connection/aidl/default/HdmiConnectionMock.cpp#185),
  [HdmiConnectionMock.h L70-71](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/tv/hdmi/connection/aidl/default/HdmiConnectionMock.h#70)).
  Neither gives the emulator an HDMI input.
- A test `TvInputService` in an ordinary app is always `TYPE_TUNER`
  (`isPassthroughInput()` false): the service builds its `TvInputInfo` from
  the manifest with no hardware info (4.1). Installing one does exercise
  `onInputAdded`, `onInputRemoved` and the tuner filter. The AOSP TV app's
  test input is `com.android.tv.testinput/.TestTvInputService`
  ([TvInputManagerHelper L170](https://android.googlesource.com/platform/packages/apps/TV/+/refs/heads/main/src/com/android/tv/util/TvInputManagerHelper.java#170)).
- The passthrough list, labels, states and the switch to a real input can
  only be checked on a TV with HDMI inputs.

### 10.1 What the emulator images have (tested)

Seen on the SDK's `system-images;android-36;android-tv;arm64-v8a`
(`ro.build.type=user`, so `adb root` is refused) on 2026-11-26:

- Features `android.software.leanback`, `leanback_only` and `live_tv`.
  `service list` shows `tv_input`, `tv_interactive_app` and `tv_ad`, and no
  `hdmi_control`. No TV input or HDMI-CEC HAL service is registered, and
  `ro.hdmi.device_type` is `4` (playback device), as in 10 above.
  `dumpsys tv_input` lists no hardware and no inputs. Nothing can be
  switched on without a writable system image.
- Installed: Live TV (`com.android.tv`, `/product/priv-app/LiveTv`,
  version "1.24-asop"), TvProvider (`com.android.providers.tv`), TvSettings
  and the classic home (`com.google.android.tvlauncher` 7.4.10). Google's
  `com.google.android.tv`, which the ATV overlay names as the receiver of the
  Inputs key (9.1), is not installed; `input keyevent KEYCODE_TV_INPUT`
  showed nothing.
- `ACTION_VIEW` on a passthrough URI resolves, with TvProvider's type
  `vnd.android.cursor.item/channel`, to `com.android.tv/.MainActivity`
  only. Started with an unknown input ID, it starts and finishes at once
  (6.2), leaving the home in front. Without the type there is no match.
- `com.android.tv.action.VIEW_INPUTS` has two exported handlers with no
  permission: `com.android.tv/.SelectInputActivity` and
  `com.google.android.tvlauncher/.inputs.InputsPanelActivity`, both in
  system apps. An implicit start would show the chooser (9.2):
  `cmd package resolve-activity` answers
  `android/com.android.internal.app.ResolverActivity`, so neither is
  preferred. `com.amazon.tv.action.NAVIGATE_TO_INPUTS` has no handler.
- Started by component on this image, which has no inputs,
  `InputsPanelActivity` opens a panel titled "Inputs" on the right of the
  screen, over what was in front, in a task of its own; Back closes it. The
  panel has no rows, or one "Channels" row while an app's tuner input is
  installed (the test APK's, below). `SelectInputActivity` starts and finishes at once, leaving the
  home in front.
- With the launcher's Home button redirect on and the classic home as the
  stock home, the panel opened from the launcher stays in front over it, and
  Back returns to the launcher: the panel is not one of the stock home
  activities the redirect replaces.
- The shell can `pm disable-user --user 0` both `com.android.tv` and
  `com.google.android.tvlauncher`; with both off, `VIEW_INPUTS` has no
  handler.
- A `TvInputService` declared in the launcher's instrumentation APK
  (`BIND_TV_INPUT`, `<tv-input />` metadata) is listed by the real
  `getTvInputList()` as `TYPE_TUNER`, not passthrough, state `CONNECTED`,
  with its service label. `loadIcon` returns the service's icon (the
  fallback in 4.3).
- From the launcher (targetSdk 36), `loadCustomLabel` and `isHidden` return
  what `adb shell settings put secure tv_input_custom_labels` and
  `tv_input_hidden_inputs` store, in the format of 4.4, with no
  `SecurityException`.
- `pm disable-user` and `pm enable` of that APK's package make the real
  `TvInputCallback` report the input gone and back. The shell may not
  disable a single component of a package that is not test-only ("Shell
  cannot change component state").
- The API 31 `android-tv` image (also a `user` build) has no Live TV: nothing
  handles the passthrough intent, so `startActivity` throws
  `ActivityNotFoundException`, and `VIEW_INPUTS` has one handler,
  `com.google.android.tvlauncher/.inputs.InputsPanelActivity`.

## 11. Open questions for real hardware

On each TV (Sony, TCL, Hisense, Philips, a Fire TV Edition set):

1. `adb shell dumpsys tv_input`: input IDs, types, parent IDs of CEC
   devices, and states with sources on, in standby and unplugged.
2. Whether `loadLabel` and `loadIcon` return null without the
   `<queries>` entry for `android.media.tv.TvInputService` on API 30+, and
   what they return with it.
3. Whether `loadCustomLabel` and `isHidden` reflect what Settings > Inputs
   shows, or whether the vendor keeps its own names elsewhere.
4. `cmd package query-activities --brief -a android.intent.action.VIEW -t
   vnd.android.cursor.item/channel`, and whether `startActivity` with the
   passthrough URI switches the TV, shows a chooser, or lands somewhere else.
5. After a switch: what Home and Back do, and whether the launcher comes back
   in front.
6. Whether `onInputStateChanged` fires on HDMI hot-plug and on a CEC device
   entering standby, without polling.
7. `cmd package query-activities --brief -a com.android.tv.action.VIEW_INPUTS`,
   and whether `com.android.tv.settings/.system.InputsActivity` exists and
   opens.
8. On Google TV TVs: which app answers `VIEW_INPUTS` and the passthrough
   intent (Google's `launcherx`, the vendor's TV app, or Live Channels).
9. Fire TV Edition: whether the passthrough intent still lands in Live TV
   settings on current Fire OS 7, 8 and 14 builds; whether
   `com.amazon.tv.action.NAVIGATE_TO_INPUTS` opens an inputs screen for an
   app, which activity answers it, and whether it stays on screen with the
   launcher's Home button redirect on; whether `VIEW_INPUTS` has a handler;
   whether a `TvView` plays HDMI without the CEC routing being a problem.
10. Fire TV Cube (3rd gen): whether HDMI IN is in `getTvInputList()`, with
    which type, and what the passthrough intent does; whether
    `InputChooserActivity` is exported with no permission, so that an app
    can start it, and whether choosing HDMI IN there switches to it.
11. On a Chromecast with Google TV, Google TV Streamer, Shield and Fire TV
    Stick: confirm the list holds no passthrough inputs, so the menu can be
    left out there; which system activities answer `VIEW_INPUTS` or
    `NAVIGATE_TO_INPUTS` and what they show (the button stays hidden on the
    boxes, and shows on a Fire TV Stick where one of Amazon's resolves); on
    a Google TV Streamer, whether its picker switches the TV's input over
    HDMI-CEC, which would be a reason to show the button there.
12. On a TV, the time from choosing an input in the menu to the picture,
    for the intent and for a `TvView`.

## 12. What the launcher does

The Inputs button and menu (docs/LAUNCHER.md, Inputs) follow from the
sections above:

- **List**: `getTvInputList()`, keeping inputs that are
  `isPassthroughInput()` and not `isHidden` (3, 4.1). The tuner is left
  out; Live TV opens from its own tile. Nothing needs a permission (5).
  Where `getSystemService` returns null there are no inputs (2).
- **Names**: `loadCustomLabel` when it is set and not blank, otherwise
  `loadLabel` (4.3, 4.4). A `SecurityException` or `ArrayIndexOutOfBoundsException`
  from the settings read falls back to `loadLabel`. Rozan already holds
  `QUERY_ALL_PACKAGES` for the Home button redirect, so the vendor's input
  service is visible to `loadLabel` and `loadIcon` without the `<queries>`
  entry of 4.3.
- **Icons**: only an input's own icon. When `loadIcon` falls back to the
  service's icon (4.3), every port would show the vendor TV app's icon, so
  that one is left out. Both load through the package manager's icon cache
  and share a `ConstantState`, which is how the fallback is recognised
  (tested on the emulator with the test input of 10.1 only).
- **Order**: by connector (HDMI, DisplayPort, DVI, component, composite,
  S-Video, SCART, VGA, other), then by the system's label with numbers in
  numeric order. Unlike Live TV's picker (7.1), state does not reorder rows,
  so a row does not move while the list is open, and renaming a port does
  not move it. A CEC device (`getParentId()`) comes right after its port,
  with the port's name as a second line. The port stays listed, because
  `isConnectedToHdmiSwitch()` is a system API (4.2, 7.1).
- **States**: `CONNECTED_STANDBY` shows "Standby" and `DISCONNECTED` shows
  "Not connected"; both are greyed but stay selectable, unlike a
  disconnected input in Live TV's picker (7.1), since CEC devices often
  report standby while they work.
- **Updates**: `registerCallback` with all five events while the home
  screen is started; a fresh read when it resumes (a stopped home
  misses nothing it needs) and when the menu opens, which covers the gap in
  4.4. No polling or timers.
- **Switching**: `ACTION_VIEW` on `buildChannelUriForPassthroughInput(id)`
  (6), sent by component to each exported system app that resolves it with
  TvProvider's type, then as the plain intent, so another app that claims
  the type (6.3) is not picked over the TV app. On `ActivityNotFoundException`
  the menu says so and offers the TV's own list, found as in the next point.
- **The TV's own picker**: the button's mode is Rozan's menu where the list
  above has an input (never on Fire OS, below); otherwise, on Fire OS only,
  the system's own picker, where one may be opened; otherwise no button.
  Off Fire OS the pickers are offered only from the menu, after a failed
  switch. The candidates, in order (9.2):
  1. `com.android.tv.action.VIEW_INPUTS`, which Live TV, vendor pickers and
     the classic home answer (7.1, 8.1, 9.2, 10.1).
  2. `com.amazon.tv.action.NAVIGATE_TO_INPUTS` in `com.amazon.tv.launcher`
     (Fire TV Edition, **UNCONFIRMED**).
  3. `com.amazon.tv.inputpreference.service/com.amazon.tv.inputpreference.player.InputChooserActivity`
     (Fire TV Cube, **UNCONFIRMED**), started like `am start -n`, with no
     action.

  An activity counts only if it is in a system app (`FLAG_SYSTEM`, so the
  button never opens an app that merely claims the action), exported, and
  either needs no permission (`ActivityInfo.permission`, which already holds
  the application's permission when the activity names none) or one the
  launcher holds. Every picker is started by component, so the chooser of
  9.2 never appears. Of several system handlers of one action, the one that
  `resolveActivity` names first (a vendor's priority or a preferred
  activity), then the stock home's, then the package manager's order; on the
  emulator that is the classic home's panel (10.1). `<queries>` entries name
  both actions and the Cube's package, though `QUERY_ALL_PACKAGES` already
  makes them visible. TvSettings' `InputsActivity` (7.2) is left out: it
  renames and hides inputs but cannot switch to one, so a button that opens
  it would not do what it says.
- **When a picker does not start**: `startActivity` is wrapped for
  `ActivityNotFoundException` and `SecurityException` (a permission the
  package manager did not show, an app that went away). The next candidate
  is tried; if none starts, the launcher says "This TV doesn't let Rozan open
  its input list." (a toast from the button, the menu's message line from
  the menu), and the pickers that failed are skipped until a package is
  added, removed or changed, so the button goes away rather than failing
  again.
- **Looking again**: inputs and pickers are looked up off the main thread
  when the home screen resumes, on `TvInputCallback` events, when the menu
  opens, and on `ACTION_PACKAGE_ADDED`, `REMOVED` and `CHANGED` (the home
  screen's existing receiver; `pm disable-user` and `pm enable` send
  `CHANGED`). No polling or timers.
- **The Home button redirect**: Fire OS 8 draws the inputs view that
  `NAVIGATE_TO_INPUTS` opens inside `HomeActivity_vNext`
  (home-button-override.md 2.1), which the redirect would replace with
  Rozan. Before starting a picker, the launcher tells the redirect process
  (`O` in home-button-override.md 6.4), which then holds the stock home
  that comes next, and takes it back if nothing started.
- **Sticks and boxes**: no passthrough inputs (8.2), so never the menu, and
  there is no public API to switch the TV's input from them (8.3). The
  button stays hidden there even where a system picker resolves. Off Fire
  OS, the pickers that answer `VIEW_INPUTS` (AOSP Live TV's
  `SelectInputView`, 7.1, and the classic home's panel) read the same
  `getTvInputList()` as Rozan, so where Rozan finds no passthrough input
  they are empty too: on the emulator, which is set up as a box, the panel
  lists nothing and Live TV's picker starts and finishes at once (10.1). A
  button there would do nothing on the Shield and other boxes. This is a
  decision that one line flips (`fireOs &&` in `InputsButtonMode.of`)
  should a box turn out to have a picker that switches the TV over CEC,
  such as possibly the Google TV Streamer (11). A Fire TV Stick is Fire OS
  and follows the next point.
- **Fire OS** (`amazon.hardware.fire_tv`: Fire TV sticks, the Cube, Fire TV
  Edition and Omni TVs): never the menu. The passthrough intent is reported
  to land in Live TV's settings on Fire OS 6, 7, 8 and 14, and Amazon's own
  switcher needs `TUNE_INPUT` (8.4), so the menu could not switch anything.
  Rozan never has inputs of its own there, so the TV's own list is the only
  way: the button opens Amazon's inputs view or the Cube's chooser (or a
  `VIEW_INPUTS` handler) where one of them resolves as above; both Amazon
  routes are **UNCONFIRMED** (11). The flag is the system feature that
  `HomeSetupReader.isFireOs` reads. The one reported
  route for switching inside an app is a `TvView` in its own activity (6.4,
  8.4), which would make Rozan the player of the HDMI picture without the
  CEC routing; it is not built.
- **Tests**: on the API 36 emulator, the real `TvInputManager` path (list,
  custom name, hidden flag, callbacks, the service-icon rule) runs against
  the test input of 10.1. The passthrough intent is sent through the real
  resolver to Live TV, and, with Live TV disabled (API 36) or absent (API
  31), fails for real and opens the classic home's input panel. Told
  through a debug-only hook that it runs on Fire OS, the home screen's
  button opens the emulator's real picker by component, disappears when
  both pickers' apps are disabled with `pm disable-user` and comes back when
  they are enabled; without the hook, the button stays hidden although both
  pickers resolve. A picker that the package manager lists but no app has makes
  the start fail for real: the toast shows, the button goes, stays gone
  after the home screen resumes, and comes back after a package change. With
  the redirect on and a test activity standing in for the stock home, a
  picker drawn by the stock home is not replaced. The menu itself (rows,
  states, focus, live changes), the picker order and the permission and
  export rules run against fakes, installed through hooks that only debug
  builds have, and as JVM tests.
