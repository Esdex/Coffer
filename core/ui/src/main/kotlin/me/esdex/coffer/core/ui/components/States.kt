package me.esdex.coffer.core.ui.components

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
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** An empty list: a title, and under it what to do about it. */
@Composable
fun EmptyStateView(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
