package scooper.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.expect

class CrashGuardTest {

    private val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val originalAwtHandlerProperty = System.getProperty("sun.awt.exception.handler")

    @AfterTest
    fun restoreHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        if (originalAwtHandlerProperty == null) {
            System.clearProperty("sun.awt.exception.handler")
        } else {
            System.setProperty("sun.awt.exception.handler", originalAwtHandlerProperty)
        }
    }

    // ---------------------------------------------------------------

    @Test
    fun `format includes thread name and exception message`() {
        val thread = Thread("AWT-EventQueue-0")
        val throwable = IllegalStateException("cannot open system clipboard")

        val line = formatUncaughtException(thread, throwable)

        assertEquals(
            "Uncaught exception on AWT-EventQueue-0: cannot open system clipboard",
            line,
        )
    }

    @Test
    fun `format falls back to exception class name when message is null`() {
        val line = formatUncaughtException(Thread("t"), RuntimeException())

        assertEquals("Uncaught exception on t: java.lang.RuntimeException", line)
    }

    @Test
    fun `install sets the default uncaught exception handler`() {
        installCrashGuards()

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler, "main 入口最早处安装后必须存在全局兜底 handler")
    }

    @Test
    fun `install registers awt event thread handler class via system property`() {
        installCrashGuards()

        assertEquals(
            "scooper.util.AwtEventThreadExceptionHandler",
            System.getProperty("sun.awt.exception.handler"),
            "AWT 反射按属性值实例化, 属性必须是新类全名",
        )
    }

    @Test
    fun `installed handler logs to stderr without throwing or killing the thread`() {
        installCrashGuards()
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(stderr))
        try {
            // 不得抛出：handler 自身抛异常会让兜底失效并终止进程
            handler.uncaughtException(Thread("paste-edt"), IllegalStateException("boom"))
        } finally {
            System.setErr(originalErr)
        }

        val output = stderr.toString()
        assertTrue(
            output.contains("Uncaught exception on paste-edt: boom"),
            "应记录线程名与异常信息, 实际输出: $output",
        )
        assertTrue(
            output.contains("java.lang.IllegalStateException"),
            "应打印完整堆栈, 实际输出: $output",
        )
    }

    @Test
    fun `reinstall overwrites previous handler so main keeps the last one`() {
        val sentinel = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(sentinel)

        installCrashGuards()

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler, "重复安装应覆盖旧 handler")
        assertTrue(handler !== sentinel, "重复安装应覆盖旧 handler")
    }

    // ---------------------------------------------------------------
    // AWT EDT 兜底: AWT 不走默认 handler, 反射读取 sun.awt.exception.handler
    // ---------------------------------------------------------------

    @Test
    fun `AwtEventThreadExceptionHandler can be instantiated with throwable constructor`() {
        // AWT 通过反射调用 Throwable 单参构造器, 构造器签名必须兼容
        val handler = AwtEventThreadExceptionHandler(IllegalStateException("edt-boom"))

        assertNotNull(handler)
    }

    @Test
    fun `AwtEventThreadExceptionHandler handle logs and returns without throwing`() {
        val handler = AwtEventThreadExceptionHandler(IllegalStateException("edt-boom"))
        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(stderr))
        try {
            handler.handle(RuntimeException("paste failed"))
        } finally {
            System.setErr(originalErr)
        }

        val output = stderr.toString()
        expect("Uncaught exception on AWT-EventQueue-0: paste failed") {
            formatUncaughtException(Thread("AWT-EventQueue-0"), RuntimeException("paste failed"))
        }
        assertTrue(
            output.contains("Uncaught exception on"),
            "handle 应复用统一日志格式, 实际输出: $output",
        )
        assertTrue(
            output.contains("java.lang.RuntimeException"),
            "handle 应打印完整堆栈, 实际输出: $output",
        )
    }
}
