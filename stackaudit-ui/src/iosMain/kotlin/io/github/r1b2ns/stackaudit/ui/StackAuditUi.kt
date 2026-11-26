package io.github.r1b2ns.stackaudit.ui

import androidx.compose.ui.window.ComposeUIViewController
import io.github.r1b2ns.stackaudit.StackAudit
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UITabBarAppearance
import platform.UIKit.UITabBarController
import platform.UIKit.UITabBarItem
import platform.UIKit.tabBarItem
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow

public object StackAuditUi {
    private var installed = false

    public fun install() {
        if (installed) return
        installed = true
        StackAudit.registerViewer { present() }
    }

    public fun viewController(onClose: () -> Unit = {}): UIViewController {
        val tabs = UITabBarController()
        tabs.tabBar.scrollEdgeAppearance = UITabBarAppearance().apply { configureWithDefaultBackground() }
        val select: (Tab) -> Unit = { tabs.selectedIndex = it.ordinal.toULong() }
        tabs.setViewControllers(
            Tab.entries.map { tab ->
                ComposeUIViewController(configure = { enforceStrictPlistSanityCheck = false }) { AuditRoot(onClose, tab, select) }.apply {
                    tabBarItem = UITabBarItem(title = tab.title, image = UIImage.systemImageNamed(tab.symbol), selectedImage = UIImage.systemImageNamed(tab.selectedSymbol))
                }
            },
            animated = false,
        )
        return tabs
    }

    private val Tab.symbol: String
        get() =
            when (this) {
                Tab.Requests -> "list.bullet"
                Tab.Storage -> "internaldrive"
                Tab.Flags -> "flag"
                Tab.Settings -> "gearshape"
            }

    private val Tab.selectedSymbol: String
        get() =
            when (this) {
                Tab.Requests -> "list.bullet"
                Tab.Storage -> "internaldrive.fill"
                Tab.Flags -> "flag.fill"
                Tab.Settings -> "gearshape.fill"
            }

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
