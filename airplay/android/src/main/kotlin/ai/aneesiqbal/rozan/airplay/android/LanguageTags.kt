package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.AppLanguage.Language
import java.util.Locale

/**
 * The part of [AppLanguage] that needs no Android: what a stored choice means, and which of the app's
 * languages a locale is written in.
 */
internal object LanguageTags {
    /** The value stored for following the system language. */
    const val SYSTEM = ""

    /** Regions whose Chinese is written in Traditional characters when no script is given. */
    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    /**
     * The language a stored value names, or null (follow the system) for no value, an empty one, or one
     * that names none of the app's languages exactly (written by another version, or edited by hand).
     */
    fun fromStored(value: String?): Language? = Language.entries.firstOrNull { it.tag == value }

    /** The value to store for [language]; null stores [SYSTEM]. */
    fun toStored(language: Language?): String = language?.tag ?: SYSTEM

    /**
     * The app language that [tag] (BCP 47) is written in, or null when the app is not translated into it.
     * Regions do not matter (fr-CA is French, pt-PT is the Brazilian translation), scripts do: Chinese
     * matches only in Simplified characters, which is the script of zh, zh-CN and zh-SG.
     */
    fun match(tag: String): Language? {
        val locale = Locale.forLanguageTag(tag)
        return when (locale.language) {
            "en" -> Language.ENGLISH
            "zh" -> if (isSimplified(locale)) Language.CHINESE_SIMPLIFIED else null
            "fr" -> Language.FRENCH
            "it" -> Language.ITALIAN
            "ja" -> Language.JAPANESE
            "pt" -> Language.PORTUGUESE_BRAZIL
            "ru" -> Language.RUSSIAN
            "es" -> Language.SPANISH
            else -> null
        }
    }

    /** What the app shows for a primary locale [tag]: its language, or English when it has no translation. */
    fun shown(tag: String): Language = match(tag) ?: Language.ENGLISH

    private fun isSimplified(locale: Locale): Boolean =
        when (locale.script) {
            "Hans" -> true
            "" -> locale.country !in TRADITIONAL_REGIONS
            else -> false
        }
}
