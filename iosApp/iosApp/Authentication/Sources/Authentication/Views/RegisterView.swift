import SwiftUI

/// Creates an account: a username and a password typed twice, which iOS offers to fill in with a
/// strong password of its own.
struct RegisterView: View {
    @StateObject private var viewModel: RegisterViewModel

    private let onRegistered: @MainActor (PendingProfile) -> Void
    private let onDeviceNotAllowed: @MainActor () -> Void

    @State private var leak: PasswordLeak?

    @FocusState private var focusedField: Field?

    private enum Field {
        case username
        case password
        case passwordConfirmation
    }

    init(
        callBacks: any AuthenticationCallBacks,
        onRegistered: @escaping @MainActor (PendingProfile) -> Void,
        onDeviceNotAllowed: @escaping @MainActor () -> Void
    ) {
        _viewModel = StateObject(wrappedValue: RegisterViewModel(callBacks: callBacks))
        self.onRegistered = onRegistered
        self.onDeviceNotAllowed = onDeviceNotAllowed
    }

    var body: some View {
        Form {
            Section {
                TextField(
                    String(localized: "Username", bundle: .module),
                    text: Binding(get: { viewModel.username }, set: viewModel.updateUsername)
                )
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.next)
                .focused($focusedField, equals: .username)
                .onSubmit { focusedField = .password }
            } footer: {
                FormErrors(
                    viewModel.usernameError?.message(
                        length: viewModel.trimmedUsername.count,
                        maxLength: viewModel.maxUsernameLength
                    )
                )
            }

            Section {
                SecureField(
                    String(localized: "Password", bundle: .module),
                    text: Binding(get: { viewModel.password }, set: viewModel.updatePassword)
                )
                .textContentType(.newPassword)
                .submitLabel(.next)
                .focused($focusedField, equals: .password)
                .onSubmit { focusedField = .passwordConfirmation }

                SecureField(
                    String(localized: "Password Confirmation", bundle: .module),
                    text: Binding(get: { viewModel.passwordConfirmation }, set: viewModel.updatePasswordConfirmation)
                )
                .textContentType(.newPassword)
                .submitLabel(.go)
                .focused($focusedField, equals: .passwordConfirmation)
                // What the button takes, no less: from here, a password too short once went
                // through on Android.
                .onSubmit(register)
            } footer: {
                FormErrors(
                    viewModel.passwordError?.message(minimumLength: viewModel.minimumPasswordLength),
                    viewModel.isPasswordConfirmationMismatched
                        ? String(localized: "The passwords do not match", bundle: .module)
                        : nil,
                    viewModel.error?.message
                )
            }
        }
        .disabled(viewModel.isLoading)
        .navigationTitle(Text("Welcome!", bundle: .module))
        // Nothing to go back to while registering.
        .navigationBarBackButtonHidden(viewModel.isLoading)
        .bottomBar {
            RegistrationConsent(
                termsOfUseLink: viewModel.termsOfUseLink,
                privacyPolicyLink: viewModel.privacyPolicyLink
            )

            ConfirmButton("Register", isLoading: viewModel.isLoading, action: register)
                .disabled(!viewModel.canRegister)
        }
        .sheet(item: $leak, onDismiss: viewModel.clearPasswords) { leak in
            PasswordLeakView(leakCount: leak.count)
        }
        .onAppear { focusedField = .username }
    }

    private func register() {
        focusedField = nil
        viewModel.register(
            onPasswordLeaked: { leak = PasswordLeak(count: $0) },
            onRegistered: onRegistered,
            onDeviceNotAllowed: onDeviceNotAllowed
        )
    }
}

/// How many times the password typed turned up in known breaches, for the sheet saying so.
private struct PasswordLeak: Identifiable {
    let id = UUID()
    let count: Int
}

/// Says that creating an account accepts the terms of use and the privacy policy, each opening in
/// Safari.
struct RegistrationConsent: View {
    let termsOfUseLink: URL
    let privacyPolicyLink: URL

    var body: some View {
        // Each link keeps its attributes wherever a translation puts it.
        let termsOfUse = link(String(localized: "terms of use", bundle: .module), to: termsOfUseLink)
        let privacyPolicy = link(String(localized: "privacy policy", bundle: .module), to: privacyPolicyLink)

        Text(AttributedString(localized: "By registering, you accept the \(termsOfUse) and the \(privacyPolicy).", bundle: .module))
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }

    private func link(_ text: String, to url: URL) -> AttributedString {
        var link = AttributedString(text)
        link.link = url

        return link
    }
}

#Preview {
    NavigationStack {
        RegisterView(callBacks: PreviewAuthenticationCallBacks(), onRegistered: { _ in }, onDeviceNotAllowed: {})
    }
}
