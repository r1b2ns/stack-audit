import Foundation
import StackAuditKit
import CryptoKit
import XCTest
@testable import StackAudit

final class MockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    static var received: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.received.append(request)
        do {
            guard let handler = Self.handler else { throw URLError(.badServerResponse) }
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

final class StackAuditTests: XCTestCase {
    private static var started = false

    override func setUp() async throws {
        if !Self.started {
            Self.started = true
            StackAudit.start { $0.shakeToOpen = false }
        }
        StackAuditURLProtocol.innerProtocolClasses = [MockURLProtocol.self]
        MockURLProtocol.received = []
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 201,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json", "Set-Cookie": "sid=1"]
            )!
            return (response, Data(#"{"ok":true,"access_token":"zzzzzzzzzzzz9999"}"#.utf8))
        }
        try await StackAuditKit.StackAudit.shared.clear()
    }

    private func exported() async throws -> String {
        try await StackAuditKit.StackAudit.shared.flush()
        return try await StackAuditKit.StackAudit.shared.exportJsonl(includeSecrets: true)
    }

    private func har() async throws -> String {
        try await StackAuditKit.StackAudit.shared.flush()
        return try await StackAuditKit.StackAudit.shared.exportHar(includeSecrets: true)
    }

    func testDefaultConfigurationIsCapturedAutomatically() async throws {
        let session = URLSession(configuration: .default)
        var request = URLRequest(url: URL(string: "https://api.test/items?token=abcdefghijklmnop")!)
        request.setValue("Bearer abcdefghijkl1234", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)

        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 201)
        XCTAssertEqual(String(decoding: data, as: UTF8.self), #"{"ok":true,"access_token":"zzzzzzzzzzzz9999"}"#)
        XCTAssertEqual(MockURLProtocol.received.count, 1)

        let log = try await exported()
        XCTAssertTrue(log.contains("https://api.test/items?token=••••mnop"), log)
        XCTAssertTrue(log.contains("Bearer ••••1234"), log)
        XCTAssertFalse(log.contains("abcdefghijkl1234"))
        XCTAssertTrue(log.contains(#""source":"urlsession""#))
        XCTAssertTrue(log.contains(#""status":201"#))
    }

    func testResponseBodyIsStoredMasked() async throws {
        let session = URLSession(configuration: .ephemeral)
        _ = try await session.data(from: URL(string: "https://api.test/token")!)
        let har = try await har()
        XCTAssertTrue(har.contains("••••9999"), har)
        XCTAssertFalse(har.contains("zzzzzzzzzzzz9999"))
    }

    func testPostBodyIsCaptured() async throws {
        let session = URLSession(configuration: .default)
        var request = URLRequest(url: URL(string: "https://api.test/login")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(#"{"user":"ana","password":"hunter2"}"#.utf8)
        _ = try await session.data(for: request)

        let har = try await har()
        XCTAssertTrue(har.contains("ana"), har)
        XCTAssertFalse(har.contains("hunter2"))
        XCTAssertEqual(MockURLProtocol.received.count, 1)
    }

    func testManualConfigurationWithAudited() async throws {
        let manual = URLSessionConfiguration.default
        manual.protocolClasses = []
        manual.audited()
        manual.audited()
        XCTAssertEqual(manual.protocolClasses?.filter { $0 == StackAuditURLProtocol.self }.count, 1)
        _ = try await URLSession(configuration: manual).data(from: URL(string: "https://api.test/manual")!)
        let log = try await exported()
        XCTAssertTrue(log.contains("api.test/manual"), log)
    }

    func testFailuresAreRecordedAndPropagated() async throws {
        MockURLProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }
        let session = URLSession(configuration: .default)
        do {
            _ = try await session.data(from: URL(string: "https://api.test/offline")!)
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet)
        }
        let log = try await exported()
        XCTAssertTrue(log.contains("api.test/offline"), log)
        XCTAssertTrue(log.contains(#""status":null"#), log)
    }

    func testNonHttpSchemesAreIgnored() {
        XCTAssertFalse(StackAuditURLProtocol.canInit(with: URLRequest(url: URL(string: "file:///tmp/a")!)))
        XCTAssertTrue(StackAuditURLProtocol.canInit(with: URLRequest(url: URL(string: "https://api.test")!)))
    }

    func testInvalidCombinationIsRejected() {
        var configuration = StackAudit.Configuration()
        configuration.secrets = .showAfterHandshake()
        XCTAssertThrowsError(try StackAuditBridge.shared.config(options: StackAudit.options(from: configuration)))
        configuration.access = .handshake(publicKey: "key")
        XCTAssertNoThrow(try StackAuditBridge.shared.config(options: StackAudit.options(from: configuration)))
    }

    func testOptionsMapping() {
        var configuration = StackAudit.Configuration()
        configuration.secrets = .mask(extraHeaders: ["X-Session"], extraFields: ["cpf"], allow: ["cookie"])
        configuration.access = .hidden
        configuration.maxBodySize = 10
        let options = StackAudit.options(from: configuration)
        XCTAssertEqual(options.extraHeaders, ["X-Session"])
        XCTAssertEqual(options.extraFields, ["cpf"])
        XCTAssertEqual(options.allowed, ["cookie"])
        XCTAssertEqual(options.maxBodySize, 10)
        XCTAssertEqual(options.access, .hidden)
    }
}

final class KeychainCipherTests: XCTestCase {
    func testSealAndOpenRoundTrip() {
        let cipher = KeychainCipher(key: .init(size: .bits256))
        let plain = Data("secret body".utf8)
        let sealed = cipher.seal(data: plain)
        XCTAssertNotEqual(sealed, plain)
        XCTAssertEqual(cipher.open(data: sealed), plain)
    }

    func testOpenRejectsTamperedData() {
        let cipher = KeychainCipher(key: .init(size: .bits256))
        var sealed = cipher.seal(data: Data("abc".utf8))
        sealed[sealed.count - 1] ^= 0xFF
        XCTAssertEqual(cipher.open(data: sealed), Data())
    }

    func testKeychainKeyIsReused() throws {
        try XCTSkipUnless(KeychainCipher.isKeychainAvailable, "keychain needs a host app entitlement")
        let first = KeychainCipher(service: "io.github.r1b2ns.stackaudit.tests", account: "k")
        let second = KeychainCipher(service: "io.github.r1b2ns.stackaudit.tests", account: "k")
        XCTAssertEqual(second.open(data: first.seal(data: Data("x".utf8))), Data("x".utf8))
    }
}
