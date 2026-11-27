#!/usr/bin/env bash
# Copies the signed release APKs into an output directory with versioned names,
# verifies their signatures, and writes SHA256SUMS and release notes.
set -euo pipefail

tag="${1:?usage: collect.sh <tag> <outdir>}"
out="${2:?usage: collect.sh <tag> <outdir>}"
version="${tag#v}"
mkdir -p "$out"

apksigner="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n 1)"

copy() {
  local module="$1" name="$2"
  local apk="app/$module/build/outputs/apk/release/$module-release.apk"
  if [ ! -f "$apk" ]; then
    echo "::error::Missing $apk"
    exit 1
  fi
  "$apksigner" verify --min-sdk-version 28 --print-certs "$apk"
  cp "$apk" "$out/$name-$version.apk"
}

copy launcher rozan-launcher
copy airplay rozan-airplay

(cd "$out" && sha256sum ./*.apk > SHA256SUMS)
scripts/release/notes.sh "$version" > "$out/notes.md"
cat "$out/SHA256SUMS"
