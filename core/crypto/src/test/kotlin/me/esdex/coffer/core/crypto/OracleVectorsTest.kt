package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.crypto.format.MetaCodec
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.VaultHeader
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Byte-exact checks against src/test/resources/vectors.json, produced by src/test/oracle/generate_vectors.py
 * from the specification with non-libsodium implementations.
 */
class OracleVectorsTest {
    private val v = resourceJson("vectors.json")
    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.bytes(name: String) = hex(str(name))
    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private val master = hex(v.str("master"))
    private val ids = v.obj("ids")
    private val fileId = Id.of(ids.bytes("fileId"))
    private val folderId = Id.of(ids.bytes("folderId"))
    private val opId = Id.of(ids.bytes("opId"))
    private val contentHash = Bytes.of(ids.bytes("contentHash"))
    private val wrappedDek = Bytes.of(v.obj("wrap").bytes("wrappedDek"))

    @Test
    fun subkeysAndMasterId() {
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val expected = v.obj("subkeys")
            assertEquals(expected.str("meta"), ring.metaKey.withBytes { it.toHex() })
            assertEquals(expected.str("index"), ring.indexKey.withBytes { it.toHex() })
            assertEquals(expected.str("wrap"), ring.wrapKey.withBytes { it.toHex() })
        }
        assertEquals(v.str("masterId"), MasterKeys.masterId(realSodium, key(master)).toByteArray().toHex())
    }

    @Test
    fun argon2idKek() {
        for (case in v.getValue("argon2id").jsonArray.map { it.jsonObject }) {
            val params = Argon2Params(case.getValue("ops").jsonPrimitive.long, case.getValue("mem").jsonPrimitive.long)
            val kek = PasswordKdf.deriveKek(realSodium, case.str("password"), Bytes.of(case.bytes("salt")), params)
            assertEquals(case.str("kek"), kek.withBytes { it.toHex() })
        }
    }

    @Test
    fun keyWrapping() {
        val w = v.obj("wrap")
        val sodium = ScriptedSodium(w.bytes("masterNonce"), w.bytes("dekNonce"))
        assertEquals(w.str("wrappedMaster"), MasterKeys.wrap(sodium, key(w.bytes("kek")), key(master)).toByteArray().toHex())
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val wrapped = FileKeys.wrapDek(sodium, ring.wrapKey, key(w.bytes("dek")), Id.of(w.bytes("fileId")))
            assertEquals(w.str("wrappedDek"), wrapped.toByteArray().toHex())
        }
        sodium.assertUsedUp()
    }

    @Test
    fun chunks() {
        val dek = hex(v.obj("wrap").str("dek"))
        for (case in v.getValue("chunks").jsonArray.map { it.jsonObject }) {
            val sodium = ScriptedSodium(case.bytes("nonce"))
            val index = case.getValue("index").jsonPrimitive.long
            val isLast = case.getValue("isLast").jsonPrimitive.boolean
            val plain = pattern(case.getValue("plainSize").jsonPrimitive.int)
            val sealed = ChunkCipher(sodium, key(dek), fileId).encrypt(index, isLast, plain)
            assertEquals(case.str("sealed"), sealed.toHex())
            sodium.assertUsedUp()
        }
    }

    @Test
    fun contentHash() {
        for (case in v.getValue("contentHash").jsonArray.map { it.jsonObject }) {
            val size = case.getValue("size").jsonPrimitive.int
            val (hash, read) = ContentHasher.hash(realSodium, ByteArrayInputStream(pattern(size)))
            assertEquals(size.toLong(), read)
            assertEquals(case.str("hash"), hash.toByteArray().toHex())
        }
    }

    @Test
    fun vaultHeaderCaption() {
        val h = v.obj("vaultHeader")
        val w = v.obj("wrap")
        val header = VaultHeader(
            createdAt = h.getValue("createdAt").jsonPrimitive.long,
            salt = Bytes.of(hex(v.getValue("argon2id").jsonArray[0].jsonObject.str("salt"))),
            argon = Argon2Params(h.getValue("ops").jsonPrimitive.long, h.getValue("mem").jsonPrimitive.long),
            wrappedMaster = Bytes.of(w.bytes("wrappedMaster")),
            masterId = Bytes.of(hex(v.str("masterId"))),
        )
        assertEquals(h.str("caption"), VaultHeaderCodec.encode(header))
        assertEquals(header, VaultHeaderCodec.decode(Captions.parse(h.str("caption"))!!))
    }

    @Test
    fun encryptedRecords() {
        val records = v.obj("records")
        val cases = mapOf(
            "blobFirstRoot" to BlobPayload(
                fileId, 0, 3, 1_100_000_000,
                FileMeta("Отчёт 2026.pdf", null, "application/pdf", 1762531200123, 1762531200456, contentHash, wrappedDek),
            ),
            "blobFirstInFolder" to BlobPayload(
                fileId, 0, 1, 0,
                FileMeta("empty.txt", folderId, "text/plain; charset=utf-8", 1, 2, contentHash, wrappedDek),
            ),
            "blobLaterPart" to BlobPayload(fileId, 2, 3, 1_100_000_000, null),
            "folderCreate" to Op.FolderCreate(opId, 1000, folderId, null, "Docs"),
            "folderRename" to Op.FolderRename(opId, 1001, folderId, "Документы"),
            "folderMove" to Op.FolderMove(opId, 1002, folderId, fileId),
            "folderDelete" to Op.FolderDelete(opId, 1003, folderId),
            "fileRename" to Op.FileRename(opId, 1004, fileId, "new name.bin"),
            "fileMove" to Op.FileMove(opId, 1005, fileId, null),
            "fileDelete" to Op.FileDelete(opId, 1006, fileId),
            "index" to IndexPayload(folderId, 123456789012, 1762531200789, 98765),
        )
        assertEquals(records.keys, cases.keys)
        KeyRing.derive(realSodium, key(master)).use { ring ->
            for ((name, payload) in cases) {
                val expected = records.obj(name)
                val sodium = ScriptedSodium(expected.bytes("nonce"))
                val codec = MetaCodec(sodium, ring.metaKey)
                val caption = when (payload) {
                    is BlobPayload -> codec.encodeBlob(payload)
                    is Op -> codec.encodeOp(payload)
                    is IndexPayload -> codec.encodeIndex(payload)
                    else -> error("unexpected $payload")
                }
                assertEquals(name, expected.str("caption"), caption)
                sodium.assertUsedUp()

                val record = Captions.parse(caption)!!
                val decoded = when (payload) {
                    is BlobPayload -> codec.decodeBlob(record)
                    is Op -> codec.decodeOp(record)
                    else -> codec.decodeIndex(record)
                }
                assertEquals(name, payload, decoded)
            }
        }
    }

    @Test
    fun worstCaseCaptionFitsTelegramLimit() {
        val w = v.obj("worstCaption")
        val big = w.getValue("big").jsonPrimitive.long
        val payload = BlobPayload(
            fileId, 0, w.getValue("partCount").jsonPrimitive.int, w.getValue("plainSize").jsonPrimitive.long,
            FileMeta(w.str("name"), folderId, w.str("mime"), big, big, contentHash, wrappedDek),
        )
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val caption = MetaCodec(ScriptedSodium(w.bytes("nonce")), ring.metaKey).encodeBlob(payload)
            assertEquals(w.str("caption"), caption)
            // Telegram counts UTF-16 units; the caption is ASCII, so length is the count.
            assertTrue("caption is ${caption.length} chars", caption.length <= 1024)
        }
    }
}
