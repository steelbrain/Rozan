package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.Test
import java.net.URL
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// URLs are compared as strings: URL.equals resolves host names.
class UrlPolicyTest {
    private val release = UrlPolicy(allowLoopbackHttp = false)
    private val debug = UrlPolicy(allowLoopbackHttp = true)
    private val asset = URL("https://github.com/steelbrain/Rozan/releases/download/v0.2.0/a.apk")

    @Test
    fun `allows https only`() {
        val url = "https://api.github.com/repos/steelbrain/Rozan/releases/latest"
        assertEquals(url, release.check(url).toString())
        assertEquals("https://github.com/a", release.check("HTTPS://github.com/a").toString())
        val bad =
            listOf(
                "http://api.github.com/",
                "ftp://github.com/",
                "file:///data/a.apk",
                "github.com/a",
                "//github.com/a",
            )
        for (url2 in bad) assertFailsWith<UpdateFormatException>(url2) { release.check(url2) }
    }

    @Test
    fun `refuses user info and missing hosts`() {
        // Split so the source holds no URL with credentials in it (lint: AuthLeak).
        val withPassword = "https://user:pass" + "@github.com/"
        for (bad in listOf(withPassword, "https://user@github.com/", "https:///path", "https:")) {
            assertFailsWith<UpdateFormatException>(bad) { release.check(bad) }
        }
    }

    @Test
    fun `allows plain http to 127_0_0_1 only with the debug override`() {
        val local = "http://127.0.0.1:8765/latest.json"
        assertFailsWith<UpdateFormatException> { release.check(local) }
        assertEquals(local, debug.check(local).toString())
        val bad =
            listOf(
                "http://localhost:8765/",
                "http://127.0.0.2/",
                "http://10.0.2.2/",
                "http://[::1]/",
                "http://example.com/",
            )
        for (url in bad) assertFailsWith<UpdateFormatException>(url) { debug.check(url) }
    }

    @Test
    fun `resolves redirects and checks their targets`() {
        val storage = "https://objects.githubusercontent.com/x/y?sig=1"
        assertEquals(storage, release.redirect(asset, storage).toString())
        assertEquals("https://github.com/other/b.apk", release.redirect(asset, "/other/b.apk").toString())
        assertEquals(
            "https://github.com/steelbrain/Rozan/releases/download/v0.2.0/b.apk",
            release.redirect(asset, "b.apk").toString(),
        )
        val bad =
            listOf("http://objects.githubusercontent.com/x", "ftp://a/b", "https://u:p" + "@a/b", "http://127.0.0.1/a")
        for (url in bad) assertFailsWith<UpdateFormatException>(url) { release.redirect(asset, url) }
        // A debug server may redirect within itself, but not to plain http elsewhere.
        val local = URL("http://127.0.0.1:8765/a.apk")
        assertEquals("http://127.0.0.1:8765/b.apk", debug.redirect(local, "/b.apk").toString())
        assertFailsWith<UpdateFormatException> { debug.redirect(local, "http://example.com/b.apk") }
    }

    @Test
    fun `refuses oversized and malformed URLs`() {
        val long = "https://github.com/" + "a".repeat(LatestRelease.MAX_URL_LENGTH)
        assertFailsWith<UpdateFormatException> { release.check(long) }
        for (bad in listOf("", " ", "https://git hub.com/", "https://github.com/a b", "https://[bad/", "\u0000")) {
            assertFailsWith<UpdateFormatException>(bad) { release.check(bad) }
        }
    }

    @Test
    fun `garbage fails only with a format error`() {
        val alphabet = "htps:/[]@%?#.:0123456789abc \\\u0000é"
        for (seed in 0 until 2_000) {
            val random = Random(seed)
            val text = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.length)] })
            try {
                release.check(text)
            } catch (expected: UpdateFormatException) {
                // The only failure allowed.
            }
            try {
                debug.redirect(asset, text)
            } catch (expected: UpdateFormatException) {
                // The only failure allowed.
            }
        }
    }
}
