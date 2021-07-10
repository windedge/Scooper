package scooper.repository

import org.junit.jupiter.api.Test
import java.io.File


internal class ScoopTest {


    @OptIn(ExperimentalStdlibApi::class)
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
        val bucketDir = File("""D:\tools\scoop\buckets\main""")
        val url = Scoop.getBucketRepo(bucketDir)
        println("url = ${url}")
    }
}