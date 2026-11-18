package scooper.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
    fun `every supported locale resolves to a reply language`() {
        supportedLocales.forEach { locale ->
            val tag = locale.locale.toLanguageTag()
            assertEquals(locale.replyLanguage, localeTagToReplyLanguage(tag), tag)
            assertNotNull(localeTagToReplyLanguage(tag), tag)
        }
    }

    @Test
    fun `unknown region variants degrade to the language-only match`() {
        assertEquals("German", localeTagToReplyLanguage("de-AT"))
        assertEquals("Japanese", localeTagToReplyLanguage("ja-JP"))
    }

    @Test
    fun `zh exact tags stay distinct and the bare tag picks the first variant`() {
        assertEquals("Simplified Chinese", localeTagToReplyLanguage("zh-CN"))
        assertEquals("Traditional Chinese", localeTagToReplyLanguage("zh-TW"))
        assertEquals("Simplified Chinese", localeTagToReplyLanguage("zh"))
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
