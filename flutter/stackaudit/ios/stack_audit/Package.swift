// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "stack_audit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "stack-audit", targets: ["stack_audit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/r1b2ns/stack-audit.git", from: "0.4.1"),
        .package(name: "FlutterFramework", path: "../FlutterFramework"),
    ],
    targets: [
        .target(
            name: "stack_audit",
            dependencies: [
                .product(name: "StackAudit", package: "stack-audit"),
                .product(name: "FlutterFramework", package: "FlutterFramework"),
            ]
        ),
    ]
)
