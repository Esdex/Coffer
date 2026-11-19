package me.esdex.coffer.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.sync.Conflict
import me.esdex.coffer.core.sync.ConflictDecider
import me.esdex.coffer.core.sync.ConflictsCancelled
import me.esdex.coffer.core.sync.Decision
import me.esdex.coffer.core.sync.FileChoice
import me.esdex.coffer.core.sync.FolderChoice
import me.esdex.coffer.core.ui.components.AppDialog
import me.esdex.coffer.core.vault.NameConflictPolicy

/**
 * A question about a taken name (see [NameConflicts][me.esdex.coffer.core.sync.NameConflicts]) on its
 * way to the screen, and the answer the action waits for. One at a time: an action asks, then goes on.
 */
class ConflictPrompt {
    class Asking(val conflict: Conflict, internal val answer: CompletableDeferred<Decision<*>?>)

    private val askingFlow = MutableStateFlow<Asking?>(null)
    val asking: StateFlow<Asking?> = askingFlow.asStateFlow()

    /** Asks on screen, or answers from [policy] when the user chose a fixed answer in Settings. */
    fun decider(policy: NameConflictPolicy): ConflictDecider = when (policy) {
        NameConflictPolicy.KEEP_BOTH -> ConflictDecider.always(FileChoice.KEEP_BOTH, FolderChoice.KEEP_BOTH)
        NameConflictPolicy.SKIP -> ConflictDecider.always(FileChoice.SKIP, FolderChoice.SKIP)
        NameConflictPolicy.REPLACE -> ConflictDecider.always(FileChoice.REPLACE, FolderChoice.MERGE)
        NameConflictPolicy.ASK -> object : ConflictDecider {
            @Suppress("UNCHECKED_CAST")
            override suspend fun file(conflict: Conflict.File) = ask(conflict) as Decision<FileChoice>

            @Suppress("UNCHECKED_CAST")
            override suspend fun folder(conflict: Conflict.Folder) = ask(conflict) as Decision<FolderChoice>
        }
    }

    private suspend fun ask(conflict: Conflict): Decision<*> {
        val asking = Asking(conflict, CompletableDeferred())
        askingFlow.value = asking
        try {
            return asking.answer.await() ?: throw ConflictsCancelled()
        } finally {
            askingFlow.compareAndSet(asking, null)
        }
    }

    /** [decision] for the question on screen; null cancels the whole action. */
    fun answer(decision: Decision<*>?) {
        askingFlow.value?.answer?.complete(decision)
    }
}

/**
 * "Report.pdf is already in Docs": what to do with it, and with the rest of its kind if ticked. Leaving
 * the dialog cancels the whole action, nothing of it done.
 */
@Composable
fun NameConflictDialog(asking: ConflictPrompt.Asking, folderName: (Id?) -> String, onAnswer: (Decision<*>?) -> Unit) {
    val conflict = asking.conflict
    var forAll by remember(asking) { mutableStateOf(false) }
    val isFile = conflict is Conflict.File
    AppDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(stringResource(if (isFile) R.string.conflict_file_title else R.string.conflict_folder_title)) },
        text = {
            Column {
                Text(stringResource(R.string.conflict_text, conflict.name, folderName(conflict.folderId)))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().clickable { forAll = !forAll }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forAll, onCheckedChange = { forAll = it })
                    Text(
                        stringResource(if (isFile) R.string.conflict_for_all_files else R.string.conflict_for_all_folders),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (isFile) {
                    TextButton(onClick = { onAnswer(Decision(FileChoice.REPLACE, forAll)) }) { Text(stringResource(R.string.conflict_replace)) }
                    TextButton(onClick = { onAnswer(Decision(FileChoice.KEEP_BOTH, forAll)) }) { Text(stringResource(R.string.conflict_keep_both)) }
                    TextButton(onClick = { onAnswer(Decision(FileChoice.SKIP, forAll)) }) { Text(stringResource(R.string.conflict_skip)) }
                } else {
                    TextButton(onClick = { onAnswer(Decision(FolderChoice.MERGE, forAll)) }) { Text(stringResource(R.string.conflict_merge)) }
                    TextButton(onClick = { onAnswer(Decision(FolderChoice.KEEP_BOTH, forAll)) }) { Text(stringResource(R.string.conflict_keep_both)) }
                    TextButton(onClick = { onAnswer(Decision(FolderChoice.SKIP, forAll)) }) { Text(stringResource(R.string.conflict_skip)) }
                }
                TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.browser_cancel)) }
            }
        },
    )
}
