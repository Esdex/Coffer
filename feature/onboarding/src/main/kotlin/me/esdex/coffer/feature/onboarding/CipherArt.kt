package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

/**
 * What encrypting means, for the welcome page that says the files are encrypted on the phone: a few
 * names as the files list shows them, which then turn, left to right, into restless noise in the accent
 * colour; the noise boils a moment, the names come back, and it goes round again. Monospaced, so a line
 * keeps its width whichever it shows. Nothing real is encrypted here: it is a picture.
 */
@Composable
internal fun CipherArt(modifier: Modifier = Modifier) {
    val time by rememberInfiniteTransition(label = "cipher").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes { durationMillis = CYCLE; 0f at 0 using LinearEasing; 1f at CYCLE }),
        label = "cipher time",
    )
    val ms = time * CYCLE
    // How far along its line the noise has come: none while the names rest, all of it while it boils.
    val sweep = ((ms - READABLE) / SWEEP).coerceIn(0f, 1f).let { if (ms >= READABLE + SWEEP + BOIL) 0f else it }
    // The noise changes a few times a second, not every frame, so it flickers rather than blurs.
    val flicker = (ms / FLICKER_MS).toInt()
    val plain = MaterialTheme.colorScheme.onSurface
    val noise = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(Corners.Large)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            NAMES.forEachIndexed { line, name ->
                val shown = (sweep * name.length).toInt()
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = noise)) {
                            for (i in 0 until shown) append(NOISE[((line * 31 + i * 7 + flicker * 13) % NOISE.length)])
                        }
                        withStyle(SpanStyle(color = plain)) { append(name.drop(shown)) }
                    },
                    fontFamily = FontFamily.Monospace,
                    // A tenth over titleMedium, as are the card's gaps (Esdex, 2026-11-13): drawn larger rather than scaled up, so it stays sharp.
                    style = MaterialTheme.typography.titleMedium.let { it.copy(fontSize = it.fontSize * 1.1f, lineHeight = it.lineHeight * 1.1f) },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Names as the files list shows them, padded to one width so the card does not change size. */
private val NAMES = listOf("holiday.jpg ", "Trip 2025/  ", "passport.pdf")

/** What the noise is drawn from: the look of encrypted bytes written out, not the bytes themselves. */
private const val NOISE = "a9f3c7e1b5d08264x#%&@$~?!=+*<>{}[]0123456789abcdef"

private const val READABLE = 1_400
private const val SWEEP = Motion.REVEAL
private const val BOIL = 1_400
private const val CYCLE = READABLE + SWEEP + BOIL
private const val FLICKER_MS = 90f
