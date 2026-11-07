package me.esdex.coffer.core.vault

import com.nulabinc.zxcvbn.Zxcvbn

/**
 * What a new vault password must be. The header sits in Saved Messages, so anyone with access to the
 * account can guess offline; Argon2id slows that down but does not save a weak password.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 10

    /** zxcvbn's scale is 0..4; 3 is "safely unguessable: moderate protection from offline attacks". */
    const val MIN_SCORE = 3

    data class Check(
        /** 0..4 */
        val score: Int,
        val longEnough: Boolean,
        /** zxcvbn's warning and suggestions, in English. */
        val warning: String?,
        val suggestions: List<String>,
    ) {
        val acceptable: Boolean get() = longEnough && score >= MIN_SCORE
    }

    private val zxcvbn by lazy { Zxcvbn() }

    fun check(password: CharSequence): Check {
        if (password.isEmpty()) return Check(0, false, null, emptyList())
        val strength = zxcvbn.measure(password)
        val feedback = strength.feedback
        return Check(
            score = strength.score,
            longEnough = password.codePoints().count() >= MIN_LENGTH,
            warning = feedback.warning?.takeIf { it.isNotBlank() },
            suggestions = feedback.suggestions.orEmpty().filter { it.isNotBlank() },
        )
    }
}
