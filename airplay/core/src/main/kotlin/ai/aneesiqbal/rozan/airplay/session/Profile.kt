package ai.aneesiqbal.rozan.airplay.session

import ai.aneesiqbal.rozan.airplay.AirPlay
import ai.aneesiqbal.rozan.airplay.crypto.Crypto
import ai.aneesiqbal.rozan.airplay.receiver.PinMode
import java.util.Locale

/**
 * The fixed receiver profile from docs/protocol/01-discovery-and-session.md §1 and §2: an Apple TV 3rd
 * generation running AirPlay server 220.68, which keeps senders on NTP timing, type-110 mirroring,
 * type-96 audio and legacy pairing. Values are the spec's, verbatim.
 */
internal object Profile {
    /** `model`, `am`, /info `model`. */
    const val MODEL = "AppleTV3,2"

    /** `vv` in TXT and /info. */
    const val VV = 2

    /** `Server` header on every RTSP response (01 §3.4). */
    const val SERVER = "AirTunes/" + AirPlay.SOURCE_VERSION

    /** Feature bits for Mode D (bit 27 clear), 01 §2.4. */
    const val FEATURES_DIGEST = 0x527F_FEE6L

    /** Feature bits for Mode P (bit 27, legacy pairing, set), 01 §2.4. */
    const val FEATURES_PIN_PAIRING = 0x5A7F_FEE6L

    /** `_airplay._tcp` `pw` in Mode D: the code is asked for as the AirPlay password (01 §2.2, §2.5). */
    const val AIRPLAY_PW_DIGEST = "true"

    /**
     * `_airplay._tcp` `pw` in Mode P: no password. With PINRequired in `flags`, macOS shows its on-screen code
     * entry for mirroring instead of a password field, and runs the same PIN flow (01 §2.2, §2.5).
     */
    const val AIRPLAY_PW_PIN_PAIRING = "false"

    /** `_airplay._tcp` `flags` in Mode D: audio cable attached (01 §2.5). */
    const val AIRPLAY_FLAGS_DIGEST = "0x4"

    /** `_airplay._tcp` `flags` in Mode P: audio cable attached + PINRequired (01 §2.5). */
    const val AIRPLAY_FLAGS_PIN_PAIRING = "0xc"

    /** /info `statusFlags` in Mode D: 0x44 (01 §2.5, §6.1). */
    const val INFO_STATUS_FLAGS_DIGEST = 68L

    /** /info `statusFlags` in Mode P: 0x4C, Mode D's value + PINRequired (01 §2.5, §6.1). */
    const val INFO_STATUS_FLAGS_PIN_PAIRING = 76L

    /** `_raop._tcp` `pw` in Mode D (01 §2.3, §2.5). */
    const val RAOP_PW_DIGEST = "true"

    /**
     * `_raop._tcp` `pw` in Mode P: `true`. macOS shows its code entry with only the `_airplay` and /info
     * values changed, so this record keeps Mode D's (01 §2.5, 02 §8.6 test 6).
     */
    const val RAOP_PW_PIN_PAIRING = "true"

    /** RAOP `sf` in Mode D: cable attached + password required (01 §2.5). */
    const val RAOP_SF_DIGEST = "0x84"

    /** RAOP `sf` in Mode P: cable attached + PIN + password required (01 §2.5). */
    const val RAOP_SF_PIN_PAIRING = "0x8c"

    /** Receiver audio latency in 44.1 kHz frames, sent in `Audio-Latency` on RECORD (03 §1.5). */
    const val AUDIO_LATENCY_FRAMES = 11_025

    /** Longest receiver name in UTF-8 bytes: 63 minus the 13-byte RAOP prefix (01 §2.1). */
    const val MAX_NAME_BYTES = 50

    /** `Public` header for OPTIONS (01 §6.2): the RTSP methods this receiver implements. */
    const val PUBLIC_METHODS = "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"

    /** The 64-bit feature mask for [mode]; the high word is zero (no H.265, 03 §7). */
    fun features(mode: PinMode): Long =
        when (mode) {
            PinMode.PIN_PAIRING -> FEATURES_PIN_PAIRING
            PinMode.DIGEST -> FEATURES_DIGEST
        }

    /** Features in TXT form: `0x<low32>,0x<high32>`, uppercase hex without leading zeros (01 §2.2). */
    fun featuresText(features: Long): String =
        "0x" +
            java.lang.Long
                .toHexString(features and LOW_32)
                .uppercase(Locale.ROOT) + ",0x" +
            java.lang.Long
                .toHexString(features ushr 32)
                .uppercase(Locale.ROOT)

    /** `_airplay._tcp` `pw` for [mode] (01 §2.2, §2.5). */
    fun airplayPw(mode: PinMode): String =
        when (mode) {
            PinMode.PIN_PAIRING -> AIRPLAY_PW_PIN_PAIRING
            PinMode.DIGEST -> AIRPLAY_PW_DIGEST
        }

    /** `_airplay._tcp` `flags` for [mode] (01 §2.5). */
    fun airplayStatusFlags(mode: PinMode): String =
        when (mode) {
            PinMode.PIN_PAIRING -> AIRPLAY_FLAGS_PIN_PAIRING
            PinMode.DIGEST -> AIRPLAY_FLAGS_DIGEST
        }

    /** `_raop._tcp` `pw` for [mode] (01 §2.3, §2.5). */
    fun raopPw(mode: PinMode): String =
        when (mode) {
            PinMode.PIN_PAIRING -> RAOP_PW_PIN_PAIRING
            PinMode.DIGEST -> RAOP_PW_DIGEST
        }

    /** RAOP `sf` for [mode] (01 §2.5). */
    fun raopStatusFlags(mode: PinMode): String =
        when (mode) {
            PinMode.PIN_PAIRING -> RAOP_SF_PIN_PAIRING
            PinMode.DIGEST -> RAOP_SF_DIGEST
        }

    /** /info `statusFlags` for [mode] (01 §2.5, §6.1). */
    fun infoStatusFlags(mode: PinMode): Long =
        when (mode) {
            PinMode.PIN_PAIRING -> INFO_STATUS_FLAGS_PIN_PAIRING
            PinMode.DIGEST -> INFO_STATUS_FLAGS_DIGEST
        }

    private const val LOW_32 = 0xFFFF_FFFFL
}

/** Text forms of the per-install receiver identity (01 §1). */
internal object IdentityText {
    /** `deviceid` / `deviceID`: lowercase hex pairs joined by colons, e.g. `02:11:22:33:44:55`. */
    fun deviceId(deviceId: ByteArray): String = deviceId.joinToString(":") { hexByte(it).lowercase(Locale.ROOT) }

    /** RAOP instance prefix: 12 uppercase hex digits, e.g. `021122334455`. */
    fun raopPrefix(deviceId: ByteArray): String = deviceId.joinToString("") { hexByte(it) }

    /** Lowercase hex of [bytes], e.g. the TXT `pk`. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { hexByte(it).lowercase(Locale.ROOT) }

    private fun hexByte(byte: Byte): String {
        val value = byte.toInt() and 0xFF
        return HEX_DIGITS[value ushr 4].toString() + HEX_DIGITS[value and 0x0F]
    }

    /**
     * A per-install UUID string derived from [deviceId] and a [label]: the spec asks for random UUIDs
     * generated once per install (`pi`, display `uuid`), and the device id already is that per-install
     * random value, so hashing it gives the same stability without storing more state. Formatted as a
     * version-4, RFC 4122 variant UUID in lowercase.
     */
    fun derivedUuid(
        deviceId: ByteArray,
        label: String,
    ): String {
        val bytes = Crypto.sha512(label.toByteArray(Charsets.US_ASCII), deviceId).copyOf(UUID_BYTES)
        bytes[VERSION_BYTE] = ((bytes[VERSION_BYTE].toInt() and 0x0F) or 0x40).toByte()
        bytes[VARIANT_BYTE] = ((bytes[VARIANT_BYTE].toInt() and 0x3F) or 0x80).toByte()
        val hex = hex(bytes)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    /**
     * The advertised name (01 §2.1): cut at the first `.local`, trimmed, and limited to
     * [Profile.MAX_NAME_BYTES] UTF-8 bytes on a code point boundary. Falls back to "AirPlay" when nothing
     * is left.
     */
    fun advertisedName(name: String): String {
        val cut = name.substringBefore(".local").trim()
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < cut.length) {
            val codePoint = cut.codePointAt(index)
            val size = utf8Length(codePoint)
            if (bytes + size > Profile.MAX_NAME_BYTES) break
            out.appendCodePoint(codePoint)
            bytes += size
            index += Character.charCount(codePoint)
        }
        return out.toString().trim().ifEmpty { "AirPlay" }
    }

    private fun utf8Length(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val UUID_BYTES = 16
    private const val VERSION_BYTE = 6
    private const val VARIANT_BYTE = 8
}
