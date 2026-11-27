package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LatestReleaseTest {
    @Test
    fun `reads the version and both assets`() {
        val release = LatestRelease.parse(ReleaseJson.github())
        assertEquals(ReleaseVersion(0, 2, 0), release.version)
        assertEquals(
            ReleaseAsset(
                "rozan-launcher-0.2.0.apk",
                ReleaseJson.APK_SIZE,
                "https://github.com/steelbrain/Rozan/releases/download/v0.2.0/rozan-launcher-0.2.0.apk",
            ),
            release.apk,
        )
        assertEquals("SHA256SUMS", release.checksums.name)
        assertEquals(ReleaseJson.SUMS_SIZE, release.checksums.size)
    }

    @Test
    fun `reads a local test server's release`() {
        // The shape the end-to-end check serves (docs in the report): plain http to 127.0.0.1.
        val url = "http://127.0.0.1:8765/rozan-launcher-0.1.1.apk"
        val assets =
            "[" + ReleaseJson.asset("rozan-launcher-0.1.1.apk", 9_000_000, "0.1.1", url = url) + "," +
                ReleaseJson.asset("SHA256SUMS", 92, "0.1.1", url = "http://127.0.0.1:8765/SHA256SUMS") + "]"
        val release = LatestRelease.parse(ReleaseJson.github(tag = "v0.1.1", assets = assets))
        assertEquals(1_001L, release.version.code)
        assertEquals(url, release.apk.url)
    }

    @Test
    fun `refuses drafts, pre-releases and tags that are not vX_Y_Z`() {
        assertFailsWith<UpdateFormatException> { LatestRelease.parse(ReleaseJson.github(draft = true)) }
        assertFailsWith<UpdateFormatException> { LatestRelease.parse(ReleaseJson.github(prerelease = true)) }
        for (tag in listOf("0.2.0", "v0.2", "v0.2.0-rc.1", "v0.2.0.1", "v1000.0.0", "V0.2.0", "")) {
            assertFailsWith<UpdateFormatException>(tag) { LatestRelease.parse(ReleaseJson.github(tag = tag)) }
        }
    }

    @Test
    fun `needs exactly one launcher APK and one SHA256SUMS`() {
        val apk = ReleaseJson.asset("rozan-launcher-0.2.0.apk", 10)
        val sums = ReleaseJson.asset("SHA256SUMS", 10)
        val airplay = ReleaseJson.asset("rozan-airplay-0.2.0.apk", 10)
        for (assets in listOf(
            "[]",
            "[$apk]",
            "[$sums]",
            "[$airplay,$sums]",
            "[$apk,$apk,$sums]",
            "[$apk,$sums,$sums]",
        )) {
            assertFailsWith<UpdateFormatException>(assets) { LatestRelease.parse(ReleaseJson.github(assets = assets)) }
        }
        // The APK of another version does not count.
        val old = ReleaseJson.asset("rozan-launcher-0.1.0.apk", 10)
        assertFailsWith<UpdateFormatException> { LatestRelease.parse(ReleaseJson.github(assets = "[$old,$sums]")) }
        assertEquals(10L, LatestRelease.parse(ReleaseJson.github(assets = "[$airplay,$apk,$sums]")).apk.size)
    }

    @Test
    fun `refuses sizes that are missing, not positive, not integers or over the caps`() {
        val sums = ReleaseJson.asset("SHA256SUMS", 10)
        val apkName = "rozan-launcher-0.2.0.apk"
        for (size in listOf("0", "-1", "1.5", "1e6", "\"10\"", "null", (LatestRelease.MAX_APK_BYTES + 1).toString())) {
            val apk = ReleaseJson.asset(apkName, 1).replace("\"size\": 1,", "\"size\": $size,")
            assertFailsWith<UpdateFormatException>(
                size,
            ) { LatestRelease.parse(ReleaseJson.github(assets = "[$apk,$sums]")) }
        }
        val apk = ReleaseJson.asset(apkName, LatestRelease.MAX_APK_BYTES)
        assertEquals(
            LatestRelease.MAX_APK_BYTES,
            LatestRelease.parse(ReleaseJson.github(assets = "[$apk,$sums]")).apk.size,
        )
        val bigSums = ReleaseJson.asset("SHA256SUMS", Sha256Sums.MAX_BYTES + 1L)
        assertFailsWith<UpdateFormatException> { LatestRelease.parse(ReleaseJson.github(assets = "[$apk,$bigSums]")) }
    }

    @Test
    fun `refuses missing or oversized URLs`() {
        val sums = ReleaseJson.asset("SHA256SUMS", 10)
        val name = "rozan-launcher-0.2.0.apk"
        val empty = ReleaseJson.asset(name, 10, url = "")
        val long = ReleaseJson.asset(name, 10, url = "https://example.com/" + "a".repeat(LatestRelease.MAX_URL_LENGTH))
        val missing = ReleaseJson.asset(name, 10).replace("\"browser_download_url\"", "\"other_url\"")
        for (apk in listOf(empty, long, missing)) {
            assertFailsWith<UpdateFormatException> { LatestRelease.parse(ReleaseJson.github(assets = "[$apk,$sums]")) }
        }
    }

    @Test
    fun `refuses documents that are not a release object`() {
        for (json in listOf("[]", "\"v0.2.0\"", "{}", "{\"tag_name\":\"v0.2.0\"}", "{\"tag_name\":1,\"assets\":[]}")) {
            assertFailsWith<UpdateFormatException>(json) { LatestRelease.parse(json) }
        }
    }

    @Test
    fun `every truncation fails with a format error`() {
        val json = ReleaseJson.github()
        for (length in 0 until json.trimEnd().length) {
            assertFailsWith<UpdateFormatException> { LatestRelease.parse(json.substring(0, length)) }
        }
    }

    @Test
    fun `garbage fails with a format error`() {
        for (seed in 0 until 200) {
            val random = Random(seed)
            val bytes = random.nextBytes(random.nextInt(0, 4096))
            assertFailsWith<UpdateFormatException> { LatestRelease.parse(String(bytes, Charsets.ISO_8859_1)) }
        }
    }
}
