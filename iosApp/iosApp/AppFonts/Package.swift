// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

/// The app's typefaces, as Android's `Type.kt` sets them, for the app and every feature package.
let package = Package(
    name: "AppFonts",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "AppFonts",
            targets: ["AppFonts"]
        ),
    ],
    targets: [
        .target(
            name: "AppFonts",
            resources: [
                .copy("Fonts")
            ]
        ),
        .testTarget(
            name: "AppFontsTests",
            dependencies: ["AppFonts"]
        ),
    ]
)
