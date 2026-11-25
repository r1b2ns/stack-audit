import Alamofire
import Foundation
import StackAudit

enum DemoClient: String, CaseIterable {
    case urlSession = "URLSession"
    case alamofire = "Alamofire"
    case pinned = "Alamofire + pinning"
    case webSocket = "URLSession WebSocket"
}

struct Scenario: Identifiable, Hashable {
    let title: String
    let client: DemoClient
    let method: String
    let url: String
    var body: String? = nil
    var headers: [String: String] = [:]

    var id: String { title }
}

enum Scenarios {
    static let bin = "https://httpbin.org"

    static let all: [Scenario] = [
        Scenario(title: "GET JSON", client: .urlSession, method: "GET", url: "https://jsonplaceholder.typicode.com/posts/1"),
        Scenario(
            title: "POST login with secrets",
            client: .urlSession,
            method: "POST",
            url: "\(bin)/post?token=abcdefghijklmnop",
            body: #"{"user":"ana","password":"hunter2","access_token":"abcdefghijkl1234"}"#,
            headers: ["Authorization": "Bearer abcdefghijkl1234", "X-Session": "session-123", "Content-Type": "application/json"]
        ),
        Scenario(title: "Redirect 302", client: .urlSession, method: "GET", url: "\(bin)/redirect-to?url=%2Fget"),
        Scenario(title: "Not found 404", client: .urlSession, method: "GET", url: "\(bin)/status/404"),
        Scenario(title: "Server error 500", client: .urlSession, method: "GET", url: "\(bin)/status/500"),
        Scenario(title: "Slow 2 s", client: .urlSession, method: "GET", url: "\(bin)/delay/2"),
        Scenario(title: "Large body 2 MB", client: .urlSession, method: "GET", url: "https://speed.cloudflare.com/__down?bytes=2000000"),
        Scenario(title: "Image", client: .urlSession, method: "GET", url: "\(bin)/image/png"),
        Scenario(title: "Unknown host", client: .urlSession, method: "GET", url: "https://does-not-exist.stackaudit.invalid/"),
        Scenario(title: "Alamofire GET", client: .alamofire, method: "GET", url: "\(bin)/get?source=alamofire"),
        Scenario(
            title: "Alamofire POST",
            client: .alamofire,
            method: "POST",
            url: "\(bin)/post",
            body: #"{"client_secret":"abcdefghijkl5678"}"#,
            headers: ["Content-Type": "application/json"]
        ),
        Scenario(title: "Pinning rejects (expected failure)", client: .pinned, method: "GET", url: "\(bin)/get?source=pinning"),
        Scenario(title: "WebSocket echo", client: .webSocket, method: "WS", url: "wss://echo.websocket.org/?token=abcdefghijklmnop"),
    ]
}

final class DemoRunner {
    private let session = URLSession(configuration: .default)
    private let alamofire = Session()
    private let pinned = Session(
        serverTrustManager: ServerTrustManager(evaluators: ["httpbin.org": PinnedCertificatesTrustEvaluator(certificates: [])])
    )

    func run(_ scenario: Scenario) async -> String {
        guard let url = URL(string: scenario.url) else { return "Invalid URL" }
        var request = URLRequest(url: url)
        request.httpMethod = scenario.method
        request.httpBody = scenario.body.map { Data($0.utf8) }
        scenario.headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
        switch scenario.client {
        case .urlSession:
            do {
                let (data, response) = try await session.data(for: request)
                return "\((response as? HTTPURLResponse)?.statusCode ?? 0) · \(data.count) bytes"
            } catch {
                return "Failed: \(error.localizedDescription)"
            }
        case .alamofire:
            return await run(alamofire, request)
        case .pinned:
            return await run(pinned, request)
        case .webSocket:
            return await echo(url)
        }
    }

    private func echo(_ url: URL) async -> String {
        let socket = session.auditedWebSocketTask(with: url)
        socket.resume()
        defer { socket.cancel(with: .normalClosure, reason: nil) }
        do {
            _ = try await socket.receive()
            try await socket.send(.string(#"{"type":"chat","text":"hello","password":"hunter2"}"#))
            try await socket.send(.data(Data([1, 2, 3])))
            _ = try await socket.receive()
            _ = try await socket.receive()
            return "echo received"
        } catch {
            return "Failed: \(error.localizedDescription)"
        }
    }

    private func run(_ session: Session, _ request: URLRequest) async -> String {
        let response = await session.request(request).serializingData().response
        switch response.result {
        case let .success(data):
            return "\(response.response?.statusCode ?? 0) · \(data.count) bytes"
        case let .failure(error):
            return "Failed: \(error.localizedDescription)"
        }
    }
}
