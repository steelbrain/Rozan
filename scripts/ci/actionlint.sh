#!/usr/bin/env bash
# Downloads a pinned actionlint release, verifies its checksum and lints the
# GitHub Actions workflows. Used by CI; locally, `brew install actionlint` and
# run `actionlint` instead.
set -euo pipefail

version="1.7.12"
sha256="8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8"
archive="actionlint_${version}_linux_amd64.tar.gz"
workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

curl -fsSL -o "$workdir/$archive" \
  "https://github.com/rhysd/actionlint/releases/download/v${version}/${archive}"
echo "${sha256}  $workdir/$archive" | sha256sum --check --strict
tar -xzf "$workdir/$archive" -C "$workdir" actionlint
"$workdir/actionlint" -color
