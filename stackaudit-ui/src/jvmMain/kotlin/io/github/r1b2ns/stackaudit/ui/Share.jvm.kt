package io.github.r1b2ns.stackaudit.ui

import java.io.File

internal actual fun shareFile(
    name: String,
    mimeType: String,
    content: String,
) {
    val dir = File(System.getProperty("user.home"), ".stackaudit/exports").apply { mkdirs() }
    File(dir, name).writeText(content)
}
