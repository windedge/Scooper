import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.31"
    kotlin("plugin.serialization") version "1.4.31"
    // id("org.jetbrains.compose") version "0.3.2"
    id("org.jetbrains.compose") version "0.4.0-build178"

    id("com.squareup.sqldelight") version "1.4.4"
}

group = "scooper"
version = "0.1.0"

repositories {
    google()
    jcenter()
    mavenCentral()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0")

    // implementation("moe.tlaster:precompose:0.1.0")
    implementation("moe.tlaster:precompose-desktop:0.1.1")

    implementation("com.squareup.sqldelight:gradle-plugin:1.4.4")
    implementation("com.squareup.sqldelight:sqlite-driver:1.4.4")
    // implementation("org.jetbrains.exposed:exposed-core:0.30.1")
    // implementation("org.jetbrains.exposed:exposed-dao:0.30.1")
    // implementation("org.jetbrains.exposed:exposed-jdbc:0.30.1")
    // implementation("org.jetbrains.exposed:exposed-jodatime:0.30.1")
    // implementation("org.xerial:sqlite-jdbc:3.30.1")
    // implementation("org.slf4j:slf4j-nop:1.7.30")
}

sqldelight {
    database("ScooperDatabase") {
        packageName = "scooper.database"
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        // jvmTarget = "11"
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
    }
}

compose.desktop {
    application {
        mainClass = "scooper.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "scooper"
            packageVersion = "1.0.0"
            vendor = "xujl"
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