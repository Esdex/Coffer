package me.esdex.coffer.feature.browser

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.ui.components.CofferScaffold
import me.esdex.coffer.core.ui.components.EmptyStateView
import me.esdex.coffer.core.ui.components.GroupedBox
import me.esdex.coffer.core.ui.components.GroupedRow
import me.esdex.coffer.core.ui.components.OperationLoading
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.ui.components.TypeTile
import me.esdex.coffer.core.ui.components.fileTypeIcon
import me.esdex.coffer.feature.browser.transfer.ShareInbox
import me.esdex.coffer.feature.browser.transfer.VaultContent
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val inbox: ShareInbox,
    private val content: VaultContent,
    index: VaultIndex,
) : ViewModel() {

    val state: StateFlow<ShareInbox.State> = inbox.state

    /** Folders only: the picker needs nothing else. Null while locked. */
    val tree: StateFlow<VaultTree?> = combine(index.observeFolders(), content.keys) { folders, keys ->
        keys ?: return@combine null
        VaultTree(folders.mapNotNull { f -> keys.local.openFolder(f.folderId, f.sealedMeta)?.let { f.folderId to it } }.toMap(), emptyMap())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(folderId: Id?, then: (Int) -> Unit) {
        val count = (inbox.state.value as? ShareInbox.State.Ready)?.items?.size ?: 0
        viewModelScope.launch {
            content.addShared(folderId)
            then(count)
        }
    }

    fun cancel() = inbox.discard()
}

/** Files shared to Coffer from another app: where to put them, then Add or Cancel. */
@Composable
fun ShareScreen(onDone: (added: Int) -> Unit, modifier: Modifier = Modifier, viewModel: ShareViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    // Ids are not Parcelable; the chosen folder survives rotation as its bytes.
    var atBytes by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    val at = atBytes?.let(Id::of)?.takeIf { tree?.folders?.containsKey(it) == true }
    val cancel = { viewModel.cancel(); onDone(0) }
    BackHandler(onBack = cancel)

    CofferScaffold(title = stringResource(R.string.share_title), onBack = cancel, modifier = modifier) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                ShareInbox.State.Empty -> EmptyStateView(stringResource(R.string.share_nothing), Modifier.fillMaxWidth().padding(vertical = 48.dp))
                is ShareInbox.State.Copying -> {
                    OperationLoading(stringResource(R.string.share_copying), Modifier.height(320.dp))
                    val total = s.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(progress = { s.done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is ShareInbox.State.Failed -> EmptyStateView(
                    stringResource(if (s.reason == ShareInbox.Reason.NO_SPACE) R.string.share_no_space else R.string.share_unreadable),
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                )
                is ShareInbox.State.Ready -> {
                    val shown = s.items.take(SHOWN)
                    SettingsGroup(pluralStringResource(R.plurals.share_count, s.items.size, s.items.size)) {
                        for (item in shown) {
                            row { shape ->
                                GroupedRow(
                                    shape,
                                    title = item.name,
                                    subtitle = Formatter.formatShortFileSize(context, item.size),
                                    leading = { TypeTile(fileTypeIcon(item.name)) },
                                )
                            }
                        }
                        if (s.items.size > SHOWN) {
                            row { shape -> GroupedRow(shape, title = pluralStringResource(R.plurals.share_more, s.items.size - SHOWN, s.items.size - SHOWN)) }
                        }
                    }
                    SettingsGroup(stringResource(R.string.share_where)) {
                        row { shape -> GroupedBox(shape) { tree?.let { FolderPicker(it, at, onAt = { id -> atBytes = id?.toByteArray() }) } } }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = cancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.browser_cancel)) }
                Button(
                    onClick = { viewModel.add(at, onDone) },
                    enabled = state is ShareInbox.State.Ready && tree != null,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.share_add)) }
            }
        }
    }
}

private const val SHOWN = 5
