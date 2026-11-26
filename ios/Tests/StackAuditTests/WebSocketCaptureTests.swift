import Foundation
import StackAuditKit
import XCTest
@testable import StackAudit

struct RecordedFrame {
    let id: String
    let source: String
    let connectionId: String
    let title: String
}

enum FrameLog {
    static func frames() async throws -> [RecordedFrame] {
        try await StackAuditKit.StackAudit.shared.flush()
        let log = try await StackAuditKit.StackAudit.shared.exportJsonl(includeSecrets: true)
        return log.split(separator: "\n").compactMap { line in
            guard
                let object = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
                object["category"] as? String == "websocket"
            else { return nil }
            let payload = object["payload"] as? [String: Any]
            return RecordedFrame(
                id: object["id"] as? String ?? "",
                source: object["source"] as? String ?? "",
                connectionId: payload?["connectionId"] as? String ?? "",
                title: object["title"] as? String ?? ""
            )
        }
    }

    static func body(_ frame: RecordedFrame) async throws -> String? {
        guard
            let event = try await StackAuditKit.StackAudit.shared.event(id: frame.id, revealSecrets: false),
            let ref = (event.payload as? WebSocketPayload)?.body,
            let bytes = try await StackAuditKit.StackAudit.shared.body(ref: ref)
        else { return nil }
        let data = Data((0 ..< bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) })
        return String(decoding: data, as: UTF8.self)
    }

    static func titles(_ frames: [RecordedFrame], source: String) -> [String] {
        frames.filter { $0.source == source }.map(\.title)
    }
}

extension Array {
    func asyncMap<T>(_ transform: (Element) async throws -> T) async rethrows -> [T] {
        var results: [T] = []
        for element in self { results.append(try await transform(element)) }
        return results
    }
}

final class WebSocketCaptureTests: XCTestCase {
    private var server: LocalWebSocketServer!
    private var url: URL!
    private var session: URLSession!

    override func setUp() async throws {
        TestSupport.startOnce()
        server = try LocalWebSocketServer()
        url = try await server.start()
        session = URLSession(configuration: .ephemeral)
        try await StackAuditKit.StackAudit.shared.clear()
    }

    override func tearDown() {
        session.invalidateAndCancel()
        server.stop()
    }

    private func target() -> String { "\(url.host!):\(url.port!)/chat" }

    func testHooksEverySelectorOnTheConcreteTaskClass() {
        XCTAssertEqual(WebSocketCapture.installations, 1)
        XCTAssertEqual(
            Set(WebSocketCapture.hookedSelectors),
            ["resume", "sendMessage:completionHandler:", "receiveMessageWithCompletionHandler:", "sendPingWithPongReceiveHandler:", "cancelWithCloseCode:reason:"]
        )
        XCTAssertNotEqual(WebSocketCapture.concreteTaskClass().map(NSStringFromClass), "NSURLSessionTask")
    }

    func testCapturesPlainTaskWithCompletionHandlers() async throws {
        let task = session.webSocketTask(with: url)
        task.resume()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            task.send(.string("hello")) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
        let echoed = try await withCheckedThrowingContinuation { continuation in
            task.receive { continuation.resume(with: $0) }
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            task.sendPing { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
        task.cancel(with: .normalClosure, reason: Data("bye".utf8))

        if case let .string(text) = echoed { XCTAssertEqual(text, "hello") } else { XCTFail("expected text") }
        let frames = try await FrameLog.frames()
        let auto = frames.filter { $0.source == WebSocketCapture.source }
        XCTAssertEqual(auto.map(\.title), [
            "WS open \(target())",
            "WS ↑ \(target()) (5 B)",
            "WS ↓ \(target()) (5 B)",
            "WS ping \(target())",
            "WS pong \(target())",
            "WS closed \(target()) 1000",
        ])
        XCTAssertEqual(Set(auto.map(\.connectionId)).count, 1)
        XCTAssertEqual(auto.first?.connectionId, WebSocketCapture.connectionId(for: task))
    }

    func testCapturesTaskWithProtocolsThroughAsyncAPI() async throws {
        let task = session.webSocketTask(with: url, protocols: ["chat.v1"])
        task.resume()
        try await task.send(.data(Data([1, 2, 3])))
        let echoed = try await task.receive()
        task.cancel(with: .goingAway, reason: nil)

        if case let .data(bytes) = echoed { XCTAssertEqual(bytes, Data([1, 2, 3])) } else { XCTFail("expected data") }
        XCTAssertEqual(task.response.flatMap { ($0 as? HTTPURLResponse)?.value(forHTTPHeaderField: "Sec-WebSocket-Protocol") }, "chat.v1")
        let frames = try await FrameLog.frames()
        XCTAssertEqual(FrameLog.titles(frames, source: WebSocketCapture.source), [
            "WS open \(target())",
            "WS ↑ \(target()) (3 B)",
            "WS ↓ \(target()) (3 B)",
            "WS closed \(target()) 1001",
        ])
    }

    func testSeparateTasksGetSeparateConnectionIds() async throws {
        let first = session.webSocketTask(with: url)
        let second = session.webSocketTask(with: url)
        first.resume()
        second.resume()
        first.cancel(with: .normalClosure, reason: nil)
        second.cancel(with: .normalClosure, reason: nil)

        let frames = try await FrameLog.frames()
        let ids = frames.filter { $0.source == WebSocketCapture.source }.map(\.connectionId)
        XCTAssertEqual(ids.count, 4)
        XCTAssertEqual(Set(ids).count, 2)
    }

    func testRecordsReceiveFailures() async throws {
        let closed = try LocalWebSocketServer()
        let deadURL = try await closed.start()
        closed.stop()
        let task = session.webSocketTask(with: deadURL)
        task.resume()
        do {
            _ = try await task.receive()
            XCTFail("expected failure")
        } catch {}

        let frames = try await FrameLog.frames()
        let titles = FrameLog.titles(frames, source: WebSocketCapture.source)
        XCTAssertEqual(titles.first, "WS open \(deadURL.host!):\(deadURL.port!)/chat")
        XCTAssertTrue(titles.contains("WS failed \(deadURL.host!):\(deadURL.port!)/chat"), "\(titles)")
    }

    func testWrappedTaskIsRecordedExactlyOnceWithCompletionHandlers() async throws {
        let socket = session.auditedWebSocketTask(with: url, protocols: ["chat.v1"])
        socket.resume()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            socket.send(.string("hello")) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
        _ = try await withCheckedThrowingContinuation { continuation in
            socket.receive { continuation.resume(with: $0) }
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            socket.sendPing { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
        socket.cancel()

        let frames = try await FrameLog.frames()
        XCTAssertEqual(FrameLog.titles(frames, source: WebSocketCapture.source), [])
        XCTAssertEqual(FrameLog.titles(frames, source: "urlsession"), [
            "WS open \(target())",
            "WS ↑ \(target()) (5 B)",
            "WS ↓ \(target()) (5 B)",
            "WS ping \(target())",
            "WS pong \(target())",
            "WS closed \(target()) 1000",
        ])
        XCTAssertEqual(Set(frames.map(\.connectionId)).count, 1)
    }

    func testWrappedTaskIsRecordedExactlyOnceWithAsyncAPI() async throws {
        let socket = session.auditedWebSocketTask(with: url)
        socket.resume()
        try await socket.send(.string("hi"))
        _ = try await socket.receive()
        socket.cancel()

        let frames = try await FrameLog.frames()
        XCTAssertEqual(frames.count, 4)
        XCTAssertTrue(frames.allSatisfy { $0.source == "urlsession" })
    }

    func testBothPathsProduceIdenticalEvents() async throws {
        let plain = session.webSocketTask(with: url)
        plain.resume()
        try await plain.send(.string("same"))
        _ = try await plain.receive()
        plain.cancel(with: .normalClosure, reason: nil)

        let wrapped = session.auditedWebSocketTask(with: url)
        wrapped.resume()
        try await wrapped.send(.string("same"))
        _ = try await wrapped.receive()
        wrapped.cancel()

        let frames = try await FrameLog.frames()
        XCTAssertEqual(FrameLog.titles(frames, source: WebSocketCapture.source), FrameLog.titles(frames, source: "urlsession"))
    }

    func testTextFramesAreMaskedOnWrite() async throws {
        let task = session.webSocketTask(with: url)
        task.resume()
        try await task.send(.string(#"{"token":"abcdefghijkl9999","msg":"hi"}"#))
        _ = try await task.receive()
        task.cancel(with: .normalClosure, reason: nil)

        let frames = try await FrameLog.frames()
        let bodies = try await frames.asyncMap(FrameLog.body)
        XCTAssertEqual(bodies.count, 4)
        for body in bodies.compactMap({ $0 }) {
            XCTAssertFalse(body.contains("abcdefghijkl9999"), body)
            XCTAssertTrue(body.contains("••••9999"), body)
        }
        XCTAssertEqual(bodies.compactMap { $0 }.count, 2)
    }

    func testPausedCaptureRecordsNothing() async throws {
        StackAuditKit.StackAudit.shared.setRecording(category: .webSocket, enabled: false)
        defer { StackAuditKit.StackAudit.shared.setRecording(category: .webSocket, enabled: true) }
        let task = session.webSocketTask(with: url)
        task.resume()
        task.cancel(with: .normalClosure, reason: nil)

        let frames = try await FrameLog.frames()
        XCTAssertTrue(frames.isEmpty)
    }

    func testManualModeInstallsNoHooks() {
        var http = 0
        var webSocket = 0
        StackAudit.installCaptures(for: .manual, http: { http += 1 }, webSocket: { webSocket += 1 })
        XCTAssertEqual(http, 0)
        XCTAssertEqual(webSocket, 0)
    }

    func testAutomaticModeInstallsHooks() {
        var http = 0
        var webSocket = 0
        StackAudit.installCaptures(for: .automatic, http: { http += 1 }, webSocket: { webSocket += 1 })
        XCTAssertEqual(http, 1)
        XCTAssertEqual(webSocket, 1)
    }

    func testDoubleStartInstallsHooksOnce() throws {
        var configuration = StackAudit.Configuration()
        configuration.shakeToOpen = false
        try StackAudit.start(configuration)
        try StackAudit.start(configuration)
        WebSocketCapture.enable()
        XCTAssertEqual(WebSocketCapture.installations, 1)
        XCTAssertEqual(WebSocketCapture.hookedSelectors.count, Set(WebSocketCapture.hookedSelectors).count)
    }
}
