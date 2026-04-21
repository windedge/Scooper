package scooper.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.koin.core.time.measureDuration
import scooper.service.ScoopLogStream
import scooper.service.ScoopService
import scooper.util.execute
import java.io.File

internal class ScoopTest {

    private val scoopService = ScoopService(ScoopLogStream())

    @Test
    fun getApps() {
        val apps = scoopService.apps
        // println("apps = ${apps.joinToString("\n")}")
        // assertNotEquals(0, apps.size)
        // println("bucketsBaseDir = ${scoopService.bucketsBaseDir}")
        // println("bucketDirs = ${scoopService.bucketDirs}")
        // println("localInstalledApps = ${scoopService.localInstalledAppDirs.joinToString("\n")}")
        // println("globalInstalledApps = ${scoopService.globalInstalledAppDirs.joinToString("\n")}")
    }

    @Test
    fun testGetUrl() {
        var duration = measureDuration {
            val bucketDir = File("""D:\tools\scoop\buckets\main""")
            val url = scoopService.getBucketRepo(bucketDir)
            println("url = ${url}")
        }
        println("getBucketRepo, duration = ${duration}")

        duration = measureDuration {
            val bucketDir = File("""D:\tools\scoop\buckets\main""")
            val url = scoopService.getRepoUrl(bucketDir)
            println("url = ${url}")
        }
        println("getRepoUrl, duration = ${duration}")
    }

    @Test
    fun encoding() {
        println("System.getProperty(\"file.encoding\") = ${System.getProperty("file.encoding")}")
        val charset = charset(System.getProperty("file.encoding"))
        println("charset = ${charset}")
        runBlocking {
            execute("scoop", "update", "adb", asShell = true, charset = charset, consumer = { println(it) })
        }
    }
}
