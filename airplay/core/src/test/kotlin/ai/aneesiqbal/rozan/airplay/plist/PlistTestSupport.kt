package ai.aneesiqbal.rozan.airplay.plist

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Fixture names under `src/test/resources/plist/`; each has an `.xml` source and its `plutil` `.bplist`. */
val FIXTURES = listOf("scalars", "nested", "uid", "many-objects", "depth-32")

fun resource(name: String): ByteArray =
    checkNotNull(PlistTestSupport::class.java.getResourceAsStream("/plist/$name")) { "missing fixture $name" }
        .use { it.readBytes() }

object PlistTestSupport

/** Bytes from unsigned ints, for hand-built binary plists. */
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/**
 * Assembles a binary plist from raw object encodings. Every parameter after [objects] can be overridden to
 * build a broken trailer or offset table.
 */
fun craftBplist(
    objects: List<ByteArray>,
    top: Long = 0,
    refSize: Int = 1,
    offsetSize: Int = 1,
    count: Long = objects.size.toLong(),
    offsets: List<Long>? = null,
    tableOffset: Long? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("bplist00".toByteArray())
    val computed = mutableListOf<Long>()
    for (obj in objects) {
        computed.add(out.size().toLong())
        out.write(obj)
    }
    val tableStart = out.size().toLong()
    for (offset in offsets ?: computed) writeBigEndian(out, offset, offsetSize)
    out.write(ByteArray(6))
    out.write(offsetSize)
    out.write(refSize)
    writeBigEndian(out, count, 8)
    writeBigEndian(out, top, 8)
    writeBigEndian(out, tableOffset ?: tableStart, 8)
    return out.toByteArray()
}

private fun writeBigEndian(
    out: ByteArrayOutputStream,
    value: Long,
    width: Int,
) {
    for (i in width - 1 downTo 0) out.write((value ushr (8 * i)).toInt() and 0xFF)
}

/**
 * Random plist values that survive both encodings unchanged: dates have whole seconds, and strings avoid
 * CR (XML turns a raw CR into LF) and characters XML cannot carry.
 */
class RandomPlist(
    private val random: Random,
) {
    private val alphabet =
        "abcXYZ019 _-.:/&<>\"'\t\näöü€世界" + "🍑" // the last is a surrogate pair (peach emoji)

    fun value(depth: Int = 0): PlistValue =
        when (random.nextInt(if (depth >= 4) 8 else 10)) {
            0 -> PlistString(string())
            1 -> integer()
            2 -> PlistReal(real())
            3 -> PlistBool(random.nextBoolean())
            4 -> PlistDate(random.nextLong(-3_000_000_000L, 3_000_000_000L).toDouble())
            5 -> PlistData(random.nextBytes(if (random.nextInt(10) == 0) 300 else random.nextInt(40)))
            6 -> PlistUid(random.nextLong(0, PlistUid.MAX_VALUE + 1))
            7 -> PlistString(string())
            8 -> PlistArray(List(random.nextInt(18)) { value(depth + 1) })
            else -> PlistDict(List(random.nextInt(18)) { string() to value(depth + 1) }.toMap())
        }

    private fun string(): String {
        val sb = StringBuilder()
        repeat(random.nextInt(if (random.nextInt(8) == 0) 40 else 8)) {
            val i = random.nextInt(alphabet.length)
            val c = alphabet[i]
            if (Character.isHighSurrogate(c)) {
                sb.append(c).append(alphabet[i + 1])
            } else if (!Character.isLowSurrogate(c)) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun integer(): PlistInteger =
        when (random.nextInt(5)) {
            0 -> PlistInteger(random.nextLong(0, 256))
            1 -> PlistInteger(random.nextLong(0, 1L shl 33))
            2 -> PlistInteger(random.nextLong())
            3 -> PlistInteger.ofUnsigned(random.nextLong().toULong() or (1UL shl 63))
            else -> PlistInteger(-random.nextLong(0, 1000))
        }

    private fun real(): Double =
        when (random.nextInt(8)) {
            0 -> Double.NaN
            1 -> Double.POSITIVE_INFINITY
            2 -> Double.NEGATIVE_INFINITY
            3 -> random.nextInt(-1000, 1000).toDouble()
            else -> Double.fromBits(random.nextLong()).takeIf { it.isFinite() } ?: 1.5
        }
}
