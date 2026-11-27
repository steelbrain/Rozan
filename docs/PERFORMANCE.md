# Performance

Rozan has to run at full frame rate on the slowest Fire TV still sold, the
Fire TV Stick Lite: four Cortex-A35 cores, a PowerVR GE8300 GPU, 1 GB of RAM
shared with the GPU, and a 1080p UI. This document covers how startup,
frames, memory, CPU and APK size are measured, the numbers measured so far,
what fails the build, where the time and memory go, and what to optimise
next.

No Fire TV or Android TV hardware has been available yet. Everything below
was measured on Android TV emulators: the Google arm64 TV system images for
API 36 (Android 16) and API 31 (Android 12), 1920x1080 at 320 dpi, 4 cores
and 2 GB of RAM, on an Apple silicon Mac. Memory and APK size mostly carry
over to devices, with the corrections in
[Emulator caveats](#emulator-caveats); timing does not.

## Tools at a glance

| What | Command | Gated in CI |
| --- | --- | --- |
| Startup, frame timing, offscreen layers, memory (Macrobenchmark) | `./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest` | no |
| Same for the standalone AirPlay app | `./gradlew :benchmark-airplay:connectedBenchmarkReleaseAndroidTest` | no |
| Baseline profiles | `./gradlew :app:launcher:generateBaselineProfile` and `:app:airplay:generateBaselineProfile` | no (committed output) |
| One process's memory and frame stats as JSON | `scripts/perf/meminfo.sh <package> [process]` | used by the next row |
| Memory scenarios, both processes | `scripts/perf/memory-scenarios.sh --apk <launcher-benchmarkRelease.apk>` | yes, `Memory budgets (API 36)` job |
| Budget check | `scripts/perf/check-budgets.py <results.json>` or `--apk name=path.apk` | yes |
| Leak tests | `LeakTest` (launcher), `SessionLeakTest` (`:airplay:android`), part of `connectedDebugAndroidTest` | yes, both emulator jobs |
| Traces, heap profiles, CPU profiles | Perfetto, heapprofd, `java_hprof`, simpleperf ([recipes](#profiling-recipes)) | no |
| CPU time and wakeups per thread | Perfetto scheduler trace and one query ([recipe](#profiling-recipes), results in [CPU](#cpu)) | no |

On an emulator, add
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`
to the benchmark commands. Set `ANDROID_SERIAL` when more than one device is
connected; the connected tasks otherwise run on all of them. Add
`-Pandroid.testInstrumentationRunnerArguments.class=ai.aneesiqbal.rozan.benchmark.HomeFramesBenchmark`
to run one class.

Every Macrobenchmark iteration leaves a Perfetto trace next to the results
(`benchmark/build/outputs/connected_android_test_additional_output/`). Open
them in [ui.perfetto.dev](https://ui.perfetto.dev) or query them with
`trace_processor_shell`.

## Builds that are measured

All measurements use release code. The baseline profile plugin adds two
build types to each app, both copies of `release` (R8, resource shrinking,
not debuggable, signed like release, so with the debug key unless the
release keystore is configured):

- `benchmarkRelease`: what Macrobenchmark and the memory scenarios run. It is
  `profileable` from the shell, so Perfetto, heapprofd and simpleperf work
  without root.
- `nonMinifiedRelease`: what the profile generators run (R8 later rewrites
  the profile for the minified release).

`:airplay:android` defines the same two build types and compiles its debug
event hooks (`src/debug`, the `DebugEventsReceiver` that fakes a PIN prompt,
a mirroring session with the bundled 720p H.264 clip, or an audio session
with artwork) into them. The receiver needs the `DUMP` permission, which
only the adb shell holds. `release` APKs never contain the hooks.

The launcher compiles its own `src/debug` into the same build types: a
`DebugEventsReceiver` (also `DUMP`-protected) that pins one backdrop photo
(`BACKDROP`), and a `DebugOverridesProvider` that applies the pin when the
process starts. It is stored, so it holds across the process restarts of a
benchmark run:

```sh
P=ai.aneesiqbal.rozan.launcher
adb shell am broadcast -f 0x20 -p $P -a $P.debug.BACKDROP --es file fog-over-rolling-hills.jpg
adb shell am force-stop $P
```

The same broadcast without its `--es` extra goes back to shuffled photos.
(An empty `""` does not work from the host: `adb shell` joins the arguments
into one command line and the empty one is lost.) `-f 0x20` also reaches a
force-stopped app.

## Baseline profiles

`BaselineProfileGenerator` (`:benchmark`) and
`AirPlayAppBaselineProfileGenerator` (`:benchmark-airplay`) drive each app
through its main paths and write the rules to
`app/<app>/src/release/generated/baselineProfiles/`, which is committed. The
launcher's covers startup (also kept as the startup profile, which R8 uses to
put startup classes first in the primary dex), browsing the grid, the
settings screen, the options panel, the screensaver, and, in the `:airplay`
process, the PIN overlay, the mirroring screen with its decoder path and the
now-playing screen. The AirPlay app's covers its status screen and the same
three session screens. Rules for the debug hooks' own classes are filtered
out.

Regenerate after changing startup or screen code, with one API 33+ device or
emulator connected (older ones need root):

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:launcher:generateBaselineProfile :app:airplay:generateBaselineProfile
```

`androidx.profileinstaller` (a dependency of both apps) installs the profile
on first launch of a sideloaded APK: it writes it where ART looks for it, and
the next background dexopt job (run while the device is idle) compiles those
methods ahead of time; `adb shell cmd package compile -m speed-profile -f
<package>` does it at once. Until then the app runs interpreted and JIT
compiled: a CPU profile of grid navigation right after install shows the
launcher's own code in ART's interpreter (`nterp`). The startup benchmark
runs with `BaselineProfileMode.Require`, which fails if profileinstaller
cannot install the profile, so a broken install shows up as a failing
benchmark.

On the emulators the profile does not help startup (see
[Startup](#startup)). On API 36 both modes start within run-to-run noise
of each other; on API 31 the profiled build started slower in every run so
far (initial display 130 against 123 ms, then 133 against 115 ms). The host
CPU runs the interpreter and the JIT fast, so neither result says much about
Cortex-A35 cores: whether the profile pays off there, and whether the API 31
slowdown is real, has to be measured on a device.

## Numbers

Measured on 2026-11-26 on `development` at b18fcad plus the changes that
added this document; a scenario run on both emulators at 4888c2d (later
protocol and audio clock work) still met every budget.

Medians. Memory is PSS in MB (1 MB = 1024 KB) from `dumpsys meminfo`, taken
by `memory-scenarios.sh` (two runs) after a GC (see
[How memory is measured](#how-memory-is-measured)). "Graphics buffers" is
the estimate of graphic buffer memory explained in
[Emulator caveats](#emulator-caveats); it is not part of PSS on an emulator
and is not listed by API 31's `gfxinfo`.

### Memory, API 36

| Scenario | Process | PSS | Java heap | Native heap | Code | Graphics buffers |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Home, idle | launcher | 41.4 | 3.3 | 24.8 | 2.9 | 33.5 |
| | `:airplay` | 10.1 | 1.2 | 1.6 | 0.3 | 0 |
| Home, after browsing | launcher | 39.8 | 1.4 | 24.9 | 3.0 | 33.5 |
| | `:airplay` | 9.9 | 1.2 | 1.6 | 0.3 | 0 |
| Screensaver | launcher | 39.8 | 1.4 | 25.0 | 3.0 | 33.5 |
| | `:airplay` | 9.9 | 1.2 | 1.7 | 0.3 | 0 |
| Mirroring (720p) | launcher | 38.5 | 1.4 | 24.9 | 1.4 | 9.8 |
| | `:airplay` | 24.7 | 1.9 | 7.8 | 3.8 | 36.0 |
| Audio with artwork | launcher | 37.9 | 1.4 | 24.8 | 0.7 | 9.8 |
| | `:airplay` | 36.5 | 2.8 | 16.5 | 3.9 | 25.1 |

### Memory, API 31

| Scenario | Process | PSS | Java heap | Native heap | Code |
| --- | --- | ---: | ---: | ---: | ---: |
| Home, idle | launcher | 35.9 | 1.7 | 24.5 | 1.2 |
| | `:airplay` | 11.4 | 1.3 | 1.5 | 0.4 |
| Home, after browsing | launcher | 36.2 | 1.7 | 24.6 | 1.1 |
| | `:airplay` | 11.4 | 1.3 | 1.5 | 0.4 |
| Screensaver | launcher | 36.2 | 1.7 | 24.6 | 1.1 |
| | `:airplay` | 11.4 | 1.3 | 1.5 | 0.4 |
| Mirroring (720p) | launcher | 35.8 | 1.8 | 24.5 | 1.1 |
| | `:airplay` | 26.5 | 2.0 | 11.0 | 2.9 |
| Audio with artwork | launcher | 35.5 | 1.8 | 24.4 | 1.1 |
| | `:airplay` | 33.6 | 2.7 | 15.9 | 3.1 |

Peaks, from Macrobenchmark's Perfetto memory counters on API 36 (largest
value during the iteration, not after a GC): launcher anonymous RSS 73.3 MB
at idle home, Java heap size 3.7 MB; `:airplay` anonymous RSS 45.4 MB while
mirroring and 54.6 MB during audio.

### Memory, standalone AirPlay app, API 36

From `AirPlayAppMemoryBenchmark` (Macrobenchmark, three cold starts each,
same snapshot after a GC):

| Scenario | Process | PSS | Java heap | Native heap | Graphics buffers |
| --- | --- | ---: | ---: | ---: | ---: |
| Status screen | app | 22.2 | 2.8 | 8.0 | 23.7 |
| | `:airplay` | 9.5 | 1.1 | 1.5 | 0 |
| Mirroring (720p) | app | 21.3 | 2.8 | 8.0 | 0 |
| | `:airplay` | 23.2 | 1.8 | 7.8 | 37.8 |

### Startup

Macrobenchmark, 10 iterations each, median ms. Cold: process killed, home
started with the HOME intent. Warm: process alive, activity re-created.
"Full display" is `reportFullyDrawn()`, called once the app list is loaded
and the tiles are set.

| | API 36, profile | API 36, JIT only | API 31, profile | API 31, JIT only |
| --- | ---: | ---: | ---: | ---: |
| Cold, initial display | 117 | 119 | 133 | 115 |
| Cold, full display | 188 | 193 | 207 | 187 |
| Warm, initial display | 31 | 29 | 24 | 29 |

The API 31 column comes from a run of the startup benchmark alone; in a
run next to the API 36 suite on the same host, the profiled cold start
measured 211 ms (spread 133 to 288 ms), another sign of how much emulator
timing depends on the host's load.

The standalone AirPlay app's cold start to its status screen (API 36,
initial display): 108 ms with the profile, 113 ms JIT only.

### Frames

Macrobenchmark `FrameTimingMetric`, ms of UI plus render thread time per
frame (P50 / P90 / P99), and the number of offscreen layers HWUI created
(see [Hot spots](#hot-spots)).

| Benchmark (per iteration) | Frames | API 36 P50 / P90 / P99 | API 31 P50 / P90 / P99 | Offscreen layers |
| --- | ---: | ---: | ---: | ---: |
| Grid navigation, two passes | 282 | 2.2 / 3.3 / 19.5 | 2.8 / 3.8 / 18.5 | 0 (144 before the scrim fix) |
| First photo fading in after a cold start | 96 | 18.7 / 34.1 / 49.8 | 17.4 / 20.2 / 45.1 | 0 |
| Screensaver in and out | 177 | 2.1 / 18.8 / 33.5 | 2.8 / 32.8 / 35.0 | 158 (264 before) |

Frame counts are API 36's; the layer counts are the same on both.

The P50 of the photo crossfade (about 18 ms against 2 ms for navigation) is
not app work: in its traces the render thread waits 15.2 ms per frame on
average in `dequeueBuffer` for a free window buffer (0.04 ms during
navigation), so those frames are paced by the emulator's compositor, which
right after a cold start also runs the system's launch transition.
Run-to-run spread is large: the same launcher APK gave a crossfade P90 of
29.3 ms in one run and 19.6 ms in the next. That is why timing is recorded and not
gated.

### Tile styles

The tiles' glass edge (the bevel `TileBevel` in `:ui` bakes into each
tile's art) was picked from four
styles by the measurements below, taken on 2026-11-26 with builds that
could switch between them. After the owner chose `BEVEL`, the other three
styles and the switch were removed, so the code has one tile look; this
section is kept as the record of why.

| Style | What a tile drew | Work beyond the plain art |
| --- | --- | --- |
| `FLAT` | Its art: one bitmap. | None. The look before 2026-11-26. |
| `RIM` | Art, a gradient stroke along the edge and a dark hairline inside it. | Two stroke draws per tile per frame. |
| `BEVEL` (chosen) | Art with a 6 dp bevel baked in by `TileBevel`: the edge band refracted, lit and shaded. | Baking, once per tile when its art is rendered; the result is what `TileArtDiskCache` stores. |
| `FRAME` | A 5 dp ring of blurred, darkened backdrop sampled 7% further out from the tile's centre (`TileGlassBackdrop`), the art inset inside it, and `RIM`'s strokes. | Per photo: a second decode at 1/8 size and a blur. Per frame while the grid scrolls, a tile's focus animates or the photo crossfades: the tiles on screen record their display lists again. |

Every style except `FLAT` added a sibling view per tile for the focused
sheen (a brighter stroke and a gloss over the top 55% of the tile), and
`BEVEL` keeps it. Its alpha follows focus, so at rest only the focused
tile's sheen is drawn.

Measured on 2026-11-26 with `benchmarkRelease` builds on two emulators of
our own, 1920x1080 at 320 dpi: API 36 (Android TV image) and API 28 (the
Google APIs phone image at TV resolution, since there is no arm64 TV image
for API 28; its status bar is visible). Frame CPU time is
`HomeFramesBenchmark.gridNavigation` (Macrobenchmark, 5 iterations), run
twice per style in opposite style order, P50 / P90 / P99 in ms:

| Style | API 28, run 1 | API 28, run 2 | API 36, run 1 | API 36, run 2 |
| --- | ---: | ---: | ---: | ---: |
| `FLAT` | 2.71 / 3.96 / 8.6 | 2.96 / 4.32 / 20.3 | 1.94 / 3.87 / 19.2 | 2.07 / 2.71 / 17.4 |
| `RIM` | 2.67 / 4.14 / 10.3 | 2.99 / 4.36 / 8.3 | (failed: grid not found in time) | 2.22 / 2.92 / 17.7 |
| `BEVEL` | 2.68 / 3.72 / 5.0 | 2.83 / 4.06 / 5.8 | 1.89 / 3.52 / 18.2 | 1.94 / 2.73 / 17.7 |
| `FRAME` | 3.12 / 4.63 / 18.3 | 3.00 / 4.89 / 49.0 | 2.07 / 2.76 / 3.7 | 2.29 / 3.43 / 13.6 |

P99 swings between runs of the same build (5 to 49 ms) with the host's
load; P50 and P90 are the comparable values. `BEVEL` is within run-to-run
noise of `FLAT` on both; `RIM` adds 0 to 0.2 ms; `FRAME` adds up to 0.4 ms
at P50 and up to 0.7 ms at P90, the cost of recording the tiles again while
they move.

The same browsing driven from the shell (`input keyevent`, 150 ms apart,
three cold starts per style, alternating) and read with
`scripts/perf/meminfo.sh`:

| | `FLAT` | `RIM` | `BEVEL` | `FRAME` |
| --- | ---: | ---: | ---: | ---: |
| API 28, janky frames (median of 3) | 0% | 0% | 0% | 0% |
| API 36, janky frames | 2.0% | 2.0% | 2.0% | 2.0% |
| API 36, graphic buffers | 34,336 KB in 13 | 34,336 KB in 13 | 34,336 KB in 13 | 34,133 KB in 14 |
| Views (API 36, 9 tiles) | 46 | 55 | 55 | 55 |

API 36's 2% are the emulator's pacing (the same frames, about 17 ms, in
every style), not app work. `FRAME`'s extra buffer is the 240x135 glass
image (127 KB); its art is 293x156 instead of 313x176, which saves more.

Background work, from `atrace` sections during a cold start right after
switching styles (so every tile is rendered again):

| | API 28 | API 36 |
| --- | ---: | ---: |
| `Rozan.bakeTileBevel`, median per tile | 1.04 ms (21 tiles) | 1.00 ms (10 tiles) |
| `Rozan.bakeTileBevel`, first tile of the start | 22.6 ms | 15.1 ms |
| `Rozan.prepareTileGlass` (`FRAME`), per photo | 8.8 ms | 8.4 ms |
| `Rozan.decodeBackdrop`, for comparison | 15 to 36 ms | 14 to 37 ms |

The first bake runs before the JIT has compiled `TileBevel`; the baseline
profile does not cover it yet. A baked tile is stored, so later starts read
it from disk like any other tile.

What the emulators cannot show is the GPU: on a GE8300 the difference is
fill. Per frame at rest, `BEVEL` blends about 34,000 more pixels than
`FLAT` (the focused tile's sheen; 1.6% of a 1080p screen), `RIM` about
3,700 per visible tile for its strokes, and `FRAME` about 9,000 per visible
tile for its ring, sampled with bilinear filtering, plus `RIM`'s strokes.

### APK size

Release APKs (R8, resource shrinking), as gated:

| APK | Size | Dex, uncompressed |
| --- | ---: | ---: |
| `launcher-release.apk` | 7,953,856 bytes (7.6 MB) | 574,168 bytes in 2 files |
| `airplay-release.apk` | 529,594 bytes (517 KB) | 401,104 bytes in 2 files |

The launcher's size is almost all the 24 bundled backdrop photos
(1920x1080 JPEGs, 7,236,273 bytes stored). The seven translations added
about 21 KB to the receiver APK and 46 KB to the launcher APK (10.6 KB of
it dex), and the classic RAOP support for Music on a Mac about 10.6 KB to
the receiver APK, and the Inputs menu about 19 KB to the launcher APK
(14.5 KB of it dex), measured on CI. Each APK has two dex files
because R8 puts the startup profile's classes in the first one.

## Emulator caveats

- **Graphic buffers are not in PSS.** The emulator keeps window buffers,
  hardware bitmaps and decoder output on the host, so `Graphics` reads 0.
  `meminfo.sh` and `MeminfoMetric` estimate them from the
  `GraphicBufferAllocator` table in `dumpsys gfxinfo` (width x height x
  bytes per pixel of every buffer the process allocated). On a device these
  buffers are real memory and show up as Graphics.
- **The emulator's GL transport is most of the native heap.** heapprofd on
  API 31 attributes 22.5 MB of the launcher's 24.4 MB live native heap at
  idle to `QemuPipeStream::allocBuffer` in `libOpenglSystemCommon.so`, the
  guest side of the emulator's GL pipe, which keeps buffers as large as the
  largest uploads (a full-screen photo is 7.9 MB). In `:airplay` during
  audio it is 18.0 MB of 21.2 MB. On a device that memory does not exist;
  the launcher's own native heap is about 2 MB (HWUI 1.2 MB, resource
  tables 0.3 MB). The native heap budgets on emulators therefore mostly
  track the largest textures uploaded.
- **Timing follows the host.** The host GPU renders through a translation
  layer and the host cores are much faster than Cortex-A35s. 85% of the
  render thread's CPU samples are kernel time (the emulator's GL calls go
  through a pipe device), which says nothing about a GPU driver. Offscreen layer
  counts, allocation counts and memory are the same on every host, so those
  are what the emulator is trusted for.
- **x86_64 and arm64 differ.** CI runs x86_64 images; their code and
  driver memory differ from arm64, so they get their own budget profile.

## How memory is measured

`scripts/perf/meminfo.sh` reads one process with `dumpsys meminfo <pid>`
(App Summary rows: Java heap, native heap, code, stack, graphics, private
other, system, total PSS and RSS; the Objects section: views, view roots,
Activities) and `dumpsys gfxinfo <pid>` (frame statistics and the graphic
buffer table).

Every `dumpsys meminfo` makes the app run an explicit GC (logcat shows
`Explicit concurrent mark compact GC` once per dump). Until a GC runs,
memory the app has already let go of still counts: 10 s after a cold start
the launcher's PSS was 50.7 MB, and a second dump right after the first
read 41.5 MB (native heap allocations 39.4 MB before, 34.1 MB after; code
7.2 MB before, 3.3 MB after). So both `meminfo.sh` and `MeminfoMetric` dump
twice and keep the second: the measurement is what the app holds, the same
on every run. Peaks before a GC are what Macrobenchmark's `memory*Max`
metrics show.

`scripts/perf/memory-scenarios.sh` installs the APK, clears its data,
switches animations on, and runs, each from the state the previous one
left:

1. `home_idle`: cold start of HomeActivity, 8 s untouched.
2. `home_browsed`: two zig-zag passes over the grid with the D-pad, 5 s settle.
3. `screensaver`: the Screensaver tile, 6 s in.
4. `airplay_mirror`: the debug hook's mirroring session over Home, 10 s in.
5. `airplay_audio`: the debug hook's audio session with artwork, 6 s in.

Each scenario snapshots both processes: the launcher and `:airplay`, which
Home starts with the receiver (on by default). The launcher's third
process, `:redirect`, runs only while the Home button redirect is enabled
and is not measured yet.

`MemoryBenchmark` (Macrobenchmark) runs the same scenarios as separate cold
starts and reports the same snapshot (`meminfo*` metrics) next to
Macrobenchmark's RSS and heap counters (`memory*`, exactly one process each).

## Budgets: what fails the build

`config/perf-budgets.json` holds a ceiling (`budget`) for every gated value
and the value it was set from (`measured`). `scripts/perf/check-budgets.py`
(Python 3 standard library) compares measurements with it and prints one row
per value:

```
category  scenario / app          metric               measured   budget     limit      use  status  when set
--------  ----------------------  -------------------  ---------  ---------  ---------  ---  ------  ---------
apk       launcher                apk_bytes            7,858,253  8,015,872  8,015,872  98%  ok      7,858,253
memory    home_idle airplay       total_pss_kb            10,384     11,520     12,096  90%  ok         10,436
memory    home_idle main          total_pss_kb            42,272     46,848     49,190  90%  ok         42,417
memory    home_idle main          gralloc_estimate_kb     34,336     37,888     39,782  91%  ok         34,336
timing    home_idle main          frame_p99_ms                36          -          -    -  info           34
timing    home_idle main          start_ms                    94          -          -    -  info           98
...
OK: 41 budgets met. Timing rows are recorded only, never gated (see the budgets file).
```

(Rows from a check of a third API 36 run, which the budgets were not set
from. Memory values are KB.) With the `home_idle main total_pss_kb` budget
lowered to 38,912 and the launcher APK budget to 7,800,000, the same check
prints `FAIL` on both rows, lists them under `FAILED: 2 of 39 budgets
exceeded or not measured`, and exits 1.

- Over `budget` but within `tolerance_percent` (5% for memory, 0 for APK
  size) is a warning; above that, or a budgeted value that was not measured,
  fails with exit code 1.
- Gated: APK size and uncompressed dex size of both release APKs; per
  scenario and process, total PSS, Java heap, native heap, Graphics (on
  devices; emulators report 0) and the graphic buffer estimate (where gfxinfo
  lists the buffers).
- Recorded, never gated: frame time percentiles, janky frames and cold start
  time. On an emulator they follow the host's GPU, CPU and load more than the
  app (the file says the same under `timing.why`).
- Memory budgets are per device profile, because PSS differs between Android
  versions and ABIs: `emulator-api36-arm64-v8a` and
  `emulator-api31-arm64-v8a` (measured here), `emulator-api36-x86_64` for CI
  (measured on CI, see below), and a `device-api<sdk>-<model>` profile for
  real hardware once measured.
- The initial budgets are the larger of two runs plus 10% (at least 1 MB);
  APK budgets are the size plus 2% (at least 16 KB), rounded up.

`emulator-api36-x86_64`, the CI emulator, cannot be measured on an arm64 Mac.
Its budgets are the larger value of two CI runs (45868423441 and
45871602578) per metric, plus the usual margin. One run was not enough: the
graphic-buffer estimates swing between runs on the CI emulator (the
launcher's during the audio scenario measured 1.5 MB in one run and 9.8 MB
in the next, the receiver's while mirroring 49.5 and 67.5 MB). To re-record,
download the `perf-results-api36` artifact of at least two newer runs, merge
them taking the larger value per metric, and:

```sh
scripts/perf/check-budgets.py --record results-api36.json
```

CI runs two checks. The `check` job checks APK sizes after `assembleRelease`
and runs the checker's own unit tests. The `Memory budgets (API 36)` job
builds the launcher's `benchmarkRelease` APK, stops the Gradle daemon, boots
its own API 36 emulator, runs `scripts/perf/memory-scenarios.sh` (bounded to
10 minutes), checks the results, and uploads them as the `perf-results-api36`
artifact. It has a runner of its own because private-repository runners have
about 7 GB of RAM: with the instrumented tests, their Gradle daemon and the
scenarios on one runner, a run once took the runner down. Every CI job that
builds on an emulator runner caps Gradle at a 2 GB heap with Kotlin compiled
in-process (`scripts/ci/limit-gradle-memory.sh`).

When a change legitimately needs more memory or size, raise the budget in the
same change and say why in the commit message. To re-record from fresh
measurements:

```sh
scripts/perf/memory-scenarios.sh --apk app/launcher/build/outputs/apk/benchmarkRelease/launcher-benchmarkRelease.apk --out build/perf/results.json
scripts/perf/check-budgets.py --record build/perf/results.json \
  --apk launcher=app/launcher/build/outputs/apk/release/launcher-release.apk \
  --apk airplay=app/airplay/build/outputs/apk/release/airplay-release.apk
```

and review the diff of `config/perf-budgets.json`.

## Leak tests

`LeakTest` (launcher, in-process) opens and closes the settings screen ten
times, enters and leaves the screensaver ten times, and ten times drops the
backdrop photo under memory pressure (`onTrimMemory(TRIM_MEMORY_BACKGROUND)`
while settings covers home) and comes back. After a warm-up cycle and a
forced GC it asserts that every closed Activity and every dropped photo
(`WeakReference`s) is collectable, that the live Java heap and malloc'd
native memory grew by at most 1 MB, and PSS by at most 12 MB.

PSS gets a looser bound because decoding a photo goes through a full-screen
native buffer (7.9 MB at 1080p) that the allocator may keep after it is
freed: over ten trim cycles native-heap PSS grew by 1 to 4 MB while
allocated bytes did not grow at all. A leaked photo is caught by the
`WeakReference` check instead (and on a device would add 7.9 MB of Graphics
per cycle).

`SessionLeakTest` (`:airplay:android`) runs ten PIN prompts, mirroring
sessions and audio sessions through the debug hooks. Those screens live in
the `:airplay` process, so it reads `dumpsys meminfo` of that process: ART
counts Activity instances after a GC, and the ViewRootImpl count covers the
PIN overlay window. It asserts no Activity and no window remain and growth
under 3 MB.

The settings test found a real leak on its first run after the "Show over
other apps" row landed: three of ten settings screens stayed reachable.
`OverlayPermission.watch` registered its listener with the Activity's
`AppOpsManager` and captured the Activity; the system service keeps the
binder stub behind the listener until its own GC runs, long after
`stopWatchingMode`. It now uses the application context's manager and
reaches the callback only through a reference that `close()` clears.

Growth over ten cycles in the latest runs (all pass; KB):

| Test | API 36 Java / native / PSS | API 31 Java / native / PSS |
| --- | --- | --- |
| `LeakTest`, largest of its three tests | 28 / 29 / 1,360 | 9 / below 0 / 3,380 |
| `SessionLeakTest` (`:airplay`) | 88 / 536 / 841 | 76 / 848 / 2,254 |

`SessionLeakTest`'s PSS growth on API 31 uses 73% of its 3 MB bound; if it
starts to fail intermittently, look at the per-kind growth it prints before
loosening the bound.

## Where the memory goes

### Graphic buffers

From the `GraphicBufferAllocator` table of `dumpsys gfxinfo` on API 36,
which is what a device counts as Graphics:

- Launcher at idle home, 33.5 MB in 13 buffers: the window's three
  1920x1080 RGBA buffers (23.7 MB, the BLAST queue of `VRI[HomeActivity]`),
  the backdrop photo as a 1920x1080 hardware bitmap (7.9 MB), and nine tile
  images of 313x176 (215 KB each, 1.9 MB). A crossfade adds the next photo
  (7.9 MB) for its 1.5 s. While another app is in front the window buffers
  are released and 9.8 MB remain (photo and tiles).
- `:airplay` while mirroring the 720p clip: 36.0 MB in the scenario.
  `MirrorActivity`'s window has two or three 1920x1080 RGBA buffers (7.9 MB
  each) although the video itself goes to a `SurfaceView`, and the decoder
  outputs into three 1280x720 buffers of format 23, which the estimate does
  not know and counts at 4 bytes per pixel (3.5 MB each; a 4:2:0 YUV format
  would be 1.3 MB). A 1080p stream has 2.25 times the pixels per decoder
  buffer.
- `:airplay` during audio: 25.1 MB, the now-playing window's three buffers
  (23.7 MB) and the artwork.

The window buffers are the system's (three per visible window) and the
largest single item. Of the app's own, the photo is the largest.

### Java heap

A `java_hprof` heap graph of the launcher at idle home (API 36) has 6,715
reachable objects in the app's own heap: 351 KB of Java objects plus 228 KB
of native memory registered against them. The largest are 998 strings
(44 KB), the app's view classes, and the 10 `Bitmap` objects (580 bytes of
Java objects and 160 KB registered; their pixels are the hardware buffers
above). The rest of the 1.4 to 3.4 MB Java heap PSS is pages shared with the
zygote and boot image, whose objects (22 MB in the graph) every app shares.
Nothing in the Java heap is worth cutting.

### Native heap

heapprofd (API 31, sampled every 4 KB, from process start; live
allocations at the end):

- Launcher, 25 s after a cold start: 24.4 MB live, of which 22.5 MB is the
  emulator's GL pipe buffer (see [Emulator caveats](#emulator-caveats)),
  1.2 MB HWUI (Skia glyph and path caches, the SkSL compiler, display
  lists) and 0.3 MB resource tables. 91 MB was allocated and freed on the
  way there.
- `:airplay` while mirroring: 10.0 MB live, 9.0 MB of it the GL pipe
  buffer.
- `:airplay` during audio: 21.2 MB live, 18.0 MB of it the GL pipe buffer
  (after the artwork upload) and 1.4 MB a software bitmap that the debug
  hook itself creates as test artwork.

### Allocations while mirroring

heapprofd on `:airplay`, covering both heaps (malloc and the ART heap) with
a dump every 5 s, through a 12 s fake mirroring session and then a 10 s
audio session:

- Starting a session allocates once: 1.9 MB of Java objects for mirroring
  and 1.3 MB for audio (the debug hook's clip and artwork included), and
  20.7 MB native for mirroring (decoder, surface; 10.8 MB of it freed again
  within the same 5 s).
- Each 5 s window of steady mirroring: nothing on the Java heap (no sampled
  allocation), 4.0 to 4.1 MB allocated and the same freed natively.
- The 5 s window of steady audio: under 1 KB on either heap.

So the packet paths hold to the no-allocation rule. The 0.8 MB/s of
native churn while mirroring is all in the platform's media plumbing, none
in Rozan's code: `AMessage` items in `libstagefright_foundation`, HIDL
parcels to the codec service, `SharedBuffer`, and `SurfaceComposerClient`
transactions, reached from `MediaCodec.queueInputBuffer`,
`dequeueOutputBuffer` and `releaseOutputBuffer`.

## CPU

Measured on 2026-11-26 on both emulators with `benchmarkRelease` builds
whose baseline profile was installed and compiled (`speed-profile`, as
Macrobenchmark's `CompilationMode.Partial` does). "Before" is
`development` at 3280b69, "after" is this change. Two tools:

- A Perfetto trace of only the scheduler (`sched_switch`, `sched_waking`):
  a thread's CPU time is the sum of its scheduler slices, exact to the
  nanosecond, and its wakeups are the times it became runnable. The
  recipe is in [Profiling recipes](#profiling-recipes).
- simpleperf (`cpu-clock` at 4 kHz with call graphs) for where the time
  goes, names mapped back through R8's `mapping.txt`.

The emulator's GPU is the host's behind a pipe, so the render thread's
time is mostly the kernel side of that pipe (`read` and `write` were 64%
of its samples while browsing) and says little about a device's GPU
driver. Wakeups and the app's own threads carry over better.

### Per scenario

CPU time of the process over the window, and wakeups (all threads). Two
processes: the launcher (`main`) and the receiver (`:airplay`).

| Scenario, window | Process | API 36 before | API 36 after | API 31 before | API 31 after |
| --- | --- | ---: | ---: | ---: | ---: |
| Home idle, 60 s | main | 18.0 ms, 17 | 21.2 ms, 16 | 17.1 ms, 12 | 16.2 ms, 11 |
| Home idle, 60 s, receiver on | `:airplay` | 4.9 ms, 61 | 0.3 ms, 2 | 5.9 ms, 59 | 0 ms, 0 |
| Grid navigation, 64 keys in 12 s | main | 830.5 ms, 4,251 | 837.5 ms, 4,205 | 828.6 ms, 2,939 | 812.7 ms, 2,933 |
| Entering the screensaver, 8 s | main | 165.9 ms, 887 | 239.8 ms, 1,328 | 224.5 ms, 743 | 387.0 ms, 1,160 |
| Screensaver photo change, 10 s | main | 147-217 ms, 481-807 | 127-221 ms, 475-835 | 225-229 ms, 547-692 | 202-221 ms, 545-584 |
| Mirroring (debug hook, 720p clip), 10 s | `:airplay` | 402.6 ms, 8,604 | 337.7 ms, 6,444 | 471.1 ms, 8,593 | 440.3 ms, 6,560 |
| Audio (debug hook), 10 s | `:airplay` | 47.2 ms, 1,393 | 34.7 ms, 1,381 | 162.6 ms, 2,513 | 91.6 ms, 2,457 |

Navigation and mirroring are medians of three runs per build, the two
builds alternating on the same emulator; the photo change shows the range
of two such runs; the other rows are single runs. Run to run, the same
build's CPU moved by up to 6% (navigation) and 29% (mirroring); wakeups
moved by under 4%. Navigation is unchanged within that; the screensaver
rows moved because the first photo change now follows the fade (proposal
3); the audio path did not change, so its row shows the spread of single
runs.

What the profiles show:

- **Idle home is quiet.** Over 60 s the launcher wakes 11 to 17 times:
  the clock's minute tick (one frame: main thread, render thread, one
  graphic buffer upload) and the binder threads. Nothing polls. The
  `:airplay` process woke once a second for the control server's
  housekeeping, with nobody connected; it now schedules housekeeping only
  while a connection is open or pairing state can expire, and sleeps
  otherwise (0 to 2 wakeups a minute, the ART daemons).
- **Browsing is the render thread.** 75 to 82% of the launcher's CPU is
  `RenderThread`, almost all of it in the emulator's GL pipe; the main
  thread's share is Choreographer frames, `FloatAnimator` updates and the
  invalidations they cause. Rozan's own code is 0.2% of the samples
  (`FloatAnimator.onAnimationUpdate`, `TileView.onValue`); there is no
  app code left to optimise on this path, only work for the GPU to skip
  (proposal 6 takes one full-screen layer off every frame below the
  first row).
- **Screensaver.** The window covers the 0.9 s fade and, now that the
  first photo change waits for the fade (proposal 3), that change's 1.5 s
  crossfade as well: more frames, lighter ones. The photo decode is 7 to
  13 ms on `rozan-decode-1` and its upload 3 to 12 ms on HWUI's upload
  thread, once per photo.
- **Mirroring.** In `:airplay`, the decoder's own threads lead
  (`MediaCodec_loop`, `CodecLooper`, `HwBinder` to the codec service),
  then `airplay-video` (Rozan's sink) and the debug hook that plays the
  sender. Rozan's code is under 1% of the samples. The sink's video
  thread polled the decoder's output every 10 ms; it now waits for output
  and is woken by an end-of-stream input when it must stop (proposal 8):
  wakeups fell by a quarter, and the CPU medians by 16% (API 36) and 7%
  (API 31), the latter within the spread.
- **Audio.** Most of the `:airplay` CPU here is the debug hook generating
  its sine wave (`sin` is its top symbol); of Rozan's code,
  `AudioTrackPcmSink.decode`. The hook feeds the sink directly, so it
  does not exercise `AudioStream`: its playout thread polled an empty
  buffer every 5 ms (up to 200 wakeups a second while a session is
  paused) and now sleeps until a packet, flush, volume change or stop
  wakes it (`AudioStreamTest`: 666 clock reads in 500 ms with nothing to
  play before, 0 after).

Not covered by these scenarios: a full protocol session from a real
sender (pairing, FairPlay, timing), which `:tools:devserver` runs on the
desktop JVM.

## Hot spots

From the Perfetto traces of the frame benchmarks on API 36, and the code
they point to.

1. **Faded scrims drew through offscreen layers (fixed here).** HWUI emitted
   `alpha caused saveLayer 1920x680` and `alpha caused saveLayer 1920x1080`
   on 72 of 287 frames each while focus changed rows: `scrim_rest` and
   `scrim_grid` in `activity_home.xml` are plain Views whose alpha animates,
   and a View has overlapping rendering by default, so each was drawn into a
   layer of its own size first, then blended. Each scrim is one drawable, so
   applying alpha to that one draw is identical. Both now set
   `android:forceHasOverlappingRendering="false"`; the same benchmark shows
   no layer during navigation.
2. **The screensaver fade draws the whole grid into one layer.** During the
   900 ms chrome fade, `alpha caused saveLayer 1920x1342` on 106 of 181
   frames. `HomeChrome.setGridFadesAsOne` does this on purpose (so the
   focused tile's shadow does not show through its body), but
   `TileGridView` measures itself as tall as its content (`max(viewport,
   content)`), so the layer covers rows that are off screen: 1342 px with
   the emulator's few apps, and one more row pitch per row of apps. A
   1920x1342 RGBA layer is 9.8 MB of GPU memory allocated for the fade.
   (Measured later: the slice names the RenderNode's size, but Skia clips
   a saveLayer to the device clip, so the layer was never larger than the
   screen. See proposal 2.)
3. **The screensaver's first photo change starts during the chrome fade.**
   `HomeActivity.onScreensaverStarted` sets the 45 s screensaver interval;
   `BackdropController.schedule` then finds the photo older than that and
   posts the change at once. The decode (16 ms on the host, on a background
   thread) and the 1.5 s crossfade (two full-screen photos, the upper one
   blended) overlap the 900 ms fade above, so the most expensive frames of
   the screensaver stack two full-screen photos and the grid layer.
4. **One full-screen blend per frame below the first row.** When a lower
   row has focus, `scrim_grid` (match_parent, `@color/scrim_grid`) is
   blended over the whole backdrop on every frame.
5. **App labels open every app's APK on the startup path.**
   `Rozan.loadApps` took 55 ms on the host during a cold start, 48 ms of it
   in `ResourcesManager#getResources` (6 calls for the emulator's 7 launcher
   activities: `loadLabel` opens each app's APK to read its label). The
   tiles and `reportFullyDrawn()` wait for it. It grows with the number of
   apps and with slower storage and cores. Tile art then asks for the same
   apps' resources again for banners and icons.
6. **Package changes are handled while Home is stopped.**
   `PackageChangeReceiver` is registered from `onCreate` to `onDestroy`, so
   every app install or update (Play Store or Appstore auto-updates while
   the user watches something) reloads the whole app list and re-renders
   that app's tile art in the launcher's process, competing with the app in
   front.
7. **Trimming the tile art cache frees little and costs a full re-render.**
   On `TRIM_MEMORY_BACKGROUND`, `TileArtLoader.trimCache()` evicts every
   entry, but the tiles still hold their bitmaps, so the memory stays; the
   next `setApps` (any package change) then renders every tile again.
8. **The video sink waits for a decoder input buffer while holding its
   lock.** `MediaCodecVideoSink.trySubmitLocked` calls
   `dequeueInputBuffer(10 ms)` inside `lock.withLock`, retried up to 50
   times, so the video thread's catch-up feeding, surface changes and `stop`
   can wait up to 500 ms behind a stalled decoder.
9. **AirPlay settings are read from disk on main threads and written with
   `fsync` on click.** `PropertiesFile` reads the file on every property
   access (three reads in `ReceiverService.applySettings`, on the
   `:airplay` main thread) and every write ends in `fd.sync()`;
   `MainActivity` (AirPlay app) writes from click handlers.
10. **Smaller items.** `KEY_MAX_INPUT_SIZE` is 2 MB per decoder input buffer
    (`VideoDecoders.MAX_INPUT_BYTES`), whatever the stream needs; the AAC
    sink copies PCM one `getShort` at a time (`AudioSinks.copyPcm`) where
    one bulk `asShortBuffer().get` would do; `renderBanner` draws each
    banner into two software bitmaps before the hardware copy; the clock is
    `wrap_content`, so each minute's text change re-measures the top bar.

Startup itself (API 36, cold, from startup benchmark traces):
`bindApplication` 17.7 ms (6.8 ms of it opening the dex files),
`activityStart` 14.2 ms (`HomeActivity.onCreate` 11.4 ms), `activityResume`
16 to 26 ms (of which the activity's own `performResume` is 1.5 ms; the
rest is framework work outside the app's code), then the first frame.
Nothing in the app's own startup code stands out.

Grid navigation on the main thread (simpleperf, API 31): 16% of the app's
CPU samples, half of them in Choreographer frames, a quarter in
`FloatAnimator` updates and the invalidation they cause; the render thread
has the other 79%, 85% of that in the kernel (the emulator's GL pipe). In
all, the app's processes used 0.74 s of CPU over 14 s of browsing.

## Optimisation proposals

Ranked by expected effect on a Fire TV Stick Lite, first the ones that cut
GPU memory and fill, which is what a 1 GB device with a GE8300 runs out of
first. Each one's outcome follows it, measured on both emulators with
`benchmarkRelease` builds (method in [CPU](#cpu)); a change that measured
no gain was dropped. Number 11 is open.

1. **Draw the fading scrims without layers.** Done in this change
   (`activity_home.xml`). Removes two offscreen layers (1920x680 and
   1920x1080, together 12.9 MB of render targets, and the extra blend of
   each) from every frame of a row change.
2. **Clip the grid's fade layer to the screen.** While
   `setGridFadesAsOne(true)` is in effect, set `grid.clipBounds` to the
   visible part of the grid (`0, -translationY, width, -translationY +
   viewport height`) and clear it when the fade ends, so that HWUI can size
   the layer to the clip. That would cut the layer from 1920x1342 (9.8 MB)
   to at most 1920x1080 (7.9 MB) here, and keep it at that size however
   many apps are installed. Check in the `screensaverEnterAndExit` trace:
   the `alpha caused saveLayer` slice names the layer's size.

   **Dropped.** With the clip, the slice still read `1920x1342`: HWUI
   names the RenderNode's size, not the layer's. Skia sizes a saveLayer to
   its intersection with the device clip, so the layer was already at most
   the screen. HWUI's GPU cache after the fade held the same scratch render
   targets with and without the clip, 9.91 MB in 2 entries, on API 36 and
   API 31.
3. **Start the screensaver's first photo change after the chrome fade.**
   Let `HomeChrome` report the end of the saver fade and only then set the
   screensaver interval on the backdrop. Removes the overlap of a second
   full-screen photo with the grid layer: the peak frame goes from two
   photos plus the layer to one photo plus the layer, and one 7.9 MB photo
   less is allocated during the fade.

   **Done.** Times from the fade's first layer frame, two runs per
   emulator: the backdrop decode started 17 to 36 ms before the fade and
   now starts 880 to 885 ms in, after the last layer frame (864 ms). The
   cost is that the crossfade follows the fade instead of overlapping it:
   entering the screensaver renders 147 frames instead of 93, and the
   launcher used 282-300 ms of CPU instead of 175-192 ms over the 8 s
   trace on API 36 (399-402 against 265-267 ms on API 31). Kept because
   the peak frame and the peak memory are the limits on a fill-limited
   1 GB device, and this runs once per screensaver start. It needed
   `FloatAnimator` to end exactly on its target, which it now does.
4. **Cache app labels (and tile art) across starts.** Store label, banner
   presence and the rendered tile per package and `lastUpdateTime` in the
   app's cache directory; show the cached grid at start and refresh it in
   the background. Removes `getResources` per app (48 of 55 ms of
   `loadApps` on the host) from the path to `reportFullyDrawn()`, and the
   tile renders at every cold start.

   **Labels: done.** `AppLabelCache` (one file in the cache directory,
   bounded, keyed by activity, used while the app's `versionCode` and
   `lastUpdateTime` match; activities the list drops get no label). Five
   cold starts each, `Rozan.loadApps`: median 3.4 ms before, 1.6 ms after
   on API 36; 8.3 ms before, 2.7 ms after on API 31. A start with the
   cache reads no label (5 to 9 without it). The list is still loaded
   before the grid shows it; showing a cached grid first was not needed
   at these times.

   **Tile art: no gain at cold start on the emulators.** Rendered art is
   kept as PNG in `TileArtDiskCache` (lossless: a stored tile reads back
   with the same pixels) for proposal 7. At cold start it reads a tile in
   1.0 to 3.3 ms where rendering took 1.4 to 2.5 ms, both including the
   hardware upload, and the whole tile art load stayed 57-96 ms on API 36
   and 27-59 ms on API 31 (64-84 and 34-47 ms before): it is dominated by
   the process's first hardware uploads and by waiting behind the
   backdrop's upload, not by rendering. The emulators read APKs from the
   host's page cache; whether reading the PNG beats opening the APK on a
   device's flash is to be measured there. Storing a tile costs 16-23 ms
   once per app version.
5. **Ignore package changes while stopped; reload on start.** Mark the list
   stale in the receiver when Home is not started, reload in `onStart`.
   No launcher work while another app plays video.

   **Done.** An app installed and then updated with the Play Store in
   front: the launcher's main process used 24.3 ms of CPU (two list loads,
   three tile renders) before and 2.0 ms after on API 36, 35.4 and 6.0 ms
   on API 31. `onStart` forgets the changed apps' art and reloads the list
   once.
6. **Darken the backdrop instead of blending a full-screen scrim.** In grid
   mode, apply the scrim colour to the backdrop's own draw (a colour filter
   on `BackdropView`'s paint, animated with the same value) instead of
   blending `scrim_grid` over it. One full-screen blend per frame fewer
   while a lower row has focus.

   **Done, at rest only.** Once the grid mode has settled at 1, the
   backdrop draws the scrim colour into its own draw (`SRC_ATOP` filter on
   its paint and on the fallback) and `scrim_grid` is hidden; while the
   scrim fades the view does it as before, because a filter would
   re-record the backdrop's display list on every frame. Only a covering
   backdrop stands in for the scrim. `BackdropTintTest` compares
   screenshots of both paths from one process: 7.7% of the pixels differ,
   none by more than 1 of 255 in a channel (the scrim was blended in the
   framebuffer, the filter applies in the shader), on API 36 and API 31.
   With HWUI's overdraw view, the screen below the top bar goes from drawn
   twice to drawn once.
7. **Make trim actually release tile art.** On `TRIM_MEMORY_BACKGROUND`,
   clear the art of tiles as well as the cache (and re-request it in
   `onStart`), or keep the cache and drop only entries no tile shows. At
   313x176 a tile's art is 215 KB of GPU memory, so 40 apps hold 8.4 MB.

   **Done.** While Home is stopped (its display lists went with the
   window's surface), the trim drops the tiles' art and the cache;
   `onStart` loads it again from `TileArtDiskCache`, so no app tile is
   rendered again. With the Play Store in front and `am send-trim-memory
   BACKGROUND`, API 36: graphic buffers left after the trim 2151 KB in 10
   buffers before, none after (API 31's `gfxinfo` lists no buffers). On
   return the app tiles are read back in 6 to 35 ms of background time;
   only built-in glyphs and new apps are rendered. App art is keyed by the
   app's version stamps, so an update renders it anew.
8. **Wait for decoder input outside the sink lock.** Dequeue the input
   buffer without holding `lock`, then take the lock only to queue it (and
   re-check `closed`/the codec). Stops a stalled decoder from blocking
   surface changes and `stop` for up to 500 ms.

   **Done.** Surface loss while a flooding sender waits for input (5
   losses, `MediaCodecVideoSinkTest`): 7-74 ms before, 3-6 ms after on
   API 36; 4-64 and 2-4 ms on API 31. The video thread also waits up to
   100 ms for output while the decoder is fed directly, cut short by an
   end-of-stream input on a stop request, instead of polling every 10 ms:
   `:airplay` wakeups in 10 s of the debug mirroring session 8,698 to
   6,487 (API 36) and 8,301 to 6,530 (API 31).
9. **Cache AirPlay settings in memory; write off the main thread.** Keep the
   parsed `Properties` and re-read only when the file watcher reports a
   change (the class already gets one `MOVED_TO` per write); move writes
   to a background thread. No disk access on main threads.

   **Done.** One copy per process, re-read on the file watcher's thread
   before listeners run; writes go to disk in order on a short-lived
   writer thread. `AirPlaySettingsTest` (StrictMode, main thread): disk
   accesses 10 to 0; reading all three settings 490-528 to 130 µs
   (API 36) and 165 to 54 µs (API 31); a write 5.5-6.4 to 0.07 ms
   (API 36) and 3.3-3.6 to 0.11-0.17 ms (API 31).
10. **Size `KEY_MAX_INPUT_SIZE` from measurements.** Log the largest access
    unit of real sessions (1080p and 4K senders) and set the bound from that
    with headroom; each input buffer is allocated at this size.

    **Dropped.** A probe of the emulators' H.264 decoders: every one
    allocated 2 MiB input buffers (the API 36 goldfish decoder 6 MiB)
    whether `KEY_MAX_INPUT_SIZE` asked for 192 KiB or 2 MiB, so the bound
    changes nothing there. Codec2 decoders treat the key as a minimum. On
    a device, check the codec's `getInputFormat()` before revisiting.
11. **Decode backdrops at a lower resolution or depth, if they still look
    right.** A 1080p ARGB photo is 7.9 MB of GPU memory, 15.8 MB during a
    crossfade. Behind the scrims, a 1280x720 photo scaled up (3.5 MB) or
    RGB_565 (4 MB) may be indistinguishable; this is a look-and-feel call
    for the owner, so it needs a side-by-side on a TV first.

Benchmark hooks to add: `NEXT_BACKDROP` and `START_SCREENSAVER` actions on
the launcher's `DebugEventsReceiver` (see
[Builds that are measured](#builds-that-are-measured)).
`screensaverEnterAndExit` then would not need to leave home idle for 48 s
per iteration, and a photo-to-photo crossfade could be measured directly.

## Profiling recipes

All on a `benchmarkRelease` install (profileable, release code) with
`ANDROID_SERIAL` set. Perfetto on API 31 cannot read config files from
`/data/local/tmp`, so the configs go in on stdin.

**Trace of an interaction**: run the matching Macrobenchmark and open its
`.perfetto-trace`. Useful slices: `Choreographer#doFrame`, `DrawFrame`,
`alpha caused saveLayer WxH` (the size is the view's; the layer itself is
clipped to the screen), `dequeueBuffer`, and the app's own `Rozan.*`
sections (`Rozan.loadApps`, `Rozan.loadLabel`, `Rozan.loadTileArt` with
`Rozan.readTileArt`, `Rozan.renderTileArt` and `Rozan.writeTileArt` inside,
`Rozan.decodeBackdrop`, `Rozan.loadBackdropManifest`).

**CPU time and wakeups per thread**, exact: trace only the scheduler for
the length of the scenario, then sum each thread's scheduler slices.

```sh
cat > sched.cfg << 'EOF'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "sched/sched_wakeup_new"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    process_stats_config { scan_all_processes_on_start: true }
  }
}
duration_ms: 60000
EOF
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/sched.pftrace < sched.cfg
adb pull /data/misc/perfetto-traces/sched.pftrace
```

and in `trace_processor_shell sched.pftrace`:

```sql
select p.name as process, t.name as thread, sum(s.dur) / 1e6 as cpu_ms,
  (select count(*) from thread_state ts where ts.utid = t.utid and ts.state = 'R') as wakeups
from sched s join thread t using(utid) join process p using(upid)
where p.name like 'ai.aneesiqbal.rozan%' group by t.utid order by cpu_ms desc;
```

Add `atrace_categories: "view"` and `"gfx"` and `atrace_apps:
"ai.aneesiqbal.rozan.launcher"` to the ftrace config to see the frames and
the `Rozan.*` sections in the same trace. On an emulator, compare builds
in alternating runs: the host's load moved one build's CPU time by up to
30% between runs, its wakeups by under 4%.

**Native heap (heapprofd)**, from process start:

```sh
cat > heapprofd.cfg << 'EOF'
buffers { size_kb: 131072 fill_policy: DISCARD }
data_sources {
  config {
    name: "android.heapprofd"
    heapprofd_config {
      sampling_interval_bytes: 4096
      process_cmdline: "ai.aneesiqbal.rozan.launcher"
      heaps: "libc.malloc"
      heaps: "com.android.art"
      shmem_size_bytes: 8388608
      block_client: true
      continuous_dump_config { dump_phase_ms: 5000 dump_interval_ms: 5000 }
    }
  }
}
duration_ms: 25000
EOF
adb shell am force-stop ai.aneesiqbal.rozan.launcher
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/heap.pftrace --background < heapprofd.cfg
adb shell am start -n ai.aneesiqbal.rozan.launcher/.HomeActivity
# ... wait for duration_ms, then:
adb pull /data/misc/perfetto-traces/heap.pftrace
```

Use `process_cmdline: "ai.aneesiqbal.rozan.launcher:airplay"` for the
receiver. In trace processor, `heap_profile_allocation` has the allocations
per dump; `experimental_ancestor_stack_profile_callsite` gives each
callsite's stack.

**Java heap graph (`java_hprof`)**: the same with

```
data_sources {
  config {
    name: "android.java_hprof"
    java_hprof_config { process_cmdline: "ai.aneesiqbal.rozan.launcher" dump_smaps: true }
  }
}
duration_ms: 10000
```

started while the process runs; `heap_graph_object` joined with
`heap_graph_class` lists reachable objects (filter `heap_type = 'app'` to
leave out the zygote and boot image; API 31 does not fill `heap_type`).

**CPU (simpleperf)**, during an interaction:

```sh
adb shell simpleperf record --app ai.aneesiqbal.rozan.launcher -e cpu-clock -f 2000 -g --duration 14 -o /data/local/tmp/perf.data
adb shell simpleperf report -i /data/local/tmp/perf.data --sort comm,dso
```

Emulators have no hardware counters, hence `cpu-clock`. R8 renames classes
in `benchmarkRelease`; map names back with
`app/launcher/build/outputs/mapping/benchmarkRelease/mapping.txt`.
