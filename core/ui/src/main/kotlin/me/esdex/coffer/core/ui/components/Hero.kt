package me.esdex.coffer.core.ui.components

import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import me.esdex.coffer.core.ui.R
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

/** The animations the first screens use, from Arcanum. */
enum class Hero(@RawRes val raw: Int) {
    HELLO(R.raw.hello_wave),
    VAULT(R.raw.vault),
    SHIELD(R.raw.shield),
    LOADING(R.raw.loading),

    /** Wanders to and fro where there is nothing to show, as in Arcanum's empty lists. */
    GHOST(R.raw.ghost),
}

/** A looping animation at the head of a screen. */
@Composable
fun HeroAnimation(hero: Hero, modifier: Modifier = Modifier, size: Dp = 160.dp, speed: Float = 1f) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(hero.raw))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever, speed = speed)
    LottieAnimation(composition, progress = { progress }, modifier = modifier.size(size))
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
