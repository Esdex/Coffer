package me.esdex.coffer.core.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.esdex.coffer.core.crypto.format.Captions
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.storage.MessageId
import java.io.File
import java.util.Properties

/**
 * What this device remembers about its vault between launches: the headers, so the unlock screen
 * comes up without a search and works offline, and a few preferences. Nothing secret: the headers are
 * the same text that sits in Saved Messages.
 */
class VaultStore(private val dir: File) {
    private val headersFile get() = File(dir, "headers.txt")
    private val prefsFile get() = File(dir, "vault.properties")

    private val version = MutableStateFlow(0)

    /** Ticks on every change to the preferences, for screens that show them. */
    val changes: StateFlow<Int> = version.asStateFlow()

    @Synchronized
    fun headers(): List<FoundHeader> {
        if (!headersFile.exists()) return emptyList()
        return headersFile.readLines().mapNotNull { line ->
            val (id, caption) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val record = Captions.parse(caption) ?: return@mapNotNull null
            val header = VaultHeaderCodec.decode(record) ?: return@mapNotNull null
            FoundHeader(MessageId(id.toLongOrNull() ?: return@mapNotNull null), header)
        }
    }

    @Synchronized
    fun saveHeaders(headers: List<FoundHeader>) {
        write(headersFile, headers.joinToString("") { "${it.messageId.value} ${VaultHeaderCodec.encode(it.header)}\n" })
    }

    /**
     * The header the password opened last time, tried first next time, so newer headers someone else
     * posted, even with the vault's master_id, do not slow down every unlock (audit 1, M3).
     */
    var openedHeader: MessageId?
        @Synchronized get() = prefs().getProperty(OPENED_HEADER)?.toLongOrNull()?.let(::MessageId)

        @Synchronized set(value) = updatePrefs { if (value == null) it.remove(OPENED_HEADER) else it.setProperty(OPENED_HEADER, value.value.toString()) }

    /**
     * The password was changed on another device: the master key the fingerprint holds here still opens the
     * vault, so fingerprint unlock is to be turned off until the new password is typed once (audit 1, M7).
     */
    var deviceUnlockStale: Boolean
        @Synchronized get() = prefs().getProperty(DEVICE_UNLOCK_STALE) == "true"

        @Synchronized set(value) = updatePrefs { if (value) it.setProperty(DEVICE_UNLOCK_STALE, "true") else it.remove(DEVICE_UNLOCK_STALE) }

    /** Auto-lock after this long in the background; null = off. SPEC §9: on, 5 minutes by default. */
    var autoLockMinutes: Int?
        @Synchronized get() {
            val value = prefs().getProperty(AUTO_LOCK) ?: return DEFAULT_AUTO_LOCK_MINUTES
            return value.toIntOrNull()?.takeIf { it > 0 }
        }

        @Synchronized set(value) = updatePrefs { it.setProperty(AUTO_LOCK, value?.toString() ?: "off") }

    /** The user said no to biometric unlock once; do not ask again on every unlock. */
    var biometricOffered: Boolean
        @Synchronized get() = prefs().getProperty(BIOMETRIC_OFFERED) == "true"

        @Synchronized set(value) = updatePrefs { it.setProperty(BIOMETRIC_OFFERED, value.toString()) }

    /** The note not to delete Coffer's messages in Telegram was read: once per install, after the vault first opens. */
    var savedMessagesNoticeSeen: Boolean
        @Synchronized get() = prefs().getProperty(SAVED_MESSAGES_NOTICE) == "true"

        @Synchronized set(value) = updatePrefs { it.setProperty(SAVED_MESSAGES_NOTICE, value.toString()) }

    /** FLAG_SECURE on every screen (SPEC §9: off by default). */
    var blockScreenshots: Boolean
        @Synchronized get() = prefs().getProperty(BLOCK_SCREENSHOTS) == "true"

        @Synchronized set(value) = updatePrefs { it.setProperty(BLOCK_SCREENSHOTS, value.toString()) }

    /** The weekly integrity check (SPEC §5.8: on by default). */
    var weeklyCheck: Boolean
        @Synchronized get() = prefs().getProperty(WEEKLY_CHECK) != "false"

        @Synchronized set(value) = updatePrefs { it.setProperty(WEEKLY_CHECK, value.toString()) }

    /**
     * New uploads padded so their exact size does not show in Telegram (SPEC §4.5): on by default, as
     * agreed with Esdex 2026-11-11. Files already uploaded keep what they were sent with.
     */
    var hideSizes: Boolean
        @Synchronized get() = prefs().getProperty(HIDE_SIZES) != "false"

        @Synchronized set(value) = updatePrefs { it.setProperty(HIDE_SIZES, value.toString()) }

    /** Debug builds: the Debug section of Settings, shown after six taps on the icon in About and the screen lock. */
    var debugTools: Boolean
        @Synchronized get() = prefs().getProperty(DEBUG_TOOLS) == "true"

        @Synchronized set(value) = updatePrefs { it.setProperty(DEBUG_TOOLS, value.toString()) }

    /** Signing out of Telegram forgets the vault on this device (SPEC §9), preferences included. */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        version.value++
    }

    private fun prefs() = Properties().apply { if (prefsFile.exists()) prefsFile.inputStream().use(::load) }

    private fun updatePrefs(change: (Properties) -> Unit) {
        val prefs = prefs().also(change)
        val text = java.io.StringWriter().also { prefs.store(it, null) }.toString()
        write(prefsFile, text)
        version.value++
    }

    private fun write(file: File, text: String) {
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(text)
        check(temp.renameTo(file)) { "could not store ${file.name}" }
    }

    private companion object {
        const val OPENED_HEADER = "openedHeader"
        const val DEVICE_UNLOCK_STALE = "deviceUnlockStale"
        const val AUTO_LOCK = "autoLockMinutes"
        const val BIOMETRIC_OFFERED = "biometricOffered"
        const val SAVED_MESSAGES_NOTICE = "savedMessagesNotice"
        const val BLOCK_SCREENSHOTS = "blockScreenshots"
        const val WEEKLY_CHECK = "weeklyCheck"
        const val HIDE_SIZES = "hideSizes"
        const val DEBUG_TOOLS = "debugTools"
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
    }
}
