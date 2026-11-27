#!/usr/bin/env bash
# Fails unless the tag (first argument) is "v" + rozan.version from gradle.properties.
set -euo pipefail

tag="${1:?usage: check-version.sh <tag>}"
version="$(sed -n 's/^rozan\.version=//p' gradle.properties)"
if [ "$tag" != "v$version" ]; then
  echo "::error::Tag $tag does not match rozan.version=$version in gradle.properties"
  exit 1
fi
if ! grep -q "^## \[$version\]" CHANGELOG.md; then
  echo "::error::CHANGELOG.md has no section for $version"
  exit 1
fi
echo "Releasing $version"
