package me.esdex.coffer.feature.browser.transfer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.core.os.bundleOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.MimeTypes
import me.esdex.coffer.core.index.FileStatus
import me.esdex.coffer.core.index.VaultIndex
import me.esdex.coffer.core.sync.NameConflicts
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultTree
import me.esdex.coffer.feature.browser.R
import me.esdex.coffer.feature.browser.transfer.PickerDocuments.Content
import me.esdex.coffer.feature.browser.transfer.PickerDocuments.Doc
import me.esdex.coffer.feature.browser.viewer.copyWhole
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coffer in the system file picker (decision 2026-11-20), so another app can open a vault file and save
 * it back, as KeePassDX does with its database. Off by default: the setting enables this component (see
 * [show]). Only the system's picker lists the vault (MANAGE_DOCUMENTS); an app gets the one file picked,
 * and no folder grants, as no folder can be picked whole.
 *
 * Nothing while locked: the root stays, empty, saying so. A file is read in place, decrypted as it is read
 * (see [VaultFileReader][me.esdex.coffer.core.sync.VaultFileReader]), and the read is cut off when the
 * vault locks. A file written goes in through an edit session, as one edited in another app does, and
 * in place of the file (see [EditSessions]); a new one through a session of its own, under its name, or
 * numbered if the name is taken. Its copy stays decrypted on the phone until it is sent and for an hour
 * after the last save, also while locked.
 */
class VaultDocumentsProvider : DocumentsProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Parts {
        fun content(): VaultContent
        fun index(): VaultIndex
    }

    private val parts by lazy { EntryPointAccessors.fromApplication(checkNotNull(context).applicationContext, Parts::class.java) }

    private val sessionsDir get() = EditSessions.dir(checkNotNull(context))

    // Reads open in other apps, cut off when the vault locks.
    private val reads = ConcurrentHashMap.newKeySet<VaultContent.OpenedFile>()
    private val watchingLock = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(): Boolean = true

    private fun keys(): VaultKeys? = parts.content().keys.value

    /** The vault as a call sees it: read once, for one answer. */
    private class View(
        val tree: VaultTree,
        val ready: Set<Id>,
        val sizes: Map<Id, Long>,
        val sessions: List<EditSession>,
        val pending: Set<String>,
        val made: List<EditSession>,
        val forwards: DocumentForwards,
    )

    private fun view(keys: VaultKeys): View = runBlocking {
        val index = parts.index()
        val gone = parts.content().deleting.value
        val files = index.files()
        val sessions = EditSessions.all(sessionsDir)
        View(
            tree = VaultTree.of(index, keys),
            ready = files.filter { it.status == FileStatus.READY && it.fileId !in gone }.mapTo(HashSet()) { it.fileId },
            sizes = files.associate { it.fileId to it.plainSize },
            sessions = sessions,
            pending = index.uploads().mapTo(HashSet()) { it.uploadId },
            made = sessions.filter { it.original == null },
            forwards = DocumentForwards.read(EditSessions.forwardsFile(sessionsDir)),
        )
    }

    /** Where [doc] went; a chain followed to a file further on is settled there (see [DocumentForwards]). */
    private fun chainOf(doc: Doc, view: View): List<Id> {
        val key = PickerDocuments.forwardKey(doc) ?: return emptyList()
        val chain = view.forwards.chain(key)
        val end = chain.lastOrNull { it in view.ready }
        if (end != null && chain.first() != end) {
            val steps = chain.takeWhile { it != end }.map { EditSession.hex(it.toByteArray()) }
            DocumentForwards.settle(EditSessions.forwardsFile(sessionsDir), (listOf(key) + steps).distinct(), end, System.currentTimeMillis())
        }
        return chain
    }

    private fun folderOf(doc: Doc?, view: View): Id? = when (doc) {
        Doc.Root -> null
        is Doc.Folder -> doc.id.takeIf { it in view.tree.folders } ?: throw FileNotFoundException()
        else -> throw FileNotFoundException()
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = checkNotNull(context)
        val locked = keys() == null
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            setNotificationUri(context.contentResolver, DocumentsContract.buildRootsUri(authority(context)))
            newRow()
                .add(Root.COLUMN_ROOT_ID, PickerDocuments.ROOT)
                .add(Root.COLUMN_DOCUMENT_ID, PickerDocuments.ROOT)
                .add(Root.COLUMN_TITLE, title(context))
                .add(Root.COLUMN_SUMMARY, if (locked) context.getString(R.string.picker_locked_summary) else null)
                .add(Root.COLUMN_ICON, context.applicationInfo.icon)
                .add(Root.COLUMN_FLAGS, if (locked) 0 else Root.FLAG_SUPPORTS_CREATE)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val context = checkNotNull(context)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        val doc = PickerDocuments.parse(documentId) ?: throw FileNotFoundException()
        val keys = keys()
        if (doc == Doc.Root) {
            cursor.folderRow(documentId, title(context), writable = keys != null)
            return cursor
        }
        val view = view(keys ?: throw FileNotFoundException())
        when (doc) {
            is Doc.Folder -> cursor.folderRow(documentId, view.tree.folders[doc.id]?.name ?: throw FileNotFoundException(), writable = true)
            else -> cursor.fileRow(documentId, doc, view) ?: throw FileNotFoundException()
        }
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val context = checkNotNull(context)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildChildDocumentsUri(authority(context), parentDocumentId))
        val keys = keys()
        if (keys == null) {
            cursor.extras = bundleOf(DocumentsContract.EXTRA_INFO to context.getString(R.string.picker_locked_info))
            return cursor
        }
        val view = view(keys)
        val folder = folderOf(PickerDocuments.parse(parentDocumentId), view)
        for (child in view.tree.childFolders(folder)) {
            cursor.folderRow(PickerDocuments.id(Doc.Folder(child)), view.tree.folders.getValue(child).name, writable = true)
        }
        for (file in view.tree.childFiles(folder)) {
            if (file !in view.ready) continue
            val doc = Doc.File(file)
            cursor.fileRow(PickerDocuments.id(doc), doc, view)
        }
        // Made here and not in the vault yet: once it is, it shows as the file.
        for (session in view.made) {
            if (session.folder?.takeIf { it in view.tree.folders } != folder) continue
            val doc = Doc.Made(session.id)
            if (PickerDocuments.content(doc, chainOf(doc, view), view.sessions, view.ready::contains, view.pending) is Content.Copy) {
                cursor.fileRow(PickerDocuments.id(doc), doc, view)
            }
        }
        return cursor
    }

    private fun MatrixCursor.folderRow(id: String, name: String, writable: Boolean) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_FLAGS, if (writable) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
    }

    /** A row for [doc], as it is now; null if it is gone. */
    private fun MatrixCursor.fileRow(id: String, doc: Doc, view: View): Unit? {
        val row = when (val now = PickerDocuments.content(doc, chainOf(doc, view), view.sessions, view.ready::contains, view.pending) ?: return null) {
            is Content.Copy -> {
                val copy = now.session.copy
                val shown = listOfNotNull(now.session.uploadFile, now.session.original).firstNotNullOfOrNull { view.tree.files[it]?.name }
                FileRow(shown ?: copy.name, now.session.mime, copy.length(), copy.lastModified())
            }
            is Content.Stored -> {
                val meta = view.tree.files[now.fileId] ?: return null
                FileRow(meta.name, meta.mime, view.sizes[now.fileId] ?: 0L, meta.modifiedAt)
            }
        }
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_MIME_TYPE, row.mime)
            .add(Document.COLUMN_DISPLAY_NAME, row.name)
            .add(Document.COLUMN_SIZE, row.size)
            .add(Document.COLUMN_LAST_MODIFIED, row.modified)
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE)
        return Unit
    }

    private class FileRow(val name: String, val mime: String, val size: Long, val modified: Long)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val keys = keys() ?: throw FileNotFoundException("Coffer is locked")
        val doc = PickerDocuments.parse(documentId)?.takeIf { it is Doc.File || it is Doc.Made } ?: throw FileNotFoundException()
        val access = ParcelFileDescriptor.parseMode(mode)
        val view = view(keys)
        val chain = chainOf(doc, view)
        if (access == ParcelFileDescriptor.MODE_READ_ONLY) {
            return when (val now = PickerDocuments.content(doc, chain, view.sessions, view.ready::contains, view.pending)) {
                is Content.Copy -> ParcelFileDescriptor.open(now.session.copy, ParcelFileDescriptor.MODE_READ_ONLY)
                is Content.Stored -> stream(now.fileId)
                null -> throw FileNotFoundException()
            }
        }
        val current = chain.lastOrNull { it in view.ready }
        val session = PickerDocuments.writable(doc, chain, view.sessions, current)
            ?: begin(current ?: throw FileNotFoundException(), view, fill = access and ParcelFileDescriptor.MODE_TRUNCATE == 0, signal)
        val root = sessionsDir
        EditSessions.opened(root, session.id, Process.myPid(), System.currentTimeMillis())
        // Closed after writing: saved, as from an editor (see EditProvider). Whether anything changed is for the hash to tell.
        return ParcelFileDescriptor.open(session.copy, access, Handler(Looper.getMainLooper())) { error ->
            // Closed with an error, as by an app that crashed mid-write: no save (audit 3, Q3-2).
            if (error == null) EditSessions.written(root, session.id, System.currentTimeMillis()) else EditSessions.failed(root, session.id, System.currentTimeMillis())
        }
    }

    /** A session for writing to [fileId]; with [fill], holding the file as it is, for a write that keeps it. */
    private fun begin(fileId: Id, view: View, fill: Boolean, signal: CancellationSignal?): EditSession {
        val meta = view.tree.files[fileId] ?: throw FileNotFoundException()
        val root = sessionsDir
        val session = EditSessions.begin(root, meta.name, meta.mime, fileId, meta.folderId, meta.contentHash, System.currentTimeMillis(), ready = !fill)
        EditSessionsSweeper.schedule(checkNotNull(context))
        if (!fill) return session
        try {
            runBlocking(Dispatchers.IO) {
                val job = coroutineContext.job
                signal?.setOnCancelListener { job.cancel() }
                val opened = parts.content().openFile(fileId) ?: throw FileNotFoundException()
                // Cut off by a lock like any read: decrypting on after it wrote the file onto a locked phone (audit 3, AND3-2).
                val file = held(opened)
                try {
                    FileOutputStream(session.copy).use { out -> copyWhole(file.reader.size, file.reader::read, { bytes, n -> out.write(bytes, 0, n) }) }
                } finally {
                    reads -= file
                    file.close()
                }
                if (keys() == null) throw FileNotFoundException("Coffer is locked")
            }
        } catch (e: Exception) {
            File(root, session.id).deleteRecursively()
            throw FileNotFoundException().apply { initCause(e) }
        }
        EditSessions.filled(root, session.id, System.currentTimeMillis())
        return session
    }

    /**
     * [fileId] decrypted as the other app reads it, a chunk at a time (see [VaultContent.openFile]), through
     * a descriptor this process serves. Never for Coffer itself: a process reading a descriptor it serves can
     * hang for good when it is killed mid-read (seen 2026-11-11, see MemoryCopy).
     */
    private fun stream(fileId: Id): ParcelFileDescriptor {
        if (Binder.getCallingUid() == Process.myUid()) throw FileNotFoundException("Coffer's own files")
        val context = checkNotNull(context)
        val opened = held(runBlocking { parts.content().openFile(fileId) } ?: throw FileNotFoundException())
        val thread = HandlerThread("CofferPickerRead").apply { start() }
        val release: () -> Unit = {
            reads -= opened
            opened.close()
            thread.quitSafely()
        }
        try {
            return context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = opened.reader.size

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                        runBlocking { opened.reader.read(offset, data, 0, size) }.coerceAtLeast(0)
                    } catch (_: Exception) {
                        // Locked, offline or damaged: to the app, a file that cannot be read.
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }

                    override fun onRelease() = release()
                },
                Handler(thread.looper),
            )
        } catch (e: IOException) {
            release()
            throw FileNotFoundException().apply { initCause(e) }
        }
    }

    /**
     * [opened], closed when the vault locks. Locked already, as between opening and this, it is closed at
     * once: the lock's signal came before there was anything to cut off (audit 3, AND3-2).
     */
    private fun held(opened: VaultContent.OpenedFile): VaultContent.OpenedFile {
        reads += opened
        cutOffOnLock()
        if (keys() == null) {
            reads -= opened
            opened.close()
            throw FileNotFoundException("Coffer is locked")
        }
        return opened
    }

    private fun cutOffOnLock() {
        if (!watchingLock.compareAndSet(false, true)) return
        scope.launch {
            parts.content().keys.collect { keys -> if (keys == null) reads.toList().forEach { it.close() } }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val keys = keys() ?: throw FileNotFoundException("Coffer is locked")
        // Folders are made in Coffer: one made here would not show until Telegram has it.
        if (mimeType == Document.MIME_TYPE_DIR) throw UnsupportedOperationException("folders are made in Coffer")
        val view = view(keys)
        val folder = folderOf(PickerDocuments.parse(parentDocumentId), view)
        val mime = MimeTypes.normalize(mimeType)
        val wanted = vaultName(withExtension(displayName, mime), FILE_FALLBACK_NAME)
        val here = view.tree.childFiles(folder).map { view.tree.files.getValue(it).name } +
            view.tree.childFolders(folder).map { view.tree.folders.getValue(it).name } +
            view.made.filter { it.folder == folder }.map { it.copy.name }
        val taken = here.mapTo(HashSet(), NameConflicts::key)
        val name = if (NameConflicts.key(wanted) in taken) NameConflicts.numbered(wanted, isFile = true, taken) else wanted
        // No hash: whatever is written, an empty file too, is sent; nothing written, nothing is.
        val session = EditSessions.begin(sessionsDir, name, mime, null, folder, Bytes.of(ByteArray(0)), System.currentTimeMillis())
        EditSessionsSweeper.schedule(checkNotNull(context))
        changed(checkNotNull(context))
        return PickerDocuments.id(Doc.Made(session.id))
    }

    /** [name] with the extension of [mime] when it has none of its own for it, as the system's own storage does. */
    private fun withExtension(name: String, mime: String): String {
        val types = MimeTypeMap.getSingleton()
        val extension = types.getExtensionFromMimeType(mime) ?: return name
        val own = name.substringAfterLast('.', "").lowercase()
        return if (own.isNotEmpty() && types.getMimeTypeFromExtension(own) == mime) name else "$name.$extension"
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS)
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: Context) = "${context.packageName}.documents"

        private fun title(context: Context) = context.applicationInfo.loadLabel(context.packageManager).toString()

        /** Shows Coffer in the system file picker, or takes it out (the setting, off by default). */
        fun show(context: Context, on: Boolean) {
            val component = ComponentName(context, VaultDocumentsProvider::class.java)
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component) != state) pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }

        /** Locked, unlocked or anything in the vault changed: the picker reads it again. */
        fun changed(context: Context) {
            context.contentResolver.notifyChange(Uri.Builder().scheme("content").authority(authority(context)).build(), null)
        }
    }
}
