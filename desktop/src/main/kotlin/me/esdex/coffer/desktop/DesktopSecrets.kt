package me.esdex.coffer.desktop

import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.storage.DeviceSealer
import me.esdex.coffer.core.tdlib.ApiCredentials
import me.esdex.coffer.core.tdlib.TelegramSecrets
import me.esdex.coffer.desktop.keyring.SecretStore
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What the Android app keeps under its Keystore key, in the keyring: TDLib's database key, the index's key,
 * a key that seals small files beside the data (the vault's headers, audit 1, L9) and, if the user asks, the
 * vault's master key. The desktop signs in with Coffer's own API keys only (Esdex, 2026-11-22), so there are
 * none of the user's to keep.
 */
class DesktopSecrets(private val keyring: SecretStore) : TelegramSecrets, DeviceSealer {

    /** Read, or created and stored on first use. Null if what is stored is not a key: TDLib then starts clean. */
    @Synchronized
    override fun databaseKey(): ByteArray? = key(DATABASE_KEY, "Telegram database key")

    @Synchronized
    override fun resetDatabaseKey() = keyring.delete(DATABASE_KEY)

    override fun apiCredentials(): ApiCredentials? = null

    override fun saveApiCredentials(credentials: ApiCredentials) =
        throw UnsupportedOperationException("the desktop app signs in with Coffer's own API keys")

    override fun clearApiCredentials() = Unit

    /** The key of the index's database (audit 1, M9); null if what is stored is not a key. */
    @Synchronized
    fun indexKey(): ByteArray? = key(INDEX_KEY, "index key")

    @Synchronized
    fun resetIndexKey() = keyring.delete(INDEX_KEY)

    // iv(12) || AES-GCM ciphertext and tag, as the Android app seals its small files; the purpose is in the AD.
    override fun seal(plain: ByteArray, purpose: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sealKey())
        cipher.updateAAD((FILE_AD_PREFIX + purpose).toByteArray(Charsets.US_ASCII))
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray, purpose: String): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, sealKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD((FILE_AD_PREFIX + purpose).toByteArray(Charsets.US_ASCII))
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    @Synchronized
    private fun sealKey() = SecretKeySpec(checkNotNull(key(SEAL_KEY, "file sealing key")) { "the keyring holds no sealing key" }, "AES")

    /** The vault's master key, kept only if the user asked to be let in without the password. */
    @Synchronized
    fun rememberedMaster(): SecretKey? = keyring.read(MASTER_KEY)?.takeIf { it.size == KEY_BYTES }?.let(SecretKey::adopt)

    @Synchronized
    fun rememberMaster(master: SecretKey) = master.withBytes { keyring.write(MASTER_KEY, "vault key", it) }

    @Synchronized
    fun forgetMaster() = keyring.delete(MASTER_KEY)

    private fun key(name: String, label: String): ByteArray? {
        keyring.read(name)?.let { stored -> return stored.takeIf { it.size == KEY_BYTES } }
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        keyring.write(name, label, key)
        return key
    }

    private companion object {
        const val DATABASE_KEY = "tdlib-database-key"
        const val INDEX_KEY = "index-key"
        const val SEAL_KEY = "file-sealing-key"
        const val MASTER_KEY = "vault-master-key"
        const val KEY_BYTES = 32
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val FILE_AD_PREFIX = "coffer/device/file/"
    }
}
