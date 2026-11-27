package ai.aneesiqbal.rozan.launcher

import android.os.Bundle
import android.widget.TextView

/** Lists the third-party libraries in the app and their licenses. The text scrolls with the D-pad. */
class OpenSourceNoticesActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notices)
        val text = resources.openRawResource(R.raw.open_source_notices).bufferedReader().use { it.readText() }
        findViewById<TextView>(R.id.notices_text).text = text
    }
}
