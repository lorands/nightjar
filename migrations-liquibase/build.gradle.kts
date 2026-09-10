plugins {
    id("nightjar.kotlin-library")
    id("nightjar.published")
    id("nightjar.integration-test")
}

description = "Deploy-time schema execution for nightjar via Liquibase, aggregating changelogs discovered on the classpath."

dependencies {
    api(project(":migrations"))
    // The schema-execution engine. This module runs at DEPLOY time (init
    // container, CLI, CI step) — applications never carry Liquibase at runtime.
    api(libs.liquibase.core)

    testImplementation(libs.h2)

    "integrationTestImplementation"(libs.postgresql)
    // real module manifests on the IT classpath — the migrator discovers and applies them
    "integrationTestRuntimeOnly"(project(":coordination-jdbc"))
    "integrationTestRuntimeOnly"(project(":domain-event-jdbc"))
    "integrationTestRuntimeOnly"(project(":migrations-jdbc"))
}
