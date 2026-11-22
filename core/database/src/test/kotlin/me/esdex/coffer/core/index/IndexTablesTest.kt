package me.esdex.coffer.core.index

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * [RoomVaultIndex.clear] names the tables itself, as Room's clearAllTables did; checked against the schema
 * Room's compiler exported for the newest version, so a table added later cannot be left out of a clear.
 */
class IndexTablesTest {
    @Test
    fun clearNamesEveryTableOfTheNewestSchema() {
        val schemas = File("schemas/me.esdex.coffer.core.index.IndexDatabase")
        val newest = schemas.listFiles()!!.maxBy { it.nameWithoutExtension.toInt() }
        val tables = Regex(""""tableName": "(\w+)"""").findAll(newest.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(tables, INDEX_TABLES.toSet())
        assertEquals("every table once", INDEX_TABLES.size, INDEX_TABLES.toSet().size)
    }
}
