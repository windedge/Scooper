import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.serialization)
    // alias(libs.plugins.gettext)
    alias(libs.plugins.buildconfig)
}

group = "Scooper"
version = "0.8.13"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jitpack.io")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)

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
//    implementation(libs.compose.dnd)

    // test
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {

        mainClass = "scooper.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            modules("java.instrument", "java.sql", "jdk.unsupported", "jdk.accessibility")
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

    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "11"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(11))
}

// tasks.withType<AbstractJLinkTask> {
//     setProperty("stripDebug\$compose", false)
// }

tasks.test {
    useJUnitPlatform()
}

fun String.quoted() = "\"$this\""
buildConfig {
    packageName("scooper.util")
    useKotlinOutput()
    // useKotlinOutput { topLevelConstants = true }
    buildConfigField("String", "APP_NAME", project.name.quoted())
    buildConfigField("String", "APP_VERSION", provider { "${project.version}".quoted() })
}