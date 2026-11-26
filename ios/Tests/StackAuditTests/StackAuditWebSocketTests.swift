import Foundation
import StackAuditKit
import XCTest
@testable import StackAudit

final class FakeWebSocketTask: WebSocketTasking {
    var originalRequest: URLRequest? = URLRequest(url: URL(string: "wss://ws.test/chat?token=abcdefghijklmnop")!)
    var sent: [URLSessionWebSocketTask.Message] = []
    var inbox: [Result<URLSessionWebSocketTask.Message, Error>] = []
    var resumed = false
    var closed: URLSessionWebSocketTask.CloseCode?

    func resume() { resumed = true }

    func cancel(with closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) { closed = closeCode }

    func send(_ message: URLSessionWebSocketTask.Message, completionHandler: @escaping @Sendable (Error?) -> Void) {
        sent.append(message)
        completionHandler(nil)
    }

    func receive(completionHandler: @escaping @Sendable (Result<URLSessionWebSocketTask.Message, Error>) -> Void) {
        completionHandler(inbox.removeFirst())
    }

    func sendPing(pongReceiveHandler: @escaping @Sendable (Error?) -> Void) { pongReceiveHandler(nil) }
}

final class StackAuditWebSocketTests: XCTestCase {
    override func setUp() async throws {
        TestSupport.startOnce()
        try await StackAuditKit.StackAudit.shared.clear()
    }

    func testRecordsConversation() async throws {
        let fake = FakeWebSocketTask()
        fake.inbox = [.success(.string(#"{"token":"abcdefghijkl9999","msg":"hi"}"#)), .success(.data(Data([1, 2])))]
        let socket = StackAuditWebSocket(task: fake)
        socket.resume()
        try await socket.send(.string("hello"))
        let first = try await socket.receive()
        _ = try await socket.receive()
        socket.cancel(with: .normalClosure, reason: nil)

        XCTAssertTrue(fake.resumed)
        XCTAssertEqual(fake.sent.count, 1)
        XCTAssertEqual(fake.closed, .normalClosure)
        if case let .string(text) = first { XCTAssertTrue(text.contains("abcdefghijkl9999")) } else { XCTFail() }

        try await StackAuditKit.StackAudit.shared.flush()
        let log = try await StackAuditKit.StackAudit.shared.exportJsonl(includeSecrets: true)
        XCTAssertTrue(log.contains("WS open ws.test/chat"), log)
        XCTAssertTrue(log.contains("WS ↑ ws.test/chat (5 B)"), log)
        XCTAssertTrue(log.contains("WS ↓ ws.test/chat"), log)
        XCTAssertTrue(log.contains("WS closed ws.test/chat 1000"), log)
        XCTAssertTrue(log.contains("token=••••mnop"), log)
    }

    func testAuditedTaskWithProtocolsNegotiatesAndRecords() async throws {
        let server = try LocalWebSocketServer()
        let url = try await server.start()
        let session = URLSession(configuration: .ephemeral)
        defer {
            session.invalidateAndCancel()
            server.stop()
        }
        let socket = session.auditedWebSocketTask(with: url, protocols: ["chat.v2", "chat.v1"])
        let native = try XCTUnwrap(socket.task as? URLSessionWebSocketTask)
        XCTAssertTrue(WebSocketCapture.isWrapped(native))
        XCTAssertEqual(native.originalRequest?.value(forHTTPHeaderField: "Sec-WebSocket-Protocol"), "chat.v2, chat.v1")

        socket.resume()
        try await socket.send(.string("hello"))
        _ = try await socket.receive()
        socket.cancel()

        XCTAssertEqual((native.response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Sec-WebSocket-Protocol"), "chat.v2")
        let frames = try await FrameLog.frames()
        XCTAssertEqual(frames.map(\.source), Array(repeating: "urlsession", count: 4))
        XCTAssertEqual(Set(frames.map(\.connectionId)), [WebSocketCapture.connectionId(for: native)])
    }

    func testFakeTasksAreNotMarkedAsWrapped() {
        let fake = FakeWebSocketTask()
        _ = StackAuditWebSocket(task: fake)
        XCTAssertFalse(WebSocketCapture.isWrapped(fake))
    }

    func testRecordsReceiveFailures() async throws {
        let fake = FakeWebSocketTask()
        fake.inbox = [.failure(URLError(.networkConnectionLost))]
        let socket = StackAuditWebSocket(task: fake)
        do {
            _ = try await socket.receive()
            XCTFail("expected failure")
        } catch {}
        try await StackAuditKit.StackAudit.shared.flush()
        let log = try await StackAuditKit.StackAudit.shared.exportJsonl(includeSecrets: true)
        XCTAssertTrue(log.contains("WS failed ws.test/chat"), log)
    }
}
