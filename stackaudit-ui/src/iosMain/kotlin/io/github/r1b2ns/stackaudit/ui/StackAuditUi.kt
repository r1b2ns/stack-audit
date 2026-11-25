package io.github.r1b2ns.stackaudit.ui

import androidx.compose.ui.window.ComposeUIViewController
import io.github.r1b2ns.stackaudit.StackAudit
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

public object StackAuditUi {
    private var installed = false

    public fun install() {
        if (installed) return
        installed = true
        StackAudit.registerViewer { present() }
    }

    public fun viewController(onClose: () -> Unit = {}): UIViewController = ComposeUIViewController { StackAuditScreen(onClose) }

    public fun present() {
        val top = topViewController() ?: return
        var controller: UIViewController? = null
        controller = viewController { controller?.dismissViewControllerAnimated(true, null) }
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        top.presentViewController(controller, animated = true, completion = null)
    }

    internal fun topViewController(): UIViewController? {
        val window =
            UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull { it.isKeyWindow() }
                ?: UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>().firstOrNull()
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
