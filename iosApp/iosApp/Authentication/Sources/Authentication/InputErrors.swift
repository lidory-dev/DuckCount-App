import Foundation

/// Why a username cannot be used as typed, at a granularity the screens can explain. The rules
/// themselves live in `lib-duckcount`, which the app maps onto this.
public enum UsernameInputError: Hashable, Sendable {
    /// Nothing was typed.
    case empty

    /// The username does not start with a letter.
    case mustStartWithLetter

    /// The username holds something other than letters and digits.
    case invalidCharacter

    /// The username is longer than the library allows.
    case tooLong

    /// What the form says about it, `length` and `maxLength` going into the "too long" message.
    func message(length: Int, maxLength: Int) -> String {
        switch self {
        case .empty:
            String(localized: "A username is required", bundle: .module)
        case .mustStartWithLetter:
            String(localized: "Must start with a letter", bundle: .module)
        case .invalidCharacter:
            String(localized: "Letters and digits only", bundle: .module)
        case .tooLong:
            String(localized: "Username too long (\(length)/\(maxLength))", bundle: .module)
        }
    }
}

/// Why a password cannot be registered as typed, before anything is sent.
public enum PasswordInputError: Hashable, Sendable {
    /// Nothing but whitespace, which no form takes for a password: logging in with it would never
    /// be offered, see `LoginViewModel.canLogIn`.
    case blank

    /// Shorter than the minimum length.
    case tooShort

    /// What the form says about it, `minimumLength` going into the "too short" message.
    func message(minimumLength: Int) -> String {
        switch self {
        case .blank:
            String(localized: "Your password can’t be only spaces", bundle: .module)
        case .tooShort:
            String(localized: "Your password must have at least \(minimumLength) characters", bundle: .module)
        }
    }
}

/// A registration awaiting its profile, the step that finishes it.
///
/// Surfaced at launch so that the flow resumes on `SetUpProfileView` rather than sending an
/// already-registered user back to log in.
public struct PendingProfile: Hashable, Sendable {
    public let uuid: String
    public let username: String
    public let hasPasswordBeenChecked: Bool

    public init(uuid: String, username: String, hasPasswordBeenChecked: Bool) {
        self.uuid = uuid
        self.username = username
        self.hasPasswordBeenChecked = hasPasswordBeenChecked
    }
}

extension String {
    /// Nothing but whitespace, as Kotlin's `isBlank` says.
    var isBlank: Bool {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// This username lowercased, as every username is, and without the whitespace around it, which
    /// is what is checked and sent: one filled in with a stray space still goes through, a space
    /// never being valid. The field holds it as typed.
    var trimmedUsername: String {
        trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
