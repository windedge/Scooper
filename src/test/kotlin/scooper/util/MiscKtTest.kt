package scooper.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.DosFileAttributes
import kotlin.io.path.isSymbolicLink


class MiscKtTest {
    @Test
    fun testDirSize() {
        val dir = File("C:\\ProgramData\\scoop\\apps\\gradle\\7.6")
        // println("dir.dirSize() = ${dir.dirSize()}")
        println("dir.dirSize() = ${dir.length()}")

        val clinkDir = File("c:\\Users\\xujl\\scoop\\apps\\clink\\1.4.16\\")
        println("clinkDir.toPath() = ${clinkDir.toPath().parent.toRealPath()}")
        println("clinkDir.toRealPath() = ${clinkDir.toPath().toRealPath().parent.toRealPath()}")
        println(
            "clinkDir.toRealPath(LinkOption.NOFOLLOW_LINKS) = ${
                clinkDir.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS)
            }"
        )
        assertTrue { clinkDir.dirSize() > 0 }
    }

    @Test
    fun testSymbolicDir() {
        val file = File("C:\\ProgramData\\scoop\\apps\\gradle\\8.0\\.gradle")
        println("parent.toPath() = ${file.toPath().parent.toRealPath()}")
        println("parent.toRealPath() = ${file.toPath().toRealPath().parent.toRealPath()}")
        println(
            "file.toRealPath(LinkOption.NOFOLLOW_LINKS) = ${
                file.toPath().toRealPath(LinkOption.NOFOLLOW_LINKS).parent.toRealPath()
            }"
        )

        assertTrue {
            file.dirSize() < 10L * 1024 * 1024 * 1024
        }
        // file.length()
        // val dosFileAttributes = Files.readAttributes(
        //     file.toPath(),
        //     DosFileAttributes::class.java,
        //     LinkOption.NOFOLLOW_LINKS
        // )
        // println("dosFileAttributes = ${dosFileAttributes}")
        // val isSymbolic = dosFileAttributes.isSymbolicLink
        // val isSymbolic2 = file.toPath().isSymbolicLink()
        // assertTrue(isSymbolic)
        // assertTrue(isSymbolic2)
    }

    @Test
    fun listFiles() {
        val file = File("C:\\ProgramData\\scoop\\apps\\gradle\\7.6\\")
        val paths = file.listFiles()!!.map { it.toPath() }
        /*
        paths.forEach {
            // val isSymbolic = Files.isSymbolicLink(it)
            // println("${it} isSymbolic = ${isSymbolic}")
            println("${it} it.toRealPath(LinkOption.NOFOLLOW_LINKS) = ${it.toRealPath()}")
        }
        */

        file.walkTopDown()
            .maxDepth(3)
            .onEnter { it.toPath() == it.toPath().toRealPath() }
            .onEach {
                println("it.toPath() = ${it.toPath()}, it.toPath().toRealPath() = ${it.toPath().toRealPath()}")
            }
            .filter { it.isFile }
            .map {
                it.length()
            }.sum().let { println("size: ${it.readableSize()}") }
    }


}