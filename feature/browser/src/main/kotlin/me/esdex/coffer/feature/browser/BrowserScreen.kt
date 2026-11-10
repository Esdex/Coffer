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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import java.util.Date
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.storage.TransferLimiter
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.sync.canTake
import me.esdex.coffer.core.tdlib.Connection
import me.esdex.coffer.core.ui.components.AppDialog
import me.esdex.coffer.core.ui.components.AppSheet
import me.esdex.coffer.core.ui.components.CofferScaffold
import me.esdex.coffer.core.ui.components.EmptyStateView
import me.esdex.coffer.core.ui.components.FolderIcon
import me.esdex.coffer.core.ui.components.GroupedBox
import me.esdex.coffer.core.ui.components.GroupedRoundIcon
import me.esdex.coffer.core.ui.components.GroupedRow
import me.esdex.coffer.core.ui.components.LocalHazeState
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.ui.components.TopBarSearchField
import me.esdex.coffer.core.ui.components.TypeTile
import me.esdex.coffer.core.ui.components.fileTypeIcon
import me.esdex.coffer.core.ui.components.groupShape
import me.esdex.coffer.core.ui.theme.CofferHazeStyle
import me.esdex.coffer.core.ui.theme.LocalAmoledMode
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.UNDO_MILLIS
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.viewer.PdfViewerScreen
import me.esdex.coffer.feature.browser.viewer.ViewerScreen
import me.esdex.coffer.feature.browser.viewer.audio.AudioPlayerScreen
import me.esdex.coffer.feature.browser.viewer.text.TextFiles
import me.esdex.coffer.feature.browser.viewer.text.TextViewerScreen
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel

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

/** The vault's folders and files (SPEC §9), after Arcanum's file manager. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onLock: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
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
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    var movingSelection by remember { mutableStateOf(false) }
    var deletingSelection by remember { mutableStateOf(false) }
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Target?>(null) }
    var moving by remember { mutableStateOf<Target?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderItem?>(null) }
    var deletingFile by remember { mutableStateOf<FileItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val amoled = LocalAmoledMode.current
    val haze = remember { HazeState() }

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

    // A folder saved from its own menu, or else the selection.
    var savingFolder by remember { mutableStateOf<Id?>(null) }
    // Pictures shown in the viewer, the folder's or the search's, and which one was tapped.
    val viewing by viewModel.viewing.collectAsStateWithLifecycle()
    // Still shown while the viewer fades out, after it was closed.
    var lastViewing by remember { mutableStateOf(viewing) }
    if (viewing != null) lastViewing = viewing
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    var lastReading by remember { mutableStateOf(reading) }
    if (reading != null) lastReading = reading
    val listening by viewModel.listening.collectAsStateWithLifecycle()
    var lastListening by remember { mutableStateOf(listening) }
    if (listening != null) lastListening = listening
    var savingFromViewer by remember { mutableStateOf<Id?>(null) }
    val saveFromViewer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val fileId = savingFromViewer
        savingFromViewer = null
        if (uri != null && fileId != null) {
            askForNotifications()
            viewModel.export(fileId, uri)
        }
    }
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val folder = savingFolder
        savingFolder = null
        if (uri == null) return@rememberLauncherForActivityResult
        askForNotifications()
        if (folder != null) viewModel.exportFolder(folder, uri) else viewModel.exportSelected(uri)
    }
    val importTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        askForNotifications()
        viewModel.importFolder(uri)
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

    // Deleted · Undo. Gone a little before the delete goes out, so a tap on Undo is never too late.
    LaunchedEffect(Unit) {
        viewModel.undoable.collectLatest { count ->
            val result = withTimeoutOrNull(UNDO_MILLIS - UNDO_MARGIN_MILLIS) {
                snackbar.showSnackbar(
                    resources.getQuantityString(R.plurals.browser_deleted_count, count, count),
                    actionLabel = resources.getString(R.string.browser_undo),
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
        }
    }

    AnimatedContent(
        targetState = showTransfers,
        transitionSpec = {
            slideInHorizontally(tween(SLIDE_MILLIS, easing = EaseInOutCubic)) { if (targetState) it else -it } togetherWith
                slideOutHorizontally(tween(SLIDE_MILLIS, easing = EaseInOutCubic)) { if (targetState) -it else it }
        },
        modifier = modifier,
        label = "transfers",
    ) { transfersShown ->
        if (transfersShown) {
            TransfersScreen(transfers, onBack = { showTransfers = false }, onCancel = viewModel::cancel, onRetry = viewModel::retry, snackbar = snackbar)
            return@AnimatedContent
        }

        fun closeSearch() {
            searching = false
            viewModel.search("")
        }
        BackHandler(enabled = searching) { closeSearch() }
        BackHandler(enabled = !searching && listing.folderId != null) { viewModel.up() }
        // Declared last, so it goes first: back leaves the selection before anything else.
        BackHandler(enabled = selecting) { viewModel.clearSelection() }

        CompositionLocalProvider(LocalHazeState provides haze) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    Column(if (amoled) Modifier.hazeEffect(state = haze, style = CofferHazeStyle.topBar) else Modifier) {
                      AnimatedContent(
                        targetState = selecting,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                        label = "selection",
                      ) { inSelection ->
                       if (inSelection) {
                        val single = selection.singleOrNull()?.let { id ->
                            listing.folders.find { it.folderId == id }?.let(Target::Folder) ?: listing.files.find { it.fileId == id }?.let(Target::File)
                        }
                        SelectionTopBar(
                            count = selection.size,
                            onCancel = viewModel::clearSelection,
                            onSelectAll = viewModel::selectAll,
                            onRename = single?.let { target -> { viewModel.clearSelection(); renaming = target } },
                            colors = if (amoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors(),
                        )
                       } else {
                        TopAppBar(
                            title = {
                                if (searching) {
                                    TopBarSearchField(query, viewModel::search, stringResource(R.string.browser_search), Modifier.fillMaxWidth())
                                } else {
                                    // Like Telegram's own apps: while there is no link the title says so, and the
                                    // change rolls down. Only that change: a folder's name moves with its list.
                                    AnimatedContent(
                                        targetState = connection.label(),
                                        transitionSpec = {
                                            (slideInVertically(tween(TITLE_MILLIS, easing = EaseInOutCubic)) { -it } + fadeIn(tween(TITLE_MILLIS))) togetherWith
                                                (slideOutVertically(tween(TITLE_MILLIS, easing = EaseInOutCubic)) { it } + fadeOut(tween(TITLE_MILLIS)))
                                        },
                                        label = "title",
                                    ) { label ->
                                        val title = label?.let { stringResource(it) }
                                            ?: listing.path.lastOrNull()?.name
                                            ?: stringResource(R.string.browser_title)
                                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            },
                            navigationIcon = {
                                // Up a folder or out of the search: a plain arrow, not the way out of a screen.
                                when {
                                    searching -> IconButton(onClick = ::closeSearch) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browser_cd_close_search))
                                    }
                                    listing.folderId != null -> IconButton(onClick = { viewModel.up() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browser_up))
                                    }
                                }
                            },
                            actions = {
                                if (!searching) {
                                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, stringResource(R.string.browser_search)) }
                                    if (transfers.isNotEmpty()) {
                                        IconButton(onClick = { showTransfers = true }) {
                                            BadgedBox(badge = { Badge { Text("${transfers.size}") } }) {
                                                Icon(Icons.Outlined.SwapVert, stringResource(R.string.browser_transfers))
                                            }
                                        }
                                    }
                                    Box {
                                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
                                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            MenuItem(Icons.AutoMirrored.Outlined.Sort, R.string.browser_sort) { menuOpen = false; sortOpen = true }
                                            MenuItem(Icons.Outlined.SwapVert, R.string.browser_transfers) { menuOpen = false; showTransfers = true }
                                            MenuItem(Icons.Outlined.Lock, R.string.browser_lock) { menuOpen = false; viewModel.lock(onLock) }
                                            MenuItem(Icons.Outlined.Settings, R.string.browser_settings) { menuOpen = false; onSettings() }
                                        }
                                    }
                                }
                            },
                            colors = if (amoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors(),
                        )
                       }
                      }
                        AnimatedVisibility(visible = !searching && !selecting && listing.path.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                            Breadcrumbs(listing.path, onRoot = viewModel::goToRoot, onFolder = viewModel::openFolder)
                        }
                        catchingUp?.let {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                pluralStringResource(R.plurals.browser_catching_up, it, it),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = selecting,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        SelectionActions(
                            onMove = { movingSelection = true },
                            // Folders go with what is in them; files that are not whole are left out and said so.
                            canSave = true,
                            onSave = { exportTree.launch(null) },
                            onDelete = { deletingSelection = true },
                        )
                    }
                },
                floatingActionButton = {
                    if (!searching && !selecting) {
                        FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, stringResource(R.string.browser_add)) }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().hazeSource(haze)) {
                    // A folder deeper in comes in from the right; going up, from the left. Same folder: no slide.
                    AnimatedContent(
                        targetState = listing,
                        contentKey = { it.folderId to searching },
                        transitionSpec = {
                            val deeper = targetState.path.size > initialState.path.size
                            slideInHorizontally(tween(FOLDER_MILLIS, easing = EaseInOutCubic)) { if (deeper) it / 3 else -it / 3 } + fadeIn(tween(FOLDER_MILLIS)) togetherWith
                                fadeOut(tween(FOLDER_MILLIS / 2))
                        },
                        label = "folder",
                    ) { shown ->
                        Listing(
                            shown = shown,
                            padding = padding,
                            selection = selection,
                            onToggle = viewModel::toggleSelected,
                            empty = when {
                                query.isNotBlank() -> R.string.browser_no_match
                                shown.folderId != null -> R.string.browser_folder_empty
                                else -> R.string.browser_empty
                            },
                            onOpenFolder = { viewModel.openFolder(it.folderId) },
                            onFolderMenu = { item, action ->
                                when (action) {
                                    RowAction.RENAME -> renaming = Target.Folder(item)
                                    RowAction.MOVE -> moving = Target.Folder(item)
                                    RowAction.DELETE -> deletingFolder = item
                                    RowAction.EXPORT -> {
                                        savingFolder = item.folderId
                                        exportTree.launch(null)
                                    }
                                    else -> Unit
                                }
                            },
                            onOpenFile = { file ->
                                if (file.isViewable()) {
                                    val media = shown.files.filter { it.isViewable() && it.status == FileStatus.READY }
                                    viewModel.view(media.map { ViewerViewModel.Page(it.fileId, it.name, it.mime) }, media.indexOf(file).coerceAtLeast(0))
                                } else if (file.isAudio()) {
                                    val tracks = shown.files.filter { it.isAudio() && it.status == FileStatus.READY }
                                    viewModel.listen(tracks.map { ViewerViewModel.Page(it.fileId, it.name, it.mime) }, tracks.indexOf(file).coerceAtLeast(0))
                                } else if (file.isReadable()) {
                                    viewModel.read(ViewerViewModel.Page(file.fileId, file.name, file.mime))
                                } else {
                                    askForNotifications()
                                    viewModel.open(file.fileId)
                                }
                            },
                            onExport = { item, uri -> askForNotifications(); viewModel.export(item.fileId, uri) },
                            onFileMenu = { item, action ->
                                when (action) {
                                    RowAction.RENAME -> renaming = Target.File(item)
                                    RowAction.MOVE -> moving = Target.File(item)
                                    RowAction.DELETE -> deletingFile = item
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (sortOpen) {
        SortSheet(sorting, onSortBy = { if (it != sorting.by) viewModel.sortBy(it) }, onToggleDirection = { viewModel.sortBy(sorting.by) }, onDismiss = { sortOpen = false })
    }
    if (adding) {
        AddSheet(
            onFiles = { adding = false; pick.launch(arrayOf("*/*")) },
            onPhoneFolder = { adding = false; importTree.launch(null) },
            onFolder = { adding = false; creatingFolder = true },
            onDismiss = { adding = false },
        )
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
        val from = when (target) {
            is Target.Folder -> currentTree.parentOf(target.item.folderId)
            is Target.File -> currentTree.folderOf(target.item.fileId)
        }
        MoveDialog(
            tree = currentTree,
            title = stringResource(R.string.browser_move_title, target.name),
            from = from,
            fromKnown = true,
            movingFolders = listOfNotNull((target as? Target.Folder)?.item?.folderId),
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
    deletingFile?.let { file ->
        AppDialog(
            onDismissRequest = { deletingFile = null },
            title = { Text(stringResource(R.string.browser_delete_title)) },
            text = { Text(stringResource(R.string.browser_delete_text, file.name)) },
            confirmButton = { TextButton(onClick = { deletingFile = null; viewModel.delete(file.fileId) }) { Text(stringResource(R.string.browser_delete)) } },
            dismissButton = { TextButton(onClick = { deletingFile = null }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
    if (movingSelection && currentTree != null) {
        val picked = selection
        val folders = picked.filter { it in currentTree.folders }
        val parents = picked.map { if (it in currentTree.folders) currentTree.parentOf(it) else currentTree.folderOf(it) }.toSet()
        MoveDialog(
            tree = currentTree,
            title = pluralStringResource(R.plurals.browser_move_many_title, picked.size, picked.size),
            // Picked from one folder: moving them back there is no move. From a search, there is no one place.
            from = parents.singleOrNull(),
            fromKnown = parents.size == 1,
            movingFolders = folders,
            onMove = { destination -> movingSelection = false; viewModel.moveSelected(destination) },
            onDismiss = { movingSelection = false },
        )
    }
    if (deletingSelection) {
        val count = selection.size
        AppDialog(
            onDismissRequest = { deletingSelection = false },
            title = { Text(pluralStringResource(R.plurals.browser_delete_many_title, count, count)) },
            text = { Text(stringResource(R.string.browser_delete_many_text)) },
            confirmButton = {
                TextButton(onClick = { deletingSelection = false; viewModel.deleteSelected() }) {
                    Text(stringResource(R.string.browser_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingSelection = false }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
    deletingFolder?.let { folder ->
        val (folders, files) = currentTree?.subtree(folder.folderId) ?: (emptyList<Id>() to emptyList())
        AppDialog(
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
    // Over everything else, the bars included.
    AnimatedVisibility(visible = viewing != null, enter = fadeIn(tween(FOLDER_MILLIS)), exit = fadeOut(tween(FOLDER_MILLIS))) {
        val (pages, start) = lastViewing ?: return@AnimatedVisibility
        ViewerScreen(
            pages = pages,
            startIndex = start,
            onClose = viewModel::closeViewer,
            onSave = { page ->
                savingFromViewer = page.fileId
                saveFromViewer.launch(page.name)
            },
            onOpenWith = { page ->
                askForNotifications()
                viewModel.open(page.fileId)
            },
        )
    }
    AnimatedVisibility(visible = reading != null, enter = fadeIn(tween(FOLDER_MILLIS)), exit = fadeOut(tween(FOLDER_MILLIS))) {
        val page = lastReading ?: return@AnimatedVisibility
        val onSave: (ViewerViewModel.Page) -> Unit = {
            savingFromViewer = it.fileId
            saveFromViewer.launch(it.name)
        }
        val onOpenWith: (ViewerViewModel.Page) -> Unit = {
            askForNotifications()
            viewModel.open(it.fileId)
        }
        if (page.mime == PDF) {
            PdfViewerScreen(page = page, onClose = viewModel::closeReading, onSave = onSave, onOpenWith = onOpenWith)
        } else {
            TextViewerScreen(page = page, onClose = viewModel::closeReading, onSave = onSave, onOpenWith = onOpenWith)
        }
    }
    AnimatedVisibility(visible = listening != null, enter = fadeIn(tween(FOLDER_MILLIS)), exit = fadeOut(tween(FOLDER_MILLIS))) {
        val (tracks, start) = lastListening ?: return@AnimatedVisibility
        AudioPlayerScreen(
            tracks = tracks,
            startIndex = start,
            onClose = viewModel::closeListening,
            onSave = {
                savingFromViewer = it.fileId
                saveFromViewer.launch(it.name)
            },
            onOpenWith = {
                askForNotifications()
                viewModel.open(it.fileId)
            },
        )
    }
}

/** What the viewer shows (pre-release item 5). SVG is an image by type but not one ImageDecoder draws: it opens as text. */
private fun FileItem.isViewable() = (mime.startsWith("image/") && mime != "image/svg+xml") || mime.startsWith("video/")

/** Tracks Coffer plays itself, the folder's one after another. */
private fun FileItem.isAudio() = mime.startsWith("audio/")

/** Documents Coffer reads itself, one at a time. */
private fun FileItem.isReadable() = mime == PDF || TextFiles.isText(name, mime)

private const val PDF = "application/pdf"

private enum class RowAction { OPEN, EXPORT, RENAME, MOVE, DELETE }

@Composable
private fun Listing(
    shown: Listing,
    padding: PaddingValues,
    selection: Set<Id>,
    onToggle: (Id) -> Unit,
    empty: Int,
    onOpenFolder: (FolderItem) -> Unit,
    onFolderMenu: (FolderItem, RowAction) -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onExport: (FileItem, Uri) -> Unit,
    onFileMenu: (FileItem, RowAction) -> Unit,
) {
    if (shown.folders.isEmpty() && shown.files.isEmpty()) {
        EmptyStateView(
            title = stringResource(empty),
            subtitle = if (empty == R.string.browser_no_match) null else stringResource(R.string.browser_empty_hint),
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        // Room under the last row for the button that floats over it.
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
    ) {
        items(shown.folders, key = { "d" + it.folderId.toByteArray().contentHashCode() }) { folder ->
            FolderRow(
                folder,
                selecting = selection.isNotEmpty(),
                selected = folder.folderId in selection,
                onOpen = { onOpenFolder(folder) },
                onToggle = { onToggle(folder.folderId) },
                onAction = { onFolderMenu(folder, it) },
                modifier = Modifier.animateItem(),
            )
        }
        items(shown.files, key = { "f" + it.fileId.toByteArray().contentHashCode() }) { file ->
            FileRow(
                file,
                selecting = selection.isNotEmpty(),
                selected = file.fileId in selection,
                onOpen = { onOpenFile(file) },
                onToggle = { onToggle(file.fileId) },
                onExport = { onExport(file, it) },
                onAction = { onFileMenu(file, it) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** Where the browser is: the root as a house, then each folder; any of them is a tap away. */
@Composable
private fun Breadcrumbs(path: List<FolderItem>, onRoot: () -> Unit, onFolder: (Id) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Home,
            contentDescription = stringResource(R.string.browser_root),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).clickable(onClick = onRoot),
        )
        path.forEachIndexed { index, folder ->
            val last = index == path.lastIndex
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = if (last) Modifier else Modifier.clickable { onFolder(folder.folderId) },
            )
        }
    }
}

/** Add to the folder on screen: files from the phone, or a new folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSheet(onFiles: () -> Unit, onPhoneFolder: () -> Unit, onFolder: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            SettingsGroup(title = null) {
                row { shape ->
                    GroupedRow(
                        shape,
                        title = stringResource(R.string.browser_add),
                        subtitle = stringResource(R.string.browser_add_files_sub),
                        leading = { GroupedRoundIcon(Icons.Outlined.UploadFile, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) },
                        onClick = onFiles,
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape,
                        title = stringResource(R.string.browser_add_folder),
                        subtitle = stringResource(R.string.browser_add_folder_sub),
                        leading = { GroupedRoundIcon(Icons.Outlined.DriveFolderUpload, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) },
                        onClick = onPhoneFolder,
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape,
                        title = stringResource(R.string.browser_new_folder),
                        subtitle = stringResource(R.string.browser_new_folder_sub),
                        leading = { GroupedRoundIcon(Icons.Outlined.CreateNewFolder, FolderIcon.second.copy(alpha = 0.85f)) },
                        onClick = onFolder,
                    )
                }
            }
        }
    }
}

/** A menu entry with its icon in front, as every menu in Arcanum has. A destructive one is red. */
@Composable
private fun MenuItem(icon: ImageVector, label: Int, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val red = destructive && enabled
    DropdownMenuItem(
        text = { Text(stringResource(label), color = if (red) MaterialTheme.colorScheme.error else Color.Unspecified) },
        leadingIcon = { Icon(icon, null, tint = if (red) MaterialTheme.colorScheme.error else LocalContentColor.current) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** How the list is ordered, in a sheet like Arcanum's: one choice, and the chosen one says which way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(sorting: Sorting, onSortBy: (SortBy) -> Unit, onToggleDirection: () -> Unit, onDismiss: () -> Unit) {
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text(stringResource(R.string.browser_sort_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            for (by in SortBy.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable { onSortBy(by) }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = sorting.by == by, onClick = { onSortBy(by) })
                    Text(stringResource(by.label()), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (sorting.by == by) {
                        IconButton(onClick = onToggleDirection) {
                            Icon(
                                if (sorting.descending) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                                contentDescription = stringResource(if (sorting.descending) R.string.browser_sort_descending else R.string.browser_sort_ascending),
                            )
                        }
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
    AppDialog(
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

/**
 * Picks a destination folder by walking the tree; no folder of [movingFolders] can go into itself or
 * below itself. [from] is where they all are now, when [fromKnown]: moving there again is no move.
 */
@Composable
private fun MoveDialog(
    tree: VaultTree,
    title: String,
    from: Id?,
    fromKnown: Boolean,
    movingFolders: List<Id>,
    onMove: (Id?) -> Unit,
    onDismiss: () -> Unit,
) {
    var at by remember { mutableStateOf(from) }
    val here = at
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { FolderPicker(tree, here, onAt = { at = it }, excluding = movingFolders) },
        confirmButton = {
            TextButton(
                onClick = { onMove(here) },
                enabled = (!fromKnown || here != from) && tree.canTake(here, movingFolders),
            ) { Text(stringResource(R.string.browser_move_here)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) } },
    )
}

/**
 * Walks the folder tree from [at]: where it is, one level up, and the folders inside. The folders of
 * [excluding] and everything below them are not offered, so a folder cannot be put inside itself.
 */
@Composable
fun FolderPicker(tree: VaultTree, at: Id?, onAt: (Id?) -> Unit, excluding: Collection<Id> = emptyList()) {
    val choices = tree.childFolders(at)
        .filter { child -> excluding.none { tree.isWithin(child, it) } }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { tree.folders.getValue(it).name })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (at == null) Icons.Outlined.Home else FolderIcon.first, null, tint = if (at == null) MaterialTheme.colorScheme.primary else FolderIcon.second)
            Spacer(Modifier.width(8.dp))
            Text(
                at?.let { tree.folders[it]?.name } ?: stringResource(R.string.browser_root),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (at != null) TextButton(onClick = { onAt(tree.parentOf(at)) }) { Text(stringResource(R.string.browser_up)) }
        if (choices.isEmpty()) {
            Text(stringResource(R.string.browser_no_subfolders), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(choices, key = { it.toByteArray().contentHashCode() }) { id ->
                    Row(Modifier.fillMaxWidth().clickable { onAt(id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TypeTile(FolderIcon, Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(tree.folders.getValue(id).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: FolderItem,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val idle = !folder.deleting
    Row(
        modifier.fillMaxWidth()
            .background(selectedBackground(selected))
            // Picking starts with a long press, as in Arcanum; while picking, a tap picks instead of opening.
            .combinedClickable(
                enabled = idle,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = { if (folder.selectable) onToggle() },
            )
            .alpha(if (idle) 1f else DELETING_ALPHA)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickTile(selecting, selected, FolderIcon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (idle) {
                Text(stringResource(R.string.browser_type_folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.browser_status_deleting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (!selecting) Box {
            IconButton(onClick = { menu = true }, enabled = idle) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(Icons.Outlined.Edit, R.string.browser_rename) { menu = false; onAction(RowAction.RENAME) }
                MenuItem(Icons.AutoMirrored.Outlined.DriveFileMove, R.string.browser_move) { menu = false; onAction(RowAction.MOVE) }
                MenuItem(Icons.Outlined.SaveAlt, R.string.browser_save_folder) { menu = false; onAction(RowAction.EXPORT) }
                MenuItem(Icons.Outlined.Delete, R.string.browser_delete, destructive = true) { menu = false; onAction(RowAction.DELETE) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileItem,
    selecting: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onExport: (Uri) -> Unit,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime)) { uri ->
        if (uri != null) onExport(uri)
    }
    val ready = file.status == FileStatus.READY
    // Until its part 0 is in Saved Messages a rename would lose to it (SPEC §5.2), so uploads are left alone.
    val settled = file.status != FileStatus.UPLOADING
    val idle = !file.deleting
    Row(
        modifier.fillMaxWidth()
            .background(selectedBackground(selected))
            // A ready file opens on a tap, like everywhere else; one that is not shows what can be done with it.
            // While picking, a tap picks; one still uploading cannot be picked.
            .combinedClickable(
                enabled = idle && (!selecting || file.selectable),
                onClick = {
                    when {
                        selecting -> onToggle()
                        ready -> onOpen()
                        else -> menu = true
                    }
                },
                onLongClick = { if (file.selectable) onToggle() else if (!selecting) menu = true },
            )
            .alpha(if (idle && (!selecting || file.selectable)) 1f else DELETING_ALPHA)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickTile(selecting, selected, fileTypeIcon(file.name))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            val details = Formatter.formatShortFileSize(context, file.size) + " · " + DateFormat.getMediumDateFormat(context).format(Date(file.modifiedAt))
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!idle) {
                Text(stringResource(R.string.browser_status_deleting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            } else file.status.label()?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (file.status == FileStatus.DAMAGED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!selecting) Box {
            IconButton(onClick = { menu = true }, enabled = idle) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(Icons.Outlined.FileOpen, R.string.browser_open, enabled = ready) { menu = false; onOpen() }
                MenuItem(Icons.Outlined.SaveAlt, R.string.browser_export, enabled = ready) { menu = false; export.launch(file.name) }
                MenuItem(Icons.Outlined.Edit, R.string.browser_rename, enabled = settled) { menu = false; onAction(RowAction.RENAME) }
                MenuItem(Icons.AutoMirrored.Outlined.DriveFileMove, R.string.browser_move, enabled = settled) { menu = false; onAction(RowAction.MOVE) }
                MenuItem(Icons.Outlined.Delete, R.string.browser_delete, enabled = settled, destructive = true) { menu = false; onAction(RowAction.DELETE) }
            }
        }
    }
}

/** A row whose delete is on the way: still there, but nothing to do with it. */
private const val DELETING_ALPHA = 0.5f

@Composable
private fun selectedBackground(selected: Boolean): Color {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
        label = "picked",
    )
    return color
}

/** The type tile, or while picking a check box in its place, as in Arcanum's file manager. */
@Composable
private fun PickTile(selecting: Boolean, selected: Boolean, icon: Pair<ImageVector, Color>) {
    AnimatedContent(targetState = selecting && selected, label = "pick") { picked ->
        if (picked) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Check, stringResource(R.string.browser_cd_picked), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        } else {
            TypeTile(icon)
        }
    }
}

/** While picking: how many, and what applies to all of them; Rename only when there is one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onRename: (() -> Unit)?,
    colors: TopAppBarColors,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, stringResource(R.string.browser_cd_cancel_selection)) }
        },
        title = { Text(pluralStringResource(R.plurals.browser_selected_count, count, count)) },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, stringResource(R.string.browser_select_all)) }
            if (onRename != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(Icons.Outlined.Edit, R.string.browser_rename) { menu = false; onRename() }
                    }
                }
            }
        },
        colors = colors,
    )
}

/** The actions on everything picked, each with its name under it. */
@Composable
private fun SelectionActions(onMove: () -> Unit, canSave: Boolean, onSave: () -> Unit, onDelete: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(R.string.browser_move, Icons.AutoMirrored.Outlined.DriveFileMove, onMove)
            SelectionAction(R.string.browser_save, Icons.Outlined.SaveAlt, onSave, enabled = canSave)
            SelectionAction(R.string.browser_delete, Icons.Outlined.Delete, onDelete, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SelectionAction(label: Int, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun FileStatus.label() = when (this) {
    FileStatus.UPLOADING -> R.string.browser_status_uploading
    FileStatus.INCOMPLETE -> R.string.browser_status_incomplete
    FileStatus.DAMAGED -> R.string.browser_status_damaged
    FileStatus.READY -> null
}

/** Transfers in progress or waiting, one card each (SPEC §9). */
@Composable
fun TransfersScreen(
    transfers: List<TransferItem>,
    onBack: () -> Unit,
    onCancel: (TransferItem) -> Unit,
    onRetry: (TransferItem) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
) {
    BackHandler(onBack = onBack)
    CofferScaffold(title = stringResource(R.string.browser_transfers), onBack = onBack, modifier = modifier, snackbar = snackbar) { padding ->
        if (transfers.isEmpty()) {
            EmptyStateView(stringResource(R.string.browser_no_transfers), Modifier.fillMaxSize().padding(padding).padding(32.dp))
            return@CofferScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexedShaped(transfers) { item, shape ->
                GroupedBox(shape) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TypeTile(fileTypeIcon(item.name.orEmpty()))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(
                                if (item.isUpload) R.string.browser_transfer_upload else R.string.browser_transfer_download,
                                item.name ?: stringResource(R.string.browser_transfer_unnamed),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TransferStatus(item)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.failure != null) OutlinedButton(onClick = { onRetry(item) }) { Text(stringResource(R.string.browser_retry)) }
                        TextButton(onClick = { onCancel(item) }) { Text(stringResource(R.string.browser_cancel)) }
                    }
                }
            }
        }
    }
}

private fun Connection.label() = when (this) {
    Connection.WAITING_FOR_NETWORK -> R.string.browser_connection_waiting
    Connection.CONNECTING -> R.string.browser_connection_connecting
    Connection.UPDATING -> R.string.browser_connection_updating
    Connection.READY -> null
}

/** What a transfer is doing: an error, a wait, or a bar with how much of how much has gone. */
@Composable
private fun TransferStatus(item: TransferItem) {
    val context = LocalContext.current
    val progress = item.progress
    val small = MaterialTheme.typography.bodySmall
    when {
        item.failure != null -> Text(stringResource(item.failure.label()), color = MaterialTheme.colorScheme.error, style = small)
        item.preparing -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.browser_transfer_preparing), style = small)
        }
        progress == null -> Text(stringResource(R.string.browser_transfer_waiting), style = small)
        progress.phase == TransferRegistry.Phase.QUEUED ->
            Text(pluralStringResource(R.plurals.browser_transfer_queued, TransferLimiter.MAX_TRANSFERS, TransferLimiter.MAX_TRANSFERS), style = small)
        else -> {
            val moving = progress.phase == TransferRegistry.Phase.MOVING
            // A part being read or encrypted moves nothing on the wire: a running bar, not a stuck one.
            if (moving && progress.total > 0) {
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val amount = stringResource(
                    R.string.transfers_amount,
                    Formatter.formatShortFileSize(context, progress.done),
                    Formatter.formatShortFileSize(context, progress.total),
                )
                Text(
                    if (moving) amount else stringResource(R.string.transfers_preparing) + " · " + amount,
                    style = small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.browser_transfer_percent, progress.percent()), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Items of one group of cards: each gets the shape its place in the group calls for. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedShaped(
    list: List<T>,
    content: @Composable (T, androidx.compose.ui.graphics.Shape) -> Unit,
) {
    items(list.size) { index -> content(list[index], groupShape(index, list.size)) }
}

private fun TransferRegistry.Failure.label() = when (this) {
    TransferRegistry.Failure.NO_SPACE -> R.string.browser_failure_no_space
    TransferRegistry.Failure.SOURCE_CHANGED -> R.string.browser_failure_source_changed
    TransferRegistry.Failure.SOURCE_GONE -> R.string.browser_failure_source_gone
    TransferRegistry.Failure.CONTENT_MISMATCH -> R.string.browser_failure_mismatch
    TransferRegistry.Failure.MISSING_PART -> R.string.browser_failure_missing_part
    TransferRegistry.Failure.TARGET_FAILED -> R.string.browser_failure_target
}

private const val SLIDE_MILLIS = 350
private const val UNDO_MARGIN_MILLIS = 500L
private const val TITLE_MILLIS = 300
private const val FOLDER_MILLIS = 250
