plugins {
    id("nightjar.kotlin-library")
    application
    alias(libs.plugins.graalvm.native)
}

// Verification harness, not a published library
kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

application {
    mainClass = "dev.nightjar.nativesmoke.NativeSmoke"
}

dependencies {
    implementation(project(":coordination"))
    implementation(project(":domain-event"))
    implementation(project(":migrations"))
    // on the classpath for their migration manifests — proves the shipped
    // native-image resource metadata makes discovery work inside the binary
    runtimeOnly(project(":domain-event-jdbc"))
    runtimeOnly(project(":migrations-jdbc"))
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "nightjar-native-smoke"
            // set explicitly on the binary: with java-library + application
            // combined, the plugin doesn't inherit application defaults and
            // falls back to shared-library mode
            mainClass = "dev.nightjar.nativesmoke.NativeSmoke"
            sharedLibrary = false
            // GraalVM is provisioned by the toolchain machinery (foojay) —
            // contributors don't need it installed. Vendor pinned: plain
            // nativeImageCapable matching can resolve JDKs without native-image.
            javaLauncher = javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(25)
                vendor = JvmVendorSpec.GRAAL_VM
                nativeImageCapable = true
            }
        }
    }
}
