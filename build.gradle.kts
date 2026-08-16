import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.gettext)
    alias(libs.plugins.buildconfig)
}

group = "Scooper"
version = "1.2.4"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jitpack.io")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.orbit.core)

    // implementation("org.jetbrains.kotlinx:atomicfu:0.18.4")
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.sqlite.jdbc)

    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    implementation(libs.commons.text)
    implementation(libs.kotlin.process)
    implementation(libs.reorderable)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
//    implementation(libs.compose.dnd)

    implementation(libs.gettext)
    implementation(libs.kotlinx.io.core)

    // test
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {

        mainClass = "scooper.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            modules("java.instrument", "java.sql", "jdk.unsupported", "jdk.accessibility", "java.net.http")
            // includeAllModules = true
            packageName = group.toString()
            packageVersion = version.toString()
            vendor = "xujl"

            windows {
                menuGroup = group.toString()
                shortcut = true
                iconFile.set(project.file("icons/icon.ico"))
            }
        }

        buildTypes.release.proguard {
            configurationFiles.from("proguard-rules.pro")
        }

        jvmArgs += listOf("-Xmx256m", "-Xms64m", "-XX:+DisableExplicitGC")

    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
}

kotlin {
    jvmToolchain(17)
}

// tasks.withType<AbstractJLinkTask> {
//     setProperty("stripDebug\$compose", false)
// }

tasks.test {
    useJUnitPlatform()
}

// Strip non-Windows native libraries from sqlite-jdbc to save ~10MB in release
abstract class StripSqliteNativeLibs : DefaultTask() {
    @get:InputDirectory
    abstract val appDir: DirectoryProperty

    @TaskAction
    fun strip() {
        appDir.get().asFile.listFiles()?.filter {
            it.name.startsWith("sqlite-jdbc") && it.name.endsWith(".jar")
        }?.forEach { jarFile ->
            val stripped = File(jarFile.parentFile, jarFile.nameWithoutExtension + "-stripped.jar")
            ZipFile(jarFile).use { zip ->
                ZipOutputStream(stripped.outputStream()).use { out ->
                    zip.entries().asSequence()
                        .filter { entry: ZipEntry ->
                            val name = entry.name
                            !name.startsWith("org/sqlite/native/") ||
                                name.startsWith("org/sqlite/native/Windows/x86_64/")
                        }
                        .forEach { entry: ZipEntry ->
                            out.putNextEntry(ZipEntry(entry.name))
                            if (!entry.isDirectory) {
                                zip.getInputStream(entry).copyTo(out)
                            }
                            out.closeEntry()
                        }
                }
            }
            // On Windows, delete + rename may fail if file is still locked by the copy process
            // Use overwrite approach instead
            if (jarFile.delete()) {
                stripped.renameTo(jarFile)
            } else {
                jarFile.outputStream().use { out -> stripped.inputStream().use { it.copyTo(out) } }
                stripped.delete()
            }
            logger.lifecycle("Stripped sqlite-jdbc: ${File(appDir.get().asFile, jarFile.name).length() / 1024 / 1024}MB")
        }
        // Clean up any leftover stripped files
        appDir.get().asFile.listFiles()?.filter {
            it.name.contains("-stripped")
        }?.forEach { it.delete() }
    }
}

val stripSqliteNativeLibs by tasks.registering(StripSqliteNativeLibs::class) {
    appDir.set(layout.buildDirectory.dir("compose/binaries/main-release/app/Scooper/app"))
    outputs.upToDateWhen { false }
}

tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    finalizedBy(stripSqliteNativeLibs)
}

fun String.quoted() = "\"$this\""
gettext {
    potFile.set(File(projectDir, "src/main/resources/lang/messages.pot"))
    keywords.set(listOf("tr", "trn:1,2", "trc:1c,2"))
}

buildConfig {
    packageName("scooper.util")
    useKotlinOutput()
    // useKotlinOutput { topLevelConstants = true }
    buildConfigField("String", "APP_NAME", project.name.quoted())
    buildConfigField("String", "APP_VERSION", provider { "${project.version}".quoted() })
}