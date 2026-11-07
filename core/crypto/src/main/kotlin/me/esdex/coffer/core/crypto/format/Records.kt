package me.esdex.coffer.core.crypto.format

import java.util.Base64

/** Message kinds of format v1, SPEC §4. The tag is part of the prefix and of the AD. */
enum class RecordKind(val tag: String) {
    VAULT("vault"),
    BLOB("blob"),
    OP("op"),
    INDEX("index"),
}

/** An app message split into kind and body, before any decoding. */
data class RawRecord(val kind: RecordKind, val body: String)

/** The `coffer:v1:<kind>:<body>` prefix on captions and texts, SPEC §4. */
object Captions {
    const val PREFIX = "coffer:v1:"

    fun format(kind: RecordKind, body: String): String = "$PREFIX${kind.tag}:$body"

    /**
     * Returns null for anything that is not an app message of a known kind: such messages are the
     * user's own content and are ignored (SPEC §4).
     */
    fun parse(text: String?): RawRecord? {
        if (text == null || !text.startsWith(PREFIX)) return null
        val rest = text.substring(PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        val kind = RecordKind.entries.firstOrNull { it.tag == rest.substring(0, colon) } ?: return null
        return RawRecord(kind, rest.substring(colon + 1))
    }
}

/** base64url without padding. Decoding accepts only the canonical form, so one value has one encoding. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val alphabet = Regex("[A-Za-z0-9_-]*")

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray? {
        if (!alphabet.matches(text) || text.length % 4 == 1) return null
        val bytes = try {
            decoder.decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Rejects non-zero unused bits in the last character.
        return if (encode(bytes) == text) bytes else null
    }
}
