package me.esdex.coffer.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.index.RoomVaultIndex
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.storage.DeviceSealer
import me.esdex.coffer.core.tdlib.DeviceSecrets
import me.esdex.coffer.core.ui.theme.AppearanceStore
import me.esdex.coffer.core.ui.theme.CacheStore
import me.esdex.coffer.core.ui.theme.PlayerStore
import me.esdex.coffer.core.ui.theme.TextStore
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun vaultStore(@ApplicationContext context: Context, sealer: DeviceSealer) = VaultStore(File(context.noBackupFilesDir, "vault"), sealer)

    /** The device-bound secrets beside TDLib's, under its Keystore key: the index key and sealed small files. */
    @Provides
    @Singleton
    fun deviceSecrets(@ApplicationContext context: Context) = DeviceSecrets(File(context.noBackupFilesDir, "secrets"))

    @Provides
    @Singleton
    fun deviceSealer(secrets: DeviceSecrets): DeviceSealer = secrets

    @Provides
    @Singleton
    fun vaultManager(sodium: Sodium, store: VaultStore) = VaultManager(sodium, store)

    @Provides
    @Singleton
    fun vaultIndex(@ApplicationContext context: Context, secrets: DeviceSecrets): VaultIndex {
        // Beside TDLib's key, under the same Keystore key (audit 1, M9).
        val key = secrets.indexKey() ?: run {
            // The Keystore lost its key: the index cannot be read any more. It is rebuilt from Saved Messages.
            context.deleteDatabase(INDEX_NAME)
            secrets.resetIndexKey()
            checkNotNull(secrets.indexKey()) { "no key for the index" }
        }
        return RoomVaultIndex(context, key, INDEX_NAME)
    }

    private const val INDEX_NAME = "index.db"

    // Outside the vault folder: signing out forgets the vault, not how the app looks.
    @Provides
    @Singleton
    fun appearanceStore(@ApplicationContext context: Context) = AppearanceStore(File(context.noBackupFilesDir, "appearance.properties"))

    @Provides
    @Singleton
    fun playerStore(@ApplicationContext context: Context) = PlayerStore(File(context.noBackupFilesDir, "player.properties"))

    @Provides
    @Singleton
    fun textStore(@ApplicationContext context: Context) = TextStore(File(context.noBackupFilesDir, "text.properties"))

    @Provides
    @Singleton
    fun cacheStore(@ApplicationContext context: Context) = CacheStore(File(context.noBackupFilesDir, "cache.properties"))

    @Provides
    @Singleton
    fun deviceUnlock(@ApplicationContext context: Context) = DeviceUnlock(context, File(context.noBackupFilesDir, "vault-device"))
}
