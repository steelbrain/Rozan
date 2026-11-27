#!/usr/bin/env bash
# Prints one app process's memory and frame statistics as JSON, from
# `dumpsys meminfo` and `dumpsys gfxinfo`. For quick local checks and CI.
#
# usage: meminfo.sh <package> [process]
#   package  the app's package, e.g. ai.aneesiqbal.rozan.launcher
#   process  the process name; default the package (the main process). A
#            leading ':' is appended to the package, e.g. ':airplay'.
#
# Uses the device in ANDROID_SERIAL (or adb's only device). Exits 2 when the
# process is not running. Every adb call is bounded by ADB_TIMEOUT seconds
# (default 30).
#
# Output (sizes in KB; null when the device does not report a value):
#   meminfo_kb   from the "App Summary" after a GC: total_pss, total_rss,
#                java_heap, native_heap, code, stack, graphics, private_other,
#                system
#   objects      views, view_roots, app_contexts, activities (counted after
#                a GC by ART, so retained Activities show up here)
#   gralloc      buffers: graphic buffers the process allocated (hardware
#                bitmaps, window surfaces), estimate_kb: their size from
#                width x height x bytes per pixel. Emulators keep these
#                buffers on the host and report Graphics as 0; this estimate
#                is what they would cost on a device.
#   gfxinfo      frame statistics since the last `dumpsys gfxinfo <pkg>
#                reset`: frames, janky_frames, janky_percent, p50/p90/p95/p99
#                frame time in ms (null when no frame was drawn)
set -euo pipefail

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0" >&2
  exit 64
}

[ $# -ge 1 ] && [ $# -le 2 ] || usage
package="$1"
process="${2:-$package}"
case "$process" in
  :*) process="${package}${process}" ;;
esac

adb_timeout="${ADB_TIMEOUT:-30}"
adb="${ADB:-adb}"

# adb with a hard time limit, so a wedged device fails the caller instead of hanging it.
# (`timeout` is GNU coreutils; stock macOS has only perl's alarm.)
run_adb() {
  if command -v timeout > /dev/null; then
    timeout "$adb_timeout" "$adb" "$@"
  else
    perl -e 'alarm shift; exec @ARGV or die "exec: $!"' "$adb_timeout" "$adb" "$@"
  fi
}

pid="$(run_adb shell pidof "$process" | tr -d '\r' | awk '{ print $1 }')" || true
if [ -z "$pid" ]; then
  echo "meminfo.sh: process $process is not running" >&2
  exit 2
fi

# Every `dumpsys meminfo` makes the app run an explicit GC (logcat shows one
# per dump). Until a GC runs, memory the app has already let go of still
# counts: about 9 MB for the launcher 10 s after a cold start. So the first
# dump only collects that garbage and the second one is the measurement:
# what the app holds, the same every run.
run_adb shell dumpsys meminfo "$pid" > /dev/null
meminfo="$(run_adb shell dumpsys meminfo "$pid" | tr -d '\r')"
gfxinfo="$(run_adb shell dumpsys gfxinfo "$pid" | tr -d '\r')"
sdk="$(run_adb shell getprop ro.build.version.sdk | tr -d '\r')"

# Pss column of an "App Summary" row (the first number after the label).
summary_value() {
  printf '%s\n' "$meminfo" | awk -v label="$1" '
    /^ *App Summary/ { in_summary = 1; next }
    /^ *Objects/ { in_summary = 0 }
    in_summary {
      line = $0
      sub(/^ +/, "", line)
      if (index(line, label ":") == 1) {
        rest = substr(line, length(label) + 2)
        split(rest, fields, " +")
        for (i = 1; i in fields; i++) if (fields[i] ~ /^[0-9]+$/) { print fields[i]; exit }
      }
    }'
}

# A value from the "TOTAL PSS: n  TOTAL RSS: n" line (API 30+) or "TOTAL: n" (API 28-29).
summary_total() {
  printf '%s\n' "$meminfo" | awk -v key="$1" '
    /^ *App Summary/ { in_summary = 1; next }
    in_summary && /TOTAL/ {
      n = split($0, words, " +")
      for (i = 1; i <= n; i++) {
        if (key == "PSS" && (words[i] == "TOTAL:" || (words[i] == "TOTAL" && words[i + 1] == "PSS:"))) {
          print (words[i] == "TOTAL:") ? words[i + 1] : words[i + 2]; exit
        }
        if (key == "RSS" && words[i] == "TOTAL" && words[i + 1] == "RSS:") { print words[i + 2]; exit }
      }
    }'
}

# A count from the "Objects" section, e.g. "Activities:".
object_count() {
  printf '%s\n' "$meminfo" | awk -v label="$1:" '
    /^ *Objects/ { in_objects = 1; next }
    in_objects && /^ *$/ { exit }
    in_objects {
      n = split($0, words, " +")
      for (i = 1; i < n; i++) if (words[i] == label) { print words[i + 1]; exit }
    }'
}

# "Janky frames: 12 (9.76%)" and friends.
gfx_line() {
  printf '%s\n' "$gfxinfo" | awk -v prefix="$1" '
    index($0, prefix) == 1 {
      rest = substr($0, length(prefix) + 1)
      gsub(/[^0-9.]+/, " ", rest)
      split(rest, numbers, " ")
      print numbers[1]
      exit
    }'
}

janky_percent() {
  printf '%s\n' "$gfxinfo" | awk '/^Janky frames: / { gsub(/[()%]/, "", $4); print $4; exit }'
}

# Graphic buffers in the process's GraphicBufferAllocator table:
#   handle | size | W (stride) x H | layers | format | usage | requestor
# prints "count estimate_kb".
gralloc_estimate() {
  printf '%s\n' "$gfxinfo" | awk -F'|' '
    /^GraphicBufferAllocator buffers:/ { in_table = 1; next }
    in_table && NF < 7 { in_table = 0 }
    in_table && $1 ~ /0x/ {
      dims = $3
      gsub(/\([^)]*\)/, " ", dims)
      split(dims, wh, "x")
      w = wh[1] + 0; h = wh[2] + 0; layers = $4 + 0; format = $5 + 0
      if (layers < 1) layers = 1
      # android_pixel_format_t: 1/2 RGBA/RGBX_8888, 3 RGB_888, 4 RGB_565, 22 RGBA_FP16,
      # 43 RGBA_1010102, 0x11/0x23/0x32315659 YUV 4:2:0, 0x20 RAW16, 0x21 BLOB.
      bpp = 4
      if (format == 3) bpp = 3
      else if (format == 4 || format == 32) bpp = 2
      else if (format == 22) bpp = 8
      else if (format == 17 || format == 35 || format == 842094169) bpp = 1.5
      else if (format == 33) bpp = 1
      count++
      bytes += w * h * bpp * layers
    }
    END { printf "%d %d\n", count, bytes / 1024 }'
}

# JSON number or null.
num() {
  if [ -n "${1:-}" ]; then printf '%s' "$1"; else printf 'null'; fi
}

frames="$(gfx_line 'Total frames rendered: ')"
janky_frames="$(gfx_line 'Janky frames: ')"
janky="$(janky_percent)"
p50="" p90="" p95="" p99=""
# With no frames, gfxinfo prints its histogram ceiling (4950ms) as every percentile.
if [ -n "$frames" ] && [ "$frames" -gt 0 ]; then
  p50="$(gfx_line '50th percentile: ')"
  p90="$(gfx_line '90th percentile: ')"
  p95="$(gfx_line '95th percentile: ')"
  p99="$(gfx_line '99th percentile: ')"
else
  janky=""
fi
read -r gralloc_count gralloc_kb < <(gralloc_estimate)

cat << EOF
{
  "package": "$package",
  "process": "$process",
  "pid": $pid,
  "sdk": $(num "$sdk"),
  "meminfo_kb": {
    "total_pss": $(num "$(summary_total PSS)"),
    "total_rss": $(num "$(summary_total RSS)"),
    "java_heap": $(num "$(summary_value 'Java Heap')"),
    "native_heap": $(num "$(summary_value 'Native Heap')"),
    "code": $(num "$(summary_value 'Code')"),
    "stack": $(num "$(summary_value 'Stack')"),
    "graphics": $(num "$(summary_value 'Graphics')"),
    "private_other": $(num "$(summary_value 'Private Other')"),
    "system": $(num "$(summary_value 'System')")
  },
  "objects": {
    "views": $(num "$(object_count 'Views')"),
    "view_roots": $(num "$(object_count 'ViewRootImpl')"),
    "app_contexts": $(num "$(object_count 'AppContexts')"),
    "activities": $(num "$(object_count 'Activities')")
  },
  "gralloc": {
    "buffers": $(num "$gralloc_count"),
    "estimate_kb": $(num "$gralloc_kb")
  },
  "gfxinfo": {
    "frames": $(num "$frames"),
    "janky_frames": $(num "$janky_frames"),
    "janky_percent": $(num "$janky"),
    "p50_ms": $(num "$p50"),
    "p90_ms": $(num "$p90"),
    "p95_ms": $(num "$p95"),
    "p99_ms": $(num "$p99")
  }
}
EOF
