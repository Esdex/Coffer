package me.esdex.coffer.feature.onboarding

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Motion

/**
 * Over signing in, what it is: an account, as a contact's picture, and a key on its corner that now and
 * then turns as in a lock and back (Esdex's idea, 2026-11-13: a person and a key say "signing in" where a
 * wave and a padlock sending to a message did not). In the theme's colours.
 */
@Composable
internal fun SignInHero() {
    val turn by rememberInfiniteTransition(label = "sign-in").animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = CYCLE
                0f at 0
                0f at REST using Motion.Easing
                TURNED at REST + Motion.SCREEN
                TURNED at REST + Motion.SCREEN + HOLD using Motion.Easing
                0f at CYCLE
            },
        ),
        label = "key turn",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(HERO)) {
            Box(
                Modifier.size(AVATAR).align(Alignment.Center).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(AVATAR * 0.62f))
            }
            // On the corner, in a ring of the page, as the cached check sits on a file's tile.
            Box(
                Modifier.size(KEY_BADGE).align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(4.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(KEY_BADGE * 0.42f).rotate(turn))
            }
        }
    }
}

private val HERO = 140.dp
private val AVATAR = 112.dp
private val KEY_BADGE = 58.dp

// A rest, a quarter turn as a key turns in a lock, a moment held, and back.
private const val REST = 1_200
private const val HOLD = 500
private const val CYCLE = REST + Motion.SCREEN + HOLD + Motion.SCREEN
private const val TURNED = -90f
