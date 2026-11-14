package me.esdex.coffer.core.tdlib

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound secrets TDLib needs before anyone unlocks the vault: its database key and, in the
 * public build, the API keys the user entered. Wrapped by an AES-256-GCM Keystore key that does
 * **not** require user authentication, so TDLib can start in the background (SPEC §8). The Master
 * Key gets its own Keystore key with authentication (SPEC §3.8), never this one.
 */
class DeviceSecrets(private val dir: File) {

    /** The key for TDLib's database, created on first use. Null if a stored one can no longer be unwrapped. */
    @Synchronized
    fun databaseKey(): ByteArray? {
        val file = File(dir, DB_KEY_FILE)
        if (file.exists()) return unwrap(file.readBytes(), DB_KEY_AD)
        val key = ByteArray(DB_KEY_BYTES).also(SecureRandom()::nextBytes)
        write(file, wrap(key, DB_KEY_AD))
        return key
    }

    /**
     * The key for Coffer's own index database, created on first use; as TDLib's, it opens without the
     * user, so transfers run in the background, and only on this phone (audit 1, M9). Null if a stored
     * one can no longer be unwrapped.
     */
    @Synchronized
    fun indexKey(): ByteArray? {
        val file = File(dir, INDEX_KEY_FILE)
        if (file.exists()) return unwrap(file.readBytes(), INDEX_KEY_AD)
        val key = ByteArray(DB_KEY_BYTES).also(SecureRandom()::nextBytes)
        write(file, wrap(key, INDEX_KEY_AD))
        return key
    }

    /** Forgets the index key, for when the index is wiped with it. */
    @Synchronized
    fun resetIndexKey() {
        File(dir, INDEX_KEY_FILE).delete()
    }

    /** Forgets the database key, for when TDLib's data is wiped with it. */
    @Synchronized
    fun resetDatabaseKey() {
        File(dir, DB_KEY_FILE).delete()
    }

    @Synchronized
    fun apiCredentials(): ApiCredentials? {
        val file = File(dir, API_FILE)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream()).use { input ->
                val apiId = input.readInt()
                val wrapped = input.readBytes()
                val hash = unwrap(wrapped, API_HASH_AD) ?: return null
                ApiCredentials(apiId, hash.toString(Charsets.US_ASCII))
            }
        }.getOrNull()
    }

    @Synchronized
    fun saveApiCredentials(credentials: ApiCredentials) {
        val wrapped = wrap(credentials.apiHash.toByteArray(Charsets.US_ASCII), API_HASH_AD)
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { it.writeInt(credentials.apiId); it.write(wrapped) }
        }.toByteArray()
        write(File(dir, API_FILE), bytes)
    }

    @Synchronized
    fun clearApiCredentials() {
        File(dir, API_FILE).delete()
    }

    private fun write(file: File, bytes: ByteArray) {
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(file)) { "could not store ${file.name}" }
    }

    // iv(12) || AES-GCM ciphertext and tag. The associated data ties each blob to its purpose.
    private fun wrap(plain: ByteArray, ad: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        cipher.updateAAD(ad.toByteArray(Charsets.US_ASCII))
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(wrapped: ByteArray, ad: String): ByteArray? = runCatching {
        val key = existingKeystoreKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped, 0, IV_BYTES))
        cipher.updateAAD(ad.toByteArray(Charsets.US_ASCII))
        cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }.getOrNull()

    private fun existingKeystoreKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey?
    }

    // One Keystore key for every instance: two made at once would each generate it, and the second would
    // replace the first, leaving what was wrapped with it unreadable.
    private fun keystoreKey(): SecretKey = synchronized(KEYSTORE_LOCK) { existingKeystoreKey() ?: newKeystoreKey() }

    private fun newKeystoreKey(): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "coffer.device"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val DB_KEY_BYTES = 32
        const val DB_KEY_FILE = "tdlib-db.key"
        const val API_FILE = "api.bin"
        const val DB_KEY_AD = "coffer/device/tdlib-db-key"
        const val INDEX_KEY_FILE = "index-db.key"
        const val INDEX_KEY_AD = "coffer/device/index-db-key"
        val KEYSTORE_LOCK = Any()
        const val API_HASH_AD = "coffer/device/api-hash"
    }
}
