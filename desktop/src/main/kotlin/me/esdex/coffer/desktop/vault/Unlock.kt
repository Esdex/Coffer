package me.esdex.coffer.desktop.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.esdex.coffer.core.vault.VaultManager

/**
 * Unlocking the vault with its password, as on the phone; with "Remember on this computer" the vault opens
 * by itself when Coffer starts (Esdex, 2026-11-22). Making a vault is the phone's job for now.
 */
@Composable
fun Unlock(vault: DesktopVault, onDone: () -> Unit) {
    val state by vault.manager.state.collectAsState()
    val catchingUp by vault.catchingUp.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            VaultManager.State.Unknown, is VaultManager.State.Searching -> {
                CircularProgressIndicator()
                Title("Looking for your vault")
                Body("Checking Saved Messages for a Coffer vault.")
                (s as? VaultManager.State.Searching)?.let { Body("${it.scanned} messages checked") }
            }
            VaultManager.State.NotFound -> {
                Title("No vault yet")
                Body("This Telegram account has no Coffer vault in Saved Messages. Create one in Coffer on your phone, then come back.")
            }
            is VaultManager.State.Locked -> PasswordStep(vault, s)
            is VaultManager.State.Unlocked -> {
                Title("Unlocked")
                if (catchingUp != null) {
                    CircularProgressIndicator()
                    Body("Reading what changed: $catchingUp messages so far.")
                } else {
                    Body("Your vault is open on this computer.")
                }
                Button(onDone) { Text("Done") }
            }
        }
    }
}

@Composable
private fun PasswordStep(vault: DesktopVault, state: VaultManager.State.Locked) {
    val scope = rememberCoroutineScope()
    // Not kept anywhere else: a password does not outlive this screen.
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(vault.remembered) }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val submit = {
        if (!busy && password.isNotEmpty()) {
            busy = true
            wrong = false
            scope.launch {
                wrong = !vault.unlock(password, remember)
                if (!wrong) password = ""
                busy = false
            }
        }
    }
    Title("Unlock your vault")
    Body("Enter the vault password. It is not your Telegram password.")
    if (state.passwordChangedElsewhere) {
        Body("The vault password was changed on another device, so the vault was locked here. Unlock it with the new password.")
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Vault password") },
        singleLine = true,
        isError = wrong,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (wrong) Text("Wrong password.", color = MaterialTheme.colorScheme.error)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(remember, { remember = it }, enabled = !busy)
        Column {
            Text("Remember on this computer")
            Text(
                "The vault opens by itself when Coffer starts. The key is kept in the system keyring.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(submit, enabled = password.isNotEmpty() && !busy) { Text(if (busy) "Unlocking" else "Unlock") }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)
