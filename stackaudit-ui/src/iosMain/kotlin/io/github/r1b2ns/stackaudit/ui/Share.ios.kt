package io.github.r1b2ns.stackaudit.ui

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun shareFile(
    name: String,
    mimeType: String,
    content: String,
) {
    val directory = NSTemporaryDirectory() + "StackAuditExport/"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    val url = NSURL.fileURLWithPath(directory + name)
    NSString.create(string = content).writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    val top = StackAuditUi.topViewController() ?: return
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    sheet.completionWithItemsHandler = { _, _, _, _ -> NSFileManager.defaultManager.removeItemAtURL(url, error = null) }
    sheet.popoverPresentationController?.let { popover ->
        popover.sourceView = top.view
        top.view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height, 1.0, 1.0) }
    }
    top.presentViewController(sheet, animated = true, completion = null)
}
