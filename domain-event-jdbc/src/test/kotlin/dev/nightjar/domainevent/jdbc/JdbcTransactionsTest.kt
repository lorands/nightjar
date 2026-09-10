package dev.nightjar.domainevent.jdbc

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import javax.sql.DataSource

class JdbcTransactionsTest {

    private lateinit var dataSource: DataSource
    private lateinit var transactions: JdbcTransactions

    @BeforeTest
    fun setUp() {
        dataSource = H2Databases.create()
        transactions = JdbcTransactions(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE business_data (id INT PRIMARY KEY)") }
        }
    }

    private fun insertBusinessRow(id: Int) {
        val connection = checkNotNull(transactions.current()) { "no active transaction" }
        connection.prepareStatement("INSERT INTO business_data (id) VALUES (?)").use {
            it.setInt(1, id)
            it.executeUpdate()
        }
    }

    private fun countBusinessRows(): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM business_data").use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            }
        }

    @Test
    fun `commits on success`() {
        transactions.run { insertBusinessRow(1) }
        assertEquals(1, countBusinessRows())
    }

    @Test
    fun `rolls back on exception`() {
        assertFailsWith<IllegalStateException> {
            transactions.run {
                insertBusinessRow(1)
                throw IllegalStateException("business rule violated")
            }
        }
        assertEquals(0, countBusinessRows())
    }

    @Test
    fun `rejects nested transactions`() {
        transactions.run {
            assertFailsWith<IllegalStateException> { transactions.run { } }
        }
    }

    @Test
    fun `no transaction outside a run block`() {
        assertEquals(null, transactions.current())
    }
}
