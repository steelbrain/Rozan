package ai.aneesiqbal.rozan.airplay.tlv

import java.io.ByteArrayOutputStream

/**
 * One TLV8 item: a type byte and its (reassembled) value. The value is copied in and out.
 *
 * @property type the item type, 0 to 255.
 */
public class TlvItem(
    public val type: UByte,
    value: ByteArray,
) {
    private val bytes = value.copyOf()

    /** Length of the value in bytes. */
    public val size: Int get() = bytes.size

    /** A copy of the value. */
    public fun value(): ByteArray = bytes.copyOf()

    internal fun unsafeValue(): ByteArray = bytes

    override fun equals(other: Any?): Boolean =
        other is TlvItem && other.type == type && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = type.hashCode() * 31 + bytes.contentHashCode()

    /** Type and length only: values are often keys or proofs. */
    override fun toString(): String = "TlvItem(type=$type, ${bytes.size} bytes)"
}

/**
 * Limits applied while decoding untrusted TLV8 data.
 *
 * @property maxItems most items accepted after fragments are joined.
 * @property maxValueBytes longest value accepted after fragments are joined.
 */
public class TlvLimits(
    public val maxItems: Int = DEFAULT_MAX_ITEMS,
    public val maxValueBytes: Int = DEFAULT_MAX_VALUE_BYTES,
) {
    init {
        require(maxItems >= 1) { "maxItems must be positive" }
        require(maxValueBytes >= 0) { "maxValueBytes must not be negative" }
    }

    /** Defaults. */
    public companion object {
        /** Default [maxItems]. */
        public const val DEFAULT_MAX_ITEMS: Int = 128

        /** Default [maxValueBytes]. */
        public const val DEFAULT_MAX_VALUE_BYTES: Int = 64 * 1024
    }
}

/**
 * A TLV8 message as used by HomeKit pairing: a sequence of `type (1 byte) ‖ length (1 byte) ‖ value`.
 *
 * A value longer than 255 bytes travels as consecutive items of the same type, every one but the last
 * exactly 255 bytes long; [decode] joins them and [encode] splits them. HomeKit separates list entries of
 * the same type with a zero-length [SEPARATOR] item; adding those is up to the caller.
 *
 * @param items the items in order, values unfragmented.
 */
public class Tlv8(
    items: List<TlvItem>,
) {
    /** The items in order. */
    public val items: List<TlvItem> = items.toList()

    /** A copy of the value of the first item of [type], or null when there is none. */
    public operator fun get(type: UByte): ByteArray? = items.firstOrNull { it.type == type }?.value()

    /** Copies of the values of every item of [type], in order. */
    public fun getAll(type: UByte): List<ByteArray> = items.filter { it.type == type }.map { it.value() }

    /**
     * The value of the first item of [type].
     *
     * @throws TlvException when there is no such item.
     */
    public fun requireValue(type: UByte): ByteArray = get(type) ?: throw TlvException("missing TLV item of type $type")

    /**
     * The single-byte value of the first item of [type] (a state, method or error code), or null when there
     * is no such item.
     *
     * @throws TlvException when the item is not exactly one byte long.
     */
    public fun byteValue(type: UByte): UByte? {
        val item = items.firstOrNull { it.type == type } ?: return null
        if (item.size != 1) throw TlvException("TLV item of type $type should be 1 byte, not ${item.size}")
        return item.unsafeValue()[0].toUByte()
    }

    /**
     * The wire encoding, with long values split into 255-byte fragments. When a value whose length is a
     * positive multiple of 255 is followed by an item of the same type, a zero-length fragment ends it, so
     * the two do not read back as one value.
     */
    public fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        for ((index, item) in items.withIndex()) {
            val value = item.unsafeValue()
            var offset = 0
            do {
                val length = minOf(MAX_FRAGMENT, value.size - offset)
                out.write(item.type.toInt())
                out.write(length)
                out.write(value, offset, length)
                offset += length
            } while (offset < value.size)
            val next = items.getOrNull(index + 1)
            if (next?.type == item.type && value.isNotEmpty() && value.size % MAX_FRAGMENT == 0) {
                out.write(item.type.toInt())
                out.write(0)
            }
        }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is Tlv8 && other.items == items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = "Tlv8($items)"

    /** Builds a [Tlv8] item by item. */
    public class Builder {
        private val items = ArrayList<TlvItem>()

        /** Appends an item of [type] holding a copy of [value]. */
        public fun add(
            type: UByte,
            value: ByteArray,
        ): Builder = apply { items.add(TlvItem(type, value)) }

        /** Appends an item of [type] holding the single byte [value]. */
        public fun add(
            type: UByte,
            value: UByte,
        ): Builder = add(type, byteArrayOf(value.toByte()))

        /** Appends a zero-length separator item (type [SEPARATOR]). */
        public fun separator(): Builder = add(SEPARATOR, ByteArray(0))

        /** Returns the message. */
        public fun build(): Tlv8 = Tlv8(items)
    }

    /** Decoding and constants. */
    public companion object {
        /** Longest fragment: values above this length are split. */
        public const val MAX_FRAGMENT: Int = 255

        /** Type of the zero-length item that separates list entries. */
        public const val SEPARATOR: UByte = 0xFFu

        /**
         * Decodes [length] bytes of untrusted TLV8 from [bytes] at [offset], joining fragmented values: an
         * item continues the previous one when it has the same type and the previous fragment was exactly
         * 255 bytes long.
         *
         * @throws TlvException when an item header or value is cut short or [limits] are exceeded.
         */
        public fun decode(
            bytes: ByteArray,
            offset: Int = 0,
            length: Int = bytes.size - offset,
            limits: TlvLimits = TlvLimits(),
        ): Tlv8 {
            require(offset >= 0 && length >= 0 && length <= bytes.size - offset) { "range outside the array" }
            return Tlv8(Tlv8Decoder(bytes, offset, offset + length, limits).decode())
        }
    }
}

private class Tlv8Decoder(
    private val bytes: ByteArray,
    private var pos: Int,
    private val end: Int,
    private val limits: TlvLimits,
) {
    private val items = ArrayList<TlvItem>()
    private var currentType = -1
    private var current = ByteArrayOutputStream()
    private var lastFragmentFull = false

    fun decode(): List<TlvItem> {
        while (pos < end) {
            if (end - pos < 2) fail("truncated TLV item header")
            val type = bytes[pos].toInt() and 0xFF
            val length = bytes[pos + 1].toInt() and 0xFF
            pos += 2
            if (end - pos < length) fail("TLV item value runs past the end")
            if (type != currentType || !lastFragmentFull) {
                flush()
                if (items.size >= limits.maxItems) fail("more than ${limits.maxItems} TLV items")
                currentType = type
            }
            if (current.size() + length > limits.maxValueBytes) {
                fail("TLV value longer than ${limits.maxValueBytes} bytes")
            }
            current.write(bytes, pos, length)
            pos += length
            lastFragmentFull = length == Tlv8.MAX_FRAGMENT
        }
        flush()
        return items
    }

    private fun flush() {
        if (currentType >= 0) {
            items.add(TlvItem(currentType.toUByte(), current.toByteArray()))
            current = ByteArrayOutputStream()
        }
    }

    private fun fail(message: String): Nothing = throw TlvException(message)
}
