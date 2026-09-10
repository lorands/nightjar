plugins {
    id("nightjar.kotlin-library")
}

// Verification harness, not a published library
kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

dependencies {
    testImplementation(project(":nightjar-spring-boot-starter"))
    // Spring Boot 4 wins dependency resolution over the starter's 3.5 baseline —
    // these tests prove the starter works on the next Boot generation
    testImplementation(libs.spring.boot4.starter.test)
    testImplementation(libs.spring.boot4.starter.jdbc)
    testImplementation(libs.h2)
}
