// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "stackaudit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "stackaudit", targets: ["stackaudit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/r1b2ns/stack-audit.git", from: "0.1.0"),
    ],
    targets: [
        .target(
            name: "stackaudit",
            dependencies: [.product(name: "StackAudit", package: "stack-audit")]
        ),
    ]
)
