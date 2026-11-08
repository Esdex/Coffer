package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalSealer
import me.esdex.coffer.core.crypto.format.LocalUpload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSealTest {
    private val v = resourceJson("vectors.json")
    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private val local = v.getValue("local").jsonObject
    private val ids = v.getValue("ids").jsonObject
    private val master = hex(v.str("master"))
    private val fileId = Id.of(hex(ids.str("fileId")))
    private val meta = FileMeta(
        "Отчёт 2026.pdf", null, "application/pdf", 1762531200123, 1762531200456,
        Bytes.of(hex(ids.str("contentHash"))), Bytes.of(hex(v.getValue("wrap").jsonObject.str("wrappedDek"))),
    )

    @Test
    fun localKeyAndSealMatchTheOracle() {
        val localKey = LocalKeys.derive(realSodium, key(master))
        assertEquals(local.str("key"), localKey.withBytes { it.toHex() })
        val sodium = ScriptedSodium(hex(local.str("nonce")))
        assertEquals(local.str("sealed"), LocalSealer(sodium, localKey).sealFileMeta(fileId, meta).toHex())
        sodium.assertUsedUp()
        assertEquals(meta, LocalSealer(realSodium, localKey).openFileMeta(fileId, hex(local.str("sealed"))))
    }

    @Test
    fun localKeyIsNoneOfTheTelegramKeys() {
        val localKey = LocalKeys.derive(realSodium, key(master)).withBytes { it.toHex() }
        val subkeys = v.getValue("subkeys").jsonObject
        for (name in listOf("meta", "index", "wrap")) assertNotEquals(subkeys.str(name), localKey)
    }

    @Test
    fun sealedMetaOpensOnlyForItsFileAndKey() {
        val localKey = LocalKeys.derive(realSodium, key(master))
        val sealer = LocalSealer(realSodium, localKey)
        val sealed = sealer.sealFileMeta(fileId, meta)
        assertEquals(meta, sealer.openFileMeta(fileId, sealed))
        assertNull(sealer.openFileMeta(Id.random(realSodium), sealed))
        assertNull(sealer.openFileMeta(fileId, sealed.flipped(sealed.size / 2)))
        assertNull(sealer.openFileMeta(fileId, sealed.copyOf(30)))
        assertNull(LocalSealer(realSodium, MasterKeys.generate(realSodium)).openFileMeta(fileId, sealed))
        val inFolder = meta.copy(folderId = Id.random(realSodium))
        assertEquals(inFolder, sealer.openFileMeta(fileId, sealer.sealFileMeta(fileId, inFolder)))
    }

    @Test
    fun uploadsAndTargetsAreSealedPerRow() {
        val sealer = LocalSealer(realSodium, LocalKeys.derive(realSodium, key(master)))
        val upload = LocalUpload("content://downloads/document/raw%3A%2FDownload%2Fsecret.pdf", "secret.pdf", null)
        val sealed = sealer.sealUpload("u1", upload)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("secret"))
        assertEquals(upload, sealer.openUpload("u1", sealed))
        assertNull(sealer.openUpload("u2", sealed))
        assertNull(sealer.openUpload("u1", sealed.flipped(30)))

        val target = sealer.sealTarget("d1", "content://x/secret.pdf")
        assertEquals("content://x/secret.pdf", sealer.openTarget("d1", target))
        assertNull(sealer.openTarget("d2", target))
        // A sealed target cannot pass for an upload with the same id, or the other way round.
        assertNull(sealer.openUpload("d1", target))
    }
}
