package me.esdex.coffer.desktop.signin

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.tdlib.AuthState
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.tdlib.TdlibException

/**
 * Signing the computer in to Telegram: by QR code from a phone where Telegram is signed in, then the cloud
 * password if the account has one. The phone-number way of the Android app is not here yet.
 */
@Composable
fun SignIn(telegram: Telegram, onDone: () -> Unit) {
    val state by telegram.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (e: FloodWaitException) {
                error = "Too many attempts. Telegram asks to wait ${formatWait(e.seconds)}."
            } catch (e: TdlibException) {
                error = errorText(e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    // The QR code is the only way in here, so it is asked for as soon as TDLib waits for a phone number.
    LaunchedEffect(state) {
        if (state == AuthState.WaitPhoneNumber) perform { telegram.requestQrCode() }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is AuthState.WaitQrConfirmation -> {
                Title("Scan with Telegram")
                Body("On a phone where you are signed in, open Telegram, go to Settings, Devices, Link Desktop Device, and scan this code.")
                // On a white card of its own in every theme; a new link from Telegram fades in over the old one.
                Box(Modifier.width(280.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)) {
                    Crossfade(s.link, label = "qr") { QrCode(it, Modifier.fillMaxWidth()) }
                }
            }
            is AuthState.WaitPassword -> PasswordStep(s, busy) { password -> perform { telegram.submitPassword(password) } }
            AuthState.Ready -> {
                var name by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { name = runCatching { telegram.accountName() }.getOrNull() }
                Title("Signed in")
                Body(name?.let { "This computer is signed in to Telegram as $it." } ?: "This computer is signed in to Telegram.")
                Button(onDone) { Text("Done") }
            }
            is AuthState.Failed -> {
                Title("Telegram could not start")
                Body("Error: ${s.error.message}")
                Button({ perform { telegram.resetTelegramData() } }, enabled = !busy) { Text("Reset Telegram data and try again") }
            }
            AuthState.NeedApiCredentials -> {
                Title("No Telegram API keys")
                Body("This build of Coffer has no Telegram API keys. Builds from the releases have them.")
            }
            is AuthState.WaitCode, AuthState.WaitEmailAddress, is AuthState.WaitEmailCode,
            AuthState.RegistrationRequired, AuthState.PremiumRequired,
            -> {
                Title("Not on the computer yet")
                Body("Telegram asks for a step the desktop app cannot do yet. Start over to sign in with a QR code.")
                Button({ perform { telegram.logOut() } }, enabled = !busy) { Text("Start over") }
            }
            AuthState.Starting, AuthState.WaitPhoneNumber, AuthState.LoggingOut, AuthState.Closed -> {
                CircularProgressIndicator()
                Body("Connecting to Telegram")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PasswordStep(state: AuthState.WaitPassword, busy: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Title("Two-step verification")
    Body("Your account is protected with a password. Enter your Telegram cloud password.")
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.hint.isNotBlank()) Body("Hint: ${state.hint}")
    Button({ onSubmit(password) }, enabled = password.isNotEmpty() && !busy) { Text("Continue") }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

/** The Android app's words for the errors sign-in can meet here (feature/onboarding loginErrorText). */
private fun errorText(code: String): String = when (code) {
    "PASSWORD_HASH_INVALID" -> "Wrong password."
    "API_ID_INVALID" -> "Telegram does not accept these API keys."
    "API_ID_PUBLISHED_FLOOD" -> "Telegram has blocked these API keys."
    else -> "Telegram error: $code"
}

private fun formatWait(seconds: Int): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3600 -> "${(seconds + 59) / 60} min"
    else -> "${(seconds + 3599) / 3600} h"
}
