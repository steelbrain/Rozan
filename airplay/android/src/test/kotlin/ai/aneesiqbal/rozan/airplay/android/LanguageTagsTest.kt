package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.AppLanguage.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LanguageTagsTest {
    @Test
    fun everyLanguageSurvivesStoring() {
        for (language in Language.entries) {
            assertEquals(language, LanguageTags.fromStored(LanguageTags.toStored(language)))
        }
    }

    @Test
    fun followingTheSystemIsStoredAsNoLanguage() {
        assertEquals(LanguageTags.SYSTEM, LanguageTags.toStored(null))
        assertNull(LanguageTags.fromStored(LanguageTags.SYSTEM))
        assertNull("never chosen", LanguageTags.fromStored(null))
    }

    @Test
    fun unknownStoredValuesFollowTheSystem() {
        for (value in listOf("de", "zh-Hant", "zh-CN", "pt", "FR", " fr", "fr ", "en-US", "x", "\u0000", "日本語")) {
            assertNull(value, LanguageTags.fromStored(value))
        }
    }

    @Test
    fun theOrderIsTheOneTheSettingLists() {
        assertEquals(
            listOf("English", "简体中文", "Français", "Italiano", "日本語", "Português (Brasil)", "Русский", "Español"),
            Language.entries.map { it.nativeName },
        )
    }

    @Test
    fun eachTagIsWellFormedAndMatchesItsOwnLanguage() {
        for (language in Language.entries) {
            assertEquals(language.tag, Locale.forLanguageTag(language.tag).toLanguageTag())
            assertEquals(language, LanguageTags.match(language.tag))
        }
    }

    @Test
    fun regionsDoNotMatter() {
        assertEquals(Language.ENGLISH, LanguageTags.match("en-GB"))
        assertEquals(Language.FRENCH, LanguageTags.match("fr-CA"))
        assertEquals(Language.SPANISH, LanguageTags.match("es-419"))
        assertEquals(Language.SPANISH, LanguageTags.match("es-US"))
        assertEquals(Language.PORTUGUESE_BRAZIL, LanguageTags.match("pt-PT"))
        assertEquals(Language.PORTUGUESE_BRAZIL, LanguageTags.match("pt"))
        assertEquals(Language.RUSSIAN, LanguageTags.match("ru-UA"))
        assertEquals(Language.JAPANESE, LanguageTags.match("ja-JP"))
        assertEquals(Language.ITALIAN, LanguageTags.match("it-CH"))
    }

    @Test
    fun onlySimplifiedChineseMatches() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN", "zh-Hans-HK")) {
            assertEquals(tag, Language.CHINESE_SIMPLIFIED, LanguageTags.match(tag))
        }
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-TW", "zh-Latn")) {
            assertNull(tag, LanguageTags.match(tag))
        }
    }

    @Test
    fun otherLanguagesHaveNoMatchAndShowEnglish() {
        for (tag in listOf("de-DE", "ko", "ar", "zh-TW", "und", "", "not a tag", "en_US")) {
            assertNull(tag, LanguageTags.match(tag))
            assertEquals(tag, Language.ENGLISH, LanguageTags.shown(tag))
        }
        assertEquals(Language.RUSSIAN, LanguageTags.shown("ru-RU"))
    }
}
