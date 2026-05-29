package scooper.util

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import name.kropp.kotlinx.gettext.Gettext
import name.kropp.kotlinx.gettext.Locale
import okio.source
import java.util.Locale as SystemLocale

/**
 * Represents a supported UI locale with its display name.
 */
data class SupportedLocale(
    val locale: Locale,
    val displayName: String,
)

/**
 * List of all supported locales. Add new languages here.
 */
val supportedLocales = listOf(
    SupportedLocale(Locale.ENGLISH, "English"),
    SupportedLocale(Locale.SIMPLIFIED_CHINESE, "中文 (简体)"),
)

/**
 * Detect the best matching locale from the system default.
 * Handles zh-Hans-CN vs zh-CN compatibility and falls back to English.
 */
fun getDefaultLocale(): Locale {
    val systemLocale = SystemLocale.getDefault()

    // Exact match
    for (sl in supportedLocales) {
        if (sl.locale == Locale.forLanguageTag(systemLocale.toLanguageTag())) return sl.locale
    }
    // Match by language code only
    for (sl in supportedLocales) {
        if (sl.locale.language == systemLocale.language) return sl.locale
    }
    return supportedLocales.first().locale
}

/**
 * Load a [Gettext] instance from the `.po` file for the given locale.
 * Falls back to the current i18n instance on error.
 */
fun loadLocale(locale: Locale): Gettext = runCatching {
    val resourcePath = "lang/$locale.po"
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: error("Cannot find PO file for locale $locale at $resourcePath")
    stream.use { s ->
        Gettext.load(locale, s.source())
    }
}.getOrElse { e ->
    e.printStackTrace()
    Strings.current
}

/**
 * Encapsulated singleton holding the current translation instance.
 *
 * - [current] is backed by Compose [mutableStateOf] so Compose automatically
 *   tracks reads and recomposes when the locale changes.
 * - Only [update] can modify the instance (private set).
 */
object Strings {
    private val _current = mutableStateOf(Gettext.load(getDefaultLocale()))

    val current: Gettext get() = _current.value

    fun update(locale: Locale) {
        _current.value = loadLocale(locale)
    }
}

// ---------------------------------------------------------------------------
// Top-level convenience functions — gettext Gradle plugin extracts strings via
// keywords.set(listOf("tr", "trc:1c,2"))
// ---------------------------------------------------------------------------

/** Simple translation. */
fun tr(msgid: String): String = Strings.current.tr(msgid)

/** Translation with named parameter interpolation ({{key}}). */
fun tr(msgid: String, vararg args: Pair<String, String>): String =
    Strings.current.tr(msgid, *args)

/** Translation with context for disambiguation. */
fun trc(context: String, msgid: String): String =
    Strings.current.trc(context, msgid)

/** Translation with plural forms. */
fun trn(msgid: String, msgidPlural: String, n: Int, vararg args: Pair<String, String>): String =
    Strings.current.trn(msgid, msgidPlural, n, *args)

// ---------------------------------------------------------------------------
// CompositionLocal — mostly useful for ProvideI18n wrapping the root.
// ---------------------------------------------------------------------------

val LocalI18n = staticCompositionLocalOf { Strings.current }

/**
 * Wrapper that updates [Strings] for the given [locale] and provides the
 * updated instance via [LocalI18n]. Call this at the root of the Compose tree.
 */
@Composable
fun ProvideI18n(locale: Locale, content: @Composable () -> Unit) {
    Strings.update(locale)
    CompositionLocalProvider(LocalI18n provides Strings.current, content = content)
}
