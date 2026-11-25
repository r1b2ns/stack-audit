import Foundation
import StackAuditKit

public enum StackAuditChannel {
    public static let name = "io.github.r1b2ns.stackaudit"

    public enum ChannelError: Error, Equatable {
        case notImplemented(String)
        case invalidArguments(String)
    }

    public static func handle(method: String, arguments: [String: Any?]) throws -> Any? {
        switch method {
        case "start":
            try StackAudit.start(configuration(from: arguments))
            return nil
        case "isCapturing":
            return StackAuditBridge.shared.isCapturing
        case "maxBodySize":
            return StackAuditBridge.shared.maxBodySize
        case "recordHttp":
            try recordHttp(arguments)
            return nil
        case "recordStorage":
            try recordStorage(arguments)
            return nil
        case "recordAnalytics":
            let params = (arguments["params"] as? [String: Any?] ?? [:]).compactMapValues { $0 }
            StackAuditAnalytics.record(provider: try text(arguments, "provider"), name: try text(arguments, "name"), params: params)
            return nil
        case "setTrackingPlan":
            do {
                try StackAuditAnalytics.setTrackingPlan(json: arguments["json"] as? String)
            } catch {
                throw ChannelError.invalidArguments(error.localizedDescription)
            }
            return nil
        case "flag":
            return try flag(arguments)
        case "handshake":
            switch StackAudit.handshake(token: try text(arguments, "token")) {
            case .accepted: return nil
            case let .rejected(reason): return reason
            }
        case "handleLink":
            return StackAuditBridge.shared.handleLink(url: try text(arguments, "url"))
        case "deviceId":
            return StackAudit.deviceId
        case "show":
            return StackAudit.show()
        default:
            throw ChannelError.notImplemented(method)
        }
    }

    public static func configuration(from arguments: [String: Any?]) throws -> StackAudit.Configuration {
        var configuration = StackAudit.Configuration()
        let headers = strings(arguments["extraHeaders"])
        let fields = strings(arguments["extraFields"])
        let allow = strings(arguments["allow"])
        switch arguments["secrets"] as? String ?? "mask" {
        case "mask": configuration.secrets = .mask(extraHeaders: headers, extraFields: fields, allow: allow)
        case "show": configuration.secrets = .show
        case "showAfterHandshake": configuration.secrets = .showAfterHandshake(extraHeaders: headers, extraFields: fields, allow: allow)
        case let other: throw ChannelError.invalidArguments("unknown secrets mode \(other)")
        }
        switch arguments["access"] as? String ?? "open" {
        case "open": configuration.access = .open
        case "hidden": configuration.access = .hidden
        case "handshake":
            guard let key = arguments["publicKey"] as? String else {
                throw ChannelError.invalidArguments("access handshake requires publicKey")
            }
            let ttl = (arguments["sessionTtlSeconds"] as? NSNumber)?.doubleValue ?? 1800
            configuration.access = .handshake(publicKey: key, sessionTTL: ttl, appId: arguments["appId"] as? String)
        case let other: throw ChannelError.invalidArguments("unknown access mode \(other)")
        }
        if let value = arguments["retentionSeconds"] as? NSNumber { configuration.retention = value.doubleValue }
        if let value = arguments["maxBodySize"] as? NSNumber { configuration.maxBodySize = value.int64Value }
        if let value = arguments["maxStorage"] as? NSNumber { configuration.maxStorage = value.int64Value }
        if let value = arguments["shakeToOpen"] as? Bool { configuration.shakeToOpen = value }
        if let value = arguments["captureNativeNetwork"] as? Bool { configuration.network = value ? .automatic : .manual }
        return configuration
    }

    private static func recordHttp(_ arguments: [String: Any?]) throws {
        let method = try text(arguments, "method")
        let url = try text(arguments, "url")
        guard StackAuditBridge.shared.isCapturing else { return }
        let requestBody = arguments["requestBody"] as? Data
        let responseBody = arguments["responseBody"] as? Data
        StackAuditBridge.shared.recordHttp(
            source: arguments["source"] as? String ?? "flutter",
            method: method,
            url: url,
            requestHeaders: headers(arguments["requestHeaders"]),
            requestBody: requestBody,
            status: Int32((arguments["status"] as? NSNumber)?.intValue ?? 0),
            responseHeaders: headers(arguments["responseHeaders"]),
            responseBody: responseBody,
            startedAtMs: (arguments["startedAt"] as? NSNumber)?.int64Value ?? now(),
            durationMs: (arguments["durationMs"] as? NSNumber)?.int64Value ?? 0,
            error: arguments["error"] as? String,
            requestBodySize: (arguments["requestBodySize"] as? NSNumber)?.int64Value ?? Int64(requestBody?.count ?? -1),
            responseBodySize: (arguments["responseBodySize"] as? NSNumber)?.int64Value ?? Int64(responseBody?.count ?? -1)
        )
    }

    private static func recordStorage(_ arguments: [String: Any?]) throws {
        let store = try text(arguments, "store")
        let operation = try text(arguments, "operation")
        guard ["read", "write", "remove", "clear"].contains(operation.lowercased()) else {
            throw ChannelError.invalidArguments("unknown storage operation \(operation)")
        }
        guard StackAuditBridge.shared.isCapturingStorage else { return }
        StackAuditBridge.shared.recordStorage(
            source: arguments["source"] as? String ?? "flutter",
            store: store,
            key: arguments["key"] as? String,
            operation: operation,
            oldValue: arguments["oldValue"] as? String,
            newValue: arguments["newValue"] as? String,
            valueType: arguments["valueType"] as? String,
            timestampMs: (arguments["timestamp"] as? NSNumber)?.int64Value ?? now()
        )
    }

    private struct FixedProvider: StackAuditFlagProvider {
        let remote: Any?

        func value(forKey key: String) -> Any? { remote }
    }

    private static func flag(_ arguments: [String: Any?]) throws -> Any {
        let key = try text(arguments, "key")
        let kind = try text(arguments, "kind")
        let remote = arguments["value"] ?? nil
        let flags = StackAuditFlags(provider: FixedProvider(remote: remote), name: arguments["provider"] as? String ?? "app")
        let fallback = arguments["default"] ?? nil
        switch kind {
        case "bool":
            guard let value = fallback as? Bool else { throw ChannelError.invalidArguments("default must be a bool") }
            return flags.bool(key, default: value)
        case "string":
            guard let value = fallback as? String else { throw ChannelError.invalidArguments("default must be a string") }
            return flags.string(key, default: value)
        case "number":
            guard let value = fallback as? NSNumber else { throw ChannelError.invalidArguments("default must be a number") }
            return flags.number(key, default: value.doubleValue)
        default:
            throw ChannelError.invalidArguments("unknown flag kind \(kind)")
        }
    }

    private static func text(_ arguments: [String: Any?], _ name: String) throws -> String {
        guard let value = arguments[name] as? String else { throw ChannelError.invalidArguments("\(name) is required") }
        return value
    }

    private static func strings(_ value: Any??) -> [String] {
        (value ?? nil) as? [String] ?? []
    }

    private static func headers(_ value: Any??) -> [String: String] {
        ((value ?? nil) as? [String: Any] ?? [:]).mapValues { "\($0)" }
    }

    private static func now() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}
