package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.fairplay.FairPlayException
import ai.aneesiqbal.rozan.airplay.fairplay.FairPlaySession
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayFailure
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandler
import ai.aneesiqbal.rozan.airplay.receiver.FairPlayHandlerFactory
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog
import android.util.Log

/**
 * `:airplay:fairplay` behind the receiver's [FairPlayHandler]: one
 * [FairPlaySession] per control connection, its failures reported as
 * [FairPlayFailure] so the receiver rejects the request and drops the
 * connection.
 */
internal class FairPlayAdapter : FairPlayHandler {
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

    companion object {
        /** A fresh session per connection; stateless itself, so safe from any thread. */
        val FACTORY: FairPlayHandlerFactory = FairPlayHandlerFactory { FairPlayAdapter() }
    }
}

/**
 * Receiver diagnostics to Logcat under one tag. The receiver's messages never
 * contain codes, keys, sender names or addresses, so they are fine in release
 * logs.
 */
internal object LogcatReceiverLog : ReceiverLog {
    private const val TAG = "AirPlayReceiver"

    override fun log(
        level: ReceiverLog.Level,
        message: String,
    ) {
        when (level) {
            ReceiverLog.Level.DEBUG -> Log.d(TAG, message)
            ReceiverLog.Level.INFO -> Log.i(TAG, message)
            ReceiverLog.Level.WARN -> Log.w(TAG, message)
        }
    }
}
