import Foundation

/// Why a username cannot be reserved as typed, as Android's `UsernameInputError`.
public enum UsernameInputError: Hashable, Sendable {
    case mustStartWithLetter
    case invalidCharacter

    /// Longer than the library allows.
    case tooLong

    /// What the field says of it, `length` and `maxLength` telling how far over the limit it is.
    func message(length: Int, maxLength: Int) -> String {
        switch self {
        case .mustStartWithLetter:
            String(localized: "Must start with a letter", bundle: .module)
        case .invalidCharacter:
            String(localized: "Letters and digits only", bundle: .module)
        case .tooLong:
            String(localized: "Username too long (\(length)/\(maxLength))", bundle: .module)
        }
    }
}

/// Why the discovery username could not be reserved, as Android's `ReserveDiscoveryUsernameError`.
public enum ReserveUsernameError: Error, Hashable, Sendable {
    /// Somebody else already holds it.
    case alreadyTaken

    /// The server refused the username itself.
    case invalid

    /// The session is no longer valid.
    case unauthorized

    /// Another device changed the account's key material meanwhile: the same one can simply be
    /// reserved again.
    case conflict

    case rateLimited
    case noInternet
    case timeout
    case serverUnreachable
    case unknown

    var message: String {
        switch self {
        case .alreadyTaken:
            String(localized: "This username is already taken.", bundle: .module)
        case .invalid:
            String(localized: "This username cannot be used.", bundle: .module)
        case .unauthorized:
            String(localized: "Please log in again to reserve a username.", bundle: .module)
        case .conflict:
            String(localized: "Your account changed on another device, please try again.", bundle: .module)
        case .rateLimited:
            Messages.rateLimited
        case .noInternet:
            Messages.noInternet
        case .timeout:
            Messages.timeout
        case .serverUnreachable:
            Messages.serverUnreachable
        case .unknown:
            String(localized: "The username could not be reserved.", bundle: .module)
        }
    }
}

/// Why saving the profile failed, as Android's `ProfileSaveError`. There is no "saved on this device
/// instead": the profile lives on the server, and a save that did not reach it did not happen.
public enum ProfileSaveError: Error, Hashable, Sendable {
    case noInternet
    case timeout
    case serverUnreachable

    /// The session can no longer open the profile, which logging in again puts right.
    case unauthorized

    /// Another device changed the profile meanwhile: trying again works.
    case conflict

    /// Longer than the server takes, which the field keeps it from being.
    case fullNameTooLong

    case unknown

    var message: String {
        switch self {
        case .noInternet:
            Messages.noInternet
        case .timeout:
            Messages.timeout
        case .serverUnreachable:
            Messages.serverUnreachable
        case .unauthorized:
            String(localized: "Please log in again to change your profile.", bundle: .module)
        case .conflict:
            String(localized: "Your profile changed on another device, please try again.", bundle: .module)
        case .fullNameTooLong:
            String(localized: "Your full name is too long.", bundle: .module)
        case .unknown:
            String(localized: "Your profile could not be saved.", bundle: .module)
        }
    }
}

/// Why a new password cannot be set as typed, before anything is sent, as Android's
/// `NewPasswordInputError`.
public enum NewPasswordInputError: Hashable, Sendable {
    /// Nothing but whitespace, which no form takes for a password, registration included.
    case blank

    /// Shorter than the minimum length.
    case tooShort

    /// The current password typed again, which changing to would change nothing.
    case sameAsCurrent

    func message(minimumLength: Int) -> String {
        switch self {
        case .blank:
            String(localized: "Your password can’t be only spaces.", bundle: .module)
        case .tooShort:
            String(localized: "Your password must have at least \(minimumLength) characters.", bundle: .module)
        case .sameAsCurrent:
            String(localized: "Must differ from your current password.", bundle: .module)
        }
    }
}

/// Why changing the password failed, as Android's `PasswordChangeError`. The password is still the
/// one it was in every case but `unconfirmed`.
public enum PasswordChangeError: Error, Hashable, Sendable {
    /// The current password typed is not the account's. Shown under its field.
    case wrongPassword

    /// The new password turns up in a known data breach, so nothing was sent. Shown under its field.
    case leaked

    case noInternet
    case timeout
    case serverUnreachable

    /// The change went out but its answer never came back, so the password may have changed
    /// already: trying again then finds the current password typed refused, which the message warns
    /// of.
    case unconfirmed

    case unknown

    /// Whether this is shown under a field of the form, rather than under the whole form.
    var isFieldError: Bool {
        self == .wrongPassword || self == .leaked
    }

    var message: String {
        switch self {
        case .wrongPassword:
            String(localized: "Incorrect password.", bundle: .module)
        case .leaked:
            String(localized: "This password has been leaked, please choose another one.", bundle: .module)
        case .noInternet:
            Messages.noInternet
        case .timeout:
            Messages.timeout
        case .serverUnreachable:
            Messages.serverUnreachable
        case .unconfirmed:
            String(
                localized: "The server’s answer never arrived, so your password may already have been changed. If your current password is refused when you try again, or you’re asked to log in again, use your new password.",
                bundle: .module
            )
        case .unknown:
            String(localized: "Your password could not be changed.", bundle: .module)
        }
    }
}

/// Why deleting the account failed, as Android's `AccountDeletionError`. The account is still there
/// in every case, as far as the attempt could tell, but `unconfirmed`.
public enum AccountDeletionError: Error, Hashable, Sendable {
    /// The password typed is not the account's.
    case wrongPassword

    case noInternet
    case timeout
    case serverUnreachable

    /// A deletion went out, this attempt's or an earlier one's, but its answer never came back, so
    /// the account may be gone already. Trying again finds out.
    case unconfirmed

    case unknown

    var message: String {
        switch self {
        case .wrongPassword:
            String(localized: "Incorrect password.", bundle: .module)
        case .noInternet:
            Messages.noInternet
        case .timeout:
            Messages.timeout
        case .serverUnreachable:
            Messages.serverUnreachable
        case .unconfirmed:
            String(
                localized: "The server’s answer never arrived, so your account may already be deleted. Check your connection, then try again to find out.",
                bundle: .module
            )
        case .unknown:
            String(localized: "Your account could not be deleted.", bundle: .module)
        }
    }
}

/// The sentences every kind of failure shares.
enum Messages {
    static var rateLimited: String {
        String(localized: "Too many attempts, please try again later.", bundle: .module)
    }

    static var noInternet: String {
        String(localized: "No internet connection.", bundle: .module)
    }

    static var timeout: String {
        String(localized: "The server took too long to answer, please try again.", bundle: .module)
    }

    static var serverUnreachable: String {
        String(localized: "The server is unreachable, please try again later.", bundle: .module)
    }
}
