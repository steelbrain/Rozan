#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml (SHA-256 of every dependency, plugin and tool
# artifact Gradle downloads) after a dependency change.
#
# usage: scripts/update-verification-metadata.sh
#
# It resolves everything in a fresh, empty Gradle home (set VERIFICATION_GRADLE_HOME to reuse one),
# so it downloads every dependency and the Gradle distribution.
#
# Gradle records only what the build resolves on this machine, but AGP's aapt2 is a per-OS
# artifact: a Mac records aapt2-<version>-osx.jar, while CI needs -linux.jar. The script adds the
# other operating systems' aapt2 jars from Google Maven, checking each download against the SHA-1
# Google Maven publishes next to it.
set -euo pipefail

cd "$(dirname "$0")/.."
metadata=gradle/verification-metadata.xml

# An empty Gradle home and --refresh-dependencies: with anything already cached, Gradle does not
# record some parent POMs and BOMs of the build's own classpath (bc-jdk18on-bom, guava-parent,
# junit-bom), and a clean CI cache then fails verification on them.
gradle_home="${VERIFICATION_GRADLE_HOME:-$(mktemp -d)}"
GRADLE_USER_HOME="$gradle_home" ./gradlew --write-verification-metadata sha256 --refresh-dependencies \
  --no-configuration-cache -q \
  check assembleDebug assembleRelease assembleDebugAndroidTest \
  :app:launcher:assembleBenchmarkRelease :benchmark:assemble :benchmark-airplay:assemble \
  :tools:devserver:installDist

version="$(sed -n 's/.*name="aapt2" version="\([^"]*\)".*/\1/p' "$metadata" | head -n 1)"
[ -n "$version" ] || { echo "no aapt2 entry in $metadata" >&2; exit 1; }

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"; [ -n "${VERIFICATION_GRADLE_HOME:-}" ] || rm -rf "$gradle_home"' EXIT
base="https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/$version"
for os in linux osx windows; do
  name="aapt2-$version-$os.jar"
  if grep -q "artifact name=\"$name\"" "$metadata"; then continue; fi
  curl -fsSL -o "$workdir/$name" "$base/$name"
  curl -fsSL -o "$workdir/$name.sha1" "$base/$name.sha1"
  if [ "$(shasum -a 1 "$workdir/$name" | cut -d' ' -f1)" != "$(tr -d '[:space:]' < "$workdir/$name.sha1")" ]; then
    echo "$name does not match its published SHA-1" >&2
    exit 1
  fi
  sha256="$(shasum -a 256 "$workdir/$name" | cut -d' ' -f1)"
  python3 -I - "$metadata" "$version" "$name" "$sha256" << 'PY'
import sys

path, version, name, sha256 = sys.argv[1:]
text = open(path, encoding="utf-8").read()
anchor = f'<component group="com.android.tools.build" name="aapt2" version="{version}">\n'
entry = (
    f'         <artifact name="{name}">\n'
    f'            <sha256 value="{sha256}" origin="Google Maven, SHA-1 checked against the published .sha1"/>\n'
    "         </artifact>\n"
)
if anchor not in text:
    sys.exit(f"no aapt2 {version} component in {path}")
open(path, "w", encoding="utf-8").write(text.replace(anchor, anchor + entry, 1))
PY
  echo "added $name"
done
