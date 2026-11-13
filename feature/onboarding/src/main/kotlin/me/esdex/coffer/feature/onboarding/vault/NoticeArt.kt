package me.esdex.coffer.feature.onboarding.vault

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Corners

/** One of Coffer's messages as Saved Messages shows it: what it looks like, and whether it is pinned. */
internal class NoticeRow(val icon: ImageVector, val text: String, val pinned: Boolean = false)

/** Coffer's notes: text messages starting coffer:, the vault header pinned. Made-up bodies in the real form. */
internal val NOTE_ROWS = listOf(
    NoticeRow(Icons.Outlined.ChatBubbleOutline, "coffer:v1:vault:pQ7x…", pinned = true),
    NoticeRow(Icons.Outlined.ChatBubbleOutline, "coffer:v2:op:Zx3q…"),
    NoticeRow(Icons.Outlined.ChatBubbleOutline, "coffer:v2:index:8kLm…"),
)

/** The parts of the files: documents named cfr_….bin. Made-up names in the real form, not anyone's own. */
internal val FILE_ROWS = listOf(
    NoticeRow(Icons.Outlined.InsertDriveFile, "cfr_1a2b3c4d5e6f.bin"),
    NoticeRow(Icons.Outlined.InsertDriveFile, "cfr_7f0e9d8c2b1a.bin"),
    NoticeRow(Icons.Outlined.InsertDriveFile, "cfr_c3d4e5f60718.bin"),
)

/**
 * Coffer's messages as Saved Messages shows them, [rows] of them, with a struck-out [badge] on the
 * corner: leave them be. The badge gives a little shake of the head once its page is [shown]. In the
 * theme's colours, the badge in the error ones.
 */
@Composable
internal fun NoticeArt(rows: List<NoticeRow>, badge: ImageVector, shown: Boolean, modifier: Modifier = Modifier) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = SHAKE_MS
                -14f at SHAKE_MS / 6
                12f at SHAKE_MS * 2 / 6
                -8f at SHAKE_MS * 3 / 6
                5f at SHAKE_MS * 4 / 6
                0f at SHAKE_MS
            },
        )
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box {
            Column(
                Modifier.clip(RoundedCornerShape(Corners.Large)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (row in rows) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(Corners.Small)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(row.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(row.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        if (row.pinned) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            // The badge, struck out, over the card's corner in a ring of the page.
            val strike = MaterialTheme.colorScheme.onErrorContainer
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 18.dp, y = 18.dp).rotate(shake.value)
                    .size(BADGE).clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(4.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer)
                    .drawWithContent {
                        drawContent()
                        val inset = size.width * 0.24f
                        drawLine(strike, Offset(inset, inset), Offset(size.width - inset, size.height - inset), strokeWidth = size.width * 0.07f, cap = StrokeCap.Round)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(BADGE * 0.48f))
            }
        }
    }
}

private val BADGE = 64.dp
private const val SHAKE_MS = 700
