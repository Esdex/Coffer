package me.esdex.coffer.core.crypto

/**
 * A 32-byte key that is zeroed on [close] (SPEC §3.8).
 *
 * Owns its array: [adopt] takes the caller's buffer without copying, so no second copy is left behind.
 * The JVM may still have moved or copied the array; zeroing is best effort, which is all the platform allows.
 */
class SecretKey private constructor(private val bytes: ByteArray) : AutoCloseable {

    var isClosed: Boolean = false
        private set

    /** Lends the raw key to [block]. The array must not be kept or modified. */
    fun <R> withBytes(block: (ByteArray) -> R): R {
        check(!isClosed) { "key is closed" }
        return block(bytes)
    }

    fun copy(): SecretKey = withBytes { SecretKey(it.copyOf()) }

    override fun close() {
        bytes.fill(0)
        isClosed = true
    }

    override fun toString(): String = "SecretKey(***)"

    companion object {
        /** Takes ownership of [bytes]; the caller must not use the array afterwards. */
        fun adopt(bytes: ByteArray): SecretKey {
            require(bytes.size == CofferFormat.KEY_BYTES) { "key must be ${CofferFormat.KEY_BYTES} bytes" }
            return SecretKey(bytes)
        }
    }
}
