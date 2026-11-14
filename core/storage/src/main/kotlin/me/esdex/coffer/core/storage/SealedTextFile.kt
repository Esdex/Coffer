package me.esdex.coffer.core.storage

import java.io.File

/**
 * Seals small files on this phone with a key that does not leave it (the Android Keystore's, in the app),
 * and opens them again. [purpose] ties each file to what it is for, so one cannot stand in for another.
 */
interface DeviceSealer {
    fun seal(plain: ByteArray, purpose: String): ByteArray

    /** Null if it does not open: another key, a key the phone lost, or tampered bytes. */
    fun open(sealed: ByteArray, purpose: String): ByteArray?

    companion object {
        /** For tests and tools: writes the text as it is. */
        val NONE: DeviceSealer = object : DeviceSealer {
            override fun seal(plain: ByteArray, purpose: String) = plain
            override fun open(sealed: ByteArray, purpose: String) = sealed
        }
    }
}

/**
 * A text file kept sealed by [sealer], for what a copy of the phone's storage should not show: the vault's
 * headers, which feed offline password guessing, and the cache's record of what was opened when (audit 1,
 * L9, L14). A file an older version wrote in the clear is still read, and sealed there and then.
 */
class SealedTextFile(private val file: File, private val sealer: DeviceSealer, private val purpose: String) {

    /** The text, or null if there is no file or it does not open. */
    fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (sealer === DeviceSealer.NONE) return bytes.toString(Charsets.UTF_8)
        if (!bytes.startsWith(MAGIC)) {
            // Left in the clear by an older version: sealed now rather than at the next change, which for
            // the cache's record could be a long time off.
            val text = bytes.toString(Charsets.UTF_8)
            write(text)
            return text
        }
        return sealer.open(bytes.copyOfRange(MAGIC.size, bytes.size), purpose)?.toString(Charsets.UTF_8)
    }

    fun write(text: String) {
        file.parentFile?.mkdirs()
        val plain = text.toByteArray(Charsets.UTF_8)
        val bytes = if (sealer === DeviceSealer.NONE) plain else MAGIC + sealer.seal(plain, purpose)
        val temp = File(file.path + ".tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(file)) { "could not store ${file.name}" }
    }

    fun delete() = file.delete()

    fun exists() = file.exists()

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        // Not text any line-based reader of the old files would take for a record.
        val MAGIC = byteArrayOf(0, 'C'.code.toByte(), 'F'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte())
    }
}
