package scooper.util

internal fun formatUncaughtException(thread: Thread, throwable: Throwable): String {
    val message = throwable.message ?: throwable.javaClass.name
    return "Uncaught exception on ${thread.name}: $message"
}

/** Install both crash safety nets: default thread handler + AWT EDT handler.
 *  Must run before any window exists: AWT reads sun.awt.exception.handler
 *  lazily at the first EDT exception. */
internal fun installCrashGuards() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        // Transient Windows clipboard locks (and other EDT hiccups) must not kill
        // the whole app; log and keep running so the user can simply retry.
        System.err.println(formatUncaughtException(thread, throwable))
        throwable.printStackTrace(System.err)
    }
    System.setProperty("sun.awt.exception.handler", AwtEventThreadExceptionHandler::class.java.name)
}

/** AWT EDT safety net: AWT reflectively instantiates this class via the
 *  sun.awt.exception.handler property set in installCrashGuards, so it must
 *  stay public with a public constructor taking Throwable. */
class AwtEventThreadExceptionHandler(cause: Throwable) {
    fun handle(t: Throwable) {
        System.err.println(formatUncaughtException(Thread.currentThread(), t))
        t.printStackTrace(System.err)
    }
}
