import Foundation
import StackAuditKit

public protocol WebSocketTasking: AnyObject {
    var originalRequest: URLRequest? { get }
    func resume()
    func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?)
    func send(_ message: URLSessionWebSocketTask.Message, completionHandler: @escaping @Sendable (Error?) -> Void)
    func receive(completionHandler: @escaping @Sendable (Result<URLSessionWebSocketTask.Message, Error>) -> Void)
    func sendPing(pongReceiveHandler: @escaping @Sendable (Error?) -> Void)
}

extension URLSessionWebSocketTask: WebSocketTasking {}

public final class StackAuditWebSocket {
    public let task: WebSocketTasking
    private let connectionId: String
    private let url: String

    public init(task: WebSocketTasking) {
        self.task = task
        url = task.originalRequest?.url?.absoluteString ?? ""
        if let native = task as? URLSessionWebSocketTask {
            WebSocketCapture.markWrapped(native)
            connectionId = WebSocketCapture.connectionId(for: native)
        } else {
            connectionId = UUID().uuidString
        }
    }

    public func resume() {
        record("open")
        task.resume()
    }

    public func send(_ message: URLSessionWebSocketTask.Message, completionHandler: @escaping @Sendable (Error?) -> Void) {
        record("sent", message)
        task.send(message) { [weak self] error in
            if let error { self?.record("failed", error: error) }
            completionHandler(error)
        }
    }

    public func send(_ message: URLSessionWebSocketTask.Message) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            send(message) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }

    public func receive(completionHandler: @escaping @Sendable (Result<URLSessionWebSocketTask.Message, Error>) -> Void) {
        task.receive { [weak self] result in
            switch result {
            case let .success(message): self?.record("received", message)
            case let .failure(error): self?.record("failed", error: error)
            }
            completionHandler(result)
        }
    }

    public func receive() async throws -> URLSessionWebSocketTask.Message {
        try await withCheckedThrowingContinuation { continuation in
            receive { continuation.resume(with: $0) }
        }
    }

    public func sendPing(pongReceiveHandler: @escaping @Sendable (Error?) -> Void) {
        record("ping")
        task.sendPing { [weak self] error in
            self?.record(error == nil ? "pong" : "failed", error: error)
            pongReceiveHandler(error)
        }
    }

    public func cancel(with closeCode: URLSessionWebSocketTask.CloseCode = .normalClosure, reason: Data? = nil) {
        record("closed", code: closeCode.rawValue, reason: reason)
        task.cancel(with: closeCode, reason: reason)
    }

    private func record(
        _ kind: String,
        _ message: URLSessionWebSocketTask.Message? = nil,
        code: Int = 0,
        reason: Data? = nil,
        error: Error? = nil
    ) {
        WebSocketRecorder.record(
            source: "urlsession",
            connectionId: connectionId,
            url: url,
            kind: kind,
            message: message,
            code: code,
            reason: reason,
            error: error
        )
    }
}

public extension URLSession {
    func auditedWebSocketTask(with url: URL) -> StackAuditWebSocket {
        StackAuditWebSocket(task: webSocketTask(with: url))
    }

    func auditedWebSocketTask(with url: URL, protocols: [String]) -> StackAuditWebSocket {
        StackAuditWebSocket(task: webSocketTask(with: url, protocols: protocols))
    }

    func auditedWebSocketTask(with request: URLRequest) -> StackAuditWebSocket {
        StackAuditWebSocket(task: webSocketTask(with: request))
    }
}
