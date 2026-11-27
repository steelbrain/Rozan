package ai.aneesiqbal.rozan.airplay

import kotlin.random.Random

/**
 * Default receiver names: four distinct fruit names, e.g. "Mango Kiwi Plum Fig".
 *
 * The name is what people see in the AirPlay menu on their Mac or iPhone, so it
 * must be short, readable and unlikely to collide with a neighbour's receiver.
 * 48 words taken 4 at a time give about 4.7 million ordered names.
 */
public object DeviceNames {
    /** Number of words in a generated name. */
    public const val WORD_COUNT: Int = 4

    /** Longest name accepted for a receiver (DNS-SD instance labels are limited to 63 bytes). */
    public const val MAX_LENGTH_BYTES: Int = 63

    internal val FRUITS: List<String> =
        listOf(
            "Apple",
            "Apricot",
            "Banana",
            "Bilberry",
            "Blackberry",
            "Blueberry",
            "Cherry",
            "Clementine",
            "Coconut",
            "Cranberry",
            "Currant",
            "Damson",
            "Date",
            "Elderberry",
            "Feijoa",
            "Fig",
            "Gooseberry",
            "Grape",
            "Guava",
            "Huckleberry",
            "Jackfruit",
            "Jujube",
            "Kiwi",
            "Kumquat",
            "Lemon",
            "Lime",
            "Loquat",
            "Lychee",
            "Mandarin",
            "Mango",
            "Melon",
            "Mulberry",
            "Nectarine",
            "Olive",
            "Orange",
            "Papaya",
            "Passionfruit",
            "Peach",
            "Pear",
            "Persimmon",
            "Pineapple",
            "Plum",
            "Pomegranate",
            "Pomelo",
            "Quince",
            "Raspberry",
            "Satsuma",
            "Tangerine",
        )

    /** Returns a new random name of [WORD_COUNT] distinct fruits separated by spaces. */
    public fun random(random: Random = Random.Default): String =
        FRUITS.shuffled(random).take(WORD_COUNT).joinToString(" ")

    /**
     * Normalises a user-entered name: trims, collapses whitespace, drops control
     * characters and cuts it to [MAX_LENGTH_BYTES] UTF-8 bytes without splitting
     * a character. Returns null when nothing usable is left.
     */
    public fun sanitize(input: String): String? {
        val cleaned =
            input
                .filterNot { it.isISOControl() }
                .trim()
                .replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < cleaned.length) {
            val codePoint = cleaned.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val size = cleaned.substring(index, index + charCount).toByteArray(Charsets.UTF_8).size
            if (bytes + size > MAX_LENGTH_BYTES) break
            out.appendCodePoint(codePoint)
            bytes += size
            index += charCount
        }
        return out.toString().trim().ifEmpty { null }
    }
}
