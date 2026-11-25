// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "StackAudit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "StackAudit", targets: ["StackAudit"]),
    ],
    targets: [
        .binaryTarget(
            name: "StackAuditKit",
            path: "stackaudit-apple/build/XCFrameworks/debug/StackAuditKit.xcframework"
        ),
        .target(
            name: "StackAudit",
            dependencies: ["StackAuditKit"],
            path: "ios/Sources/StackAudit",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .testTarget(
            name: "StackAuditTests",
            dependencies: ["StackAudit"],
            path: "ios/Tests/StackAuditTests"
        ),
    ]
)
