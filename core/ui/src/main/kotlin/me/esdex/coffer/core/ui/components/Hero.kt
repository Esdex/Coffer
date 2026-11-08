package me.esdex.coffer.core.ui.components

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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

/** The animations the first screens use, from Arcanum. */
enum class Hero(@RawRes val raw: Int) {
    HELLO(R.raw.hello_wave),
    VAULT(R.raw.vault),
    SHIELD(R.raw.shield),
    LOADING(R.raw.loading),
}

/** A looping animation at the head of a screen. */
@Composable
fun HeroAnimation(hero: Hero, modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(hero.raw))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    LottieAnimation(composition, progress = { progress }, modifier = modifier.size(size))
}

/** The main action of a first-start screen: full width, 52 high, fully rounded, as in Arcanum's onboarding. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(99.dp), modifier = modifier.fillMaxWidth().height(52.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
