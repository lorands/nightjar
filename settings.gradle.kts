plugins {
    // Auto-provisions JDKs for the toolchain if the required one isn't installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nightjar"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include("coordination")
include("coordination-jdbc")
include("domain-event")
include("domain-event-jdbc")
include("domain-event-rabbitmq")
include("examples")
include("migrations")
include("migrations-jdbc")
include("migrations-liquibase")
include("native-smoke")
include("nightjar-spring-boot-starter")
include("spring-boot-compat-check")
