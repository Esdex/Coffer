package me.esdex.coffer.core.crypto

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A 32-byte key that is zeroed on [close] (SPEC §3.8).
 *
 * Owns its array: [adopt] takes the caller's buffer without copying, so no second copy is left behind.
 * The JVM may still have moved or copied the array; zeroing is best effort, which is all the platform allows.
 */
class SecretKey private constructor(private val bytes: ByteArray) : AutoCloseable {

    // Lending holds the read side, closing the write side: a lock zeroing the key waits for any work still
    // using it, rather than turning it to zeros halfway through, where a caption or a chunk could be
    // encrypted under a key of zeros and sent (audit 1, L10).
    private val lending = ReentrantReadWriteLock()

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Lends the raw key to [block]. The array must not be kept or modified. */
    fun <R> withBytes(block: (ByteArray) -> R): R = lending.read {
        check(!isClosed) { "key is closed" }
        block(bytes)
    }

    fun copy(): SecretKey = withBytes { SecretKey(it.copyOf()) }

    override fun close() = lending.write {
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
