import Foundation
@testable import StackAudit

enum TestSupport {
    private static var started = false

    static func startOnce() {
        guard !started else { return }
        started = true
        StackAudit.start { $0.shakeToOpen = false }
    }
}
