import XCTest
@testable import StackAudit

final class ChannelTests: XCTestCase {
    override func setUp() {
        TestSupport.startOnce()
    }

    func testBuildsConfigurationFromDartOptions() throws {
        let configuration = try StackAuditChannel.configuration(from: [
            "secrets": "showAfterHandshake",
            "extraHeaders": ["X-Session"],
            "access": "handshake",
            "publicKey": "key",
            "sessionTtlSeconds": 600,
            "appId": "com.example",
            "maxBodySize": 10,
            "captureNativeNetwork": false,
        ])
        XCTAssertEqual(configuration.secrets, .showAfterHandshake(extraHeaders: ["X-Session"]))
        XCTAssertEqual(configuration.access, .handshake(publicKey: "key", sessionTTL: 600, appId: "com.example"))
        XCTAssertEqual(configuration.maxBodySize, 10)
        XCTAssertEqual(configuration.network, .manual)
        XCTAssertEqual(try StackAuditChannel.configuration(from: [:]).secrets, .mask())
    }

    func testRejectsInvalidOptions() {
        XCTAssertThrowsError(try StackAuditChannel.configuration(from: ["access": "handshake"]))
        XCTAssertThrowsError(try StackAuditChannel.configuration(from: ["secrets": "loud"]))
    }

    func testRecordsAndAnswersCalls() throws {
        XCTAssertNoThrow(try StackAuditChannel.handle(method: "recordHttp", arguments: [
            "method": "GET",
            "url": "https://api.example.com/items",
            "status": 200,
            "responseBody": Data("ok".utf8),
        ]))
        XCTAssertNoThrow(try StackAuditChannel.handle(method: "recordStorage", arguments: ["store": "prefs", "key": "a", "operation": "write", "newValue": "1"]))
        XCTAssertNoThrow(try StackAuditChannel.handle(method: "recordAnalytics", arguments: ["provider": "firebase", "name": "login", "params": ["method": "email"]]))
        XCTAssertEqual(try StackAuditChannel.handle(method: "isCapturing", arguments: [:]) as? Bool, true)
        XCTAssertEqual(try StackAuditChannel.handle(method: "handshake", arguments: ["token": "a.b"]) as? String, "access is not Access.Handshake")
    }

    func testResolvesFlagsWithTheRemoteValue() throws {
        XCTAssertEqual(try StackAuditChannel.handle(method: "flag", arguments: ["key": "channel_bool", "kind": "bool", "value": true, "default": false]) as? Bool, true)
        XCTAssertEqual(try StackAuditChannel.handle(method: "flag", arguments: ["key": "channel_text", "kind": "string", "value": nil, "default": "x"]) as? String, "x")
        XCTAssertThrowsError(try StackAuditChannel.handle(method: "flag", arguments: ["key": "k", "kind": "bool", "default": "no"]))
    }

    func testRejectsUnknownMethodsAndMissingArguments() {
        XCTAssertThrowsError(try StackAuditChannel.handle(method: "explode", arguments: [:])) { error in
            XCTAssertEqual(error as? StackAuditChannel.ChannelError, .notImplemented("explode"))
        }
        XCTAssertThrowsError(try StackAuditChannel.handle(method: "recordHttp", arguments: ["method": "GET"]))
        XCTAssertThrowsError(try StackAuditChannel.handle(method: "recordStorage", arguments: ["store": "s", "operation": "nope"]))
    }
}
