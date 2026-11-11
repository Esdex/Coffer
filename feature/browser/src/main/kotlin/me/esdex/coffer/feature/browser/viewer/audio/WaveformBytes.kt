package me.esdex.coffer.feature.browser.viewer.audio

import kotlin.math.roundToInt

/** A finished waveform as kept on the phone: one byte a bar, 0 to 255. */
object WaveformBytes {
    /** How many bars a waveform has, as in Arcanum. */
    const val BARS = 80

    fun encode(bars: List<Float>): ByteArray {
        require(bars.size == BARS) { "a waveform has $BARS bars" }
        return ByteArray(BARS) { (bars[it].coerceIn(0f, 1f) * 255).roundToInt().toByte() }
    }

    /** Null for anything but [BARS] bytes: kept by another version, or damaged. */
    fun decode(bytes: ByteArray): List<Float>? =
        if (bytes.size != BARS) null else bytes.map { (it.toInt() and 0xFF) / 255f }
}
