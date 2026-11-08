package me.esdex.coffer.feature.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.VaultContent
import java.util.Date

/** The vault's files (SPEC §9). Folders, rename and move come with the operation log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onLock: () -> Unit,
    onLogOut: () -> Unit,
    modifier: Modifier = Modifier,
    /** Extra entries for the overflow menu, such as debug tools. */
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val files by viewModel.files.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sorting by viewModel.sorting.collectAsStateWithLifecycle()
    val catchingUp by viewModel.catchingUp.collectAsStateWithLifecycle()
    var showTransfers by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        askForNotifications()
        viewModel.upload(uris.map { uri -> context.uploadRequest(uri) })
    }

    // Decrypted files go straight to the app that can show them.
    LaunchedEffect(Unit) {
        viewModel.readyToOpen.collect { ready ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, ready.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    if (showTransfers) {
        TransfersScreen(transfers, onBack = { showTransfers = false }, onCancel = viewModel::cancel, onRetry = viewModel::retry, modifier = modifier)
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_title)) },
                actions = {
                    if (transfers.isNotEmpty()) {
                        TextButton(onClick = { showTransfers = true }) {
                            Text(stringResource(R.string.browser_transfers_count, transfers.size))
                        }
                    }
                    TextButton(onClick = { sortOpen = true }) { Text(stringResource(R.string.browser_sort)) }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        for (by in SortBy.entries) {
                            val arrow = if (sorting.by == by) (if (sorting.descending) " (desc)" else " (asc)") else ""
                            DropdownMenuItem(text = { Text(stringResource(by.label()) + arrow) }, onClick = { viewModel.sortBy(by); sortOpen = false })
                        }
                    }
                    TextButton(onClick = onLock) { Text(stringResource(R.string.browser_lock)) }
                    TextButton(onClick = { menuOpen = true }) { Text("...") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.browser_transfers)) }, onClick = { showTransfers = true; menuOpen = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.browser_log_out)) }, onClick = { menuOpen = false; onLogOut() })
                        extraMenu?.invoke { menuOpen = false }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.browser_add)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::search,
                placeholder = { Text(stringResource(R.string.browser_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            catchingUp?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    stringResource(R.string.browser_catching_up, it),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (query.isBlank()) R.string.browser_empty else R.string.browser_no_match),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.fileId.toByteArray().contentHashCode() }) { file ->
                        FileRow(
                            file = file,
                            onOpen = { askForNotifications(); viewModel.open(file.fileId) },
                            onExport = { uri -> askForNotifications(); viewModel.export(file.fileId, uri) },
                            onDelete = { viewModel.delete(file.fileId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun SortBy.label() = when (this) {
    SortBy.NAME -> R.string.browser_sort_name
    SortBy.DATE -> R.string.browser_sort_date
    SortBy.SIZE -> R.string.browser_sort_size
}

private fun Context.uploadRequest(uri: Uri): VaultContent.UploadRequest {
    // The read permission must outlive this screen: the upload may resume after a restart (SPEC §6).
    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
    return VaultContent.UploadRequest(uri, name, contentResolver.getType(uri))
}

@Composable
private fun FileRow(file: FileItem, onOpen: () -> Unit, onExport: (Uri) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        if (uri != null) onExport(uri)
    }
    val ready = file.status == FileStatus.READY
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { menu = true }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeBadge(file.name)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val details = Formatter.formatShortFileSize(context, file.size) + " · " + DateFormat.getMediumDateFormat(context).format(Date(file.modifiedAt))
                Text(details, style = MaterialTheme.typography.bodySmall)
                file.status.label()?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = if (file.status == FileStatus.DAMAGED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_open)) }, enabled = ready, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_export)) }, enabled = ready, onClick = { menu = false; export.launch(file.name) })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_delete)) }, enabled = file.status != FileStatus.UPLOADING, onClick = { menu = false; confirmDelete = true })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.browser_delete_title)) },
            text = { Text(stringResource(R.string.browser_delete_text, file.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.browser_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
}

private fun FileStatus.label() = when (this) {
    FileStatus.UPLOADING -> R.string.browser_status_uploading
    FileStatus.INCOMPLETE -> R.string.browser_status_incomplete
    FileStatus.DAMAGED -> R.string.browser_status_damaged
    FileStatus.READY -> null
}

/** The extension in a small box: a type hint without an icon set. */
@Composable
private fun TypeBadge(name: String) {
    val extension = name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }
    Box(
        Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(extension, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    transfers: List<TransferItem>,
    onBack: () -> Unit,
    onCancel: (TransferItem) -> Unit,
    onRetry: (TransferItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_transfers)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.browser_back)) } },
            )
        },
    ) { padding ->
        if (transfers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.browser_no_transfers))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(transfers, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            if (item.isUpload) R.string.browser_transfer_upload else R.string.browser_transfer_download,
                            item.name ?: stringResource(R.string.browser_transfer_unnamed),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val progress = item.progress
                    when {
                        item.failure != null -> Text(stringResource(item.failure.label()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        item.preparing -> Text(stringResource(R.string.browser_transfer_preparing), style = MaterialTheme.typography.bodySmall)
                        progress != null && progress.total > 0 -> LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                        else -> Text(stringResource(R.string.browser_transfer_waiting), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.failure != null) TextButton(onClick = { onRetry(item) }) { Text(stringResource(R.string.browser_retry)) }
                        TextButton(onClick = { onCancel(item) }) { Text(stringResource(R.string.browser_cancel)) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun TransferRegistry.Failure.label() = when (this) {
    TransferRegistry.Failure.NO_SPACE -> R.string.browser_failure_no_space
    TransferRegistry.Failure.SOURCE_CHANGED -> R.string.browser_failure_source_changed
    TransferRegistry.Failure.SOURCE_GONE -> R.string.browser_failure_source_gone
    TransferRegistry.Failure.CONTENT_MISMATCH -> R.string.browser_failure_mismatch
    TransferRegistry.Failure.MISSING_PART -> R.string.browser_failure_missing_part
    TransferRegistry.Failure.TARGET_FAILED -> R.string.browser_failure_target
}
