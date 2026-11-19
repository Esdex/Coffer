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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.sync.OpKind
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.sync.canTake
import me.esdex.coffer.core.tdlib.Connection
import me.esdex.coffer.core.ui.components.AppDialog
import me.esdex.coffer.core.ui.components.AppMenu
import me.esdex.coffer.core.ui.components.AppSheet
import me.esdex.coffer.core.ui.components.AppSnackbarHost
import me.esdex.coffer.core.ui.components.CofferScaffold
import me.esdex.coffer.core.ui.components.EmptyStateView
import me.esdex.coffer.core.ui.components.FloatingCard
import me.esdex.coffer.core.ui.components.FolderIcon
import me.esdex.coffer.core.ui.components.GroupedBox
import me.esdex.coffer.core.ui.components.GroupedRoundIcon
import me.esdex.coffer.core.ui.components.GroupedRow
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.LocalHazeState
import me.esdex.coffer.core.ui.components.LocalLockFlight
import me.esdex.coffer.core.ui.components.PreviewTile
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.ui.components.ShimmerText
import me.esdex.coffer.core.ui.components.TopBarSearchField
import me.esdex.coffer.core.ui.components.TypeTile
import me.esdex.coffer.core.ui.components.fileTypeIcon
import me.esdex.coffer.core.ui.components.groupShape
import me.esdex.coffer.core.ui.components.screenRect
import me.esdex.coffer.core.ui.theme.CachePrefs
import me.esdex.coffer.core.ui.theme.CofferHazeStyle
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Hues
import me.esdex.coffer.core.ui.theme.LocalAmoledMode
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular
import me.esdex.coffer.feature.browser.preview.Previews
import me.esdex.coffer.feature.browser.transfer.CacheOffer
import me.esdex.coffer.feature.browser.transfer.QueueSummary
import me.esdex.coffer.feature.browser.transfer.TransferKind
import me.esdex.coffer.feature.browser.transfer.TransferRegistry
import me.esdex.coffer.feature.browser.transfer.UNDO_MILLIS
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.viewer.PdfViewerScreen
import me.esdex.coffer.feature.browser.viewer.ViewerScreen
import me.esdex.coffer.feature.browser.viewer.audio.AudioPlayerScreen
import me.esdex.coffer.feature.browser.viewer.audio.MiniPlayer
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
    val run by viewModel.run.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sorting by viewModel.sorting.collectAsStateWithLifecycle()
    val catchingUp by viewModel.catchingUp.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val cacheOffer by viewModel.cacheOffer.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    var movingSelection by remember { mutableStateOf(false) }
    var deletingSelection by remember { mutableStateOf(false) }
    var showTransfers by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var lockIconRect by remember { mutableStateOf<Rect?>(null) }
    val lockFlight = LocalLockFlight.current
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
    val playerExpanded by viewModel.playerExpanded.collectAsStateWithLifecycle()
    var savingFromViewer by remember { mutableStateOf<Id?>(null) }
    val saveFromViewer = rememberLauncherForActivityResult(SaveAs()) { uri ->
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
            slideInHorizontally(Motion.screen()) { if (targetState) it else -it } togetherWith
                slideOutHorizontally(Motion.screen()) { if (targetState) -it else it }
        },
        modifier = modifier,
        label = "transfers",
    ) { transfersShown ->
        if (transfersShown) {
            TransfersScreen(
                transfers,
                run = run,
                offline = connection == Connection.WAITING_FOR_NETWORK,
                previews = viewModel.previews,
                onBack = { showTransfers = false },
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onCancelAll = viewModel::cancelAll,
                snackbar = snackbar,
            )
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
                snackbarHost = { AppSnackbarHost(snackbar) },
                topBar = {
                    // One ground for the bar and what hangs under it: frosted glass in pure black, the bar's own colour
                    // otherwise, or the path and the catch-up line let the list show through them.
                    Column(
                        if (amoled) Modifier.hazeEffect(state = haze, style = CofferHazeStyle.topBar) else Modifier.background(MaterialTheme.colorScheme.surface),
                    ) {
                      AnimatedContent(
                        targetState = selecting,
                        transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) },
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
                                // The search field takes the title's place in a cross-fade, not at once.
                                AnimatedContent(
                                    targetState = searching,
                                    transitionSpec = { fadeIn(Motion.standard()) togetherWith fadeOut(Motion.quick()) },
                                    label = "search",
                                ) { inSearch ->
                                    if (inSearch) {
                                        TopBarSearchField(query, viewModel::search, stringResource(R.string.browser_search), Modifier.fillMaxWidth())
                                    } else {
                                        // Like Telegram's own apps: while there is no link the title says so, and the
                                        // change rolls down. Only that change: a folder's name moves with its list.
                                        AnimatedContent(
                                            targetState = connection.label(),
                                            transitionSpec = {
                                                (slideInVertically(Motion.standard()) { -it } + fadeIn(Motion.standard())) togetherWith
                                                    (slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.standard()))
                                            },
                                            label = "title",
                                        ) { label ->
                                            if (label != null) {
                                                // Grey with light running over it: under way, not a name.
                                                ShimmerText(stringResource(label))
                                            } else {
                                                Text(
                                                    listing.path.lastOrNull()?.name ?: stringResource(R.string.browser_title),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
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
                                AnimatedVisibility(!searching, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
                                  Row {
                                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, stringResource(R.string.browser_search)) }
                                    AnimatedVisibility(
                                        transfers.isNotEmpty(),
                                        enter = fadeIn(Motion.standard()) + scaleIn(Motion.standard(), initialScale = 0.6f),
                                        exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.6f),
                                    ) {
                                        // The count over the button, not inside it: an icon button clips to its
                                        // circle, which cut the right end off a count of two digits.
                                        Box {
                                            IconButton(onClick = { showTransfers = true }) {
                                                Icon(Icons.Outlined.SwapVert, stringResource(R.string.browser_transfers))
                                            }
                                            Badge(Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 6.dp)) {
                                                Text(if (transfers.size > 99) "99+" else "${transfers.size}")
                                            }
                                        }
                                    }
                                    Box {
                                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
                                        AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                            MenuItem(Icons.AutoMirrored.Outlined.Sort, R.string.browser_sort) { menuOpen = false; sortOpen = true }
                                            MenuItem(Icons.Outlined.SwapVert, R.string.browser_transfers) { menuOpen = false; showTransfers = true }
                                            // The padlock flies from this item's icon to the unlock screen.
                                            MenuItem(Icons.Outlined.Lock, R.string.browser_lock, onIconPlaced = { lockIconRect = it }) {
                                                menuOpen = false
                                                lockIconRect?.let(lockFlight::launch)
                                                viewModel.lock(onLock)
                                            }
                                            MenuItem(Icons.Outlined.Settings, R.string.browser_settings) { menuOpen = false; onSettings() }
                                        }
                                    }
                                  }
                                }
                            },
                            colors = if (amoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors(),
                        )
                       }
                      }
                        // At the root too, with the house alone: going into a folder and back does not move the list.
                        AnimatedVisibility(visible = !searching && !selecting, enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
                            Breadcrumbs(listing.path, onRoot = viewModel::goToRoot, onFolder = viewModel::openFolder)
                        }
                        // Kept while it folds away, so the count does not blank out on the way.
                        var lastCaughtUp by remember { mutableIntStateOf(0) }
                        catchingUp?.let { lastCaughtUp = it }
                        AnimatedVisibility(
                            catchingUp != null,
                            enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
                            exit = shrinkVertically(Motion.quick()) + fadeOut(Motion.quick()),
                        ) {
                            Column {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    pluralStringResource(R.plurals.browser_catching_up, lastCaughtUp, lastCaughtUp),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                },
                bottomBar = {
                    // Music playing on with its player folded away, unless a selection needs the place.
                    AnimatedVisibility(
                        visible = listening.isNotEmpty() && !playerExpanded && !selecting,
                        enter = slideInVertically(Motion.screen()) { it } + fadeIn(Motion.screen()),
                        exit = slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.standard()),
                    ) {
                        MiniPlayer()
                    }
                    AnimatedVisibility(
                        visible = selecting,
                        enter = slideInVertically(Motion.screen()) { it } + fadeIn(Motion.screen()),
                        exit = slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.standard()),
                    ) {
                        SelectionActions(
                            onMove = { movingSelection = true },
                            // Folders go with what is in them; files that are not whole are left out and said so.
                            canSave = true,
                            onSave = { exportTree.launch(null) },
                            onCache = { askForNotifications(); viewModel.cacheSelected() },
                            onDelete = { deletingSelection = true },
                        )
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        !searching && !selecting,
                        enter = fadeIn(Motion.standard()) + scaleIn(Motion.standard(), initialScale = 0.6f),
                        exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.6f),
                    ) {
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
                            slideInHorizontally(Motion.standard()) { if (deeper) it / 3 else -it / 3 } + fadeIn(Motion.standard()) togetherWith
                                fadeOut(Motion.quick())
                        },
                        label = "folder",
                    ) { shown ->
                        Listing(
                            shown = shown,
                            padding = padding,
                            previews = viewModel.previews,
                            selection = selection,
                            onToggle = viewModel::toggleSelected,
                            empty = when {
                                query.isNotBlank() -> Empty.SEARCH
                                shown.folderId != null -> Empty.FOLDER
                                else -> Empty.VAULT
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
                                    RowAction.CACHE -> {
                                        askForNotifications()
                                        viewModel.cacheFolder(item.folderId)
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
                                    RowAction.CACHE -> {
                                        askForNotifications()
                                        viewModel.cacheFile(item.fileId)
                                    }
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
            confirmButton = {
                TextButton(onClick = { deletingFile = null; viewModel.delete(file.fileId) }) {
                    Text(stringResource(R.string.browser_delete), color = MaterialTheme.colorScheme.error)
                }
            },
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
    cacheOffer?.let { offer ->
        CacheOfferDialog(offer, onCache = viewModel::acceptCacheOffer, onDismiss = viewModel::dismissCacheOffer)
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
                TextButton(onClick = { deletingFolder = null; viewModel.deleteFolder(folder.folderId) }) {
                    Text(stringResource(R.string.browser_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }
    // Over everything else, the bars included.
    AnimatedVisibility(visible = viewing != null, enter = viewerEnter, exit = viewerExit) {
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
    AnimatedVisibility(visible = reading != null, enter = viewerEnter, exit = viewerExit) {
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
    // The player comes up from the card it folds into, and goes back down into it.
    AnimatedVisibility(
        visible = listening.isNotEmpty() && playerExpanded,
        enter = slideInVertically(Motion.screen()) { it } + fadeIn(Motion.screen()),
        exit = slideOutVertically(Motion.standard()) { it } + fadeOut(Motion.standard()),
    ) {
        AudioPlayerScreen(
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

private enum class RowAction { OPEN, EXPORT, CACHE, RENAME, MOVE, DELETE }

/** Each kind of empty list says what it is and what to do about it. */
private enum class Empty(val title: Int, val hint: Int) {
    VAULT(R.string.browser_empty, R.string.browser_empty_hint),
    FOLDER(R.string.browser_folder_empty, R.string.browser_folder_empty_hint),
    SEARCH(R.string.browser_no_match, R.string.browser_no_match_hint),
}

@Composable
private fun Listing(
    shown: Listing,
    padding: PaddingValues,
    previews: Previews,
    selection: Set<Id>,
    onToggle: (Id) -> Unit,
    empty: Empty,
    onOpenFolder: (FolderItem) -> Unit,
    onFolderMenu: (FolderItem, RowAction) -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onExport: (FileItem, Uri) -> Unit,
    onFileMenu: (FileItem, RowAction) -> Unit,
) {
    if (shown.folders.isEmpty() && shown.files.isEmpty()) {
        // The ghost wherever the list is empty: a new vault, an empty folder, a search with no match.
        EmptyStateView(
            title = stringResource(empty.title),
            subtitle = stringResource(empty.hint),
            hero = Hero.GHOST,
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        // Room under the last row for the button that floats over it.
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
    ) {
        items(shown.folders, key = { "d" + it.folderId.rowKey() }) { folder ->
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
        items(shown.files, key = { "f" + it.fileId.rowKey() }) { file ->
            FileRow(
                file,
                previews = previews,
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

/** Where the browser is: the root as a house, then each folder; any of them but the one on screen is a tap away. */
@Composable
private fun Breadcrumbs(path: List<FolderItem>, onRoot: () -> Unit, onFolder: (Id) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Each step a pill of its own, big enough for a thumb, rippling in its own shape. At the root the house is
        // where the browser is, and marked as the last folder of a path is.
        val atRoot = path.isEmpty()
        Box(
            Modifier.clip(Corners.pill)
                .then(if (atRoot) Modifier else Modifier.clickable(onClick = onRoot))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Home,
                contentDescription = stringResource(R.string.browser_root),
                tint = if (atRoot) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        path.forEachIndexed { index, folder ->
            val last = index == path.lastIndex
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(Corners.pill)
                    .then(if (last) Modifier else Modifier.clickable { onFolder(folder.folderId) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
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
                        // A hue of its own, as New folder has: the theme's secondary container, from the wallpaper's
                        // colours, came out nearly grey and the row looked disabled (seen 2026-11-12).
                        leading = { GroupedRoundIcon(Icons.Outlined.DriveFolderUpload, Hues.Blue.copy(alpha = 0.85f)) },
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
private fun MenuItem(
    icon: ImageVector,
    label: Int,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onIconPlaced: ((Rect) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val red = destructive && enabled
    DropdownMenuItem(
        text = { Text(stringResource(label), color = if (red) MaterialTheme.colorScheme.error else Color.Unspecified) },
        leadingIcon = {
            Icon(
                icon,
                null,
                tint = if (red) MaterialTheme.colorScheme.error else LocalContentColor.current,
                modifier = if (onIconPlaced != null) Modifier.onGloballyPositioned { onIconPlaced(it.screenRect()) } else Modifier,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** How the list is ordered, in a sheet like Arcanum's: one choice, and the chosen one says which way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(sorting: Sorting, onSortBy: (SortBy) -> Unit, onToggleDirection: () -> Unit, onDismiss: () -> Unit) {
    // In the same grouped rows as the Add sheet, so the two sheets of this screen look alike.
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            SettingsGroup(title = stringResource(R.string.browser_sort_title)) {
                for (by in SortBy.entries) {
                    row { shape ->
                        val chosen = sorting.by == by
                        GroupedRow(
                            shape,
                            title = stringResource(by.label()),
                            leading = { RadioButton(selected = chosen, onClick = null) },
                            trailing = if (chosen) {
                                {
                                    IconButton(onClick = onToggleDirection) {
                                        Icon(
                                            if (sorting.descending) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                                            contentDescription = stringResource(if (sorting.descending) R.string.browser_sort_descending else R.string.browser_sort_ascending),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            onClick = { if (chosen) onToggleDirection() else onSortBy(by) },
                        )
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
                items(choices, key = { it.rowKey() }) { id ->
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
    val haptic = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth()
            .background(selectedBackground(selected))
            // Picking starts with a long press, as in Arcanum; while picking, a tap picks instead of opening.
            .combinedClickable(
                enabled = idle,
                onClick = { if (selecting) onToggle() else onOpen() },
                onLongClick = {
                    if (folder.selectable) {
                        // Felt as well as seen: picking has started.
                        if (!selecting) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle()
                    }
                },
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
                // What is in it, rather than that it is a folder: the icon says that already.
                val folders = pluralStringResource(R.plurals.browser_folder_folders, folder.folders, folder.folders)
                val files = pluralStringResource(R.plurals.browser_folder_files, folder.files, folder.files)
                val contents = when {
                    folder.folders > 0 && folder.files > 0 -> stringResource(R.string.browser_folder_both, folders, files)
                    folder.folders > 0 -> folders
                    folder.files > 0 -> files
                    else -> stringResource(R.string.browser_folder_nothing)
                }
                Text(contents, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.browser_status_deleting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        RowMenu(selecting, idle, onOpen = { menu = true }) {
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuItem(Icons.Outlined.Edit, R.string.browser_rename) { menu = false; onAction(RowAction.RENAME) }
                MenuItem(Icons.AutoMirrored.Outlined.DriveFileMove, R.string.browser_move) { menu = false; onAction(RowAction.MOVE) }
                MenuItem(Icons.Outlined.SaveAlt, R.string.browser_save_folder) { menu = false; onAction(RowAction.EXPORT) }
                MenuItem(Icons.Outlined.DownloadForOffline, R.string.browser_cache) { menu = false; onAction(RowAction.CACHE) }
                MenuItem(Icons.Outlined.Delete, R.string.browser_delete, destructive = true) { menu = false; onAction(RowAction.DELETE) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: FileItem,
    previews: Previews,
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
    val export = rememberLauncherForActivityResult(SaveAs()) { uri ->
        if (uri != null) onExport(uri)
    }
    val ready = file.status == FileStatus.READY
    // Until its part 0 is in Saved Messages a rename would lose to it (SPEC §5.2), so uploads are left alone.
    val settled = file.status != FileStatus.UPLOADING
    val idle = !file.deleting
    val haptic = LocalHapticFeedback.current
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
                onLongClick = {
                    if (file.selectable) {
                        if (!selecting) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggle()
                    } else if (!selecting) {
                        menu = true
                    }
                },
            )
            .alpha(if (idle && (!selecting || file.selectable)) 1f else DELETING_ALPHA)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val previewsOn by previews.enabled.collectAsStateWithLifecycle()
        // Made while the row is on screen; scrolled away, the work stops with it.
        val preview by produceState(previews.cached(file.fileId), file.fileId, file.status, previewsOn) {
            value = if (previewsOn) previews.cached(file.fileId) ?: previews.preview(file) else null
        }
        PickTile(selecting, selected, fileTypeIcon(file.name), cached = file.cached, preview = preview)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            // Two lines for every file: a status takes the date's place, in its colour, instead of adding a line.
            val status = if (!idle) R.string.browser_status_deleting else file.status.label()
            val statusColor = if (file.status == FileStatus.DAMAGED && idle) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            val date = fileDate(file.modifiedAt)
            val details = buildAnnotatedString {
                append(Formatter.formatShortFileSize(context, file.size))
                append(" · ")
                if (status == null) append(date) else withStyle(SpanStyle(color = statusColor)) { append(stringResource(status)) }
            }
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RowMenu(selecting, idle, onOpen = { menu = true }) {
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // What the short status on the row means, where there is room for it.
                file.status.explanation()?.let { explanation ->
                    Text(
                        stringResource(explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (file.status == FileStatus.DAMAGED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
                MenuItem(Icons.Outlined.FileOpen, R.string.browser_open, enabled = ready) { menu = false; onOpen() }
                MenuItem(Icons.Outlined.SaveAlt, R.string.browser_export, enabled = ready) { menu = false; export.launch(file.name) }
                MenuItem(Icons.Outlined.DownloadForOffline, R.string.browser_cache, enabled = ready && !file.cached) { menu = false; onAction(RowAction.CACHE) }
                MenuItem(Icons.Outlined.Edit, R.string.browser_rename, enabled = settled) { menu = false; onAction(RowAction.RENAME) }
                MenuItem(Icons.AutoMirrored.Outlined.DriveFileMove, R.string.browser_move, enabled = settled) { menu = false; onAction(RowAction.MOVE) }
                MenuItem(Icons.Outlined.Delete, R.string.browser_delete, enabled = settled, destructive = true) { menu = false; onAction(RowAction.DELETE) }
            }
        }
    }
}

/** A row whose delete is on the way: still there, but nothing to do with it. */
private const val DELETING_ALPHA = 0.5f

/**
 * A row's menu button. While picking it fades out but keeps its place, so the row's text does not jump
 * across when the picking starts.
 */
@Composable
private fun RowMenu(selecting: Boolean, idle: Boolean, onOpen: () -> Unit, menu: @Composable () -> Unit) {
    val shown by animateFloatAsState(if (selecting) 0f else 1f, Motion.standard(), label = "row menu")
    Box(Modifier.alpha(shown)) {
        IconButton(onClick = onOpen, enabled = idle && !selecting) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
        menu()
    }
}

/** Today's and yesterday's by name, at a glance; older ones by their date. */
@Composable
private fun fileDate(millis: Long): String {
    val context = LocalContext.current
    return when (dayOf(millis, System.currentTimeMillis())) {
        Day.TODAY -> stringResource(R.string.browser_date_today, DateFormat.getTimeFormat(context).format(Date(millis)))
        Day.YESTERDAY -> stringResource(R.string.browser_date_yesterday)
        Day.OLDER -> DateFormat.getMediumDateFormat(context).format(Date(millis))
    }
}

@Composable
private fun selectedBackground(selected: Boolean): Color {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent,
        Motion.standard(),
        label = "picked",
    )
    return color
}

/**
 * The type tile or the file's preview. While picking, every tile gets a round check box on its corner,
 * empty or ticked, so what can be picked shows as plainly as what is; the picture stays to pick by. A
 * file whole on the phone has a green check on the opposite corner.
 */
@Composable
private fun PickTile(selecting: Boolean, selected: Boolean, icon: Pair<ImageVector, Color>, cached: Boolean = false, preview: ImageBitmap? = null) {
    Box {
        if (preview != null) PreviewTile(preview, icon) else TypeTile(icon)
        if (cached) CachedBadge(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
        AnimatedVisibility(
            selecting,
            enter = fadeIn(Motion.quick()) + scaleIn(Motion.quick(), initialScale = 0.6f),
            exit = fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.6f),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
        ) {
            PickMark(selected)
        }
    }
}

/** A round check box in a ring of the background: empty, or filled and ticked. */
@Composable
private fun PickMark(selected: Boolean) {
    val fill by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, Motion.quick(), label = "pick fill")
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(2.dp)
            .clip(CircleShape).background(fill)
            .then(if (selected) Modifier else Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, stringResource(R.string.browser_cd_picked), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
    }
}

/** A small green check in a ring of the background, so it stands apart from the tile under it. */
@Composable
private fun CachedBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(17.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(1.5.dp).clip(CircleShape).background(CachedGreen),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Check, stringResource(R.string.browser_cd_cached), tint = Color.White, modifier = Modifier.size(11.dp))
    }
}

private val CachedGreen = Hues.BrightGreen

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
                    AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
private fun SelectionActions(onMove: () -> Unit, canSave: Boolean, onSave: () -> Unit, onCache: () -> Unit, onDelete: () -> Unit) {
    // The same card as the mini player it takes the place of, so one gives way to the other.
    FloatingCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction(R.string.browser_move, Icons.AutoMirrored.Outlined.DriveFileMove, onMove)
            SelectionAction(R.string.browser_save, Icons.Outlined.SaveAlt, onSave, enabled = canSave)
            SelectionAction(R.string.browser_cache_short, Icons.Outlined.DownloadForOffline, onCache)
            SelectionAction(R.string.browser_delete, Icons.Outlined.Delete, onDelete, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SelectionAction(label: Int, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(Corners.Tile))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** A file's state in a word, for its row. */
private fun FileStatus.label() = when (this) {
    FileStatus.UPLOADING -> R.string.browser_status_uploading
    FileStatus.INCOMPLETE -> R.string.browser_status_incomplete_short
    FileStatus.DAMAGED -> R.string.browser_status_damaged_short
    FileStatus.READY -> null
}

/** What that word means, at the top of the file's menu; the upload says it plainly enough. */
private fun FileStatus.explanation() = when (this) {
    FileStatus.INCOMPLETE -> R.string.browser_status_incomplete
    FileStatus.DAMAGED -> R.string.browser_status_damaged
    FileStatus.UPLOADING, FileStatus.READY -> null
}

/** Transfers in progress or waiting, one card each (SPEC §9); over them, how far the whole run is. */
@Composable
fun TransfersScreen(
    transfers: List<TransferItem>,
    run: TransferRegistry.Run?,
    offline: Boolean,
    previews: Previews,
    onBack: () -> Unit,
    onCancel: (TransferItem) -> Unit,
    onRetry: (TransferItem) -> Unit,
    onCancelAll: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
) {
    BackHandler(onBack = onBack)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    CofferScaffold(
        title = stringResource(R.string.browser_transfers),
        onBack = onBack,
        modifier = modifier,
        snackbar = snackbar,
        actions = {
            AnimatedVisibility(transfers.isNotEmpty(), enter = fadeIn(Motion.standard()), exit = fadeOut(Motion.quick())) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
                    AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem(Icons.Outlined.Close, R.string.browser_transfers_cancel_all, destructive = true) {
                            menuOpen = false
                            confirmCancelAll = true
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (transfers.isEmpty()) {
            EmptyStateView(
                title = stringResource(R.string.browser_no_transfers),
                subtitle = stringResource(R.string.browser_no_transfers_hint),
                hero = Hero.GHOST,
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            )
            return@CofferScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // A single transfer says it all on its own card.
            if (run != null && run.total > 1) {
                item(key = "summary") { QueueHeader(run, transfers, Modifier.animateItem().padding(bottom = 12.dp)) }
            }
            // Keyed by transfer: one that ends takes its own state with it, not its neighbour's.
            itemsIndexed(transfers, key = { _, item -> item.id }) { index, item ->
                TransferCard(item, groupShape(index, transfers.size), offline, previews, onCancel, onRetry, Modifier.animateItem())
            }
        }
    }
    if (confirmCancelAll) {
        AppDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text(stringResource(R.string.browser_transfers_cancel_all_title)) },
            text = { Text(stringResource(R.string.browser_transfers_cancel_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    onCancelAll()
                }) { Text(stringResource(R.string.browser_transfers_cancel_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCancelAll = false }) { Text(stringResource(R.string.browser_transfers_keep)) } },
        )
    }
}

/** The run as the notification tells it: how many of how many are done, and how far it all is. */
@Composable
private fun QueueHeader(run: TransferRegistry.Run, transfers: List<TransferItem>, modifier: Modifier = Modifier) {
    val summary = QueueSummary.of(transfers.map { it.kind }, run.finished, run.total, transfers.filter { it.progress != null }.map { it.kind to it.progress })
    Box(modifier) {
        GroupedBox(RoundedCornerShape(Corners.Panel)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.transfers_done_of, run.total, run.finished, run.total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                summary.percent?.let { Text(stringResource(R.string.browser_transfer_percent, it), style = MaterialTheme.typography.labelLarge.tabular()) }
            }
            Spacer(Modifier.height(10.dp))
            TransferBar(summary.percent?.let { it / 100f })
        }
    }
}

/** One transfer: the file as the list shows it, what is happening to it, and what can be done about it. */
@Composable
private fun TransferCard(
    item: TransferItem,
    shape: Shape,
    offline: Boolean,
    previews: Previews,
    onCancel: (TransferItem) -> Unit,
    onRetry: (TransferItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val operation = item.operation
    if (operation != null) {
        OperationCard(item, operation, shape, offline, modifier)
        return
    }
    Row(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransferTile(item, previews)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name ?: stringResource(R.string.browser_transfer_unnamed),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(6.dp))
            TransferStatus(item, offline)
        }
        if (item.failure != null) IconButton(onClick = { onRetry(item) }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.browser_retry)) }
        IconButton(onClick = { onCancel(item) }) { Icon(Icons.Outlined.Close, stringResource(R.string.browser_cancel)) }
    }
}

/**
 * One action's operations on their way to Telegram: what they do, how many have gone, and Telegram's
 * pause when it asks for one. Not cancelled: cut short, a folder brought in would lose its files.
 */
@Composable
private fun OperationCard(item: TransferItem, operation: OperationBatch, shape: Shape, offline: Boolean, modifier: Modifier = Modifier) {
    val (icon, title) = when (operation.kind) {
        OpKind.CREATE_FOLDERS -> Icons.Outlined.CreateNewFolder to pluralStringResource(R.plurals.browser_op_create_folders, operation.total, operation.total)
        OpKind.RENAME -> Icons.Outlined.DriveFileRenameOutline to stringResource(R.string.browser_op_rename)
        OpKind.MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove to pluralStringResource(R.plurals.browser_op_move, operation.total, operation.total)
    }
    Row(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TypeTile(icon to MaterialTheme.colorScheme.primary)
            KindBadge(TransferKind.OPS, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            val running = item.progress != null
            if (running) {
                TransferBar(if (operation.total > 0) operation.sent.toFloat() / operation.total else null)
                Spacer(Modifier.height(6.dp))
            }
            val wait = item.wait
            val line = when {
                offline -> stringResource(R.string.browser_transfer_wait_network)
                wait != null -> waitLabel(wait)
                running || operation.total > 1 -> stringResource(R.string.browser_op_sent, operation.sent, operation.total)
                else -> stringResource(R.string.browser_transfer_waiting)
            }
            Text(line, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The file's tile or preview, as in the list, with which way it goes on the corner. */
@Composable
private fun TransferTile(item: TransferItem, previews: Previews) {
    val icon = fileTypeIcon(item.name.orEmpty())
    val file = item.file
    val previewsOn by previews.enabled.collectAsStateWithLifecycle()
    val preview by produceState(file?.let { previews.cached(it.fileId) }, file?.fileId, file?.status, previewsOn) {
        value = if (previewsOn && file != null) previews.cached(file.fileId) ?: previews.preview(file) else null
    }
    Box {
        val shown = preview
        if (shown != null) PreviewTile(shown, icon) else TypeTile(icon)
        KindBadge(item.kind, Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp))
    }
}

/** Up, down, or down into the cache (green, as the cached check in the list), in a ring of the card. */
@Composable
private fun KindBadge(kind: TransferKind, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val (icon, label, color) = when (kind) {
        TransferKind.UPLOAD, TransferKind.OPS -> Triple(Icons.Outlined.ArrowUpward, R.string.browser_cd_upload, primary)
        TransferKind.DOWNLOAD -> Triple(Icons.Outlined.ArrowDownward, R.string.browser_cd_download, primary)
        TransferKind.CACHE -> Triple(Icons.Outlined.ArrowDownward, R.string.browser_cd_caching, CachedGreen)
    }
    Box(
        modifier.size(17.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(1.5.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), tint = if (color == primary) MaterialTheme.colorScheme.onPrimary else Color.White, modifier = Modifier.size(11.dp))
    }
}

/** A bar that glides to each new value instead of jumping from one report to the next; with nothing to measure, it runs. */
@Composable
private fun TransferBar(fraction: Float?) {
    if (fraction == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val shown by animateFloatAsState(fraction, Motion.standard(), label = "transfer progress")
    LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
}

/**
 * What caching the picked files would take, before it starts: their size, and a word when the cache is
 * off, too small to hold them all, or the phone has no room. Too small offers the limit that would do.
 */
@Composable
private fun CacheOfferDialog(offer: CacheOffer, onCache: (Int?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val count = offer.fileIds.size
    val raiseTo = offer.raiseTo
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_cache_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val amount = pluralStringResource(R.plurals.browser_cache_amount, count, count, size(offer.totalBytes))
                val missing = if (offer.missingBytes < offer.totalBytes) " " + stringResource(R.string.browser_cache_to_download, size(offer.missingBytes)) else ""
                Text(amount + missing)
                Text(stringResource(R.string.browser_cache_info))
                if (!offer.prefs.enabled) Text(stringResource(R.string.browser_cache_off))
                if (!offer.fits) Text(stringResource(R.string.browser_cache_over_limit, CachePrefs.limitLabel(offer.prefs.limitMb)), color = MaterialTheme.colorScheme.primary)
                if (!offer.enoughSpace) Text(stringResource(R.string.browser_cache_no_space, size(offer.freeBytes)), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (raiseTo != null) {
                TextButton(onClick = { onCache(raiseTo) }, enabled = offer.enoughSpace) {
                    Text(stringResource(R.string.browser_cache_raise, CachePrefs.limitLabel(raiseTo)))
                }
            } else {
                TextButton(onClick = { onCache(null) }, enabled = offer.enoughSpace) {
                    Text(stringResource(if (offer.fits) R.string.browser_cache_confirm else R.string.browser_cache_anyway))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) }
                // Too much for the present limit: raising it is offered first, keeping it stays possible.
                if (raiseTo != null) TextButton(onClick = { onCache(null) }, enabled = offer.enoughSpace) { Text(stringResource(R.string.browser_cache_anyway)) }
            }
        },
    )
}

private fun Connection.label() = when (this) {
    Connection.WAITING_FOR_NETWORK -> R.string.browser_connection_waiting
    Connection.CONNECTING -> R.string.browser_connection_connecting
    Connection.UPDATING -> R.string.browser_connection_updating
    Connection.READY -> null
}

/**
 * What a transfer is doing: an error, a wait and what for, or a bar with how much of how much has gone.
 * Without network nothing goes anywhere, so that is what every transfer says then.
 */
@Composable
private fun TransferStatus(item: TransferItem, offline: Boolean) {
    val context = LocalContext.current
    val progress = item.progress
    val small = MaterialTheme.typography.bodySmall
    if (item.failure != null) {
        Text(stringResource(item.failure.label()), color = MaterialTheme.colorScheme.error, style = small)
        return
    }
    val started = progress != null && progress.phase != TransferRegistry.Phase.QUEUED
    // A part being read or encrypted moves nothing on the wire: a running bar, not a stuck one.
    val fraction = if (progress != null && progress.phase == TransferRegistry.Phase.MOVING && progress.total > 0) progress.done.toFloat() / progress.total else null
    if (item.preparing || started) {
        TransferBar(fraction)
        Spacer(Modifier.height(6.dp))
    }
    val wait = item.wait
    val line = when {
        item.preparing -> stringResource(R.string.browser_transfer_preparing)
        offline -> stringResource(R.string.browser_transfer_wait_network)
        wait != null -> waitLabel(wait)
        progress == null -> stringResource(R.string.browser_transfer_waiting)
        progress.phase == TransferRegistry.Phase.QUEUED -> stringResource(R.string.browser_transfer_queued)
        else -> {
            val amount = stringResource(
                R.string.transfers_amount,
                Formatter.formatShortFileSize(context, progress.done),
                Formatter.formatShortFileSize(context, progress.total),
            )
            if (fraction != null) amount else stringResource(R.string.transfers_preparing) + " · " + amount
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            line,
            style = small.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (fraction != null && progress != null) {
            Text(stringResource(R.string.browser_transfer_percent, progress.percent()), style = MaterialTheme.typography.labelLarge.tabular())
        }
    }
}

/** What a transfer waits for, counting down to when it tries again. */
@Composable
private fun waitLabel(wait: TransferRegistry.Waiting): String {
    val left by produceState(wait.until - System.currentTimeMillis(), wait) {
        while (value > 0) {
            // On the second, so the count steps evenly.
            delay(value % 1_000 + 1)
            value = wait.until - System.currentTimeMillis()
        }
    }
    val clock = countdown(left)
    return when (wait.reason) {
        TransferRegistry.Wait.FLOOD -> stringResource(R.string.browser_transfer_wait_flood, clock)
        TransferRegistry.Wait.RETRY -> stringResource(R.string.browser_transfer_wait_retry, clock)
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

// A file opening: it comes up out of the list a little, rather than only fading in over it.
private val viewerEnter = fadeIn(Motion.screen()) + scaleIn(Motion.screen(), initialScale = VIEWER_SCALE)
private val viewerExit = fadeOut(Motion.standard()) + scaleOut(Motion.standard(), targetScale = VIEWER_SCALE)
private const val VIEWER_SCALE = 0.96f

private const val UNDO_MARGIN_MILLIS = 500L
