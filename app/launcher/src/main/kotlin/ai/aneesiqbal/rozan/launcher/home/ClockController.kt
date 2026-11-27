package ai.aneesiqbal.rozan.launcher.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.widget.TextView
import java.util.Locale

/**
 * Keeps the home clock current. It updates on the system's minute tick
 * (`ACTION_TIME_TICK`) and on time, zone and 12/24-hour changes, and only
 * while the home screen is visible; there is no timer of its own.
 */
class ClockController(
    private val context: Context,
    private val view: TextView,
) : BroadcastReceiver() {
    private var registered = false

    fun start() {
        if (!registered) {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
            // Protected system broadcasts only; no export flag is involved.
            context.registerReceiver(this, filter)
            registered = true
        }
        update()
    }

    fun stop() {
        if (registered) {
            context.unregisterReceiver(this)
            registered = false
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        update()
    }

    private fun update() {
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        view.text = DateFormat.format(pattern, System.currentTimeMillis())
    }
}
