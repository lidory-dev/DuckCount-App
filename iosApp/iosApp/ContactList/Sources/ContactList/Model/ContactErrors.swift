import Foundation

/// Why a username cannot be looked up or reserved as typed, as Android's `UsernameInputError`.
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

/// Why a contact request could not be sent, as Android's `AddContactError`.
public enum AddContactError: Error, Hashable, Sendable {
    case invalidUsername

    /// Nobody reserved that username, or its holder cannot be reached yet: the server answers both
    /// alike.
    case notFound

    case selfUsername
    case alreadyContact
    case alreadyPending
    case rateLimited

    /// The recipient presented a security key other than the one this device saw for them before.
    case identityChanged

    /// The user blocked them, and has to unblock them first.
    case blocked

    case noInternet
    case timeout
    case serverUnreachable
    case unknown

    var message: String {
        switch self {
        case .invalidUsername:
            String(localized: "This username is not valid.", bundle: .module)
        case .notFound:
            String(localized: "No user found with this username.", bundle: .module)
        case .selfUsername:
            String(localized: "You can’t add yourself as a contact.", bundle: .module)
        case .alreadyContact:
            String(localized: "This user is already one of your contacts.", bundle: .module)
        case .alreadyPending:
            String(localized: "You already sent this user a contact request.", bundle: .module)
        case .rateLimited:
            Messages.rateLimited
        case .identityChanged:
            String(localized: "Your safety number with this user has changed, so the request was not sent.", bundle: .module)
        case .blocked:
            String(localized: "You blocked this user. Unblock them to send a request.", bundle: .module)
        case .noInternet:
            Messages.noInternet
        case .timeout:
            Messages.timeout
        case .serverUnreachable:
            Messages.serverUnreachable
        case .unknown:
            String(localized: "The contact request could not be sent.", bundle: .module)
        }
    }
}

/// Why a contact request could not be accepted, refused, or its sender blocked, as Android's
/// `AnswerContactRequestError`.
public enum AnswerContactRequestError: Error, Hashable, Sendable {
    /// The request is no longer waiting for the user.
    case notFound

    /// The request could not be refused: the user had accepted it already, so its sender was added
    /// as a contact.
    case alreadyAccepted

    case noInternet
    case timeout
    case serverUnreachable

    /// The sender presented a security key other than the one this device has seen for them since
    /// the request arrived.
    case identityChanged

    case unknown

    var message: String {
        switch self {
        case .notFound:
            String(localized: "This contact request no longer exists.", bundle: .module)
        case .alreadyAccepted:
            String(localized: "You had already accepted this request, so this person was added to your contacts.", bundle: .module)
        case .noInternet:
            Messages.noInternet
        case .timeout:
            Messages.timeout
        case .serverUnreachable:
            Messages.serverUnreachable
        case .identityChanged:
            String(localized: "Your safety number with this user has changed, so the request was not accepted.", bundle: .module)
        case .unknown:
            String(localized: "The contact request could not be answered, please try again.", bundle: .module)
        }
    }
}

/// Why a contact could not be removed, or blocked, which removes them first, as Android's
/// `RemoveContactError`.
public enum RemoveContactError: Error, Hashable, Sendable {
    /// Too many messages sent in too short a time.
    case rateLimited

    case noInternet
    case timeout
    case serverUnreachable
    case unknown

    /// What is said of it when removing the contact.
    var removalMessage: String {
        commonMessage ?? String(localized: "The contact could not be deleted, please try again.", bundle: .module)
    }

    /// What is said of it when blocking the contact, which removes them first.
    var blockingMessage: String {
        commonMessage ?? String(localized: "This contact could not be blocked, please try again.", bundle: .module)
    }

    /// What removing and blocking say alike, nil for `unknown`, which each says its own way.
    private var commonMessage: String? {
        switch self {
        case .rateLimited: Messages.rateLimited
        case .noInternet: Messages.noInternet
        case .timeout: Messages.timeout
        case .serverUnreachable: Messages.serverUnreachable
        case .unknown: nil
        }
    }
}

/// Why the safety number of a contact could not be computed, as Android's
/// `ContactVerificationError`.
public enum ContactVerificationError: Error, Hashable, Sendable {
    case noInternet
    case timeout
    case serverUnreachable
    case unknown

    var message: String {
        switch self {
        case .noInternet: Messages.noInternet
        case .timeout: Messages.timeout
        case .serverUnreachable: Messages.serverUnreachable
        case .unknown: String(localized: "The safety number could not be shown.", bundle: .module)
        }
    }
}

/// Why the discovery username could not be reserved, as Android's `ReserveDiscoveryUsernameError`.
public enum ReserveUsernameError: Error, Hashable, Sendable {
    /// Somebody else already holds it.
    case alreadyTaken

    /// The server rejected the username itself.
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
