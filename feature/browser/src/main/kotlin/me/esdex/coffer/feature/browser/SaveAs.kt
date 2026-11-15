package me.esdex.coffer.feature.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContract

/**
 * The system's "save as" for one file, under its name, with the type its extension says. The picker
 * keeps the extension apart from the name only when the type matches it: given any other, a second
 * "file.pdf" in the same folder became "file.pdf (1)" instead of "file (1).pdf".
 */
internal class SaveAs : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(typeOf(input))
        .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.takeIf { resultCode == Activity.RESULT_OK }?.data

    private fun typeOf(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }
}
