import Foundation
import StackAuditKit

public enum StackAudit {
    public enum Secrets: Equatable {
        case mask(extraHeaders: [String] = [], extraFields: [String] = [], allow: [String] = [])
        case show
        case showAfterHandshake(extraHeaders: [String] = [], extraFields: [String] = [], allow: [String] = [])
    }

    public enum Access: Equatable {
        case open
        case handshake(publicKey: String, sessionTTL: TimeInterval = 1800)
        case hidden
    }

    public enum Network: Equatable {
        case automatic
        case manual
    }

    public struct Configuration {
        public var secrets: Secrets = .mask()
        public var access: Access = .open
        public var network: Network = .automatic
        public var retention: TimeInterval = 7 * 24 * 3600
        public var maxBodySize: Int64 = 1024 * 1024
        public var maxStorage: Int64 = 100 * 1024 * 1024
        public var shakeToOpen: Bool = true

        public init() {}
    }

    public enum ConfigurationError: Error, Equatable {
        case invalid(String)
    }

    public static var shakeToOpen: Bool = true

    public static func start(_ configure: (inout Configuration) -> Void = { _ in }) {
        var configuration = Configuration()
        configure(&configuration)
        do {
            try start(configuration)
        } catch let ConfigurationError.invalid(message) {
            preconditionFailure(message)
        } catch {
            preconditionFailure(error.localizedDescription)
        }
    }

    public static func start(_ configuration: Configuration) throws {
        do {
            try StackAuditBridge.shared.start(options: options(from: configuration), cipher: KeychainCipher())
        } catch {
            throw ConfigurationError.invalid(error.localizedDescription)
        }
        shakeToOpen = configuration.shakeToOpen
        ShakeToOpen.install()
        if configuration.network == .automatic {
            NetworkCapture.enable()
        }
    }

    @discardableResult
    public static func show() -> Bool {
        StackAuditBridge.shared.show()
    }

    static func options(from configuration: Configuration) -> BridgeOptions {
        let options = BridgeOptions()
        switch configuration.secrets {
        case let .mask(headers, fields, allowed):
            options.secrets = .mask
            options.extraHeaders = headers
            options.extraFields = fields
            options.allowed = allowed
        case .show:
            options.secrets = .show
        case let .showAfterHandshake(headers, fields, allowed):
            options.secrets = .showAfterHandshake
            options.extraHeaders = headers
            options.extraFields = fields
            options.allowed = allowed
        }
        switch configuration.access {
        case .open:
            options.access = .open
        case let .handshake(publicKey, ttl):
            options.access = .handshake
            options.publicKey = publicKey
            options.sessionTtlSeconds = Int64(ttl)
        case .hidden:
            options.access = .hidden
        }
        options.retentionSeconds = Int64(configuration.retention)
        options.maxBodySize = configuration.maxBodySize
        options.maxStorage = configuration.maxStorage
        return options
    }
}

public extension URLSessionConfiguration {
    @discardableResult
    func audited() -> URLSessionConfiguration {
        NetworkCapture.insert(into: self)
        return self
    }
}
