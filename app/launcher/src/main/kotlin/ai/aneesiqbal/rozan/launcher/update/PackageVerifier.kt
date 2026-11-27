package ai.aneesiqbal.rozan.launcher.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

/** The downloaded APK is not an update Rozan may install. */
internal class PackageMismatchException(
    message: String,
) : Exception(message)

/**
 * Checks a downloaded APK against the installed Rozan with the system's own
 * APK parser: the same package name, a higher `versionCode`, and the same
 * signing certificates. Android's installer checks the signature again; this
 * check keeps a file that would fail it (or replace Rozan with another app's
 * update) from ever reaching the installer.
 */
internal object PackageVerifier {
    /** The installed app's `versionCode`. */
    fun installedVersionCode(context: Context): Long = installed(context, 0).longVersionCode

    /** The installed app's `versionName`. */
    fun installedVersionName(context: Context): String = installed(context, 0).versionName.orEmpty()

    /** Throws [PackageMismatchException] unless [apk] is a newer build of this app with the same signers. */
    fun verify(
        context: Context,
        apk: File,
    ) {
        val archive = archiveInfo(context, apk) ?: mismatch("the system cannot parse the APK")
        if (archive.packageName != context.packageName) mismatch("package ${archive.packageName}")
        val installed = installed(context, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) {
            mismatch("versionCode ${archive.longVersionCode} is not newer")
        }
        if (!sameSigners(signers(archive), signers(installed))) mismatch("signed with another key")
    }

    private fun mismatch(message: String): Nothing = throw PackageMismatchException(message)

    /** Whether both lists hold the same certificates (order does not matter); empty lists never match. */
    fun sameSigners(
        a: List<ByteArray>,
        b: List<ByteArray>,
    ): Boolean {
        if (a.isEmpty() || a.size != b.size) return false
        val left = a.map(::hexOf).toSet()
        val right = b.map(::hexOf).toSet()
        return left.size == a.size && left == right
    }

    /** The certificates that sign the APK's contents now (not earlier ones in a rotation history). */
    private fun signers(info: PackageInfo): List<ByteArray> =
        info.signingInfo
            ?.apkContentsSigners
            .orEmpty()
            .map(Signature::toByteArray)

    private fun installed(
        context: Context,
        flags: Int,
    ): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
    }

    private fun archiveInfo(
        context: Context,
        apk: File,
    ): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apk.path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The int-flags overload is the only one before API 33; deprecated from 33 on.
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            // Before API 30 the archive parser collects certificates only when GET_SIGNATURES is set; then
            // signingInfo holds them too. Only signingInfo is read, never the deprecated signatures.
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(
                apk.path,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES,
            )
        }
    }
}
