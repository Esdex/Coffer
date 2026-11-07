package me.esdex.coffer.core.vault

import me.esdex.coffer.core.crypto.bip39.Bip39
import kotlin.random.Random

/**
 * After showing the recovery key, the user types a few of its words back (SPEC §3.7), so a key that
 * was never written down is caught while it can still be shown again.
 */
class RecoveryQuiz(private val words: List<String>, random: Random = Random.Default) {

    /** Zero-based positions to ask for, in ascending order. */
    val positions: List<Int> = words.indices.shuffled(random).take(QUESTIONS).sorted()

    /** [answers] in the order of [positions]; case and surrounding spaces do not matter. */
    fun check(answers: List<String>): Boolean =
        answers.size == positions.size &&
            positions.zip(answers).all { (position, answer) -> Bip39.split(answer).singleOrNull() == words[position] }

    companion object {
        const val QUESTIONS = 3
    }
}
