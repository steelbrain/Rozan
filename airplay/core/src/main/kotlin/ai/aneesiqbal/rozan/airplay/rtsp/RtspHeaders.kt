package ai.aneesiqbal.rozan.airplay.rtsp

import java.util.Collections

/**
 * One header field as it appeared on the wire.
 *
 * @property name the field name with its original case, e.g. `CSeq`.
 * @property value the field value without surrounding whitespace. Bytes are mapped to chars one to one
 *   (ISO-8859-1), so a UTF-8 value can be recovered with `value.toByteArray(Charsets.ISO_8859_1)`.
 */
public data class RtspHeader(
    val name: String,
    val value: String,
)

/**
 * An immutable, ordered list of header fields.
 *
 * Lookups ignore the case of the name, as RFC 9110 and RFC 2326 require; iteration returns the fields in
 * their original order and case, duplicates included.
 */
public class RtspHeaders internal constructor(
    entries: List<RtspHeader>,
) : Iterable<RtspHeader> {
    private val entries: List<RtspHeader> = Collections.unmodifiableList(entries)

    /** Number of fields, counting duplicates. */
    public val size: Int get() = entries.size

    /** Returns the value of the first field named [name] (any case), or null. */
    public operator fun get(name: String): String? =
        entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /** Returns the values of every field named [name] (any case), in order. */
    public fun getAll(name: String): List<String> =
        entries.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

    /** True if at least one field is named [name] (any case). */
    public operator fun contains(name: String): Boolean = entries.any { it.name.equals(name, ignoreCase = true) }

    /** Iterates over the fields in wire order. */
    override fun iterator(): Iterator<RtspHeader> = entries.iterator()

    /** Returns a builder that starts with these fields. */
    public fun toBuilder(): Builder = Builder(entries)

    /** Equal when both hold the same fields, with the same case, in the same order. */
    override fun equals(other: Any?): Boolean = other is RtspHeaders && other.entries == entries

    /** Hash of the fields in order. */
    override fun hashCode(): Int = entries.hashCode()

    /** Lists the fields as `name: value` pairs. */
    override fun toString(): String =
        entries.joinToString(prefix = "RtspHeaders(", postfix = ")") {
            "${it.name}: ${it.value}"
        }

    /**
     * Builds an [RtspHeaders] in insertion order.
     *
     * Names must be HTTP tokens. Values may contain visible characters, spaces, tabs and chars
     * U+0080..U+00FF (written as single bytes), but no line breaks or other control characters, and no
     * leading or trailing whitespace. Invalid input throws [IllegalArgumentException]: it is a programming
     * error, never the peer's.
     */
    public class Builder internal constructor(
        initial: List<RtspHeader>,
    ) {
        private val entries = ArrayList(initial)

        /** Starts with no fields. */
        public constructor() : this(emptyList())

        /** Appends a field, keeping any existing fields of the same name. */
        public fun add(
            name: String,
            value: String,
        ): Builder {
            entries += checked(name, value)
            return this
        }

        /**
         * Sets the only field named [name] (any case) to [value]: replaces the first existing one in place
         * and removes the rest, or appends if there is none.
         */
        public fun set(
            name: String,
            value: String,
        ): Builder {
            val header = checked(name, value)
            val first = entries.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (first < 0) {
                entries += header
            } else {
                entries[first] = header
                for (i in entries.lastIndex downTo first + 1) {
                    if (entries[i].name.equals(name, ignoreCase = true)) entries.removeAt(i)
                }
            }
            return this
        }

        /** Removes every field named [name] (any case). */
        public fun remove(name: String): Builder {
            entries.removeAll { it.name.equals(name, ignoreCase = true) }
            return this
        }

        /** Returns the fields added so far. */
        public fun build(): RtspHeaders = RtspHeaders(ArrayList(entries))

        private fun checked(
            name: String,
            value: String,
        ): RtspHeader {
            require(Syntax.isToken(name)) { "header name is not a token: $name" }
            require(Syntax.isFieldValue(value)) { "invalid value for header $name" }
            return RtspHeader(name, value)
        }
    }

    /** Constants and factories. */
    public companion object {
        /** No fields. */
        public val EMPTY: RtspHeaders = RtspHeaders(emptyList())

        /** Returns headers holding [fields] in order, validated as by [Builder.add]. */
        public fun of(vararg fields: Pair<String, String>): RtspHeaders {
            val builder = Builder()
            for ((name, value) in fields) builder.add(name, value)
            return builder.build()
        }
    }
}
