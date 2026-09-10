package dev.nightjar.migrations.liquibase

/**
 * CLI entry point for init containers and deploy steps:
 *
 * ```
 * java -cp <app-classpath> dev.nightjar.migrations.liquibase.Main \
 *     [jdbcUrl] [username] [password]
 * ```
 *
 * Arguments fall back to the environment variables `NIGHTJAR_DB_URL`,
 * `NIGHTJAR_DB_USERNAME`, `NIGHTJAR_DB_PASSWORD` (Cloud-Native: bind them
 * from your secret store / ConfigMap).
 */
public object Main {

    @JvmStatic
    public fun main(args: Array<String>) {
        val url = args.getOrNull(0) ?: System.getenv("NIGHTJAR_DB_URL")
            ?: error("Missing JDBC url: pass as first argument or set NIGHTJAR_DB_URL")
        val username = args.getOrNull(1) ?: System.getenv("NIGHTJAR_DB_USERNAME")
        val password = args.getOrNull(2) ?: System.getenv("NIGHTJAR_DB_PASSWORD")

        LiquibaseMigrator().migrate(url, username, password)
    }
}
