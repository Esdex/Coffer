package me.esdex.coffer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CofferApp : Application() {
    @Inject
    lateinit var vaultLifecycle: VaultLifecycle

    override fun onCreate() {
        super.onCreate()
        vaultLifecycle.start()
    }
}
