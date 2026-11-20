// swift-tools-version: 6.2
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "ExpenseList",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v18)
    ],
    products: [
        .library(
            name: "ExpenseList",
            targets: ["ExpenseList"]
        ),
    ],
    dependencies: [
        .package(path: "../AppFonts"),
        .package(path: "../AppColors")
    ],
    targets: [
        .target(
            name: "ExpenseList",
            dependencies: [
                .product(name: "AppFonts", package: "AppFonts"),
                .product(name: "AppColors", package: "AppColors")
            ],
            resources: [
                .process("Resources")
            ]
        ),
        .testTarget(
            name: "ExpenseListTests",
            dependencies: ["ExpenseList"]
        ),
    ]
)
