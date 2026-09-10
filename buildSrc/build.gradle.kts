plugins {
    `kotlin-dsl`
}

java {
    // Build logic compiles for the daemon JVM by default (now Java 25), which
    // Gradle's embedded Kotlin can't target yet — pin to 17 to keep it stable
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
}
