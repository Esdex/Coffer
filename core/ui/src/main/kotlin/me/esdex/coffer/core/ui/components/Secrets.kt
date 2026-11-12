package me.esdex.coffer.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.R
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

/*
 * What every screen that handles the vault's secrets shares, so creating a vault and changing its
 * password look and behave alike.
 */

/**
 * A password, hidden as it is typed, with an eye to show it: a long password is easy to mistype and
 * impossible to check blind. Shown only until the eye is pressed again; never remembered past the screen.
 */
@Composable
fun PasswordField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    stringResource(if (visible) R.string.ui_hide_password else R.string.ui_show_password),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** A line of error under a field that opens and closes smoothly, so the form below it does not jump. */
@Composable
fun FieldError(visible: Boolean, text: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible,
        enter = expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
        exit = shrinkVertically(Motion.quick()) + fadeOut(Motion.quick()),
        modifier = modifier,
    ) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The recovery words on a card: three columns numbered top to bottom like the paper backup, the
 * numbers quieter than the words they count.
 */
@Composable
fun RecoveryWords(words: List<String>, modifier: Modifier = Modifier) {
    val number = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val rows = (words.size + 2) / 3
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Corners.Panel)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (column in 0 until 3) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0 until rows) {
                    val index = column * rows + row
                    val word = words.getOrNull(index) ?: continue
                    Text(
                        buildAnnotatedString {
                            withStyle(number) { append("%2d. ".format(index + 1)) }
                            append(word)
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * How strong a new password is: five segments filling up to [score] (0 to 4), in the accent colour once
 * it is [acceptable] and in red before; [label] says it in a word, [notes] say what would help.
 */
@Composable
fun StrengthMeter(score: Int, acceptable: Boolean, label: String, notes: List<String>) {
    val color by animateColorAsState(if (acceptable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, Motion.standard(), label = "strength")
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(STRENGTH_STEPS) { i ->
                val fill by animateFloatAsState(if (i <= score) 1f else 0f, Motion.standard(), label = "strength step")
                Box(Modifier.weight(1f).height(6.dp).clip(Corners.pill).background(lerp(empty, color, fill)))
            }
        }
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
        notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

private const val STRENGTH_STEPS = 5

