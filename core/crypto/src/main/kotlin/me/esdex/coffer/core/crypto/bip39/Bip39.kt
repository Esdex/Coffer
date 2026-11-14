package me.esdex.coffer.core.crypto.bip39

import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.SecretKey
import java.security.MessageDigest
import java.text.Normalizer

/** Why a mnemonic was rejected; the UI shows a message per reason. */
class InvalidMnemonicException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { WRONG_WORD_COUNT, UNKNOWN_WORD, BAD_CHECKSUM }
}

/**
 * The master key as a 24-word BIP39 mnemonic (English list), SPEC §3.7.
 * The 256-bit key is the BIP39 entropy itself; the BIP39 seed (PBKDF2) is not used.
 */
object Bip39 {
    const val WORD_COUNT = 24

    private const val ENTROPY_BYTES = CofferFormat.KEY_BYTES
    private const val BITS_PER_WORD = 11
    private const val WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

    private val words: List<String> by lazy { loadWordlist() }
    private val indexOf: Map<String, Int> by lazy { words.withIndex().associate { (i, w) -> w to i } }

    fun toMnemonic(master: SecretKey): List<String> = master.withBytes { entropy ->
        // The key with its checksum, in an array of its own that is zeroed after: `entropy + checksum` left
        // a copy of the master key for the garbage collector (audit 1, L11).
        val packed = entropy.copyOf(ENTROPY_BYTES + 1)
        try {
            packed[ENTROPY_BYTES] = checksumByte(entropy)
            val bits = BitString(packed)
            List(WORD_COUNT) { i -> words[bits.read(i * BITS_PER_WORD, BITS_PER_WORD)] }
        } finally {
            packed.fill(0)
        }
    }

    /** Splits user input into words: any whitespace, any case, NFKD as BIP39 prescribes. */
    fun split(input: String): List<String> =
        Normalizer.normalize(input, Normalizer.Form.NFKD).lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun isWord(word: String): Boolean = word in indexOf

    fun toMasterKey(mnemonic: List<String>): SecretKey {
        if (mnemonic.size != WORD_COUNT) {
            throw InvalidMnemonicException(InvalidMnemonicException.Reason.WRONG_WORD_COUNT, "expected $WORD_COUNT words")
        }
        val packed = ByteArray(ENTROPY_BYTES + 1)
        val bits = BitString(packed)
        mnemonic.forEachIndexed { i, word ->
            val index = indexOf[word]
                ?: throw InvalidMnemonicException(InvalidMnemonicException.Reason.UNKNOWN_WORD, "word ${i + 1} is not in the list")
            bits.write(i * BITS_PER_WORD, BITS_PER_WORD, index)
        }
        val entropy = packed.copyOf(ENTROPY_BYTES)
        val checksumOk = checksumByte(entropy) == packed[ENTROPY_BYTES]
        packed.fill(0)
        if (!checksumOk) {
            entropy.fill(0)
            throw InvalidMnemonicException(InvalidMnemonicException.Reason.BAD_CHECKSUM, "checksum does not match")
        }
        return SecretKey.adopt(entropy)
    }

    // 256 bits of entropy take the first 8 bits of SHA-256 as checksum.
    // The digest is zeroed too: its first byte is the checksum, the rest is derived from the key.
    private fun checksumByte(entropy: ByteArray): Byte {
        val digest = MessageDigest.getInstance("SHA-256").digest(entropy)
        return digest[0].also { digest.fill(0) }
    }

    private fun loadWordlist(): List<String> {
        val bytes = Bip39::class.java.getResourceAsStream("english.txt")?.use { it.readBytes() }
            ?: error("BIP39 wordlist resource is missing")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest == WORDLIST_SHA256) { "BIP39 wordlist resource is corrupted" }
        return bytes.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.also { check(it.size == 2048) }
    }

    /** Big-endian bit access over a byte array. */
    private class BitString(private val bytes: ByteArray) {
        fun read(start: Int, count: Int): Int {
            var value = 0
            for (i in start until start + count) {
                value = (value shl 1) or ((bytes[i / 8].toInt() ushr (7 - i % 8)) and 1)
            }
            return value
        }

        fun write(start: Int, count: Int, value: Int) {
            for (i in 0 until count) {
                val bit = (value ushr (count - 1 - i)) and 1
                val pos = start + i
                bytes[pos / 8] = (bytes[pos / 8].toInt() or (bit shl (7 - pos % 8))).toByte()
            }
        }
    }
}
