package me.esdex.coffer.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.crypto.sodium.Sodium

/** Real libsodium through the same adapter the app uses. */
val realSodium: Sodium by lazy { LazySodiumAdapter(LazySodiumJava(SodiumJava())) }

/** Real primitives, but [randomBytes] hands out the given values in order: for byte-exact vectors. */
class ScriptedSodium(vararg randoms: ByteArray) : Sodium by realSodium {
    private val queue = ArrayDeque(randoms.toList())

    override fun randomBytes(size: Int): ByteArray {
        val next = queue.removeFirstOrNull() ?: error("no scripted random bytes left")
        check(next.size == size) { "scripted random has ${next.size} bytes, $size requested" }
        return next
    }

    fun assertUsedUp() = check(queue.isEmpty()) { "${queue.size} scripted random values were not used" }
}

fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Same generator as `pattern()` in the oracle. */
fun pattern(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

fun resourceJson(name: String): JsonObject {
    val text = object {}.javaClass.getResourceAsStream("/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    return Json.parseToJsonElement(text).jsonObject
}

fun key(bytes: ByteArray): SecretKey = SecretKey.adopt(bytes.copyOf())

/** Flips one bit; for tamper tests. */
fun ByteArray.flipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
