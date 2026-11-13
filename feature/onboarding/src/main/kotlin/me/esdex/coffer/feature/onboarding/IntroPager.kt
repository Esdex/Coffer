package me.esdex.coffer.feature.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.components.PillButton
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Motion

/**
 * A page of a paged introduction: a picture over a title and a few words. [picture] is told whether its
 * page is the one on screen, for a picture that plays once when it is seen.
 */
internal class IntroPage(val title: Int, val text: Int, val picture: @Composable (shown: Boolean) -> Unit)

/** The pictures of the introductions: the size the welcome's animations have. */
internal val INTRO_PICTURE = 220.dp

/**
 * Pages read one after another, as in Arcanum: the welcome, and the note on Saved Messages after the
 * vault first opens, so both read alike. Only the button turns the page, every page has to be read;
 * back on a later page turns back a page, instead of leaving. The last page's button says [doneLabel].
 */
@Composable
internal fun IntroPager(pages: List<IntroPage>, doneLabel: Int, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
    Box(modifier.fillMaxSize()) {
        HorizontalPager(state = pager, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) { index ->
            IntroPageView(pages[index], shown = pager.currentPage == index)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 480.dp).padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // One page alone needs no dots.
            if (pages.size > 1) {
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
            }
            val last = pager.currentPage == pages.lastIndex
            PillButton(
                text = stringResource(if (last) doneLabel else R.string.welcome_next),
                onClick = {
                    if (last) {
                        onDone()
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
            )
        }
    }
}

@Composable
private fun IntroPageView(page: IntroPage, shown: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        page.picture(shown)
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
