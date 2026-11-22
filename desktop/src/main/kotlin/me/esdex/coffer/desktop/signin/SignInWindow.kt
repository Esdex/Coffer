package me.esdex.coffer.desktop.signin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import me.esdex.coffer.core.tdlib.Telegram
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The sign-in window, opened from the tray. One at a time; closing it leaves Coffer running in the tray,
 * so it is an application of its own that ends with the window instead of the process.
 */
class SignInWindow(private val telegram: Telegram) {
    private val showing = AtomicBoolean(false)

    fun show() {
        if (!showing.compareAndSet(false, true)) return
        thread(name = "sign-in window") {
            try {
                application(exitProcessOnExit = false) {
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = "Coffer",
                        state = rememberWindowState(width = 440.dp, height = 620.dp),
                    ) {
                        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                            Surface { SignIn(telegram, onDone = ::exitApplication) }
                        }
                    }
                }
            } finally {
                showing.set(false)
            }
        }
    }
}
