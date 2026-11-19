import Foundation

/// The account screen's state, as Android's `AccountViewModel` holds it.
@MainActor
final class AccountViewModel: ObservableObject {
    /// Whether the user is being logged out, which takes the whole app back to its first screen.
    @Published private(set) var isLoggingOut = false

    /// The username the user logs in with, which the screen says they are connected as.
    let username: String?

    private let callBacks: any SettingsCallBacks

    init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
        self.username = callBacks.authenticatedUsername()
    }

    /// Logs out, once: the app starts over on its first screen, see `SettingsCallBacks.logOut`.
    func logOut() {
        guard !isLoggingOut else {
            return
        }

        isLoggingOut = true

        Task {
            await callBacks.logOut()
        }
    }
}

/// The password change screen's state, as Android's `PasswordChangeViewModel` and
/// `PasswordChangeScreenState` hold it. The current password typed there is what the server asks
/// for before it changes anything. Nothing typed yet in a field is no mistake worth pointing out:
/// the button stays disabled anyway.
@MainActor
final class PasswordChangeViewModel: ObservableObject {
    @Published private(set) var currentPassword = ""
    @Published private(set) var newPassword = ""
    @Published private(set) var newPasswordConfirmation = ""
    @Published private(set) var isChanging = false

    /// Why the last change failed, until an edit answers it.
    @Published private(set) var error: PasswordChangeError?

    /// True once the password is changed, the only thing the screen is for, which closes it.
    @Published private(set) var isChanged = false

    let minimumLength: Int

    private let callBacks: any SettingsCallBacks

    init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
        self.minimumLength = callBacks.minimumPasswordLength
    }

    /// Why `newPassword` cannot be set, taken as typed, never trimmed: whitespace is part of a
    /// password.
    var newPasswordInputError: NewPasswordInputError? {
        if newPassword.isEmpty {
            nil
        } else if newPassword.isBlank {
            .blank
        } else if newPassword.utf16.count < minimumLength {
            .tooShort
        } else if newPassword == currentPassword {
            .sameAsCurrent
        } else {
            nil
        }
    }

    var isConfirmationMismatched: Bool {
        !newPasswordConfirmation.isEmpty && newPasswordConfirmation != newPassword
    }

    /// Whether the form holds a change to send: every field filled in and none in error, a failure
    /// shown under one of them included, since sending it again would fail the same way. A current
    /// password of nothing but whitespace is none, as on every form: no account holds one.
    var canConfirm: Bool {
        !currentPassword.isBlank
            && !newPassword.isEmpty
            && newPasswordInputError == nil
            && newPasswordConfirmation == newPassword
            && error?.isFieldError != true
            && !isChanging
            && !isChanged
    }

    // Each edit clears the failure it answers: the one shown under the field edited, and the one
    // shown under the whole form, which was about the form as it was. Not the one shown under
    // another field, which still holds what failed.

    func updateCurrentPassword(_ password: String) {
        currentPassword = password
        error = error == .leaked ? error : nil
    }

    func updateNewPassword(_ password: String) {
        newPassword = password
        error = error == .wrongPassword ? error : nil
    }

    func updateNewPasswordConfirmation(_ password: String) {
        newPasswordConfirmation = password
        error = error?.isFieldError == true ? error : nil
    }

    func confirm() {
        // Only a change the form holds, and not while one is under way, which a second tap landing
        // before the button is disabled would start again, its handshakes replacing the first one's.
        guard canConfirm else {
            return
        }

        let currentPassword = currentPassword
        let newPassword = newPassword

        isChanging = true
        error = nil

        Task {
            let failure = await callBacks.changePassword(currentPassword: currentPassword, newPassword: newPassword)

            // The change is the only thing this screen is for, so a successful one hands the user
            // back to the account screen.
            if failure == nil {
                isChanged = true
            }

            isChanging = false
            error = failure
        }
    }
}

/// The account deletion screen's state, as Android's `AccountDeletionViewModel` holds it. The
/// password typed there is the whole confirmation: it is what the server asks for before it deletes
/// anything.
@MainActor
final class AccountDeletionViewModel: ObservableObject {
    @Published private(set) var password = ""
    @Published private(set) var isDeleting = false

    /// Why the last attempt failed, cleared as soon as the password changes.
    @Published private(set) var error: AccountDeletionError?

    private let callBacks: any SettingsCallBacks

    init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
    }

    /// Nothing but whitespace is no password, as on every form: no account holds one.
    var canDelete: Bool {
        !password.isBlank && !isDeleting
    }

    func updatePassword(_ password: String) {
        self.password = password
        error = nil
    }

    func delete() {
        // Not while a deletion is under way, which a second tap landing before the button is
        // disabled would start again, its re-authentication replacing the first one's.
        guard canDelete else {
            return
        }

        let password = password

        isDeleting = true
        error = nil

        Task {
            // Only ever back with a failure: a deletion that went through starts the app over.
            let failure = await callBacks.deleteAccount(password: password)

            isDeleting = false
            error = failure
        }
    }
}
