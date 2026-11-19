import AppFonts
import SwiftUI

/// Warns that the new password typed turns up in known breaches, and asks for another, as the
/// registration's screen does: the change stops here, before the server has heard of it.
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
                    .font(.app(.title2))

                Text(PasswordChangeError.leaked(count: leakCount).message)

                Text("Please consider generating a brand new random password using a password manager.", bundle: .module)

                Text("If you used this password on any other service, you MUST update it immediately.", bundle: .module)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.top, 32)
        }
        .scrollBounceBehavior(.basedOnSize)
        .bottomBar {
            ConfirmButton(Text("Noted", bundle: .module)) {
                dismiss()
            }
        }
    }
}

#Preview {
    Text(verbatim: "Change password")
        .sheet(isPresented: .constant(true)) {
            PasswordLeakView(leakCount: 1_234_567)
        }
}
