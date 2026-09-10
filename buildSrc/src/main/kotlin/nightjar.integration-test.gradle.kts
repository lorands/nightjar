/**
 * Adds an `integrationTest` source set + task for tests that need real
 * services (PostgreSQL, RabbitMQ — provided locally and in CI by devbox:
 * `devbox services up`).
 *
 * Deliberately NOT wired into `check`: `./gradlew build` stays hermetic.
 * The tests themselves skip via JUnit assumptions when a service is down,
 * so `./gradlew integrationTest` is always safe to invoke.
 */

plugins {
    id("nightjar.kotlin-library")
}

val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

tasks.register<Test>("integrationTest") {
    description = "Runs tests against real services (start them with: devbox services up)"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
    // services may be (re)started between runs — never skip as up-to-date
    outputs.upToDateWhen { false }
}
