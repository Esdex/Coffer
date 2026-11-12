package me.esdex.coffer.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.esdex.coffer.core.ui.components.GroupedBox
import me.esdex.coffer.core.ui.components.GroupedSwitch
import me.esdex.coffer.core.ui.components.SettingsGroup
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.LocalDarkMode
import me.esdex.coffer.core.ui.theme.TextPrefs
import me.esdex.coffer.feature.browser.viewer.text.Syntax
import me.esdex.coffer.feature.browser.viewer.text.SyntaxColors
import me.esdex.coffer.feature.browser.viewer.text.SyntaxHighlighter

/**
 * The text viewer's settings, with a few lines of it on top, after Arcanum's editor settings:
 * "monospace" and "show whitespace" mean little in words and everything shown (pre-release item 5).
 */
@Composable
internal fun TextSection(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val prefs by viewModel.text.collectAsStateWithLifecycle()
    fun set(change: (TextPrefs) -> TextPrefs) = viewModel.setText(change)
    SectionScaffold(stringResource(R.string.settings_card_text), onBack) {
        TextPreview(prefs)
        SettingsGroup(stringResource(R.string.settings_text_group_text)) {
            row { shape ->
                GroupedBox(shape) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_text_font_size), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_text_font_size_value, prefs.fontSizeSp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = prefs.fontSizeSp.toFloat(),
                        // A drag passes through every size; only a change is worth writing down.
                        onValueChange = { v -> v.toInt().takeIf { it != prefs.fontSizeSp }?.let { size -> set { it.copy(fontSizeSp = size) } } },
                        valueRange = TextPrefs.FONT_SIZES.first.toFloat()..TextPrefs.FONT_SIZES.last.toFloat(),
                        steps = TextPrefs.FONT_SIZES.count() - 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_text_monospace), checked = prefs.monospace, onCheckedChange = { on -> set { it.copy(monospace = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_text_word_wrap), checked = prefs.wordWrap,
                    info = stringResource(R.string.settings_text_word_wrap_info),
                    onCheckedChange = { on -> set { it.copy(wordWrap = on) } })
            }
        }
        SettingsGroup(stringResource(R.string.settings_text_group_marks)) {
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_text_line_numbers), checked = prefs.lineNumbers, onCheckedChange = { on -> set { it.copy(lineNumbers = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_text_whitespace), checked = prefs.showWhitespace, onCheckedChange = { on -> set { it.copy(showWhitespace = on) } })
            }
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_text_highlight), checked = prefs.highlight,
                    info = stringResource(R.string.settings_text_highlight_info),
                    onCheckedChange = { on -> set { it.copy(highlight = on) } })
            }
        }
        SettingsGroup(stringResource(R.string.settings_text_group_markdown)) {
            row { shape ->
                GroupedSwitch(shape, title = stringResource(R.string.settings_text_render_markdown), checked = prefs.renderMarkdown,
                    info = stringResource(R.string.settings_text_render_markdown_info),
                    onCheckedChange = { on -> set { it.copy(renderMarkdown = on) } })
            }
        }
    }
}

/**
 * A few lines drawn the way the viewer draws them. Markdown, because it has something to show for each
 * setting: a heading and a link for the colouring, indentation for the dots, a long line for the wrap.
 */
@Composable
private fun TextPreview(prefs: TextPrefs) {
    val colors = if (LocalDarkMode.current) SyntaxColors.Dark else SyntaxColors.Light
    val style = TextStyle(
        fontFamily = if (prefs.monospace) FontFamily.Monospace else FontFamily.Default,
        fontSize = prefs.fontSizeSp.sp,
        lineHeight = (prefs.fontSizeSp * 1.45f).sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val source = stringResource(R.string.settings_text_preview_sample)
    val shown = if (prefs.showWhitespace) source.replace(' ', '·') else source
    val text = if (prefs.highlight) SyntaxHighlighter.highlight(shown, Syntax.MARKDOWN, colors) else AnnotatedString(shown)
    val lines = source.count { it == '\n' } + 1

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(min = 150.dp)
            .clip(RoundedCornerShape(Corners.Panel))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 12.dp),
    ) {
        if (prefs.lineNumbers) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width((lines.toString().length * prefs.fontSizeSp * 0.62f + 20f).dp).padding(end = 8.dp)) {
                for (n in 1..lines) Text(n.toString(), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        Box(Modifier.weight(1f).padding(start = if (prefs.lineNumbers) 0.dp else 16.dp, end = 12.dp).then(if (prefs.wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))) {
            Text(text, style = style, softWrap = prefs.wordWrap)
        }
    }
}
