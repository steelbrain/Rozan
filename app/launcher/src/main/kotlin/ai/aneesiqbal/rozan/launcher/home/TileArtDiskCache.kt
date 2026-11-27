package ai.aneesiqbal.rozan.launcher.home

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Rendered tile art from earlier starts, one PNG per tile in the cache directory, so that a cold start
 * (or a return after the art was released under memory pressure) draws app tiles without loading the
 * apps' resources and rendering them again. PNG is lossless: a tile read back has the pixels it was
 * rendered with.
 *
 * A file is named `package-WxH-hash` (`-` never occurs in a package name), where the hash covers
 * everything the art depends on; writing a tile deletes the other files of the same package and size,
 * and past [maxBytes] the oldest files go.
 * Blocking: call off the main thread.
 */
internal class TileArtDiskCache(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val lock = Any()

    /**
     * The art stored as [name], decoded at [width] x [height] as a hardware bitmap (or, with
     * [hardware] false, a software one), or null when there is none or the file is not one this cache
     * wrote at that size.
     */
    fun read(
        name: String,
        width: Int,
        height: Int,
        hardware: Boolean = true,
    ): Bitmap? {
        val file = File(dir, name)
        val bytes =
            try {
                if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
                file.readBytes()
            } catch (ignored: IOException) {
                // Unreadable: the tile is rendered again.
                return null
            }
        if (!isPng(bytes, width, height)) {
            file.delete()
            return null
        }
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = if (hardware) ImageDecoder.ALLOCATOR_HARDWARE else ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (ignored: IOException) {
            // Damaged (DecodeException is an IOException): render the tile again, and store it anew.
            file.delete()
            null
        }
    }

    /** Stores [bitmap] (a software bitmap) as [name], replacing older art of the same package and size. */
    fun write(
        name: String,
        bitmap: Bitmap,
    ) {
        synchronized(lock) {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val temp = File(dir, "$name$TEMP_SUFFIX")
            try {
                temp.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) return }
                if (!temp.renameTo(File(dir, name))) return
            } catch (ignored: IOException) {
                // Only a cache: the tile is rendered again next time.
                return
            } finally {
                temp.delete()
            }
            val sibling = name.substringBeforeLast(SEPARATOR) + SEPARATOR
            dir.listFiles()?.forEach { if (it.name.startsWith(sibling) && it.name != name) it.delete() }
            trim(keep = name)
        }
    }

    /** Deletes the stored art of [packageName] (it was updated or removed). */
    fun forgetPackage(packageName: String) {
        synchronized(lock) {
            val prefix = "$packageName$SEPARATOR"
            dir.listFiles()?.forEach { if (it.name.startsWith(prefix)) it.delete() }
        }
    }

    /** Deletes the oldest files, but not [keep] (just written), until the rest fit in [maxBytes]. */
    private fun trim(keep: String) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.filter { it.name != keep }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    companion object {
        /** Bytes kept on disk: a few dozen tiles at 1080p. */
        const val MAX_BYTES = 8L * 1024 * 1024

        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private const val SEPARATOR = '-'
        private const val TEMP_SUFFIX = ".tmp"
        private const val PNG_QUALITY = 100 // Ignored for PNG, which is lossless.
        private const val MAX_PACKAGE_CHARS = 160
        private const val IHDR_END = 24
        private const val WIDTH_OFFSET = 16
        private const val HEIGHT_OFFSET = 20
        private val PNG_HEAD =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A, // signature
                0,
                0,
                0,
                0x0D,
                0x49,
                0x48,
                0x44,
                0x52, // first chunk: IHDR, 13 bytes
            )

        /**
         * The file name for art of [packageName] at [width] x [height] whose content is fully described by
         * [identity], or null when the package name is too long to be part of a file name.
         */
        fun nameFor(
            packageName: String,
            width: Int,
            height: Int,
            identity: String,
        ): String? {
            if (packageName.length > MAX_PACKAGE_CHARS) return null
            return "$packageName$SEPARATOR${width}x$height$SEPARATOR${hash(identity)}"
        }

        /** Whether [bytes] start like a PNG of [width] x [height]: checked before decoding anything. */
        fun isPng(
            bytes: ByteArray,
            width: Int,
            height: Int,
        ): Boolean {
            if (bytes.size < IHDR_END) return false
            for (i in PNG_HEAD.indices) if (bytes[i] != PNG_HEAD[i]) return false
            val buffer = ByteBuffer.wrap(bytes)
            return buffer.getInt(WIDTH_OFFSET) == width && buffer.getInt(HEIGHT_OFFSET) == height
        }

        private fun hash(identity: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            return buildString(HASH_BYTES * 2) {
                for (i in 0 until HASH_BYTES) {
                    val byte = digest[i].toInt()
                    append(HEX[byte shr 4 and 0xF])
                    append(HEX[byte and 0xF])
                }
            }
        }

        private const val HASH_BYTES = 16
        private const val HEX = "0123456789abcdef"
    }
}
