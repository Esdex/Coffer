package me.esdex.coffer.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.esdex.coffer.core.ui.theme.Corners
import me.esdex.coffer.core.ui.theme.Hues

/** The icon and colour a file gets by its name, the same as in Arcanum. */
fun fileTypeIcon(name: String): Pair<ImageVector, Color> = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> Icons.Outlined.Image to Hues.Blue
    "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp" -> Icons.Outlined.Videocam to Hues.Red
    "mp3", "m4a", "aac", "ogg", "flac", "wav", "opus" -> Icons.Outlined.AudioFile to Hues.Violet
    "xls", "xlsx", "csv", "ods" -> Icons.Outlined.TableChart to Hues.Green
    "zip", "rar", "7z", "tar", "gz", "bz2" -> Icons.Outlined.Archive to Hues.Brown
    "pdf" -> Icons.Outlined.PictureAsPdf to Hues.DeepRed
    else -> Icons.Outlined.Description to Hues.DeepBlue
}

val FolderIcon: Pair<ImageVector, Color> = Icons.Outlined.Folder to Hues.Amber

/** The icon in a rounded square of its own colour, at the head of a list row. */
@Composable
fun TypeTile(icon: Pair<ImageVector, Color>, modifier: Modifier = Modifier) {
    val (vector, color) = icon
    Box(modifier.size(TILE_SIZE).clip(RoundedCornerShape(Corners.Tile)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(vector, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

/** The file's own picture in the tile's place, with its type still marked on a corner. */
@Composable
fun PreviewTile(preview: ImageBitmap, icon: Pair<ImageVector, Color>) {
    val (vector, color) = icon
    Box(Modifier.size(TILE_SIZE).clip(RoundedCornerShape(Corners.Tile))) {
        Image(preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.BottomStart).padding(3.dp).size(16.dp).clip(RoundedCornerShape(Corners.Badge)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(vector, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
        }
    }
}

/** The side of a file's tile, in the list and wherever a file is shown in a row. */
val TILE_SIZE = 42.dp
