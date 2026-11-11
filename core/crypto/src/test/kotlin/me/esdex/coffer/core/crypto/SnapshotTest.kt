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
import org.junit.Assert.assertThrows
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
        val state = codec.decode(snapshotId, deflated.size.toLong(), ByteArrayInputStream(document), padded = false)
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
        for (padded in listOf(false, true)) {
            val sealed = codec.encode(snapshotId, expected, padded)
            assertEquals(expected, codec.decode(snapshotId, sealed.plainSize, ByteArrayInputStream(sealed.bytes), padded))
            // Read as the other version, it is not a snapshot.
            assertNull(codec.decode(snapshotId, sealed.plainSize, ByteArrayInputStream(sealed.bytes), !padded))
            val empty = IndexState(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            val sealedEmpty = codec.encode(snapshotId, empty, padded)
            assertEquals(empty, codec.decode(snapshotId, sealedEmpty.plainSize, ByteArrayInputStream(sealedEmpty.bytes), padded))
        }
    }

    @Test
    fun paddedFilesAreCountedFromTheirPaddedSizeAndOnlyInVersion2() {
        // Past 64 GiB PADMÉ rounds by more than a part: 2^36 + 1 bytes is 129 parts, padded 130.
        val size = (1L shl 36) + 1
        val meta = expected.files.first().meta
        val padded = SnapshotFile(Id.of(ByteArray(16) { -1 }), size, meta, mapOf(129 to 99L), damaged = false, padded = true)
        assertEquals(130, padded.partCount)
        assertEquals(129, padded.copy(padded = false, parts = emptyMap()).partCount)
        // The largest id: last in order, as the decoder lists them.
        val state = expected.copy(files = expected.files + padded)
        val cbor = codec.stateCbor(state)
        assertEquals(state, codec.decodeState(cbor, version2 = true))
        // A version 1 snapshot has no label 15, and cannot be written with a padded file.
        assertNull(codec.decodeState(cbor, version2 = false))
        assertThrows(IllegalArgumentException::class.java) { codec.encode(snapshotId, state, padded = false) }
        val sealed = codec.encode(snapshotId, state, padded = true)
        assertEquals(state, codec.decode(snapshotId, sealed.plainSize, ByteArrayInputStream(sealed.bytes), padded = true))
        // "padded": false written out is a second encoding of "not padded". On a small file, so the part
        // count is the same either way and only that rule can refuse it.
        val small = expected.copy(files = expected.files.map { if (it.plainSize == 1048577L) it.copy(padded = true) else it })
        val smallCbor = codec.stateCbor(small)
        assertEquals(small, codec.decodeState(smallCbor))
        val label = smallCbor.indices.single { smallCbor[it] == 0x0F.toByte() && smallCbor[it + 1] == 0xF5.toByte() }
        assertNull(codec.decodeState(smallCbor.copyOf().also { it[label + 1] = 0xF4.toByte() }))
        // The part count of the plain size, 129, under a padded file: refused. Its last part, 129, is
        // still in range, so it is the count that is wrong.
        val count = cbor.indices.filter { cbor[it] == 0x03.toByte() && cbor[it + 1] == 0x18.toByte() && cbor[it + 2] == 130.toByte() }
        assertEquals(1, count.size)
        assertNull(codec.decodeState(cbor.copyOf().also { it[count.single() + 2] = 129.toByte() }))
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
            codec.decode(id, plainSize, ByteArrayInputStream(bytes), padded = false)
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
