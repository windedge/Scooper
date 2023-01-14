package scooper.repository

import org.junit.jupiter.api.Test
import org.koin.core.time.measureDuration
import scooper.util.Scoop
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.time.measureTime


internal class ScoopTest {

    @Test
    fun getApps() {
        val apps = Scoop.apps
        // println("apps = ${apps.joinToString("\n")}")
        // assertNotEquals(0, apps.size)

        // println("Scoop.bucketsBaseDir = ${Scoop.bucketsBaseDir}")
        // println("Scoop.bucketDirs = ${Scoop.bucketDirs}")
        // println("Scoop.localInstalledApps = ${Scoop.localInstalledAppDirs.joinToString("\n")}")
        // println("Scoop.globalInstalledApps = ${Scoop.globalInstalledAppDirs.joinToString("\n")}")
    }

    @Test
    fun testGetUrl() {
        var duration = measureDuration {
            val bucketDir = File("""D:\tools\scoop\buckets\main""")
            val url = Scoop.getBucketRepo(bucketDir)
            println("url = ${url}")
        }
        println("getBucketRepo, duration = ${duration}")

        duration = measureDuration {
            val bucketDir = File("""D:\tools\scoop\buckets\main""")
            val url = Scoop.getRepoUrl(bucketDir)
            println("url = ${url}")
        }
        println("getRepoUrl, duration = ${duration}")
    }
}