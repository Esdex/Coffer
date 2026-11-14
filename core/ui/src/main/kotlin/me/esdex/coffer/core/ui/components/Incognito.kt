package me.esdex.coffer.core.ui.components

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Text fields in [content] ask the keyboard not to learn from what is typed: file and folder names, searches,
 * passwords, the recovery words and sign-in details would otherwise end up in its dictionary and its
 * suggestions, outside the vault (audit 1, L6). The keyboard is asked; a keyboard that ignores the flag
 * is not something an app can stop.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            val incognito = PlatformTextInputMethodRequest { outAttributes: EditorInfo ->
                val connection: InputConnection = request.createInputConnection(outAttributes)
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                connection
            }
            next.startInputMethod(incognito)
        },
        content = content,
    )
}
