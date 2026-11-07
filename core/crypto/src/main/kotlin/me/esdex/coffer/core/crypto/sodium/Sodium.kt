package me.esdex.coffer.core.crypto.sodium

/**
 * The libsodium primitives Coffer uses, and nothing more.
 *
 * Everything above this interface is plain Kotlin, so it runs on the JVM in tests and on Android in the app
 * with the same code; only the binding underneath differs.
 */
interface Sodium {

    fun randomBytes(size: Int): ByteArray

    /** XChaCha20-Poly1305-IETF. Returns `ciphertext || tag`. */
    fun aeadEncrypt(plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    /** XChaCha20-Poly1305-IETF. Returns null when the tag does not verify. */
    fun aeadDecrypt(ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    /** `crypto_pwhash` with `ALG_ARGON2ID13`. */
    fun argon2id(outSize: Int, password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long): ByteArray

    /** `crypto_kdf_derive_from_key`; [context] is exactly 8 bytes. */
    fun kdfDerive(outSize: Int, subkeyId: Long, context: ByteArray, key: ByteArray): ByteArray

    /** `crypto_generichash` (BLAKE2b), keyed when [key] is not null. */
    fun genericHash(outSize: Int, message: ByteArray, key: ByteArray? = null): ByteArray

    /** Incremental `crypto_generichash`, for content that does not fit in memory. */
    fun genericHashStream(outSize: Int, key: ByteArray? = null): GenericHashStream
}

interface GenericHashStream {
    /** Hashes the first [length] bytes of [data]. */
    fun update(data: ByteArray, length: Int = data.size)

    fun finish(): ByteArray
}
