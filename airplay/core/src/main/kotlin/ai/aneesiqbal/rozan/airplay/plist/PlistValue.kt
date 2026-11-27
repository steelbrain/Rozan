package ai.aneesiqbal.rozan.airplay.plist

import java.time.Instant

/**
 * A property list value. Instances are immutable: containers copy what they are given, and [PlistData]
 * copies its bytes in and out.
 *
 * Decode with [BinaryPlist.decode] or [XmlPlist.decode]; encode with [BinaryPlist.encode] or
 * [XmlPlist.encode].
 */
public sealed interface PlistValue

/**
 * A dictionary with string keys. Iteration follows insertion order (for decoded plists: the order in the
 * input). Equality ignores order, like a [Map].
 */
public class PlistDict(
    entries: Map<String, PlistValue>,
) : PlistValue {
    /** The entries, in insertion order. */
    public val entries: Map<String, PlistValue> = LinkedHashMap(entries)

    /** Number of entries. */
    public val size: Int get() = entries.size

    /** The value for [key], or null when absent. */
    public operator fun get(key: String): PlistValue? = entries[key]

    /** Whether [key] is present. */
    public operator fun contains(key: String): Boolean = key in entries

    /** The string value for [key], or null when absent or not a string. */
    public fun string(key: String): String? = (entries[key] as? PlistString)?.value

    /**
     * The integer value for [key], or null when absent or not an integer. For an integer with
     * [PlistInteger.isLargeUnsigned] set this is its bit pattern; use [PlistInteger.toULongOrNull] for those.
     */
    public fun long(key: String): Long? = (entries[key] as? PlistInteger)?.value

    /** The value for [key] as a double, accepting both reals and integers; null when absent or neither. */
    public fun double(key: String): Double? =
        when (val value = entries[key]) {
            is PlistReal -> value.value
            is PlistInteger -> value.toDouble()
            else -> null
        }

    /** The boolean value for [key], or null when absent or not a boolean. */
    public fun bool(key: String): Boolean? = (entries[key] as? PlistBool)?.value

    /** A copy of the data value for [key], or null when absent or not data. */
    public fun data(key: String): ByteArray? = (entries[key] as? PlistData)?.toByteArray()

    /** The dictionary value for [key], or null when absent or not a dictionary. */
    public fun dict(key: String): PlistDict? = entries[key] as? PlistDict

    /** The array value for [key], or null when absent or not an array. */
    public fun array(key: String): PlistArray? = entries[key] as? PlistArray

    override fun equals(other: Any?): Boolean = other is PlistDict && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "PlistDict($entries)"

    /** Builds a [PlistDict] entry by entry; a repeated key replaces the earlier value in place. */
    public class Builder {
        private val entries = LinkedHashMap<String, PlistValue>()

        /** Sets [key] to [value]. */
        public fun put(
            key: String,
            value: PlistValue,
        ): Builder = apply { entries[key] = value }

        /** Sets [key] to a string. */
        public fun put(
            key: String,
            value: String,
        ): Builder = put(key, PlistString(value))

        /** Sets [key] to an integer. */
        public fun put(
            key: String,
            value: Long,
        ): Builder = put(key, PlistInteger(value))

        /** Sets [key] to an integer. */
        public fun put(
            key: String,
            value: Int,
        ): Builder = put(key, PlistInteger(value.toLong()))

        /** Sets [key] to a real. */
        public fun put(
            key: String,
            value: Double,
        ): Builder = put(key, PlistReal(value))

        /** Sets [key] to a boolean. */
        public fun put(
            key: String,
            value: Boolean,
        ): Builder = put(key, PlistBool(value))

        /** Sets [key] to a copy of [value] as data. */
        public fun put(
            key: String,
            value: ByteArray,
        ): Builder = put(key, PlistData(value))

        /** Returns the dictionary built so far. */
        public fun build(): PlistDict = PlistDict(entries)
    }
}

/** Builds a [PlistDict] with [block]. */
public inline fun buildPlistDict(block: PlistDict.Builder.() -> Unit): PlistDict =
    PlistDict.Builder().apply(block).build()

/** An ordered array. */
public class PlistArray(
    items: List<PlistValue>,
) : PlistValue {
    /** The elements, in order. */
    public val items: List<PlistValue> = items.toList()

    /** Number of elements. */
    public val size: Int get() = items.size

    /** The element at [index]. */
    public operator fun get(index: Int): PlistValue = items[index]

    override fun equals(other: Any?): Boolean = other is PlistArray && other.items == items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = "PlistArray($items)"
}

/** A string. */
public data class PlistString(
    /** The text. */
    val value: String,
) : PlistValue

/**
 * An integer.
 *
 * Holds every signed 64-bit value, and also unsigned 64-bit values of 2^63 and above, which binary plists
 * store as 128-bit integers. For those [isLargeUnsigned] is true and [value] holds the bit pattern (so it
 * reads as negative); [toULongOrNull] returns the real value. Values outside both ranges cannot be
 * represented, and the readers reject them.
 */
public class PlistInteger private constructor(
    /** The value as a signed 64-bit integer, or the bit pattern when [isLargeUnsigned] is true. */
    public val value: Long,
    /** True when this is an unsigned value of 2^63 or more. */
    public val isLargeUnsigned: Boolean,
) : PlistValue {
    /** A signed 64-bit integer. */
    public constructor(value: Long) : this(value, false)

    /** The value as an unsigned 64-bit integer, or null when it is negative. */
    public fun toULongOrNull(): ULong? = if (isLargeUnsigned || value >= 0) value.toULong() else null

    /** The value as a double (rounded for magnitudes above 2^53). */
    public fun toDouble(): Double = if (isLargeUnsigned) value.toULong().toDouble() else value.toDouble()

    override fun equals(other: Any?): Boolean =
        other is PlistInteger && other.value == value && other.isLargeUnsigned == isLargeUnsigned

    override fun hashCode(): Int = value.hashCode() * 31 + isLargeUnsigned.hashCode()

    override fun toString(): String =
        "PlistInteger(${if (isLargeUnsigned) value.toULong().toString() else value.toString()})"

    /** Factories. */
    public companion object {
        /** An integer holding the unsigned 64-bit [value]. */
        public fun ofUnsigned(value: ULong): PlistInteger =
            if (value <= Long.MAX_VALUE.toULong()) {
                PlistInteger(value.toLong())
            } else {
                PlistInteger(value.toLong(), isLargeUnsigned = true)
            }
    }
}

/** A floating-point number. Binary plists may store it as 32-bit; it is always read into a double. */
public data class PlistReal(
    /** The number. */
    val value: Double,
) : PlistValue

/** A boolean. */
public data class PlistBool(
    /** The truth value. */
    val value: Boolean,
) : PlistValue

/** Raw bytes. The bytes are copied on construction and by [toByteArray]. */
public class PlistData private constructor(
    bytes: ByteArray,
    copy: Boolean,
) : PlistValue {
    private val bytes: ByteArray = if (copy) bytes.copyOf() else bytes

    /** Data holding a copy of [bytes]. */
    public constructor(bytes: ByteArray) : this(bytes, copy = true)

    /** Number of bytes. */
    public val size: Int get() = bytes.size

    /** A copy of the bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    internal fun unsafeBytes(): ByteArray = bytes

    override fun equals(other: Any?): Boolean = other is PlistData && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "PlistData(${bytes.size} bytes)"

    internal companion object {
        /** Wraps [bytes] without copying; the caller must not keep or change them. */
        fun adopt(bytes: ByteArray): PlistData = PlistData(bytes, copy = false)
    }
}

/**
 * A point in time, stored as Apple stores it: seconds (possibly fractional) since the reference date
 * 2001-01-01T00:00:00Z. XML plists keep whole seconds only.
 */
public data class PlistDate(
    /** Seconds since 2001-01-01T00:00:00Z; negative for earlier times. */
    val secondsSinceReferenceDate: Double,
) : PlistValue {
    /**
     * This date as an [Instant], rounded down to the nanosecond.
     *
     * @throws PlistException when the date is not finite or lies outside about ±31 million years.
     */
    public fun toInstant(): Instant {
        val whole = Math.floor(secondsSinceReferenceDate)
        if (!(whole >= MIN_SECONDS && whole <= MAX_SECONDS)) throw PlistException("date is out of range")
        val nanos = ((secondsSinceReferenceDate - whole) * NANOS_PER_SECOND).toLong().coerceIn(0, NANOS_PER_SECOND - 1)
        return Instant.ofEpochSecond(whole.toLong() + REFERENCE_DATE_UNIX_SECONDS, nanos)
    }

    /** Conversions. */
    public companion object {
        /** Unix time of the reference date 2001-01-01T00:00:00Z. */
        public const val REFERENCE_DATE_UNIX_SECONDS: Long = 978_307_200L

        private const val NANOS_PER_SECOND = 1_000_000_000L

        // Keeps toInstant() inside Instant's range (about ±3.1e16 s) with room to spare.
        private const val MIN_SECONDS = -1.0e15
        private const val MAX_SECONDS = 1.0e15

        /** The date for [instant]. Sub-microsecond precision may be lost to the double. */
        public fun of(instant: Instant): PlistDate =
            PlistDate(
                (instant.epochSecond - REFERENCE_DATE_UNIX_SECONDS).toDouble() +
                    instant.nano / NANOS_PER_SECOND.toDouble(),
            )
    }
}

/** A keyed-archiver object reference (`CF$UID`). Not used by AirPlay itself; kept so archives round-trip. */
public data class PlistUid(
    /** The referenced object number, 0 to 2^32 - 1. */
    val value: Long,
) : PlistValue {
    init {
        require(value in 0..MAX_VALUE) { "UID out of range" }
    }

    /** Limits. */
    public companion object {
        /** Largest UID value. */
        public const val MAX_VALUE: Long = 0xFFFF_FFFFL
    }
}

/**
 * Limits applied while decoding untrusted plists.
 *
 * @property maxObjects largest number of values accepted. For binary plists this caps both the object
 *   count in the trailer and the number of values in the decoded tree, counting a shared object once per
 *   reference, so a small input cannot expand into a huge tree.
 * @property maxDepth deepest container nesting accepted; the top-level value is depth 1.
 */
public class PlistLimits(
    public val maxObjects: Int = DEFAULT_MAX_OBJECTS,
    public val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    init {
        require(maxObjects >= 1) { "maxObjects must be positive" }
        require(maxDepth in 1..MAX_DEPTH_CEILING) { "maxDepth must be in 1..$MAX_DEPTH_CEILING" }
    }

    /** Defaults. */
    public companion object {
        /** Default [maxObjects]. */
        public const val DEFAULT_MAX_OBJECTS: Int = 10_000

        /** Default [maxDepth]. */
        public const val DEFAULT_MAX_DEPTH: Int = 32

        /** Highest [maxDepth] allowed, to keep recursion well inside the thread stack. */
        public const val MAX_DEPTH_CEILING: Int = 256

        /** Limits with every default. */
        public val DEFAULT: PlistLimits = PlistLimits()
    }
}
