package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.media.MediaSessionFactory
import ai.aneesiqbal.rozan.airplay.pairing.CodeDisplay
import ai.aneesiqbal.rozan.airplay.pairing.PairingService
import ai.aneesiqbal.rozan.airplay.plist.BinaryPlist
import ai.aneesiqbal.rozan.airplay.plist.PlistDict
import ai.aneesiqbal.rozan.airplay.plist.PlistValue
import ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver
import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandlerFactory
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import ai.aneesiqbal.rozan.airplay.rtsp.RtspException
import ai.aneesiqbal.rozan.airplay.rtsp.RtspProtocol
import ai.aneesiqbal.rozan.airplay.rtsp.RtspRequest
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponse
import ai.aneesiqbal.rozan.airplay.rtsp.RtspResponseReader
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.fail

/** A running receiver on an ephemeral port with fake media, pairing and FairPlay layers. */
internal class ReceiverHarness(
    mode: PinMode = PinMode.DIGEST,
    limits: ControlLimits = ControlLimits(),
    clock: () -> Long = System::nanoTime,
    fairPlay: FairPlayHandlerFactory = FairPlayHandlerFactory { FakeFairPlay() },
    /** Builds a real pairing service with the harness clock; null uses [FakePairing]. */
    realPairing: ((PinMode, ByteArray, CodeDisplay, () -> Unit, () -> Long) -> PairingService)? = null,
    /** The real media layer, or null for [FakeMedia]. */
    mediaFactory: MediaSessionFactory? = null,
    name: String = "Living Room",
) : AutoCloseable {
    val platform = RecordingPlatform()
    val media = FakeMedia()
    val logs = CopyOnWriteArrayList<String>()

    @Volatile private var pairingService: FakePairing? = null

    val pairing: FakePairing get() = pairingService ?: fail("receiver not started")

    val config =
        ReceiverConfig(
            name = name,
            deviceId = DEVICE_ID.copyOf(),
            identitySeed = ByteArray(32) { (0x80 + it).toByte() },
            display = DisplayInfo(1920, 1080, 60),
            pinMode = mode,
            controlPort = 0,
        )

    val receiver =
        AirPlayReceiver(
            config,
            platform,
            fairPlay,
            { level, message -> logs += "$level $message" },
            ReceiverParts(
                mediaFactory = mediaFactory ?: media,
                pairingFactory =
                    PairingFactory { mode, seed, display, changed ->
                        realPairing?.invoke(mode, seed, display, changed, clock)
                            ?: FakePairing(display, changed).also { pairingService = it }
                    },
                limits = limits,
                clock = clock,
            ),
        )

    init {
        receiver.start()
    }

    val port: Int get() = receiver.controlPort

    /** A client connected from [address] (loopback by default). */
    fun connect(address: InetAddress = InetAddress.getLoopbackAddress()): TestClient = TestClient(address, port)

    override fun close() = receiver.stop()

    companion object {
        val DEVICE_ID = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)
    }
}

/** A blocking RTSP client for tests; numbers CSeq from 0 like Apple senders. */
class TestClient(
    address: InetAddress,
    port: Int,
) : AutoCloseable {
    private val socket = Socket().apply { connect(InetSocketAddress(address, port), TIMEOUT_MILLIS) }
    private val reader = RtspResponseReader(socket.getInputStream())
    private var cSeq = 0

    init {
        socket.soTimeout = TIMEOUT_MILLIS
    }

    /** Sends a request and returns its response. */
    fun request(
        method: String,
        target: String,
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        headers: List<Pair<String, String>> = emptyList(),
        protocol: RtspProtocol = RtspProtocol.RTSP_1_0,
    ): RtspResponse {
        val builder = RtspRequest.Builder(method, target, protocol)
        if (protocol == RtspProtocol.RTSP_1_0) builder.header("CSeq", (cSeq++).toString())
        for ((name, value) in headers) builder.header(name, value)
        if (body.isNotEmpty() || contentType != null) builder.body(body, contentType)
        send(builder.build().toByteArray())
        return response() ?: fail("connection closed before the response to $method $target")
    }

    /** POSTs a binary plist. */
    fun postPlist(
        path: String,
        value: PlistValue,
    ): RtspResponse = request("POST", path, BinaryPlist.encode(value), ContentTypes.BPLIST)

    /** Sends a SETUP with a binary plist body. */
    fun setup(
        value: PlistDict,
        headers: List<Pair<String, String>> = emptyList(),
    ): RtspResponse = request("SETUP", SESSION_URI, BinaryPlist.encode(value), ContentTypes.BPLIST, headers)

    /** Writes raw bytes. */
    fun send(bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    /** The next response, or null when the receiver closed the connection. */
    fun response(): RtspResponse? = reader.read()

    /** Whether the receiver has closed the connection (reads EOF or a reset within the timeout). */
    fun closedByReceiver(): Boolean =
        try {
            reader.read() == null
        } catch (ignored: IOException) {
            true
        } catch (ignored: RtspException) {
            true
        }

    override fun close() = socket.close()

    companion object {
        const val TIMEOUT_MILLIS = 5_000
        const val SESSION_URI = "rtsp://127.0.0.1/1234567890"
    }
}

/** The body of [response] as a dictionary. */
fun plistBody(response: RtspResponse): PlistDict = BinaryPlist.decode(response.body) as PlistDict
