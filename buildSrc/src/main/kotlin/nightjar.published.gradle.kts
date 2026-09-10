/**
 * Applied by every module that ships to consumers. Adds `maven-publish`, a
 * sources jar and the POM metadata a published artifact needs (license, SCM,
 * project URL).
 *
 * Modules that are NOT published — `examples`, `native-smoke`,
 * `spring-boot-compat-check` — deliberately do not apply this, so they never
 * reach a release.
 *
 * Three repositories are wired:
 * - `mavenLocal` via the standard `publishToMavenLocal` task
 * - `staging`, a plain directory (`<root>/build/staging-repo`) that the release
 *   workflow packages into the GitHub release
 * - `githubPackages`, the GitHub Maven registry
 */

plugins {
    id("nightjar.kotlin-library")
    `maven-publish`
    signing
    // Javadoc-format API docs generated from the modules' KDoc
    id("org.jetbrains.dokka-javadoc")
}

java {
    withSourcesJar()
}

// Maven Central requires a javadoc jar alongside every non-pom artifact.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

// Maven Central requires a PGP signature (.asc) beside every published file.
// The release workflow supplies the key as ORG_GRADLE_PROJECT_signingKey /
// ...signingPassword; without them signing is skipped entirely, so ordinary
// builds, publishToMavenLocal and the staging publish are unaffected.
signing {
    // `filter` matters: CI passes the secret unconditionally, so on a run without
    // it the property is present-but-blank. Blank must count as absent, or the
    // build would try to sign with an empty key.
    val signingKey = providers.gradleProperty("signingKey").map(String::trim).filter(String::isNotEmpty)
    val signingPassword = providers.gradleProperty("signingPassword")
    isRequired = signingKey.isPresent
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.getOrElse(""))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)

            if (project.extensions.getByType<SigningExtension>().isRequired) {
                signing.sign(this)
            }

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
                        name = "Lorand Somogyi"
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

        // GitHub's Maven registry. Credentials come from the `githubPackages`
        // Gradle properties, which the release workflow supplies as
        // ORG_GRADLE_PROJECT_githubPackagesUsername / ...Password. Gradle only
        // demands them when a task publishing here is actually in the graph, so
        // every other build is unaffected.
        maven {
            name = "githubPackages"
            // Env var so a fork publishes to its own registry; the value is
            // owner/repo, e.g. lorands/nightjar.
            val slug = providers.environmentVariable("GITHUB_REPOSITORY").getOrElse("lorands/nightjar")
            url = uri("https://maven.pkg.github.com/$slug")
            credentials(PasswordCredentials::class)
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
