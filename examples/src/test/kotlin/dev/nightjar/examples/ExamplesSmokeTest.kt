package dev.nightjar.examples

import kotlin.test.Test

/**
 * Executes every runnable example so they are verified on each build —
 * examples that don't run are documentation that lies.
 * (The Spring/Quarkus wiring examples are compile-checked instead: they need
 * a running framework.)
 */
class ExamplesSmokeTest {

    @Test
    fun `kotlin quickstart runs`() {
        KotlinQuickstart.main(emptyArray())
    }

    @Test
    fun `java quickstart runs`() {
        JavaQuickstart.main(emptyArray())
    }

    @Test
    fun `jdbc outbox example runs`() {
        JdbcOutboxExample.main(emptyArray())
    }

    @Test
    fun `schema migration example runs`() {
        SchemaMigrationExample.main(emptyArray())
    }

    @Test
    fun `data migration example runs`() {
        DataMigrationExample.main(emptyArray())
    }

    @Test
    fun `scheduler example runs`() {
        SchedulerExample.main(emptyArray())
    }

    @Test
    fun `lock and worker example runs`() {
        LockAndWorkerExample.main(emptyArray())
    }
}
