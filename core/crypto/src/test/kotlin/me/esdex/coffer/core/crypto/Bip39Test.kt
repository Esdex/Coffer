package me.esdex.coffer.core.crypto

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException.Reason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Bip39Test {
    /** The 24-word entries of the reference vectors (trezor/python-mnemonic). */
    private val vectors = resourceJson("bip39_vectors.json").getValue("vectors").jsonArray.map {
        val o = it.jsonObject
        hex(o.getValue("entropy").jsonPrimitive.content) to o.getValue("mnemonic").jsonPrimitive.content
    }

    @Test
    fun referenceVectors() {
        assertEquals(8, vectors.size)
        for ((entropy, mnemonic) in vectors) {
            assertEquals(mnemonic, Bip39.toMnemonic(key(entropy)).joinToString(" "))
            assertArrayEquals(entropy, Bip39.toMasterKey(Bip39.split(mnemonic)).withBytes { it.copyOf() })
        }
    }

    @Test
    fun randomKeysRoundTrip() {
        repeat(200) {
            val master = MasterKeys.generate(realSodium)
            val words = Bip39.toMnemonic(master)
            assertArrayEquals(master.withBytes { it.copyOf() }, Bip39.toMasterKey(words).withBytes { it.copyOf() })
        }
    }

    @Test
    fun inputIsForgivingAboutCaseAndSpacing() {
        val (entropy, mnemonic) = vectors.first()
        val messy = "  " + mnemonic.uppercase().replace(" ", " \n\t ") + "\n"
        assertArrayEquals(entropy, Bip39.toMasterKey(Bip39.split(messy)).withBytes { it.copyOf() })
    }

    @Test
    fun badMnemonicsAreRejectedWithReason() {
        val words = Bip39.split(vectors.first().second)
        fun reason(list: List<String>) = assertThrows(InvalidMnemonicException::class.java) { Bip39.toMasterKey(list) }.reason

        assertEquals(Reason.WRONG_WORD_COUNT, reason(words.dropLast(1)))
        assertEquals(Reason.WRONG_WORD_COUNT, reason(words + "abandon"))
        assertEquals(Reason.UNKNOWN_WORD, reason(words.toMutableList().also { it[5] = "coffer" }))
        // Swapping two different words keeps every word valid but breaks the checksum.
        val i = words.indices.first { words[it] != words[it + 1] }
        assertEquals(Reason.BAD_CHECKSUM, reason(words.toMutableList().also { it[i] = words[i + 1]; it[i + 1] = words[i] }))
        // Last word replaced: same entropy bits except the checksum ones mostly.
        val last = if (words.last() == "zoo") "zone" else "zoo"
        assertEquals(Reason.BAD_CHECKSUM, reason(words.dropLast(1) + last))
    }
}
