package me.esdex.coffer.feature.browser.viewer.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** How a finished waveform is kept on the phone. */
class WaveformBytesTest {
    @Test
    fun aWaveformKeepsItsShapeInOneByteABar() {
        val bars = List(WaveformBytes.BARS) { it / 79f }
        val bytes = WaveformBytes.encode(bars)
        assertEquals(80, bytes.size)
        assertEquals(0, bytes.first().toInt())
        assertEquals(255, bytes.last().toInt() and 0xFF)
        val back = WaveformBytes.decode(bytes)!!
        back.forEachIndexed { i, h -> assertEquals(bars[i], h, 0.5f / 255) }
        assertArrayEquals(bytes, WaveformBytes.encode(back))
        // Out of range is held to it, and any other length is not a waveform.
        assertEquals(255, WaveformBytes.encode(List(80) { 1.5f })[0].toInt() and 0xFF)
        assertNull(WaveformBytes.decode(ByteArray(79)))
        assertThrows(IllegalArgumentException::class.java) { WaveformBytes.encode(List(81) { 0f }) }
    }
}
