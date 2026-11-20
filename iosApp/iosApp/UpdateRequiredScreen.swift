import AppColors
import AppFonts
import SwiftUI

/// Tells the user this build is no longer supported, in place of the whole app: there is no way
/// past it but updating, which its button leads to, as Android's `UpdateRequiredScreen` does.
struct UpdateRequiredScreen: View {
    /// DuckCount's page on the App Store, by its Apple ID in App Store Connect.
    private static let appStorePage = URL(string: "https://apps.apple.com/app/id6837376653")!

    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(decorative: "UpdateRequiredLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 160, height: 160)

                Text("Update required")
                    .font(.app(.title2))

                Text(
                    "For security and compatibility reasons, this version of DuckCount is no longer supported. Update the app to keep using it."
                )
                .foregroundStyle(AppColors.onSurfaceVariant)

                Button {
                    openURL(Self.appStorePage)
                } label: {
                    Text("Update")
                }
                .buttonStyle(.appProminent)
                .padding(.top, 8)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding()
        }
        // Centered while it all fits. Past that, at a large Dynamic Type, it starts from the top
        // and scrolls, so that nothing is out of reach.
        .defaultScrollAnchor(.center, for: .alignment)
        .scrollBounceBehavior(.basedOnSize)
    }
}

#Preview {
    UpdateRequiredScreen()
}
