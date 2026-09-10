plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
}

description = "Spring Boot auto-configuration for the full nightjar surface: domain events, coordination and migrations."

dependencies {
    // the starter brings the whole nightjar surface
    api(project(":coordination"))
    api(project(":coordination-jdbc"))
    api(project(":domain-event"))
    api(project(":domain-event-jdbc"))
    api(project(":migrations"))
    api(project(":migrations-jdbc"))

    // optional integrations: apps opt in by adding the dependency
    compileOnly(project(":domain-event-rabbitmq"))
    compileOnly(project(":migrations-liquibase"))

    // Spring Boot 3.5 baseline (Boot 4 verified by :spring-boot-compat-check)
    api(libs.spring.boot.autoconfigure)
    implementation(libs.spring.jdbc)
    implementation(libs.spring.tx)
    api(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(project(":domain-event-rabbitmq"))
    testImplementation(project(":migrations-liquibase"))
    testImplementation(libs.h2)
}
