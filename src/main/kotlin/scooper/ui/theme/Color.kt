package scooper.ui.theme

import androidx.compose.material.Colors
import androidx.compose.ui.graphics.Color

// ── Slate (gray scale) ──────────────────────────────
val Slate50 = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate300 = Color(0xFFCBD5E1)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)

// ── Blue (primary / accent) ─────────────────────────
val Blue50 = Color(0xFFEFF6FF)
val Blue100 = Color(0xFFDBEAFE)
val Blue200 = Color(0xFFBFDBFE)
val Blue500 = Color(0xFF3B82F6)
val Blue600 = Color(0xFF2563EB)
val Blue700 = Color(0xFF1D4ED8)
val Blue800 = Color(0xFF1E40AF)

// ── Emerald (success / update) ──────────────────────
val Emerald500 = Color(0xFF10B981)
val Emerald600 = Color(0xFF059669)
val Emerald700 = Color(0xFF047857)

// ── Red (danger / delete) ───────────────────────────
val Red50 = Color(0xFFFEF2F2)
val Red500 = Color(0xFFEF4444)
val Red600 = Color(0xFFDC2626)
val Red700 = Color(0xFFB91C1C)

// ── Amber (warning) ─────────────────────────────────
val Amber500 = Color(0xFFF59E0B)

// ── Extended Material Theme colors ──────────────────
// Usage: MaterialTheme.colors.backgroundHover

val Colors.backgroundHover: Color
    get() = if (isLight) Slate50 else Slate700

val Colors.borderDefault: Color
    get() = if (isLight) Slate200 else Slate700

val Colors.borderLight: Color
    get() = if (isLight) Slate100 else Slate600

val Colors.borderHover: Color
    get() = if (isLight) Slate300 else Slate600

val Colors.textTitle: Color
    get() = if (isLight) Slate900 else Slate50

val Colors.textBody: Color
    get() = if (isLight) Slate500 else Slate300

val Colors.textMuted: Color
    get() = if (isLight) Slate400 else Slate400

val Colors.textPlaceholder: Color
    get() = if (isLight) Slate400 else Slate500

val Colors.primaryHover: Color
    get() = if (isLight) Blue700 else Blue600

val Colors.primaryPressed: Color
    get() = if (isLight) Blue800 else Blue700

val Colors.primarySubtle: Color
    get() = if (isLight) Blue50 else Color(0xFF1E3A5F)

val Colors.primaryBadgeBg: Color
    get() = if (isLight) Blue100 else Color(0xFF1E3A5F)

val Colors.updateDefault: Color
    get() = Emerald500

val Colors.updateHover: Color
    get() = Emerald600

val Colors.updatePressed: Color
    get() = Emerald700

val Colors.dangerDefault: Color
    get() = Red500

val Colors.dangerHover: Color
    get() = if (isLight) Red600 else Red600

val Colors.dangerBg: Color
    get() = if (isLight) Red50 else Color(0xFF3B1C1C)

val Colors.warningDefault: Color
    get() = Amber500

// Standard component colors (convenience aliases)

val Colors.divider: Color
    get() = if (isLight) Slate100 else Slate700

val Colors.inputBackground: Color
    get() = if (isLight) Slate50 else Slate700

val Colors.inputBorder: Color
    get() = if (isLight) Slate200 else Slate500

val Colors.sidebarBackground: Color
    get() = if (isLight) Slate50 else Slate900

val Colors.sidebarBorder: Color
    get() = if (isLight) Slate200 else Slate700

val Colors.sidebarSelectedBg: Color
    get() = if (isLight) surface else Slate700

val Colors.sidebarSelectedText: Color
    get() = if (isLight) Blue700 else Blue500

val Colors.sidebarSelectedIcon: Color
    get() = if (isLight) Blue600 else Blue500

val Colors.sidebarHoverBg: Color
    get() = if (isLight) Slate200 else Slate700

val Colors.sidebarTextMedium: Color
    get() = if (isLight) Slate600 else Slate300

val Colors.sidebarTextLight: Color
    get() = if (isLight) Slate500 else Slate400

val Colors.sidebarBadgeBg: Color
    get() = if (isLight) Blue50 else Color(0xFF1E3A5F)

val Colors.sidebarBadgeText: Color
    get() = if (isLight) Blue600 else Blue500

val Colors.sidebarBadgeBorder: Color
    get() = if (isLight) Blue200 else Slate600

val Colors.sidebarUnselectedBadgeBg: Color
    get() = if (isLight) Slate200 else Slate600

val Colors.settingsSidebarBg: Color
    get() = if (isLight) Slate50 else Slate900

val Colors.statusBarBg: Color
    get() = if (isLight) Slate50 else Slate900

val Colors.segmentedBg: Color
    get() = if (isLight) Slate200 else Slate700

val Colors.segmentedSelectedBorder: Color
    get() = if (isLight) Slate200.copy(alpha = 0.5f) else Slate600.copy(alpha = 0.5f)

val Colors.unselectedBadgeBg: Color
    get() = if (isLight) Slate300 else Slate600

val Colors.unselectedBadgeText: Color
    get() = if (isLight) Slate600 else Slate300

val Colors.unselectedTabText: Color
    get() = if (isLight) Slate500 else Slate400
