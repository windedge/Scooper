package scooper.util

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import name.kropp.kotlinx.gettext.Gettext
import name.kropp.kotlinx.gettext.Locale
import okio.source
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale as SystemLocale

/**
 * Represents a supported UI locale with its display name.
 */
data class SupportedLocale(
    val locale: Locale,
    val displayName: String,
    val replyLanguage: String,
)

/**
 * List of all supported locales. Add new languages here.
 */
val supportedLocales = listOf(
    SupportedLocale(Locale.ENGLISH, "English", "English"),
    SupportedLocale(Locale.GERMAN, "Deutsch", "German"),
    SupportedLocale(Locale("es"), "Español", "Spanish"),
    SupportedLocale(Locale.FRENCH, "Français", "French"),
    SupportedLocale(Locale("sl"), "Slovenščina", "Slovenian"),
    SupportedLocale(Locale("ru"), "Русский", "Russian"),
    SupportedLocale(Locale.JAPANESE, "日本語", "Japanese"),
    SupportedLocale(Locale.SIMPLIFIED_CHINESE, "简体中文", "Simplified Chinese"),
    SupportedLocale(Locale.TRADITIONAL_CHINESE, "繁體中文", "Traditional Chinese"),
)

/**
 * Map a locale tag (e.g. the persisted UI locale "zh-CN" or "de-AT") to the
 * English reply language name for the engine prompt. Exact tag first, then
 * the language-only part as fallback, so unknown region variants such as
 * "de-AT" degrade to their base language. Returns null for blank or
 * unsupported tags. Never reads the JVM default locale.
 */
fun localeTagToReplyLanguage(tag: String?): String? {
    val normalized = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    supportedLocales.firstOrNull { it.locale.toLanguageTag() == normalized }
        ?.let { return it.replyLanguage }
    val languageOnly = normalized.substringBefore('-').substringBefore('_')
    return supportedLocales.firstOrNull { it.locale.language == languageOnly }?.replyLanguage
}

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

private val logger: Logger = LoggerFactory.getLogger("Localization")

/**
 * Load a [Gettext] instance from the `.po` file for the given locale.
 * Never throws: on missing/failed resources it keeps the current i18n instance
 * and only logs a warning.
 */
fun loadLocale(locale: Locale): Gettext {
    val resourcePath = "lang/$locale.po"
    return try {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
            ?: error("Cannot find PO file for locale $locale at $resourcePath")
        // stream is closed by use() in both the success and the failure path
        stream.use { s ->
            Gettext.load(locale, s.source())
        }
    } catch (e: Exception) {
        logger.warn("Failed to load PO file for locale $locale, keeping current language", e)
        Strings.current
    }
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
        // java.util.Locale equality is identity-based, so compare by language tag.
        // Guarding here prevents the .po file from being re-parsed on every
        // recomposition when the locale did not actually change.
        if (current.locale.toLanguageTag() == locale.toLanguageTag()) return
        _current.value = loadLocale(locale)
    }
}

// ---------------------------------------------------------------------------
// Top-level convenience functions - gettext Gradle plugin extracts strings via
// keywords.set(listOf("tr", "trc:1c,2"))
// ---------------------------------------------------------------------------

/**
 * Marker interface for types whose display strings are translated at render
 * time (e.g. enums shown in dropdowns or tabs).
 *
 * - [displayName] reads [Strings.current] on every call, so invoking it inside
 *   a Composable registers a snapshot read and the caller recomposes
 *   automatically when the language changes.
 * - The `tr(...)` calls in the implementing body are plain source calls, so
 *   the gettext Gradle plugin extracts their msgids as usual.
 */
interface Translatable {
    fun displayName(): String
}

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
// CompositionLocal - mostly useful for ProvideI18n wrapping the root.
// ---------------------------------------------------------------------------

val LocalI18n = staticCompositionLocalOf { Strings.current }

/**
 * Wrapper that updates [Strings] for the given [locale] and provides the
 * updated instance via [LocalI18n]. Call this at the root of the Compose tree.
 */
@Composable
fun ProvideI18n(locale: Locale, content: @Composable () -> Unit) {
    // Perform the (potentially blocking) .po parse as a side effect after
    // composition instead of during it. Strings.update() is a no-op for the
    // already-active locale, so unrelated recompositions stay cheap.
    SideEffect {
        Strings.update(locale)
    }
    CompositionLocalProvider(LocalI18n provides Strings.current, content = content)
}
