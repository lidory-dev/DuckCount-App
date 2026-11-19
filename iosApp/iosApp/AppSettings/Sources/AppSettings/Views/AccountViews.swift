import SwiftUI

/// Where the account screen leads.
enum AccountRoute: Hashable {
    case passwordChange
    case deletion
}

/// Who the user is connected as, and what they can do with their account: change its password, log
/// out of it, or delete it, as Android's `AccountScreen`.
struct AccountView: View {
    private let callBacks: any SettingsCallBacks

    @StateObject private var viewModel: AccountViewModel

    /// Set as the password change closes on success, for this screen to say so: the change has no
    /// confirmation of its own, being the only thing it is for. Once: leaving drops it.
    @State private var isPasswordChanged = false

    init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
        _viewModel = StateObject(wrappedValue: AccountViewModel(callBacks: callBacks))
    }

    var body: some View {
        List {
            Section {
                NavigationLink(value: AccountRoute.passwordChange) {
                    Label {
                        Text("Change password", bundle: .module)
                    } icon: {
                        Image(systemName: "key")
                    }
                }
            } header: {
                if let username = viewModel.username {
                    Text("Connected as **\(username)**", bundle: .module)
                        .textCase(nil)
                }
            } footer: {
                if isPasswordChanged {
                    FormMessage(String(localized: "Your password has been changed.", bundle: .module), isError: false)
                }
            }

            Section {
                LogOutButton(isLoggingOut: viewModel.isLoggingOut, onConfirm: viewModel.logOut)
            }

            Section {
                NavigationLink(value: AccountRoute.deletion) {
                    Label {
                        Text("Delete account", bundle: .module)
                    } icon: {
                        Image(systemName: "trash")
                    }
                    .foregroundStyle(.red)
                }
            }
        }
        .listStyle(.insetGrouped)
        .disabled(viewModel.isLoggingOut)
        .navigationTitle(Text("Account", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(viewModel.isLoggingOut)
        .navigationDestination(for: AccountRoute.self) { route in
            switch route {
            case .passwordChange:
                PasswordChangeView(callBacks: callBacks) {
                    isPasswordChanged = true

                    // A footer appearing is not read out, where Android's snackbar is.
                    AccessibilityNotification.Announcement(
                        String(localized: "Your password has been changed.", bundle: .module)
                    ).post()
                }

            case .deletion:
                AccountDeletionView(callBacks: callBacks)
            }
        }
        .onDisappear {
            isPasswordChanged = false
        }
    }
}

/// The row that logs the user out once they confirmed it, the confirmation pointing at it.
private struct LogOutButton: View {
    let isLoggingOut: Bool
    let onConfirm: () -> Void

    @State private var isConfirming = false

    var body: some View {
        Button(role: .destructive) {
            isConfirming = true
        } label: {
            HStack {
                Label {
                    Text("Log out", bundle: .module)
                } icon: {
                    Image(systemName: "rectangle.portrait.and.arrow.right")
                }
                // The icon too, which the destructive role leaves in the accent color.
                .foregroundStyle(.red)

                if isLoggingOut {
                    Spacer()

                    ProgressView()
                }
            }
        }
        .confirmationDialog(
            Text("Log out", bundle: .module),
            isPresented: $isConfirming,
            titleVisibility: .visible
        ) {
            Button(role: .destructive, action: onConfirm) {
                Text("Log out", bundle: .module)
            }
        } message: {
            Text(
                "Are you sure you want to log out? You’ll need to log back in to access your groups and expenses.",
                bundle: .module
            )
        }
    }
}

/// The form the password is changed with, as Android's `PasswordChangeScreen`, which closes once it
/// is, calling `onChanged`. Nothing else changes: the user's keys, their data and the session on
/// this device stay as they are.
struct PasswordChangeView: View {
    @StateObject private var viewModel: PasswordChangeViewModel

    private let onChanged: () -> Void

    @FocusState private var focusedField: Field?

    @Environment(\.dismiss) private var dismiss

    private enum Field {
        case currentPassword
        case newPassword
        case newPasswordConfirmation
    }

    init(callBacks: any SettingsCallBacks, onChanged: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: PasswordChangeViewModel(callBacks: callBacks))
        self.onChanged = onChanged
    }

    var body: some View {
        Form {
            Section {
                SecureField(
                    String(localized: "Current password", bundle: .module),
                    text: Binding(get: { viewModel.currentPassword }, set: viewModel.updateCurrentPassword)
                )
                .textContentType(.password)
                .submitLabel(.next)
                .focused($focusedField, equals: .currentPassword)
                .onSubmit { focusedField = .newPassword }
            } footer: {
                if viewModel.error == .wrongPassword {
                    FormMessage(PasswordChangeError.wrongPassword.message)
                }
            }

            Section {
                SecureField(
                    String(localized: "New password", bundle: .module),
                    text: Binding(get: { viewModel.newPassword }, set: viewModel.updateNewPassword)
                )
                .textContentType(.newPassword)
                .submitLabel(.next)
                .focused($focusedField, equals: .newPassword)
                .onSubmit { focusedField = .newPasswordConfirmation }

                SecureField(
                    String(localized: "New password confirmation", bundle: .module),
                    text: Binding(get: { viewModel.newPasswordConfirmation }, set: viewModel.updateNewPasswordConfirmation)
                )
                .textContentType(.newPassword)
                .submitLabel(.go)
                .focused($focusedField, equals: .newPasswordConfirmation)
                .onSubmit(confirm)
            } footer: {
                FormMessages(
                    viewModel.newPasswordInputError?.message(minimumLength: viewModel.minimumLength),
                    viewModel.isConfirmationMismatched
                        ? String(localized: "The passwords do not match.", bundle: .module)
                        : nil,
                    viewModel.error.flatMap { $0.isFieldError ? nil : $0.message }
                )
            }
        }
        .disabled(viewModel.isChanging)
        .navigationTitle(Text("Change password", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        // Leaving would not stop the change, which the shared module sees through, only hide how it
        // ended.
        .navigationBarBackButtonHidden(viewModel.isChanging)
        .bottomBar {
            ConfirmButton(Text("Confirm", bundle: .module), isLoading: viewModel.isChanging, action: confirm)
                .disabled(!viewModel.canConfirm)
        }
        .sheet(
            item: Binding(
                get: { viewModel.leak },
                set: { leak in
                    if leak == nil {
                        viewModel.dismissLeak()
                    }
                }
            ),
            onDismiss: {
                // Back to the new password, which the screen asked to choose again.
                focusedField = .newPassword
            }
        ) { leak in
            PasswordLeakView(leakCount: leak.count)
        }
        .onAppear {
            focusedField = .currentPassword
        }
        .onChange(of: viewModel.isChanged) { _, isChanged in
            if isChanged {
                onChanged()
                dismiss()
            }
        }
    }

    private func confirm() {
        // Nothing to change to yet: the keyboard stays up to finish typing.
        guard viewModel.canConfirm else {
            return
        }

        focusedField = nil
        viewModel.confirm()
    }
}

/// The form the account is deleted with, as Android's `AccountDeletionScreen`: the password typed
/// there is the whole confirmation, which the server asks for before it deletes anything. A
/// deletion that goes through starts the app over.
struct AccountDeletionView: View {
    @StateObject private var viewModel: AccountDeletionViewModel

    @FocusState private var isFieldFocused: Bool

    init(callBacks: any SettingsCallBacks) {
        _viewModel = StateObject(wrappedValue: AccountDeletionViewModel(callBacks: callBacks))
    }

    var body: some View {
        Form {
            Section {
                Disclaimer(systemImage: "exclamationmark.triangle.fill", tint: .red) {
                    Text(
                        "Deleting your account is permanent and your data cannot be recovered.\n\nGroups you created will be deleted for all their members, who keep a read-only copy.\n\nOther expenses you’ve created or participated in will not be deleted. They’ll remain visible to anyone else involved.\n\nTo continue, you’ll need to re-enter your password to confirm.",
                        bundle: .module
                    )
                }
            }

            Section {
                SecureField(
                    String(localized: "Password", bundle: .module),
                    text: Binding(get: { viewModel.password }, set: viewModel.updatePassword)
                )
                .textContentType(.password)
                // Go only closes the keyboard: the button, red, is deliberate enough for a deletion.
                .submitLabel(.done)
                .focused($isFieldFocused)
            } footer: {
                if let error = viewModel.error {
                    FormMessage(error.message)
                }
            }
        }
        .disabled(viewModel.isDeleting)
        .navigationTitle(Text("Delete account", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        // Leaving would not stop the deletion, which the shared module sees through, only hide how it
        // ended.
        .navigationBarBackButtonHidden(viewModel.isDeleting)
        .bottomBar {
            ConfirmButton(
                Text("Delete account", bundle: .module),
                role: .destructive,
                isLoading: viewModel.isDeleting
            ) {
                isFieldFocused = false
                viewModel.delete()
            }
            .disabled(!viewModel.canDelete)
        }
    }
}

#Preview("Account") {
    NavigationStack {
        AccountView(callBacks: PreviewSettingsCallBacks())
    }
}

#Preview("Change password") {
    NavigationStack {
        PasswordChangeView(callBacks: PreviewSettingsCallBacks()) {}
    }
}

#Preview("Delete account") {
    NavigationStack {
        AccountDeletionView(callBacks: PreviewSettingsCallBacks())
    }
}
