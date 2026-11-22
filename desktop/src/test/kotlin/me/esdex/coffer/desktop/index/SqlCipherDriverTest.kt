package me.esdex.coffer.desktop.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.execSQL
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [SqlCipherDriver] against what SQLite says of itself: value types from its typeof(), transactions from
 * whether a VACUUM is refused. Room uses only part of the driver; this covers the rest.
 */
class SqlCipherDriverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var connection: SQLiteConnection

    @Before
    fun open() {
        connection = SqlCipherDriver(ByteArray(32) { 7 }).open(File(folder.root, "driver.db").path)
        connection.execSQL("CREATE TABLE t(a)")
    }

    @After
    fun close() = connection.close()

    @Test
    fun valuesComeBackWithTheirTypesAsSqliteSeesThem() {
        val sql = "SELECT 42 AS i, 1.5 AS f, 'text' AS s, x'0102' AS b, NULL AS n"
        val types = connection.prepare("SELECT typeof(42), typeof(1.5), typeof('text'), typeof(x'0102'), typeof(NULL)").use { s ->
            assertTrue(s.step())
            (0 until 5).map { s.getText(it) }
        }
        assertEquals(listOf("integer", "real", "text", "blob", "null"), types)
        connection.prepare(sql).use { s ->
            assertEquals("columns are known before the first step", 5, s.getColumnCount())
            assertEquals(listOf("i", "f", "s", "b", "n"), s.getColumnNames())
            assertTrue(s.step())
            assertEquals(types.map(::sqliteCode), (0 until 5).map { s.getColumnType(it) })
            assertEquals(42L, s.getLong(0))
            assertEquals(1.5, s.getDouble(1), 0.0)
            assertEquals("text", s.getText(2))
            assertArrayEquals(byteArrayOf(1, 2), s.getBlob(3))
            assertTrue(s.isNull(4))
            assertFalse(s.isNull(0))
            assertFalse("one row", s.step())
        }
    }

    @Test
    fun bindingsArriveWithTheirTypesAndCanBeClearedAndReused() {
        connection.prepare("SELECT typeof(?1), typeof(?2), typeof(?3), typeof(?4), typeof(?5), ?1, ?3").use { s ->
            s.bindLong(1, 5)
            s.bindDouble(2, 2.5)
            s.bindText(3, "x")
            s.bindBlob(4, byteArrayOf(9))
            s.bindNull(5)
            assertTrue(s.step())
            assertEquals(listOf("integer", "real", "text", "blob", "null"), (0 until 5).map { s.getText(it) })
            assertEquals(5L, s.getLong(5))
            assertEquals("x", s.getText(6))

            s.reset()
            s.bindLong(1, 6)
            assertTrue(s.step())
            assertEquals("bindings are kept by a reset", "x", s.getText(6))
            assertEquals(6L, s.getLong(5))

            s.reset()
            s.clearBindings()
            assertTrue(s.step())
            assertEquals("null", s.getText(0))
        }
    }

    @Test
    fun aStatementRunsAgainAfterAReset() {
        connection.prepare("INSERT INTO t VALUES (?)").use { s ->
            for (i in 1..3) {
                s.bindLong(1, i.toLong())
                assertFalse(s.step())
                s.reset()
            }
        }
        connection.prepare("SELECT count(*) FROM t").use { s ->
            assertTrue(s.step())
            assertEquals(3L, s.getLong(0))
        }
        // Nothing left running on the connection: VACUUM refuses otherwise.
        connection.execSQL("VACUUM")
    }

    @Test
    fun transactionsAreKnownAsSqliteKnowsThem() {
        assertFalse(connection.inTransaction())
        connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
        assertTrue(connection.inTransaction())
        assertSqliteAgrees()
        connection.execSQL("INSERT INTO t VALUES (1)")
        assertTrue(connection.inTransaction())
        connection.execSQL("END TRANSACTION")
        assertFalse(connection.inTransaction())
        assertSqliteAgrees()

        connection.execSQL("BEGIN DEFERRED TRANSACTION")
        connection.execSQL("ROLLBACK TRANSACTION")
        assertFalse(connection.inTransaction())

        // A transaction that only read ends with no commit of SQLite's to hear of.
        connection.execSQL("BEGIN DEFERRED TRANSACTION")
        connection.prepare("SELECT count(*) FROM t").use { it.step() }
        assertTrue(connection.inTransaction())
        connection.execSQL("END TRANSACTION")
        assertFalse(connection.inTransaction())
        assertSqliteAgrees()

        // A savepoint outside a transaction starts one; releasing it ends it.
        connection.execSQL("SAVEPOINT 'a'")
        assertTrue(connection.inTransaction())
        assertSqliteAgrees()
        connection.execSQL("SAVEPOINT 'b'")
        connection.execSQL("ROLLBACK TRANSACTION TO SAVEPOINT 'b'")
        assertTrue("rolling back to a savepoint keeps the transaction", connection.inTransaction())
        connection.execSQL("RELEASE SAVEPOINT 'a'")
        assertFalse(connection.inTransaction())
        assertSqliteAgrees()

        // Statements outside a transaction commit by themselves.
        connection.execSQL("INSERT INTO t VALUES (2)")
        assertFalse(connection.inTransaction())
    }

    @Test
    fun errorsAreSqliteExceptions() {
        assertThrows(SQLiteException::class.java) { connection.prepare("SELECT * FROM missing").use { it.step() } }
        assertThrows(SQLiteException::class.java) { connection.prepare("NOT SQL").use { it.step() } }
    }

    /**
     * SQLite refuses a VACUUM inside a transaction: an oracle for [SQLiteConnection.inTransaction], which
     * asks SQLite by a refused BEGIN instead. Outside one, the VACUUM just runs.
     */
    private fun assertSqliteAgrees() {
        val refused = try {
            connection.execSQL("VACUUM")
            false
        } catch (e: SQLiteException) {
            assertTrue(e.message, "within a transaction" in e.message.orEmpty())
            true
        }
        assertEquals("inTransaction against SQLite", refused, connection.inTransaction())
    }

    private fun sqliteCode(type: String) = when (type) {
        "integer" -> 1
        "real" -> 2
        "text" -> 3
        "blob" -> 4
        else -> 5
    }
}
