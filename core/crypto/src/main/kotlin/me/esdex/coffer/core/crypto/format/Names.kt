package me.esdex.coffer.core.crypto.format

import me.esdex.coffer.core.crypto.CofferFormat

/** File and folder name limits, SPEC §4.0. */
object FileNames {

    // Room left for the extension when shortening; longer "extensions" are not treated as one.
    private const val MAX_EXTENSION_BYTES = 32

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** Non-empty, well-formed UTF-16 (no lone surrogates) and at most 255 bytes in UTF-8. */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && isWellFormed(name) && utf8Size(name) <= CofferFormat.MAX_NAME_BYTES

    /**
     * For imports only: shortens a too long name at a code point boundary and keeps the extension.
     * The UI refuses names that are too long instead of shortening them.
     */
    fun fitForImport(name: String): String {
        require(name.isNotEmpty() && isWellFormed(name)) { "name must be non-empty, well-formed text" }
        if (utf8Size(name) <= CofferFormat.MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && utf8Size(name.substring(dot)) <= MAX_EXTENSION_BYTES) name.substring(dot) else ""
        val base = name.substring(0, name.length - extension.length)
        return truncate(base, CofferFormat.MAX_NAME_BYTES - utf8Size(extension)) + extension
    }

    private fun truncate(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val size = utf8Size(String(Character.toChars(codePoint)))
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return text.substring(0, end)
    }

    private fun isWellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return false
                i++
            }
        }
        return true
    }
}

/** MIME type limit, SPEC §4.0. */
object MimeTypes {
    /** Anything missing, non-ASCII or longer than 127 bytes becomes `application/octet-stream`. */
    fun normalize(mime: String?): String {
        if (mime.isNullOrBlank()) return CofferFormat.FALLBACK_MIME
        // Printable ASCII; spaces are allowed for parameters such as "; charset=utf-8".
        val ascii = mime.all { it.code in 0x20..0x7e }
        return if (ascii && mime.length <= CofferFormat.MAX_MIME_BYTES) mime else CofferFormat.FALLBACK_MIME
    }

    internal fun isNormalized(mime: String): Boolean = normalize(mime) == mime
}
