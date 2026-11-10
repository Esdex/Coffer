package me.esdex.coffer.feature.browser.viewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** How the viewer reads bytes as text; the expected strings are written out, not decoded by the same code. */
class TextDocumentTest {

    private fun doc(bytes: ByteArray) = TextDocument.decode(bytes)

    @Test
    fun utf8IsReadAsIs() {
        val d = doc(byteArrayOf(0x41, 0xD0.toByte(), 0x96.toByte(), 0x0A, 0x42))!!
        assertEquals("AЖ\nB", d.text)
        assertFalse(d.decodedAsFallback)
    }

    @Test
    fun lineBreaksOfEveryKindBecomeOne() {
        assertEquals("a\nb\nc\nd", doc("a\r\nb\rc\nd".toByteArray())!!.text)
    }

    @Test
    fun aByteOrderMarkIsNotShown() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x68, 0x69)
        assertEquals("hi", doc(utf8)!!.text)
        // UTF-16 has a NUL in every ASCII character; the mark is what makes it text.
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0x00, 0x69, 0x00)
        assertEquals("hi", doc(utf16le)!!.text)
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x68, 0x00, 0x69)
        assertEquals("hi", doc(utf16be)!!.text)
    }

    @Test
    fun anotherEncodingIsShownAsLatin1AndSaysSo() {
        // "Ж" in windows-1251 is 0xC6, which is not UTF-8 on its own; Latin-1 reads it as "Æ".
        val d = doc(byteArrayOf(0xC6.toByte(), 0x0A))!!
        assertTrue(d.decodedAsFallback)
        assertEquals("Æ\n", d.text)
        assertTrue(doc("Этот файл в 1251".toByteArray(Charset.forName("windows-1251")))!!.decodedAsFallback)
    }

    @Test
    fun binaryIsNotText() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        assertNull(doc(png))
        // A NUL past the first 8 KB is not looked for.
        val late = ByteArray(9000) { 'a'.code.toByte() }.also { it[8500] = 0 }
        assertNotNull(doc(late))
    }

    @Test
    fun anEmptyFileIsText() {
        assertEquals("", doc(ByteArray(0))!!.text)
    }

    @Test
    fun textFilesAreKnownByNameOrType() {
        assertTrue(TextFiles.isText("notes.MD", "application/octet-stream"))
        assertTrue(TextFiles.isText("build.gradle.kts", "application/octet-stream"))
        assertTrue(TextFiles.isText("README", "text/plain"))
        assertFalse(TextFiles.isText("photo.jpg", "image/jpeg"))
        assertFalse(TextFiles.isText("archive.txt.zip", "application/zip"))
        assertTrue(TextFiles.isMarkdown("a.markdown"))
        assertFalse(TextFiles.isMarkdown("a.txt"))
    }
}
