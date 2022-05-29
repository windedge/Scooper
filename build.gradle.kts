import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    id("org.jetbrains.compose") version "1.1.1"
}

group = "Scooper"
version = "0.6.3"

repositories {
    jcenter()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0")
    implementation("io.insert-koin:koin-core:3.0.1-beta-2")
    implementation("org.orbit-mvi:orbit-core:4.1.3")

    implementation("org.jetbrains.exposed:exposed-core:0.32.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.32.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.32.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.32.1")
    // implementation("org.jetbrains.exposed:exposed-jodatime:0.30.1")
    implementation("org.xerial:sqlite-jdbc:3.30.1")

    implementation("org.slf4j:slf4j-api:1.8.0-beta4")
    implementation("org.slf4j:slf4j-simple:1.8.0-beta4")

    implementation("com.dorkbox:Executor:3.2") {
        // exclude("com.dorkbox", "Updates")
    }

    // test
    implementation("org.junit.jupiter:junit-jupiter:5.4.2")
    testImplementation(kotlin("test"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
    }
}

// tasks.withType<AbstractJLinkTask> {
//     setProperty("stripDebug\$compose", false)
// }

compose.desktop {
    application {
        mainClass = "scooper.MainKt"
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
                // console = true
                iconFile.set(project.file("icons/icon.ico"))
            }
        }
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "11"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "11"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(11))
}

tasks.test {
    useJUnitPlatform()
}
