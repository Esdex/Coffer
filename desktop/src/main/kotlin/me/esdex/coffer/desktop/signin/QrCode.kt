package me.esdex.coffer.desktop.signin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * [content] as a QR code, dark on white in every theme: scanners need the contrast and the quiet zone.
 * The Android app's (feature/onboarding QrCode.kt), which lives in an Android module.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) { Encoder.encode(content, ErrorCorrectionLevel.M).matrix }
    Canvas(modifier.aspectRatio(1f)) {
        val modules = matrix.width + 2 * QUIET_ZONE
        val cell = size.minDimension / modules
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y).toInt() == 1) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset((x + QUIET_ZONE) * cell, (y + QUIET_ZONE) * cell),
                        // A hair wider than the cell, so no seams show between neighbours.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

private const val QUIET_ZONE = 4
