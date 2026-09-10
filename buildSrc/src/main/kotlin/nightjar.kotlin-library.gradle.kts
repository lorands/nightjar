plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

// Maven coordinates. Verified on Maven Central via lorands.com (the com.lorands
// namespace covers every com.lorands.* subgroup). Kotlin packages stay
// dev.nightjar.* — package names and Maven coordinates are independent.
group = "com.lorands.nightjar"

// Release builds pass -Pversion=<semver>; the release workflow derives it from the
// git tag. Everything else builds as the snapshot.
version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

kotlin {
    // Library mode: every public declaration must state visibility and return type explicitly
    explicitApi()

    // Compatibility floor: Java 17. Compiling WITH a JDK 17 toolchain (not just
    // targeting 17 bytecode) guarantees no post-17 JDK APIs can leak into the
    // library, regardless of which JDK a contributor has installed.
    // Do not raise without a deliberate decision — consumers run Java 17+.
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
