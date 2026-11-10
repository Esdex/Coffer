package me.esdex.coffer.feature.browser.stats

import me.esdex.coffer.core.crypto.Id
import org.junit.Assert.assertEquals
import org.junit.Test

class BreakdownTest {

    private var next = 0
    private fun file(name: String, mime: String, size: Long) = StatsFile(Id.of(ByteArray(16) { if (it == 0) (++next).toByte() else 0 }), name, mime, size, null)

    @Test
    fun kindsComeFromTheTypeFirstThenTheName() {
        assertEquals(FileKind.PICTURES, FileKind.of("a.bin", "image/jpeg"))
        assertEquals(FileKind.VIDEOS, FileKind.of("clip", "video/mp4"))
        assertEquals(FileKind.MUSIC, FileKind.of("song.mp3", "audio/mpeg"))
        assertEquals(FileKind.DOCUMENTS, FileKind.of("report.pdf", "application/pdf"))
        assertEquals(FileKind.DOCUMENTS, FileKind.of("table.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        // A type the sending app left vague: the name decides.
        assertEquals(FileKind.DOCUMENTS, FileKind.of("Report.DOCX", "application/octet-stream"))
        assertEquals(FileKind.DOCUMENTS, FileKind.of("notes.md", "application/octet-stream"))
        assertEquals(FileKind.DOCUMENTS, FileKind.of("drawing.svg", "image/svg+xml"))
        assertEquals(FileKind.OTHER, FileKind.of("backup.zip", "application/zip"))
        assertEquals(FileKind.OTHER, FileKind.of("no-extension", "application/octet-stream"))
    }

    @Test
    fun totalsAreCountedByKindInAFixedOrder() {
        val breakdown = Breakdown.of(
            listOf(
                file("a.jpg", "image/jpeg", 100),
                file("b.png", "image/png", 50),
                file("c.mp4", "video/mp4", 1_000),
                file("d.zip", "application/zip", 7),
            ),
            folders = 3,
        )
        assertEquals(
            listOf(
                KindTotal(FileKind.PICTURES, 2, 150),
                KindTotal(FileKind.VIDEOS, 1, 1_000),
                KindTotal(FileKind.MUSIC, 0, 0),
                KindTotal(FileKind.DOCUMENTS, 0, 0),
                KindTotal(FileKind.OTHER, 1, 7),
            ),
            breakdown.kinds,
        )
        assertEquals(4, breakdown.files)
        assertEquals(1_157, breakdown.bytes)
        assertEquals(3, breakdown.folders)
    }

    @Test
    fun anEmptyVaultHasEveryKindAtZero() {
        val empty = Breakdown.of(emptyList(), 0)
        assertEquals(FileKind.entries.map { KindTotal(it, 0, 0) }, empty.kinds)
        assertEquals(0L, empty.bytes)
    }
}
