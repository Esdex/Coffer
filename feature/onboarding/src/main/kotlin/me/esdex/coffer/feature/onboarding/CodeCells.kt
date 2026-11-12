package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion
import me.esdex.coffer.core.ui.theme.tabular

/**
 * The code from Telegram as a row of cells, one character each, when Telegram has said how long it is.
 * The keyboard comes up with the step. Nothing is sent by itself: the button still does that.
 */
@Composable
internal fun CodeCells(value: String, length: Int, onChange: (String) -> Unit, isError: Boolean) {
    val focus = remember { FocusRequester() }
    val touch = remember { MutableInteractionSource() }
    val focused by touch.collectIsFocusedAsState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { onChange(CodeCells.clean(it, length)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        interactionSource = touch,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                repeat(length) { i ->
                    // The cell the next character goes into is marked while the field has the keyboard.
                    val next = focused && i == value.length.coerceAtMost(length - 1)
                    val edge by animateColorAsState(
                        when {
                            isError -> MaterialTheme.colorScheme.error
                            next -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                        Motion.quick(),
                        label = "code cell",
                    )
                    val shape = RoundedCornerShape(Corners.Small)
                    Box(
                        Modifier.size(48.dp, 56.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(if (next) 2.dp else 1.dp, edge, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(value.getOrNull(i)?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall.tabular())
                    }
                }
            }
            // The real field, out of sight: it takes the keyboard and the paste, the cells only show.
            Box(Modifier.size(1.dp).alpha(0f)) { field() }
        },
    )
}

internal object CodeCells {
    /** Code lengths drawn as cells; any other is typed into a plain field. */
    val LENGTHS = 3..8

    /** What may be typed or pasted: letters and digits, no more than the code holds. */
    fun clean(typed: String, length: Int): String = typed.filter(Char::isLetterOrDigit).take(length)
}
