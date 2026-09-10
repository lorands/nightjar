package dev.nightjar.coordination.jdbc

import org.h2.jdbcx.JdbcDataSource
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Fresh in-memory H2 (PostgreSQL mode) with the coordination schema applied. */
object H2Databases {

    private val counter = AtomicInteger()

    fun create(): DataSource {
        val dataSource = JdbcDataSource()
        dataSource.setURL(
            "jdbc:h2:mem:nightjar-coord${counter.incrementAndGet()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                // executes the SHIPPED PostgreSQL DDL — it is portable to H2 PG-mode
                val ddl = checkNotNull(
                    javaClass.classLoader.getResourceAsStream("dev/nightjar/coordination/jdbc/coordination-postgresql.sql"),
                ).use { String(it.readAllBytes()) }
                statement.execute(ddl)
            }
        }
        return dataSource
    }
}
