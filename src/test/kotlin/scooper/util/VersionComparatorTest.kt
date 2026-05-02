package scooper.util

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionComparatorTest {

    private val cmp = VersionComparator

    @Test
    fun `10_x is greater than 9_x`() {
        assertPositive(cmp.compare("10.0.0.260501214", "9.16.3.26010361"))
    }

    @Test
    fun `same major, higher minor wins`() {
        assertPositive(cmp.compare("9.16.3", "9.16.2"))
        assertPositive(cmp.compare("9.16.3", "9.15.0"))
    }

    @Test
    fun `longer numeric segment 10 greater than 9`() {
        assertPositive(cmp.compare("1.2.10", "1.2.9"))
    }

    @Test
    fun `equal versions return 0`() {
        assertEquals(0, cmp.compare("9.16.3", "9.16.3"))
        assertEquals(0, cmp.compare("1.0.0", "1.0.0"))
    }

    @Test
    fun `release is greater than pre-release`() {
        assertPositive(cmp.compare("2.0.0", "2.0.0-beta"))
        assertPositive(cmp.compare("1.0.0", "1.0.0-rc1"))
    }

    @Test
    fun `alpha is less than beta`() {
        assertPositive(cmp.compare("1.0.0-beta", "1.0.0-alpha"))
    }

    @Test
    fun `different segment counts`() {
        assertPositive(cmp.compare("1.2.1", "1.2"))
        assertPositive(cmp.compare("1.2.0.1", "1.2.0"))
    }

    @Test
    fun `sortedByVersionDesc orders correctly`() {
        val versions = listOf(
            "9.14.2.2402143",
            "10.0.0.260501214",
            "9.16.3.26010361",
            "9.16.2.25121653",
            "9.15.0.2501014",
            "9.16.1.25120847",
        )
        val sorted = versions.sortedByVersionDesc()
        assertEquals(
            listOf(
                "10.0.0.260501214",
                "9.16.3.26010361",
                "9.16.2.25121653",
                "9.16.1.25120847",
                "9.15.0.2501014",
                "9.14.2.2402143",
            ),
            sorted,
        )
    }

    private fun assertPositive(value: Int) {
        assert(value > 0) { "Expected positive, got $value" }
    }
}
