package scooper.util

import com.github.pgreze.process.ProcessResult
import com.github.pgreze.process.Redirect
import com.github.pgreze.process.process
import kotlinx.coroutines.*
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.nio.charset.Charset

val defaultCharset by lazy { charset(System.getProperty("file.encoding")) }

@Suppress("SameParameterValue")
fun execute(
    vararg commandArgs: String,
    asShell: Boolean = true,
    workingDir: File? = null,
    charset: Charset = defaultCharset,
    consumer: suspend (line: String) -> Unit = { },
    onFinish: suspend (exitValue: Int) -> Unit = {},
): ProcessResult {
    val args = commandArgs.toList()
    return execute(args, asShell, workingDir, charset = charset, consumer = consumer, onFinish = onFinish)
}

/** Test-only: when set, prepends the shim directory to the child PATH so "scoop ..." resolves to a stub. */
internal const val TEST_SCOOP_SHIM_ENV = "scooper.test.scoopShim"

internal fun scoopTestEnv(): Map<String, String>? {
    val shim = System.getProperty(TEST_SCOOP_SHIM_ENV) ?: System.getenv(TEST_SCOOP_SHIM_ENV) ?: return null
    if (shim.isBlank()) return null
    val dir = java.io.File(shim).parent ?: return null
    val path = System.getenv("PATH") ?: ""
    return mapOf("PATH" to "$dir${java.io.File.pathSeparator}$path", TEST_SCOOP_SHIM_ENV to shim)
}

/**
 * Execute a command synchronously (blocks the calling thread).
 * Use only when coroutine context is unavailable.
 * Prefer [executeSuspend] when possible.
 */
fun execute(
    commandArgs: List<String>,
    asShell: Boolean = true,
    workingDir: File? = null,
    charset: Charset = defaultCharset,
    consumer: suspend (line: String) -> Unit = { },
    onFinish: suspend (exitValue: Int) -> Unit,
): ProcessResult = runBlocking {
    val command = buildCommand(commandArgs, asShell)
    val result = process(
        *command,
        stdout = Redirect.CAPTURE,
        stderr = Redirect.CAPTURE,
        directory = workingDir,
        consumer = consumer,
        charset = charset
    )
    onFinish(result.resultCode)
    return@runBlocking result
}

/**
 * Suspend version that executes a command in coroutine context without blocking.
 */
suspend fun executeSuspend(
    commandArgs: List<String>,
    asShell: Boolean = true,
    workingDir: File? = null,
    charset: Charset = defaultCharset,
    env: Map<String, String>? = null,
    consumer: suspend (line: String) -> Unit = { },
    onFinish: suspend (exitValue: Int) -> Unit,
): ProcessResult {
    val command = buildCommand(commandArgs, asShell)
    // destroyForcibly = true: cmd.exe ignores the pipe close of a graceful
    // destroy, so a cancelled long-running scoop would keep the UI busy
    // flags stuck until the command finished by itself.
    val result = process(
        *command,
        stdout = Redirect.CAPTURE,
        stderr = Redirect.CAPTURE,
        directory = workingDir,
        env = env,
        // Wrap the consumer so cancellation surfaces from the streaming
        // branch too, not only from the waitFor interruptible call.
        consumer = { line ->
            currentCoroutineContext().ensureActive()
            consumer(line)
        },
        charset = charset,
        destroyForcibly = true,
    )
    onFinish(result.resultCode)
    return result
}

private fun buildCommand(commandArgs: List<String>, asShell: Boolean): Array<String> {
    return if (asShell) {
        val args = commandArgs.joinToString(" ", transform = StringEscapeUtils::escapeXSI)
        arrayOf("cmd", "/c", args)
    } else {
        commandArgs.toTypedArray()
    }
}

fun killAllSubProcesses() {
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        // taskkill /T kills the whole tree and must run while the parent chain (cmd -> pwsh ->
        // aria2) is alive: killing only the direct child orphans descendants that keep going.
        for (child in ProcessHandle.current().children().toList()) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", child.pid().toString(), "/T", "/F")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor()
            }
        }
    }
    // Force-kill whatever is left, including stragglers re-parented mid-kill.
    ProcessHandle.current().descendants().forEach { it.destroyForcibly() }
}

fun findExecutable(name: String): String? {
    for (dirname in System.getenv("PATH").split(File.pathSeparator)) {
        val file = File(dirname, name)
        if (file.isFile && file.canExecute()) {
            return file.absolutePath
        }
    }
    return null
}
