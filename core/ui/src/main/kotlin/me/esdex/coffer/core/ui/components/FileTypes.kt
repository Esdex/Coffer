package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The icon and colour a file gets by its name, the same as in Arcanum. */
fun fileTypeIcon(name: String): Pair<ImageVector, Color> = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> Icons.Outlined.Image to Color(0xFF3B82F6)
    "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp" -> Icons.Outlined.Videocam to Color(0xFFEF4444)
    "mp3", "m4a", "aac", "ogg", "flac", "wav", "opus" -> Icons.Outlined.AudioFile to Color(0xFF8B5CF6)
    "xls", "xlsx", "csv", "ods" -> Icons.Outlined.TableChart to Color(0xFF16A34A)
    "zip", "rar", "7z", "tar", "gz", "bz2" -> Icons.Outlined.Archive to Color(0xFF92400E)
    "pdf" -> Icons.Outlined.PictureAsPdf to Color(0xFFDC2626)
    else -> Icons.Outlined.Description to Color(0xFF2563EB)
}

val FolderIcon: Pair<ImageVector, Color> = Icons.Outlined.Folder to Color(0xFFF59E0B)

/** The icon in a rounded square of its own colour, at the head of a list row. */
@Composable
fun TypeTile(icon: Pair<ImageVector, Color>, modifier: Modifier = Modifier) {
    val (vector, color) = icon
    Box(modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(vector, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}
