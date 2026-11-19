package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.esdex.coffer.core.crypto.format.Base64Url
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.MetaCodec
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.format.RawRecord
import me.esdex.coffer.core.crypto.format.RecordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Format v3, SPEC §4.6: several operations in one message, byte for byte against vectors from
 * src/test/oracle/generate_vectors.py, which builds the CBOR with cbor2 and seals without libsodium.
 * Telegram lets about 20 texts into a chat before a pause of minutes (measured 2026-11-19).
 */
class FormatV3Test {
    private val v = resourceJson("vectors.json")
    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private val v3 = v.obj("v3")
    private val packs = v3.obj("packs")
    private val master = hex(v.str("master"))
    private val ids = v.obj("ids")
    private val fileId = Id.of(hex(ids.str("fileId")))
    private val opId = Id.of(hex(ids.str("opId")))

    /** An operation from its CBOR map as the oracle wrote it, labels as strings. */
    private fun opOf(map: JsonObject): Op {
        fun id(label: String) = Id.of(hex(map.str(label)))
        fun idOrNull(label: String) = if (map.getValue(label) is JsonNull) null else id(label)
        val opId = id("1")
        val ts = map.getValue("3").jsonPrimitive.long
        return when (map.str("2")) {
            "folder.create" -> Op.FolderCreate(opId, ts, id("5"), idOrNull("6"), map.str("7"))
            "folder.rename" -> Op.FolderRename(opId, ts, id("5"), map.str("7"))
            "folder.move" -> Op.FolderMove(opId, ts, id("5"), idOrNull("6"))
            "folder.delete" -> Op.FolderDelete(opId, ts, id("5"))
            "file.rename" -> Op.FileRename(opId, ts, id("4"), map.str("7"))
            "file.move" -> Op.FileMove(opId, ts, id("4"), idOrNull("5"))
            "file.delete" -> Op.FileDelete(opId, ts, id("4"))
            else -> error("unknown type")
        }
    }

    private fun opsOf(pack: JsonObject) = pack.getValue("ops").jsonArray.map { opOf(it.jsonObject) }

    @Test
    fun packsMatchTheOracleByteForByte() {
        KeyRing.derive(realSodium, key(master)).use { ring ->
            for ((name, element) in packs) {
                val pack = element.jsonObject
                val ops = opsOf(pack)
                val sodium = ScriptedSodium(hex(pack.str("nonce")))
                val caption = MetaCodec(sodium, ring.metaKey).encodeOpPack(ops)
                assertEquals(name, pack.str("caption"), caption)
                sodium.assertUsedUp()
                val record = Captions.parse(caption)!!
                assertEquals(RecordKind.OPS, record.kind)
                assertEquals(Captions.V3, record.version)
                val codec = MetaCodec(realSodium, ring.metaKey)
                assertEquals(name, ops, codec.decodeOpPack(record))
                assertEquals(name, ops, codec.decodeOps(record))
                // A version 1 codec reads and writes packs the same.
                assertEquals(name, ops, MetaCodec(realSodium, ring.metaKey, Captions.V1).decodeOps(record))
            }
        }
    }

    @Test
    fun everyPackIsOneLengthAndFitsATelegramMessage() {
        val lengths = packs.values.map { it.jsonObject.str("caption").length }.toSet()
        assertEquals(setOf(4068), lengths)
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val codec = MetaCodec(realSodium, ring.metaKey)
            assertEquals(4068, codec.encodeOpPack(listOf(Op.FileDelete(opId, 1, fileId))).length)
        }
    }

    @Test
    fun packingSplitsWhereTheOracleDoes() {
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val codec = MetaCodec(realSodium, ring.metaKey)
            for ((name, count) in listOf("worstFull" to v3.getValue("worstCount"), "deletesFull" to v3.getValue("deleteCount"))) {
                val full = opsOf(packs.obj(name))
                assertEquals(name, count.jsonPrimitive.int, full.size)
                // One more than fits goes on into a second pack, in order.
                val more = full + full.first().let { first ->
                    when (first) {
                        is Op.FolderCreate -> first.copy(opId = Id.of(ByteArray(16) { 0x77 }))
                        is Op.FileDelete -> first.copy(opId = Id.of(ByteArray(16) { 0x77 }))
                        else -> error("unexpected")
                    }
                }
                val split = codec.packOps(more)
                assertEquals(name, listOf(full.size, 1), split.map { it.size })
                assertEquals(name, more, split.flatten())
                for (pack in split) assertNotNull(codec.decodeOpPack(Captions.parse(codec.encodeOpPack(pack))!!))
                assertThrows(name, IllegalArgumentException::class.java) { codec.encodeOpPack(more) }
            }
            assertEquals(emptyList<List<Op>>(), codec.packOps(emptyList()))
            assertThrows(IllegalArgumentException::class.java) { codec.encodeOpPack(emptyList()) }
            val twice = Op.FileDelete(opId, 1, fileId)
            assertThrows(IllegalArgumentException::class.java) { codec.encodeOpPack(listOf(twice, twice)) }
        }
    }

    /** P sealed as given, the way a buggy or hostile writer holding MetaKey could. */
    private fun sealed(metaKey: SecretKey, plain: ByteArray, ad: String = "coffer/v3/ops", kind: RecordKind = RecordKind.OPS, version: Int = Captions.V3): RawRecord {
        val nonce = realSodium.randomBytes(24)
        val sealed = metaKey.withBytes { realSodium.aeadEncrypt(plain, ad.toByteArray(), nonce, it) }
        return RawRecord(kind, Base64Url.encode(nonce + sealed), version)
    }

    private fun framed(cbor: ByteArray, room: Int = 2998) =
        byteArrayOf((cbor.size ushr 8).toByte(), cbor.size.toByte()) + cbor + ByteArray(room - cbor.size)

    @Test
    fun aPackOfAnyOtherShapeIsRefusedWhole() {
        KeyRing.derive(realSodium, key(master)).use { ring ->
            val codec = MetaCodec(realSodium, ring.metaKey)
            fun read(cbor: ByteArray, room: Int = 2998) = codec.decodeOpPack(sealed(ring.metaKey, framed(cbor, room)))
            val mixed = hex(packs.obj("mixed").str("cbor"))
            val one = hex(packs.obj("one").str("cbor"))
            val deletes = hex(packs.obj("deletesFull").str("cbor"))
            // The oracle's bytes, sealed here, as a control.
            assertEquals(opsOf(packs.obj("mixed")), read(mixed))
            assertNotNull(read(deletes))
            // One byte short or long of the size, a byte after the CBOR that is not zero.
            assertNull(read(mixed, 2997))
            assertNull(read(mixed, 2999))
            assertNull(codec.decodeOpPack(sealed(ring.metaKey, framed(mixed).also { it[it.size - 1] = 1 })))
            // Another key than 1, or a second entry.
            assertNull(read(mixed.copyOf().also { it[1] = 0x02 }))
            assertNull(read(byteArrayOf(0xA2.toByte()) + mixed.copyOfRange(1, mixed.size) + byteArrayOf(0x02, 0x00)))
            // Empty, or a list head in a longer form than its count needs.
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x80.toByte())))
            val oneOp = one.copyOfRange(3, one.size)
            assertNotNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x81.toByte()) + oneOp))
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x98.toByte(), 0x01) + oneOp))
            // A list of indefinite length.
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x9F.toByte()) + oneOp + byteArrayOf(0xFF.toByte())))
            // A count past what is there, or bytes after the list.
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x82.toByte()) + oneOp))
            assertNull(read(mixed + byteArrayOf(0x00)))
            // The same operation twice.
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x82.toByte()) + oneOp + oneOp))
            // One operation that is not valid takes the whole pack.
            val unknownType = oneOp.copyOf().also { bytes ->
                val at = String(bytes, Charsets.ISO_8859_1).indexOf("file.move")
                bytes[at] = 'x'.code.toByte()
            }
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x81.toByte()) + unknownType))
            // A list nested deep inside an operation's place: refused, not walked into.
            val deep = ByteArray(64) { 0x81.toByte() } + byteArrayOf(0x00)
            assertNull(read(byteArrayOf(0xA1.toByte(), 0x01, 0x81.toByte()) + deep))
            // Version 2 frame sizes and ADs do not open as a pack, nor a pack as a single operation.
            assertNull(codec.decodeOpPack(sealed(ring.metaKey, framed(mixed), ad = "coffer/v2/ops")))
            assertNull(codec.decodeOpPack(sealed(ring.metaKey, framed(one, 352))))
            assertNull(codec.decodeOp(sealed(ring.metaKey, framed(mixed), kind = RecordKind.OP)))
            assertNull(codec.decodeOpPack(Captions.parse(codec.encodeOp(Op.FileDelete(opId, 1, fileId)))!!))
            assertEquals(1, codec.decodeOps(Captions.parse(codec.encodeOp(Op.FileDelete(opId, 1, fileId)))!!)?.size)
        }
    }

    @Test
    fun packsAreVersion3OnlyAndVersion3IsPacksOnly() {
        val body = packs.obj("one").str("caption").substringAfter("coffer:v3:ops:")
        assertNotNull(Captions.parse("coffer:v3:ops:$body"))
        assertNull(Captions.parse("coffer:v2:ops:$body"))
        assertNull(Captions.parse("coffer:v1:ops:$body"))
        assertNull(Captions.parse("coffer:v3:op:$body"))
        assertNull(Captions.parse("coffer:v3:blob:$body"))
        assertNull(Captions.parse("coffer:v3:vault:$body"))
        assertThrows(IllegalArgumentException::class.java) { Captions.format(RecordKind.OPS, body, Captions.V2) }
        assertThrows(IllegalArgumentException::class.java) { Captions.format(RecordKind.OP, body, Captions.V3) }
    }
}
