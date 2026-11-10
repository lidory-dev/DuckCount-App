import Foundation

/// The login form's state, as Android's `LoginViewModel` holds it.
@MainActor
public final class LoginViewModel: ObservableObject {
    /// The username field's text, as typed: checked and logged in with lowercased and without the
    /// whitespace around it, see `trimmedUsername`.
    @Published public private(set) var username = ""
    @Published public private(set) var password = ""
    @Published public private(set) var usernameError: UsernameInputError?
    @Published public private(set) var error: AuthError?
    @Published public private(set) var isLoading = false

    private let callBacks: any AuthenticationCallBacks

    public init(callBacks: any AuthenticationCallBacks) {
        self.callBacks = callBacks
    }

    var maxUsernameLength: Int {
        callBacks.maxUsernameLength
    }

    var trimmedUsername: String {
        username.trimmedUsername
    }

    /// Whether the form can log in: a username the rules take, and a password other than nothing
    /// but whitespace, which no account holds, registration and a password change refusing it.
    var canLogIn: Bool {
        !username.isBlank && usernameError == nil && !password.isBlank && !isLoading
    }

    func updateUsername(_ username: String) {
        self.username = username
        // Nothing typed yet isn't a mistake worth pointing out, the button stays disabled anyway.
        usernameError = username.isBlank ? nil : callBacks.validateUsername(username.trimmedUsername)
        error = nil
    }

    func updatePassword(_ password: String) {
        self.password = password
        error = nil
    }

    /// Logs in with what the form holds, from its button or the keyboard's Go alike, calling
    /// `onLoggedIn` once the session is stored.
    func logIn(onLoggedIn: @escaping @MainActor () -> Void) {
        guard canLogIn else {
            return
        }

        let username = trimmedUsername

        // The digest the server knows the account by is derived from this username, so one that
        // does not pass the rules can never match an account.
        guard callBacks.validateUsername(username) == nil else {
            return
        }

        isLoading = true
        error = nil

        Task {
            let failure = await callBacks.logIn(username: username, password: password)

            isLoading = false

            if let failure {
                error = failure
            } else {
                onLoggedIn()
            }
        }
    }
}
