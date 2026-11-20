package me.esdex.coffer.feature.browser.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Process
import android.system.Os
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Opens what another app hands Coffer to read: a file shared in, picked to upload. Never one of Coffer's
 * own proxy descriptors handed back: the picker refuses Coffer its own documents, but an app given one can
 * pass that same descriptor on through a provider of its own. Read here, it is served by a thread of this
 * process, and a process killed mid-read can never exit; Coffer then does not start until the phone
 * restarts (seen 2026-11-11; audit 3, AND3-3).
 */
object OtherAppsFiles {
    fun open(context: Context, uri: Uri): InputStream {
        val resolver = context.contentResolver
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return resolver.openInputStream(uri) ?: throw FileNotFoundException("cannot open")
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException("cannot open")
        val path = runCatching { Os.readlink("/proc/self/fd/${descriptor.parcelFileDescriptor.fd}") }.getOrNull()
        if (path != null && isOwnProxy(path, Process.myUid())) {
            descriptor.close()
            throw FileNotFoundException("one of Coffer's own files")
        }
        return descriptor.createInputStream()
    }

    /** A descriptor served by app [uid] through StorageManager.openProxyFileDescriptor lives on its appfuse mount. */
    internal fun isOwnProxy(path: String, uid: Int): Boolean = path.startsWith("/mnt/appfuse/${uid}_")
}
