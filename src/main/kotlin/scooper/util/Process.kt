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

fun execute(
    commandArgs: List<String>,
    asShell: Boolean = true,
    workingDir: File? = null,
    charset: Charset = defaultCharset,
    consumer: suspend (line: String) -> Unit = { },
    onFinish: suspend (exitValue: Int) -> Unit,
): ProcessResult = runBlocking {
    val command = if (asShell) {
        val args = commandArgs.joinToString(" ", transform = StringEscapeUtils::escapeXSI)
        arrayOf("cmd", "/c", args)
    } else {
        commandArgs.toTypedArray()
    }

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
    // throw AssertionError("should have found the executable")
    return null
}