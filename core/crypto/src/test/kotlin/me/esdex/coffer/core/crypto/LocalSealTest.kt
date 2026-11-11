package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
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
    fun folderAndUploadSealsMatchTheOracle() {
        val localKey = LocalKeys.derive(realSodium, key(master))
        val folderId = Id.of(hex(ids.str("folderId")))
        // The oracle seals the folder under the file id vector: any 16-byte id will do for the AD.
        val folder = LocalFolder("Налоги", folderId)
        val fv = local.getValue("folder").jsonObject
        val folderSodium = ScriptedSodium(hex(fv.str("nonce")))
        assertEquals(fv.str("sealed"), LocalSealer(folderSodium, localKey).sealFolder(fileId, folder).toHex())
        folderSodium.assertUsedUp()
        assertEquals(folder, LocalSealer(realSodium, localKey).openFolder(fileId, hex(fv.str("sealed"))))

        val upload = LocalUpload("content://x/1", "a.txt", null, folderId)
        val uv = local.getValue("upload").jsonObject
        val uploadSodium = ScriptedSodium(hex(uv.str("nonce")))
        assertEquals(uv.str("sealed"), LocalSealer(uploadSodium, localKey).sealUpload("u1", upload).toHex())
        uploadSodium.assertUsedUp()
        assertEquals(upload, LocalSealer(realSodium, localKey).openUpload("u1", hex(uv.str("sealed"))))
    }

    @Test
    fun previewSealMatchesTheOracleAndOpensOnlyForItsFile() {
        val localKey = LocalKeys.derive(realSodium, key(master))
        val pv = local.getValue("preview").jsonObject
        val plain = hex(pv.str("plain"))
        val sodium = ScriptedSodium(hex(pv.str("nonce")))
        assertEquals(pv.str("sealed"), LocalSealer(sodium, localKey).sealPreview(fileId, plain).toHex())
        sodium.assertUsedUp()
        val sealer = LocalSealer(realSodium, localKey)
        val sealed = hex(pv.str("sealed"))
        assertEquals(plain.toHex(), sealer.openPreview(fileId, sealed)?.toHex())
        assertNull(sealer.openPreview(Id.random(realSodium), sealed))
        assertNull(sealer.openPreview(fileId, sealed.flipped(sealed.size - 1)))
        assertNull(sealer.openPreview(fileId, sealed.copyOf(CofferFormat.NONCE_BYTES + CofferFormat.TAG_BYTES - 1)))
        // A file's sealed name is not its preview, nor the other way round.
        assertNull(sealer.openPreview(fileId, sealer.sealFileMeta(fileId, meta)))
        assertNull(sealer.openFileMeta(fileId, sealed))
        // No preview to make: empty, and still sealed.
        assertEquals(0, sealer.openPreview(fileId, sealer.sealPreview(fileId, ByteArray(0)))?.size)
    }

    @Test
    fun waveformSealMatchesTheOracleAndOpensOnlyForItsFile() {
        val localKey = LocalKeys.derive(realSodium, key(master))
        val wv = local.getValue("waveform").jsonObject
        val plain = hex(wv.str("plain"))
        val sodium = ScriptedSodium(hex(wv.str("nonce")))
        assertEquals(wv.str("sealed"), LocalSealer(sodium, localKey).sealWaveform(fileId, plain).toHex())
        sodium.assertUsedUp()
        val sealer = LocalSealer(realSodium, localKey)
        val sealed = hex(wv.str("sealed"))
        assertEquals(plain.toHex(), sealer.openWaveform(fileId, sealed)?.toHex())
        assertNull(sealer.openWaveform(Id.random(realSodium), sealed))
        assertNull(sealer.openWaveform(fileId, sealed.flipped(sealed.size - 1)))
        // A preview is not a waveform, nor the other way round.
        assertNull(sealer.openPreview(fileId, sealed))
        assertNull(sealer.openWaveform(fileId, sealer.sealPreview(fileId, plain)))
    }

    @Test
    fun sealedFolderOpensOnlyForItsRow() {
        val sealer = LocalSealer(realSodium, LocalKeys.derive(realSodium, key(master)))
        val root = LocalFolder("Root level", null)
        val sealed = sealer.sealFolder(fileId, root)
        assertEquals(root, sealer.openFolder(fileId, sealed))
        assertNull(sealer.openFolder(Id.random(realSodium), sealed))
        assertNull(sealer.openFolder(fileId, sealed.flipped(sealed.size - 1)))
        // Same id, other kind of row: a file's sealed metadata is not a folder and the other way round.
        assertNull(sealer.openFileMeta(fileId, sealed))
        assertNull(sealer.openFolder(fileId, sealer.sealFileMeta(fileId, meta)))
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
