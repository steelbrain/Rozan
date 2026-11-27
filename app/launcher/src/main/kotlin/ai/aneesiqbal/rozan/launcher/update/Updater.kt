package ai.aneesiqbal.rozan.launcher.update

import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus
import ai.aneesiqbal.rozan.launcher.DebugOverrides
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Writing an update file failed. */
internal class StorageException(
    cause: IOException,
) : Exception(cause.message, cause)

/**
 * The updater's steps. Every method runs on [UpdateWorker]'s thread in the
 * UI process.
 *
 * A check reads the latest release (at most [MAX_METADATA_BYTES]); when it
 * is newer than the installed version it downloads `SHA256SUMS` and the
 * launcher APK, and keeps the APK only if its size and SHA-256 match and
 * [PackageVerifier] accepts it. The install then follows [InstallTiming].
 * Any failure deletes the download and is recorded in [UpdateState]; the
 * next daily check starts over.
 */
internal class Updater(
    context: Context,
) {
    private val app: Context = context.applicationContext
    private val state = UpdateState(app)

    /** Runs one check with [http], then tries to install what is pending. */
    fun check(http: UpdateHttp) {
        if (!state.enabled) return
        try {
            checkAndDownload(http)
        } catch (ignored: UpdateCancelledException) {
            // Stopped on purpose (the switch, or the system): nothing to record.
            UpdateFiles.download(app).delete()
            Log.i(TAG, "check stopped")
            return
        } catch (e: HttpStatusException) {
            failed(UpdateState.Failure.SERVER, e, e.status)
        } catch (e: UpdateFormatException) {
            failed(UpdateState.Failure.RELEASE, e)
        } catch (e: ChecksumException) {
            failed(UpdateState.Failure.CHECKSUM, e)
        } catch (e: PackageMismatchException) {
            failed(UpdateState.Failure.PACKAGE, e)
        } catch (e: StorageException) {
            failed(UpdateState.Failure.STORAGE, e)
        } catch (e: IOException) {
            if (http.isCancelled) {
                UpdateFiles.download(app).delete()
                return
            }
            failed(UpdateState.Failure.NETWORK, e)
        }
        // After a failed check, an update downloaded earlier can still go in.
        install(userInitiated = false)
    }

    private fun checkAndDownload(http: UpdateHttp) {
        val body = http.fetch(metadataUrl(), MAX_METADATA_BYTES, ACCEPT_GITHUB_JSON)
        val release = LatestRelease.parse(String(body, Charsets.UTF_8))
        val installed = PackageVerifier.installedVersionCode(app)
        val version = release.version
        if (version.code <= installed) {
            discardPending()
            state.record(now(), UpdateState.Result.UP_TO_DATE, version.name)
            return
        }
        val pending = state.pending
        if (pending?.versionCode == version.code && UpdateFiles.apk(app).isFile) return
        discardPending()
        download(http, release)
        state.record(now(), UpdateState.Result.DOWNLOADED, version.name)
    }

    private fun download(
        http: UpdateHttp,
        release: LatestRelease,
    ) {
        val sums = http.fetch(release.checksums.url, Sha256Sums.MAX_BYTES)
        val expected =
            Sha256Sums.find(String(sums, Charsets.UTF_8), release.apk.name)
                ?: formatError("${release.apk.name} is not in SHA256SUMS")
        val temp = UpdateFiles.download(app)
        val digest = writeApk(http, release.apk, temp)
        if (!MessageDigest.isEqual(digest, expected)) throw ChecksumException("SHA-256 differs")
        PackageVerifier.verify(app, temp)
        // The switch may have been turned off meanwhile; its cleanup runs after this task.
        if (!state.enabled) throw UpdateCancelledException()
        if (!temp.renameTo(UpdateFiles.apk(app))) storageError("cannot rename $temp")
        state.setPending(
            UpdateState.Pending(release.version.name, release.version.code, hexOf(expected), stage = null),
        )
    }

    /** Streams [apk] into [temp] and returns its SHA-256. */
    private fun writeApk(
        http: UpdateHttp,
        apk: ReleaseAsset,
        temp: File,
    ): ByteArray {
        val dir = UpdateFiles.dir(app)
        if (!dir.isDirectory && !dir.mkdirs()) storageError("cannot create $dir")
        val digest = MessageDigest.getInstance(SHA_256)
        val out = storage { FileOutputStream(temp) }
        try {
            http.download(apk.url, apk.size) { buffer, length ->
                digest.update(buffer, 0, length)
                storage { out.write(buffer, 0, length) }
            }
            storage { out.fd.sync() }
        } finally {
            storage { out.close() }
        }
        return digest.digest()
    }

    /**
     * Installs the pending update if [InstallTiming] allows it now; otherwise records what it waits
     * for. [userInitiated]: the user selected "Install update".
     */
    fun install(userInitiated: Boolean) {
        val pending = state.pending
        if (!state.enabled || pending == null) return
        if (userInitiated) {
            // "Install update" is offered only while the update waits for the user.
            if (pending.stage != UpdateState.Stage.ASK_USER) return
            // A confirmation the user left without answering: this press starts over.
            UpdateInstaller.abandonAll(app)
        } else if (UpdateInstaller.inProgress(app)) {
            return
        }
        val apk = UpdateFiles.apk(app)
        if (!stillValid(apk, pending)) return
        // Before the blockers are read: an event that ends a wait after this sees the marker (UpdateFiles).
        val waiting = UpdateFiles.waiting(app)
        try {
            waiting.createNewFile()
        } catch (e: IOException) {
            Log.w(TAG, "cannot create $waiting", e)
        }
        val decision =
            InstallTiming.decide(
                sdkInt = Build.VERSION.SDK_INT,
                userInitiated = userInitiated,
                mayInstallApps = InstallPermission.isGranted(app),
                rozanOnScreen = UpdateTriggers.rozanOnScreen(),
                airPlayInUse = ReceiverStatus(app).current.inUse,
            )
        Log.i(TAG, "update ${pending.versionName}: $decision")
        when (decision) {
            InstallTiming.Decision.WAIT_FOR_SCREEN -> {
                state.setStage(UpdateState.Stage.WAIT_FOR_SCREEN)
            }

            InstallTiming.Decision.WAIT_FOR_AIRPLAY -> {
                state.setStage(UpdateState.Stage.WAIT_FOR_AIRPLAY)
            }

            InstallTiming.Decision.ASK_USER -> {
                waiting.delete()
                state.setStage(UpdateState.Stage.ASK_USER)
            }

            InstallTiming.Decision.INSTALL -> {
                waiting.delete()
                state.setStage(UpdateState.Stage.INSTALLING)
                try {
                    UpdateInstaller.commit(app, apk, userInitiated)
                } catch (e: IOException) {
                    discardAfter(UpdateState.Failure.INSTALL, e, pending.versionName)
                } catch (e: SecurityException) {
                    discardAfter(UpdateState.Failure.INSTALL, e, pending.versionName)
                }
            }
        }
    }

    /** The package installer's result for one of our sessions ([UpdateInstallReceiver]). */
    fun onInstallStatus(
        status: Int,
        sessionId: Int,
        userInitiated: Boolean,
        confirm: Intent?,
        message: String?,
    ) {
        Log.i(TAG, "session $sessionId: status $status ($message)")
        // Turning the switch off abandons the sessions; their results change nothing. Without a pending
        // update (onPackageReplaced already forgot the installed one) there is nothing to act on either.
        val pending = state.pending
        if (!state.enabled || pending == null) {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) UpdateInstaller.abandon(app, sessionId)
            return
        }
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                // Rarely seen: installing an update of this app ends this process first.
                discardPending()
                state.record(now(), UpdateState.Result.UPDATED, pending.versionName)
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (userInitiated && confirm != null) {
                    // "Install update" stays offered in case the user leaves the confirmation unanswered.
                    state.setStage(UpdateState.Stage.ASK_USER)
                    confirmInstall(confirm, pending.versionName)
                } else {
                    // Never from the background: the settings offer "Install update" instead. The stage is
                    // set first, so the abort that abandoning reports is recognised as this one.
                    state.setStage(UpdateState.Stage.ASK_USER)
                    UpdateInstaller.abandon(app, sessionId)
                }
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user declined the confirmation, or this updater abandoned the session above.
                if (userInitiated || pending.stage == UpdateState.Stage.ASK_USER) {
                    state.setStage(UpdateState.Stage.ASK_USER)
                } else {
                    discardAfter(UpdateState.Failure.INSTALL, null, pending.versionName)
                }
            }

            else -> {
                discardAfter(UpdateState.Failure.INSTALL, null, pending.versionName)
            }
        }
    }

    private fun confirmInstall(
        confirm: Intent,
        version: String?,
    ) {
        try {
            // The user selected "Install update" on Rozan's screen, which is still in front.
            app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            discardAfter(UpdateState.Failure.INSTALL, e, version)
        }
    }

    /** Rozan was replaced by a new version: forgets the update that is now installed. */
    fun onPackageReplaced() {
        val installed = PackageVerifier.installedVersionCode(app)
        val pending = state.pending
        if (pending == null) {
            UpdateFiles.clear(app)
        } else if (pending.versionCode <= installed) {
            discardPending()
            if (pending.versionCode == installed) state.record(now(), UpdateState.Result.UPDATED, pending.versionName)
        }
        if (state.enabled) UpdateJobs.ensureDaily(app)
    }

    /** The switch was turned off: no jobs, no sessions, no files, no history. */
    fun disable() {
        // Turned on again before this ran: keep what the new jobs use.
        if (state.enabled) return
        UpdateJobs.cancelAll(app)
        UpdateInstaller.abandonAll(app)
        UpdateFiles.clear(app)
        state.clearHistory()
    }

    /** Checks the pending file again right before installing; discards it (and says false) if it fails. */
    private fun stillValid(
        apk: File,
        pending: UpdateState.Pending,
    ): Boolean {
        val failure =
            try {
                reverify(apk, pending)
                return true
            } catch (e: ChecksumException) {
                UpdateState.Failure.CHECKSUM to e
            } catch (e: PackageMismatchException) {
                UpdateState.Failure.PACKAGE to e
            } catch (e: IOException) {
                UpdateState.Failure.STORAGE to e
            }
        discardAfter(failure.first, failure.second, pending.versionName)
        return false
    }

    private fun reverify(
        apk: File,
        pending: UpdateState.Pending,
    ) {
        if (!apk.isFile) throw ChecksumException("the downloaded file is gone")
        val digest = MessageDigest.getInstance(SHA_256)
        FileInputStream(apk).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (hexOf(digest.digest()) != pending.sha256) throw ChecksumException("the downloaded file changed")
        PackageVerifier.verify(app, apk)
    }

    private fun failed(
        failure: UpdateState.Failure,
        cause: Exception,
        status: Int = 0,
    ) {
        Log.w(TAG, "update check failed: $failure", cause)
        UpdateFiles.download(app).delete()
        state.record(now(), UpdateState.Result.FAILED, null, failure, status)
    }

    private fun discardAfter(
        failure: UpdateState.Failure,
        cause: Exception?,
        version: String?,
    ) {
        Log.w(TAG, "update $version discarded: $failure", cause)
        discardPending()
        state.record(now(), UpdateState.Result.FAILED, version, failure)
    }

    private fun discardPending() {
        UpdateFiles.clear(app)
        state.clearPending()
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "RozanUpdate"
        private const val SHA_256 = "SHA-256"
        private const val BUFFER_BYTES = 64 * 1024

        /** Largest release metadata response accepted. */
        const val MAX_METADATA_BYTES = 1 shl 20

        /** GitHub's latest non-draft, non-pre-release release of Rozan. */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/steelbrain/Rozan/releases/latest"

        private const val ACCEPT_GITHUB_JSON = "application/vnd.github+json"

        /** The metadata URL: GitHub's, or a debug build's local test server (`DebugOverrides`). */
        fun metadataUrl(): String = DebugOverrides.updateMetadataUrl ?: LATEST_RELEASE_URL

        /** A client for one job. Plain `http://127.0.0.1` only while a debug override is set. */
        fun newHttp(context: Context): UpdateHttp {
            val version = PackageVerifier.installedVersionName(context)
            return UpdateHttp(UrlPolicy(allowLoopbackHttp = DebugOverrides.updateMetadataUrl != null), "Rozan/$version")
        }

        private fun storageError(message: String): Nothing = throw StorageException(IOException(message))

        private inline fun <T> storage(block: () -> T): T =
            try {
                block()
            } catch (e: IOException) {
                throw StorageException(e)
            }
    }
}
