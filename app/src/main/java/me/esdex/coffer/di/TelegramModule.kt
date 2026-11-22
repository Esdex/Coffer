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
import me.esdex.coffer.core.tdlib.androidTelegram
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TelegramModule {

    @Provides
    @Singleton
    fun telegram(@ApplicationContext context: Context): Telegram {
        // Built-in keys come from local.properties; the public build also takes a user's own.
        val bundled = if (BuildConfig.HAS_BUNDLED_API_KEYS) ApiCredentials(BuildConfig.API_ID, BuildConfig.API_HASH) else null
        return androidTelegram(context, bundled, BuildConfig.ALLOWS_OWN_API_KEYS, BuildConfig.VERSION_NAME)
    }
}
