package scooper.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * localeTagToReplyLanguage: mapping from the stored UI locale tag
 * (e.g. "zh-CN") to the English reply language name passed to the
 * scoop-ai engine. Must not read the JVM default locale: the result
 * depends only on the given tag.
 */
class LocaleTagToReplyLanguageTest {

    @Test
    fun `every supported locale maps to its English reply language name`() {
        val expected = listOf(
            "zh-CN" to "Simplified Chinese",
            "zh-TW" to "Traditional Chinese",
            "ja" to "Japanese",
            "de" to "German",
            "es" to "Spanish",
            "fr" to "French",
            "ru" to "Russian",
            "sl" to "Slovenian",
            "en" to "English",
        )
        expected.forEach { (tag, language) ->
            assertEquals(language, localeTagToReplyLanguage(tag), tag)
        }
        // The mapping must cover exactly the supported locales.
        assertEquals(
            supportedLocales.map { it.locale.toLanguageTag() }.sorted(),
            expected.map { it.first }.sorted(),
        )
    }

    @Test
    fun `unknown region variants degrade to the language-only match`() {
        assertEquals("German", localeTagToReplyLanguage("de-AT"))
        assertEquals("Japanese", localeTagToReplyLanguage("ja-JP"))
    }

    @Test
    fun `zh exact tags hit but the bare zh tag stays ambiguous`() {
        assertEquals("Simplified Chinese", localeTagToReplyLanguage("zh-CN"))
        assertEquals("Traditional Chinese", localeTagToReplyLanguage("zh-TW"))
        assertNull(localeTagToReplyLanguage("zh"))
    }

    @Test
    fun `unknown tags return null`() {
        assertNull(localeTagToReplyLanguage("xx"))
        assertNull(localeTagToReplyLanguage("th"))
    }

    @Test
    fun `blank or null input returns null`() {
        assertNull(localeTagToReplyLanguage(null))
        assertNull(localeTagToReplyLanguage(""))
        assertNull(localeTagToReplyLanguage("  "))
    }
}
