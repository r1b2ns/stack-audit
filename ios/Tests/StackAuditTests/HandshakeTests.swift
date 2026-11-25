import Foundation
import XCTest
@testable import StackAudit

final class HandshakeTests: XCTestCase {
    override func setUp() {
        TestSupport.startOnce()
    }

    func testOpenAccessRejectsTokens() {
        XCTAssertEqual(StackAudit.handshake(token: "a.b"), .rejected("access is not Access.Handshake"))
    }

    func testHandleIgnoresOtherLinks() {
        XCTAssertFalse(StackAudit.handle(url: URL(string: "myapp://home")!))
        XCTAssertFalse(StackAudit.handle(url: URL(string: "myapp://stackaudit/open?token=bad")!))
    }

    func testDeviceIdIsStable() {
        let first = StackAudit.deviceId
        XCTAssertNotNil(first)
        XCTAssertEqual(first, StackAudit.deviceId)
    }

    func testHandshakeOptionsCarryAppId() {
        var configuration = StackAudit.Configuration()
        configuration.access = .handshake(publicKey: "key", sessionTTL: 60, appId: "com.example")
        let options = StackAudit.options(from: configuration)
        XCTAssertEqual(options.appId, "com.example")
        XCTAssertEqual(options.sessionTtlSeconds, 60)
    }
}
