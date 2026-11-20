import AppColors
import AppFonts
import SwiftUI

/// The skeleton of a form of the settings, see `Skeleton`, as the profile and username forms lay
/// theirs out: what the form says to begin with, under its icon, its one field, and the button that
/// confirms it.
struct FormSkeleton: View {
    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    AvatarBone(size: 34)

                    // Two paragraphs, as the forms' disclaimers have.
                    VStack(spacing: 0) {
                        TextBone(.body, width: 0.85, alignment: .center)
                        TextBone(.body, width: 0.5, alignment: .center)
                        TextBone(.body, width: 0)
                        TextBone(.body, width: 0.9, alignment: .center)
                        TextBone(.body, width: 0.95, alignment: .center)
                        TextBone(.body, width: 0.4, alignment: .center)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            .appCardRows()

            Section {
                TextBone(.body, width: 0.45)
            }
            .appCardRows()
        }
        .appListBackground()
        .bottomBar {
            ButtonBone()
        }
        .skeleton(.loading)
    }
}

extension TextBone {
    /// A line of text in the app's font for `style`, see `TextBone`.
    init(_ style: Font.TextStyle, width: CGFloat = 1, alignment: HorizontalAlignment = .leading) {
        self.init(font: .app(style), style: style, width: width, alignment: alignment)
    }
}

extension Text {
    /// What VoiceOver reads a skeleton as.
    static var loading: Text {
        Text("Loading", bundle: .module)
    }
}
