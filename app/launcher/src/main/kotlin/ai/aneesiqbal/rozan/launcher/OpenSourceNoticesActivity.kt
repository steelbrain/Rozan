package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.notices.OpenSourceNotices
import ai.aneesiqbal.rozan.launcher.settings.SettingsList
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import java.text.Collator

/**
 * The third-party libraries in the app, one row per library in alphabetical order, each with its
 * copyright holder and license. Selecting a row opens its license text ([LicenseActivity]).
 */
class OpenSourceNoticesActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.settings_heading).setText(R.string.notices_title)
        val items = SettingsList(findViewById(R.id.settings_list))
        val collator = Collator.getInstance(resources.configuration.locales[0])
        OpenSourceNotices.LIBRARIES.sortedWith(compareBy(collator) { it.name }).forEachIndexed { index, library ->
            val row = items.row(library.name)
            row.summary = "${library.holder} · ${library.license}"
            row.setOnClickListener {
                startActivity(
                    Intent(this, LicenseActivity::class.java).putExtra(LicenseActivity.EXTRA_LIBRARY, library.name),
                )
            }
            if (index == 0) row.requestFocus()
        }
    }
}
