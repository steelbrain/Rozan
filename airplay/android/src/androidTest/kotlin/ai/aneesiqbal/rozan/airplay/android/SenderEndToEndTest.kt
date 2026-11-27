package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.debug.DebugEventsReceiver
import ai.aneesiqbal.rozan.airplay.android.debug.TestClip
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import ai.aneesiqbal.rozan.airplay.testing.AirPlayTestSender
import ai.aneesiqbal.rozan.airplay.testing.H264Clip
import ai.aneesiqbal.rozan.airplay.testing.SenderException
import ai.aneesiqbal.rozan.airplay.testing.SenderIdentity
import android.app.ActivityManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The real receiver service, debug build, in its `:airplay` process, against the test sender from
 * `:airplay:testing` in this process: pairing with the PIN read off the screen, FairPlay, mirroring the
 * bundled clip with audio, Back on the mirroring screen, and a second connection. Everything goes over
 * 127.0.0.1:7000 and loopback UDP, as from a Mac on the network.
 */
@RunWith(AndroidJUnit4::class)
class SenderEndToEndTest {
    @get:Rule
    val timeout: Timeout = Timeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = testContext.packageName
    private val settings = AirPlaySettings(testContext)
    private val status = ReceiverStatus(testContext)
    private val name = "Rozan E2E ${Random.nextInt(0x10000).toString(16)}"
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val port = ReceiverConfig.DEFAULT_CONTROL_PORT
    private val pinCode = By.res(pkg, "airplay_pin_code")
    private val mirrorSurface = By.res(pkg, "airplay_mirror_surface")
    private var sender: AirPlayTestSender? = null

    @Before
    fun startReceiver() {
        // The overlay permission, as the setup instructions grant it over adb; the PIN goes over Home.
        shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        assertTrue("overlay permission", eventually { OverlayPermission.isGranted(testContext) })
        // A fresh :airplay process: no receiver state, sinks or counters from earlier tests.
        settings.enabled = false
        killReceiverProcess()
        device.pressHome()
        settings.deviceName = name
        settings.wakeOnConnect = true
        settings.enabled = true
        ReceiverService.start(testContext)
        assertTrue(
            "receiver \"$name\" listening on $port (${status.current})",
            eventually(STATUS_TIMEOUT_MILLIS) {
                val now = status.current
                now.running && now.receiverName == name && now.failure == null && acceptsConnections()
            },
        )
    }

    @After
    fun stopReceiver() {
        sender?.close()
        settings.enabled = false
        killReceiverProcess()
    }

    @Test
    fun aPairedSenderMirrorsWithAudioAndBackEndsTheSession() {
        val identity = SenderIdentity(name = "Rozan Test Sender")
        val sender = newSender(identity)
        assertEquals("the receiver on $port is this test's", name, sender.apply { connect() }.info().string("name"))

        sender.pairWithPin {
            readPin().also {
                assertTrue(
                    "the prompt names the sender",
                    device.hasObject(By.text("Rozan Test Sender wants to AirPlay. Enter this code on your device.")),
                )
            }
        }
        assertTrue("PIN gone after pairing", device.wait(Until.gone(pinCode), UI_TIMEOUT_MILLIS))

        sender.fairPlaySetup()
        sender.setupSession(mirroring = true)
        sender.setupMirroring()
        assertTrue("mirroring screen in front", device.wait(Until.hasObject(mirrorSurface), UI_TIMEOUT_MILLIS))
        sender.setupAudio(usingScreen = true)
        sender.record()
        sender.setVolume(-10.0)
        sender.setMetadata("Test clip", "Rozan", "End to end")
        sender.startHeartbeat()

        val before = stats()
        val result = sender.stream(clip(), STREAM_NANOS)
        assertTrue("streamed to the end: $result", result.completed)
        assertEquals(90, result.videoFrames)
        // The receiver plays audio 0.75 s after it is sent; give the decoders that and some slack.
        assertTrue(
            "frames rendered and audio written (${stats()} after $before)",
            eventually(MEDIA_TIMEOUT_MILLIS) {
                val now = stats()
                now.video - before.video >= MIN_RENDERED_FRAMES && now.audio - before.audio >= MIN_AUDIO_FRAMES
            },
        )
        Log.i(TAG, "sent $result; receiver ${stats()} (before $before)")
        assertTrue("still mirroring", device.hasObject(mirrorSurface))

        // Back on the mirroring screen ends the session: the sender sees its connection close.
        device.pressBack()
        assertTrue("connection closed by the receiver", sender.awaitClosedByReceiver(CLOSE_TIMEOUT_MILLIS))
        assertTrue("mirroring screen gone", device.wait(Until.gone(mirrorSurface), UI_TIMEOUT_MILLIS))

        // The same sender comes back with its stored pairing: refused, nothing was remembered.
        sender.connect()
        sender.info()
        val refused = assertThrows { sender.pairVerify() }
        assertEquals("pair-verify with the old pairing", 470, refused.status)
        // A new PIN pairs it again, straight away: the first prompt is over, so nothing holds the next back.
        sender.pairWithPin { readPin() }
        assertTrue("second PIN gone after pairing", device.wait(Until.gone(pinCode), UI_TIMEOUT_MILLIS))
        sender.fairPlaySetup()
        sender.setupSession(mirroring = true)
        sender.teardown()
    }

    @Test
    fun aWrongPinGetsNoMirroringScreen() {
        val sender = newSender(SenderIdentity(name = "Rozan Wrong PIN"))
        val before = stats()
        val refused =
            assertThrows {
                sender.pairWithPin {
                    val shown = readPin()
                    ((shown.toInt() + 1) % 10_000).toString().padStart(4, '0')
                }
            }
        assertEquals("wrong PIN refused", 470, refused.status)
        assertTrue("connection closed by the receiver", sender.awaitClosedByReceiver(CLOSE_TIMEOUT_MILLIS))

        // Without the PIN, SETUP is challenged and no stream can be set up.
        sender.connect()
        sender.fairPlaySetup()
        assertEquals("session SETUP challenged", 401, assertThrows { sender.setupSession() }.status)
        val stream = assertThrows { sender.setupMirroring() }
        assertTrue("stream SETUP refused (${stream.status})", stream.status in 400..499)
        assertFalse("no mirroring screen", device.wait(Until.hasObject(mirrorSurface), NEGATIVE_WAIT_MILLIS))
        assertEquals("nothing rendered", before.video, stats().video)
    }

    @Test
    fun aProbeLeavesTheScreenOffAndAPinWakesIt() {
        val power = testContext.getSystemService(PowerManager::class.java)
        shell("input keyevent KEYCODE_SLEEP")
        assertTrue("screen off", eventually { !power.isInteractive })
        try {
            // What a Mac or iPhone with its AirPlay menu open does to every receiver it sees.
            val sender = newSender(SenderIdentity(name = "Rozan Wake Test"))
            sender.connect()
            sender.info()
            assertFalse("a probe leaves the screen off", eventually(NEGATIVE_WAIT_MILLIS) { power.isInteractive })

            sender.pairWithPin {
                assertTrue("the PIN wakes the screen", eventually(UI_TIMEOUT_MILLIS) { power.isInteractive })
                readPin()
            }
            assertTrue("PIN gone after pairing", device.wait(Until.gone(pinCode), UI_TIMEOUT_MILLIS))
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
        }
    }

    private fun newSender(identity: SenderIdentity): AirPlayTestSender =
        AirPlayTestSender(loopback, port, identity).also { sender = it }

    private fun clip(): H264Clip = H264Clip.parse(testContext.assets.open(TestClip.ASSET_720P).use { it.readBytes() })

    /** The four digits on screen, as the user reads them. */
    private fun readPin(): String {
        val code = device.wait(Until.findObject(pinCode), UI_TIMEOUT_MILLIS) ?: throw AssertionError("no PIN on screen")
        val text = code.text
        assertTrue("four digits: $text", text.matches(Regex("\\d{4}")))
        return text
    }

    private class Stats(
        val video: Long,
        val audio: Long,
    ) {
        override fun toString(): String = "video $video frames, audio $audio frames"
    }

    /** The receiver process's media totals, from the debug build's STATS broadcast. */
    private fun stats(): Stats {
        val out = shell("am broadcast -p $pkg -a ${DebugEventsReceiver.ACTION_STATS}")

        fun value(key: String): Long =
            Regex("$key=(\\d+)")
                .find(out)
                ?.groupValues
                ?.get(1)
                ?.toLong() ?: error("no $key in: $out")
        return Stats(value("video_frames_rendered"), value("audio_frames_written"))
    }

    private fun acceptsConnections(): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress(loopback, port), CONNECT_TIMEOUT_MILLIS) }
            true
        } catch (ignored: IOException) {
            false
        }

    private fun killReceiverProcess() {
        val pid = receiverProcess()?.pid ?: return
        Process.killProcess(pid)
        assertTrue(":airplay process gone", eventually { receiverProcess()?.pid != pid })
    }

    private fun receiverProcess(): ActivityManager.RunningAppProcessInfo? =
        testContext
            .getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull { it.processName == "$pkg:airplay" }

    private fun assertThrows(block: () -> Unit): SenderException {
        try {
            block()
        } catch (e: SenderException) {
            return e
        }
        throw AssertionError("the receiver accepted it")
    }

    private companion object {
        const val TAG = "SenderEndToEndTest"
        const val TEST_TIMEOUT_SECONDS = 90L
        const val STATUS_TIMEOUT_MILLIS = 20_000L
        const val UI_TIMEOUT_MILLIS = 10_000L
        const val MEDIA_TIMEOUT_MILLIS = 5_000L
        const val CLOSE_TIMEOUT_MILLIS = 10_000L
        const val NEGATIVE_WAIT_MILLIS = 2_000L
        const val CONNECT_TIMEOUT_MILLIS = 1_000
        const val STREAM_NANOS = 3_000_000_000L

        /** A third of the 90 frames sent: emulator decoders are slow and frames late at start are skipped. */
        const val MIN_RENDERED_FRAMES = 30L

        /** One of the three seconds of audio sent. */
        const val MIN_AUDIO_FRAMES = 44_100L
    }
}
