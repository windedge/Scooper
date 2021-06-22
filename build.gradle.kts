import org.jetbrains.compose.compose
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    kotlin("plugin.serialization") version "1.5.10"
    id("org.jetbrains.compose") version "0.5.0-build225"
}

group = "scooper"
version = "1.0.0"

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
    implementation("io.insert-koin:koin-core:3.0.1-beta-2")
    implementation("org.orbit-mvi:orbit-core:3.1.1")

    implementation("org.jetbrains.exposed:exposed-core:0.31.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.31.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.31.1")
    implementation("org.jetbrains.exposed:exposed-java-time:0.31.1")
    // implementation("org.jetbrains.exposed:exposed-jodatime:0.30.1")
    implementation("org.xerial:sqlite-jdbc:3.30.1")

    // implementation("org.slf4j:slf4j-nop:1.7.30")
    implementation("org.slf4j:slf4j-api:1.8.0-beta4")
    implementation("org.slf4j:slf4j-simple:1.8.0-beta4")
    // implementation("log4j:log4j:1.2.17")

    // implementation("com.lordcodes.turtle:turtle:0.5.0")
    implementation("com.dorkbox:Executor:3.2") {
        exclude("com.dorkbox:Updates")
    }

    //test
    implementation("org.junit.jupiter:junit-jupiter:5.4.2")
    testImplementation(kotlin("test"))
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
            targetFormats(TargetFormat.Msi)
            packageName = "Scooper"
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

tasks.test {
    useJUnitPlatform()
}
