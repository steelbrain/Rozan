#!/usr/bin/env bash
# Creates the GitHub release for a tag from the files collect.sh produced.
set -euo pipefail

tag="${1:?usage: publish.sh <tag> <dir>}"
dir="${2:?usage: publish.sh <tag> <dir>}"
version="${tag#v}"

flags=()
case "$version" in
  *-*) flags+=(--prerelease) ;;
esac

gh release create "$tag" \
  --verify-tag \
  --title "Rozan $version" \
  --notes-file "$dir/notes.md" \
  "${flags[@]}" \
  "$dir"/*.apk "$dir/SHA256SUMS"
