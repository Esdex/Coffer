package me.esdex.coffer.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A window opened from the tray: sign-in, unlock. One of each at a time; closing it leaves Coffer running
 * in the tray, so it is an application of its own that ends with the window instead of the process.
 */
class AppWindow(private val content: @Composable (close: () -> Unit) -> Unit) {
    private val showing = AtomicBoolean(false)

    fun show() {
        if (!showing.compareAndSet(false, true)) return
        thread(name = "window") {
            try {
                application(exitProcessOnExit = false) {
                    Window(
                        onCloseRequest = ::exitApplication,
                        title = BuildInfo.variant.title,
                        state = rememberWindowState(width = 440.dp, height = 620.dp),
                    ) {
                        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                            Surface { content(::exitApplication) }
                        }
                    }
                }
            } finally {
                showing.set(false)
            }
        }
    }
}
