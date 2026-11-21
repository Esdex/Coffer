package me.esdex.coffer.core.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.SecretKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Biometric unlock (SPEC §3.8): the master key wrapped by an AES-256-GCM Keystore key that needs
 * strong biometrics for every use and dies when a new fingerprint or face is enrolled.
 *
 * Biometrics only, not the phone's PIN as well: Android does not invalidate a key that also takes the
 * device credential when a new fingerprint is enrolled (seen on a Nothing Phone (1), Android 15), and
 * whoever knows the PIN could open the vault with it anyway.
 *
 * The Keystore hands out a [Cipher] that works only after a BiometricPrompt with that cipher as its
 * CryptoObject succeeds, so this class prepares ciphers and the UI runs the prompt in between.
 */
class DeviceUnlock(private val context: Context, private val dir: File) {
    private val file get() = File(dir, "master.bin")

    /** The device has strong biometrics enrolled that Keystore can use. */
    fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    val isEnabled: Boolean get() = file.exists()

    /** Step 1 of enabling: a fresh Keystore key and a cipher to authenticate. */
    fun enableCipher(): Cipher {
        disable()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    // 0 seconds: every use needs its own authentication, bound to this cipher.
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
        }.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Step 2 of enabling, with the cipher the prompt authenticated. */
    fun completeEnable(cipher: Cipher, vault: UnlockedVault) {
        cipher.updateAAD(AD)
        val sealed = vault.withMasterKey { master -> master.withBytes { cipher.doFinal(it) } }
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                val masterId = vault.masterId.toByteArray()
                data.writeByte(masterId.size)
                data.write(masterId)
                data.writeByte(cipher.iv.size)
                data.write(cipher.iv)
                data.write(sealed)
            }
        }.toByteArray()
        dir.mkdirs()
        val temp = File(dir, "master.bin.tmp")
        temp.writeBytes(bytes)
        check(temp.renameTo(file)) { "could not store the wrapped key" }
    }

    sealed interface Prepared {
        data class Ready(val cipher: Cipher) : Prepared

        /** Not enabled, or enabled for another vault. */
        data object NotEnabled : Prepared

        /**
         * A new fingerprint or face, or the lock screen removed: the key is gone (SPEC §3.8). A key that
         * also takes the device credential, made by builds before biometrics only, ends up here too.
         */
        data object Invalidated : Prepared
    }

    /** Step 1 of unlocking: a cipher to authenticate, for the vault with [expectedMasterId]. */
    fun unlockCipher(expectedMasterId: Bytes): Prepared {
        val stored = read() ?: return Prepared.NotEnabled
        if (stored.masterId != expectedMasterId) return Prepared.NotEnabled
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as javax.crypto.SecretKey? ?: run {
                disable()
                return Prepared.Invalidated
            }
            if (!biometricOnly(key)) {
                disable()
                return Prepared.Invalidated
            }
            Prepared.Ready(Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, stored.iv)) })
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable()
            Prepared.Invalidated
        }
    }

    /** Step 2 of unlocking: the master key, or null if the stored blob does not open. */
    fun completeUnlock(cipher: Cipher): SecretKey? {
        val stored = read() ?: return null
        return runCatching {
            cipher.updateAAD(AD)
            SecretKey.adopt(cipher.doFinal(stored.sealed))
        }.getOrNull()
    }

    fun disable() {
        file.delete()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun biometricOnly(key: javax.crypto.SecretKey): Boolean {
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return info.userAuthenticationType == KeyProperties.AUTH_BIOMETRIC_STRONG
    }

    private class Stored(val masterId: Bytes, val iv: ByteArray, val sealed: ByteArray)

    private fun read(): Stored? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream()).use { data ->
                val masterId = ByteArray(data.readUnsignedByte()).also(data::readFully)
                val iv = ByteArray(data.readUnsignedByte()).also(data::readFully)
                Stored(Bytes.of(masterId), iv, data.readBytes())
            }
        }.getOrNull()
    }

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_STRONG

        /** The system prompt for this key: the same authenticators the key was made for (SPEC §3.8). */
        fun promptInfo(title: String, cancel: String): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButtonText(cancel)
            .build()

        /** Biometrics or the phone's PIN with nothing unlocked behind it: proof that the owner holds the phone. */
        fun ownerPromptInfo(title: String): BiometricPrompt.PromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "coffer.master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private val AD = "coffer/device/master".toByteArray(Charsets.US_ASCII)
    }
}
