package me.esdex.coffer.core.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Properties

/**
 * The file cache's settings: the encrypted pieces of files already read are kept on the phone up to
 * [limitMb], so opening them again costs neither time nor traffic. On by default; the cache is
 * emptied on lock only when asked for. [previews] puts a small picture of each photo, video and song
 * cover on its icon, kept on the phone like the cache.
 */
data class CachePrefs(
    val enabled: Boolean = true,
    val limitMb: Int = DEFAULT_LIMIT_MB,
    val clearOnLock: Boolean = false,
    val previews: Boolean = true,
) {
    val limitBytes: Long get() = limitMb.toLong() * 1024 * 1024

    companion object {
        /** 500 MB, then 1, 2, 5 and 10 GB, as agreed with Esdex. */
        val LIMITS_MB = listOf(500, 1024, 2048, 5120, 10240)
        const val DEFAULT_LIMIT_MB = 2048

        /** A limit as the settings offer it: 500 MB, 1 GB, and so on. */
        fun limitLabel(mb: Int): String = limitParts(mb).let { (number, unit) -> "$number $unit" }

        /** The same split in two, for a button too narrow for both on one line: "500" over "MB". */
        fun limitParts(mb: Int): Pair<String, String> = if (mb < 1024) "$mb" to "MB" else "${mb / 1024}" to "GB"
    }
}

/** Where [CachePrefs] is kept: a file of its own beside the players', kept over a sign-out. */
class CacheStore(private val file: File) {
    private val state = MutableStateFlow(read())
    val prefs: StateFlow<CachePrefs> = state.asStateFlow()

    @Synchronized
    fun update(change: (CachePrefs) -> CachePrefs) {
        val next = change(state.value).let { if (it.limitMb in CachePrefs.LIMITS_MB) it else it.copy(limitMb = CachePrefs.DEFAULT_LIMIT_MB) }
        val props = Properties()
        props.setProperty(ENABLED, next.enabled.toString())
        props.setProperty(LIMIT_MB, next.limitMb.toString())
        props.setProperty(CLEAR_ON_LOCK, next.clearOnLock.toString())
        props.setProperty(PREVIEWS, next.previews.toString())
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.outputStream().use { props.store(it, null) }
        check(temp.renameTo(file)) { "could not store ${file.name}" }
        state.value = next
    }

    private fun read(): CachePrefs {
        if (!file.exists()) return CachePrefs()
        val props = Properties().apply { file.inputStream().use(::load) }
        val d = CachePrefs()
        return CachePrefs(
            enabled = props.getProperty(ENABLED)?.toBooleanStrictOrNull() ?: d.enabled,
            limitMb = props.getProperty(LIMIT_MB)?.toIntOrNull()?.takeIf { it in CachePrefs.LIMITS_MB } ?: d.limitMb,
            clearOnLock = props.getProperty(CLEAR_ON_LOCK)?.toBooleanStrictOrNull() ?: d.clearOnLock,
            previews = props.getProperty(PREVIEWS)?.toBooleanStrictOrNull() ?: d.previews,
        )
    }

    private companion object {
        const val ENABLED = "enabled"
        const val LIMIT_MB = "limitMb"
        const val CLEAR_ON_LOCK = "clearOnLock"
        const val PREVIEWS = "previews"
    }
}
