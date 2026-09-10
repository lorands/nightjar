plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
    id("nightjar.integration-test")
}

description = "Transactional-outbox persistence for nightjar domain events over plain JDBC."

dependencies {
    api(project(":domain-event"))

    testImplementation(libs.h2)

    "integrationTestImplementation"(libs.postgresql)
}
