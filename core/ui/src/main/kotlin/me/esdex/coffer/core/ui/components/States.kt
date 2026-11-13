package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import me.esdex.coffer.core.ui.R

/**
 * An empty list: a title, and under it what to do about it. Over them [hero], an animation (the ghost
 * where there is nothing to show, as in Arcanum; the error where something could not be), or else
 * [icon], a still one in a circle.
 */
@Composable
fun EmptyStateView(title: String, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null, hero: Hero? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // What kind of empty this is, before a word is read.
            if (hero != null) {
                // Arcanum's size and pace for its empty lists: big enough to wander in, slow enough to be calm.
                HeroAnimation(hero, size = EMPTY_HERO_SIZE, speed = if (hero.loops) EMPTY_HERO_SPEED else 1f)
                Spacer(Modifier.height(16.dp))
            } else if (icon != null) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/** Something is on its way to this place on the screen: one size of spinner for the whole app. */
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(SPINNER_SIZE))
}

/** The spinner's size, for a place that draws its own in another colour. */
val SPINNER_SIZE = 40.dp

/** "This is running, wait": a looping animation over a title, and an optional line under it. */
@Composable
fun OperationLoading(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LottieAnimation(composition, iterations = LottieConstants.IterateForever, modifier = Modifier.size(160.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            }
        }
    }
}

/** "It finished": a check that plays once, a title and body, and a full-width button at the bottom. */
@Composable
fun OperationSuccess(
    title: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) = OperationResult(R.raw.success_check, title, body, onDone, modifier, FontWeight.Bold, extra)

/** "It did not work": the same shape as [OperationSuccess] with the other animation, so a failure is as much of a screen. */
@Composable
fun OperationFailure(title: String, onDone: () -> Unit, modifier: Modifier = Modifier, body: String? = null) =
    OperationResult(R.raw.error, title, body, onDone, modifier, FontWeight.SemiBold, null)

@Composable
private fun OperationResult(
    animation: Int,
    title: String,
    body: String?,
    onDone: () -> Unit,
    modifier: Modifier,
    weight: FontWeight,
    extra: (@Composable ColumnScope.() -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(animation))
    val progress by animateLottieCompositionAsState(composition, iterations = 1)
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    Column(modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LottieAnimation(composition, progress = { progress }, modifier = Modifier.size(180.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = weight, textAlign = TextAlign.Center)
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
                extra?.invoke(this)
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.ui_done), fontWeight = FontWeight.SemiBold)
        }
    }
}

private val EMPTY_HERO_SIZE = 200.dp
private const val EMPTY_HERO_SPEED = 0.8f
