package me.esdex.coffer.core.tdlib

/**
 * What [Telegram] keeps sealed on the device, readable without the user so TDLib starts in the
 * background (SPEC §8): its database key and, in the public build, the API keys the user entered.
 */
interface TelegramSecrets {
    /** The key for TDLib's database, created on first use. Null if a stored one can no longer be opened. */
    fun databaseKey(): ByteArray?

    /** Forgets the database key, for when TDLib's data is wiped with it. */
    fun resetDatabaseKey()

    /** The keys the user entered, if any. */
    fun apiCredentials(): ApiCredentials?

    fun saveApiCredentials(credentials: ApiCredentials)

    fun clearApiCredentials()
}
