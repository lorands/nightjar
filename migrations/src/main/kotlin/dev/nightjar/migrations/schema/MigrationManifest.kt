package dev.nightjar.migrations.schema

/**
 * A module's contribution of schema migrations, declared in a classpath
 * resource `META-INF/nightjar/migrations.properties`:
 *
 * ```properties
 * id=domain-event-jdbc
 * changelog=db/changelog/domain-event-jdbc-changelog.xml
 * order=100
 * ```
 *
 * The changelog is a standard Liquibase changelog that references the
 * module's pristine `.sql` files (`sqlFile`) — SQL is never transformed.
 * Execution is the host's concern: see `migrations-liquibase` or wire the
 * changelogs into your own Liquibase setup.
 */
public class MigrationManifest(
    /** Unique contributor id, conventionally the module name. */
    public val id: String,
    /** Classpath location of the module's Liquibase changelog. */
    public val changelog: String,
    /** Aggregation order — lower runs first. Default 100. */
    public val order: Int,
) : Comparable<MigrationManifest> {

    override fun compareTo(other: MigrationManifest): Int =
        compareValuesBy(this, other, { it.order }, { it.id })

    override fun equals(other: Any?): Boolean =
        this === other || (other is MigrationManifest && other.id == id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "MigrationManifest(id=$id, changelog=$changelog, order=$order)"
}
