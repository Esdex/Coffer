package me.esdex.coffer.core.vault

import me.esdex.coffer.core.crypto.Argon2Params
import me.esdex.coffer.core.crypto.AuthenticationException
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.KeyRing
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.PasswordKdf
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.RecordKind
import me.esdex.coffer.core.crypto.format.VaultHeader
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StoredMessage

/** A vault header as found in Saved Messages. */
data class FoundHeader(val messageId: MessageId, val header: VaultHeader) {
    companion object {
        /** The header in [message], or null if it is anything else or does not decode. */
        fun from(message: StoredMessage): FoundHeader? {
            val text = (message.content as? MessageContent.Text)?.text ?: return null
            val record = Captions.parse(text)?.takeIf { it.kind == RecordKind.VAULT } ?: return null
            return VaultHeaderCodec.decode(record)?.let { FoundHeader(message.id, it) }
        }
    }
}

/**
 * The master key and its subkeys while the vault is unlocked (SPEC §3.1). [close] wipes them all.
 */
class UnlockedVault internal constructor(private val master: SecretKey, val masterId: Bytes, val keys: KeyRing) :
    AutoCloseable {

    /** For wrapping into the Keystore (SPEC §3.8) and showing the recovery key. */
    fun <R> withMasterKey(block: (SecretKey) -> R): R = block(master)

    override fun close() {
        keys.close()
        master.close()
    }

    companion object {
        /** Takes ownership of [master]. */
        fun open(sodium: Sodium, master: SecretKey): UnlockedVault =
            UnlockedVault(master, MasterKeys.masterId(sodium, master), KeyRing.derive(sodium, master))
    }
}

/** Unlocking with what the user knows (SPEC §3.3, §3.7). */
object VaultUnlock {

    /**
     * Tries [password] on the headers that share the newest header's master_id, newest first, so a
     * password change left half done (SPEC §4.1) does not lock the user out. [headers] newest first.
     * Null if the password opens none of them.
     */
    fun withPassword(sodium: Sodium, headers: List<FoundHeader>, password: CharSequence): UnlockedVault? {
        val newest = headers.firstOrNull() ?: return null
        val candidates = headers
            .filter { it.header.masterId == newest.header.masterId }
            // The same salt, cost and wrapping twice would cost a second Argon2 run for nothing.
            .distinctBy { Triple(it.header.salt, it.header.argon, it.header.wrappedMaster) }
        for (candidate in candidates) {
            open(sodium, candidate.header, password)?.let { return it }
        }
        return null
    }

    private fun open(sodium: Sodium, header: VaultHeader, password: CharSequence): UnlockedVault? {
        val master = PasswordKdf.deriveKek(sodium, password, header.salt, header.argon).use { kek ->
            try {
                MasterKeys.unwrap(sodium, kek, header.wrappedMaster)
            } catch (_: AuthenticationException) {
                return null
            }
        }
        // A header whose master_id does not belong to its own wrapped key is broken or forged.
        if (MasterKeys.masterId(sodium, master) != header.masterId) {
            master.close()
            return null
        }
        return UnlockedVault.open(sodium, master)
    }

    sealed interface MnemonicResult {
        data class Unlocked(val vault: UnlockedVault) : MnemonicResult

        /** A valid recovery key, but of another vault than any header in Saved Messages. */
        data object OtherVault : MnemonicResult
    }

    /**
     * The recovery key gives the master key directly; the newest header with its master_id is the vault.
     * Throws [me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException] for words that are not a recovery key.
     */
    fun withMnemonic(sodium: Sodium, headers: List<FoundHeader>, words: List<String>): MnemonicResult {
        val master = Bip39.toMasterKey(words)
        val masterId = MasterKeys.masterId(sodium, master)
        if (headers.none { it.header.masterId == masterId }) {
            master.close()
            return MnemonicResult.OtherVault
        }
        return MnemonicResult.Unlocked(UnlockedVault(master, masterId, KeyRing.derive(sodium, master)))
    }
}

/** A vault made on this device but not published yet (SPEC §7, step 4). */
class NewVault internal constructor(val vault: UnlockedVault, val header: VaultHeader) : AutoCloseable {
    val recoveryWords: List<String> get() = vault.withMasterKey(Bip39::toMnemonic)

    override fun close() = vault.close()
}

object VaultCreation {

    /** New master key wrapped under [password]. Nothing is sent yet. */
    fun prepare(
        sodium: Sodium,
        password: CharSequence,
        createdAt: Long,
        params: Argon2Params = Argon2Params.DEFAULT,
    ): NewVault {
        val master = MasterKeys.generate(sodium)
        val salt = PasswordKdf.newSalt(sodium)
        val wrapped = PasswordKdf.deriveKek(sodium, password, salt, params).use { kek -> MasterKeys.wrap(sodium, kek, master) }
        val vault = UnlockedVault.open(sodium, master)
        return NewVault(vault, VaultHeader(createdAt, salt, params, wrapped, vault.masterId))
    }
}
