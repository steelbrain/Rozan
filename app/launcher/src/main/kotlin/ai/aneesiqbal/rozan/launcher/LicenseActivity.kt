package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.notices.OpenSourceNotices
import android.os.Bundle
import android.widget.TextView

/**
 * One library's notice, opened from [OpenSourceNoticesActivity]: the library's name as the title,
 * then what it covers, its copyright holder and license, and the license's full text. The text
 * scrolls with the D-pad.
 */
class LicenseActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = OpenSourceNotices.named(intent.getStringExtra(EXTRA_LIBRARY))
        if (library == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_notices)
        findViewById<TextView>(R.id.notices_heading).text = library.name
        val about = listOfNotNull(library.parts, "${library.holder} · ${library.license}").joinToString("\n")
        findViewById<TextView>(R.id.notices_about).text = about
        findViewById<TextView>(R.id.notices_text).text =
            resources.openRawResource(library.text).bufferedReader().use { it.readText() }
    }

    companion object {
        /** Intent extra: the [ai.aneesiqbal.rozan.launcher.notices.Library.name] to show. */
        const val EXTRA_LIBRARY = "ai.aneesiqbal.rozan.launcher.extra.LIBRARY"
    }
}
