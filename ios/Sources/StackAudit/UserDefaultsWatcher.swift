import Foundation
import StackAuditKit

public final class UserDefaultsWatcher {
    private let defaults: UserDefaults
    private let name: String
    private let domain: String?
    private let lock = NSLock()
    private var snapshot: [String: String] = [:]
    private var observer: NSObjectProtocol?

    public init(defaults: UserDefaults = .standard, name: String = "standard", domain: String? = Bundle.main.bundleIdentifier) {
        self.defaults = defaults
        self.name = name
        self.domain = domain
    }

    deinit {
        stop()
    }

    public func start() {
        guard observer == nil else { return }
        lock.lock()
        snapshot = current()
        lock.unlock()
        observer = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: defaults,
            queue: nil
        ) { [weak self] _ in self?.check() }
    }

    public func stop() {
        observer.map(NotificationCenter.default.removeObserver)
        observer = nil
    }

    func check() {
        lock.lock()
        let before = snapshot
        let after = current()
        snapshot = after
        lock.unlock()
        guard StackAuditBridge.shared.isCapturingStorage else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for key in Set(before.keys).union(after.keys).sorted() {
            let old = before[key]
            let new = after[key]
            guard old != new else { continue }
            StackAuditBridge.shared.recordStorage(
                source: "user_defaults",
                store: name,
                key: key,
                operation: new == nil ? "remove" : "write",
                oldValue: old,
                newValue: new,
                valueType: new == nil ? nil : types[key],
                timestampMs: now
            )
        }
    }

    private var types: [String: String] = [:]

    private func current() -> [String: String] {
        let values = domain.flatMap { defaults.persistentDomain(forName: $0) } ?? defaults.dictionaryRepresentation()
        var result: [String: String] = [:]
        for (key, value) in values {
            result[key] = Self.describe(value)
            types[key] = String(describing: type(of: value))
        }
        return result
    }

    static func describe(_ value: Any) -> String {
        switch value {
        case let data as Data: return "<\(data.count) bytes>"
        case let date as Date: return ISO8601DateFormatter().string(from: date)
        default: return String(describing: value)
        }
    }
}
