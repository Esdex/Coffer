package me.esdex.coffer.core.crypto.format

import java.util.Base64

/** Message kinds, SPEC §4. The tag is part of the prefix and of the AD. */
enum class RecordKind(val tag: String) {
    VAULT("vault"),
    BLOB("blob"),
    OP("op"),
    INDEX("index"),

    /** Several operations in one message, version 3 only (SPEC §4.6). */
    OPS("ops"),
}

/** An app message split into format version, kind and body, before any decoding. */
data class RawRecord(val kind: RecordKind, val body: String, val version: Int = 1)

/**
 * The `coffer:v<version>:<kind>:<body>` prefix on captions and texts, SPEC §4. Version 2 (§4.5) hides
 * sizes; the vault header has only version 1; version 3 (§4.6) has only packs of operations, and they
 * have only version 3.
 */
object Captions {
    const val V1 = 1
    const val V2 = 2
    const val V3 = 3

    fun format(kind: RecordKind, body: String, version: Int = V1): String {
        require(exists(kind, version)) { "no such version of ${kind.tag}" }
        return "coffer:v$version:${kind.tag}:$body"
    }

    private fun exists(kind: RecordKind, version: Int): Boolean = when (kind) {
        RecordKind.VAULT -> version == V1
        RecordKind.OPS -> version == V3
        else -> version == V1 || version == V2
    }

    /**
     * Returns null for anything that is not an app message of a known version and kind: such messages
     * are the user's own content and are ignored (SPEC §4).
     */
    fun parse(text: String?): RawRecord? {
        if (text == null) return null
        val version = when {
            text.startsWith("coffer:v1:") -> V1
            text.startsWith("coffer:v2:") -> V2
            text.startsWith("coffer:v3:") -> V3
            else -> return null
        }
        val rest = text.substring("coffer:v1:".length)
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        val kind = RecordKind.entries.firstOrNull { it.tag == rest.substring(0, colon) } ?: return null
        if (!exists(kind, version)) return null
        return RawRecord(kind, rest.substring(colon + 1), version)
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
