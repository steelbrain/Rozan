package ai.aneesiqbal.rozan.launcher.settings

import ai.aneesiqbal.rozan.airplay.android.AppLanguage
import ai.aneesiqbal.rozan.airplay.android.AppLanguage.Language
import ai.aneesiqbal.rozan.launcher.R
import ai.aneesiqbal.rozan.ui.SettingsRowView
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import java.util.Locale

/**
 * The language row of the launcher settings, a choice row like the others: OK steps forward through the
 * system language and the app's languages, left and right step either way. Each language is named in
 * itself. A choice takes effect at once: every screen of the app is recreated in it (the system does it
 * from API 33, [AppLanguage] before), and the window gives focus back to the row by its id.
 */
internal object LanguageRow {
    /** The system language first (null), then the app's languages in the order they are listed. */
    private val CHOICES: List<Language?> = listOf(null) + Language.entries

    /** Makes [row] the language row. */
    fun bind(row: SettingsRowView) {
        val context = row.context
        row.id = R.id.settings_language
        row.value = name(context, AppLanguage.chosen(context))

        fun step(delta: Int) {
            val index = CHOICES.indexOf(AppLanguage.chosen(context)).coerceAtLeast(0)
            val next = CHOICES[(index + delta + CHOICES.size) % CHOICES.size]
            row.value = name(context, next)
            AppLanguage.choose(context, next)
        }
        row.setOnClickListener { step(1) }
        row.adjustListener = SettingsRowView.AdjustListener { step(it) }
    }

    /**
     * What the row shows for [language]: its own name, or "System default (name)" for null. The name is
     * marked as its language, so that Chinese and Japanese glyphs are drawn in their own forms.
     */
    private fun name(
        context: Context,
        language: Language?,
    ): CharSequence {
        val named = language ?: AppLanguage.systemLanguage(context)
        val text =
            when (language) {
                null -> context.getString(R.string.settings_language_system, named.nativeName)
                else -> named.nativeName
            }
        val start = text.indexOf(named.nativeName)
        if (start < 0) return text
        return SpannableString(text).apply {
            val end = start + named.nativeName.length
            setSpan(LocaleSpan(Locale.forLanguageTag(named.tag)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
