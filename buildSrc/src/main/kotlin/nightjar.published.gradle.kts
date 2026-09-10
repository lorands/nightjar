/**
 * Applied by every module that ships to consumers. Adds `maven-publish`, a
 * sources jar and the POM metadata a published artifact needs (license, SCM,
 * project URL).
 *
 * Modules that are NOT published — `examples`, `native-smoke`,
 * `spring-boot-compat-check` — deliberately do not apply this, so they never
 * reach a release.
 *
 * Two repositories are wired:
 * - `mavenLocal` via the standard `publishToMavenLocal` task
 * - `staging`, a plain directory (`<root>/build/staging-repo`) that the release
 *   workflow packages into the GitHub release
 */

plugins {
    id("nightjar.kotlin-library")
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name = project.name
                url = "https://github.com/lorands/nightjar"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "lorands"
                        url = "https://github.com/lorands"
                    }
                }
                scm {
                    url = "https://github.com/lorands/nightjar"
                    connection = "scm:git:https://github.com/lorands/nightjar.git"
                    developerConnection = "scm:git:ssh://git@github.com/lorands/nightjar.git"
                }
            }
        }
    }

    repositories {
        // A directory repository the release workflow zips and attaches to the
        // GitHub release — no credentials, no external service.
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// `description` is set further down each module's build script, i.e. after this
// convention plugin has been applied — read it once the script has been evaluated.
afterEvaluate {
    publishing.publications.named<MavenPublication>("maven") {
        pom.description = project.description
    }
}
