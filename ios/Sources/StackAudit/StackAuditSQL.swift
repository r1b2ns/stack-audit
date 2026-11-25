import Foundation
import SQLite3
import StackAuditKit

public enum StackAuditSQL {
    public static func record(
        database: String,
        sql: String,
        arguments: [Any?] = [],
        durationMs: Int64? = nil,
        rowsAffected: Int64? = nil,
        error: Error? = nil,
        source: String = "sqlite"
    ) {
        guard StackAuditBridge.shared.isCapturingDatabase else { return }
        StackAuditBridge.shared.recordDatabase(
            source: source,
            database: database,
            sql: sql,
            arguments: arguments.map { $0.map(describe) },
            durationMs: durationMs ?? -1,
            rowsAffected: rowsAffected ?? -1,
            error: error.map { "\(type(of: $0)): \($0.localizedDescription)" },
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    @discardableResult
    public static func trace(_ db: OpaquePointer, name: String) -> Bool {
        let context = Unmanaged.passRetained(TraceContext(name: name)).toOpaque()
        let status = sqlite3_trace_v2(db, UInt32(SQLITE_TRACE_PROFILE), { _, context, statement, elapsed in
            guard let context, let statement, let elapsed else { return 0 }
            let name = Unmanaged<TraceContext>.fromOpaque(context).takeUnretainedValue().name
            let stmt = OpaquePointer(statement)
            guard let sql = sqlite3_sql(stmt).map({ String(cString: $0) }) else { return 0 }
            let nanos = elapsed.assumingMemoryBound(to: Int64.self).pointee
            StackAuditSQL.record(database: name, sql: sql, durationMs: nanos / 1_000_000, source: "sqlite3")
            return 0
        }, context)
        return status == SQLITE_OK
    }

    private final class TraceContext {
        let name: String

        init(name: String) {
            self.name = name
        }
    }

    static func describe(_ value: Any) -> String {
        switch value {
        case let data as Data: return "<\(data.count) bytes>"
        default: return String(describing: value)
        }
    }
}
