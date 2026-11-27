package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.receiver.AudioFormat
import ai.aneesiqbal.rozan.airplay.receiver.VideoCodec
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The test sender against the real receiver on loopback, as the Android instrumented test runs it on
 * the emulator: PIN pairing, FairPlay v2, a mirroring session with the bundled 720p clip and ALAC audio,
 * then the ways a session ends.
 */
class SenderAgainstReceiverTest {
    private val clip = H264Clip.parse(File(System.getProperty("rozan.testing.clip")).readBytes())

    @Test
    fun `a paired sender mirrors the clip with audio, volume and metadata`() {
        TestReceiver().use { r ->
            r.sender().use { sender ->
                sender.pairWithPin { r.platform.awaitPin() }
                r.platform.awaitEvent { it == "pinFinished ACCEPTED" }
                sender.fairPlaySetup()
                val session = sender.setupSession()
                assertTrue((session.long("timingPort") ?: 0) > 0)
                eventually("timing requests answered") { sender.timingResponder.requestsAnswered > 0 }
                sender.setupMirroring()
                r.platform.awaitEvent { it == "mirroringStarted" }
                sender.setupAudio(usingScreen = true)
                sender.record()
                sender.setVolume(-20.0)
                sender.setMetadata("Clip", "Rozan", "Tests")
                sender.startHeartbeat()

                val started = System.nanoTime()
                val result = sender.stream(clip, 2_000_000_000L)
                assertTrue(result.completed, "$result")
                assertEquals(60, result.videoFrames)
                assertTrue(result.audioPackets in 249..251, "$result")

                eventually("all frames at the sink") {
                    r.platform.videoSinks
                        .singleOrNull()
                        ?.frames
                        ?.size == 60
                }
                val video = r.platform.videoSinks.single()
                val config = video.configs.single()
                assertEquals(VideoCodec.H264, config.codec)
                assertEquals(1280 to 720, config.width to config.height)
                assertContentEquals(annexB(listOf(clip.sps, clip.pps)), config.parameterSets)
                for ((i, frame) in video.frames.withIndex()) {
                    val expected = annexB(clip.frames[i].nalUnits)
                    // The first access unit after a configuration may carry the parameter sets in front (03b).
                    assertContentEquals(
                        expected,
                        frame.bytes.copyOfRange(frame.bytes.size - expected.size, frame.bytes.size),
                    )
                }
                val videoTimes = video.frames.map { it.presentationNanos }
                assertTrue(videoTimes.zipWithNext().all { (a, b) -> b > a }, "video presentation times increase")

                eventually("audio at the sink", timeoutMillis = 5_000) {
                    (
                        r.platform.audioSinks
                            .singleOrNull()
                            ?.units
                            ?.size ?: 0
                    ) >= result.audioPackets
                }
                val audio = r.platform.audioSinks.single()
                assertIs<AudioFormat.Alac>(audio.format)
                for ((k, unit) in audio.units.withIndex()) {
                    assertContentEquals(AlacFrames.tone(k * 352L, 352), unit.pcm, "unit $k decodes to the tone")
                }
                // Sender and receiver share System.nanoTime() here, so the playout delay is visible: the
                // sender's 0.5 s lead plus the receiver's 0.25 s.
                val delay = audio.units.first().presentationNanos - started
                assertTrue(
                    abs(delay - 750_000_000L) < 60_000_000L,
                    "first audio due ${delay / 1_000_000} ms after sending",
                )
                assertTrue(audio.volumes.any { abs(it - 0.1f) < 1e-4f }, "-20 dB applied: ${audio.volumes}")
                val track =
                    r.platform.nowPlaying
                        .filterNotNull()
                        .last()
                assertEquals("Clip" to "Rozan", track.title to track.artist)

                val teardown = sender.teardown()
                assertEquals("close", teardown.headers["Connection"])
                r.platform.awaitEvent { it == "mirroringStopped" }
                eventually("sinks closed") { video.closed && audio.closed }
            }
        }
    }

    @Test
    fun `ending the session on the receiver closes the sender's connection and the next one needs a new PIN`() {
        TestReceiver().use { r ->
            val identity = SenderIdentity()
            r.sender(identity).use { sender ->
                var firstPin = 0L
                sender.pairWithPin { r.platform.awaitPin().also { firstPin = System.nanoTime() } }
                sender.fairPlaySetup()
                sender.setupSession()
                sender.setupMirroring()
                r.platform.awaitEvent { it == "mirroringStarted" }
                val streaming = sender.startStreaming(clip, 2_000_000_000L)
                eventually("frames") {
                    (
                        r.platform.videoSinks
                            .singleOrNull()
                            ?.frames
                            ?.size ?: 0
                    ) >= 5
                }

                // What Back on the mirroring screen does.
                r.receiver.disconnectAll()
                assertTrue(sender.awaitClosedByReceiver(5_000), "the sender sees its connection close")
                assertFalse(streaming.await().completed, "the mirroring stream was cut off")
                r.platform.awaitEvent { it == "mirroringStopped" }

                // The same sender comes back at once with its stored pairing: refused, nothing remembered.
                sender.connect()
                sender.info()
                val refused = assertFailsWith<SenderException> { sender.pairVerify() }
                assertEquals(470, refused.status)
                assertTrue(sender.awaitClosedByReceiver(5_000))

                // A new PIN pairs it again. Codes for one address are at least 5 s apart (pairing policy).
                sleepUntil(firstPin + 5_100_000_000L)
                sender.pairWithPin { r.platform.awaitPin() }
                sender.fairPlaySetup()
                sender.setupSession()
            }
        }
    }

    @Test
    fun `a wrong PIN is refused and the sender never gets a session`() {
        TestReceiver().use { r ->
            r.sender().use { sender ->
                val refused =
                    assertFailsWith<SenderException> {
                        sender.pairWithPin {
                            val shown = r.platform.awaitPin()
                            ((shown.toInt() + 1) % 10_000).toString().padStart(4, '0')
                        }
                    }
                assertEquals(470, refused.status)
                assertTrue(sender.awaitClosedByReceiver(5_000), "the receiver closes the connection")

                // Without the PIN the receiver challenges the SETUP (Mode D Digest) and starts nothing.
                sender.connect()
                sender.fairPlaySetup()
                val challenged = assertFailsWith<SenderException> { sender.setupSession() }
                assertEquals(401, challenged.status)
                assertTrue(r.platform.eventLog.none { it == "mirroringStarted" })
                assertTrue(r.platform.videoSinks.isEmpty())
            }
        }
    }

    private fun annexB(nals: List<ByteArray>): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }
}
