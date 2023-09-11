import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.10"
    kotlin("plugin.serialization") version "1.9.10"

    id("org.jetbrains.compose") version "1.5.1"
    id("com.github.gmazzo.buildconfig") version "3.1.0"
}

group = "Scooper"
version = "0.8.11"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    maven("https://jitpack.io")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("io.insert-koin:koin-core:3.4.3")
    implementation("org.orbit-mvi:orbit-core:4.5.0")
    // implementation("org.jetbrains.kotlinx:atomicfu:0.18.4")

    implementation("org.jetbrains.exposed:exposed-core:0.40.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.40.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.40.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.40.1")
    implementation("org.xerial:sqlite-jdbc:3.41.2.2")

    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("org.slf4j:slf4j-simple:2.0.5")

    implementation("org.apache.commons:commons-text:1.10.0")
    implementation("com.github.pgreze:kotlin-process:1.4.1")

    // test
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "scooper.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            modules("java.instrument", "java.sql", "jdk.unsupported")
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