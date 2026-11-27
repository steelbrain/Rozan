#!/usr/bin/env bash
# Prints the CHANGELOG.md section for a version (without its heading).
set -euo pipefail

version="${1:?usage: notes.sh <version>}"
awk -v version="$version" '
  /^## \[/ {
    if (found) exit
    if (index($0, "## [" version "]") == 1) { found = 1; next }
  }
  found { print }
' CHANGELOG.md | sed -e '/./,$!d'
