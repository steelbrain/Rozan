#!/usr/bin/env bash
# Boots an Android TV emulator and runs the instrumented tests on it.
#
# usage: emulator-tests.sh <api-level> <abi>   e.g. emulator-tests.sh 36 x86_64
#
# With PERF_SCENARIOS_APK set (a launcher benchmarkRelease APK), it then runs
# the memory scenarios on the same emulator and checks them against
# config/perf-budgets.json; the results go to PERF_RESULTS (default
# build/perf/results.json). INSTRUMENTED_TESTS=false skips the tests (CI runs
# the memory scenarios on their own runner).
#
# Every wait has a hard limit and checks that the emulator process is still
# alive, so a crashed or hung emulator fails the job within minutes instead
# of being polled until the job timeout. adb commands are retried because the
# emulator restarts adbd once right after boot ("device offline").
set -euo pipefail

api="${1:?usage: emulator-tests.sh <api-level> <abi>}"
abi="${2:?usage: emulator-tests.sh <api-level> <abi>}"
image="system-images;android-${api};android-tv;${abi}"
port="${EMULATOR_PORT:-5554}"
serial="emulator-${port}"
boot_limit_seconds=300
# Installing and running every module's instrumented tests, one module at a time, takes about 4
# minutes on a local API 31 or 36 emulator (about 2 of them the leak tests); the limit leaves room
# for a slower runner.
tests_limit_seconds=900
workdir="${RUNNER_TEMP:-$(mktemp -d)}"
# Keep the AVD in a known place; avdmanager's default location differs
# between SDK versions and hosts.
export ANDROID_AVD_HOME="$workdir/avd"
mkdir -p "$ANDROID_AVD_HOME"

sdk="${ANDROID_HOME:?ANDROID_HOME must be set}"
sdkmanager="$(find "$sdk/cmdline-tools" -path '*/bin/sdkmanager' | sort -V | tail -n 1)"
avdmanager="$(dirname "$sdkmanager")/avdmanager"
adb="$sdk/platform-tools/adb"

echo "::group::Install emulator and $image"
yes | "$sdkmanager" --licenses > /dev/null || true
"$sdkmanager" --install emulator platform-tools "$image" > /dev/null
echo "::endgroup::"

echo "::group::Create AVD"
echo no | "$avdmanager" create avd --force --name ci-tv --package "$image" --device tv_1080p
printf 'hw.cpu.ncore=2\nhw.ramSize=2048\ndisk.dataPartition.size=4G\n' >> "$ANDROID_AVD_HOME/ci-tv.avd/config.ini"
echo "::endgroup::"

"$adb" start-server
"$sdk/emulator/emulator" -avd ci-tv -port "$port" -no-window -no-audio -no-boot-anim \
  -no-snapshot -gpu swiftshader_indirect -camera-back none -camera-front none \
  > "$workdir/emulator.log" 2>&1 &
emulator_pid=$!

cleanup() {
  "$adb" -s "$serial" emu kill > /dev/null 2>&1 || true
  kill "$emulator_pid" > /dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "::error::$1"
  echo "::group::Emulator log"
  tail -n 200 "$workdir/emulator.log" || true
  echo "::endgroup::"
  exit 1
}

# Waits until "$@" succeeds, at most $1 seconds, failing if the emulator dies.
wait_for() {
  local limit="$1" description="$2"
  shift 2
  local deadline=$((SECONDS + limit))
  until "$@" > /dev/null 2>&1; do
    kill -0 "$emulator_pid" 2> /dev/null || fail "Emulator exited while waiting for $description"
    [ "$SECONDS" -lt "$deadline" ] || fail "Timed out after ${limit}s waiting for $description"
    sleep 2
  done
}

booted() { [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = "1" ]; }
package_manager_ready() { "$adb" -s "$serial" shell pm path android | grep -q package; }

echo "::group::Boot"
wait_for "$boot_limit_seconds" "boot_completed" booted
wait_for 60 "package manager" package_manager_ready
echo "Booted after ${SECONDS}s"
echo "::endgroup::"

# Retries an adb shell command across the post-boot adbd restart.
adb_shell() {
  local attempt
  for attempt in 1 2 3 4 5; do
    if "$adb" -s "$serial" shell "$@"; then return 0; fi
    echo "adb shell $* failed (attempt $attempt); waiting for the device"
    wait_for 30 "device to reconnect" "$adb" -s "$serial" get-state
  done
  fail "adb shell $* kept failing"
}

adb_shell settings put global window_animation_scale 0
adb_shell settings put global transition_animation_scale 0
adb_shell settings put global animator_duration_scale 0
adb_shell settings put system screen_off_timeout 2147483647
adb_shell input keyevent KEYCODE_WAKEUP

# One module at a time (--no-parallel): the receiver tests of :airplay:android and the launcher, which
# starts its own receiver on port 7000 whenever Home comes up, must not share the emulator's screen and
# port at the same moment. The whole run has a hard limit, like every wait above.
free -m || true
if [ "${INSTRUMENTED_TESTS:-true}" != "false" ]; then
  echo "::group::Instrumented tests"
  ANDROID_SERIAL="$serial" ./gradlew connectedDebugAndroidTest --no-parallel --stacktrace &
  tests_pid=$!
  tests_deadline=$((SECONDS + tests_limit_seconds))
  while kill -0 "$tests_pid" 2> /dev/null; do
    if ! kill -0 "$emulator_pid" 2> /dev/null; then
      kill "$tests_pid" 2> /dev/null || true
      fail "Emulator exited during the instrumented tests"
    fi
    if [ "$SECONDS" -ge "$tests_deadline" ]; then
      kill "$tests_pid" 2> /dev/null || true
      fail "Instrumented tests still running after ${tests_limit_seconds}s"
    fi
    sleep 5
  done
  wait "$tests_pid"
  echo "::endgroup::"
fi

# Memory budgets (config/perf-budgets.json) on the same emulator, when the
# workflow built the measurement APK: the launcher's benchmarkRelease build
# (release code plus the AirPlay debug hooks the scenarios drive).
if [ -n "${PERF_SCENARIOS_APK:-}" ]; then
  echo "::group::Memory scenarios"
  results="${PERF_RESULTS:-build/perf/results.json}"
  ADB="$adb" ANDROID_SERIAL="$serial" timeout 600 scripts/perf/memory-scenarios.sh \
    --apk "$PERF_SCENARIOS_APK" --out "$results"
  echo "::endgroup::"
  python3 scripts/perf/check-budgets.py "$results"
fi
