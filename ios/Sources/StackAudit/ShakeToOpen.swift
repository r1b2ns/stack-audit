import ObjectiveC
import StackAuditKit
import UIKit

enum ShakeToOpen {
    private typealias MotionEnded = @convention(c) (AnyObject, Selector, UIEvent.EventSubtype, UIEvent?) -> Void
    private typealias MotionEndedBlock = @convention(block) (UIWindow, UIEvent.EventSubtype, UIEvent?) -> Void

    private static var installed = false
    private static var previous: IMP?

    static func install() {
        guard !installed else { return }
        let type: AnyClass = UIWindow.self
        let selector = #selector(UIResponder.motionEnded(_:with:))
        guard let method = class_getInstanceMethod(type, selector) else { return }
        installed = true

        let block: MotionEndedBlock = { window, motion, event in
            handle(motion)
            guard let previous else { return }
            unsafeBitCast(previous, to: MotionEnded.self)(window, selector, motion, event)
        }
        let replacement = imp_implementationWithBlock(block)
        let inherited = method_getImplementation(method)
        if class_addMethod(type, selector, replacement, method_getTypeEncoding(method)) {
            previous = inherited
        } else {
            previous = method_setImplementation(method, replacement)
        }
    }

    static func handle(_ motion: UIEvent.EventSubtype) {
        guard motion == .motionShake, StackAudit.shakeToOpen else { return }
        _ = StackAuditBridge.shared.show()
    }
}
