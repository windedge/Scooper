package scooper.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import scooper.data.App
import scooper.data.Bucket
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.ZoneId

@Suppress("MemberVisibilityCanBePrivate")
object Scoop {

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
                .resolve("apps").listFiles { file -> file.isDirectory and file.resolve("current").exists() }
                ?.toList() ?: listOf()
        }

    val globalInstalledAppDirs: List<File>
        get() {
            return globalRootDir
                .resolve("apps").listFiles { file -> file.isDirectory and file.resolve("current").exists() }
                ?.toList() ?: listOf()
        }

    val apps: List<App>
        get() {
            val localInstallApps = localInstalledAppDirs.map { it.name }
            val globalInstalledApps = globalInstalledAppDirs.map { it.name }

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

                            if (globalInstalledApps.contains(name)) {
                                this.global = true
                            }

                            if (globalInstalledApps.contains(name) || localInstallApps.contains(name)) {
                                this.installed = true
                                val version = (globalInstalledAppDirs + localInstalledAppDirs)
                                    .find { it.name == name }!!
                                    .resolve("current")
                                    .toPath().toRealPath().fileName.toString()

                                this.version = version
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
}

fun JsonObject.getString(key: String): String {
    return getOrDefault(key, "").toString().removeSurrounding("\"")
}