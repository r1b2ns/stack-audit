import Foundation
import SQLite3
import StackAuditKit
import XCTest
@testable import StackAudit

final class StorageTests: XCTestCase {
    override func setUp() async throws {
        TestSupport.startOnce()
        try await StackAuditKit.StackAudit.shared.clear()
    }

    private func log() async throws -> String {
        try await StackAuditKit.StackAudit.shared.flush()
        return try await StackAuditKit.StackAudit.shared.exportJsonl(includeSecrets: true)
    }

    func testUserDefaultsChangesAreDiffed() async throws {
        let suite = "io.github.r1b2ns.stackaudit.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defaults.set("light", forKey: "theme")
        let watcher = UserDefaultsWatcher(defaults: defaults, name: "test-suite", domain: suite)
        watcher.start()
        defaults.set("dark", forKey: "theme")
        defaults.set("abcdefghijkl9999", forKey: "access_token")
        defaults.set(true, forKey: "com.apple.something")
        defaults.removeObject(forKey: "theme")
        watcher.check()
        watcher.stop()
        defaults.removePersistentDomain(forName: suite)

        let text = try await log()
        XCTAssertTrue(text.contains("test-suite set theme"), text)
        XCTAssertTrue(text.contains("test-suite remove theme"), text)
        XCTAssertTrue(text.contains("••••9999"), text)
        XCTAssertFalse(text.contains("abcdefghijkl9999"))
        XCTAssertFalse(text.contains("com.apple.something"))
    }

    func testDescribeFormatsValues() {
        XCTAssertEqual(UserDefaultsWatcher.describe(Data([1, 2, 3])), "<3 bytes>")
        XCTAssertEqual(UserDefaultsWatcher.describe(42), "42")
    }

    func testManualSqlRecordMasksSecretColumns() async throws {
        StackAuditSQL.record(database: "app.sqlite", sql: "UPDATE user SET password = ? WHERE id = ?", arguments: ["hunter2", 7], durationMs: 2, rowsAffected: 1)
        let text = try await log()
        XCTAssertTrue(text.contains("app.sqlite: UPDATE user SET password = ? WHERE id = ?"), text)
        XCTAssertTrue(text.contains(#""arguments":["••••","7"]"#), text)
    }

    func testSqliteTraceRecordsStatements() async throws {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(":memory:", &db), SQLITE_OK)
        XCTAssertTrue(StackAuditSQL.trace(db!, name: "memory.db"))
        XCTAssertEqual(sqlite3_exec(db, "CREATE TABLE t(a INTEGER); INSERT INTO t VALUES (1);", nil, nil, nil), SQLITE_OK)
        sqlite3_close(db)
        let text = try await log()
        XCTAssertTrue(text.contains("memory.db: CREATE TABLE t(a INTEGER)"), text)
        XCTAssertTrue(text.contains("memory.db: INSERT INTO t VALUES (1)"), text)
    }
}
