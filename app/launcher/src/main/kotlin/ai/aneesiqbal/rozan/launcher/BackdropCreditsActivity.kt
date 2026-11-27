package ai.aneesiqbal.rozan.launcher

import ai.aneesiqbal.rozan.launcher.backdrop.BackdropSource
import ai.aneesiqbal.rozan.launcher.settings.SettingsList
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * The backdrop photos' credits, opened from "Backdrop photos" in the About section of the launcher
 * settings: one row per photo with its title, author, license and source, in alphabetical order of
 * title. The rows take focus so the D-pad scrolls through them.
 */
class BackdropCreditsActivity : LocalizedActivity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<TextView>(R.id.settings_heading).setText(R.string.settings_section_credits)
        val items = SettingsList(findViewById(R.id.settings_list))
        val loading = items.note(R.string.settings_credits_loading)
        scope.launch {
            val images = withContext(Background.work) { BackdropSource(this@BackdropCreditsActivity).loadImages() }
            items.remove(loading)
            if (images.isEmpty()) {
                items.note(R.string.settings_credits_none)
                return@launch
            }
            val collator = Collator.getInstance(resources.configuration.locales[0])
            images.sortedWith(compareBy(collator) { it.title.ifEmpty { it.file } }).forEachIndexed { index, image ->
                val row = items.row(image.title.ifEmpty { image.file })
                val line = listOf(image.author, image.license).filter { it.isNotEmpty() }.joinToString(" · ")
                row.summary = if (image.sourceUrl.isEmpty()) line else "$line\n${image.sourceUrl}"
                if (index == 0) row.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
