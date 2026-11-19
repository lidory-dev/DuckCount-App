import CoreText
import SwiftUI
import UIKit

/// The app's two typefaces, as Android's `Type.kt` sets them: DuckCount Display for every title, the
/// display, headline and title styles of Material, and ABeeZee for everything else, its body and
/// label styles. The same files as Android's, DuckCount Display being Acme with its circumflex turned
/// the right way up.
///
/// Each SwiftUI text style keeps the size iOS gives it, and grows with Dynamic Type as it would, in
/// the family its Material counterpart uses: the titles, `largeTitle` to `title3`, and `headline`,
/// which iOS sets a list item's title in as Android does in `titleMedium`, in DuckCount Display, and
/// the rest in ABeeZee. Neither face comes in bold, so nothing set in them is: Android's titles are
/// not either.
public enum AppFonts {
    /// DuckCount Display's PostScript name.
    static let display = "DuckCountDisplay-Regular"

    /// ABeeZee's, its italic being registered beside it.
    static let body = "ABeeZee-Regular"

    /// Makes the fonts usable by name, before anything is drawn. Once, however often it is called.
    public static func register() {
        _ = registration
    }

    private static let registration: Void = {
        let fonts = Bundle.module.urls(forResourcesWithExtension: "ttf", subdirectory: "Fonts") ?? []

        for font in fonts {
            var error: Unmanaged<CFError>?

            if !CTFontManagerRegisterFontsForURL(font as CFURL, .process, &error) {
                print("AppFonts: could not register \(font.lastPathComponent): \(String(describing: error?.takeRetainedValue()))")
            }
        }
    }()

    /// Has what SwiftUI draws through UIKit use the fonts too, which no SwiftUI modifier reaches: the
    /// navigation bar's titles, large and inline, in DuckCount Display, as Android's top bar's, and the
    /// tab bar's and segmented controls' labels and the search field in ABeeZee, as Android's bottom
    /// bar's, tabs' and search field's.
    @MainActor
    public static func styleSystemControls() {
        register()

        UINavigationBar.appearance().largeTitleTextAttributes = [.font: uiFont(display, .largeTitle)]
        UINavigationBar.appearance().titleTextAttributes = [.font: uiFont(display, .headline)]

        let label = uiFont(body, .caption2)
        let tabs = UITabBarAppearance()
        tabs.configureWithDefaultBackground()

        for layout in [tabs.stackedLayoutAppearance, tabs.inlineLayoutAppearance, tabs.compactInlineLayoutAppearance] {
            layout.normal.titleTextAttributes = [.font: label]
            layout.selected.titleTextAttributes = [.font: label]
        }

        UITabBar.appearance().standardAppearance = tabs
        UITabBar.appearance().scrollEdgeAppearance = tabs

        UISegmentedControl.appearance().setTitleTextAttributes([.font: uiFont(body, .footnote)], for: .normal)

        UITextField.appearance(whenContainedInInstancesOf: [UISearchBar.self]).font = uiFont(body, .body)
    }

    /// The family a text style is set in, see `AppFonts`.
    static func name(for style: Font.TextStyle) -> String {
        switch style {
        case .largeTitle, .title, .title2, .title3, .headline: display
        default: body
        }
    }

    /// The size iOS gives `style` at the default text size, which Dynamic Type scales from.
    static func size(for style: Font.TextStyle) -> CGFloat {
        UIFont.preferredFont(
            forTextStyle: uiTextStyle(style),
            compatibleWith: UITraitCollection(preferredContentSizeCategory: .large)
        ).pointSize
    }

    /// The font `name` at `style`'s size, scaled with Dynamic Type, or the system's should it be
    /// missing.
    private static func uiFont(_ name: String, _ style: Font.TextStyle) -> UIFont {
        let textStyle = uiTextStyle(style)

        guard let font = UIFont(name: name, size: size(for: style)) else {
            return .preferredFont(forTextStyle: textStyle)
        }

        return UIFontMetrics(forTextStyle: textStyle).scaledFont(for: font)
    }

    private static func uiTextStyle(_ style: Font.TextStyle) -> UIFont.TextStyle {
        switch style {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .subheadline: .subheadline
        case .callout: .callout
        case .footnote: .footnote
        case .caption: .caption1
        case .caption2: .caption2
        default: .body
        }
    }
}

public extension Font {
    /// The app's font for `style`, in the family Android sets its counterpart in, at the size iOS gives
    /// it and growing with Dynamic Type, see `AppFonts`.
    static func app(_ style: Font.TextStyle) -> Font {
        .custom(AppFonts.name(for: style), size: AppFonts.size(for: style), relativeTo: style)
    }

    /// ABeeZee at `size`, which does not grow with Dynamic Type, for text sized to fit a shape, as an
    /// avatar's initial is on Android.
    static func app(size: CGFloat) -> Font {
        .custom(AppFonts.body, fixedSize: size)
    }
}
