// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "GroupList",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "GroupList",
            targets: ["GroupList"]
        ),
    ],
    dependencies: [
        .package(path: "../AppFonts")
    ],
    targets: [
        .target(
            name: "GroupList",
            dependencies: [
                .product(name: "AppFonts", package: "AppFonts")
            ],
            resources: [
                .process("Resources")
            ]
        ),
        .testTarget(
            name: "GroupListTests",
            dependencies: ["GroupList"]
        ),
    ]
)
