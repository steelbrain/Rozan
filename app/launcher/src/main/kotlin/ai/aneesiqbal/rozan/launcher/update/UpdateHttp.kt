package ai.aneesiqbal.rozan.launcher.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** The server answered with [status] instead of 200 (or with a redirect the updater does not follow). */
internal class HttpStatusException(
    val status: Int,
) : Exception("HTTP status $status")

/** The APK's size or digest is not the one the release states. */
internal class ChecksumException(
    message: String,
) : Exception(message)

/** The job was stopped (the toggle went off, or the system stopped it). */
internal class UpdateCancelledException : Exception("cancelled")

/**
 * GET requests for the updater over the platform's `HttpsURLConnection`.
 * Every URL, including each redirect target, passes [policy] (HTTPS only);
 * at most [MAX_REDIRECTS] redirects are followed. Responses are read into a
 * bounded buffer or streamed to the caller, never past the caller's limit.
 *
 * There are no timeouts: a stalled request ends when the job does.
 * [cancel] (from `JobService.onStopJob`, or when the switch is turned off)
 * disconnects the current connection, which ends a blocked read.
 */
internal class UpdateHttp(
    private val policy: UrlPolicy,
    private val userAgent: String,
) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var connection: HttpURLConnection? = null

    /** Whether [cancel] was called. */
    val isCancelled: Boolean get() = cancelled

    /** Stops the request in progress and every later one. Any thread. */
    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /**
     * The body of [url], at most [maxBytes] bytes, or [UpdateFormatException] when it is longer.
     * Network failures are [IOException]s; other statuses [HttpStatusException].
     */
    fun fetch(
        url: String,
        maxBytes: Int,
        accept: String? = null,
    ): ByteArray {
        val conn = open(url, accept, identity = false)
        try {
            if (conn.contentLengthLong > maxBytes) formatError("response over $maxBytes bytes")
            val out = ByteArrayOutputStream(INITIAL_BUFFER)
            val total = conn.inputStream.use { input -> copy(input, maxBytes.toLong()) { b, n -> out.write(b, 0, n) } }
            if (total > maxBytes) formatError("response over $maxBytes bytes")
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams [url] to [sink], which must be exactly [size] bytes long ([ChecksumException]
     * otherwise). [sink] gets each chunk in a reused buffer.
     */
    fun download(
        url: String,
        size: Long,
        sink: (buffer: ByteArray, length: Int) -> Unit,
    ) {
        val conn = open(url, accept = null, identity = true)
        try {
            val declared = conn.contentLengthLong
            if (declared >= 0 && declared != size) throw ChecksumException("server sends $declared bytes, not $size")
            val total = conn.inputStream.use { input -> copy(input, size, sink) }
            if (total != size) throw ChecksumException("got $total bytes, not $size")
        } finally {
            conn.disconnect()
        }
    }

    /** Copies until the end or until more than [max] bytes were read; returns the bytes read. */
    private inline fun copy(
        input: InputStream,
        max: Long,
        sink: (ByteArray, Int) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            if (cancelled) stopped()
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > max) return total
            sink(buffer, read)
        }
    }

    private fun open(
        url: String,
        accept: String?,
        identity: Boolean,
    ): HttpURLConnection {
        var target: URL = policy.check(url)
        repeat(MAX_REDIRECTS + 1) {
            val conn = request(target, accept, identity)
            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_OK) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (status !in REDIRECT_STATUSES || location == null) throw HttpStatusException(status)
            target = policy.redirect(target, location)
        }
        formatError("more than $MAX_REDIRECTS redirects")
    }

    /** Sends one GET to [target] and reads the status line. */
    private fun request(
        target: URL,
        accept: String?,
        identity: Boolean,
    ): HttpURLConnection {
        if (cancelled) stopped()
        val conn = target.openConnection() as? HttpURLConnection ?: formatError("not HTTP")
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", userAgent)
        if (accept != null) conn.setRequestProperty("Accept", accept)
        // The APK is streamed and counted as sent: no transparent gzip in between.
        if (identity) conn.setRequestProperty("Accept-Encoding", "identity")
        connection = conn
        // cancel() may have run before `connection` was set; it then found nothing to disconnect.
        if (cancelled) {
            conn.disconnect()
            stopped()
        }
        try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
        return conn
    }

    private fun stopped(): Nothing = throw UpdateCancelledException()

    companion object {
        /** Most redirects followed for one request (GitHub sends one for each asset). */
        const val MAX_REDIRECTS = 5

        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private const val BUFFER_BYTES = 64 * 1024
        private const val INITIAL_BUFFER = 16 * 1024
    }
}
