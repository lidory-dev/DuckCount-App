// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

/// What both the app and its notification service extension read of a push: the keys its payload is
/// encrypted to, who may wake the user, and the signal itself. Foundation, CryptoKit and Security
/// only, the extension running in a few megabytes with no Kotlin in it.
let package = Package(
    name: "PushSignals",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "PushSignals",
            targets: ["PushSignals"]
        ),
    ],
    targets: [
        .target(
            name: "PushSignals"
        ),
        .testTarget(
            name: "PushSignalsTests",
            dependencies: ["PushSignals"]
        ),
    ]
)
