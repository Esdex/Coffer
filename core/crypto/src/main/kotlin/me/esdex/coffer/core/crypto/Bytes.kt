package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.sodium.Sodium

/**
 * Immutable bytes with value equality, so payload classes can be data classes.
 * Only for public values (hashes, wrapped keys, salts); secrets live in [SecretKey].
 */
class Bytes private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is Bytes && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "Bytes(${bytes.size})"

    companion object {
        fun of(bytes: ByteArray): Bytes = Bytes(bytes.copyOf())
    }
}

/** A 16-byte random identifier: file, folder, operation or snapshot (SPEC §4.0). */
class Id private constructor(private val bytes: ByteArray) {

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is Id && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    // Prefix only: ids come out of decrypted metadata and should not end up whole in logs.
    override fun toString(): String = "Id(" + bytes.take(4).joinToString("") { "%02x".format(it) } + "…)"

    companion object {
        fun random(sodium: Sodium): Id = Id(sodium.randomBytes(CofferFormat.ID_BYTES))

        fun of(bytes: ByteArray): Id {
            require(bytes.size == CofferFormat.ID_BYTES) { "id must be ${CofferFormat.ID_BYTES} bytes" }
            return Id(bytes.copyOf())
        }
    }
}
