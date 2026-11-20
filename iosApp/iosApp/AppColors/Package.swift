// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

/// The app's colours, Android's Material scheme from `Color.kt`, for the app and every feature package.
let package = Package(
    name: "AppColors",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "AppColors",
            targets: ["AppColors"]
        ),
    ],
    targets: [
        .target(
            name: "AppColors"
        ),
        .testTarget(
            name: "AppColorsTests",
            dependencies: ["AppColors"]
        ),
    ]
)
