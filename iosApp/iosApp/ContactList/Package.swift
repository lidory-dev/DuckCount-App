// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "ContactList",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "ContactList",
            targets: ["ContactList"]
        ),
    ],
    dependencies: [
        .package(path: "../AppFonts")
    ],
    targets: [
        .target(
            name: "ContactList",
            dependencies: [
                .product(name: "AppFonts", package: "AppFonts")
            ],
            resources: [
                .process("Resources")
            ]
        ),
        .testTarget(
            name: "ContactListTests",
            dependencies: ["ContactList"]
        ),
    ]
)
