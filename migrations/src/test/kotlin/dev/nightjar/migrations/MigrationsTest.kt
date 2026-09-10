package dev.nightjar.migrations

import kotlin.test.Test
import kotlin.test.assertNotNull

class MigrationsTest {

    @Test
    fun `module compiles and test infrastructure works`() {
        assertNotNull(Migrations)
    }
}
