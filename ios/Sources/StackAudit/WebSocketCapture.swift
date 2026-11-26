import Foundation
import ObjectiveC

enum WebSocketCapture {
    static let source = "urlsession-auto"

    private static var enabled = false
    private(set) static var installations = 0
    private(set) static var hookedSelectors: [String] = []
    private static let lock = NSLock()
    private static let idLock = NSLock()
    private static let idKey = UnsafeRawPointer(UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1))
    private static let wrappedKey = UnsafeRawPointer(UnsafeMutableRawPointer.allocate(byteCount: 1, alignment: 1))

    typealias ErrorHandler = @convention(block) (NSError?) -> Void
    typealias ReceiveHandler = @convention(block) (AnyObject?, NSError?) -> Void

    static func enable() {
        lock.lock()
        defer { lock.unlock() }
        guard !enabled else { return }
        enabled = true
        guard let type = concreteTaskClass() else { return }
        installations += 1
        hookResume(type)
        hookSend(type)
        hookReceive(type)
        hookPing(type)
        hookCancel(type)
    }

    static func concreteTaskClass() -> AnyClass? {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        guard let url = URL(string: "wss://stackaudit.invalid") else { return nil }
        let task = session.webSocketTask(with: url)
        defer { task.cancel() }
        return object_getClass(task)
    }

    static func markWrapped(_ task: URLSessionWebSocketTask) {
        objc_setAssociatedObject(task, wrappedKey, true as NSNumber, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }

    static func isWrapped(_ task: AnyObject) -> Bool {
        (objc_getAssociatedObject(task, wrappedKey) as? NSNumber)?.boolValue == true
    }

    static func connectionId(for task: AnyObject) -> String {
        idLock.lock()
        defer { idLock.unlock() }
        if let id = objc_getAssociatedObject(task, idKey) as? UUID {
            return id.uuidString
        }
        let id = UUID()
        objc_setAssociatedObject(task, idKey, id, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return id.uuidString
    }

    static func message(from object: AnyObject?) -> URLSessionWebSocketTask.Message? {
        guard let object = object as? NSObject else { return nil }
        if let text = object.value(forKey: "string") as? String { return .string(text) }
        if let data = object.value(forKey: "data") as? Data { return .data(data) }
        return nil
    }

    private static func record(
        _ task: AnyObject,
        _ kind: String,
        message: URLSessionWebSocketTask.Message? = nil,
        code: Int = 0,
        reason: Data? = nil,
        error: Error? = nil
    ) {
        guard let task = task as? URLSessionTask, !isWrapped(task) else { return }
        WebSocketRecorder.record(
            source: source,
            connectionId: connectionId(for: task),
            url: task.originalRequest?.url?.absoluteString ?? task.currentRequest?.url?.absoluteString ?? "",
            kind: kind,
            message: message,
            code: code,
            reason: reason,
            error: error
        )
    }

    private static func install(_ type: AnyClass, _ name: String, _ make: (IMP) -> Any) {
        let selector = NSSelectorFromString(name)
        guard let method = class_getInstanceMethod(type, selector) else { return }
        let original = method_getImplementation(method)
        let replacement = imp_implementationWithBlock(make(original))
        if !class_addMethod(type, selector, replacement, method_getTypeEncoding(method)) {
            method_setImplementation(method, replacement)
        }
        hookedSelectors.append(name)
    }

    private static func hookResume(_ type: AnyClass) {
        let selector = NSSelectorFromString("resume")
        typealias Original = @convention(c) (AnyObject, Selector) -> Void
        install(type, "resume") { imp in
            let original = unsafeBitCast(imp, to: Original.self)
            let block: @convention(block) (AnyObject) -> Void = { task in
                if (task as? URLSessionTask)?.state == .suspended, (task as? URLSessionTask)?.error == nil {
                    record(task, "open")
                }
                original(task, selector)
            }
            return block
        }
    }

    private static func hookSend(_ type: AnyClass) {
        let selector = NSSelectorFromString("sendMessage:completionHandler:")
        typealias Original = @convention(c) (AnyObject, Selector, AnyObject, ErrorHandler?) -> Void
        install(type, "sendMessage:completionHandler:") { imp in
            let original = unsafeBitCast(imp, to: Original.self)
            let block: @convention(block) (AnyObject, AnyObject, ErrorHandler?) -> Void = { task, message, handler in
                record(task, "sent", message: WebSocketCapture.message(from: message))
                let wrapped: ErrorHandler = { error in
                    if let error { record(task, "failed", error: error) }
                    handler?(error)
                }
                original(task, selector, message, wrapped)
            }
            return block
        }
    }

    private static func hookReceive(_ type: AnyClass) {
        let selector = NSSelectorFromString("receiveMessageWithCompletionHandler:")
        typealias Original = @convention(c) (AnyObject, Selector, ReceiveHandler?) -> Void
        install(type, "receiveMessageWithCompletionHandler:") { imp in
            let original = unsafeBitCast(imp, to: Original.self)
            let block: @convention(block) (AnyObject, ReceiveHandler?) -> Void = { task, handler in
                let wrapped: ReceiveHandler = { message, error in
                    if let error {
                        record(task, "failed", error: error)
                    } else if let parsed = WebSocketCapture.message(from: message) {
                        record(task, "received", message: parsed)
                    }
                    handler?(message, error)
                }
                original(task, selector, wrapped)
            }
            return block
        }
    }

    private static func hookPing(_ type: AnyClass) {
        let selector = NSSelectorFromString("sendPingWithPongReceiveHandler:")
        typealias Original = @convention(c) (AnyObject, Selector, ErrorHandler?) -> Void
        install(type, "sendPingWithPongReceiveHandler:") { imp in
            let original = unsafeBitCast(imp, to: Original.self)
            let block: @convention(block) (AnyObject, ErrorHandler?) -> Void = { task, handler in
                record(task, "ping")
                let wrapped: ErrorHandler = { error in
                    record(task, error == nil ? "pong" : "failed", error: error)
                    handler?(error)
                }
                original(task, selector, wrapped)
            }
            return block
        }
    }

    private static func hookCancel(_ type: AnyClass) {
        let selector = NSSelectorFromString("cancelWithCloseCode:reason:")
        typealias Original = @convention(c) (AnyObject, Selector, Int, NSData?) -> Void
        install(type, "cancelWithCloseCode:reason:") { imp in
            let original = unsafeBitCast(imp, to: Original.self)
            let block: @convention(block) (AnyObject, Int, NSData?) -> Void = { task, code, reason in
                record(task, "closed", code: code, reason: reason as Data?)
                original(task, selector, code, reason)
            }
            return block
        }
    }
}
