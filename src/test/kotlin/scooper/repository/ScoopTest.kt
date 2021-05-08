package scooper.repository

import org.junit.jupiter.api.Test


internal class ScoopTest {



    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun getApps() {
        val apps = Scoop.apps
        println("apps = ${apps.joinToString("\n")}")
        // assertNotEquals(0, apps.size)

        // println("Scoop.bucketsBaseDir = ${Scoop.bucketsBaseDir}")
        // println("Scoop.bucketDirs = ${Scoop.bucketDirs}")
        println("Scoop.localInstalledApps = ${Scoop.localInstalledAppDirs.joinToString("\n")}")
        println("Scoop.globalInstalledApps = ${Scoop.globalInstalledAppDirs.joinToString("\n")}")
    }
}