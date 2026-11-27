package ai.aneesiqbal.rozan.launcher.redirect

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// These run in the test APK's own process, which has no Kotlin standard library (the test APK
// leaves out what the app APK already contains). So they use nothing from it: no non-null
// parameters (each adds a null check from the stdlib), no stdlib functions, only platform calls.
// Their labels are test fixtures, never shown to users, so they are not string resources.

/** Stands in for the stock launcher's home screen (the test's `stockHome`). */
@SuppressLint("SetTextI18n")
class FakeStockHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this)
        label.text = "Fake stock home"
        setContentView(label)
    }
}

/** Another activity of the same package: like Fire OS Settings inside the Amazon launcher. */
@SuppressLint("SetTextI18n")
class FakeStockSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this)
        label.text = "Fake stock settings"
        setContentView(label)
    }
}

/** Any other app. */
@SuppressLint("SetTextI18n")
class FakeAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this)
        label.text = "Fake app"
        setContentView(label)
    }
}
