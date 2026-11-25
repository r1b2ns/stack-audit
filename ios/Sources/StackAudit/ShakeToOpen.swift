import ObjectiveC
import StackAuditKit
import UIKit

enum ShakeToOpen {
    private static var installed = false

    static func install() {
        guard !installed else { return }
        installed = true
        let type: AnyClass = UIWindow.self
        let original = #selector(UIResponder.motionEnded(_:with:))
        let replacement = #selector(UIWindow.stackAuditMotionEnded(_:with:))
        guard
            let originalMethod = class_getInstanceMethod(type, original),
            let replacementMethod = class_getInstanceMethod(type, replacement)
        else { return }
        if class_addMethod(type, original, method_getImplementation(replacementMethod), method_getTypeEncoding(replacementMethod)) {
            class_replaceMethod(type, replacement, method_getImplementation(originalMethod), method_getTypeEncoding(originalMethod))
        } else {
            method_exchangeImplementations(originalMethod, replacementMethod)
        }
    }

    static func handle(_ motion: UIEvent.EventSubtype) {
        guard motion == .motionShake, StackAudit.shakeToOpen else { return }
        _ = StackAuditBridge.shared.show()
    }
}

extension UIWindow {
    @objc func stackAuditMotionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        ShakeToOpen.handle(motion)
        stackAuditMotionEnded(motion, with: event)
    }
}
