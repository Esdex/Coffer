package me.esdex.coffer.feature.onboarding

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.components.Hero
import me.esdex.coffer.core.ui.components.HeroAnimation
import me.esdex.coffer.core.ui.components.PillButton
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

private class WelcomePage(val title: Int, val text: Int, val hero: Hero)

private val pages = listOf(
    WelcomePage(R.string.welcome_title, R.string.welcome_what, Hero.HELLO),
    WelcomePage(R.string.welcome_api_title, R.string.welcome_api, Hero.SHIELD),
    WelcomePage(R.string.welcome_saved_title, R.string.welcome_saved_messages, Hero.VAULT),
)

/** The first-start explanation (SPEC §7, step 1), shown once per install; then [content]. Paged as in Arcanum. */
@Composable
fun WelcomeGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var seen by remember { mutableStateOf(prefs.getBoolean(WELCOME_SEEN, false)) }
    if (seen) {
        content()
        return
    }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize()) {
        // Only the button turns the page: every page has to be read before the vault is set up.
        HorizontalPager(state = pager, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) { index ->
            Page(pages[index])
        }
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 480.dp).padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { index ->
                    val selected = pager.currentPage == index
                    val width by animateDpAsState(if (selected) 24.dp else 8.dp, Motion.standard(), label = "dot")
                    val color by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        Motion.standard(),
                        label = "dot",
                    )
                    Box(Modifier.height(8.dp).width(width).clip(Corners.pill).background(color))
                }
            }
            val last = pager.currentPage == pages.lastIndex
            PillButton(
                text = stringResource(if (last) R.string.welcome_start else R.string.welcome_next),
                onClick = {
                    if (last) {
                        prefs.edit { putBoolean(WELCOME_SEEN, true) }
                        seen = true
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
            )
        }
    }
}

@Composable
private fun Page(page: WelcomePage) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HeroAnimation(page.hero, size = 220.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(page.text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        )
    }
}

private const val PREFS = "onboarding"
private const val WELCOME_SEEN = "welcomeSeen"
