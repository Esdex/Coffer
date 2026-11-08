package me.esdex.coffer.core.crypto

import me.esdex.coffer.core.crypto.sodium.Sodium
import java.text.Normalizer

/** Thrown when a tag does not verify: wrong key, wrong associated data or tampered bytes. */
class AuthenticationException(message: String) : Exception(message)

/** Argon2id cost, stored in the vault header so it can change later (SPEC §3.3). */
data class Argon2Params(val opsLimit: Long, val memLimitBytes: Long) {
    init {
        require(opsLimit in OPS_RANGE) { "opsLimit out of range" }
        require(memLimitBytes in MEM_RANGE) { "memLimit out of range" }
    }

    companion object {
        // Bounds on what a header may ask for. The header is read before any authentication,
        // so without a ceiling a crafted one could make the device allocate gigabytes.
        // Declared before DEFAULT: companion properties initialize in order, and DEFAULT's init reads these.
        val OPS_RANGE = 1L..16L
        val MEM_RANGE = 8L * 1024 * 1024..1024L * 1024 * 1024

        val DEFAULT = Argon2Params(opsLimit = 3, memLimitBytes = 64L * 1024 * 1024)
    }
}

/** Master key operations, SPEC §3.1, §3.2, §3.7. */
object MasterKeys {

    fun generate(sodium: Sodium): SecretKey = SecretKey.adopt(sodium.randomBytes(CofferFormat.KEY_BYTES))

    /** `crypto_generichash(16, "coffer/v1/master-id", key = master)`: tells which header a key belongs to. */
    fun masterId(sodium: Sodium, master: SecretKey): Bytes = master.withBytes {
        Bytes.of(sodium.genericHash(CofferFormat.MASTER_ID_BYTES, AssociatedData.MASTER_ID_MESSAGE, it))
    }

    fun wrap(sodium: Sodium, kek: SecretKey, master: SecretKey): Bytes =
        KeyWrap.wrap(sodium, kek, master, AssociatedData.WRAP_MASTER)

    fun unwrap(sodium: Sodium, kek: SecretKey, wrapped: Bytes): SecretKey =
        KeyWrap.unwrap(sodium, kek, wrapped, AssociatedData.WRAP_MASTER)
}

/** Password to KEK, SPEC §3.3. */
object PasswordKdf {

    /**
     * The password is NFC-normalized before UTF-8 encoding, so the same password typed on keyboards that
     * produce composed or decomposed characters gives the same KEK on every device.
     */
    fun deriveKek(sodium: Sodium, password: CharSequence, salt: Bytes, params: Argon2Params): SecretKey {
        require(salt.size == CofferFormat.SALT_BYTES) { "salt must be ${CofferFormat.SALT_BYTES} bytes" }
        val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        try {
            return SecretKey.adopt(
                sodium.argon2id(
                    CofferFormat.KEY_BYTES, passwordBytes, salt.toByteArray(), params.opsLimit, params.memLimitBytes,
                ),
            )
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun newSalt(sodium: Sodium): Bytes = Bytes.of(sodium.randomBytes(CofferFormat.SALT_BYTES))
}

/** Subkeys of the master key, SPEC §3.1. The master key itself never encrypts anything. */
class KeyRing private constructor(
    val metaKey: SecretKey,
    val indexKey: SecretKey,
    val wrapKey: SecretKey,
) : AutoCloseable {

    override fun close() {
        metaKey.close()
        indexKey.close()
        wrapKey.close()
    }

    companion object {
        private fun context(text: String) = text.toByteArray(Charsets.US_ASCII).also { check(it.size == 8) }

        private val META_CONTEXT = context("cofmeta_")
        private val INDEX_CONTEXT = context("cofindex")
        private val WRAP_CONTEXT = context("cofwrap_")

        fun derive(sodium: Sodium, master: SecretKey): KeyRing = master.withBytes { key ->
            fun subkey(id: Long, context: ByteArray) =
                SecretKey.adopt(sodium.kdfDerive(CofferFormat.KEY_BYTES, id, context, key))
            KeyRing(
                metaKey = subkey(1, META_CONTEXT),
                indexKey = subkey(2, INDEX_CONTEXT),
                wrapKey = subkey(3, WRAP_CONTEXT),
            )
        }
    }
}

/**
 * The device-only key that seals the local index (SPEC §8). Derived like the subkeys, with its own
 * context, and never used for anything that goes to Telegram.
 */
object LocalKeys {
    private val CONTEXT = "coflocal".toByteArray(Charsets.US_ASCII)

    fun derive(sodium: Sodium, master: SecretKey): SecretKey = master.withBytes {
        SecretKey.adopt(sodium.kdfDerive(CofferFormat.KEY_BYTES, 1, CONTEXT, it))
    }
}

/** Per-file data keys, SPEC §3.1, §3.2. */
object FileKeys {

    fun generateDek(sodium: Sodium): SecretKey = SecretKey.adopt(sodium.randomBytes(CofferFormat.KEY_BYTES))

    fun wrapDek(sodium: Sodium, wrapKey: SecretKey, dek: SecretKey, fileId: Id): Bytes =
        KeyWrap.wrap(sodium, wrapKey, dek, AssociatedData.wrapDek(fileId))

    fun unwrapDek(sodium: Sodium, wrapKey: SecretKey, wrapped: Bytes, fileId: Id): SecretKey =
        KeyWrap.unwrap(sodium, wrapKey, wrapped, AssociatedData.wrapDek(fileId))
}

/** `nonce(24) || XChaCha20-Poly1305(key)` = 72 bytes, SPEC §3.2. */
internal object KeyWrap {

    fun wrap(sodium: Sodium, kek: SecretKey, key: SecretKey, ad: ByteArray): Bytes {
        val nonce = sodium.randomBytes(CofferFormat.NONCE_BYTES)
        val sealed = kek.withBytes { k -> key.withBytes { plain -> sodium.aeadEncrypt(plain, ad, nonce, k) } }
        return Bytes.of(nonce + sealed)
    }

    fun unwrap(sodium: Sodium, kek: SecretKey, wrapped: Bytes, ad: ByteArray): SecretKey {
        if (wrapped.size != CofferFormat.WRAPPED_KEY_BYTES) throw AuthenticationException("wrapped key has wrong size")
        val raw = wrapped.toByteArray()
        val nonce = raw.copyOfRange(0, CofferFormat.NONCE_BYTES)
        val sealed = raw.copyOfRange(CofferFormat.NONCE_BYTES, raw.size)
        val plain = kek.withBytes { sodium.aeadDecrypt(sealed, ad, nonce, it) }
            ?: throw AuthenticationException("key unwrap failed")
        return SecretKey.adopt(plain)
    }
}
