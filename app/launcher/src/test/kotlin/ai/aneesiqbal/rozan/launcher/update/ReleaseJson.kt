package ai.aneesiqbal.rozan.launcher.update

/**
 * Release responses in the shape of GitHub's `GET /repos/{owner}/{repo}/releases/latest`
 * (fields as GitHub documents them; values made up), with the assets
 * `scripts/release/collect.sh` uploads.
 */
internal object ReleaseJson {
    const val APK_SIZE = 7953856L
    const val SUMS_SIZE = 182L

    fun github(
        tag: String = "v0.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = assets(tag.removePrefix("v")),
    ): String =
        """
        {
          "url": "https://api.github.com/repos/steelbrain/Rozan/releases/1",
          "assets_url": "https://api.github.com/repos/steelbrain/Rozan/releases/1/assets",
          "upload_url": "https://uploads.github.com/repos/steelbrain/Rozan/releases/1/assets{?name,label}",
          "html_url": "https://github.com/steelbrain/Rozan/releases/tag/$tag",
          "id": 1,
          "author": {
            "login": "octocat",
            "id": 1,
            "node_id": "MDQ6VXNlcjE=",
            "type": "User",
            "site_admin": false
          },
          "node_id": "RE_kwDOAAAAAc4AAAAB",
          "tag_name": "$tag",
          "target_commitish": "main",
          "name": "Rozan ${tag.removePrefix("v")}",
          "draft": $draft,
          "immutable": false,
          "prerelease": $prerelease,
          "created_at": "2026-11-26T12:00:00Z",
          "updated_at": "2026-11-26T12:05:00Z",
          "published_at": "2026-11-26T12:05:00Z",
          "assets": $assets,
          "tarball_url": "https://api.github.com/repos/steelbrain/Rozan/tarball/$tag",
          "zipball_url": "https://api.github.com/repos/steelbrain/Rozan/zipball/$tag",
          "body": "### Added\n- \"Update automatically\" in the settings — café 😀\r\n",
          "reactions": {
            "url": "https://api.github.com/repos/steelbrain/Rozan/releases/1/reactions",
            "total_count": 2,
            "+1": 2,
            "-1": 0
          }
        }
        """.trimIndent()

    fun assets(version: String): String =
        "[" +
            listOf(
                asset("rozan-airplay-$version.apk", 529594, version, id = 1),
                asset("rozan-launcher-$version.apk", APK_SIZE, version, id = 2),
                asset("SHA256SUMS", SUMS_SIZE, version, id = 3, type = "application/octet-stream"),
            ).joinToString(",\n") +
            "]"

    fun asset(
        name: String,
        size: Long,
        version: String = "0.2.0",
        id: Int = 1,
        type: String = "application/vnd.android.package-archive",
        url: String = "https://github.com/steelbrain/Rozan/releases/download/v$version/$name",
    ): String =
        """
        {
          "url": "https://api.github.com/repos/steelbrain/Rozan/releases/assets/$id",
          "id": $id,
          "node_id": "RA_kwDOAAAAAc4AAAA$id",
          "name": "$name",
          "label": null,
          "uploader": { "login": "github-actions[bot]", "id": 41898282, "type": "Bot", "site_admin": false },
          "content_type": "$type",
          "state": "uploaded",
          "size": $size,
          "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000",
          "download_count": 42,
          "created_at": "2026-11-26T12:03:00Z",
          "updated_at": "2026-11-26T12:03:01Z",
          "browser_download_url": "$url"
        }
        """.trimIndent()
}
