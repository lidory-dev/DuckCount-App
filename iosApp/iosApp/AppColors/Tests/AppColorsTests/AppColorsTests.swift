import Foundation
import SwiftUI
import Testing
import UIKit

@testable import AppColors

/// The app's colours, held to Android's own files, so that the two schemes never drift apart.
struct AppColorsTests {
    /// The repository's root, from this file's place in it.
    private static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // AppColorsTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // AppColors
        .deletingLastPathComponent() // iosApp/iosApp
        .deletingLastPathComponent() // iosApp
        .deletingLastPathComponent()

    private static let theme = root.appending(path: "android-ui/src/androidMain/kotlin/app/lidory/duckcount/android/ui/theme")

    @Test func everyRoleOfAndroidsSchemeIsHere() throws {
        let source = try String(contentsOf: Self.theme.appending(path: "Theme.kt"), encoding: .utf8)
        let scheme = try #require(source.firstMatch(of: /private val lightScheme = lightColorScheme\((?<roles>[^)]*)\)/))
        let roles = Set(scheme.roles.matches(of: /(?<role>\w+) = \w+,/).map { String($0.role) })

        #expect(roles.count == 35)
        #expect(Set(AppColors.byRole.keys) == roles)
    }

    @Test func everyRoleHasAndroidsValues() throws {
        let source = try String(contentsOf: Self.theme.appending(path: "Color.kt"), encoding: .utf8)
        var android: [String: UInt32] = [:]

        for match in source.matches(of: /val (?<name>\w+) = Color\(0xFF(?<rgb>[0-9A-F]{6})\)/) {
            android[String(match.name)] = UInt32(match.rgb, radix: 16)
        }

        for (role, color) in AppColors.byRole {
            #expect(rgb(of: color, in: .light) == android[role + "Light"], "\(role), light")
            #expect(rgb(of: color, in: .dark) == android[role + "Dark"], "\(role), dark")
        }
    }

    /// `color` as `0xRRGGBB` in `style`.
    private func rgb(of color: Color, in style: UIUserInterfaceStyle) -> UInt32 {
        let resolved = UIColor(color).resolvedColor(with: UITraitCollection(userInterfaceStyle: style))
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        resolved.getRed(&red, green: &green, blue: &blue, alpha: nil)

        return [red, green, blue].reduce(0) { $0 << 8 | UInt32(($1 * 255).rounded()) }
    }
}
