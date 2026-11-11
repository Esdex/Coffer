package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.esdex.coffer.core.crypto.format.Base64Url
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.IndexPayload
import me.esdex.coffer.core.crypto.format.MetaCodec
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RawRecord
import me.esdex.coffer.core.crypto.format.RecordKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Format v2, SPEC §4.5: PADMÉ, padded content and captions of one length per kind, byte for byte against
 * vectors from src/test/oracle/generate_vectors.py, which computes them without libsodium.
 */
class FormatV2Test {
    private val v = resourceJson("vectors.json")
    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.bytes(name: String) = hex(str(name))
    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private val v2 = v.obj("v2")
    private val master = hex(v.str("master"))
    private val ids = v.obj("ids")
    private val fileId = Id.of(ids.bytes("fileId"))
    private val folderId = Id.of(ids.bytes("folderId"))
    private val opId = Id.of(ids.bytes("opId"))
    private val contentHash = Bytes.of(ids.bytes("contentHash"))
    private val wrappedDek = Bytes.of(v.obj("wrap").bytes("wrappedDek"))
    private val dek = v.obj("wrap").bytes("dek")

    @Test
    fun padmeMatchesTheOracle() {
        val cases = v2.getValue("padme").jsonArray
        assertTrue(cases.size >= 20)
        for (case in cases) {
            val (size, padded) = case.jsonArray.map { it.jsonPrimitive.long }
            assertEquals("padme($size)", padded, Padme.pad(size))
        }
        assertEquals(1000L, Padme.contentSize(1000, padded = false))
        assertEquals(1024L, Padme.contentSize(1000, padded = true))
    }

    @Test
    fun padmeNeverShrinksAndStaysWithinTwelvePercent() {
        val random = java.util.Random(11)
        repeat(10_000) {
            val size = (random.nextLong() ushr 14).coerceAtMost(CofferFormat.MAX_PLAIN_SIZE)
            val padded = Padme.pad(size)
            assertTrue(padded >= size)
            assertTrue("padme($size) = $padded", padded <= CofferFormat.MAX_PLAIN_SIZE)
            if (size >= 1024) assertTrue("padme($size) = $padded", (padded - size).toDouble() / size < 0.12)
        }
    }

    @Test
    fun recordsMatchTheOracleByteForByte() {
        val records = v2.obj("records")
        val big = 1_100_000_000L
        val bigParts = ContentLayout.partCount(Padme.pad(big))
        val cases = linkedMapOf<String, Any>(
            "blobFirstPadded" to BlobPayload(
                fileId, 0, bigParts, big,
                FileMeta("Отчёт 2026.pdf", null, "application/pdf", 1762531200123, 1762531200456, contentHash, wrappedDek),
                padded = true,
            ),
            "blobLaterPadded" to BlobPayload(fileId, bigParts - 1, bigParts, big, null, padded = true),
            "blobFirstPlain" to BlobPayload(
                fileId, 0, 1, 0,
                FileMeta("empty.txt", folderId, "text/plain; charset=utf-8", 1, 2, contentHash, wrappedDek),
            ),
            "fileRename" to Op.FileRename(opId, 1004, fileId, "new name.bin"),
            "index" to IndexPayload(folderId, 123456789012, 1762531200789, 98765, padded = true),
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
                assertEquals(Captions.V2, record.version)
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
    fun everyBlobCaptionIsOneLengthWhateverItHolds() {
        val records = v2.obj("records")
        val lengths = listOf("blobFirstPadded", "blobLaterPadded", "blobFirstPlain").map { records.obj(it).str("caption").length }
        assertEquals(setOf(871), lengths.toSet())
        // The worst case of version 1 (name 255 bytes, mime 127, largest numbers), padded and in a folder.
        val worst = v.obj("worstCaption")
        val big = worst.getValue("big").jsonPrimitive.long
        val plainSize = worst.getValue("plainSize").jsonPrimitive.long
        val payload = BlobPayload(
            fileId, 0, ContentLayout.partCount(Padme.pad(plainSize)), plainSize,
            FileMeta(worst.str("name"), folderId, worst.str("mime"), big, big, contentHash, wrappedDek),
            padded = true,
        )
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val codec = MetaCodec(realSodium, ring.metaKey)
            val caption = codec.encodeBlob(payload)
            assertEquals(871, caption.length)
            assertEquals(payload, codec.decodeBlob(Captions.parse(caption)!!))
            val longest = Op.FolderCreate(opId, Long.MIN_VALUE, folderId, fileId, worst.str("name"))
            assertEquals(539, codec.encodeOp(longest).length)
            assertEquals(539, codec.encodeOp(Op.FileDelete(opId, 0, fileId)).length)
        }
    }

    @Test
    fun paddedContentMatchesTheOracleAndComesBackWithoutThePadding() {
        val c = v2.obj("paddedContent")
        val plainSize = c.getValue("plainSize").jsonPrimitive.long
        assertEquals(c.getValue("contentSize").jsonPrimitive.int.toLong(), Padme.pad(plainSize))
        val plain = pattern(plainSize.toInt())
        val sodium = ScriptedSodium(c.bytes("nonce"))
        val out = ByteArrayOutputStream()
        ContentEncryptor(sodium, key(dek), fileId, plainSize, padded = true).encryptPart(0, ByteArrayInputStream(plain), out)
        sodium.assertUsedUp()
        assertEquals(c.str("sealed"), out.toByteArray().toHex())

        val back = ByteArrayOutputStream()
        val hasher = ContentHasher(realSodium)
        ContentDecryptor(realSodium, key(dek), fileId, plainSize, padded = true).decryptPart(0, ByteArrayInputStream(out.toByteArray()), back, hasher)
        assertArrayEquals(plain, back.toByteArray())
        // The hash is the file's, not the padding's.
        assertEquals(ContentHasher.hash(realSodium, ByteArrayInputStream(plain)).first, hasher.finish())
        // Read as not padded, the sizes do not match.
        assertThrows(Exception::class.java) {
            ContentDecryptor(realSodium, key(dek), fileId, plainSize).decryptPart(0, ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        }
    }

    @Test
    fun paddingThatIsNotZerosIsDamage() {
        // Padding of non-zero bytes, sealed as a writer holding the key could: refused, though it authenticates.
        val plainSize = 1000L
        val content = pattern(1024).also { it[1010] = 7 }
        val sealed = ChunkCipher(realSodium, key(dek), fileId).encrypt(0, true, content)
        assertThrows(AuthenticationException::class.java) {
            ContentDecryptor(realSodium, key(dek), fileId, plainSize, padded = true).decryptChunk(0, sealed)
        }
        content[1010] = 0
        for (i in 1000 until 1024) content[i] = 0
        val good = ChunkCipher(realSodium, key(dek), fileId).encrypt(0, true, content)
        assertArrayEquals(content.copyOf(1000), ContentDecryptor(realSodium, key(dek), fileId, plainSize, padded = true).decryptChunk(0, good))
    }

    @Test
    fun paddingThatCrossesIntoAnotherChunkIsWrittenAndReadBack() {
        // 1 MiB and one byte: padded to 1 081 344, so the second chunk is one byte of file and the rest zeros.
        val plainSize = 1_048_577L
        val plain = pattern(plainSize.toInt())
        val out = ByteArrayOutputStream()
        val encryptor = ContentEncryptor(realSodium, key(dek), fileId, plainSize, padded = true)
        val hasher = ContentHasher(realSodium)
        encryptor.encryptPart(0, ByteArrayInputStream(plain), out, hasher)
        // What the upload checks the file against: the file's bytes alone.
        assertEquals(ContentHasher.hash(realSodium, ByteArrayInputStream(plain)).first, hasher.finish())
        assertEquals(1_081_344L + 2 * CofferFormat.ENCRYPTED_CHUNK_OVERHEAD, out.size().toLong())
        val decryptor = ContentDecryptor(realSodium, key(dek), fileId, plainSize, padded = true)
        val back = ByteArrayOutputStream()
        decryptor.decryptPart(0, ByteArrayInputStream(out.toByteArray()), back)
        assertArrayEquals(plain, back.toByteArray())
        // A longer or shorter source is refused, as before.
        assertThrows(ContentSizeException::class.java) {
            encryptor.encryptPart(0, ByteArrayInputStream(plain + 1), ByteArrayOutputStream())
        }
        assertThrows(ContentSizeException::class.java) {
            encryptor.encryptPart(0, ByteArrayInputStream(plain.copyOf(plain.size - 1)), ByteArrayOutputStream())
        }
    }

    /** P sealed as given, the way a buggy or hostile writer holding MetaKey could. */
    private fun sealedV2(metaKey: SecretKey, kind: RecordKind, plain: ByteArray): RawRecord {
        val nonce = realSodium.randomBytes(24)
        val sealed = metaKey.withBytes { realSodium.aeadEncrypt(plain, "coffer/v2/${kind.tag}".toByteArray(), nonce, it) }
        return RawRecord(kind, Base64Url.encode(nonce + sealed), Captions.V2)
    }

    private fun framed(cbor: ByteArray, room: Int) = byteArrayOf((cbor.size ushr 8).toByte(), cbor.size.toByte()) + cbor + ByteArray(room - cbor.size)

    @Test
    fun aFrameOfAnyOtherShapeIsRefused() {
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val codec = MetaCodec(realSodium, ring.metaKey)
            val cbor = hex(v2.obj("records").obj("fileRename").str("cbor"))
            assertNotNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, framed(cbor, 352))))
            // One byte short or long of the kind's size.
            assertNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, framed(cbor, 351))))
            assertNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, framed(cbor, 353))))
            // A byte after the CBOR that is not zero.
            assertNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, framed(cbor, 352).also { it[it.size - 1] = 1 })))
            // A length past the room, or one that cuts the CBOR short.
            assertNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, framed(cbor, 352).also { it[0] = 2; it[1] = 0 })))
            assertNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, framed(cbor, 352).also { it[1] = (cbor.size - 1).toByte() })))
            // Version 1 bytes under the version 2 prefix, and the other way round.
            assertNull(codec.decodeOp(sealedV2(ring.metaKey, RecordKind.OP, cbor)))
            val v2Caption = Captions.parse(codec.encodeOp(Op.FileDelete(opId, 1, fileId)))!!
            assertNull(codec.decodeOp(v2Caption.copy(version = Captions.V1)))
        }
    }

    @Test
    fun onlyVersion2MarksPaddingAndNeverAsFalse() {
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val v1 = MetaCodec(realSodium, ring.metaKey, writeVersion = Captions.V1)
            val padded = BlobPayload(fileId, 0, 1, 1000, FileMeta("a", null, "text/plain", 1, 2, contentHash, wrappedDek), padded = true)
            assertThrows(IllegalArgumentException::class.java) { v1.encodeBlob(padded) }
            // Label 13 in a version 1 caption: sealed by hand, refused.
            val cbor = hex(v2.obj("records").obj("blobLaterPadded").str("cbor"))
            val nonce = realSodium.randomBytes(24)
            val sealed = ring.metaKey.withBytes { realSodium.aeadEncrypt(cbor, "coffer/v1/blob".toByteArray(), nonce, it) }
            assertNull(v1.decodeBlob(RawRecord(RecordKind.BLOB, Base64Url.encode(nonce + sealed), Captions.V1)))
            assertNotNull(v1.decodeBlob(sealedV2(ring.metaKey, RecordKind.BLOB, framed(cbor, 600))))
            // padded = false written out: a second encoding of "not padded".
            val asFalse = cbor.copyOf().also { check(it.last() == 0xF5.toByte()); it[it.size - 1] = 0xF4.toByte() }
            assertNull(v1.decodeBlob(sealedV2(ring.metaKey, RecordKind.BLOB, framed(asFalse, 600))))
            // A version 1 snapshot caption is not padded, a version 2 one is.
            val index = IndexPayload(folderId, 1, 2, 3)
            assertEquals(index, v1.decodeIndex(Captions.parse(v1.encodeIndex(index))!!))
            assertThrows(IllegalArgumentException::class.java) { MetaCodec(realSodium, ring.metaKey).encodeIndex(index) }
        }
    }
}
