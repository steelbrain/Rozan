# Releasing

Releases are built and published by `.github/workflows/release.yml` when a tag
`vX.Y.Z` is pushed. The workflow:

1. checks that the tag equals `v` + `rozan.version` from `gradle.properties`
   and that `CHANGELOG.md` has a `## [X.Y.Z]` section,
2. runs `./gradlew check assembleRelease` with release signing required,
3. verifies both APK signatures with `apksigner`,
4. creates a GitHub release named "Rozan X.Y.Z" with
   `rozan-launcher-X.Y.Z.apk`, `rozan-airplay-X.Y.Z.apk`, `SHA256SUMS`, and the
   changelog section as release notes. Versions with a suffix
   (`1.2.0-rc.1`) are marked as pre-releases.

## Cutting a release

1. Move the `Unreleased` entries in `CHANGELOG.md` into a new
   `## [X.Y.Z] - YYYY-MM-DD` section.
2. Set `rozan.version=X.Y.Z` in `gradle.properties`. The Android `versionCode`
   is derived from it (`major * 1_000_000 + minor * 1_000 + patch`), so it
   always increases. A pre-release (`X.Y.Z-rc.N`) has the same `versionCode`
   as `X.Y.Z`, so the final release installs over it.
3. Commit, then tag and push:

   ```sh
   git tag -s vX.Y.Z -m "Rozan X.Y.Z"
   git push origin vX.Y.Z
   ```

## Signing key

Both apps are signed with one RSA-4096 key (`CN=Rozan`, alias `rozan`, PKCS12).
Android only installs an update over an existing install when the signing key
matches, so losing the key means users must uninstall before installing a new
build. Keep the keystore backed up outside the repository.

The workflow reads four repository secrets:

| Secret | Contents |
| --- | --- |
| `ROZAN_KEYSTORE_BASE64` | the `.jks` file, base64-encoded |
| `ROZAN_KEYSTORE_PASSWORD` | keystore password |
| `ROZAN_KEY_ALIAS` | key alias (`rozan`) |
| `ROZAN_KEY_PASSWORD` | key password (same as the keystore password for PKCS12) |

`scripts/release/upload-signing-secrets.sh <credentials.env>` sets all four with
the GitHub CLI.

Local release builds use the same environment variables
(`ROZAN_KEYSTORE_FILE`, `ROZAN_KEYSTORE_PASSWORD`, `ROZAN_KEY_ALIAS`,
`ROZAN_KEY_PASSWORD`). Without them, `assembleRelease` signs with the debug key
so the APK can still be installed for testing.

Certificate SHA-256 fingerprint of the current key:

```
F9:4A:53:EE:35:B9:6D:45:02:E9:E5:78:18:4D:2D:CD:3C:5D:94:14:75:95:19:93:35:DE:B1:26:FD:33:22:EB
```
