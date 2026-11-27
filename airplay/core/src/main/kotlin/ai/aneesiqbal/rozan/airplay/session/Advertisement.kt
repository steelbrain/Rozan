package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.AirPlay
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import ai.aneesiqbal.rozan.airplay.receiver.ServiceRecord
import java.io.ByteArrayOutputStream

/**
 * What the receiver advertises at one moment (docs/protocol/01-discovery-and-session.md §2): both TXT
 * records, the DNS-SD service records, and the TXT rdata that `GET /info` returns as `txtAirPlay` and
 * `txtRAOP` (§2.6, §6.1). Immutable; a new instance is built whenever the name or the identity key
 * changes, so `/info` and mDNS always agree.
 *
 * @param requestedName the configured receiver name, before the §2.1 rules are applied.
 * @param deviceId the 6-byte receiver device id.
 * @param publicKey the current 32-byte Ed25519 identity public key (`pk`).
 * @property mode the access-control mode, which selects feature bit 27, `pw` in each record, the `_airplay`
 *   `flags`, the RAOP `sf` and the `/info` `statusFlags` (01 §2.5).
 * @property pi the per-install pairing identifier UUID.
 */
internal class Advertisement(
    requestedName: String,
    deviceId: ByteArray,
    publicKey: ByteArray,
    val mode: PinMode,
    val pi: String,
) {
    /** The name as advertised: cut at `.local`, at most 50 UTF-8 bytes (01 §2.1). */
    val name: String = IdentityText.advertisedName(requestedName)

    /** The name this advertisement was built from, to detect renames. */
    val requestedName: String = requestedName

    /** `deviceid` / `deviceID` text, lowercase with colons. */
    val deviceIdText: String = IdentityText.deviceId(deviceId)

    /** The 64-bit feature mask (01 §2.4). */
    val features: Long = Profile.features(mode)

    private val publicKeyBytes = publicKey.copyOf()
    private val raopInstance = IdentityText.raopPrefix(deviceId) + "@" + name

    /** A copy of the identity public key. */
    val publicKey: ByteArray get() = publicKeyBytes.copyOf()

    /** Whether this advertisement was built for [key]. */
    fun hasPublicKey(key: ByteArray): Boolean = publicKeyBytes.contentEquals(key)

    /** `_airplay._tcp` TXT entries, in the order of 01 §2.2. */
    val airplayTxt: List<Pair<String, String>> =
        listOf(
            "deviceid" to deviceIdText,
            "features" to Profile.featuresText(features),
            "pw" to Profile.airplayPw(mode),
            "flags" to Profile.airplayStatusFlags(mode),
            "model" to Profile.MODEL,
            "pk" to IdentityText.hex(publicKeyBytes),
            "pi" to pi,
            "srcvers" to AirPlay.SOURCE_VERSION,
            "vv" to Profile.VV.toString(),
        )

    /** `_raop._tcp` TXT entries, in the order of 01 §2.3. */
    val raopTxt: List<Pair<String, String>> =
        listOf(
            "ch" to "2",
            "cn" to "0,1,2,3",
            "da" to "true",
            "et" to "0,3,5",
            "vv" to Profile.VV.toString(),
            "ft" to Profile.featuresText(features),
            "am" to Profile.MODEL,
            "md" to "0,1,2",
            "rhd" to "5.6.0.0",
            "pw" to Profile.raopPw(mode),
            "sf" to Profile.raopStatusFlags(mode),
            "sr" to "44100",
            "ss" to "16",
            "sv" to "false",
            "tp" to "UDP",
            "txtvers" to "1",
            "vs" to AirPlay.SOURCE_VERSION,
            "vn" to "65537",
            "pk" to IdentityText.hex(publicKeyBytes),
        )

    /** The `_airplay._tcp` TXT rdata (01 §2.6), a fresh copy. */
    fun airplayRdata(): ByteArray = TxtRdata.encode(airplayTxt)

    /** The `_raop._tcp` TXT rdata, a fresh copy. */
    fun raopRdata(): ByteArray = TxtRdata.encode(raopTxt)

    /** Both service records on control [port] (01 §2.1). */
    fun records(port: Int): List<ServiceRecord> =
        listOf(
            ServiceRecord(name, AIRPLAY_SERVICE, port, airplayTxt),
            ServiceRecord(raopInstance, RAOP_SERVICE, port, raopTxt),
        )

    companion object {
        /** DNS-SD type of the AirPlay service. */
        const val AIRPLAY_SERVICE = "_airplay._tcp"

        /** DNS-SD type of the RAOP (audio) service. */
        const val RAOP_SERVICE = "_raop._tcp"
    }
}

/** DNS TXT rdata (RFC 6763 §6): each entry as one length byte and `key=value` in UTF-8. */
internal object TxtRdata {
    private const val MAX_STRING_BYTES = 255

    /** Encodes [entries]; each `key=value` must fit in 255 bytes. */
    fun encode(entries: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val bytes = "$key=$value".toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_STRING_BYTES) { "TXT entry $key is longer than 255 bytes" }
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        return out.toByteArray()
    }
}
