package me.esdex.coffer.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {
    private val sodium = realSodium
    private val fast = Argon2Params(1, 8L * 1024 * 1024)

    @Test
    fun masterRoundTripThroughPassword() {
        val master = MasterKeys.generate(sodium)
        val salt = PasswordKdf.newSalt(sodium)
        val wrapped = MasterKeys.wrap(sodium, PasswordKdf.deriveKek(sodium, "pässword", salt, fast), master)
        assertEquals(CofferFormat.WRAPPED_KEY_BYTES, wrapped.size)

        val unwrapped = MasterKeys.unwrap(sodium, PasswordKdf.deriveKek(sodium, "pässword", salt, fast), wrapped)
        assertArrayEquals(master.withBytes { it.copyOf() }, unwrapped.withBytes { it.copyOf() })
    }

    @Test
    fun wrongPasswordOrSaltFails() {
        val master = MasterKeys.generate(sodium)
        val salt = PasswordKdf.newSalt(sodium)
        val wrapped = MasterKeys.wrap(sodium, PasswordKdf.deriveKek(sodium, "right", salt, fast), master)
        assertThrows(AuthenticationException::class.java) {
            MasterKeys.unwrap(sodium, PasswordKdf.deriveKek(sodium, "wrong", salt, fast), wrapped)
        }
        assertThrows(AuthenticationException::class.java) {
            MasterKeys.unwrap(sodium, PasswordKdf.deriveKek(sodium, "right", PasswordKdf.newSalt(sodium), fast), wrapped)
        }
    }

    @Test
    fun passwordIsNormalizedToNfc() {
        val salt = PasswordKdf.newSalt(sodium)
        val composed = PasswordKdf.deriveKek(sodium, "café", salt, fast).withBytes { it.copyOf() }
        val decomposed = PasswordKdf.deriveKek(sodium, "café", salt, fast).withBytes { it.copyOf() }
        assertArrayEquals(composed, decomposed)
    }

    @Test
    fun tamperedWrappedKeyFails() {
        val kek = MasterKeys.generate(sodium)
        val wrapped = MasterKeys.wrap(sodium, kek, MasterKeys.generate(sodium)).toByteArray()
        for (i in listOf(0, 23, 24, 55, 56, 71)) {
            assertThrows(AuthenticationException::class.java) { MasterKeys.unwrap(sodium, kek, Bytes.of(wrapped.flipped(i))) }
        }
        assertThrows(AuthenticationException::class.java) {
            MasterKeys.unwrap(sodium, kek, Bytes.of(wrapped.copyOf(71)))
        }
    }

    @Test
    fun wrappedMasterAndDekAreNotInterchangeable() {
        val kek = MasterKeys.generate(sodium)
        val fileId = Id.random(sodium)
        val asMaster = MasterKeys.wrap(sodium, kek, MasterKeys.generate(sodium))
        assertThrows(AuthenticationException::class.java) { FileKeys.unwrapDek(sodium, kek, asMaster, fileId) }
        val asDek = FileKeys.wrapDek(sodium, kek, FileKeys.generateDek(sodium), fileId)
        assertThrows(AuthenticationException::class.java) { MasterKeys.unwrap(sodium, kek, asDek) }
    }

    @Test
    fun dekIsBoundToItsFile() {
        val wrapKey = MasterKeys.generate(sodium)
        val fileId = Id.random(sodium)
        val dek = FileKeys.generateDek(sodium)
        val wrapped = FileKeys.wrapDek(sodium, wrapKey, dek, fileId)
        assertArrayEquals(dek.withBytes { it.copyOf() }, FileKeys.unwrapDek(sodium, wrapKey, wrapped, fileId).withBytes { it.copyOf() })
        assertThrows(AuthenticationException::class.java) { FileKeys.unwrapDek(sodium, wrapKey, wrapped, Id.random(sodium)) }
    }

    @Test
    fun subkeysDifferFromEachOtherAndFromMaster() {
        val master = MasterKeys.generate(sodium)
        KeyRing.derive(sodium, master).use { ring ->
            val all = listOf(master, ring.metaKey, ring.indexKey, ring.wrapKey).map { k -> k.withBytes { it.toHex() } }
            assertEquals(4, all.toSet().size)
        }
    }

    @Test
    fun masterIdDependsOnKey() {
        val a = MasterKeys.generate(sodium)
        assertEquals(MasterKeys.masterId(sodium, a), MasterKeys.masterId(sodium, a.copy()))
        assertNotEquals(MasterKeys.masterId(sodium, a), MasterKeys.masterId(sodium, MasterKeys.generate(sodium)))
    }

    @Test
    fun closeWipesKeys() {
        val raw = sodium.randomBytes(32)
        val k = SecretKey.adopt(raw)
        val ring = KeyRing.derive(sodium, k)
        ring.close()
        k.close()
        assertTrue(raw.all { it == 0.toByte() })
        assertTrue(ring.metaKey.isClosed && ring.indexKey.isClosed && ring.wrapKey.isClosed)
        assertThrows(IllegalStateException::class.java) { k.withBytes { } }
    }

    @Test
    fun argonParamsOutsideBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(0, 64L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(3, 4L * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { Argon2Params(3, 2048L * 1024 * 1024) }
    }
}
