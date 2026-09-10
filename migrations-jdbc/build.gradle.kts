plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
}

description = "Data-migration queues for nightjar over plain JDBC."

dependencies {
    api(project(":migrations"))
    // Reuses the TransactionalConnectionSource SPI + JdbcTransactions so one
    // transaction story covers outbox and data migrations alike
    api(project(":domain-event-jdbc"))

    testImplementation(libs.h2)
}
