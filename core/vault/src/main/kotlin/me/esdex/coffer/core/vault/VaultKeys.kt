package me.esdex.coffer.core.vault

import me.esdex.coffer.core.crypto.Argon2Params
import me.esdex.coffer.core.crypto.AuthenticationException
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.KeyRing
import me.esdex.coffer.core.crypto.LocalKeys
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
 * The master key and its subkeys while the vault is unlocked (SPEC §3.1), plus the device-only key that
 * seals the local index (SPEC §8). [close] wipes them all.
 */
class UnlockedVault private constructor(
    private val master: SecretKey,
    val masterId: Bytes,
    val keys: KeyRing,
    val localKey: SecretKey,
) : AutoCloseable {

    /** For wrapping into the Keystore (SPEC §3.8) and showing the recovery key. */
    fun <R> withMasterKey(block: (SecretKey) -> R): R = block(master)

    override fun close() {
        keys.close()
        localKey.close()
        master.close()
    }

    companion object {
        /** Takes ownership of [master]. */
        fun open(sodium: Sodium, master: SecretKey): UnlockedVault = UnlockedVault(
            master,
            MasterKeys.masterId(sodium, master),
            KeyRing.derive(sodium, master),
            LocalKeys.derive(sodium, master),
        )
    }
}

/** Unlocking with what the user knows (SPEC §3.3, §3.7). */
object VaultUnlock {

    /**
     * Tries [password] on [headers], newest first, so a password change left half done (SPEC §4.1) does
     * not lock the user out. Every vault's headers are tried, not only the newest one's: a newer header
     * of another vault, which anyone in the Telegram account can post, must not hide the user's own
     * (audit 1, M2). Null if the password opens none of them.
     */
    fun withPassword(sodium: Sodium, headers: List<FoundHeader>, password: CharSequence): UnlockedVault? =
        withPasswordFrom(sodium, headers, password)?.first

    /**
     * As [withPassword], also telling which header opened. [preferred], the header that opened last time,
     * is tried first, so newer headers anyone in the Telegram account can post, even with the vault's
     * master_id, cost nothing when the right password is typed (audit 1, M3).
     */
    fun withPasswordFrom(
        sodium: Sodium,
        headers: List<FoundHeader>,
        password: CharSequence,
        preferred: MessageId? = null,
        tried: MutableSet<KdfInput> = HashSet(),
    ): Pair<UnlockedVault, MessageId>? {
        val candidates = (headers.filter { it.messageId == preferred } + headers.filter { it.messageId != preferred })
            .filter { affordable(it.header.argon) }
        // One Argon2 run per salt and cost, whatever number of headers share them: a copy of the real header,
        // posted again by anyone in the account, costs nothing more (audit 2, L-a). [tried] carries this over
        // to a second pass with the same password, which then works out only what is new.
        for ((input, group) in candidates.groupBy { KdfInput(it.header.salt, it.header.argon) }) {
            if (!tried.add(input)) continue
            // A header that cannot be worked out, for example memory the phone cannot give, opens nothing.
            val kek = try {
                PasswordKdf.deriveKek(sodium, password, input.salt, input.argon)
            } catch (_: IllegalStateException) {
                continue
            }
            kek.use {
                for (candidate in group) open(sodium, kek, candidate.header)?.let { return it to candidate.messageId }
            }
        }
        return null
    }

    /** What an Argon2 run depends on besides the password. */
    data class KdfInput(val salt: Bytes, val argon: Argon2Params)

    /**
     * Whether a header's Argon2 cost is one this version would ever have written: up to four times the
     * default's work, and no more memory than a phone can be expected to give. The format allows up to
     * 16 passes over 1 GiB, about 32 times the default; a forged header asking that much would make every
     * unlock take a minute, or fail for lack of memory (audit 1, M3). Such a header is not tried.
     */
    internal fun affordable(params: Argon2Params): Boolean =
        params.memLimitBytes <= MAX_MEMORY && params.opsLimit * params.memLimitBytes <= MAX_WORK

    private const val MAX_MEMORY = 256L * 1024 * 1024
    private val MAX_WORK = 4 * Argon2Params.DEFAULT.opsLimit * Argon2Params.DEFAULT.memLimitBytes

    private fun open(sodium: Sodium, kek: SecretKey, header: VaultHeader): UnlockedVault? {
        val master = try {
            MasterKeys.unwrap(sodium, kek, header.wrappedMaster)
        } catch (_: AuthenticationException) {
            return null
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
        return MnemonicResult.Unlocked(UnlockedVault.open(sodium, master))
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

    /**
     * A header for the vault whose recovery key is [words], under a new [password], for a vault whose
     * header is gone from Saved Messages while its files are still there (audit 1, M1). The master key is
     * the one the words hold, so everything it encrypted opens again. Throws
     * [me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException] for words that are not a recovery key.
     */
    fun restore(
        sodium: Sodium,
        words: List<String>,
        password: CharSequence,
        createdAt: Long,
        params: Argon2Params = Argon2Params.DEFAULT,
    ): NewVault {
        val vault = UnlockedVault.open(sodium, Bip39.toMasterKey(words))
        val header = try {
            rewrap(sodium, vault, password, createdAt, params)
        } catch (e: Exception) {
            vault.close()
            throw e
        }
        return NewVault(vault, header)
    }

    /**
     * A header for the open [vault] under a new password (SPEC §3.1, §4.1): fresh salt, the same master
     * key and master_id. Files are not touched.
     */
    fun rewrap(
        sodium: Sodium,
        vault: UnlockedVault,
        password: CharSequence,
        createdAt: Long,
        params: Argon2Params = Argon2Params.DEFAULT,
    ): VaultHeader {
        val salt = PasswordKdf.newSalt(sodium)
        val wrapped = PasswordKdf.deriveKek(sodium, password, salt, params).use { kek ->
            vault.withMasterKey { MasterKeys.wrap(sodium, kek, it) }
        }
        return VaultHeader(createdAt, salt, params, wrapped, vault.masterId)
    }
}
