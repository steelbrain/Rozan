package ai.aneesiqbal.rozan.launcher.update

/** A file attached to a release. */
internal data class ReleaseAsset(
    /** File name, such as `rozan-launcher-0.2.0.apk`. */
    val name: String,
    /** Size in bytes, as GitHub states it. */
    val size: Long,
    /** Where to download it (`browser_download_url`). */
    val url: String,
)

/**
 * The parts of GitHub's "latest release" response the updater uses
 * (`GET /repos/{owner}/{repo}/releases/latest`): the version from the tag
 * `vX.Y.Z`, the launcher APK `rozan-launcher-X.Y.Z.apk` and `SHA256SUMS`, the
 * names `scripts/release/collect.sh` gives them.
 */
internal data class LatestRelease(
    val version: ReleaseVersion,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset,
) {
    companion object {
        /** Largest launcher APK accepted. Release APKs are about 8 MB (config/perf-budgets.json). */
        const val MAX_APK_BYTES = 64L * 1024 * 1024

        /** Longest download URL accepted. */
        const val MAX_URL_LENGTH = 2048

        /** The checksum file's name in every release. */
        const val CHECKSUMS_NAME = "SHA256SUMS"

        /** The launcher APK's name in the release of [version]. */
        fun apkName(version: ReleaseVersion): String = "rozan-launcher-${version.name}.apk"

        /**
         * Reads the release from the response [json]. Throws [UpdateFormatException] when it is not a
         * published, non-pre-release `vX.Y.Z` release with both assets, a size within limits and a URL.
         */
        fun parse(json: String): LatestRelease {
            val root = Json.parse(json) as? JsonValue.Object ?: formatError("not an object")
            if (root.flag("draft") || root.flag("prerelease")) formatError("not a release")
            val tag = root.text("tag_name") ?: formatError("no tag_name")
            val version = ReleaseVersion.parseTag(tag) ?: formatError("tag is not vX.Y.Z")
            val assets = root.members["assets"] as? JsonValue.Array ?: formatError("no assets")
            val apk = asset(assets, apkName(version), MAX_APK_BYTES)
            val checksums = asset(assets, CHECKSUMS_NAME, Sha256Sums.MAX_BYTES.toLong())
            return LatestRelease(version, apk, checksums)
        }

        private fun asset(
            assets: JsonValue.Array,
            name: String,
            maxSize: Long,
        ): ReleaseAsset {
            val matches = assets.items.filterIsInstance<JsonValue.Object>().filter { it.text("name") == name }
            val entry = matches.singleOrNull() ?: formatError("not exactly one $name")
            val size = (entry.members["size"] as? JsonValue.Number)?.longOrNull
            if (size == null || size <= 0 || size > maxSize) formatError("bad size of $name")
            val url = entry.text("browser_download_url")
            if (url.isNullOrEmpty() || url.length > MAX_URL_LENGTH) formatError("bad URL of $name")
            return ReleaseAsset(name, size, url)
        }

        private fun JsonValue.Object.text(key: String): String? = (members[key] as? JsonValue.Text)?.value

        private fun JsonValue.Object.flag(key: String): Boolean = (members[key] as? JsonValue.Bool)?.value == true
    }
}
