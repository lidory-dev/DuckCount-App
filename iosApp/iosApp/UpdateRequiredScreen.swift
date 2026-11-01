import SwiftUI

/// Tells the user this build is no longer supported, in place of the whole app: there is no way
/// past it but updating. No button leads to the App Store, since there is no App Store ID to link
/// to yet, so the text names it instead.
struct UpdateRequiredScreen: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(decorative: "UpdateRequiredLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 160, height: 160)

                Text("Update required")
                    .font(.title2)
                    .fontWeight(.semibold)

                Text(
                    "For security and compatibility reasons, this version of DuckCount is no longer supported. Update the app from the App Store to keep using it."
                )
                .foregroundStyle(.secondary)
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
