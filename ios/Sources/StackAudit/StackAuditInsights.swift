import CoreFoundation
import Foundation
import StackAuditKit

public enum StackAuditAnalytics {
    public static func record(provider: String, name: String, params: [String: Any] = [:]) {
        StackAuditBridge.shared.recordAnalytics(provider: provider, name: name, params: params.mapValues(kotlinValue))
    }

    public static func setTrackingPlan(json: String?) throws {
        try StackAuditBridge.shared.setTrackingPlan(json: json)
    }

    static func kotlinValue(_ value: Any) -> Any {
        if CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID(), let flag = value as? Bool {
            return KotlinBoolean(bool: flag)
        }
        switch value {
        case let number as Int: return KotlinLong(value: Int64(number))
        case let number as Int64: return KotlinLong(value: number)
        case let number as Double: return KotlinDouble(value: number)
        case let number as Float: return KotlinDouble(value: Double(number))
        case let text as String: return text
        default: return String(describing: value)
        }
    }
}

public protocol StackAuditFlagProvider {
    func value(forKey key: String) -> Any?
}

public final class StackAuditFlags {
    private let flags: AuditedFlags

    public init(provider: StackAuditFlagProvider, name: String = "app") {
        flags = StackAuditBridge.shared.flags(provider: name) { key in
            provider.value(forKey: key).flatMap(StackAuditFlags.flagValue)
        }
    }

    public func bool(_ key: String, default value: Bool) -> Bool {
        flags.bool(key: key, default: value)
    }

    public func string(_ key: String, default value: String) -> String {
        flags.string(key: key, default: value)
    }

    public func number(_ key: String, default value: Double) -> Double {
        flags.number(key: key, default: value)
    }

    static func flagValue(_ value: Any) -> FlagValue? {
        if CFGetTypeID(value as CFTypeRef) == CFBooleanGetTypeID(), let flag = value as? Bool {
            return FlagValueBool(value: flag)
        }
        switch value {
        case let text as String: return FlagValueText(value: text)
        case let number as Int: return FlagValueNumber(value: Double(number))
        case let number as Double: return FlagValueNumber(value: number)
        default: return nil
        }
    }
}
