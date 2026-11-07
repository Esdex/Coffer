package me.esdex.coffer.di

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.crypto.sodium.Sodium
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun sodium(): Sodium = LazySodiumAdapter(LazySodiumAndroid(SodiumAndroid()))
}
