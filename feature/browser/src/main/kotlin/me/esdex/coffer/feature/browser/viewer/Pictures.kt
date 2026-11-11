package me.esdex.coffer.feature.browser.viewer

import android.content.res.AssetFileDescriptor
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.withSave
import kotlin.math.roundToInt

/**
 * Pictures from the vault, decoded from their copy in memory (see [MemoryCopy]): one path takes JPEG,
 * PNG, WebP, HEIF and GIF, animated ones included, turned the right way up by their EXIF orientation.
 */
internal object Pictures {

    /**
     * Decodes [descriptor], no larger than [maxDimension] on a side: a 50 MP photo is not held at full size
     * for a phone screen. A still comes back as a BitmapDrawable, an animated one as an
     * AnimatedImageDrawable. Null when the decoder refuses the file.
     */
    fun decode(descriptor: ParcelFileDescriptor, maxDimension: Int): Drawable? = try {
        val source = ImageDecoder.createSource { AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH) }
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > maxDimension || info.size.height / sample > maxDimension) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    } catch (_: Exception) {
        null
    }

    /** Loops forever rather than freezing on the last frame of a GIF that declares one pass. */
    fun play(drawable: Drawable) {
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
    }

    fun stop(drawable: Drawable?) {
        if (drawable is AnimatedImageDrawable && drawable.isRunning) drawable.stop()
    }
}

private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Paints a platform Drawable in Compose, after Arcanum's DrawablePainter: an AnimatedImageDrawable
 * only animates through its Callback, which here bumps a counter the drawing reads and posts the
 * next frames on the main looper.
 */
internal class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {

    private var redraws by mutableIntStateOf(0)

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            redraws++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            mainHandler.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            mainHandler.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        } else {
            Size.Unspecified
        }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            redraws // read, so the next frame repaints this
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            canvas.nativeCanvas.withSave { drawable.draw(this) }
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
    }

    override fun onAbandoned() {
        drawable.callback = null
    }

    override fun onForgotten() {
        drawable.callback = null
    }
}

@Composable
internal fun rememberDrawablePainter(drawable: Drawable): Painter = remember(drawable) { DrawablePainter(drawable) }
