plugins {
    id("nightjar.kotlin-library")
}

// Examples are not a published library: relax the library-mode compiler checks
kotlin {
    explicitApi = org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
}

dependencies {
    implementation(project(":coordination"))
    implementation(project(":coordination-jdbc"))
    implementation(project(":domain-event"))
    implementation(project(":domain-event-jdbc"))
    implementation(project(":domain-event-rabbitmq"))
    implementation(project(":migrations"))
    implementation(project(":migrations-jdbc"))
    implementation(project(":migrations-liquibase"))
    implementation(libs.h2) // for the runnable JDBC examples

    // Framework wiring examples compile against these APIs but never run them —
    // the libraries themselves stay framework-free
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.jdbc)
    compileOnly(libs.spring.tx)
    compileOnly(libs.cdi.api)
    compileOnly(libs.transaction.api)
}
