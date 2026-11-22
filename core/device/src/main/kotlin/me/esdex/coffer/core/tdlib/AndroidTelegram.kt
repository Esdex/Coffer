package me.esdex.coffer.core.tdlib

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import java.io.File

/**
 * The app's [Telegram] on this phone: TDLib's data and secrets in no_backup, Coffer's lines about TDLib
 * in logcat, and a reconnect whenever Android hands the network back.
 */
fun androidTelegram(
    context: Context,
    bundledCredentials: ApiCredentials?,
    allowsOwnKeys: Boolean,
    applicationVersion: String,
): Telegram {
    TdlibLog.sink = TdlibLog.Sink { warning, tag, message -> if (warning) Log.w(tag, message) else Log.i(tag, message) }
    val telegram = Telegram(
        root = File(context.noBackupFilesDir, "tdlib"),
        secrets = DeviceSecrets(File(context.noBackupFilesDir, "secrets")),
        bundledCredentials = bundledCredentials,
        allowsOwnKeys = allowsOwnKeys,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        systemVersion = "Android ${Build.VERSION.RELEASE}",
        applicationVersion = applicationVersion,
    )
    // Android 15 cuts the network of an app a few seconds after it leaves the screen and gives it
    // back when it returns or runs a transfer; TDLib, meanwhile, backs off further with every
    // failed attempt. Seen 2026-11-09: sends waited 28 s to 7 min with the app on screen.
    context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = telegram.networkChanged()

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!blocked) telegram.networkChanged()
            }
        },
    )
    return telegram
}
