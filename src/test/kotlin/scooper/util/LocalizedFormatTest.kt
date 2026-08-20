package scooper.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class LocalizedFormatTest {

    @Test
    fun `formatFileSize uses English decimals and units for US locale`() {
        assertEquals("0 bytes", formatFileSize(0L, Locale.US))
        assertEquals("2 kB", formatFileSize(2048L, Locale.US))
        assertEquals("2.0 MB", formatFileSize(2L * 1024 * 1024, Locale.US))
        assertEquals("1.5 GB", formatFileSize((1.5 * (1 shl 30)).toLong(), Locale.US))
        // Sub-kB byte counts keep one decimal, like the previous "\$this bytes" output
        assertEquals("512.0 bytes", formatFileSize(512L, Locale.US))
    }

    @Test
    fun `formatFileSize uses German decimal separator`() {
        assertEquals("2,0 MB", formatFileSize(2L * 1024 * 1024, Locale.GERMANY))
        assertEquals("2 kB", formatFileSize(2048L, Locale.GERMANY))
        assertEquals("0 bytes", formatFileSize(0L, Locale.GERMANY))
    }

    @Test
    fun `formatNumber groups thousands per locale`() {
        assertEquals("1,234,567", formatNumber(1234567L, Locale.US))
        assertEquals("1.234.567", formatNumber(1234567L, Locale.GERMANY))
        assertEquals("42", formatNumber(42L, Locale.US))
    }

    @Test
    fun `formatDate renders localized date`() {
        val dateTime = LocalDateTime.of(2024, 5, 27, 15, 45)
        val instant = dateTime.atZone(ZoneId.systemDefault()).toInstant()

        assertEquals("May 27, 2024", formatDate(instant, Locale.US))
        assertEquals("27.05.2024", formatDate(instant, Locale.GERMANY))
        // LocalDateTime overload formats the same calendar date
        assertEquals("May 27, 2024", formatDate(dateTime, Locale.US))
    }

    @Test
    fun `localeAwareComparator sorts alphabetically per locale`() {
        val us = localeAwareComparator(Locale.ENGLISH)
        assertTrue(us.compare("abc", "abd") < 0)

        val de = localeAwareComparator(Locale.GERMAN)
        // German collation treats Ä as a variant of A, so it sorts before Z
        val sorted = listOf("Zebra", "Ärger", "Apfel").sortedWith(de)
        assertEquals(listOf("Apfel", "Ärger", "Zebra"), sorted)
    }
}
