#!/usr/bin/env bash
# Runs the launcher through the memory scenarios that config/perf-budgets.json
# budgets and writes the measurements as JSON for scripts/perf/check-budgets.py.
#
# usage: memory-scenarios.sh [--apk PATH] [--out FILE] [--profile NAME]
#   --apk      install this launcher APK first. The AirPlay scenarios use the
#              debug event hooks, so it must be a benchmarkRelease build
#              (release code plus the hooks; what the budgets were set with)
#              or a debug build.
#   --out      results file (default: build/perf/results.json)
#   --profile  budget profile; default emulator-api<sdk>-<abi> on an
#              emulator and device-api<sdk>-<model> otherwise
#
# Scenarios, each from the state the previous one left, each measuring both
# processes: the launcher ("main") and the AirPlay receiver ("airplay", in
# the :airplay process that Home starts while AirPlay is on, the default):
#   home_idle        cold start of the home screen, then 8 s untouched
#   home_browsed     two passes over the grid with the D-pad, 5 s settle
#   screensaver      the Screensaver tile, 6 s in
#   airplay_mirror   fake mirroring session (720p H.264 clip) over Home, 10 s in
#   airplay_audio    fake audio session with artwork (now playing), 6 s in
#
# Uses the device in ANDROID_SERIAL (or adb's only device). Every wait has a
# hard limit, and every adb call is bounded by ADB_TIMEOUT (default 30 s).
# Animations are on during the run (scales 1) and restored afterwards, and
# the launcher is stopped at the end, so its receiver does not keep port 7000
# from tests run after this.
set -euo pipefail

package="ai.aneesiqbal.rozan.launcher"
debug_action="ai.aneesiqbal.rozan.airplay.debug"
here="$(cd "$(dirname "$0")" && pwd)"
out="build/perf/results.json"
apk=""
profile=""

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0" >&2
  exit 64
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) apk="${2:?--apk needs a path}"; shift 2 ;;
    --out) out="${2:?--out needs a path}"; shift 2 ;;
    --profile) profile="${2:?--profile needs a name}"; shift 2 ;;
    *) usage ;;
  esac
done

adb_timeout="${ADB_TIMEOUT:-30}"
adb="${ADB:-adb}"
export ADB="$adb" ADB_TIMEOUT="$adb_timeout"

adb_once() {
  if command -v timeout > /dev/null; then
    timeout "$adb_timeout" "$adb" "$@"
  else
    perl -e 'alarm shift; exec @ARGV or die "exec: $!"' "$adb_timeout" "$adb" "$@"
  fi
}

# Runs adb, retrying when the transport drops (an emulator restarts adbd once shortly after boot:
# "device offline"). Any other failure, such as a shell command's own exit status, is returned as is.
run_adb() {
  local attempt status out err
  out="$(mktemp)"
  err="$(mktemp)"
  for attempt in 1 2 3 4 5; do
    status=0
    adb_once "$@" > "$out" 2> "$err" || status=$?
    if [ "$status" -eq 0 ] || ! grep -qE "device offline|device '[^']*' not found|no devices/emulators found|device still connecting|closed" "$err"; then
      cat "$out"
      cat "$err" >&2
      rm -f "$out" "$err"
      return "$status"
    fi
    log "adb $1: $(head -n 1 "$err"); waiting for the device (attempt $attempt)"
    local deadline=$((SECONDS + 30))
    until adb_once get-state > /dev/null 2>&1; do
      [ "$SECONDS" -lt "$deadline" ] || break
      sleep 0.5
    done
  done
  cat "$err" >&2
  rm -f "$out" "$err"
  return "$status"
}

shell() { run_adb shell "$@" | tr -d '\r'; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

fail() {
  log "error: $*"
  exit 1
}

# Waits until "$@" succeeds, at most $1 seconds.
wait_for() {
  local limit="$1" what="$2"
  shift 2
  local deadline=$((SECONDS + limit))
  until "$@" > /dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] || fail "timed out after ${limit}s waiting for $what"
    sleep 0.5
  done
}

resumed() { shell dumpsys activity activities | grep -E 'ResumedActivity' | grep -q "$1"; }
running() { [ -n "$(shell pidof "$1")" ]; }
not_resumed() { ! resumed "$1"; }

keys() {
  # One device shell for the whole sequence, 150 ms apart: a quick human pace.
  shell "for key in $*; do input keyevent KEYCODE_\$key; sleep 0.15; done"
}

# Two zig-zag passes over the built-in row and two app rows, then back up.
browse() {
  local pass
  for pass in 1 2; do
    keys DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_DOWN \
      DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_LEFT DPAD_DOWN \
      DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_UP DPAD_UP
    log "browse pass $pass done"
  done
}

# Both processes: the launcher ("main") and the AirPlay receiver ("airplay").
snapshots() {
  local scenario="$1" process key
  for process in "$package" ":airplay"; do
    key=main
    [ "$process" = ":airplay" ] && key=airplay
    "$here/meminfo.sh" "$package" "$process" > "$work/$scenario.$key.json"
    log "$scenario $key: $(grep -m1 '"total_pss"' "$work/$scenario.$key.json" | tr -d ' ,')"
  done
}

# Frame statistics restart for both processes, so each scenario reports only its own frames.
gfx_reset() {
  shell dumpsys gfxinfo "$package" reset > /dev/null
  shell dumpsys gfxinfo "$airplay_pid" reset > /dev/null
}

work="$(mktemp -d)"
trap 'restore_animations; stop_launcher; rm -rf "$work"' EXIT

stop_launcher() { run_adb shell am force-stop "$package" > /dev/null 2>&1 || true; }

saved_scales=""
restore_animations() {
  [ -n "$saved_scales" ] || return 0
  local name value
  for name in window_animation_scale transition_animation_scale animator_duration_scale; do
    value="$(printf '%s\n' "$saved_scales" | awk -v n="$name" '$1 == n { print $2 }')"
    if [ -z "$value" ] || [ "$value" = null ]; then value=1; fi
    run_adb shell settings put global "$name" "$value" || true
  done
}

wait_for 60 "device" run_adb get-state
sdk="$(shell getprop ro.build.version.sdk)"
abi="$(shell getprop ro.product.cpu.abi)"
model="$(shell getprop ro.product.model)"
qemu="$(shell getprop ro.kernel.qemu)$(shell getprop ro.boot.qemu)"
if [ -z "$profile" ]; then
  case "$qemu" in
    *1*) profile="emulator-api${sdk}-${abi}" ;;
    *) profile="device-api${sdk}-$(printf '%s' "$model" | tr -c 'A-Za-z0-9\n' '-')" ;;
  esac
fi
log "device: API $sdk $abi ($model), profile $profile"

if [ -n "$apk" ]; then
  log "installing $apk"
  run_adb install -r "$apk" > /dev/null
fi
shell pm path "$package" | grep -q package: || fail "$package is not installed"
# Default settings (AirPlay on, so Home starts the receiver), nothing left from earlier tests.
shell pm clear "$package" > /dev/null
shell cmd package query-receivers --brief -a "$debug_action.START_MIRROR" "$package" | grep -q DebugEventsReceiver ||
  fail "$package has no debug event hooks: install a benchmarkRelease or debug build"

for name in window_animation_scale transition_animation_scale animator_duration_scale; do
  saved_scales="$saved_scales$name $(shell settings get global "$name")"$'\n'
  shell settings put global "$name" 1
done
shell input keyevent KEYCODE_WAKEUP
shell appops set "$package" SYSTEM_ALERT_WINDOW allow

log "home_idle"
shell am force-stop "$package"
start_ms="$(shell am start -W -n "$package/.HomeActivity" | awk -F': ' '/^TotalTime/ { print $2 }')"
wait_for 20 "home screen" resumed "$package/.HomeActivity"
# Home starts the AirPlay receiver (on by default) in the :airplay process.
wait_for 20 "the :airplay process" running "$package:airplay"
airplay_pid="$(shell pidof "$package:airplay")"
gfx_reset
sleep 8
snapshots home_idle

log "home_browsed"
gfx_reset
browse
sleep 5
snapshots home_browsed

log "screensaver"
# Home again (as the Home button would): back to the first tile, then right to the Screensaver tile.
shell am start -n "$package/.HomeActivity" > /dev/null 2>&1
gfx_reset
keys DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_CENTER
sleep 6
snapshots screensaver
keys BACK

log "airplay_mirror"
shell am broadcast -p "$package" -a "$debug_action.START_MIRROR" > /dev/null
wait_for 20 "mirroring screen" resumed "MirrorActivity"
gfx_reset
sleep 10
snapshots airplay_mirror
shell am broadcast -p "$package" -a "$debug_action.STOP_MIRROR" > /dev/null
wait_for 20 "mirroring screen to close" not_resumed "MirrorActivity"

log "airplay_audio"
shell am broadcast -p "$package" -a "$debug_action.START_AUDIO" > /dev/null
wait_for 20 "now-playing screen" resumed "NowPlayingActivity"
gfx_reset
sleep 6
snapshots airplay_audio
shell am broadcast -p "$package" -a "$debug_action.STOP_AUDIO" > /dev/null
if resumed "NowPlayingActivity"; then keys BACK; fi
[ "$(shell pidof "$package:airplay")" = "$airplay_pid" ] || fail "the :airplay process restarted during the run"

mkdir -p "$(dirname "$out")"
python3 - "$work" "$out" "$profile" "$sdk" "$abi" "$model" "${start_ms:-}" << 'EOF'
import json, pathlib, sys
work, out, profile, sdk, abi, model, start_ms = sys.argv[1:]
memory = {}
for path in sorted(pathlib.Path(work).glob("*.json")):
    scenario, process, _ = path.name.split(".")
    memory.setdefault(scenario, {})[process] = json.loads(path.read_text())
if start_ms:
    memory["home_idle"]["main"]["timing"] = {"start_ms": int(start_ms)}
results = {
    "schema": 1,
    "profile": profile,
    "device": {"sdk": int(sdk), "abi": abi, "model": model},
    "memory": memory,
}
pathlib.Path(out).write_text(json.dumps(results, indent=2) + "\n")
EOF
log "wrote $out"
