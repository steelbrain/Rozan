package ai.aneesiqbal.rozan.launcher.apps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * App labels from earlier starts, so that a cold start does not open every
 * app's APK to read its label (the package manager loads the app's resources
 * for that). One small file in the cache directory; an entry is used only
 * while its package has the version code and last update time it was stored
 * with, and the device has the same locale. Blocking: call off the main thread.
 */
class AppLabelCache(
    private val file: File,
) {
    /** The label of one activity, `package/class`, and the package version it was read from. */
    class Entry(
        val key: String,
        val versionCode: Long,
        val updatedAt: Long,
        val label: String,
    )

    /** Entries stored for [locale] (a BCP 47 tag) by key; empty when there are none or the file is unusable. */
    fun read(locale: String): Map<String, Entry> {
        val bytes =
            try {
                if (!file.isFile || file.length() > MAX_FILE_BYTES) return emptyMap()
                file.readBytes()
            } catch (ignored: IOException) {
                // Unreadable: the labels are read from the apps again.
                return emptyMap()
            }
        return parse(bytes, locale) ?: emptyMap()
    }

    /** Replaces the stored labels with [entries], for [locale]. A failed write leaves no file behind. */
    fun write(
        locale: String,
        entries: List<Entry>,
    ) {
        val bytes = encode(locale, entries) ?: return
        val dir = file.parentFile ?: return
        val temp = File(dir, file.name + ".tmp")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
        } catch (ignored: IOException) {
            // Only a cache: the next start reads the labels from the apps.
            temp.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x525A4C31 // "RZL1"
        private const val MAX_FILE_BYTES = 256 * 1024L
        private const val MAX_ENTRIES = 2048
        private const val MAX_KEY_CHARS = 512
        private const val MAX_LABEL_CHARS = 512

        /**
         * The entries in [bytes] by key, or null when they were stored for another locale or the bytes are
         * not a complete, well-formed file within the bounds (entries, key and label lengths).
         */
        fun parse(
            bytes: ByteArray,
            locale: String,
        ): Map<String, Entry>? {
            if (bytes.size > MAX_FILE_BYTES) return null
            return try {
                readEntries(DataInputStream(ByteArrayInputStream(bytes)), locale)
            } catch (ignored: IOException) {
                // Truncated or malformed (EOFException, UTFDataFormatException): not a cache file.
                null
            }
        }

        private fun readEntries(
            input: DataInputStream,
            locale: String,
        ): Map<String, Entry>? {
            val count = if (input.readInt() == MAGIC && input.readUTF() == locale) input.readInt() else -1
            if (count !in 0..MAX_ENTRIES) return null
            val entries = HashMap<String, Entry>(count * 2)
            repeat(count) {
                // Named arguments are evaluated in the order written: the file's field order.
                val entry =
                    Entry(
                        key = input.readUTF(),
                        versionCode = input.readLong(),
                        updatedAt = input.readLong(),
                        label = input.readUTF(),
                    )
                if (entry.key.length > MAX_KEY_CHARS || entry.label.length > MAX_LABEL_CHARS) return null
                entries[entry.key] = entry
            }
            return entries.takeIf { input.available() == 0 }
        }

        /** The file content for [entries], or null when they exceed the bounds [parse] accepts. */
        fun encode(
            locale: String,
            entries: List<Entry>,
        ): ByteArray? {
            if (entries.size > MAX_ENTRIES) return null
            if (entries.any { it.key.length > MAX_KEY_CHARS || it.label.length > MAX_LABEL_CHARS }) return null
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(locale)
                out.writeInt(entries.size)
                for (entry in entries) {
                    out.writeUTF(entry.key)
                    out.writeLong(entry.versionCode)
                    out.writeLong(entry.updatedAt)
                    out.writeUTF(entry.label)
                }
            }
            return bytes.toByteArray().takeIf { it.size <= MAX_FILE_BYTES }
        }
    }
}
