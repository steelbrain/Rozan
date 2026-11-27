package ai.aneesiqbal.rozan.airplay.testing

import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.crypto.Ed25519KeyPair

/**
 * Who the test sender is. An Apple sender keeps these across connections, so reusing one identity for a
 * second connection is how a test plays "the same Mac connects again".
 *
 * @property deviceId the sender's device id, sent as the SRP user and the SETUP `deviceID`
 *   (`AA:BB:CC:DD:EE:FF` form).
 * @property name the device name the receiver shows (`X-Apple-Client-Name`, SETUP `name`).
 * @property model the SETUP `model`.
 * @property longTermKey the sender's Ed25519 key, sent in PIN step 3 and used to sign pair-verify.
 */
public class SenderIdentity(
    public val deviceId: String = randomDeviceId(),
    public val name: String = "Rozan Test Sender",
    public val model: String = "MacBookPro18,3",
    public val longTermKey: Ed25519KeyPair = Ed25519KeyPair.generate(),
) {
    /** The `DACP-ID` header: 64-bit upper-case hex. */
    public val dacpId: String = Crypto.randomBytes(8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** The `Active-Remote` header: a decimal token. */
    public val activeRemote: String =
        (Crypto.randomBytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) })
            .toString()

    private companion object {
        fun randomDeviceId(): String = Crypto.randomBytes(6).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
}
