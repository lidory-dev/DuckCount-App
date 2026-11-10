import SwiftUI

/// Warns that the password typed for a new account turns up in known breaches, and asks for
/// another: the registration stops here, before the server has heard of it.
struct PasswordLeakView: View {
    let leakCount: Int

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)

                Text("Watch out!", bundle: .module)
                    .font(.title2)
                    .fontWeight(.bold)

                Text(
                    "The password you are using has been leaked on the web \(leakCount) times and hackers can already use it to get into your accounts.",
                    bundle: .module
                )

                Text("Please consider generating a brand new random password using a password manager.", bundle: .module)
                    .foregroundStyle(.secondary)

                Text("If you used this password on any other service, you MUST update it immediately.", bundle: .module)
                    .fontWeight(.semibold)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.top, 32)
        }
        .scrollBounceBehavior(.basedOnSize)
        .bottomBar {
            ConfirmButton("Noted") { dismiss() }
        }
    }
}

#Preview {
    Text(verbatim: "Register")
        .sheet(isPresented: .constant(true)) {
            PasswordLeakView(leakCount: 1_234_567)
        }
}
