// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "AppSettings",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "AppSettings",
            targets: ["AppSettings"]
        ),
    ],
    targets: [
        .target(
            name: "AppSettings",
            resources: [
                .process("Resources")
            ]
        ),
        .testTarget(
            name: "AppSettingsTests",
            dependencies: ["AppSettings"]
        ),
    ]
)
