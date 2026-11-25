import Foundation
import ObjectiveC

enum NetworkCapture {
    private static var enabled = false
    private static let lock = NSLock()

    static func enable() {
        lock.lock()
        defer { lock.unlock() }
        guard !enabled else { return }
        enabled = true
        URLProtocol.registerClass(StackAuditURLProtocol.self)
        exchangeClassGetter(
            NSSelectorFromString("defaultSessionConfiguration"),
            #selector(getter: URLSessionConfiguration.stackAuditDefault)
        )
        exchangeClassGetter(
            NSSelectorFromString("ephemeralSessionConfiguration"),
            #selector(getter: URLSessionConfiguration.stackAuditEphemeral)
        )
    }

    static func insert(into configuration: URLSessionConfiguration) {
        var classes = configuration.protocolClasses ?? []
        guard !classes.contains(where: { $0 == StackAuditURLProtocol.self }) else { return }
        classes.insert(StackAuditURLProtocol.self, at: 0)
        configuration.protocolClasses = classes
    }

    private static func exchangeClassGetter(_ original: Selector, _ replacement: Selector) {
        let type: AnyClass = URLSessionConfiguration.self
        guard
            let originalMethod = class_getClassMethod(type, original),
            let replacementMethod = class_getClassMethod(type, replacement)
        else { return }
        method_exchangeImplementations(originalMethod, replacementMethod)
    }
}

extension URLSessionConfiguration {
    @objc class var stackAuditDefault: URLSessionConfiguration {
        let configuration = stackAuditDefault
        NetworkCapture.insert(into: configuration)
        return configuration
    }

    @objc class var stackAuditEphemeral: URLSessionConfiguration {
        let configuration = stackAuditEphemeral
        NetworkCapture.insert(into: configuration)
        return configuration
    }
}
