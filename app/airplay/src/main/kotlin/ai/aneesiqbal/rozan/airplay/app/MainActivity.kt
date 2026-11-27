package ai.aneesiqbal.rozan.airplay.app

import ai.aneesiqbal.rozan.airplay.DeviceNames
import ai.aneesiqbal.rozan.airplay.android.AirPlaySettings
import ai.aneesiqbal.rozan.airplay.android.OverlayPermission
import ai.aneesiqbal.rozan.airplay.android.ReceiverService
import ai.aneesiqbal.rozan.airplay.android.ReceiverStatus
import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

/**
 * The standalone receiver's only screen: what the receiver is called, whether
 * Apple devices can see it, whether it may draw over other apps, and the
 * three settings. Starts the receiver service.
 */
class MainActivity :
    Activity(),
    AirPlaySettings.Listener,
    ReceiverStatus.Listener {
    private lateinit var settings: AirPlaySettings
    private lateinit var status: ReceiverStatus
    private lateinit var receiverName: TextView
    private lateinit var statusLine: TextView
    private lateinit var enabledValue: TextView
    private lateinit var wakeValue: TextView
    private lateinit var nameField: EditText
    private lateinit var overlayState: TextView
    private lateinit var overlayCommand: TextView
    private var overlayWatch: AutoCloseable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AirPlaySettings(this)
        status = ReceiverStatus(this)
        receiverName = findViewById(R.id.receiver_name)
        statusLine = findViewById(R.id.status)
        enabledValue = findViewById(R.id.value_enabled)
        wakeValue = findViewById(R.id.value_wake)
        nameField = findViewById(R.id.edit_name)
        overlayState = findViewById(R.id.overlay_state)
        overlayCommand = findViewById(R.id.overlay_command)

        findViewById<View>(R.id.row_enabled).setOnClickListener {
            settings.enabled = !settings.enabled
            if (settings.enabled) ReceiverService.start(this)
        }
        findViewById<View>(R.id.row_wake).setOnClickListener { settings.wakeOnConnect = !settings.wakeOnConnect }
        nameField.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (done) commitName()
            done
        }
        nameField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitName() }
        findViewById<View>(R.id.row_enabled).requestFocus()
    }

    override fun onStart() {
        super.onStart()
        // Every time the screen comes up: also retries a receiver that could not start.
        ReceiverService.start(this)
        settings.addListener(this)
        status.addListener(this)
        overlayWatch = OverlayPermission.watch(this, ::render)
        render()
    }

    override fun onStop() {
        settings.removeListener(this)
        status.removeListener(this)
        overlayWatch?.close()
        overlayWatch = null
        super.onStop()
    }

    override fun onAirPlaySettingsChanged() = render()

    override fun onReceiverStatusChanged(status: ReceiverStatus.Snapshot) = render()

    private fun commitName() {
        val entered = DeviceNames.sanitize(nameField.text.toString())
        if (entered != null && entered != settings.deviceName) settings.deviceName = entered
        nameField.setText(settings.deviceName)
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(nameField.windowToken, 0)
    }

    private fun render() {
        val name = settings.deviceName
        val enabled = settings.enabled
        receiverName.text = name
        enabledValue.setText(if (enabled) R.string.value_on else R.string.value_off)
        wakeValue.setText(if (settings.wakeOnConnect) R.string.value_on else R.string.value_off)
        if (!nameField.hasFocus()) nameField.setText(name)
        renderStatus(enabled)
        renderOverlay()
    }

    private fun renderStatus(enabled: Boolean) {
        val current = status.current
        val running = enabled && current.running
        val advertising = if (running) current.advertising else ReceiverStatus.Advertising.OFF
        statusLine.text =
            when (if (running) current.failure else null) {
                ReceiverStatus.Failure.PORT_IN_USE -> getString(R.string.status_port_in_use, current.port)
                ReceiverStatus.Failure.START_FAILED -> getString(R.string.status_start_failed)
                null -> getString(advertisingText(advertising))
            }
        val visible = advertising == ReceiverStatus.Advertising.ADVERTISING
        statusLine.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot, 0, 0, 0)
        statusLine.compoundDrawableTintList =
            ColorStateList.valueOf(getColor(if (visible) R.color.accent else R.color.text_secondary))
    }

    private fun advertisingText(advertising: ReceiverStatus.Advertising): Int =
        when (advertising) {
            ReceiverStatus.Advertising.OFF -> R.string.status_off
            ReceiverStatus.Advertising.NOT_PUBLISHED -> R.string.status_starting
            ReceiverStatus.Advertising.WAITING_FOR_NETWORK -> R.string.status_waiting
            ReceiverStatus.Advertising.REGISTERING -> R.string.status_registering
            ReceiverStatus.Advertising.ADVERTISING -> R.string.status_advertising
            ReceiverStatus.Advertising.FAILED -> R.string.status_failed
        }

    private fun renderOverlay() {
        val granted = OverlayPermission.isGranted(this)
        overlayState.setText(if (granted) R.string.overlay_granted else R.string.overlay_missing)
        overlayCommand.visibility = if (granted) View.GONE else View.VISIBLE
        overlayCommand.text = OverlayPermission.adbCommand(this)
    }
}
