package me.esdex.coffer.core.tdlib

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceSecretsTest {
    private val dir = File(TestDc.context.cacheDir, "secrets-test-${System.nanoTime()}")
    private val credentials = ApiCredentials(12345, "0123456789abcdef0123456789abcdef")

    @Test
    fun databaseKeyIsStable() {
        val key = DeviceSecrets(dir).databaseKey()!!
        assertEquals(32, key.size)
        assertArrayEquals(key, DeviceSecrets(dir).databaseKey())
        // Stored wrapped, not in the clear.
        val stored = File(dir, "tdlib-db.key").readBytes()
        assertFalse(stored.toList().windowed(32).any { it == key.toList() })

        DeviceSecrets(dir).resetDatabaseKey()
        assertFalse(key.contentEquals(DeviceSecrets(dir).databaseKey()))
    }

    @Test
    fun apiCredentialsRoundTrip() {
        val secrets = DeviceSecrets(dir)
        assertNull(secrets.apiCredentials())
        secrets.saveApiCredentials(credentials)
        assertEquals(credentials, DeviceSecrets(dir).apiCredentials())
        assertFalse(String(File(dir, "api.bin").readBytes(), Charsets.ISO_8859_1).contains(credentials.apiHash))
        secrets.clearApiCredentials()
        assertNull(secrets.apiCredentials())
    }

    @Test
    fun tamperedOrMisplacedSecretsDoNotOpen() {
        val secrets = DeviceSecrets(dir)
        secrets.databaseKey()
        val file = File(dir, "tdlib-db.key")
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(secrets.databaseKey())

        // A wrapped database key put where the API hash belongs fails its associated data check.
        secrets.resetDatabaseKey()
        secrets.databaseKey()
        val wrappedKey = File(dir, "tdlib-db.key").readBytes()
        File(dir, "api.bin").writeBytes(byteArrayOf(0, 0, 48, 57) + wrappedKey)
        assertNull(secrets.apiCredentials())
    }
}
