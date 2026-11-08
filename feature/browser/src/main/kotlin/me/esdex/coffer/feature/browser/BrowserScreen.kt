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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.VaultContent
import java.util.Date

/** Something a dialog acts on: a folder or a file. */
private sealed interface Target {
    val name: String

    data class Folder(val item: FolderItem) : Target {
        override val name get() = item.name
    }

    data class File(val item: FileItem) : Target {
        override val name get() = item.name
    }
}

/** The vault's folders and files (SPEC §9). */
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
    val resources = LocalResources.current
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sorting by viewModel.sorting.collectAsStateWithLifecycle()
    val catchingUp by viewModel.catchingUp.collectAsStateWithLifecycle()
    var showTransfers by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Target?>(null) }
    var moving by remember { mutableStateOf<Target?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderItem?>(null) }
    var confirmRescan by remember { mutableStateOf(false) }
    val integrity by viewModel.integrity.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        askForNotifications()
        // Into the folder on screen when the picker returns.
        val folderId = viewModel.listing.value.folderId
        viewModel.upload(uris.map { uri -> context.uploadRequest(uri, folderId) })
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

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(resources.getString(it)) }
    }

    if (showTransfers) {
        TransfersScreen(
            transfers,
            onBack = { showTransfers = false },
            onCancel = viewModel::cancel,
            onRetry = viewModel::retry,
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbar) },
        )
        return
    }

    BackHandler(enabled = listing.folderId != null && query.isBlank()) { viewModel.up() }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        listing.path.lastOrNull()?.name ?: stringResource(R.string.browser_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (listing.folderId != null) TextButton(onClick = { viewModel.up() }) { Text(stringResource(R.string.browser_back)) }
                },
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_check)) },
                            enabled = catchingUp == null,
                            onClick = { menuOpen = false; viewModel.checkIntegrity() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_rescan)) },
                            enabled = catchingUp == null,
                            onClick = { menuOpen = false; confirmRescan = true },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.browser_log_out)) }, onClick = { menuOpen = false; onLogOut() })
                        extraMenu?.invoke { menuOpen = false }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(onClick = { creatingFolder = true }) {
                    Text(stringResource(R.string.browser_new_folder), modifier = Modifier.padding(horizontal = 12.dp))
                }
                ExtendedFloatingActionButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.browser_add)) }
            }
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
            if (listing.folders.isEmpty() && listing.files.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    val empty = when {
                        query.isNotBlank() -> R.string.browser_no_match
                        listing.folderId != null -> R.string.browser_folder_empty
                        else -> R.string.browser_empty
                    }
                    Text(stringResource(empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(listing.folders, key = { "d" + it.folderId.toByteArray().contentHashCode() }) { folder ->
                        FolderRow(
                            folder = folder,
                            onOpen = { viewModel.openFolder(folder.folderId) },
                            onRename = { renaming = Target.Folder(folder) },
                            onMove = { moving = Target.Folder(folder) },
                            onDelete = { deletingFolder = folder },
                        )
                        HorizontalDivider()
                    }
                    items(listing.files, key = { "f" + it.fileId.toByteArray().contentHashCode() }) { file ->
                        FileRow(
                            file = file,
                            onOpen = { askForNotifications(); viewModel.open(file.fileId) },
                            onExport = { uri -> askForNotifications(); viewModel.export(file.fileId, uri) },
                            onRename = { renaming = Target.File(file) },
                            onMove = { moving = Target.File(file) },
                            onDelete = { viewModel.delete(file.fileId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (creatingFolder) {
        NameDialog(
            title = R.string.browser_new_folder_title,
            confirm = R.string.browser_create,
            initial = "",
            onConfirm = { creatingFolder = false; viewModel.createFolder(it) },
            onDismiss = { creatingFolder = false },
        )
    }
    renaming?.let { target ->
        NameDialog(
            title = R.string.browser_rename_title,
            confirm = R.string.browser_rename,
            initial = target.name,
            onConfirm = { name ->
                renaming = null
                when (target) {
                    is Target.Folder -> viewModel.renameFolder(target.item.folderId, name)
                    is Target.File -> viewModel.renameFile(target.item.fileId, name)
                }
            },
            onDismiss = { renaming = null },
        )
    }
    val currentTree = tree
    moving?.let { target ->
        if (currentTree == null) return@let
        MoveDialog(
            tree = currentTree,
            target = target,
            onMove = { destination ->
                moving = null
                when (target) {
                    is Target.Folder -> viewModel.moveFolder(target.item.folderId, destination)
                    is Target.File -> viewModel.moveFile(target.item.fileId, destination)
                }
            },
            onDismiss = { moving = null },
        )
    }
    integrity?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::dismissIntegrity,
            title = { Text(stringResource(R.string.browser_check_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.browser_check_result,
                        report.messagesRead,
                        report.damaged,
                        report.repaired,
                        report.garbageDeleted,
                        report.snapshotsDeleted,
                        report.operationsCompacted,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = viewModel::dismissIntegrity) { Text(stringResource(R.string.browser_ok)) } },
        )
    }
    if (confirmRescan) {
        AlertDialog(
            onDismissRequest = { confirmRescan = false },
            title = { Text(stringResource(R.string.browser_rescan_title)) },
            text = { Text(stringResource(R.string.browser_rescan_text)) },
            confirmButton = { TextButton(onClick = { confirmRescan = false; viewModel.rescan() }) { Text(stringResource(R.string.browser_rescan_start)) } },
            dismissButton = { TextButton(onClick = { confirmRescan = false }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
    deletingFolder?.let { folder ->
        val (folders, files) = currentTree?.subtree(folder.folderId) ?: (emptyList<Id>() to emptyList())
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            title = { Text(stringResource(R.string.browser_delete_folder_title)) },
            text = {
                val subfolders = (folders.size - 1).coerceAtLeast(0)
                Text(
                    if (files.isEmpty() && subfolders == 0) stringResource(R.string.browser_delete_folder_empty, folder.name)
                    else stringResource(R.string.browser_delete_folder_text, folder.name, files.size, subfolders),
                )
            },
            confirmButton = {
                TextButton(onClick = { deletingFolder = null; viewModel.deleteFolder(folder.folderId) }) { Text(stringResource(R.string.browser_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
}

private fun SortBy.label() = when (this) {
    SortBy.NAME -> R.string.browser_sort_name
    SortBy.DATE -> R.string.browser_sort_date
    SortBy.SIZE -> R.string.browser_sort_size
}

private fun Context.uploadRequest(uri: Uri, folderId: Id?): VaultContent.UploadRequest {
    // The read permission must outlive this screen: the upload may resume after a restart (SPEC §6).
    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
    return VaultContent.UploadRequest(uri, name, contentResolver.getType(uri), folderId)
}

/** A name for a new folder or a rename, held to the format's limits (SPEC §4.0). */
@Composable
private fun NameDialog(title: Int, confirm: Int, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && FileNames.isValid(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                isError = trimmed.isNotEmpty() && !valid,
                supportingText = { if (trimmed.isNotEmpty() && !valid) Text(stringResource(R.string.browser_name_too_long)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = valid && trimmed != initial) { Text(stringResource(confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

/** Picks a destination folder by walking the tree; a folder cannot go into itself or below itself. */
@Composable
private fun MoveDialog(tree: VaultTree, target: Target, onMove: (Id?) -> Unit, onDismiss: () -> Unit) {
    val movingFolder = (target as? Target.Folder)?.item?.folderId
    val from = when (target) {
        is Target.Folder -> tree.parentOf(target.item.folderId)
        is Target.File -> tree.folderOf(target.item.fileId)
    }
    var at by remember { mutableStateOf(from) }
    val here = at
    val choices = tree.childFolders(here)
        .filter { movingFolder == null || !tree.isWithin(it, movingFolder) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { tree.folders.getValue(it).name })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_move_title, target.name), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    here?.let { tree.folders[it]?.name } ?: stringResource(R.string.browser_root),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (here != null) TextButton(onClick = { at = tree.parentOf(here) }) { Text(stringResource(R.string.browser_up)) }
                if (choices.isEmpty()) {
                    Text(stringResource(R.string.browser_no_subfolders), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(choices, key = { it.toByteArray().contentHashCode() }) { id ->
                            Text(
                                tree.folders.getValue(id).name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable { at = id }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMove(here) }, enabled = here != from) { Text(stringResource(R.string.browser_move_here)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

@Composable
private fun FolderRow(folder: FolderItem, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Badge("DIR", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(16.dp))
            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { menu = true }) { Text("...") }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_rename)) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_move)) }, onClick = { menu = false; onMove() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_delete)) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun FileRow(
    file: FileItem,
    onOpen: () -> Unit,
    onExport: (Uri) -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        if (uri != null) onExport(uri)
    }
    val ready = file.status == FileStatus.READY
    // Until its part 0 is in Saved Messages a rename would lose to it (SPEC §5.2), so uploads are left alone.
    val settled = file.status != FileStatus.UPLOADING
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
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_rename)) }, enabled = settled, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_move)) }, enabled = settled, onClick = { menu = false; onMove() })
            DropdownMenuItem(text = { Text(stringResource(R.string.browser_delete)) }, enabled = settled, onClick = { menu = false; confirmDelete = true })
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
    Badge(extension, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
}

@Composable
private fun Badge(text: String, background: Color, foreground: Color) {
    Box(Modifier.size(44.dp).background(background, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = foreground)
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
    snackbarHost: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
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
