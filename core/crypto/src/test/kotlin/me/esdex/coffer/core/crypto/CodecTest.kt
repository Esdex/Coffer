package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.format.Base64Url
import me.esdex.coffer.core.crypto.format.BlobPayload
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.FileNames
import me.esdex.coffer.core.crypto.format.MetaCodec
import me.esdex.coffer.core.crypto.format.MimeTypes
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RawRecord
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {
    private val sodium = realSodium
    private val metaKey = MasterKeys.generate(sodium)
    private val codec = MetaCodec(sodium, metaKey)
    private val fileId = Id.random(sodium)
    private val wrappedDek = Bytes.of(ByteArray(72) { it.toByte() })
    private val hash = Bytes.of(ByteArray(32) { it.toByte() })
    private val part0 = BlobPayload(fileId, 0, 1, 10, FileMeta("a.txt", null, "text/plain", 1, 2, hash, wrappedDek))

    /** Seals arbitrary CBOR as a blob, the way a buggy or hostile writer holding MetaKey could. */
    private fun sealRaw(kind: RecordKind, cbor: ByteArray): RawRecord {
        val nonce = sodium.randomBytes(24)
        val sealed = metaKey.withBytes { sodium.aeadEncrypt(cbor, "coffer/v1/${kind.tag}".toByteArray(), nonce, it) }
        return RawRecord(kind, Base64Url.encode(nonce + sealed))
    }

    @Test
    fun captionsOfOtherContentAreIgnored() {
        assertNull(Captions.parse(null))
        assertNull(Captions.parse("holiday photo"))
        assertNull(Captions.parse("coffer:v2:blob:abc"))
        assertNull(Captions.parse("coffer:v1:unknown:abc"))
        assertNull(Captions.parse("coffer:v1:blob"))
        assertEquals(RawRecord(RecordKind.OP, "xyz"), Captions.parse("coffer:v1:op:xyz"))
    }

    @Test
    fun recordOfAnotherKindOrKeyDoesNotOpen() {
        val record = Captions.parse(codec.encodeBlob(part0))!!
        assertNull(codec.decodeOp(record))
        assertNull(codec.decodeOp(RawRecord(RecordKind.OP, record.body))) // same bytes under another AD
        assertNull(MetaCodec(sodium, MasterKeys.generate(sodium)).decodeBlob(record))
        assertEquals(part0, codec.decodeBlob(record))
    }

    @Test
    fun tamperedOrMangledBodyIsRejected() {
        val body = Captions.parse(codec.encodeBlob(part0))!!.body
        val raw = Base64Url.decode(body)!!
        assertNull(codec.decodeBlob(RawRecord(RecordKind.BLOB, Base64Url.encode(raw.flipped(raw.size / 2)))))
        assertNull(codec.decodeBlob(RawRecord(RecordKind.BLOB, "$body=")))
        assertNull(codec.decodeBlob(RawRecord(RecordKind.BLOB, body.replace('-', '+'))))
        assertNull(codec.decodeBlob(RawRecord(RecordKind.BLOB, body.dropLast(1))))
        assertNull(codec.decodeBlob(RawRecord(RecordKind.BLOB, "")))
    }

    @Test
    fun base64UrlIsCanonical() {
        assertEquals("AQ", Base64Url.encode(byteArrayOf(1)))
        assertNotNull(Base64Url.decode("AQ"))
        assertNull(Base64Url.decode("AR")) // same byte, non-zero spare bits
        assertNull(Base64Url.decode("AQ=="))
        assertNull(Base64Url.decode("A"))
    }

    @Test
    fun nonCanonicalCborIsRejectedEvenWhenAuthentic() {
        // A complete, valid later-part payload, so only the encoding differs between the variants.
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val k1 = b(0x01, 0x50) + fileId.toByteArray()
        val k2 = b(0x02, 0x01)
        val k3 = b(0x03, 0x02)
        val k4 = b(0x04, 0x1a, 0x00, 0x10, 0x00, 0x00)
        val k5 = b(0x05, 0x1a, 0x23, 0xc3, 0x46, 0x00) // 600 000 000
        val canonical = b(0xa5) + k1 + k2 + k3 + k4 + k5
        assertArrayEquals(
            TinyCbor.map(listOf(1 to fileId.toByteArray(), 2 to 1, 3 to 2, 4 to 1048576, 5 to 600_000_000)),
            canonical,
        )
        assertNotNull("control", codec.decodeBlob(sealRaw(RecordKind.BLOB, canonical)))

        val variants = mapOf(
            "2-byte int" to b(0xa5) + k1 + b(0x02, 0x19, 0x00, 0x01) + k3 + k4 + k5,
            "indefinite map" to b(0xbf) + k1 + k2 + k3 + k4 + k5 + b(0xff),
            "unsorted keys" to b(0xa5) + k2 + k1 + k3 + k4 + k5,
            "duplicate key" to b(0xa6) + k1 + k2 + k3 + k4 + k5 + k5,
        )
        for ((name, cbor) in variants) assertNull(name, codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor)))
    }

    @Test
    fun authenticButInvalidPayloadsAreRejected() {
        fun cbor(vararg pairs: Pair<Int, Any?>) = TinyCbor.map(pairs.toList())
        val id = fileId.toByteArray()
        // Part 1 of a one-part file.
        assertNull(codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor(1 to id, 2 to 1, 3 to 1, 4 to 1048576, 5 to 10))))
        // Part count that does not match the size.
        assertNull(codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor(1 to id, 2 to 1, 3 to 5, 4 to 1048576, 5 to 10))))
        // Another chunk size.
        assertNull(codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor(1 to id, 2 to 1, 3 to 2, 4 to 65536, 5 to 600_000_000))))
        // Short file id.
        assertNull(codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor(1 to id.copyOf(15), 2 to 1, 3 to 2, 4 to 1048576, 5 to 600_000_000))))
        // An extra key.
        assertNull(codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor(1 to id, 2 to 1, 3 to 2, 4 to 1048576, 5 to 600_000_000, 13 to 0))))
        // Valid later part, as a control: the same writer can produce an accepted record.
        assertNotNull(codec.decodeBlob(sealRaw(RecordKind.BLOB, cbor(1 to id, 2 to 1, 3 to 2, 4 to 1048576, 5 to 600_000_000))))

        val opId = Id.random(sodium).toByteArray()
        // Op whose fields belong to another type.
        assertNull(codec.decodeOp(sealRaw(RecordKind.OP, cbor(1 to opId, 2 to "file.delete", 3 to 1, 5 to id))))
        assertNull(codec.decodeOp(sealRaw(RecordKind.OP, cbor(1 to opId, 2 to "file.explode", 3 to 1, 4 to id))))
        // file.move must state its folder, null included.
        assertNull(codec.decodeOp(sealRaw(RecordKind.OP, cbor(1 to opId, 2 to "file.move", 3 to 1, 4 to id))))
        assertEquals(
            Op.FileMove(Id.of(opId), 1, fileId, null),
            codec.decodeOp(sealRaw(RecordKind.OP, cbor(1 to opId, 2 to "file.move", 3 to 1, 4 to id, 5 to null))),
        )
    }

    @Test
    fun metadataOnlyOnPartZero() {
        val meta = part0.meta
        assertThrows(IllegalArgumentException::class.java) { BlobPayload(fileId, 0, 1, 10, null) }
        assertThrows(IllegalArgumentException::class.java) { BlobPayload(fileId, 1, 2, 600_000_000, meta) }
    }

    @Test
    fun vaultHeaderRejectsUnsafeArgonParams() {
        fun header(ops: Long, mem: Long) = TinyCbor.map(
            listOf(1 to 1, 2 to 0, 3 to ByteArray(16), 4 to ops, 5 to mem, 6 to ByteArray(72), 7 to ByteArray(16)),
        )
        fun decode(cbor: ByteArray) = VaultHeaderCodec.decode(RawRecord(RecordKind.VAULT, Base64Url.encode(cbor)))
        assertNotNull(decode(header(3, 64L * 1024 * 1024)))
        assertNull(decode(header(3, 64L * 1024 * 1024 * 1024)))
        assertNull(decode(header(1000, 64L * 1024 * 1024)))
        // Unknown format version.
        assertNull(decode(TinyCbor.map(listOf(1 to 2, 2 to 0, 3 to ByteArray(16), 4 to 3, 5 to 67108864, 6 to ByteArray(72), 7 to ByteArray(16)))))
    }

    @Test
    fun namesAndMime() {
        assertTrue(FileNames.isValid("a"))
        assertFalse(FileNames.isValid(""))
        assertTrue(FileNames.isValid("я".repeat(127) + "x"))
        assertFalse(FileNames.isValid("я".repeat(128)))
        assertFalse(FileNames.isValid("bad\uD800"))

        val long = "Отчёт ".repeat(40) + "😀.pdf"
        val fitted = FileNames.fitForImport(long)
        assertTrue(FileNames.isValid(fitted))
        assertTrue(fitted.endsWith(".pdf"))
        assertTrue(long.startsWith(fitted.removeSuffix(".pdf")))
        // A 4-byte emoji straddling the limit is dropped whole, not split.
        val emojis = FileNames.fitForImport("😀".repeat(70))
        assertEquals(63, emojis.codePointCount(0, emojis.length))

        assertEquals("application/pdf", MimeTypes.normalize("application/pdf"))
        assertEquals("application/octet-stream", MimeTypes.normalize(null))
        assertEquals("application/octet-stream", MimeTypes.normalize("x/" + "y".repeat(126)))
        assertEquals("application/octet-stream", MimeTypes.normalize("text/пример"))
        assertEquals("x/" + "y".repeat(125), MimeTypes.normalize("x/" + "y".repeat(125)))
    }
}

/** Minimal canonical CBOR writer for hand-made test inputs; independent of kotlinx. */
object TinyCbor {
    fun map(pairs: List<Pair<Int, Any?>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        head(out, 5, pairs.size.toLong())
        for ((k, v) in pairs.sortedBy { it.first }) {
            head(out, 0, k.toLong())
            when (v) {
                null -> out.write(0xf6)
                is Int -> head(out, 0, v.toLong())
                is Long -> head(out, 0, v)
                is String -> v.toByteArray().let { head(out, 3, it.size.toLong()); out.write(it) }
                is ByteArray -> { head(out, 2, v.size.toLong()); out.write(v) }
                else -> error("unsupported $v")
            }
        }
        return out.toByteArray()
    }

    private fun head(out: java.io.ByteArrayOutputStream, major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> out.write(m or value.toInt())
            value < 0x100 -> { out.write(m or 24); out.write(value.toInt()) }
            value < 0x10000 -> { out.write(m or 25); for (s in 8 downTo 0 step 8) out.write((value ushr s).toInt() and 0xff) }
            value < 0x100000000 -> { out.write(m or 26); for (s in 24 downTo 0 step 8) out.write((value ushr s).toInt() and 0xff) }
            else -> { out.write(m or 27); for (s in 56 downTo 0 step 8) out.write((value ushr s).toInt() and 0xff) }
        }
    }
}
