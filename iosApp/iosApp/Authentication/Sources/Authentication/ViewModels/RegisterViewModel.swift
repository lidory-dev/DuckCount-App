import Foundation

/// The registration form's state, as Android's `RegisterViewModel` holds it.
@MainActor
public final class RegisterViewModel: ObservableObject {
    /// The username field's text, as typed: checked and registered lowercased and without the
    /// whitespace around it, see `trimmedUsername`.
    @Published public private(set) var username = ""

    /// Taken as typed, never trimmed.
    @Published public private(set) var password = ""
    @Published public private(set) var passwordConfirmation = ""
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

    var minimumPasswordLength: Int {
        callBacks.minimumPasswordLength
    }

    var termsOfUseLink: URL {
        callBacks.termsOfUseLink
    }

    var privacyPolicyLink: URL {
        callBacks.privacyPolicyLink
    }

    var trimmedUsername: String {
        username.trimmedUsername
    }

    /// Why the password cannot be registered, or nil while nothing is typed yet, which is no
    /// mistake worth pointing out: the button stays disabled anyway, see `canRegister`. Its length
    /// is counted in UTF-16 units, as Android counts it.
    var passwordError: PasswordInputError? {
        if password.isEmpty {
            nil
        } else if password.isBlank {
            .blank
        } else if password.utf16.count < minimumPasswordLength {
            .tooShort
        } else {
            nil
        }
    }

    /// Whether the confirmation, once anything is typed in it, whitespace included, differs from
    /// the password, which the form points out.
    var isPasswordConfirmationMismatched: Bool {
        !passwordConfirmation.isEmpty && password != passwordConfirmation
    }

    /// Whether the form holds a registration to send, from its button or the keyboard's Go alike:
    /// a username the rules take, a password with nothing wrong with it, typed again the same, and
    /// no registration under way.
    var canRegister: Bool {
        !username.isBlank
            && usernameError == nil
            && !password.isEmpty
            && passwordError == nil
            && password == passwordConfirmation
            && !isLoading
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

    func updatePasswordConfirmation(_ passwordConfirmation: String) {
        self.passwordConfirmation = passwordConfirmation
        error = nil
    }

    /// Forgets both passwords, once one turned out leaked or the form is left for good.
    func clearPasswords() {
        password = ""
        passwordConfirmation = ""
        error = nil
    }

    /// Registers what the form holds.
    ///
    /// The password is looked up in the known breaches first, which is advisory: one found there
    /// calls `onPasswordLeaked` with how many times and stops, while a check that could not run
    /// lets the registration go ahead, recorded as unchecked. Then `onRegistered` gets the
    /// registration awaiting its profile, or `onDeviceNotAllowed` is called if the server refused
    /// this device, nothing on the form changing that answer.
    func register(
        onPasswordLeaked: @escaping @MainActor (Int) -> Void,
        onRegistered: @escaping @MainActor (PendingProfile) -> Void,
        onDeviceNotAllowed: @escaping @MainActor () -> Void
    ) {
        guard canRegister else {
            return
        }

        let username = trimmedUsername
        let password = password

        // The account is keyed by a digest derived from this username, so registering one that
        // does not pass the rules would mint an account nothing can log back into.
        guard callBacks.validateUsername(username) == nil else {
            return
        }

        isLoading = true
        error = nil

        Task {
            let leakCount = await callBacks.passwordLeakCount(password: password)

            if let leakCount, leakCount > 0 {
                isLoading = false
                onPasswordLeaked(leakCount)
                return
            }

            let result = await callBacks.register(username: username, password: password)

            isLoading = false

            switch result {
            case .success(let uuid):
                let profile = PendingProfile(
                    uuid: uuid,
                    username: username,
                    hasPasswordBeenChecked: leakCount != nil
                )

                // So that the profile step is resumed if the app is killed before it is completed.
                callBacks.markPendingRegistration(profile)
                clearPasswords()
                onRegistered(profile)

            case .failure(.deviceNotAllowed):
                clearPasswords()
                onDeviceNotAllowed()

            case .failure(let failure):
                error = failure
            }
        }
    }
}
