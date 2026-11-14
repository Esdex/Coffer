package me.esdex.coffer.core.vault

import com.nulabinc.zxcvbn.Zxcvbn

/**
 * What a new vault password must be. The header sits in Saved Messages, so anyone with access to the
 * account can guess offline; Argon2id slows that down but does not save a weak password.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    /**
     * How many guesses, by zxcvbn's estimate, a password must hold out for. Its score 3 starts at 10^8,
     * which one GPU gets through in about four days against Argon2id at 128 MiB and 4 passes (audit 1, M8);
     * even its score 4 starts at 10^10, where "MySecretPass2024" sits. 10^12 is ten thousand times the old
     * floor: three random words or twelve mixed characters reach it.
     */
    const val MIN_GUESSES_LOG10 = 12.0

    data class Check(
        /** zxcvbn's own, 0..4 */
        val score: Int,
        val longEnough: Boolean,
        /** Holds out for [MIN_GUESSES_LOG10]. */
        val hardEnough: Boolean,
        /** zxcvbn's warning and suggestions, in English. */
        val warning: String?,
        val suggestions: List<String>,
    ) {
        val acceptable: Boolean get() = longEnough && hardEnough

        /** For the meter, 0..4: full only for a password that will do, so "strong" never shows on one refused. */
        val meterScore: Int get() = if (acceptable) 4 else minOf(score, 3)
    }

    private val zxcvbn by lazy { Zxcvbn() }

    fun check(password: CharSequence): Check {
        if (password.isEmpty()) return Check(0, false, false, null, emptyList())
        val strength = zxcvbn.measure(password)
        val feedback = strength.feedback
        return Check(
            score = strength.score,
            longEnough = password.codePoints().count() >= MIN_LENGTH,
            hardEnough = strength.guessesLog10 >= MIN_GUESSES_LOG10,
            warning = feedback.warning?.takeIf { it.isNotBlank() },
            suggestions = feedback.suggestions.orEmpty().filter { it.isNotBlank() },
        )
    }
}
