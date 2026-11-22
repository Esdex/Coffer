package me.esdex.coffer.desktop

import me.esdex.coffer.core.tdlib.ApiCredentials
import me.esdex.coffer.core.tdlib.TelegramSecrets
import me.esdex.coffer.desktop.keyring.Keyring
import java.security.SecureRandom

/**
 * TDLib's database key in the keyring, as the Android app keeps it under a Keystore key. The desktop
 * signs in with Coffer's own API keys only (Esdex, 2026-11-22), so there are none of the user's to keep.
 */
class DesktopSecrets(private val keyring: Keyring) : TelegramSecrets {

    /** Read, or created and stored on first use. Null if what is stored is not a key: TDLib then starts clean. */
    @Synchronized
    override fun databaseKey(): ByteArray? {
        keyring.read(DATABASE_KEY)?.let { stored -> return stored.takeIf { it.size == DATABASE_KEY_BYTES } }
        val key = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        keyring.write(DATABASE_KEY, "Telegram database key", key)
        return key
    }

    @Synchronized
    override fun resetDatabaseKey() = keyring.delete(DATABASE_KEY)

    override fun apiCredentials(): ApiCredentials? = null

    override fun saveApiCredentials(credentials: ApiCredentials) =
        throw UnsupportedOperationException("the desktop app signs in with Coffer's own API keys")

    override fun clearApiCredentials() = Unit

    private companion object {
        const val DATABASE_KEY = "tdlib-database-key"
        const val DATABASE_KEY_BYTES = 32
    }
}
