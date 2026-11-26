import Foundation
import StackAuditKit

enum WebSocketRecorder {
    static func record(
        source: String,
        connectionId: String,
        url: String,
        kind: String,
        message: URLSessionWebSocketTask.Message? = nil,
        code: Int = 0,
        reason: Data? = nil,
        error: Error? = nil
    ) {
        guard StackAuditBridge.shared.isCapturingWebSockets else { return }
        let (data, isText) = payload(message)
        StackAuditBridge.shared.recordWebSocket(
            source: source,
            connectionId: connectionId,
            url: url,
            kind: kind,
            data: data,
            isText: isText,
            code: Int32(code),
            reason: reason.map { String(decoding: $0, as: UTF8.self) },
            error: error.map(describe),
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    static func payload(_ message: URLSessionWebSocketTask.Message?) -> (Data?, Bool) {
        switch message {
        case let .string(text): return (Data(text.utf8), true)
        case let .data(bytes): return (bytes, false)
        default: return (nil, true)
        }
    }

    static func describe(_ error: Error) -> String {
        if let urlError = error as? URLError {
            return "URLError: \(urlError.localizedDescription)"
        }
        return "\(type(of: error)): \(error.localizedDescription)"
    }
}
