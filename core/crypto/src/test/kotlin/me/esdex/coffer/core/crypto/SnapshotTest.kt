package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.crypto.format.IndexState
import me.esdex.coffer.core.crypto.format.MetaCodec
import me.esdex.coffer.core.crypto.format.SnapshotCodec
import me.esdex.coffer.core.crypto.format.SnapshotFile
import me.esdex.coffer.core.crypto.format.SnapshotFolder
import me.esdex.coffer.core.crypto.format.SnapshotOrphan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/** The index snapshot, SPEC §4.4, against the independent oracle (generate_vectors.py). */
class SnapshotTest {
    private val v = resourceJson("vectors.json")
    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private val snap = v.getValue("snapshot").jsonObject
    private val ids = v.getValue("ids").jsonObject
    private val sids = snap.getValue("ids").jsonObject
    private val master = hex(v.str("master"))
    private val ring = KeyRing.derive(realSodium, key(master))
    private val snapshotId = Id.of(hex(snap.str("snapshotId")))

    private fun id(obj: JsonObject, name: String) = Id.of(hex(obj.str(name)))

    /** The oracle's state, written out by hand from the vector script. */
    private val expected: IndexState by lazy {
        val folder = id(ids, "folderId")
        val hash = Bytes.of(hex(ids.str("contentHash")))
        val dek = Bytes.of(hex(v.getValue("wrap").jsonObject.str("wrappedDek")))
        fun meta(name: String, folderId: Id?) = FileMeta(name, folderId, "application/pdf", 1762531200123, 1762531200456, hash, dek)
        IndexState(
            lastAppliedMessageId = 7L shl 20,
            folders = listOf(SnapshotFolder(id(sids, "subFolder"), folder, "2025"), SnapshotFolder(folder, null, "Налоги")),
            files = listOf(
                SnapshotFile(id(sids, "emptyFile"), 0, meta("empty.txt", null), emptyMap(), damaged = true),
                SnapshotFile(id(ids, "fileId"), 1048577, meta("Отчёт.pdf", folder), mapOf(0 to (1L shl 20)), damaged = false),
            ),
            orphans = listOf(SnapshotOrphan(id(sids, "orphanFile"), mapOf(1 to (3L shl 20)))),
            deletedFiles = listOf(id(sids, "goneA"), id(sids, "goneB")),
            deletedFolders = listOf(id(sids, "goneFolder")),
        )
    }

    private val codec get() = SnapshotCodec(realSodium, ring.indexKey)

    @Test
    fun stateEncodesLikeTheOracle() {
        assertEquals(snap.str("cbor"), codec.stateCbor(expected).toHex())
        // Input order does not matter: the encoder sorts.
        val shuffled = expected.copy(
            folders = expected.folders.reversed(),
            files = expected.files.reversed(),
            deletedFiles = expected.deletedFiles.reversed(),
        )
        assertEquals(snap.str("cbor"), codec.stateCbor(shuffled).toHex())
    }

    @Test
    fun oracleDocumentAndCaptionDecode() {
        val deflated = hex(snap.str("deflated"))
        val document = hex(snap.str("document"))
        val state = codec.decode(snapshotId, deflated.size.toLong(), ByteArrayInputStream(document))
        assertEquals(expected, state)

        val caption = snap.getValue("caption").jsonObject.str("caption")
        val payload = MetaCodec(realSodium, ring.metaKey).decodeIndex(Captions.parse(caption)!!)
        assertEquals(IndexPayload(snapshotId, 7L shl 20, snap.getValue("createdAt").jsonPrimitive.long, deflated.size.toLong()), payload)
    }

    @Test
    fun encryptsLikeTheOracleWithItsNonce() {
        // Same deflated bytes and nonce as the oracle: the encryption is checked byte for byte.
        val deflated = hex(snap.str("deflated"))
        val out = java.io.ByteArrayOutputStream()
        val sodium = ScriptedSodium(hex(snap.str("nonce")))
        ContentEncryptor(sodium, ring.indexKey, snapshotId, deflated.size.toLong()).encryptPart(0, ByteArrayInputStream(deflated), out)
        sodium.assertUsedUp()
        assertEquals(snap.str("document"), out.toByteArray().toHex())
    }

    @Test
    fun ownSnapshotRoundTrips() {
        val sealed = codec.encode(snapshotId, expected)
        assertEquals(expected, codec.decode(snapshotId, sealed.plainSize, ByteArrayInputStream(sealed.bytes)))
        val empty = IndexState(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val sealedEmpty = codec.encode(snapshotId, empty)
        assertEquals(empty, codec.decode(snapshotId, sealedEmpty.plainSize, ByteArrayInputStream(sealedEmpty.bytes)))
    }

    @Test
    fun everyBadStateIsRefusedAndTheGoodOneIsNot() {
        assertNotNull(codec.decodeState(hex(snap.str("cbor"))))
        val bad = snap.getValue("bad").jsonArray.map { it.jsonObject }
        assertEquals(14, bad.size)
        for (case in bad) assertNull(case.str("why"), codec.decodeState(hex(case.str("cbor"))))
    }

    @Test
    fun documentIsRefusedWithAnyOtherKeyIdSizeOrByte() {
        val deflated = hex(snap.str("deflated"))
        val document = hex(snap.str("document"))
        val size = deflated.size.toLong()
        fun decode(codec: SnapshotCodec = this.codec, id: Id = snapshotId, plainSize: Long = size, bytes: ByteArray = document) =
            codec.decode(id, plainSize, ByteArrayInputStream(bytes))
        assertNotNull(decode())
        assertNull(decode(codec = SnapshotCodec(realSodium, ring.metaKey)))
        assertNull(decode(id = Id.random(realSodium)))
        assertNull(decode(plainSize = size - 1))
        assertNull(decode(bytes = document.flipped(document.size - 3)))
        assertNull(decode(bytes = document.copyOf(document.size - 1)))
        assertNull(decode(bytes = document + 0))
    }

    @Test
    fun inflateRefusesBombsTruncationAndTrailingBytes() {
        val cbor = hex(snap.str("cbor"))
        val deflated = SnapshotCodec.deflate(cbor)
        assertEquals(cbor.toHex(), SnapshotCodec.inflate(deflated, cbor.size)!!.toHex())
        assertNull(SnapshotCodec.inflate(deflated, cbor.size - 1))
        assertNull(SnapshotCodec.inflate(deflated.copyOf(deflated.size - 1), Int.MAX_VALUE))
        assertNull(SnapshotCodec.inflate(deflated + 0, Int.MAX_VALUE))
        assertNull(SnapshotCodec.inflate(byteArrayOf(1, 2, 3, 4), Int.MAX_VALUE))
        // 64 MiB of zeros deflate to a few dozen KiB: refused long before it is all in memory.
        val bomb = SnapshotCodec.deflate(ByteArray(64 * 1024 * 1024))
        assertNull(SnapshotCodec.inflate(bomb, 1024 * 1024))
        // The oracle's own deflate (Python zlib) inflates here too.
        assertEquals(cbor.toHex(), SnapshotCodec.inflate(hex(snap.str("deflated")), cbor.size)!!.toHex())
    }
}
