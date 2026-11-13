package me.esdex.coffer.feature.onboarding

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import me.esdex.coffer.core.ui.theme.Motion

private val pages = listOf(
    // Coffer's own icon, where a waving hand from LottieFiles was (Esdex, 2026-11-13).
    IntroPage(R.string.welcome_title, R.string.welcome_what) { shown -> AppIconArt(shown, Modifier.size(INTRO_PICTURE)) },
    // Names turning into noise, for what "encrypted on this phone" means (Esdex, 2026-11-13).
    IntroPage(R.string.welcome_encrypt_title, R.string.welcome_encrypt) { CipherArt(Modifier.size(INTRO_PICTURE)) },
    // The key itself, for "Only you hold the key", rather than a shield (Esdex, 2026-11-13).
    IntroPage(R.string.welcome_api_title, R.string.welcome_api) { KeyArt(Modifier.size(INTRO_PICTURE)) },
    // Saved Messages itself, drawn, rather than a safe: that is where the files go (Esdex, 2026-11-13).
    IntroPage(R.string.welcome_saved_title, R.string.welcome_saved_messages) { shown -> SavedMessagesArt(shown, Modifier.size(INTRO_PICTURE)) },
)

/** The first-start explanation (SPEC §7, step 1), shown once per install; then [content]. Paged as in Arcanum. */
@Composable
fun WelcomeGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var seen by remember { mutableStateOf(prefs.getBoolean(WELCOME_SEEN, false)) }
    // The last page fades into signing in rather than being swapped for it.
    AnimatedContent(seen, transitionSpec = { fadeIn(Motion.screen()) togetherWith fadeOut(Motion.standard()) }, label = "welcome") { done ->
        if (done) {
            content()
        } else {
            IntroPager(pages, R.string.welcome_start, modifier) {
                prefs.edit { putBoolean(WELCOME_SEEN, true) }
                seen = true
            }
        }
    }
}

private const val PREFS = "onboarding"
private const val WELCOME_SEEN = "welcomeSeen"
