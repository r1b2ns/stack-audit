import Foundation
import StackAuditKit
import XCTest
@testable import StackAudit

private struct DictionaryProvider: StackAuditFlagProvider {
    let values: [String: Any]

    func value(forKey key: String) -> Any? { values[key] }
}

final class InsightsTests: XCTestCase {
    override func setUp() async throws {
        TestSupport.startOnce()
        try await StackAuditKit.StackAudit.shared.clear()
        StackAuditKit.StackAudit.shared.flags.clearOverrides()
    }

    override func tearDown() async throws {
        try StackAuditAnalytics.setTrackingPlan(json: nil)
    }

    private func log() async throws -> String {
        try await StackAuditKit.StackAudit.shared.flush()
        return try await StackAuditKit.StackAudit.shared.exportJsonl(includeSecrets: true)
    }

    func testAnalyticsAreValidatedAgainstThePlan() async throws {
        try StackAuditAnalytics.setTrackingPlan(json: #"{"events":{"purchase":{"required":["value"],"types":{"value":"number","vip":"boolean"}}}}"#)
        StackAuditAnalytics.record(provider: "firebase", name: "purchase", params: ["value": 9.9, "vip": true])
        StackAuditAnalytics.record(provider: "firebase", name: "purchase", params: ["value": "9.9"])
        StackAuditAnalytics.record(provider: "firebase", name: "login")
        let text = try await log()
        XCTAssertTrue(text.contains(#""title":"firebase: purchase""#), text)
        XCTAssertTrue(text.contains("firebase: purchase (invalid)"), text)
        XCTAssertTrue(text.contains("firebase: login (not in plan)"), text)
    }

    func testInvalidPlanThrows() {
        XCTAssertThrowsError(try StackAuditAnalytics.setTrackingPlan(json: "{not json"))
    }

    func testFlagsResolveRemoteAndOverrides() async throws {
        let flags = StackAuditFlags(provider: DictionaryProvider(values: ["checkout": true, "limit": 3, "title": "Hi"]), name: "remote")
        XCTAssertTrue(flags.bool("checkout", default: false))
        XCTAssertEqual(flags.number("limit", default: 1), 3)
        XCTAssertEqual(flags.string("title", default: ""), "Hi")
        XCTAssertEqual(flags.string("missing", default: "x"), "x")
        StackAuditKit.StackAudit.shared.flags.override(key: "checkout", value: FlagValueBool(value: false))
        XCTAssertFalse(flags.bool("checkout", default: true))
        let text = try await log()
        XCTAssertTrue(text.contains("flag checkout = false (override)"), text)
    }
}
