import SwiftUI
import Testing
import UIKit

@testable import AppFonts

/// The app's typefaces, as Android's `Type.kt` sets them.
struct AppFontsTests {
    init() {
        AppFonts.register()
    }

    @Test(arguments: [AppFonts.display, AppFonts.body, "ABeeZee-Italic"])
    func everyFaceIsRegistered(name: String) {
        #expect(UIFont(name: name, size: 17) != nil)
    }

    /// Android's display, headline and title styles.
    @Test(arguments: [Font.TextStyle.largeTitle, .title, .title2, .title3, .headline])
    func titlesAreSetInDuckCountDisplay(style: Font.TextStyle) {
        #expect(AppFonts.name(for: style) == AppFonts.display)
    }

    /// Android's body and label styles.
    @Test(arguments: [Font.TextStyle.body, .callout, .subheadline, .footnote, .caption, .caption2])
    func everythingElseIsSetInABeeZee(style: Font.TextStyle) {
        #expect(AppFonts.name(for: style) == AppFonts.body)
    }

    /// The size iOS gives each style at the default text size, which Dynamic Type scales from.
    @Test func sizesAreThoseOfIOS() {
        #expect(AppFonts.size(for: .largeTitle) == 34)
        #expect(AppFonts.size(for: .body) == 17)
        #expect(AppFonts.size(for: .caption2) == 11)
    }
}
