#!/usr/bin/env bash
# Stores the release signing key as GitHub Actions secrets for this repository.
# Reads a credentials file in the form written when the key was generated:
#   ROZAN_KEYSTORE_FILE=..., ROZAN_KEYSTORE_PASSWORD=..., ROZAN_KEY_ALIAS=..., ROZAN_KEY_PASSWORD=...
# Requires an authenticated GitHub CLI (`gh auth login`).
set -euo pipefail

credentials="${1:-$HOME/.rozan-signing/credentials.env}"
# shellcheck source=/dev/null
source "$credentials"

base64 < "$ROZAN_KEYSTORE_FILE" | tr -d '\n' | gh secret set ROZAN_KEYSTORE_BASE64
printf '%s' "$ROZAN_KEYSTORE_PASSWORD" | gh secret set ROZAN_KEYSTORE_PASSWORD
printf '%s' "$ROZAN_KEY_ALIAS" | gh secret set ROZAN_KEY_ALIAS
printf '%s' "$ROZAN_KEY_PASSWORD" | gh secret set ROZAN_KEY_PASSWORD
echo "Signing secrets updated."
