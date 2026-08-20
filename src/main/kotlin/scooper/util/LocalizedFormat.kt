package scooper.util

import java.text.Collator
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Comparator
import java.util.Locale

/**
 * Locale-aware formatting helpers (dates, numbers, file sizes) driven by the
 * active UI locale.
 *
 * Each helper takes a locale parameter defaulting to [currentLocale]. Since that
 * is backed by a Compose [androidx.compose.runtime.mutableStateOf], calling them
 * inside a @Composable registers a snapshot read and the call site recomposes
 * when the UI language changes. Explicit locales are still accepted for tests
 * and background work where the ambient UI locale must not be (or cannot be) read.
 */

/**
 * The [java.util.Locale] of the currently active UI translation.
 *
 * kotlinx-gettext's Locale is a typealias of java.util.Locale on the JVM, so
 * the value can be handed to java.text/java.time directly.
 */
fun currentLocale(): Locale = Strings.current.locale

/** Format an absolute date in the locale's medium style, e.g. "May 27, 2024" / "2024年5月27日". */
fun formatDate(instant: Instant, locale: Locale = currentLocale()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(instant)

/** Format a [LocalDateTime] (already in the system zone) in the locale's medium date style. */
fun formatDate(dateTime: LocalDateTime, locale: Locale = currentLocale()): String =
    formatDate(dateTime.atZone(ZoneId.systemDefault()).toInstant(), locale)

/** Format a date-time in the locale's medium date / short time style, e.g. "May 27, 2024, 3:45 PM". */
fun formatDateTime(dateTime: LocalDateTime, locale: Locale = currentLocale()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(dateTime)

/** Format an integer count with the locale's grouping separator, e.g. "1,234,567" / "1.234.567". */
fun formatNumber(n: Long, locale: Locale = currentLocale()): String =
    NumberFormat.getIntegerInstance(locale).format(n)

/**
 * Format a byte count for display: same thresholds and units as the previous
 * "%.1f GB/MB" / "%.0f kB" / "0 bytes" / "N bytes" implementation, but the
 * decimal separator follows [locale]. English locales render identically to
 * the old String.format output ("2.0 MB", "2 kB").
 */
fun formatFileSize(bytes: Long, locale: Locale = currentLocale()): String =
    formatFileSize(bytes.toDouble(), locale)

/** Double variant of [formatFileSize] (kept for the readableSize extensions). */
fun formatFileSize(bytes: Double, locale: Locale = currentLocale()): String = when {
    bytes >= 1 shl 30 -> fixedDecimal(locale, 1).format(bytes / (1 shl 30)) + " GB"
    bytes >= 1 shl 20 -> fixedDecimal(locale, 1).format(bytes / (1 shl 20)) + " MB"
    bytes >= 1 shl 10 -> fixedDecimal(locale, 0).format(bytes / (1 shl 10)) + " kB"
    bytes == 0.0 -> "0 bytes"
    else -> fixedDecimal(locale, 1).format(bytes) + " bytes"
}

/** NumberFormat without grouping and with fixed fraction digits, so only the separator is localized. */
private fun fixedDecimal(locale: Locale, fractionDigits: Int): NumberFormat =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = fractionDigits
        maximumFractionDigits = fractionDigits
    }

/**
 * A name comparator honoring the current locale's collation rules (accents,
 * case, non-Latin scripts) instead of String's binary order.
 */
@Suppress("UNCHECKED_CAST")
fun localeAwareComparator(locale: Locale = currentLocale()): Comparator<String> =
    Collator.getInstance(locale) as Comparator<String>
