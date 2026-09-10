package dev.nightjar.migrations.liquibase

import dev.nightjar.migrations.schema.MigrationManifest
import dev.nightjar.migrations.schema.MigrationManifests
import liquibase.Scope
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.resource.CompositeResourceAccessor
import liquibase.resource.DirectoryResourceAccessor
import java.lang.System.Logger.Level
import java.nio.file.Files
import java.sql.Connection

/**
 * Applies every schema migration discovered through nightjar migration
 * manifests (`META-INF/nightjar/migrations.properties`) using Liquibase.
 *
 * A synthetic master changelog `<include>`s the discovered changelogs in
 * manifest order; the changelogs reference each module's pristine `.sql`
 * files. Liquibase tracks execution in its standard `DATABASECHANGELOG`
 * table — re-running is a no-op.
 *
 * This is a DEPLOY-time tool (init container, CLI step, CI job — see [Main]);
 * applications never carry Liquibase at runtime.
 */
public class LiquibaseMigrator @JvmOverloads constructor(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) {

    private val log = System.getLogger(LiquibaseMigrator::class.java.name)

    /** Discover manifests and apply all changelogs to the database at [url]. */
    @JvmOverloads
    public fun migrate(url: String, username: String? = null, password: String? = null) {
        update { commandScope ->
            commandScope.addArgumentValue("url", url)
            username?.let { commandScope.addArgumentValue("username", it) }
            password?.let { commandScope.addArgumentValue("password", it) }
        }
    }

    /** Discover manifests and apply all changelogs over an existing JDBC [connection]. */
    public fun migrate(connection: Connection) {
        val database = DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(JdbcConnection(connection))
        update { commandScope ->
            commandScope.addArgumentValue("database", database)
        }
    }

    private fun update(configure: (CommandScope) -> Unit) {
        val manifests = MigrationManifests.discover(classLoader)
        check(manifests.isNotEmpty()) {
            "No nightjar migration manifests (${MigrationManifests.RESOURCE}) found on the classpath"
        }
        log.log(Level.INFO, "Applying schema migrations from: ${manifests.joinToString { it.id }}")

        // Synthetic master changelog on disk; module changelogs + SQL resolve
        // from the classpath through the composite accessor.
        val tempDir = Files.createTempDirectory("nightjar-migrations")
        val masterFile = tempDir.resolve(MASTER_CHANGELOG)
        Files.writeString(masterFile, masterChangelog(manifests))
        try {
            val resourceAccessor = CompositeResourceAccessor(
                DirectoryResourceAccessor(tempDir),
                ClassLoaderResourceAccessor(classLoader),
            )
            Scope.child(mapOf(Scope.Attr.resourceAccessor.name to resourceAccessor)) {
                val commandScope = CommandScope("update")
                commandScope.addArgumentValue("changelogFile", MASTER_CHANGELOG)
                configure(commandScope)
                commandScope.execute()
            }
        } finally {
            Files.deleteIfExists(masterFile)
            Files.deleteIfExists(tempDir)
        }
    }

    private fun masterChangelog(manifests: List<MigrationManifest>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"""" +
                """ xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""" +
                """ xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog""" +
                """ http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">""",
        )
        for (manifest in manifests) {
            appendLine("""    <include file="${manifest.changelog}"/>""")
        }
        appendLine("</databaseChangeLog>")
    }

    private companion object {
        const val MASTER_CHANGELOG = "nightjar-master-changelog.xml"
    }
}
