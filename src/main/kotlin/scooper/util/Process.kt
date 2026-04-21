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

/**
 * 同步执行命令（阻塞调用线程）。仅在无法使用协程上下文时使用。
 * 优先使用 [executeSuspend] 版本。
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
 * 挂起函数版本，在协程上下文中执行命令，不阻塞线程。
 */
suspend fun executeSuspend(
    commandArgs: List<String>,
    asShell: Boolean = true,
    workingDir: File? = null,
    charset: Charset = defaultCharset,
    consumer: suspend (line: String) -> Unit = { },
    onFinish: suspend (exitValue: Int) -> Unit,
): ProcessResult {
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
    ProcessHandle.current().descendants().forEach { it.destroy() }
    while (ProcessHandle.current().descendants().anyMatch { it.isAlive }) {
        runBlocking { delay(100) }
    }
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
