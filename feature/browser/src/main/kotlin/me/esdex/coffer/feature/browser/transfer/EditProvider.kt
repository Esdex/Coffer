package me.esdex.coffer.feature.browser.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import java.io.FileNotFoundException

/**
 * Hands the copies of [EditSessions] to the app editing them, read and write: `content://<authority>/<id>/<name>`.
 * When the editor closes a copy it opened for writing, the session is marked for saving. Not exported:
 * only an app given the URI with its grant gets in, and only to that one copy.
 */
class EditProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun session(uri: Uri): EditSession {
        val (id, name) = uri.pathSegments.takeIf { it.size == 2 } ?: throw FileNotFoundException()
        val session = EditSessions.get(EditSessions.dir(checkNotNull(context)), id) ?: throw FileNotFoundException()
        if (session.copy.name != name) throw FileNotFoundException()
        return session
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val session = session(uri)
        val access = ParcelFileDescriptor.parseMode(mode)
        if (access == ParcelFileDescriptor.MODE_READ_ONLY) return ParcelFileDescriptor.open(session.copy, access)
        val root = EditSessions.dir(checkNotNull(context))
        // Kept while open, however long before the editor saves (audit 3, DATA3-2).
        EditSessions.opened(root, session.id, Process.myPid(), System.currentTimeMillis())
        // Closed after writing: saved. Whether anything changed is for the hash to tell.
        return ParcelFileDescriptor.open(session.copy, access, Handler(Looper.getMainLooper())) { error ->
            // Closed with an error, as by an editor that crashed mid-write: no save (audit 3, Q3-2).
            if (error == null) EditSessions.written(root, session.id, System.currentTimeMillis()) else EditSessions.failed(root, session.id, System.currentTimeMillis())
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val copy = session(uri).copy
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) copy.name else copy.length() })
        }
    }

    override fun getType(uri: Uri): String? = runCatching { session(uri).mime }.getOrNull()

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun authority(context: Context) = "${context.packageName}.edit"

        fun uri(context: Context, session: EditSession): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).appendPath(session.id).appendPath(session.copy.name).build()
    }
}
