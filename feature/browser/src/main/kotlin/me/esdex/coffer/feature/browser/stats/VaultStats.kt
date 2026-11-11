package me.esdex.coffer.feature.browser.stats

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.sync.Census
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.core.sync.census
import me.esdex.coffer.core.tdlib.Telegram
import me.esdex.coffer.feature.browser.transfer.OpenFiles
import me.esdex.coffer.feature.browser.transfer.ShareInbox
import me.esdex.coffer.feature.browser.transfer.VaultContent
import me.esdex.coffer.feature.browser.transfer.WorkFiles
import me.esdex.coffer.feature.browser.transfer.readyStorage
import java.io.File
import me.esdex.coffer.feature.browser.preview.Previews
import javax.inject.Inject

/**
 * The numbers behind Settings, Storage and On this phone (pre-release item 6). The vault's are worked
 * out from the local index as it changes; Saved Messages is counted on request, as it means reading
 * its whole history; the phone's are measured when asked.
 */
class VaultStats @Inject constructor(
    index: VaultIndex,
    content: VaultContent,
    private val telegram: Telegram,
    @ApplicationContext private val context: Context,
) {
    private class Contents(val files: List<StatsFile>, val folders: Int, val paths: Map<StatsFile, String>)

    private val contents: Flow<Contents?> = combine(index.observeFiles(), index.observeFolders(), content.keys) { files, folders, keys ->
        if (keys == null) return@combine null
        val metas = files.mapNotNull { file -> keys.local.openFileMeta(file.fileId, file.sealedMeta)?.let { file to it } }
        val tree = VaultTree(
            folders = folders.mapNotNull { f -> keys.local.openFolder(f.folderId, f.sealedMeta)?.let { f.folderId to it } }.toMap(),
            files = metas.associate { (file, meta) -> file.fileId to meta },
        )
        val stats = metas.map { (file, meta) -> StatsFile(file.fileId, meta.name, meta.mime, file.plainSize, tree.folderOf(file.fileId)) }
        val paths = stats.associateWith { s -> s.folderId?.let { id -> tree.path(id).mapNotNull { tree.folders[it]?.name }.joinToString(" / ") }.orEmpty() }
        Contents(stats, tree.folders.size, paths)
    }.flowOn(Dispatchers.Default)

    /** The vault by kind; null while locked. */
    val breakdown: Flow<Breakdown?> = contents.map { it?.let { c -> Breakdown.of(c.files, c.folders) } }

    /** The files of [kind], largest first, each with the folders it is in from the root down (empty in the root). */
    fun filesOf(kind: FileKind): Flow<List<Pair<StatsFile, String>>?> = contents.map { c ->
        c?.files?.filter { it.kind == kind }?.sortedByDescending { it.size }?.map { it to c.paths.getValue(it) }
    }

    /** Saved Messages counted by kind of message; null when Telegram cannot be reached. Nothing is changed. */
    suspend fun census(onProgress: (Int) -> Unit = {}): Census? {
        val storage = telegram.readyStorage() ?: return null
        return storage.census(onProgress)
    }

    /** What is on the phone now: TDLib's files and databases, Coffer's index, the temporary files. */
    suspend fun phone(): PhoneUsage = withContext(Dispatchers.IO) {
        val telegramUsage = runCatching { telegram.usage() }.getOrNull()
        val db = context.getDatabasePath(INDEX_DB)
        PhoneUsage(
            telegramFiles = telegramUsage?.filesBytes ?: 0L,
            telegramDatabase = telegramUsage?.databaseBytes ?: 0L,
            index = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { it.length() },
            temporary = listOf(OpenFiles.dir(context), WorkFiles.upload(context), WorkFiles.snapshot(context), ShareInbox.dir(context)).sumOf(::sizeOf),
            previews = sizeOf(Previews.dir(context)),
        )
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val INDEX_DB = "index.db"
    }
}

/**
 * What Coffer takes up on the phone, by where it comes from. [telegramFiles] is what TDLib has downloaded
 * and not yet let go of; [temporary] the decrypted copies opened in other apps and the files on their way
 * to Telegram; [previews] the small pictures on the file icons. None of it is the vault itself, which is
 * in Saved Messages.
 */
data class PhoneUsage(val telegramFiles: Long, val telegramDatabase: Long, val index: Long, val temporary: Long, val previews: Long = 0) {
    val total: Long get() = telegramFiles + telegramDatabase + index + temporary + previews
}
