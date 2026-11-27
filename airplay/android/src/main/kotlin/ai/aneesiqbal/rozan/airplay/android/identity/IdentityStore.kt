package ai.aneesiqbal.rozan.airplay.android.identity

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * Loads this install's [ReceiverIdentity] from [file], creating it on first
 * use. The file lives in `noBackupFilesDir` (never backed up or transferred)
 * and is readable and writable by this app only.
 *
 * A missing or corrupt file yields a fresh identity; that changes the
 * receiver's id on the network, which clients handle like a new receiver.
 */
internal class IdentityStore(
    private val file: File,
    private val random: SecureRandom = SecureRandom(),
) {
    private var cached: ReceiverIdentity? = null

    /** Returns the stored identity, generating and persisting it the first time. */
    @Synchronized
    fun loadOrCreate(): ReceiverIdentity {
        cached?.let { return it }
        val identity = read() ?: ReceiverIdentity.generate(random).also(::write)
        cached = identity
        return identity
    }

    private fun read(): ReceiverIdentity? {
        if (!file.isFile || file.length() != ReceiverIdentity.ENCODED_SIZE.toLong()) return null
        return try {
            ReceiverIdentity.decode(file.readBytes())
        } catch (ignored: IOException) {
            null
        }
    }

    private fun write(identity: ReceiverIdentity) {
        val dir = requireNotNull(file.parentFile) { "identity file needs a parent directory" }
        if (!dir.isDirectory) {
            Files.createDirectories(dir.toPath(), PosixFilePermissions.asFileAttribute(DIR_PERMISSIONS))
        }
        val temp = File(dir, file.name + ".tmp").toPath()
        Files.deleteIfExists(temp)
        // Created owner-only from the start, so the seed is never readable by others even briefly.
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        FileOutputStream(temp.toFile()).use { out ->
            out.write(identity.encode())
            out.fd.sync()
        }
        Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        val FILE_PERMISSIONS: Set<PosixFilePermission> =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val DIR_PERMISSIONS: Set<PosixFilePermission> =
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    }
}
