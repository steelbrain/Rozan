package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.media.DefaultMediaSessionFactory
import ai.aneesiqbal.rozan.airplay.media.MediaConfig
import ai.aneesiqbal.rozan.airplay.media.MediaLog
import ai.aneesiqbal.rozan.airplay.pairing.DefaultPairingService
import ai.aneesiqbal.rozan.airplay.receiver.ReceiverLog

/** The production media and pairing layers. */
internal object DefaultParts {
    /** The pairing layer of docs/protocol/02. */
    val pairing: PairingFactory =
        PairingFactory { mode, seed, display, changed -> DefaultPairingService(mode, seed, display, changed) }

    /** Production parts; media diagnostics go to [log]. */
    fun create(log: ReceiverLog): ReceiverParts =
        ReceiverParts(
            mediaFactory =
                DefaultMediaSessionFactory(
                    MediaConfig(log = MediaLog { log.log(ReceiverLog.Level.WARN, "media: $it") }),
                ),
            pairingFactory = pairing,
        )
}
