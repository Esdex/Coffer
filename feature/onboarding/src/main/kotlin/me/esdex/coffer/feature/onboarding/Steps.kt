package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.components.FieldError
import me.esdex.coffer.core.ui.theme.Motion

/*
 * The parts every first-start step is made of, signing in and the vault alike, so each step reads the
 * same way: the animation, the title, what to do, the field, the main button, and the other ways
 * centred under it.
 */

@Composable
internal fun StepTitle(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
internal fun StepBody(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

/** The picture at the head of a step, centred, in a square of [STEP_HERO]. */
@Composable
internal fun StepHero(picture: @Composable (Modifier) -> Unit) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    picture(Modifier.size(STEP_HERO))
}

internal val STEP_HERO = 140.dp

/** Another way through a step, quieter than its main button and centred under it. */
@Composable
internal fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    TextButton(onClick = onClick, enabled = enabled) { Text(text) }
}

/**
 * What went wrong, under the field it is about, opening and closing smoothly. The last message stays
 * while it closes, so it does not blank out on the way.
 */
@Composable
internal fun StepError(message: String?) {
    var last by remember { mutableStateOf(message) }
    if (message != null) last = message
    FieldError(message != null, last.orEmpty())
}

/** One step to the next: forward comes in from the right, back from the left, both fading. */
internal fun <S> AnimatedContentTransitionScope<S>.stepSlide(forward: Boolean): ContentTransform {
    val way = if (forward) 1 else -1
    return (fadeIn(Motion.screen()) + slideInHorizontally(Motion.screen()) { it * way / STEP_SLIDE_PART }) togetherWith
        (fadeOut(Motion.standard()) + slideOutHorizontally(Motion.standard()) { -it * way / STEP_SLIDE_PART })
}

// A step slides by a quarter of the width: enough to show the way, not a whole page.
private const val STEP_SLIDE_PART = 4
