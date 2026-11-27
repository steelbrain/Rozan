package ai.aneesiqbal.rozan.launcher.update

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [UpdateHttp] against a minimal HTTP/1.1 server on 127.0.0.1 (the JDK's HttpURLConnection on the client side). */
class UpdateHttpTest {
    private val server = TestServer()
    private val http = UpdateHttp(UrlPolicy(allowLoopbackHttp = true), "Rozan/test")
    private val base = "http://127.0.0.1:${server.port}"

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `fetches a body with the updater's headers`() {
        server.route("/latest.json", TestServer.Response(200, body = "{}".toByteArray()))
        assertContentEquals("{}".toByteArray(), http.fetch("$base/latest.json", 1024, "application/vnd.github+json"))
        val request = server.requests.single()
        assertEquals("GET /latest.json HTTP/1.1", request.line)
        assertEquals("Rozan/test", request.headers["user-agent"])
        assertEquals("application/vnd.github+json", request.headers["accept"])
    }

    @Test
    fun `refuses a body over the limit, with or without a length`() {
        val body = ByteArray(2048) { 'a'.code.toByte() }
        server.route("/long", TestServer.Response(200, body = body))
        server.route("/unsized", TestServer.Response(200, body = body, sendLength = false))
        assertFailsWith<UpdateFormatException> { http.fetch("$base/long", 1024) }
        assertFailsWith<UpdateFormatException> { http.fetch("$base/unsized", 1024) }
        assertEquals(2048, http.fetch("$base/unsized", 2048).size)
    }

    @Test
    fun `reports statuses other than 200`() {
        server.route("/missing", TestServer.Response(404))
        server.route("/limited", TestServer.Response(403))
        assertEquals(404, assertFailsWith<HttpStatusException> { http.fetch("$base/missing", 1024) }.status)
        assertEquals(403, assertFailsWith<HttpStatusException> { http.fetch("$base/limited", 1024) }.status)
        // A redirect without a Location is a status like any other.
        server.route("/nowhere", TestServer.Response(302))
        assertEquals(302, assertFailsWith<HttpStatusException> { http.fetch("$base/nowhere", 1024) }.status)
    }

    @Test
    fun `follows redirects that pass the policy`() {
        server.route("/a", TestServer.Response(302, mapOf("Location" to "/b")))
        server.route("/b", TestServer.Response(307, mapOf("Location" to "$base/c")))
        server.route("/c", TestServer.Response(200, body = "ok".toByteArray()))
        assertContentEquals("ok".toByteArray(), http.fetch("$base/a", 16))
        assertEquals(listOf("/a", "/b", "/c"), server.requests.map { it.path })
    }

    @Test
    fun `stops at redirects the policy refuses, before connecting`() {
        server.route("/away", TestServer.Response(302, mapOf("Location" to "http://example.com/a.apk")))
        assertFailsWith<UpdateFormatException> { http.fetch("$base/away", 16) }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `gives up after too many redirects`() {
        server.route("/loop", TestServer.Response(301, mapOf("Location" to "/loop")))
        assertFailsWith<UpdateFormatException> { http.fetch("$base/loop", 16) }
        assertEquals(UpdateHttp.MAX_REDIRECTS + 1, server.requests.size)
    }

    @Test
    fun `release builds never send plain http`() {
        val strict = UpdateHttp(UrlPolicy(allowLoopbackHttp = false), "Rozan/test")
        assertFailsWith<UpdateFormatException> { strict.fetch("$base/latest.json", 16) }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `downloads exactly the stated size`() {
        val apk = ByteArray(200_000) { it.toByte() }
        server.route("/a.apk", TestServer.Response(200, body = apk))
        val out = ByteArrayOutputStream()
        http.download("$base/a.apk", apk.size.toLong()) { buffer, length -> out.write(buffer, 0, length) }
        assertContentEquals(apk, out.toByteArray())
        assertEquals("identity", server.requests.single().headers["accept-encoding"])
    }

    @Test
    fun `refuses downloads of another size`() {
        val apk = ByteArray(1000)
        server.route("/a.apk", TestServer.Response(200, body = apk))
        server.route("/unsized.apk", TestServer.Response(200, body = apk, sendLength = false))
        for (path in listOf("/a.apk", "/unsized.apk")) {
            for (size in listOf(999L, 1001L)) {
                assertFailsWith<ChecksumException>("$path $size") { http.download("$base$path", size) { _, _ -> } }
            }
        }
    }

    @Test
    fun `cancel ends a request that is waiting for data`() {
        val sent = CountDownLatch(1)
        server.route(
            "/stall.apk",
            TestServer.Response(200, body = ByteArray(10), sendLength = false, stallAfterBody = sent),
        )
        val failure = CopyOnWriteArrayList<Exception>()
        // The first bytes reached the caller, so the client is past the headers and blocked in a read.
        val reading = CountDownLatch(1)
        val client =
            thread {
                try {
                    http.download("$base/stall.apk", 1_000_000) { _, _ -> reading.countDown() }
                } catch (e: IOException) {
                    failure += e
                } catch (e: UpdateCancelledException) {
                    failure += e
                }
            }
        assertTrue(sent.await(10, TimeUnit.SECONDS))
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        http.cancel()
        client.join(10_000)
        assertEquals(1, failure.size, "the download ended with an error")
        assertTrue(http.isCancelled)
        assertFailsWith<UpdateCancelledException> { http.fetch("$base/stall.apk", 16) }
    }

    /** One connection at a time; each answer closes its connection. */
    private class TestServer : AutoCloseable {
        class Response(
            val status: Int,
            val headers: Map<String, String> = emptyMap(),
            val body: ByteArray = ByteArray(0),
            val sendLength: Boolean = true,
            val stallAfterBody: CountDownLatch? = null,
        )

        class Request(
            val line: String,
            val headers: Map<String, String>,
        ) {
            val path: String get() = line.split(' ')[1]
        }

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val routes = ConcurrentHashMap<String, Response>()
        private val stalled = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<Request>()
        val port: Int = socket.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client =
                        try {
                            socket.accept()
                        } catch (ignored: IOException) {
                            // close() closed the socket.
                            break
                        }
                    thread(isDaemon = true) { client.use(::serve) }
                }
            }
        }

        fun route(
            path: String,
            response: Response,
        ) {
            routes[path] = response
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = input.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val header = input.readLine()
                if (header.isNullOrEmpty()) break
                headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
            }
            val request = Request(line, headers)
            requests += request
            val response = routes[request.path] ?: Response(404)
            val head = StringBuilder("HTTP/1.1 ${response.status} X\r\nConnection: close\r\n")
            for ((name, value) in response.headers) head.append("$name: $value\r\n")
            if (response.sendLength) head.append("Content-Length: ${response.body.size}\r\n")
            head.append("\r\n")
            val out = client.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
            response.stallAfterBody?.let {
                it.countDown()
                // Holds the connection open until the server closes; the client must end it itself.
                stalled.await(30, TimeUnit.SECONDS)
            }
        }

        override fun close() {
            stalled.countDown()
            socket.close()
        }
    }
}
