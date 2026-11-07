package me.esdex.coffer.core.crypto.sodium

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import me.esdex.coffer.core.crypto.CofferFormat

/**
 * [Sodium] over Lazysodium. Takes the common base class, so the same adapter serves
 * `LazySodiumJava` in tests and `LazySodiumAndroid` in the app.
 */
class LazySodiumAdapter(private val lazySodium: LazySodium) : Sodium {

    override fun randomBytes(size: Int): ByteArray = lazySodium.randomBytesBuf(size)

    override fun aeadEncrypt(plaintext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        requireKeyAndNonce(key, nonce)
        val out = ByteArray(plaintext.size + CofferFormat.TAG_BYTES)
        val ok = lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            out, null, plaintext, plaintext.size.toLong(), ad, ad.size.toLong(), null, nonce, key,
        )
        check(ok) { "crypto_aead_xchacha20poly1305_ietf_encrypt failed" }
        return out
    }

    override fun aeadDecrypt(ciphertext: ByteArray, ad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        requireKeyAndNonce(key, nonce)
        if (ciphertext.size < CofferFormat.TAG_BYTES) return null
        val out = ByteArray(ciphertext.size - CofferFormat.TAG_BYTES)
        val ok = lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, null, null, ciphertext, ciphertext.size.toLong(), ad, ad.size.toLong(), nonce, key,
        )
        return if (ok) out else null
    }

    override fun argon2id(
        outSize: Int,
        password: ByteArray,
        salt: ByteArray,
        opsLimit: Long,
        memLimitBytes: Long,
    ): ByteArray {
        val out = ByteArray(outSize)
        val ok = lazySodium.cryptoPwHash(
            out, outSize, password, password.size, salt, opsLimit, NativeLong(memLimitBytes),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "crypto_pwhash failed (out of memory or limits out of range)" }
        return out
    }

    override fun kdfDerive(outSize: Int, subkeyId: Long, context: ByteArray, key: ByteArray): ByteArray {
        require(context.size == 8) { "KDF context must be 8 bytes" }
        val out = ByteArray(outSize)
        check(lazySodium.cryptoKdfDeriveFromKey(out, outSize, subkeyId, context, key) == 0) {
            "crypto_kdf_derive_from_key failed"
        }
        return out
    }

    override fun genericHash(outSize: Int, message: ByteArray, key: ByteArray?): ByteArray {
        val out = ByteArray(outSize)
        val ok = if (key == null) {
            lazySodium.cryptoGenericHash(out, outSize, message, message.size.toLong())
        } else {
            lazySodium.cryptoGenericHash(out, outSize, message, message.size.toLong(), key, key.size)
        }
        check(ok) { "crypto_generichash failed" }
        return out
    }

    override fun genericHashStream(outSize: Int, key: ByteArray?): GenericHashStream {
        val state = ByteArray(lazySodium.sodium.crypto_generichash_statebytes())
        val ok = if (key == null) {
            lazySodium.cryptoGenericHashInit(state, outSize)
        } else {
            lazySodium.cryptoGenericHashInit(state, key, key.size, outSize)
        }
        check(ok) { "crypto_generichash_init failed" }
        return object : GenericHashStream {
            override fun update(data: ByteArray, length: Int) {
                require(length in 0..data.size) { "length out of range" }
                check(lazySodium.cryptoGenericHashUpdate(state, data, length.toLong())) {
                    "crypto_generichash_update failed"
                }
            }

            override fun finish(): ByteArray {
                val out = ByteArray(outSize)
                check(lazySodium.cryptoGenericHashFinal(state, out, outSize)) { "crypto_generichash_final failed" }
                state.fill(0)
                return out
            }
        }
    }

    private fun requireKeyAndNonce(key: ByteArray, nonce: ByteArray) {
        require(key.size == CofferFormat.KEY_BYTES) { "AEAD key must be ${CofferFormat.KEY_BYTES} bytes" }
        require(nonce.size == CofferFormat.NONCE_BYTES) { "AEAD nonce must be ${CofferFormat.NONCE_BYTES} bytes" }
    }
}
