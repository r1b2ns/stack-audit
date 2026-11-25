package io.github.r1b2ns.stackaudit.ui

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

internal actual fun shareFile(
    name: String,
    mimeType: String,
    content: String,
) {
    val context = StackAuditUi.context ?: return
    val dir = File(context.cacheDir, "stackaudit").apply { mkdirs() }
    val file = File(dir, name).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".stackaudit.files", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
