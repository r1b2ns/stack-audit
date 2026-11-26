// swift-tools-version:5.9
import Foundation
import PackageDescription

let releaseVersion = "0.2.0"
let releaseChecksum = "c0908b1549908002801d06de8229aa5e0cb2a0c3975d583d41c166122865f86b"

let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let localKit = "stackaudit-apple/build/XCFrameworks/debug/StackAuditKit.xcframework"
let useLocalKit = releaseChecksum.isEmpty || FileManager.default.fileExists(atPath: "\(packageRoot)/\(localKit)")

let kit: Target =
    useLocalKit
        ? .binaryTarget(name: "StackAuditKit", path: localKit)
        : .binaryTarget(
            name: "StackAuditKit",
            url: "https://github.com/r1b2ns/stack-audit/releases/download/v\(releaseVersion)/StackAuditKit.xcframework.zip",
            checksum: releaseChecksum
        )

let package = Package(
    name: "StackAudit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "StackAudit", targets: ["StackAudit"]),
    ],
    targets: [
        kit,
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
