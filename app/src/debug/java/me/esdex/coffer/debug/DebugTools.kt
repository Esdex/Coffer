package me.esdex.coffer.debug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.core.vault.DeviceUnlock
import me.esdex.coffer.core.vault.VaultManager
import me.esdex.coffer.core.vault.VaultStore
import me.esdex.coffer.feature.browser.transfer.VaultContent
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DebugToolsViewModel @Inject constructor(
    private val telegram: Telegram,
    private val vault: VaultManager,
    private val store: VaultStore,
    private val device: DeviceUnlock,
    private val content: VaultContent,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val reloadFlow = MutableStateFlow<String?>(null)

    /** The result of the last reload, for checking snapshots by hand (SPEC §5.4). */
    val reload = reloadFlow.asStateFlow()

    fun reloadAsNewDevice() {
        reloadFlow.value = "Reading..."
        viewModelScope.launch {
            reloadFlow.value = runCatching { content.reloadAsNewDevice() }.fold(
                { report ->
                    if (report == null) "Vault is locked"
                    else "Snapshot used: ${if (report.snapshotUsed) "yes" else "no"}, messages read: ${report.messagesRead}"
                },
                { "Failed: ${it::class.simpleName}" },
            )
        }
    }

    /** For calibrating Argon2id on real phones (SPEC §3.3). */
    val lastKdfMillis: Long? get() = vault.lastKdfMillis

    val deviceUnlockEnabled: Boolean get() = device.isEnabled

    /** The offer comes once; this brings it back after the next password unlock. */
    fun offerDeviceUnlockAgain() {
        store.biometricOffered = false
    }


    sealed interface CheckState {
        data object Idle : CheckState
        data object Running : CheckState
        data class Done(val report: StorageSelfCheck.Report) : CheckState
        data class Crashed(val error: String) : CheckState
    }

    data class EventCounts(val newMessages: Int = 0, val deletions: Int = 0, val last: String = "")

    private val checkFlow = MutableStateFlow<CheckState>(CheckState.Idle)
    val check = checkFlow.asStateFlow()

    private val eventFlow = MutableStateFlow(EventCounts())
    val events = eventFlow.asStateFlow()

    init {
        // Lets the cross-device step be checked by hand: post or delete something in Saved
        // Messages on another device and watch the counters.
        viewModelScope.launch {
            runCatching {
                telegram.storage().events.collect { event ->
                    val counts = eventFlow.value
                    eventFlow.value = when (event) {
                        is StorageEvent.NewMessage -> counts.copy(newMessages = counts.newMessages + 1, last = "new ${event.message.id}")
                        is StorageEvent.MessagesDeleted -> counts.copy(deletions = counts.deletions + 1, last = "deleted ${event.ids.size}")
                    }
                }
            }
        }
    }

    fun runSelfCheck() {
        if (checkFlow.value == CheckState.Running) return
        checkFlow.value = CheckState.Running
        viewModelScope.launch {
            checkFlow.value = runCatching {
                StorageSelfCheck(telegram.storage(), File(context.cacheDir, "selfcheck")).run()
            }.fold({ CheckState.Done(it) }, { CheckState.Crashed(it.toString()) })
        }
    }
}

/** Debug builds only. */
@Composable
fun DebugTools(modifier: Modifier = Modifier, viewModel: DebugToolsViewModel = hiltViewModel()) {
    val check by viewModel.check.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Debug", style = MaterialTheme.typography.titleMedium)
        Text("Last password check: ${viewModel.lastKdfMillis?.let { "$it ms" } ?: "none yet"}")
        Text("Screen-lock unlock: ${if (viewModel.deviceUnlockEnabled) "on" else "off"}")
        OutlinedButton(onClick = viewModel::offerDeviceUnlockAgain) { Text("Offer screen-lock unlock again") }
        Text("Events from Telegram: new ${events.newMessages}, deletions ${events.deletions}  ${events.last}")
        val reload by viewModel.reload.collectAsStateWithLifecycle()
        OutlinedButton(onClick = viewModel::reloadAsNewDevice) { Text("Forget index, catch up as a new device") }
        reload?.let { Text(it) }
        Button(onClick = viewModel::runSelfCheck, enabled = check != DebugToolsViewModel.CheckState.Running) {
            Text("Run storage self-check")
        }
        when (val c = check) {
            DebugToolsViewModel.CheckState.Idle -> Unit
            DebugToolsViewModel.CheckState.Running -> CircularProgressIndicator()
            is DebugToolsViewModel.CheckState.Crashed -> Text("Self-check crashed: ${c.error}", color = MaterialTheme.colorScheme.error)
            is DebugToolsViewModel.CheckState.Done -> {
                val failed = c.report.results.count { it.failure != null }
                Text(
                    "Passed ${c.report.results.size - failed} of ${c.report.results.size}. " +
                        "Cleaned up ${c.report.deleted} messages, ${c.report.leftOver} left over.",
                    style = MaterialTheme.typography.titleSmall,
                )
                for (result in c.report.results) {
                    Text(
                        (if (result.failure == null) "ok   " else "FAIL ") + result.name + (result.failure?.let { "\n$it" } ?: ""),
                        color = if (result.failure == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
