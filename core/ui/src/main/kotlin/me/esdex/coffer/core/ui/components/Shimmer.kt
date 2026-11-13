package me.esdex.coffer.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import me.esdex.coffer.core.ui.theme.LocalDarkMode
import me.esdex.coffer.core.ui.theme.Motion

/**
 * Text that says something is under way: grey, with a band of light running across it again and again.
 * White light in the dark themes; in the light one the band is the text's own dark, as white there would
 * wipe the words out for a moment.
 */
@Composable
fun ShimmerText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val light = if (LocalDarkMode.current) Color.White else MaterialTheme.colorScheme.onSurface
    var width by remember { mutableFloatStateOf(0f) }
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(Motion.linear(Motion.SHIMMER), RepeatMode.Restart),
        label = "shimmer sweep",
    )
    // The band starts off the left edge and leaves off the right one, so each pass is whole.
    val band = width * BAND
    val centre = -band + (width + 2 * band) * sweep
    val brush = Brush.linearGradient(listOf(base, light, base), start = Offset(centre - band, 0f), end = Offset(centre + band, 0f))
    Text(
        text,
        modifier = modifier,
        style = style.merge(TextStyle(brush = brush)),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { width = it.size.width.toFloat() },
    )
}

/** Half the width of the band of light, as a share of the text's width. */
private const val BAND = 0.35f
