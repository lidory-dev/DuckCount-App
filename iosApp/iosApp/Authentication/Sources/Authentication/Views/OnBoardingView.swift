import AppColors
import AppFonts
import SwiftUI

/// The first screen of a device with no account on it: what DuckCount is, and the way to create an
/// account or to log in to one.
struct OnBoardingView: View {
    let onRegister: () -> Void
    let onLogIn: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(verbatim: "DuckCount")
                    .font(.app(.largeTitle))

                Text("Share your expenses, not your data.", bundle: .module)
                    .font(.app(.title2))

                Text(
                    "Split bills with your group in seconds. The amounts and what they’re for stay encrypted — we can’t read them.",
                    bundle: .module
                )
                .foregroundStyle(AppColors.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
        }
        // Centered while it all fits. Past that, at a large Dynamic Type, it starts from the top
        // and scrolls, so that nothing is out of reach.
        .defaultScrollAnchor(.center, for: .alignment)
        .scrollBounceBehavior(.basedOnSize)
        .bottomBar {
            ConfirmButton("Register", action: onRegister)

            Button(action: onLogIn) {
                Text("Log In", bundle: .module)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .toolbar(.hidden, for: .navigationBar)
    }
}

#Preview {
    NavigationStack {
        OnBoardingView(onRegister: {}, onLogIn: {})
    }
}
