package ai.aneesiqbal.rozan.airplay.plist

/**
 * Apple binary property lists (`bplist00`), the format AirPlay uses for most request and response bodies.
 *
 * Layout: an 8-byte header, the objects, a table of object offsets, and a 32-byte trailer that gives the
 * offset and reference sizes, the object count, the top object and where the offset table starts.
 * Containers refer to their children by object number.
 */
public object BinaryPlist {
    /** The 8 header bytes, `bplist00` in ASCII. */
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    /** Whether [bytes] starts with the binary plist header. Says nothing about the rest. */
    public fun isBinaryPlist(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * Decodes a binary plist from untrusted [bytes].
     *
     * Rejects, with [PlistException]: a bad header or trailer; offsets or lengths that point outside the
     * object area; references to objects that do not exist; cycles; nesting deeper than
     * [PlistLimits.maxDepth]; more values than [PlistLimits.maxObjects]; dictionaries with non-string or
     * repeated keys; integers outside the 64-bit range; non-ASCII bytes in ASCII strings; and the object
     * types AirPlay does not use (null, fill, sets). Memory use is bounded by the input size.
     */
    public fun decode(
        bytes: ByteArray,
        limits: PlistLimits = PlistLimits.DEFAULT,
    ): PlistValue {
        if (!isBinaryPlist(bytes)) malformed("not a binary plist")
        return BinaryPlistReader(bytes, limits).read()
    }

    /**
     * Encodes [value] as a binary plist. Equal strings, numbers, dates and data are stored once.
     *
     * @throws PlistException when containers nest deeper than [maxDepth].
     */
    public fun encode(
        value: PlistValue,
        maxDepth: Int = PlistLimits.DEFAULT_MAX_DEPTH,
    ): ByteArray = BinaryPlistWriter(maxDepth).write(value)

    internal fun magic(): ByteArray = MAGIC.copyOf()
}

/** Object markers: the high nibble of an object's first byte. */
private object Marker {
    const val SIMPLE = 0x0
    const val INT = 0x1
    const val REAL = 0x2
    const val DATE = 0x3
    const val DATA = 0x4
    const val ASCII = 0x5
    const val UTF16 = 0x6
    const val UID = 0x8
    const val ARRAY = 0xA
    const val SET = 0xC
    const val DICT = 0xD

    const val FALSE = 0x08
    const val TRUE = 0x09
    const val DATE_FULL = 0x33
    const val REAL_32 = 0x22
    const val REAL_64 = 0x23

    /** Low nibble meaning "the length follows as an integer object". */
    const val LENGTH_FOLLOWS = 0xF
}

private const val HEADER_SIZE = 8
private const val TRAILER_SIZE = 32

private class BinaryPlistReader(
    private val buf: ByteArray,
    private val limits: PlistLimits,
) {
    private var refSize = 0
    private var objectCount = 0
    private var objectsEnd = 0
    private var offsets = IntArray(0)

    // Per object: decoded value, its container height, and its size as a tree (shared children counted
    // once per reference). Filled the first time the object is decoded.
    private var cache = arrayOfNulls<PlistValue>(0)
    private var heights = IntArray(0)
    private var treeSizes = IntArray(0)
    private var inProgress = BooleanArray(0)

    fun read(): PlistValue {
        if (buf.size < HEADER_SIZE + 1 + 1 + TRAILER_SIZE) malformed("binary plist is too short")
        val trailer = buf.size - TRAILER_SIZE
        val offsetIntSize = u8(trailer + 6)
        refSize = u8(trailer + 7)
        val count = readBigEndian(trailer + 8, 8)
        val top = readBigEndian(trailer + 16, 8)
        val tableStart = readBigEndian(trailer + 24, 8)
        if (offsetIntSize !in 1..8 || refSize !in 1..8) malformed("bad integer sizes in trailer")
        if (count < 1 || count > limits.maxObjects) malformed("object count out of range")
        if (top < 0 || top >= count) malformed("top object out of range")
        if (tableStart < HEADER_SIZE + 1 || tableStart > trailer) malformed("offset table out of range")
        if (tableStart + count * offsetIntSize > trailer) malformed("offset table overlaps trailer")

        objectCount = count.toInt()
        objectsEnd = tableStart.toInt()
        offsets = IntArray(objectCount)
        for (i in 0 until objectCount) {
            val offset = readBigEndian(objectsEnd + i * offsetIntSize, offsetIntSize)
            if (offset < HEADER_SIZE || offset >= objectsEnd) malformed("object offset out of range")
            offsets[i] = offset.toInt()
        }
        cache = arrayOfNulls(objectCount)
        heights = IntArray(objectCount)
        treeSizes = IntArray(objectCount)
        inProgress = BooleanArray(objectCount)
        return resolve(top.toInt(), enclosing = 0)
    }

    /** Decodes object [index], found inside [enclosing] containers. */
    private fun resolve(
        index: Int,
        enclosing: Int,
    ): PlistValue {
        val cached = cache[index]
        if (cached != null) {
            if (enclosing + heights[index] > limits.maxDepth) depthError()
            return cached
        }
        if (inProgress[index]) malformed("object graph has a cycle")
        inProgress[index] = true
        val value = decodeObject(index, enclosing)
        inProgress[index] = false
        cache[index] = value
        return value
    }

    private fun depthError(): Nothing = malformed("containers nest deeper than ${limits.maxDepth}")

    private fun decodeObject(
        index: Int,
        enclosing: Int,
    ): PlistValue {
        val start = offsets[index]
        val marker = u8(start)
        val low = marker and 0xF
        treeSizes[index] = 1
        return when (marker ushr 4) {
            Marker.SIMPLE -> {
                decodeSimple(marker)
            }

            Marker.INT -> {
                decodeInteger(start + 1, low)
            }

            Marker.REAL -> {
                decodeReal(start + 1, marker)
            }

            Marker.DATE -> {
                if (marker != Marker.DATE_FULL) malformed("bad date marker")
                need(start + 1, 8)
                PlistDate(Double.fromBits(readBigEndian(start + 1, 8)))
            }

            Marker.DATA -> {
                val (length, pos) = readLength(start, low)
                need(pos, length.toLong())
                PlistData.adopt(buf.copyOfRange(pos, pos + length))
            }

            Marker.ASCII -> {
                decodeAscii(start, low)
            }

            Marker.UTF16 -> {
                decodeUtf16(start, low)
            }

            Marker.UID -> {
                decodeUid(start + 1, low)
            }

            Marker.ARRAY -> {
                decodeArray(index, start, low, enclosing)
            }

            Marker.DICT -> {
                decodeDict(index, start, low, enclosing)
            }

            Marker.SET -> {
                malformed("sets are not supported")
            }

            else -> {
                malformed("unknown object marker")
            }
        }
    }

    private fun decodeSimple(marker: Int): PlistValue =
        when (marker) {
            Marker.FALSE -> PlistBool(false)
            Marker.TRUE -> PlistBool(true)
            else -> malformed("null and fill objects are not supported")
        }

    private fun decodeInteger(
        pos: Int,
        sizeExponent: Int,
    ): PlistInteger {
        if (sizeExponent > 4) malformed("bad integer size")
        val size = 1 shl sizeExponent
        need(pos, size.toLong())
        if (size <= 8) {
            // 1-, 2- and 4-byte integers are unsigned; 8-byte integers are signed.
            return PlistInteger(readBigEndian(pos, size))
        }
        val high = readBigEndian(pos, 8)
        val low = readBigEndian(pos + 8, 8)
        return when {
            high == 0L -> PlistInteger.ofUnsigned(low.toULong())
            high == -1L && low < 0 -> PlistInteger(low)
            else -> malformed("integer does not fit in 64 bits")
        }
    }

    private fun decodeReal(
        pos: Int,
        marker: Int,
    ): PlistReal =
        when (marker) {
            Marker.REAL_32 -> {
                need(pos, 4)
                PlistReal(Float.fromBits(readBigEndian(pos, 4).toInt()).toDouble())
            }

            Marker.REAL_64 -> {
                need(pos, 8)
                PlistReal(Double.fromBits(readBigEndian(pos, 8)))
            }

            else -> {
                malformed("bad real size")
            }
        }

    private fun decodeAscii(
        start: Int,
        low: Int,
    ): PlistString {
        val (length, pos) = readLength(start, low)
        need(pos, length.toLong())
        val chars = CharArray(length)
        for (i in 0 until length) {
            val b = buf[pos + i].toInt()
            if (b < 0) malformed("non-ASCII byte in ASCII string")
            chars[i] = b.toChar()
        }
        return PlistString(String(chars))
    }

    private fun decodeUtf16(
        start: Int,
        low: Int,
    ): PlistString {
        val (units, pos) = readLength(start, low)
        need(pos, units * 2L)
        val chars = CharArray(units)
        for (i in 0 until units) {
            chars[i] = readBigEndian(pos + 2 * i, 2).toInt().toChar()
        }
        return PlistString(String(chars))
    }

    private fun decodeUid(
        pos: Int,
        low: Int,
    ): PlistUid {
        if (low > 7) malformed("bad UID size")
        val size = low + 1
        need(pos, size.toLong())
        val value = readBigEndian(pos, size)
        if (value < 0 || value > PlistUid.MAX_VALUE) malformed("UID out of range")
        return PlistUid(value)
    }

    private fun decodeArray(
        index: Int,
        start: Int,
        low: Int,
        enclosing: Int,
    ): PlistArray {
        val depth = enterContainer(enclosing)
        val (count, pos) = readLength(start, low)
        need(pos, count.toLong() * refSize)
        val items = ArrayList<PlistValue>(count)
        var height = 0
        var size = 1L
        for (i in 0 until count) {
            val child = readRef(pos + i * refSize)
            items.add(resolve(child, depth))
            height = maxOf(height, heights[child])
            size = addTreeSize(size, child)
        }
        heights[index] = height + 1
        treeSizes[index] = size.toInt()
        return PlistArray(items)
    }

    private fun decodeDict(
        index: Int,
        start: Int,
        low: Int,
        enclosing: Int,
    ): PlistDict {
        val depth = enterContainer(enclosing)
        val (count, pos) = readLength(start, low)
        need(pos, count.toLong() * refSize * 2)
        val entries = LinkedHashMap<String, PlistValue>()
        var height = 0
        var size = 1L
        val valuesStart = pos + count * refSize
        for (i in 0 until count) {
            val keyRef = readRef(pos + i * refSize)
            val key = resolve(keyRef, depth) as? PlistString ?: malformed("dictionary key is not a string")
            size = addTreeSize(size, keyRef)
            val valueRef = readRef(valuesStart + i * refSize)
            val value = resolve(valueRef, depth)
            size = addTreeSize(size, valueRef)
            height = maxOf(height, heights[valueRef])
            if (entries.put(key.value, value) != null) malformed("repeated dictionary key")
        }
        heights[index] = height + 1
        treeSizes[index] = size.toInt()
        return PlistDict(entries)
    }

    /** Returns the depth of a container found inside [enclosing] containers, after checking the limit. */
    private fun enterContainer(enclosing: Int): Int {
        val depth = enclosing + 1
        if (depth > limits.maxDepth) depthError()
        return depth
    }

    private fun addTreeSize(
        size: Long,
        child: Int,
    ): Long {
        val total = size + treeSizes[child]
        if (total > limits.maxObjects) malformed("plist expands to more than ${limits.maxObjects} values")
        return total
    }

    private fun readRef(pos: Int): Int {
        val ref = readBigEndian(pos, refSize)
        if (ref < 0 || ref >= objectCount) malformed("object reference out of range")
        return ref.toInt()
    }

    /**
     * Reads the element count of the object at [start] whose marker has low nibble [low]. Returns the count
     * and the position of the first content byte.
     */
    private fun readLength(
        start: Int,
        low: Int,
    ): Pair<Int, Int> {
        if (low != Marker.LENGTH_FOLLOWS) return low to start + 1
        need(start + 1, 1)
        val marker = u8(start + 1)
        if (marker ushr 4 != Marker.INT || marker and 0xF > 3) malformed("bad length object")
        val size = 1 shl (marker and 0xF)
        need(start + 2, size.toLong())
        val length = readBigEndian(start + 2, size)
        if (length < 0 || length > Int.MAX_VALUE) malformed("length out of range")
        return length.toInt() to start + 2 + size
    }

    /** Checks that [count] bytes starting at [pos] lie inside the object area. */
    private fun need(
        pos: Int,
        count: Long,
    ) {
        if (count < 0 || pos.toLong() + count > objectsEnd) malformed("object runs past the object area")
    }

    private fun u8(pos: Int): Int = buf[pos].toInt() and 0xFF

    /** Reads [size] (1..8) bytes big-endian. Callers check bounds first. */
    private fun readBigEndian(
        pos: Int,
        size: Int,
    ): Long {
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (buf[pos + i].toLong() and 0xFF)
        return value
    }
}

private class BinaryPlistWriter(
    private val maxDepth: Int,
) {
    private val objects = ArrayList<PlistValue>()
    private val children = ArrayList<IntArray?>()
    private val unique = HashMap<PlistValue, Int>()
    private val out = ByteSink()
    private var refSize = 1

    fun write(root: PlistValue): ByteArray {
        flatten(root, enclosing = 0)
        refSize = byteWidth((objects.size - 1).toLong())
        out.write(BinaryPlist.magic())
        val offsets = LongArray(objects.size)
        for (i in objects.indices) {
            offsets[i] = out.size.toLong()
            writeObject(objects[i], children[i])
        }
        val tableStart = out.size.toLong()
        // Apple's reader wants offsets wide enough for any position in the object area, not just the
        // offsets actually used (it rejects a long last object with one-byte offsets).
        val offsetSize = byteWidth(tableStart - 1)
        for (offset in offsets) out.writeBigEndian(offset, offsetSize)
        out.write(ByteArray(6))
        out.writeByte(offsetSize)
        out.writeByte(refSize)
        out.writeBigEndian(objects.size.toLong(), 8)
        out.writeBigEndian(0, 8)
        out.writeBigEndian(tableStart, 8)
        return out.toByteArray()
    }

    /** Assigns object numbers depth-first; returns [value]'s number. */
    private fun flatten(
        value: PlistValue,
        enclosing: Int,
    ): Int =
        when (value) {
            is PlistArray -> {
                val index = reserve(value, enclosing)
                children[index] = IntArray(value.size) { flatten(value.items[it], enclosing + 1) }
                index
            }

            is PlistDict -> {
                val index = reserve(value, enclosing)
                val refs = IntArray(value.size * 2)
                var i = 0
                for ((key, item) in value.entries) {
                    refs[i] = flatten(PlistString(key), enclosing + 1)
                    refs[value.size + i] = flatten(item, enclosing + 1)
                    i++
                }
                children[index] = refs
                index
            }

            else -> {
                unique.getOrPut(value) {
                    objects.add(value)
                    children.add(null)
                    objects.size - 1
                }
            }
        }

    private fun reserve(
        container: PlistValue,
        enclosing: Int,
    ): Int {
        if (enclosing + 1 > maxDepth) malformed("containers nest deeper than $maxDepth")
        objects.add(container)
        children.add(null)
        return objects.size - 1
    }

    private fun writeObject(
        value: PlistValue,
        refs: IntArray?,
    ) {
        when (value) {
            is PlistBool -> {
                out.writeByte(if (value.value) Marker.TRUE else Marker.FALSE)
            }

            is PlistInteger -> {
                writeInteger(value)
            }

            is PlistReal -> {
                out.writeByte(Marker.REAL_64)
                out.writeBigEndian(value.value.toRawBits(), 8)
            }

            is PlistDate -> {
                out.writeByte(Marker.DATE_FULL)
                out.writeBigEndian(value.secondsSinceReferenceDate.toRawBits(), 8)
            }

            is PlistData -> {
                writeHeader(Marker.DATA, value.size)
                out.write(value.unsafeBytes())
            }

            is PlistString -> {
                writeString(value.value)
            }

            is PlistUid -> {
                val size = byteWidth(value.value) // at most 4: PlistUid caps the value at 2^32 - 1
                out.writeByte((Marker.UID shl 4) or (size - 1))
                out.writeBigEndian(value.value, size)
            }

            is PlistArray -> {
                writeHeader(Marker.ARRAY, value.size)
                checkNotNull(refs).forEach { out.writeBigEndian(it.toLong(), refSize) }
            }

            is PlistDict -> {
                writeHeader(Marker.DICT, value.size)
                checkNotNull(refs).forEach { out.writeBigEndian(it.toLong(), refSize) }
            }
        }
    }

    private fun writeInteger(value: PlistInteger) {
        when {
            value.isLargeUnsigned -> {
                out.writeByte((Marker.INT shl 4) or 4)
                out.writeBigEndian(0, 8)
                out.writeBigEndian(value.value, 8)
            }

            value.value < 0 -> {
                out.writeByte((Marker.INT shl 4) or 3)
                out.writeBigEndian(value.value, 8)
            }

            else -> {
                writeUnsigned(value.value)
            }
        }
    }

    /** Writes a non-negative integer object in the smallest of the 1, 2, 4 and 8 byte forms. */
    private fun writeUnsigned(value: Long) {
        val size = byteWidth(value)
        out.writeByte((Marker.INT shl 4) or Integer.numberOfTrailingZeros(size))
        out.writeBigEndian(value, size)
    }

    private fun writeString(text: String) {
        if (text.all { it.code < 0x80 }) {
            writeHeader(Marker.ASCII, text.length)
            for (c in text) out.writeByte(c.code)
        } else {
            writeHeader(Marker.UTF16, text.length)
            for (c in text) out.writeBigEndian(c.code.toLong(), 2)
        }
    }

    private fun writeHeader(
        type: Int,
        count: Int,
    ) {
        if (count < Marker.LENGTH_FOLLOWS) {
            out.writeByte((type shl 4) or count)
        } else {
            out.writeByte((type shl 4) or Marker.LENGTH_FOLLOWS)
            writeUnsigned(count.toLong())
        }
    }

    /** Smallest of 1, 2, 4 or 8 bytes that holds the non-negative [value]. */
    private fun byteWidth(value: Long): Int =
        when {
            value < 0x100 -> 1
            value < 0x1_0000 -> 2
            value < 0x1_0000_0000L -> 4
            else -> 8
        }
}

/** A growable byte buffer without the synchronisation of `ByteArrayOutputStream`. */
private class ByteSink {
    private var bytes = ByteArray(INITIAL_CAPACITY)

    var size = 0
        private set

    fun writeByte(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun write(data: ByteArray) {
        ensure(data.size)
        data.copyInto(bytes, size)
        size += data.size
    }

    fun writeBigEndian(
        value: Long,
        width: Int,
    ) {
        ensure(width)
        for (i in width - 1 downTo 0) bytes[size++] = (value ushr (8 * i)).toByte()
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed < 0) malformed("plist is too large to encode")
        if (needed > bytes.size) {
            val grown = maxOf(needed, if (bytes.size > Int.MAX_VALUE / 2) Int.MAX_VALUE else bytes.size * 2)
            bytes = bytes.copyOf(grown)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}
