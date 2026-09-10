package dev.nightjar.migrations.schema

import java.util.Properties

/**
 * Discovers [MigrationManifest]s on the classpath — every module (nightjar's
 * or yours) that ships `META-INF/nightjar/migrations.properties` contributes
 * its schema to the host application.
 */
public object MigrationManifests {

    public const val RESOURCE: String = "META-INF/nightjar/migrations.properties"

    /** All manifests visible to [classLoader], sorted by order then id. */
    @JvmStatic
    @JvmOverloads
    public fun discover(
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    ): List<MigrationManifest> =
        classLoader.getResources(RESOURCE).asSequence()
            .map { url ->
                val properties = Properties()
                url.openStream().use(properties::load)
                MigrationManifest(
                    id = properties.requireProperty("id", url.toString()),
                    changelog = properties.requireProperty("changelog", url.toString()),
                    order = properties.getProperty("order", "100").trim().toInt(),
                )
            }
            .distinct() // the same artifact may appear twice on messy classpaths
            .sorted()
            .toList()

    private fun Properties.requireProperty(key: String, source: String): String =
        getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Missing '$key' in migration manifest $source")
}
