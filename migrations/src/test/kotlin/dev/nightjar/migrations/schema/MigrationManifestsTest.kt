package dev.nightjar.migrations.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationManifestsTest {

    @Test
    fun `discovers manifests on the classpath`() {
        val manifests = MigrationManifests.discover(javaClass.classLoader)

        val testModule = manifests.single { it.id == "test-module" }
        assertEquals("db/changelog/test-module-changelog.xml", testModule.changelog)
        assertEquals(42, testModule.order)
    }

    @Test
    fun `sorts by order then id`() {
        val manifests = listOf(
            MigrationManifest("b-module", "b.xml", 100),
            MigrationManifest("a-module", "a.xml", 100),
            MigrationManifest("z-module", "z.xml", 1),
        ).sorted()

        assertEquals(listOf("z-module", "a-module", "b-module"), manifests.map { it.id })
    }

    @Test
    fun `discovered list is sorted`() {
        val manifests = MigrationManifests.discover(javaClass.classLoader)
        assertTrue(manifests == manifests.sorted())
    }
}
