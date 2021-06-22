package scooper.repository

import dorkbox.executor.Executor
import dorkbox.executor.listener.ProcessListener
import dorkbox.executor.processResults.ProcessResult
import dorkbox.executor.processResults.SyncProcessResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import scooper.data.App
import scooper.data.Bucket
import scooper.util.killTree
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.ZoneId

@Suppress("MemberVisibilityCanBePrivate")
object Scoop {
    private val logger = LoggerFactory.getLogger(javaClass)

    val rootDir: File
        get() {
            return File(System.getenv("USERPROFILE")).resolve("scoop")
        }

    val globalRootDir: File
        get() {
            return File(System.getenv("ALLUSERSPROFILE")).resolve("scoop")
        }

    val bucketsBaseDir: File
        get() {
            return rootDir.resolve("buckets")
        }

    val bucketNames: List<String>
        get() {
            return bucketsBaseDir.list()?.asList() ?: listOf()
        }

    val bucketDirs: List<File>
        get() {
            val buckets = mutableListOf<File>()
            for (bucketName in bucketNames) {
                buckets.add((bucketsBaseDir.resolve(bucketName)))
            }
            return buckets
        }

    val localInstalledAppDirs: List<File>
        get() {
            return rootDir
                .resolve("apps").listFiles { file -> file.isDirectory }
                ?.toList() ?: listOf()
        }

    val globalInstalledAppDirs: List<File>
        get() {
            return globalRootDir
                .resolve("apps").listFiles { file -> file.isDirectory }
                ?.toList() ?: listOf()
        }

    val apps: List<App>
        get() {
            val localInstallApps = localInstalledAppDirs.map { it.name.lowercase() }
            val globalInstalledApps = globalInstalledAppDirs.map { it.name.lowercase() }

            val all = mutableListOf<App>()
            for (bucketDir in bucketDirs) {
                val bucket = Bucket(name = bucketDir.name, url = "")
                val apps = bucketDir.resolve("bucket").listFiles()
                    // ?.take(10)
                    ?.map { file ->
                        parseManifest(file).apply {
                            this.version = this.latestVersion
                            this.bucket = bucket
                            val attrs = Files.readAttributes(
                                file.toPath(),
                                BasicFileAttributes::class.java
                            )
                            this.createAt = LocalDateTime.ofInstant(
                                attrs.creationTime().toInstant(),
                                ZoneId.systemDefault()
                            )
                            this.updateAt = LocalDateTime.ofInstant(
                                attrs.lastModifiedTime().toInstant(),
                                ZoneId.systemDefault()
                            )

                            if (globalInstalledApps.contains(name.lowercase())) {
                                this.global = true
                            }

                            if (globalInstalledApps.contains(name.lowercase())
                                || localInstallApps.contains(name.lowercase())
                            ) {
                                val version = (globalInstalledAppDirs + localInstalledAppDirs)
                                    .find { it.name.equals(name, ignoreCase = true) }!!
                                    .resolve("current").let {
                                        if (!it.exists()) {
                                            null
                                        } else {
                                            it.toPath().toRealPath().fileName.toString()
                                        }
                                    }
                                this.version = version
                                if (version == null) {
                                    this.status = "failed"
                                } else {
                                    this.status = "installed"
                                }
                            }
                        }
                    } ?: listOf()
                all.addAll(apps)
            }
            return all
        }

    private fun parseManifest(manifest: File): App {
        val json = Json.parseToJsonElement(manifest.readText()).jsonObject
        return json.run {
            App(
                name = manifest.nameWithoutExtension,
                latestVersion = getString("version"),
                homepage = getString("homepage"),
                description = getString("description"),
                url = getString("url"),
            )
        }
    }

    val processes = mutableMapOf<Long, Process>()
    val listener = object : ProcessListener() {
        override fun afterStart(process: Process, executor: Executor) {
            synchronized(processes) {
                processes.put(process.pid(), process)
            }
        }

        override fun afterFinish(process: Process, result: ProcessResult) {
            synchronized(processes) {
                processes.remove(process.pid())
            }
        }
    }

    fun update(onFinish: suspend () -> Unit = {}) {
        val commandArgs = mutableListOf("scoop", "update")
        val command = command(commandArgs, onFinish = onFinish)
        command.startAsShellAsync()
    }

    fun install(app: App, global: Boolean = false, onFinish: suspend () -> Unit = {}) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "install", "-g", app.name)
        } else {
            mutableListOf("scoop", "install", app.name)
        }
        val command = command(commandArgs, onFinish)
        command.startAsShellAsync()
    }

    fun uninstall(app: App, global: Boolean = false, onFinish: suspend () -> Unit = {}) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "uninstall", "-g", app.name)
        } else {
            mutableListOf("scoop", "uninstall", app.name)
        }
        val command = command(commandArgs, onFinish)
        command.startAsShellAsync()
    }

    fun upgrade(app: App, global: Boolean = false, onFinish: suspend () -> Unit = {}) {
        val commandArgs = if (global) {
            mutableListOf("sudo", "scoop", "update", "-g", app.name)
        } else {
            mutableListOf("scoop", "update", app.name)
        }
        val command = command(commandArgs, onFinish)
        command.startAsShellAsync()
    }

    fun stop() {
        for ((_, process) in processes) {
            process.killTree()
        }
    }

    private fun command(
        commandArgs: Iterable<String>,
        onFinish: suspend () -> Unit = {}
    ) = Executor(commandArgs).redirectErrorAsInfo().redirectOutputAsInfo()
        .addListener(listener)
        .addListener(object : ProcessListener() {
            override fun afterFinish(process: Process, result: ProcessResult) {
                runBlocking {
                    logger.info("execute finished.");
                    onFinish()
                    if (result is SyncProcessResult) {
                        logger.info("result.output.utf8() = " + result.output.utf8());
                    }
                }
            }
        })
}

fun JsonObject.getString(key: String): String {
    return getOrDefault(key, "").toString().removeSurrounding("\"")
}