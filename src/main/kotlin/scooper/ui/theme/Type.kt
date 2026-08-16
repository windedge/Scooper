package scooper.ui.theme

import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontWidth
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.FontStyle as SkiaFontStyle
import org.jetbrains.skia.FontWeight as SkiaFontWeight

/**
 * App typography with an optional locale-mapped system [FontFamily].
 *
 * Background: leaving [Typography.defaultFontFamily] unset (FontFamily.Default)
 * lets Skia pick Segoe UI for Latin text, but CJK characters are resolved
 * through the system fallback chain, which in a non-zh-CN process often ends
 * on SimSun (Songti) - thin, blurry-edged glyphs that look wrong next to
 * Segoe UI. To fix this we map the active UI language to a Windows system
 * family and pass it as [Typography.defaultFontFamily]: CJK languages get
 * their standard Windows UI font (YaHei for zh, Yu Gothic UI for ja, Malgun
 * Gothic for ko - those families include Latin glyphs themselves), while
 * every non-CJK language - currently "en" - gets Arial, which is what the
 * app loaded explicitly before i18n; using Segoe UI instead would subtly
 * change metrics and rendering of the historical English UI.
 *
 * Loading a font file explicitly (e.g. Arial from C:\Windows\Fonts) bypasses
 * the system font manager and often causes Skia to pick inconsistent CJK
 * fallback fonts across different [FontWeight] values, so we stay with
 * system-font-manager lookups ([FontMgr.matchFamilyStyle]) instead.
 *
 * Adding support for a new CJK language is a one-line change: add an entry to
 * the mapping table in [fontFamilyFor].
 */
// Only Windows carries the reliable standard CJK UI font set and the Arial
// family the historical UI was designed against; the app does not target
// macOS, and Linux font ecosystems vary too much to map safely.
private val isWindows: Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

/**
 * Resolve the system [FontFamily] for [language] (ISO 639-1 code such as
 * "en", "zh", "ja" or "ko"), or null to keep [FontFamily.Default].
 *
 * CJK languages map to their Windows UI font; every other language (the
 * non-CJK default, currently "en") maps to Arial to preserve the pre-i18n
 * English UI look. Each candidate family is validated against the actual
 * system font manager (see [matchInstalledTypeface]); when no candidate is
 * installed (e.g. Arial stripped from the system) this returns null and
 * [Typography.defaultFontFamily] falls back to [FontFamily.Default].
 *
 * Non-Windows platforms return null and keep [FontFamily.Default] (system
 * fallback chain); the app does not target macOS.
 */
fun fontFamilyFor(language: String): FontFamily? {
    if (!isWindows) return null
    val candidates = when (language) {
        "zh" -> listOf("Microsoft YaHei UI", "Microsoft YaHei")
        "ja" -> listOf("Yu Gothic UI")
        "ko" -> listOf("Malgun Gothic")
        // Non-CJK languages keep the pre-i18n look: Arial, with a safe
        // FontFamily.Default fallback when it is not installed.
        else -> listOf("Arial")
    }
    return buildSystemFamily(candidates)
}

/**
 * Look up [familyName] in the system font manager at [skiaWeight] (Skia's
 * integer weight: 400/500/700) and return the matching [Typeface], or null
 * when the family is not installed.
 *
 * [FontMgr.matchFamilyStyle] silently falls back to a default font when the
 * family is unknown, so a hit counts only when the resolved
 * [Typeface.familyName] matches the requested name (ignore case). The
 * caller owns the returned typeface's native handle and must close it.
 */
private fun matchInstalledTypeface(familyName: String, skiaWeight: Int): Typeface? =
    FontMgr.default.matchFamilyStyle(
        familyName,
        SkiaFontStyle(skiaWeight, FontWidth.NORMAL, FontSlant.UPRIGHT)
    )?.takeIf { it.familyName.equals(familyName, ignoreCase = true) }

/**
 * Check whether [familyName] is a system family actually installed at
 * [skiaWeight] (Skia's integer weight: 400/500/700).
 */
private fun isFamilyInstalled(familyName: String, skiaWeight: Int): Boolean {
    val typeface = matchInstalledTypeface(familyName, skiaWeight) ?: return false
    typeface.close()
    return true
}

/**
 * Build a [FontFamily] from the first [candidates] family name that is
 * actually installed, covering Normal, Medium and Bold. Weights that cannot
 * be resolved are skipped; null when no candidate resolves at all. Candidates
 * are never mixed: the returned [FontFamily] comes from a single family.
 *
 * Compose 1.10.x has no public [Font] factory that wraps an existing Skia
 * [Typeface], and the name-based [androidx.compose.ui.text.platform.Font]
 * factory means a classpath *resource*, not a system family (it fails with
 * "Can't load font from ..." at render time and silently falls back to
 * [FontFamily.Default]). The desktop [SystemFont] variant is the one that
 * resolves its identity through the system font manager, exactly like
 * [matchInstalledTypeface], so only validated family names get through here.
 */
@OptIn(ExperimentalTextApi::class)
private fun buildSystemFamily(candidates: List<String>): FontFamily? {
    for (familyName in candidates) {
        val fonts = listOf(
            FontWeight.Normal to SkiaFontWeight.NORMAL,
            FontWeight.Medium to SkiaFontWeight.MEDIUM,
            FontWeight.Bold to SkiaFontWeight.BOLD,
        ).mapNotNull { (composeWeight, skiaWeight) ->
            if (isFamilyInstalled(familyName, skiaWeight)) {
                SystemFont(familyName, composeWeight, FontStyle.Normal)
            } else {
                null
            }
        }
        if (fonts.isNotEmpty()) return FontFamily(fonts)
    }
    return null
}

/**
 * Resolve the user-selected interface font family [name] (from settings), or
 * null to keep the locale-mapped default from [fontFamilyFor].
 *
 * A blank name means "Default" (no override). Because a family may have been
 * uninstalled since it was saved, the name goes through the same
 * [matchInstalledTypeface] validation as [fontFamilyFor], so an uninstalled
 * (or otherwise unresolvable) name returns null here instead of rendering
 * with a wrong font. Non-Windows platforms return null.
 */
fun fontFamilyOverride(name: String): FontFamily? {
    if (!isWindows || name.isBlank()) return null
    return buildSystemFamily(listOf(name))
}

/**
 * Enumerate installed font families that actually cover CJK characters.
 *
 * Used to populate the "Interface Font" dropdown. Every family name reported
 * by [FontMgr] is re-validated through [matchInstalledTypeface] to guard
 * against silent fallbacks, then checked for CJK coverage via
 * [Typeface.getUTF32Glyph] (glyph id 0 means the character has no glyph in
 * this typeface). Non-Windows platforms return an empty list.
 */
fun listInstalledCjkFontFamilies(): List<String> {
    if (!isWindows) return emptyList()
    val fontMgr = FontMgr.default
    val families = mutableListOf<String>()
    for (i in 0 until fontMgr.familiesCount) {
        val familyName = try {
            fontMgr.getFamilyName(i)
        } catch (_: Exception) {
            continue
        }
        val typeface = matchInstalledTypeface(familyName, SkiaFontWeight.NORMAL) ?: continue
        try {
            if (typeface.getUTF32Glyph('已'.code) != 0.toShort()) {
                families.add(familyName)
            }
        } finally {
            typeface.close()
        }
    }
    return families.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/**
 * Single-weight [FontFamily] for rendering a dropdown item with its own font
 * so users can preview each choice, or null to render with the default font
 * (the "Default" entry). Returns null when the family is not installed.
 */
@OptIn(ExperimentalTextApi::class)
fun fontFamilyForPreview(name: String): FontFamily? {
    if (!isWindows || name.isBlank()) return null
    if (!isFamilyInstalled(name, SkiaFontWeight.NORMAL)) return null
    return FontFamily(SystemFont(name, FontWeight.Normal, FontStyle.Normal))
}

/**
 * App typography.
 *
 * @param scale multiplicative font-size scale from user settings.
 * @param systemFontFamily locale-mapped family from [fontFamilyFor] (Arial
 *   for non-CJK languages, the language's UI font for CJK), or null to keep
 *   [FontFamily.Default] (Segoe UI and system fallback).
 */
@Composable
fun typography(scale: Float = 1.0f, systemFontFamily: FontFamily? = null) = Typography(
    defaultFontFamily = systemFontFamily ?: FontFamily.Default,
    h5 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (19 * scale).sp,
    ),
    h6 = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (17 * scale).sp,
    ),
    body1 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (15 * scale).sp,
    ),
    body2 = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (15 * scale).sp,
    ),
    button = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (14 * scale).sp,
        letterSpacing = (0.5 * scale).sp,
    ),
    caption = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = (13 * scale).sp,
    ),
    subtitle1 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (15 * scale).sp,
    ),
    subtitle2 = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = (13 * scale).sp,
    ),
    overline = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = (11 * scale).sp,
        letterSpacing = (1 * scale).sp,
    ),
)
