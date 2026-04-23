package scooper.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressParserTest {

    @Test
    fun `parse aria2 progress - mid download`() {
        assertEquals(22, ProgressParser.parseProgress("[#a1b2c3 1.2MiB/5.4MiB(22%) CN:1 DL:800KiB ETA:3s]"))
    }

    @Test
    fun `parse aria2 progress - zero percent`() {
        assertEquals(0, ProgressParser.parseProgress("[#a1b2c3 0B/5.4MiB(0%) CN:1 DL:0B]"))
    }

    @Test
    fun `parse aria2 progress - near complete`() {
        assertEquals(98, ProgressParser.parseProgress("[#a1b2c3 5.3MiB/5.4MiB(98%) CN:1 DL:100KiB]"))
    }

    @Test
    fun `parse aria2 progress - 100 percent`() {
        assertEquals(100, ProgressParser.parseProgress("[#a1b2c3 5.4MiB/5.4MiB(100%) CN:1 DL:0B]"))
    }

    @Test
    fun `parse aria2 progress - embedded in other output`() {
        assertEquals(35, ProgressParser.parseProgress("some prefix [#abc123 2.0MiB/6.0MiB(35%) CN:2 DL:1.2MiB ETA:2s] suffix"))
    }

    @Test
    fun `return null for non-progress lines`() {
        assertNull(ProgressParser.parseProgress("Installing 'app' (1.0.0) [64bit]"))
        assertNull(ProgressParser.parseProgress("Loading installapp from json manifest..."))
        assertNull(ProgressParser.parseProgress(""))
    }
}
