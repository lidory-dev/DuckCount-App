import SwiftUI
import UIKit

/// The app's colours: Android's Material scheme, `lightScheme` and `darkScheme` in its `Theme.kt`,
/// every role with the light and dark values of its `Color.kt`, following the system's appearance as
/// Android's `AppTheme` follows its dark theme. Android uses neither its contrast schemes nor dynamic
/// colour, and neither does iOS.
///
/// iOS's own elements take the role their Material counterpart draws with:
/// - the tint, that of buttons, links, toggles and the selected tab, is `primary`, which the app's
///   `AccentColor` is too, as Material's buttons and selected states are;
/// - a screen's background is `background`, and a list's rows are on `surfaceContainerHighest`,
///   the colour of Material's `Card`, which Android's lists are made of (`appListColors()`,
///   `appBackground()`);
/// - secondary text is `onSurfaceVariant`, an error `error`, and a notice `tertiaryContainer`
///   with `onTertiaryContainer`.
public enum AppColors {
    // Android's Color.kt, role by role, in Theme.kt's order: AppColorsTests holds each to it.
    public static let primary = color(light: 0x36693B, dark: 0x9CD49C)
    public static let onPrimary = color(light: 0xFFFFFF, dark: 0x013911)
    public static let primaryContainer = color(light: 0x75AB76, dark: 0x75AB76)
    public static let onPrimaryContainer = color(light: 0x073E15, dark: 0x073E15)
    public static let secondary = color(light: 0x4F644E, dark: 0xB6CDB2)
    public static let onSecondary = color(light: 0xFFFFFF, dark: 0x223522)
    public static let secondaryContainer = color(light: 0xD1E9CD, dark: 0x384C37)
    public static let onSecondaryContainer = color(light: 0x556A53, dark: 0xA4BBA1)
    public static let tertiary = color(light: 0x8F4406, dark: 0xFFB68B)
    public static let onTertiary = color(light: 0xFFFFFF, dark: 0x522300)
    public static let tertiaryContainer = color(light: 0xAE5B20, dark: 0xAE5B20)
    public static let onTertiaryContainer = color(light: 0xFFF5F1, dark: 0xFFF5F1)
    public static let error = color(light: 0xBA1A1A, dark: 0xFFB4AB)
    public static let onError = color(light: 0xFFFFFF, dark: 0x690005)
    public static let errorContainer = color(light: 0xFFDAD6, dark: 0x93000A)
    public static let onErrorContainer = color(light: 0x93000A, dark: 0xFFDAD6)
    public static let background = color(light: 0xF8FAF3, dark: 0x111410)
    public static let onBackground = color(light: 0x191C18, dark: 0xE1E3DD)
    public static let surface = color(light: 0xF8FAF3, dark: 0x111410)
    public static let onSurface = color(light: 0x191C18, dark: 0xE1E3DD)
    public static let surfaceVariant = color(light: 0xDDE5D8, dark: 0x414940)
    public static let onSurfaceVariant = color(light: 0x414940, dark: 0xC1C9BD)
    public static let outline = color(light: 0x71796F, dark: 0x8B9388)
    public static let outlineVariant = color(light: 0xC1C9BD, dark: 0x414940)
    public static let scrim = color(light: 0x000000, dark: 0x000000)
    public static let inverseSurface = color(light: 0x2E312D, dark: 0xE1E3DD)
    public static let inverseOnSurface = color(light: 0xF0F1EB, dark: 0x2E312D)
    public static let inversePrimary = color(light: 0x9CD49C, dark: 0x36693B)
    public static let surfaceDim = color(light: 0xD9DBD4, dark: 0x111410)
    public static let surfaceBright = color(light: 0xF8FAF3, dark: 0x373A36)
    public static let surfaceContainerLowest = color(light: 0xFFFFFF, dark: 0x0C0F0B)
    public static let surfaceContainerLow = color(light: 0xF3F4ED, dark: 0x191C18)
    public static let surfaceContainer = color(light: 0xEDEFE8, dark: 0x1D201C)
    public static let surfaceContainerHigh = color(light: 0xE7E9E2, dark: 0x282B27)
    public static let surfaceContainerHighest = color(light: 0xE1E3DD, dark: 0x323631)

    /// Every role by its name in `Theme.kt`, for the tests to hold each to `Color.kt`.
    static let byRole: [String: Color] = [
        "primary": primary,
        "onPrimary": onPrimary,
        "primaryContainer": primaryContainer,
        "onPrimaryContainer": onPrimaryContainer,
        "secondary": secondary,
        "onSecondary": onSecondary,
        "secondaryContainer": secondaryContainer,
        "onSecondaryContainer": onSecondaryContainer,
        "tertiary": tertiary,
        "onTertiary": onTertiary,
        "tertiaryContainer": tertiaryContainer,
        "onTertiaryContainer": onTertiaryContainer,
        "error": error,
        "onError": onError,
        "errorContainer": errorContainer,
        "onErrorContainer": onErrorContainer,
        "background": background,
        "onBackground": onBackground,
        "surface": surface,
        "onSurface": onSurface,
        "surfaceVariant": surfaceVariant,
        "onSurfaceVariant": onSurfaceVariant,
        "outline": outline,
        "outlineVariant": outlineVariant,
        "scrim": scrim,
        "inverseSurface": inverseSurface,
        "inverseOnSurface": inverseOnSurface,
        "inversePrimary": inversePrimary,
        "surfaceDim": surfaceDim,
        "surfaceBright": surfaceBright,
        "surfaceContainerLowest": surfaceContainerLowest,
        "surfaceContainerLow": surfaceContainerLow,
        "surfaceContainer": surfaceContainer,
        "surfaceContainerHigh": surfaceContainerHigh,
        "surfaceContainerHighest": surfaceContainerHighest,
    ]

    /// A colour that is `light` in the light appearance and `dark` in the dark one, as `0xRRGGBB`.
    private static func color(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            rgb(traits.userInterfaceStyle == .dark ? dark : light)
        })
    }

    private static func rgb(_ value: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }
}

/// iOS's `.borderedProminent` in Android's colours, as Material's filled button: filled with
/// `primary`, or `error` for a destructive one, its label in `onPrimary` or `onError`, where iOS
/// writes white whatever the fill, which the dark scheme's light green leaves unreadable. A disabled
/// one is iOS's, greyed out.
public struct AppProminentButtonStyle: PrimitiveButtonStyle {
    public func makeBody(configuration: Configuration) -> some View {
        Prominent(configuration: configuration)
    }

    private struct Prominent: View {
        let configuration: Configuration

        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            let isDestructive = configuration.role == .destructive

            Button(role: configuration.role, action: configuration.trigger) {
                if isEnabled {
                    configuration.label
                        .foregroundStyle(isDestructive ? AppColors.onError : AppColors.onPrimary)
                } else {
                    configuration.label
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(isDestructive ? AppColors.error : AppColors.primary)
        }
    }
}

public extension PrimitiveButtonStyle where Self == AppProminentButtonStyle {
    /// See `AppProminentButtonStyle`.
    static var appProminent: AppProminentButtonStyle { AppProminentButtonStyle() }
}

public extension View {
    /// A list or form on Android's `background`, where iOS draws its own, and the page it is on,
    /// see `appBackground()`. Its rows keep iOS's colour unless their sections, or they, say
    /// otherwise: `appCardRows()` or `appPlainRows()`, a list's own row colour reaching none of them.
    func appListBackground() -> some View {
        scrollContentBackground(.hidden)
            .background(AppColors.background)
            .appBackground()
    }

    /// A page of a navigation stack, or a column of a split view, on Android's `background` behind
    /// whatever it shows, a list, an empty state or a spinner: where iOS draws its white or black.
    /// Set on the page itself, a background set higher up reaching no navigation container.
    func appBackground() -> some View {
        containerBackground(AppColors.background, for: .navigation)
    }

    /// A section's rows, or a row, in an inset grouped list or a form, on `surfaceContainerHighest`,
    /// the colour of Material's `Card`, which Android's lists are made of. A row setting its own
    /// colour keeps it.
    func appCardRows() -> some View {
        listRowBackground(AppColors.surfaceContainerHighest)
    }

    /// A section's rows, or a row, in a plain list, on the list's own `background`, as Android's
    /// rows lie on the screen's.
    func appPlainRows() -> some View {
        listRowBackground(AppColors.background)
    }
}
