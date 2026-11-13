package me.esdex.coffer.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

/**
 * The animations that head a screen. Each is drawn in Compose, in the theme's colours, rather than
 * played from an animation file: all of Coffer's pictures are its own, under its own license (Esdex,
 * 2026-11-13, after the LottieFiles terms turned out to clash with GPLv3 and two files looked made for
 * other companies).
 */
enum class Hero(val loops: Boolean = true) {
    /** Something is on its way: dots rising in turn in a circle. */
    LOADING,

    /** Wanders to and fro where there is nothing to show. */
    GHOST,

    /** Something could not be shown: plays once, as the failed operations' does, rather than going on. */
    ERROR(loops = false),
}

/** An animation at the head of a screen, in a square of [size]. [speed] paces the ones that go on. */
@Composable
fun HeroAnimation(hero: Hero, modifier: Modifier = Modifier, size: Dp = 160.dp, speed: Float = 1f) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (hero) {
            Hero.LOADING -> LoadingDots(Modifier.fillMaxSize())
            Hero.GHOST -> Ghost(Modifier.fillMaxSize(), speed)
            Hero.ERROR -> ResultMark(success = false, Modifier.fillMaxSize(0.8f))
        }
    }
}

/**
 * The main action of a first-start screen: full width, 52 high, fully rounded, as in Arcanum's
 * onboarding. [loading] puts a spinner in the text's place while the step works: the button keeps its
 * size and colour, so nothing under it jumps, and a second tap does nothing.
 */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled || loading,
        shape = Corners.pill,
        modifier = modifier.fillMaxWidth().height(52.dp),
    ) {
        AnimatedContent(loading, transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) }, label = "pill") { working ->
            if (working) {
                CircularProgressIndicator(color = LocalContentColor.current, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
