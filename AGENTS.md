# AGENTS.md

Guidance for AI agents and humans working in this repository. Read it before
changing anything.

## What this is

Rozan is an Android TV / Fire TV launcher with a built-in AirPlay receiver
(screen mirroring and audio from macOS and iOS), installed by sideloading.
The same AirPlay modules also ship as a standalone receiver app.

- `ai.aneesiqbal.rozan.launcher`: the launcher app (`:app:launcher`)
- `ai.aneesiqbal.rozan.airplay`: the standalone receiver app (`:app:airplay`)

## Module map

| Module | Kind | Contents |
| --- | --- | --- |
| `:airplay:core` | Kotlin/JVM library | AirPlay protocol: discovery records, RTSP/HTTP, binary plist, TLV8, pairing, session state machine, stream parsing and decryption, timing. No Android APIs. |
| `:airplay:fairplay` | Kotlin/JVM library | Receiver-side FairPlay v2/v3, implemented from `docs/protocol/04-fairplay.md`. |
| `:airplay:alac` | Kotlin/JVM library | Apple Lossless decoder. |
| `:airplay:android` | Android library | Receiver service, mDNS (NsdManager), MediaCodec/AudioTrack sinks, PIN overlay, mirror activity, wake handling, `AirPlaySettings`. |
| `:ui` | Android library | Shared TV UI: theme, colors, focusable tiles, settings rows. |
| `:app:launcher` | Android app | Home screen, app grid, backdrops, screensaver, launcher settings, Fire TV home-button handling. |
| `:app:airplay` | Android app | Standalone receiver skin. |
| `:tools:devserver` | JVM app | Runs the receiver on a desktop JVM for interop testing with a real Mac. Not shipped. |
| `build-logic/` | Included build | Convention plugins (`rozan.jvm.library`, `rozan.android.library`, `rozan.android.application`, `rozan.quality`). |

Dependencies point downwards only: apps → `:airplay:android`/`:ui` → `:airplay:core`, `:airplay:fairplay`, `:airplay:alac`.

## Commands

Environment (macOS dev machine): JDK 21 and the Android SDK.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

| Task | Command |
| --- | --- |
| Everything CI runs | `./gradlew check assembleDebug` |
| Format code | `./gradlew spotlessApply` |
| One module's JVM tests | `./gradlew :airplay:core:test` |
| Release APKs | `./gradlew assembleRelease` |
| Instrumented tests (emulator running) | `./gradlew connectedDebugAndroidTest` |

`check` runs unit tests, Android Lint (warnings are errors), ktlint (via
Spotless) and detekt. A change is not done until `./gradlew check
assembleDebug` passes.

## Rules

### Safety of untrusted input
- Every byte that arrives from the network is hostile until pairing succeeds,
  and still untrusted after. Parsers must bound every length, never allocate
  from an attacker-supplied size without a cap, and fail with an exception
  that closes the connection, never with a crash of the process.
- All parsing, crypto glue and codecs are Kotlin. Do not add native code, JNI,
  or dependencies that contain native code or parse media/containers. Native
  code is reached only through Android platform APIs (MediaCodec, AudioTrack,
  Conscrypt via JCA).
- Third-party dependencies need a reason. Today the runtime set is: Kotlin
  stdlib, kotlinx-coroutines, AndroidX (core, annotation, tracing,
  profileinstaller) and BouncyCastle (pure Java; used for X25519/Ed25519 that
  API 28-32 lack). Anything new must be pure JVM and must be justified in the
  commit message.
- Every downloaded artifact is pinned by SHA-256 in
  `gradle/verification-metadata.xml`; a build that resolves anything not in
  it fails. After adding or bumping a dependency or plugin (including
  Dependabot's pull requests), run `scripts/update-verification-metadata.sh`
  and review the diff: only the artifacts you changed should move.

### Protocol work
- The AirPlay and FairPlay implementations are written from the specs in
  `docs/protocol/` only; do not read or copy other receivers' source code.
  If a spec is wrong or incomplete, fix the spec (citing the observed
  behaviour or public documentation), then the code.
- Protocol constants and wire strings come from the spec, verbatim.

### Kotlin style
- Library modules use explicit API mode; keep the public surface small and
  documented (KDoc on every public declaration).
- Compiler warnings are errors. Do not suppress a warning without a comment
  that says why.
- Prefer immutable data, `sealed` hierarchies for protocol messages and states,
  and value classes for units (e.g. RTP timestamps vs. NTP times).
- No reflection, no `!!` in production code, no `GlobalScope`.
- Hot paths (video packets, audio frames, draw calls) do not allocate per
  packet or per frame: reuse buffers, avoid boxing and lambdas there.

### Android
- `minSdk` 28 (Fire OS 7), `targetSdk` 36. Guard newer APIs with
  `Build.VERSION.SDK_INT` checks; Lint enforces this, including for the JVM
  modules through `checkDependencies`.
- UI is plain Android Views drawn with hardware acceleration. No Compose, no
  AppCompat, no Leanback. Animate with RenderNode-backed view properties
  (`alpha`, `translationX/Y`, `scaleX/Y`), not by re-laying out.
- Decode bitmaps at display size with `ImageDecoder` and
  `ALLOCATOR_HARDWARE` where they are only drawn.
- Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (reduced motion).
- No time-based hacks: never "wait N ms then do X" to work around system
  behaviour. React to events (intents, accessibility events, key events,
  callbacks).

### Tests
- JVM tests use JUnit 6 (Jupiter) with `kotlin.test` assertions.
- Every parser gets tests for valid input, truncated input, oversized length
  fields and random garbage.
- Crypto and codec code is tested against published vectors (RFCs) or vectors
  in `docs/protocol/vectors/`.

### Git
- Work on `development` or a branch off it. Never commit to `master` directly.
- No merge commits: rebase, then fast-forward.
- Commit messages: imperative subject under 72 chars, body explains why.
- Update `CHANGELOG.md` (Unreleased section) for user-visible changes.

## Docs

`README.md` is a short overview with screenshots that links to the subject
docs. It and the user docs describe the product as it is; the depth on a
subject lives in that subject's doc. Dated changes, test results and full
limit lists belong in `CHANGELOG.md`, `docs/INTEROP.md` and `SECURITY.md`,
not in the README.

For users:
- `docs/INSTALL.md`: supported devices, installing with adb, the Home button
  redirect, recovery.
- `docs/LAUNCHER.md`: the home screen, every launcher setting, the
  screensaver and backdrops.
- `docs/AIRPLAY.md`: using the receiver and Rozan AirPlay, user-facing
  limits, what has been tested, known issues.
- `docs/BACKDROPS.md`: sources, licences and processing of the backdrop photos.
- `SECURITY.md`: threat model, every receiver limit, reporting a vulnerability.

For developers:
- `docs/DEVELOPMENT.md`: building, modules, tests, the dev receiver, CI.
- `docs/ARCHITECTURE.md`, `docs/PERFORMANCE.md`, `docs/RELEASING.md`.
- `docs/INTEROP.md`: checks to run with a real Mac or iPhone, the dev
  receiver setup, and the results.
- `docs/protocol/`: AirPlay/FairPlay specifications used by the implementation.
- `docs/research/`: device research (e.g. Fire TV home-button behaviour).
