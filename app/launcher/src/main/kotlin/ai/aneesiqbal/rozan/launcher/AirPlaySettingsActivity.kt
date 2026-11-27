package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.settings.AirPlaySection
import ai.aneesiqbal.rozan.launcher.settings.SettingsList
import android.os.Bundle
import android.widget.TextView

/**
 * What the home screen's AirPlay tile opens: the AirPlay receiver's settings and nothing else
 * ([AirPlaySection]), under the title "AirPlay". The full launcher settings show the same rows as
 * one of their sections.
 */
class AirPlaySettingsActivity : LocalizedActivity() {
    private lateinit var section: AirPlaySection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.settings_heading).setText(R.string.settings_section_airplay)
        section = AirPlaySection(this, SettingsList(findViewById(R.id.settings_list)), withHeader = false)
        section.firstRow.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        section.start()
    }

    override fun onStop() {
        section.stop()
        super.onStop()
    }
}
