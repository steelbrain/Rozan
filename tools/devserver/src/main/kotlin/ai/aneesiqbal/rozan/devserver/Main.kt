package ai.aneesiqbal.rozan.devserver

import ai.aneesiqbal.rozan.airplay.fairplay.FairPlayException
import ai.aneesiqbal.rozan.airplay.fairplay.FairPlaySession
import ai.aneesiqbal.rozan.airplay.receiver.AirPlayReceiver
import ai.aneesiqbal.rozan.airplay.receiver.DisplayInfo
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverConfig
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import java.io.File
import java.security.SecureRandom
import java.time.LocalTime
import java.util.concurrent.CountDownLatch

/**
 * Desktop AirPlay receiver for interop testing against a real Mac or iPhone.
 *
 * Usage: `./gradlew :tools:devserver:run --args="--name 'Rozan Dev' --out /tmp/rozan-dev --port 47000 --mode pin"`
 *
 * The control port defaults to [Options.DEFAULT_PORT], not 7000: macOS's own AirPlay receiver holds 7000
 * and 5000. `--mode pin` (the default) runs Mode P, `--mode digest` Mode D (docs/protocol/02 §1.2).
 *
 * It publishes the receiver with macOS `dns-sd` (Avahi on Linux), prints the PIN to the
 * terminal, writes the mirrored video as an H.264/HEVC Annex-B elementary
 * stream (`video.h264` / `video.h265`, playable with `ffplay`) and audio access
 * units per stream, and logs every lifecycle event. Never point a client at a
 * production receiver while testing.
 */
fun main(args: Array<String>) {
    val options = Options.parse(args)
    options.out.mkdirs()
    val identity = DevIdentity.loadOrCreate(File(options.out, "identity.bin"))
    val config =
        ReceiverConfig(
            name = options.name,
            deviceId = identity.deviceId,
            identitySeed = identity.seed,
            display = DisplayInfo(widthPixels = 1920, heightPixels = 1080, refreshRateHz = 60),
            pinMode = options.mode,
            controlPort = options.port,
        )
    val platform = DevPlatform(options.out)
    val log = ReceiverLog { level, message -> println("${LocalTime.now()} [$level] $message") }
    val receiver = AirPlayReceiver(config, platform, { DevFairPlay() }, log)
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            receiver.stop()
            platform.unpublish()
            stopped.countDown()
        },
    )
    receiver.start()
    println(
        "Rozan dev receiver \"${options.name}\" (${options.mode}) on port ${receiver.controlPort}; " +
            "output in ${options.out}. Ctrl-C to stop.",
    )
    stopped.await()
}

internal data class Options(
    val name: String,
    val out: File,
    val port: Int,
    val mode: PinMode,
) {
    companion object {
        /** High default port: macOS's own receiver owns 7000 and 5000. */
        const val DEFAULT_PORT = 47000

        fun parse(args: Array<String>): Options {
            var name = "Rozan Dev"
            var out = File(System.getProperty("java.io.tmpdir"), "rozan-dev")
            var port = DEFAULT_PORT
            var mode = PinMode.PIN_PAIRING
            var index = 0
            while (index < args.size) {
                val value = args.getOrNull(index + 1)
                when (args[index]) {
                    "--name" -> name = value ?: usage()
                    "--out" -> out = File(value ?: usage())
                    "--port" -> port = value?.toIntOrNull()?.takeIf { it in 0..65_535 } ?: usage()
                    "--mode" -> mode = mode(value)
                    else -> usage()
                }
                index += 2
            }
            return Options(name, out, port, mode)
        }

        private fun mode(value: String?): PinMode =
            when (value) {
                "pin" -> PinMode.PIN_PAIRING
                "digest" -> PinMode.DIGEST
                else -> usage()
            }

        private fun usage(): Nothing {
            System.err.println("usage: devserver [--name NAME] [--out DIR] [--port PORT] [--mode pin|digest]")
            kotlin.system.exitProcess(2)
        }
    }
}

/** Adapts the real FairPlay implementation to the receiver's [FairPlayHandler]; one per connection. */
internal class DevFairPlay : FairPlayHandler {
    private val session = FairPlaySession()

    override fun setup(request: ByteArray): ByteArray = adapt { session.setup(request) }

    override fun handshake(request: ByteArray): ByteArray = adapt { session.handshake(request) }

    override fun decryptKey(encryptedKey: ByteArray): ByteArray = adapt { session.decryptKey(encryptedKey) }

    private inline fun adapt(step: () -> ByteArray): ByteArray =
        try {
            step()
        } catch (e: FairPlayException) {
            throw FairPlayFailure(e.message ?: "FairPlay failed", e)
        }
}

/** Per-checkout identity so the Mac sees the same receiver across runs. */
internal class DevIdentity(
    val deviceId: ByteArray,
    val seed: ByteArray,
) {
    companion object {
        fun loadOrCreate(file: File): DevIdentity {
            if (file.length() == 38L) {
                val bytes = file.readBytes()
                return DevIdentity(bytes.copyOfRange(0, 6), bytes.copyOfRange(6, 38))
            }
            val random = SecureRandom()
            val deviceId = ByteArray(6).also(random::nextBytes)
            // Locally administered, unicast.
            deviceId[0] = ((deviceId[0].toInt() or 0x02) and 0xFE).toByte()
            val seed = ByteArray(32).also(random::nextBytes)
            file.writeBytes(deviceId + seed)
            return DevIdentity(deviceId, seed)
        }
    }
}
