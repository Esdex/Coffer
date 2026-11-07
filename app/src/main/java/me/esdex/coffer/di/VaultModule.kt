package me.esdex.coffer.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @Singleton
    fun vaultStore(@ApplicationContext context: Context) = VaultStore(File(context.noBackupFilesDir, "vault"))

    @Provides
    @Singleton
    fun vaultManager(sodium: Sodium, store: VaultStore) = VaultManager(sodium, store)

    @Provides
    @Singleton
    fun deviceUnlock(@ApplicationContext context: Context) = DeviceUnlock(context, File(context.noBackupFilesDir, "vault-device"))
}
