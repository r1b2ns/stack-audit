import Flutter
import StackAudit
import UIKit

public final class StackAuditPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: StackAuditChannel.name, binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(StackAuditPlugin(), channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let raw = call.arguments as? [String: Any] ?? [:]
        var arguments: [String: Any?] = [:]
        for (key, value) in raw {
            arguments[key] = Self.unwrap(value)
        }
        do {
            result(try StackAuditChannel.handle(method: call.method, arguments: arguments))
        } catch StackAuditChannel.ChannelError.notImplemented {
            result(FlutterMethodNotImplemented)
        } catch let StackAuditChannel.ChannelError.invalidArguments(message) {
            result(FlutterError(code: "invalid_arguments", message: message, details: nil))
        } catch {
            result(FlutterError(code: "stackaudit_error", message: error.localizedDescription, details: nil))
        }
    }

    private static func unwrap(_ value: Any) -> Any? {
        switch value {
        case is NSNull: return nil
        case let data as FlutterStandardTypedData: return data.data
        default: return value
        }
    }
}
