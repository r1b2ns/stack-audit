import Foundation
import StackAuditKit

final class StackAuditURLProtocol: URLProtocol, URLSessionDataDelegate {
    static let handledKey = "io.github.r1b2ns.stackaudit.handled"
    static var innerProtocolClasses: [AnyClass] = []

    private var session: URLSession?
    private var dataTask: URLSessionDataTask?
    private var response: URLResponse?
    private var received = Data()
    private var receivedTotal: Int64 = 0
    private var sentBody: Data?
    private var startedAt = Date()
    private var limit: Int64 = 0

    override class func canInit(with request: URLRequest) -> Bool {
        guard StackAuditBridge.shared.isCapturing else { return false }
        guard let scheme = request.url?.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return false }
        return URLProtocol.property(forKey: handledKey, in: request) == nil
    }

    override class func canInit(with task: URLSessionTask) -> Bool {
        if #available(iOS 13.0, *), task is URLSessionWebSocketTask { return false }
        guard let request = task.currentRequest ?? task.originalRequest else { return false }
        return canInit(with: request)
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        startedAt = Date()
        limit = StackAuditBridge.shared.maxBodySize
        guard let mutable = (request as NSURLRequest).mutableCopy() as? NSMutableURLRequest else { return }
        URLProtocol.setProperty(true, forKey: Self.handledKey, in: mutable)
        if let body = request.httpBody {
            sentBody = body
        } else if let stream = request.httpBodyStream {
            let body = Self.read(stream)
            sentBody = body
            mutable.httpBodyStream = nil
            mutable.httpBody = body
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = Self.innerProtocolClasses + (configuration.protocolClasses ?? []).filter { $0 != StackAuditURLProtocol.self }
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.dataTask(with: mutable as URLRequest)
        self.dataTask = task
        task.resume()
    }

    override func stopLoading() {
        dataTask?.cancel()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        self.response = response
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        receivedTotal += Int64(data.count)
        if Int64(received.count) <= limit {
            received.append(data)
        }
        client?.urlProtocol(self, didLoad: data)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard let client else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        let sender = ChallengeSender(completionHandler)
        let forwarded = URLAuthenticationChallenge(authenticationChallenge: challenge, sender: sender)
        client.urlProtocol(self, didReceive: forwarded)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        record(error: error, finalURL: task.currentRequest?.url)
        if let error {
            client?.urlProtocol(self, didFailWithError: error)
        } else {
            client?.urlProtocolDidFinishLoading(self)
        }
        session.finishTasksAndInvalidate()
        self.session = nil
    }

    private func record(error: Error?, finalURL: URL?) {
        let http = response as? HTTPURLResponse
        let responseHeaders = (http?.allHeaderFields ?? [:]).reduce(into: [String: String]()) { result, item in
            result[String(describing: item.key)] = String(describing: item.value)
        }
        let body: Data? = Int64(received.count) <= limit && receivedTotal > 0 ? received : nil
        let requestBody: Data? = sentBody.flatMap { Int64($0.count) <= limit ? $0 : nil }
        StackAuditBridge.shared.recordHttp(
            source: "urlsession",
            method: request.httpMethod ?? "GET",
            url: (finalURL ?? request.url)?.absoluteString ?? "",
            requestHeaders: request.allHTTPHeaderFields ?? [:],
            requestBody: requestBody,
            status: Int32(http?.statusCode ?? 0),
            responseHeaders: responseHeaders,
            responseBody: body,
            startedAtMs: Int64(startedAt.timeIntervalSince1970 * 1000),
            durationMs: Int64(Date().timeIntervalSince(startedAt) * 1000),
            error: error.map { "\(type(of: $0)): \($0.localizedDescription)" },
            requestBodySize: Int64(sentBody?.count ?? -1),
            responseBodySize: response == nil ? -1 : receivedTotal
        )
    }

    private static func read(_ stream: InputStream) -> Data {
        var data = Data()
        stream.open()
        defer { stream.close() }
        let size = 16 * 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: size)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let count = stream.read(buffer, maxLength: size)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private final class ChallengeSender: NSObject, URLAuthenticationChallengeSender {
    private let handler: (URLSession.AuthChallengeDisposition, URLCredential?) -> Void

    init(_ handler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        self.handler = handler
    }

    func use(_ credential: URLCredential, for challenge: URLAuthenticationChallenge) {
        handler(.useCredential, credential)
    }

    func continueWithoutCredential(for challenge: URLAuthenticationChallenge) {
        handler(.useCredential, nil)
    }

    func cancel(_ challenge: URLAuthenticationChallenge) {
        handler(.cancelAuthenticationChallenge, nil)
    }

    func performDefaultHandling(for challenge: URLAuthenticationChallenge) {
        handler(.performDefaultHandling, nil)
    }

    func rejectProtectionSpaceAndContinue(with challenge: URLAuthenticationChallenge) {
        handler(.rejectProtectionSpace, nil)
    }
}
