package me.esdex.coffer.feature.browser.viewer.text

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.text.format.Formatter
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.ui.components.EmptyStateView
import me.esdex.coffer.core.ui.theme.LocalDarkMode
import me.esdex.coffer.core.ui.theme.TextPrefs
import me.esdex.coffer.feature.browser.R
import me.esdex.coffer.feature.browser.viewer.ViewerViewModel

/**
 * A text file, read from Saved Messages and shown (pre-release item 5), after Arcanum's text editor
 * with the editing taken out: line numbers, colouring and the other settings from Settings, Text
 * viewer. A Markdown file opens rendered or as its source, as the settings say, and the bar flips it.
 */
@Composable
fun TextViewerScreen(
    page: ViewerViewModel.Page,
    onClose: () -> Unit,
    onSave: (ViewerViewModel.Page) -> Unit,
    onOpenWith: (ViewerViewModel.Page) -> Unit,
    viewModel: TextViewModel = hiltViewModel(),
) {
    LaunchedEffect(page.fileId) { viewModel.open(page.fileId) }
    // Left in any way, the vault locking included: the text is let go.
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) onClose() }
    BackHandler(onBack = onClose)

    val markdown = TextFiles.isMarkdown(page.name)
    // Decided each time a file opens, from the setting; the bar flips it for this file only.
    var rendered by remember(page.fileId) { mutableStateOf(prefs.renderMarkdown) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Leaving the app from a file in the vault is not for a stray touch; the address is shown in full.
    pendingLink?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.viewer_link_title)) },
            text = { Text(url) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (_: ActivityNotFoundException) {
                    }
                }) { Text(stringResource(R.string.viewer_link_open)) }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null }) { Text(stringResource(R.string.browser_cancel)) } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_cd_back)) }
                Text(page.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (markdown && state.document != null) {
                    IconButton(onClick = { rendered = !rendered }) {
                        Icon(
                            if (rendered) Icons.Outlined.Code else Icons.Outlined.Visibility,
                            stringResource(if (rendered) R.string.viewer_text_show_source else R.string.viewer_text_show_rendered),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.browser_cd_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_export)) },
                            leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                            onClick = { menuOpen = false; onSave(page) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.viewer_open_with)) },
                            leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                            onClick = { menuOpen = false; onOpenWith(page) },
                        )
                    }
                }
            }

            val document = state.document
            if (document?.decodedAsFallback == true) {
                // Said once under the bar and left there: what is on screen may be wrong in places.
                Text(
                    stringResource(R.string.viewer_text_encoding_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                val failure = state.failure
                when {
                    state.loading -> CircularProgressIndicator()
                    failure != null -> EmptyStateView(
                        title = stringResource(
                            when (failure) {
                                TextViewModel.Failure.TOO_LARGE -> R.string.viewer_text_too_large
                                TextViewModel.Failure.NOT_TEXT -> R.string.viewer_text_not_text
                                TextViewModel.Failure.NOT_AVAILABLE -> R.string.viewer_text_not_available
                            },
                        ),
                        subtitle = when (failure) {
                            TextViewModel.Failure.TOO_LARGE -> stringResource(R.string.viewer_text_too_large_hint, Formatter.formatShortFileSize(context, TextDocument.MAX_BYTES))
                            TextViewModel.Failure.NOT_TEXT -> stringResource(R.string.viewer_text_not_text_hint)
                            TextViewModel.Failure.NOT_AVAILABLE -> stringResource(R.string.viewer_pdf_not_available_hint)
                        },
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    document == null -> Unit
                    markdown && rendered -> MarkdownView(document.text, prefs.fontSizeSp, onLink = { pendingLink = it }, modifier = Modifier.fillMaxSize())
                    else -> CodeText(document, prefs, SyntaxHighlighter.of(page.name))
                }
            }
        }
    }
}

@Composable
private fun CodeText(document: TextDocument, prefs: TextPrefs, syntax: Syntax) {
    val context = LocalContext.current
    // One view per document, kept while the Markdown side is on screen: laying a big file out again on
    // every flip is the cost the platform view is here to avoid. Wrapping is set before the text, or the
    // whole file would be laid out twice.
    val view = remember(document) {
        CodeTextView(context).apply {
            setWordWrap(prefs.wordWrap)
            setDocument(document.text)
        }
    }
    val colors = if (LocalDarkMode.current) SyntaxColors.Dark else SyntaxColors.Light
    val palette = remember(colors) { SyntaxHighlighter.paletteOf(colors) }
    val textColor = MaterialTheme.colorScheme.onBackground.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val gutterText = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gutterBack = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
    val frontBack = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        // The instance is remembered above; this only puts it back on screen, with no parent of its own.
        factory = { view },
        update = { v ->
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp.toFloat())
            v.typeface = if (prefs.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
            v.setTextColor(textColor)
            v.accentColor = accent
            v.gutterTextColor = gutterText
            v.gutterBackground = gutterBack
            v.frontMatterBackground = frontBack
            v.showLineNumbers = prefs.lineNumbers
            v.showWhitespace = prefs.showWhitespace
            v.palette = palette
            v.syntax = if (prefs.highlight) syntax else Syntax.NONE
            v.setWordWrap(prefs.wordWrap)
        },
    )
}
