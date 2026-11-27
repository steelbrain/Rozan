package ai.aneesiqbal.rozan.airplay.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the receiver after boot and after the app was updated, when it is enabled. */
internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ReceiverService.start(context)
        }
    }
}
