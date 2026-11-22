package me.esdex.coffer.core.index

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.room.migration.AutoMigrationSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.storage.MessageId

// Ids are stored as lowercase hex: readable in a debugger, and the same everywhere.
private fun Id.hex(): String = toByteArray().joinToString("") { "%02x".format(it) }
private fun idOf(hex: String): Id = Id.of(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

@Entity(tableName = "files")
internal class FileRow(
    @PrimaryKey val fileId: String,
    val plainSize: Long,
    val partCount: Int,
    val sealedMeta: ByteArray,
    val status: String,
    // Rows from before format v2 (SPEC §4.5) are files that were not padded.
    @ColumnInfo(defaultValue = "0") val padded: Boolean,
)

@Entity(tableName = "parts", primaryKeys = ["fileId", "partIndex"], indices = [Index("messageId", unique = true)])
internal class PartRow(val fileId: String, val partIndex: Int, val messageId: Long)

@Entity(tableName = "deleted_files")
internal class DeletedRow(@PrimaryKey val fileId: String)

@Entity(tableName = "folders")
internal class FolderRow(@PrimaryKey val folderId: String, val sealedMeta: ByteArray)

@Entity(tableName = "deleted_folders")
internal class DeletedFolderRow(@PrimaryKey val folderId: String)

@Entity(tableName = "snapshots")
internal class SnapshotRow(@PrimaryKey val messageId: Long, val lastApplied: Long)

@Entity(tableName = "sync_state")
internal class SyncRow(@PrimaryKey val key: String, val value: Long)

@Entity(tableName = "uploads")
internal class UploadRow(
    @PrimaryKey val uploadId: String,
    val sealedRequest: ByteArray,
    val fileId: String?,
    /** Newline-separated; captions are base64url and never contain one. */
    val captions: String,
    val abandonOp: String?,
    // Schema 10: a name taken, and the user said replace (2026-11-19).
    val replaces: String? = null,
)

/** Schema 10 kept a ready delete per replaced file; they are packed together once the key is there instead. */
@DeleteColumn(tableName = "uploads", columnName = "replaceOp")
internal class DropReplaceOp : AutoMigrationSpec

/**
 * Operations already applied, so one sent again under a new message id is not applied twice (audit 1, M4),
 * with the message each came in first, so a rescan applies it again from there only (audit 2, N2).
 */
@Entity(tableName = "applied_ops")
internal class AppliedOpRow(@PrimaryKey val opId: String, @ColumnInfo(defaultValue = "0") val messageId: Long)

/** Operations waiting to go to Telegram, in the order they were made (see [PendingOp]). */
@Entity(tableName = "pending_ops")
internal class PendingOpRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val batch: String,
    val batchSize: Int,
    val kind: String,
    val caption: String,
    // Version 3 packs several in one message (2026-11-19); before, each was alone.
    @ColumnInfo(defaultValue = "1") val opCount: Int = 1,
    // Deletes, queued since schema 9: ids comma-separated, empty for none.
    @ColumnInfo(defaultValue = "") val deletedFiles: String = "",
    @ColumnInfo(defaultValue = "") val deletedFolders: String = "",
    @ColumnInfo(defaultValue = "") val parts: String = "",
    @ColumnInfo(defaultValue = "0") val sent: Boolean = false,
)

@Entity(tableName = "downloads")
internal class DownloadRow(@PrimaryKey val downloadId: String, val fileId: String, val sealedTarget: ByteArray?)

@Dao
internal interface IndexDao {
    @Query("SELECT * FROM files") fun observeFiles(): Flow<List<FileRow>>
    @Query("SELECT * FROM files") suspend fun files(): List<FileRow>
    @Query("SELECT * FROM files WHERE fileId = :fileId") suspend fun file(fileId: String): FileRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFile(row: FileRow)
    @Query("UPDATE files SET status = :status WHERE fileId = :fileId") suspend fun setStatus(fileId: String, status: String)
    @Query("UPDATE files SET plainSize = :plainSize, partCount = :partCount, sealedMeta = :sealedMeta, padded = :padded WHERE fileId = :fileId")
    suspend fun updateMeta(fileId: String, plainSize: Long, partCount: Int, sealedMeta: ByteArray, padded: Boolean): Int
    @Query("DELETE FROM files WHERE fileId = :fileId") suspend fun deleteFile(fileId: String)

    @Query("SELECT * FROM parts") fun observeParts(): Flow<List<PartRow>>
    @Query("SELECT * FROM parts") suspend fun allParts(): List<PartRow>
    @Query("SELECT * FROM parts WHERE fileId = :fileId") suspend fun parts(fileId: String): List<PartRow>
    @Query("SELECT * FROM parts WHERE messageId = :messageId") suspend fun partOf(messageId: Long): PartRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPart(row: PartRow)
    @Query("DELETE FROM parts WHERE messageId = :messageId") suspend fun deletePart(messageId: Long)
    @Query("DELETE FROM parts WHERE fileId = :fileId") suspend fun deleteParts(fileId: String)

    @Query("SELECT COUNT(*) FROM deleted_files WHERE fileId = :fileId") suspend fun deletedCount(fileId: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markDeleted(row: DeletedRow)
    @Query("SELECT * FROM deleted_files") suspend fun deletedFiles(): List<DeletedRow>
    @Query("SELECT * FROM deleted_folders") suspend fun deletedFolders(): List<DeletedFolderRow>

    @Query("SELECT * FROM snapshots") suspend fun snapshots(): List<SnapshotRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSnapshot(row: SnapshotRow)
    @Query("DELETE FROM snapshots WHERE messageId = :messageId") suspend fun deleteSnapshot(messageId: Long): Int

    // Bulk writes for replaceContent.
    @Query("DELETE FROM files") suspend fun deleteAllFiles()
    @Query("DELETE FROM parts") suspend fun deleteAllParts()
    @Query("DELETE FROM deleted_files") suspend fun deleteAllDeletedFiles()
    @Query("DELETE FROM folders") suspend fun deleteAllFolders()
    @Query("DELETE FROM deleted_folders") suspend fun deleteAllDeletedFolders()
    @Query("DELETE FROM snapshots") suspend fun deleteAllSnapshots()
    @Query("DELETE FROM sync_state WHERE `key` = :key") suspend fun deleteSync(key: String)
    @Insert suspend fun insertFiles(rows: List<FileRow>)
    @Insert suspend fun insertParts(rows: List<PartRow>)
    @Insert suspend fun insertDeletedFiles(rows: List<DeletedRow>)
    @Insert suspend fun insertFolders(rows: List<FolderRow>)
    @Insert suspend fun insertDeletedFolders(rows: List<DeletedFolderRow>)
    @Insert suspend fun insertSnapshots(rows: List<SnapshotRow>)

    @Query("SELECT * FROM folders") fun observeFolders(): Flow<List<FolderRow>>
    @Query("SELECT * FROM folders") suspend fun folders(): List<FolderRow>
    @Query("SELECT * FROM folders WHERE folderId = :folderId") suspend fun folder(folderId: String): FolderRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFolder(row: FolderRow)
    @Query("DELETE FROM folders WHERE folderId = :folderId") suspend fun deleteFolder(folderId: String)
    @Query("SELECT COUNT(*) FROM deleted_folders WHERE folderId = :folderId") suspend fun deletedFolderCount(folderId: String): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markFolderDeleted(row: DeletedFolderRow)

    @Query("SELECT value FROM sync_state WHERE `key` = :key") suspend fun syncValue(key: String): Long?
    @Query("SELECT * FROM sync_state") suspend fun syncRows(): List<SyncRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSync(row: SyncRow)

    @Query("SELECT * FROM pending_ops ORDER BY seq") fun observeOps(): Flow<List<PendingOpRow>>
    @Query("SELECT * FROM pending_ops ORDER BY seq") suspend fun ops(): List<PendingOpRow>
    @Insert suspend fun insertOps(rows: List<PendingOpRow>)
    @Query("UPDATE pending_ops SET sent = 1 WHERE seq = :seq") suspend fun markOpSent(seq: Long)
    @Query("DELETE FROM pending_ops WHERE seq = :seq") suspend fun deleteOp(seq: Long)

    @Query("SELECT * FROM uploads") fun observeUploads(): Flow<List<UploadRow>>
    @Query("SELECT * FROM uploads") suspend fun uploads(): List<UploadRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putUpload(row: UploadRow)
    @Query("DELETE FROM uploads WHERE uploadId = :uploadId") suspend fun deleteUpload(uploadId: String)

    @Query("SELECT messageId FROM applied_ops WHERE opId = :opId") suspend fun opAppliedFrom(opId: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun markOpApplied(row: AppliedOpRow)
    @Query("SELECT * FROM applied_ops") suspend fun appliedOps(): List<AppliedOpRow>

    @Query("SELECT * FROM downloads") fun observeDownloads(): Flow<List<DownloadRow>>
    @Query("SELECT * FROM downloads") suspend fun downloads(): List<DownloadRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDownload(row: DownloadRow)
    @Query("DELETE FROM downloads WHERE downloadId = :downloadId") suspend fun deleteDownload(downloadId: String)
}

@Database(
    entities = [
        FileRow::class, PartRow::class, DeletedRow::class, FolderRow::class, DeletedFolderRow::class,
        SnapshotRow::class, SyncRow::class, UploadRow::class, DownloadRow::class, AppliedOpRow::class,
        PendingOpRow::class,
    ],
    version = 11,
    // Only new tables and columns so far: the queue of transfers survives the update.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11, spec = DropReplaceOp::class),
    ],
)
/** Public for the platforms to open it, each with its own SQLCipher: :core:device on Android, :desktop. */
abstract class IndexDatabase : RoomDatabase() {
    internal abstract fun dao(): IndexDao
}

/**
 * How the platform runs a transaction: Android keeps room-ktx's withTransaction, which the app has always
 * used; it is not in Room for the JVM.
 */
interface IndexPlatform {
    suspend fun <R> transaction(block: suspend () -> R): R
}

/** Every table of [IndexDatabase], for [RoomVaultIndex.clear]; IndexTablesTest checks it against the schema. */
internal val INDEX_TABLES = listOf(
    "files", "parts", "deleted_files", "folders", "deleted_folders", "snapshots", "sync_state", "uploads",
    "downloads", "applied_ops", "pending_ops",
)

/**
 * The app's [VaultIndex], in Room. Each platform opens [db] encrypted with SQLCipher (audit 1, M9): file
 * sizes, part counts, access times and the transfer queue are not readable from a copy of the device's
 * storage. On Android see androidVaultIndex in :core:device.
 */
class RoomVaultIndex(private val db: IndexDatabase, private val platform: IndexPlatform) : VaultIndex {
    private val dao = db.dao()

    private suspend fun <R> withTransactionCompat(block: suspend () -> R): R = platform.transaction(block)


    private fun FileRow.indexed(parts: List<PartRow>) = IndexedFile(
        fileId = idOf(fileId),
        plainSize = plainSize,
        partCount = partCount,
        sealedMeta = sealedMeta,
        status = FileStatus.valueOf(status),
        parts = parts.associate { it.partIndex to MessageId(it.messageId) },
        padded = padded,
    )

    private fun join(files: List<FileRow>, parts: List<PartRow>): List<IndexedFile> {
        val byFile = parts.groupBy { it.fileId }
        return files.map { it.indexed(byFile[it.fileId].orEmpty()) }
    }

    override fun observeFiles(): Flow<List<IndexedFile>> = combine(dao.observeFiles(), dao.observeParts(), ::join)

    override suspend fun files(): List<IndexedFile> = join(dao.files(), dao.allParts())

    override suspend fun file(fileId: Id): IndexedFile? = dao.file(fileId.hex())?.indexed(dao.parts(fileId.hex()))

    override suspend fun putFile(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray, status: FileStatus, padded: Boolean) =
        dao.putFile(FileRow(fileId.hex(), plainSize, partCount, sealedMeta, status.name, padded))

    override suspend fun updateFileMeta(fileId: Id, plainSize: Long, partCount: Int, sealedMeta: ByteArray, padded: Boolean): Boolean =
        dao.updateMeta(fileId.hex(), plainSize, partCount, sealedMeta, padded) > 0

    override suspend fun setStatus(fileId: Id, status: FileStatus) = dao.setStatus(fileId.hex(), status.name)

    override suspend fun removeFile(fileId: Id) = withTransactionCompat {
        dao.deleteFile(fileId.hex())
        dao.deleteParts(fileId.hex())
    }

    override suspend fun putPart(fileId: Id, partIndex: Int, messageId: MessageId) =
        dao.putPart(PartRow(fileId.hex(), partIndex, messageId.value))

    override suspend fun parts(fileId: Id): Map<Int, MessageId> =
        dao.parts(fileId.hex()).associate { it.partIndex to MessageId(it.messageId) }

    override suspend fun removePart(messageId: MessageId): Pair<Id, Int>? = withTransactionCompat {
        val part = dao.partOf(messageId.value) ?: return@withTransactionCompat null
        dao.deletePart(messageId.value)
        idOf(part.fileId) to part.partIndex
    }

    override suspend fun isDeleted(fileId: Id): Boolean = dao.deletedCount(fileId.hex()) > 0
    override suspend fun markDeleted(fileId: Id) = dao.markDeleted(DeletedRow(fileId.hex()))

    private fun FolderRow.indexed() = IndexedFolder(idOf(folderId), sealedMeta)

    override fun observeFolders(): Flow<List<IndexedFolder>> = dao.observeFolders().map { rows -> rows.map { it.indexed() } }
    override suspend fun folders(): List<IndexedFolder> = dao.folders().map { it.indexed() }
    override suspend fun folder(folderId: Id): IndexedFolder? = dao.folder(folderId.hex())?.indexed()
    override suspend fun putFolder(folderId: Id, sealedMeta: ByteArray) = dao.putFolder(FolderRow(folderId.hex(), sealedMeta))
    override suspend fun removeFolder(folderId: Id) = dao.deleteFolder(folderId.hex())
    override suspend fun isFolderDeleted(folderId: Id): Boolean = dao.deletedFolderCount(folderId.hex()) > 0
    override suspend fun markFolderDeleted(folderId: Id) = dao.markFolderDeleted(DeletedFolderRow(folderId.hex()))

    override suspend fun lastApplied(): MessageId? = dao.syncValue(LAST_APPLIED)?.let(::MessageId)
    override suspend fun setLastApplied(messageId: MessageId) = dao.putSync(SyncRow(LAST_APPLIED, messageId.value))

    private fun UploadRow.pending() = PendingUpload(
        uploadId, sealedRequest, fileId?.let(::idOf), captions.split('\n').filter { it.isNotEmpty() }, abandonOp,
        replaces?.let(::idOf),
    )

    override suspend fun allParts(): Map<Id, Map<Int, MessageId>> =
        dao.allParts().groupBy { idOf(it.fileId) }.mapValues { (_, rows) -> rows.associate { it.partIndex to MessageId(it.messageId) } }
    override suspend fun deletedFiles(): Set<Id> = dao.deletedFiles().mapTo(mutableSetOf()) { idOf(it.fileId) }
    override suspend fun deletedFolders(): Set<Id> = dao.deletedFolders().mapTo(mutableSetOf()) { idOf(it.folderId) }

    override suspend fun contentVersion(): MessageId? = dao.syncValue(CONTENT_VERSION)?.let(::MessageId)
    override suspend fun setContentVersion(messageId: MessageId) = dao.putSync(SyncRow(CONTENT_VERSION, messageId.value))

    override suspend fun snapshots(): List<KnownSnapshot> =
        dao.snapshots().map { KnownSnapshot(MessageId(it.messageId), MessageId(it.lastApplied)) }
    override suspend fun putSnapshot(snapshot: KnownSnapshot) =
        dao.putSnapshot(SnapshotRow(snapshot.messageId.value, snapshot.lastApplied.value))
    override suspend fun removeSnapshot(messageId: MessageId): Boolean = dao.deleteSnapshot(messageId.value) > 0

    override suspend fun maintainedAt(): Long? = dao.syncValue(MAINTAINED_AT)
    override suspend fun setMaintainedAt(millis: Long) = dao.putSync(SyncRow(MAINTAINED_AT, millis))

    override suspend fun opAppliedFrom(opId: Id): MessageId? = dao.opAppliedFrom(opId.hex())?.let(::MessageId)
    override suspend fun markOpApplied(opId: Id, messageId: MessageId) = dao.markOpApplied(AppliedOpRow(opId.hex(), messageId.value))

    override suspend fun oldestRecord(): MessageId? = dao.syncValue(OLDEST_RECORD)?.let(::MessageId)
    override suspend fun setOldestRecord(messageId: MessageId) = dao.putSync(SyncRow(OLDEST_RECORD, messageId.value))

    override suspend fun replaceContent(content: IndexContent): Unit = withTransactionCompat {
        dao.deleteAllFiles()
        dao.deleteAllParts()
        dao.deleteAllDeletedFiles()
        dao.deleteAllFolders()
        dao.deleteAllDeletedFolders()
        dao.deleteAllSnapshots()
        dao.deleteSync(LAST_APPLIED)
        dao.deleteSync(CONTENT_VERSION)
        dao.insertFiles(content.files.map { FileRow(it.fileId.hex(), it.plainSize, it.partCount, it.sealedMeta, it.status.name, it.padded) })
        val parts = content.files.associate { it.fileId to it.parts } + content.orphanParts
        dao.insertParts(parts.flatMap { (id, byIndex) -> byIndex.map { (i, m) -> PartRow(id.hex(), i, m.value) } })
        dao.insertDeletedFiles(content.deletedFiles.map { DeletedRow(it.hex()) })
        dao.insertFolders(content.folders.map { FolderRow(it.folderId.hex(), it.sealedMeta) })
        dao.insertDeletedFolders(content.deletedFolders.map { DeletedFolderRow(it.hex()) })
        dao.insertSnapshots(content.snapshots.map { SnapshotRow(it.messageId.value, it.lastApplied.value) })
        content.lastApplied?.let { dao.putSync(SyncRow(LAST_APPLIED, it.value)) }
        content.contentVersion?.let { dao.putSync(SyncRow(CONTENT_VERSION, it.value)) }
        Unit
    }

    private fun PendingOpRow.pending() = PendingOp(
        seq, batch, batchSize, kind, caption, opCount,
        deletedFiles = ids(deletedFiles), deletedFolders = ids(deletedFolders),
        parts = parts.split(',').filter { it.isNotEmpty() }.map { MessageId(it.toLong()) }, sent = sent,
    )

    private fun ids(joined: String): List<Id> = joined.split(',').filter { it.isNotEmpty() }.map(::idOf)

    override fun observeOps(): Flow<List<PendingOp>> = dao.observeOps().map { rows -> rows.map { it.pending() } }
    override suspend fun ops(): List<PendingOp> = dao.ops().map { it.pending() }
    override suspend fun putOps(batch: String, kind: String, messages: List<OpMessage>) {
        val size = messages.sumOf { it.opCount }
        dao.insertOps(
            messages.map {
                PendingOpRow(
                    batch = batch, batchSize = size, kind = kind, caption = it.caption, opCount = it.opCount,
                    deletedFiles = it.deletedFiles.joinToString(",") { id -> id.hex() },
                    deletedFolders = it.deletedFolders.joinToString(",") { id -> id.hex() },
                    parts = it.parts.joinToString(",") { id -> id.value.toString() },
                )
            },
        )
    }
    override suspend fun markOpSent(seq: Long) = dao.markOpSent(seq)
    override suspend fun removeOp(seq: Long) = dao.deleteOp(seq)

    override fun observeUploads(): Flow<List<PendingUpload>> = dao.observeUploads().map { rows -> rows.map { it.pending() } }
    override suspend fun uploads(): List<PendingUpload> = dao.uploads().map { it.pending() }
    override suspend fun putUpload(upload: PendingUpload) = dao.putUpload(
        UploadRow(
            upload.uploadId, upload.sealedRequest, upload.fileId?.hex(), upload.captions.joinToString("\n"), upload.abandonOp,
            upload.replaces?.hex(),
        ),
    )
    override suspend fun removeUpload(uploadId: String) = dao.deleteUpload(uploadId)

    private fun DownloadRow.pending() = PendingDownload(downloadId, idOf(fileId), sealedTarget)

    override fun observeDownloads(): Flow<List<PendingDownload>> = dao.observeDownloads().map { rows -> rows.map { it.pending() } }
    override suspend fun downloads(): List<PendingDownload> = dao.downloads().map { it.pending() }
    override suspend fun putDownload(download: PendingDownload) =
        dao.putDownload(DownloadRow(download.downloadId, download.fileId.hex(), download.sealedTarget))
    override suspend fun removeDownload(downloadId: String) = dao.deleteDownload(downloadId)

    /**
     * What Room's clearAllTables does on Android (2.8.5, performClear), which Room for the JVM does not have:
     * every table emptied in one transaction, then the log folded back and the file compacted, so deleted
     * rows do not linger in free pages. Observers hear of it from the transaction itself (MigrationTest).
     */
    override suspend fun clear() = db.useWriterConnection { connection ->
        connection.immediateTransaction { INDEX_TABLES.forEach { execSQL("DELETE FROM `$it`") } }
        connection.execSQL("PRAGMA wal_checkpoint(FULL)")
        connection.execSQL("VACUUM")
    }

    companion object {
        /** Every row of [from] into [to], which is empty: how an index changes hands, as from plain to encrypted. */
        suspend fun copy(from: IndexDatabase, to: IndexDatabase) = copy(from.dao(), to.dao())

        private suspend fun copy(from: IndexDao, to: IndexDao) {
            to.insertFiles(from.files())
            to.insertParts(from.allParts())
            to.insertDeletedFiles(from.deletedFiles())
            to.insertFolders(from.folders())
            to.insertDeletedFolders(from.deletedFolders())
            to.insertSnapshots(from.snapshots())
            from.syncRows().forEach { to.putSync(it) }
            from.uploads().forEach { to.putUpload(it) }
            to.insertOps(from.ops())
            from.downloads().forEach { to.putDownload(it) }
            from.appliedOps().forEach { to.markOpApplied(it) }
        }

        private const val LAST_APPLIED = "lastAppliedMessageId"
        private const val CONTENT_VERSION = "contentVersion"
        private const val MAINTAINED_AT = "maintainedAt"
        private const val OLDEST_RECORD = "oldestRecord"
    }
}
