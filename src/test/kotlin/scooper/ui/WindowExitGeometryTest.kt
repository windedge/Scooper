package scooper.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowExitGeometryTest {

    private val saved = WindowExitGeometry(
        x = 120,
        y = 80,
        width = 1280,
        height = 800,
        isMaximized = false,
    )

    private val current = WindowExitGeometry(
        x = 9999,
        y = 9999,
        width = 2560,
        height = 1440,
        isMaximized = false,
    )

    @Test
    fun `maximized exit keeps saved geometry with isMaximized true`() {
        val result = resolveWindowExitGeometry(
            saved = saved,
            current = current,
            isMaximized = true,
        )
        assertEquals(
            saved.copy(isMaximized = true),
            result,
            "全屏边界不能覆盖用户保存的窗口化几何",
        )
    }

    @Test
    fun `floating exit adopts current geometry with isMaximized false`() {
        val result = resolveWindowExitGeometry(
            saved = saved,
            current = current,
            isMaximized = false,
        )
        assertEquals(current.copy(isMaximized = false), result)
    }

    @Test
    fun `identical saved and current geometry is idempotent`() {
        val same = saved
        val result = resolveWindowExitGeometry(
            saved = same,
            current = same,
            isMaximized = false,
        )
        assertEquals(same, result)
    }
}
