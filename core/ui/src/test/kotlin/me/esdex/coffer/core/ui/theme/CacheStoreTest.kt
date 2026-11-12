package me.esdex.coffer.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun onAtTwoGigabytesAndKeptOverALock() {
        val prefs = CacheStore(File(temp.root, "cache.properties")).prefs.value
        assertEquals(CachePrefs(enabled = true, limitMb = 2048, clearOnLock = false, previews = true), prefs)
        assertEquals(2L * 1024 * 1024 * 1024, prefs.limitBytes)
        assertEquals(listOf(500, 1024, 2048, 5120, 10240), CachePrefs.LIMITS_MB)
    }

    @Test
    fun eachSettingIsKeptByItself() {
        val file = File(temp.root, "nested/cache.properties")
        val store = CacheStore(file)
        store.update { it.copy(limitMb = 500) }
        store.update { it.copy(clearOnLock = true) }
        val expected = CachePrefs(enabled = true, limitMb = 500, clearOnLock = true)
        assertEquals(expected, store.prefs.value)
        assertEquals(expected, CacheStore(file).prefs.value)
        val off = File(temp.newFolder(), "cache.properties")
        CacheStore(off).update { it.copy(enabled = false) }
        assertEquals(CachePrefs(enabled = false), CacheStore(off).prefs.value)
        val noPreviews = File(temp.newFolder(), "cache.properties")
        CacheStore(noPreviews).update { it.copy(previews = false) }
        assertEquals(CachePrefs(previews = false), CacheStore(noPreviews).prefs.value)
    }

    @Test
    fun aLimitNotOnOfferIsNotKept() {
        val file = File(temp.root, "cache.properties")
        CacheStore(file).update { it.copy(limitMb = 3000) }
        assertEquals(CachePrefs.DEFAULT_LIMIT_MB, CacheStore(file).prefs.value.limitMb)
        file.writeText("limitMb=7\nenabled=maybe\nclearOnLock=true\npreviews=no\n")
        assertEquals(CachePrefs(clearOnLock = true), CacheStore(file).prefs.value)
    }

    @Test
    fun everyLimitOnOfferReadsTheSameWholeOrSplit() {
        // Written out by hand, not worked out the way the code does it.
        val expected = listOf("500" to "MB", "1" to "GB", "2" to "GB", "5" to "GB", "10" to "GB")
        assertEquals(expected, CachePrefs.LIMITS_MB.map(CachePrefs::limitParts))
        assertEquals(listOf("500 MB", "1 GB", "2 GB", "5 GB", "10 GB"), CachePrefs.LIMITS_MB.map(CachePrefs::limitLabel))
    }
}
