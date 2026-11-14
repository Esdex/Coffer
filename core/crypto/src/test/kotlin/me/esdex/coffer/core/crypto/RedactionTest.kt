package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.crypto.format.LocalUpload
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.SnapshotFolder
import org.junit.Assert.assertFalse
import org.junit.Test

/** What a log or crash report would print of these never holds a name (audit 1, Info DATA-12). */
class RedactionTest {
    private val id = Id.of(ByteArray(16) { 1 })
    private val name = "Tax return 2026.pdf"

    @Test
    fun namesStayOutOfToString() {
        val printed = listOf(
            FileMeta(name, null, "application/pdf", 1, 1, Bytes.of(ByteArray(32)), Bytes.of(ByteArray(72))),
            Op.FileRename(id, 1, id, name),
            Op.FolderRename(id, 1, id, name),
            Op.FolderCreate(id, 1, id, null, name),
            SnapshotFolder(id, null, name),
            LocalFolder(name, null),
            LocalUpload("content://media/external/$name", name, "application/pdf"),
        ).map { it.toString() }
        for (text in printed) assertFalse(text, text.contains("Tax return"))
    }
}
