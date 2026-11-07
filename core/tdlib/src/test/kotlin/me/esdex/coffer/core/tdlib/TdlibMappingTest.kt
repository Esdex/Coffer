package me.esdex.coffer.core.tdlib

import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageEvent
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TdlibMappingTest {
    private val chat = 777L
    private val a = 5L shl 20
    private val b = 6L shl 20

    private fun deleted(chatId: Long, ids: LongArray, permanent: Boolean, fromCache: Boolean) =
        TdApi.UpdateDeleteMessages(chatId, ids, permanent, fromCache)

    @Test
    fun onlyPermanentServerDeletionsCount() {
        assertEquals(
            StorageEvent.MessagesDeleted(setOf(MessageId(a), MessageId(b))),
            deleted(chat, longArrayOf(a, b), permanent = true, fromCache = false).toEvent(chat),
        )
        // TDLib dropping messages from its cache is not a deletion (SPEC §5.1, §11).
        assertNull(deleted(chat, longArrayOf(a), permanent = true, fromCache = true).toEvent(chat))
        assertNull(deleted(chat, longArrayOf(a), permanent = false, fromCache = false).toEvent(chat))
        assertNull(deleted(chat + 1, longArrayOf(a), permanent = true, fromCache = false).toEvent(chat))
        // A message that never reached the server (cancelled upload) is not in the log.
        assertNull(deleted(chat, longArrayOf(a + 1), permanent = true, fromCache = false).toEvent(chat))
    }

    @Test
    fun serverIdsHaveLowBitsClear() {
        assertTrue(isServerMessageId(1L shl 20))
        assertFalse(isServerMessageId((1L shl 20) + 1))
        assertFalse(isServerMessageId(0))
    }

    @Test
    fun floodWaitIsRecognized() {
        assertEquals(37, floodWaitSeconds(429, "Too Many Requests: retry after 37"))
        assertEquals(12, floodWaitSeconds(420, "FLOOD_WAIT_12"))
        assertNull(floodWaitSeconds(400, "PHONE_CODE_INVALID"))
        assertEquals(37, (TdApi.Error(429, "Too Many Requests: retry after 37").toException() as FloodWaitException).seconds)
        val other = TdApi.Error(400, "PHONE_CODE_INVALID").toException() as TdlibException
        assertEquals(400, other.code)
        assertEquals("PHONE_CODE_INVALID", other.message)
    }

    @Test
    fun apiCredentialsAreValidatedAndHashStaysHidden() {
        val hash = "0123456789abcdef0123456789ABCDEF"
        val parsed = ApiCredentials.parse(" 12345 ", " $hash ")!!
        assertEquals(12345, parsed.apiId)
        assertFalse(parsed.toString().contains(hash))
        assertNull(ApiCredentials.parse("0", hash))
        assertNull(ApiCredentials.parse("12a", hash))
        assertNull(ApiCredentials.parse("1", hash.drop(1)))
        assertNull(ApiCredentials.parse("1", hash.dropLast(1) + "g"))
    }
}
