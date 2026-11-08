package me.esdex.coffer.feature.browser.transfer

import android.content.Context
import android.net.Uri
import me.esdex.coffer.core.sync.UploadSource
import java.io.FileNotFoundException
import java.io.InputStream

/** A file the user picked through the system picker, read with its persisted permission. */
class ContentUriSource(private val context: Context, private val uri: String) : UploadSource {
    override fun open(): InputStream =
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw FileNotFoundException("cannot open the picked file")
}
