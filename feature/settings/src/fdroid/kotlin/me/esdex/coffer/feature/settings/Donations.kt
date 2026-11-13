package me.esdex.coffer.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import me.esdex.coffer.core.ui.theme.Corners
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** GitHub and F-Droid builds offer donations; the Play build has none (see its own Donations.kt). */
internal const val DONATIONS = true

/** One way to give: links open outside Coffer, wallet addresses are copied to the clipboard. */
private sealed interface DonationTarget {
    val label: Int
    val icon: Int

    /**
     * Ethereum's mark is white with no colour of its own, so it follows the theme, or it would vanish
     * on a light background. Every other mark carries its brand colour and is left as it is.
     */
    val tinted: Boolean get() = false

    data class Link(override val label: Int, override val icon: Int, val description: Int, val url: String) : DonationTarget

    data class Wallet(override val label: Int, override val icon: Int, val address: String, override val tinted: Boolean = false) :
        DonationTarget
}

// The same addresses as in Arcanum and in the README.
private val TARGETS = listOf(
    DonationTarget.Link(R.string.donations_github_sponsors, R.drawable.ic_coin_sponsors, R.string.donations_github_sponsors_desc, "https://github.com/sponsors/Esdex"),
    DonationTarget.Link(R.string.donations_kofi, R.drawable.ic_coin_kofi, R.string.donations_kofi_desc, "https://ko-fi.com/Esdex"),
    DonationTarget.Wallet(R.string.donations_bitcoin, R.drawable.ic_coin_bitcoin, "bc1qk3pjpxfzafpc56924m8hnyewcgmutchwrg4v2p"),
    DonationTarget.Wallet(R.string.donations_lightning, R.drawable.ic_coin_lightning, "esdex@cake.cash"),
    DonationTarget.Wallet(R.string.donations_ethereum, R.drawable.ic_coin_ethereum, "0xDc4B00d937e4a9633d37d70dDF56E8370f44E0f8", tinted = true),
    DonationTarget.Wallet(
        R.string.donations_monero,
        R.drawable.ic_coin_monero,
        "83xHcG9NNzLhsYQ9QoMcX2EFCwEPT1rSSa4EPgDMG3PqQEXVZ1vgaTtAq9x4zETjkRRK7CiH6giHshTLUJHTD4mCRBbt42s",
    ),
    DonationTarget.Wallet(R.string.donations_solana, R.drawable.ic_coin_solana, "GJgu5VqmEfxfQQbRpp9CDcYUxjsjUPJpHsCfyjRQGGSX"),
)

/**
 * The donations page, as Arcanum has it: a star, a few words, and the ways to give. The star is drawn
 * here rather than played from an animation file, as every picture in Coffer is; a tap on it fires
 * Arcanum's confetti.
 */
@Composable
internal fun DonationsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var burst by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        SectionScaffold(stringResource(R.string.donations_title), onBack, snackbar) {
            SupportStar(
                Modifier
                    .fillMaxWidth()
                    .height(STAR_HEIGHT)
                    // No ripple: a splash around a free-floating picture looks like a fault, not a press.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        burst++
                    },
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.donations_heading), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.donations_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(28.dp))
            TARGETS.forEach { target ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(target.label).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    when (target) {
                        is DonationTarget.Link -> DonationCard(
                            icon = { BrandMark(target) },
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target.url.toUri())) } },
                        ) {
                            Text(stringResource(target.description), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        is DonationTarget.Wallet -> DonationCard(
                            icon = { BrandMark(target) },
                            trailing = {
                                Box(
                                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val label = resources.getString(target.label)
                                copy(context, label, target.address)
                                // Android 13 and later say so themselves whenever an app copies something.
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.donations_copied, label)) }
                                }
                            },
                        ) {
                            // Cut short on purpose: a Monero address is 95 characters and would set the row's
                            // height on its own. A tap copies all of it.
                            Text(target.address, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        // Over the page, so pieces fall over the cards, with no pointer input of its own: taps get through.
        ConfettiOverlay(burst = burst, modifier = Modifier.fillMaxSize())
    }
}

private fun copy(context: Context, label: String, address: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, address))
}

/** The brand's own mark at full size: they carry their own colour and need no backdrop. */
@Composable
private fun BrandMark(target: DonationTarget) {
    if (target.tinted) {
        Icon(painterResource(target.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(40.dp))
    } else {
        Image(painterResource(target.icon), contentDescription = null, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun DonationCard(icon: @Composable () -> Unit, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Corners.Large)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape, modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f)) { content() }
            Spacer(Modifier.width(10.dp))
            if (trailing != null) {
                trailing()
            } else {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A gold star that breathes and sways a little, with a glow behind it that swells with it, where
 * Arcanum plays its star animation. The gold of Coffer's chest.
 */
@Composable
private fun SupportStar(modifier: Modifier) {
    val time by rememberInfiniteTransition(label = "star").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(STAR_CYCLE, easing = LinearEasing)),
        label = "star time",
    )
    Canvas(modifier) {
        val r = size.minDimension * 0.30f
        val breath = 1f + 0.05f * sin(time)
        drawCircle(
            Brush.radialGradient(listOf(GOLD.copy(alpha = 0.30f + 0.10f * sin(time)), Color.Transparent), center, r * 1.9f),
            radius = r * 1.9f,
        )
        val star = Path().apply {
            for (i in 0 until 10) {
                val radius = if (i % 2 == 0) r else r * 0.46f
                val angle = -PI / 2 + i * PI / 5
                val point = Offset(center.x + (radius * cos(angle)).toFloat(), center.y + (radius * sin(angle)).toFloat())
                if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        rotate(6f * sin(time * 2)) {
            scale(breath) {
                val fill = Brush.verticalGradient(listOf(GOLD_LIGHT, GOLD), startY = center.y - r, endY = center.y + r)
                drawPath(star, fill)
                // Rounded points, as the icons around it have.
                drawPath(star, fill, style = Stroke(width = r * 0.16f, join = StrokeJoin.Round))
            }
        }
    }
}

private val GOLD = Color(0xFFFAB927)
private val GOLD_LIGHT = Color(0xFFFFE082)
private val STAR_HEIGHT = 200.dp
private const val STAR_CYCLE = 4_000
