package io.github.r1b2ns.stackaudit.ui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun shareFile(
    name: String,
    mimeType: String,
    content: String,
) {
    val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + name)
    NSString.create(string = content).writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    val top = StackAuditUi.topViewController() ?: return
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    top.presentViewController(sheet, animated = true, completion = null)
}
