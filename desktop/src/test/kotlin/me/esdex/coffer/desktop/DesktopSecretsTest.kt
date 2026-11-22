package me.esdex.coffer.desktop

import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.desktop.keyring.SecretStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The keyring stands in as a map: these keys must never touch the real one. */
class DesktopSecretsTest {
    private class MapStore : SecretStore {
        val items = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = items[name]?.copyOf()
        override fun write(name: String, label: String, value: ByteArray) {
            items[name] = value.copyOf()
        }
        override fun delete(name: String) {
            items.remove(name)
        }
    }

    private val store = MapStore()
    private val secrets = DesktopSecrets(store)

    @Test
    fun sealedFilesOpenForTheirPurposeOnly() {
        val sealed = secrets.seal("headers text".toByteArray(), "headers")
        assertEquals("headers text", secrets.open(sealed, "headers")!!.decodeToString())
        assertNull(secrets.open(sealed, "other"))
        assertNull(secrets.open(sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }, "headers"))
        assertNull(secrets.open(ByteArray(12), "headers"))
        assertNotEquals("a fresh iv each time", sealed.toList(), secrets.seal("headers text".toByteArray(), "headers").toList())
    }

    /** Checked with the JDK's AES-GCM directly, from the key in the store and the layout the Android app uses. */
    @Test
    fun theSealIsIvThenAesGcmWithThePurposeInTheAd() {
        val sealed = secrets.seal(byteArrayOf(1, 2, 3), "headers")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(store.items.getValue("file-sealing-key"), "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD("coffer/device/file/headers".toByteArray())
        assertArrayEquals(byteArrayOf(1, 2, 3), cipher.doFinal(sealed, 12, sealed.size - 12))
    }

    @Test
    fun keysAreMadeOnceAndAReplacedOneIsALostOne() {
        val index = secrets.indexKey()!!
        assertArrayEquals(index, secrets.indexKey())
        assertEquals(32, index.size)
        assertFalse(index.contentEquals(secrets.databaseKey()))
        store.items["index-key"] = ByteArray(5)
        assertNull("not a key: the index starts again", secrets.indexKey())
        secrets.resetIndexKey()
        assertEquals(32, secrets.indexKey()!!.size)
    }

    @Test
    fun theMasterKeyIsKeptOnlyWhenRememberedAndGoesWhenForgotten() {
        assertNull(secrets.rememberedMaster())
        val bytes = ByteArray(32) { it.toByte() }
        secrets.rememberMaster(SecretKey.adopt(bytes.copyOf()))
        secrets.rememberedMaster()!!.use { it.withBytes { kept -> assertArrayEquals(bytes, kept) } }
        secrets.forgetMaster()
        assertNull(secrets.rememberedMaster())
        assertFalse(store.items.containsKey("vault-master-key"))
    }
}
