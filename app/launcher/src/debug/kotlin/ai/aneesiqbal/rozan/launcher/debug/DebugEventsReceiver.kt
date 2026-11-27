package ai.aneesiqbal.rozan.launcher.debug

import ai.aneesiqbal.rozan.launcher.DebugOverrides
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug and benchmark builds only: pins a backdrop photo, for measurements and
 * screenshots (docs/PERFORMANCE.md). Needs the sender to hold `DUMP`, which
 * adb's shell has and apps do not. The value is stored and takes effect at the
 * next start of the launcher's process:
 *
 * ```
 * P=ai.aneesiqbal.rozan.launcher
 * adb shell am broadcast -f 0x20 -p $P -a $P.debug.BACKDROP --es file yosemite-valley-fog.jpg
 * adb shell am force-stop $P
 * ```
 *
 * Without the extra, the broadcast clears the override (an empty `""` is
 * lost on the way through `adb shell`).
 *
 * It also points the updater at a local test server instead of GitHub; this
 * one applies at once and stays until it is cleared:
 *
 * ```
 * adb shell am broadcast -f 0x20 -p $P -a $P.debug.UPDATE_URL --es url http://127.0.0.1:8765/latest.json
 * ```
 *
 * The broadcast's result data (printed by `am broadcast`) is the overrides now stored.
 */
internal class DebugEventsReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_BACKDROP -> {
                DebugOverridesProvider.store(context, backdrop = intent.getStringExtra(EXTRA_FILE).orEmpty())
            }

            ACTION_UPDATE_URL -> {
                DebugOverridesProvider.storeUpdateUrl(context, intent.getStringExtra(EXTRA_URL).orEmpty())
            }
        }
        resultData = "backdrop=${DebugOverrides.backdropFile} update_url=${DebugOverrides.updateMetadataUrl}"
    }

    private companion object {
        const val ACTION_BACKDROP = "ai.aneesiqbal.rozan.launcher.debug.BACKDROP"
        const val ACTION_UPDATE_URL = "ai.aneesiqbal.rozan.launcher.debug.UPDATE_URL"
        const val EXTRA_FILE = "file"
        const val EXTRA_URL = "url"
    }
}
