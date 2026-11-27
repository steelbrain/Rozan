package ai.aneesiqbal.rozan.launcher.update

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * Which URLs the updater may fetch: `https` only, with a host and no user
 * name or password. Debug builds that point the updater at a local test
 * server ([allowLoopbackHttp], set only by `DebugOverrides`) may also use
 * `http://127.0.0.1`; their network security config allows cleartext to that
 * address alone. Redirects are resolved against the URL that sent them and
 * must pass the same check.
 */
internal class UrlPolicy(
    private val allowLoopbackHttp: Boolean,
) {
    /** [url] as a [URL] if it may be fetched; otherwise [UpdateFormatException]. */
    fun check(url: String): URL = checked(parse(url))

    /** The target of a redirect from [from] to [location] (absolute or relative), if it may be fetched. */
    fun redirect(
        from: URL,
        location: String,
    ): URL {
        val base =
            try {
                from.toURI()
            } catch (e: URISyntaxException) {
                formatError("bad URL: ${e.reason}", e)
            }
        val target =
            try {
                base.resolve(parse(location))
            } catch (e: IllegalArgumentException) {
                formatError("bad redirect: ${e.message}", e)
            }
        return checked(target)
    }

    private fun parse(url: String): URI {
        if (url.length > LatestRelease.MAX_URL_LENGTH) formatError("URL too long")
        return try {
            URI(url)
        } catch (e: URISyntaxException) {
            formatError("bad URL: ${e.reason}", e)
        }
    }

    private fun checked(uri: URI): URL {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val allowed =
            when {
                host.isNullOrEmpty() || uri.rawUserInfo != null -> false
                scheme == "https" -> true
                else -> scheme == "http" && allowLoopbackHttp && host == LOOPBACK
            }
        if (!allowed) formatError("URL not allowed: scheme $scheme")
        return try {
            uri.toURL()
        } catch (e: MalformedURLException) {
            formatError("bad URL: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            formatError("bad URL: ${e.message}", e)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
