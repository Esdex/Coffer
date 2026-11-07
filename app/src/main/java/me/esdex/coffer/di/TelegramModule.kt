package me.esdex.coffer.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.esdex.coffer.BuildConfig
import me.esdex.coffer.core.tdlib.ApiCredentials
import me.esdex.coffer.core.tdlib.Telegram
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {

    @Provides
    @Singleton
    fun telegram(@ApplicationContext context: Context): Telegram {
        // The personal build carries the keys from local.properties; the public one asks the user.
        val bundled = if (BuildConfig.HAS_BUNDLED_API_KEYS) ApiCredentials(BuildConfig.API_ID, BuildConfig.API_HASH) else null
        return Telegram(context, bundled, BuildConfig.VERSION_NAME)
    }
}
