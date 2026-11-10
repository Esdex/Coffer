package me.esdex.coffer.feature.browser

import me.esdex.coffer.core.crypto.Id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RowKeysTest {
    @Test
    fun idsThatShareTheirFirstBytesStillGetTheirOwnKeys() {
        // Id.toString shows the first four bytes only: these two print alike.
        val a = Id.of(ByteArray(16) { if (it == 15) 1 else 0 })
        val b = Id.of(ByteArray(16) { if (it == 15) 2 else 0 })
        assertEquals(a.toString(), b.toString())
        assertNotEquals(a.rowKey(), b.rowKey())
        assertEquals(a.rowKey(), Id.of(a.toByteArray()).rowKey())
    }
}
