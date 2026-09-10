plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
    id("nightjar.integration-test")
}

description = "Database-backed implementations of nightjar's process lock, worker pool and scheduler over plain JDBC."

dependencies {
    api(project(":coordination"))
    // TransactionalConnectionSource SPI + JdbcTransactions — one TX story project-wide
    api(project(":domain-event-jdbc"))

    testImplementation(libs.h2)

    "integrationTestImplementation"(libs.postgresql)
}
