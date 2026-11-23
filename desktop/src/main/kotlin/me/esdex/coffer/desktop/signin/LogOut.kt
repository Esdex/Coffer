package me.esdex.coffer.desktop.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.desktop.vault.DesktopVault
import me.esdex.coffer.desktop.vault.VaultWriter

/**
 * Logging out of Telegram on this computer, as Settings, Log out on the phone (Esdex, 2026-11-23): TDLib ends the
 * session and deletes its data, and the vault is forgotten with it ([DesktopVault]): its file list, the remembered
 * key, the files written and not yet sent. What is lost that way is said before, in numbers when the vault is open.
 */
@Composable
fun LogOut(telegram: Telegram, vault: DesktopVault, onDone: () -> Unit) {
    val transfers by vault.transfers.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Log out?", style = MaterialTheme.typography.headlineSmall)
        for (paragraph in logOutText(transfers, vault.hasUnsentFiles())) Text(paragraph, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onDone, enabled = !busy) { Text("Cancel") }
            Button(
                {
                    busy = true
                    scope.launch {
                        runCatching { telegram.logOut() }
                        onDone()
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(if (busy) "Logging out" else "Log out") }
        }
    }
}

/** What logging out does, and what it loses: [transfers] of an open vault, or [unsentFiles] of a locked one. */
internal fun logOutText(transfers: VaultWriter.Transfers, unsentFiles: Boolean): List<String> {
    val files = transfers.uploading.size + transfers.waiting
    val changes = transfers.operations
    val lost = listOfNotNull(
        files.takeIf { it > 0 }?.let { if (it == 1) "1 file" else "$it files" },
        changes.takeIf { it > 0 }?.let { if (it == 1) "1 change" else "$it changes" },
    )
    val warning = when {
        lost.isNotEmpty() -> "${lost.joinToString(" and ")} ${if (files + changes == 1) "has" else "have"} not reached Telegram yet and will be lost."
        unsentFiles -> "Files written on this computer that have not reached Telegram yet will be lost."
        else -> null
    }
    return listOfNotNull(
        "Coffer forgets the vault and its file list on this computer, and the drive goes. Your files stay in Saved Messages, and you can unlock them again after signing in.",
        warning,
    )
}
