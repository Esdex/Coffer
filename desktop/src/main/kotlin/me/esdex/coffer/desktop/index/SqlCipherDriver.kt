package me.esdex.coffer.desktop.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/**
 * Room's driver for the desktop's index: SQLite through JDBC, encrypted as SQLCipher 4 with a raw 32-byte
 * [key], the way Android's index is (audit 1, M9). SQLite3 Multiple Ciphers in sqlite-jdbc-crypt does the
 * work (Esdex, 2026-11-22). Room keeps its own pool of connections; each one opens keyed.
 */
class SqlCipherDriver(key: ByteArray) : SQLiteDriver {
    init {
        require(key.size == KEY_BYTES) { "the index key is $KEY_BYTES bytes" }
    }

    private val key = key.copyOf()

    override fun open(fileName: String): SQLiteConnection = wrap {
        val config = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build()
        // After each INSERT sqlite-jdbc would query the new row id and leave that query open, which SQLite
        // counts as running (VACUUM refused "SQL statements in progress"). Room reads ids its own way.
        config.setGetGeneratedKeys(false)
        JdbcConnection(config.createConnection("jdbc:sqlite:$fileName"))
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}

private class JdbcConnection(private val connection: Connection) : SQLiteConnection {
    /**
     * Room asks this when no transaction of its own is open, to find one opened by plain SQL. SQLite knows
     * (sqlite3_get_autocommit), but sqlite-jdbc does not pass it on, so SQLite is asked the way sqlite-jdbc's
     * own ensureAutoCommit asks it: a BEGIN is refused inside a transaction. A deferred BEGIN takes no lock,
     * and is rolled back at once.
     */
    override fun inTransaction(): Boolean = wrap {
        try {
            connection.createStatement().use { it.execute("BEGIN DEFERRED TRANSACTION") }
        } catch (e: SQLException) {
            if ("within a transaction" in e.message.orEmpty()) return@wrap true
            throw e
        }
        connection.createStatement().use { it.execute("ROLLBACK TRANSACTION") }
        false
    }

    override fun prepare(sql: String): SQLiteStatement = wrap { JdbcStatement(connection.prepareStatement(sql)) }

    override fun close() = wrap { connection.close() }
}

/**
 * One statement, stepped as SQLite's own: the first [step] runs it, the next ones move through its rows.
 * Bindings count from 1, columns from 0, as in androidx.sqlite.
 */
private class JdbcStatement(private val statement: PreparedStatement) : SQLiteStatement {
    private var rows: ResultSet? = null
    private var ran = false

    override fun bindBlob(index: Int, value: ByteArray) = wrap { statement.setBytes(index, value) }
    override fun bindDouble(index: Int, value: Double) = wrap { statement.setDouble(index, value) }
    override fun bindLong(index: Int, value: Long) = wrap { statement.setLong(index, value) }
    override fun bindText(index: Int, value: String) = wrap { statement.setString(index, value) }
    override fun bindNull(index: Int) = wrap { statement.setNull(index, Types.NULL) }

    override fun step(): Boolean = wrap {
        run()
        rows?.next() ?: false
    }

    /** Runs the statement once; its rows, if any, wait before the first. */
    private fun run() {
        if (ran) return
        ran = true
        rows = if (statement.execute()) statement.resultSet else null
    }

    override fun getBlob(index: Int): ByteArray = wrap { row().getBytes(index + 1) ?: ByteArray(0) }
    override fun getDouble(index: Int): Double = wrap { row().getDouble(index + 1) }
    override fun getLong(index: Int): Long = wrap { row().getLong(index + 1) }
    override fun getText(index: Int): String = wrap { row().getString(index + 1) ?: "" }
    override fun isNull(index: Int): Boolean = wrap { row().getObject(index + 1) == null }

    /** SQLite's own type codes, from the value in the current row. */
    override fun getColumnType(index: Int): Int = wrap {
        when (row().getObject(index + 1)) {
            null -> SQLITE_NULL
            is Long, is Int, is Short, is Byte, is Boolean -> SQLITE_INTEGER
            is Double, is Float -> SQLITE_FLOAT
            is ByteArray -> SQLITE_BLOB
            else -> SQLITE_TEXT
        }
    }

    // Room asks for the columns of a query before its first step; sqlite-jdbc describes them only once it ran.
    // Running a query early is harmless: its rows wait, and Room asks this of queries only.
    override fun getColumnCount(): Int = wrap {
        run()
        rows?.metaData?.columnCount ?: 0
    }

    override fun getColumnName(index: Int): String = wrap {
        run()
        rows?.metaData?.getColumnLabel(index + 1) ?: throw SQLiteException("the statement has no columns")
    }

    override fun reset() = wrap {
        rows?.close()
        rows = null
        ran = false
    }

    override fun clearBindings() = wrap { statement.clearParameters() }

    override fun close() = wrap {
        rows?.close()
        statement.close()
    }

    private fun row(): ResultSet = rows ?: throw SQLiteException("no row: the statement has not stepped onto one")

    private companion object {
        const val SQLITE_INTEGER = 1
        const val SQLITE_FLOAT = 2
        const val SQLITE_TEXT = 3
        const val SQLITE_BLOB = 4
        const val SQLITE_NULL = 5
    }
}

/** JDBC's errors as androidx.sqlite's, which Room expects. */
private inline fun <T> wrap(block: () -> T): T = try {
    block()
} catch (e: SQLException) {
    throw SQLiteException(e.message ?: "SQLite error").apply { initCause(e) }
}
