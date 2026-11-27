# Development

How to build Rozan, what each module holds, how it is tested, and where the
deeper developer docs are. The rules for contributors, human or not, are in
[AGENTS.md](../AGENTS.md).

## Requirements

JDK 21 and the Android SDK, with `ANDROID_HOME` pointing at it. On a Mac
with Homebrew:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

## Commands

| Task | Command |
| --- | --- |
| Unit tests, Android Lint, ktlint (through Spotless), detekt and the debug APKs. A change is not done until this passes. | `./gradlew check assembleDebug` |
| Format the code | `./gradlew spotlessApply` |
| One module's JVM tests | `./gradlew :airplay:core:test` |
| Instrumented tests; needs a running emulator or device | `./gradlew connectedDebugAndroidTest` |
| Release APKs | `./gradlew assembleRelease` |
| Run the dev receiver on this computer | `./gradlew :tools:devserver:run --args="--name 'Dev Receiver'"` |

Lint warnings and compiler warnings are errors. Without the signing
variables in [RELEASING.md](RELEASING.md), `assembleRelease` signs with the
debug key, so the APKs can still be installed for testing.

## Modules

| Module | Contents |
| --- | --- |
| `:airplay:core` | AirPlay protocol: discovery records, RTSP/HTTP, binary plist, TLV8, pairing, sessions, stream decryption, timing. No Android APIs. |
| `:airplay:fairplay` | FairPlay v2/v3, receiver side, written from [protocol/04-fairplay.md](protocol/04-fairplay.md). |
| `:airplay:alac` | Apple Lossless decoder, a port of Apple's Apache-2.0 reference decoder. |
| `:airplay:android` | Receiver service, mDNS through `NsdManager`, `MediaCodec`/`AudioTrack` output, the code prompt, the mirroring screen, wake handling, `AirPlaySettings`. |
| `:airplay:testing` | A scripted sender for end-to-end tests. Not shipped. |
| `:ui` | Shared TV views and theme: colours, focusable tiles, settings rows. |
| `:app:launcher` | The home screen app, `ai.aneesiqbal.rozan.launcher`. |
| `:app:airplay` | The standalone receiver app, Rozan AirPlay (`ai.aneesiqbal.rozan.airplay`). |
| `:tools:devserver` | The receiver on a desktop JVM, for testing with a real Mac or iPhone. Not shipped. |
| `:benchmark`, `:benchmark-airplay` | Macrobenchmarks and baseline profiles for the two apps ([PERFORMANCE.md](PERFORMANCE.md)). |
| `build-logic/` | Gradle convention plugins shared by the modules. |

Dependencies point downwards only: the apps depend on `:airplay:android`
and `:ui`, which depend on the pure Kotlin/JVM modules `:airplay:core`,
`:airplay:fairplay` and `:airplay:alac`.
[ARCHITECTURE.md](ARCHITECTURE.md) explains how they fit together, the
threads, and the contract between the core and the platform.

## Tests

- JVM tests use JUnit 6 (Jupiter) with `kotlin.test` assertions.
- Every parser is tested with valid, truncated, oversized and random input.
  Crypto and codec code is tested against published vectors, or against the
  vectors in [protocol/vectors/](protocol/vectors/).
- `:airplay:core`'s end-to-end test runs the test sender from
  `:airplay:testing` against the receiver in the same process.
- `:airplay:android`'s `SenderEndToEndTest` runs the test sender against the
  real receiver service on an Android TV emulator, over 127.0.0.1:7000. It
  reads the code off the screen, pairs, runs FairPlay and streams video and
  audio.
- The launcher's inputs tests (`inputs` package) run the TV input code
  against the emulator's real `TvInputManager`, with a `TvInputService` that
  the test APK declares; an app's input is never a passthrough input, so the
  menu itself runs against a fake list of HDMI inputs. `InputPickerTest`
  tells the home screen it runs on Fire OS, opens the emulator's real
  system input lists from the button, switches their apps off and on with
  `pm disable-user`, and uses a fake package manager answer for a list that
  will not open; without that, it checks the button stays hidden. Only debug
  builds can take those fakes (`src/tvInputsDebug`; every other build compiles
  `src/tvInputsRelease`). `InputsScreenshots` saves screenshots of the menu
  in English and Russian, and of the button opening the system's list, when
  run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`.
- `scripts/emulator-gamepad.sh A` (or B, X, Y, SELECT, START, RIGHT, DOWN)
  presses a button on a virtual USB game controller on an emulator or
  device. The key goes through Android's standard controller key map, as
  from a real controller; `adb shell input` uses a different map for X and
  Y.
- Leak tests, memory and APK size budgets, and how to profile are in
  [PERFORMANCE.md](PERFORMANCE.md).

## Testing automatic updates

Debug builds can point the updater at a server on the computer instead of
GitHub, set from adb (`DebugEventsReceiver`, `UPDATE_URL`; it stays set
until cleared). Only debug builds allow plain HTTP, and only to `127.0.0.1`
(`src/debug/res/xml/network_security_config.xml`); `adb reverse` maps that
address on the device to the computer. Both debug APKs below are signed with
the same debug key, so one installs over the other.

```sh
P=ai.aneesiqbal.rozan.launcher
./gradlew :app:launcher:assembleDebug
adb install -r app/launcher/build/outputs/apk/debug/launcher-debug.apk
adb shell appops set $P REQUEST_INSTALL_PACKAGES allow

# A newer version to offer: -Prozan.version overrides gradle.properties.
mkdir -p /tmp/rozan-update
./gradlew :app:launcher:assembleDebug -Prozan.version=0.1.1
cp app/launcher/build/outputs/apk/debug/launcher-debug.apk /tmp/rozan-update/rozan-launcher-0.1.1.apk
python3 scripts/update-test-release.py /tmp/rozan-update 0.1.1 8765
python3 -m http.server 8765 --bind 127.0.0.1 --directory /tmp/rozan-update &

adb reverse tcp:8765 tcp:8765
adb shell am broadcast -f 0x20 -p $P -a $P.debug.UPDATE_URL --es url http://127.0.0.1:8765/latest.json
```

Then turn on "Update automatically" in Rozan's settings. The server's log
shows `latest.json`, `SHA256SUMS` and the APK being fetched, and
`adb logcat -s RozanUpdate` shows the decision (`update 0.1.1:
WAIT_FOR_SCREEN` while the settings are open). On API 31 and later the
update installs once no Rozan screen is started: open another app
(`adb shell am start -a android.settings.SETTINGS`) or put the device to
sleep (`adb shell input keyevent KEYCODE_SLEEP`). Pressing Home does not
count while Rozan is the home screen. `adb shell dumpsys package $P | grep
versionName` then reads 0.1.1. The same broadcast without `--es url` goes
back to GitHub.

## Translations

Strings are translated into zh-Hans, fr, it, ja, pt-BR, ru and es
(`values-b+zh+Hans`, `values-fr`, ...). Lint fails on a missing or extra
translation, a missing plural quantity or a format mismatch, so a new string
needs all seven translations, or `translatable="false"` for brand names and
commands. `AppLanguage` in `:airplay:android` applies the app language:
`LocaleManager` from API 33, and before that a file shared by both processes
plus context wrapping.

## The dev receiver

`:tools:devserver` runs the receiver's protocol code on a desktop JVM, which
is the way to test against a real Mac or iPhone without a TV. It prints the
code in the terminal and writes the mirrored video and the audio to files.

```sh
./gradlew :tools:devserver:run --args="--name 'Dev Receiver'"
```

Options: `--name`, `--port` (47000 by default, since macOS's own AirPlay
receiver holds 7000 and 5000), `--mode pin` or `--mode digest`, and `--out`
for the output directory. A Mac does not list a receiver that runs on
itself, so run it on a second computer. [INTEROP.md](INTEROP.md) has the
setup, the checks to run with real devices, and the results so far.

## CI

`.github/workflows/ci.yml` runs on pushes to `master` and `development` and on
every pull request:

- Lint, unit tests, build: `./gradlew check assembleDebug assembleRelease`,
  the tests of the budget checker, and the APK size budgets.
- Instrumented tests on Android TV emulators at API 31 and API 36.
- Memory budgets on an API 36 emulator.
- Workflow lint with actionlint and zizmor.

`.github/workflows/release.yml` builds and publishes a release when a
`vX.Y.Z` tag is pushed. [RELEASING.md](RELEASING.md) covers cutting a
release and the signing key.

## Dependencies

The runtime dependencies are the Kotlin standard library,
kotlinx-coroutines, a few AndroidX libraries and BouncyCastle (pure Java).
Rozan ships no native libraries; video and AAC decoding use the device's
own `MediaCodec` decoders. Every downloaded artifact is pinned by SHA-256 in
`gradle/verification-metadata.xml`. After adding or bumping a dependency,
run `scripts/update-verification-metadata.sh` and review the diff.
[SECURITY.md](../SECURITY.md) explains why, and AGENTS.md has the rules for
new dependencies. A library that ends up in the launcher's APK also needs an
entry in `notices/OpenSourceNotices.kt` in `:app:launcher`, with its licence
text in `res/raw`, so that it appears under "Open-source notices".

## Backdrop photos

The backdrops are in `app/launcher/src/main/assets/backdrops/`, one entry
per photo in `manifest.json` there (title, author, licence, source page,
original file URL, SHA-256 of the processed JPEG). The credits and licences
are in [BACKDROPS.md](BACKDROPS.md); this section records how the photos
were chosen and processed, for replacing or adding one.

### Selection criteria

- Natural landscapes only: mountains, lakes, coast, forest, desert, canyons,
  glaciers, aurora, a meadow, a waterfall and the night sky. No people,
  animals in focus, buildings, roads, vehicles, fences, text, logos,
  watermarks or borders, and no heavy HDR or oversaturated processing.
  Candidates were rejected for a hiker under a waterfall, a road below a
  mountain pass, a fence on a cliff top, a lighthouse on a distant island
  and a staircase beside a waterfall. Two photos were cropped to leave out
  buildings near the horizon.
- A mix of colours, seasons and times of day, so that consecutive crossfades
  differ, with a calm overall look.
- The clock sits in the top-right corner and the app tiles sit on a dark
  gradient over the bottom third. Photos with a bright or busy top-right
  corner were passed over, and crops were chosen so the main subject or
  horizon still reads with the bottom third darkened.
- Licence: public domain or CC0 only. Every file comes from Wikimedia
  Commons, and its licence was checked through the Commons API
  (`extmetadata` fields `LicenseShortName`, `License`, `UsageTerms` and
  `Artist`). Two kinds of file qualify:
  - Works of US federal agencies (National Park Service, US Forest Service),
    tagged public domain on Commons.
  - Photos published on Unsplash before June 2017, while Unsplash released
    photos under CC0 1.0. Commons hosts copies tagged CC0 with a link to the
    Unsplash page.

  Files tagged CC BY, CC BY-SA, GFDL or with an unknown licence were not
  used. Credit is recorded for every photo even where the licence does not
  require it.
- Originals are at least 2400 px wide, so every 1920x1080 crop is a
  downscale.

### Processing

Each original was cropped to a 16:9 window chosen per photo (see the table
below), converted from its embedded ICC profile to sRGB when it had one,
downscaled with Lanczos, stripped of all metadata and saved as a baseline
JPEG with 4:2:0 chroma subsampling. Quality starts at 85 and steps down to
82 until the file is at most 450 KiB. No sharpening was applied.

```sh
# WxH+X+Y is the crop window in original pixels (table below).
# The -profile step runs only when the original embeds an ICC profile.
magick original.jpg[0] -auto-orient \
  -profile "/System/Library/ColorSync/Profiles/sRGB Profile.icc" \
  -crop WxH+X+Y +repage -colorspace sRGB -depth 8 \
  -filter Lanczos -resize 1920x1080! \
  -strip -interlace none -sampling-factor 4:2:0 -quality 85 \
  backdrop.jpg
```

`mossy-gorge-waterfall.jpg` also has `-define filter:blur=1.15` before
`-resize`. The photo is fine moss texture edge to edge and came out at
about 463 KiB at quality 82 without it. The slightly wider Lanczos kernel
brings it to 446 KiB with no visible change at 100%.

To check the processed files against the manifest:

```sh
cd app/launcher/src/main/assets/backdrops
shasum -a 256 *.jpg
```

| File | Original | Crop window | Quality | Size |
| --- | --- | --- | --- | --- |
| `bettmerhorn-glacier-sunrise.jpg` | 8196x5464 | 8196x4610+0+427 | 85 | 449 KiB |
| `cape-alava-sea-stack-fog.jpg` | 3200x2400 | 3200x1800+0+300 | 85 | 165 KiB |
| `coastal-cliffs-sunset.jpg` | 5190x3460 | 4463x2510+675+475 | 85 | 352 KiB |
| `crater-lake-milky-way.jpg` | 5965x2540 | 4516x2540+724+0 | 84 | 445 KiB |
| `desert-dunes-haze.jpg` | 4550x2560 | 3185x1791+1365+384 | 85 | 86 KiB |
| `fog-over-conifer-forest.jpg` | 3936x2624 | 3936x2214+0+205 | 85 | 261 KiB |
| `fog-over-rolling-hills.jpg` | 7020x3828 | 5308x2986+856+842 | 85 | 222 KiB |
| `grand-canyon-yavapai-sunset.jpg` | 2500x1416 | 2500x1406+0+5 | 85 | 187 KiB |
| `great-sand-dunes-sunset.jpg` | 8257x5505 | 8257x4645+0+430 | 85 | 228 KiB |
| `green-river-lakes-reflection.jpg` | 2516x1612 | 2516x1415+0+98 | 85 | 309 KiB |
| `la-sal-mountains-shafer-canyon.jpg` | 5184x3888 | 4147x2333+518+505 | 85 | 222 KiB |
| `lake-haiyaha-alpenglow.jpg` | 3008x2000 | 3008x1692+0+74 | 82 | 443 KiB |
| `lake-matheson-morning-fog.jpg` | 4811x3207 | 4811x2706+0+250 | 85 | 258 KiB |
| `lake-placid-blue-ridges.jpg` | 4135x2760 | 4135x2326+0+217 | 85 | 94 KiB |
| `logan-glacier-aerial.jpg` | 5650x3767 | 5650x3178+0+0 | 82 | 433 KiB |
| `mammoth-lakes-meadow-dusk.jpg` | 4800x3200 | 4800x2700+0+0 | 82 | 435 KiB |
| `mossy-gorge-waterfall.jpg` | 3500x2333 | 3500x1969+0+0 | 82 | 446 KiB |
| `mount-tukuhnikivatz-autumn.jpg` | 2660x1745 | 2660x1496+0+124 | 82 | 448 KiB |
| `northern-lights-over-forest.jpg` | 7360x4912 | 7360x4140+0+386 | 85 | 359 KiB |
| `rialto-beach-pastel-sunset.jpg` | 3200x2400 | 3200x1800+0+300 | 85 | 202 KiB |
| `snow-capped-peak-overcast.jpg` | 8277x5141 | 8277x4656+0+242 | 85 | 164 KiB |
| `teton-northern-lights.jpg` | 7807x5207 | 7807x4391+0+408 | 85 | 164 KiB |
| `windswept-valley.jpg` | 4288x2848 | 4288x2412+0+218 | 85 | 337 KiB |
| `yosemite-valley-fog.jpg` | 4928x3264 | 4928x2772+0+246 | 85 | 356 KiB |

## Protocol work

The AirPlay and FairPlay code is written from the specifications in
[protocol/](protocol/). When a spec turns out wrong or incomplete, fix the
spec first, citing what was observed, then the code.

## Other developer docs

- [ARCHITECTURE.md](ARCHITECTURE.md): layers, threads, the core and
  platform contract.
- [PERFORMANCE.md](PERFORMANCE.md): measured memory, startup, frames and
  CPU, the budgets CI enforces, and profiling recipes.
- [RELEASING.md](RELEASING.md): how a tagged release is built, signed and
  published.
- [INTEROP.md](INTEROP.md): testing with a real Mac or iPhone, and the
  results.
- [protocol/](protocol/): the AirPlay and FairPlay specifications.
- [research/](research/): device research, such as the Fire TV Home button.
- [CHANGELOG.md](../CHANGELOG.md): changes per version; user-visible changes
  go under Unreleased.
